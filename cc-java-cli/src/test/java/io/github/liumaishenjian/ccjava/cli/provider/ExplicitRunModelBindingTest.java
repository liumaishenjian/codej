package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.pi.gateway.PiCredentialSessionFactory;
import io.github.liumaishenjian.ccjava.model.springai.provider.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** ADR-100 显式绑定的确定性边缘测试；仅本地合成元数据和 Fake，不启动 Node 或访问账号。 */
class ExplicitRunModelBindingTest {
    @TempDir Path home;
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final CancellationToken NONE = CancellationToken.none();
    private static final PiCredentialIdentity ID = new PiCredentialIdentity("openai",
            PiCredentialIdentity.AuthMethod.API_KEY, "default");
    private final CredentialLeaseRegistry leases = new CredentialLeaseRegistry();
    private final PiProviderCatalog catalog = new PiProviderCatalog();
    private final List<FakeGateway> created = new CopyOnWriteArrayList<>();
    private final AtomicInteger secretReads = new AtomicInteger();

    private ProviderSelectionSnapshot selected() {
        return new ProviderSelectionSnapshot(ID.providerId(), ID.profileId(),
                catalog.require(ID.providerId()).models().getFirst().id(), "pi", "API_KEY");
    }
    private PiCredentialStore store() { return new PiCredentialStore(home); }
    private long login() {
        var store = store();
        try (var material = PiCredentialMaterial.envRef("SYNTHETIC_BINDING_KEY")) {
            return store.saveLogin(ID, material, store.snapshot(NONE).generation(), false, NONE).authEpoch();
        }
    }
    private SelectedProviderRouteFactory routes() {
        var pi = new PiSelectedProviderRouteFactory(store(), leases, catalog, () -> null, name -> {
            secretReads.incrementAndGet();
            throw new AssertionError("capture must not resolve a secret");
        }, (configuration, provider, model, timeout, sessions) -> {
            var result = new FakeGateway(model, sessions);
            created.add(result);
            return result;
        });
        return new SelectedProviderRouteFactory(new ProviderDefinitionStore(home),
                new CredentialResolver(new RestrictedFileCredentialStore(home), Map.of()), leases,
                ProviderGatewayFactoryRegistry.production(), pi);
    }
    private RunScopedModelGateway gateway() { return routes().lazyGateway(() -> Optional.of(selected())); }
    private static RunModelBinding binding(RunScopedModelGateway.RunScope scope) {
        assertThat(scope.binding()).isPresent();
        return scope.binding().orElseThrow();
    }
    private static ModelRequest request() {
        return new ModelRequest(new SessionId("binding-session"), new RunId("binding-run"), 1, List.of(), List.of());
    }

