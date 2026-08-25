package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.util.Objects;

/**
 * 对持续规划 Run 的 Tool 可见性与执行资格实施确定性能力 Gate。
 *
 * <p>策略只相信可信 {@link ToolDefinition}，不根据模型参数猜测副作用。Workspace 写入、进程执行及
 * 系统操作在规划期间永久拒绝；写例外是受控 PlanArtifact，以及名称/effect 精确匹配的四个 BUILT_IN
 * Session Task Tool。网络读取仍须经过既有 Permission/AutoReview。外部、MCP 和 Plugin Tool 默认不可用。</p>
 *
 * @since 0.1.0
 */
public final class PlanEligibilityPolicy {

    /** 判断 Tool 是否可出现在规划模型请求中。 */
    public boolean eligible(ToolDefinition definition) {
        ToolDefinition checked = Objects.requireNonNull(definition, "definition 不能为空");
        if (checked.effect() == ToolEffect.WRITE_WORKSPACE
                || checked.effect() == ToolEffect.EXECUTE_PROCESS
                || checked.effect() == ToolEffect.SYSTEM_OR_DESTRUCTIVE) return false;
        if (checked.effect() == ToolEffect.PLAN_ARTIFACT_WRITE) {
            return checked.source() == ToolSource.BUILT_IN
                    && checked.planCapabilities().contains(PlanToolCapability.PLAN_ARTIFACT_WRITE);
        }
        if (checked.effect() == ToolEffect.USER_INTERACTION) {
            return checked.source() == ToolSource.BUILT_IN
                    && checked.planCapabilities().contains(PlanToolCapability.USER_QUESTION);
        }
        if (checked.effect() == ToolEffect.READ_WORKSPACE) {
            return checked.planCapabilities().contains(PlanToolCapability.READ_ONLY_LOCAL)
                    || checked.planCapabilities().contains(PlanToolCapability.BOUNDED_READ_ONLY_SUBAGENT);
        }
        if (checked.effect() == ToolEffect.READ_SESSION_STATE
                || checked.effect() == ToolEffect.WRITE_SESSION_STATE) {
            return checked.source() == ToolSource.BUILT_IN && switch (checked.name()) {
                case "task_list", "task_get" -> checked.effect() == ToolEffect.READ_SESSION_STATE;
                case "task_create", "task_update" -> checked.effect() == ToolEffect.WRITE_SESSION_STATE;
                default -> false;
            };
        }
        return checked.effect() == ToolEffect.NETWORK_OR_REMOTE
                && checked.planCapabilities().contains(PlanToolCapability.READ_ONLY_NETWORK);
    }

    /**
     * 对模型即使猜中隐藏 Tool 名称的调用再次执行同一 Gate。
     *
     * @param definition 已由唯一 Registry 解析的定义
     * @return 允许进入 Hook/Permission/Pipeline 时为 {@code true}
     */
    public boolean executionAllowed(ToolDefinition definition) {
        return eligible(definition);
    }
}
