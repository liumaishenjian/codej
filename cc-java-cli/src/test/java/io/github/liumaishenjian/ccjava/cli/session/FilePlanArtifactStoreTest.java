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
