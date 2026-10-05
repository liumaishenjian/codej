package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameCodec;
import io.github.liumaishenjian.ccjava.model.pi.protocol.JsonLineDecoder;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-100 独立认证管道，不接受聊天帧或 credential RPC。
 *
 * <p>登录、输入和输出使用独立 daemon 线程；成功必须观察停止握手后的真实 EOF、
 * 登录返回及资源关闭。中断不是输入停止证据。故障返回后宿主必须退出独立进程，
 * 不得复用可能仍阻塞于 OS IO 的线程。所有等待受总预算约束，未收敛绝不发送 stored。
 * 数组由拥有者擦除；不承诺擦除 JSON/JVM String、OS 或对端副本。</p>
 */
public final class PiAuthBridge {
    /** 封闭错误分类，绝不保存原诊断。 */
    public enum Code {
        /** 输入协议或提示关联错误。 */ PROTOCOL,
        /** 原始字节或帧数量超限。 */ LIMIT,
        /** 输入提前结束或显式取消。 */ CANCELLED,
        /** 登录未取得可信回执。 */ LOGIN,
        /** 输入停止或资源清理未确认。 */ CLEANUP,
        /** 输出失败或阻塞。 */ OUTPUT,
        /** 总预算耗尽。 */ TIMEOUT
    }

    /** 生产委派共享服务；Fake 仅替换此窄边缘，不构造 Session。 */
    @FunctionalInterface
    public interface Login {
        /**
         * 返回已经确认 Worker 清理的原始存储回执。
         * @param interaction 非阻塞交互端口
         * @param cancellation 整个桥操作的剩余预算与取消
         * @return 原始精确回执，不能通过重读 snapshot 推断
         * @throws Exception 登录失败，桥只发布封闭分类
         */
        PiLoginOperation.Receipt run(PiLoginOperation.Interaction interaction,
                                    CancellationToken cancellation) throws Exception;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int LINE = 32 * 1024;
    private static final int TOTAL = 128 * 1024;
    private final Object guard = new Object();
    private final String operationId;
    private final PiCredentialIdentity identity;
    private final InputStream input;
    private final OutputStream output;
    private final Login login;
    private final AutoCloseable resources;
    private final Duration timeout;
    private final Duration stopTimeout;
    private final ArrayBlockingQueue<Emission> outgoing = new ArrayBlockingQueue<>(32);
    private final AtomicReference<Code> failure = new AtomicReference<>();
    private final CompletableFuture<Void> eof = new CompletableFuture<>();
    private final Set<Integer> late = new HashSet<>();
    private CancellationSource cancellation;
    private long deadline;
    private long incomingSequence;
    private long outgoingSequence;
    private int bytes;
    private int frames;
    private int lastPrompt;
    private Slot active;
    private boolean stopping;
    private boolean ran;
    private volatile boolean writerDone;
    private volatile boolean outputBroken;
    private volatile Emission writing;
    private volatile long writingStarted;
    private final java.util.concurrent.atomic.AtomicBoolean inputCloseStarted =
            new java.util.concurrent.atomic.AtomicBoolean();
    private Thread reader;
    private Thread writer;

    /**
     * 绑定可信外层身份；本对象取得流及资源关闭责任，一次性运行。
     * @param operationId 启动器生成的外层标识
     * @param identity 完整 Pi 身份
     * @param input 独立私有 stdin
     * @param output 独立私有 stdout
     * @param login 真实协调器或离线测试接缝
     * @param resources 登录结束后必须关闭的本进程资源
     */
    public PiAuthBridge(String operationId, PiCredentialIdentity identity, InputStream input,
                        OutputStream output, Login login, AutoCloseable resources) {
        this(operationId, identity, input, output, login, resources, Duration.ofSeconds(300), Duration.ofSeconds(2));
    }

    // 测试只能缩短预算；生产保留 300 秒总期限与两秒停止窗口。
    PiAuthBridge(String operationId, PiCredentialIdentity identity, InputStream input,
                 OutputStream output, Login login, AutoCloseable resources, Duration timeout, Duration stopTimeout) {
        if (operationId == null || !operationId.matches("[a-zA-Z0-9_-]{1,96}") || identity == null
                || input == null || output == null || login == null || resources == null || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(300)) > 0
                || stopTimeout == null || stopTimeout.isNegative() || stopTimeout.isZero()
                || stopTimeout.compareTo(Duration.ofSeconds(2)) > 0) throw new IllegalArgumentException("PI_BRIDGE_CONFIGURATION");
        this.operationId = operationId; this.identity = identity; this.input = input; this.output = output;
        this.login = login; this.resources = resources; this.timeout = timeout; this.stopTimeout = stopTimeout;
    }

