package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.*;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.core.task.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 ADR-088 Batch C 与 S06 canonical Session journal 的组合。 */
class FileSessionTaskBoardTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC);
    private static final SessionSpec SPEC = new SessionSpec("test", Map.of("model", "fake"));
    @TempDir Path root;

    @Test
    void resumeRebuildsBoardAndMarksTerminatedClaimsRecoveryRequiredUntilExplicitResume() throws Exception {
        Path workspace = workspace("resume"); Path sessions = root.resolve("sessions-resume");
        SessionId id; TaskMutation.Create create = create("create-1", "work");
        try (FileSessionStore store = store(sessions, workspace, 1)) {
            id = store.create(SPEC).id();
            TaskListService board = store.taskBoard(id).orElseThrow();
            TaskBoardCapability run1 = TaskBoardCapabilityFactory.root(board.snapshot().boardId(), id, new RunId("run-1"));
            TaskMutationResult created = board.execute(run1, create);
            board.execute(run1, new TaskMutation.Claim(new TaskCallId("claim-1"), new TaskId(1),
                    created.task().orElseThrow().revision()));
            store.runStarted(id, new RunId("run-1"), new UserMessage("work"));
            store.runCompleted(id, new RunId("run-1"), StopReason.COMPLETED);
            store.close(id);
        }
        try (FileSessionStore reopened = store(sessions, workspace, 100)) {
            reopened.open(new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(id)), SPEC);
            TaskListService board = reopened.taskBoard(id).orElseThrow();
            TaskItemView interrupted = board.snapshot().task(new TaskId(1)).orElseThrow();
            assertThat(interrupted.status()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(interrupted.recoveryRequired()).isTrue();
            assertThat(board.execute(TaskBoardCapabilityFactory.root(board.snapshot().boardId(), id, new RunId("run-2")),
                    new TaskMutation.ResumeClaim(new TaskCallId("resume-1"), new TaskId(1), interrupted.revision(),
                            interrupted.claim().orElseThrow().epoch())).succeeded()).isTrue();
            assertThat(board.snapshot().task(new TaskId(1)).orElseThrow().recoveryRequired()).isFalse();
        }
        assertThat(Files.readString(sessions.resolve(id.value()).resolve("session.jsonl"), StandardCharsets.UTF_8))
                .contains("task.mutation.succeeded", "RESUME_CLAIM");
    }

    @Test
    void forkCreatesNewLineagePreservesCompletedAndResetsInProgressWithoutIdempotency() throws Exception {
        Path workspace = workspace("fork"); Path sessions = root.resolve("sessions-fork");
        SessionId source; SessionId fork;
        try (FileSessionStore store = store(sessions, workspace, 1)) {
            source = store.create(SPEC).id();
            TaskListService board = store.taskBoard(source).orElseThrow();
            TaskBoardCapability cap = TaskBoardCapabilityFactory.root(board.snapshot().boardId(), source, new RunId("run-source"));
            TaskMutationResult one = board.execute(cap, create("create-1", "done"));
            board.execute(cap, new TaskMutation.Transition(new TaskCallId("complete-1"), new TaskId(1),
                    one.task().orElseThrow().revision(), TaskStatus.COMPLETED, java.util.OptionalLong.empty()));
            TaskMutationResult two = board.execute(cap, create("create-2", "active"));
            board.execute(cap, new TaskMutation.Claim(new TaskCallId("claim-2"), new TaskId(2),
                    two.task().orElseThrow().revision()));
            store.runStarted(source, new RunId("run-source"), new UserMessage("source"));
            store.runCompleted(source, new RunId("run-source"), StopReason.COMPLETED);
            store.close(source);
            SessionOpenResult forked = store.open(new SessionOpenRequest(SessionOpenMode.FORK, Optional.of(source)), SPEC);
            fork = forked.session().id();
            TaskListService target = store.taskBoard(fork).orElseThrow();
            assertThat(target.snapshot().boardId()).isNotEqualTo(board.snapshot().boardId());
            assertThat(target.snapshot().task(new TaskId(1)).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
            TaskItemView reset = target.snapshot().task(new TaskId(2)).orElseThrow();
            assertThat(reset.status()).isEqualTo(TaskStatus.PENDING);
            assertThat(reset.owner()).isEmpty(); assertThat(reset.claim()).isEmpty(); assertThat(reset.recoveryRequired()).isFalse();
            store.close(fork);
        }
        String forkJournal = Files.readString(sessions.resolve(fork.value()).resolve("session.jsonl"), StandardCharsets.UTF_8);
        assertThat(forkJournal).contains("task.board.forked").doesNotContain("\"actorRunId\":\"run-source\"");
    }

    @Test
    void writerConflictDoesNotExposeDurableTaskBoardAndCompletePrefixRemainsReplayable() throws Exception {
        Path workspace = workspace("writer"); Path sessions = root.resolve("sessions-writer");
        try (FileSessionStore owner = store(sessions, workspace, 1); FileSessionStore contender = store(sessions, workspace, 100)) {
            SessionId id = owner.create(SPEC).id();
            TaskListService board = owner.taskBoard(id).orElseThrow();
            board.execute(TaskBoardCapabilityFactory.root(board.snapshot().boardId(), id, new RunId("run-1")),
                    create("call-1", "durable"));
            assertThatThrownBy(() -> contender.open(new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(id)), SPEC))
                    .isInstanceOfSatisfying(SessionOpenException.class, failure -> assertThat(failure.code()).isEqualTo("SESSION_ACTIVE"));
            assertThat(contender.taskBoard(id)).isEmpty();
            Path journal = sessions.resolve(id.value()).resolve("session.jsonl");
            List<String> complete = Files.readAllLines(journal, StandardCharsets.UTF_8);
            Files.writeString(journal, "{\"schemaMajor\":1", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            JsonlSessionCodec codec = new JsonlSessionCodec();
            assertThat(codec.replayTaskBoard(complete).events()).hasSize(1);
        }
    }

    @Test
    void mutationJournalStoresOnlyChangedTaskAndRemainsBoundedAtLiveTaskLimit() throws Exception {
        Path workspace = workspace("bounded");
        Path sessions = root.resolve("sessions-bounded");
        SessionId id;
        try (FileSessionStore store = store(sessions, workspace, 1)) {
            id = store.create(SPEC).id();
            TaskListService board = store.taskBoard(id).orElseThrow();
            TaskBoardCapability capability = TaskBoardCapabilityFactory.root(
                    board.snapshot().boardId(), id, new RunId("run-bounded"));
            String description = "x".repeat(4_096);
            for (int index = 1; index <= 256; index++) {
                assertThat(board.execute(capability, new TaskMutation.Create(
                        new TaskCallId("create-" + index), "task " + index, description,
                        Optional.empty(), TaskMetadata.EMPTY, List.of())).succeeded()).isTrue();
            }
            store.close(id);
        }
        Path journal = sessions.resolve(id.value()).resolve("session.jsonl");
        assertThat(Files.size(journal)).isLessThan(4L * 1_048_576L);
        String text = Files.readString(journal, StandardCharsets.UTF_8);
        assertThat(text).contains("\"changedTask\"").doesNotContain("\"snapshot\":{");
        try (FileSessionStore reopened = store(sessions, workspace, 500)) {
            reopened.open(new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(id)), SPEC);
            assertThat(reopened.taskBoard(id).orElseThrow().snapshot().tasks()).hasSize(256);
        }
    }

    private TaskMutation.Create create(String callId, String subject) {
        return new TaskMutation.Create(new TaskCallId(callId), subject, "", Optional.empty(), TaskMetadata.EMPTY, List.of());
    }
    private Path workspace(String name) throws Exception { Path value = root.resolve("workspace-" + name); Files.createDirectories(value); return value; }
    private FileSessionStore store(Path sessions, Path workspace, int first) {
        return new FileSessionStore(sessions, workspace, new TestIds(first),
                new LifecycleDispatcher(CLOCK, AgentEventSink.noop()), CLOCK);
    }
    private static final class TestIds implements AgentIdGenerator {
        private final AtomicInteger sessions; private final AtomicInteger runs;
        TestIds(int first) { sessions = new AtomicInteger(first); runs = new AtomicInteger(first); }
        @Override public SessionId newSessionId() { return new SessionId("session-task-" + sessions.getAndIncrement()); }
        @Override public RunId newRunId() { return new RunId("run-task-" + runs.getAndIncrement()); }
    }
}
