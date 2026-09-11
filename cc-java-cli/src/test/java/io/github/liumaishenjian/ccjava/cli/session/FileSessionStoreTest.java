package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind;
import io.github.liumaishenjian.ccjava.core.ToolResolutionReason;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.PlanApprovalGate;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 S06 文件 Session Adapter 的恢复边界和本机单 Writer lease。 */
class FileSessionStoreTest {

    private static final SessionSpec SPEC = new SessionSpec(
            "test instructions",
            Map.of("model", "fake-model"));

    @TempDir
    Path temporaryRoot;

    @Test
    void planSnapshotReopensAndInstallsSessionOwnedPlanWithoutReplay() throws IOException {
        Path workspace = workspace("plan-reopen");
        Path root = storeRoot("plan-reopen");
        SessionId id;
        PlanDocument document = new PlanDocument("plan-durable", "safe change", List.of(
                new PlanStep(1, "step", "detail", "digest-a")), PlanStatus.APPROVED, "digest-a");
        PlanExecutionState state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                1, null, PlanStatus.APPROVED, "digest-a");
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.planSnapshot(id, document, state);
            store.close(id);
        }
        try (FileSessionStore reopened = store(root, workspace, 2)) {
            SessionOpenResult result = reopened.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(id)), SPEC);
            assertThat(result.session().plan()).isPresent();
            assertThat(result.session().plan().orElseThrow().state()).isEqualTo(state);
            assertThat(result.session().plan().orElseThrow().document()).isEqualTo(document);
            assertThat(result.session().hasActiveRun()).isFalse();
        }
    }

    @Test
    void activePlanStepRequiresRecoveryChoiceAndDoesNotReplay() throws IOException {
        Path workspace = workspace("plan-active");
        Path root = storeRoot("plan-active");
        SessionId id;
        PlanDocument document = new PlanDocument("plan-active", "safe change", List.of(
                new PlanStep(1, "step", "detail", "digest-a")), PlanStatus.EXECUTING, "digest-a");
        PlanExecutionState state = new PlanExecutionState(document.id(), PlanApprovalGate.APPROVED,
                null, 1, PlanStatus.EXECUTING, "digest-a");
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.planSnapshot(id, document, state);
            store.close(id);
        }
        try (FileSessionStore reopened = store(root, workspace, 2)) {
            assertThatThrownBy(() -> reopened.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(id)), SPEC))
                    .isInstanceOf(SessionOpenException.class)
                    .extracting(error -> ((SessionOpenException) error).code())
                    .isEqualTo("RECOVERY_REQUIRED");
            SessionOpenResult inspect = reopened.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, Optional.of(id)), SPEC);
            assertThat(inspect.issues()).extracting(issue -> issue.kind())
                    .contains(SessionRecoveryIssueKind.PLAN_ACTIVE_STEP_RECOVERY);
            assertThat(inspect.session().plan()).isPresent();
        }
    }

    @Test
    void roundTripsResolvedAndCompletedResultsWithoutTokenRecords() throws IOException {
        Path workspace = workspace("round-trip");
        Path storeRoot = storeRoot("round-trip");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId firstRun = new RunId("run-1");
            ToolCall denied = new ToolCall("call-denied", "write_file", JsonObject.empty());
            store.runStarted(sessionId, firstRun, new UserMessage("first"));
            store.assistantAppended(sessionId, firstRun, AssistantMessage.tools(java.util.List.of(denied)));
            store.toolResolved(
                    sessionId,
                    firstRun,
                    1,
                    ToolResult.denied("call-denied", "write_file", "denied"),
                    ToolResolutionReason.PERMISSION_DENIED);
            store.runCompleted(sessionId, firstRun, StopReason.COMPLETED);

            RunId secondRun = new RunId("run-2");
            ToolCall read = new ToolCall("call-read", "read_file", JsonObject.empty());
            store.runStarted(sessionId, secondRun, new UserMessage("second"));
            store.assistantAppended(sessionId, secondRun, AssistantMessage.tools(java.util.List.of(read)));
            store.toolStarted(
                    sessionId,
                    secondRun,
                    1,
                    read.id(),
                    read.name(),
                    ToolEffect.READ_WORKSPACE);
            store.toolCompleted(
                    sessionId,
                    secondRun,
                    1,
                    ToolResult.success(read.id(), read.name(), "bounded result"));
            store.assistantAppended(sessionId, secondRun, AssistantMessage.text("done"));
            store.runCompleted(sessionId, secondRun, StopReason.COMPLETED);
            store.close(sessionId);
        }

        try (FileSessionStore resumedStore = store(storeRoot, workspace, 100)) {
            SessionOpenResult resumed = resumedStore.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);

            assertThat(resumed.readOnly()).isFalse();
            assertThat(resumed.issues()).isEmpty();
            assertThat(resumed.session().messages()).hasSize(7);
            assertThat(resumed.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .extracting(message -> ((ToolResultMessage) message).result().callId())
                    .containsExactly("call-denied", "call-read");
            ToolResultMessage deniedResult = resumed.session().messages().stream()
                    .filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast).findFirst().orElseThrow();
            assertThat(deniedResult.result().error().orElseThrow().category())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.PERMISSION);
            assertThat(deniedResult.result().error().orElseThrow().retryable()).isFalse();
            assertThat(Files.readAllLines(journal(storeRoot, sessionId), StandardCharsets.UTF_8))
                    .noneMatch(line -> line.contains("token") || line.contains("chunk"));
        }
    }

    @Test
    void skillJournalPersistsOnlyIdentityAndResumeNeverReplaysInvocation() throws IOException {
        Path workspace = workspace("skill-journal");
        Path storeRoot = storeRoot("skill-journal");
        SessionId sessionId;
        RunId runId = new RunId("run-skill");
        var recovery = new io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord(
                new io.github.liumaishenjian.ccjava.domain.skill.SkillId("review"),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
                "e".repeat(64), "f".repeat(64), "1".repeat(64), "2".repeat(64),
                "3".repeat(64), "4".repeat(64));
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            store.runStarted(sessionId, runId, new UserMessage("invoke"));
            store.skillInvoked(sessionId, runId,
                    io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind.EXPLICIT, recovery);
            store.skillCompleted(sessionId, runId, recovery.skillId(),
                    io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind.EXPLICIT, null);
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }

        String jsonl = Files.readString(journal(storeRoot, sessionId), StandardCharsets.UTF_8);
        assertThat(jsonl).contains("skill.invoked", "skill.completed", "\"skillId\":\"review\"")
                .contains("a".repeat(64), "b".repeat(64))
                .doesNotContain("ARG_SENTINEL", "PRIVATE_BODY_SENTINEL",
                        "PRIVATE_RESOURCE_SENTINEL", "PRIVATE_PATH_SENTINEL",
                        "PRIVATE_ENDPOINT_SENTINEL", "PRIVATE_ENV_SENTINEL", "absolutePath");
        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            SessionOpenResult opened = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)), SPEC);
            assertThat(opened.skillRecords()).containsExactly(recovery);
            assertThat(opened.session().messages()).containsExactly(new UserMessage("invoke"));
            assertThat(opened.issues()).isEmpty();
        }
    }

    @Test
    void unfinishedSkillInvocationFailsClosedOnResume() throws IOException {
        Path workspace = workspace("skill-unfinished");
        Path storeRoot = storeRoot("skill-unfinished");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-skill-unfinished");
            store.runStarted(sessionId, runId, new UserMessage("invoke"));
            store.skillInvoked(sessionId, runId,
                    io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind.MODEL,
                    new io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord(
                            new io.github.liumaishenjian.ccjava.domain.skill.SkillId("review"),
                            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
                            "e".repeat(64), "f".repeat(64), "1".repeat(64), "2".repeat(64),
                            "3".repeat(64), "4".repeat(64)));
            store.runCompleted(sessionId, runId, StopReason.INTERNAL_ERROR);
            store.close(sessionId);
        }
        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)), SPEC))
                    .isInstanceOf(SessionOpenException.class)
                    .extracting(failure -> ((SessionOpenException) failure).code())
                    .isEqualTo("RECOVERY_REQUIRED");
        }
    }

    @Test
    void forkCopiesCompletedHistoryWithoutChangingSourceAndResumesCleanly() throws IOException {
        Path workspace = workspace("fork");
        Path storeRoot = storeRoot("fork");
        SessionId sourceId;
        java.util.List<io.github.liumaishenjian.ccjava.domain.AgentMessage> sourceMessages;
        byte[] sourceBytes;
        SessionId forkId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sourceId = store.create(SPEC).id();
            RunId runId = new RunId("run-fork-source");
            ToolCall call = new ToolCall("call-fork-read", "read_file", JsonObject.empty());
            store.runStarted(sourceId, runId, new UserMessage("inspect"));
            store.assistantAppended(sourceId, runId, AssistantMessage.tools(java.util.List.of(call)));
            store.toolStarted(
                    sourceId,
                    runId,
                    1,
                    call.id(),
                    call.name(),
                    ToolEffect.READ_WORKSPACE);
            store.toolCompleted(
                    sourceId,
                    runId,
                    1,
                    ToolResult.success(call.id(), call.name(), "fork-result"));
            store.assistantAppended(sourceId, runId, AssistantMessage.text("source done"));
            store.runCompleted(sourceId, runId, StopReason.COMPLETED);
            sourceMessages = store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.INSPECT,
                            java.util.Optional.of(sourceId)),
                    SPEC).session().messages();
            sourceBytes = Files.readAllBytes(journal(storeRoot, sourceId));

            SessionOpenResult forked = store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.FORK,
                            java.util.Optional.of(sourceId)),
                    SPEC);
            forkId = forked.session().id();
            assertThat(forkId).isNotEqualTo(sourceId);
            assertThat(forked.parentSessionId()).contains(sourceId);
            assertThat(forked.issues()).isEmpty();
            assertThat(forked.session().messages()).isEqualTo(sourceMessages);
            assertThat(forked.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .hasSize(1);
            assertThat(Files.readAllBytes(journal(storeRoot, sourceId))).isEqualTo(sourceBytes);
            store.close(forkId);
            assertThat(Files.readAllBytes(journal(storeRoot, sourceId))).isEqualTo(sourceBytes);
        }

        try (FileSessionStore resumedStore = store(storeRoot, workspace, 100)) {
            SessionOpenResult resumedFork = resumedStore.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(forkId)),
                    SPEC);
            assertThat(resumedFork.parentSessionId()).contains(sourceId);
            assertThat(resumedFork.issues()).isEmpty();
            assertThat(resumedFork.session().messages()).isEqualTo(sourceMessages);
            assertThat(resumedFork.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .hasSize(1);
            assertThat(Files.readAllBytes(journal(storeRoot, sourceId))).isEqualTo(sourceBytes);
        }
    }

    @Test
    void continueSkipsNewerDamagedOrUnfinishedSession() throws IOException {
        Path workspace = workspace("continue-clean");
        Path storeRoot = storeRoot("continue-clean");
        SessionId cleanId;
        SessionId damagedId;
        SessionId unfinishedId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            cleanId = store.create(SPEC).id();
            store.close(cleanId);
            damagedId = store.create(SPEC).id();
            store.close(damagedId);
            unfinishedId = store.create(SPEC).id();
            store.runStarted(
                    unfinishedId,
                    new RunId("run-unfinished-latest"),
                    new UserMessage("unfinished"));
        }
        Files.writeString(
                journal(storeRoot, damagedId),
                "{\"schemaMajor\":1",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        Files.setLastModifiedTime(
                journal(storeRoot, cleanId),
                FileTime.from(Instant.parse("2026-08-03T00:00:00Z")));
        Files.setLastModifiedTime(
                journal(storeRoot, damagedId),
                FileTime.from(Instant.parse("2026-08-03T00:01:00Z")));
        Files.setLastModifiedTime(
                journal(storeRoot, unfinishedId),
                FileTime.from(Instant.parse("2026-08-03T00:02:00Z")));

        try (FileSessionStore continuing = store(storeRoot, workspace, 100)) {
            SessionOpenResult result = continuing.open(
                    SessionOpenRequest.continueLatest(),
                    SPEC);
            assertThat(result.session().id()).isEqualTo(cleanId);
            assertThat(result.mode()).isEqualTo(SessionOpenMode.CONTINUE);
            assertThat(result.issues()).isEmpty();
        }
    }

    @Test
    void sameStoreDuplicateResumePreservesOriginalWriter() throws IOException {
        Path workspace = workspace("same-store-resume");
        Path storeRoot = storeRoot("same-store-resume");
        SessionId sessionId = createAndClose(storeRoot, workspace);

        try (FileSessionStore store = store(storeRoot, workspace, 100)) {
            SessionOpenResult original = store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC);
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SESSION_ACTIVE"));

            RunId runId = new RunId("run-after-duplicate-resume");
            store.runStarted(sessionId, runId, new UserMessage("still writable"));
            store.assistantAppended(sessionId, runId, AssistantMessage.text("still active"));
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            assertThat(store.find(sessionId)).contains(original.session());
            store.close(sessionId);
        }

        try (FileSessionStore reopened = store(storeRoot, workspace, 200)) {
            SessionOpenResult resumed = reopened.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(resumed.issues()).isEmpty();
            assertThat(resumed.session().messages())
                    .containsSubsequence(
                            new UserMessage("still writable"),
                            AssistantMessage.text("still active"));
        }
    }

    @Test
    void rejectsWorkspaceMismatch() throws IOException {
        Path firstWorkspace = workspace("workspace-a");
        Path secondWorkspace = workspace("workspace-b");
        Path storeRoot = storeRoot("workspace-binding");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, firstWorkspace, 1)) {
            sessionId = store.create(SPEC).id();
            store.close(sessionId);
        }

        try (FileSessionStore otherWorkspace = store(storeRoot, secondWorkspace, 100)) {
            assertThatThrownBy(() -> otherWorkspace.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_MISMATCH"));
        }
    }

    @Test
    void enforcesSingleWriterWhileInspectRemainsReadOnly() throws IOException {
        Path workspace = workspace("lease");
        Path storeRoot = storeRoot("lease");
        try (FileSessionStore writer = store(storeRoot, workspace, 1);
             FileSessionStore contender = store(storeRoot, workspace, 100)) {
            SessionId sessionId = writer.create(SPEC).id();

            assertThatThrownBy(() -> contender.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SESSION_ACTIVE"));

            SessionOpenResult inspected = contender.open(
                    new SessionOpenRequest(
                            SessionOpenMode.INSPECT,
                            java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(inspected.readOnly()).isTrue();
            assertThat(inspected.session().isFenced()).isTrue();
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(SessionRecoveryIssueKind.READ_ONLY_INSPECT);
            assertThat(contender.find(sessionId)).isEmpty();
            assertThat(writer.find(sessionId)).isPresent();
        }
    }

    @Test
    void damagedTrailingRecordIsInspectWarningButNotWritable() throws IOException {
        Path workspace = workspace("damaged-tail");
        Path storeRoot = storeRoot("damaged-tail");
        SessionId sessionId = createAndClose(storeRoot, workspace);
        Files.writeString(
                journal(storeRoot, sessionId),
                "{\"schemaMajor\":1",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        try (FileSessionStore store = store(storeRoot, workspace, 100)) {
            SessionOpenResult inspected = store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(
                            SessionRecoveryIssueKind.DAMAGED_TAIL,
                            SessionRecoveryIssueKind.READ_ONLY_INSPECT);
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RECOVERY_REQUIRED"));
        }
    }

    @Test
    void rejectsInteriorCorruption() throws IOException {
        Path workspace = workspace("interior-corruption");
        Path storeRoot = storeRoot("interior-corruption");
        SessionId sessionId = createAndClose(storeRoot, workspace);
        Files.writeString(
                journal(storeRoot, sessionId),
                "not-json\n{}\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        try (FileSessionStore store = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MALFORMED_RECORD"));
        }
    }

    @Test
    void rejectsUnknownMajorAndDuplicateSchemaField() throws IOException {
        Path workspace = workspace("schema");
        Path unknownRoot = storeRoot("unknown-major");
        SessionId unknownId = createAndClose(unknownRoot, workspace);
        Path unknownJournal = journal(unknownRoot, unknownId);
        String unknown = Files.readString(unknownJournal, StandardCharsets.UTF_8)
                .replaceFirst("\\\"schemaMajor\\\":1", "\\\"schemaMajor\\\":2");
        Files.writeString(unknownJournal, unknown, StandardCharsets.UTF_8);

        try (FileSessionStore store = store(unknownRoot, workspace, 100)) {
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(unknownId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("UNSUPPORTED_VERSION"));
        }

        Path duplicateRoot = storeRoot("duplicate-schema");
        SessionId duplicateId = createAndClose(duplicateRoot, workspace);
        Path duplicateJournal = journal(duplicateRoot, duplicateId);
        String duplicate = Files.readString(duplicateJournal, StandardCharsets.UTF_8)
                .replaceFirst(
                        "\\{\\\"schemaMajor\\\":1,",
                        "{\\\"schemaMajor\\\":1,\\\"schemaMajor\\\":1,");
        Files.writeString(duplicateJournal, duplicate, StandardCharsets.UTF_8);

        try (FileSessionStore store = store(duplicateRoot, workspace, 200)) {
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(duplicateId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MALFORMED_RECORD"));
        }
    }

    @Test
    void startedWithoutCompletedIsFencedAndNeverCreatesToolResult() throws IOException {
        Path workspace = workspace("incomplete-tool");
        Path storeRoot = storeRoot("incomplete-tool");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-incomplete");
            ToolCall call = new ToolCall("call-write", "write_file", JsonObject.empty());
            store.runStarted(sessionId, runId, new UserMessage("write"));
            store.assistantAppended(sessionId, runId, AssistantMessage.tools(java.util.List.of(call)));
            store.toolStarted(
                    sessionId,
                    runId,
                    1,
                    call.id(),
                    call.name(),
                    ToolEffect.WRITE_WORKSPACE);
        }

        try (FileSessionStore recovery = store(storeRoot, workspace, 100)) {
            SessionOpenResult inspected = recovery.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(inspected.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .isEmpty();
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(
                            SessionRecoveryIssueKind.UNFINISHED_RUN,
                            SessionRecoveryIssueKind.POTENTIAL_SIDE_EFFECT);
            assertThatThrownBy(() -> recovery.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RECOVERY_REQUIRED"));
        }
    }

    @Test
    void recordEncodingFailureReleasesWriterAndAllowsSameIdRetry() throws IOException {
        Path workspace = workspace("encoding-failure-release");
        Path storeRoot = storeRoot("encoding-failure-release");
        SessionSpec oversized = new SessionSpec("x".repeat(1_048_577), Map.of());
        SessionId sessionId = new SessionId("session-test-1");

        try (FileSessionStore failing = store(storeRoot, workspace, 1)) {
            assertThatThrownBy(() -> failing.create(oversized))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("LIMIT_EXCEEDED"));
            assertThat(failing.find(sessionId)).isEmpty();

            try (FileSessionStore retry = store(storeRoot, workspace, 1)) {
                assertThat(retry.create(SPEC).id()).isEqualTo(sessionId);
            }
        }
    }

    @Test
    void writerCloseReleasesLeaseForNextStore() throws IOException {
        Path workspace = workspace("release");
        Path storeRoot = storeRoot("release");
        SessionId sessionId;
        try (FileSessionStore first = store(storeRoot, workspace, 1)) {
            sessionId = first.create(SPEC).id();
            first.close(sessionId);
        }

        try (FileSessionStore second = store(storeRoot, workspace, 100)) {
            SessionOpenResult resumed = second.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(resumed.session().id()).isEqualTo(sessionId);
            assertThat(resumed.readOnly()).isFalse();
        }
    }

    @Test
    void persistsEscapeHeavyOneMibPromptWithoutTruncation() throws IOException {
        Path workspace = workspace("escape-heavy");
        Path root = storeRoot("escape-heavy");
        String prompt = "".repeat(1_048_576);
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.runStarted(id, new RunId("run-large"), new UserMessage(prompt));
            store.runCompleted(id, new RunId("run-large"), StopReason.COMPLETED);
            store.close(id);
        }
        assertThat(Files.size(journal(root, id))).isGreaterThan(6L * 1_048_576L);
        try (FileSessionStore resumed = store(root, workspace, 10)) {
            SessionOpenResult result = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(id)), SPEC);
            assertThat(((UserMessage) result.session().messages().getLast()).content()).isEqualTo(prompt);
        }
    }

    @Test
    void persistsAndReplaysUserFileAttachmentsAcrossResumeAndFork() throws IOException {
        Path workspace = workspace("attachments");
        Path storeRoot = storeRoot("attachments");
        UserMessage withAttachment = new UserMessage(
                "explain @src/App.java",
                java.util.List.of(new io.github.liumaishenjian.ccjava.domain.UserFileAttachment(
                        "src/App.java",
                        "line one\nline two",
                        "a".repeat(64),
                        3,
                        4,
                        true)));
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-attach");
            store.runStarted(sessionId, runId, withAttachment);
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            SessionOpenResult result = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(result.issues()).isEmpty();
            assertThat(result.session().messages()).containsExactly(withAttachment);
            resumed.close(sessionId);

            SessionOpenResult forked = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(forked.session().messages()).containsExactly(withAttachment);
            resumed.close(forked.session().id());
        }
    }

    @Test
    void replaysLegacyRunStartedRecordsWithoutAttachmentsField() throws IOException {
        Path workspace = workspace("legacy-attachments");
        Path storeRoot = storeRoot("legacy-attachments");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-legacy");
            store.runStarted(sessionId, runId, new UserMessage("legacy prompt"));
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }
        // 模拟本切片之前写出的记录：run.started 完全没有 attachments 字段。
        Path journal = journal(storeRoot, sessionId);
        String stripped = Files.readString(journal, StandardCharsets.UTF_8)
                .replace(",\"attachments\":[]", "");
        assertThat(stripped).doesNotContain("attachments");
        Files.writeString(journal, stripped, StandardCharsets.UTF_8);

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            SessionOpenResult result = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(result.issues()).isEmpty();
            assertThat(result.session().messages())
                    .containsExactly(new UserMessage("legacy prompt"));
            assertThat(((UserMessage) result.session().messages().getFirst()).attachments()).isEmpty();
            resumed.close(sessionId);
        }
    }

    @Test
    void rejectsRunStartedRecordWithOversizedAttachmentList() throws IOException {
        Path workspace = workspace("attachment-limit");
        Path storeRoot = storeRoot("attachment-limit");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-limit");
            store.runStarted(sessionId, runId, new UserMessage("prompt"));
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }
        Path journal = journal(storeRoot, sessionId);
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < 9; index++) {
            if (index > 0) {
                items.append(',');
            }
            items.append("{\"protocolPath\":\"src/F").append(index)
                    .append(".java\",\"textSnapshot\":\"x\",\"sha256Digest\":\"")
                    .append("b".repeat(64))
                    .append("\",\"startLine\":1,\"endLine\":1,\"truncated\":false}");
        }
        Files.writeString(
                journal,
                Files.readString(journal, StandardCharsets.UTF_8)
                        .replace("\"attachments\":[]", "\"attachments\":[" + items + "]"),
                StandardCharsets.UTF_8);

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOf(SessionOpenException.class);
        }
    }

    @Test
    void rejectsUnknownAttachmentSchemaField() throws IOException {
        Path workspace = workspace("attachment-schema");
        Path storeRoot = storeRoot("attachment-schema");
        SessionId sessionId;
        UserMessage message = new UserMessage(
                "prompt",
                java.util.List.of(new io.github.liumaishenjian.ccjava.domain.UserFileAttachment(
                        "src/App.java", "x", "c".repeat(64), 1, 1, false)));
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-schema");
            store.runStarted(sessionId, runId, message);
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }
        Path journal = journal(storeRoot, sessionId);
        Files.writeString(
                journal,
                Files.readString(journal, StandardCharsets.UTF_8)
                        .replace("\"truncated\":false}",
                                "\"truncated\":false,\"unexpected\":true}"),
                StandardCharsets.UTF_8);

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOf(SessionOpenException.class);
        }
    }


    @Test
    void invalidArtifactTransitionIsRejectedBeforeJournalAndProjectionChange() throws IOException {
        Path workspace = workspace("plan-write-invalid-state");
        Path root = storeRoot("plan-write-invalid-state");
        SessionId id;
        PlanArtifact first;
        long journalSize;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            first = PlanArtifact.create("plan-invalid-write", id, "# First", PlanStatus.DRAFT,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(first, 0, "");
            journalSize = Files.size(journal(root, id));
            PlanArtifact invalid = first.nextRevision("# Invalid", PlanStatus.COMPLETED,
                    Instant.parse("2026-08-20T00:00:01Z"));

            long firstRevision = first.revision();
            String firstDigest = first.contentDigest();
            assertThatThrownBy(() -> store.savePlanArtifact(
                    invalid, firstRevision, firstDigest))
                    .isInstanceOfSatisfying(
                            io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException.Code.INVALID_STATE));
            assertThat(Files.size(journal(root, id))).isEqualTo(journalSize);
            assertThat(store.planArtifacts(id).load(id)).contains(first);
            assertThat(Files.exists(root.resolve(id.value())
                    .resolve("plan-r2-" + invalid.contentDigest() + ".md"))).isFalse();
            assertThat(store.planArtifacts(id).load(id)).contains(first);
            PlanArtifact legal = first.nextRevision(
                    "# Legal update", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:02Z"));
            store.savePlanArtifact(legal, first.revision(), first.contentDigest());
            first = legal;
        }
        PlanArtifact expected = first;
        try (FileSessionStore reopened = store(root, workspace, 20)) {
            assertThat(reopened.open(SessionOpenRequest.resume(id), SPEC).session().isFenced()).isFalse();
            assertThat(reopened.planArtifacts(id).load(id)).contains(expected);
        }
    }

    @Test
    void terminalPlanIdentityRotationSurvivesResumeAndRepairsOlderProjection() throws IOException {
        Path workspace = workspace("plan-terminal-rotation");
        Path root = storeRoot("plan-terminal-rotation");
        SessionId id;
        PlanArtifact rejected;
        PlanArtifact fresh;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            PlanArtifact draft = store.savePlanArtifact(PlanArtifact.create(
                    "plan-old", id, "# Old", PlanStatus.DRAFT,
                    Instant.parse("2026-08-20T00:00:00Z")), 0, "");
            PlanArtifact awaiting = store.savePlanArtifact(draft.nextRevision(
                    "# Old", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:01Z")), draft.revision(), draft.contentDigest());
            rejected = store.savePlanArtifact(awaiting.nextRevision(
                    "# Old", PlanStatus.REJECTED,
                    Instant.parse("2026-08-20T00:00:02Z")), awaiting.revision(), awaiting.contentDigest());
            PlanDocument rejectedDocument = new PlanDocument(rejected.planId(), "old", List.of(
                    new PlanStep(1, "inspect", "inspect", "workspace-digest")),
                    PlanStatus.REJECTED, "workspace-digest");
            store.planSnapshot(id, rejectedDocument, new PlanExecutionState(
                    rejected.planId(), PlanApprovalGate.REJECTED, null, null,
                    PlanStatus.REJECTED, "workspace-digest"));
            fresh = PlanArtifact.create("plan-new", id, "# Fresh", PlanStatus.DRAFT,
                    Instant.parse("2026-08-20T00:00:03Z"));
            store.replaceTerminalPlanArtifact(
                    fresh, rejected.planId(), rejected.revision(), rejected.contentDigest());
            store.close(id);
        }

        try (FileSessionStore resumed = store(root, workspace, 20)) {
            SessionOpenResult opened = resumed.open(SessionOpenRequest.resume(id), SPEC);
            assertThat(opened.session().plan()).isEmpty();
            assertThat(resumed.planArtifacts(id).load(id)).contains(fresh);
            resumed.close(id);
        }

        Path directory = root.resolve(id.value());
        Files.delete(directory.resolve(FilePlanArtifactStore.MANIFEST_FILE));
        new FilePlanArtifactStore(directory, id).restoreAuthoritative(rejected);
        assertThat(new FilePlanArtifactStore(directory, id).load(id)).contains(rejected);

        try (FileSessionStore recovered = store(root, workspace, 40)) {
            SessionOpenResult opened = recovered.open(SessionOpenRequest.resume(id), SPEC);
            assertThat(opened.session().plan()).isEmpty();
            assertThat(recovered.planArtifacts(id).load(id)).contains(fresh);
        }
    }

    @Test
    void journalOneRevisionAheadOfOlderManifestFastForwardsProjection() throws IOException {
        Path workspace = workspace("plan-journal-fast-forward");
        Path root = storeRoot("plan-journal-fast-forward");
        SessionId id;
        PlanArtifact first;
        PlanArtifact second;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            first = PlanArtifact.create("plan-fast-forward", id, "# First", PlanStatus.DRAFT,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(first, 0, "");
            second = first.nextRevision("# Second", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:01Z"));
            store.savePlanArtifact(second, first.revision(), first.contentDigest());
            store.close(id);
        }
        Path directory = root.resolve(id.value());
        Files.delete(directory.resolve(FilePlanArtifactStore.MANIFEST_FILE));
        new FilePlanArtifactStore(directory, id).restoreAuthoritative(first);
        assertThat(new FilePlanArtifactStore(directory, id).load(id)).contains(first);

        try (FileSessionStore reopened = store(root, workspace, 10)) {
            reopened.open(SessionOpenRequest.resume(id), SPEC);
            assertThat(reopened.planArtifacts(id).load(id)).contains(second);
        }
    }

    @Test
    void planArtifactResumesRecoversMissingFileAndForksIndependentIdentity() throws IOException {
        Path workspace = workspace("plan-artifact-recovery");
        Path root = storeRoot("plan-artifact-recovery");
        SessionId sourceId;
        io.github.liumaishenjian.ccjava.domain.PlanArtifact sourceArtifact;
        try (FileSessionStore store = store(root, workspace, 1)) {
            sourceId = store.create(SPEC).id();
            sourceArtifact = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                    "plan-source", sourceId, "# Plan\n\nDurable", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(sourceArtifact, 0, "");
            store.close(sourceId);
        }

        Path sourceDirectory = root.resolve(sourceId.value());
        try (var files = Files.list(sourceDirectory)) {
            files.filter(path -> path.getFileName().toString().startsWith("plan-"))
                    .forEach(path -> {
                        try { Files.delete(path); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    });
        }
        Files.delete(sourceDirectory.resolve(FilePlanArtifactStore.MANIFEST_FILE));
        SessionId forkId;
        try (FileSessionStore reopened = store(root, workspace, 100)) {
            SessionOpenResult resumed = reopened.open(SessionOpenRequest.resume(sourceId), SPEC);
            assertThat(reopened.planArtifacts(sourceId).load(sourceId)).contains(sourceArtifact);
            resumed.session().plan().ifPresent(ignored ->
                    org.junit.jupiter.api.Assertions.fail("legacy PlanDocument 不应由 PlanArtifact 伪造"));
            reopened.close(sourceId);

            SessionOpenResult forked = reopened.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC);
            forkId = forked.session().id();
            var forkArtifact = reopened.planArtifacts(forkId).load(forkId).orElseThrow();
            assertThat(forkArtifact.sessionId()).isEqualTo(forkId);
            assertThat(forkArtifact.planId()).isNotEqualTo(sourceArtifact.planId());
            assertThat(forkArtifact.revision()).isEqualTo(1);
            assertThat(forkArtifact.markdownContent()).isEqualTo(sourceArtifact.markdownContent());
            reopened.close(forkId);
        }

        var sourceBefore = reopenedArtifact(sourceDirectory, sourceId);
        Path forkDirectory = root.resolve(forkId.value());
        try (FileSessionStore forkWriter = store(root, workspace, 200)) {
            forkWriter.open(SessionOpenRequest.resume(forkId), SPEC);
            var firstFork = forkWriter.planArtifacts(forkId).load(forkId).orElseThrow();
            var changed = firstFork.nextRevision("# Fork only", PlanStatus.APPROVED,
                    firstFork.updatedAt().plusSeconds(1));
            forkWriter.savePlanArtifact(changed, firstFork.revision(), firstFork.contentDigest());
        }
        assertThat(reopenedArtifact(sourceDirectory, sourceId)).isEqualTo(sourceBefore);
        assertThat(reopenedArtifact(forkDirectory, forkId).markdownContent()).isEqualTo("# Fork only");
    }

    @Test
    void journalCommittedWithMissingProjectionRebuildsManifestWithoutReplayingPlan() throws IOException {
        Path workspace = workspace("plan-journal-ahead");
        Path root = storeRoot("plan-journal-ahead");
        SessionId id;
        io.github.liumaishenjian.ccjava.domain.PlanArtifact artifact;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            artifact = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                    "plan-journal-ahead", id, "# Durable journal", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(artifact, 0, "");
            store.close(id);
        }
        Files.delete(root.resolve(id.value()).resolve(FilePlanArtifactStore.MANIFEST_FILE));

        try (FileSessionStore reopened = store(root, workspace, 2)) {
            SessionOpenResult result = reopened.open(SessionOpenRequest.resume(id), SPEC);
            assertThat(reopened.planArtifacts(id).load(id)).contains(artifact);
            assertThat(result.session().hasActiveRun()).isFalse();
            assertThat(result.session().plan()).isEmpty();
        }
    }

    @Test
    void localManifestAheadOfJournalIsDiscardedInsteadOfBlockingResume() throws IOException {
        Path workspace = workspace("plan-local-ahead");
        Path root = storeRoot("plan-local-ahead");
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.close(id);
        }
        Path directory = root.resolve(id.value());
        var orphan = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                "plan-local-ahead", id, "# Not journaled", PlanStatus.AWAITING_APPROVAL,
                Instant.parse("2026-08-20T00:00:00Z"));
        new FilePlanArtifactStore(directory, id).save(orphan, 0, "");

        try (FileSessionStore reopened = store(root, workspace, 2)) {
            SessionOpenResult result = reopened.open(SessionOpenRequest.resume(id), SPEC);
            assertThat(result.issues()).isEmpty();
            assertThat(reopened.planArtifacts(id).load(id)).isEmpty();
            assertThat(Files.exists(directory.resolve(FilePlanArtifactStore.MANIFEST_FILE))).isFalse();
        }
    }

    @Test
    void forkOfCompletedPlanResetsArtifactAndProjectionToPendingApproval() throws IOException {
        Path workspace = workspace("plan-fork-terminal");
        Path root = storeRoot("plan-fork-terminal");
        SessionId sourceId;
        PlanDocument completedDocument = new PlanDocument("plan-terminal", "finished", List.of(
                new PlanStep(1, "done", "done", "digest-a")), PlanStatus.COMPLETED, "digest-a");
        PlanExecutionState completedState = new PlanExecutionState(
                completedDocument.id(), PlanApprovalGate.APPROVED,
                null, null, PlanStatus.COMPLETED, "digest-a");
        try (FileSessionStore store = store(root, workspace, 1)) {
            sourceId = store.create(SPEC).id();
            var artifact = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                    completedDocument.id(), sourceId, "# Completed", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:00Z"))
                    .nextRevision("# Completed", PlanStatus.APPROVED,
                            Instant.parse("2026-08-20T00:00:01Z"))
                    .nextRevision("# Completed", PlanStatus.EXECUTING,
                            Instant.parse("2026-08-20T00:00:02Z"))
                    .nextRevision("# Completed", PlanStatus.COMPLETED,
                            Instant.parse("2026-08-20T00:00:03Z"));
            store.savePlanArtifact(artifact.fork(completedDocument.id(), sourceId,
                    Instant.parse("2026-08-20T00:00:00Z")), 0, "");
            var first = store.planArtifacts(sourceId).load(sourceId).orElseThrow();
            var approved = first.nextRevision("# Completed", PlanStatus.APPROVED,
                    Instant.parse("2026-08-20T00:00:01Z"));
            store.savePlanArtifact(approved, first.revision(), first.contentDigest());
            var executing = approved.nextRevision("# Completed", PlanStatus.EXECUTING,
                    Instant.parse("2026-08-20T00:00:02Z"));
            store.savePlanArtifact(executing, approved.revision(), approved.contentDigest());
            var completed = executing.nextRevision("# Completed", PlanStatus.COMPLETED,
                    Instant.parse("2026-08-20T00:00:03Z"));
            store.savePlanArtifact(completed, executing.revision(), executing.contentDigest());
            store.planSnapshot(sourceId, completedDocument, completedState);
            store.close(sourceId);
        }

        try (FileSessionStore reopened = store(root, workspace, 10)) {
            SessionOpenResult forked = reopened.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC);
            var artifact = reopened.planArtifacts(forked.session().id())
                    .load(forked.session().id()).orElseThrow();
            assertThat(artifact.revision()).isEqualTo(1);
            assertThat(artifact.status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
            assertThat(artifact.sessionId()).isEqualTo(forked.session().id());
            assertThat(forked.session().plan()).isPresent();
            assertThat(forked.session().plan().orElseThrow().document().id()).isEqualTo(artifact.planId());
            assertThat(forked.session().plan().orElseThrow().state().status())
                    .isEqualTo(PlanStatus.AWAITING_APPROVAL);
            assertThat(forked.session().plan().orElseThrow().state().approvalGate())
                    .isEqualTo(PlanApprovalGate.PENDING);
        }
    }

    @Test
    void journalRejectsNonMonotonicPlanRevisionAndStatusChain() throws IOException {
        Path workspace = workspace("plan-journal-invalid-chain");
        Path root = storeRoot("plan-journal-invalid-chain");
        SessionId id;
        io.github.liumaishenjian.ccjava.domain.PlanArtifact first;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            first = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                    "plan-invalid-chain", id, "# First", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(first, 0, "");
            store.close(id);
        }
        Path journal = journal(root, id);
        String invalid = Files.readString(journal, StandardCharsets.UTF_8)
                .replaceFirst("\"revision\":1", "\"revision\":2");
        Files.writeString(journal, invalid, StandardCharsets.UTF_8);

        try (FileSessionStore reopened = store(root, workspace, 2)) {
            assertThatThrownBy(() -> reopened.open(SessionOpenRequest.resume(id), SPEC))
                    .isInstanceOfSatisfying(SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("INVALID_RECORD"));
        }
    }

    @Test
    void journalRejectsPlanArtifactOwnedByAnotherSession() throws IOException {
        Path workspace = workspace("plan-journal-foreign");
        Path root = storeRoot("plan-journal-foreign");
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            var artifact = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                    "plan-foreign-owner", id, "# Plan", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(artifact, 0, "");
            store.close(id);
        }
        Path journal = journal(root, id);
        String invalid = Files.readString(journal, StandardCharsets.UTF_8)
                .replace("\"sessionId\":\"" + id.value() + "\",\"revision\"",
                        "\"sessionId\":\"session-foreign-owner\",\"revision\"");
        Files.writeString(journal, invalid, StandardCharsets.UTF_8);

        try (FileSessionStore reopened = store(root, workspace, 2)) {
            assertThatThrownBy(() -> reopened.open(SessionOpenRequest.resume(id), SPEC))
                    .isInstanceOfSatisfying(SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("INVALID_RECORD"));
        }
    }

    @Test
    void corruptLocalPlanArtifactFailsClosedEvenWhenJournalCanRecover() throws IOException {
        Path workspace = workspace("plan-artifact-corrupt");
        Path root = storeRoot("plan-artifact-corrupt");
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            var artifact = io.github.liumaishenjian.ccjava.domain.PlanArtifact.create(
                    "plan-corrupt", id, "# Valid", PlanStatus.DRAFT,
                    Instant.parse("2026-08-20T00:00:00Z"));
            store.savePlanArtifact(artifact, 0, "");
            store.close(id);
        }
        Path directory = root.resolve(id.value());
        Path generation;
        try (var files = Files.list(directory)) {
            generation = files.filter(path -> path.getFileName().toString().startsWith("plan-r"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(generation, "tampered", StandardCharsets.UTF_8);
        try (FileSessionStore reopened = store(root, workspace, 10)) {
            assertThatThrownBy(() -> reopened.open(SessionOpenRequest.resume(id), SPEC))
                    .isInstanceOfSatisfying(SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("PLAN_ARTIFACT_CORRUPT"));
        }
    }

    @Test
    void failedForkRollsBackOnlyFreshTargetAndReleasesWriterEntry() throws IOException {
        Path workspace = workspace("fork-failure-cleanup");
        Path root = storeRoot("fork-failure-cleanup");
        SessionId sourceId;
        PlanArtifact sourceArtifact;
        try (FileSessionStore sourceStore = store(root, workspace, 1)) {
            sourceId = sourceStore.create(SPEC).id();
            sourceArtifact = PlanArtifact.create("plan-fork-source", sourceId, "# Source",
                    PlanStatus.AWAITING_APPROVAL, Instant.parse("2026-08-20T00:00:00Z"));
            sourceStore.savePlanArtifact(sourceArtifact, 0, "");
            sourceStore.close(sourceId);
        }
        Path sourceJournal = journal(root, sourceId);
        byte[] sourceBefore = Files.readAllBytes(sourceJournal);
        SessionId targetId = new SessionId("session-test-50");
        AtomicInteger failures = new AtomicInteger();
        try (FileSessionStore failing = new FileSessionStore(
                root, workspace, new TestIds(50), lifecycle(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(),
                ignored -> {
                    if (failures.getAndIncrement() == 0) throw new InjectedForkFailure();
                })) {
            assertThatThrownBy(() -> failing.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC))
                    .isInstanceOf(InjectedForkFailure.class);
            assertThat(Files.exists(root.resolve(targetId.value()))).isFalse();
            assertThat(Files.readAllBytes(sourceJournal)).isEqualTo(sourceBefore);

            SessionOpenResult retry = failing.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC);
            assertThat(retry.session().id()).isEqualTo(new SessionId("session-test-51"));
            assertThat(failing.planArtifacts(retry.session().id()).load(retry.session().id()))
                    .get().extracting(PlanArtifact::markdownContent)
                    .isEqualTo(sourceArtifact.markdownContent());
        }
    }

    @Test
    void forkFailureAfterArtifactRestoreRemovesManifestAndGenerationsWithoutTouchingSource() throws IOException {
        Path workspace = workspace("fork-artifact-failure-cleanup");
        Path root = storeRoot("fork-artifact-failure-cleanup");
        SessionId sourceId;
        try (FileSessionStore sourceStore = store(root, workspace, 1)) {
            sourceId = sourceStore.create(SPEC).id();
            sourceStore.savePlanArtifact(PlanArtifact.create(
                    "plan-fork-artifact-source", sourceId, "# Source", PlanStatus.AWAITING_APPROVAL,
                    Instant.parse("2026-08-20T00:00:00Z")), 0, "");
            sourceStore.close(sourceId);
        }
        byte[] sourceBefore = Files.readAllBytes(journal(root, sourceId));
        SessionId targetId = new SessionId("session-test-60");
        FileSessionStore.NewSessionFault fault = new FileSessionStore.NewSessionFault() {
            @Override public void afterJournalWritten(SessionId ignored) { }
            @Override public void afterArtifactRestored(SessionId ignored) {
                throw new InjectedForkFailure();
            }
        };
        try (FileSessionStore failing = new FileSessionStore(
                root, workspace, new TestIds(60), lifecycle(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(), fault)) {
            assertThatThrownBy(() -> failing.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC))
                    .isInstanceOf(InjectedForkFailure.class);
            assertThat(Files.exists(root.resolve(targetId.value()))).isFalse();
            assertThat(Files.readAllBytes(journal(root, sourceId))).isEqualTo(sourceBefore);
        }
    }

    @Test
    void forkFailureAfterSessionStartPhaseRollsBackTargetAndReleasesWriterEntry() throws IOException {
        Path workspace = workspace("fork-start-failure-cleanup");
        Path root = storeRoot("fork-start-failure-cleanup");
        SessionId sourceId = createAndClose(root, workspace);
        byte[] sourceBefore = Files.readAllBytes(journal(root, sourceId));
        AtomicInteger failures = new AtomicInteger();
        FileSessionStore.NewSessionFault fault = new FileSessionStore.NewSessionFault() {
            @Override public void afterJournalWritten(SessionId ignored) { }
            @Override public void afterSessionStarted(SessionId ignored) {
                if (failures.getAndIncrement() == 0) throw new InjectedForkFailure();
            }
        };
        try (FileSessionStore failing = new FileSessionStore(
                root, workspace, new TestIds(80), lifecycle(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(), fault)) {
            assertThatThrownBy(() -> failing.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC))
                    .isInstanceOf(InjectedForkFailure.class);
            assertThat(Files.exists(root.resolve("session-test-80"))).isFalse();
            assertThat(Files.readAllBytes(journal(root, sourceId))).isEqualTo(sourceBefore);
            assertThat(failing.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(sourceId)), SPEC)
                    .session().id()).isEqualTo(new SessionId("session-test-81"));
        }
    }

    @Test
    void failedCreateRollsBackJournalAndReleasesWriterEntry() throws IOException {
        Path workspace = workspace("create-failure-cleanup");
        Path root = storeRoot("create-failure-cleanup");
        AtomicInteger failures = new AtomicInteger();
        try (FileSessionStore failing = new FileSessionStore(
                root, workspace, new TestIds(70), lifecycle(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(),
                ignored -> {
                    if (failures.getAndIncrement() == 0) throw new InjectedForkFailure();
                })) {
            assertThatThrownBy(() -> failing.create(SPEC)).isInstanceOf(InjectedForkFailure.class);
            assertThat(Files.exists(root.resolve("session-test-70"))).isFalse();
            assertThat(failing.create(SPEC).id()).isEqualTo(new SessionId("session-test-71"));
        }
    }

    private io.github.liumaishenjian.ccjava.domain.PlanArtifact reopenedArtifact(
            Path directory, SessionId id) {
        return new FilePlanArtifactStore(directory, id).load(id).orElseThrow();
    }

    private SessionId createAndClose(Path storeRoot, Path workspace) {
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            SessionId id = store.create(SPEC).id();
            store.close(id);
            return id;
        }
    }

    private Path workspace(String name) throws IOException {
        Path path = temporaryRoot.resolve("workspaces").resolve(name);
        Files.createDirectories(path);
        return path;
    }

    private Path storeRoot(String name) {
        return temporaryRoot.resolve("stores").resolve(name);
    }

    private Path journal(Path storeRoot, SessionId sessionId) {
        return storeRoot.resolve(sessionId.value()).resolve("session.jsonl");
    }

    private FileSessionStore store(Path root, Path workspace, int firstId) {
        return new FileSessionStore(
                root,
                workspace,
                new TestIds(firstId),
                new LifecycleDispatcher(
                        Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                        AgentEventSink.noop()),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC));
    }

    private LifecycleDispatcher lifecycle() {
        return new LifecycleDispatcher(
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                AgentEventSink.noop());
    }

    private static final class InjectedForkFailure extends RuntimeException {
    }

    private static final class TestIds implements AgentIdGenerator {
        private final AtomicInteger sessionIds;
        private final AtomicInteger runIds;

        private TestIds(int firstId) {
            sessionIds = new AtomicInteger(firstId);
            runIds = new AtomicInteger(firstId);
        }

        @Override
        public SessionId newSessionId() {
            return new SessionId("session-test-" + sessionIds.getAndIncrement());
        }

        @Override
        public RunId newRunId() {
            return new RunId("run-test-" + runIds.getAndIncrement());
        }
    }
}
