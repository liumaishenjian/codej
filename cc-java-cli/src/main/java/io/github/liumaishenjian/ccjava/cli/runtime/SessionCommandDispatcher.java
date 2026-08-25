package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.core.PlanModeCoordinator;
import io.github.liumaishenjian.ccjava.domain.settings.EffectivePermissionRule;
import io.github.liumaishenjian.ccjava.domain.settings.SessionSettingsPatch;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.command.CommandId;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandIntent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResult;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResultCode;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandStatus;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADR-047 Session Command 的 Java Application 分派基础。
 *
 * <p>分派器只调用既有的只读 Runtime seam 或 Surface transient port；没有安全实现的命令
 * 返回固定终态，绝不绕过 S05 Pipeline、S06 Recovery Gate 或 S07 Context Gate。每个
 * {@link CommandId} 的首次终态会在当前 dispatcher 生命周期内缓存，重复分派不会重新执行副作用。
 * 缓存由固定 request budget 限制且不淘汰；耗尽后新 ID 在执行前 fail closed。</p>
 *
 * @since 0.8.0
 */
public final class SessionCommandDispatcher {
    private final HeadlessRuntimeSession runtime;
    private final DoctorReportService doctor;
    private final SurfaceTransientState transientState;
    private final boolean hasTransientSurface;
    private final int maxCommandIds;
    private final Map<CommandId, SessionCommandResult> terminalResults = new HashMap<>();

