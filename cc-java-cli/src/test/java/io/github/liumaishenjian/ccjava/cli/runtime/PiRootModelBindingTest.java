package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.cli.provider.RunModelSourceRegistry;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-100 Root 装配回归：同步 facade 是失败探针，不是另一个可用模型。
 * 使用真实 Runtime worker、持久 Session 与 Pipeline；不代表真实 Pi 账号或终端验收。
 */
class PiRootModelBindingTest {
    @TempDir Path temporary;

    @Test
    void declaredBindingCannotSilentlyBecomeLegacyWhenMissing() throws Exception {
        var facade = new BoundOnlyGateway();
        facade.missingBinding = true;
        try (var runtime = session(facade, Duration.ofSeconds(5), false)) {
            runtime.open();
            assertThatThrownBy(() -> runtime.run("reject missing binding"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Declared model binding unavailable");
            assertThat(runtime.hasActiveRun()).isFalse();
            assertThat(facade.requests).isEmpty();
        }
        assertThat(facade.closes).hasValue(1);
    }

    @Test
    void ordinaryRunUsesBoundWorkerAndRegistersOnlyRealIdentity() throws Exception {
        var facade = new BoundOnlyGateway();
        var owner = new AtomicReference<HeadlessRuntimeSession>();
        var caller = Thread.currentThread();
        facade.model = request -> {
            assertThat(Thread.currentThread()).isNotSameAs(caller);
            var boundScope = (HeadlessRuntimeScope) field(field(owner.get(), "activeRun"), "scope");
            assertThat(boundScope.configuration().modelName()).contains("bound-model");
            assertThat(boundScope.configuration().permissionRules())
                    .isEqualTo(owner.get().runtimeConfiguration().permissionRules());
            assertThat(boundScope.configuration().enabledBuiltinTools())
                    .isEqualTo(owner.get().runtimeConfiguration().enabledBuiltinTools());
            var registry = registry(owner.get());
            assertThat(registry.capture(request.sessionId(), request.runId(), Optional.empty()).modelName())
                    .isEqualTo("bound-model");
            return ModelTurn.text("bound delivery");
        };
        try (var runtime = session(facade, Duration.ofSeconds(5), false)) {
            owner.set(runtime);
            var id = runtime.open();
            var result = runtime.run("ordinary");
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(result.finalText()).contains("bound delivery");
            assertThat(result.sessionId()).isEqualTo(id);
            assertThatThrownBy(() -> registry(runtime).capture(id, result.runId(), Optional.empty()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(runtime.hasActiveRun()).isFalse();
        }
        assertThat(facade.closes).hasValue(1);
    }

    @Test
    void planningAndAcceptedExecutionDeliverUsingDistinctBindings() throws Exception {
        var facade = new BoundOnlyGateway();
        var calls = new AtomicInteger();
        var owner = new AtomicReference<HeadlessRuntimeSession>();
        facade.model = request -> {
            assertThat(registry(owner.get()).capture(request.sessionId(), request.runId(), Optional.empty())
                    .modelName()).isEqualTo("bound-model");
            return switch (calls.getAndIncrement()) {
                case 0 -> tool("draft", "revise_plan_artifact", Map.of("markdown", "# 查询\n\n批准后执行只读命令并交付正文。"));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence", new JsonObject(Map.of(
                        "requirementId", "check", "kind", "VERIFICATION", "locator", "run_command",
                        "label", "真实命令完成", "required", true))),
                        new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 2 -> ModelTurn.text("等待审核");
                case 3 -> tool("query", "run_command", Map.of("command", "[Console]::Out.WriteLine('bound-result'); exit 0", "timeoutSeconds", 10));
                default -> ModelTurn.text("bound-result 已交付");
            };
        };
        try (var runtime = session(facade, Duration.ofSeconds(10), false)) {
            owner.set(runtime);
            runtime.open();
            var planned = runtime.runPlan("查询并回答");
            assertThat(planned.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(planned.finalText()).contains("等待审核");
            var artifact = runtime.planArtifact().orElseThrow();
            var accepted = runtime.acceptPlanExecution(artifact.planId(), artifact.revision(), artifact.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            var result = runtime.runAcceptedPlan(accepted);
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(result.finalText()).contains("bound-result 已交付");
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.COMPLETED);
            assertThatThrownBy(() -> registry(runtime).capture(result.sessionId(), result.runId(), Optional.empty()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(facade.opens).hasValue(2);
        assertThat(facade.closes).hasValue(2);
    }

    @Test
    void reviewerUsesSameBindingAndRealPipeline() throws Exception {
        var facade = new BoundOnlyGateway();
        var reviews = new AtomicInteger();
        var turns = new AtomicInteger();
        facade.model = request -> {
            if (request.toolDefinitions().isEmpty()) {
                reviews.incrementAndGet();
                return ModelTurn.text("{\"verdict\":\"ALLOW_ONCE\"}");
            }
            return switch (turns.getAndIncrement()) {
                case 0 -> tool("read", "read_file", Map.of("path", "sample.txt"));
                case 1 -> tool("patch", "apply_patch", Map.of("path", "sample.txt", "oldText", "old", "newText", "new"));
                default -> ModelTurn.text("patched through bound reviewer");
            };
        };
        try (var runtime = session(facade, Duration.ofSeconds(5), false)) {
            Files.writeString(temporary.resolve("workspace/sample.txt"), "old\n");
            runtime.open();
            var current = runtime.runtimeConfiguration();
            assertThat(runtime.replaceRuntimeConfiguration(new RuntimeConfiguration(current.modelName(),
                    current.permissionMode(), ApprovalReviewer.AUTO_REVIEW, current.permissionRules(),
                    current.enabledBuiltinTools(), current.toolConfigurations(), current.compactAnchors(),
                    current.diagnosticsVerbosity()))).isTrue();
            assertThat(runtime.run("patch").finalText()).contains("patched through bound reviewer");
        }
        assertThat(reviews).hasValue(1);
        assertThat(Files.readString(temporary.resolve("workspace/sample.txt"))).isEqualTo("new\n");
    }

    @Test
    void exactBoundRuntimeReceivesCancellationAndRootGateRemainsClosed() throws Exception {
        var facade = new BoundOnlyGateway();
        var entered = new CountDownLatch(1);
        var result = new AtomicReference<AgentRunResult>();
        facade.model = request -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            return ModelTurn.text("late success must be rejected");
        };
        try (var runtime = session(facade, Duration.ofSeconds(5), false)) {
            runtime.open();
            Thread runner = Thread.ofPlatform().start(() -> result.set(runtime.run("wait")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> runtime.run("overlap")).isInstanceOf(IllegalStateException.class);
            facade.cancellation.get().run();
            runner.join(5_000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(result.get().stopReason()).isEqualTo(StopReason.USER_CANCELLED);
            assertThat(result.get().finalText()).isEmpty();
            facade.model = request -> ModelTurn.text("next run");
            assertThat(runtime.run("continue").finalText()).contains("next run");
        }
        assertThat(facade.closes).hasValue(2);
    }

    @Test
    void revocationBeforeRuntimeStartupIsNotLost() throws Exception {
        var facade = new BoundOnlyGateway();
        facade.revokeOnBind = true;
        try (var runtime = session(facade, Duration.ofSeconds(5), false)) {
            runtime.open();
            assertThat(runtime.run("already revoked").stopReason()).isEqualTo(StopReason.USER_CANCELLED);
            assertThat(facade.requests).isEmpty();
            assertThat(runtime.hasActiveRun()).isFalse();
            facade.revokeOnBind = false;
            assertThat(runtime.run("next binding").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
        assertThat(facade.closes).hasValue(2);
    }

    @Test
    void rebindConstructionFailureClosesScopeAndReleasesRootOwnership() throws Exception {
        var facade = new BoundOnlyGateway();
        facade.window = OptionalLong.of(1);
        try (var runtime = session(facade, Duration.ofSeconds(5), true)) {
            runtime.open();
            assertThatThrownBy(() -> runtime.run("invalid window")).isInstanceOf(IllegalArgumentException.class);
            assertThat(facade.closes).hasValue(1);
            assertThat(runtime.hasActiveRun()).isFalse();
            facade.window = OptionalLong.empty();
            assertThat(runtime.run("retry after construction failure").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
    }

    @Test
    void boundCompactHasNoRunAndProjectionAndLateExternalContextSurviveRebinding() throws Exception {
        var facade = new BoundOnlyGateway();
        try (var runtime = session(facade, Duration.ofSeconds(5), true)) {
            runtime.open();
            runtime.run("history " + "x".repeat(100));
            runtime.run("more " + "y".repeat(100));
            var requestsBefore = facade.requests.size();
            var journal = journal();
            var before = Files.readAllBytes(journal);
            assertThat(runtime.compactForNextRun(List.of(), CancellationToken.none()))
                    .isEqualTo(HeadlessRuntimeSession.CompactResult.ADOPTED);
            assertThat(facade.requests).hasSize(requestsBefore);
            assertThat(Files.readAllBytes(journal)).isEqualTo(before);
            preparation(runtime).recordExternalContext(runtime.sessionId(), "late external context marker");
            runtime.run("next input");
            assertThat(facade.requests.getLast().messages()).anyMatch(ContextSummaryMessage.class::isInstance);
            assertThat(facade.requests.getLast().messages().toString()).contains("late external context marker");
            assertThat(facade.summaryCalls).hasValue(1);
        }
        assertThat(facade.opens.get()).isEqualTo(facade.closes.get());
    }

    @Test
    void compactUsesBoundC3ThenC4WithoutConsultingFacade() throws Exception {
        var facade = new BoundOnlyGateway();
        var tiers = new CopyOnWriteArrayList<SummaryTier>();
        facade.summary = (request, token) -> {
            tiers.add(request.tier());
            return request.tier() == SummaryTier.C3_ROLLING ? Optional.empty() : shortSummary(request, token);
        };
        try (var runtime = session(facade, Duration.ofSeconds(5), true)) {
            runtime.open();
            runtime.run("history " + "x".repeat(100));
            runtime.run("more " + "y".repeat(100));
            assertThat(runtime.compactForNextRun(List.of(), CancellationToken.none()))
                    .isEqualTo(HeadlessRuntimeSession.CompactResult.ADOPTED);
            assertThat(tiers).containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
        }
    }

    @Test
    void overflowRecoveryUsesTheBoundSummaryAndPreservesSameRunIdentity() throws Exception {
        var facade = new BoundOnlyGateway();
        var attempts = new AtomicInteger();
        try (var runtime = session(facade, Duration.ofSeconds(5), true)) {
            runtime.open();
            runtime.run("history " + "x".repeat(100));
            facade.model = request -> {
                if (attempts.getAndIncrement() == 0) {
                    throw new ModelGatewayException(ModelGatewayException.FailureKind.CONTEXT_OVERFLOW, "fixture overflow");
                }
                assertThat(request.messages()).anyMatch(ContextSummaryMessage.class::isInstance);
                return ModelTurn.text("recovered bound delivery");
            };
            var result = runtime.run("recover");
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(result.finalText()).contains("recovered bound delivery");
            assertThat(facade.requests.subList(1, facade.requests.size()))
                    .allSatisfy(request -> assertThat(request.runId()).isEqualTo(result.runId()));
            assertThat(facade.summaryCalls).hasValue(1);
        }
    }

    @Test
    void compactDeadlineCancelsBoundSummaryAndDoesNotCommit() throws Exception {
        var facade = new BoundOnlyGateway();
        facade.summary = (request, token) -> {
            assertThat(token.remainingTime()).isPresent();
            CountDownLatch cancelled = new CountDownLatch(1);
            try (var registration = token.onCancellation(cancelled::countDown)) {
                try { assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                assertThat(token.isCancellationRequested()).isTrue();
                return shortSummary(request, token);
            }
        };
        try (var runtime = session(facade, Duration.ofMillis(150), true)) {
            runtime.open();
            runtime.run("history " + "x".repeat(100));
            var before = Files.readAllBytes(journal());
            assertThat(runtime.compactForNextRun(List.of(), CancellationToken.none()))
                    .isEqualTo(HeadlessRuntimeSession.CompactResult.CANCELLED);
            assertThat(Files.readAllBytes(journal())).isEqualTo(before);
            runtime.run("after deadline");
            assertThat(facade.requests.getLast().messages()).noneMatch(ContextSummaryMessage.class::isInstance);
        }
        assertThat(facade.opens.get()).isEqualTo(facade.closes.get());
    }

    @Test
    void compactDeadlineIncludesOpeningAndStopsBeforeSummary() throws Exception {
        var facade = new BoundOnlyGateway();
        try (var runtime = session(facade, Duration.ofMillis(150), true)) {
            runtime.open(); runtime.run("history " + "x".repeat(100));
            facade.startup = token -> {
                var cancelled = new CountDownLatch(1);
                assertThat(token.remainingTime()).isPresent();
                try (var registration = token.onCancellation(cancelled::countDown)) {
                    try { assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
            };
            assertThat(runtime.compactForNextRun(List.of(), CancellationToken.none()))
                    .isEqualTo(HeadlessRuntimeSession.CompactResult.CANCELLED);
            assertThat(facade.summaryCalls).hasValue(0);
            assertThat(facade.opens.get()).isEqualTo(facade.closes.get());
        }
    }

    @Test
    void compactRejectsCandidateWhenCanonicalChangedWhileSummarizing() throws Exception {
        var facade = new BoundOnlyGateway();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var result = new AtomicReference<HeadlessRuntimeSession.CompactResult>();
        facade.summary = (request, token) -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            return shortSummary(request, token);
        };
        try (var runtime = session(facade, Duration.ofSeconds(5), true)) {
            runtime.open(); runtime.run("history " + "x".repeat(100));
            Thread compactor = Thread.ofPlatform().start(() -> result.set(runtime.compactForNextRun(List.of(), CancellationToken.none())));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                runtime.run("canonical changed");
            } finally { release.countDown(); }
            compactor.join(5_000);
            assertThat(compactor.isAlive()).isFalse();
            assertThat(result.get()).isEqualTo(HeadlessRuntimeSession.CompactResult.STALE);
        }
    }

    @Test
    void failedScopeCloseStillUnregistersActualRunAndReleasesRoot() throws Exception {
        var facade = new BoundOnlyGateway();
        var last = new AtomicReference<ModelRequest>();
        facade.model = request -> { last.set(request); return ModelTurn.text("done"); };
        facade.failClose = true;
        try (var runtime = session(facade, Duration.ofSeconds(5), false)) {
            runtime.open();
            assertThatThrownBy(() -> runtime.run("close failure")).isInstanceOf(IllegalStateException.class);
            assertThat(runtime.hasActiveRun()).isFalse();
            var request = last.get();
            assertThatThrownBy(() -> registry(runtime).capture(request.sessionId(), request.runId(), Optional.empty()))
                    .isInstanceOf(IllegalStateException.class);
            facade.failClose = false;
            assertThat(runtime.run("next").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
    }

    @Test
    void deadlineSharesStartupCancellationAndUnregistersAtClose() throws Exception {
        var caller = new CancellationSource();
        try (var deadline = new CompactDeadline(Duration.ofHours(1), caller.token())) {
            assertThat(deadline.token().remainingTime().orElseThrow()).isLessThanOrEqualTo(Duration.ofSeconds(300));
            var notified = new AtomicInteger();
            try (var registration = deadline.token().onCancellation(notified::incrementAndGet)) {
                caller.cancel();
                assertThat(deadline.token().isCancellationRequested()).isTrue();
                assertThat(notified).hasValue(1);
            }
        }
        var later = new CancellationSource();
        var deadline = new CompactDeadline(Duration.ofSeconds(5), later.token());
        var notified = new AtomicInteger();
        deadline.token().onCancellation(notified::incrementAndGet);
        deadline.close();
        later.cancel();
        assertThat(notified).hasValue(0);
    }

    private HeadlessRuntimeSession session(BoundOnlyGateway gateway, Duration timeout, boolean context) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        var config = context ? Optional.of(new ContextPreparationConfig(new ContextCapacity("fake-model", 4000, 100, 100),
                200, 0, 1024, 256)) : Optional.<ContextPreparationConfig>empty();
        var options = new HeadlessRuntimeOptions(workspace, "fake-model", timeout, PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), temporary.resolve("sessions"), config,
                ModelDiagnosticMode.OFF, Optional.empty(),
                io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference.LOCAL,
                io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell.WINDOWS_PLATFORM);
        return new HeadlessRuntimeSession(gateway, AgentEventSink.noop(), options,
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce(), gateway);
    }

    private Path journal() throws Exception {
        try (var paths = Files.walk(temporary.resolve("sessions"))) {
            return paths.filter(path -> path.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
    }

    private static RunModelSourceRegistry registry(HeadlessRuntimeSession session) {
        return (RunModelSourceRegistry) field(session, "modelSources");
    }

    private static ContextPreparationService preparation(HeadlessRuntimeSession session) {
        return (ContextPreparationService) field(session, "contextPreparation");
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static ModelTurn tool(String id, String name, Map<String, Object> arguments) {
        return ModelTurn.tools(List.of(new ToolCall(id, name, new JsonObject(arguments))));
    }

    private static Optional<SummaryCandidate> shortSummary(SummaryRequest request, CancellationToken token) {
        return Optional.of(new SummaryCandidate(request.tier(), "short", request.sourceRevision(),
                request.sourceMessageIds(), 5, 1));
    }

    /** Fake facade 故意不可调用，binding 端口才代表真实已开启模型。 */
    private static final class BoundOnlyGateway implements RunScopedModelGateway, ContextSummarizer {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger summaryCalls = new AtomicInteger();
        private final AtomicReference<Runnable> cancellation = new AtomicReference<>();
        private final CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        private volatile ModelGateway model = request -> ModelTurn.text("done");
        private volatile ContextSummarizer summary = PiRootModelBindingTest::shortSummary;
        private volatile OptionalLong window = OptionalLong.empty();
        private volatile boolean failClose;
        private volatile boolean missingBinding;
        @Override public boolean providesRunBindings() { return true; }
        private volatile boolean revokeOnBind;
        private volatile java.util.function.Consumer<CancellationToken> startup = token -> { };

        @Override public ModelTurn complete(ModelRequest request) { throw new AssertionError("Root called facade.complete"); }
        @Override public Optional<SummaryCandidate> summarize(SummaryRequest request, CancellationToken token) {
            throw new AssertionError("Root called facade.summarize");
        }
        @Override public RunScope openRun(CancellationToken token) {
            startup.accept(token);
            return openRun();
        }
        @Override public RunScope openRun(Duration budget, CancellationToken token) {
            return openRun(token);
        }
        @Override public RunScope openRun() {
            opens.incrementAndGet();
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            ModelGateway boundModel = request -> {
                assertThat(closed).isFalse();
                requests.add(request);
                return model.complete(request);
            };
            ContextSummarizer boundSummary = (request, token) -> {
                assertThat(closed).isFalse();
                summaryCalls.incrementAndGet();
                return summary.summarize(request, token);
            };
            // 本批只验证来源登记，不借 Fake 子来源伪造真实 child 消费完成。
            CapturedRunSource source = (override, budget) -> { throw new AssertionError("Child execution not part of Root fixture"); };
            var binding = new RunModelBinding(boundModel, boundSummary, source,
                    Optional.of(new ProviderSelectionSnapshot("openai", "default", "bound-model", "pi", "API_KEY")), window);
            return new RunScope() {
                @Override public Optional<RunModelBinding> binding() {
                    return missingBinding ? Optional.empty() : Optional.of(binding);
                }
                @Override public void bindCancellation(Runnable action) {
                    cancellation.set(action);
                    if (revokeOnBind) action.run();
                }
                @Override public void close() {
                    if (closed.compareAndSet(false, true)) {
                        closes.incrementAndGet();
                        if (failClose) throw new IllegalStateException("fixture close failure");
                    }
                }
            };
        }
    }
}