    /**
     * 执行到真正管道关闭，返回固定进程退出码。非零可能发生在凭证发布后，不可自动重试或回滚。
     * @return 0 仅表示回执、EOF、资源关闭和输出关闭均确认；1 表示失败
     */
    public int run() {
        synchronized (guard) {
            if (ran) return 1;
            ran = true;
            deadline = System.nanoTime() + timeout.toNanos();
            cancellation = new CancellationSource(timeout);
        }
        writer = daemon("pi-auth-bridge-output", this::writeLoop);
        reader = daemon("pi-auth-bridge-input", this::readLoop);
        CompletableFuture<PiLoginOperation.Receipt> result = new CompletableFuture<>();
        Thread coordinator = daemon("pi-auth-bridge-login", () -> {
            try { check(); result.complete(login.run(new Interaction(), cancellation.token())); }
            catch (Throwable ignored) { fail(Code.LOGIN); result.completeExceptionally(new BridgeFailure()); }
        });
        boolean resourcesCloseAttempted = false;
        boolean stored = false;
        try {
            PiLoginOperation.Receipt receipt = await(result, remaining(), true);
            check();
            if (receipt == null || !identity.equals(receipt.identity()) || receipt.authEpoch() <= 0) {
                fail(Code.LOGIN); throw new BridgeFailure();
            }
            join(coordinator, remaining());
            synchronized (guard) {
                check();
                if (active != null) { fail(Code.CLEANUP); throw new BridgeFailure(); }
                stopping = true;
            }
            flush(emit("auth.input_stop", object()), stopBudget());
            await(eof, stopBudget(), true);
            join(reader, stopBudget());
            check();
            resourcesCloseAttempted = true;
            closeResources();
            check();
            ObjectNode payload = object().put("backend", identity.backend()).put("providerId", identity.providerId())
                    .put("profileId", identity.profileId()).put("authMethod", identity.authMethod().name())
                    .put("authEpoch", Long.toString(receipt.authEpoch()));
            flush(emit("auth.stored", payload), stopBudget());
            stored = true;
        } catch (Throwable ignored) {
            if (failure.get() == null) fail(remaining() == 0 ? Code.TIMEOUT : Code.CLEANUP);
        } finally {
            if (!stored) {
                fail(failure.get() == null ? Code.CLEANUP : failure.get());
                synchronized (guard) {
                    stopping = true;
                    if (active != null) active.close();
                }
                if (!resourcesCloseAttempted) {
                    try { closeResources(); } catch (Throwable ignored) { fail(Code.CLEANUP); }
                }
                // 失败帧也必须服从预算；预算耗尽时以非零退出报告，不越界补发。
                if (!outputBroken) {
                    try { flush(emit("auth.failed", object().put("code", failure.get().name())), stopBudget()); }
                    catch (Throwable ignored) { fail(Code.OUTPUT); }
                }
            }
            writerDone = true;
            writer.interrupt(); // 只唤醒队列轮询，不宣称能终止 OS 写入。
            try { join(writer, stopBudget()); } catch (Throwable ignored) { fail(Code.OUTPUT); }
            Emission pending;
            while ((pending = outgoing.poll()) != null) pending.erase();
            // 未收敛写入只能失败；擦除本进程数组不声称 OS 已停止使用其内部副本。
            Emission blocked = writing;
            if (blocked != null) blocked.erase();
            // close 自身也可能阻塞，因此必须在独立有界任务中确认。
            try { boundedClose(output); } catch (Throwable ignored) { fail(Code.OUTPUT); }
            // 异常 EOF 不是流关闭证据；只启动一次关闭，不能重试阻塞 close 并据中断报成功。
            if (inputCloseStarted.compareAndSet(false, true)) {
                try { boundedClose(input); } catch (Throwable ignored) { fail(Code.CLEANUP); }
            }
            // close 返回仍不代表正在读取的线程已经退出；失败路径同样尝试确认 owned 缓冲收敛。
            if (reader.isAlive()) {
                try { join(reader, stopBudget()); } catch (Throwable ignored) { fail(Code.CLEANUP); }
            }
            if (coordinator.isAlive()) {
                try { join(coordinator, stopBudget()); } catch (Throwable ignored) { fail(Code.CLEANUP); }
            }
        }
        return stored && failure.get() == null ? 0 : 1;
    }

