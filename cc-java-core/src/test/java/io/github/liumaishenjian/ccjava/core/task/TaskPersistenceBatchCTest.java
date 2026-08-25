package io.github.liumaishenjian.ccjava.core.task;

import static org.assertj.core.api.Assertions.*;

import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 Batch C durable-before-visible、恢复幂等与可信 capability。 */
class TaskPersistenceBatchCTest {
    private static final SessionId OWNER = new SessionId("session-root");
    private static final TaskBoardId BOARD = new TaskBoardId("board-root");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistenceFailureRollsBackCandidateBeforeReadersCanObserveIt() {
        AtomicInteger appends = new AtomicInteger();
        TaskListService service = new TaskListService(BOARD, OWNER, CLOCK, TaskRunState.noneTerminated(),
                (ignored, event) -> { appends.incrementAndGet(); throw new IllegalStateException("disk"); }, List.of());
        TaskBoardCapability root = TaskBoardCapabilityFactory.root(BOARD, OWNER, new RunId("run-1"));
        TaskMutation.Create create = new TaskMutation.Create(new TaskCallId("call-1"), "subject", "",
                Optional.empty(), TaskMetadata.EMPTY, List.of());

        assertThatThrownBy(() -> service.execute(root, create)).isInstanceOf(IllegalStateException.class);
        assertThat(appends).hasValue(1);
        assertThat(service.snapshot().revision()).isZero();
        assertThat(service.snapshot().tasks()).isEmpty();
    }

    @Test
    void wrongBoardOwnerAndActorSessionCannotMutateCanonicalBoard() {
        TaskListService service = new TaskListService(BOARD, OWNER, CLOCK, TaskRunState.noneTerminated());
        TaskMutation.Create command = new TaskMutation.Create(new TaskCallId("call-identity"), "subject", "",
                Optional.empty(), TaskMetadata.EMPTY, List.of());

        TaskBoardCapability wrongBoard = TaskBoardCapabilityFactory.root(
                new TaskBoardId("board-other"), OWNER, new RunId("run-1"));
        TaskBoardCapability wrongOwner = TaskBoardCapabilityFactory.root(
                BOARD, new SessionId("session-other"), new RunId("run-1"));
        assertThat(service.execute(wrongBoard, command).succeeded()).isFalse();
        assertThat(service.execute(wrongOwner, command).succeeded()).isFalse();
        assertThat(service.snapshot().revision()).isZero();
        assertThatThrownBy(() -> new TaskBoardCapability(
                BOARD, OWNER, new TaskActorId("root:session-root"), new SessionId("session-child"),
                new RunId("run-1"), true,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replayRebuildsBoundedSuccessfulIdempotencyAndRejectsWrongInvocationDomain() {
        List<TaskMutationEvent> journal = new ArrayList<>();
        TaskListService first = new TaskListService(BOARD, OWNER, CLOCK, TaskRunState.noneTerminated(),
                (ignored, event) -> journal.add(event), List.of());
        TaskBoardCapability root = TaskBoardCapabilityFactory.root(BOARD, OWNER, new RunId("run-1"));
        TaskMutation.Create command = new TaskMutation.Create(new TaskCallId("call-1"), "subject", "",
                Optional.empty(), TaskMetadata.EMPTY, List.of());
        TaskMutationResult created = first.execute(root, command);

        TaskListService replayed = new TaskListService(BOARD, OWNER, CLOCK, TaskRunState.noneTerminated(),
                TaskMutationJournal.volatileOnly(), journal);
        assertThat(replayed.execute(root, command)).isEqualTo(created);
        assertThat(replayed.snapshot()).isEqualTo(created.snapshot());

        TaskBoardCapability wrongRun = TaskBoardCapabilityFactory.root(BOARD, OWNER, new RunId("run-2"));
        TaskMutationResult secondDomain = replayed.execute(wrongRun, command);
        assertThat(secondDomain.succeeded()).isTrue();
        assertThat(secondDomain.snapshot().revision()).isEqualTo(2);

        TaskBoardCapability child = TaskBoardCapabilityFactory.child(BOARD, OWNER, new TaskActorId("child-a"),
                new SessionId("session-child"), new RunId("run-child"), Set.of(new TaskId(1)));
        assertThat(child.ownerSessionId()).isEqualTo(OWNER);
        assertThat(child.actorSessionId()).isNotEqualTo(OWNER);
        assertThat(child.allows(new TaskId(1))).isTrue();
        assertThat(child.allows(new TaskId(2))).isFalse();
    }
}
