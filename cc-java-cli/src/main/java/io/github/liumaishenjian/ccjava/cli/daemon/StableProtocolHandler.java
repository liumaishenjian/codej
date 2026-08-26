package io.github.liumaishenjian.ccjava.cli.daemon;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.protocol.ProtocolCodecException;
import io.github.liumaishenjian.ccjava.protocol.ProtocolConnection;
import io.github.liumaishenjian.ccjava.protocol.ProtocolEnvelope;
import io.github.liumaishenjian.ccjava.protocol.ProtocolFeature;
import io.github.liumaishenjian.ccjava.protocol.ProtocolMessageKind;
import io.github.liumaishenjian.ccjava.protocol.ProtocolVersion;
import io.github.liumaishenjian.ccjava.protocol.StableProtocolCodec;
import io.github.liumaishenjian.ccjava.sdk.AgentApplicationService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * stable v1 wire 到唯一 {@link AgentApplicationService} 的生产处理器。
 *
 * <p>本处理器同时使用 {@link StableProtocolCodec} 与 {@link ProtocolConnection}，负责
 * initialize、严格连接序号、请求关联、语义幂等、Run/Cancel、唯一终态、背压和断连 fence。
 * 它不拥有 Agent Loop；所有模型与 Tool 副作用仍由 Application Service 下的同一 Runtime/Pipeline
 * 决定。关闭连接会先 fence 事件，再取消活动 Run，防止迟到事件被发布给已断开的 Client。</p>
 *
 * @since 0.1.0
 */
public final class StableProtocolHandler implements AutoCloseable {
    /** 连接级有界输出队列允许保留的最大消息数。 */
    public static final int MAX_EGRESS_MESSAGES = 256;
    private static final int MAX_PROMPT_CHARS = 1_048_576;
    private static final Set<String> INITIALIZE_FIELDS = Set.of("token", "version", "features");
    private static final Set<String> RUN_FIELDS = Set.of(
            "prompt", "maxModelTurns", "maxToolCalls", "timeoutMillis");
    private static final Set<String> CANCEL_FIELDS = Set.of("runId");
    private static final Set<String> EMPTY_FIELDS = Set.of();
    private static final Set<String> SESSION_LIST_FIELDS = Set.of("offset", "limit");
    private static final Set<String> SESSION_SEARCH_FIELDS = Set.of("query", "limit");
    private static final Set<String> SESSION_EXPORT_FIELDS = Set.of(
            "sessionId", "includeContent", "redacted", "confirmed");
    private static final Set<String> SESSION_RETAIN_FIELDS = Set.of(
            "sessionId", "action", "firstConfirmation", "secondConfirmation");
    private static final Set<String> SESSION_MIGRATE_FIELDS = Set.of(
            "sourceFile", "targetFile", "fromMajor", "toMajor");
    private static final Set<String> TASK_SNAPSHOT_FIELDS = Set.of("cursor", "limit");

