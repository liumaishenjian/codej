package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.sdk.AgentApplicationService;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将现有 {@link HeadlessRuntimeSession} 适配为 SDK/stdio v1/Daemon 共用 Application Service。
 *
 * <p>适配器把完整 {@link AgentRunRequest} 和调用方事件 sink 交给同一 Headless Session，
 * 不建立第二条 Tool Loop。它串行化 Run、暴露权威 Run ID，并在 drain 超时后协作式取消活动 Run。</p>
 *
 * @since 0.1.0
 */
public final class HeadlessAgentApplicationService implements AgentApplicationService {
    private final HeadlessRuntimeSession session;
    private final io.github.liumaishenjian.ccjava.sdk.AgentControlApi control;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Object monitor = new Object();
    private boolean running;
    private CloseState closeState = CloseState.OPEN;

    /**
     * 创建不含生产控制面的 Application Service。
     *
     * @param session 已打开或可打开的唯一 Headless Session
     */
    public HeadlessAgentApplicationService(HeadlessRuntimeSession session) {
        this(session, null);
    }

    /**
     * 使用共享生产控制 API 装配 SDK/v1。
     *
     * @param session 唯一 Headless Session
     * @param control 与 canonical Session/Governance 绑定的可选控制面
     */
    public HeadlessAgentApplicationService(
            HeadlessRuntimeSession session,
            io.github.liumaishenjian.ccjava.sdk.AgentControlApi control) {
        this.session = Objects.requireNonNull(session, "session 不能为空");
        this.control = control;
    }

    /** 执行完整请求并把 Run 事件复制到调用方 sink。 */
    @Override
    public AgentRunResult run(AgentRunRequest request, AgentEventSink events) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(events, "events 不能为空");
        synchronized (monitor) {
            if (draining.get() || closeState != CloseState.OPEN) {
                throw new IllegalStateException("Application 不再接受新 Run");
            }
            if (running) {
                throw new IllegalStateException("已有活动 Run");
            }
            running = true;
        }
        try {
            return session.run(request, events);
        } finally {
            synchronized (monitor) {
                running = false;
                monitor.notifyAll();
            }
        }
    }

    /** 只取消 identity 匹配的当前活动 Run。 */
    @Override
    public boolean cancel(RunId runId) {
        return session.cancel(Objects.requireNonNull(runId, "runId 不能为空"));
    }

    @Override
    public void beginDrain() {
        draining.set(true);
    }

    /**
     * 等待活动 Run 收敛；超时会发出取消并再给予一个短的协作式收敛窗口。
     */
    @Override
    public boolean awaitTermination(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long deadline = saturatedDeadline(timeout);
        synchronized (monitor) {
            while ((running || session.hasPendingRunResources()) && System.nanoTime() < deadline) {
                waitBounded(deadline);
            }
            if (!running && !session.hasPendingRunResources()) {
                return true;
            }
        }
        session.cancelActive();
        long cancellationDeadline = saturatedDeadline(Duration.ofMillis(250));
        synchronized (monitor) {
            while ((running || session.hasPendingRunResources()) && System.nanoTime() < cancellationDeadline) {
                waitBounded(cancellationDeadline);
            }
            if (!running && !session.hasPendingRunResources()) {
                return true;
            }
        }
        session.forceCloseModelResources();
        synchronized (monitor) {
            return !running && !session.hasPendingRunResources();
        }
    }

    @Override
    public Optional<RunId> activeRun() {
        return session.activeRunId();
    }

    @Override
    public Optional<io.github.liumaishenjian.ccjava.sdk.AgentControlApi> control() {
        return Optional.ofNullable(control);
    }

    /** 返回同一 Headless Session 的 canonical Task Board 只读快照。 */
    @Override
    public Optional<io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot> taskBoardSnapshot() {
        return session.taskBoardSnapshot();
    }

    /**
     * 幂等且可重试地关闭；活动 Run 先 drain/cancel，再关闭 Session。
     *
     * <p>首次等待失败时保持 {@link CloseState#DRAINING}，后续调用会重新等待并尝试释放
     * Session，而不会把尚未关闭的资源误标为 CLOSED。并发 close 由同一 monitor 串行化。</p>
     */
    @Override
    public void close() {
        synchronized (monitor) {
            while (closeState == CloseState.CLOSING) {
                waitForCloseAttempt();
            }
            if (closeState == CloseState.CLOSED) {
                return;
            }
            closeState = CloseState.CLOSING;
            draining.set(true);
        }
        boolean terminated = awaitTermination(Duration.ofSeconds(5));
        if (!terminated) {
            synchronized (monitor) {
                closeState = CloseState.DRAINING;
                monitor.notifyAll();
            }
            throw new IllegalStateException("活动 Run 未在关闭期限内终止");
        }
        try {
            session.close();
        } catch (RuntimeException failure) {
            synchronized (monitor) {
                closeState = CloseState.DRAINING;
                monitor.notifyAll();
            }
            throw failure;
        }
        synchronized (monitor) {
            closeState = CloseState.CLOSED;
            monitor.notifyAll();
        }
    }

    private void waitForCloseAttempt() {
        try {
            monitor.wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发关闭时被中断", interrupted);
        }
    }

    private void waitBounded(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            long millis = Math.max(1, Math.min(100, remaining / 1_000_000));
            monitor.wait(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private enum CloseState {
        OPEN,
        CLOSING,
        DRAINING,
        CLOSED
    }

    private static long saturatedDeadline(Duration timeout) {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
