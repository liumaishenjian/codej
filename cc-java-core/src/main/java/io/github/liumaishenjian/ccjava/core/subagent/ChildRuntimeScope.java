package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.RunInitializer;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import java.util.Objects;

/**
 * 一次子任务独占的 Runtime/Session/资源组合。
 *
 * <p>实现必须创建新的 Session、Context、Permission state、Tool Registry、Budget 和取消所有权；
 * {@link AgentRuntime} 仍是唯一模型/Tool Loop。</p>
 * @param runtime 重用的核心 Runtime 类型
 * @param sessionId 独立子 Session
 * @param cleanup 逆序幂等资源清理
 * @param worktreeDisposition 清理后可选的 Worktree 保留/移除诊断
 * @param initializer 以 Runtime 生成的真实 Run 身份初始化绑定
 * @param startupCancellation 装配来源的启动取消信号，不提供 Run 身份
 * @param cleanupStatus 独立状态盒的清理探针，关闭后只采样一次，不持有 Runtime 追踪迟到成功
 * @since 0.12.0
 */
public record ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup,
        java.util.function.Supplier<java.util.Optional<String>> worktreeDisposition,
        RunInitializer initializer, CancellationToken startupCancellation,
        java.util.function.Supplier<ResourceCleanupStatus> cleanupStatus) implements AutoCloseable {
    /**
     * 创建不含 Worktree disposition 的兼容 scope。
     *
     * @param runtime 独立装配的 Runtime
     * @param sessionId 独立子 Session
     * @param cleanup 幂等资源清理
     */
    public ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup) {
        this(runtime, sessionId, cleanup, java.util.Optional::empty);
    }
    /**
     * 保留四参数静态装配入口，不附加启动动作。
     * @param runtime 独立 Runtime
     * @param sessionId 子 Session
     * @param cleanup 幂等清理
     * @param worktreeDisposition Worktree 诊断
     */
    public ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup,
            java.util.function.Supplier<java.util.Optional<String>> worktreeDisposition) {
        this(runtime, sessionId, cleanup, worktreeDisposition, RunInitializer.noop(), CancellationToken.none());
    }

    /**
     * 创建只需要真实 Run 初始化、不附加来源取消的兼容 scope。
     * @param runtime 独立 Runtime
     * @param sessionId 子 Session
     * @param cleanup 幂等清理
     * @param worktreeDisposition Worktree 诊断
     * @param initializer 单次真实身份初始化
     */
    public ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup,
            java.util.function.Supplier<java.util.Optional<String>> worktreeDisposition, RunInitializer initializer) {
        this(runtime, sessionId, cleanup, worktreeDisposition, initializer, CancellationToken.none());
    }

    /**
     * 保留六参数装配入口；旧实现没有清理证据，不从 close 返回推断 RELEASED。
     * @param runtime 独立 Runtime
     * @param sessionId 子 Session
     * @param cleanup 幂等清理
     * @param worktreeDisposition Worktree 诊断
     * @param initializer 单次真实身份初始化
     * @param startupCancellation 来源启动取消信号
     */
    public ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup,
            java.util.function.Supplier<java.util.Optional<String>> worktreeDisposition,
            RunInitializer initializer, CancellationToken startupCancellation) {
        this(runtime, sessionId, cleanup, worktreeDisposition, initializer, startupCancellation,
                () -> ResourceCleanupStatus.UNKNOWN);
    }

    /** 校验 scope 所有权资源均已装配。 */
    public ChildRuntimeScope {
        Objects.requireNonNull(runtime); Objects.requireNonNull(sessionId); Objects.requireNonNull(cleanup);
        Objects.requireNonNull(worktreeDisposition);
        Objects.requireNonNull(initializer); Objects.requireNonNull(startupCancellation);
        Objects.requireNonNull(cleanupStatus);
    }

    /**
     * 请求清理；失败只抛固定无 cause 分类，不泄露底层路径或凭据。
     * 正常返回仍须查询显式探针，不能作为已释放证据。
     * @throws IllegalStateException 清理没有正常返回时
     */
    @Override public void close() {
        try { cleanup.close(); }
        catch (Exception failure) { throw new IllegalStateException("child_resource_cleanup_unconfirmed"); }
    }
}
