package io.github.liumaishenjian.ccjava.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** ADR-100：运行终态先于清理，清理证据冻结且不保留资源查询入口。 */
class AgentSupervisorCleanupStatusTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String SECRET = "credential-secret-C:/private/owned";
    private static final ChildBudget BUDGET = new ChildBudget(2, 0, 1000, 256, Duration.ofSeconds(10));
    private static final AgentDefinitionSnapshot DEFINITION = new AgentDefinitionSnapshot(
            new AgentDefinitionId("cleanup"), "test", "test", Set.of(), PermissionMode.PLAN,
            "fake", BUDGET, false, "0".repeat(64), "project");

    @Test
    void awaitPublishesCleaningBeforeBlockedCloseThenInspectShowsExplicitRelease() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ResourceCleanupStatus> evidence = new AtomicReference<>(ResourceCleanupStatus.NOT_STARTED);
        AtomicReference<ChildTaskReport> journalReport = new AtomicReference<>();
        ChildTaskJournal journal = new ChildTaskJournal() {
            @Override public void requested(ChildTaskId id) { }
            @Override public void started(ChildTaskId id) { }
            @Override public void terminal(ChildTaskReport report) { journalReport.set(report); }
        };
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(ignored -> ModelTurn.text(SECRET), () -> {
            closing.countDown();
            release.await();
            evidence.set(ResourceCleanupStatus.RELEASED);
        }, evidence::get), journal)) {
            try {
                ChildTaskHandle task = supervisor.submit(request("slow"), CancellationToken.none());
                assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
                long waitingSince = System.nanoTime();
                ChildTaskReport terminal = task.await(WAIT);
                assertThat(Duration.ofNanos(System.nanoTime() - waitingSince)).isLessThan(Duration.ofSeconds(2));
                assertThat(terminal.status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
                assertThat(terminal.cleanupStatus()).isEqualTo(ResourceCleanupStatus.CLEANING);
                assertThat(journalReport.get().cleanupStatus()).isEqualTo(ResourceCleanupStatus.CLEANING);
                assertThat(render(terminal)).contains("status=succeeded", "cleanup=cleaning", "modelTurns=1", "toolCalls=0")
                        .doesNotContain(SECRET);
                release.countDown();
                awaitCleanup(task, ResourceCleanupStatus.RELEASED);
                assertThat(task.inspect().status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
                assertThat(journalReport.get().cleanupStatus()).isEqualTo(ResourceCleanupStatus.CLEANING);
            } finally { release.countDown(); }
        }
    }

    @Test
    void closeFailureNeverRewritesSucceededAndDoesNotLeakCause() throws Exception {
        assertCloseFailurePreservesResult(ignored -> ModelTurn.text(SECRET), ChildTaskStatus.SUCCEEDED);
    }

    @Test
    void closeFailureNeverRewritesFailedAndDoesNotLeakCause() throws Exception {
        assertCloseFailurePreservesResult(ignored -> { throw new IllegalStateException(SECRET); }, ChildTaskStatus.FAILED);
    }

    private void assertCloseFailurePreservesResult(ModelGateway model, ChildTaskStatus status) throws Exception {
        AtomicInteger terminals = new AtomicInteger();
        ChildTaskJournal journal = new ChildTaskJournal() {
            @Override public void requested(ChildTaskId id) { }
            @Override public void started(ChildTaskId id) { }
            @Override public void terminal(ChildTaskReport report) { terminals.incrementAndGet(); }
        };
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(model,
                () -> { throw new IllegalStateException(SECRET); }, () -> ResourceCleanupStatus.RELEASED), journal)) {
            ChildTaskHandle task = supervisor.submit(request("close-failure"), CancellationToken.none());
            assertThat(task.await(WAIT).status()).isEqualTo(status);
            awaitCleanup(task, ResourceCleanupStatus.UNCONFIRMED);
            assertThat(task.inspect().status()).isEqualTo(status);
            assertThat(terminals).hasValue(1);
            assertThat(render(task.inspect())).contains("cleanup=unconfirmed").doesNotContain(SECRET);
        }
    }

    @Test
    void runtimeErrorPublishesFailedBeforeBlockingCleanup() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(ignored -> {
            throw new IllegalStateException(SECRET);
        }, () -> { closing.countDown(); release.await(); }, () -> ResourceCleanupStatus.RELEASED))) {
            try {
                ChildTaskHandle task = supervisor.submit(request("failed"), CancellationToken.none());
                assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
                long waitingSince = System.nanoTime();
                assertThat(task.await(WAIT).status()).isEqualTo(ChildTaskStatus.FAILED);
                assertThat(Duration.ofNanos(System.nanoTime() - waitingSince)).isLessThan(Duration.ofSeconds(2));
                assertThat(task.inspect().cleanupStatus()).isEqualTo(ResourceCleanupStatus.CLEANING);
                release.countDown();
                awaitCleanup(task, ResourceCleanupStatus.RELEASED);
                assertThat(task.inspect().status()).isEqualTo(ChildTaskStatus.FAILED);
            } finally { release.countDown(); }
        }
    }

    @Test
    void oldFakeNormalCloseIsNotReleaseEvidence() throws Exception {
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> {
            ChildRuntimeScope fresh = scope(ignored -> ModelTurn.text("done"), () -> { }, () -> ResourceCleanupStatus.RELEASED);
            return new ChildRuntimeScope(fresh.runtime(), fresh.sessionId(), fresh.cleanup());
        })) {
            ChildTaskHandle task = supervisor.submit(request("legacy"), CancellationToken.none());
            assertThat(task.await(WAIT).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            awaitCleanup(task, ResourceCleanupStatus.UNCONFIRMED);
        }
    }

    @Test
    void scopeCreationFailureIsUnconfirmedNotReleased() throws Exception {
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> { throw new IllegalStateException(SECRET); })) {
            ChildTaskReport report = supervisor.submit(request("creation"), CancellationToken.none()).await(WAIT);
            assertThat(report.status()).isEqualTo(ChildTaskStatus.FAILED);
            assertThat(report.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
            assertThat(render(report)).doesNotContain(SECRET);
        }
    }

    @Test
    void queuedCancellationConfirmsOnlyUnacquiredScopeResources() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> {
            created.incrementAndGet();
            return scope(ignored -> {
                running.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return ModelTurn.text("done");
            }, () -> { }, () -> ResourceCleanupStatus.RELEASED);
        })) {
            try {
                ChildTaskHandle active = supervisor.submit(request("active"), CancellationToken.none());
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                ChildTaskHandle queued = supervisor.submit(request("queued"), CancellationToken.none());
                assertThat(queued.inspect().cleanupStatus()).isEqualTo(ResourceCleanupStatus.NOT_STARTED);
                assertThat(queued.cancel()).isTrue();
                ChildTaskReport terminal = queued.await(WAIT);
                assertThat(terminal.status()).isEqualTo(ChildTaskStatus.CANCELLED);
                assertThat(terminal.cleanupStatus()).isEqualTo(ResourceCleanupStatus.RELEASED);
                assertThat(created).hasValue(1);
                release.countDown();
                active.await(WAIT);
            } finally { release.countDown(); }
        }
    }

    @Test
    void freezesOneProbeAndNeverFollowsLateRelease() throws Exception {
        for (ResourceCleanupStatus pending : List.of(ResourceCleanupStatus.UNKNOWN,
                ResourceCleanupStatus.NOT_STARTED, ResourceCleanupStatus.CLEANING, ResourceCleanupStatus.UNCONFIRMED)) {
            AtomicInteger probes = new AtomicInteger();
            AtomicReference<ResourceCleanupStatus> evidence = new AtomicReference<>(pending);
            try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(ignored -> ModelTurn.text("done"),
                    () -> { }, () -> { probes.incrementAndGet(); return evidence.get(); }))) {
                ChildTaskHandle task = supervisor.submit(request("late"), CancellationToken.none());
                task.await(WAIT);
                awaitCleanup(task, ResourceCleanupStatus.UNCONFIRMED);
                evidence.set(ResourceCleanupStatus.RELEASED);
                for (int index = 0; index < 5; index++) {
                    assertThat(task.inspect().cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
                    assertThat(task.await(Duration.ZERO).cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
                }
                assertThat(probes).hasValue(1);
            }
        }
    }

    @Test
    void journalFailureStaysFailedEvenWhenOwnedResourcesAreReleased() throws Exception {
        AtomicInteger terminalAttempts = new AtomicInteger();
        AtomicReference<ChildTaskReport> marker = new AtomicReference<>();
        ChildTaskJournal journal = new ChildTaskJournal() {
            @Override public void requested(ChildTaskId id) { }
            @Override public void started(ChildTaskId id) { }
            @Override public void terminal(ChildTaskReport report) {
                terminalAttempts.incrementAndGet();
                throw new IllegalStateException(SECRET);
            }
            @Override public void terminalFailure(ChildTaskReport report) { marker.set(report); }
        };
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(ignored -> ModelTurn.text("done"),
                () -> { }, () -> ResourceCleanupStatus.RELEASED), journal)) {
            ChildTaskHandle task = supervisor.submit(request("journal"), CancellationToken.none());
            assertThat(task.await(WAIT).failureCode()).isEqualTo(ChildTaskFailureCode.JOURNAL_FAILED);
            awaitCleanup(task, ResourceCleanupStatus.RELEASED);
            assertThat(task.inspect().status()).isEqualTo(ChildTaskStatus.FAILED);
            assertThat(marker.get().cleanupStatus()).isEqualTo(ResourceCleanupStatus.CLEANING);
            assertThat(terminalAttempts).hasValue(1);
            assertThat(render(task.inspect())).doesNotContain(SECRET);
        }
    }

    @Test
    void missingProbeValueIsConservativelyUnconfirmed() throws Exception {
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(ignored -> ModelTurn.text("done"),
                () -> { }, () -> null))) {
            ChildTaskHandle task = supervisor.submit(request("null-probe"), CancellationToken.none());
            assertThat(task.await(WAIT).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            awaitCleanup(task, ResourceCleanupStatus.UNCONFIRMED);
        }
    }

    @Test
    void brokenProbeCannotChangeRuntimeTerminal() throws Exception {
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> scope(ignored -> ModelTurn.text("done"), () -> { },
                () -> { throw new AssertionError(SECRET); }))) {
            ChildTaskHandle task = supervisor.submit(request("probe"), CancellationToken.none());
            assertThat(task.await(WAIT).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            awaitCleanup(task, ResourceCleanupStatus.UNCONFIRMED);
        }
    }

    @Test
    void compatibilityScopeConstructorsAndRunScopeKeepUnknownEvidence() {
        ChildRuntimeScope full = scope(ignored -> ModelTurn.text("done"), () -> { }, () -> ResourceCleanupStatus.RELEASED);
        List<ChildRuntimeScope> oldScopes = List.of(
                new ChildRuntimeScope(full.runtime(), full.sessionId(), full.cleanup()),
                new ChildRuntimeScope(full.runtime(), full.sessionId(), full.cleanup(), Optional::empty),
                new ChildRuntimeScope(full.runtime(), full.sessionId(), full.cleanup(), Optional::empty, RunInitializer.noop()),
                new ChildRuntimeScope(full.runtime(), full.sessionId(), full.cleanup(), Optional::empty,
                        RunInitializer.noop(), CancellationToken.none()));
        oldScopes.forEach(value -> assertThat(value.cleanupStatus().get()).isEqualTo(ResourceCleanupStatus.UNKNOWN));
        RunScopedModelGateway.RunScope legacy = new RunScopedModelGateway.RunScope() {
            @Override public void bindCancellation(Runnable cancellation) { }
            @Override public void close() { }
        };
        legacy.close();
        assertThat(legacy.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
        assertThatThrownBy(() -> new ChildRuntimeScope(full.runtime(), full.sessionId(), full.cleanup(),
                Optional::empty, RunInitializer.noop(), CancellationToken.none(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void recoveredLegacyReportDoesNotInventReleaseEvidence() {
        ChildTaskReport old = new ChildTaskReport(new ChildTaskId("task-recovered"), DEFINITION.id(),
                ChildTaskStatus.INTERRUPTED_UNKNOWN, ChildTaskFailureCode.INTERRUPTED_UNKNOWN,
                0, 0, 0, Duration.ZERO, "interrupted_unknown", false, Optional.empty());
        try (AgentSupervisor supervisor = supervisor((d, r, c) -> { throw new AssertionError(); })) {
            supervisor.registerRecovered(old);
            ChildTaskReport recovered = supervisor.find(old.taskId()).orElseThrow().inspect();
            assertThat(recovered.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
            assertThat(recovered).isEqualTo(old);
        }
    }

    @Test
    void scopeCloseHidesOriginalExceptionAndDoesNotClaimRelease() {
        ChildRuntimeScope scope = scope(ignored -> ModelTurn.text("done"),
                () -> { throw new IllegalStateException(SECRET); }, () -> ResourceCleanupStatus.UNKNOWN);
        assertThatThrownBy(scope::close).isInstanceOf(IllegalStateException.class)
                .hasMessage("child_resource_cleanup_unconfirmed").hasNoCause();
        assertThat(scope.cleanupStatus().get()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
    }

    private static void awaitCleanup(ChildTaskHandle task, ResourceCleanupStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (task.inspect().cleanupStatus() != expected && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(task.inspect().cleanupStatus()).isEqualTo(expected);
    }

    private static String render(ChildTaskReport report) throws Exception {
        var method = DelegateAgentTool.class.getDeclaredMethod("render", ChildTaskReport.class);
        method.setAccessible(true);
        return (String) method.invoke(null, report);
    }

    private static ChildTaskRequest request(String name) {
        return new ChildTaskRequest(new DelegationId("delegation-" + name), DEFINITION.id(), "private prompt",
                Set.of(), BUDGET, true, 1, false);
    }

    private static AgentSupervisor supervisor(ChildRuntimeScopeFactory factory) {
        return supervisor(factory, ChildTaskJournal.noop());
    }

    private static AgentSupervisor supervisor(ChildRuntimeScopeFactory factory, ChildTaskJournal journal) {
        AgentDefinitionCatalog catalog = new AgentDefinitionCatalog() {
            @Override public Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id) { return Optional.of(DEFINITION); }
            @Override public List<AgentDefinitionSnapshot> snapshots() { return List.of(DEFINITION); }
        };
        return new AgentSupervisor(catalog, factory,
                new ChildBudgetLedger(new ChildBudget(8, 0, 4000, 1024, Duration.ofSeconds(40))),
                AgentDefinitionNarrower.identity(), journal, ChildTaskObserver.noop(), ChildTaskLifecycle.noop(),
                Clock.systemUTC(), 1, 2, 2);
    }

    private static ChildRuntimeScope scope(ModelGateway gateway, AutoCloseable cleanup,
            Supplier<ResourceCleanupStatus> evidence) {
        AgentIdGenerator ids = new AgentIdGenerator() {
            private final AtomicInteger sequence = new AtomicInteger();
            @Override public SessionId newSessionId() { return new SessionId("cleanup-session-" + sequence.incrementAndGet()); }
            @Override public RunId newRunId() { return new RunId("cleanup-run-" + sequence.incrementAndGet()); }
        };
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), ignored -> { });
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT, PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.deny(), lifecycle);
        AgentRuntime runtime = new AgentRuntime(sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle);
        AgentSession session = sessions.create(new SessionSpec("cleanup", Map.of()));
        return new ChildRuntimeScope(runtime, session.id(), () -> {
            try { cleanup.close(); } finally { sessions.close(session.id()); }
        }, Optional::empty, RunInitializer.noop(), CancellationToken.none(), evidence);
    }
}
