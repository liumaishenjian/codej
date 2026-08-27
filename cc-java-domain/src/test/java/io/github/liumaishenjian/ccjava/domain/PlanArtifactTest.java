package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 PlanArtifact 的单调时钟和 Fork 身份边界。 */
class PlanArtifactTest {
    private static final SessionId SOURCE = new SessionId("session-plan-source");
    private static final Instant CREATED = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void nextRevisionClampsWallClockRollbackToCurrentUpdatedAt() {
        PlanArtifact current = PlanArtifact.create(
                "plan-source", SOURCE, "# Plan", PlanStatus.DRAFT, CREATED)
                .nextRevision("# Plan\n\nApproved", PlanStatus.APPROVED, CREATED.plusSeconds(10));

        PlanArtifact next = current.nextRevision(
                "# Plan\n\nStill monotonic", PlanStatus.EXECUTING, CREATED.minusSeconds(30));

        assertThat(next.revision()).isEqualTo(3);
        assertThat(next.updatedAt()).isEqualTo(current.updatedAt());
        assertThat(next.updatedAt()).isAfterOrEqualTo(next.createdAt());
    }

    @Test
    void forkUsesIndependentIdentityRevisionAndReapprovalEvenFromTerminalSource() {
        PlanArtifact completed = PlanArtifact.create(
                "plan-source", SOURCE, "# Completed plan", PlanStatus.COMPLETED, CREATED);
        SessionId target = new SessionId("session-plan-target");

        PlanArtifact fork = completed.fork("plan-target", target, CREATED.plusSeconds(1));

        assertThat(fork.planId()).isEqualTo("plan-target");
        assertThat(fork.sessionId()).isEqualTo(target);
        assertThat(fork.revision()).isEqualTo(1);
        assertThat(fork.status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
        assertThat(fork.markdownContent()).isEqualTo(completed.markdownContent());
    }
    @Test
    void lifecyclePolicyAllowsContentRevisionsButClosesTerminalChains() {
        assertThat(PlanLifecyclePolicy.validInitial(PlanStatus.DRAFT)).isTrue();
        assertThat(PlanLifecyclePolicy.validInitial(PlanStatus.AWAITING_APPROVAL)).isTrue();
        assertThat(PlanLifecyclePolicy.validInitial(PlanStatus.APPROVED)).isFalse();
        assertThat(PlanLifecyclePolicy.validTransition(PlanStatus.DRAFT, PlanStatus.DRAFT)).isTrue();
        assertThat(PlanLifecyclePolicy.validTransition(
                PlanStatus.AWAITING_APPROVAL, PlanStatus.AWAITING_APPROVAL)).isTrue();
        assertThat(PlanLifecyclePolicy.validTransition(
                PlanStatus.AWAITING_APPROVAL, PlanStatus.DRAFT)).isTrue();
        assertThat(PlanLifecyclePolicy.validTransition(
                PlanStatus.NEEDS_VERIFICATION, PlanStatus.AWAITING_APPROVAL)).isTrue();
        assertThat(PlanLifecyclePolicy.validTransition(
                PlanStatus.NEEDS_VERIFICATION, PlanStatus.EXECUTING)).isFalse();
        assertThat(PlanLifecyclePolicy.validTransition(PlanStatus.REJECTED, PlanStatus.REJECTED)).isFalse();
        assertThat(PlanLifecyclePolicy.validTransition(PlanStatus.COMPLETED, PlanStatus.APPROVED)).isFalse();
    }

}
