package io.github.liumaishenjian.ccjava.core.subagent;

import static org.assertj.core.api.Assertions.*;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** S15 / SUB-02、SUB-08、MODEL-13：入队来源与启动取消的独立离线回归，不使用账号或网络。 */
class PreparedChildRuntimeTest {
    private static final ChildBudget BUDGET = new ChildBudget(2, 0, 1000, 256, Duration.ofSeconds(5));
    private static final ChildBudget TOTAL = new ChildBudget(20, 0, 10000, 2560, Duration.ofSeconds(50));
    private static final AgentDefinitionSnapshot DEFINITION = new AgentDefinitionSnapshot(
            new AgentDefinitionId("research"), "readonly", "isolated", Set.of(), PermissionMode.PLAN,
            Optional.empty(), BUDGET, false, "0".repeat(64), "project");
    private static final SessionId PARENT_SESSION = new SessionId("trusted-parent-session");
    private static final RunId PARENT_RUN = new RunId("trusted-parent-run");

    @Test
    void capturesOnSubmitThreadBeforeWorkerAndQueuedSourceSurvivesChangeOnReusedWorker() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("original");
        List<String> used = new CopyOnWriteArrayList<>();
        List<Thread> prepareThreads = new CopyOnWriteArrayList<>();
        List<Thread> workerThreads = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChildRuntimeScopeFactory factory = new ChildRuntimeScopeFactory() {
            public ChildRuntimeScope create(AgentDefinitionSnapshot d, ChildTaskRequest r, CancellationToken t) {
                throw new AssertionError("worker must use prepared factory");
            }
            public PreparedChildRuntime prepare(SessionId session, RunId run, AgentDefinitionSnapshot d,
                    ChildTaskRequest request, CancellationToken token) {
                assertThat(session).isEqualTo(PARENT_SESSION);
                assertThat(run).isEqualTo(PARENT_RUN);
                prepareThreads.add(Thread.currentThread());
                String captured = current.get();
                return (effective, startup) -> {
                    workerThreads.add(Thread.currentThread());
                    if (request.prompt().equals("first")) { entered.countDown(); waitFor(release); }
                    assertThat(effective).isEqualTo(request);
                    used.add(captured);
                    return scope(ignored -> ModelTurn.text("done"));
                };
            }
        };
        try (AgentSupervisor supervisor = supervisor(factory, new ChildBudgetLedger(TOTAL), ChildTaskJournal.noop(), Clock.systemUTC())) {
            ChildTaskHandle first = supervisor.submit(PARENT_SESSION, PARENT_RUN, request("first"), CancellationToken.none());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            ChildTaskHandle queued = supervisor.submit(PARENT_SESSION, PARENT_RUN, request("queued"), CancellationToken.none());
            assertThat(used).isEmpty();
            current.set("different-account");
            release.countDown();
            assertThat(first.await(Duration.ofSeconds(3)).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            assertThat(queued.await(Duration.ofSeconds(3)).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            assertThat(prepareThreads).containsExactly(Thread.currentThread(), Thread.currentThread());
            assertThat(used).containsExactly("original", "original");
            assertThat(workerThreads.get(0)).isSameAs(workerThreads.get(1));
            assertThat(workerThreads.get(0).isVirtual()).isFalse();
        } finally { release.countDown(); }
    }

    @Test
    void identityRequiredRejectsLegacySubmitAndDelegateSuppliesOnlyTrustedInvocationIdentity() {
        AtomicInteger captures = new AtomicInteger();
        ChildRuntimeScopeFactory factory = new ChildRuntimeScopeFactory() {
            public boolean requiresParentIdentity() { return true; }
            public ChildRuntimeScope create(AgentDefinitionSnapshot d, ChildTaskRequest r, CancellationToken t) {
                throw new AssertionError();
            }
            public PreparedChildRuntime prepare(SessionId session, RunId run, AgentDefinitionSnapshot d,
                    ChildTaskRequest request, CancellationToken token) {
                captures.incrementAndGet();
                assertThat(session).isEqualTo(PARENT_SESSION);
                assertThat(run).isEqualTo(PARENT_RUN);
                assertThat(request.toString()).doesNotContain(PARENT_SESSION.value(), PARENT_RUN.value());
                return (effective, startup) -> scope(ignored -> ModelTurn.text("done"));
            }
        };
        try (AgentSupervisor supervisor = supervisor(factory, new ChildBudgetLedger(TOTAL), ChildTaskJournal.noop(), Clock.systemUTC())) {
            assertThatThrownBy(() -> supervisor.submit(request("old"), CancellationToken.none()))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(captures).hasValue(0);
            DelegateAgentTool tool = new DelegateAgentTool(supervisor);
            JsonObject arguments = new JsonObject(Map.of("definition", "research", "prompt", "delegate",
                    "maxModelTurns", 2, "maxToolCalls", 0, "maxInputTokens", 1000,
                    "maxOutputCharacters", 256, "timeoutSeconds", 5));
            tool.execute(new ToolInvocation(PARENT_SESSION, PARENT_RUN, 1,
                    new ToolCall("delegate-call", "delegate_agent", arguments)));
            assertThat(captures).hasValue(1);
            assertThat(arguments.toString()).doesNotContain(PARENT_SESSION.value(), PARENT_RUN.value());
            assertThat(tool.definition().inputSchemaJson()).doesNotContain("parentSessionId", "parentRunId");
            Map<String, Object> forged = new HashMap<>(arguments.values());
            forged.put("parentRunId", "forged");
            assertThat(tool.validate(new JsonObject(forged)).valid()).isFalse();
        }
    }

    @Test
    void prepareAndReserveFailuresNeverRegisterParentOrLeakBudget() {
        CountingToken parent = new CountingToken();
        ChildBudgetLedger ledger = new ChildBudgetLedger(TOTAL);
        ChildRuntimeScopeFactory broken = new ChildRuntimeScopeFactory() {
            public ChildRuntimeScope create(AgentDefinitionSnapshot d, ChildTaskRequest r, CancellationToken t) { throw new AssertionError(); }
            public PreparedChildRuntime prepare(SessionId s, RunId r, AgentDefinitionSnapshot d,
                    ChildTaskRequest request, CancellationToken t) { throw new IllegalStateException("capture failed"); }
        };
        try (AgentSupervisor supervisor = supervisor(broken, ledger, ChildTaskJournal.noop(), Clock.systemUTC())) {
            assertThatThrownBy(() -> supervisor.submit(request("capture"), parent)).isInstanceOf(IllegalStateException.class);
            assertThat(ledger.remaining()).isEqualTo(TOTAL);
            assertThat(parent.registrations).hasValue(0);
        }
        ChildBudgetLedger small = new ChildBudgetLedger(BUDGET);
        try (var held = small.reserve(BUDGET).orElseThrow();
                AgentSupervisor supervisor = supervisor((d, r, t) -> { throw new AssertionError(); }, small,
                        ChildTaskJournal.noop(), Clock.systemUTC())) {
            assertThatThrownBy(() -> supervisor.submit(request("reserve"), parent)).isInstanceOf(RejectedExecutionException.class);
            assertThat(parent.registrations).hasValue(0);
        }
        assertThat(small.remaining()).isEqualTo(BUDGET);
    }

    @Test
    void requestedJournalExceptionAndErrorReleaseRegistrationAndReservation() {
        for (boolean error : List.of(false, true)) {
            CountingToken parent = new CountingToken();
            ChildBudgetLedger ledger = new ChildBudgetLedger(TOTAL);
            ChildTaskJournal journal = new ChildTaskJournal() {
                public void requested(ChildTaskId id) { if (error) throw new AssertionError("journal"); throw new IllegalStateException("journal"); }
                public void started(ChildTaskId id) { }
                public void terminal(ChildTaskReport report) { }
            };
            try (AgentSupervisor supervisor = supervisor((d, r, t) -> { throw new AssertionError(); }, ledger, journal, Clock.systemUTC())) {
                assertThatThrownBy(() -> supervisor.submit(request("journal"), parent))
                        .isInstanceOf(error ? AssertionError.class : RejectedExecutionException.class);
                assertThat(parent.registrations).hasValue(0);
                assertThat(ledger.remaining()).isEqualTo(TOTAL);
                assertThat(supervisor.find(new ChildTaskId("task-1"))).isEmpty();
            }
        }
    }

    @Test
    void taskConstructionErrorReleasesAlreadyReservedBudgetBeforeRegistration() {
        CountingToken parent = new CountingToken();
        ChildBudgetLedger ledger = new ChildBudgetLedger(TOTAL);
        Clock broken = new Clock() {
            private int calls;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { if (++calls == 2) throw new AssertionError("construction"); return Instant.EPOCH; }
        };
        try (AgentSupervisor supervisor = supervisor((d, r, t) -> { throw new AssertionError(); }, ledger, ChildTaskJournal.noop(), broken)) {
            assertThatThrownBy(() -> supervisor.submit(request("constructor"), parent)).isInstanceOf(AssertionError.class);
            assertThat(ledger.remaining()).isEqualTo(TOTAL);
            assertThat(parent.registrations).hasValue(0);
        }
    }

    @Test
    void parentRegistrationErrorReturnsReservationWithoutPublishingTask() {
        ChildBudgetLedger ledger = new ChildBudgetLedger(TOTAL);
        CancellationToken parent = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public Registration onCancellation(Runnable action) { throw new AssertionError("registration failed"); }
        };
        try (AgentSupervisor supervisor = supervisor((d, r, t) -> { throw new AssertionError(); }, ledger,
                ChildTaskJournal.noop(), Clock.systemUTC())) {
            assertThatThrownBy(() -> supervisor.submit(request("registration"), parent)).isInstanceOf(AssertionError.class);
            assertThat(ledger.remaining()).isEqualTo(TOTAL);
            assertThat(supervisor.find(new ChildTaskId("task-1"))).isEmpty();
        }
    }