    private void readLoop() {
        byte[] chunk = new byte[2048];
        try (JsonLineDecoder decoder = new JsonLineDecoder(this::receive, LINE, TOTAL, 512)) {
            for (;;) {
                int count = input.read(chunk);
                if (count < 0) {
                    decoder.end();
                    synchronized (guard) { if (!stopping) fail(Code.CANCELLED); }
                    if (!inputCloseStarted.compareAndSet(false, true)) throw new BridgeFailure();
                    try { input.close(); }
                    catch (Throwable ignored) { fail(Code.CLEANUP); throw new BridgeFailure(); }
                    eof.complete(null);
                    return;
                }
                if (count == 0) continue;
                byte[] exact = Arrays.copyOf(chunk, count);
                try { charge(count, false); decoder.push(exact); }
                finally { Arrays.fill(exact, (byte) 0); Arrays.fill(chunk, (byte) 0); }
            }
        } catch (Throwable ignored) {
            fail(ignored instanceof io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolException protocol
                    && protocol.code() == io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolException.Code.PROTOCOL_LIMIT
                    ? Code.LIMIT : Code.PROTOCOL);
            eof.completeExceptionally(new BridgeFailure());
        } finally { Arrays.fill(chunk, (byte) 0); }
    }

    private void receive(ProtocolFrame frame) {
        synchronized (guard) {
            charge(0, true);
            if (!operationId.equals(frame.operationId()) || frame.sequence() != incomingSequence++) invalid();
            ObjectNode payload = frame.payload();
            if ("auth.cancel".equals(frame.type())) {
                if (!payload.isEmpty()) invalid();
                fail(Code.CANCELLED); return;
            }
            if (!"auth.response".equals(frame.type()) || payload.size() != 2
                    || !payload.has("promptId") || !payload.has("value")
                    || !payload.get("promptId").isIntegralNumber() || !payload.get("value").isString()) invalid();
            long number = payload.get("promptId").asLong();
            if (number < 1 || number > 16) invalid();
            int id = (int) number;
            char[] chars = payload.get("value").asString().toCharArray();
            try (SecretMaterial material = new SecretMaterial(chars)) {
                if (late.remove(id)) return; // 只消费原 cancelled slot 的一次迟到材料。
                if (stopping || failure.get() != null || active == null || active.id != id || active.delivered) invalid();
                active.delivered = true;
                // result 的所有权转交协调器；临时材料仍在本方法擦除。
                SecretMaterial transferred = new SecretMaterial(chars);
                if (!active.result.complete(transferred)) transferred.close();
            } finally { Arrays.fill(chars, '\0'); }
        }
    }

    private final class Interaction implements PiLoginOperation.Interaction {
        @Override public PiLoginOperation.PromptHandle request(PiLoginOperation.Prompt prompt) {
            synchronized (guard) {
                check();
                if (stopping || active != null || prompt == null || prompt.promptId() != lastPrompt + 1
                        || prompt.promptId() > 16 || !("secret".equals(prompt.kind()) || "manual_code".equals(prompt.kind()))) invalid();
                active = new Slot(++lastPrompt);
                emit("auth.prompt", object().put("promptId", active.id).put("kind", prompt.kind()));
                return active;
            }
        }
        @Override public void authorizationUrl(URI url) {
            synchronized (guard) {
                check();
                if (stopping || url == null) invalid();
                // 仅 PiLoginOperation 校验过的 URI 可到达生产端口，不接受 stdin 提供 URL。
                emit("auth.url", object().put("url", url.toASCIIString()));
            }
        }
    }

    private final class Slot implements PiLoginOperation.PromptHandle {
        private final int id;
        private final CompletableFuture<SecretMaterial> result = new CompletableFuture<>();
        private boolean closed;
        private boolean delivered;
        Slot(int id) { this.id = id; }
        @Override public CompletionStage<SecretMaterial> result() { return result; }
        @Override public void close() {
            synchronized (guard) {
                if (closed) return;
                closed = true;
                if (active == this) active = null;
                if (!delivered) {
                    late.add(id);
                    if (!stopping && failure.get() == null) emit("auth.prompt_cancelled", object().put("promptId", id));
                }
            }
        }
    }

    private Emission emit(String type, ObjectNode payload) {
        synchronized (guard) {
            byte[] encoded;
            try { encoded = FrameCodec.encode(new ProtocolFrame(operationId, outgoingSequence, type, payload), LINE); }
            catch (io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolException rejected) {
                fail(rejected.code() == io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolException.Code.PROTOCOL_LIMIT
                        ? Code.LIMIT : Code.PROTOCOL);
                throw new BridgeFailure();
            }
            Emission emission = new Emission(encoded, "auth.failed".equals(type));
            try {
                charge(encoded.length, true);
                if (outputBroken || writerDone || !outgoing.offer(emission)) { fail(Code.OUTPUT); throw new BridgeFailure(); }
                outgoingSequence++;
                return emission;
            } catch (Throwable failed) { emission.erase(); throw failed; }
        }
    }

