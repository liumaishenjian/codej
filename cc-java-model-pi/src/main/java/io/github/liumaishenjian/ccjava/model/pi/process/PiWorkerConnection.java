package io.github.liumaishenjian.ccjava.model.pi.process;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameCodec;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameSequence;
import io.github.liumaishenjian.ccjava.model.pi.protocol.JsonLineDecoder;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolException;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一操作一进程的有界私有双工连接，供认证和单次模型适配器共用。
 *
 * <p>发送串行化，接收由单一业务消费者调用；两方向独立序号。接收队列最多八帧，
 * 发送至多一个排队帧和一个正在写入帧；不消费时施加背压而非无界收集。
 * 终态只封存序号，仍继续读取至真正 EOF。业务只能在消费终态并成功 awaitExit 后结算，
 * 退出码仍须由业务判断，operation.failed 不会被翻译为成功。</p>
 *
 * <p>取消回调只置位；专属后台生命周期线程负责启动及有界清理，只销毁本连接启动的进程，
 * 不扫描或杀死其他 Node。不是 OS sandbox，不提供跨进程撤销或任意孙进程清理保证。
 * close 必须被检查；超出清理窗口显式 CLEANUP_FAILED，不冒充所有资源已经结束。
 * 已解码的 JSON/JVM 字符串不保证物理擦除，连接自有字节缓冲在释放时擦除。</p>
 */
public final class PiWorkerConnection implements AutoCloseable {
    private static final long OUTPUT_LIMIT = 16L * 1024 * 1024;
    private static final long STDERR_LIMIT = 1024L * 1024;
    private static final long TICK_MILLIS = 10;
    private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long AUTHENTICATION_LIMIT = 128L * 1024;
    private final String operationId;
    private final CancellationToken token;
    private final boolean authentication;
    private final AtomicLong authenticationBytes = new AtomicLong();
    private final long begun = System.nanoTime();
    private final long budgetNanos;
    private final ArrayBlockingQueue<ProtocolFrame> incoming = new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<WriteRequest> outgoing = new ArrayBlockingQueue<>(1);
    private final ReentrantLock sendLock = new ReentrantLock();
    private final AtomicReference<PiWorkerException.Code> failure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private volatile boolean cancelled;
    private volatile boolean closeRequested;
    private volatile boolean stopping;
    private volatile boolean eof;
    private volatile boolean terminalSeen;
    private volatile boolean terminalDelivered;
    private volatile boolean cleanupFailed;
    private volatile Process process;
    private Thread reader;
    private Thread writer;
    private Thread stderr;
    private CancellationToken.Registration registration;
    private long nextSend;
    private long sentBytes;

    private PiWorkerConnection(String operationId, CancellationToken token, Duration timeout, boolean authentication) {
        this.authentication = authentication;
        try {
            new FrameSequence(operationId).close();
            if (token == null || timeout == null || timeout.isNegative() || timeout.isZero()) throw error(PiWorkerException.Code.CONFIGURATION_INVALID);
            Duration effective = token.remainingTime().filter(value -> value.compareTo(timeout) < 0).orElse(timeout);
            if (effective.isNegative() || effective.isZero()) throw error(PiWorkerException.Code.DEADLINE_EXCEEDED);
            long nanos;
            try { nanos = effective.toNanos(); }
            catch (ArithmeticException tooLarge) { nanos = Long.MAX_VALUE; }
            this.budgetNanos = nanos;
            this.operationId = operationId;
            this.token = token;
        } catch (ProtocolException invalid) {
            throw error(PiWorkerException.Code.PROTOCOL_INVALID);
        } catch (PiWorkerException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw error(PiWorkerException.Code.CONFIGURATION_INVALID);
        }
    }

    /**
     * 在显式 timeout 与 token 剩余预算的交集中启动；启动等待也计入同一个单调时钟预算。
     * @param configuration 可信安装配置
     * @param operationId 此连接唯一操作身份
     * @param token Runtime 取消端口；回调不会执行阻塞 IO 或等待进程
     * @param timeout 全操作总时限，不会在 send/receive 时重置
     * @return 调用方拥有且必须 close 的连接
     * @throws PiWorkerException 配置、启动、取消、超时或清理失败，无底层 cause
     */
    public static PiWorkerConnection start(PiWorkerConfiguration configuration, String operationId,
            CancellationToken token, Duration timeout) {
        return start(configuration, operationId, token, timeout, ProcessBuilder::start);
    }

