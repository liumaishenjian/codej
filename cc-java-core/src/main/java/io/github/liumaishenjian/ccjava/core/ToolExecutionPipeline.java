package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewContextItem;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.CheckpointId;
import io.github.liumaishenjian.ccjava.domain.CheckpointTarget;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolOutputStream;
import io.github.liumaishenjian.ccjava.domain.hook.HookAggregateResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一执行模型提出的每一次 Tool Call。
 *
 * <p>确定性顺序为：解析 Tool → 参数校验 → Before 事件 → Permission
 * → 可选 Approval → 同步执行 → 规范化/最终裁剪 Result → After 事件。未知 Tool、
 * 参数错误和执行异常都转换为带原始 Call ID 的结构化失败结果，使模型可以
 * 在下一回合纠正。S03 在这里强制最终字符 ceiling，确保 Tool、事件、Session History
 * 和下一模型回合看不到裁剪前旁路结果；超时和取消仍由后续 Stage 加入同一管线。</p>
 *
 * @since 0.1.0
 */
public final class ToolExecutionPipeline {

    /** 无论 Tool Definition 如何声明，Pipeline 都不会向 Context 放入更多字符。 */
    public static final int ABSOLUTE_MAX_OUTPUT_CHARACTERS = 64_000;

    private static final String TRUNCATION_MARKER = "\n[truncated: pipeline character limit]";

