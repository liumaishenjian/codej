package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelTurnTelemetry;
import io.github.liumaishenjian.ccjava.core.RunTelemetry;
import io.github.liumaishenjian.ccjava.core.ToolCallTelemetry;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.cli.runtime.DoctorReportService;
import io.github.liumaishenjian.ccjava.cli.runtime.SessionCommandDispatcher;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.command.CommandId;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandIntent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResult;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * 把 stdio v0 命令适配到真实 {@link HeadlessRuntimeSession}。
 *
 * <p>该类型只管理单连接的 Session/Run 状态和事件映射。模型循环、规范消息历史、
 * Tool Pipeline、取消与终态仍由 Core 拥有；Tool 进度只投影固定白名单中的有界活动摘要，
 * 不发送参数对象、正文型字段、绝对路径或原始异常。</p>
 *
 * @since 0.1.0
 */
public final class RuntimeStdioCommandHandler
        implements StdioProtocol.CommandHandler, AgentEventSink {

    /**
     * 在 stdio 的事件出口与审批协调器都就绪后创建生产 Runtime。
     *
     * <p>该工厂避免先创建一个绑定拒绝型 ApprovalHandler 的 Session，再在外层创建一个
     * 永远不会被 Runtime 调用的交互审批器。返回的 Session 必须把两个参数原样接入其
     * EventSink 与 ApprovalHandler。</p>
     *
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface RuntimeApplicationFactory {
        /**
         * 创建当前 stdio 连接独占的 Runtime Session。
         *
         * @param events 当前连接的唯一事件出口
         * @param approvals 当前连接的交互审批端口
         * @return 尚未打开、由 Handler 持有生命周期的 Session
         */
        HeadlessRuntimeSession create(
                AgentEventSink events,
                io.github.liumaishenjian.ccjava.core.ApprovalHandler approvals);
    }

    /** 当前连接允许保留的未发送 steering 数量。 */
    static final int MAX_STEERING_MESSAGES = 100;
    static final int MAX_EXPANDED_INPUT_BYTES = 1_048_576;
    static final int MAX_INPUT_CHUNKS = 64;
    static final Duration INPUT_ASSEMBLY_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_INPUT_TOMBSTONES = 256;
    /** {@code file.suggestions} 单条事件 payload 的 UTF-8 预算。 */
    static final int MAX_SUGGESTION_EVENT_BYTES = 8_192;

    private final Object lock = new Object();
    private final StdioProtocolCodec codec = new StdioProtocolCodec();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("cc-java-runtime-run").daemon(true).factory());
    private final InputAssemblyScheduler assemblyScheduler;
    private final Clock clock;
    private final StdioApprovalCoordinator approvals;
    private final StdioQuestionCoordinator questions;
    private final HeadlessRuntimeSession application;
    private final io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService providerAuth;
    private final Deque<QueuedSteering> steeringQueue = new ArrayDeque<>();
    private State state = State.NEW;
    private ActiveRun activeRun;
    private SessionCommandDispatcher commandDispatcher;
    /** 仅记录 dispatcher 已接受的有限 commandId，拒绝预算外新 ID 后立即关闭连接。 */
    private final Set<CommandId> emittedCommandResults = new HashSet<>();
    private boolean commandRequestBudgetExhausted;
    private final LinkedHashMap<String, InputTerminal> inputTombstones = new LinkedHashMap<>();
    private InputAssembly inputAssembly;
    private ExpiryHandle inputExpiry;
    private io.github.liumaishenjian.ccjava.cli.mentions.FileMentionService fileMentions;
    private io.github.liumaishenjian.ccjava.cli.mentions.FileSuggestionService fileSuggestions;

    /**
     * 使用已校验的本地 Provider 设置装配 Headless Runtime。
     *
     * @param settings 不得记录或持久化的 Provider 设置
     */
    public RuntimeStdioCommandHandler(OpenAiCompatibleSettings settings) {
        this(
                settings,
                Path.of("").toAbsolutePath().normalize(),
                io.github.liumaishenjian.ccjava.domain.AgentLimits.DEFAULT.maxDuration());
    }

    /**
     * 使用 CLI 已解析的 Workspace 与墙钟限制装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout) {
        this(settings, workspace, timeout, PermissionMode.DEFAULT);
    }

    /**
     * 使用显式 S05 Permission Mode 装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Headless Session 的权限模式
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode) {
        this(settings, workspace, timeout, permissionMode, SessionOpenRequest.create());
    }

    /**
     * 使用 CLI 已解析的持久 Session 选择装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param sessionOpenRequest Create/Continue/Resume/Fork 选择
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest) {
        this(
                settings,
                workspace,
                timeout,
                permissionMode,
                sessionOpenRequest,
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty());
    }

    /**
     * 使用可选显式 S07 启动容量装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param sessionOpenRequest Create/Continue/Resume/Fork 选择
     * @param contextPreparation 可信 CLI 容量元组；空表示不启用 Projection
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest,
            Optional<ContextPreparationConfig> contextPreparation) {
        this(settings, workspace, timeout, permissionMode, sessionOpenRequest,
                contextPreparation, ModelDiagnosticMode.OFF, Optional.empty());
    }

    /**
     * 使用默认 Local/platform execution 配置的兼容构造器。
     *
     * @param settings Provider 设置
     * @param workspace 已解析 Workspace
     * @param timeout Run 墙钟限制
     * @param permissionMode 权限模式
     * @param sessionOpenRequest Session 选择
     * @param contextPreparation Context 配置
     * @param diagnosticMode 模型诊断模式
     * @param diagnosticDirectory 可选可信目录
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest,
            Optional<ContextPreparationConfig> contextPreparation,
            ModelDiagnosticMode diagnosticMode,
            Optional<Path> diagnosticDirectory) {
        this(
                settings,
                workspace,
                timeout,
                permissionMode,
                sessionOpenRequest,
                contextPreparation,
                diagnosticMode,
                diagnosticDirectory,
                io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference.LOCAL,
                platformShell());
    }

    /**
     * 使用显式本机诊断配置装配 Headless Runtime；目录不会进入 stdio 事件。
     *
     * @param settings Provider 设置
     * @param workspace 已解析 Workspace
     * @param timeout Run 墙钟限制
     * @param permissionMode 权限模式
     * @param sessionOpenRequest Session 选择
     * @param contextPreparation Context 配置
     * @param diagnosticMode 模型诊断模式
     * @param diagnosticDirectory 可选可信目录
     * @param executionBackend 显式后端偏好
     * @param executionShell 后端必须执行的命令语义
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest,
            Optional<ContextPreparationConfig> contextPreparation,
            ModelDiagnosticMode diagnosticMode,
            Optional<Path> diagnosticDirectory,
            io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference executionBackend,
            io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell executionShell) {
        clock = Clock.systemUTC();
        assemblyScheduler = InputAssemblyScheduler.production();
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        questions = new StdioQuestionCoordinator(this::emitUserQuestion);
        providerAuth = null;
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(settings, "settings 不能为空"),
                this,
                new HeadlessRuntimeOptions(
                        workspace,
                        settings.model(),
                        timeout,
                        permissionMode,
                        java.util.List.of(),
                        Objects.requireNonNull(sessionOpenRequest, "sessionOpenRequest 不能为空"),
                        SessionStorage.defaultRoot(),
                        Objects.requireNonNull(contextPreparation, "contextPreparation 不能为空"),
                        Objects.requireNonNull(diagnosticMode, "diagnosticMode 不能为空"),
                        Objects.requireNonNull(diagnosticDirectory, "diagnosticDirectory 不能为空"),
                        Objects.requireNonNull(executionBackend, "executionBackend 不能为空"),
                        Objects.requireNonNull(executionShell, "executionShell 不能为空")),
                approvals);
    }

    /**
     * 同时接入 Provider/Auth 服务，使 stdio/TUI 消费结构化本地控制面结果。
     *
     * @param selectedApplication 已完成 Provider 选择装配的生产 Session
     * @param providerAuth Provider/Auth 本地控制面服务
     */
    public RuntimeStdioCommandHandler(HeadlessRuntimeSession selectedApplication,
                                      io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService providerAuth) {
        clock = Clock.systemUTC(); assemblyScheduler = InputAssemblyScheduler.production();
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        questions = new StdioQuestionCoordinator(this::emitUserQuestion);
        application = Objects.requireNonNull(selectedApplication, "selectedApplication 不能为空");
        this.providerAuth = Objects.requireNonNull(providerAuth, "providerAuth 不能为空");
    }

    /**
     * 使用当前连接的真实事件出口和审批协调器装配生产 Session。
     *
     * @param applicationFactory 必须把收到的 events/approvals 接入 Session
     * @param providerAuth Provider/Auth 本地控制面服务
     */
    public RuntimeStdioCommandHandler(
            RuntimeApplicationFactory applicationFactory,
            io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService providerAuth) {
        clock = Clock.systemUTC();
        assemblyScheduler = InputAssemblyScheduler.production();
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        questions = new StdioQuestionCoordinator(this::emitUserQuestion);
        application = Objects.requireNonNull(applicationFactory, "applicationFactory 不能为空")
                .create(this, approvals);
        if (application == null) {
            throw new IllegalArgumentException("applicationFactory 返回 null");
        }
        this.providerAuth = Objects.requireNonNull(providerAuth, "providerAuth 不能为空");
    }

    /**
     * 使用可交互审批工厂装配无 Provider 控制面的测试/嵌入式 Session。
     *
     * @param applicationFactory 必须把收到的 events/approvals 接入 Session
     */
    public RuntimeStdioCommandHandler(RuntimeApplicationFactory applicationFactory) {
        clock = Clock.systemUTC();
        assemblyScheduler = InputAssemblyScheduler.production();
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        questions = new StdioQuestionCoordinator(this::emitUserQuestion);
        application = Objects.requireNonNull(applicationFactory, "applicationFactory 不能为空")
                .create(this, approvals);
        if (application == null) {
            throw new IllegalArgumentException("applicationFactory 返回 null");
        }
        providerAuth = null;
    }
    /**
     * 使用已经接入 ProviderAuthRuntimeResources 的生产 Session 装配 stdio handler。
     *
     * @param selectedApplication 已接入 Provider/Auth 运行时资源的生产 Session
     */
    public RuntimeStdioCommandHandler(HeadlessRuntimeSession selectedApplication) {
        clock = Clock.systemUTC();
        assemblyScheduler = InputAssemblyScheduler.production();
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        questions = new StdioQuestionCoordinator(this::emitUserQuestion);
        application = Objects.requireNonNull(selectedApplication, "selectedApplication 不能为空");
        providerAuth = null;
    }
    /**
     * 使用 Fake Model 装配真实 Runtime/stdio Adapter，供确定性契约测试使用。
     *
     * @param model 不访问网络的模型端口
     */
    RuntimeStdioCommandHandler(ModelGateway model) {
        this(
                model,
                new HeadlessRuntimeOptions(
                        Path.of("").toAbsolutePath().normalize(),
                        "fake-model",
                        io.github.liumaishenjian.ccjava.domain.AgentLimits.DEFAULT.maxDuration()));
    }

    /**
     * 使用 Fake Model 和显式 Workspace 装配真实 Runtime/stdio Adapter。
     *
     * @param model 不访问网络的模型端口
     * @param options 测试 Workspace 与墙钟配置
     */
    RuntimeStdioCommandHandler(
            ModelGateway model,
            HeadlessRuntimeOptions options) {
        this(model, options, Clock.systemUTC(), InputAssemblyScheduler.production());
    }

    RuntimeStdioCommandHandler(
            ModelGateway model,
            HeadlessRuntimeOptions options,
            Clock clock,
            InputAssemblyScheduler assemblyScheduler) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.assemblyScheduler = Objects.requireNonNull(assemblyScheduler, "assemblyScheduler 不能为空");
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        questions = new StdioQuestionCoordinator(this::emitUserQuestion);
        providerAuth = null;
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(model, "model 不能为空"),
                this,
                Objects.requireNonNull(options, "options 不能为空"),
                approvals);
    }

    @Override
    public StdioProtocol.Disposition handle(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        return switch (command.type()) {
            case "initialize" -> initialize(command, events);
            case "run.start" -> startRun(command, events);
            case "plan.start" -> startPlan(command, events);
            case "plan.review.resolve" -> resolvePlanReview(command, events);
            case "plan.execute" -> executeApprovedPlan(command, events);
            case "plan.feedback" -> returnPlanFeedback(command, events);
            case "input.begin" -> beginInput(command);
            case "input.chunk" -> appendInputChunk(command);
            case "input.commit" -> commitInput(command, events);
            case "run.cancel" -> cancelRun(command);
            case "approval.resolve" -> resolveApproval(command);
            case "question.resolve" -> resolveQuestion(command);
            case "checkpoint.list" -> listCheckpoints(command, events);
            case "checkpoint.diff" -> checkpointDiff(command, events);
            case "checkpoint.undo" -> checkpointUndo(command, events);
            case "session.command" -> sessionCommand(command, events);
            case "provider.control" -> providerControl(command, events);
            case "skill.invoke" -> invokeSkill(command, events);
            case "task.inspect" -> inspectTask(command, events);
            case "task.wait" -> waitTask(command, events);
            case "task.cancel" -> cancelTask(command, events);
            case "task.keep" -> keepTaskWorktree(command, events);
            case "task.remove" -> removeTaskWorktree(command, events);
            case "file.suggest" -> suggestFiles(command, events);
            case "shutdown" -> shutdown();
            default -> throw protocolError(
                    "UNKNOWN_COMMAND",
                    command,
                    "不支持该命令");
        };
    }

    private StdioProtocol.Disposition initialize(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.NEW, command);
            if (command.sessionId().isPresent() || command.runId().isPresent()) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "initialize 不能携带 Session 或 Run");
            }
            application.setChildTaskObserver(report -> emitBackgroundTaskTerminal(events, report));
            application.installUserQuestionHandler(questions);
            application.open();
            fileMentions = new io.github.liumaishenjian.ccjava.cli.mentions.FileMentionService(
                    application.workspaceGuard());
            fileSuggestions = new io.github.liumaishenjian.ccjava.cli.mentions.FileSuggestionService(
                    application.workspaceGuard());
            state = State.READY;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("protocolVersion", StdioProtocol.VERSION);
        var sessionOpen = application.sessionOpenResult();
        payload.put("openMode", sessionOpen.mode().name().toLowerCase(Locale.ROOT));
        payload.put("readOnly", sessionOpen.readOnly());
        payload.put("modelConfigured", modelConfigured());
        sessionOpen.parentSessionId().ifPresent(parent ->
                payload.put("parentSessionId", parent.value()));
        ArrayNode warnings = codec.arrayNode();
        sessionOpen.issues().forEach(issue ->
                warnings.add(issue.kind().name().toLowerCase(Locale.ROOT)));
        payload.set("warnings", warnings);
        events.emit(
                "initialized",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    /** 本机 Provider/Auth 状态损坏时只触发重新配置，不让初始化泄漏底层失败。 */
    private boolean modelConfigured() {
        if (providerAuth == null) return false;
        try {
            return providerAuth.hasUsableDefaultSelection(CancellationToken.none());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private StdioProtocol.Disposition startRun(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String prompt = requiredPrompt(command);
        return startAcceptedInput(command, events, prompt);
    }

    /**
     * 以自然语言任务启动独立的只读 Plan Runtime。
     *
     * <p>该命令不依赖可变 Permission overlay，也不接受 workspace digest 或结构化步骤；
     * Java 在真实 Run 内生成提案和摘要，并继续拥有 Session、事件与终态。</p>
     */
    private StdioProtocol.Disposition startPlan(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        return startPlan(command, events, "plan.start");
    }

    private StdioProtocol.Disposition startPlan(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events,
            String commandType) throws StdioProtocolException {
        String task = requiredPrompt(command);
        ActiveRun run;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            run = startRunLocked(command.requestId(), task.length(), events);
            run.suppressModelText = true;
        }
        submitAcceptedRun(command, run, events, commandType, () -> executePlanRun(run, task));
        return StdioProtocol.Disposition.CONTINUE;
    }

    /** 将匹配的 durable review revision 退回 DRAFT；正文不经协议往返。 */
    private StdioProtocol.Disposition returnPlanFeedback(
            StdioProtocol.Command command, StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String planId;
        long revision;
        String digest;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            JsonNode rawPlanId = command.payload().get("planId");
            JsonNode rawRevision = command.payload().get("revision");
            JsonNode rawDigest = command.payload().get("contentDigest");
            if (command.payload().size() != 3 || rawPlanId == null || !rawPlanId.isString()
                    || rawPlanId.stringValue().isBlank() || rawPlanId.stringValue().length() > 128
                    || rawRevision == null || !rawRevision.isIntegralNumber() || rawRevision.longValue() < 1
                    || rawDigest == null || !rawDigest.isString()
                    || !rawDigest.stringValue().matches("[0-9a-f]{64}")) {
                throw protocolError("INVALID_PAYLOAD", command, "plan.feedback 绑定参数无效");
            }
            planId = rawPlanId.stringValue();
            revision = rawRevision.longValue();
            digest = rawDigest.stringValue();
        }
        var draft = application.returnPlanForFeedback(planId, revision, digest)
                .orElseThrow(() -> protocolError("STALE_PLAN_REVIEW", command,
                        "Plan review 已变化或不再等待反馈"));
        ObjectNode payload = codec.objectNode();
        payload.put("planId", draft.planId());
        payload.put("status", "draft");
        payload.put("revision", draft.revision());
        payload.put("contentDigest", draft.contentDigest());
        events.emit("plan.feedback.accepted", command.requestId(),
                Optional.of(application.sessionId().value()), Optional.empty(), payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    /**
     * 原子收敛 durable review 决定；批准时同一次命令可靠提交 APPROVED 并接受执行 Run。
     *
     * <p>命令成功返回只表示执行已被服务端 executor 接受，而不是已经完成。worker 在
     * {@code plan.execution.accepted} 成功进入事件出口前受一次性闸门阻塞，因此该事件确定早于
     * {@code run.started}；入队、传输或关闭失败会释放尚未开始的句柄并保持 APPROVED，供显式恢复，
     * 不会伪造 EXECUTING 或 COMPLETED。</p>
     */
    private StdioProtocol.Disposition resolvePlanReview(
            StdioProtocol.Command command, StdioProtocol.EventEmitter events) throws StdioProtocolException {
        requirePlanReviewPayload(command);
        String decisionText = command.payload().get("decision").stringValue();
        io.github.liumaishenjian.ccjava.domain.PlanReviewDecision decision;
        io.github.liumaishenjian.ccjava.domain.PlanContextPolicy contextPolicy;
        try {
            decision = io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.valueOf(decisionText);
            contextPolicy = io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.valueOf(
                    command.payload().get("contextPolicy").stringValue());
        } catch (RuntimeException invalid) {
            throw protocolError("INVALID_PAYLOAD", command, "Plan review 决定无效");
        }
        String planId = command.payload().get("planId").stringValue();
        long revision = command.payload().get("revision").longValue();
        String contentDigest = command.payload().get("contentDigest").stringValue();
        String workspaceDigest = command.payload().get("workspaceDigest").stringValue();
        String feedback = command.payload().get("feedback").stringValue();
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
        }
        if (decision == io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.CONTINUE_PLANNING) {
            var draft = application.returnPlanForFeedback(planId, revision, contentDigest)
                    .orElseThrow(() -> protocolError("STALE_PLAN_REVIEW", command, "Plan review 已变化"));
            ObjectNode payload = codec.objectNode();
            payload.put("planId", draft.planId()); payload.put("status", "draft");
            payload.put("revision", draft.revision()); payload.put("contentDigest", draft.contentDigest());
            events.emit("plan.feedback.accepted", command.requestId(), Optional.of(application.sessionId().value()),
                    Optional.empty(), payload);
            if (!feedback.isBlank()) {
                StdioProtocol.Command planCommand = new StdioProtocol.Command(command.version(), "plan.start",
                        command.requestId(), command.sessionId(), Optional.empty(), command.sequence(),
                        codec.objectNode().put("prompt", feedback));
                return startPlan(planCommand, events, "plan.review.resolve");
            }
            return StdioProtocol.Disposition.CONTINUE;
        }
        if (decision == io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.REJECT) {
            var rejected = application.rejectDurablePlan(planId, revision, contentDigest)
                    .orElseThrow(() -> protocolError("STALE_PLAN_REVIEW", command, "Plan review 已变化"));
            ObjectNode payload = codec.objectNode();
            payload.put("planId", rejected.planId()); payload.put("status", "rejected");
            events.emit("plan.review.rejected", command.requestId(), Optional.of(application.sessionId().value()),
                    Optional.empty(), payload);
            return StdioProtocol.Disposition.CONTINUE;
        }
        HeadlessRuntimeSession.PlanExecutionAcceptance acceptance;
        try {
            acceptance = application.acceptPlanExecution(planId, revision, contentDigest, workspaceDigest,
                    decision, contextPolicy, feedback);
        } catch (RuntimeException stale) {
            throw protocolError("STALE_PLAN_REVIEW", command, "Plan revision、摘要、状态或工作区已变化");
        }
        ActiveRun run;
        synchronized (lock) {
            run = startAcceptedPlanRunLocked(command.requestId(), events, acceptance);
        }
        try {
            executor.submit(() -> {
                if (run.commandStart.awaitStart()) {
                    executeAcceptedPlanRun(run);
                }
            });
        } catch (RuntimeException enqueueFailure) {
            synchronized (lock) {
                releasePendingAcceptedPlanLocked(run);
                if (activeRun == run) { activeRun = null; state = State.READY; }
            }
            throw protocolError("PLAN_ENQUEUE_FAILED", command, "Plan 已批准但执行未入队，可显式恢复");
        }
        ObjectNode accepted = codec.objectNode();
        accepted.put("planId", planId); accepted.put("status", "approved");
        accepted.put("revision", acceptance.brief().approvedRevision());
        accepted.put("contentDigest", contentDigest);
        accepted.put("contextPolicy", contextPolicy.name().toLowerCase(Locale.ROOT));
        accepted.put("approvalReviewer", acceptance.brief().approvalReviewer().name().toLowerCase(Locale.ROOT));
        try {
            emitRunCommandResult(events, command.requestId(), "plan.review.resolve", "accepted", "ACCEPTED", 0);
            events.emit("plan.execution.accepted", command.requestId(), Optional.of(application.sessionId().value()),
                    Optional.empty(), accepted);
        } catch (RuntimeException transportFailure) {
            synchronized (lock) {
                closeForTransportFailureLocked();
            }
            throw new AcceptedRunTransportException(transportFailure);
        }
        return run.commandStart.start()
                ? StdioProtocol.Disposition.CONTINUE
                : StdioProtocol.Disposition.SHUTDOWN;
    }

    private ActiveRun startAcceptedPlanRunLocked(String requestId, StdioProtocol.EventEmitter events,
            HeadlessRuntimeSession.PlanExecutionAcceptance acceptance) {
        ActiveRun run = new ActiveRun(requestId, 0, events);
        run.approvedPlanExecution = true;
        run.suppressModelText = true;
        run.planAcceptance = acceptance;
        activeRun = run;
        state = State.RUNNING;
        return run;
    }

    private void requirePlanReviewPayload(StdioProtocol.Command command) throws StdioProtocolException {
        Set<String> fields = Set.of("planId", "revision", "contentDigest", "workspaceDigest", "decision",
                "contextPolicy", "feedback");
        JsonNode planId = command.payload().get("planId");
        JsonNode revision = command.payload().get("revision");
        JsonNode contentDigest = command.payload().get("contentDigest");
        JsonNode workspaceDigest = command.payload().get("workspaceDigest");
        JsonNode decision = command.payload().get("decision");
        JsonNode context = command.payload().get("contextPolicy");
        JsonNode feedback = command.payload().get("feedback");
        if (command.payload().properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))
                || command.payload().size() != fields.size() || planId == null || !planId.isString()
                || planId.stringValue().isBlank() || planId.stringValue().length() > 128
                || revision == null || !revision.isIntegralNumber() || revision.longValue() < 1
                || contentDigest == null || !contentDigest.isString()
                || !contentDigest.stringValue().matches("[0-9a-f]{64}")
                || workspaceDigest == null || !workspaceDigest.isString()
                || !workspaceDigest.stringValue().matches("[0-9a-f]{64}")
                || decision == null || !decision.isString() || context == null || !context.isString()
                || feedback == null || !feedback.isString()
                || feedback.stringValue().codePointCount(0, feedback.stringValue().length()) > 8_192) {
            throw protocolError("INVALID_PAYLOAD", command, "Plan review payload 无效");
        }
    }

    /** 隐藏兼容入口：durable review 不再允许通过 plan.execute 触发第二次用户动作。 */
    private StdioProtocol.Disposition executeApprovedPlan(
            StdioProtocol.Command command, StdioProtocol.EventEmitter events) throws StdioProtocolException {
        throw protocolError("LEGACY_PLAN_EXECUTION_DISABLED", command,
                "durable Plan 必须使用单次 plan.review.resolve 原子交接");
    }

    /** 将 TUI 的类型化 Skill 命令启动为普通 Run；Java 仍生成 Run ID 并拥有终态。 */
    private StdioProtocol.Disposition invokeSkill(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String name;
        String arguments;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            JsonNode rawName = command.payload().get("name");
            JsonNode rawArguments = command.payload().get("arguments");
            if (rawName == null || !rawName.isString()
                    || rawArguments == null || !rawArguments.isString()) {
                throw protocolError("INVALID_PAYLOAD", command, "skill.invoke 参数无效");
            }
            name = rawName.stringValue();
            arguments = rawArguments.stringValue();
        }
        io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation invocation;
        try {
            invocation = new io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation(
                    new io.github.liumaishenjian.ccjava.domain.skill.SkillId(name), arguments);
        } catch (IllegalArgumentException invalid) {
            throw protocolError("INVALID_PAYLOAD", command, "skill.invoke 参数无效");
        }
        ActiveRun run;
        synchronized (lock) {
            run = startRunLocked(command.requestId(), arguments.length(), events);
        }
        ObjectNode invoked = codec.objectNode();
        invoked.put("skillId", name);
        invoked.put("invocationKind", "explicit");
        var accepted = invocation;
        submitAcceptedRun(command, run, events, "skill.invoke", () ->
                events.emit("skill.invoked", command.requestId(), Optional.of(application.sessionId().value()),
                        Optional.empty(), invoked), () -> executeSkillRun(run, accepted));
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition startAcceptedInput(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events,
            String prompt) throws StdioProtocolException {
        ActiveRun run;
        io.github.liumaishenjian.ccjava.domain.UserMessage message;
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            if (command.runId().isPresent()) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.start 的 Run ID 必须由 Java 生成");
            }
            // 显式文件提及必须在创建 Run、写 Session 或请求模型之前完成权威校验。
            try {
                message = fileMentions.resolve(prompt);
            } catch (io.github.liumaishenjian.ccjava.cli.mentions.FileMentionException invalid) {
                throw protocolError(
                        io.github.liumaishenjian.ccjava.cli.mentions.FileMentionException.CODE,
                        command,
                        "显式文件提及无法安全解析");
            }
            if (state == State.RUNNING) {
                if (steeringQueue.size() >= MAX_STEERING_MESSAGES) {
                    throw protocolError("STEERING_QUEUE_FULL", command, "steering 队列已满");
                }
                QueuedSteering steering = new QueuedSteering(
                        command.requestId(), application.sessionId().value(), message, events);
                steeringQueue.addLast(steering);
                try {
                    emitRunCommandResult(events, command.requestId(), "run.start", "queued", "QUEUED",
                            steeringQueue.size());
                    emitSteeringQueued(command, events, steeringQueue.size());
                } catch (RuntimeException failure) {
                    closeForTransportFailureLocked();
                    throw new AcceptedRunTransportException(failure);
                }
                return StdioProtocol.Disposition.CONTINUE;
            }
            run = startRunLocked(command.requestId(), prompt.length(), events);
        }
        io.github.liumaishenjian.ccjava.domain.UserMessage accepted = message;
        submitAcceptedRun(command, run, events, "run.start", () -> executeRun(run, accepted));
        return StdioProtocol.Disposition.CONTINUE;
    }

    /**
     * 返回只服务 UX 的有界 Workspace-relative 候选，绝不启动 Run 或修改 Session。
     *
     * <p>候选不是授权依据：提交时仍由
     * {@link io.github.liumaishenjian.ccjava.cli.mentions.FileMentionService} 重新做权威校验。
     * 事件超过固定预算时从低优先级尾部移除候选；建议本来就不是完整清单，因此该裁剪不会改变
     * 权威文件解析语义。</p>
     *
     * @param command 已通过严格 schema 的 file.suggest
     * @param events 当前连接的有序事件出口
     * @return 连接继续读取下一条命令
     * @throws StdioProtocolException 状态、Session 或扫描不可用时
     */
    private StdioProtocol.Disposition inspectTask(StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        requireSession(command);
        var report = application.inspectChildTask(taskId(command))
                .orElseThrow(() -> protocolError("TASK_NOT_FOUND", command, "子任务不存在"));
        emitTask(command, events, report, "task.status");
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition waitTask(StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        requireSession(command);
        try {
            var report = application.waitForChildTask(taskId(command),
                    Duration.ofMillis(command.payload().get("timeoutMillis").longValue()))
                    .orElseThrow(() -> protocolError("TASK_NOT_FOUND", command, "子任务不存在"));
            emitTask(command, events, report, "task.status");
            return StdioProtocol.Disposition.CONTINUE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw protocolError("TASK_WAIT_INTERRUPTED", command, "等待子任务被中断");
        }
    }

    private StdioProtocol.Disposition cancelTask(StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        requireSession(command);
        if (!application.cancelChildTask(taskId(command)))
            throw protocolError("TASK_NOT_FOUND_OR_TERMINAL", command, "子任务不存在或已终态");
        var report = application.inspectChildTask(taskId(command)).orElseThrow();
        emitTask(command, events, report, "task.status");
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition keepTaskWorktree(StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        requireSession(command);
        String disposition = application.keepChildTaskWorktree(taskId(command))
                .orElseThrow(() -> protocolError("TASK_WORKTREE_UNAVAILABLE", command,
                        "任务不存在、未终态或没有 worktree"));
        emitWorktreeDisposition(command, events, disposition);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition removeTaskWorktree(StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        requireSession(command);
        String disposition = application.removeChildTaskWorktree(taskId(command))
                .orElseThrow(() -> protocolError("TASK_WORKTREE_UNAVAILABLE", command,
                        "任务不存在、未终态或没有 worktree"));
        emitWorktreeDisposition(command, events, disposition);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private void emitWorktreeDisposition(StdioProtocol.Command command, StdioProtocol.EventEmitter events,
            String disposition) {
        ObjectNode payload = codec.objectNode();
        payload.put("taskId", taskId(command).value());
        payload.put("disposition", disposition.toLowerCase(Locale.ROOT));
        events.emit("task.worktree", command.requestId(), Optional.of(application.sessionId().value()),
                Optional.empty(), payload);
    }

    private io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId taskId(StdioProtocol.Command command) {
        return new io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId(
                command.payload().get("taskId").stringValue());
    }

    private void emitTask(StdioProtocol.Command command, StdioProtocol.EventEmitter events,
            io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport report, String type) {
        events.emit(type, command.requestId(), Optional.of(application.sessionId().value()), Optional.empty(),
                taskPayload(report));
    }

    /**
     * 主动投影后台任务终态；requestId 使用任务身份，避免错误关联任一已结束父 Run。
     */
    private void emitBackgroundTaskTerminal(StdioProtocol.EventEmitter events,
            io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport report) {
        if (!report.status().terminal()) return;
        try {
            events.emit("task.terminal", report.taskId().value(),
                    Optional.of(application.sessionId().value()), Optional.empty(), taskPayload(report));
        } catch (RuntimeException transportFailure) {
            synchronized (lock) {
                closeForTransportFailureLocked();
            }
        }
    }

    private ObjectNode taskPayload(io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport report) {
        ObjectNode payload = codec.objectNode();
        payload.put("taskId", report.taskId().value());
        payload.put("definitionId", report.definitionId().value());
        payload.put("status", report.status().name().toLowerCase(Locale.ROOT));
        payload.put("failure", report.failureCode().name().toLowerCase(Locale.ROOT));
        payload.put("modelTurns", report.modelTurns());
        payload.put("toolCalls", report.toolCalls());
        payload.put("estimatedTokens", report.estimatedTokens());
        payload.put("elapsedMillis", report.elapsed().toMillis());
        payload.put("summary", report.summary());
        payload.put("verified", report.verified());
        if (report.worktreeDisposition().isPresent()) {
            payload.put("worktreeDisposition",
                    report.worktreeDisposition().orElseThrow().toLowerCase(Locale.ROOT));
        } else {
            payload.putNull("worktreeDisposition");
        }
        return payload;
    }

    private StdioProtocol.Disposition suggestFiles(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String query;
        String sessionId;
        io.github.liumaishenjian.ccjava.cli.mentions.FileSuggestionService suggestionService;
        java.util.List<String> candidates;
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            query = command.payload().get("query").stringValue();
            sessionId = application.sessionId().value();
            suggestionService = fileSuggestions;
        }
        try {
            candidates = suggestionService.suggest(query);
        } catch (RuntimeException failure) {
            throw protocolError("FILE_SUGGEST_UNAVAILABLE", command, "文件候选不可用");
        }
        ObjectNode payload = codec.objectNode();
        payload.put("query", query);
        ArrayNode items = codec.arrayNode();
        candidates.forEach(items::add);
        payload.set("candidates", items);
        StdioProtocol.Event sizeProbe = new StdioProtocol.Event(
                StdioProtocol.VERSION,
                "file.suggestions",
                command.requestId(),
                Optional.of(sessionId),
                Optional.empty(),
                Long.MAX_VALUE,
                payload);
        while (items.size() > 0
                && codec.encodeEvent(sizeProbe).getBytes(StandardCharsets.UTF_8).length + 1
                        > MAX_SUGGESTION_EVENT_BYTES) {
            items.remove(items.size() - 1);
        }
        if (codec.encodeEvent(sizeProbe).getBytes(StandardCharsets.UTF_8).length + 1
                > MAX_SUGGESTION_EVENT_BYTES) {
            throw protocolError("FILE_SUGGEST_TOO_LARGE", command, "文件候选事件超过预算");
        }
        events.emit(
                "file.suggestions",
                command.requestId(),
                Optional.of(sessionId),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition beginInput(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            expireInputAssembly();
            String inputId = requiredAssemblyText(command, "inputId");
            rejectTerminalInputId(command, inputId);
            if (inputAssembly != null) {
                InputAssembly abandoned = inputAssembly;
                failAssemblyLocked(abandoned, InputTerminal.FAILED);
                recordInputTombstone(inputId, InputTerminal.FAILED);
                throw correlatedError("INPUT_IN_FLIGHT", command, abandoned.requestId, "已有输入正在组装");
            }
            int byteCount;
            int chunkCount;
            String digest;
            try {
                byteCount = requiredAssemblyInt(command, "byteCount", 1, MAX_EXPANDED_INPUT_BYTES);
                chunkCount = requiredAssemblyInt(command, "chunkCount", 1, MAX_INPUT_CHUNKS);
                digest = requiredAssemblyText(command, "sha256");
            } catch (StdioProtocolException invalid) {
                recordInputTombstone(inputId, InputTerminal.FAILED);
                throw invalid;
            }
            if (!digest.matches("[0-9a-f]{64}")) {
                recordInputTombstone(inputId, InputTerminal.FAILED);
                throw protocolError("INPUT_DIGEST_INVALID", command, "输入摘要格式无效");
            }
            inputAssembly = new InputAssembly(
                    command.requestId(), inputId, byteCount, chunkCount, digest,
                    clock.instant().plus(INPUT_ASSEMBLY_TIMEOUT), new java.io.ByteArrayOutputStream(byteCount));
            InputAssembly captured = inputAssembly;
            inputExpiry = assemblyScheduler.schedule(INPUT_ASSEMBLY_TIMEOUT, () -> expireInputAssembly(captured));
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition appendInputChunk(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            InputAssembly assembly = requireAssembly(command);
            requireAssemblyId(command, assembly);
            int ordinal = requiredAssemblyInt(command, "ordinal", 0, MAX_INPUT_CHUNKS - 1);
            if (ordinal != assembly.receivedChunks) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_CHUNK_ORDER", command, assembly.requestId, "输入分块顺序不连续");
            }
            JsonNode value = command.payload().get("text");
            if (value == null || !value.isString() || value.stringValue().isEmpty()) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_CHUNK_INVALID", command, assembly.requestId, "输入分块必须是非空文本");
            }
            byte[] bytes = value.stringValue().getBytes(StandardCharsets.UTF_8);
            if (assembly.bytes.size() + bytes.length > assembly.byteCount
                    || assembly.receivedChunks >= assembly.chunkCount) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_SIZE_MISMATCH", command, assembly.requestId, "输入分块超过声明边界");
            }
            assembly.bytes.writeBytes(bytes);
            assembly.receivedChunks++;
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition commitInput(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String prompt;
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            InputAssembly assembly = requireAssembly(command);
            requireAssemblyId(command, assembly);
            byte[] bytes = assembly.bytes.toByteArray();
            if (assembly.receivedChunks != assembly.chunkCount || bytes.length != assembly.byteCount
                    || !sha256(bytes).equals(assembly.sha256)) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_COMMIT_MISMATCH", command, assembly.requestId, "输入分块校验失败");
            }
            try {
                prompt = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException invalid) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_UTF8_INVALID", command, assembly.requestId, "输入不是严格 UTF-8");
            }
            if (prompt.isBlank() || prompt.length() > MAX_EXPANDED_INPUT_BYTES) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INVALID_PAYLOAD", command, assembly.requestId, "展开输入为空或超过限制");
            }
            completeAssemblyLocked(assembly, InputTerminal.COMPLETED);
            command = new StdioProtocol.Command(
                    command.version(), "run.start", assembly.requestId, command.sessionId(),
                    Optional.empty(), command.sequence(), command.payload());
        }
        return startAcceptedInput(command, events, prompt);
    }

    private InputAssembly requireAssembly(StdioProtocol.Command command) throws StdioProtocolException {
        expireInputAssembly();
        String inputId = requiredAssemblyText(command, "inputId");
        InputTerminal terminal = inputTombstones.get(inputId);
        if (terminal != null) {
            throw correlatedError("INPUT_REPLAY", command, command.requestId(), "输入 ID 已终结：" + terminal.name());
        }
        if (inputAssembly == null) {
            throw protocolError("INPUT_NOT_IN_FLIGHT", command, "没有正在组装的输入");
        }
        return inputAssembly;
    }

    private void requireAssemblyId(StdioProtocol.Command command, InputAssembly assembly)
            throws StdioProtocolException {
        String mismatchedId = requiredAssemblyText(command, "inputId");
        if (!assembly.inputId.equals(mismatchedId)) {
            failAssemblyLocked(assembly, InputTerminal.FAILED);
            recordInputTombstone(mismatchedId, InputTerminal.FAILED);
            throw correlatedError("INPUT_ID_MISMATCH", command, assembly.requestId, "输入 ID 不匹配");
        }
    }

    private void expireInputAssembly() {
        if (inputAssembly != null && !clock.instant().isBefore(inputAssembly.deadline)) {
            failAssemblyLocked(inputAssembly, InputTerminal.EXPIRED);
        }
    }

    private void expireInputAssembly(InputAssembly expected) {
        synchronized (lock) {
            if (inputAssembly == expected) failAssemblyLocked(expected, InputTerminal.EXPIRED);
        }
    }

    private void rejectTerminalInputId(StdioProtocol.Command command, String inputId)
            throws StdioProtocolException {
        InputTerminal terminal = inputTombstones.get(inputId);
        if (terminal != null) {
            throw correlatedError("INPUT_REPLAY", command, command.requestId(), "输入 ID 已终结：" + terminal.name());
        }
    }

    private void failAssemblyLocked(InputAssembly assembly, InputTerminal terminal) {
        completeAssemblyLocked(assembly, terminal);
    }

    private void completeAssemblyLocked(InputAssembly assembly, InputTerminal terminal) {
        if (inputAssembly == assembly) inputAssembly = null;
        if (inputExpiry != null) {
            inputExpiry.cancel();
            inputExpiry = null;
        }
        recordInputTombstone(assembly.inputId, terminal);
    }

    private void recordInputTombstone(String inputId, InputTerminal terminal) {
        inputTombstones.put(inputId, terminal);
        while (inputTombstones.size() > MAX_INPUT_TOMBSTONES) {
            inputTombstones.remove(inputTombstones.keySet().iterator().next());
        }
    }

    private StdioProtocolException correlatedError(
            String code, StdioProtocol.Command command, String logicalRequestId, String message) {
        return new StdioProtocolException(code, logicalRequestId, message);
    }

    private String requiredAssemblyText(StdioProtocol.Command command, String field)
            throws StdioProtocolException {
        JsonNode value = command.payload().get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || (value.stringValue().length() > StdioProtocolCodec.MAX_IDENTIFIER_CHARS
                        && !field.equals("sha256"))) {
            throw protocolError("INVALID_PAYLOAD", command, field + " 无效");
        }
        return value.stringValue();
    }

    private int requiredAssemblyInt(StdioProtocol.Command command, String field, int minimum, int maximum)
            throws StdioProtocolException {
        JsonNode value = command.payload().get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw protocolError("INVALID_PAYLOAD", command, field + " 超过边界");
        }
        return value.intValue();
    }

    private static io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell platformShell() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell.WINDOWS_PLATFORM
                : io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell.POSIX_PLATFORM;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", impossible);
        }
    }

    private ActiveRun startRunLocked(String requestId, int promptChars, StdioProtocol.EventEmitter events) {
        ActiveRun run = new ActiveRun(requestId, promptChars, events);
        activeRun = run;
        state = State.RUNNING;
        return run;
    }

    /**
     * 先让单线程 executor 接受 worker，再发布确定的 command acceptance，最后才允许进入 Runtime。
     *
     * <p>因此 {@code run.started} 不可能早于 acceptance；若入队、事件出口或连接关闭失败，尚未开始的
     * worker 只退出等待，不会写 Session、请求模型或执行 Tool。</p>
     */
    private void submitAcceptedRun(
            StdioProtocol.Command command,
            ActiveRun run,
            StdioProtocol.EventEmitter events,
            String commandType,
            Runnable work) throws StdioProtocolException {
        submitAcceptedRun(command, run, events, commandType, () -> { }, work);
    }

    /**
     * 在 acceptance 已进入事件出口、Runtime 尚未启动的确定性缝隙发布命令专属生命周期。
     *
     * <p>{@code afterAccepted} 失败与 acceptance 传输失败具有相同语义：关闭连接并中止 gate，
     * 从而避免例如 {@code skill.invoked} 已发布但执行器拒绝任务，或 Runtime 抢先发布
     * {@code run.started} 的乱序投影。</p>
     */
    private void submitAcceptedRun(
            StdioProtocol.Command command,
            ActiveRun run,
            StdioProtocol.EventEmitter events,
            String commandType,
            Runnable afterAccepted,
            Runnable work) throws StdioProtocolException {
        try {
            executor.submit(() -> {
                if (run.commandStart.awaitStart()) {
                    work.run();
                }
            });
        } catch (RuntimeException enqueueFailure) {
            synchronized (lock) {
                run.commandStart.abort();
                if (activeRun == run) {
                    activeRun = null;
                    state = State.READY;
                }
            }
            throw protocolError("RUN_ENQUEUE_FAILED", command, "Run 未被执行器接受");
        }
        try {
            emitRunCommandResult(events, command.requestId(), commandType, "accepted", "ACCEPTED", 0);
            afterAccepted.run();
        } catch (RuntimeException transportFailure) {
            synchronized (lock) {
                closeForTransportFailureLocked();
            }
            throw new AcceptedRunTransportException(transportFailure);
        }
        run.commandStart.start();
    }

    /** 发布不含用户正文的 Run-producing command disposition。 */
    private void emitRunCommandResult(
            StdioProtocol.EventEmitter events,
            String requestId,
            String commandType,
            String disposition,
            String code,
            int queueDepth) {
        ObjectNode payload = codec.objectNode();
        payload.put("commandType", commandType);
        payload.put("disposition", disposition);
        payload.put("code", code);
        if ("queued".equals(disposition)) {
            payload.put("queueDepth", queueDepth);
        }
        events.emit("run.command.result", requestId, Optional.of(application.sessionId().value()),
                Optional.empty(), payload);
    }

    private void emitSteeringQueued(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events,
            int queueDepth) {
        ObjectNode payload = codec.objectNode();
        payload.put("queueDepth", queueDepth);
        events.emit("steering.queued", command.requestId(), Optional.of(application.sessionId().value()), Optional.empty(), payload);
    }

    /**
     * 将严格解码的 stdio 命令交给 S08 Application dispatcher，并只发布一次安全终态。
     *
     * @param command 已通过 v0 传输/字段校验的命令
     * @param events 当前连接的有序事件出口
     * @return 连接继续读取下一条命令
     * @throws StdioProtocolException Session 不匹配或请求状态不合法时
     */
    private StdioProtocol.Disposition sessionCommand(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        SessionCommandResult result;
        CommandId commandId;
        synchronized (lock) {
            ensureStateNotNewOrClosed(command);
            requireSession(command);
            if (command.runId().isPresent()) {
                throw protocolError("INVALID_STATE", command, "session.command 不能携带 Run ID");
            }
            if (commandDispatcher == null) {
                commandDispatcher = new SessionCommandDispatcher(
                        application, new DoctorReportService(application),
                        () -> discardSteering(DiscardReason.CLEAR));
            }
            try {
                commandId = new CommandId(requiredSessionCommandText(command, "commandId"));
                if (commandRequestBudgetExhausted && !emittedCommandResults.contains(commandId)) {
                    return StdioProtocol.Disposition.SHUTDOWN;
                }
                SessionCommandIntent intent = decodeSessionCommandIntent(command);
                result = commandDispatcher.dispatch(commandId, intent, CancellationToken.none());
                if (intent instanceof SessionCommandIntent.Resume
                        && result.event().status() == io.github.liumaishenjian.ccjava.domain.command.SessionCommandStatus.SUCCEEDED) {
                    discardSteering(DiscardReason.SESSION_SWITCH);
                }
            } catch (IllegalArgumentException invalid) {
                throw protocolError("INVALID_PAYLOAD", command, "session.command 参数无效");
            }
        }
        synchronized (lock) {
            if (!emittedCommandResults.contains(commandId)) {
                if (result.event().code() == io.github.liumaishenjian.ccjava.domain.command.SessionCommandResultCode.REQUEST_BUDGET_EXHAUSTED) {
                    commandRequestBudgetExhausted = true;
                    emitSessionCommandResult(command.requestId(), result, events);
                    return StdioProtocol.Disposition.SHUTDOWN;
                }
                emittedCommandResults.add(commandId);
                emitSessionCommandResult(command.requestId(), result, events);
            }
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    /** 执行不含 secret 的 MODEL-13 本地控制命令，并发出严格安全投影。 */
    private StdioProtocol.Disposition providerControl(
            StdioProtocol.Command command, StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureStateNotNewOrClosed(command);
            requireSession(command);
            requireNoRunId(command);
        }
        if (providerAuth == null) {
            throw protocolError("INVALID_STATE", command, "provider.control 未装配");
        }
        String controlId = requiredSessionCommandText(command, "controlId");
        String intent = requiredSessionCommandText(command, "intent");
        JsonNode arguments = command.payload().get("arguments");
        ObjectNode result = codec.objectNode();
        String status = "succeeded";
        String code = "OK";
        try {
            switch (intent) {
                case "providers.configure" -> {
                    var configured = providerAuth.configureCodejProvider(
                            new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService
                                    .ConfigureProviderRequest(
                                    requiredArgument(arguments, "baseUrl"),
                                    requiredArgument(arguments, "modelId")),
                            CancellationToken.none());
                    result.put("providerId", configured.providerId());
                    result.put("displayName", configured.displayName());
                    result.put("modelId", configured.modelId());
                }
                case "providers.add" -> {
                    var added = providerAuth.addCompatibleProvider(
                            new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService
                                    .AddProviderRequest(
                                    requiredArgument(arguments, "providerId"),
                                    requiredArgument(arguments, "displayName"),
                                    requiredArgument(arguments, "baseUrl"),
                                    requiredArgument(arguments, "modelId")),
                            CancellationToken.none());
                    result.put("providerId", added.providerId());
                    result.put("displayName", added.displayName());
                    result.put("modelId", added.modelId());
                }
                case "auth.list" -> {
                    ArrayNode profiles = codec.arrayNode();
                    providerAuth.listProfiles(Optional.empty(), CancellationToken.none()).forEach(value -> {
                        ObjectNode item = codec.objectNode();
                        item.put("providerId", value.providerId()); item.put("profileId", value.profileId());
                        item.put("authMethod", value.authMethod()); item.put("refKind", value.refKind());
                        item.put("localStatus", value.status().name()); item.put("providerDefault", value.providerDefault());
                        value.lastProbeCode().ifPresent(v -> item.put("lastProbeCode", v));
                        value.lastProbeAt().ifPresent(v -> item.put("lastProbeAt", v.toString()));
                        profiles.add(item);
                    });
                    result.set("profiles", profiles);
                }
                case "models.list" -> {
                    Optional<String> provider = optionalArgument(arguments, "providerId");
                    ArrayNode models = codec.arrayNode();
                    providerAuth.listModels(provider, CancellationToken.none()).forEach(value -> {
                        ObjectNode item = codec.objectNode(); item.put("providerId", value.providerId());
                        item.put("modelId", value.modelId()); item.put("providerDefault", value.providerDefault());
                        models.add(item);
                    });
                    result.set("models", models);
                }
                case "models.add" -> {
                    String providerId = requiredArgument(arguments, "providerId");
                    String modelId = requiredArgument(arguments, "modelId");
                    boolean setDefault = optionalBooleanArgument(arguments, "setDefault");
                    providerAuth.addModel(providerId, modelId, setDefault, CancellationToken.none());
                    result.put("providerId", providerId); result.put("modelId", modelId);
                    result.put("setDefault", setDefault);
                }
                case "models.remove" -> {
                    String providerId = requiredArgument(arguments, "providerId");
                    String modelId = requiredArgument(arguments, "modelId");
                    providerAuth.removeModel(providerId, modelId, CancellationToken.none());
                    result.put("providerId", providerId); result.put("modelId", modelId);
                }
                case "models.use" -> {
                    boolean setDefault = optionalBooleanArgument(arguments, "setDefault");
                    var selected = providerAuth.selectModel(new io.github.liumaishenjian.ccjava.cli.runtime
                            .ProviderAuthApplicationService.ModelSelectionRequest(
                            requiredArgument(arguments, "providerId"), requiredArgument(arguments, "modelId"),
                            optionalArgument(arguments, "profileId"), setDefault), CancellationToken.none());
                    result.put("providerId", selected.providerId()); result.put("profileId", selected.profileId());
                    result.put("modelId", selected.modelId()); result.put("setDefault", setDefault);
                }
                case "auth.probe" -> {
                    String providerId = requiredArgument(arguments, "providerId");
                    String modelId = optionalArgument(arguments, "modelId").orElseGet(() -> providerAuth
                            .listModels(Optional.of(providerId), CancellationToken.none()).stream()
                            .filter(io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService
                                    .ModelSummary::providerDefault).findFirst().orElseThrow().modelId());
                    var probe = providerAuth.probe(new io.github.liumaishenjian.ccjava.cli.runtime
                            .ProviderAuthApplicationService.ProbeRequest(providerId,
                            requiredArgument(arguments, "profileId"), modelId, Duration.ofSeconds(5)),
                            CancellationToken.none());
                    result.put("providerId", probe.providerId()); result.put("profileId", probe.profileId());
                    result.put("modelId", probe.modelId()); result.put("outcome", probe.outcome().name());
                    result.put("probedAt", probe.probedAt().toString());
                }
                case "auth.logout" -> {
                    var logout = providerAuth.logout(requiredArgument(arguments, "providerId"),
                            requiredArgument(arguments, "profileId"), CancellationToken.none());
                    result.put("providerId", logout.providerId()); result.put("profileId", logout.profileId());
                    result.put("remoteRevoked", false);
                }
                default -> throw new IllegalArgumentException("未知 provider control intent");
            }
        } catch (io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException failure) {
            status = "rejected"; code = failure.code().name(); result = codec.objectNode();
        } catch (RuntimeException failure) {
            status = "rejected"; code = "INVALID_ARGUMENT"; result = codec.objectNode();
        }
        ObjectNode payload = codec.objectNode(); payload.put("controlId", controlId);
        payload.put("intent", intent); payload.put("status", status); payload.put("code", code);
        payload.set("result", result);
        events.emit("provider.control.result", command.requestId(), Optional.of(application.sessionId().value()),
                Optional.empty(), payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private static String requiredArgument(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException(field);
        return value.stringValue();
    }

    private static Optional<String> optionalArgument(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value == null ? Optional.empty() : Optional.of(requiredArgument(arguments, field));
    }

    private static boolean optionalBooleanArgument(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null) return false;
        if (!value.isBoolean()) throw new IllegalArgumentException(field);
        return value.booleanValue();
    }
    private void emitSessionCommandResult(
            String requestId,
            SessionCommandResult result,
            StdioProtocol.EventEmitter events) {
        SessionCommandEvent event = result.event();
        ObjectNode payload = codec.objectNode();
        payload.put("commandId", event.commandId().value());
        payload.put("intent", safeIntentName(event.kind()));
        payload.put("status", event.status().name().toLowerCase(Locale.ROOT));
        payload.put("code", event.code().name().toLowerCase(Locale.ROOT));
        payload.set("result", sessionCommandPayload(event.payload()));
        events.emit("session.command.result", requestId, Optional.of(event.sessionId().value()), Optional.empty(), payload);
    }

    private ObjectNode sessionCommandPayload(SessionCommandEvent.SessionCommandPayload source) {
        ObjectNode payload = codec.objectNode();
        switch (source) {
            case SessionCommandEvent.EmptyPayload ignored -> { }
            case SessionCommandEvent.HelpPayload help -> {
                ArrayNode commands = codec.arrayNode();
                help.commands().forEach(value -> {
                    ObjectNode item = codec.objectNode();
                    item.put("intent", safeIntentName(value.kind()));
                    item.put("support", value.support().name().toLowerCase(Locale.ROOT));
                    commands.add(item);
                });
                payload.set("commands", commands);
            }
            case SessionCommandEvent.ContextPayload context -> {
                payload.put("systemTokens", context.systemTokens());
                payload.put("transcriptTokens", context.transcriptTokens());
                payload.put("toolTokens", context.toolTokens());
                payload.put("memoryTokens", context.memoryTokens());
                payload.put("totalTokens", context.totalTokens());
                payload.put("availableInputTokens", context.availableInputTokens());
                payload.put("freeTokens", context.freeTokens());
                payload.put("overflowTokens", context.overflowTokens());
                payload.put("sourceRevision", context.sourceRevision());
                payload.put("estimateKind", context.estimateKind());
                payload.put("contextStatus", context.status());
                payload.put("modelRequestAttempts", context.modelRequestAttempts());
                payload.set("reductionStrategies", mapperEnums(context.reductionStrategies()));
                payload.set("reasonCodes", mapperEnums(context.reasonCodes()));
            }
            case SessionCommandEvent.PermissionsPayload permissions -> {
                payload.put("effectiveMode", permissions.effectiveMode());
                payload.put("effectiveReviewer", permissions.effectiveReviewer());
                payload.put("effectiveSelection", permissions.effectiveSelection());
                payload.put("modeSourceKind", permissions.modeSourceKind());
                payload.put("modeSafeSourceId", permissions.modeSafeSourceId());
                payload.put("modeValidationStatus", permissions.modeValidationStatus());
                payload.put("startupRuleCount", permissions.startupRuleCount());
                ArrayNode rules = codec.arrayNode();
                permissions.rules().forEach(value -> {
                    ObjectNode item = codec.objectNode();
                    item.put("ruleId", value.ruleId());
                    item.put("sourceKind", value.sourceKind());
                    item.put("safeSourceId", value.safeSourceId());
                    item.put("operation", value.operation());
                    item.put("validationStatus", value.validationStatus());
                    rules.add(item);
                });
                payload.set("rules", rules);
            }
            case SessionCommandEvent.ResumePayload resume -> {
                payload.put("previousSessionId", resume.previousSessionId());
                payload.put("resumedSessionId", resume.resumedSessionId());
            }
            case SessionCommandEvent.PlanPayload plan -> {
                payload.put("planId", plan.planId());
                payload.put("status", plan.status());
                payload.put("approvalGate", plan.approvalGate());
                if (plan.nextStep() != null) payload.put("nextStep", plan.nextStep()); else payload.putNull("nextStep");
                if (plan.activeStep() != null) payload.put("activeStep", plan.activeStep()); else payload.putNull("activeStep");
                payload.put("objective", plan.objective());
                payload.put("workspaceDigest", plan.workspaceDigest());
                ArrayNode steps = codec.arrayNode();
                plan.steps().forEach(step -> {
                    ObjectNode item = codec.objectNode();
                    item.put("ordinal", step.ordinal()); item.put("title", step.title());
                    item.put("detail", step.detail()); item.put("expectedDigest", step.expectedDigest());
                    steps.add(item);
                });
                payload.set("steps", steps);
            }
            case SessionCommandEvent.DoctorPayload doctor -> {
                payload.put("settingsAvailable", doctor.settingsAvailable());
                payload.put("settingsRevision", doctor.settingsRevision());
                payload.put("instructionCount", doctor.instructionCount());
                payload.put("contextAvailable", doctor.contextAvailable());
                payload.put("activeRun", doctor.activeRun());
                ArrayNode entries = codec.arrayNode();
                doctor.entries().forEach(value -> {
                    ObjectNode item = codec.objectNode();
                    item.put("component", value.component());
                    item.put("sourceKind", value.sourceKind());
                    item.put("safeId", value.safeId());
                    item.put("code", value.code());
                    item.put("severity", value.severity());
                    entries.add(item);
                });
                payload.set("entries", entries);
            }
        }
        return payload;
    }

    private ArrayNode mapperEnums(java.util.List<String> values) {
        ArrayNode array = codec.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private SessionCommandIntent decodeSessionCommandIntent(StdioProtocol.Command command) throws StdioProtocolException {
        String intent = requiredSessionCommandText(command, "intent");
        ObjectNode arguments = (ObjectNode) command.payload().get("arguments");
        return switch (intent) {
            case "help" -> new SessionCommandIntent.Help();
            case "clear" -> new SessionCommandIntent.Clear();
            case "compact" -> new SessionCommandIntent.Compact(
                    java.util.stream.StreamSupport.stream(arguments.get("anchors").spliterator(), false)
                            .map(JsonNode::stringValue).toList());
            case "context" -> new SessionCommandIntent.Context();
            case "doctor" -> new SessionCommandIntent.Doctor();
            case "model" -> new SessionCommandIntent.ModelChange(arguments.get("name").stringValue());
            case "permissions" -> new SessionCommandIntent.Permissions(arguments.isEmpty()
                    ? new SessionCommandIntent.PermissionsOperation.Query()
                    : arguments.has("selection")
                            ? new SessionCommandIntent.PermissionsOperation.SelectionChange(
                                    io.github.liumaishenjian.ccjava.domain.PermissionSelection.valueOf(
                                            arguments.get("selection").stringValue()))
                            : new SessionCommandIntent.PermissionsOperation.ModeChange(
                                    io.github.liumaishenjian.ccjava.domain.PermissionMode.valueOf(
                                            arguments.get("mode").stringValue())));
            case "resume" -> new SessionCommandIntent.Resume(new SessionId(arguments.get("sessionId").stringValue()));
            case "plan-status" -> new SessionCommandIntent.PlanStatus();
            case "plan-approve" -> new SessionCommandIntent.PlanApprove(
                    arguments.get("planId").stringValue(), arguments.get("workspaceDigest").stringValue());
            case "plan-step-begin" -> new SessionCommandIntent.PlanStepBegin(arguments.get("workspaceDigest").stringValue());
            case "plan-reject" -> new SessionCommandIntent.PlanReject(arguments.get("planId").stringValue());
            case "plan-step-complete" -> new SessionCommandIntent.PlanStepComplete(
                    arguments.get("workspaceDigest").stringValue());
            case "plan-execute" -> new SessionCommandIntent.PlanExecute(
                    arguments.get("planId").stringValue(), arguments.get("workspaceDigest").stringValue(),
                    arguments.get("maxSteps").intValue());
            case "plan" -> new SessionCommandIntent.Plan(
                    arguments.get("objective").stringValue(),
                    java.util.stream.StreamSupport.stream(arguments.get("steps").spliterator(), false)
                            .map(step -> new SessionCommandIntent.PlanStepInput(
                                    step.get("ordinal").intValue(), step.get("title").stringValue(),
                                    step.get("detail").stringValue(), step.get("expectedDigest").stringValue())).toList(),
                    arguments.get("workspaceDigest").stringValue());
            default -> throw protocolError("INVALID_ARGUMENT", command, "未知 session.command intent");
        };
    }

    private String requiredSessionCommandText(StdioProtocol.Command command, String field)
            throws StdioProtocolException {
        JsonNode value = command.payload().get(field);
        if (value == null || !value.isString()) {
            throw protocolError("INVALID_PAYLOAD", command, "session.command 缺少必填字段");
        }
        return value.stringValue();
    }

    private static String safeIntentName(io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind kind) {
        return switch (kind) {
            case HELP -> "help";
            case CLEAR -> "clear";
            case COMPACT -> "compact";
            case CONTEXT -> "context";
            case DOCTOR -> "doctor";
            case MODEL_CHANGE -> "model";
            case PERMISSIONS -> "permissions";
            case RESUME -> "resume";
            case PLAN_STATUS -> "plan-status";
            case PLAN -> "plan";
            case PLAN_APPROVE -> "plan-approve";
            case PLAN_STEP_BEGIN -> "plan-step-begin";
            case PLAN_REJECT -> "plan-reject";
            case PLAN_STEP_COMPLETE -> "plan-step-complete";
            case PLAN_EXECUTE -> "plan-execute";
        };
    }

    private StdioProtocol.Disposition listCheckpoints(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
        }
        ArrayNode items = codec.arrayNode();
        for (var summary : application.checkpoints()) {
            ObjectNode item = codec.objectNode();
            item.put("checkpointId", summary.id().value());
            item.put("callId", summary.callId());
            item.put("toolName", summary.toolName());
            item.put("target", summary.target());
            item.put("existedBefore", summary.existedBefore());
            item.put("phase", summary.phase().name().toLowerCase(Locale.ROOT));
            item.put("undoable", summary.undoable());
            items.add(item);
        }
        ObjectNode payload = codec.objectNode();
        payload.set("checkpoints", items);
        events.emit(
                "checkpoint.listed",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition checkpointDiff(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String checkpointId;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            checkpointId = requiredCheckpointId(command);
        }
        var diff = application.checkpointDiff(
                new io.github.liumaishenjian.ccjava.domain.CheckpointId(checkpointId));
        ObjectNode payload = codec.objectNode();
        payload.put("checkpointId", diff.checkpointId().value());
        payload.put("target", diff.target());
        payload.put("status", diff.status().name().toLowerCase(Locale.ROOT));
        payload.put("text", diff.text());
        payload.put("truncated", diff.truncated());
        events.emit(
                "checkpoint.diffed",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition checkpointUndo(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String checkpointId;
        JsonNode confirmed = command.payload().get("confirmed");
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            checkpointId = requiredCheckpointId(command);
            if (confirmed == null || !confirmed.isBoolean()) {
                throw protocolError("INVALID_PAYLOAD", command, "checkpoint.undo.confirmed 必须是布尔值");
            }
        }
        var result = application.undoCheckpoint(
                new io.github.liumaishenjian.ccjava.domain.CheckpointId(checkpointId),
                confirmed.booleanValue());
        ObjectNode payload = codec.objectNode();
        payload.put("checkpointId", result.checkpointId().value());
        payload.put("target", result.target());
        payload.put("status", result.status().name().toLowerCase(Locale.ROOT));
        payload.put("message", result.message());
        events.emit(
                "checkpoint.undone",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition cancelRun(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun.runId == null
                    || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())
                    || !application.cancel(activeRun.runId)) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.cancel 与活动 Run 不匹配或取消已经发生");
            }
            if (inputAssembly != null) failAssemblyLocked(inputAssembly, InputTerminal.CANCELLED);
            discardSteering(DiscardReason.CANCELLED);
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition shutdown() {
        RuntimeException failure = null;
        try {
            synchronized (lock) {
                state = State.CLOSED;
                if (inputAssembly != null) failAssemblyLocked(inputAssembly, InputTerminal.CANCELLED);
                cancelActiveRunLocked();
                discardSteering(DiscardReason.SHUTDOWN);
            }
        } catch (RuntimeException cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            approvals.close();
            questions.close();
        } catch (RuntimeException closeFailure) {
            failure = retainFirstFailure(failure, closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
        return StdioProtocol.Disposition.SHUTDOWN;
    }

    private StdioProtocol.Disposition resolveApproval(StdioProtocol.Command command)
            throws StdioProtocolException {
        String approvalId;
        ApprovalResponse decision;
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun == null
                    || activeRun.runId == null
                    || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "approval.resolve 与活动 Run 不匹配");
            }
            JsonNode id = command.payload().get("approvalId");
            JsonNode rawDecision = command.payload().get("decision");
            if (id == null
                    || !id.isString()
                    || id.stringValue().isBlank()
                    || id.stringValue().length() > 128
                    || rawDecision == null
                    || !rawDecision.isString()) {
                throw protocolError(
                        "INVALID_PAYLOAD",
                        command,
                        "approval.resolve payload 无效");
            }
            approvalId = id.stringValue();
            decision = switch (rawDecision.stringValue()) {
                case "allow_once" -> ApprovalResponse.allowOnce();
                case "allow_session" -> {
                    StdioApprovalCoordinator.Request pending = approvals.pendingRequest();
                    if (pending == null || !pending.approvalId().equals(approvalId)) {
                        throw protocolError(
                                "STALE_APPROVAL",
                                command,
                                "审批不存在、已结束或与当前请求不匹配");
                    }
                    yield ApprovalResponse.allowSession(pending.scope());
                }
                case "deny" -> ApprovalResponse.deny();
                default -> throw protocolError(
                        "INVALID_PAYLOAD",
                        command,
                        "approval.resolve decision 无效");
            };
        }
        try {
            if (!approvals.resolve(approvalId, decision)) {
                throw protocolError(
                        "STALE_APPROVAL",
                        command,
                        "审批不存在、已结束或与当前请求不匹配");
            }
        } catch (StdioProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 审批完成会唤醒后台 Tool 线程；这里不能让该线程的运行时异常
            // 穿透 stdio 命令循环并把整个 Java 子进程映射为 exit=1。
            // 将当前请求收敛为一次拒绝，后台 Run 会通过正常的 tool.failed/run.failed
            // 生命周期结束，连接仍可承载后续 Run。
            try {
                approvals.resolve(approvalId, ApprovalResponse.deny());
            } catch (RuntimeException ignored) {
                // 该请求可能已经在异常前完成；无论如何不能再次击穿协议循环。
            }
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    /** 接受匹配当前活动 Run/callId 的结构化单选答案。 */
    private StdioProtocol.Disposition resolveQuestion(StdioProtocol.Command command)
            throws StdioProtocolException {
        String callId;
        String optionId;
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun == null || activeRun.runId == null || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())) {
                throw protocolError("INVALID_STATE", command, "question.resolve 与活动 Run 不匹配");
            }
            JsonNode rawCallId = command.payload().get("callId");
            JsonNode rawOptionId = command.payload().get("optionId");
            if (command.payload().size() != 2 || rawCallId == null || !rawCallId.isString()
                    || rawCallId.stringValue().isBlank() || rawCallId.stringValue().length() > 128
                    || rawOptionId == null || !rawOptionId.isString()
                    || rawOptionId.stringValue().isBlank() || rawOptionId.stringValue().length() > 64) {
                throw protocolError("INVALID_PAYLOAD", command, "question.resolve payload 无效");
            }
            callId = rawCallId.stringValue();
            optionId = rawOptionId.stringValue();
        }
        if (!questions.resolve(callId, optionId)) {
            throw protocolError("STALE_QUESTION", command, "问题不存在、已结束或答案不匹配");
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    /** 只投影用户可见问题和选项，不输出原始 Tool JSON。 */
    private void emitUserQuestion(io.github.liumaishenjian.ccjava.domain.UserQuestionRequest request) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null || run.runId == null || state != State.RUNNING) {
                throw new IllegalStateException("用户问题与活动 Run 不匹配");
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("callId", request.callId());
        payload.put("question", request.question());
        ArrayNode options = codec.arrayNode();
        request.options().forEach(option -> {
            ObjectNode item = codec.objectNode();
            item.put("optionId", option.optionId());
            item.put("label", option.label());
            item.put("description", option.description());
            options.add(item);
        });
        payload.set("options", options);
        emit(run, "question.requested", payload);
    }

    private void emitApprovalRequest(StdioApprovalCoordinator.Request request) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null
                    || run.runId == null
                    || !run.runId.equals(request.runId())
                    || state != State.RUNNING) {
                throw new IllegalStateException("审批请求与活动 Run 不匹配");
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("approvalId", request.approvalId());
        payload.put("ordinal", request.ordinal());
        payload.put("toolName", request.toolName());
        payload.put("effect", request.effect().name().toLowerCase(Locale.ROOT));
        payload.put("sessionScope", !request.scope().toolWide());
        if (!request.preview().target().isEmpty()) {
            payload.put("target", request.preview().target());
            payload.put("operation", request.preview().operation());
            payload.put("removedLines", request.preview().removedLines());
            payload.put("addedLines", request.preview().addedLines());
        }
        if (!request.preview().command().isEmpty()) {
            payload.put("command", request.preview().command());
            payload.put("shell", request.preview().shell());
            payload.put("workingDirectory", request.preview().workingDirectory());
            payload.put("operation", request.preview().operation());
        }
        if (!request.preview().networkQuery().isEmpty()) {
            payload.put("destination", request.preview().networkDestination());
            payload.put("query", request.preview().networkQuery());
            payload.put("operation", request.preview().operation());
        }
        emit(run, "approval.requested", payload);
    }

    private void executeRun(ActiveRun run, io.github.liumaishenjian.ccjava.domain.UserMessage message) {
        try {
            if (application.runtimeConfiguration().permissionMode() == PermissionMode.PLAN) {
                application.runPlan(message.content());
            } else {
                application.run(message);
            }
        } catch (RuntimeException exception) {
            emitUnexpectedFailure(run);
        }
    }

    private void executePlanRun(ActiveRun run, String task) {
        try {
            application.runPlan(task);
        } catch (RuntimeException exception) {
            emitUnexpectedFailure(run);
        }
    }

    private void executeAcceptedPlanRun(ActiveRun run) {
        try {
            AgentRunResult result = application.runAcceptedPlan(run.planAcceptance);
            var artifact = application.planArtifact().orElseThrow(
                    () -> new IllegalStateException("Plan 执行终态缺少 durable artifact"));
            if (result.stopReason() != io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED) {
                emitPlanExecutionFailure(run, result, artifact);
                emitTerminal(run, result, false);
                return;
            }
            ObjectNode payload = codec.objectNode();
            payload.put("planId", artifact.planId());
            payload.put("status", artifact.status().name().toLowerCase(Locale.ROOT));
            payload.put("requiredEvidence", artifact.evidenceLedger().requirements().stream()
                    .filter(io.github.liumaishenjian.ccjava.domain.PlanEvidenceRequirement::required).count());
            payload.put("satisfiedEvidence", artifact.evidenceLedger().references().stream()
                    .filter(reference -> reference.status() == io.github.liumaishenjian.ccjava.domain.PlanEvidenceStatus.PASSED
                            || reference.status() == io.github.liumaishenjian.ccjava.domain.PlanEvidenceStatus.SKIPPED).count());
            artifact.evidenceLedger().firstBlockingRequirement()
                    .ifPresent(requirement -> payload.put("blockingRequirementId", requirement));
            boolean completed = artifact.status() == io.github.liumaishenjian.ccjava.domain.PlanStatus.COMPLETED;
            run.events.emit(completed ? "plan.verification.completed" : "plan.verification.required",
                    run.requestId, Optional.of(application.sessionId().value()), Optional.empty(), payload);
            emitTerminal(run, result, completed);
        } catch (HeadlessRuntimeSession.PlanExecutionWorkspaceDriftException drift) {
            // typed session-level plan.execution.blocked 已在抛出前发布；不得再伪造成 run.failed。
        } catch (RuntimeException exception) {
            emitUnexpectedFailure(run);
        }
    }

    private void emitPlanExecutionFailure(
            ActiveRun run,
            AgentRunResult result,
            io.github.liumaishenjian.ccjava.domain.PlanArtifact artifact) {
        ObjectNode payload = codec.objectNode();
        payload.put("planId", artifact.planId());
        payload.put("status", artifact.status().name().toLowerCase(Locale.ROOT));
        payload.put("stopReason", result.stopReason().name().toLowerCase(Locale.ROOT));
        result.modelFailure().ifPresent(failure -> {
            ObjectNode summary = payload.putObject("modelFailure");
            summary.put("category", failure.category().name().toLowerCase(Locale.ROOT));
            failure.statusClass().ifPresent(status -> summary.put(
                    "statusClass",
                    status == io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass.CLIENT_ERROR
                            ? "4xx"
                            : "5xx"));
            summary.put("attempts", failure.attempts());
            summary.put("receivedOutput", failure.receivedOutput());
        });
        run.events.emit("plan.execution.failed", run.requestId,
                Optional.of(application.sessionId().value()), Optional.empty(), payload);
    }

    private void executeSkillRun(ActiveRun run,
            io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation invocation) {
        try {
            AgentRunResult result = application.runSkill(invocation);
            ObjectNode completed = codec.objectNode();
            completed.put("skillId", invocation.skillId().value());
            completed.put("invocationKind", "explicit");
            completed.put("status", result.stopReason() == io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED
                    ? "succeeded" : "failed");
            completed.put("stopReason", result.stopReason().name().toLowerCase(Locale.ROOT));
            run.events.emit("skill.completed", run.requestId, Optional.of(application.sessionId().value()),
                    Optional.of(result.runId().value()), completed);
        } catch (RuntimeException exception) {
            ObjectNode completed = codec.objectNode();
            completed.put("skillId", invocation.skillId().value());
            completed.put("invocationKind", "explicit");
            completed.put("status", "failed");
            completed.put("stopReason", "internal_error");
            run.events.emit("skill.completed", run.requestId, Optional.of(application.sessionId().value()),
                    Optional.ofNullable(run.runId).map(RunId::value), completed);
            emitUnexpectedFailure(run);
        }
    }

    @Override
    public void publish(AgentEventEnvelope envelope) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null
                    || !envelope.sessionId().equals(application.sessionId())) {
                return;
            }
            if (envelope.event() instanceof LifecycleEvent.RunStarted) {
                run.runId = envelope.runId().orElseThrow();
            } else if (run.runId == null
                    || envelope.runId().isEmpty()
                    || !run.runId.equals(envelope.runId().orElseThrow())) {
                return;
            }
        }

        if (envelope.event() instanceof LifecycleEvent.RunStarted) {
            ObjectNode payload = codec.objectNode();
            payload.put("promptChars", run.promptChars);
            emit(run, "run.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.ModelTurnStarted started) {
            ObjectNode payload = codec.objectNode();
            payload.put("turn", started.turnNumber());
            emit(run, "model.turn.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.ModelAttemptStarted started) {
            ObjectNode payload = codec.objectNode();
            payload.put("turn", started.turnNumber());
            payload.put("attempt", started.attempt());
            payload.put("maxAttempts", started.maxAttempts());
            emit(run, "model.retry.attempt.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.ModelRetryScheduled scheduled) {
            ObjectNode payload = codec.objectNode();
            payload.put("turn", scheduled.turnNumber());
            payload.put("failedAttempt", scheduled.failedAttempt());
            payload.put("nextAttempt", scheduled.nextAttempt());
            payload.put("maxAttempts", scheduled.maxAttempts());
            payload.put("waitMillis", scheduled.waitMillis());
            payload.put("category", scheduled.category().name().toLowerCase(Locale.ROOT));
            emit(run, "model.retry.scheduled", payload);
        } else if (envelope.event() instanceof LifecycleEvent.ModelTurnCompleted completed) {
            ObjectNode payload = codec.objectNode();
            payload.put("turn", completed.turnNumber());
            payload.put("finishReason", completed.turn().metadata().finishReason()
                    .name().toLowerCase(Locale.ROOT));
            completed.turn().metadata().usage().ifPresent(usage -> {
                ObjectNode usageNode = codec.objectNode();
                usageNode.put("inputTokens", usage.inputTokens());
                usageNode.put("outputTokens", usage.outputTokens());
                usageNode.put("totalTokens", usage.totalTokens());
                payload.set("usage", usageNode);
            });
            application.latestContextUsage().ifPresent(context -> {
                ObjectNode contextNode = codec.objectNode();
                contextNode.put("usedTokens", context.usage().totalTokens());
                contextNode.put("maximumInputTokens", context.maximumInputTokens());
                contextNode.put("estimateKind", context.usage().estimateKind().name().toLowerCase(Locale.ROOT));
                payload.set("context", contextNode);
            });
            emit(run, "model.turn.completed", payload);
        } else if (envelope.event() instanceof LifecycleEvent.BeforeTool before) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", before.ordinal());
            payload.put("toolName", before.call().name());
            payload.put("status", "started");
            safeToolMode(before.call()).ifPresent(mode -> {
                run.toolModes.put(before.ordinal(), mode);
                payload.put("mode", mode);
            });
            safeToolActivity(before.call()).ifPresent(activity -> payload.put("activity", activity));
            emit(run, "tool.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.AfterTool after) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", after.ordinal());
            payload.put("toolName", after.result().toolName());
            payload.put("status", after.result().status().name().toLowerCase());
            payload.put("returnedCharacters", after.result().metadata().returnedCharacters());
            payload.put("returnedItems", after.result().metadata().returnedItems());
            payload.put("truncated", after.result().metadata().truncated());
            payload.put(
                    "truncationReason",
                    after.result().metadata().truncationReason().name().toLowerCase(Locale.ROOT));
            payload.put("filteredItems", after.result().metadata().filteredItems());
            Optional.ofNullable(run.toolModes.remove(after.ordinal()))
                    .ifPresent(mode -> payload.put("mode", mode));
            after.result().error().ifPresent(error -> {
                payload.put("errorCode", error.code().name().toLowerCase());
                payload.put("failureCategory", error.category().name().toLowerCase());
                payload.put("retryable", error.retryable());
                Object argumentChangeRequired = error.details().values().get("argumentChangeRequired");
                if (argumentChangeRequired instanceof Boolean required) {
                    payload.put("argumentChangeRequired", required);
                }
                Object strategyChangeRequired = error.details().values().get("requiredStrategyChange");
                if (strategyChangeRequired instanceof Boolean required) {
                    payload.put("strategyChangeRequired", required);
                }
            });
            safeCommandExitCode(after.result()).ifPresent(exitCode -> payload.put("exitCode", exitCode));
            String type = after.result().status()
                    == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                            ? "tool.completed" : "tool.failed";
            emit(run, type, payload);
        } else if (envelope.event()
                instanceof LifecycleEvent.PlanVerificationCorrectionRequested correction) {
            ObjectNode payload = codec.objectNode();
            payload.put("attempt", correction.attempt());
            payload.put("maxAttempts", correction.maxAttempts());
            ArrayNode failures = payload.putArray("failures");
            for (var failure : correction.failures()) {
                ObjectNode item = failures.addObject();
                item.put("requirementId", failure.requirementId());
                item.put("kind", failure.kind().name().toLowerCase(Locale.ROOT));
                item.put("locator", failure.locator());
                item.put("reason", failure.reason());
            }
            emit(run, "plan.verification.correction", payload);
        } else if (envelope.event() instanceof LifecycleEvent.BudgetGoverned budget) {
            ObjectNode payload = codec.objectNode();
            payload.put("reason", budget.reason().name().toLowerCase(Locale.ROOT));
            payload.put("modelTurns", budget.modelTurns());
            payload.put("toolCalls", budget.toolCalls());
            payload.put("effectiveModelLimit", budget.effectiveModelLimit());
            payload.put("effectiveToolLimit", budget.effectiveToolLimit());
            emit(run, "run.budget.governed", payload);
        } else if (envelope.event() instanceof LifecycleEvent.ToolOutput output) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", output.ordinal());
            payload.put("toolName", output.toolName());
            payload.put("stream", output.stream().name().toLowerCase(Locale.ROOT));
            payload.put("text", output.text());
            emit(run, "tool.output", payload);
        } else if (envelope.event() instanceof ModelTextDelta delta && !run.suppressModelText) {
            ObjectNode payload = codec.objectNode();
            payload.put("text", delta.text());
            payload.put("turn", delta.turnNumber());
            emit(run, "model.text.delta", payload);
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.PlanReviewEvent review) {
            ObjectNode payload = codec.objectNode();
            payload.put("planId", review.planId());
            payload.put("status", "awaiting_approval");
            payload.put("revision", review.revision());
            payload.put("contentDigest", review.contentDigest());
            payload.put("markdown", review.markdownContent());
            payload.put("workspaceDigest", review.workspaceDigest());
            payload.put("originalPermissionMode", review.originalPermissionMode().name().toLowerCase(Locale.ROOT));
            payload.put("suggestedContextPolicy", review.suggestedContextPolicy().name().toLowerCase(Locale.ROOT));
            emit(run, "plan.review.requested", payload);
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.PlanExecutionBlockedEvent blocked) {
            ObjectNode payload = codec.objectNode();
            payload.put("planId", blocked.planId());
            payload.put("approvedRevision", blocked.approvedRevision());
            payload.put("approvedWorkspaceDigest", blocked.approvedWorkspaceDigest());
            payload.put("currentWorkspaceDigest", blocked.currentWorkspaceDigest());
            payload.put("reason", blocked.reason().name().toLowerCase(Locale.ROOT));
            payload.put("recoveryStatus", blocked.recoveryStatus().name().toLowerCase(Locale.ROOT));
            emit(run, "plan.execution.blocked", payload);
        } else if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.PlanProposalEvent proposal) {
            ObjectNode payload = codec.objectNode();
            payload.put("planId", proposal.planId());
            payload.put("status", proposal.status().name().toLowerCase(Locale.ROOT));
            payload.put("objective", proposal.objective());
            payload.put("workspaceDigest", proposal.workspaceDigest());
            ArrayNode steps = codec.arrayNode();
            proposal.steps().forEach(step -> {
                ObjectNode value = codec.objectNode();
                value.put("ordinal", step.ordinal());
                value.put("title", step.title());
                value.put("detail", step.detail());
                steps.add(value);
            });
            payload.set("steps", steps);
            emit(run, "plan.proposed", payload);
        } else if (envelope.event() instanceof LifecycleEvent.RunFinished finished) {
            if (!run.approvedPlanExecution) {
                emitTerminal(run, finished.result());
            }
        }
    }

    private void emitTerminal(ActiveRun run, AgentRunResult result) {
        emitTerminal(run, result, !run.suppressModelText);
    }

    private void emitTerminal(ActiveRun run, AgentRunResult result, boolean includeFinalText) {
        ObjectNode payload = codec.objectNode();
        payload.put("stopReason", result.stopReason().name().toLowerCase());
        payload.put("modelTurns", result.modelTurns());
        payload.put("toolCalls", result.toolCalls());
        if (includeFinalText) result.finalText().ifPresent(value -> payload.put("finalText", value));
        result.modelFailure().ifPresent(value -> {
            ObjectNode failure = codec.objectNode();
            failure.put("category", value.category().name().toLowerCase(Locale.ROOT));
            value.statusClass().ifPresent(status -> failure.put(
                    "statusClass",
                    status == io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass.CLIENT_ERROR
                            ? "4xx"
                            : "5xx"));
            failure.put("attempts", value.attempts());
            failure.put("receivedOutput", value.receivedOutput());
            payload.set("modelFailure", failure);
        });
        application.telemetry(result.runId())
                .ifPresent(value -> payload.set("telemetry", telemetryPayload(value)));
        String type = switch (result.stopReason()) {
            case COMPLETED -> "run.completed";
            case USER_CANCELLED -> "run.cancelled";
            default -> "run.failed";
        };
        emit(run, type, payload);
        finish(run, result.stopReason() == io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED);
    }

    private ObjectNode telemetryPayload(RunTelemetry telemetry) {
        ObjectNode payload = codec.objectNode();
        payload.put("elapsedMillis", telemetry.elapsed().toMillis());
        payload.put("usageReportedTurns", telemetry.usageReportedTurns());
        payload.put("usageMissingTurns", telemetry.usageMissingTurns());

        ArrayNode modelTurns = codec.arrayNode();
        for (ModelTurnTelemetry turn : telemetry.modelTurns()) {
            ObjectNode item = codec.objectNode();
            item.put("turn", turn.turnNumber());
            item.put("elapsedMillis", turn.elapsed().toMillis());
            item.put("completed", turn.completed());
            turn.finishReason().ifPresent(
                    reason -> item.put("finishReason", reason.name().toLowerCase()));
            turn.usage().ifPresent(usage -> {
                ObjectNode usageNode = codec.objectNode();
                usageNode.put("inputTokens", usage.inputTokens());
                usageNode.put("outputTokens", usage.outputTokens());
                usageNode.put("totalTokens", usage.totalTokens());
                item.set("usage", usageNode);
            });
            modelTurns.add(item);
        }
        payload.set("modelTurns", modelTurns);

        ArrayNode toolCalls = codec.arrayNode();
        for (ToolCallTelemetry call : telemetry.toolCalls()) {
            ObjectNode item = codec.objectNode();
            item.put("ordinal", call.ordinal());
            item.put("elapsedMillis", call.elapsed().toMillis());
            item.put("completed", call.completed());
            toolCalls.add(item);
        }
        payload.set("toolCalls", toolCalls);

        telemetry.totalUsage().ifPresent(usage -> {
            ObjectNode usageNode = codec.objectNode();
            usageNode.put("inputTokens", usage.inputTokens());
            usageNode.put("outputTokens", usage.outputTokens());
            usageNode.put("totalTokens", usage.totalTokens());
            payload.set("totalUsage", usageNode);
        });
        return payload;
    }

    /**
     * 只从 run_command 的结构化执行事实投影退出码，不解析正文或外部诊断文本。
     *
     * @param result 已经过唯一 Tool Pipeline 规范化的结果
     * @return 成功命令的 0，或失败错误 details 中的实际整数退出码
     */
    static Optional<Integer> safeCommandExitCode(
            io.github.liumaishenjian.ccjava.domain.ToolResult result) {
        Objects.requireNonNull(result, "result 不能为空");
        if (!"run_command".equals(result.toolName())) {
            return Optional.empty();
        }
        if (result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
            return Optional.of(0);
        }
        return result.error()
                .map(error -> error.details().values().get("exitCode"))
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .filter(exitCode -> exitCode != -1);
    }

    /**
     * 从 Tool Call 中只提取允许进入展示协议的固定枚举，不暴露查询、路径或其他参数。
     *
     * @param call 原始 Tool Call
     * @return search_text 的安全模式；非搜索或非法模式为空
     */
    static Optional<String> safeToolMode(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        if (!"search_text".equals(call.name())) {
            return Optional.empty();
        }
        try {
            String mode = call.arguments().string("mode")
                    .orElse("content")
                    .toLowerCase(Locale.ROOT);
            return switch (mode) {
                case "content", "files", "count" -> Optional.of(mode);
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 为已通过 Tool 参数校验的内置调用生成有界、面向用户的执行摘要。
     *
     * <p>这里只识别固定 Tool/字段组合；未知、外部或正文型参数保持不可见。摘要只进入当前
     * stdio/TUI 观察面，不写入 Session，也不能影响 Permission 或 Tool 执行。</p>
     *
     * @param call 已通过唯一 Pipeline 参数校验的调用
     * @return 最多 320 个字符的本地展示摘要
     */
    static Optional<String> safeToolActivity(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        JsonObject arguments = call.arguments();
        return switch (call.name()) {
            case "read_file" -> Optional.of(summaryWithTarget("读取", arguments, "path", 180));
            case "list_files" -> Optional.of(summaryWithTarget("枚举", arguments, "path", 180)
                    + arguments.string("glob").map(value -> " · 过滤 " + boundedInline(value, 96)).orElse(""));
            case "search_text" -> arguments.string("query")
                    .map(value -> "搜索 “" + boundedInline(value, 160) + "”"
                            + arguments.string("path").map(path -> " · "
                                    + safeWorkspaceTarget(path, 120)).orElse(""));
            case "git_diff" -> Optional.of("查看 "
                    + arguments.string("mode").orElse("unstaged") + " 变更"
                    + arguments.string("path").map(path -> " · "
                            + safeWorkspaceTarget(path, 140)).orElse(""));
            case "run_command" -> arguments.string("command")
                    .map(command -> "执行 “" + boundedInline(command, 240) + "”");
            case "write_file" -> Optional.of(summaryWithTarget("创建", arguments, "path", 180));
            case "apply_patch" -> Optional.of(summaryWithTarget("修改", arguments, "path", 180));
            case "web_search" -> arguments.string("query")
                    .map(query -> "查询 “" + boundedInline(query, 180) + "”");
            case "revise_plan_artifact" -> Optional.of("更新计划文档");
            case "request_plan_review" -> Optional.of("提交计划审核");
            case "declare_plan_evidence" -> Optional.of("登记计划验证要求");
            default -> Optional.empty();
        };
    }

    private static String summaryWithTarget(
            String verb, JsonObject arguments, String field, int maximumCharacters) {
        return verb + " " + arguments.string(field)
                .map(value -> safeWorkspaceTarget(value, maximumCharacters)).orElse("工作区");
    }

    /**
     * 只允许相对工作区目标进入瞬时展示，避免把主机绝对路径或穿越表达式带到协议层。
     */
    private static String safeWorkspaceTarget(String value, int maximumCharacters) {
        String normalized = boundedInline(value, maximumCharacters).replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:/.*")
                || java.util.Arrays.stream(normalized.split("/"))
                        .anyMatch(segment -> segment.equals(".."))) {
            return "工作区目标";
        }
        return normalized.isBlank() ? "工作区" : normalized;
    }

    private static String boundedInline(String value, int maximumCharacters) {
        String normalized = value.replace("\r\n", " ↵ ").replace('\r', ' ').replace("\n", " ↵ ")
                .replaceAll("[\\p{Cc}&&[^\\t]]", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= maximumCharacters) return normalized;
        int end = normalized.offsetByCodePoints(0, maximumCharacters);
        return normalized.substring(0, end) + "…";
    }

    private void emitUnexpectedFailure(ActiveRun run) {
        boolean failedBeforeStart;
        synchronized (lock) {
            if (activeRun != run) {
                return;
            }
            failedBeforeStart = run.runId == null;
        }
        if (failedBeforeStart) {
            ObjectNode payload = codec.objectNode();
            payload.put("code", "RUNTIME_LAUNCH_FAILED");
            payload.put("stopReason", "internal_error");
            try {
                run.events.emit("run.launch.failed", run.requestId,
                        Optional.of(application.sessionId().value()), Optional.empty(), payload);
            } catch (RuntimeException transportFailure) {
                synchronized (lock) {
                    closeForTransportFailureLocked();
                }
                throw transportFailure;
            }
            finish(run, false);
            return;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("code", "RUNTIME_FAILURE");
        payload.put("stopReason", "internal_error");
        payload.put("modelTurns", 0);
        payload.put("toolCalls", 0);
        emit(run, "run.failed", payload);
        finish(run, false);
    }

    private void emit(ActiveRun run, String type, ObjectNode payload) {
        try {
            run.events.emit(
                    type,
                    run.requestId,
                    Optional.of(application.sessionId().value()),
                    Optional.of(run.runId.value()),
                    payload);
        } catch (RuntimeException failure) {
            synchronized (lock) {
                closeForTransportFailureLocked();
            }
            throw failure;
        }
    }

    /**
     * 把不可继续的事件传输故障收敛为关闭状态。
     *
     * <p>未发送 steering 只存在于本适配器内存，传输失效时不能再启动它们。丢弃事件本身无法可靠
     * 投影，故仅在内存中移除；活动 Run 交给既有取消路径，并禁止其终态后继续调度下一 Run。</p>
     */
    private void closeForTransportFailureLocked() {
        state = State.CLOSED;
        steeringQueue.clear();
        cancelActiveRunLocked();
    }

    private void cancelActiveRunLocked() {
        if (activeRun == null) {
            return;
        }
        if (activeRun.runId != null) {
            application.cancel(activeRun.runId);
            return;
        }
        abortPendingRunLocked(activeRun);
    }

    /**
     * 中止任何尚未越过 acceptance gate 的 Run；durable Plan 还必须归还批准句柄。
     *
     * <p>普通 Run、Plan 规划和 Skill 同样使用该 gate。若 transport 在 accepted 投影期间失败，
     * 只释放 Plan 会让其他 worker 永久阻塞并占住单线程 executor。</p>
     */
    private void abortPendingRunLocked(ActiveRun run) {
        if (!run.commandStart.abort()) {
            return;
        }
        if (run.approvedPlanExecution && run.planAcceptance != null) {
            application.releaseAcceptedPlan(run.planAcceptance);
        }
    }

    /**
     * 中止尚未进入 {@code runAcceptedPlan} 的批准交接，并把 durable APPROVED 句柄交还显式恢复入口。
     *
     * <p>启动闸门只有首次完成者生效：若 worker 已被允许进入 Runtime，本方法不会把正在启动的
     * 执行错误释放；若 transport、shutdown 或 enqueue failure 先发生，则 worker 只退出等待，
     * 不会发布 {@code run.started} 或执行 Tool。</p>
     */
    private void releasePendingAcceptedPlanLocked(ActiveRun run) {
        if (!run.approvedPlanExecution || run.planAcceptance == null) {
            return;
        }
        abortPendingRunLocked(run);
    }

    private static RuntimeException retainFirstFailure(
            RuntimeException first,
            RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * 在唯一 Run 终态已经投影后释放活动状态，并且只在安全边界调度下一条 steering。
     *
     * <p>当前 Run 的终态事件先于下一 Run 的启动；用户取消、关闭或显式丢弃都不会消费未发送
     * 文本，其余终态才可进入下一条。队列中的原始文本始终只停留在本适配器内存，直到被实际启动时
     * 才交给 Runtime。</p>
     */
    private void finish(ActiveRun run, boolean discardQueuedSteering) {
        QueuedSteering next = null;
        synchronized (lock) {
            if (activeRun != run) {
                return;
            }
            activeRun = null;
            if (discardQueuedSteering || state == State.CLOSED) {
                discardSteering(discardQueuedSteering ? DiscardReason.CANCELLED : DiscardReason.SHUTDOWN);
                return;
            }
            state = State.READY;
            next = steeringQueue.pollFirst();
            if (next != null) {
                QueuedSteering steering = next;
                ActiveRun nextRun = startRunLocked(
                        steering.requestId(), steering.message().content().length(), steering.events());
                executor.submit(() -> executeRun(nextRun, steering.message()));
            }
        }
    }

    /**
     * 清除尚未消费的 Surface steering，不触及 Runtime 或任何 durable state。
     *
     * <p>每条已接收消息恰好产生一次不含文本的 discarded 投影；空队列不产生事件，重复清理也不会
     * 重复投影。reason 是固定枚举值，避免将用户输入、路径或其他不可信内容写入 stdout。</p>
     */
    private void discardSteering(DiscardReason reason) {
        RuntimeException failure = null;
        QueuedSteering steering;
        while ((steering = steeringQueue.pollFirst()) != null) {
            ObjectNode payload = codec.objectNode();
            payload.put("reason", reason.wireValue());
            try {
                steering.events().emit("steering.discarded", steering.requestId(), Optional.of(steering.sessionId()),
                        Optional.empty(), payload);
            } catch (RuntimeException emissionFailure) {
                if (failure == null) {
                    failure = emissionFailure;
                }
            }
        }
        if (failure != null) {
            closeForTransportFailureLocked();
            throw failure;
        }
    }

    private String requiredPrompt(StdioProtocol.Command command)
            throws StdioProtocolException {
        JsonNode prompt = command.payload().get("prompt");
        if (prompt == null
                || !prompt.isString()
                || prompt.stringValue().isBlank()
                || prompt.stringValue().length() > HeadlessRuntimeSession.MAX_PROMPT_CHARS
                || prompt.stringValue().codePointCount(0, prompt.stringValue().length()) > HeadlessRuntimeSession.MAX_PROMPT_CHARS
                || prompt.stringValue().getBytes(StandardCharsets.UTF_8).length > HeadlessRuntimeSession.MAX_PROMPT_UTF8_BYTES) {
            throw protocolError(
                    "INVALID_PAYLOAD",
                    command,
                    "run.start.prompt 为空或超过长度限制");
        }
        return prompt.stringValue();
    }

    private void ensureStateNotNewOrClosed(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state == State.NEW || state == State.CLOSED) {
            throw protocolError("INVALID_STATE", command, "session.command 需要已初始化且未关闭的 Session");
        }
    }

    private void ensureState(State expected, StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state != expected) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "命令与当前 Application 状态不兼容");
        }
    }

    private void ensureStateReadyOrRunning(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state != State.READY && state != State.RUNNING) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "run.start 需要已初始化且未关闭的 Session");
        }
    }

    private void requireSession(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (command.sessionId().isEmpty()
                || !application.sessionId().value().equals(command.sessionId().orElseThrow())) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "命令 Session 与当前连接不匹配");
        }
    }

    private void requireNoRunId(StdioProtocol.Command command) throws StdioProtocolException {
        if (command.runId().isPresent()) {
            throw protocolError("INVALID_STATE", command, "Checkpoint 命令不能携带 Run ID");
        }
    }

    private String requiredCheckpointId(StdioProtocol.Command command)
            throws StdioProtocolException {
        JsonNode value = command.payload().get("checkpointId");
        if (value == null
                || !value.isString()
                || value.stringValue().isBlank()
                || value.stringValue().length() > 128) {
            throw protocolError("INVALID_PAYLOAD", command, "checkpointId 为空或超过长度限制");
        }
        try {
            return new io.github.liumaishenjian.ccjava.domain.CheckpointId(
                            value.stringValue())
                    .value();
        } catch (IllegalArgumentException invalid) {
            throw protocolError("INVALID_PAYLOAD", command, "checkpointId 格式无效");
        }
    }

    private StdioProtocolException protocolError(
            String code,
            StdioProtocol.Command command,
            String message) {
        return new StdioProtocolException(code, command.requestId(), message);
    }

    @Override
    public void close() throws InterruptedException {
        RuntimeException failure = null;
        try {
            synchronized (lock) {
                state = State.CLOSED;
                if (inputAssembly != null) failAssemblyLocked(inputAssembly, InputTerminal.CANCELLED);
                cancelActiveRunLocked();
                discardSteering(DiscardReason.SHUTDOWN);
            }
        } catch (RuntimeException cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            approvals.close();
            questions.close();
        } catch (RuntimeException closeFailure) {
            failure = retainFirstFailure(failure, closeFailure);
        }
        assemblyScheduler.close();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    failure = retainFirstFailure(failure,
                            new IllegalStateException("Runtime Run Executor 未退出"));
                }
            }
        } finally {
            if (state != State.NEW) {
                try {
                    application.close();
                } catch (RuntimeException closeFailure) {
                    failure = retainFirstFailure(failure, closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface ExpiryHandle {
        void cancel();
    }

    interface InputAssemblyScheduler extends AutoCloseable {
        ExpiryHandle schedule(Duration delay, Runnable task);

        static InputAssemblyScheduler production() {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("cc-java-input-expiry").daemon(true).factory());
            return new InputAssemblyScheduler() {
                @Override
                public ExpiryHandle schedule(Duration delay, Runnable task) {
                    ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                }

                @Override
                public void close() {
                    executor.shutdownNow();
                }
            };
        }

        @Override
        void close();
    }

    private enum InputTerminal {
        COMPLETED,
        FAILED,
        EXPIRED,
        CANCELLED
    }

    private enum State {
        NEW,
        READY,
        RUNNING,
        CLOSED
    }

    /** steering 丢弃原因只用于内部状态转换，禁止携带用户文本。 */
    private enum DiscardReason {
        CLEAR("clear"),
        CANCELLED("cancelled"),
        SESSION_SWITCH("session_switch"),
        SHUTDOWN("shutdown");

        private final String wireValue;

        DiscardReason(String wireValue) {
            this.wireValue = wireValue;
        }

        private String wireValue() {
            return wireValue;
        }
    }

    /**
     * 尚未送入 Runtime 的单条 Surface 输入。
     *
     * <p>该对象不进入 AgentEvent、Canonical Transcript、Session JSONL 或 Checkpoint；其文本仅在
     * 当前 Run 正常终态后的安全边界被消费一次。</p>
     */
    private record QueuedSteering(
            String requestId,
            String sessionId,
            io.github.liumaishenjian.ccjava.domain.UserMessage message,
            StdioProtocol.EventEmitter events) {
        private QueuedSteering {
            Objects.requireNonNull(requestId, "requestId 不能为空");
            Objects.requireNonNull(sessionId, "sessionId 不能为空");
            Objects.requireNonNull(message, "message 不能为空");
            Objects.requireNonNull(events, "events 不能为空");
        }
    }

    private static final class InputAssembly {
        private final String requestId;
        private final String inputId;
        private final int byteCount;
        private final int chunkCount;
        private final String sha256;
        private final Instant deadline;
        private final java.io.ByteArrayOutputStream bytes;
        private int receivedChunks;

        private InputAssembly(
                String requestId,
                String inputId,
                int byteCount,
                int chunkCount,
                String sha256,
                Instant deadline,
                java.io.ByteArrayOutputStream bytes) {
            this.requestId = requestId;
            this.inputId = inputId;
            this.byteCount = byteCount;
            this.chunkCount = chunkCount;
            this.sha256 = sha256;
            this.deadline = deadline;
            this.bytes = bytes;
        }
    }

    /**
     * 把 executor 接受与 stdio accepted 事件发布拆成两个确定性阶段。
     *
     * <p>{@code true} 只在 accepted 已成功进入事件出口后完成；{@code false} 表示 transport、关闭或
     * 入队失败已回收批准句柄。worker 不使用 timeout 或轮询，因此不存在慢机器上的假失败。</p>
     */
    private static final class CommandStartGate {
        private final java.util.concurrent.CompletableFuture<Boolean> decision =
                new java.util.concurrent.CompletableFuture<>();

        private boolean start() {
            return decision.complete(true);
        }

        private boolean abort() {
            return decision.complete(false);
        }

        private boolean awaitStart() {
            return decision.join();
        }
    }

    /**
     * acceptance 输出的成败已经不可由 Server 判定，禁止再补发第二个 rejected disposition。
     *
     * <p>该异常只跨越同包 stdio composition boundary；Server 应直接关闭 transport，交由 Client
     * watchdog/Session recovery 收敛 outcome-unknown 状态。</p>
     */
    static final class AcceptedRunTransportException extends RuntimeException {
        AcceptedRunTransportException(RuntimeException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final class ActiveRun {
        private final String requestId;
        private final int promptChars;
        private final StdioProtocol.EventEmitter events;
        private final Map<Integer, String> toolModes = new LinkedHashMap<>();
        private final CommandStartGate commandStart = new CommandStartGate();
        private RunId runId;
        private boolean suppressModelText;
        private boolean approvedPlanExecution;
        private HeadlessRuntimeSession.PlanExecutionAcceptance planAcceptance;

        private ActiveRun(
                String requestId,
                int promptChars,
                StdioProtocol.EventEmitter events) {
            this.requestId = requestId;
            this.promptChars = promptChars;
            this.events = events;
        }
    }
}
