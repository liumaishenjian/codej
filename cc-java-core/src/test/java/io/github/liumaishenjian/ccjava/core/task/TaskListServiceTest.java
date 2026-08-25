package io.github.liumaishenjian.ccjava.core.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-088 TaskListService 的 DAG、CAS、claim、recovery、capability 与幂等回归。 */
class TaskListServiceTest {
    private static final SessionId OWNER_SESSION = new SessionId("session-task-root");
    private static final TaskBoardId BOARD_ID = new TaskBoardId("board-session-task-root");
    private static final TaskActorId ROOT_ACTOR = new TaskActorId("root:session-task-root");
    private final AtomicInteger calls = new AtomicInteger();
    private final Set<RunId> terminatedRuns = Collections.synchronizedSet(new HashSet<>());
    private TaskListService service;
    private TaskBoardCapability root;

    @BeforeEach
    void setUp() {
        service = new TaskListService(BOARD_ID, OWNER_SESSION,
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC), terminatedRuns::contains);
        root = new TaskBoardCapability(BOARD_ID, OWNER_SESSION, ROOT_ACTOR, OWNER_SESSION,
                new RunId("run-root"), true,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
    }

    @Test
    void rejectsMissingDuplicateSelfAndDeepCycleDependenciesWithoutRevisionChange() {
        TaskMutationResult missing = execute(new TaskMutation.Create(call(), "missing", "", Optional.empty(),
                TaskMetadata.EMPTY, List.of(new TaskId(999))));
        assertCode(missing, TaskDiagnosticCode.TASK_NOT_FOUND);
        assertThat(missing.snapshot().revision()).isZero();

        TaskId a = create("a");
        TaskMutationResult duplicate = execute(new TaskMutation.Create(call(), "duplicate", "", Optional.empty(),
                TaskMetadata.EMPTY, List.of(a, a)));
        assertCode(duplicate, TaskDiagnosticCode.TASK_DEPENDENCY_INVALID);

        TaskMutationResult self = dependency(a, List.of(a), List.of());
        assertCode(self, TaskDiagnosticCode.TASK_DEPENDENCY_INVALID);
        TaskId b = create("b");
        TaskId c = create("c");
        assertThat(dependency(a, List.of(b), List.of()).succeeded()).isTrue();
        assertThat(dependency(b, List.of(c), List.of()).succeeded()).isTrue();
        long before = service.snapshot().revision();
        TaskMutationResult cycle = dependency(c, List.of(a), List.of());
        assertCode(cycle, TaskDiagnosticCode.TASK_DEPENDENCY_CYCLE);
        assertThat(cycle.snapshot().revision()).isEqualTo(before);
    }

    @Test
    void blockedTaskCannotClaimOrCompleteAndDeleteWithInboundEdgeIsRejected() {
        TaskId blocker = create("blocker");
        TaskId blocked = create("blocked", List.of(blocker));
        TaskMutationResult claim = execute(new TaskMutation.Claim(call(), blocked, revision(blocked)));
        assertCode(claim, TaskDiagnosticCode.TASK_BLOCKED);
        TaskMutationResult complete = execute(new TaskMutation.Transition(call(), blocked, revision(blocked),
                TaskStatus.COMPLETED, OptionalLong.empty()));
        assertCode(complete, TaskDiagnosticCode.TASK_BLOCKED);
        TaskMutationResult delete = execute(new TaskMutation.Delete(call(), blocker, revision(blocker),
                service.snapshot().revision()));
        assertCode(delete, TaskDiagnosticCode.TASK_DEPENDENCY_INVALID);
        assertThat(delete.diagnostic().orElseThrow().relatedTaskIds()).containsExactly(blocked);
    }

    @Test
    void reopenCompletedBlockerRecomputesBlockedAndBlocksProjection() {
        TaskId blocker = create("blocker");
        TaskId dependent = create("dependent", List.of(blocker));
        assertThat(service.snapshot().task(dependent).orElseThrow().blocked()).isTrue();
        assertThat(execute(new TaskMutation.Transition(call(), blocker, revision(blocker),
                TaskStatus.COMPLETED, OptionalLong.empty())).succeeded()).isTrue();
        TaskBoardSnapshot completed = service.snapshot();
        assertThat(completed.task(dependent).orElseThrow().blocked()).isFalse();
        assertThat(completed.task(blocker).orElseThrow().blocks()).containsExactly(dependent);
        assertThat(execute(new TaskMutation.Transition(call(), blocker, revision(blocker),
                TaskStatus.PENDING, OptionalLong.empty())).succeeded()).isTrue();
        assertThat(service.snapshot().task(dependent).orElseThrow().activeBlockers()).containsExactly(blocker);
    }