    @Test void selectionReceivesStartupTokenAndLateCancellationCannotBecomeStartupFallback() throws Exception {
        var cancellation = new CancellationSource();
        var gateway = routes().lazyGatewayWithCancellation(token -> {
            assertThat(token).isSameAs(cancellation.token());
            cancellation.cancel();
            return Optional.empty();
        }, request -> { throw new AssertionError("Cancelled selection used legacy"); },
                (request, token) -> { throw new AssertionError("Cancelled selection used legacy summary"); });
        assertThat(gateway.providesRunBindings()).isTrue();
        try (var scope = gateway.openRun(BUDGET, cancellation.token())) {
            assertThatThrownBy(() -> binding(scope).gateway().complete(request()))
                    .isInstanceOf(ModelGatewayException.class);
            assertThatThrownBy(() -> binding(scope).summarizer().summarize(null, NONE))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(created).isEmpty();
        assertThat(secretReads).hasValue(0);
    }

    @Test void registryCapturesIndependentTemplatesAndRejectsNewCaptureAfterUnregister() throws Exception {
        login();
        var registry = new RunModelSourceRegistry();
        var context = ContextPreparationService.noop();
        var session = request().sessionId();
        var run = request().runId();
        var parent = gateway().openRun(BUDGET);
        var registration = registry.register(session, run, binding(parent), context, Optional.empty());
        try {
            context.recordExternalContext(session, "parent-only-context");
            var first = registry.capture(session, run, Optional.empty());
            var second = registry.capture(session, run, Optional.empty());
            assertThat(first.contextTemplate()).isNotSameAs(second.contextTemplate());
            assertThat(first.contextTemplate().prepare(request(), NONE).messages()).isEmpty();
            assertThat(second.contextTemplate().prepare(request(), NONE).messages()).isEmpty();
            assertThat(context.prepare(request(), NONE).messages()).hasSize(1);
            registration.close();
            assertThatThrownBy(() -> registry.capture(session, run, Optional.empty()))
                    .isInstanceOf(IllegalStateException.class);
            parent.close();
            try (var child = first.source().open(Optional.empty(), BUDGET)) {
                assertThat(binding(child).gateway().complete(request()).assistantMessage().text())
                        .isEqualTo(first.modelName());
            }
        } finally { registration.close(); parent.close(); }
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void modelCanBeValidatedBeforeQueueWithoutOpeningOrReadingCredentials() throws Exception {
        login();
        String other = catalog.require(ID.providerId()).models().get(1).id();
        CapturedRunSource prepared;
        try (var parent = gateway().openRun(BUDGET)) {
            var source = binding(parent).childSource();
            prepared = source.forModel(Optional.of(other));
            assertThatThrownBy(() -> source.forModel(Optional.of("not-a-catalog-model")))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(created).hasSize(1);
            assertThat(secretReads).hasValue(0);
            assertThat(leases.activeCount(ID)).isEqualTo(1);
        }
        try (var child = prepared.open(Optional.empty(), BUDGET)) {
            assertThat(binding(child).selection().orElseThrow().modelId()).isEqualTo(other);
            assertThat(binding(child).gateway().complete(request()).assistantMessage().text()).isEqualTo(other);
        }
        assertThat(created).hasSize(2);
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void bindingCrossesThreadsAndCloseRejectsModelAndSummary() throws Exception {
        login();
        var gateway = gateway();
        var scope = gateway.openRun(BUDGET);
        var bound = binding(scope);
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThat(worker.submit(() -> bound.gateway().complete(request()).assistantMessage().text())
                    .get(5, TimeUnit.SECONDS)).isEqualTo(selected().modelId());
            assertThat(worker.submit(() -> bound.summarizer().summarize(null, NONE))
                    .get(5, TimeUnit.SECONDS)).isEmpty();
            // 普通 facade 不再把创建者 Run 隐式继承到模型线程。
            worker.submit(() -> assertThatThrownBy(() -> gateway.complete(request()))
                    .isInstanceOf(ModelGatewayException.class)).get(5, TimeUnit.SECONDS);
            worker.submit(scope::close).get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> bound.gateway().complete(request())).isInstanceOf(ModelGatewayException.class);
            assertThatThrownBy(() -> bound.summarizer().summarize(null, NONE)).isInstanceOf(IllegalStateException.class);
        } finally { scope.close(); }
        assertThat(created.getFirst().closes).hasValue(1);
        assertThat(secretReads).hasValue(0);
    }

    @Test void twoLiveRunsOnSameWorkerKeepIndependentBindings() throws Exception {
        login(); var gateway = gateway();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> gateway.openRun(BUDGET)).get(5, TimeUnit.SECONDS);
            var second = worker.submit(() -> gateway.openRun(BUDGET)).get(5, TimeUnit.SECONDS);
            try {
                assertThat(binding(first).gateway()).isNotSameAs(binding(second).gateway());
                worker.submit(() -> binding(first).gateway().complete(request())).get(5, TimeUnit.SECONDS);
                worker.submit(first::close).get(5, TimeUnit.SECONDS);
                assertThat(worker.submit(() -> binding(second).gateway().complete(request()).assistantMessage().text())
                        .get(5, TimeUnit.SECONDS)).isEqualTo(selected().modelId());
                assertThat(worker.submit(() -> gateway.complete(request()).assistantMessage().text())
                        .get(5, TimeUnit.SECONDS)).isEqualTo(selected().modelId());
            } finally { first.close(); second.close(); }
        }
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void capturedSourceOutlivesParentAndCreatesDistinctChildrenWithoutSecretOrLeaseAtCapture() throws Exception {
        login(); var gateway = gateway();
        CapturedRunSource source;
        try (var parent = gateway.openRun(BUDGET)) {
            source = binding(parent).childSource();
            assertThat(created).hasSize(1);
            assertThat(leases.activeCount(ID)).isOne();
        }
        assertThat(leases.activeCount(ID)).isZero();
        try (var first = source.open(Optional.empty(), BUDGET);
             var second = source.open(Optional.empty(), BUDGET)) {
            assertThat(created).hasSize(3);
            assertThat(created.get(1)).isNotSameAs(created.get(2));
            assertThat(created.get(1).sessions).isNotSameAs(created.get(2).sessions);
            assertThat(binding(first).gateway()).isNotSameAs(binding(second).gateway());
            binding(first).gateway().complete(request());
            binding(second).gateway().complete(request());
            assertThat(leases.activeCount(ID)).isEqualTo(2);
        }
        assertThat(created.getFirst().closes).hasValue(1);
        assertThat(secretReads).hasValue(0);
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void reloginRejectsOldSourceBeforeAnyNewGateway() {
        login(); CapturedRunSource source;
        try (var parent = gateway().openRun(BUDGET)) { source = binding(parent).childSource(); }
        created.clear();
        login();
        assertThatThrownBy(() -> source.open(Optional.empty(), BUDGET)).isInstanceOf(ProviderAuthException.class);
        assertThat(created).isEmpty();
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void logoutRejectsOldSourceBeforeAnyNewGateway() {
        login(); CapturedRunSource source;
        try (var parent = gateway().openRun(BUDGET)) { source = binding(parent).childSource(); }
        created.clear();
        assertThat(leases.fenceAndDrain(ID, BUDGET, NONE)).isTrue();
        assertThatThrownBy(() -> source.open(Optional.empty(), BUDGET)).isInstanceOf(ProviderAuthException.class);
        assertThat(created).isEmpty();
    }

    @Test void childOverrideChangesOnlyModelAndUsesTargetWindowDeclaration() {
        login();
        String other = catalog.require(ID.providerId()).models().get(1).id();
        try (var parent = gateway().openRun(BUDGET)) {
            var source = binding(parent).childSource();
            try (var child = source.open(Optional.of(other), BUDGET)) {
                var selection = binding(child).selection().orElseThrow();
                assertThat(selection).isEqualTo(new ProviderSelectionSnapshot(ID.providerId(), ID.profileId(),
                        other, "pi", "API_KEY"));
                assertThat(binding(child).contextWindowTokens()).isEqualTo(
                        OptionalLong.of(catalog.requireModel(ID.providerId(), other).contextWindow()));
                assertThat(binding(parent).selection()).contains(selected());
            }
            int before = created.size();
            assertThatThrownBy(() -> source.open(Optional.of("not-in-this-provider"), BUDGET))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(created).hasSize(before);
        }
    }

    @Test void failedRootHasExplicitFailingPortsAndCannotFallback() {
        AtomicInteger fallback = new AtomicInteger();
        var gateway = routes().lazyGateway(() -> Optional.of(selected()), request -> {
            fallback.incrementAndGet(); return ModelTurn.text("wrong");
        }, (request, token) -> { fallback.incrementAndGet(); return Optional.empty(); });
        try (var root = gateway.openRun(BUDGET)) {
            var bound = binding(root);
            assertThatThrownBy(() -> bound.gateway().complete(request())).isInstanceOf(ModelGatewayException.class);
            assertThatThrownBy(() -> bound.summarizer().summarize(null, NONE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> bound.childSource().open(Optional.empty(), BUDGET))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(bound.selection()).contains(selected());
        }
        try (var absent = routes().lazyGateway(Optional::empty).openRun(BUDGET)) {
            assertThatThrownBy(() -> binding(absent).gateway().complete(request())).isInstanceOf(ModelGatewayException.class);
        }
        assertThat(fallback).hasValue(0);
        assertThat(created).isEmpty();
    }

    @Test void startupCompatibilityRefusesOverrideWithoutKnownModelIdentity() throws Exception {
        var gateway = routes().lazyGateway(Optional::empty, ignored -> ModelTurn.text("startup"),
                (request, token) -> Optional.empty());
        CapturedRunSource source;
        try (var root = gateway.openRun(BUDGET)) {
            source = binding(root).childSource();
            assertThat(binding(root).selection()).isEmpty();
            assertThat(binding(root).contextWindowTokens()).isEqualTo(OptionalLong.empty());
        }
        assertThatThrownBy(() -> source.open(Optional.of("guessed-model"), BUDGET))
                .isInstanceOf(ProviderAuthException.class);
        try (var child = source.open(Optional.empty(), BUDGET)) {
            assertThat(binding(child).gateway().complete(request()).assistantMessage().text()).isEqualTo("startup");
        }
    }

    @Test void childDoesNotAcquireOrRetainRootAuthMutex() throws Exception {
        login();
        var definitions = new ProviderDefinitionStore(home);
        var credentials = new RestrictedFileCredentialStore(home);
        var service = new ProviderAuthApplicationService(definitions, credentials,
                new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(home), definitions, credentials),
                Map.of(), leases);
        // 跨包调用已有包级装配 seam；不为测试扩大生产公开 API，也不复制 FencedRunGateway。
        var seam = io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources.class
                .getDeclaredMethod("fencedGateway", RunScopedModelGateway.class, ProviderAuthApplicationService.class);
        seam.setAccessible(true);
        var fenced = (RunScopedModelGateway) seam.invoke(null, gateway(), service);
        var parent = fenced.openRun(BUDGET);
        RunScopedModelGateway.RunScope child;
        try {
            assertThatThrownBy(service::beginRun).hasMessage("RUN_ACTIVE");
            child = binding(parent).childSource().open(Optional.empty(), BUDGET);
        } finally { parent.close(); }
        try (child; var nextRootMutex = service.beginRun()) {
            assertThat(binding(child).selection()).contains(selected());
            assertThat(leases.activeCount(ID)).isOne();
        }
    }

    @Test void cancellationBeforeChildOpenCreatesNoGateway() {
        login();
        try (var root = gateway().openRun(BUDGET)) {
            var source = binding(root).childSource();
            created.clear();
            CancellationToken cancelled = new CancellationToken() {
                public boolean isCancellationRequested() { return true; }
                public Registration onCancellation(Runnable action) { action.run(); return () -> { }; }
            };
            assertThatThrownBy(() -> source.open(Optional.empty(), BUDGET, cancelled))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(created).isEmpty();
        }
    }

    @Test void closeDuringModelOrSummaryCannotReturnLateSuccess() throws Exception {
        for (boolean summary : List.of(false, true)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Runnable wait = () -> {
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            };
            var gateway = routes().lazyGateway(Optional::empty,
                    request -> { wait.run(); return ModelTurn.text("late"); },
                    (request, token) -> { wait.run(); return Optional.empty(); });
            var root = gateway.openRun(BUDGET);
            var bound = binding(root);
            try (var worker = Executors.newSingleThreadExecutor()) {
                var result = worker.submit(() -> {
                    if (summary) return bound.summarizer().summarize(null, NONE);
                    return bound.gateway().complete(request());
                });
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    root.close();
                } finally { release.countDown(); }
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(summary ? IllegalStateException.class : ModelGatewayException.class);
            } finally { root.close(); }
        }
    }

    @Test void explicitLegacyProfileRetainsOriginalGenerationAndRejectsNewAccount() {
        var definitions = new ProviderDefinitionStore(home);
        var credentials = new RestrictedFileCredentialStore(home);
        credentials.saveEnv("anthropic", "personal", "SYNTHETIC_OLD", false, NONE);
        AtomicInteger creations = new AtomicInteger();
        List<ProviderGatewayFactory> factories = Arrays.stream(ProviderGatewayKind.values())
                .<ProviderGatewayFactory>map(kind -> new ProviderGatewayFactory() {
                    public ProviderGatewayKind kind() { return kind; }
                    public ModelGateway create(ProviderGatewayConfiguration configuration) {
                        creations.incrementAndGet();
                        return new FakeGateway("legacy", null);
                    }
                }).toList();
        var routes = new SelectedProviderRouteFactory(definitions,
                new CredentialResolver(credentials, Map.of("SYNTHETIC_OLD", "old-fixture", "SYNTHETIC_NEW", "new-fixture")),
                leases, new ProviderGatewayFactoryRegistry(factories));
        var model = definitions.snapshot(NONE).catalog().require("anthropic").defaultModelId();
        var selected = new ProviderSelectionSnapshot("anthropic", "personal", model);
        CapturedRunSource source;
        try (var root = routes.lazyGateway(() -> Optional.of(selected)).openRun(BUDGET)) {
            source = binding(root).childSource();
            assertThat(binding(root).selection()).contains(selected);
            try (var child = source.open(Optional.of(model), BUDGET)) {
                assertThat(binding(child).selection()).contains(selected);
            }
        }
        assertThat(creations).hasValue(2);
        creations.set(0);
        credentials.saveEnv("anthropic", "personal", "SYNTHETIC_NEW", false, NONE);
        assertThatThrownBy(() -> source.open(Optional.empty(), BUDGET)).isInstanceOf(ProviderAuthException.class);
        assertThat(creations).hasValue(0);
        assertThat(leases.activeCount("anthropic", "personal")).isZero();
    }

    @Test void metadataReloginDuringConfigurationIsRejectedBeforeGatewayConstruction() {
        long epoch = login();
        AtomicInteger creations = new AtomicInteger();
        var factory = new PiSelectedProviderRouteFactory(store(), leases, catalog,
                () -> { login(); return null; }, name -> { throw new AssertionError("secret read"); },
                (configuration, provider, model, timeout, sessions) -> {
                    creations.incrementAndGet(); return new FakeGateway(model, sessions);
                });
        assertThatThrownBy(() -> factory.openCaptured(selected(), new CredentialVersion.PiAuthEpoch(epoch), BUDGET, NONE))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(creations).hasValue(0);
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void windowIsOptionalPositiveDeclarationAndAllBindingComponentsAreRequired() {
        ModelGateway model = ignored -> ModelTurn.text("ok");
        ContextSummarizer summary = (request, token) -> Optional.empty();
        CapturedRunSource source = (override, budget) -> { throw new IllegalStateException("unused"); };
        assertThat(new RunModelBinding(model, summary, source, Optional.empty(), OptionalLong.of(32))
                .contextWindowTokens()).isEqualTo(OptionalLong.of(32));
        for (long invalid : new long[]{0, -1}) {
            assertThatThrownBy(() -> new RunModelBinding(model, summary, source, Optional.empty(), OptionalLong.of(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RunModelBinding(null, summary, source, Optional.empty(), OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunModelBinding(model, null, source, Optional.empty(), OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunModelBinding(model, summary, null, Optional.empty(), OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunModelBinding(model, summary, source, null, OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunModelBinding(model, summary, source, Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 故意不在 close 后自拒绝，由显式端口本身证明生命周期检查，不用 Fake 掩盖漏洞。 */
    private static final class FakeGateway implements StreamingModelGateway, ContextSummarizer, AutoCloseable {
        private final String model;
        private final PiCredentialSessionFactory sessions;
        private final AtomicInteger closes = new AtomicInteger();
        private FakeGateway(String model, PiCredentialSessionFactory sessions) { this.model = model; this.sessions = sessions; }
        public ModelTurn complete(ModelRequest request) { return ModelTurn.text(model); }
        public ModelTurn complete(ModelRequest request, ModelStreamObserver observer, CancellationToken cancellation) {
            return complete(request);
        }
        public Optional<SummaryCandidate> summarize(SummaryRequest request, CancellationToken cancellation) {
            return Optional.empty();
        }
        public void close() { closes.incrementAndGet(); }
    }
}
