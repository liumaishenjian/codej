package io.github.liumaishenjian.ccjava.model.pi.gateway;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ContextSummarizer;
import java.util.function.Supplier;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConnection;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameCodec;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameSequence;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-100 的单回合 Pi 边缘适配器，Java Runtime 继续独占循环、重试及工具执行。
 *
 * <p>固定路由的实例可逐回合复用，但只允许一个活动操作；每次操作创建新进程和凭证会话。
 * 不读取凭证环境、不创建 Agent、不切换 Provider、不执行工具，也不发送 attempt 通知。
 * result 只是候选结果，只有唯一终态、实际 EOF、零退出码及全部资源确认关闭后才返回。</p>
 *
 * <p>外部 close 永久封闭实例并传播取消，与内部正常清理不同；迟到创建的资源仍归本操作清理。
 * 清理未确认是粘性失败，不释放活动槽。每个 Run/子 Run 应由组合根建立不同实例。</p>
 */
public final class PiModelGateway implements StreamingModelGateway, ContextSummarizer, AutoCloseable {
    private static final Set<String> ROUTES = Set.of("openai", "openai-codex", "deepseek", "qwen-token-plan-cn");
    private static final Set<String> ERRORS = Set.of("AUTH", "CANCELLED", "TIMEOUT", "RATE_LIMIT", "TRANSIENT",
            "CONTEXT_OVERFLOW", "INCOMPLETE", "PROTOCOL", "PERMANENT", "UNSUPPORTED", "LIMIT");
    private final PiWorkerConfiguration configuration;
    private final String providerId;
    private final String modelId;
    private final Duration requestTimeout;
    private final PiCredentialSessionFactory credentials;
    private final TransportFactory transport;
    private boolean closed;
    private boolean cleanupFailed;
    private Operation active;

    /**
     * 构造使用生产 PiWorkerConnection 的固定路由网关；不立即启动进程或访问凭证。
     * @param configuration 可信的固定 Worker 安装配置
     * @param providerId ADR-100 支持的固定提供商路由
     * @param modelId 非空且符合 Domain 身份预算的固定模型标识
     * @param requestTimeout 每个完整操作的正总预算，最多三百秒
     * @param credentials 由组合根绑定身份及 epoch 的新会话工厂
     */
    public PiModelGateway(PiWorkerConfiguration configuration, String providerId, String modelId,
            Duration requestTimeout, PiCredentialSessionFactory credentials) {
        this(configuration, providerId, modelId, requestTimeout, credentials, (config, id, token, timeout) -> {
            PiWorkerConnection connection = PiWorkerConnection.start(config, id, token, timeout);
            return new Channel() {
                private boolean terminal;
                private Integer exit;
                public void send(String type, ObjectNode payload) { connection.send(type, payload); }
                public ProtocolFrame receive() {
                    if (terminal) {
                        // Connection通过awaitExit证明实际EOF及IO清理；不能把任意CLOSED异常冒充EOF。
                        exit = connection.awaitExit();
                        return null;
                    }
                    ProtocolFrame frame = connection.receive();
                    terminal = frame.type().equals("operation.completed") || frame.type().equals("operation.failed");
                    return frame;
                }
                public int awaitExit() { return exit == null ? connection.awaitExit() : exit; }
                public void close() { connection.close(); }
            };
        });
    }

