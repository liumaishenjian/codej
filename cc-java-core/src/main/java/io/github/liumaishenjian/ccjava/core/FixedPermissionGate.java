package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.util.Objects;

/**
 * 实现 S04 不可配置的最小权限决策表。
 *
 * <p>该类型只根据可信的 Tool Effect 和当前模式返回 Allow、Ask 或 Deny，不读取
 * Tool 参数，也不执行审批。参数级安全校验仍由 Tool Adapter 负责，交互审批由
 * {@link ApprovalHandler} 负责；完整规则系统属于 S05。</p>
 *
 * @since 0.1.0
 */
public final class FixedPermissionGate implements PermissionGate {

    private final PermissionMode mode;

    /**
     * 创建固定模式的权限决策器。
     *
     * @param mode 当前固定权限模式
     */
    public FixedPermissionGate(PermissionMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode 不能为空");
    }

    /**
     * 根据 S04 固定决策表评估 Tool 的最高副作用。
     *
     * @param invocation 已通过参数校验的调用上下文
     * @param definition Tool Definition
     * @return Workspace Read 与名称、来源、Effect 精确匹配的内置 Task Tool 为 Allow；DEFAULT 的 Workspace Write/Process 为 Ask；其余为 Deny
     */
    @Override
    public PermissionOutcome evaluate(
            ToolInvocation invocation,
            ToolDefinition definition) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        ToolDefinition checked = Objects.requireNonNull(definition, "definition 不能为空");
        ToolEffect effect = checked.effect();
        PermissionSelector selector = PermissionSelector.toolWide(
                checked.name(), checked.source());
        boolean trustedTaskState = checked.source() == io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN
                && switch (checked.name()) {
                    case "task_list", "task_get" -> effect == ToolEffect.READ_SESSION_STATE;
                    case "task_create", "task_update" -> effect == ToolEffect.WRITE_SESSION_STATE;
                    default -> false;
                };
        if (effect == ToolEffect.READ_WORKSPACE
                || trustedTaskState
                || (mode == PermissionMode.DEFAULT
                    && (effect == ToolEffect.PLAN_ARTIFACT_WRITE || effect == ToolEffect.USER_INTERACTION))) {
            return PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.EFFECT_DEFAULT,
                    selector);
        }
        if (mode == PermissionMode.DEFAULT
                && (effect == ToolEffect.WRITE_WORKSPACE
                || effect == ToolEffect.EXECUTE_PROCESS)) {
            return PermissionOutcome.of(
                    PermissionDecision.ASK,
                    PermissionReason.EFFECT_DEFAULT,
                    selector);
        }
        return PermissionOutcome.of(
                PermissionDecision.DENY,
                mode == PermissionMode.PLAN
                        ? PermissionReason.PLAN_RESTRICTION
                        : PermissionReason.HARD_DENIAL,
                selector);
    }
}
