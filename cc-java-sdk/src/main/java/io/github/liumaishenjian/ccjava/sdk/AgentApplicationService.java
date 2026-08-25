package io.github.liumaishenjian.ccjava.sdk;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import java.time.Duration;
import java.util.Optional;

/**
 * CLI、SDK、stdio v1 与 Daemon 共用的 Application Service。
 *
 * <p>实现必须委托唯一 AgentRuntime；Surface 不能自行拼接 Tool Loop 或猜测终态。</p>
 *
 * @since 0.1.0
 */
public interface AgentApplicationService extends AutoCloseable {
    /**
     * 启动并等待一次 Run 收敛。
     *
     * @param request 用户消息与确定性限制
     * @param events 有序事件接收端
     * @return Runtime 的唯一终态
     */
    AgentRunResult run(AgentRunRequest request, AgentEventSink events);

    /**
     * 请求取消指定活动 Run。
     *
     * @param runId 目标 Run identity
     * @return 是否找到并接受取消
     */
    boolean cancel(RunId runId);

    /** 开始 graceful drain，之后拒绝新 Run。 */
    void beginDrain();

    /**
     * 等待活动 Run 收敛，到期后由实现取消。
     *
     * @param timeout 最大等待时间
     * @return 是否在期限内收敛
     */
    boolean awaitTermination(Duration timeout);

    /**
     * 查询当前活动 Run，不暴露 Runtime mutable state。
     *
     * @return 当前活动 Run identity；idle 时为空
     */
    Optional<RunId> activeRun();

    /**
     * 查询与同一 Application Service 绑定的生产控制面。
     *
     * @return 可选生产控制 API；基础嵌入实现可返回 empty
     */
    default Optional<AgentControlApi> control() {
        return Optional.empty();
    }

    /**
     * 查询与本 Application 绑定的 canonical Session Task Board 快照。
     *
     * <p>基础 SDK 实现可以不支持；返回值不可用于绕过 Task Tool mutation、Permission 或 Pipeline。</p>
     *
     * @return 当前不可变 Board，未装配时为空
     */
    default Optional<io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot> taskBoardSnapshot() {
        return Optional.empty();
    }

    /** 进入 drain 并释放 Runtime、Session 和 Adapter 资源。 */
    @Override
    void close();
}
