package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.*;

import io.github.liumaishenjian.ccjava.core.hook.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.hook.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** S15 / SUB-08、MODEL-13：真实 Runtime 在 USER_PROMPT 前绑定启动取消，保持原有生命周期。 */
class RuntimeStartupCancellationTest {
    @Test
    void startupRemainingBudgetIsVisibleToPromptInsteadOfResetToRunDefault() {
        var observed = new AtomicReference<Duration>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var coordinator = hooks(executor, (invocation, token) -> {
                observed.set(token.remainingTime().orElseThrow());
                return HookExecutionResult.continued("prompt");
            });
            var harness = harness(coordinator, SessionJournal.noop());
            var result = harness.runtime.run(harness.session.id(), AgentRunRequest.of("short budget"),
                    RunInitializer.noop(), remaining(Duration.ofSeconds(2)));
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(observed.get()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        }
    }

    @Test
    void longerStartupBudgetCannotExpandExplicitRunLimit() {
        var observed = new AtomicReference<Duration>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var coordinator = hooks(executor, (invocation, token) -> {
                observed.set(token.remainingTime().orElseThrow());
                return HookExecutionResult.continued("prompt");
            });
            var harness = harness(coordinator, SessionJournal.noop());
            var request = new AgentRunRequest(new UserMessage("keep tighter limit"),
                    new AgentLimits(1, 0, Duration.ofSeconds(1)));
            var result = harness.runtime.run(harness.session.id(), request,
                    RunInitializer.noop(), remaining(Duration.ofSeconds(5)));
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(observed.get()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    void exhaustedStartupBudgetCannotReachFirstModelEvenWithoutCancellationNotification() {
        var harness = harness(HookCoordinator.disabled(), SessionJournal.noop());
        var result = harness.runtime.run(harness.session.id(), AgentRunRequest.of("expired"),
                RunInitializer.noop(), remaining(Duration.ZERO));
        assertThat(result.stopReason()).isEqualTo(StopReason.USER_CANCELLED);
        assertThat(harness.models).hasValue(0);
    }

    @Test
    void runtimeEnforcesShorterStartupDeadlineWithoutAnExternalTimer() {
        var observed = new AtomicReference<Duration>();
        StreamingModelGateway gateway = (request, observer, token) -> {
            observed.set(token.remainingTime().orElseThrow());
            var cancelled = new CountDownLatch(1);
            try (var registration = token.onCancellation(cancelled::countDown)) {
                try {
                    if (!cancelled.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("deadline not enforced");
                } catch (InterruptedException expectedCancellation) {
                    Thread.currentThread().interrupt();
                }
            }
            return ModelTurn.text("late body must not be delivered");
        };
        var harness = harness(HookCoordinator.disabled(), SessionJournal.noop(), gateway);
        var result = harness.runtime.run(harness.session.id(), AgentRunRequest.of("bound deadline"),
                RunInitializer.noop(), remaining(Duration.ofSeconds(1)));
        assertThat(observed.get()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(result.stopReason()).isEqualTo(StopReason.TIME_LIMIT_REACHED);
        assertThat(result.finalText()).isEmpty();
    }

    private static CancellationToken remaining(Duration duration) {
        return new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public Optional<Duration> remainingTime() { return Optional.of(duration); }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
    }

    @Test
    void alreadyCancelledStartupPreventsPromptHandlerAndFirstModel() {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            AtomicInteger promptCalls = new AtomicInteger();
            HookCoordinator hooks = hooks(executor, (invocation, token) -> {
                promptCalls.incrementAndGet(); return HookExecutionResult.continued("prompt");
            });
            Harness harness = harness(hooks, SessionJournal.noop());
            CountingToken startup = new CountingToken(); startup.source.cancel();
            AgentRunResult result = harness.runtime.run(harness.session.id(), AgentRunRequest.of("cancelled"),
                    RunInitializer.noop(), startup);
            assertThat(result.stopReason()).isEqualTo(StopReason.USER_CANCELLED);
            assertThat(promptCalls).hasValue(0);
            assertThat(harness.models).hasValue(0);
            assertThat(startup.registrations).hasValue(0);
        }
    }

    @Test
    void cancellationDuringPromptIsVisibleBeforeInitializerAndNoModelRuns() throws Exception {
        try (ExecutorService hooksExecutor = Executors.newSingleThreadExecutor();
                ExecutorService runExecutor = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1), cancellationObserved = new CountDownLatch(1);
            AtomicReference<CancellationToken> observed = new AtomicReference<>();
            CountingToken startup = new CountingToken();
            HookCoordinator hooks = hooks(hooksExecutor, (invocation, token) -> {
                observed.set(token);
                try (var subscription = token.onCancellation(cancellationObserved::countDown)) {
                    entered.countDown();
                    if (!cancellationObserved.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("cancel not propagated");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted");
                }
                return HookExecutionResult.continued("prompt");
            });
            Harness harness = harness(hooks, SessionJournal.noop());
            AtomicInteger initialized = new AtomicInteger();
            Future<AgentRunResult> running = runExecutor.submit(() -> harness.runtime.run(harness.session.id(),
                    AgentRunRequest.of("cancel during prompt"), (session, run) -> initialized.incrementAndGet(), startup));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            startup.source.cancel();
            assertThat(cancellationObserved.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get().isCancellationRequested()).isTrue();
            assertThat(running.get(3, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.USER_CANCELLED);
            assertThat(initialized).hasValue(1);
            assertThat(harness.models).hasValue(0);
            assertThat(startup.registrations).hasValue(0);
        }
    }

    @Test
    void promptBlockStillReturnsHookBlockedBeforeJournalAndUnregistersStartup() {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            HookCoordinator hooks = hooks(executor, (invocation, token) -> new HookExecutionResult("prompt",
                    HookDisposition.BLOCK, HookExecutionStatus.COMPLETED, Optional.empty(), Optional.empty()));
            Harness harness = harness(hooks, SessionJournal.noop());
            CountingToken startup = new CountingToken();
            AgentRunResult result = harness.runtime.run(harness.session.id(), AgentRunRequest.of("blocked"),
                    (session, run) -> { throw new AssertionError("initializer after blocked prompt"); }, startup);
            assertThat(result.stopReason()).isEqualTo(StopReason.HOOK_BLOCKED);
            assertThat(harness.session.messages()).isEmpty();
            assertThat(harness.models).hasValue(0);
            assertThat(startup.registrations).hasValue(0);
        }
    }

    @Test
    void journalFailureAndErrorAlwaysUnregisterStartup() {
        for (boolean error : List.of(false, true)) {
            SessionJournal failing = new SessionJournal() {
                public void runStarted(SessionId session, RunId run, UserMessage message) {
                    if (error) throw new AssertionError("journal"); throw new IllegalStateException("journal");
                }
                public void assistantAppended(SessionId s, RunId r, AssistantMessage m) { }
                public void toolResolved(SessionId s, RunId r, int ordinal, ToolResult result, ToolResolutionReason reason) { }
                public void toolStarted(SessionId s, RunId r, int ordinal, String id, String name, ToolEffect effect) { }
                public void toolCompleted(SessionId s, RunId r, int ordinal, ToolResult result) { }
                public void runCompleted(SessionId s, RunId r, StopReason reason) { }
            };
            Harness harness = harness(HookCoordinator.disabled(), failing);
            CountingToken startup = new CountingToken();
            assertThatThrownBy(() -> harness.runtime.run(harness.session.id(), AgentRunRequest.of("journal failure"),
                    RunInitializer.noop(), startup)).isInstanceOf(error ? AssertionError.class : IllegalStateException.class);
            assertThat(startup.registrations).hasValue(0);
            assertThat(harness.models).hasValue(0);
        }
    }

    @Test
    void initializationFailurePreservesInternalErrorAndOrdinaryNextRunWorks() {
        Harness harness = harness(HookCoordinator.disabled(), SessionJournal.noop());
        CountingToken startup = new CountingToken();
        AgentRunResult failed = harness.runtime.run(harness.session.id(), AgentRunRequest.of("init"),
                (session, run) -> { throw new IllegalStateException("init"); }, startup);
        assertThat(failed.stopReason()).isEqualTo(StopReason.INTERNAL_ERROR);
        assertThat(startup.registrations).hasValue(0);
        assertThat(harness.models).hasValue(0);
        startup.source.cancel();
        AgentRunResult next = harness.runtime.run(harness.session.id(), AgentRunRequest.of("ordinary"));
        assertThat(next.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(next.runId()).isNotEqualTo(failed.runId());
        assertThat(harness.models).hasValue(1);
    }

    private static HookCoordinator hooks(ExecutorService executor, HookHandler handler) {
        return new HookCoordinator(List.of(new HookBinding("prompt", HookMatcher.event(HookEventKind.USER_PROMPT),
                handler, HookFailurePolicy.FAIL_OPEN, true, 0)), executor, Duration.ofSeconds(3));
    }

    private static Harness harness(HookCoordinator hooks, SessionJournal journal) {
        return harness(hooks, journal, null);
    }

    private static Harness harness(HookCoordinator hooks, SessionJournal journal, ModelGateway override) {
        AgentIdGenerator ids = new AgentIdGenerator() {
            private final AtomicInteger next = new AtomicInteger();
            public SessionId newSessionId() { return new SessionId("startup-session-" + next.incrementAndGet()); }
            public RunId newRunId() { return new RunId("startup-run-" + next.incrementAndGet()); }
        };
        AtomicInteger models = new AtomicInteger();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
        InMemorySessionStore store = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW, PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.deny(), lifecycle);
        AgentRuntime runtime = new AgentRuntime(store, ids,
                override == null ? request -> { models.incrementAndGet(); return ModelTurn.text("done"); } : override,
                new DefaultContextAssembler(), registry, pipeline, lifecycle, journal,
                ContextPreparationService.noop(), MemoryContextService.noop(),
                io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService.noop(), hooks);
        return new Harness(runtime, store.create(SessionSpec.of("startup cancellation")), models);
    }

    private record Harness(AgentRuntime runtime, AgentSession session, AtomicInteger models) { }

    private static final class CountingToken implements CancellationToken {
        private final CancellationSource source = new CancellationSource();
        private final AtomicInteger registrations = new AtomicInteger();
        public boolean isCancellationRequested() { return source.token().isCancellationRequested(); }
        public Registration onCancellation(Runnable action) {
            Registration upstream = source.token().onCancellation(action);
            registrations.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean();
            return () -> { if (closed.compareAndSet(false, true)) { upstream.close(); registrations.decrementAndGet(); } };
        }
    }
}
