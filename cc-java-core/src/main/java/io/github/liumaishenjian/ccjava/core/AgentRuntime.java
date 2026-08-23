package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.hook.HookAggregateResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 驱动单次 Agent Run 中的模型回合与 Tool 回合。
 *
 * <p>该类型是 Core 唯一公开的 Agent Loop 入口。它只负责状态迁移、规范消息
 * 协议、预算和终止判断，不直接访问文件系统、调用终端 UI 或执行具体 Tool。
 * 所有 Tool Call 必须交给 {@link ToolExecutionPipeline}。</p>
 *
 * <p>S01 使用普通同步控制流：追加一条 User Message，请求聚合后的 Model
 * Turn；若无 Tool Call 则完成，否则把 Assistant Message 连同全部调用追加
 * 一次，按顺序得到一一对应的 Tool Result，再请求下一回合。</p>
 *
 * @since 0.1.0
 */
public final class AgentRuntime {

    private final SessionStore sessionStore;
    private final AgentIdGenerator idGenerator;
    private final ModelGateway modelGateway;
    private final ContextAssembler contextAssembler;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionPipeline toolPipeline;
    private final LifecycleDispatcher lifecycle;
    private final SessionJournal sessionJournal;
    private final ContextPreparationService contextPreparation;
    private final MemoryContextService memoryContext;
    private final InstructionContextService instructionContext;
    private final HookCoordinator hooks;
    private final SkillRunCoordinator skills;
    private final PluginRunCoordinator plugins;
    private final PluginRunHooks pluginHooks;
    private final FinalAssistantHandler finalAssistantHandler;
    private final io.github.liumaishenjian.ccjava.core.subagent.ParallelToolBatchExecutor parallelToolBatch;
    private final ConcurrentMap<SessionId, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final java.util.Set<Thread> modelWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 创建显式 Agent Runtime。
     *
     * @param sessionStore    当前进程的 Session Store
     * @param idGenerator     Run ID 来源
     * @param modelGateway    单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry    当前可见 Tool Registry
     * @param toolPipeline    统一 Tool 执行管线
     * @param lifecycle       生命周期分发器
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle) {
        this(
                sessionStore,
                idGenerator,
                modelGateway,
                contextAssembler,
                toolRegistry,
                toolPipeline,
                lifecycle,
                SessionJournal.noop(),
                ContextPreparationService.noop(),
                MemoryContextService.noop(),
                InstructionContextService.noop(),
                HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(),
                PluginRunCoordinator.disabled(),
                PluginRunHooks.none());
    }

    /**
     * 创建接入 durable Session journal 的显式 Agent Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal) {
        this(
                sessionStore,
                idGenerator,
                modelGateway,
                contextAssembler,
                toolRegistry,
                toolPipeline,
                lifecycle,
                sessionJournal,
                ContextPreparationService.noop(),
                MemoryContextService.noop(),
                InstructionContextService.noop(),
                HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(),
                PluginRunCoordinator.disabled(),
                PluginRunHooks.none());
    }

    /**
     * 创建显式启用短生命周期 Context Projection 的 Agent Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与 Run 终态清理服务
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation) {
        this(
                sessionStore,
                idGenerator,
                modelGateway,
                contextAssembler,
                toolRegistry,
                toolPipeline,
                lifecycle,
                sessionJournal,
                contextPreparation,
                MemoryContextService.noop(),
                InstructionContextService.noop(),
                HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(),
                PluginRunCoordinator.disabled(),
                PluginRunHooks.none());
    }

    /**
     * 创建同时启用 Context Projection 与 ready-only Memory Projection 的 Runtime。
     *
     * <p>Memory 服务只参与单次模型请求 Projection，不进入 Canonical Session、Journal 或
     * Permission/Tool 执行路径。旧构造器均传入 no-op，保持 S01-S06 和既有 S07 行为。</p>
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与 Run 终态清理服务
     * @param memoryContext 每回合预取、唯一消费与非阻塞清理服务
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext) {
        this(
                sessionStore,
                idGenerator,
                modelGateway,
                contextAssembler,
                toolRegistry,
                toolPipeline,
                lifecycle,
                sessionJournal,
                contextPreparation,
                memoryContext,
                InstructionContextService.noop(),
                HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(),
                PluginRunCoordinator.disabled(),
                PluginRunHooks.none());
    }

    /**
     * 创建同时启用 Instructions、Memory 与 Context 短生命周期 Projection 的 Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与 Run 终态清理服务
     * @param memoryContext 每回合 ready-only Memory Projection 服务
     * @param instructionContext 每回合 Instructions Projection 与成功 Tool 激活服务
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext) {
        this(
                sessionStore,
                idGenerator,
                modelGateway,
                contextAssembler,
                toolRegistry,
                toolPipeline,
                lifecycle,
                sessionJournal,
                contextPreparation,
                memoryContext,
                instructionContext,
                HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(),
                PluginRunCoordinator.disabled(),
                PluginRunHooks.none());
    }

    /**
     * 创建同时接入 S09 生命周期 Hook 的 Runtime。
     *
     * <p>Hook 只接收有界、脱敏摘要；它不能替代 Agent Loop、Session Journal、Permission
     * 或 Tool Pipeline。User Prompt Hook 是 Run 建立前唯一可阻断的 Runtime 入口，其他
     * Session/Run/Model 事件默认只观察。</p>
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与 Run 终态清理服务
     * @param memoryContext 每回合 ready-only Memory Projection 服务
     * @param instructionContext 每回合 Instructions Projection 服务
     * @param hooks S09 Hook 协调器
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks) {
        this(sessionStore, idGenerator, modelGateway, contextAssembler, toolRegistry, toolPipeline, lifecycle,
                sessionJournal, contextPreparation, memoryContext, instructionContext, hooks,
                SkillRunCoordinator.disabled(), PluginRunCoordinator.disabled(), PluginRunHooks.none());
    }

    /**
     * 创建同时接入 S11 Run scoped Skill Projection 的 Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与清理服务
     * @param memoryContext ready-only Memory Projection 服务
     * @param instructionContext Instructions Projection 服务
     * @param hooks S09 Hook 协调器
     * @param skills Skill 双入口、投影与 Tool visibility 协调器
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            SkillRunCoordinator skills) {
        this(sessionStore, idGenerator, modelGateway, contextAssembler, toolRegistry, toolPipeline, lifecycle,
                sessionJournal, contextPreparation, memoryContext, instructionContext, hooks, skills,
                PluginRunCoordinator.disabled(), PluginRunHooks.none());
    }

    /**
     * 创建同时捕获 S11 Plugin snapshot Run lease 的 Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与清理服务
     * @param memoryContext ready-only Memory Projection 服务
     * @param instructionContext Instructions Projection 服务
     * @param hooks S09 Hook 协调器
     * @param skills Skill Run 协调器
     * @param plugins Plugin generation Run 生命周期协调器
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            SkillRunCoordinator skills,
            PluginRunCoordinator plugins) {
        this(sessionStore, idGenerator, modelGateway, contextAssembler, toolRegistry, toolPipeline, lifecycle,
                sessionJournal, contextPreparation, memoryContext, instructionContext, hooks, skills, plugins,
                PluginRunHooks.none());
    }

    /**
     * 创建同时接入受信 Plugin Run-scoped Hook templates 的完整 Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 可失败的观察生命周期分发器
     * @param sessionJournal 必须成功的规范 Session journal
     * @param contextPreparation 每回合 Projection 准备与清理服务
     * @param memoryContext ready-only Memory Projection 服务
     * @param instructionContext Instructions Projection 服务
     * @param hooks S09 Hook 协调器
     * @param skills Skill Run 协调器
     * @param plugins Plugin generation Run 生命周期协调器
     * @param pluginHooks Plugin Run-scoped Hook templates
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            SkillRunCoordinator skills,
            PluginRunCoordinator plugins,
            PluginRunHooks pluginHooks) {
        this(sessionStore, idGenerator, modelGateway, contextAssembler, toolRegistry, toolPipeline, lifecycle,
                sessionJournal, contextPreparation, memoryContext, instructionContext, hooks, skills, plugins,
                pluginHooks, FinalAssistantHandler.acceptAll());
    }

    /**
     * 创建带最终 Assistant 确定性处理器的完整 Runtime。
     *
     * <p>该接缝只供需要在 RunFinished 前验证结构化模型终态的宿主使用；Tool Loop、Context、
     * Session 和取消仍由本 Runtime 唯一拥有。</p>
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            ContextPreparationService contextPreparation,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            SkillRunCoordinator skills,
            PluginRunCoordinator plugins,
            PluginRunHooks pluginHooks,
            FinalAssistantHandler finalAssistantHandler) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore 不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway 不能为空");
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.toolPipeline = Objects.requireNonNull(toolPipeline, "toolPipeline 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        this.sessionJournal = Objects.requireNonNull(
                sessionJournal, "sessionJournal 不能为空");
        this.contextPreparation = Objects.requireNonNull(
                contextPreparation, "contextPreparation 不能为空");
        this.memoryContext = Objects.requireNonNull(
                memoryContext, "memoryContext 不能为空");
        this.instructionContext = Objects.requireNonNull(
                instructionContext, "instructionContext 不能为空");
        this.hooks = Objects.requireNonNull(hooks, "hooks 不能为空");
        this.skills = Objects.requireNonNull(skills, "skills 不能为空");
        this.plugins = Objects.requireNonNull(plugins, "plugins 不能为空");
        this.pluginHooks = Objects.requireNonNull(pluginHooks, "pluginHooks 不能为空");
        this.finalAssistantHandler = Objects.requireNonNull(finalAssistantHandler, "finalAssistantHandler 不能为空");
        this.parallelToolBatch = new io.github.liumaishenjian.ccjava.core.subagent.ParallelToolBatchExecutor(
                toolRegistry, toolPipeline,
                java.util.Set.of("read_file", "list_files", "search_text", "git_status", "git_diff"), 4);
    }

    /**
     * 在已创建的 Session 中执行一条用户消息，直到唯一终态。
     *
     * @param sessionId 目标 Session
     * @param request   用户消息和本次 Run 限制
     * @return Run 终态摘要
     * @throws IllegalArgumentException Session 不存在时抛出
     * @throws IllegalStateException Session 已关闭或已有活动 Run 时抛出
     */
    public AgentRunResult run(SessionId sessionId, AgentRunRequest request) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        AgentSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session 不存在: " + sessionId.value()));
        session.ensureRunnable();
        RunId runId = Objects.requireNonNull(idGenerator.newRunId(), "newRunId 返回 null");
        AgentRunState state = new AgentRunState(sessionId, runId, request.limits());
        CancellationSource cancellation = new CancellationSource(request.limits().maxDuration());

        HookAggregateResult promptHook = hooks.evaluate(
                new HookInvocation(
                        HookEventKind.USER_PROMPT,
                        sessionId,
                        Optional.of(runId),
                        "user_prompt",
                        new JsonObject(Map.of(
                                "sessionId", sessionId.value(),
                                "messageCharacters", request.userMessage().content()
                                        .codePointCount(0, request.userMessage().content().length())))),
                cancellation.token());
        if (promptHook.blocking()) {
            return AgentRunResult.stopped(
                    sessionId,
                    runId,
                    StopReason.HOOK_BLOCKED,
                    0,
                    0);
        }

        sessionJournal.runStarted(sessionId, runId, request.userMessage());
        session.beginRun(runId, request.userMessage());
        ActiveRun activeRun = new ActiveRun(
                runId,
                cancellation,
                new AtomicReference<>());
        if (activeRuns.putIfAbsent(sessionId, activeRun) != null) {
            session.endRun(runId);
            throw new IllegalStateException("Session 已有活动 Run");
        }
        lifecycle.dispatch(session, runId, new LifecycleEvent.RunStarted(request));
        AutoCloseable runHookLease = () -> { };
        try {
            plugins.openRun(runId);
            runHookLease = hooks.bindRun(runId,
                    pluginHooks.bindings(runId, plugins.fingerprints(runId)));
        } catch (RuntimeException pluginFailure) {
            activeRuns.remove(sessionId, activeRun);
            plugins.closeRun(runId);
            sessionJournal.runCompleted(sessionId, runId, StopReason.INTERNAL_ERROR);
            session.endRun(runId);
            AgentRunResult rejected = AgentRunResult.stopped(
                    sessionId, runId, StopReason.INTERNAL_ERROR, 0, 0);
            lifecycle.dispatch(session, runId, new LifecycleEvent.RunFinished(rejected));
            return rejected;
        }
        if (request.explicitSkill().isPresent()) {
            var explicit = request.explicitSkill().orElseThrow();
            var activation = skills.invokeExplicit(sessionId,
                    new io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationRequest(
                            runId, explicit.skillId(),
                            io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind.EXPLICIT,
                            explicit.arguments()),
                    cancellation.token());
            if (!activation.succeeded()) {
                activeRuns.remove(sessionId, activeRun);
                skills.closeRun(runId);
                closeRunHooks(runHookLease);
                plugins.closeRun(runId);
                sessionJournal.runCompleted(sessionId, runId, StopReason.HOOK_BLOCKED);
                session.endRun(runId);
                AgentRunResult rejected = AgentRunResult.stopped(
                        sessionId, runId, StopReason.HOOK_BLOCKED, 0, 0);
                lifecycle.dispatch(session, runId, new LifecycleEvent.RunFinished(rejected));
                return rejected;
            }
        }
        hooks.evaluate(
                new HookInvocation(
                        HookEventKind.RUN_START,
                        sessionId,
                        Optional.of(runId),
                        "run",
                        new JsonObject(Map.of(
                                "sessionId", sessionId.value(),
                                "runId", runId.value()))),
                cancellation.token());
        Thread deadlineThread = startDeadline(request.limits().maxDuration(), activeRun);

        AgentRunResult result;
        AutoReviewRunScope autoReviewScope = toolPipeline.createRunScope(runId);
        try {
            result = executeLoop(
                    session, runId, state, activeRun, request.userMessage(), autoReviewScope);
        } catch (RuntimeException exception) {
            result = state.stop(StopReason.INTERNAL_ERROR);
        } finally {
            deadlineThread.interrupt();
            activeRuns.remove(sessionId, activeRun);
            contextPreparation.closeRun(runId);
            autoReviewScope.close();
            toolPipeline.closeRunGovernance(runId);
            skills.closeRun(runId);
            closeRunHooks(runHookLease);
            plugins.closeRun(runId);
            hooks.clearTransientContext(runId);
        }

        try {
            sessionJournal.runCompleted(sessionId, runId, result.stopReason());
        } catch (RuntimeException journalFailure) {
            session.fence();
            result = AgentRunResult.stopped(
                    sessionId,
                    runId,
                    StopReason.INTERNAL_ERROR,
                    result.modelTurns(),
                    result.toolCalls());
        }
        session.endRun(runId);
        lifecycle.dispatch(session, runId, new LifecycleEvent.RunFinished(result));
        hooks.evaluate(
                new HookInvocation(
                        HookEventKind.RUN_END,
                        sessionId,
                        Optional.of(runId),
                        "run",
                        new JsonObject(Map.of(
                                "sessionId", sessionId.value(),
                                "runId", runId.value(),
                                "stopReason", result.stopReason().name(),
                                "modelTurns", result.modelTurns(),
                                "toolCalls", result.toolCalls()))),
                CancellationToken.none());
        return result;
    }

    /**
     * 取消指定 Session 中与 Run ID 精确匹配的活动执行。
     *
     * <p>该方法只传播取消信号，不直接写入终态。执行线程观察到信号后会产生唯一的
     * {@link StopReason#USER_CANCELLED} 终态；不匹配或已经结束的请求不会影响后续 Run。</p>
     *
     * @param sessionId 活动 Run 所属 Session
     * @param runId 客户端已观察到的精确 Run ID
     * @return 是否由本次调用首次触发了匹配 Run 的取消
     */
    public boolean cancel(SessionId sessionId, RunId runId) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        ActiveRun activeRun = activeRuns.get(sessionId);
        return activeRun != null
                && activeRun.runId().equals(runId)
                && activeRun.requestStop(StopReason.USER_CANCELLED);
    }

    /**
     * 返回是否仍有忽略取消的模型调用工作线程尚未退出。
     *
     * <p>Run 可以在 deadline 后先形成唯一终态，但 Session/Application 关闭必须继续把仍持有
     * Provider 资源的工作线程视为未 drain，避免提前关闭后丢失可重试清理语义。</p>
     *
     * @return 至少一个模型工作线程仍存活时为 {@code true}
     */
    public boolean hasInFlightModelWork() {
        return !modelWorkers.isEmpty();
    }

    /**
     * 在宿主关闭期限耗尽后解除对不合作模型 worker 的进程存活所有权。
     *
     * <p>先再次中断所有 worker，再从 drain 集合移除。模型 worker 使用 daemon virtual thread，
     * 因此不会阻止 JVM 退出；调用方必须同时关闭底层 Provider Client。迟到结果仍因原 Run
     * CancellationToken 已取消而不能形成第二终态或写入 Session。</p>
     */
    public void abandonInFlightModelWork() {
        for (Thread worker : modelWorkers) {
            worker.interrupt();
        }
        modelWorkers.clear();
    }

    private AgentRunResult executeLoop(
            AgentSession session,
            RunId runId,
            AgentRunState state,
            ActiveRun activeRun,
            UserMessage currentUserMessage,
            AutoReviewRunScope autoReviewScope) {
        while (true) {
            if (activeRun.cancellation().token().isCancellationRequested()) {
                return stopForCancellation(state, activeRun);
            }
            java.util.Optional<io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason> turnBudget =
                    state.ensureModelBudget();
            if (turnBudget.isPresent()) {
                lifecycle.dispatch(session, runId, new LifecycleEvent.BudgetGoverned(
                        turnBudget.orElseThrow(), state.modelTurns(), state.toolCalls(),
                        state.effectiveModelLimit(), state.effectiveToolLimit()));
                if (turnBudget.orElseThrow()
                        != io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.PROGRESS_EXTENDED) {
                    return state.stop(StopReason.TURN_LIMIT_REACHED);
                }
            }

            int turnNumber = state.recordModelTurnAttempt();
            MemoryContextService.TurnPrefetch memoryPrefetch = memoryContext.start(
                    currentUserMessage,
                    activeRun.cancellation().token());
            try {
                lifecycle.dispatch(
                        session,
                        runId,
                        new LifecycleEvent.ModelTurnStarted(turnNumber));
                hooks.evaluate(
                        new HookInvocation(
                                HookEventKind.MODEL_TURN_START,
                                session.id(),
                                Optional.of(runId),
                                "turn-" + turnNumber,
                                new JsonObject(Map.of("turnNumber", turnNumber))),
                        activeRun.cancellation().token());
                ModelRequest canonicalRequest = contextAssembler.assemble(
                        session,
                        runId,
                        turnNumber,
                        toolRegistry.definitions());
                ModelRequest withHookContext = hooks.projectTransientContext(canonicalRequest);
                ModelRequest withSkillContext = skills.project(withHookContext);
                ModelRequest withInstructions = instructionContext.project(
                        withSkillContext,
                        activeRun.cancellation().token());
                ModelRequest withMemory = memoryContext.consumeReady(
                        withInstructions,
                        currentUserMessage,
                        memoryPrefetch);
                ModelRequest modelRequest = contextPreparation.prepare(
                        withMemory,
                        activeRun.cancellation().token());
                if (activeRun.cancellation().token().isCancellationRequested()) {
                    return stopForCancellation(state, activeRun);
                }
                ModelTurn modelTurn;
                try {
                    modelTurn = contextPreparation.executePrepared(
                            modelRequest,
                            activeRun.cancellation().token(),
                            request -> completeModelTurn(
                                    session,
                                    runId,
                                    turnNumber,
                                    request,
                                    activeRun.cancellation()));
                } catch (ModelGatewayException exception) {
                    if (activeRun.cancellation().token().isCancellationRequested()) {
                        return stopForCancellation(state, activeRun);
                    }
                    return state.stop(stopReasonFor(exception), exception.summary());
                } catch (RuntimeException exception) {
                    if (activeRun.cancellation().token().isCancellationRequested()) {
                        return stopForCancellation(state, activeRun);
                    }
                    return state.stop(StopReason.MODEL_ERROR);
                }
                if (activeRun.cancellation().token().isCancellationRequested()) {
                    return stopForCancellation(state, activeRun);
                }
                if (modelTurn == null) {
                    return state.stop(StopReason.INVALID_MODEL_RESPONSE);
                }
                lifecycle.dispatch(
                        session,
                        runId,
                        new LifecycleEvent.ModelTurnCompleted(turnNumber, modelTurn));
                hooks.evaluate(
                        new HookInvocation(
                                HookEventKind.MODEL_TURN_END,
                                session.id(),
                                Optional.of(runId),
                                "turn-" + turnNumber,
                                new JsonObject(Map.of(
                                        "turnNumber", turnNumber,
                                        "finishReason", modelTurn.metadata().finishReason().name()))),
                        activeRun.cancellation().token());

                if (modelTurn.metadata().finishReason() == ModelFinishReason.LENGTH) {
                    return state.stop(StopReason.OUTPUT_LIMIT_REACHED);
                }
                AssistantMessage assistant = modelTurn.assistantMessage();
                if (assistant.isEmpty()) {
                    return state.stop(StopReason.INVALID_MODEL_RESPONSE);
                }

                List<ToolCall> calls = assistant.toolCalls();
                if (!hasValidToolCallIds(session, calls)) {
                    return state.stop(StopReason.INVALID_MODEL_RESPONSE);
                }
                if (calls.isEmpty()) {
                    FinalAssistantDecision finalDecision = finalAssistantHandler.decide(session.id(), runId, assistant);
                    if (finalDecision.outcome() == FinalAssistantDecision.Outcome.REJECT) {
                        return state.stop(StopReason.INVALID_MODEL_RESPONSE);
                    }
                    if (finalDecision.outcome() == FinalAssistantDecision.Outcome.CONTINUE) {
                        continue;
                    }
                    appendAssistant(session, runId, assistant);
                    return state.complete(assistant.text());
                }
                java.util.Optional<io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason> toolBudget =
                        state.ensureToolBatchBudget(calls.size());
                if (toolBudget.isPresent()) {
                    lifecycle.dispatch(session, runId, new LifecycleEvent.BudgetGoverned(
                            toolBudget.orElseThrow(), state.modelTurns(), state.toolCalls(),
                            state.effectiveModelLimit(), state.effectiveToolLimit()));
                    if (toolBudget.orElseThrow()
                            != io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.PROGRESS_EXTENDED) {
                        return state.stop(StopReason.TOOL_LIMIT_REACHED);
                    }
                }

                appendAssistant(session, runId, assistant);
                List<Integer> ordinals = new java.util.ArrayList<>(calls.size());
                for (int ignored = 0; ignored < calls.size(); ignored++) {
                    ordinals.add(state.recordToolCall());
                }
                ToolBatchExecutionResult batchExecution;
                try {
                    batchExecution = parallelToolBatch.executeBatch(
                            session, runId, ordinals, calls, activeRun.cancellation().token(), autoReviewScope);
                } catch (ToolJournalPersistenceException journalFailure) {
                    session.fence();
                    return state.stop(StopReason.INTERNAL_ERROR);
                } catch (RuntimeException exception) {
                    session.fence();
                    return state.stop(StopReason.INTERNAL_ERROR);
                }
                List<ToolResult> batchResults = batchExecution.results();
                state.recordBatchResults(batchResults);
                if (batchResults.size() != calls.size()) {
                    session.fence();
                    return state.stop(StopReason.INTERNAL_ERROR);
                }
                for (int index = 0; index < calls.size(); index++) {
                    ToolCall call = calls.get(index);
                    ToolResult result = batchResults.get(index);
                    if (!call.id().equals(result.callId()) || !call.name().equals(result.toolName())) {
                        session.fence();
                        return state.stop(StopReason.INTERNAL_ERROR);
                    }
                    session.appendToolResult(new ToolResultMessage(result));
                    if (result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
                        try {
                            instructionContext.recordSuccessfulTool(call, result, activeRun.cancellation().token());
                        } catch (RuntimeException ignored) {
                            // Instructions 刷新是短生命周期投影旁路，不能推翻已持久化的 Tool 成功事实。
                        }
                    }
                }
                if (batchExecution.stopAfterBatch()) {
                    return state.stop(StopReason.AUTO_REVIEW_CIRCUIT_OPEN);
                }
                if (state.repeatedFailureCircuitOpen()) {
                    return state.stop(StopReason.TOOL_ERROR);
                }
            } finally {
                memoryPrefetch.close();
            }
        }
    }

    private static void closeRunHooks(AutoCloseable lease) {
        try {
            lease.close();
        } catch (Exception ignored) {
            // Hook lease 只做内存解绑；失败不能跳过后续 Plugin lease 清理或泄漏异常文本。
        }
    }

    private void appendAssistant(
            AgentSession session,
            RunId runId,
            AssistantMessage assistant) {
        sessionJournal.assistantAppended(session.id(), runId, assistant);
        session.appendAssistant(assistant);
    }

    /**
     * 在独立虚拟线程执行可能阻塞在创建 Publisher 之前的模型调用。
     *
     * <p>Run 线程只等待一个可由 CancellationToken 结算的 Future；timeout、Ctrl+C 或 logout 会先
     * cancel(true) 中断底层调用，再让 Agent Loop 产生唯一终态。Provider/SDK 若完全忽略线程中断，
     * Runtime 仍能按 deadline 返回，但 Adapter 还必须依靠自己的 request timeout 和 close 释放 I/O。</p>
     */
    private ModelTurn completeModelTurn(
            AgentSession session,
            RunId runId,
            int turnNumber,
            ModelRequest modelRequest,
            CancellationSource cancellation) throws ModelGatewayException {
        java.util.concurrent.FutureTask<ModelTurn> task = new java.util.concurrent.FutureTask<>(
                () -> invokeModelTurn(session, runId, turnNumber, modelRequest, cancellation));
        Thread worker = Thread.ofVirtual()
                .name("cc-java-model-turn-" + runId.value() + "-" + turnNumber)
                .unstarted(() -> {
                    try {
                        task.run();
                    } finally {
                        modelWorkers.remove(Thread.currentThread());
                    }
                });
        modelWorkers.add(worker);
        try (CancellationToken.Registration ignored = cancellation.token().onCancellation(
                () -> task.cancel(true))) {
            worker.start();
            try {
                return task.get();
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.CANCELLED, "Model request cancelled");
            } catch (InterruptedException interrupted) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.CANCELLED, "Model request interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof ModelGatewayException modelFailure) {
                    throw modelFailure;
                }
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.PERMANENT, "Model request failed", cause);
            }
        }
    }

    private ModelTurn invokeModelTurn(
            AgentSession session,
            RunId runId,
            int turnNumber,
            ModelRequest modelRequest,
            CancellationSource cancellation) throws ModelGatewayException {
        if (modelGateway instanceof StreamingModelGateway streamingGateway) {
            AtomicBoolean emittedText = new AtomicBoolean();
            try {
                return streamingGateway.complete(
                        modelRequest,
                        new ModelStreamObserver() {
                            @Override
                            public void onTextDelta(String delta) {
                                emittedText.set(true);
                                if (!cancellation.token().isCancellationRequested()) {
                                    lifecycle.dispatch(
                                            session,
                                            runId,
                                            new ModelTextDelta(turnNumber, delta));
                                }
                            }

                            @Override
                            public void onAttemptStarted(int attempt, int maxAttempts) {
                                lifecycle.dispatch(
                                        session,
                                        runId,
                                        new LifecycleEvent.ModelAttemptStarted(
                                                turnNumber, attempt, maxAttempts));
                            }

                            @Override
                            public void onRetryScheduled(
                                    int failedAttempt,
                                    int nextAttempt,
                                    int maxAttempts,
                                    java.time.Duration delay,
                                    io.github.liumaishenjian.ccjava.domain.ModelFailureCategory category) {
                                lifecycle.dispatch(
                                        session,
                                        runId,
                                        new LifecycleEvent.ModelRetryScheduled(
                                                turnNumber,
                                                failedAttempt,
                                                nextAttempt,
                                                maxAttempts,
                                                delay.toMillis(),
                                                category));
                            }
                        },
                        cancellation.token());
            } catch (ModelGatewayException failure) {
                if (emittedText.get()
                        && failure.kind()
                                == ModelGatewayException.FailureKind.CONTEXT_OVERFLOW) {
                    throw new ModelGatewayException(
                            ModelGatewayException.FailureKind.INCOMPLETE_STREAM,
                            "Model stream failed after publishing output",
                            failure);
                }
                throw failure;
            }
        }
        return modelGateway.complete(modelRequest);
    }

    private static Thread startDeadline(
            java.time.Duration maxDuration,
            ActiveRun activeRun) {
        return Thread.ofVirtual()
                .name("cc-java-run-deadline-" + activeRun.runId().value())
                .start(() -> {
                    try {
                        Thread.sleep(maxDuration);
                        activeRun.requestStop(StopReason.TIME_LIMIT_REACHED);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    private static AgentRunResult stopForCancellation(
            AgentRunState state,
            ActiveRun activeRun) {
        StopReason reason = activeRun.stopReason().getAcquire();
        return state.stop(Objects.requireNonNullElse(
                reason,
                StopReason.USER_CANCELLED));
    }

    private static StopReason stopReasonFor(ModelGatewayException exception) {
        return switch (exception.kind()) {
            case INCOMPLETE_STREAM -> StopReason.INCOMPLETE_MODEL_STREAM;
            case RETRY_EXHAUSTED -> StopReason.MODEL_RETRY_EXHAUSTED;
            case CONTEXT_OVERFLOW -> StopReason.CONTEXT_LIMIT_REACHED;
            default -> StopReason.MODEL_ERROR;
        };
    }

    private boolean hasValidToolCallIds(AgentSession session, List<ToolCall> calls) {
        Set<String> batchIds = new HashSet<>();
        for (ToolCall call : calls) {
            if (!batchIds.add(call.id()) || session.hasToolCallId(call.id())) {
                return false;
            }
        }
        return true;
    }

    private ToolResult internalToolFailure(ToolCall call) {
        return ToolResult.failure(
                call.id(),
                call.name(),
                ToolError.of(
                        ToolErrorCode.INTERNAL_ERROR,
                        "Tool Pipeline 发生内部错误"));
    }

    private record ActiveRun(
            RunId runId,
            CancellationSource cancellation,
            AtomicReference<StopReason> stopReason) {

        private boolean requestStop(StopReason reason) {
            Objects.requireNonNull(reason, "reason 不能为空");
            if (reason != StopReason.USER_CANCELLED
                    && reason != StopReason.TIME_LIMIT_REACHED) {
                throw new IllegalArgumentException("不支持的取消终止原因: " + reason);
            }
            return stopReason.compareAndSet(null, reason)
                    && cancellation.cancel();
        }
    }
}