    PiModelGateway(PiWorkerConfiguration configuration, String providerId, String modelId,
            Duration timeout, PiCredentialSessionFactory credentials, TransportFactory transport) {
        this.configuration = Objects.requireNonNull(configuration, "configuration 不能为空");
        if (!ROUTES.contains(Objects.requireNonNull(providerId))) throw new IllegalArgumentException("PI_ROUTE_INVALID");
        // 复用 Domain 的严格 Unicode/身份长度契约，不从模型请求动态选择路由。
        new ModelContinuation("pi", providerId, modelId, "{}");
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(300)) > 0)
            throw new IllegalArgumentException("PI_TIMEOUT_INVALID");
        this.providerId = providerId;
        this.modelId = modelId;
        this.requestTimeout = timeout;
        this.credentials = Objects.requireNonNull(credentials, "credentials 不能为空");
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
    }

    /**
     * 执行一个模型回合；观察者异常被隔离，失败只保留封闭码和无 HTTP 状态的摘要。
     * @param request Runtime 提供的不可变消息及工具定义快照
     * @param observer 仅观察文本增量，不参与模型决策
     * @param cancellation Runtime 取消及剩余预算端口
     * @return 资源清理及终态均已确认的完整 Assistant（含可选续接）
     * @throws ModelGatewayException 取消、并发调用、关闭、协议、Provider 或清理失败
     */
    @Override
    public ModelTurn complete(ModelRequest request, ModelStreamObserver observer, CancellationToken cancellation)
            throws ModelGatewayException {
        Objects.requireNonNull(request); Objects.requireNonNull(observer); Objects.requireNonNull(cancellation);
        return execute(() -> new PiPromptMapper().map(request, providerId, modelId), observer, cancellation, false);
    }

    /**
     * 在同一固定身份与活动槽执行无工具摘要，不发布增量、Usage 或隐藏续接。
     * <p>只返回资源关闭已确认的纯正文候选；来源元数据取自请求，采纳仍由 Core 决定。
     * 取消返回空，其他失败抛固定无 cause 异常；取消不能掩盖粘性清理失败。</p>
     * @param request 有界来源及输出预算，不赋予来源文本 System 权限
     * @param cancellationToken Runtime 取消与剩余预算
     * @return 已完成的有界候选，或取消/候选无效时为空
     * @throws RuntimeException 执行、协议或资源清理失败，消息固定且无底层异常链
     */
    @Override
    public Optional<SummaryCandidate> summarize(SummaryRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request); Objects.requireNonNull(cancellationToken);
        try {
            ModelTurn turn = execute(() -> new PiSummaryMapper().map(request, providerId, modelId),
                    ignored -> {}, cancellationToken, true);
            synchronized (this) {
                if (cleanupFailed) throw new SummaryExecutionException();
                if (closed || cancellationToken.isCancellationRequested()) return Optional.empty();
                return PiSummaryMapper.candidate(request, turn.assistantMessage());
            }
        } catch (ModelGatewayException failure) {
            if (failure.kind() == FailureKind.CANCELLED) return Optional.empty();
            throw new SummaryExecutionException();
        } catch (RuntimeException failure) {
            throw new SummaryExecutionException();
        }
    }

    /** 共用活动槽、映射预检和资源状态机；摘要语义显式传递，观察者仅观察，不承担控制职责。 */
    private ModelTurn execute(Supplier<ObjectNode> payloadFactory, ModelStreamObserver observer,
            CancellationToken cancellation, boolean summary) throws ModelGatewayException {
        Operation op;
        synchronized (this) {
            if (closed || cleanupFailed || active != null) throw failure("UNAVAILABLE", false, false, null);
            op = new Operation(requestTimeout);
            active = op;
        }
        ModelTurn result = null;
        ModelGatewayException failure = null;
        try {
            op.upstream = cancellation.onCancellation(op.source::cancel);
            op.upstreamToken = cancellation;
            check(op);
            ObjectNode payload = payloadFactory.get();
            validateBytes(new ProtocolFrame(op.id, 0, "operation.start", payload));
            check(op);
            op.session = Objects.requireNonNull(credentials.open(op.id, op.token));
            check(op); // 取消/close 与工厂返回竞争：资源已绑定，finally 必须关闭迟到会话。
            op.channel = Objects.requireNonNull(transport.start(configuration, op.id, op.token, requestTimeout));
            check(op); // 同样覆盖进程启动期间的关闭竞争。
            op.channel.send("operation.start", payload);
            result = receive(op, observer, summary);
        } catch (ModelGatewayException safe) {
            failure = safe;
        } catch (PiWorkerException safe) {
            if (safe.code() == PiWorkerException.Code.CLEANUP_FAILED) markCleanupFailed();
            failure = failure(switch (safe.code()) {
                case CANCELLED -> "CANCELLED";
                case DEADLINE_EXCEEDED -> "TIMEOUT";
                case LIMIT_EXCEEDED -> "LIMIT";
                default -> "PROTOCOL";
            }, op.frame, false, null);
        } catch (RuntimeException unsafe) {
            // 不把 SDK、JSON、凭证工厂的任意正文或 cause 带入 Runtime/Session。
            failure = failure("PROTOCOL", op.frame, false, null);
        } finally {
            boolean clean = cleanup(op.channel) & cleanup(op.session) & cleanup(op.upstream);
            synchronized (this) {
                if (!clean) cleanupFailed = true;
                op.clean = clean;
                op.done.countDown();
            }
        }
        synchronized (this) {
            // 必须在内部清理之后再次观察外部 close，不能把候选回执当成已交付。
            if (cleanupFailed || !op.clean) throw failure("CLEANUP_FAILED", op.frame, false, null);
            active = null;
            if (closed || op.token.isCancellationRequested()) throw failure("CANCELLED", op.frame, false, null);
            if (failure != null) throw failure;
            check(op);
            return Objects.requireNonNull(result);
        }
    }

    private ModelTurn receive(Operation op, ModelStreamObserver observer, boolean summary) throws ModelGatewayException {
        try (FrameSequence sequence = new FrameSequence(op.id)) {
            ProtocolFrame ready = next(op, sequence);
            if (ready == null || !ready.type().equals("operation.ready")) throw invalid(op);
            ObjectNode r = ready.payload();
            fields(r, Set.of("piVersion", "operations"), Set.of());
            if (!string(r, "piVersion").equals("0.85.1") || !r.get("operations").isArray()) throw invalid(op);
            Set<String> operations = new HashSet<>();
            for (JsonNode name : r.get("operations")) {
                if (!name.isString() || !Set.of("catalog", "auth.login", "model").contains(name.asString())
                        || !operations.add(name.asString())) throw invalid(op);
            }
            if (!operations.contains("model")) throw invalid(op);
            StringBuilder text = new StringBuilder();
            ModelTurn candidate = null;
            ModelGatewayException typed = null;
            boolean terminal = false;
            while (!terminal) {
                ProtocolFrame frame = next(op, sequence);
                if (frame == null) throw invalid(op);
                ObjectNode value = frame.payload();
                boolean priorFrame = op.frame;
                // 即使内容事件随后因乱序/字段错误被拒绝，也不能重新取得无内容重试资格。
                if (frame.type().equals("model.frame") || frame.type().equals("model.delta")) op.frame = true;
                if (frame.type().equals("model.error") && value.has("providerFrame")
                        && value.get("providerFrame").isBoolean() && value.get("providerFrame").asBoolean()) op.frame = true;
                switch (frame.type()) {
                    case "model.frame" -> {
                        if (candidate != null || typed != null || priorFrame) throw invalid(op);
                        fields(value, Set.of(), Set.of());
                    }
                    case "model.delta" -> {
                        if (!priorFrame || candidate != null || typed != null) throw invalid(op);
                        fields(value, Set.of("text"), Set.of());
                        String delta = string(value, "text");
                        if (delta.isEmpty()) throw invalid(op);
                        text.append(delta);
                        try { observer.onTextDelta(delta); } catch (RuntimeException ignored) { /* 观察不能改变结果。 */ }
                    }
                    case "credential.request" -> {
                        if (candidate != null || typed != null) throw invalid(op);
                        ObjectNode response = Objects.requireNonNull(op.session.handle(frame));
                        check(op);
                        op.channel.send("credential.response", response);
                    }
                    case "model.result" -> {
                        if (candidate != null || typed != null || !op.frame) throw invalid(op);
                        candidate = result(value, summary);
                        if (!candidate.assistantMessage().text().contentEquals(text)) throw invalid(op);
                    }
                    case "model.error" -> {
                        if (candidate != null || typed != null) throw invalid(op);
                        fields(value, Set.of("code", "retryable", "providerFrame"), Set.of("retryAfterMs"));
                        String code = string(value, "code");
                        if (!ERRORS.contains(code) || !value.get("retryable").isBoolean()
                                || !value.get("providerFrame").isBoolean()) throw invalid(op);
                        op.frame |= value.get("providerFrame").asBoolean();
                        boolean retry = value.get("retryable").asBoolean();
                        if (retry && !Set.of("TIMEOUT", "RATE_LIMIT", "TRANSIENT").contains(code)) throw invalid(op);
                        Long delay = value.has("retryAfterMs") ? count(value.get("retryAfterMs"), 86_400_000) : null;
                        typed = failure(code, op.frame, retry, delay);
                    }
                    case "operation.completed" -> {
                        fields(value, Set.of("status"), Set.of());
                        if (candidate == null || typed != null || !string(value, "status").equals("completed")) throw invalid(op);
                        terminal = true;
                    }
                    case "operation.failed" -> {
                        fields(value, Set.of("code"), Set.of());
                        if (candidate != null || !Set.of("WORKER_FAILED", "WORKER_IO_FAILED", "PROTOCOL_INVALID",
                                "PROTOCOL_LIMIT", "PROTOCOL_CLOSED", "TIMEOUT", "CANCELLED", "OPERATION_UNSUPPORTED")
                                .contains(string(value, "code"))) throw invalid(op);
                        if (typed != null && !string(value, "code").equals("WORKER_FAILED")) throw invalid(op);
                        if (typed == null) {
                            // Worker总期限可直接结束操作；保留封闭超时分类，但不凭终态增加重试许可。
                            String code = string(value, "code");
                            typed = failure(Set.of("CANCELLED", "TIMEOUT").contains(code) ? code : "PROTOCOL",
                                    op.frame, false, null);
                        }
                        terminal = true;
                    }
                    default -> throw invalid(op);
                }
            }
            sequence.seal();
            if (next(op, sequence) != null) throw invalid(op); // 实际 EOF，拒绝尾部帧/输出。
            int exit = op.channel.awaitExit();
            check(op);
            if (typed != null) {
                // 正常错误回执为 exit 1；exit 2 可能表示协议或 Worker 清理失败，不能保留 typed 重试许可。
                if (exit != 1) throw invalid(op);
                throw typed;
            }
            if (exit != 0) throw invalid(op);
            return candidate;
        }
    }

    private ProtocolFrame next(Operation op, FrameSequence sequence) throws ModelGatewayException {
        check(op);
        ProtocolFrame frame = op.channel.receive();
        check(op);
        if (frame != null) {
            sequence.accept(frame);
            op.bytes += validateBytes(frame);
            if (++op.frames > FrameCodec.MAXIMUM_FRAMES || op.bytes > 16L * 1024 * 1024) throw failure("LIMIT", op.frame, false, null);
        }
        return frame;
    }

    private static int validateBytes(ProtocolFrame frame) {
        byte[] bytes = FrameCodec.encode(frame);
        try { return bytes.length; } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private ModelTurn result(ObjectNode value, boolean summary) {
        fields(value, Set.of("text", "toolCalls", "usage", "continuation"), Set.of());
        String text = string(value, "text");
        JsonNode calls = value.get("toolCalls");
        if (!calls.isArray() || calls.size() > 1024) throw bad();
        var tools = new ArrayList<ToolCall>();
        var ids = new HashSet<String>();
        for (JsonNode call : calls) {
            fields(call, Set.of("id", "name", "arguments"), Set.of());
            String id = string(call, "id"), name = string(call, "name");
            if (id.isBlank() || id.length() > 512 || name.isBlank() || name.length() > 256
                    || !ids.add(id) || !call.get("arguments").isObject()) throw bad();
            tools.add(new ToolCall(id, name, new JsonObject(objectValues(call.get("arguments")))));
        }
        JsonNode c = value.get("continuation");
        fields(c, Set.of("backend", "providerId", "modelId", "payload"), Set.of());
        ModelContinuation continuation = new ModelContinuation(string(c, "backend"), string(c, "providerId"),
                string(c, "modelId"), string(c, "payload"));
        if (!continuation.backend().equals("pi") || !continuation.providerId().equals(providerId)
                || !continuation.modelId().equals(modelId)) throw bad();
        AssistantMessage assistant = new AssistantMessage(text, tools, Optional.of(continuation));
        if (assistant.isEmpty() && !summary) throw bad();
        return new ModelTurn(assistant, new ModelTurnMetadata(tools.isEmpty() ? ModelFinishReason.STOP
                : ModelFinishReason.TOOL_CALLS, usage(value.get("usage")), Optional.empty()));
    }

    /**
     * Pi0.85.1 公开 SDK api/openai-responses-shared.js 与 openai-completions.js 的分项语义：
     * input 已扣除 cacheRead/cacheWrite。恢复 Domain 输入后精确求和；不采用 SDK total 或价格。
     */
    private static Optional<ModelUsage> usage(JsonNode value) {
        fields(value, Set.of("input", "output", "cacheRead", "cacheWrite"), Set.of());
        int input = Math.addExact(Math.addExact((int) count(value.get("input"), Integer.MAX_VALUE),
                (int) count(value.get("cacheRead"), Integer.MAX_VALUE)), (int) count(value.get("cacheWrite"), Integer.MAX_VALUE));
        int output = (int) count(value.get("output"), Integer.MAX_VALUE);
        int total = Math.addExact(input, output);
        return total == 0 ? Optional.empty() : Optional.of(new ModelUsage(input, output, total));
    }

    private static LinkedHashMap<String, Object> objectValues(JsonNode node) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : node.properties()) map.put(entry.getKey(), jsonValue(entry.getValue()));
        return map;
    }
    private static Object jsonValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isObject()) return objectValues(node);
        if (node.isArray()) { var list = new ArrayList<Object>(); for (JsonNode child : node) list.add(jsonValue(child)); return list; }
        if (node.isString()) return node.asString();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.numberValue();
        throw bad();
    }
    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw bad();
        for (String key : required) if (!node.has(key)) throw bad();
        for (String key : node.propertyNames()) if (!required.contains(key) && !optional.contains(key)) throw bad();
    }
    private static String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString()) throw bad();
        return value.asString();
    }
    private static long count(JsonNode value, long max) {
        // 计数必须是整数节点；先转double会把精细小数或下溢值误认成合法整数。
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw bad();
        long number = value.longValue();
        if (number < 0 || number > max) throw bad();
        return number;
    }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("PI_MODEL_PROTOCOL_INVALID"); }
    private ModelGatewayException invalid(Operation op) { return failure("PROTOCOL", op.frame, false, null); }

    /**
     * Node 未传 HTTP 状态，AUTH/RATE_LIMIT 不能使用要求 4xx 证据的 Domain 类别；保留通用 Provider 类别。
     * INCOMPLETE 但没有实际内容 fence 时使用 PERMANENT，避免伪造 receivedOutput；二者均禁止自动重试。
     */
    private static ModelGatewayException failure(String code, boolean frame, boolean retryable, Long retryAfterMs) {
        FailureKind kind = code.equals("CANCELLED") ? FailureKind.CANCELLED : frame ? FailureKind.INCOMPLETE_STREAM
                : code.equals("CONTEXT_OVERFLOW") ? FailureKind.CONTEXT_OVERFLOW
                : retryable ? FailureKind.RETRYABLE : FailureKind.PERMANENT;
        ModelFailureCategory category = kind == FailureKind.INCOMPLETE_STREAM ? ModelFailureCategory.INCOMPLETE_STREAM
                : code.equals("TIMEOUT") ? ModelFailureCategory.REQUEST_TIMEOUT
                : code.equals("PROTOCOL") || code.equals("LIMIT") ? ModelFailureCategory.INVALID_RESPONSE
                : ModelFailureCategory.PROVIDER_ERROR;
        var summary = new ModelFailureSummary(category, Optional.empty(), 1, frame);
        // Node 上限为一天，Domain 为五分钟；保守截上限，不编造 HTTP 状态或转发任何原始 header。
        return retryAfterMs == null ? new ModelGatewayException(kind, "PI_MODEL_" + code, summary, null)
                : new ModelGatewayException(kind, "PI_MODEL_" + code, summary,
                        Duration.ofMillis(Math.min(retryAfterMs, 300_000)), null);
    }

    private void check(Operation op) throws ModelGatewayException {
        synchronized (this) {
            if (closed || op.token.isCancellationRequested()) throw failure("CANCELLED", op.frame, false, null);
        }
        if (op.token.remainingTime().orElse(Duration.ZERO).isZero()) throw failure("TIMEOUT", op.frame, false, null);
    }
    private static boolean cleanup(AutoCloseable resource) {
        if (resource == null) return true;
        try { resource.close(); return true; } catch (Exception failed) { return false; }
    }
    private synchronized void markCleanupFailed() { cleanupFailed = true; }

    /**
     * 永久关闭入口并等待当前操作确认清理，独立等待窗口最多三秒。
     * <p>不强制中断任意凭证文件 IO；超时或清理失败永久保持失败，迟到资源仍由操作线程关闭。</p>
     * @throws PiWorkerException 清理尚未确认，不能报告租约已释放
     */
    @Override
    public void close() {
        Operation op;
        synchronized (this) { closed = true; op = active; }
        if (op != null) {
            op.source.cancel();
            boolean interrupted = Thread.interrupted();
            boolean done = false;
            long begun = System.nanoTime();
            try {
                if (op.owner != Thread.currentThread()) {
                    while (!done && System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(3)) {
                        try { done = op.done.await(10, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                } else done = op.done.getCount() == 0;
                if (!done) markCleanupFailed();
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        synchronized (this) {
            if (cleanupFailed) throw new PiWorkerException(PiWorkerException.Code.CLEANUP_FAILED);
        }
    }

    @FunctionalInterface
    interface TransportFactory {
        Channel start(PiWorkerConfiguration configuration, String operationId, CancellationToken token, Duration timeout);
    }
    interface Channel extends AutoCloseable {
        void send(String type, ObjectNode payload);
        ProtocolFrame receive();
        int awaitExit();
        @Override void close();
    }

    /** 固定摘要失败标识，不携带 Provider 正文、cause 或 suppressed 异常。 */
    private static final class SummaryExecutionException extends RuntimeException {
        private SummaryExecutionException() { super("Context summary model request failed"); }
    }

    private static final class Operation {
        private final String id = UUID.randomUUID().toString();
        private final Thread owner = Thread.currentThread();
        private final CancellationSource source;
        private final CountDownLatch done = new CountDownLatch(1);
        private CancellationToken upstreamToken = CancellationToken.none();
        private final CancellationToken token = new CancellationToken() {
            public boolean isCancellationRequested() { return source.token().isCancellationRequested() || upstreamToken.isCancellationRequested(); }
            public Registration onCancellation(Runnable callback) { return source.token().onCancellation(callback); }
            public Optional<Duration> remainingTime() {
                Duration own = source.token().remainingTime().orElseThrow();
                Duration parent = upstreamToken.remainingTime().orElse(own);
                return Optional.of(parent.compareTo(own) < 0 ? (parent.isNegative() ? Duration.ZERO : parent) : own);
            }
        };
        private CancellationToken.Registration upstream;
        private PiCredentialSession session;
        private Channel channel;
        private boolean frame;
        private boolean clean;
        private long bytes;
        private int frames;
        private Operation(Duration timeout) { source = new CancellationSource(timeout); }
    }
}
