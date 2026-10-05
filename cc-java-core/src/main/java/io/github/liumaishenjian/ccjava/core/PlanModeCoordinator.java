package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanApprovalGate;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 管理两阶段 Plan 生命周期：只读探索产出工件，显式批准后逐步执行。
 *
 * <p>协调器只维护摘要绑定和步骤状态，不执行 Tool。步骤执行必须继续交给统一
 * {@link ToolExecutionPipeline}，因此 Permission、Hard Denial、Hook 和取消边界不被绕过。</p>
 */
public final class PlanModeCoordinator {
    private PlanDocument document;
    private PlanExecutionState state;

    /**
     * 将草稿或待审文档规范化为 PENDING 审批状态，不授予执行权限。
     * @param document 非空且状态为 DRAFT 或 AWAITING_APPROVAL 的文档
     */
    public PlanModeCoordinator(PlanDocument document) {
        this.document = Objects.requireNonNull(document, "document 不能为空");
        if (document.status() != PlanStatus.DRAFT && document.status() != PlanStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("规划必须从 DRAFT 或 AWAITING_APPROVAL 开始");
        }
        this.document = document.withStatus(PlanStatus.AWAITING_APPROVAL);
        this.state = new PlanExecutionState(document.id(), PlanApprovalGate.PENDING,
                1, null, PlanStatus.AWAITING_APPROVAL, document.workspaceDigest());
    }

    /**
     * 从已经校验的 durable projection 恢复，不执行任何步骤。
     * @param document 已校验的持久化计划
     * @param state 与文档具有相同计划身份的持久化执行状态
     * @return 保留原状态且尚未执行步骤的协调器
     */
    public static PlanModeCoordinator restore(PlanDocument document, PlanExecutionState state) {
        Objects.requireNonNull(document, "document 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        if (!document.id().equals(state.planId())) throw new IllegalArgumentException("Plan ID 不匹配");
        PlanModeCoordinator coordinator = new PlanModeCoordinator(document, state);
        return coordinator;
    }

    private PlanModeCoordinator(PlanDocument document, PlanExecutionState state) {
        this.document = document;
        this.state = state;
    }

    /**
     * 获取当前不可变文档快照。
     * @return 随状态迁移更新的计划文档
     */
    public synchronized PlanDocument document() { return document; }
    /**
     * 获取当前不可变执行状态快照。
     * @return 审批 Gate、游标与摘要绑定
     */
    public synchronized PlanExecutionState state() { return state; }

    /**
     * 显式批准，并绑定批准瞬间观察到的当前摘要。
     * @param currentDigest 批准瞬间的非空工作区摘要
     * @return 批准或冲突状态；非 PENDING 时原样返回
     */
    public synchronized PlanExecutionState approve(String currentDigest) {
        Objects.requireNonNull(currentDigest, "currentDigest 不能为空");
        if (state.approvalGate() != PlanApprovalGate.PENDING) return state;
        if (!document.workspaceDigest().equals(currentDigest)) return conflict(currentDigest);
        document = document.withStatus(PlanStatus.APPROVED);
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                1, null, PlanStatus.APPROVED, currentDigest);
        return state;
    }

    /**
     * 拒绝尚未批准的计划；不撤销已生效的 APPROVED Gate。
     * @return 拒绝后的状态，已批准时保持原状态
     */
    public synchronized PlanExecutionState reject() {
        if (state.approvalGate() == PlanApprovalGate.APPROVED) return state;
        document = document.withStatus(PlanStatus.REJECTED);
        state = new PlanExecutionState(document.id(), PlanApprovalGate.REJECTED,
                state.nextStep(), null, PlanStatus.REJECTED, state.workspaceDigest());
        return state;
    }

