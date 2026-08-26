package io.github.liumaishenjian.ccjava.cli.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.sdk.AgentApplicationService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 只绑定 loopback、受 token 保护的本机 Application HTTP 原型。
 *
 * <p>本类使用独立的 HTTP JSON handler 提供 initialize、run、cancel、event 与 health，并把
 * Run 委托给同一个 {@link AgentApplicationService}。它没有使用 {@code StableProtocolCodec} 或
 * {@code ProtocolConnection}，不提供 stable v1 的版本/feature negotiation、连接 sequence、
 * correlation 或 idempotency 契约，因此不得作为 stable v1 wire/Daemon 能力宣传。Ingress、事件
 * 缓冲与并发 Run 数有界；drain 后拒绝新 Run。它也不提供远程监听、TLS、账户认证或多租户。</p>
 *
 * @since 0.1.0
 */
public final class LoopbackApplicationPrototype implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_EVENTS = 256;
    private static final String JSON = "application/json; charset=utf-8";
    private static final Set<String> INITIALIZE_FIELDS = Set.of();
    private static final Set<String> RUN_FIELDS = Set.of(
            "prompt", "maxModelTurns", "maxToolCalls", "timeoutMillis");
    private static final Set<String> CANCEL_FIELDS = Set.of("runId");

    private final HttpServer server;
    private final ExecutorService executor;
    private final CapabilityToken token;
    private final AgentApplicationService application;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Object runMonitor = new Object();
    private final ArrayDeque<ObjectNode> events = new ArrayDeque<>();
    private Future<?> runTask;
    private AgentRunResult terminal;
    private Throwable runFailure;

    /**
     * 创建仅绑定 loopback 的应用层 HTTP 原型，尚不会自动开始监听。
     *
     * @param port 监听端口；零表示由操作系统分配
     * @param token 初始化后请求必须携带的 capability token
     * @param application 唯一生产 Agent Application Service
     * @throws IOException 无法创建 loopback HTTP server 时
     */
    public LoopbackApplicationPrototype(
            int port,
            CapabilityToken token,
            AgentApplicationService application) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port 非法");
        }
        this.token = Objects.requireNonNull(token, "token 不能为空");
        this.application = Objects.requireNonNull(application, "application 不能为空");
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.createContext("/v1/health", this::health);
        server.createContext("/v1/initialize", this::initialize);
        server.createContext("/v1/run", this::run);
        server.createContext("/v1/cancel", this::cancel);
        server.createContext("/v1/events", this::eventStream);
        server.setExecutor(executor);
    }

    /** 启动一次；重复启动被拒绝。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Daemon 已启动");
        }
        server.start();
    }

    /**
     * 返回实际绑定的 loopback 端口。
     *
     * @return 构造时指定或由操作系统分配的端口
     */
    public int port() {
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        handle(exchange, "GET", false, ignored -> object()
                .put("status", accepting.get() ? "ready" : "draining")
                .put("applicationPrototype", "loopback-http-json-v0"));
    }

    private void initialize(HttpExchange exchange) throws IOException {
        handle(exchange, "POST", true, body -> {
            rejectUnknown(body, INITIALIZE_FIELDS);
            return object()
                    .put("applicationPrototype", "loopback-http-json-v0")
                    .put("stableProtocol", false)
                    .put("run", true)
                    .put("cancel", true)
                    .put("events", true);
        });
    }

    private void run(HttpExchange exchange) throws IOException {
        handle(exchange, "POST", true, body -> {
            rejectUnknown(body, RUN_FIELDS);
            if (!accepting.get()) {
                throw new HttpFailure(503, "DRAINING");
            }
            AgentRunRequest request = request(body);
            synchronized (runMonitor) {
                if (runTask != null && !runTask.isDone()) {
                    throw new HttpFailure(409, "RUN_ACTIVE");
                }
                events.clear();
                terminal = null;
                runFailure = null;
                runTask = executor.submit(() -> execute(request));
            }
            return object().put("accepted", true);
        });
    }

    private void cancel(HttpExchange exchange) throws IOException {
        handle(exchange, "POST", true, body -> {
            rejectUnknown(body, CANCEL_FIELDS);
            String value = requiredText(body, "runId", 128);
            boolean cancelled;
            try {
                cancelled = application.cancel(new RunId(value));
            } catch (IllegalArgumentException invalid) {
                throw new HttpFailure(400, "RUN_ID_INVALID");
            }
            return object().put("cancelled", cancelled);
        });
    }

    private void eventStream(HttpExchange exchange) throws IOException {
        handle(exchange, "GET", false, ignored -> {
            ObjectNode response = object();
            ArrayNode snapshot = response.putArray("events");
            synchronized (runMonitor) {
                events.forEach(snapshot::add);
                application.activeRun().ifPresent(id -> response.put("activeRunId", id.value()));
                if (terminal != null) {
                    response.set("terminal", terminal(terminal));
                } else if (runFailure != null) {
                    response.set("terminal", object().put("status", "FAILED").put("reason", "INTERNAL_ERROR"));
                }
            }
            return response;
        });
    }

    private void execute(AgentRunRequest request) {
        try {
            AgentRunResult result = application.run(request, this::recordEvent);
            synchronized (runMonitor) {
                terminal = result;
                addBounded(terminal(result).put("eventType", "terminal"), true);
                runMonitor.notifyAll();
            }
        } catch (Throwable failure) {
            synchronized (runMonitor) {
                runFailure = failure;
                addBounded(object().put("eventType", "terminal")
                        .put("status", "FAILED").put("reason", "INTERNAL_ERROR"), true);
                runMonitor.notifyAll();
            }
        }
    }

    private void recordEvent(AgentEventEnvelope envelope) {
        ObjectNode event = object()
                .put("eventType", envelope.event().getClass().getSimpleName())
                .put("sequence", envelope.sequence())
                .put("sessionId", envelope.sessionId().value());
        envelope.runId().ifPresent(id -> event.put("runId", id.value()));
        synchronized (runMonitor) {
            addBounded(event, false);
        }
    }

    private void addBounded(ObjectNode event, boolean terminalEvent) {
        while (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(event.deepCopy());
        if (terminalEvent && events.isEmpty()) {
            throw new IllegalStateException("terminal 事件不能丢失");
        }
    }

    private AgentRunRequest request(JsonNode body) {
        String prompt = requiredText(body, "prompt", MAX_BODY_BYTES);
        int turns = positiveInt(body, "maxModelTurns", AgentLimits.DEFAULT.totalModelTurns().orElseThrow());
        int tools = positiveInt(body, "maxToolCalls", AgentLimits.DEFAULT.totalToolCalls().orElseThrow());
        long timeoutMillis = positiveLong(
                body, "timeoutMillis", AgentLimits.DEFAULT.runDeadline().orElseThrow().toMillis());
        Duration timeout;
        try {
            timeout = Duration.ofMillis(timeoutMillis);
            return new AgentRunRequest(
                    new UserMessage(prompt), new AgentLimits(turns, tools, timeout), Optional.empty());
        } catch (IllegalArgumentException failure) {
            throw new HttpFailure(400, "LIMIT_INVALID");
        }
    }

    private void handle(
            HttpExchange exchange,
            String method,
            boolean bodyRequired,
            ExchangeAction action) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.getResponseHeaders().set("Allow", method);
                throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
            }
            authenticate(exchange);
            if (bodyRequired && !isJson(exchange)) {
                throw new HttpFailure(415, "JSON_REQUIRED");
            }
            JsonNode body = bodyRequired ? readBody(exchange) : object();
            send(exchange, 200, action.apply(body));
        } catch (HttpFailure failure) {
            send(exchange, failure.status, object().put("error", failure.code));
        } catch (RuntimeException failure) {
            send(exchange, 500, object().put("error", "INTERNAL_ERROR"));
        } finally {
            exchange.close();
        }
    }

    private void authenticate(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !token.matches(authorization.substring(7))) {
            throw new HttpFailure(401, "UNAUTHORIZED");
        }
    }

    private boolean isJson(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
            throw new HttpFailure(413, "BODY_SIZE");
        }
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new HttpFailure(400, "BODY_INVALID");
            }
            return node;
        } catch (HttpFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new HttpFailure(400, "BODY_INVALID");
        }
    }

    private void send(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * 停止接收新 Run并有界等待，再回收 HTTP 与 executor。
     *
     * <p>Application Service 的活动 Run 到期收敛由其自身契约负责；返回 {@code false} 表示
     * 原型传输已停止但应用资源可能仍需调用方再次关闭。</p>
     *
     * @param timeout 等待活动 Run 收敛的最大时间
     * @return 活动 Run 是否在期限内干净终止
     */
    public boolean shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (!stopped.compareAndSet(false, true)) {
            return application.activeRun().isEmpty();
        }
        accepting.set(false);
        application.beginDrain();
        boolean clean = application.awaitTermination(timeout);
        server.stop(0);
        executor.shutdownNow();
        return clean;
    }

    /** 幂等关闭 Daemon 及其 Application Service。 */
    @Override
    public void close() {
        shutdown(Duration.ofSeconds(5));
        application.close();
    }

    private ObjectNode terminal(AgentRunResult result) {
        return object()
                .put("sessionId", result.sessionId().value())
                .put("runId", result.runId().value())
                .put("status", result.status().name())
                .put("reason", result.stopReason().name())
                .put("modelTurns", result.modelTurns())
                .put("toolCalls", result.toolCalls());
    }

    private ObjectNode object() {
        return mapper.createObjectNode();
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed) {
        Set<String> unknown = new HashSet<>();
        node.propertyNames().forEach(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new HttpFailure(400, "UNKNOWN_FIELD");
        }
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maxLength) {
            throw new HttpFailure(400, "FIELD_" + field);
        }
        return value.asText();
    }

    private static int positiveInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new HttpFailure(400, "FIELD_" + field);
        }
        return value.intValue();
    }

    private static long positiveLong(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new HttpFailure(400, "FIELD_" + field);
        }
        return value.longValue();
    }

    @FunctionalInterface
    private interface ExchangeAction {
        ObjectNode apply(JsonNode body);
    }

    private static final class HttpFailure extends RuntimeException {
        private final int status;
        private final String code;

        private HttpFailure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