    @Test
    void queueRejectionReturnsOnlyRejectedReservationAndUnregistersParent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChildBudgetLedger ledger = new ChildBudgetLedger(TOTAL);
        ChildRuntimeScopeFactory factory = (d, request, token) -> {
            if (request.prompt().equals("first")) { entered.countDown(); waitFor(release); }
            return scope(ignored -> ModelTurn.text("done"));
        };
        try (AgentSupervisor supervisor = supervisor(factory, ledger, ChildTaskJournal.noop(), Clock.systemUTC())) {
            supervisor.submit(request("first"), CancellationToken.none());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            supervisor.submit(request("queued"), CancellationToken.none());
            ChildBudget before = ledger.remaining();
            CountingToken rejected = new CountingToken();
            assertThatThrownBy(() -> supervisor.submit(request("rejected"), rejected)).isInstanceOf(RejectedExecutionException.class);
            assertThat(rejected.registrations).hasValue(0);
            assertThat(ledger.remaining()).isEqualTo(before);
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test
    void closingOrCancellingDuringPreparePreventsWorkerAndReleasesBudget() {
        for (boolean close : List.of(false, true)) {
            CountingToken parent = new CountingToken();
            AtomicReference<AgentSupervisor> owner = new AtomicReference<>();
            ChildBudgetLedger ledger = new ChildBudgetLedger(TOTAL);
            ChildRuntimeScopeFactory factory = new ChildRuntimeScopeFactory() {
                public ChildRuntimeScope create(AgentDefinitionSnapshot d, ChildTaskRequest r, CancellationToken t) { throw new AssertionError(); }
                public PreparedChildRuntime prepare(SessionId s, RunId r, AgentDefinitionSnapshot d,
                        ChildTaskRequest request, CancellationToken t) {
                    if (close) owner.get().close(); else parent.source.cancel();
                    return (effective, startup) -> { throw new AssertionError("not admitted"); };
                }
            };
            try (AgentSupervisor supervisor = supervisor(factory, ledger, ChildTaskJournal.noop(), Clock.systemUTC())) {
                owner.set(supervisor);
                assertThatThrownBy(() -> supervisor.submit(request("race"), parent)).isInstanceOf(RejectedExecutionException.class);
                assertThat(parent.registrations).hasValue(0);
                assertThat(ledger.remaining()).isEqualTo(TOTAL);
            }
        }
    }

    @Test
    void initializerUsesActualRunAndExactCancelDoesNotCancelNextRun() throws Exception {
        AtomicInteger models = new AtomicInteger(), initialized = new AtomicInteger();
        ChildRuntimeScope child = scope(ignored -> { models.incrementAndGet(); return ModelTurn.text("done"); });
        CountingToken startup = new CountingToken();
        ChildRuntimeScopeFactory factory = (d, r, t) -> new ChildRuntimeScope(child.runtime(), child.sessionId(),
                () -> { }, Optional::empty, (session, run) -> {
                    initialized.incrementAndGet();
                    assertThat(session).isEqualTo(child.sessionId());
                    assertThat(child.runtime().cancel(session, new RunId("not-the-run"))).isFalse();
                    assertThat(child.runtime().cancel(session, run)).isTrue();
                }, startup);
        try (child; AgentSupervisor supervisor = supervisor(factory, new ChildBudgetLedger(TOTAL), ChildTaskJournal.noop(), Clock.systemUTC())) {
            assertThat(supervisor.submit(request("cancel"), CancellationToken.none()).await(Duration.ofSeconds(3)).status())
                    .isEqualTo(ChildTaskStatus.CANCELLED);
            assertThat(initialized).hasValue(1);
            assertThat(models).hasValue(0);
            assertThat(startup.registrations).hasValue(0);
            assertThat(child.runtime().run(child.sessionId(), AgentRunRequest.of("next")).stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(models).hasValue(1);
        }
    }

    @Test
    void scopeStartupCancellationStopsBeforeModelWhileRunningRealInitializer() throws Exception {
        CountingToken startup = new CountingToken(); startup.source.cancel();
        AtomicInteger models = new AtomicInteger(), initialized = new AtomicInteger();
        ChildRuntimeScopeFactory factory = (d, r, t) -> {
            ChildRuntimeScope child = scope(ignored -> { models.incrementAndGet(); return ModelTurn.text("unexpected"); });
            return new ChildRuntimeScope(child.runtime(), child.sessionId(), child, Optional::empty,
                    (session, run) -> initialized.incrementAndGet(), startup);
        };
        try (AgentSupervisor supervisor = supervisor(factory, new ChildBudgetLedger(TOTAL), ChildTaskJournal.noop(), Clock.systemUTC())) {
            assertThat(supervisor.submit(request("startup"), CancellationToken.none()).await(Duration.ofSeconds(3)).status())
                    .isEqualTo(ChildTaskStatus.CANCELLED);
            assertThat(initialized).hasValue(1);
            assertThat(models).hasValue(0);
            assertThat(startup.registrations).hasValue(0);
        }
    }

    @Test
    void startHookOnlyChangesEffectivePromptAndTerminalStillPrecedesSlowCleanup() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1), release = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        AtomicReference<ChildTaskRequest> original = new AtomicReference<>(), effective = new AtomicReference<>();
        ChildRuntimeScopeFactory factory = new ChildRuntimeScopeFactory() {
            public ChildRuntimeScope create(AgentDefinitionSnapshot d, ChildTaskRequest r, CancellationToken t) { throw new AssertionError(); }
            public PreparedChildRuntime prepare(SessionId s, RunId r, AgentDefinitionSnapshot d,
                    ChildTaskRequest request, CancellationToken t) {
                original.set(request);
                return (changed, token) -> {
                    effective.set(changed);
                    ChildRuntimeScope child = scope(ignored -> ModelTurn.text("done"));
                    return new ChildRuntimeScope(child.runtime(), child.sessionId(), () -> {
                        cleaning.countDown();
                        try { waitFor(release); child.close(); } finally { cleaned.countDown(); }
                    });
                };
            }
        };
        ChildTaskLifecycle hook = new ChildTaskLifecycle() {
            public Optional<String> beforeStart(ChildTaskRequest r, CancellationToken t) { return Optional.of("hook context"); }
            public Optional<String> afterTerminal(ChildTaskReport report) { return Optional.empty(); }
        };
        try (AgentSupervisor supervisor = supervisor(factory, new ChildBudgetLedger(TOTAL), ChildTaskJournal.noop(), Clock.systemUTC(), hook)) {
            ChildTaskHandle handle = supervisor.submit(request("hook"), CancellationToken.none());
            assertThat(cleaning.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handle.await(Duration.ofMillis(100)).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            assertThat(cleaned.getCount()).isEqualTo(1);
            assertThat(effective.get().prompt()).contains("trust=\"untrusted\"", "hook context");
            ChildTaskRequest actual = effective.get(), captured = original.get();
            assertThat(new ChildTaskRequest(actual.delegationId(), actual.definitionId(), captured.prompt(),
                    actual.requestedTools(), actual.requestedBudget(), actual.background(), actual.depth(),
                    actual.worktree(), actual.taskScope())).isEqualTo(captured);
            release.countDown();
            assertThat(cleaned.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void combinedSecondRegistrationFailureUnregistersFirstInput() {
        CountingToken first = new CountingToken();
        CancellationToken failing = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public Registration onCancellation(Runnable action) { throw new AssertionError("registration rejected"); }
        };
        assertThatThrownBy(() -> AgentSupervisor.combineCancellation(first, failing).onCancellation(() -> { }))
                .isInstanceOf(AssertionError.class);
        assertThat(first.registrations).hasValue(0);
    }

    @Test
    void combinedCancellationNotifiesOnceClosesBothAndUsesMinimumPresentDeadline() {
        CountingToken left = new CountingToken(Duration.ofSeconds(3));
        CountingToken right = new CountingToken(Duration.ofSeconds(1));
        CancellationToken combined = AgentSupervisor.combineCancellation(left, right);
        AtomicInteger calls = new AtomicInteger();
        try (var registration = combined.onCancellation(calls::incrementAndGet)) {
            left.source.cancel(); right.source.cancel();
            assertThat(calls).hasValue(1);
            assertThat(combined.isCancellationRequested()).isTrue();
            assertThat(combined.remainingTime()).contains(Duration.ofSeconds(1));
        }
        assertThat(left.registrations).hasValue(0);
        assertThat(right.registrations).hasValue(0);
        assertThat(AgentSupervisor.combineCancellation(CancellationToken.none(), right).remainingTime()).contains(Duration.ofSeconds(1));
        assertThat(AgentSupervisor.combineCancellation(left, CancellationToken.none()).remainingTime()).contains(Duration.ofSeconds(3));
        assertThat(AgentSupervisor.combineCancellation(CancellationToken.none(), CancellationToken.none()).remainingTime()).isEmpty();
        CountingToken pending = new CountingToken();
        var registration = AgentSupervisor.combineCancellation(pending, CancellationToken.none()).onCancellation(calls::incrementAndGet);
        registration.close(); registration.close(); pending.source.cancel();
        assertThat(calls).hasValue(1);
        assertThat(pending.registrations).hasValue(0);
    }

    private static AgentSupervisor supervisor(ChildRuntimeScopeFactory factory, ChildBudgetLedger ledger,
            ChildTaskJournal journal, Clock clock) {
        return supervisor(factory, ledger, journal, clock, ChildTaskLifecycle.noop());
    }

    private static AgentSupervisor supervisor(ChildRuntimeScopeFactory factory, ChildBudgetLedger ledger,
            ChildTaskJournal journal, Clock clock, ChildTaskLifecycle taskLifecycle) {
        AgentDefinitionCatalog catalog = new AgentDefinitionCatalog() {
            public Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id) { return Optional.of(DEFINITION); }
            public List<AgentDefinitionSnapshot> snapshots() { return List.of(DEFINITION); }
        };
        return new AgentSupervisor(catalog, factory, ledger, AgentDefinitionNarrower.identity(), journal,
                ChildTaskObserver.noop(), taskLifecycle, clock, 1, 1, 2);
    }

    private static ChildTaskRequest request(String prompt) {
        return new ChildTaskRequest(new DelegationId("delegation-" + prompt), DEFINITION.id(), prompt,
                Set.of(), BUDGET, true, 1, false);
    }

    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("fixture interrupted"); }
    }