    private final StableProtocolCodec codec;
    private final ProtocolConnection connection;
    private final AgentApplicationService application;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("cc-java-v1-run-", 0).factory());
    private final Object monitor = new Object();
    private final ArrayDeque<ProtocolEnvelope> egress = new ArrayDeque<>();
    private final Map<String, ProtocolEnvelope> completedResponses = new LinkedHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private long outboundSequence;
    private Future<?> runTask;
    private String activeRequestId;
    private RunId activeRunId;
    private boolean terminalPublished;

    /**
     * 创建绑定 capability token、能力集合和唯一 Application Service 的连接处理器。
     *
     * @param token initialize 必须精确匹配的 capability token
     * @param features Server 可协商能力
     * @param application 唯一生产 Application Service
     */
    public StableProtocolHandler(
            CapabilityToken token,
            Set<ProtocolFeature> features,
            AgentApplicationService application) {
        this.codec = new StableProtocolCodec();
        this.connection = new ProtocolConnection(
                Objects.requireNonNull(token, "token 不能为空"),
                Set.copyOf(Objects.requireNonNull(features, "features 不能为空")));
        this.application = Objects.requireNonNull(application, "application 不能为空");
    }

    /**
     * 接收一条完整 UTF-8 JSON 消息；响应与异步事件通过 {@link #takeOutput(Duration)} 读取。
     *
     * @param line 不含 framing 换行的 UTF-8 JSON
     * @throws ProtocolCodecException wire 或连接状态非法；调用方应发送 typed error 后断开
     */
    public void receive(byte[] line) throws ProtocolCodecException {
        if (closed.get()) {
            throw new ProtocolCodecException("CONNECTION_CLOSED");
        }
        ProtocolEnvelope request = codec.decode(line);
        if (request.kind() != ProtocolMessageKind.REQUEST) {
            throw new ProtocolCodecException("REQUEST_REQUIRED");
        }
        if ("initialize".equals(request.type())) {
            initialize(request);
            return;
        }
        Optional<String> replayId = connection.accept(request);
        if (replayId.isPresent()) {
            ProtocolEnvelope cached;
            synchronized (monitor) {
                cached = completedResponses.get(replayId.orElseThrow());
            }
            if (cached == null) {
                throw new ProtocolCodecException("IDEMPOTENCY_CACHE_MISS");
            }
            enqueue(copyForReplay(cached, request), true);
            return;
        }
        requireFeature(request);
        switch (request.type()) {
            case "run.start" -> startRun(request);
            case "run.cancel" -> cancelRun(request);
            case "session.list" -> sessionList(request);
            case "session.search" -> sessionSearch(request);
            case "session.export" -> sessionExport(request);
            case "session.retain" -> sessionRetain(request);
            case "session.migrate" -> sessionMigrate(request);
            case "governance.get" -> governance(request);
            case "task.snapshot" -> taskSnapshot(request);
            case "drain" -> drain(request);
            case "shutdown" -> shutdown(request);
            default -> throw failAccepted(request, "UNKNOWN_REQUEST");
        }
    }

    private void requireFeature(ProtocolEnvelope request) throws ProtocolCodecException {
        ProtocolFeature required = switch (request.type()) {
            case "run.start" -> ProtocolFeature.RUN;
            case "run.cancel" -> ProtocolFeature.CANCEL;
            case "session.list", "session.search" -> ProtocolFeature.SESSION_INDEX;
            case "session.export" -> ProtocolFeature.SESSION_EXPORT;
            case "session.retain" -> ProtocolFeature.SESSION_RETENTION;
            case "session.migrate" -> ProtocolFeature.SESSION_MIGRATION;
            case "governance.get" -> ProtocolFeature.GOVERNANCE;
            case "task.snapshot" -> ProtocolFeature.TASK_LIST_V1;
            case "drain", "shutdown" -> ProtocolFeature.DAEMON;
            default -> null;
        };
        if (required != null && !connection.negotiatedFeatures().contains(required)) {
            throw failAccepted(request, "FEATURE_NOT_NEGOTIATED");
        }
    }

    /**
     * 在期限内取得下一条已编码输出；无输出返回 empty。
     *
     * @param timeout 最大等待时间
     * @return 下一条 UTF-8 JSON 或空
     */
    public Optional<byte[]> takeOutput(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long deadline = saturatedDeadline(timeout);
        synchronized (monitor) {
            while (egress.isEmpty() && !closed.get() && System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                try {
                    monitor.wait(Math.max(1, Math.min(100, remaining / 1_000_000)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
            ProtocolEnvelope next = egress.pollFirst();
            return next == null ? Optional.empty() : Optional.of(codec.encode(next));
        }
    }

    /**
     * 返回连接是否已完成 shutdown/close fence。
     *
     * @return 已关闭时为 {@code true}
     */
    public boolean isClosed() { return closed.get(); }

    /**
     * 返回当前有界输出深度，用于健康检查和背压测试。
     *
     * @return 待发送消息数
     */
    public int pendingOutputCount() {
        synchronized (monitor) {
            return egress.size();
        }
    }

    private void initialize(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), INITIALIZE_FIELDS);
        ObjectNode payload = request.payload();
        String token = requiredText(payload, "token", 256);
        ProtocolVersion version = parseVersion(requiredText(payload, "version", 32));
        Set<ProtocolFeature> requested = parseFeatures(payload.get("features"));
        Set<ProtocolFeature> negotiated = connection.initialize(
                token, version, requested, request.sequence());
        ObjectNode responsePayload = codec.objectNode().put("version", "1.0");
        ArrayNode features = responsePayload.putArray("features");
        negotiated.stream().sorted().forEach(feature -> features.add(feature.wireName()));
        enqueue(envelope(
                ProtocolMessageKind.RESPONSE, "initialized", request.messageId(),
                request.sessionId(), request.runId(), Optional.empty(), responsePayload), true);
    }

    private void startRun(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), RUN_FIELDS);
        if (!accepting.get()) {
            throw failAccepted(request, "DRAINING");
        }
        AgentRunRequest runRequest = toRunRequest(request.payload());
        synchronized (monitor) {
            if (activeRequestId != null || (runTask != null && !runTask.isDone())) {
                throw failAccepted(request, "RUN_ACTIVE");
            }
            activeRequestId = request.messageId();
            activeRunId = null;
            terminalPublished = false;
        }
        respond(request, "run.accepted", codec.objectNode().put("accepted", true));
        synchronized (monitor) {
            runTask = runs.submit(() -> executeRun(request, runRequest));
        }
    }

    private void executeRun(ProtocolEnvelope request, AgentRunRequest runRequest) {
        try {
            AgentRunResult result = application.run(runRequest, eventSink(request));
            publishTerminal(request, result);
        } catch (Throwable failure) {
            publishInternalTerminal(request);
        } finally {
            synchronized (monitor) {
                activeRequestId = null;
                activeRunId = null;
                monitor.notifyAll();
            }
        }
    }

    private AgentEventSink eventSink(ProtocolEnvelope request) {
        return envelope -> {
            if (closed.get() || !Objects.equals(activeRequestId, request.messageId())) {
                return;
            }
            envelope.runId().ifPresent(id -> {
                synchronized (monitor) {
                    activeRunId = id;
                }
            });
            ObjectNode payload = eventPayload(envelope);
            ProtocolEnvelope event = envelope(
                    ProtocolMessageKind.EVENT, "run.event", request.messageId(),
                    Optional.of(envelope.sessionId().value()),
                    envelope.runId().map(RunId::value), Optional.empty(), payload);
            enqueue(event, false);
        };
    }

    private ObjectNode eventPayload(AgentEventEnvelope envelope) {
        ObjectNode payload = codec.objectNode().put("schema", "cc-java-run-event-v1")
                .put("eventSequence", envelope.sequence());
        if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.ModelTextDelta delta) {
            payload.put("eventType", "assistant.text.delta").put("text", delta.text());
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.LifecycleEvent.RunStarted) {
            payload.put("eventType", "run.started");
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.LifecycleEvent.ModelTurnStarted) {
            payload.put("eventType", "model.turn.started");
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.LifecycleEvent.ModelTurnCompleted) {
            payload.put("eventType", "model.turn.completed");
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.LifecycleEvent.BeforeTool tool) {
            payload.put("eventType", "tool.started").put("toolName", tool.call().name())
                    .put("callId", tool.call().id());
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.LifecycleEvent.AfterTool tool) {
            payload.put("eventType", "tool.completed").put("callId", tool.result().callId())
                    .put("success", tool.result().status()
                            == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS);
        } else {
            payload.put("eventType", "lifecycle.observed");
        }
        return payload;
    }

    private void publishTerminal(ProtocolEnvelope request, AgentRunResult result) {
        synchronized (monitor) {
            if (closed.get() || terminalPublished || !Objects.equals(activeRequestId, request.messageId())) {
                return;
            }
            terminalPublished = true;
        }
        ObjectNode payload = codec.objectNode()
                .put("schema", "cc-java-run-terminal-v1")
                .put("eventType", "assistant.final")
                .put("status", result.status().name())
                .put("stopReason", result.stopReason().name())
                .put("modelTurns", result.modelTurns())
                .put("toolCalls", result.toolCalls());
        result.finalText().ifPresent(text -> payload.put("text", text));
        application.taskBoardSnapshot().ifPresent(board -> {
            long pending = board.tasks().values().stream()
                    .filter(task -> task.status()
                            != io.github.liumaishenjian.ccjava.domain.task.TaskStatus.COMPLETED)
                    .count();
            long recovery = board.tasks().values().stream()
                    .filter(io.github.liumaishenjian.ccjava.domain.task.TaskItemView::recoveryRequired)
                    .count();
            payload.put("pendingTaskCount", pending).put("recoveryTaskCount", recovery);
        });
        enqueue(envelope(
                ProtocolMessageKind.EVENT, "run.terminal", request.messageId(),
                Optional.of(result.sessionId().value()), Optional.of(result.runId().value()),
                Optional.empty(), payload), true);
    }

    private void publishInternalTerminal(ProtocolEnvelope request) {
        synchronized (monitor) {
            if (closed.get() || terminalPublished || !Objects.equals(activeRequestId, request.messageId())) {
                return;
            }
            terminalPublished = true;
        }
        enqueue(envelope(
                ProtocolMessageKind.EVENT, "run.terminal", request.messageId(),
                request.sessionId(), Optional.ofNullable(activeRunId).map(RunId::value), Optional.empty(),
                codec.objectNode().put("schema", "cc-java-run-terminal-v1")
                        .put("eventType", "assistant.final")
                        .put("status", "FAILED").put("stopReason", "INTERNAL_ERROR")), true);
    }

    private void cancelRun(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), CANCEL_FIELDS);
        RunId runId;
        try {
            runId = new RunId(requiredText(request.payload(), "runId", 128));
        } catch (IllegalArgumentException invalid) {
            throw failAccepted(request, "RUN_ID_INVALID");
        }
        boolean cancelled = application.cancel(runId);
        respond(request, "run.cancelled", codec.objectNode().put("cancelled", cancelled));
    }

    private io.github.liumaishenjian.ccjava.sdk.AgentControlApi control(ProtocolEnvelope request)
            throws ProtocolCodecException {
        return application.control().orElseThrow(() -> failAccepted(request, "CONTROL_UNAVAILABLE"));
    }

    private void sessionList(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), SESSION_LIST_FIELDS);
        int offset = nonNegativeInt(request.payload(), "offset", 0);
        int limit = positiveInt(request.payload(), "limit", 100);
        var entries = control(request).listSessions(offset, limit);
        ObjectNode payload = codec.objectNode();
        ArrayNode array = payload.putArray("sessions");
        entries.forEach(entry -> array.add(sessionEntry(entry)));
        respond(request, "session.listed", payload);
    }

    private void sessionSearch(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), SESSION_SEARCH_FIELDS);
        String query = requiredText(request.payload(), "query", 256);
        int limit = positiveInt(request.payload(), "limit", 100);
        ObjectNode payload = codec.objectNode();
        ArrayNode array = payload.putArray("sessions");
        control(request).searchSessions(query, limit).forEach(entry -> array.add(sessionEntry(entry)));
        respond(request, "session.searched", payload);
    }

    private void sessionExport(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), SESSION_EXPORT_FIELDS);
        ObjectNode input = request.payload();
        byte[] exported;
        try {
            exported = control(request).exportSession(
                    requiredText(input, "sessionId", 128),
                    bool(input, "includeContent", false), bool(input, "redacted", false),
                    bool(input, "confirmed", false));
        } catch (IllegalArgumentException invalid) {
            throw failAccepted(request, "EXPORT_POLICY");
        }
        respond(request, "session.exported", codec.objectNode()
                .put("encoding", "base64url")
                .put("data", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(exported)));
    }

    private void sessionRetain(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), SESSION_RETAIN_FIELDS);
        ObjectNode input = request.payload();
        try {
            var result = control(request).retainSession(requiredText(input, "sessionId", 128),
                    io.github.liumaishenjian.ccjava.core.session.RetentionAction.valueOf(
                            requiredText(input, "action", 64)),
                    bool(input, "firstConfirmation", false), bool(input, "secondConfirmation", false));
            respond(request, "session.retained", codec.objectNode()
                    .put("success", result.success()).put("status", result.status()));
        } catch (IllegalArgumentException invalid) {
            throw failAccepted(request, "RETENTION_INVALID");
        }
    }

    private void sessionMigrate(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), SESSION_MIGRATE_FIELDS);
        ObjectNode input = request.payload();
        try {
            var result = control(request).migrateSession(
                    requiredText(input, "sourceFile", 128), requiredText(input, "targetFile", 128),
                    positiveInt(input, "fromMajor", 1), positiveInt(input, "toMajor", 2));
            respond(request, "session.migrated", codec.objectNode().put("success", result.success())
                    .put("status", result.status()).put("records", result.records()));
        } catch (IllegalArgumentException invalid) {
            throw failAccepted(request, "MIGRATION_INVALID");
        }
    }

    private void governance(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), EMPTY_FIELDS);
        var view = control(request).governance();
        ObjectNode payload = codec.objectNode().put("status", view.status()).put("usingLkg", view.usingLkg());
        ArrayNode stable = payload.putArray("stableEnabled"); view.stableEnabled().forEach(stable::add);
        ArrayNode experimental = payload.putArray("experimentalEnabled"); view.experimentalEnabled().forEach(experimental::add);
        respond(request, "governance.current", payload);
    }

    private void taskSnapshot(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), TASK_SNAPSHOT_FIELDS);
        int limit = positiveInt(request.payload(), "limit", 25);
        if (limit > 50) throw failAccepted(request, "TASK_LIMIT_INVALID");
        long cursor = 0;
        JsonNode rawCursor = request.payload().get("cursor");
        if (rawCursor != null && !rawCursor.isNull()) {
            if (!rawCursor.isTextual() || !rawCursor.asText().matches("task-[1-9][0-9]*")) {
                throw failAccepted(request, "TASK_CURSOR_INVALID");
            }
            try { cursor = Long.parseLong(rawCursor.asText().substring(5)); }
            catch (NumberFormatException invalid) { throw failAccepted(request, "TASK_CURSOR_INVALID"); }
        }
        var snapshot = application.taskBoardSnapshot()
                .orElseThrow(() -> failAccepted(request, "TASK_BOARD_UNAVAILABLE"));
        ObjectNode payload = codec.objectNode().put("schema", "task-list-v1")
                .put("boardRevision", snapshot.revision()).put("totalTasks", snapshot.tasks().size());
        ArrayNode tasks = payload.putArray("tasks");
        long last = cursor;
        boolean hasMore = false;
        for (var entry : snapshot.tasks().entrySet()) {
            if (entry.getKey().sequence() <= cursor) continue;
            if (tasks.size() >= limit) { hasMore = true; break; }
            var task = entry.getValue();
            ObjectNode item = codec.objectNode().put("taskId", task.id().value())
                    .put("revision", task.revision()).put("status", task.status().name())
                    .put("subject", task.subject()).put("blocked", task.blocked())
                    .put("recoveryRequired", task.recoveryRequired());
            task.owner().ifPresent(owner -> item.put("owner", owner.value()));
            task.activeForm().ifPresent(value -> item.put("activeForm", value));
            ArrayNode blockers = item.putArray("blockerIds");
            task.activeBlockers().forEach(blocker -> blockers.add(blocker.value()));
            tasks.add(item);
            last = task.id().sequence();
        }
        payload.put("hasMore", hasMore);
        if (hasMore) payload.put("nextCursor", "task-" + last);
        respond(request, "task.snapshot", payload);
    }

    private ObjectNode sessionEntry(io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry entry) {
        return codec.objectNode().put("sessionId", entry.sessionId())
                .put("workspaceIdentity", entry.workspaceIdentity()).put("displayName", entry.displayName())
                .put("updatedAt", entry.updatedAt().toString()).put("status", entry.status().name());
    }

    private void drain(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), EMPTY_FIELDS);
        accepting.set(false);
        connection.beginDrain();
        application.beginDrain();
        respond(request, "draining", codec.objectNode().put("draining", true));
    }

    private void shutdown(ProtocolEnvelope request) throws ProtocolCodecException {
        rejectUnknown(request.payload(), EMPTY_FIELDS);
        accepting.set(false);
        application.beginDrain();
        respond(request, "shutdown.accepted", codec.objectNode().put("accepted", true));
        close();
    }

    private void respond(ProtocolEnvelope request, String type, ObjectNode payload)
            throws ProtocolCodecException {
        ProtocolEnvelope response = envelope(
                ProtocolMessageKind.RESPONSE, type, request.messageId(), request.sessionId(), request.runId(),
                Optional.empty(), payload);
        connection.recordResponse(request, response);
        synchronized (monitor) {
            completedResponses.put(response.messageId(), response);
            while (completedResponses.size() > 1024) {
                completedResponses.remove(completedResponses.keySet().iterator().next());
            }
        }
        enqueue(response, true);
    }

    private ProtocolCodecException failAccepted(ProtocolEnvelope request, String code) {
        try {
            ProtocolEnvelope error = envelope(
                    ProtocolMessageKind.ERROR, "request.error", request.messageId(),
                    request.sessionId(), request.runId(), Optional.empty(), codec.objectNode().put("code", code));
            connection.recordResponse(request, errorAsResponse(error));
            enqueue(error, true);
        } catch (ProtocolCodecException ignored) {
            // 原始连接错误优先；不得以第二个失败覆盖。
        }
        return new ProtocolCodecException(code);
    }

    private ProtocolEnvelope errorAsResponse(ProtocolEnvelope error) {
        return new ProtocolEnvelope(
                error.version(), ProtocolMessageKind.RESPONSE, error.type(), error.messageId(),
                error.correlationId(), error.sessionId(), error.runId(), error.sequence(),
                error.idempotencyKey(), error.payload());
    }

    private ProtocolEnvelope copyForReplay(ProtocolEnvelope cached, ProtocolEnvelope request) {
        return envelope(
                cached.kind(), cached.type(), request.messageId(), request.sessionId(), request.runId(),
                request.idempotencyKey(), cached.payload());
    }

    private ProtocolEnvelope envelope(
            ProtocolMessageKind kind,
            String type,
            String correlation,
            Optional<String> sessionId,
            Optional<String> runId,
            Optional<String> idempotency,
            ObjectNode payload) {
        long sequence;
        synchronized (monitor) {
            sequence = ++outboundSequence;
        }
        return new ProtocolEnvelope(
                ProtocolVersion.V1_0, kind, type, UUID.randomUUID().toString(), correlation,
                sessionId, runId, sequence, idempotency, payload);
    }

    private void enqueue(ProtocolEnvelope envelope, boolean priority) {
        synchronized (monitor) {
            if (closed.get() && !priority) {
                return;
            }
            if (egress.size() >= MAX_EGRESS_MESSAGES) {
                if (priority) {
                    ProtocolEnvelope removable = egress.stream()
                            .filter(candidate -> candidate.kind() == ProtocolMessageKind.EVENT
                                    && !"run.terminal".equals(candidate.type()))
                            .findFirst().orElse(null);
                    if (removable != null) {
                        egress.remove(removable);
                    } else {
                        throw new IllegalStateException("EGRESS_TERMINAL_BACKPRESSURE");
                    }
                } else {
                    return;
                }
            }
            egress.addLast(envelope);
            monitor.notifyAll();
        }
    }

    private AgentRunRequest toRunRequest(ObjectNode payload) throws ProtocolCodecException {
        String prompt = requiredText(payload, "prompt", MAX_PROMPT_CHARS);
        int turns = positiveInt(payload, "maxModelTurns", AgentLimits.DEFAULT.totalModelTurns().orElseThrow());
        int tools = positiveInt(payload, "maxToolCalls", AgentLimits.DEFAULT.totalToolCalls().orElseThrow());
        long millis = positiveLong(payload, "timeoutMillis", AgentLimits.DEFAULT.runDeadline().orElseThrow().toMillis());
        try {
            return new AgentRunRequest(
                    new UserMessage(prompt), new AgentLimits(turns, tools, Duration.ofMillis(millis)),
                    Optional.empty());
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolCodecException("RUN_LIMIT_INVALID");
        }
    }

    private static Set<ProtocolFeature> parseFeatures(JsonNode value) throws ProtocolCodecException {
        if (value == null || !value.isArray() || value.size() > ProtocolFeature.values().length) {
            throw new ProtocolCodecException("FEATURES");
        }
        EnumSet<ProtocolFeature> result = EnumSet.noneOf(ProtocolFeature.class);
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new ProtocolCodecException("FEATURES");
            }
            try {
                if (!result.add(ProtocolFeature.fromWireName(item.asText()))) {
                    throw new ProtocolCodecException("FEATURES_DUPLICATE");
                }
            } catch (IllegalArgumentException unknown) {
                throw new ProtocolCodecException("FEATURE_UNKNOWN");
            }
        }
        return Set.copyOf(result);
    }

    private static ProtocolVersion parseVersion(String value) throws ProtocolCodecException {
        if (!"1.0".equals(value)) {
            throw new ProtocolCodecException("VERSION");
        }
        return ProtocolVersion.V1_0;
    }

    private static void rejectUnknown(ObjectNode payload, Set<String> allowed)
            throws ProtocolCodecException {
        List<String> unknown = new ArrayList<>();
        payload.propertyNames().forEach(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new ProtocolCodecException("UNKNOWN_PAYLOAD_FIELD");
        }
    }

    private static String requiredText(JsonNode payload, String field, int max)
            throws ProtocolCodecException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > max) {
            throw new ProtocolCodecException("FIELD_" + field);
        }
        return value.asText();
    }

    private static int positiveInt(JsonNode payload, String field, int fallback)
            throws ProtocolCodecException {
        JsonNode value = payload.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new ProtocolCodecException("FIELD_" + field);
        }
        return value.intValue();
    }

    private static int nonNegativeInt(JsonNode payload, String field, int fallback)
            throws ProtocolCodecException {
        JsonNode value = payload.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new ProtocolCodecException("FIELD_" + field);
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode payload, String field, boolean fallback)
            throws ProtocolCodecException {
        JsonNode value = payload.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new ProtocolCodecException("FIELD_" + field);
        return value.booleanValue();
    }

    private static long positiveLong(JsonNode payload, String field, long fallback)
            throws ProtocolCodecException {
        JsonNode value = payload.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new ProtocolCodecException("FIELD_" + field);
        }
        return value.longValue();
    }

    /** 断连 fence、取消、drain 与资源清理；幂等。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        accepting.set(false);
        connection.beginDrain();
        application.beginDrain();
        RunId run;
        synchronized (monitor) {
            run = activeRunId;
            monitor.notifyAll();
        }
        if (run != null) {
            application.cancel(run);
        }
        application.awaitTermination(Duration.ofSeconds(5));
        connection.close();
        runs.shutdownNow();
    }

    private static long saturatedDeadline(Duration timeout) {
        try {
            return Math.addExact(System.nanoTime(), timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
