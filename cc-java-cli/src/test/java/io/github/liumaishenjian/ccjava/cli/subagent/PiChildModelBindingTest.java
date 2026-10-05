package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.*;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.subagent.PreparedChildRuntime;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.execution.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import io.github.liumaishenjian.ccjava.model.springai.provider.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 真正的child Runtime与read_file Pipeline，模型仍为Fake；不代表Node/账号/终端证据。 */
class PiChildModelBindingTest {
    @TempDir Path home;
    private static final CancellationToken NONE = CancellationToken.none();
    private static final SessionId PARENT = new SessionId("captured-parent");
    private static final RunId PARENT_RUN = new RunId("captured-parent-run");
    private static final Duration DURATION = Duration.ofSeconds(15);
    private static final ChildBudget BUDGET = new ChildBudget(4, 2, 10000, 4096, DURATION);
    private final PiProviderCatalog catalog = new PiProviderCatalog();
    private final PiCredentialIdentity identity = new PiCredentialIdentity("openai",
            PiCredentialIdentity.AuthMethod.API_KEY, "child-fixture");
    private final CredentialLeaseRegistry leases = new CredentialLeaseRegistry();
    private final RunModelSourceRegistry sources = new RunModelSourceRegistry();
    private final List<FakeGateway> gateways = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    private String originalModel() { return catalog.require("openai").models().getFirst().id(); }
    private String otherModel() { return catalog.require("openai").models().get(1).id(); }
    private PiCredentialStore store() { return new PiCredentialStore(home); }
    private void login() {
        try (var material = PiCredentialMaterial.envRef("SYNTHETIC_CHILD_KEY")) {
            store().saveLogin(identity, material, store().snapshot(NONE).generation(), false, NONE);
        }
    }
    private RunScopedModelGateway routed() {
        var pi = PiRouteFixture.create(store(), leases, catalog, model -> {
            FakeGateway fake = new FakeGateway(model); gateways.add(fake); return fake;
        });
        var routes = new SelectedProviderRouteFactory(new ProviderDefinitionStore(home),
                new CredentialResolver(new RestrictedFileCredentialStore(home), Map.of()), leases,
                ProviderGatewayFactoryRegistry.production(), pi);
        return routes.lazyGateway(() -> Optional.of(new ProviderSelectionSnapshot("openai", identity.profileId(),
                originalModel(), "pi", "API_KEY")));
    }
    private AgentDefinitionSnapshot definition(Optional<String> model) {
        return new AgentDefinitionSnapshot(new AgentDefinitionId("fixture"), "fixture reader", "Read independently",
                Set.of("read_file"), PermissionMode.PLAN, model, BUDGET, false, "a".repeat(64), "user");
    }
    private ChildTaskRequest request() {
        return new ChildTaskRequest(new DelegationId("fixture-delegation"), new AgentDefinitionId("fixture"),
                "Read fixture.txt and deliver its marker", Set.of("read_file"), BUDGET, false, 1, false);
    }
    private HeadlessChildRuntimeScopeFactory factory() throws Exception {
        Path workspace = Files.createDirectories(home.resolve("workspace"));
        Files.writeString(workspace.resolve("fixture.txt"), "independent-child-marker\n");
        return new HeadlessChildRuntimeScopeFactory(workspace, home.resolve("sessions"),
                request -> { throw new AssertionError("Child used startup gateway"); },
                (invocation, tool, outcome) -> { throw new AssertionError("Read should not require approval"); },
                new AgentIdGenerator() {
                    public SessionId newSessionId() { return new SessionId("session-child-" + ids.incrementAndGet()); }
                    public RunId newRunId() { return new RunId("run-child-" + ids.incrementAndGet()); }
                }, new LifecycleDispatcher(java.time.Clock.systemUTC(), ignored -> { }), HookCoordinator.disabled(), () -> null,
                ExecutionBackendPreference.LOCAL,
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? ExecutionShell.WINDOWS_PLATFORM : ExecutionShell.POSIX_PLATFORM,
                ignored -> Optional.empty(), sources);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void parentCanEndBeforeIndependentChildRunsRealReadPipeline(boolean override) throws Exception {
        login();
        var factory = factory();
        String expected = override ? otherModel() : originalModel();
        PreparedChildRuntime prepared;
        try (var parent = routed().openRun(DURATION);
             var registration = sources.register(PARENT, PARENT_RUN, parent.binding().orElseThrow(),
                     ContextPreparationService.noop(), Optional.empty())) {
            prepared = factory.prepare(PARENT, PARENT_RUN,
                    definition(override ? Optional.of(expected) : Optional.empty()), request(), NONE);
            assertThat(gateways).hasSize(1);
            assertThat(leases.activeCount(identity)).isEqualTo(1);
        }
        assertThat(leases.activeCount(identity)).isZero();
        try (var child = prepared.create(request(), NONE)) {
            assertThat(leases.activeCount(identity)).isEqualTo(1);
            AgentRunResult result = child.runtime().run(child.sessionId(),
                    new AgentRunRequest(new UserMessage(request().prompt()), new AgentLimits(4, 2, DURATION)),
                    child.initializer(), child.startupCancellation());
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(result.modelTurns()).isEqualTo(2);
            assertThat(result.toolCalls()).isEqualTo(1);
            assertThat(result.finalText()).contains("delivered " + expected);
            assertThat(sources.capture(child.sessionId(), result.runId(), Optional.empty()).modelName()).isEqualTo(expected);
            assertThat(gateways.getLast().requests.getLast().messages()).anySatisfy(message -> {
                assertThat(message).isInstanceOf(ToolResultMessage.class);
                assertThat(((ToolResultMessage) message).result().content()).contains("independent-child-marker");
            });
        }
        assertThat(gateways).hasSize(2);
        assertThat(gateways.getFirst().requests).isEmpty();
        assertThat(gateways).allSatisfy(gateway -> assertThat(gateway.closed).isTrue());
        assertThat(leases.activeCount(identity)).isZero();
    }

    @Test void completedChildWithFailingModelCloseRemainsUnconfirmedAndFenced() throws Exception {
        login(); var factory = factory(); PreparedChildRuntime prepared;
        try (var parent = routed().openRun(DURATION);
             var registration = sources.register(PARENT, PARENT_RUN, parent.binding().orElseThrow(),
                     ContextPreparationService.noop(), Optional.empty())) {
            prepared = factory.prepare(PARENT, PARENT_RUN, definition(Optional.empty()), request(), NONE);
        }
        try (var child = prepared.create(request(), NONE)) {
            gateways.getLast().failClose = true;
            var result = child.runtime().run(child.sessionId(),
                    new AgentRunRequest(new UserMessage(request().prompt()), new AgentLimits(4, 2, DURATION)),
                    child.initializer(), child.startupCancellation());
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            child.close();
            assertThat(child.cleanupStatus().get()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
            assertThat(leases.activeCount(identity)).isEqualTo(1);
            assertThat(leases.fenced(identity)).isTrue();
            assertThatThrownBy(() -> prepared.create(request(), NONE)).isInstanceOf(ProviderAuthException.class);
            assertThat(gateways).hasSize(2);
        }
    }

    @Test void queuedCapturedEpochCannotChangeToNewLogin() throws Exception {
        login(); var factory = factory(); PreparedChildRuntime prepared;
        try (var parent = routed().openRun(DURATION);
             var registration = sources.register(PARENT, PARENT_RUN, parent.binding().orElseThrow(),
                     ContextPreparationService.noop(), Optional.empty())) {
            prepared = factory.prepare(PARENT, PARENT_RUN, definition(Optional.empty()), request(), NONE);
        }
        login();
        assertThatThrownBy(() -> prepared.create(request(), NONE)).isInstanceOf(ProviderAuthException.class);
        assertThat(gateways).hasSize(1);
        assertThat(leases.activeCount(identity)).isZero();
    }

    @Test void childContextUsesOwnSummaryAndNeverParentObserverOrPendingContext() {
        login();
        var observer = new AtomicInteger();
        var parentContext = new ContextPreparationService(new ContextPreparationConfig(
                new ContextCapacity("original", 130, 20, 10), 40, 1, 64, 32),
                (request, token) -> { throw new AssertionError("Child called parent summary"); },
                ignored -> observer.incrementAndGet());
        RunModelSourceRegistry.Prepared prepared;
        try (var parent = routed().openRun(DURATION);
             var registration = sources.register(PARENT, PARENT_RUN, parent.binding().orElseThrow(),
                     parentContext, Optional.empty())) {
            parentContext.recordExternalContext(PARENT, "parent-only-private");
            prepared = sources.capture(PARENT, PARENT_RUN, Optional.empty());
        }
        var messages = List.<AgentMessage>of(new SystemMessage("s"), new UserMessage("a".repeat(80)),
                AssistantMessage.text("b".repeat(80)), new UserMessage("tail"));
        try (var child = ChildModelRun.open(sources, prepared, DURATION, NONE)) {
            var projection = child.context().prepare(new ModelRequest(PARENT, PARENT_RUN, 1, messages, List.of()), NONE);
            assertThat(projection.messages().stream().filter(ContextSummaryMessage.class::isInstance)
                    .map(ContextSummaryMessage.class::cast).map(ContextSummaryMessage::content))
                    .containsExactly("child-summary");
            assertThat(projection.messages().toString()).doesNotContain("parent-only-private");
            assertThat(gateways.getLast().summaries).hasValue(1);
            assertThat(observer).hasValue(0);
        }
        assertThat(leases.activeCount(identity)).isZero();
    }

    private static final class FakeGateway implements ModelGateway, ContextSummarizer, AutoCloseable {
        private final String model;
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger summaries = new AtomicInteger();
        private volatile boolean closed;
        private volatile boolean failClose;
        private FakeGateway(String model) { this.model = model; }
        @Override public ModelTurn complete(ModelRequest request) {
            assertThat(closed).isFalse(); requests.add(request);
            if (requests.size() == 1) return ModelTurn.tools(List.of(
                    new ToolCall("child-read", "read_file", new JsonObject(Map.of("path", "fixture.txt")))));
            return ModelTurn.text("delivered " + model);
        }
        @Override public Optional<SummaryCandidate> summarize(SummaryRequest request, CancellationToken token) {
            assertThat(closed).isFalse(); summaries.incrementAndGet();
            return Optional.of(new SummaryCandidate(request.tier(), "child-summary", request.sourceRevision(),
                    request.sourceMessageIds(), 13, 13));
        }
        @Override public void close() {
            closed = true;
            if (failClose) throw new IllegalStateException("synthetic private cleanup detail");
        }
    }
}