    /**
     * 创建未接入 stdio/TUI transient state 的 dispatcher。
     *
     * @param runtime 提供只读状态与活动 Run Gate 的当前 Runtime
     * @param doctor 只读 doctor 投影服务
     */
    public SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor) {
        this(runtime, doctor, () -> { }, false, 256);
    }

    /**
     * 创建绑定具体 transient Surface state 的 dispatcher。
     *
     * @param runtime 提供只读状态与活动 Run Gate 的当前 Runtime
     * @param doctor 只读 doctor 投影服务
     * @param transientState 仅允许清理 Surface 瞬态状态的端口
     */
    public SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor,
                                    SurfaceTransientState transientState) {
        this(runtime, doctor, transientState, true, 256);
    }

    /**
     * 创建使用固定 request budget 的 dispatcher。
     *
     * <p>已接受的 commandId 永不淘汰，避免重复请求重放 Surface 副作用；达到上限时仅拒绝
     * 新 commandId，不执行其 intent。</p>
     *
     * @param runtime 提供只读状态与活动 Run Gate 的当前 Runtime
     * @param doctor 只读 doctor 投影服务
     * @param transientState 仅允许清理 Surface 瞬态状态的端口
     * @param maxCommandIds 当前 dispatcher 生命周期中可接受的不同 commandId 数
     */
    public SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor,
                                    SurfaceTransientState transientState, int maxCommandIds) {
        this(runtime, doctor, transientState, true, maxCommandIds);
    }

    private SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor,
                                     SurfaceTransientState transientState, boolean hasTransientSurface,
                                     int maxCommandIds) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.doctor = Objects.requireNonNull(doctor, "doctor 不能为空");
        this.transientState = Objects.requireNonNull(transientState, "transientState 不能为空");
        if (maxCommandIds <= 0) throw new IllegalArgumentException("maxCommandIds 必须为正数");
        this.hasTransientSurface = hasTransientSurface;
        this.maxCommandIds = maxCommandIds;
    }

    /**
     * 执行一次命令分派并返回唯一终态。
     *
     * @param commandId 本次请求关联标识
     * @param intent 已解码且受限的命令意图
     * @param cancellationToken 本次请求的协作式取消边界
     * @return 与 commandId 一一对应的唯一终态
     */
    public synchronized SessionCommandResult dispatch(CommandId commandId, SessionCommandIntent intent,
                                                      CancellationToken cancellationToken) {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        Objects.requireNonNull(intent, "intent 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        SessionCommandResult previous = terminalResults.get(commandId);
        if (previous != null) return previous;
        if (terminalResults.size() >= maxCommandIds) {
            return rejected(intent.kind(), commandId, safeSessionId(), SessionCommandResultCode.REQUEST_BUDGET_EXHAUSTED);
        }
        SessionCommandResult result = dispatchOnce(commandId, intent, cancellationToken);
        terminalResults.put(commandId, result);
        return result;
    }

    private SessionCommandResult dispatchOnce(CommandId commandId, SessionCommandIntent intent,
                                              CancellationToken cancellationToken) {
        try {
            SessionId sessionId = runtime.sessionId();
            if (cancellationToken.isCancellationRequested()) {
                return terminal(intent.kind(), commandId, sessionId, SessionCommandStatus.CANCELLED,
                        SessionCommandResultCode.CANCELLED, new SessionCommandEvent.EmptyPayload());
            }
            if (requiresIdle(intent) && runtime.hasActiveRun()) {
                return terminal(intent.kind(), commandId, sessionId, SessionCommandStatus.REJECTED,
                        SessionCommandResultCode.ACTIVE_RUN, new SessionCommandEvent.EmptyPayload());
            }
            return switch (intent) {
                case SessionCommandIntent.Help ignored -> success(intent.kind(), commandId, sessionId, help());
                case SessionCommandIntent.Clear ignored -> clear(commandId, sessionId);
                case SessionCommandIntent.Compact compact -> compact(commandId, sessionId, compact.anchors(), cancellationToken);
                case SessionCommandIntent.Context ignored -> context(commandId, sessionId);
                case SessionCommandIntent.Doctor ignored -> success(intent.kind(), commandId, sessionId, doctor.report());
                case SessionCommandIntent.ModelChange model -> applyPatch(commandId, sessionId,
                        new SessionSettingsPatch.ModelName(model.modelName()), cancellationToken);
                case SessionCommandIntent.Permissions permissions -> permissions(commandId, sessionId, permissions.operation(),
                        cancellationToken);
                case SessionCommandIntent.Resume resume -> resume(commandId, sessionId, resume.sessionId(), cancellationToken);
                case SessionCommandIntent.Tasks ignored -> tasks(commandId, sessionId);
                case SessionCommandIntent.PlanStatus ignored -> planStatus(commandId, sessionId);
                case SessionCommandIntent.Plan create -> createPlan(commandId, sessionId, create);
                case SessionCommandIntent.PlanApprove approve -> planApprove(commandId, sessionId,
                        approve.planId(), approve.workspaceDigest());
                case SessionCommandIntent.PlanReject reject -> planReject(commandId, sessionId, reject.planId());
                case SessionCommandIntent.PlanStepBegin begin -> planBegin(commandId, sessionId, begin.workspaceDigest());
                case SessionCommandIntent.PlanStepComplete complete -> planComplete(commandId, sessionId, complete.workspaceDigest());
                case SessionCommandIntent.PlanExecute execute -> planExecute(commandId, sessionId,
                        execute.planId(), execute.workspaceDigest(), execute.maxSteps(), cancellationToken);
            };
        } catch (RuntimeException ignored) {
            return terminal(intent.kind(), commandId, safeSessionId(), SessionCommandStatus.FAILED,
                    SessionCommandResultCode.INTERNAL_FAILURE, new SessionCommandEvent.EmptyPayload());
        }
    }

    private SessionCommandResult planStatus(CommandId id, SessionId sid) {
        return runtime.planStatus()
                .map(value -> success(SessionCommandKind.PLAN_STATUS, id, sid, planPayload(value.document(), value.state())))
                .orElseGet(() -> rejected(SessionCommandKind.PLAN_STATUS, id, sid, SessionCommandResultCode.NOT_AVAILABLE));
    }

    private SessionCommandResult createPlan(CommandId id, SessionId sid, SessionCommandIntent.Plan input) {
        try {
            List<PlanStep> steps = input.steps().stream().map(s -> new PlanStep(s.ordinal(), s.title(), s.detail(), s.expectedDigest())).toList();
                return runtime.createPlan("plan-" + id.value(), input.objective(), steps, input.workspaceDigest())
                    .map(value -> success(SessionCommandKind.PLAN, id, sid, planPayload(value.document(), value.state())))
                    .orElseGet(() -> rejected(SessionCommandKind.PLAN, id, sid, SessionCommandResultCode.ACTIVE_RUN));
        } catch (IllegalArgumentException failure) {
            return rejected(SessionCommandKind.PLAN, id, sid, SessionCommandResultCode.INVALID_ARGUMENT);
        }
    }

    private SessionCommandResult planApprove(CommandId id, SessionId sid, String planId, String digest) {
        return runtime.approvePlan(planId, digest)
                .filter(value -> value.state().approvalGate()
                        == io.github.liumaishenjian.ccjava.domain.PlanApprovalGate.APPROVED)
                .map(value -> success(SessionCommandKind.PLAN_APPROVE, id, sid, planPayload(value.document(), value.state())))
                .orElseGet(() -> rejected(SessionCommandKind.PLAN_APPROVE, id, sid,
                        SessionCommandResultCode.INVALID_ARGUMENT));
    }

    private SessionCommandResult planReject(CommandId id, SessionId sid, String planId) {
        return runtime.rejectPlan(planId)
                .map(value -> success(SessionCommandKind.PLAN_REJECT, id, sid, planPayload(value.document(), value.state())))
                .orElseGet(() -> rejected(SessionCommandKind.PLAN_REJECT, id, sid, SessionCommandResultCode.NOT_AVAILABLE));
    }

    private SessionCommandResult planBegin(CommandId id, SessionId sid, String digest) {
        var outcome = runtime.beginPlanStep(digest);
        if (outcome.isEmpty()) return rejected(SessionCommandKind.PLAN_STEP_BEGIN, id, sid, SessionCommandResultCode.NOT_AVAILABLE);
        var value = outcome.orElseThrow();
        if (value.step().isEmpty()) return rejected(SessionCommandKind.PLAN_STEP_BEGIN, id, sid,
                value.state().status() == PlanStatus.DIGEST_CONFLICT ? SessionCommandResultCode.INVALID_ARGUMENT : SessionCommandResultCode.NOT_AVAILABLE);
        return success(SessionCommandKind.PLAN_STEP_BEGIN, id, sid, planPayload(value.document(), value.state()));
    }

    private SessionCommandResult planComplete(CommandId id, SessionId sid, String digest) {
        try {
            String current = runtime.currentWorkspaceDigest();
            String checked = digest.equals(current) ? current : "conflict-" + current;
            return runtime.completePlanStep(checked)
                    .map(next -> next.state().status() == PlanStatus.DIGEST_CONFLICT
                            ? rejected(SessionCommandKind.PLAN_STEP_COMPLETE, id, sid, SessionCommandResultCode.INVALID_ARGUMENT)
                            : success(SessionCommandKind.PLAN_STEP_COMPLETE, id, sid, planPayload(next.document(), next.state())))
                    .orElseGet(() -> rejected(SessionCommandKind.PLAN_STEP_COMPLETE, id, sid, SessionCommandResultCode.NOT_AVAILABLE));
        } catch (IllegalArgumentException invalid) {
            return rejected(SessionCommandKind.PLAN_STEP_COMPLETE, id, sid, SessionCommandResultCode.INVALID_ARGUMENT);
        }
    }

    private SessionCommandResult planExecute(CommandId id, SessionId sid, String planId,
                                              String workspaceDigest, int maxSteps,
                                              CancellationToken cancellationToken) {
        var current = runtime.planStatus();
        if (current.isEmpty()
                || !current.orElseThrow().document().id().equals(planId)
                || !current.orElseThrow().document().workspaceDigest().equals(workspaceDigest)
                || current.orElseThrow().state().approvalGate()
                    != io.github.liumaishenjian.ccjava.domain.PlanApprovalGate.APPROVED) {
            return rejected(SessionCommandKind.PLAN_EXECUTE, id, sid, SessionCommandResultCode.INVALID_ARGUMENT);
        }
        return runtime.executePlan(cancellationToken, maxSteps)
                .map(value -> success(SessionCommandKind.PLAN_EXECUTE, id, sid,
                        planPayload(value.document(), value.state())))
                .orElseGet(() -> rejected(SessionCommandKind.PLAN_EXECUTE, id, sid,
                        SessionCommandResultCode.NOT_AVAILABLE));
    }

    private static SessionCommandEvent.PlanPayload planPayload(PlanDocument document, PlanExecutionState state) {
        return new SessionCommandEvent.PlanPayload(document.id(), document.status().name(), state.approvalGate().name(),
                state.nextStep(), state.activeStep(), document.objective(), document.steps().stream()
                .map(step -> new SessionCommandEvent.PlanStepView(step.ordinal(), step.title(), step.detail(), step.expectedDigest())).toList(),
                document.workspaceDigest());
    }

    private SessionCommandResult resume(CommandId commandId, SessionId sessionId, SessionId targetId,
                                        CancellationToken cancellationToken) {
        return switch (runtime.resume(targetId, cancellationToken)) {
            case RESUMED -> success(SessionCommandKind.RESUME, commandId, runtime.sessionId(),
                    new SessionCommandEvent.ResumePayload(sessionId.value(), runtime.sessionId().value()));
            case CANCELLED -> terminal(SessionCommandKind.RESUME, commandId, sessionId, SessionCommandStatus.CANCELLED,
                    SessionCommandResultCode.CANCELLED, new SessionCommandEvent.EmptyPayload());
            case ACTIVE_RUN -> rejected(SessionCommandKind.RESUME, commandId, sessionId, SessionCommandResultCode.ACTIVE_RUN);
            case CURRENT_SESSION -> rejected(SessionCommandKind.RESUME, commandId, sessionId,
                    SessionCommandResultCode.CURRENT_SESSION);
            case SESSION_ACTIVE -> rejected(SessionCommandKind.RESUME, commandId, sessionId,
                    SessionCommandResultCode.SESSION_ACTIVE);
            case RECOVERY_REQUIRED, STALE -> rejected(SessionCommandKind.RESUME, commandId, sessionId,
                    SessionCommandResultCode.RECOVERY_REQUIRED);
            case INTERNAL_FAILURE -> terminal(SessionCommandKind.RESUME, commandId, sessionId, SessionCommandStatus.FAILED,
                    SessionCommandResultCode.INTERNAL_FAILURE, new SessionCommandEvent.EmptyPayload());
        };
    }

    private SessionCommandResult compact(CommandId commandId, SessionId sessionId, List<String> anchors,
                                         CancellationToken cancellationToken) {
        try {
            if (cancellationToken.isCancellationRequested()) {
                return terminal(SessionCommandKind.COMPACT, commandId, sessionId, SessionCommandStatus.CANCELLED,
                        SessionCommandResultCode.CANCELLED, new SessionCommandEvent.EmptyPayload());
            }
            return switch (runtime.compactForNextRun(anchors, cancellationToken)) {
                case ADOPTED -> success(SessionCommandKind.COMPACT, commandId, sessionId,
                        new SessionCommandEvent.EmptyPayload());
                case CANCELLED -> terminal(SessionCommandKind.COMPACT, commandId, sessionId,
                        SessionCommandStatus.CANCELLED, SessionCommandResultCode.CANCELLED,
                        new SessionCommandEvent.EmptyPayload());
                case ACTIVE_RUN -> rejected(SessionCommandKind.COMPACT, commandId, sessionId,
                        SessionCommandResultCode.ACTIVE_RUN);
                case UNAVAILABLE -> rejected(SessionCommandKind.COMPACT, commandId, sessionId,
                        SessionCommandResultCode.UNAVAILABLE);
                case STALE, REJECTED, HOOK_BLOCKED -> rejected(SessionCommandKind.COMPACT, commandId, sessionId,
                        SessionCommandResultCode.COMPACTION_REJECTED);
                case SUMMARIZER_FAILURE -> terminal(SessionCommandKind.COMPACT, commandId, sessionId,
                        SessionCommandStatus.FAILED, SessionCommandResultCode.INTERNAL_FAILURE,
                        new SessionCommandEvent.EmptyPayload());
            };
        } catch (IllegalArgumentException failure) {
            return rejected(SessionCommandKind.COMPACT, commandId, sessionId, SessionCommandResultCode.INVALID_ARGUMENT);
        }
    }

    private SessionCommandResult permissions(CommandId commandId, SessionId sessionId,
                                             SessionCommandIntent.PermissionsOperation operation,
                                             CancellationToken cancellationToken) {
        return switch (operation) {
            case SessionCommandIntent.PermissionsOperation.Query ignored -> permissionsView(commandId, sessionId);
            case SessionCommandIntent.PermissionsOperation.ModeChange change -> applyPatch(commandId, sessionId,
                    new SessionSettingsPatch.PermissionModeChange(change.mode()), cancellationToken);
            case SessionCommandIntent.PermissionsOperation.SelectionChange change -> applyPatch(commandId, sessionId,
                    new SessionSettingsPatch.PermissionSelectionChange(change.selection()), cancellationToken);
        };
    }

    /**
     * 通过 Session Settings Application 事务应用下一 Run 的受限标量更新。
     *
     * <p>调用方的取消 token 原样交给 Application，避免命令层把取消误报为参数错误。除成功、
     * 已知拒绝和取消外，Scope/CAS 构建故障统一收敛为内部失败，且不会回显配置值。</p>
     */
    private SessionCommandResult applyPatch(CommandId commandId, SessionId sessionId, SessionSettingsPatch patch,
                                            CancellationToken cancellationToken) {
        SessionCommandKind kind = patch instanceof SessionSettingsPatch.ModelName
                ? SessionCommandKind.MODEL_CHANGE : SessionCommandKind.PERMISSIONS;
        return runtime.patchSessionSettings(patch, cancellationToken)
                .<SessionCommandResult>map(result -> result.published()
                        ? kind == SessionCommandKind.PERMISSIONS
                                ? permissionsView(commandId, sessionId)
                                : success(kind, commandId, sessionId, new SessionCommandEvent.EmptyPayload())
                        : mappedPatchFailure(kind, commandId, sessionId, result))
                .orElseGet(() -> rejected(kind, commandId, sessionId, SessionCommandResultCode.NOT_AVAILABLE));
    }

    private static SessionCommandResult mappedPatchFailure(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                           SettingsApplicationService.SettingsApplicationResult result) {
        if (result.diagnostics().stream().anyMatch(diagnostic -> diagnostic instanceof SettingsApplicationService.ConfigurationFailure failure
                && failure.code() == io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode.CANCELLED)) {
            return terminal(kind, commandId, sessionId, SessionCommandStatus.CANCELLED, SessionCommandResultCode.CANCELLED,
                    new SessionCommandEvent.EmptyPayload());
        }
        if (result.diagnostics().stream().anyMatch(SessionCommandDispatcher::isInternalFailure)) {
            return terminal(kind, commandId, sessionId, SessionCommandStatus.FAILED, SessionCommandResultCode.INTERNAL_FAILURE,
                    new SessionCommandEvent.EmptyPayload());
        }
        return rejected(kind, commandId, sessionId, code(result));
    }

    private static boolean isInternalFailure(SettingsApplicationService.ApplicationDiagnostic diagnostic) {
        if (diagnostic instanceof SettingsApplicationService.RuntimeFailure failure) {
            return failure.code() == io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsDiagnosticCode.INTERNAL_FAILURE;
        }
        if (diagnostic instanceof SettingsApplicationService.ConfigurationFailure failure) {
            return failure.code() == io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode.CAS_CONFLICT
                    || failure.code() == io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode.REVISION_EXHAUSTED
                    || failure.code() == io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode.INTERNAL_FAILURE;
        }
        return false;
    }

    /**
     * 读取下一 Run 的实际 PermissionMode 与已发布 Settings 派生规则来源。
     *
     * <p>Runtime baseline mode 在无 LKG 时仍可查询；规则列表只报告 EffectiveSettings 中会映射为
     * STARTUP 的声明，绝不把 Session grant、Hard Denial 或其他 Runtime 规则误称为 Settings 规则。</p>
     */
    private SessionCommandResult permissionsView(CommandId commandId, SessionId sessionId) {
        var runtimeConfiguration = runtime.runtimeConfiguration();
        return runtime.settingsSnapshot().<SessionCommandResult>map(snapshot -> {
            var settings = snapshot.settings();
            var provenance = settings.permissionMode().map(value -> value.provenance());
            List<SessionCommandEvent.PermissionRuleProvenance> rules = settings.permissionRules().stream()
                    .map(this::safeRule).toList();
            return success(SessionCommandKind.PERMISSIONS, commandId, sessionId,
                    permissionsPayload(runtimeConfiguration, provenance.map(value -> value.sourceId().kind().name()).orElse("BASELINE"),
                            provenance.map(value -> value.sourceId().safeId()).orElse("runtime-baseline"),
                            provenance.map(value -> value.validationStatus().name()).orElse("BASELINE"), rules));
        }).orElseGet(() -> success(SessionCommandKind.PERMISSIONS, commandId, sessionId,
                permissionsPayload(runtimeConfiguration, "BASELINE", "runtime-baseline", "BASELINE", List.of())));
    }

    private static SessionCommandEvent.PermissionsPayload permissionsPayload(
            io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration configuration,
            String modeSourceKind, String modeSafeSourceId, String modeValidationStatus,
            List<SessionCommandEvent.PermissionRuleProvenance> rules) {
        String selection = selectionFor(configuration);
        return new SessionCommandEvent.PermissionsPayload(configuration.permissionMode().name(),
                configuration.approvalReviewer().name(), selection, modeSourceKind, modeSafeSourceId,
                modeValidationStatus, rules.size(), rules);
    }

    /**
     * 将运行时有效 mode/reviewer 投影为 Surface 选择；兼容 ACCEPT_EDITS 仅暴露 ADVANCED。
     */
    private static String selectionFor(io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration configuration) {
        if (configuration.permissionMode() == io.github.liumaishenjian.ccjava.domain.PermissionMode.ACCEPT_EDITS
                && configuration.approvalReviewer() == io.github.liumaishenjian.ccjava.domain.ApprovalReviewer.USER) {
            return "ADVANCED";
        }
        return java.util.Arrays.stream(io.github.liumaishenjian.ccjava.domain.PermissionSelection.values())
                .filter(value -> value.mode() == configuration.permissionMode()
                        && value.reviewer() == configuration.approvalReviewer())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("permission selection 不可投影"))
                .name();
    }

    private SessionCommandEvent.PermissionRuleProvenance safeRule(EffectivePermissionRule rule) {
        var provenance = rule.provenance();
        return new SessionCommandEvent.PermissionRuleProvenance(rule.definition().ruleId(),
                provenance.sourceId().kind().name(), provenance.sourceId().safeId(), provenance.operation().name(),
                provenance.validationStatus().name());
    }

    private static SessionCommandResultCode code(SettingsApplicationService.SettingsApplicationResult result) {
        return result.diagnostics().stream().anyMatch(diagnostic -> diagnostic instanceof SettingsApplicationService.RuntimeFailure failure
                && failure.code() == io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsDiagnosticCode.ACTIVE_RUN)
                ? SessionCommandResultCode.ACTIVE_RUN
                : SessionCommandResultCode.INVALID_ARGUMENT;
    }

    private SessionCommandResult clear(CommandId commandId, SessionId sessionId) {
        if (!hasTransientSurface) {
            return rejected(SessionCommandKind.CLEAR, commandId, sessionId, SessionCommandResultCode.DEFERRED);
        }
        transientState.clear();
        return success(SessionCommandKind.CLEAR, commandId, sessionId, new SessionCommandEvent.EmptyPayload());
    }

    private SessionCommandResult context(CommandId commandId, SessionId sessionId) {
        return runtime.latestContextUsage()
                .<SessionCommandResult>map(view -> success(SessionCommandKind.CONTEXT, commandId, sessionId,
                        DoctorReportService.context(view)))
                .orElseGet(() -> rejected(SessionCommandKind.CONTEXT, commandId, sessionId,
                        SessionCommandResultCode.UNAVAILABLE));
    }

    private SessionCommandResult tasks(CommandId commandId, SessionId sessionId) {
        return runtime.taskBoardSnapshot()
                .<SessionCommandResult>map(snapshot -> success(SessionCommandKind.TASKS, commandId, sessionId,
                        TaskBoardProjection.project(snapshot)))
                .orElseGet(() -> rejected(SessionCommandKind.TASKS, commandId, sessionId,
                        SessionCommandResultCode.UNAVAILABLE));
    }

    private static boolean requiresIdle(SessionCommandIntent intent) {
        return intent instanceof SessionCommandIntent.Compact
                || intent instanceof SessionCommandIntent.Plan
                || intent instanceof SessionCommandIntent.PlanApprove
                || intent instanceof SessionCommandIntent.PlanReject
                || intent instanceof SessionCommandIntent.PlanStepBegin
                || intent instanceof SessionCommandIntent.PlanStepComplete
                || intent instanceof SessionCommandIntent.PlanExecute
                || intent instanceof SessionCommandIntent.ModelChange
                || intent instanceof SessionCommandIntent.Permissions
                || intent instanceof SessionCommandIntent.Resume;
    }

    private SessionCommandEvent.HelpPayload help() {
        List<SessionCommandEvent.CommandAvailability> commands = Arrays.stream(SessionCommandKind.values())
                .filter(SessionCommandDispatcher::publicCommand)
                .map(kind -> new SessionCommandEvent.CommandAvailability(kind, support(kind))).toList();
        return new SessionCommandEvent.HelpPayload(commands);
    }

    /** 内部 Plan 运维 intent 保持协议兼容，但不进入用户可发现命令面。 */
    private static boolean publicCommand(SessionCommandKind kind) {
        return switch (kind) {
            case PLAN_APPROVE, PLAN_REJECT, PLAN_STEP_BEGIN, PLAN_STEP_COMPLETE, PLAN_EXECUTE -> false;
            default -> true;
        };
    }

    private SessionCommandEvent.CommandSupport support(SessionCommandKind kind) {
        return switch (kind) {
            case HELP, CONTEXT, DOCTOR -> SessionCommandEvent.CommandSupport.AVAILABLE;
            case CLEAR -> hasTransientSurface ? SessionCommandEvent.CommandSupport.AVAILABLE
                    : SessionCommandEvent.CommandSupport.DEFERRED;
            case COMPACT -> runtime.latestContextUsage().isPresent()
                    ? SessionCommandEvent.CommandSupport.AVAILABLE : SessionCommandEvent.CommandSupport.NOT_AVAILABLE;
            case MODEL_CHANGE -> runtime.settingsSnapshot().isPresent()
                    ? SessionCommandEvent.CommandSupport.AVAILABLE : SessionCommandEvent.CommandSupport.NOT_AVAILABLE;
            case PERMISSIONS -> SessionCommandEvent.CommandSupport.AVAILABLE;
            case TASKS -> runtime.taskBoardSnapshot().isPresent()
                    ? SessionCommandEvent.CommandSupport.AVAILABLE : SessionCommandEvent.CommandSupport.NOT_AVAILABLE;
            case RESUME, PLAN_STATUS, PLAN, PLAN_APPROVE, PLAN_REJECT, PLAN_STEP_BEGIN, PLAN_STEP_COMPLETE, PLAN_EXECUTE -> SessionCommandEvent.CommandSupport.AVAILABLE;
        };
    }

    private static SessionCommandResult success(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                SessionCommandEvent.SessionCommandPayload payload) {
        return terminal(kind, commandId, sessionId, SessionCommandStatus.SUCCEEDED, SessionCommandResultCode.OK, payload);
    }

    private static SessionCommandResult rejected(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                 SessionCommandResultCode code) {
        return terminal(kind, commandId, sessionId, SessionCommandStatus.REJECTED, code,
                new SessionCommandEvent.EmptyPayload());
    }

    private static SessionCommandResult terminal(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                 SessionCommandStatus status, SessionCommandResultCode code,
                                                 SessionCommandEvent.SessionCommandPayload payload) {
        SessionCommandEvent event = new SessionCommandEvent(kind, commandId, sessionId, status, code, payload);
        return switch (status) {
            case SUCCEEDED -> new SessionCommandResult.Succeeded(event);
            case REJECTED -> new SessionCommandResult.Rejected(event);
            case CANCELLED -> new SessionCommandResult.Cancelled(event);
            case FAILED -> new SessionCommandResult.Failed(event);
        };
    }

    private SessionId safeSessionId() {
        try {
            return runtime.sessionId();
        } catch (RuntimeException ignored) {
            return new SessionId("unavailable");
        }
    }
}