    /**
     * 为登录操作建立更窄的物理连接：单帧32KiB，stdin/stdout/stderr累计128KiB。
     * <p>总量按实际接收字节与发送前保守预留的整帧字节共同计数，空白、未完成行及stderr亦计入。
     * SDK模型操作内的凭证RPC仍须另行限制，不能把整个模型上下文放进这个认证连接。</p>
     * @param configuration 可信安装配置
     * @param operationId 此连接唯一操作身份
     * @param token Runtime取消端口
     * @param timeout 登录总时限
     * @return 调用方必须关闭的认证连接
     * @throws PiWorkerException 配置、启动、预算或清理失败
     */
    public static PiWorkerConnection startAuthentication(PiWorkerConfiguration configuration, String operationId,
            CancellationToken token, Duration timeout) {
        return startAuthentication(configuration, operationId, token, timeout, ProcessBuilder::start);
    }

    // 离线测试只替换启动原语，仍经过同一环境、目录、管道、预算和清理路径。
    static PiWorkerConnection start(PiWorkerConfiguration configuration, String operationId,
            CancellationToken token, Duration timeout, Starter starter) {
        return start(configuration, operationId, token, timeout, starter, false);
    }
    static PiWorkerConnection startAuthentication(PiWorkerConfiguration configuration, String operationId,
            CancellationToken token, Duration timeout, Starter starter) {
        return start(configuration, operationId, token, timeout, starter, true);
    }
    private static PiWorkerConnection start(PiWorkerConfiguration configuration, String operationId,
            CancellationToken token, Duration timeout, Starter starter, boolean authentication) {
        if (configuration == null) throw error(PiWorkerException.Code.CONFIGURATION_INVALID);
        PiWorkerConnection connection = new PiWorkerConnection(operationId, token, timeout, authentication);
        try {
            connection.registration = token.onCancellation(() -> connection.cancelled = true);
        } catch (RuntimeException invalid) {
            throw error(PiWorkerException.Code.CONFIGURATION_INVALID);
        }
        Thread.ofVirtual().name("pi-worker-lifecycle").start(() -> connection.run(configuration, starter));
        try {
            while (connection.started.getCount() != 0) {
                connection.check();
                pause();
            }
            connection.check();
            return connection;
        } catch (PiWorkerException failed) {
            connection.close();
            throw failed;
        }
    }

    /**
     * 编码并等待整帧写入及 flush；不把秘密加入 argv。发送者之间串行且序号从零递增。
     * @param type 帧类型；业务白名单由调用者负责
     * @param payload 对象数据；调用期间不得并发修改
     * @throws PiWorkerException 任何失败永久封闭连接；阻塞受同一取消和 deadline 控制
     */
    public void send(String type, ObjectNode payload) {
        boolean locked = false;
        byte[] bytes = null;
        WriteRequest request = null;
        try {
            while (!locked) {
                checkSending();
                locked = sendLock.tryLock(TICK_MILLIS, TimeUnit.MILLISECONDS);
            }
            checkSending();
            if (nextSend >= maximumFrames()) throw error(PiWorkerException.Code.LIMIT_EXCEEDED);
            bytes = FrameCodec.encode(new ProtocolFrame(operationId, nextSend, type, payload), maximumLineBytes());
            if (bytes.length > FrameCodec.MAXIMUM_TOTAL_BYTES - sentBytes) throw error(PiWorkerException.Code.LIMIT_EXCEEDED);
            chargeAuthentication(bytes.length);
            request = new WriteRequest(bytes);
            while (!outgoing.offer(request, TICK_MILLIS, TimeUnit.MILLISECONDS)) checkSending();
            bytes = null; // 所有权转给 writer 或 cleanup，不在发送线程竞态擦除正在写的字节。
            sentBytes += request.bytes.length;
            nextSend++;
            while (!request.done) {
                check();
                pause();
            }
            check();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(PiWorkerException.Code.CANCELLED);
            throw error(PiWorkerException.Code.CANCELLED);
        } catch (ProtocolException invalid) {
            PiWorkerException.Code code = protocolCode(invalid);
            fail(code);
            throw error(code);
        } catch (PiWorkerException failed) {
            fail(failed.code());
            throw failed;
        } finally {
            // cleanup可能先于入队完成。只有成功从队列移除才能重新取得字节所有权，
            // 否则writer已领取，仍必须由其finally擦除，不能竞态修改正在写入的帧。
            if (request != null && outgoing.remove(request)) Arrays.fill(request.bytes, (byte) 0);
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (locked) sendLock.unlock();
        }
    }

    /**
     * 等待下一个已校验帧；增量数据不是业务成功证据。必须持续消费直到唯一终态。
     * @return 操作身份、序号、封套及预算均合法的帧
     * @throws PiWorkerException 无终态 EOF、终态后数据、取消、超时或 IO 失败
     */
    public ProtocolFrame receive() {
        try {
            while (true) {
                check();
                ProtocolFrame frame = incoming.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    check();
                    if (isTerminal(frame)) terminalDelivered = true;
                    return frame;
                }
                if (eof) throw error(PiWorkerException.Code.CLOSED);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(PiWorkerException.Code.CANCELLED);
            throw error(PiWorkerException.Code.CANCELLED);
        }
    }

