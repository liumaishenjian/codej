package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;
import io.github.liumaishenjian.ccjava.domain.subagent.DelegationId;
import java.util.Optional;

/**
 * 在执行边界重新装配独立子 Runtime scope 的 Application Port。
 *
 * @since 0.12.0
 */
@FunctionalInterface
public interface ChildRuntimeScopeFactory {
    /**
     * 根据收窄定义创建独立 Session/Context/Tool/Permission scope。
     *
     * @param definition 已冻结并完成收窄的定义
     * @param request 当前委托请求
     * @param cancellationToken 父级传播的取消身份
     * @return 由调用方关闭的独立 scope
     */
    ChildRuntimeScope create(
            AgentDefinitionSnapshot definition,
            ChildTaskRequest request,
            CancellationToken cancellationToken);

    /**
     * 在提交线程校验并捕获不可变来源，不取得 lease、不启动进程。
     *
     * <p>生产实现必须使用独立可信的父身份查找来源；默认实现保留静态/Fake 工厂的惰性行为。
     * 返回的闭包在 worker 上仅接受 Hook 附加的 prompt，不应重新读取父来源或当前模型。</p>
     * @param parentSessionId 可信父 Session；仅旧静态入口允许为空
     * @param parentRunId 可信父 Run；仅旧静态入口允许为空
     * @param definition 已冻结并收窄的定义
     * @param request 原始委托请求
     * @param token 提交时的父取消信号
     * @return 尚未占用资源的准备结果
     */
    default PreparedChildRuntime prepare(SessionId parentSessionId, RunId parentRunId,
            AgentDefinitionSnapshot definition, ChildTaskRequest request, CancellationToken token) {
        java.util.Objects.requireNonNull(definition);
        java.util.Objects.requireNonNull(request);
        java.util.Objects.requireNonNull(token);
        return (effectiveRequest, cancellation) -> create(definition, effectiveRequest, cancellation);
    }

    /**
     * 声明生产来源捕获是否要求真实父身份；不能从模型参数补齐身份。
     * @return 使用父 Run 来源注册表时为 true
     */
    default boolean requiresParentIdentity() {
        return false;
    }

    /**
     * 对任务绑定的 retained worktree 执行显式 keep。
     *
     * @param id 任务 delegation identity
     * @return 固定处置状态；无 worktree 时为空
     */
    default Optional<String> keepWorktree(DelegationId id) {
        return Optional.empty();
    }

    /**
     * 对任务绑定的 retained worktree 执行显式 clean remove；不确定时必须保留。
     *
     * @param id 任务 delegation identity
     * @return 固定处置状态；无 worktree 时为空
     */
    default Optional<String> removeWorktree(DelegationId id) {
        return Optional.empty();
    }
}