    @Test
    void tombstonePreventsIdentityReuseAndDistinguishesDeletedFromUnknown() {
        TaskId first = create("first");
        assertThat(execute(new TaskMutation.Delete(call(), first, revision(first),
                service.snapshot().revision())).succeeded()).isTrue();
        TaskId second = create("second");
        assertThat(second.sequence()).isEqualTo(first.sequence() + 1);
        assertThat(service.snapshot().tombstones()).contains(first);
        assertCode(execute(new TaskMutation.Edit(call(), first, 1, OptionalLong.empty(), Optional.of("changed"), Optional.empty(),
                false, Optional.empty(), TaskMetadataPatch.empty())), TaskDiagnosticCode.TASK_DELETED);
        assertCode(execute(new TaskMutation.Edit(call(), new TaskId(999), 1, OptionalLong.empty(), Optional.of("changed"), Optional.empty(),
                false, Optional.empty(), TaskMetadataPatch.empty())), TaskDiagnosticCode.TASK_NOT_FOUND);
    }

    @Test
    void taskAndBoardCasRejectStaleMutations() {
        TaskId task = create("task");
        long oldBoard = service.snapshot().revision();
        assertThat(execute(new TaskMutation.Edit(call(), task, 1, OptionalLong.empty(), Optional.of("edited"), Optional.empty(),
                false, Optional.empty(), TaskMetadataPatch.empty())).succeeded()).isTrue();
        assertCode(execute(new TaskMutation.Edit(call(), task, 1, OptionalLong.empty(), Optional.of("stale"), Optional.empty(),
                false, Optional.empty(), TaskMetadataPatch.empty())), TaskDiagnosticCode.TASK_REVISION_CONFLICT);
        assertCode(execute(new TaskMutation.Dependency(call(), task, revision(task), oldBoard,
                List.of(), List.of())), TaskDiagnosticCode.TASK_BOARD_CONFLICT);
    }