    /**
     * 消费终态后确认真正 EOF、直接子进程退出及 IO 清理；不能用收到终态代替进程退出。
     * @return 真实退出码，包括非零码；业务必须自行校验终态 payload 与退出码的一致性
     * @throws PiWorkerException 尚未消费终态、坏 EOF、deadline、取消或未确认清理
     */
    public int awaitExit() {
        check();
        if (!terminalDelivered) throw error(PiWorkerException.Code.PROTOCOL_INVALID);
        while (cleaned.getCount() != 0) {
            check();
            pause();
        }
        check();
        if (cleanupFailed) throw error(PiWorkerException.Code.CLEANUP_FAILED);
        if (!eof || !terminalSeen || process == null || process.isAlive()) throw error(PiWorkerException.Code.PROTOCOL_INVALID);
        return process.exitValue();
    }

    /**
     * 幂等请求关闭并在至多三秒内确认清理；取消/操作 deadline 不取消这段独立安全清理预算。
     * 中断状态会被恢复，但仍尝试确认清理；失败每次 close 都继续报告 CLEANUP_FAILED。
     * @throws PiWorkerException 未确认进程或 reader/writer/stderr/流关闭线程终止
     */
    @Override
    public void close() {
        closeRequested = true;
        boolean interrupted = Thread.interrupted();
        long start = System.nanoTime();
        try {
            while (cleaned.getCount() != 0 && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) {
                try { cleaned.await(TICK_MILLIS, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (cleaned.getCount() != 0 || cleanupFailed) throw error(PiWorkerException.Code.CLEANUP_FAILED);
            incoming.clear();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void run(PiWorkerConfiguration configuration, Starter starter) {
        try {
            check();
            ProcessBuilder builder = configuration.builder();
            try { process = starter.start(builder); }
            catch (Exception unavailable) { throw error(PiWorkerException.Code.START_FAILED); }
            check();
            reader = Thread.ofVirtual().name("pi-worker-stdout").start(this::readOutput);
            writer = Thread.ofVirtual().name("pi-worker-stdin").start(this::writeInput);
            stderr = Thread.ofVirtual().name("pi-worker-stderr").start(this::discardError);
            started.countDown();
            while (true) {
                check();
                if (eof && !process.isAlive() && !reader.isAlive() && !stderr.isAlive()) break;
                pause();
            }
        } catch (PiWorkerException failed) {
            if (!closeRequested) fail(failed.code());
        } catch (Throwable unexpected) {
            fail(PiWorkerException.Code.IO_FAILED);
        } finally {
            started.countDown();
            cleanup();
        }
    }

    private void readOutput() {
        byte[] buffer = new byte[8192];
        try (FrameSequence sequence = new FrameSequence(operationId);
                JsonLineDecoder decoder = new JsonLineDecoder(frame -> {
                    sequence.accept(frame);
                    if (isTerminal(frame)) {
                        sequence.seal();
                        terminalSeen = true;
                    }
                    try {
                        while (!incoming.offer(frame, TICK_MILLIS, TimeUnit.MILLISECONDS)) check();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw error(PiWorkerException.Code.CANCELLED);
                    }
                }, maximumLineBytes(), authentication ? AUTHENTICATION_LIMIT : OUTPUT_LIMIT, maximumFrames())) {
            InputStream input = process.getInputStream();
            int count;
            while ((count = input.read(buffer)) != -1) {
                chargeAuthentication(count);
                check();
                byte[] chunk = Arrays.copyOf(buffer, count);
                try { decoder.push(chunk); }
                finally { Arrays.fill(chunk, (byte) 0); }
            }
            decoder.end();
            if (!terminalSeen) throw error(PiWorkerException.Code.PROTOCOL_INVALID);
            eof = true;
        } catch (ProtocolException invalid) {
            if (!stopping) fail(protocolCode(invalid));
        } catch (PiWorkerException failed) {
            if (!stopping) fail(failed.code());
        } catch (Throwable io) {
            if (!stopping) fail(PiWorkerException.Code.IO_FAILED);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private int maximumLineBytes() { return authentication ? 32 * 1024 : FrameCodec.MAXIMUM_LINE_BYTES; }
    private int maximumFrames() { return authentication ? 512 : FrameCodec.MAXIMUM_FRAMES; }
    private void chargeAuthentication(int count) {
        if (authentication && authenticationBytes.addAndGet(count) > AUTHENTICATION_LIMIT) {
            throw error(PiWorkerException.Code.LIMIT_EXCEEDED);
        }
    }

    private void writeInput() {
        try {
            while (!stopping) {
                WriteRequest request = outgoing.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                if (request == null) continue;
                try {
                    check();
                    process.getOutputStream().write(request.bytes);
                    process.getOutputStream().flush();
                    request.done = true;
                } finally {
                    Arrays.fill(request.bytes, (byte) 0);
                }
            }
        } catch (PiWorkerException failed) {
            if (!stopping) fail(failed.code());
        } catch (Throwable io) {
            if (!stopping) fail(PiWorkerException.Code.IO_FAILED);
        }
    }

    private void discardError() {
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int count;
            while ((count = process.getErrorStream().read(buffer)) != -1) {
                total += count;
                Arrays.fill(buffer, (byte) 0);
                chargeAuthentication(count);
                if (total > STDERR_LIMIT) throw error(PiWorkerException.Code.LIMIT_EXCEEDED);
                check();
            }
        } catch (PiWorkerException failed) {
            if (!stopping) fail(failed.code());
        } catch (Throwable io) {
            if (!stopping) fail(PiWorkerException.Code.IO_FAILED);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    /** 只由生命周期线程调用；先终止进程再关 pipe，避免 close 等待持有流锁的阻塞 writer。 */
    private void cleanup() {
        stopping = true;
        long start = System.nanoTime();
        try {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                while (process.isAlive() && System.nanoTime() - start < CLEANUP_NANOS) pause();
                Thread closeInput = closeAsync(process.getInputStream());
                Thread closeOutput = closeAsync(process.getOutputStream());
                Thread closeError = closeAsync(process.getErrorStream());
                for (Thread thread : new Thread[] {reader, writer, stderr}) if (thread != null) thread.interrupt();
                for (Thread thread : new Thread[] {reader, writer, stderr, closeInput, closeOutput, closeError}) {
                    if (thread == null) continue;
                    while (thread.isAlive() && System.nanoTime() - start < CLEANUP_NANOS) pause();
                    if (thread.isAlive()) cleanupFailed = true;
                }
                if (process.isAlive()) cleanupFailed = true;
            }
            if (registration != null) registration.close();
        } catch (Throwable failed) {
            cleanupFailed = true;
        } finally {
            // writer 结束后才释放剩余排队字节；若未结束，其私有帧由 writer finally 负责。
            WriteRequest pending;
            while ((pending = outgoing.poll()) != null) Arrays.fill(pending.bytes, (byte) 0);
            if (failure.get() != null || closeRequested) incoming.clear();
            cleaned.countDown();
        }
    }

    private Thread closeAsync(AutoCloseable stream) {
        return Thread.ofVirtual().name("pi-worker-pipe-close").start(() -> {
            try { stream.close(); }
            catch (Throwable failed) { cleanupFailed = true; }
        });
    }

    private void checkSending() {
        check();
        if (terminalSeen || stopping) throw error(PiWorkerException.Code.CLOSED);
    }

    private void check() {
        try {
            if (cancelled || token.isCancellationRequested()) fail(PiWorkerException.Code.CANCELLED);
        } catch (RuntimeException invalid) {
            fail(PiWorkerException.Code.IO_FAILED);
        }
        if (System.nanoTime() - begun >= budgetNanos) fail(PiWorkerException.Code.DEADLINE_EXCEEDED);
        PiWorkerException.Code code = failure.get();
        if (code != null) throw error(code);
        if (closeRequested) throw error(PiWorkerException.Code.CLOSED);
    }

    private void fail(PiWorkerException.Code code) { failure.compareAndSet(null, code); }

    private static void pause() {
        try { Thread.sleep(TICK_MILLIS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw error(PiWorkerException.Code.CANCELLED);
        }
    }

    private static boolean isTerminal(ProtocolFrame frame) {
        return "operation.completed".equals(frame.type()) || "operation.failed".equals(frame.type());
    }

    private static PiWorkerException.Code protocolCode(ProtocolException failure) {
        return failure.code() == ProtocolException.Code.PROTOCOL_LIMIT
                ? PiWorkerException.Code.LIMIT_EXCEEDED : PiWorkerException.Code.PROTOCOL_INVALID;
    }

    private static PiWorkerException error(PiWorkerException.Code code) { return new PiWorkerException(code); }

    @FunctionalInterface
    interface Starter {
        Process start(ProcessBuilder builder) throws java.io.IOException;
    }

    private static final class WriteRequest {
        private final byte[] bytes;
        private volatile boolean done;
        private WriteRequest(byte[] bytes) { this.bytes = bytes; }
    }

    @Override
    public String toString() { return "PI_WORKER_CONNECTION"; }
}
