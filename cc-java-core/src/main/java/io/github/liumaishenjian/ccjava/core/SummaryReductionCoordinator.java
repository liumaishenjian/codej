package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextReduction;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryDiagnostic;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SummaryTier;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 C1/C2 无法满足容量后按 C3、C4 顺序执行摘要 Gate 与候选提交。
 *
 * <p>Coordinator 只接收 Canonical Transcript 的不可变请求快照和 C1/C2 Projection；它不会
 * 修改两者。每个候选先校验 tier、revision、来源 ID、严格 UTF-8 字节、Token 预算、事实锚点、
 * Tool 协议污染和严格降幅，再用摘要消息替换选定 Projection 区间并重新估算。失败、空候选、
 * 取消和不满足容量均保留上一 Projection。最终采用必须通过 Run Guard 与 close 共锁提交：采用先
 * 线性化时可返回 ADOPTED，close 先线性化时必须丢弃候选并返回取消终态。</p>
 *
 * @since 0.7.0
 */
public final class SummaryReductionCoordinator implements AutoCloseable {

    private final ContextSummarizer summarizer;
    private final ContextTokenEstimator estimator;
    private final SummaryAttemptGuard attemptGuard;

    /**
     * 创建 C3/C4 Coordinator。
     *
     * @param summarizer 仅生成摘要候选、不得执行 Tool 的 Port
     * @param estimator 对候选 Projection 重新计算容量的 Core 策略
     * @param attemptGuard 绑定唯一 Run 或显式操作的 tier 冷却与关闭线性化 Guard
     */
    public SummaryReductionCoordinator(
            ContextSummarizer summarizer,
            ContextTokenEstimator estimator,
            SummaryAttemptGuard attemptGuard) {
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不能为空");
        this.estimator = Objects.requireNonNull(estimator, "estimator 不能为空");
        this.attemptGuard = Objects.requireNonNull(attemptGuard, "attemptGuard 不能为空");
    }

    /**
     * 对 C1/C2 Projection 尝试 C3，必要时再尝试 C4。
     *
     * @param runId 本次运行 ID，用于绑定冷却范围
     * @param request 原始 Canonical Transcript 快照和容量边界
     * @param previousProjection C1/C2 产生的最后完整 Projection
     * @param policy C3/C4 eligibility 与候选输出上限
     * @param cancellationToken 提交前必须检查的取消令牌
     * @return 候选提交或保持 previousProjection 的唯一终态
     */
    public SummaryOutcome reduce(
            RunId runId,
            ProjectionRequest request,
            ContextProjection previousProjection,
            SummaryReductionPolicy policy,
            CancellationToken cancellationToken) {
        attemptGuard.checkRun(runId);
        return reduceInternal((revision, tier) -> attemptGuard.tryAcquire(runId, revision, tier),
                request, previousProjection, policy, cancellationToken, false);
    }

    /**
     * 执行用户显式请求的 C3/C4 摘要尝试。
     *
     * <p>该入口仅跳过自动 reduction 在已满足预算时的短路；范围资格、Tool Call/Result
     * 协议边界、锚点、候选校验、单次尝试、取消和提交 Gate 与 {@link #reduce} 完全相同。
     * 因而它不能把普通自动 Run 扩展为主动摘要，也不能绕过任何摘要安全约束。</p>
     *
     * @param runId 本次显式请求的唯一运行标识
     * @param request 原始 Canonical Transcript 快照和容量边界
     * @param previousProjection C1/C2 后的完整 Projection
     * @param policy C3/C4 eligibility 与候选输出上限
     * @param cancellationToken 提交前必须检查的取消令牌
     * @return 候选提交或保持 previousProjection 的唯一终态
     */
    public SummaryOutcome reduceExplicitly(
            RunId runId,
            ProjectionRequest request,
            ContextProjection previousProjection,
            SummaryReductionPolicy policy,
            CancellationToken cancellationToken) {
        attemptGuard.checkRun(runId);
        return reduceInternal((revision, tier) -> attemptGuard.tryAcquire(runId, revision, tier),
                request, previousProjection, policy, cancellationToken, true);
    }

