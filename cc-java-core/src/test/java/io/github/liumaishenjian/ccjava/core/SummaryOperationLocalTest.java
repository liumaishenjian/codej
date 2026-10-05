package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryDiagnostic;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import io.github.liumaishenjian.ccjava.domain.SummaryTier;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR-100：局部操作没有 Run 身份，同时不能削弱普通 Run 的身份、层级和关闭 Gate。 */
class SummaryOperationLocalTest {
    private static final RunId RUN = new RunId("real-summary-run");
    private static final ContextTokenEstimator ESTIMATOR = new CodePointContextTokenEstimator();
    private static final ContextCapacity CAPACITY = new ContextCapacity("model", 1_000, 10, 10);
    private static final List<AgentMessage> MESSAGES = List.of(
            new UserMessage("a".repeat(80)), new UserMessage("b".repeat(80)), new UserMessage("tail"));
    private static final ProjectionRequest REQUEST = new ProjectionRequest(MESSAGES, CAPACITY, 3, 1, true);
    private static final ContextProjection PROJECTION = new ContextProjection(
            MESSAGES, ESTIMATOR.estimate(MESSAGES, CAPACITY), List.of(), 3);
    private static final SummaryReductionPolicy POLICY = new SummaryReductionPolicy(
            1, true, List.of(), List.of(), 64, 32);

    @Test
    void operationLocalAndRunEntriesCannotBeInterchanged() {
        try (SummaryAttemptGuard local = SummaryAttemptGuard.operationLocal();
             SummaryAttemptGuard run = new SummaryAttemptGuard(RUN)) {
            local.checkOperationLocal();
            assertThat(local.tryAcquire(3, SummaryTier.C3_ROLLING)).isTrue();
            assertThat(local.tryAcquire(3, SummaryTier.C3_ROLLING)).isFalse();
            assertThat(local.tryAcquire(3, SummaryTier.C4_FULL)).isTrue();
            assertThatThrownBy(() -> local.checkRun(RUN)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> local.tryAcquire(RUN, 3, SummaryTier.C3_ROLLING))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(run::checkOperationLocal).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> run.tryAcquire(3, SummaryTier.C3_ROLLING))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(run.tryAcquire(RUN, 3, SummaryTier.C3_ROLLING)).isTrue();
            assertThatThrownBy(() -> run.tryAcquire(new RunId("other-real-run"), 3, SummaryTier.C4_FULL))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> local.tryAcquire(-1, SummaryTier.C3_ROLLING))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void noOpAndCancelledReductionsStillRequireCorrectRealRunIdentity() {
        try (SummaryReductionCoordinator run = coordinator((r, c) -> Optional.empty(), new SummaryAttemptGuard(RUN));
             SummaryReductionCoordinator local = coordinator((r, c) -> Optional.empty(), SummaryAttemptGuard.operationLocal())) {
            assertThatThrownBy(() -> run.reduce(new RunId("wrong-run"), REQUEST, PROJECTION, POLICY, CancellationToken.none()))
                    .isInstanceOf(IllegalArgumentException.class);
            CancellationSource cancelled = new CancellationSource();
            cancelled.cancel();
            assertThatThrownBy(() -> run.reduceExplicitly(new RunId("wrong-run"), REQUEST, PROJECTION, POLICY, cancelled.token()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> run.reduceExplicitly(REQUEST, PROJECTION, POLICY, CancellationToken.none()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> local.reduceExplicitly(RUN, REQUEST, PROJECTION, POLICY, CancellationToken.none()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void localReductionPreservesC3ThenC4AndCooldownButFreshOperationCanTryAgain() {
        List<SummaryTier> calls = new ArrayList<>();
        ContextSummarizer empty = (request, token) -> {
            calls.add(request.tier());
            return Optional.empty();
        };
        try (SummaryReductionCoordinator local = coordinator(empty, SummaryAttemptGuard.operationLocal())) {
            SummaryOutcome first = local.reduceExplicitly(REQUEST, PROJECTION, POLICY, CancellationToken.none());
            assertThat(first.attemptedTiers()).containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
            assertThat(first.projection()).isSameAs(PROJECTION);
            SummaryOutcome second = local.reduceExplicitly(REQUEST, PROJECTION, POLICY, CancellationToken.none());
            assertThat(second.attemptedTiers()).isEmpty();
            assertThat(second.diagnostics()).extracting(SummaryDiagnostic::kind)
                    .containsExactly(SummaryDiagnostic.Kind.ATTEMPT_COOLDOWN, SummaryDiagnostic.Kind.ATTEMPT_COOLDOWN);
        }
        try (SummaryReductionCoordinator next = coordinator(empty, SummaryAttemptGuard.operationLocal())) {
            next.reduceExplicitly(REQUEST, PROJECTION, POLICY, CancellationToken.none());
        }
        assertThat(calls).containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL,
                SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
    }

    @Test
    void closingLocalGuardDuringSummaryDiscardsCandidateAndCannotAffectAnotherGuard() {
        SummaryAttemptGuard guard = SummaryAttemptGuard.operationLocal();
        AtomicInteger calls = new AtomicInteger();
        ContextSummarizer closing = (request, token) -> {
            calls.incrementAndGet();
            guard.close();
            return Optional.of(new SummaryCandidate(request.tier(), "brief", request.sourceRevision(),
                    request.sourceMessageIds(), 5, 5));
        };
        try (SummaryReductionCoordinator local = coordinator(closing, guard);
             SummaryAttemptGuard other = SummaryAttemptGuard.operationLocal()) {
            SummaryOutcome outcome = local.reduceExplicitly(REQUEST, PROJECTION, POLICY, CancellationToken.none());
            assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.CANCELLED);
            assertThat(outcome.projection()).isSameAs(PROJECTION);
            assertThat(outcome.adoptedCandidate()).isEmpty();
            assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> guard.tryAcquire(3, SummaryTier.C4_FULL)).isInstanceOf(IllegalStateException.class);
            assertThat(guard.commitIfOpen(() -> "must-not-publish")).isEmpty();
            assertThat(other.tryAcquire(3, SummaryTier.C3_ROLLING)).isTrue();
        }
    }

    private static SummaryReductionCoordinator coordinator(ContextSummarizer summarizer, SummaryAttemptGuard guard) {
        return new SummaryReductionCoordinator(summarizer, ESTIMATOR, guard);
    }
}
