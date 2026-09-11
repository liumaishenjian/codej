package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanContextPolicy;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanVerificationResumeReview;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Markdown PlanArtifact 的 manifest 发布、CAS、崩溃边界、身份和路径安全。 */
class FilePlanArtifactStoreTest {
    private static final SessionId SESSION = new SessionId("session-plan-artifact");
    private static final Instant CREATED = Instant.parse("2026-08-20T00:00:00Z");

    @TempDir Path root;

    @Test
    void publishesImmutableGenerationThroughSingleAuthoritativeManifest() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact first = PlanArtifact.create("plan-artifact", SESSION, "# Plan\n\nFirst", PlanStatus.DRAFT, CREATED);
        assertThat(store.save(first, 0, "")).isEqualTo(first);
        PlanArtifact second = first.nextRevision("# Plan\n\nSecond", PlanStatus.AWAITING_APPROVAL,
                CREATED.plusSeconds(1));
        assertThat(store.save(second, first.revision(), first.contentDigest())).isEqualTo(second);
        assertThat(store.load(SESSION)).contains(second);
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(
                            "plan-r1-" + first.contentDigest() + ".md",
                            "plan-r2-" + second.contentDigest() + ".md",
                            FilePlanArtifactStore.MANIFEST_FILE);
        }
    }

    @Test
    void verificationResumeReviewSurvivesStoreReopenWithoutAdvancingRevision() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact base = PlanArtifact.create(
                "plan-resume", SESSION, "# Resume", PlanStatus.AWAITING_APPROVAL, CREATED);
        PlanArtifact marked = new PlanArtifact(
                base.planId(), base.sessionId(), base.revision(), base.markdownContent(), base.contentDigest(),
                base.status(), base.createdAt(), base.updatedAt(), Optional.empty(),
                Optional.of(new PlanVerificationResumeReview(PermissionMode.DEFAULT, PlanContextPolicy.KEEP)),
                base.evidenceLedger());

        store.save(marked, 0, "");
        PlanArtifact reopened = new FilePlanArtifactStore(directory, SESSION).load(SESSION).orElseThrow();

        assertThat(reopened).isEqualTo(marked);
        assertThat(reopened.revision()).isEqualTo(1);
    }

    @Test
    void generationDurableButManifestNotSwitchedLeavesOldCompleteRevisionVisible() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore stable = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact first = stable.save(PlanArtifact.create(
                "plan-artifact", SESSION, "# First", PlanStatus.DRAFT, CREATED), 0, "");
        PlanArtifact second = first.nextRevision("# Second", PlanStatus.AWAITING_APPROVAL, CREATED.plusSeconds(1));
        AtomicBoolean injected = new AtomicBoolean();
        FilePlanArtifactStore crashing = new FilePlanArtifactStore(directory, SESSION, artifact -> {
            if (artifact.revision() == 2 && injected.compareAndSet(false, true)) {
                throw new InjectedCrash();
            }
        });

        assertThatThrownBy(() -> crashing.save(second, 1, first.contentDigest()))
                .isInstanceOf(InjectedCrash.class);
        assertThat(stable.load(SESSION)).contains(first);
        assertThat(Files.exists(directory.resolve(
                "plan-r2-" + second.contentDigest() + ".md"))).isTrue();
    }

    @Test
    void rejectsManifestThatReferencesMissingOrTamperedGeneration() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact first = store.save(PlanArtifact.create(
                "plan-artifact", SESSION, "# First", PlanStatus.DRAFT, CREATED), 0, "");
        Path generation = directory.resolve("plan-r1-" + first.contentDigest() + ".md");
        Files.delete(generation);
        assertThatThrownBy(() -> store.load(SESSION))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(PlanArtifactStoreException.Code.CORRUPT));

        Files.writeString(generation, "tampered", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.load(SESSION))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(PlanArtifactStoreException.Code.CORRUPT));
    }

    @Test
    void rejectsStaleRevisionAndDigestWithoutChangingCurrentArtifact() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact first = store.save(PlanArtifact.create(
                "plan-artifact", SESSION, "# First", PlanStatus.DRAFT, CREATED), 0, "");
        PlanArtifact second = first.nextRevision("# Second", PlanStatus.DRAFT, CREATED.plusSeconds(1));
        assertThatThrownBy(() -> store.save(second, 2, first.contentDigest()))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.STALE_REVISION));
        assertThatThrownBy(() -> store.save(second, 1, "f".repeat(64)))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.DIGEST_CONFLICT));
        assertThat(store.load(SESSION)).contains(first);
    }

    @Test
    void replacesOnlyAnExactlyMatchedRejectedPlanWithANewDraftIdentity() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact draft = store.save(PlanArtifact.create(
                "plan-old", SESSION, "# Old", PlanStatus.DRAFT, CREATED), 0, "");
        PlanArtifact awaiting = store.save(draft.nextRevision(
                "# Old", PlanStatus.AWAITING_APPROVAL, CREATED.plusSeconds(1)),
                draft.revision(), draft.contentDigest());
        PlanArtifact rejected = store.save(awaiting.nextRevision(
                "# Old", PlanStatus.REJECTED, CREATED.plusSeconds(2)),
                awaiting.revision(), awaiting.contentDigest());
        PlanArtifact regressed = PlanArtifact.create(
                "plan-regressed", SESSION, "# Regressed", PlanStatus.DRAFT, CREATED.plusSeconds(1));
        assertThatThrownBy(() -> store.replaceTerminal(regressed, rejected.planId(),
                rejected.revision(), rejected.contentDigest()))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.INVALID_STATE));

        PlanArtifact boundBase = PlanArtifact.create(
                "plan-bound", SESSION, "# Bound", PlanStatus.DRAFT, CREATED.plusSeconds(3));
        var boundLedger = boundBase.evidenceLedger().bind(
                1, "a".repeat(64), "b".repeat(64), CREATED.plusSeconds(3));
        PlanArtifact bound = new PlanArtifact(
                boundBase.planId(), boundBase.sessionId(), boundBase.revision(),
                boundBase.markdownContent(), boundBase.contentDigest(), boundBase.status(),
                boundBase.createdAt(), boundBase.updatedAt(), boundBase.executionBrief(),
                boundBase.verificationResumeReview(), boundLedger);
        assertThat(bound.evidenceLedger().requirements()).isEmpty();
        assertThatThrownBy(() -> store.replaceTerminal(bound, rejected.planId(),
                rejected.revision(), rejected.contentDigest()))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.INVALID_STATE));

        PlanArtifact dirtyBase = PlanArtifact.create(
                "plan-dirty", SESSION, "# Dirty", PlanStatus.DRAFT, CREATED.plusSeconds(3));
        var dirtyLedger = dirtyBase.evidenceLedger().declare(
                new io.github.liumaishenjian.ccjava.domain.PlanEvidenceRequirement(
                        "inherited", io.github.liumaishenjian.ccjava.domain.PlanEvidenceKind.DELIVERABLE,
                        "old.txt", "old evidence", true), CREATED.plusSeconds(3));
        PlanArtifact dirty = new PlanArtifact(
                dirtyBase.planId(), dirtyBase.sessionId(), dirtyBase.revision(),
                dirtyBase.markdownContent(), dirtyBase.contentDigest(), dirtyBase.status(),
                dirtyBase.createdAt(), dirtyBase.updatedAt(), dirtyBase.executionBrief(),
                dirtyBase.verificationResumeReview(), dirtyLedger);
        assertThatThrownBy(() -> store.replaceTerminal(dirty, rejected.planId(),
                rejected.revision(), rejected.contentDigest()))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.INVALID_STATE));

        PlanArtifact fresh = PlanArtifact.create(
                "plan-new", SESSION, "# Fresh", PlanStatus.DRAFT, CREATED.plusSeconds(3));
        assertThat(store.load(SESSION)).contains(rejected);
        assertThat(store.replaceTerminal(fresh, rejected.planId(),
                rejected.revision(), rejected.contentDigest())).isEqualTo(fresh);
        assertThat(store.load(SESSION)).contains(fresh);
        assertThat(fresh.revision()).isEqualTo(1);
        assertThat(fresh.evidenceLedger().requirements()).isEmpty();

        assertThatThrownBy(() -> store.replaceTerminal(
                PlanArtifact.create("plan-other", SESSION, "# Other", PlanStatus.DRAFT,
                        CREATED.plusSeconds(4)), rejected.planId(), rejected.revision(), rejected.contentDigest()))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.IDENTITY_MISMATCH));
    }

    @Test
    void rejectsSessionMismatchAndLinkedManifest() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact foreign = PlanArtifact.create("plan-foreign", new SessionId("session-foreign"),
                "# Foreign", PlanStatus.DRAFT, CREATED);
        assertThatThrownBy(() -> store.save(foreign, 0, ""))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.IDENTITY_MISMATCH));
        Path external = Files.writeString(root.resolve("external.json"), "{}", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(directory.resolve(FilePlanArtifactStore.MANIFEST_FILE), external);
        } catch (UnsupportedOperationException | IOException denied) {
            return;
        }
        assertThatThrownBy(() -> store.load(SESSION))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(PlanArtifactStoreException.Code.PATH_REJECTED));
    }

    @Test
    void rejectsIllegalInitialAndTransitionBeforePublishingAnyFile() throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        FilePlanArtifactStore store = new FilePlanArtifactStore(directory, SESSION);
        PlanArtifact illegalInitial = PlanArtifact.create(
                "plan-artifact", SESSION, "# Invalid", PlanStatus.COMPLETED, CREATED);

        assertThatThrownBy(() -> store.save(illegalInitial, 0, ""))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.INVALID_STATE));
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }

        PlanArtifact first = store.save(PlanArtifact.create(
                "plan-artifact", SESSION, "# First", PlanStatus.DRAFT, CREATED), 0, "");
        PlanArtifact illegalTransition = first.nextRevision(
                "# Invalid jump", PlanStatus.COMPLETED, CREATED.plusSeconds(1));
        assertThatThrownBy(() -> store.save(illegalTransition, first.revision(), first.contentDigest()))
                .isInstanceOfSatisfying(PlanArtifactStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                PlanArtifactStoreException.Code.INVALID_STATE));
        assertThat(store.load(SESSION)).contains(first);
        assertThat(Files.exists(directory.resolve(
                "plan-r2-" + illegalTransition.contentDigest() + ".md"))).isFalse();
    }

    private static final class InjectedCrash extends RuntimeException {
    }
}