    /**
     * 执行无 Run 身份的显式摘要；调用方必须为每次操作新建局部 Guard 并关闭 Coordinator。
     *
     * <p>只省略 Run 身份，不省略 C3/C4 次序、冷却、候选及取消 Gate。
     * Run 级 Coordinator 不能通过此入口绕过所有权检查。</p>
     *
     * @param request 原始 Canonical 快照和容量边界
     * @param previousProjection C1/C2 后的完整 Projection
     * @param policy 摘要资格及输出限制
     * @param cancellationToken 提交前必须检查的取消令牌
     * @return 采用候选或保留原 Projection 的终态
     */
    public SummaryOutcome reduceExplicitly(
            ProjectionRequest request,
            ContextProjection previousProjection,
            SummaryReductionPolicy policy,
            CancellationToken cancellationToken) {
        attemptGuard.checkOperationLocal();
        return reduceInternal(attemptGuard::tryAcquire,
                request, previousProjection, policy, cancellationToken, true);
    }

    private SummaryOutcome reduceInternal(
            AttemptEntry entry,
            ProjectionRequest request,
            ContextProjection previousProjection,
            SummaryReductionPolicy policy,
            CancellationToken cancellationToken,
            boolean explicitRequest) {
        if (!attemptGuard.isOpen()) {
            throw new IllegalStateException("SummaryReductionCoordinator 已关闭");
        }
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(previousProjection, "previousProjection 不能为空");
        Objects.requireNonNull(policy, "policy 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        verifyInputs(request, previousProjection);

        if (cancellationToken.isCancellationRequested()) {
            return cancelled(previousProjection, List.of(), List.of(), null);
        }
        if (!explicitRequest && previousProjection.usage().fits()) {
            return unchanged(
                    previousProjection,
                    List.of(),
                    List.of(SummaryDiagnostic.global(
                            SummaryDiagnostic.Kind.SUMMARY_NOT_REQUIRED)));
        }

        ArrayList<SummaryTier> attempts = new ArrayList<>();
        ArrayList<SummaryDiagnostic> diagnostics = new ArrayList<>();
        int protectedStart = previousProjection.messages().size()
                - request.protectedMessageCount();
        int firstSummarizable = firstSummarizableIndex(previousProjection.messages());

        Optional<Range> c3Range = rollingRange(
                previousProjection.messages(),
                firstSummarizable,
                protectedStart,
                policy.rollingWindowEndExclusive());
        if (c3Range.isEmpty()) {
            diagnostics.add(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.ROLLING_WINDOW_INELIGIBLE,
                    SummaryTier.C3_ROLLING));
        } else {
            Attempt c3 = attempt(
                    entry,
                    request,
                    previousProjection,
                    policy,
                    SummaryTier.C3_ROLLING,
                    c3Range.orElseThrow(),
                    policy.rollingProtectedAnchors(),
                    attempts,
                    cancellationToken);
            diagnostics.addAll(c3.diagnostics());
            if (c3.wasCancelled()) {
                return cancelled(previousProjection, attempts, diagnostics, SummaryTier.C3_ROLLING);
            }
            if (c3.projection().isPresent()) {
                return adopted(
                        previousProjection,
                        c3.projection().orElseThrow(),
                        attempts,
                        diagnostics,
                        c3.candidate().orElseThrow());
            }
        }

        if (cancellationToken.isCancellationRequested() || !attemptGuard.isOpen()) {
            return cancelled(previousProjection, attempts, diagnostics, null);
        }
        if (!policy.fullSummaryPrerequisitesSatisfied()) {
            diagnostics.add(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.FULL_SUMMARY_PREREQUISITES_MISSING,
                    SummaryTier.C4_FULL));
            return unchanged(previousProjection, attempts, diagnostics);
        }
        Optional<Range> c4Range = fullRange(
                previousProjection.messages(), firstSummarizable, protectedStart);
        if (c4Range.isEmpty()) {
            diagnostics.add(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.FULL_SUMMARY_PREREQUISITES_MISSING,
                    SummaryTier.C4_FULL));
            return unchanged(previousProjection, attempts, diagnostics);
        }
        Attempt c4 = attempt(
                entry,
                request,
                previousProjection,
                policy,
                SummaryTier.C4_FULL,
                c4Range.orElseThrow(),
                policy.fullProtectedAnchors(),
                attempts,
                cancellationToken);
        diagnostics.addAll(c4.diagnostics());
        if (c4.wasCancelled()) {
            return cancelled(previousProjection, attempts, diagnostics, SummaryTier.C4_FULL);
        }
        if (c4.projection().isPresent()) {
            return adopted(
                    previousProjection,
                    c4.projection().orElseThrow(),
                    attempts,
                    diagnostics,
                    c4.candidate().orElseThrow());
        }
        return unchanged(previousProjection, attempts, diagnostics);
    }

    private Attempt attempt(
            AttemptEntry entry,
            ProjectionRequest projectionRequest,
            ContextProjection previous,
            SummaryReductionPolicy policy,
            SummaryTier tier,
            Range range,
            List<String> anchors,
            List<SummaryTier> attempts,
            CancellationToken cancellationToken) {
        if (!entry.acquire(projectionRequest.sourceRevision(), tier)) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.ATTEMPT_COOLDOWN, tier));
        }
        attempts.add(tier);
        if (cancellationToken.isCancellationRequested() || !attemptGuard.isOpen()) {
            return Attempt.cancelled();
        }
        if (!isProtocolBoundary(previous.messages(), range)) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.SOURCE_BOUNDARY_INVALID, tier));
        }

        SummaryRequest request;
        try {
            String snapshot = SummaryMessageCodec.snapshot(
                    previous.messages(), range.fromInclusive(), range.toExclusive());
            long sourceTokens = estimator.estimate(
                    previous.messages().subList(range.fromInclusive(), range.toExclusive()),
                    projectionRequest.capacity()).totalTokens();
            long maxTokens = Math.min(policy.maxOutputTokens(), sourceTokens - 1);
            if (maxTokens < 1) {
                return Attempt.rejected(SummaryDiagnostic.tier(
                        SummaryDiagnostic.Kind.NO_TOKEN_REDUCTION, tier));
            }
            request = new SummaryRequest(
                    tier,
                    snapshot,
                    projectionRequest.sourceRevision(),
                    SummaryMessageCodec.sourceIds(
                            projectionRequest.sourceRevision(),
                            range.fromInclusive(),
                            range.toExclusive()),
                    anchors,
                    policy.maxOutputUtf8Bytes(),
                    maxTokens,
                    sourceTokens);
        } catch (IllegalArgumentException | ArithmeticException invalidInput) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.INPUT_LIMIT_EXCEEDED, tier));
        }

        Optional<SummaryCandidate> supplied;
        try {
            supplied = Objects.requireNonNull(
                    summarizer.summarize(request, cancellationToken),
                    "summarizer result 不能为空");
        } catch (RuntimeException failure) {
            if (cancellationToken.isCancellationRequested()) {
                return Attempt.cancelled();
            }
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.SUMMARIZER_FAILED, tier));
        }
        if (cancellationToken.isCancellationRequested() || !attemptGuard.isOpen()) {
            return Attempt.cancelled();
        }
        if (supplied.isEmpty()) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.EMPTY_CANDIDATE, tier));
        }
        SummaryCandidate candidate = supplied.orElseThrow();
        SummaryDiagnostic rejection = validateCandidate(request, candidate);
        if (rejection != null) {
            return Attempt.rejected(rejection);
        }

        List<AgentMessage> candidateMessages = replaceRange(
                previous.messages(), range, candidate);
        if (!isValidProtocol(candidateMessages)) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.SOURCE_BOUNDARY_INVALID, tier));
        }
        if (cancellationToken.isCancellationRequested() || !attemptGuard.isOpen()) {
            return Attempt.cancelled();
        }
        ContextUsage usage = estimator.estimate(candidateMessages, projectionRequest.capacity());
        if (cancellationToken.isCancellationRequested() || !attemptGuard.isOpen()) {
            return Attempt.cancelled();
        }
        if (usage.totalTokens() >= previous.usage().totalTokens()) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.NO_TOKEN_REDUCTION, tier));
        }
        if (!usage.fits()) {
            return Attempt.rejected(SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.CAPACITY_STILL_EXCEEDED, tier));
        }
        if (cancellationToken.isCancellationRequested() || !attemptGuard.isOpen()) {
            return Attempt.cancelled();
        }
        ArrayList<ContextReduction> reductions = new ArrayList<>(
                previous.appliedReductions());
        reductions.add(new ContextReduction(
                tier.strategy(),
                previous.usage().totalTokens(),
                usage.totalTokens(),
                candidate.sourceMessageIds().size()));
        ContextProjection projection = new ContextProjection(
                candidateMessages,
                usage,
                reductions,
                previous.sourceRevision());
        if (cancellationToken.isCancellationRequested()) {
            return Attempt.cancelled();
        }
        return attemptGuard.commitIfOpen(() -> Attempt.adopted(candidate, projection))
                .orElseGet(Attempt::cancelled);
    }

    private SummaryDiagnostic validateCandidate(
            SummaryRequest request,
            SummaryCandidate candidate) {
        SummaryTier tier = request.tier();
        if (candidate.tier() != tier) {
            return SummaryDiagnostic.tier(SummaryDiagnostic.Kind.TIER_MISMATCH, tier);
        }
        if (candidate.sourceRevision() != request.sourceRevision()) {
            return SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.STALE_SOURCE_REVISION, tier);
        }
        if (!candidate.sourceMessageIds().equals(request.sourceMessageIds())) {
            return SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.SOURCE_COVERAGE_MISMATCH, tier);
        }
        if (candidate.utf8Bytes() > request.maxOutputUtf8Bytes()) {
            return SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.OUTPUT_BYTE_LIMIT_EXCEEDED, tier);
        }
        if (candidate.estimatedTokens() >= request.sourceEstimatedTokens()) {
            return SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.NO_TOKEN_REDUCTION, tier);
        }
        if (candidate.estimatedTokens() > request.maxOutputTokens()) {
            return SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.OUTPUT_TOKEN_LIMIT_EXCEEDED, tier);
        }
        for (String anchor : request.requiredProtectedAnchors()) {
            if (!candidate.summary().contains(anchor)) {
                return SummaryDiagnostic.tier(
                        SummaryDiagnostic.Kind.PROTECTED_ANCHOR_LOSS, tier);
            }
        }
        if (containsToolProtocol(candidate.summary())) {
            return SummaryDiagnostic.tier(
                    SummaryDiagnostic.Kind.TOOL_PROTOCOL_CONTAMINATION, tier);
        }
        return null;
    }

    private boolean containsToolProtocol(String summary) {
        String lower = summary.toLowerCase(java.util.Locale.ROOT);
        String compact = lower.replaceAll("\\s+", "");
        return lower.contains("<tool_call")
                || lower.contains("</tool_call")
                || lower.contains("<tool_result")
                || lower.contains("</tool_result")
                || lower.contains("[tool_call]")
                || lower.contains("[tool_result]")
                || lower.contains("tool_use_id")
                || compact.contains("\"type\":\"tool_use\"")
                || compact.contains("\"type\":\"tool_result\"");
    }

    private List<AgentMessage> replaceRange(
            List<AgentMessage> messages,
            Range range,
            SummaryCandidate candidate) {
        ArrayList<AgentMessage> projected = new ArrayList<>(messages.size());
        projected.addAll(messages.subList(0, range.fromInclusive()));
        projected.add(new ContextSummaryMessage(
                candidate.tier(), candidate.summary(), candidate.sourceMessageIds()));
        projected.addAll(messages.subList(range.toExclusive(), messages.size()));
        return List.copyOf(projected);
    }

    private Optional<Range> rollingRange(
            List<AgentMessage> messages,
            int firstSummarizable,
            int protectedStart,
            int requestedEnd) {
        int end = Math.min(requestedEnd, protectedStart);
        if (requestedEnd <= 0
                || requestedEnd > protectedStart
                || end <= firstSummarizable
                || end > messages.size()) {
            return Optional.empty();
        }
        Range range = new Range(firstSummarizable, end);
        return isProtocolBoundary(messages, range) ? Optional.of(range) : Optional.empty();
    }

    private Optional<Range> fullRange(
            List<AgentMessage> messages,
            int firstSummarizable,
            int protectedStart) {
        if (protectedStart <= firstSummarizable || protectedStart > messages.size()) {
            return Optional.empty();
        }
        Range range = new Range(firstSummarizable, protectedStart);
        return isProtocolBoundary(messages, range) ? Optional.of(range) : Optional.empty();
    }

    private int firstSummarizableIndex(List<AgentMessage> messages) {
        int index = 0;
        while (index < messages.size() && messages.get(index) instanceof SystemMessage) {
            index++;
        }
        return index;
    }

    private boolean isProtocolBoundary(List<AgentMessage> messages, Range range) {
        return range.fromInclusive() >= 0
                && range.toExclusive() <= messages.size()
                && range.fromInclusive() < range.toExclusive()
                && isValidProtocol(messages.subList(range.fromInclusive(), range.toExclusive()));
    }

    private boolean isValidProtocol(List<AgentMessage> messages) {
        List<String> pending = List.of();
        int resultIndex = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (AgentMessage message : messages) {
            if (message instanceof AssistantMessage assistant && !assistant.toolCalls().isEmpty()) {
                if (!pending.isEmpty() && resultIndex != pending.size()) {
                    return false;
                }
                ArrayList<String> ids = new ArrayList<>();
                for (var call : assistant.toolCalls()) {
                    if (!seen.add(call.id())) {
                        return false;
                    }
                    ids.add(call.id());
                }
                pending = List.copyOf(ids);
                resultIndex = 0;
            } else if (message instanceof ToolResultMessage result) {
                if (pending.isEmpty()
                        || resultIndex >= pending.size()
                        || !pending.get(resultIndex).equals(result.result().callId())) {
                    return false;
                }
                resultIndex++;
                if (resultIndex == pending.size()) {
                    pending = List.of();
                    resultIndex = 0;
                }
            } else if (!pending.isEmpty()) {
                return false;
            }
        }
        return pending.isEmpty();
    }

    private void verifyInputs(
            ProjectionRequest request,
            ContextProjection previousProjection) {
        if (request.sourceRevision() != previousProjection.sourceRevision()) {
            throw new IllegalArgumentException("request 与 previousProjection revision 不一致");
        }
        if (!isValidProtocol(request.canonicalMessages())
                || !isValidProtocol(previousProjection.messages())) {
            throw new IllegalArgumentException("输入 Projection 的 Tool 协议非法");
        }
        int protectedStart = request.canonicalMessages().size()
                - request.protectedMessageCount();
        List<AgentMessage> canonicalTail = request.canonicalMessages().subList(
                protectedStart, request.canonicalMessages().size());
        if (previousProjection.messages().size() < canonicalTail.size()
                || !previousProjection.messages().subList(
                        previousProjection.messages().size() - canonicalTail.size(),
                        previousProjection.messages().size()).equals(canonicalTail)) {
            throw new IllegalArgumentException("Projection 未逐条保留 Canonical protected tail");
        }
    }

    /**
     * 关闭本 Run 的摘要冷却状态；关闭后拒绝新 reduce，进行中的候选不得提交。
     */
    @Override
    public void close() {
        attemptGuard.close();
    }

    private SummaryOutcome adopted(
            ContextProjection previous,
            ContextProjection projection,
            List<SummaryTier> attempts,
            List<SummaryDiagnostic> diagnostics,
            SummaryCandidate candidate) {
        return new SummaryOutcome(
                SummaryOutcome.Status.ADOPTED,
                previous,
                projection,
                attempts,
                diagnostics,
                Optional.of(candidate));
    }

    private SummaryOutcome unchanged(
            ContextProjection previous,
            List<SummaryTier> attempts,
            List<SummaryDiagnostic> diagnostics) {
        return new SummaryOutcome(
                SummaryOutcome.Status.UNCHANGED,
                previous,
                previous,
                attempts,
                diagnostics,
                Optional.empty());
    }

    private SummaryOutcome cancelled(
            ContextProjection previous,
            List<SummaryTier> attempts,
            List<SummaryDiagnostic> diagnostics,
            SummaryTier tier) {
        ArrayList<SummaryDiagnostic> complete = new ArrayList<>(diagnostics);
        complete.add(tier == null
                ? SummaryDiagnostic.global(SummaryDiagnostic.Kind.CANCELLED)
                : SummaryDiagnostic.tier(SummaryDiagnostic.Kind.CANCELLED, tier));
        return new SummaryOutcome(
                SummaryOutcome.Status.CANCELLED,
                previous,
                previous,
                attempts,
                complete,
                Optional.empty());
    }

    /** 内部占用策略固定入口身份，不把缺失身份编码成 null 或合成 Run ID。 */
    @FunctionalInterface
    private interface AttemptEntry {
        boolean acquire(long revision, SummaryTier tier);
    }

    private record Range(int fromInclusive, int toExclusive) {
    }

    private record Attempt(
            Optional<SummaryCandidate> candidate,
            Optional<ContextProjection> projection,
            List<SummaryDiagnostic> diagnostics,
            boolean wasCancelled) {

        private static Attempt adopted(
                SummaryCandidate candidate,
                ContextProjection projection) {
            return new Attempt(
                    Optional.of(candidate), Optional.of(projection), List.of(), false);
        }

        private static Attempt rejected(SummaryDiagnostic diagnostic) {
            return new Attempt(Optional.empty(), Optional.empty(), List.of(diagnostic), false);
        }

        private static Attempt cancelled() {
            return new Attempt(Optional.empty(), Optional.empty(), List.of(), true);
        }
    }
}