    @Test
    void concurrentClaimHasSingleWinner() throws Exception {
        TaskId task = create("race");
        TaskBoardCapability one = child("child-one", "session-child-one", Set.of(task));
        TaskBoardCapability two = child("child-two", "session-child-two", Set.of(task));
        TaskMutation.Claim first = new TaskMutation.Claim(new TaskCallId("claim-one"), task, 1);
        TaskMutation.Claim second = new TaskMutation.Claim(new TaskCallId("claim-two"), task, 1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> service.execute(one, first));
            var secondFuture = executor.submit(() -> service.execute(two, second));
            List<TaskMutationResult> results = List.of(firstFuture.get(), secondFuture.get());
            assertThat(results).filteredOn(TaskMutationResult::succeeded).hasSize(1);
            assertThat(results).filteredOn(result -> !result.succeeded()).singleElement()
                    .satisfies(result -> assertThat(result.diagnostic().orElseThrow().code())
                            .isIn(TaskDiagnosticCode.TASK_REVISION_CONFLICT, TaskDiagnosticCode.TASK_CLAIM_CONFLICT));
        }
    }

    @Test
    void oldEpochIsRejectedAndTerminatedRunRequiresExplicitRootRecovery() {
        TaskId task = create("recover");
        RunId firstRun = new RunId("run-first");
        TaskBoardCapability child = child("child-a", "session-child-a", firstRun, Set.of(task));
        TaskMutationResult claimed = service.execute(child,
                new TaskMutation.Claim(new TaskCallId("claim"), task, 1));
        long epoch = claimed.task().orElseThrow().claim().orElseThrow().epoch();
        assertCode(service.execute(child, new TaskMutation.Release(new TaskCallId("old-epoch"), task,
                revision(task), epoch + 1)), TaskDiagnosticCode.TASK_CLAIM_CONFLICT);
        long beforeTerminationRevision = service.snapshot().revision();
        long beforeTerminationTaskRevision = revision(task);
        terminatedRuns.add(firstRun);
        TaskItemView recoveryView = service.snapshot().task(task).orElseThrow();
        assertThat(recoveryView.recoveryRequired()).isTrue();
        assertThat(recoveryView.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(recoveryView.claim().orElseThrow().epoch()).isEqualTo(epoch);
        assertThat(service.snapshot().revision()).isEqualTo(beforeTerminationRevision);
        assertThat(revision(task)).isEqualTo(beforeTerminationTaskRevision);
        assertCode(service.execute(child, new TaskMutation.ResumeClaim(new TaskCallId("child-resume"), task,
                revision(task), epoch)), TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
        TaskMutationResult resumed = execute(new TaskMutation.ResumeClaim(call(), task, revision(task), epoch));
        assertThat(resumed.succeeded()).isTrue();
        assertThat(resumed.task().orElseThrow().claim().orElseThrow().epoch()).isEqualTo(epoch + 1);
        assertThat(resumed.task().orElseThrow().recoveryRequired()).isFalse();
    }

    @Test
    void resumeClaimRechecksBlockersThatReopenedAfterOriginalClaim() {
        TaskId blocker = create("resume-blocker");
        TaskId dependent = create("resume-dependent", List.of(blocker));
        assertThat(execute(new TaskMutation.Transition(call(), blocker, revision(blocker),
                TaskStatus.COMPLETED, OptionalLong.empty())).succeeded()).isTrue();
        RunId childRun = new RunId("run-resume-blocked");
        TaskBoardCapability child = child("child-resume-blocked", "session-resume-blocked",
                childRun, Set.of(dependent));
        TaskMutationResult claimed = service.execute(child,
                new TaskMutation.Claim(new TaskCallId("claim-resume-blocked"), dependent, revision(dependent)));
        long epoch = claimed.task().orElseThrow().claim().orElseThrow().epoch();
        assertThat(execute(new TaskMutation.Transition(call(), blocker, revision(blocker),
                TaskStatus.PENDING, OptionalLong.empty())).succeeded()).isTrue();
        terminatedRuns.add(childRun);
        long boardRevision = service.snapshot().revision();

        TaskMutationResult resume = execute(new TaskMutation.ResumeClaim(call(), dependent,
                revision(dependent), epoch));

        assertCode(resume, TaskDiagnosticCode.TASK_BLOCKED);
        assertThat(resume.diagnostic().orElseThrow().relatedTaskIds()).containsExactly(blocker);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
    }

    @Test
    void rootAndChildCapabilitiesEnforceBoardScopeAndOwnership() {
        TaskId task = create("scoped");
        TaskBoardCapability wrongBoard = new TaskBoardCapability(new TaskBoardId("board-other"), OWNER_SESSION,
                ROOT_ACTOR, OWNER_SESSION, new RunId("run-root"), true,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
        assertCode(service.execute(wrongBoard, new TaskMutation.Edit(new TaskCallId("wrong-board"), task, 1, OptionalLong.empty(),
                Optional.of("x"), Optional.empty(), false, Optional.empty(), TaskMetadataPatch.empty())),
                TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
        TaskBoardCapability noScope = child("child-no-scope", "session-child-no-scope", Set.of());
        TaskMutationResult denied = service.execute(noScope,
                new TaskMutation.Claim(new TaskCallId("no-scope"), task, 1));
        assertCode(denied, TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
        assertThat(denied.diagnostic().orElseThrow().taskRevision()).isEmpty();
        TaskBoardCapability allowed = child("child-allowed", "session-child-allowed", Set.of(task));
        assertThat(service.execute(allowed, new TaskMutation.Claim(new TaskCallId("allowed"), task, 1)).succeeded()).isTrue();
        TaskBoardCapability other = child("child-other", "session-child-other", Set.of(task));
        assertCode(service.execute(other, new TaskMutation.Edit(new TaskCallId("other-edit"), task, revision(task), OptionalLong.of(1),
                Optional.of("stolen"), Optional.empty(), false, Optional.empty(), TaskMetadataPatch.empty())),
                TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
        assertThat(service.execute(allowed, new TaskMutation.Edit(new TaskCallId("owner-edit"), task, revision(task), OptionalLong.of(1),
                Optional.of("owned"), Optional.empty(), false, Optional.empty(), TaskMetadataPatch.empty())).succeeded())
                .isTrue();
    }

    @Test
    void sameActorCallIdRetryReturnsOriginalResultAndChangedArgumentsConflict() {
        TaskMutation.Create create = new TaskMutation.Create(new TaskCallId("idempotent"), "same", "",
                Optional.empty(), TaskMetadata.EMPTY, List.of());
        TaskMutationResult first = service.execute(root, create);
        TaskMutationResult replay = service.execute(root, create);
        assertThat(replay).isSameAs(first);
        TaskMutationResult changed = service.execute(root, new TaskMutation.Create(new TaskCallId("idempotent"),
                "different", "", Optional.empty(), TaskMetadata.EMPTY, List.of()));
        assertCode(changed, TaskDiagnosticCode.TASK_BOARD_CONFLICT);
        assertThat(changed.snapshot().tasks()).hasSize(1);
    }

    @Test
    void recoveryTaskRejectsEditAndCompletionUntilExplicitRecoveryMutation() {
        TaskId task = create("recover-guard");
        RunId runId = new RunId("run-recover-guard");
        TaskBoardCapability child = child("child-recover", "session-child-recover", runId, Set.of(task));
        TaskMutationResult claimed = service.execute(child,
                new TaskMutation.Claim(new TaskCallId("claim-recover-guard"), task, revision(task)));
        long epoch = claimed.task().orElseThrow().claim().orElseThrow().epoch();
        terminatedRuns.add(runId);
        long boardRevision = service.snapshot().revision();
        long taskRevision = revision(task);

        assertCode(service.execute(child, new TaskMutation.Edit(new TaskCallId("edit-recovery"), task,
                taskRevision, OptionalLong.of(epoch), Optional.of("unsafe"), Optional.empty(), false,
                Optional.empty(), TaskMetadataPatch.empty())), TaskDiagnosticCode.TASK_RECOVERY_REQUIRED);
        assertCode(execute(new TaskMutation.Transition(call(), task, taskRevision, TaskStatus.COMPLETED,
                OptionalLong.of(epoch))), TaskDiagnosticCode.TASK_RECOVERY_REQUIRED);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
        assertThat(revision(task)).isEqualTo(taskRevision);
        assertThat(service.snapshot().task(task).orElseThrow().status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void nonRecoveryResumeAndReadOnlyMutationAreDeniedWithoutRevisionChange() {
        TaskId task = create("active");
        TaskBoardCapability child = child("child-active", "session-child-active", Set.of(task));
        TaskMutationResult claimed = service.execute(child,
                new TaskMutation.Claim(new TaskCallId("claim-active"), task, revision(task)));
        long epoch = claimed.task().orElseThrow().claim().orElseThrow().epoch();
        long boardRevision = service.snapshot().revision();
        assertCode(execute(new TaskMutation.ResumeClaim(call(), task, revision(task), epoch)),
                TaskDiagnosticCode.TASK_RECOVERY_REQUIRED);

        TaskBoardCapability readOnly = new TaskBoardCapability(BOARD_ID, OWNER_SESSION,
                new TaskActorId("child-read-only"), new SessionId("session-child-read-only"),
                new RunId("run-child-read-only"), false,
                Set.of(ToolEffect.READ_SESSION_STATE), Set.of(task));
        assertCode(service.execute(readOnly, new TaskMutation.Release(new TaskCallId("read-only-release"), task,
                revision(task), epoch)), TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
    }

    @Test
    void callIdIdempotencyIsScopedToActorIdentity() {
        TaskId first = create("first-actor-task");
        TaskId second = create("second-actor-task");
        TaskCallId sharedCall = new TaskCallId("shared-call");
        TaskMutationResult firstClaim = service.execute(child("actor-one", "session-actor-one", Set.of(first)),
                new TaskMutation.Claim(sharedCall, first, revision(first)));
        TaskMutationResult secondClaim = service.execute(child("actor-two", "session-actor-two", Set.of(second)),
                new TaskMutation.Claim(sharedCall, second, revision(second)));
        assertThat(firstClaim.succeeded()).isTrue();
        assertThat(secondClaim.succeeded()).isTrue();
    }

    @Test
    void assignReassignReleaseAndCompleteRespectActorAndClaimRules() {
        TaskId task = create("assignment");
        TaskActorId childActor = new TaskActorId("child-assigned");
        assertThat(execute(new TaskMutation.Assign(call(), task, 1, childActor)).succeeded()).isTrue();
        TaskBoardCapability child = child(childActor.value(), "session-assigned", Set.of(task));
        TaskMutationResult claimed = service.execute(child, new TaskMutation.Claim(new TaskCallId("assigned-claim"),
                task, revision(task)));
        long epoch = claimed.task().orElseThrow().claim().orElseThrow().epoch();
        assertThat(service.execute(child, new TaskMutation.Release(new TaskCallId("assigned-release"), task,
                revision(task), epoch)).succeeded()).isTrue();
        assertThat(service.snapshot().task(task).orElseThrow().owner()).contains(childActor);
        TaskActorId nextActor = new TaskActorId("child-next");
        assertThat(execute(new TaskMutation.Reassign(call(), task, revision(task),
                nextActor, OptionalLong.empty())).succeeded()).isTrue();
        assertThat(execute(new TaskMutation.Transition(call(), task, revision(task), TaskStatus.COMPLETED,
                OptionalLong.empty())).succeeded()).isTrue();
        assertThat(execute(new TaskMutation.Transition(call(), task, revision(task), TaskStatus.PENDING,
                OptionalLong.empty())).succeeded()).isTrue();
        assertThat(service.snapshot().task(task).orElseThrow().owner()).contains(nextActor);
    }

    @Test
    void liveTaskAndDependencyResourceCeilingsFailClosed() {
        for (int index = 0; index < 256; index++) create("task-" + index);
        long revision = service.snapshot().revision();
        assertCode(execute(new TaskMutation.Create(call(), "overflow", "", Optional.empty(),
                TaskMetadata.EMPTY, List.of())), TaskDiagnosticCode.TASK_LIMIT_EXCEEDED);
        assertThat(service.snapshot().revision()).isEqualTo(revision);

        setUp();
        List<TaskId> blockers = new ArrayList<>();
        for (int index = 0; index < 33; index++) blockers.add(create("blocker-" + index));
        TaskMutationResult exact = execute(new TaskMutation.Create(call(), "exact-dependencies", "", Optional.empty(),
                TaskMetadata.EMPTY, blockers.subList(0, 32)));
        assertThat(exact.succeeded()).isTrue();
        assertThat(exact.task().orElseThrow().blockedBy()).hasSize(32);
        assertCode(execute(new TaskMutation.Create(call(), "too-many-dependencies", "", Optional.empty(),
                TaskMetadata.EMPTY, blockers)), TaskDiagnosticCode.TASK_LIMIT_EXCEEDED);

        TaskId exactTarget = create("dependency-exact-target");
        assertThat(execute(new TaskMutation.Dependency(call(), exactTarget, revision(exactTarget),
                service.snapshot().revision(), blockers.subList(0, 32), List.of())).succeeded()).isTrue();
        assertThat(service.snapshot().task(exactTarget).orElseThrow().blockedBy()).hasSize(32);

        TaskId target = create("dependency-overflow-target");
        long boardRevision = service.snapshot().revision();
        long taskRevision = revision(target);
        assertCode(execute(new TaskMutation.Dependency(call(), target, taskRevision, boardRevision,
                blockers, List.of())), TaskDiagnosticCode.TASK_LIMIT_EXCEEDED);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
        assertThat(revision(target)).isEqualTo(taskRevision);
    }

    @Test
    void claimAndResumeUseHostInjectedRunIdentity() {
        TaskId task = create("trusted-run");
        RunId childRun = new RunId("trusted-child-run");
        TaskBoardCapability child = child("trusted-child", "trusted-child-session", childRun, Set.of(task));
        TaskMutationResult claimed = service.execute(child,
                new TaskMutation.Claim(new TaskCallId("trusted-claim"), task, revision(task)));
        assertThat(claimed.task().orElseThrow().claim().orElseThrow().runId()).isEqualTo(childRun);

        terminatedRuns.add(childRun);
        long epoch = claimed.task().orElseThrow().claim().orElseThrow().epoch();
        TaskMutationResult resumed = execute(new TaskMutation.ResumeClaim(call(), task, revision(task), epoch));
        assertThat(resumed.task().orElseThrow().claim().orElseThrow().runId())
                .isEqualTo(root.actorRunId());
    }

    @Test
    void idempotencyKeyIncludesActorSessionAndActorRun() {
        TaskId first = create("key-first");
        TaskId second = create("key-second");
        TaskId third = create("key-third");
        TaskCallId shared = new TaskCallId("same-call-across-runs");
        TaskActorId actor = new TaskActorId("same-actor");
        TaskBoardCapability runOne = new TaskBoardCapability(BOARD_ID, OWNER_SESSION, actor,
                new SessionId("same-session"), new RunId("run-one"), false,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of(first));
        TaskBoardCapability runTwo = new TaskBoardCapability(BOARD_ID, OWNER_SESSION, actor,
                new SessionId("same-session"), new RunId("run-two"), false,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of(second));
        TaskBoardCapability otherSession = new TaskBoardCapability(BOARD_ID, OWNER_SESSION, actor,
                new SessionId("other-session"), new RunId("run-two"), false,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of(third));

        assertThat(service.execute(runOne, new TaskMutation.Claim(shared, first, revision(first))).succeeded()).isTrue();
        assertThat(service.execute(runTwo, new TaskMutation.Claim(shared, second, revision(second))).succeeded()).isTrue();
        assertThat(service.execute(otherSession, new TaskMutation.Claim(shared, third, revision(third))).succeeded()).isTrue();
    }

    @Test
    void semanticNoOpEditAndDependencyAreRejectedWithoutRevisionChange() {
        TaskId task = create("no-op");
        long boardRevision = service.snapshot().revision();
        long taskRevision = revision(task);
        assertCode(execute(new TaskMutation.Edit(call(), task, taskRevision, OptionalLong.empty(),
                Optional.of("no-op"), Optional.of(""), false, Optional.empty(), TaskMetadataPatch.empty())),
                TaskDiagnosticCode.TASK_INVALID_TRANSITION);
        assertCode(execute(new TaskMutation.Dependency(call(), task, taskRevision, boardRevision,
                List.of(), List.of())), TaskDiagnosticCode.TASK_DEPENDENCY_INVALID);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
        assertThat(revision(task)).isEqualTo(taskRevision);
    }

    @Test
    void completedReopenRejectsIrrelevantExpectedClaimEpoch() {
        TaskId task = create("reopen-epoch");
        assertThat(execute(new TaskMutation.Transition(call(), task, revision(task), TaskStatus.COMPLETED,
                OptionalLong.empty())).succeeded()).isTrue();
        long boardRevision = service.snapshot().revision();
        long taskRevision = revision(task);
        assertCode(execute(new TaskMutation.Transition(call(), task, taskRevision, TaskStatus.PENDING,
                OptionalLong.of(1))), TaskDiagnosticCode.TASK_CLAIM_CONFLICT);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
        assertThat(revision(task)).isEqualTo(taskRevision);
    }

    @Test
    void successfulMutationBudgetReturnsStoredRetryAndRejectsNewCallsAtCeiling() {
        TaskId task = create("budget-0");
        TaskMutation.Edit last = null;
        TaskMutationResult lastResult = null;
        for (int index = 1; index < 4_096; index++) {
            last = new TaskMutation.Edit(call(), task, revision(task), OptionalLong.empty(),
                    Optional.of("budget-" + index), Optional.empty(), false, Optional.empty(),
                    TaskMetadataPatch.empty());
            lastResult = execute(last);
            assertThat(lastResult.succeeded()).isTrue();
        }
        long boardRevision = service.snapshot().revision();
        long taskRevision = revision(task);
        assertThat(service.execute(root, Objects.requireNonNull(last))).isSameAs(lastResult);
        assertCode(execute(new TaskMutation.Edit(call(), task, taskRevision, OptionalLong.empty(),
                Optional.of("over-budget"), Optional.empty(), false, Optional.empty(), TaskMetadataPatch.empty())),
                TaskDiagnosticCode.TASK_LIMIT_EXCEEDED);
        assertThat(service.snapshot().revision()).isEqualTo(boardRevision);
        assertThat(revision(task)).isEqualTo(taskRevision);
    }

    @Test
    void listUsesStablePaginationFiltersAndBoundedSummaryProjection() {
        TaskId alpha = create("Alpha task");
        TaskId beta = create("beta task");
        TaskId gamma = create("Gamma task");
        assertThat(execute(new TaskMutation.Transition(call(), beta, revision(beta),
                TaskStatus.COMPLETED, OptionalLong.empty())).succeeded()).isTrue();

        TaskReadResult<TaskListPage> first = service.list(root,
                new TaskListQuery(Optional.empty(), Optional.empty(), Optional.empty(), 2));
        assertThat(first.succeeded()).isTrue();
        assertThat(first.value().orElseThrow().tasks()).extracting(TaskSummary::id)
                .containsExactly(alpha, beta);
        assertThat(first.value().orElseThrow().nextCursor()).contains(beta);
        TaskReadResult<TaskListPage> second = service.list(root,
                new TaskListQuery(Optional.empty(), Optional.empty(), Optional.of(beta), 2));
        assertThat(second.value().orElseThrow().tasks()).extracting(TaskSummary::id)
                .containsExactly(gamma);
        assertThat(second.value().orElseThrow().nextCursor()).isEmpty();

        TaskListPage filtered = service.list(root, new TaskListQuery(Optional.of(TaskStatus.PENDING),
                Optional.of("gAmMa"), Optional.empty(), 25)).value().orElseThrow();
        assertThat(filtered.tasks()).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(gamma);
            assertThat(summary.subject()).isEqualTo("Gamma task");
        });
    }

    @Test
    void getAndListEnforceReadCapabilityAndChildScope() {
        TaskId first = create("first-readable");
        TaskId second = create("second-hidden");
        TaskBoardCapability child = child("reader", "reader-session", Set.of(first));
        TaskListPage page = service.list(child, TaskListQuery.defaults()).value().orElseThrow();
        assertThat(page.tasks()).extracting(TaskSummary::id).containsExactly(first);
        TaskGetProjection projection = service.get(child, first).value().orElseThrow();
        assertThat(projection.boardRevision()).isEqualTo(service.snapshot().revision());
        assertThat(projection.task().subject()).isEqualTo("first-readable");
        assertCode(service.get(child, second), TaskDiagnosticCode.TASK_CAPABILITY_DENIED);

        TaskBoardCapability writeOnly = new TaskBoardCapability(BOARD_ID, OWNER_SESSION,
                ROOT_ACTOR, OWNER_SESSION, new RunId("write-only-run"), true,
                Set.of(ToolEffect.WRITE_SESSION_STATE), Set.of());
        assertCode(service.list(writeOnly, TaskListQuery.defaults()), TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
        assertCode(service.get(writeOnly, first), TaskDiagnosticCode.TASK_CAPABILITY_DENIED);
    }

    private TaskMutationResult dependency(TaskId task, List<TaskId> add, List<TaskId> remove) {
        return execute(new TaskMutation.Dependency(call(), task, revision(task), service.snapshot().revision(), add, remove));
    }

    private TaskId create(String subject) { return create(subject, List.of()); }

    private TaskId create(String subject, List<TaskId> blockedBy) {
        TaskMutationResult result = execute(new TaskMutation.Create(call(), subject, "", Optional.empty(),
                TaskMetadata.EMPTY, blockedBy));
        assertThat(result.succeeded()).isTrue();
        return result.task().orElseThrow().id();
    }

    private TaskMutationResult execute(TaskMutation mutation) { return service.execute(root, mutation); }

    private long revision(TaskId id) { return service.snapshot().task(id).orElseThrow().revision(); }

    private TaskCallId call() { return new TaskCallId("call-" + calls.incrementAndGet()); }

    private static TaskBoardCapability child(String actor, String session, Set<TaskId> scope) {
        return child(actor, session, new RunId("run:" + session), scope);
    }

    private static TaskBoardCapability child(String actor, String session, RunId runId, Set<TaskId> scope) {
        return new TaskBoardCapability(BOARD_ID, OWNER_SESSION, new TaskActorId(actor),
                new SessionId(session), runId, false,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), scope);
    }

    private static void assertCode(TaskMutationResult result, TaskDiagnosticCode code) {
        assertThat(result.succeeded()).isFalse();
        assertThat(result.diagnostic().orElseThrow().code()).isEqualTo(code);
    }

    private static void assertCode(TaskReadResult<?> result, TaskDiagnosticCode code) {
        assertThat(result.succeeded()).isFalse();
        assertThat(result.diagnostic().orElseThrow().code()).isEqualTo(code);
    }
}