    private static ChildRuntimeScope scope(ModelGateway gateway) {
        AgentIdGenerator ids = new AgentIdGenerator() {
            private final AtomicInteger next = new AtomicInteger();
            public SessionId newSessionId() { return new SessionId("child-session-" + next.incrementAndGet()); }
            public RunId newRunId() { return new RunId("child-run-" + next.incrementAndGet()); }
        };
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW, PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.deny(), lifecycle);
        AgentRuntime runtime = new AgentRuntime(sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("prepared child"));
        return new ChildRuntimeScope(runtime, session.id(), () -> sessions.close(session.id()));
    }

    private static final class CountingToken implements CancellationToken {
        private final CancellationSource source = new CancellationSource();
        private final AtomicInteger registrations = new AtomicInteger();
        private final Optional<Duration> deadline;
        private CountingToken() { deadline = Optional.empty(); }
        private CountingToken(Duration duration) { deadline = Optional.of(duration); }
        public boolean isCancellationRequested() { return source.token().isCancellationRequested(); }
        public Optional<Duration> remainingTime() { return deadline; }
        public Registration onCancellation(Runnable action) {
            Registration registered = source.token().onCancellation(action);
            registrations.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean();
            return () -> { if (closed.compareAndSet(false, true)) { registered.close(); registrations.decrementAndGet(); } };
        }
    }
}