    private void writeLoop() {
        try {
            while (!writerDone || !outgoing.isEmpty()) {
                Emission emission;
                try { emission = outgoing.poll(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { continue; }
                if (emission == null) continue;
                try {
                    // 首个失败后不再发布排队中的旧成功/提示帧，失败帧本身仍可有界输出。
                    if (failure.get() != null && !emission.failureFrame) {
                        // 已分配的序号不能跳过后再发 failed；直接 EOF/nonzero，让客户端失败关闭。
                        outputBroken = true;
                        emission.sent.completeExceptionally(new BridgeFailure());
                        return;
                    }
                    writingStarted = System.nanoTime();
                    writing = emission;
                    output.write(emission.bytes); output.flush();
                    if (output instanceof java.io.PrintStream stream && stream.checkError()) throw new BridgeFailure();
                    emission.sent.complete(null);
                } catch (Throwable ignored) {
                    outputBroken = true; fail(Code.OUTPUT);
                    emission.sent.completeExceptionally(new BridgeFailure()); return;
                } finally { emission.erase(); writing = null; writingStarted = 0; }
            }
        } finally { if (!writerDone && !outputBroken) { outputBroken = true; fail(Code.OUTPUT); } }
    }

    private void charge(int count, boolean frame) {
        synchronized (guard) {
            if (count > TOTAL - bytes || (frame && frames >= 512)) { fail(Code.LIMIT); throw new BridgeFailure(); }
            bytes += count;
            if (frame) frames++;
        }
    }
    private void invalid() { fail(Code.PROTOCOL); throw new BridgeFailure(); }
    private void check() {
        // 提示端口必须立即返回，因此写入期限由等待协调器结果的宿主监测，而非阻塞端口。
        long started = writingStarted;
        if (writing != null && started != 0 && System.nanoTime() - started >= stopTimeout.toNanos()) {
            outputBroken = true;
            fail(Code.OUTPUT);
        }
        if (remaining() == 0) fail(Code.TIMEOUT);
        if (failure.get() != null) throw new BridgeFailure();
    }
    private void fail(Code code) {
        failure.compareAndSet(null, code);
        if (cancellation != null) cancellation.cancel();
    }
    private long remaining() { return Math.max(0, deadline - System.nanoTime()); }
    private long stopBudget() { return Math.min(remaining(), stopTimeout.toNanos()); }
    private void flush(Emission emission, long nanos) {
        try { await(emission.sent, nanos, false); }
        catch (Throwable ignored) { outputBroken = true; fail(Code.OUTPUT); throw new BridgeFailure(); }
    }
    private <T> T await(CompletableFuture<T> future, long nanos, boolean observeFailure) {
        long until = System.nanoTime() + nanos;
        for (;;) {
            if (observeFailure) check();
            long left = Math.min(remaining(), Math.max(0, until - System.nanoTime()));
            if (left == 0) throw new BridgeFailure();
            try { return future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS); }
            catch (java.util.concurrent.TimeoutException ignored) { /* 定期检查首个失败与总期限。 */ }
            catch (Exception ignored) { throw new BridgeFailure(); }
        }
    }
    private void join(Thread thread, long nanos) {
        if (nanos <= 0) throw new BridgeFailure();
        try { thread.join(Duration.ofNanos(nanos)); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new BridgeFailure(); }
        if (thread.isAlive()) throw new BridgeFailure();
    }
    private void closeResources() { boundedClose(resources); }
    private void boundedClose(AutoCloseable resource) {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Thread closer = daemon("pi-auth-bridge-close", () -> {
            try {
                resource.close();
                // System.out 的 PrintStream 会吞 IOException；close 正常返回仍需检查 sticky error。
                if (resource instanceof java.io.PrintStream stream && stream.checkError()) throw new BridgeFailure();
                closed.complete(null);
            }
            catch (Throwable ignored) { closed.completeExceptionally(new BridgeFailure()); }
        });
        await(closed, stopBudget(), false);
        join(closer, stopBudget());
    }
    private static Thread daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); thread.start(); return thread;
    }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static final class Emission {
        final byte[] bytes;
        final CompletableFuture<Void> sent = new CompletableFuture<>();
        final boolean failureFrame;
        Emission(byte[] bytes, boolean failureFrame) { this.bytes = bytes; this.failureFrame = failureFrame; }
        void erase() { Arrays.fill(bytes, (byte) 0); }
    }
    private static final class BridgeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        BridgeFailure() { super("PI_BRIDGE_FAILED", null, false, false); }
    }
}
