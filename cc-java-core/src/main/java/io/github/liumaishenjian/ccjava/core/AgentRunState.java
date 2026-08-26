package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 保存单次 Agent Run 的显式、非全局预算状态。
 *
 * <p>Print/API/SDK 调用方提供的总模型回合和总 Tool 次数严格执行；对应维度 absent 时，
 * 普通 Interactive、Plan 与 approved-plan 不检查隐式总次数。连续 repeated-failure 批次仍独立
 * 计数，用于在无总次数限制时阻止确定性失败死循环。</p>
 *
 * @since 0.1.0
 */
final class AgentRunState {
    private final SessionId sessionId;
    private final RunId runId;
    private final AgentLimits limits;
    private int modelTurns;
    private int toolCalls;
    private int consecutiveRepeatedFailureBatches;
    private boolean finished;

    AgentRunState(SessionId sessionId, RunId runId, AgentLimits limits) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.runId = Objects.requireNonNull(runId, "runId 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
    }

    Optional<BudgetGovernanceReason> ensureModelBudget() {
        if (limits.totalModelTurns().isPresent()
                && modelTurns >= limits.totalModelTurns().getAsInt()) {
            return Optional.of(BudgetGovernanceReason.EXPLICIT_LIMIT);
        }
        return Optional.empty();
    }

    int recordModelTurnAttempt() {
        ensureRunning();
        if (limits.totalModelTurns().isPresent()
                && modelTurns >= limits.totalModelTurns().getAsInt()) {
            throw new IllegalStateException("模型回合预算已经耗尽");
        }
        return ++modelTurns;
    }

    /**
     * 在追加 Assistant Tool Call 批次前原子检查显式预算。
     *
     * <p>present 的硬上限无法容纳整批时不允许部分执行，以保持 Assistant Message 与全部
     * Tool Result 一一配对。对应维度 absent 时始终放行，直到用户取消、单次 timeout 或重复
     * 失败熔断生效。</p>
     *
     * @param batchSize 同一 Assistant Message 中的 Tool Call 数量
     * @return 空表示可以执行；显式硬上限不足时返回确定性终止原因
     */
    Optional<BudgetGovernanceReason> ensureToolBatchBudget(int batchSize) {
        if (batchSize < 0) throw new IllegalArgumentException("batchSize 不能小于 0");
        if (limits.totalToolCalls().isEmpty()) return Optional.empty();
        long required = (long) toolCalls + batchSize;
        return required <= limits.totalToolCalls().getAsInt()
                ? Optional.empty()
                : Optional.of(BudgetGovernanceReason.EXPLICIT_LIMIT);
    }

    int recordToolCall() {
        ensureRunning();
        if (limits.totalToolCalls().isPresent()
                && toolCalls >= limits.totalToolCalls().getAsInt()) {
            throw new IllegalStateException("Tool Call 预算已经耗尽");
        }
        return ++toolCalls;
    }

    void recordBatchResults(List<ToolResult> results) {
        Objects.requireNonNull(results, "results 不能为空");
        boolean repeatedOnly = !results.isEmpty() && results.stream().allMatch(result ->
                result.error().map(error -> error.code() == ToolErrorCode.REPEATED_FAILURE).orElse(false));
        consecutiveRepeatedFailureBatches = repeatedOnly
                ? consecutiveRepeatedFailureBatches + 1 : 0;
    }

    /**
     * 返回是否已连续两批只收到 repeated-failure 结果。
     *
     * <p>Runtime 只在整批结果均已与原 Call ID 配对并追加历史后检查该信号，
     * 因而不会破坏多 Tool Call 协议。任一不同结果都会重置计数。</p>
     */
    boolean repeatedFailureCircuitOpen() {
        return consecutiveRepeatedFailureBatches >= 2;
    }

    int modelTurns() { return modelTurns; }
    int toolCalls() { return toolCalls; }
    java.util.OptionalInt totalModelTurns() { return limits.totalModelTurns(); }
    java.util.OptionalInt totalToolCalls() { return limits.totalToolCalls(); }

    AgentRunResult complete(String text) {
        markFinished();
        return AgentRunResult.completed(sessionId, runId, text, modelTurns, toolCalls);
    }

    AgentRunResult stop(StopReason reason) { return stop(reason, Optional.empty()); }

    AgentRunResult stop(StopReason reason, Optional<ModelFailureSummary> failure) {
        markFinished();
        return AgentRunResult.stopped(sessionId, runId, reason, failure, modelTurns, toolCalls);
    }

    private void markFinished() { ensureRunning(); finished = true; }
    private void ensureRunning() {
        if (finished) throw new IllegalStateException("Agent Run 已经进入终态");
    }
}
