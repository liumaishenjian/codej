package io.github.liumaishenjian.ccjava.cli.stdio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.node.ObjectNode;

/**
 * 通过有界队列和单一 Writer 线程把事件写入 stdout。
 *
 * <p>模型线程、取消线程和命令线程可以并发发布事件，但只有 Writer 线程能够编码和写出
 * NDJSON。队列满时在有限时间内失败，不允许用无界内存掩盖 Client 停止读取的问题。</p>
 *
 * <p>该 Spike 暂不合并相邻文本 Delta；若真实流量证明需要合并，必须在保持终态顺序和
 * 可观测性的前提下通过后续 ADR 增加。</p>
 *
 * @since 0.1.0
 */
public final class QueuedStdioEventEmitter
        implements StdioProtocol.EventEmitter, AutoCloseable {

    private static final QueueItem STOP = new StopItem();
    private static final Set<String> RUN_TERMINALS = Set.of(
            "run.completed",
            "run.failed",
            "run.cancelled");

    private final StdioProtocolCodec codec;
    private final OutputStream output;
    private final ArrayBlockingQueue<QueueItem> queue;
    private final Duration enqueueTimeout;
    private final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    private final Thread writerThread;
    private final Map<String, RunEventState> runStates = new HashMap<>();
    private long nextSequence = 1;
    private volatile boolean closed;

    /**
     * 创建单 Writer 事件出口。
     *
     * @param codec JSON Codec
     * @param output Java 子进程 stdout
     * @param queueCapacity 最大待写事件数
     * @param enqueueTimeout 发布者等待队列空间的最长时间
     */
    public QueuedStdioEventEmitter(
            StdioProtocolCodec codec,
            OutputStream output,
            int queueCapacity,
            Duration enqueueTimeout) {
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.output = Objects.requireNonNull(output, "output 不能为空");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity 必须大于 0");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.enqueueTimeout = Objects.requireNonNull(
                enqueueTimeout,
                "enqueueTimeout 不能为空");
        if (enqueueTimeout.isNegative() || enqueueTimeout.isZero()) {
            throw new IllegalArgumentException("enqueueTimeout 必须大于 0");
        }
        writerThread = Thread.ofPlatform()
                .name("cc-java-stdio-writer")
                .daemon(true)
                .start(this::writeLoop);
    }

    /**
     * 原子分配事件序号并加入有界队列。
     *
     * @throws IllegalStateException Writer 已失败、Emitter 已关闭或队列持续满时
     */
    @Override
    public synchronized void emit(
            String type,
            String requestId,
            Optional<String> sessionId,
            Optional<String> runId,
            ObjectNode payload) {
        ensureWritable();
        validateRunEvent(type, runId);
        StdioProtocol.Event event = new StdioProtocol.Event(
                StdioProtocol.VERSION,
                type,
                requestId,
                sessionId,
                runId,
                nextSequence,
                payload);
        try {
            if (!queue.offer(
                    new EventItem(event),
                    enqueueTimeout.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("stdio 事件队列已满");
            }
            nextSequence++;
            recordRunEvent(type, runId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发布 stdio 事件时被中断", exception);
        }
    }

    /**
     * 排空已接收事件并停止 Writer。
     *
     * <p>不关闭底层 stdout，调用方仍负责进程级流生命周期。</p>
     *
     * @throws IllegalStateException 队列无法停止、Writer 未退出或写出失败时
     */
    @Override
    public synchronized void close() {
        if (closed) {
            checkWriterFailure();
            return;
        }
        closed = true;
        try {
            if (!queue.offer(
                    STOP,
                    enqueueTimeout.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                writerThread.interrupt();
                throw new IllegalStateException("stdio Writer 无法接收停止标记");
            }
            writerThread.join(enqueueTimeout.multipliedBy(10).toMillis());
        } catch (InterruptedException exception) {
            writerThread.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 stdio Writer 退出时被中断", exception);
        }
        if (writerThread.isAlive()) {
            writerThread.interrupt();
            throw new IllegalStateException("stdio Writer 未在期限内退出");
        }
        checkWriterFailure();
    }

    private void writeLoop() {
        try {
            while (true) {
                QueueItem item = queue.take();
                if (item == STOP) {
                    output.flush();
                    return;
                }
                StdioProtocol.Event event = ((EventItem) item).event();
                byte[] encoded = (codec.encodeEvent(event) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                output.write(encoded);
                output.flush();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!closed) {
                writerFailure.compareAndSet(null, exception);
            }
        } catch (IOException | RuntimeException exception) {
            writerFailure.compareAndSet(null, exception);
        }
    }

    private void ensureWritable() {
        if (closed) {
            throw new IllegalStateException("stdio EventEmitter 已关闭");
        }
        checkWriterFailure();
    }

    private void validateRunEvent(String type, Optional<String> runId) {
        if (type.equals("run.started")) {
            String value = runId.orElseThrow(
                    () -> new IllegalArgumentException("run.started 必须携带 runId"));
            if (runStates.containsKey(value)) {
                throw new IllegalStateException("同一 Run 不能重复发布 run.started");
            }
            return;
        }
        if (type.equals("plan.review.requested") && runId.isEmpty()) {
            // 显式 plan.resume 只打开 durable 审批，不创建模型 Run；request/session correlation 由 TUI fail closed。
            return;
        }
        if (type.equals("model.text.delta") || type.equals("plan.proposed")
                || type.equals("plan.review.requested") || type.equals("question.requested")
                || RUN_TERMINALS.contains(type)) {
            String value = runId.orElseThrow(
                    () -> new IllegalArgumentException(type + " 必须携带 runId"));
            RunEventState state = runStates.get(value);
            if (state == null) {
                throw new IllegalStateException("Run 事件发生在 run.started 之前");
            }
            if (state == RunEventState.TERMINAL) {
                throw new IllegalStateException("Run 终态后不能继续发布事件");
            }
        }
    }

    private void recordRunEvent(String type, Optional<String> runId) {
        if (type.equals("run.started")) {
            runStates.put(runId.orElseThrow(), RunEventState.ACTIVE);
        } else if (RUN_TERMINALS.contains(type)) {
            runStates.put(runId.orElseThrow(), RunEventState.TERMINAL);
        }
    }

    private void checkWriterFailure() {
        Throwable failure = writerFailure.get();
        if (failure != null) {
            throw new IllegalStateException("stdio Writer 已失败", failure);
        }
    }

    private sealed interface QueueItem permits EventItem, StopItem {
    }

    private record EventItem(StdioProtocol.Event event) implements QueueItem {
    }

    private static final class StopItem implements QueueItem {
    }

    private enum RunEventState {
        ACTIVE,
        TERMINAL
    }
}
