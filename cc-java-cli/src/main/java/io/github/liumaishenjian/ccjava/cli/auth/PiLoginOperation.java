package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConnection;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一次性私有登录协调器：Java 持有存储权威，接收线程从不等待用户输入。
 *
 * <p>本对象拥有连接、LOGIN RPC、最多十六个提示及单线程有界发送队列；不拥有 Store，
 * 不设置默认身份、不激活 route，也不注册应用 lease。Interaction 必须立即返回，关闭提示
 * 必须确认输入入口停止，而不是取消 future 或假定 Console 已中断。未返回的材料可迟到，
 * 其回调只会擦除；可擦除数组之外的私有 JSON/JVM 字符串不承诺物理擦除。</p>
 *
 * <p>回执只证明本次 epoch 的持久 ACK、协议终态、实际 EOF/exit0 和清理确认，
 * 不证明账号或模型可用。发布后的断连、取消和失败可能是未知结果，绝不声称回滚。
 * 关闭对提示、启动/交互返回竞态及发送任务各使用独立两秒确认窗口，连接复用既有三秒
 * close；Store/RPC 的 OS 文件清理不承诺强制可中断。任何未收敛资源使清理失败 sticky。</p>
 */
public final class PiLoginOperation implements AutoCloseable {
    /** 安全诊断分类，不包含上游文字。 */
    public enum Code {
        /** 可信装配参数不符合构造契约。 */
        CONFIGURATION_INVALID,
        /** 帧结构、顺序、身份或授权链接不符合协议。 */
        PROTOCOL_INVALID,
        /** 外部取消或总预算耗尽，不说明已发布材料被撤销。 */
        CANCELLED,
        /** 操作失败；尤其 ACK 后断连可能已有持久效果。 */
        FAILED,
        /** 索引或本次身份 epoch 已改变，不能返回成功回执。 */
        CONFLICT,
        /** 尚未确认全部资源收敛；重复关闭仍报告失败。 */
        CLEANUP_FAILED,
        /** 一次性对象已运行或已关闭，禁止复用。 */
        CLOSED
    }