    private final ToolRegistry registry;
    private final PermissionGate permissionGate;
    private final ApprovalHandler approvalHandler;
    private final SessionPermissionState permissionState;
    private final LifecycleDispatcher lifecycle;
    private final SessionJournal sessionJournal;
    private final CheckpointCoordinator checkpoints;
    private final HookCoordinator hooks;
    private final SkillRunCoordinator skills;
    private final ApprovalReviewer reviewer;
    private final AutoReviewCoordinator autoReview;
    /** 可选的 Plan Gate；为空表示保持非 Plan Runtime 的既有语义。 */
    private PlanModeCoordinator planMode;
    /** 可选的持续规划 capability Gate；隐藏与猜名调用使用同一策略。 */
    private PlanEligibilityPolicy planEligibility;
    /** 当前 Run 的短生命周期失败 fingerprint。 */
    private final java.util.concurrent.ConcurrentMap<RunId, ToolFailureFingerprintGovernance> failureGovernance =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 创建 Tool 执行管线。
     *
     * @param registry        唯一 Tool Registry
     * @param permissionGate  最小权限决策端口
     * @param approvalHandler ASK 决策的审批端口
     * @param lifecycle       生命周期分发器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            LifecycleDispatcher lifecycle) {
        this(
                registry,
                permissionGate,
                approvalHandler,
                new InMemorySessionPermissionState(),
                lifecycle,
                SessionJournal.noop(),
                CheckpointCoordinator.noop(),
                HookCoordinator.disabled());
    }

    /**
     * 创建共享 S05 Session Permission 状态的 Tool 管线。
     *
     * @param registry 唯一 Tool Registry
     * @param permissionGate 类型化 Policy Kernel
     * @param approvalHandler ASK 审批端口
     * @param permissionState 与 Policy 共享的当前 Session 内存状态
     * @param lifecycle 生命周期分发器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle) {
        this(
                registry,
                permissionGate,
                approvalHandler,
                permissionState,
                lifecycle,
                SessionJournal.noop(),
                CheckpointCoordinator.noop(),
                HookCoordinator.disabled());
    }

    /**
     * 创建接入 durable Tool started/completed 边界的执行管线。
     *
     * @param registry 唯一 Tool Registry
     * @param permissionGate 类型化 Policy Kernel
     * @param approvalHandler ASK 审批端口
     * @param permissionState 当前 Session Permission 状态
     * @param lifecycle 可失败的观察生命周期
     * @param sessionJournal 必须成功的 Session journal
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal) {
        this(
                registry,
                permissionGate,
                approvalHandler,
                permissionState,
                lifecycle,
                sessionJournal,
                CheckpointCoordinator.noop(),
                HookCoordinator.disabled());
    }

    /**
     * 创建同时接入 durable Session journal 与普通文件 Checkpoint 的执行管线。
     *
     * @param registry 唯一 Tool Registry
     * @param permissionGate 类型化 Policy Kernel
     * @param approvalHandler ASK 审批端口
     * @param permissionState 当前 Session Permission 状态
     * @param lifecycle 可失败的观察生命周期
     * @param sessionJournal 必须成功的 Session journal
     * @param checkpoints 写 Tool 的 durable Checkpoint 协调器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            CheckpointCoordinator checkpoints) {
        this(
                registry,
                permissionGate,
                approvalHandler,
                permissionState,
                lifecycle,
                sessionJournal,
                checkpoints,
                HookCoordinator.disabled());
    }

    /**
     * 创建同时接入 durable Session、Checkpoint 和 S09 Hook 的 Tool 管线。
     *
     * <p>Pre Tool Hook 位于参数校验之后、Permission 之前；Post Tool Hook 位于
     * Result 规范化并记录之后。Hook 不能直接执行 Tool 或覆盖 Hard Denial。</p>
     *
     * @param registry 唯一 Tool Registry
     * @param permissionGate 类型化 Policy Kernel
     * @param approvalHandler ASK 审批端口
     * @param permissionState 当前 Session Permission 状态
     * @param lifecycle 可失败的观察生命周期
     * @param sessionJournal 必须成功的 Session journal
     * @param checkpoints 写 Tool 的 durable Checkpoint 协调器
     * @param hooks S09 Hook 协调器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            CheckpointCoordinator checkpoints,
            HookCoordinator hooks) {
        this(registry, permissionGate, approvalHandler, permissionState, lifecycle, sessionJournal,
                checkpoints, hooks, SkillRunCoordinator.disabled());
    }

    /**
     * 创建同时接入 Skill Run visibility Gate 的唯一执行管线。
     *
     * <p>Gate 位于 Registry 解析前，确保模型即使提出已从 definitions 隐藏的 Tool，也只得到
     * durable execute=0 结果，不会触发 Hook、Permission、Approval 或 Adapter。</p>
     *
     * @param registry 唯一 Tool Registry
     * @param permissionGate 确定性权限 Gate
     * @param approvalHandler 用户审批适配器
     * @param permissionState Session 级授权状态
     * @param lifecycle Tool 生命周期分发器
     * @param sessionJournal 必须成功的 Session journal
     * @param checkpoints 写 Tool 的 durable Checkpoint 协调器
     * @param hooks S09 Hook 协调器
     * @param skills 当前 Runtime 的 Skill Run 协调器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            CheckpointCoordinator checkpoints,
            HookCoordinator hooks,
            SkillRunCoordinator skills) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate 不能为空");
        this.approvalHandler = Objects.requireNonNull(
                approvalHandler,
                "approvalHandler 不能为空");
        this.permissionState = Objects.requireNonNull(
                permissionState,
                "permissionState 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        this.sessionJournal = Objects.requireNonNull(
                sessionJournal, "sessionJournal 不能为空");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints 不能为空");
        this.hooks = Objects.requireNonNull(hooks, "hooks 不能为空");
        this.skills = Objects.requireNonNull(skills, "skills 不能为空");
        this.reviewer = ApprovalReviewer.USER;
        this.autoReview = null;
        this.planMode = null;
        this.planEligibility = null;
    }

    /**
     * 创建接入 AUTO final-ASK 审查的 Tool 管线。
     *
     * <p>旧构造器保持 USER 审批。自动审查只在 Policy 与 Permission Hook 后仍为 ASK 时运行，
     * 不读取或写入 SessionPermissionState 的 Grant/拒绝状态。</p>
     *
     * @param registry 当前 Runtime 的 Tool 注册表
     * @param permissionGate 确定性 Permission Policy 入口
     * @param approvalHandler USER reviewer 的交互审批端口
     * @param permissionState Session Grant 与拒绝计数状态
     * @param lifecycle Tool/Permission 生命周期分发器
     * @param sessionJournal 副作用 Tool 的持久化 Journal
     * @param checkpoints ordinary-file Checkpoint 协调器
     * @param hooks Tool 与 Permission Hook 协调器
     * @param skills Skill Run 生命周期协调器
     * @param reviewer final ASK 的收敛主体
     * @param autoReview AUTO_REVIEW 使用的失败关闭协调器；USER 时可为 null
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal,
            CheckpointCoordinator checkpoints,
            HookCoordinator hooks,
            SkillRunCoordinator skills,
            ApprovalReviewer reviewer,
            AutoReviewCoordinator autoReview) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate 不能为空");
        this.approvalHandler = Objects.requireNonNull(approvalHandler, "approvalHandler 不能为空");
        this.permissionState = Objects.requireNonNull(permissionState, "permissionState 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        this.sessionJournal = Objects.requireNonNull(sessionJournal, "sessionJournal 不能为空");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints 不能为空");
        this.hooks = Objects.requireNonNull(hooks, "hooks 不能为空");
        this.skills = Objects.requireNonNull(skills, "skills 不能为空");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer 不能为空");
        this.autoReview = reviewer == ApprovalReviewer.AUTO_REVIEW
                ? Objects.requireNonNull(autoReview, "autoReview 不能为空") : autoReview;
        this.planMode = null;
        this.planEligibility = null;
    }

    /**
     * 创建绑定当前 Plan Gate 的 Tool 管线。Plan Gate 位于所有副作用前，
     * 但获准调用仍完整经过本管线的 Hook、Permission、Approval、Journal 和 Checkpoint。
     *
     * @param registry Tool 注册表
     * @param permissionGate 权限策略
     * @param approvalHandler 审批适配器
     * @param permissionState Session 权限状态
     * @param lifecycle 生命周期分发器
     * @param sessionJournal Session journal
     * @param checkpoints 写 Tool checkpoint
     * @param hooks Hook 协调器
     * @param skills Skill scope
     * @param planMode 当前 Plan Gate
     */
    public ToolExecutionPipeline(
            ToolRegistry registry, PermissionGate permissionGate, ApprovalHandler approvalHandler,
            SessionPermissionState permissionState, LifecycleDispatcher lifecycle,
            SessionJournal sessionJournal, CheckpointCoordinator checkpoints, HookCoordinator hooks,
            SkillRunCoordinator skills, PlanModeCoordinator planMode) {
        this(registry, permissionGate, approvalHandler, permissionState, lifecycle, sessionJournal,
                checkpoints, hooks, skills, ApprovalReviewer.USER, null);
        this.planMode = Objects.requireNonNull(planMode, "planMode 不能为空");
    }

    /**
     * 创建当前 Run 独占的自动审查 scope。
     *
     * <p>Pipeline 是 final ASK 收敛策略的唯一配置所有者，因此由它依据当前 reviewer
     * 创建 scope，Runtime 只负责在 Run 结束时关闭。返回的对象不得跨 Run 传递或缓存。</p>
     *
     * @param runId 当前 Run 标识
     * @return AUTO_REVIEW 时启用、否则保持 USER 既有语义的 scope
     */
    /**
     * 在首次执行前启用持续规划 hard boundary。
     *
     * <p>返回当前 Pipeline 便于 Composition Root 在创建 Scope 时原子装配；Pipeline 不得跨普通
     * Run 与 Plan Run 共享，因此启用后不可清除。</p>
     *
     * @param policy 能力驱动规划资格策略
     * @return 当前 Pipeline
     */
    public ToolExecutionPipeline restrictToPlanning(PlanEligibilityPolicy policy) {
        if (planEligibility != null) throw new IllegalStateException("Plan eligibility 已启用");
        planEligibility = Objects.requireNonNull(policy, "policy 不能为空");
        return this;
    }

    /** 清除当前 Run 的短生命周期失败 fingerprint。 */
    public void closeRunGovernance(RunId runId) {
        failureGovernance.remove(Objects.requireNonNull(runId, "runId 不能为空"));
    }

    public AutoReviewRunScope createRunScope(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        return reviewer == ApprovalReviewer.AUTO_REVIEW
                ? AutoReviewRunScope.enabled(runId)
                : AutoReviewRunScope.disabled(runId);
    }

    /**
     * 顺序处理一次 Tool Call，并保证结果 ID 与原始调用一致。
     *
     * @param session 当前 Session
     * @param runId   当前 Run
     * @param ordinal 本次 Run 内的调用序号
     * @param call    原始模型调用
     * @return 已规范化结果
     */
    public ToolResult execute(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolCall call) {
        return execute(session, runId, ordinal, call, CancellationToken.none(),
                AutoReviewRunScope.disabled(runId));
    }

    /**
     * 顺序处理一次 Tool Call，并把当前 Run 的取消信号传播给 Tool Adapter。
     *
     * <p>取消信号只允许 Adapter 终止自身 I/O 或子进程；Run 的最终
     * {@code USER_CANCELLED} 状态仍由 {@link AgentRuntime} 唯一决定。</p>
     *
     * @param session 当前 Session
     * @param runId 当前 Run
     * @param ordinal 本次 Run 内的调用序号
     * @param call 原始模型调用
     * @param cancellationToken 当前 Run 的取消信号
     * @return 已规范化结果
     */
    public ToolResult execute(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolCall call,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(call, "call 不能为空");
        return execute(session, runId, ordinal, call, cancellationToken, AutoReviewRunScope.disabled(runId));
    }

    /**
     * 以显式 Run-owned 自动审查 scope 执行一次 Tool Call。
     *
     * @param session 当前 Session
     * @param runId 当前 Run
     * @param ordinal 本次 Run 内的调用序号
     * @param call 原始模型调用
     * @param cancellationToken 当前 Run 的取消信号
     * @param autoReviewScope 当前 Run 的自动审查状态；不得跨 Run 复用
     * @return 已规范化结果
     */
    public ToolResult execute(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolCall call,
            CancellationToken cancellationToken,
            AutoReviewRunScope autoReviewScope) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Objects.requireNonNull(autoReviewScope, "autoReviewScope 不能为空");
        if (!runId.equals(autoReviewScope.runId())) {
            throw new IllegalArgumentException("自动审查 scope 必须绑定当前 Run");
        }
        ToolInvocation invocation = new ToolInvocation(
                session.id(),
                runId,
                ordinal,
                call,
                cancellationToken,
                (stream, text) -> publishToolOutput(
                        session, runId, ordinal, call.name(), stream, text));

        if (!skills.isToolVisible(runId, call.name())) {
            return resolveWithoutExecution(
                    session,
                    runId,
                    ordinal,
                    ToolResult.denied(call.id(), call.name(), "Tool 不在当前 Skill scope"),
                    ToolResolutionReason.SKILL_SCOPE_DENIED,
                    cancellationToken);
        }

        AgentTool tool = registry.find(call.name()).orElse(null);
        if (tool == null) {
            return resolveWithoutExecution(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                    ToolError.of(
                                    ToolErrorCode.UNKNOWN_TOOL,
                                    "未注册 Tool: " + call.name())),
                    ToolResolutionReason.UNKNOWN_TOOL,
                    cancellationToken);
        }

        ToolFailureFingerprintGovernance governance = failureGovernance.computeIfAbsent(
                runId, ignored -> new ToolFailureFingerprintGovernance());
        if (governance.repeated(call)) {
            return resolveWithoutExecution(session, runId, ordinal,
                    ToolResult.failure(call.id(), call.name(), ToolFailureFingerprintGovernance.repeatedFailure()),
                    ToolResolutionReason.REPEATED_FAILURE, cancellationToken);
        }

        ToolValidationResult validation;
        try {
            validation = Objects.requireNonNull(
                    tool.validate(call.arguments()),
                    "Tool validate 返回 null");
        } catch (RuntimeException exception) {
            validation = ToolValidationResult.invalid(
                    "参数校验器发生异常");
        }
        if (!validation.valid()) {
            Map<String, Object> details = new LinkedHashMap<>(validation.details().values());
            details.put("violations", validation.violations());
            details.put("argumentChangeRequired", true);
            details.put("retrySameArguments", false);
            ToolError error = new ToolError(
                    ToolErrorCode.INVALID_ARGUMENTS,
                    "Tool 参数校验失败；请按 details 修改参数后再调用",
                    new JsonObject(details));
            if (governance.recordValidationFailureOrRepeated(
                    call, error, validation.correctionSignature())) {
                return resolveWithoutExecution(session, runId, ordinal,
                        ToolResult.failure(call.id(), call.name(),
                                ToolFailureFingerprintGovernance.repeatedFailure()),
                        ToolResolutionReason.REPEATED_FAILURE, cancellationToken);
            }
            return resolveWithoutExecution(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(call.id(), call.name(), error),
                    ToolResolutionReason.INVALID_ARGUMENTS,
                    cancellationToken);
        }

        ToolDefinition definition = tool.definition();
        if (planEligibility != null && !planEligibility.executionAllowed(definition)) {
            return resolveWithoutExecution(session, runId, ordinal,
                    ToolResult.failure(call.id(), call.name(),
                            ToolError.of(ToolErrorCode.PLAN_GATE_BLOCKED,
                                    "Tool capability is unavailable while planning")),
                    ToolResolutionReason.PLAN_GATE_BLOCKED, cancellationToken);
        }
        if (planMode != null && isPlanSideEffect(definition.effect()) && !planAllows()) {
            return resolveWithoutExecution(session, runId, ordinal,
                    ToolResult.failure(call.id(), call.name(),
                            ToolError.of(ToolErrorCode.PLAN_GATE_BLOCKED,
                                    "Plan 尚未批准或没有活动步骤")),
                    ToolResolutionReason.PLAN_GATE_BLOCKED, cancellationToken);
        }

        HookAggregateResult preTool = hooks.evaluate(
                new HookInvocation(
                        HookEventKind.PRE_TOOL,
                        session.id(),
                        java.util.Optional.of(runId),
                        call.name(),
                        new JsonObject(Map.of(
                                "callId", call.id(),
                                "toolName", call.name()))),
                cancellationToken);
        if (preTool.blocking()) {
            String reason = preTool.blockingReason().orElse("Hook 阻断 Tool 调用");
            return resolveWithoutExecution(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                            ToolError.of(ToolErrorCode.HOOK_BLOCKED, reason)),
                    ToolResolutionReason.HOOK_BLOCKED,
                    cancellationToken);
        }

        lifecycle.dispatch(session, runId, new LifecycleEvent.BeforeTool(ordinal, call));
        LifecycleEvent.PermissionCallSummary permissionCall = permissionCall(call, definition);
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionEvaluationStarted(permissionCall));
        PermissionOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    permissionGate.evaluate(invocation, definition),
                    "PermissionGate 返回 null");
        } catch (RuntimeException exception) {
            outcome = policyFailureOutcome(call, definition);
        }
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionEvaluated(
                        permissionCall,
                        permissionSummary(outcome, outcome.decision() == PermissionDecision.ASK)));
        if (outcome.decision() == PermissionDecision.ASK) {
            outcome = reviewer == ApprovalReviewer.USER
                    ? resolveUserFinalAsk(session, runId, call, invocation, definition, permissionCall,
                            outcome, cancellationToken)
                    : resolveAutoFinalAsk(session, runId, call, definition, permissionCall, outcome,
                            cancellationToken, autoReviewScope);
        }
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionDecided(
                        permissionCall,
                        permissionSummary(outcome, false)));
        if (outcome.decision() == PermissionDecision.DENY) {
            if (reviewer == ApprovalReviewer.USER
                    && outcome.reason() != PermissionReason.POLICY_EVALUATION_FAILED_CLOSED) {
                permissionState.recordDenialUpTo(session.id(), outcome.selector(), 3);
            }
            return resolveWithoutExecution(
                    session,
                    runId,
                    ordinal,
                    ToolResult.denied(call.id(), call.name(), "Tool 调用未获授权"),
                    ToolResolutionReason.PERMISSION_DENIED,
                    cancellationToken);
        }

        PermissionOutcome finalOutcome = outcome;
        CheckpointId checkpointId = null;
        if (definition.effect() == io.github.liumaishenjian.ccjava.domain.ToolEffect.WRITE_WORKSPACE) {
            CheckpointTarget target;
            try {
                target = tool.checkpointTarget(invocation)
                        .orElseThrow(() -> new IllegalStateException(
                                "WRITE_WORKSPACE Tool 未声明 Checkpoint 目标"));
                checkpointId = checkpoints.create(invocation, target);
            } catch (Exception checkpointFailure) {
                throw new ToolJournalPersistenceException(
                        "Checkpoint 创建失败，Tool 未执行",
                        checkpointFailure);
            }
        }
        try {
            sessionJournal.toolStarted(
                    session.id(),
                    runId,
                    ordinal,
                    call.id(),
                    call.name(),
                    definition.effect());
        } catch (RuntimeException journalFailure) {
            throw new ToolJournalPersistenceException(
                    "Tool 启动记录失败，调用未执行",
                    journalFailure);
        }

        ToolResult result;
        try {
            ToolExecutionOutcome execution = Objects.requireNonNull(
                    tool.execute(invocation),
                    "Tool execute 返回 null");
            result = execution.successful()
                    ? normalizeSuccess(call, definition, execution)
                    : normalizeFailure(call, definition, execution);
        } catch (PlanArtifactStoreException planFailure) {
            result = ToolResult.failure(call.id(), call.name(), trustedPlanArtifactTool(definition)
                    ? planArtifactFailure(planFailure)
                    : ToolError.of(ToolErrorCode.EXECUTION_FAILED, "Tool 执行失败"));
        } catch (Exception exception) {
            result = ToolResult.failure(
                    call.id(),
                    call.name(),
                    ToolError.of(
                            ToolErrorCode.EXECUTION_FAILED,
                            "Tool 执行失败"));
        }

        if (checkpointId != null) {
            try {
                checkpoints.complete(invocation, checkpointId, result);
            } catch (RuntimeException checkpointFailure) {
                throw new ToolJournalPersistenceException(
                        "Tool 已执行但 Checkpoint post-image 未可靠持久化",
                        checkpointFailure);
            }
        }
        try {
            sessionJournal.toolCompleted(session.id(), runId, ordinal, result);
        } catch (RuntimeException journalFailure) {
            throw new ToolJournalPersistenceException(
                    "Tool 已执行但完成记录未可靠持久化",
                    journalFailure);
        }
        if (result.status() != io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                && result.error().isPresent()
                && !(trustedPlanArtifactTool(definition)
                        && result.error().orElseThrow().code() == ToolErrorCode.PLAN_ARTIFACT_CONFLICT)) {
            governance.record(call, result.error().orElseThrow());
        } else if (result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
            governance.recordSuccess(call, definition.effect());
        }
        if (reviewer == ApprovalReviewer.USER
                && result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
            permissionState.clearDenials(session.id(), finalOutcome.selector());
        }
        return finish(session, runId, ordinal, result, cancellationToken);
    }

    /**
     * 把 Plan durable CAS/状态失败映射为不泄漏路径或正文的模型可行动错误。
     *
     * <p>并发与生命周期冲突允许模型重新提交同一高层 intent；持久层损坏或 I/O 不确定性要求
     * 停止当前写入并恢复 Session。固定 {@code reason/action} 只来自封闭枚举，不拼接底层异常。</p>
     */
    private static boolean planArtifactError(ToolErrorCode code) {
        return code == ToolErrorCode.PLAN_ARTIFACT_CONFLICT
                || code == ToolErrorCode.PLAN_ARTIFACT_UNAVAILABLE;
    }

    private static boolean trustedPlanArtifactTool(ToolDefinition definition) {
        return definition.source() == io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN
                && definition.effect() == io.github.liumaishenjian.ccjava.domain.ToolEffect.PLAN_ARTIFACT_WRITE
                && definition.planCapabilities().contains(
                        io.github.liumaishenjian.ccjava.domain.PlanToolCapability.PLAN_ARTIFACT_WRITE);
    }

    private static ToolError planArtifactFailure(PlanArtifactStoreException failure) {
        boolean conflict = switch (failure.code()) {
            case NOT_FOUND, ALREADY_EXISTS, STALE_REVISION, DIGEST_CONFLICT, INVALID_STATE -> true;
            case CORRUPT, IDENTITY_MISMATCH, PATH_REJECTED, LIMIT_EXCEEDED,
                    ATOMIC_MOVE_UNAVAILABLE, IO_FAILURE -> false;
        };
        if (conflict) {
            String action = failure.code() == PlanArtifactStoreException.Code.NOT_FOUND
                    ? "revise_plan_artifact"
                    : "retry_current_plan_intent";
            return ToolError.classified(ToolErrorCode.PLAN_ARTIFACT_CONFLICT,
                    ToolFailureCategory.VALIDATION, true,
                    "Plan durable state changed; the runtime did not commit this mutation",
                    new JsonObject(Map.of("reason", failure.code().name(), "action", action)));
        }
        return ToolError.classified(ToolErrorCode.PLAN_ARTIFACT_UNAVAILABLE,
                ToolFailureCategory.INTERNAL, false,
                "Plan durable storage is unavailable; stop mutations and resume the session safely",
                new JsonObject(Map.of("reason", failure.code().name(), "action", "resume_session")));
    }

    private static boolean isPlanSideEffect(io.github.liumaishenjian.ccjava.domain.ToolEffect effect) {
        return effect != io.github.liumaishenjian.ccjava.domain.ToolEffect.READ_WORKSPACE;
    }

    private boolean planAllows() {
        io.github.liumaishenjian.ccjava.domain.PlanExecutionState state = planMode.state();
        if (!state.sideEffectsAllowed() || state.activeStep() == null) return false;
        io.github.liumaishenjian.ccjava.domain.PlanStep step = planMode.document().steps().get(state.activeStep() - 1);
        return step.expectedDigest().equals(state.workspaceDigest());
    }

    private static PermissionOutcome policyFailureOutcome(
            ToolCall call,
            ToolDefinition definition) {
        return PermissionOutcome.of(
                PermissionDecision.DENY,
                PermissionReason.POLICY_EVALUATION_FAILED_CLOSED,
                io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                        call.name(), definition.source()));
    }

    private static LifecycleEvent.PermissionCallSummary permissionCall(
            ToolCall call,
            ToolDefinition definition) {
        return new LifecycleEvent.PermissionCallSummary(
                call.id(),
                call.name(),
                definition.effect());
    }

    private static LifecycleEvent.PermissionDecisionSummary permissionSummary(
            PermissionOutcome outcome,
            boolean interactive) {
        return new LifecycleEvent.PermissionDecisionSummary(
                outcome.decision(),
                outcome.reason(),
                outcome.ruleSource(),
                interactive,
                !outcome.selector().toolWide());
    }

    /**
     * 保留既有 USER 审批与 Session Permission State 语义。
     */
    private PermissionOutcome resolveUserFinalAsk(
            AgentSession session,
            RunId runId,
            ToolCall call,
            ToolInvocation invocation,
            ToolDefinition definition,
            LifecycleEvent.PermissionCallSummary permissionCall,
            PermissionOutcome initial,
            CancellationToken cancellationToken) {
        if (permissionState.denialCount(session.id(), initial.selector()) >= 2) {
            return PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.REPEATED_DENIAL,
                    initial.selector());
        }
        PermissionOutcome hookOutcome = resolvePermissionHook(
                session, runId, call, definition, initial, cancellationToken);
        if (hookOutcome.decision() != PermissionDecision.ASK) {
            return hookOutcome;
        }
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.ApprovalRequested(
                        permissionCall,
                        permissionSummary(hookOutcome, true)));
        return requestApprovalFailClosed(session, invocation, definition, hookOutcome);
    }

    /**
     * 收敛 AUTO 的最终 ASK，不访问 Session Grant 或拒绝计数。
     */
    private PermissionOutcome resolveAutoFinalAsk(
            AgentSession session,
            RunId runId,
            ToolCall call,
            ToolDefinition definition,
            LifecycleEvent.PermissionCallSummary permissionCall,
            PermissionOutcome initial,
            CancellationToken cancellationToken,
            AutoReviewRunScope scope) {
        if (!scope.enabled()) {
            throw new IllegalStateException("AUTO_REVIEW 必须使用启用的 AutoReviewRunScope");
        }
        PermissionOutcome hookOutcome = resolvePermissionHook(
                session, runId, call, definition, initial, cancellationToken);
        if (hookOutcome.decision() != PermissionDecision.ASK) {
            return hookOutcome;
        }
        // AUTO_REVIEW 不弹出交互面板，但仍记录一次审批请求生命周期，
        // 使审查尝试与 USER reviewer 共享可观察的 Permission 边界。
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.ApprovalRequested(
                        permissionCall,
                        permissionSummary(hookOutcome, true)));
        ApprovalReviewRequest reviewRequest = approvalReviewRequest(
                session, runId, call, definition, hookOutcome);
        AutoReviewDecision decision = autoReview.reviewAuto(
                hookOutcome,
                reviewRequest,
                cancellationToken,
                scope.circuit());
        // Auto 模式没有交互审批提示；classifier 是内部受限回合，fast path 与
        // classifier allow 都只留下唯一 PermissionDecided 生命周期。
        if (decision.stopAfterCurrentDeny()) {
            scope.requestStopAfterBatch();
        }
        return switch (decision.status()) {
            case ALLOW_ONCE -> PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.AUTO_REVIEW_ALLOW_ONCE,
                    hookOutcome.selector());
            case DENY -> PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.AUTO_REVIEW_DENY,
                    hookOutcome.selector());
            case FAILED_CLOSED -> PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.AUTO_REVIEW_FAILED_CLOSED,
                    hookOutcome.selector());
            case CIRCUIT_OPEN, RUN_CLOSED -> PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.AUTO_REVIEW_CIRCUIT_STOP,
                    hookOutcome.selector());
            case NOT_FINAL_ASK -> throw new IllegalStateException("Auto Review 未收敛最终 ASK");
        };
    }

    private PermissionOutcome resolvePermissionHook(
            AgentSession session,
            RunId runId,
            ToolCall call,
            ToolDefinition definition,
            PermissionOutcome initial,
            CancellationToken cancellationToken) {
        HookAggregateResult permissionHook = hooks.evaluate(
                new HookInvocation(
                        HookEventKind.PERMISSION_REQUEST,
                        session.id(),
                        java.util.Optional.of(runId),
                        call.name(),
                        new JsonObject(Map.of(
                                "callId", call.id(),
                                "toolName", call.name(),
                                "effect", definition.effect().name()))),
                cancellationToken);
        if (permissionHook.disposition() == HookDisposition.DENY
                || permissionHook.disposition() == HookDisposition.BLOCK) {
            return PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.HOOK_DENIED,
                    initial.selector());
        }
        if (permissionHook.disposition() == HookDisposition.ALLOW) {
            return PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.HOOK_ALLOWED,
                    initial.selector());
        }
        return initial;
    }

    private static ApprovalReviewRequest approvalReviewRequest(
            AgentSession session,
            RunId runId,
            ToolCall call,
            ToolDefinition definition,
            PermissionOutcome outcome) {
        java.util.List<ApprovalReviewContextItem> context = new java.util.ArrayList<>();
        for (AgentMessage message : session.messages()) {
            ApprovalReviewContextItem item = approvalReviewContextItem(message);
            if (item != null) {
                context.add(item);
                if (context.size() > ApprovalReviewRequest.MAX_CONTEXT_ITEMS) {
                    context.removeFirst();
                }
            }
        }
        return new ApprovalReviewRequest(
                session.id(),
                runId,
                call.id(),
                call.name(),
                definition.effect(),
                definition.source(),
                !outcome.selector().toolWide(),
                "请求执行受控 Tool 调用",
                context);
    }

    private static ApprovalReviewContextItem approvalReviewContextItem(AgentMessage message) {
        if (message instanceof UserMessage) {
            return new ApprovalReviewContextItem(ApprovalReviewContextItem.Role.USER, "用户已提交请求");
        }
        if (message instanceof AssistantMessage) {
            return new ApprovalReviewContextItem(ApprovalReviewContextItem.Role.ASSISTANT, "模型已提出响应");
        }
        if (message instanceof ToolResultMessage) {
            return new ApprovalReviewContextItem(ApprovalReviewContextItem.Role.TOOL_RESULT, "已有 Tool 结果");
        }
        return null;
    }

    private PermissionOutcome requestApprovalFailClosed(
            AgentSession session,
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionOutcome initial) {
        try {
            ApprovalResponse response = Objects.requireNonNull(
                    approvalHandler.requestApproval(invocation, definition, initial),
                    "ApprovalHandler 返回 null");
            return resolveApproval(session, initial, response);
        } catch (RuntimeException exception) {
            return PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.APPROVAL_FAILED_CLOSED,
                    initial.selector());
        }
    }

    private PermissionOutcome resolveApproval(
            AgentSession session,
            PermissionOutcome initial,
            ApprovalResponse response) {
        return switch (response.action()) {
            case ALLOW_ONCE -> PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.USER_ALLOW_ONCE,
                    initial.selector());
            case ALLOW_SESSION -> {
                var scope = response.scope().orElseThrow();
                if (!scope.equals(initial.selector())
                        || (scope.toolWide() && !(scope.toolName().equals("web_search")
                        && scope.source() == io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN))) {
                    throw new IllegalArgumentException("Session approval scope 与请求不匹配");
                }
                permissionState.grant(session.id(), scope);
                yield PermissionOutcome.of(
                        PermissionDecision.ALLOW,
                        PermissionReason.USER_ALLOW_SESSION,
                        initial.selector());
            }
            case DENY -> PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.USER_DENY,
                    initial.selector());
        };
    }

    private ToolResult normalizeFailure(ToolCall call, ToolDefinition definition,
            ToolExecutionOutcome outcome) {
        int limit = Math.min(definition.maxOutputCharacters(), ABSOLUTE_MAX_OUTPUT_CHARACTERS);
        String original = outcome.content();
        int originalCharacters = original.codePointCount(0, original.length());
        String normalized = originalCharacters <= limit ? original
                : prefixByCodePoints(original, Math.max(0, limit - Math.min(limit,
                        TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length()))))
                        + prefixByCodePoints(TRUNCATION_MARKER, Math.min(limit,
                                TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length())));
        ToolError error = outcome.error().orElseThrow();
        if (planArtifactError(error.code()) && !trustedPlanArtifactTool(definition)) {
            error = ToolError.of(ToolErrorCode.EXECUTION_FAILED, "Tool 执行失败");
        }
        return ToolResult.failure(call.id(), call.name(), normalized, error,
                outcome.metadata().normalize(normalized, originalCharacters > limit, originalCharacters));
    }

    private ToolResult normalizeSuccess(
            ToolCall call,
            ToolDefinition definition,
            ToolExecutionOutcome outcome) {
        int limit = Math.min(
                definition.maxOutputCharacters(),
                ABSOLUTE_MAX_OUTPUT_CHARACTERS);
        String original = outcome.content();
        int originalCharacters = original.codePointCount(0, original.length());
        if (originalCharacters <= limit) {
            return ToolResult.success(
                    call.id(),
                    call.name(),
                    original,
                    outcome.metadata().normalize(original, false, originalCharacters));
        }

        int markerCharacters = TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length());
        String normalized;
        if (limit <= markerCharacters) {
            normalized = prefixByCodePoints(TRUNCATION_MARKER, limit);
        } else {
            normalized = prefixByCodePoints(original, limit - markerCharacters)
                    + TRUNCATION_MARKER;
        }
        return ToolResult.success(
                call.id(),
                call.name(),
                normalized,
                outcome.metadata().normalize(normalized, true, originalCharacters));
    }

    private static String prefixByCodePoints(String value, int codePoints) {
        if (codePoints == 0) {
            return "";
        }
        int end = value.offsetByCodePoints(0, codePoints);
        return value.substring(0, end);
    }

    private ToolResult resolveWithoutExecution(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolResult result,
            ToolResolutionReason reason,
            CancellationToken cancellationToken) {
        try {
            sessionJournal.toolResolved(session.id(), runId, ordinal, result, reason);
        } catch (RuntimeException journalFailure) {
            throw new ToolJournalPersistenceException(
                    "Tool 未执行结果未可靠持久化",
                    journalFailure);
        }
        return finish(session, runId, ordinal, result, cancellationToken);
    }

    private ToolResult finish(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolResult result,
            CancellationToken cancellationToken) {
        lifecycle.dispatch(session, runId, new LifecycleEvent.AfterTool(ordinal, result));
        HookAggregateResult post = hooks.evaluate(
                new HookInvocation(
                        HookEventKind.POST_TOOL,
                        session.id(),
                        java.util.Optional.of(runId),
                        result.toolName(),
                        new JsonObject(Map.of(
                                "callId", result.callId(),
                                "toolName", result.toolName(),
                                "status", result.status().name()))),
                cancellationToken);
        post.additionalContext().ifPresent(context -> hooks.recordTransientContext(runId, context));
        return result;
    }

    private void publishToolOutput(
            AgentSession session,
            RunId runId,
            int ordinal,
            String toolName,
            ToolOutputStream stream,
            String text) {
        try {
            lifecycle.dispatch(
                    session,
                    runId,
                    new LifecycleEvent.ToolOutput(ordinal, toolName, stream, text));
        } catch (RuntimeException ignored) {
            // 输出事件是只读旁路，Surface 失败不能改变 Tool 的权威执行结果。
        }
    }

}