    /**
     * 原子领取步骤；必须匹配当前预期摘要且保证唯一活动步骤。
     * @param currentDigest 领取时的非空工作区摘要
     * @return 本次独占领取的步骤；Gate、游标或摘要不满足时为空
     */
    public synchronized Optional<PlanStep> beginNext(String currentDigest) {
        Objects.requireNonNull(currentDigest, "currentDigest 不能为空");
        if (state.approvalGate() != PlanApprovalGate.APPROVED || state.nextStep() == null
                || state.activeStep() != null) return Optional.empty();
        if (!state.workspaceDigest().equals(currentDigest)) {
            conflict(currentDigest);
            return Optional.empty();
        }
        int ordinal = state.nextStep();
        PlanStep step = document.steps().get(ordinal - 1);
        if (!step.expectedDigest().equals(currentDigest)) {
            conflict(currentDigest);
            return Optional.empty();
        }
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                null, ordinal, PlanStatus.EXECUTING, currentDigest);
        document = document.withStatus(PlanStatus.EXECUTING);
        return Optional.of(step);
    }

    /**
     * 完成活动步骤并以成功执行后的真实摘要推进下一步预期摘要。
     *
     * <p>步骤开始前的实时漂移由 {@link #beginNext(String)} 与调用方的 Tool 前置 Gate 拦截；
     * 成功 Tool 本身可以产生预期副作用，因此完成摘要不要求等于步骤开始摘要。下一步只接受这里
     * 记录的新摘要，从而继续阻止两个步骤之间的外部漂移。旧版无参数调用保留为兼容入口，但不会
     * 伪造摘要推进。</p>
     * @param completedDigest 成功执行后的非空工作区摘要
     * @return 推进后的状态；无正在执行的活动步骤时保持原状态
     */
    public synchronized PlanExecutionState completeStep(String completedDigest) {
        Objects.requireNonNull(completedDigest, "completedDigest 不能为空");
        if (state.activeStep() == null || state.status() != PlanStatus.EXECUTING) return state;
        int next = state.activeStep() + 1;
        boolean done = next > document.steps().size();
        List<PlanStep> steps = new ArrayList<>(document.steps());
        if (!done) steps.set(next - 1, steps.get(next - 1).withExpectedDigest(completedDigest));
        document = new PlanDocument(document.id(), document.objective(), steps,
                done ? PlanStatus.COMPLETED : PlanStatus.APPROVED, completedDigest);
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                done ? null : next, null, done ? PlanStatus.COMPLETED : PlanStatus.APPROVED,
                completedDigest);
        return state;
    }

    /**
     * 旧版无参数兼容入口必须显式失败，避免调用方误以为步骤已完成。
     *
     * @return 始终抛出异常，不返回状态
     * @throws IllegalArgumentException 缺少完成后的工作区摘要
     */
    @Deprecated
    public synchronized PlanExecutionState completeStep() {
        throw new IllegalArgumentException("completeStep 必须携带 completedDigest");
    }

    /**
     * 暂停已批准计划，并把活动步骤退回待领取游标；本方法不取消执行器。
     * @return 暂停后的状态；未批准时保持原状态
     */
    public synchronized PlanExecutionState pause() {
        if (state.approvalGate() != PlanApprovalGate.APPROVED) return state;
        Integer next = state.activeStep() == null ? state.nextStep() : state.activeStep();
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                next, null, PlanStatus.PAUSED, state.workspaceDigest());
        document = document.withStatus(PlanStatus.PAUSED);
        return state;
    }

    /**
     * 摘要未漂移时恢复暂停计划的可领取状态，不自动重放步骤。
     * @param currentDigest 恢复时的非空工作区摘要
     * @return 恢复或冲突状态；非 PAUSED 时保持原状态
     */
    public synchronized PlanExecutionState resume(String currentDigest) {
        Objects.requireNonNull(currentDigest, "currentDigest 不能为空");
        if (state.status() != PlanStatus.PAUSED) return state;
        if (!state.workspaceDigest().equals(currentDigest)) return conflict(currentDigest);
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                state.nextStep(), null, PlanStatus.APPROVED, currentDigest);
        document = document.withStatus(PlanStatus.APPROVED);
        return state;
    }

    /**
     * 领取整份已批准的自然语言 Plan，供一个正常 Agent Run 逐步落实。
     *
     * <p>本方法只改变 Plan 状态；模型调用和 Tool 执行仍由 AgentRuntime 与统一 Pipeline 负责。</p>
     * @param currentDigest 启动前的非空工作区摘要
     * @return 领取或冲突后的状态；Gate 不允许时保持原状态
     */
    public synchronized PlanExecutionState beginAgentRun(String currentDigest) {
        Objects.requireNonNull(currentDigest, "currentDigest 不能为空");
        if (state.approvalGate() != PlanApprovalGate.APPROVED || state.activeStep() != null
                || state.nextStep() == null || isTerminalFailure(state.status())) return state;
        if (!state.workspaceDigest().equals(currentDigest)) return conflict(currentDigest);
        document = document.withStatus(PlanStatus.EXECUTING);
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                null, state.nextStep(), PlanStatus.EXECUTING, currentDigest);
        return state;
    }

    /**
     * 仅在正常 Agent Run 成功终止后完成整份 Plan，并接受副作用产生的新摘要。
     * @param completedDigest 成功 Run 后的非空工作区摘要
     * @return 完成后的状态；无活动执行时保持原状态
     */
    public synchronized PlanExecutionState completeAgentRun(String completedDigest) {
        Objects.requireNonNull(completedDigest, "completedDigest 不能为空");
        if (state.status() != PlanStatus.EXECUTING || state.activeStep() == null) return state;
        document = new PlanDocument(document.id(), document.objective(), document.steps(),
                PlanStatus.COMPLETED, completedDigest);
        state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                null, null, PlanStatus.COMPLETED, completedDigest);
        return state;
    }

    /**
     * 把未成功结束的 Agent Run 收敛为可观察终态，不得显示 COMPLETED。
     * @param failureStatus 该协调器支持的失败终态，不接受 COMPLETED
     * @param digest 终止时的非空工作区摘要
     * @return 清除活动步骤后的失败状态
     */
    public synchronized PlanExecutionState failAgentRun(PlanStatus failureStatus, String digest) {
        Objects.requireNonNull(failureStatus, "failureStatus 不能为空");
        if (!isTerminalFailure(failureStatus)) throw new IllegalArgumentException("failureStatus 必须是失败终态");
        return terminal(failureStatus, Objects.requireNonNull(digest, "digest 不能为空"));
    }

    /**
     * 在批准、摘要和单活动步骤 Gate 下顺序执行全部剩余步骤。
     *
     * <p>每个步骤只领取一次；首个非成功结果立即进入可恢复终态，后续步骤不会被
     * 自动尝试。执行器必须把副作用调用交给统一 Tool 管线。</p>
     * @param executor 非空步骤执行器，不得绕过权限管线
     * @param cancellationToken 非空的取消与剩余时间令牌
     * @param maxSteps 本次最多执行的步骤数，范围为 1 到 128
     * @return 最终或预算内暂停推进的当前状态；达到步骤数上限不伪造成功
     */
    public synchronized PlanExecutionState executeAll(
            PlanStepExecutor executor, CancellationToken cancellationToken, int maxSteps) {
        Objects.requireNonNull(executor, "executor 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (maxSteps < 1 || maxSteps > 128) throw new IllegalArgumentException("maxSteps 无效");
        if (state.status() == PlanStatus.COMPLETED || isTerminalFailure(state.status())) return state;
        if (state.approvalGate() != PlanApprovalGate.APPROVED || state.activeStep() != null
                || state.nextStep() == null) return state;
        int executed = 0;
        while (state.nextStep() != null && executed < maxSteps) {
            if (cancellationToken.isCancellationRequested()) return terminal(PlanStatus.CANCELLED,
                    state.workspaceDigest());
            if (cancellationToken.remainingTime().orElse(java.time.Duration.ofDays(1)).isZero()
                    || cancellationToken.remainingTime().orElse(java.time.Duration.ofDays(1)).isNegative()) {
                return terminal(PlanStatus.TIMED_OUT, state.workspaceDigest());
            }
            Optional<PlanStep> step = beginNext(state.workspaceDigest());
            if (step.isEmpty()) return state;
            PlanStepExecutionResult result = PlanStepExecutor.requireResult(
                    executor.execute(step.orElseThrow(), cancellationToken));
            if (result.status() != PlanStepExecutionResult.Status.SUCCESS) {
                return terminal(statusFor(result.status()), result.workspaceDigest());
            }
            completeStep(result.workspaceDigest());
            executed++;
        }
        return state;
    }

    private static boolean isTerminalFailure(PlanStatus status) {
        return status == PlanStatus.FAILED || status == PlanStatus.CANCELLED
                || status == PlanStatus.TIMED_OUT || status == PlanStatus.LIMIT_EXCEEDED
                || status == PlanStatus.DIGEST_CONFLICT || status == PlanStatus.REJECTED;
    }

    private static PlanStatus statusFor(PlanStepExecutionResult.Status status) {
        return switch (status) {
            case DENIED, FAILURE -> PlanStatus.FAILED;
            case CANCELLED -> PlanStatus.CANCELLED;
            case TIMED_OUT -> PlanStatus.TIMED_OUT;
            case LIMIT_EXCEEDED -> PlanStatus.LIMIT_EXCEEDED;
            case CONFLICT -> PlanStatus.DIGEST_CONFLICT;
            case SUCCESS -> PlanStatus.COMPLETED;
        };
    }

    private PlanExecutionState terminal(PlanStatus next, String digest) {
        document = document.withStatus(next);
        state = new PlanExecutionState(document.id(), state.approvalGate(), state.nextStep(),
                null, next, digest);
        return state;
    }

    private PlanExecutionState conflict(String digest) {
        return terminal(PlanStatus.DIGEST_CONFLICT, digest);
    }
}