    /** 禁用 cause、suppressed 与堆栈，不能借清理异常泄露秘密。 */
    public static final class LoginException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        /** 可传播的诊断身份；不保留原异常。 */
        private final Code code;
        private LoginException(Code code) { super(code.name(), null, false, false); this.code = code; }
        /**
         * 返回不带原始诊断的有限失败分类。
         * @return 唯一可传播的封闭分类
         */
        public Code code() { return code; }
    }

    /**
     * 不携带提示原文或材料的可信关联。
     * @param operationId 本次操作标识
     * @param promptId 连续 1..16 的提示标识
     * @param kind secret 或 manual_code
     */
    public record Prompt(String operationId, int promptId, String kind) { }

    /**
     * 输入所有权端口；result 完成即将 SecretMaterial 所有权转交协调器。
     * close 必须确认不再接受新输入，不能以 future.cancel 代替实际入口收敛。
     * 尚未完成的 result 允许以后交付已读取材料，协调器负责擦除。
     */
    public interface PromptHandle extends AutoCloseable {
        /**
         * 取得输入完成通知，不在调用线程等待用户输入。
         * @return 非阻塞取得的一次性材料结果，不得返回 null
         */
        CompletionStage<SecretMaterial> result();
        /** 停止输入入口；不允许在成功返回后接受新输入。 */
        @Override void close();
    }

    /** UI/Console 的非阻塞接缝，不创建第二套输入界面。 */
    public interface Interaction {
        /**
         * 必须立即返回，不得等待输入；抛出前必须自行释放尚未转交的输入资源。
         * @param prompt 安全提示关联
         * @return 所有权移交给本操作的 handle
         */
        PromptHandle request(Prompt prompt);
        /**
         * 非阻塞展示已独立校验的官方链接；不得写入普通日志或 Session。
         * @param url 已校验的 OAuth 授权 URI
         */
        void authorizationUrl(URI url);
    }

    /**
     * 无材料的存储回执，不等于账号验证或 route 激活。
     * @param identity 本次绑定身份
     * @param authEpoch 本次 PUT 的身份代次
     */
    public record Receipt(PiCredentialIdentity identity, long authEpoch) { }

    // Fake 只替换连接，不替换持久 RPC，也不建立另一套生产进程管理器。
    // receive/awaitExit 必须遵守 Connection 的封套/终态后拒绝/真实 EOF 契约；close 至多三秒。
    // 并发 close 必须解除正在进行的 IO，失败不能报告已排空。
    interface Channel extends AutoCloseable {
        void send(String type, ObjectNode payload);
        ProtocolFrame receive();
        int awaitExit();
        @Override void close();
    }
    @FunctionalInterface interface ConnectionFactory {
        Channel start(PiWorkerConfiguration configuration, String operationId, CancellationToken token, Duration timeout);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final Object guard = new Object();
    private final Object closing = new Object();
    private final String operationId = UUID.randomUUID().toString();
    private final PiWorkerConfiguration configuration;
    private final PiCredentialStore store;
    private final PiCredentialIdentity identity;
    private final long expected;
    private final Interaction interaction;
    private final Duration timeout;
    private final ConnectionFactory factory;
    private final CancellationToken token;
    private final CancellationToken external;
    private final CancellationToken.Registration registration;
    private final PiCredentialRpcSession rpc;
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofVirtual().name("pi-login-send-", 0).factory());
    private final List<Pending> prompts = new ArrayList<>(16);
    private volatile boolean stopped;
    private volatile boolean externallyClosed;
    private volatile boolean cancelled;
    private volatile boolean cleanupFailed;
    private volatile Code asynchronousFailure;
    private boolean ran;
    private int acquiring;
    private int sending;
    private Pending activePrompt;
    private Channel channel;
    private volatile long begun;

    /**
     * 可信装配绑定身份与输入前索引代次；构造不启动进程或读取输入。
     * @param configuration 可信 Worker 配置
     * @param store Java 权威 Store，生命周期仍归装配者
     * @param identity 可信完整身份
     * @param expectedIndexGeneration 输入前捕获的全索引代次
     * @param interaction 非阻塞交互端口
     * @param cancellation 外部取消；回调只置位
     * @param timeout 整个操作的总预算
     */
    public PiLoginOperation(PiWorkerConfiguration configuration, PiCredentialStore store,
            PiCredentialIdentity identity, long expectedIndexGeneration, Interaction interaction,
            CancellationToken cancellation, Duration timeout) {
        this(configuration, store, identity, expectedIndexGeneration, interaction, cancellation, timeout,
                (config, id, token, budget) -> {
                    PiWorkerConnection connection = PiWorkerConnection.startAuthentication(config, id, token, budget);
                    return new Channel() {
                        public void send(String type, ObjectNode payload) { connection.send(type, payload); }
                        public ProtocolFrame receive() { return connection.receive(); }
                        public int awaitExit() { return connection.awaitExit(); }
                        public void close() { connection.close(); }
                    };
                });
    }

    PiLoginOperation(PiWorkerConfiguration configuration, PiCredentialStore store,
            PiCredentialIdentity identity, long expectedIndexGeneration, Interaction interaction,
            CancellationToken cancellation, Duration timeout, ConnectionFactory factory) {
        if (configuration == null || store == null || identity == null || interaction == null || cancellation == null
                || timeout == null || timeout.isNegative() || timeout.isZero() || factory == null
                || expectedIndexGeneration < 0 || expectedIndexGeneration == Long.MAX_VALUE) throw error(Code.CONFIGURATION_INVALID);
        this.configuration = configuration; this.store = store; this.identity = identity;
        this.expected = expectedIndexGeneration; this.interaction = interaction; this.timeout = timeout; this.factory = factory;
        this.external = cancellation;
        token = new CancellationToken() {
            public boolean isCancellationRequested() {
                return stopped || cancelled || asynchronousFailure != null || cancellation.isCancellationRequested()
                        || remainingTime().orElseThrow().isZero();
            }
            public Optional<Duration> remainingTime() {
                Duration remaining = timeout;
                if (begun != 0) remaining = timeout.minusNanos(Math.max(0, System.nanoTime() - begun));
                Duration external = cancellation.remainingTime().orElse(remaining);
                if (external.compareTo(remaining) < 0) remaining = external;
                return Optional.of(remaining.isNegative() ? Duration.ZERO : remaining);
            }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        try {
            registration = cancellation.onCancellation(() -> cancelled = true);
            rpc = new PiCredentialRpcSession(operationId, identity, PiCredentialRpcSession.Purpose.LOGIN,
                    store, expected, false, name -> { throw error(Code.PROTOCOL_INVALID); }, token);
        } catch (Throwable failure) { sender.shutdownNow(); throw error(Code.CONFIGURATION_INVALID); }
    }

    /**
     * 提供私有输入与当前操作的关联，不包含材料。
     * @return 可供交互关联使用的安全操作标识
     */
    public String operationId() { return operationId; }

    /**
     * 至多调用一次；接收循环只等待私有协议，不等待用户 future。
     * @return 清理及最终 Store epoch 重读均成功后的安全存储回执
     * @throws LoginException 协议、取消、未知存储结果或未确认清理；绝不包含上游异常
     */
    public Receipt run() {
        synchronized (guard) {
            if (ran || stopped) throw error(Code.CLOSED);
            ran = true; begun = System.nanoTime(); acquiring++;
        }
        Code failure = null;
        try {
            Channel opened = null;
            try { opened = factory.start(configuration, operationId, token, timeout); }
            finally {
                synchronized (guard) {
                    channel = opened;
                    acquiring--; guard.notifyAll();
                }
                // close 超时后的迟到工厂结果仍归本对象，不能遗失新建连接。
                if (stopped && opened != null) safeClose(opened);
            }
            check();
            if (opened == null) throw error(Code.FAILED);
            send("operation.start", JSON.createObjectNode().put("operation", "auth.login")
                    .put("providerId", identity.providerId()).put("authType", authType()));
            receiveLogin();
        } catch (Throwable failed) {
            failure = classify(failed);
            if (failure == Code.CLEANUP_FAILED) cleanupFailed = true;
        }
        try { cleanup(); } catch (Throwable failed) { failure = Code.CLEANUP_FAILED; }
        if (failure == null) failure = asynchronousFailure;
        if (failure != null) throw error(failure);
        try {
            // 清理完成后只读取元数据；不能将其他登录产生的较新 epoch 当作本次回执。
            checkExternal();
            var current = store.snapshot(external).find(identity).orElseThrow(() -> error(Code.CONFLICT));
            if (current.authEpoch() != expected + 1) throw error(Code.CONFLICT);
            checkExternal();
            return new Receipt(identity, current.authEpoch());
        } catch (Throwable failed) { throw error(classify(failed)); }
    }

    private void receiveLogin() {
        boolean ready = false, stored = false;
        int acknowledgements = 0;
        long sequence = 0;
        while (true) {
            check();
            ProtocolFrame frame = channel.receive();
            check();
            if (frame == null || !operationId.equals(frame.operationId()) || frame.sequence() != sequence++ || sequence > 512)
                throw error(Code.PROTOCOL_INVALID);
            ObjectNode p = frame.payload();
            if (!ready && !frame.type().equals("operation.ready")) throw error(Code.PROTOCOL_INVALID);
            if (stored && !frame.type().equals("operation.completed")) throw error(Code.PROTOCOL_INVALID);
            switch (frame.type()) {
                case "operation.ready" -> {
                    if (ready) throw error(Code.PROTOCOL_INVALID);
                    fields(p, "piVersion", "operations");
                    if (!text(p, "piVersion").equals("0.85.1") || !p.path("operations").isArray()) throw error(Code.PROTOCOL_INVALID);
                    Set<String> capabilities = new HashSet<>();
                    for (JsonNode entry : p.path("operations"))
                        if (!entry.isString() || !capabilities.add(entry.asString())) throw error(Code.PROTOCOL_INVALID);
                    if (!capabilities.contains("auth.login")) throw error(Code.PROTOCOL_INVALID);
                    ready = true;
                }
                case "auth.prompt" -> request(p);
                case "auth.prompt_cancelled" -> cancelPrompt(p);
                case "auth.url" -> { fields(p, "url"); interaction.authorizationUrl(authorizationUri(text(p, "url"))); }
                case "credential.request" -> {
                    if (acknowledgements != 0) throw error(Code.PROTOCOL_INVALID);
                    ObjectNode response = rpc.handle(frame);
                    boolean put = "finish".equals(p.path("action").asString())
                            && "put".equals(p.path("arguments").path("change").path("kind").asString());
                    send("credential.response", response);
                    if (!response.path("ok").asBoolean()) throw error(Code.FAILED);
                    if (put) acknowledgements++;
                }
                case "auth.result" -> {
                    fields(p, "providerId", "authType", "status");
                    if (acknowledgements != 1 || !text(p, "providerId").equals(identity.providerId())
                            || !text(p, "authType").equals(authType()) || !text(p, "status").equals("stored"))
                        throw error(Code.PROTOCOL_INVALID);
                    stored = true;
                }
                case "operation.completed" -> {
                    fields(p, "status");
                    if (!stored || !text(p, "status").equals("completed")) throw error(Code.PROTOCOL_INVALID);
                    // Channel 契约要求拒绝终态后帧，且真正 EOF/进程退出才返回。
                    if (channel.awaitExit() != 0) throw error(Code.FAILED);
                    check(); return;
                }
                case "operation.failed" -> throw error(Code.FAILED);
                default -> throw error(Code.PROTOCOL_INVALID);
            }
        }
    }

    private void request(ObjectNode payload) {
        fields(payload, "promptId", "kind");
        int id = promptId(payload);
        String kind = text(payload, "kind");
        if (!kind.equals(identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH ? "manual_code" : "secret"))
            throw error(Code.PROTOCOL_INVALID);
        Pending pending;
        synchronized (guard) {
            check();
            if (activePrompt != null || id != prompts.size() + 1) throw error(Code.PROTOCOL_INVALID);
            pending = new Pending(id); prompts.add(pending); activePrompt = pending; acquiring++;
        }
        try {
            PromptHandle handle = interaction.request(new Prompt(operationId, id, kind));
            if (handle == null) throw error(Code.PROTOCOL_INVALID);
            synchronized (guard) { pending.handle = handle; }
            // 即使 close 已发生仍安装擦除回调；result() 也属于必须非阻塞的端口契约。
            CompletionStage<SecretMaterial> result = handle.result();
            if (result == null) throw error(Code.PROTOCOL_INVALID);
            result.whenComplete((material, failed) -> materialArrived(pending, material, failed));
        } finally {
            synchronized (guard) {
                if (stopped) beginHandleClose(pending);
                acquiring--; guard.notifyAll();
            }
        }
    }

    private void materialArrived(Pending pending, SecretMaterial material, Throwable failed) {
        synchronized (guard) {
            if (stopped || cancelled || pending.cancelled || pending.delivered) {
                if (material != null) material.close();
                return;
            }
            pending.delivered = true;
            if (failed != null || material == null) {
                if (material != null) material.close();
                asynchronousFailure = Code.FAILED;
                return;
            }
            Delivery delivery = new Delivery(pending, material);
            try { sender.execute(delivery); }
            catch (Throwable rejected) { delivery.erase(); asynchronousFailure = Code.FAILED; }
        }
    }

    private final class Delivery implements Runnable {
        private final Pending pending;
        private final SecretMaterial material;
        private Delivery(Pending pending, SecretMaterial material) { this.pending = pending; this.material = material; }
        private void erase() { material.close(); }
        public void run() {
            char[] copy = null;
            try {
                synchronized (guard) {
                    if (stopped || pending.cancelled) return;
                    beginHandleClose(pending);
                }
                waitHandle(pending);
                synchronized (guard) {
                    if (stopped || pending.cancelled) return;
                    if (activePrompt == pending) activePrompt = null;
                }
                copy = material.copyChars();
                send("auth.response", JSON.createObjectNode().put("promptId", pending.id).put("value", new String(copy)));
            } catch (Throwable failed) { asynchronousFailure = classify(failed); }
            finally { if (copy != null) Arrays.fill(copy, '\0'); erase(); }
        }
    }

    private void cancelPrompt(ObjectNode payload) {
        fields(payload, "promptId");
        int id = promptId(payload);
        Pending pending;
        synchronized (guard) {
            if (id > prompts.size()) throw error(Code.PROTOCOL_INVALID);
            pending = prompts.get(id - 1);
            if (pending.cancelled || (activePrompt != pending && !pending.delivered)) throw error(Code.PROTOCOL_INVALID);
            pending.cancelled = true;
            if (activePrompt == pending) activePrompt = null;
            beginHandleClose(pending);
        }
        waitHandle(pending);
    }

    private void send(String type, ObjectNode payload) {
        Channel target;
        synchronized (guard) { check(); target = channel; sending++; }
        // 许可是发送开始的线性化点；close 必须确认该许可归还，不能只等待 executor。
        try { target.send(type, payload); }
        finally { synchronized (guard) { sending--; guard.notifyAll(); } }
    }

    private URI authorizationUri(String value) {
        try {
            if (identity.authMethod() != PiCredentialIdentity.AuthMethod.OAUTH || value.length() > 16384) throw error(Code.PROTOCOL_INVALID);
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost())
                    || !"auth.openai.com".equals(uri.getRawAuthority())
                    || !"/oauth/authorize".equals(uri.getRawPath()) || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() != -1 || uri.getRawQuery() == null)
                throw error(Code.PROTOCOL_INVALID);
            int redirects = 0, states = 0;
            for (String pair : uri.getRawQuery().split("&", -1)) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String argument = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                if (key.equals("redirect_uri")) {
                    if (++redirects != 1 || !argument.equals("http://localhost:1455/auth/callback")) throw error(Code.PROTOCOL_INVALID);
                }
                if (key.equals("state") && (++states != 1 || argument.isBlank())) throw error(Code.PROTOCOL_INVALID);
            }
            if (redirects != 1 || states != 1) throw error(Code.PROTOCOL_INVALID);
            return uri;
        } catch (Throwable failed) { throw error(Code.PROTOCOL_INVALID); }
    }

    private static final class Pending {
        private final int id;
        private PromptHandle handle;
        private boolean delivered;
        private boolean cancelled;
        private CountDownLatch closed;
        private Pending(int id) { this.id = id; }
    }

    // guard 内只启动任务，不执行用户 close。每个提示至多一个关闭任务，全操作最多十六个。
    private void beginHandleClose(Pending pending) {
        if (pending.handle == null || pending.closed != null) return;
        pending.closed = new CountDownLatch(1);
        Thread.ofVirtual().name("pi-login-prompt-close").start(() -> {
            try { pending.handle.close(); }
            catch (Throwable failed) { cleanupFailed = true; asynchronousFailure = Code.CLEANUP_FAILED; }
            finally { pending.closed.countDown(); }
        });
    }

    private void waitHandle(Pending pending) {
        CountDownLatch latch;
        synchronized (guard) { latch = pending.closed; }
        if (latch == null || !await(latch, CLEANUP_NANOS) || cleanupFailed) {
            cleanupFailed = true; throw error(Code.CLEANUP_FAILED);
        }
    }

    /**
     * 停止新输入与发送，确认已拥有资源收敛；并发/重复关闭不吞掉 sticky 清理失败。
     * 未返回材料的 future 不必完成，但其 handle 必须已确认停止输入。OS 存储关闭不强制中断。
     * @throws LoginException 任一资源未确认释放，固定 CLEANUP_FAILED
     */
    @Override public void close() {
        externallyClosed = true;
        cleanup();
    }

    // 正常run也必须清理，但不能把外部close与内部清理混成同一回执许可。
    private void cleanup() {
        stopped = true;
        synchronized (closing) {
            boolean interrupted = Thread.interrupted();
            try {
                List<Runnable> abandoned;
                synchronized (guard) {
                    abandoned = sender.shutdownNow();
                    for (Pending pending : prompts) beginHandleClose(pending);
                }
                for (Runnable runnable : abandoned) ((Delivery) runnable).erase();
                long start = System.nanoTime();
                synchronized (guard) {
                    while (acquiring != 0 && System.nanoTime() - start < CLEANUP_NANOS) {
                        try { guard.wait(10); } catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (acquiring != 0) cleanupFailed = true;
                }
                Channel target;
                List<Pending> owned;
                synchronized (guard) { target = channel; owned = List.copyOf(prompts); }
                if (target != null) safeClose(target);
                start = System.nanoTime();
                for (Pending pending : owned) {
                    CountDownLatch latch;
                    synchronized (guard) { beginHandleClose(pending); latch = pending.closed; }
                    if (latch == null || !await(latch, Math.max(0, CLEANUP_NANOS - (System.nanoTime() - start)))) cleanupFailed = true;
                }
                start = System.nanoTime();
                while (!sender.isTerminated() && System.nanoTime() - start < CLEANUP_NANOS) {
                    try { sender.awaitTermination(10, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                synchronized (guard) {
                    while (sending != 0 && System.nanoTime() - start < CLEANUP_NANOS) {
                        try { guard.wait(10); } catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (sending != 0) cleanupFailed = true;
                }
                if (!sender.isTerminated()) cleanupFailed = true;
                safeClose(rpc);
                safeClose(registration);
                if (cleanupFailed) throw error(Code.CLEANUP_FAILED);
            } catch (Throwable failed) {
                cleanupFailed = true;
                throw error(Code.CLEANUP_FAILED);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private void safeClose(AutoCloseable resource) {
        try { resource.close(); } catch (Throwable failed) { cleanupFailed = true; }
    }
    private static boolean await(CountDownLatch latch, long nanos) {
        boolean interrupted = Thread.interrupted();
        long start = System.nanoTime();
        try {
            while (latch.getCount() != 0 && System.nanoTime() - start < nanos) {
                try { latch.await(Math.min(TimeUnit.MILLISECONDS.toNanos(10), nanos), TimeUnit.NANOSECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            return latch.getCount() == 0;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void checkExternal() {
        if (externallyClosed || cancelled || external.isCancellationRequested() || token.remainingTime().orElseThrow().isZero())
            throw error(Code.CANCELLED);
    }
    private void check() {
        if (cleanupFailed) throw error(Code.CLEANUP_FAILED);
        if (asynchronousFailure != null) throw error(asynchronousFailure);
        if (token.isCancellationRequested()) throw error(Code.CANCELLED);
    }
    private String authType() { return identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH ? "oauth" : "api_key"; }
    private static int promptId(JsonNode node) {
        JsonNode id = node.get("promptId");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() < 1 || id.intValue() > 16)
            throw error(Code.PROTOCOL_INVALID);
        return id.intValue();
    }
    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length || !node.propertyNames().containsAll(Set.of(names)))
            throw error(Code.PROTOCOL_INVALID);
    }
    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString()) throw error(Code.PROTOCOL_INVALID);
        return value.asString();
    }
    private static Code classify(Throwable failed) {
        if (failed instanceof LoginException login) return login.code();
        if (failed instanceof io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException worker) return switch (worker.code()) {
            case PROTOCOL_INVALID -> Code.PROTOCOL_INVALID;
            case CLEANUP_FAILED -> Code.CLEANUP_FAILED;
            case CANCELLED, DEADLINE_EXCEEDED -> Code.CANCELLED;
            default -> Code.FAILED;
        };
        if (failed instanceof ProviderAuthException auth && auth.code() == ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT) return Code.CONFLICT;
        return Code.FAILED;
    }
    private static LoginException error(Code code) { return new LoginException(code); }
    @Override public String toString() { return "PI_LOGIN_OPERATION"; }
}
