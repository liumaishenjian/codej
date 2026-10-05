package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.model.*;
import io.github.liumaishenjian.ccjava.model.pi.gateway.*;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import io.github.liumaishenjian.ccjava.model.springai.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Pi Run 组合回归：真实本地 Store/RPC，Fake 模型端口，不访问网络或真实账号。 */
class PiSelectedProviderRouteFactoryTest {
    @TempDir Path home;
    private static final CancellationToken NONE = CancellationToken.none();
    private static final PiCredentialIdentity ID = new PiCredentialIdentity("openai",
            PiCredentialIdentity.AuthMethod.API_KEY, "default");
    private final PiProviderCatalog catalog = new PiProviderCatalog();
    private final CredentialLeaseRegistry leases = new CredentialLeaseRegistry();
    private final List<FakeGateway> created = new ArrayList<>();
    private final AtomicInteger environmentReads = new AtomicInteger();
    private final AtomicInteger configurationReads = new AtomicInteger();
    private final AtomicInteger legacyReads = new AtomicInteger();
    private final List<Duration> timeouts = new ArrayList<>();
    private boolean closeFails;
    private int transientFailures;

    private ProviderSelectionSnapshot selected() {
        return new ProviderSelectionSnapshot(ID.providerId(), ID.profileId(),
                catalog.require(ID.providerId()).models().getFirst().id(), "pi", "API_KEY");
    }

    private PiCredentialStore store() { return new PiCredentialStore(home); }

    private long login() {
        var store = store();
        try (var material = PiCredentialMaterial.envRef("CODEJ_SYNTHETIC_TEST")) {
            return store.saveLogin(ID, material, store.snapshot(NONE).generation(), false, NONE).authEpoch();
        }
    }

    private PiSelectedProviderRouteFactory pi() {
        return new PiSelectedProviderRouteFactory(store(), leases, catalog,
                () -> { configurationReads.incrementAndGet(); return null; }, name -> {
                    assertThat(name).isEqualTo("CODEJ_SYNTHETIC_TEST");
                    environmentReads.incrementAndGet();
                    return "synthetic-route-key".getBytes(StandardCharsets.UTF_8);
                }, (configuration, provider, model, timeout, sessions) -> {
                    assertThat(provider).isEqualTo(ID.providerId());
                    assertThat(model).isEqualTo(selected().modelId());
                    timeouts.add(timeout);
                    var gateway = new FakeGateway(sessions, closeFails, transientFailures);
                    created.add(gateway);
                    return gateway;
                });
    }

    private SelectedProviderRouteFactory routes(PiSelectedProviderRouteFactory pi) {
        // 旧存储一旦被触碰就使测试失败；同名默认配置不能成为 Pi 缺材料的 fallback。
        CredentialStore forbidden = new CredentialStore() {
            private AssertionError touched() { legacyReads.incrementAndGet(); return new AssertionError("legacy accessed"); }
            public Snapshot snapshot(CancellationToken token) { throw touched(); }
            public CredentialProfile saveStore(String p,String id,SecretMaterial s,boolean d,CancellationToken c) { throw touched(); }
            public CredentialProfile saveEnv(String p,String id,String n,boolean d,CancellationToken c) { throw touched(); }
            public boolean secretExists(SecretRef.Store ref,CancellationToken c) { throw touched(); }
            public SecretMaterial readSecret(SecretRef.Store ref,CancellationToken c) { throw touched(); }
            public void delete(String p,String id,long g,CancellationToken c) { throw touched(); }
        };
        var runtime = new ModelRetryRuntime() {
            public double nextRandom() { return 0; }
            public void await(Duration delay, CancellationToken cancellation) { }
        };
        return new SelectedProviderRouteFactory(new ProviderDefinitionStore(home),
                new CredentialResolver(forbidden, Map.of("OPENAI_API_KEY", "never-read")), leases,
                ProviderGatewayFactoryRegistry.production(),
                new ModelRetryPolicy(3, List.of(Duration.ZERO, Duration.ZERO)), runtime, pi);
    }

    @Test void missingPiMaterialNeverTouchesLegacyOrUsesStartupFallback() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        var gateway = routes(pi()).lazyGateway(() -> Optional.of(selected()), request -> {
            fallback.incrementAndGet(); return ModelTurn.text("wrong");
        }, (request, token) -> { fallback.incrementAndGet(); return Optional.empty(); });
        try (var run = gateway.openRun()) {
            assertThatThrownBy(() -> gateway.complete(request())).isInstanceOf(ModelGatewayException.class);
        }
        assertThat(legacyReads).hasValue(0);
        assertThat(fallback).hasValue(0);
        assertThat(configurationReads).hasValue(0);
        assertThat(created).isEmpty();
    }

    @Test void noPiInjectionStillRejectsExplicitPiEvenWithValidMaterial() {
        login();
        var gateway = routes(null).lazyGateway(() -> Optional.of(selected()));
        try (var run = gateway.openRun()) {
            assertThatThrownBy(() -> gateway.complete(request())).isInstanceOf(ModelGatewayException.class);
        }
        assertThat(legacyReads).hasValue(0);
        assertThat(created).isEmpty();
    }

    @Test void crossAuthMethodAndUnknownModelFailBeforeRuntimeOrEnvironment() {
        login();
        var pi = pi();
        var wrongAuth = new ProviderSelectionSnapshot("openai", "default", selected().modelId(), "pi", "OAUTH");
        var wrongModel = new ProviderSelectionSnapshot("openai", "default", "not-a-catalog-model", "pi", "API_KEY");
        assertThatThrownBy(() -> pi.open(wrongAuth, Duration.ofSeconds(5))).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> pi.open(wrongModel, Duration.ofSeconds(5))).isInstanceOf(ProviderAuthException.class);
        assertThat(configurationReads).hasValue(0);
        assertThat(environmentReads).hasValue(0);
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void eachRunCreatesNewGatewayAndEachTurnUsesNewRpcAtFixedEpoch() throws Exception {
        long firstEpoch = login();
        var pi = pi();
        var first = pi.open(selected(), Duration.ofHours(1));
        assertThat(first.lease().version()).isEqualTo(new CredentialVersion.PiAuthEpoch(firstEpoch));
        assertThat(environmentReads).hasValue(0); // 打开 Run 只读取元数据。
        first.raw().complete(request());
        first.raw().complete(request());
        assertThat(environmentReads).hasValue(2);
        long nextEpoch = login();
        assertThat(nextEpoch).isGreaterThan(firstEpoch);
        assertThatThrownBy(() -> first.raw().complete(request())).isInstanceOf(ModelGatewayException.class);
        assertThat(first.lease().generation()).isEqualTo(firstEpoch);
        first.lease().close();
        var second = pi.open(selected(), Duration.ofSeconds(2));
        try {
            assertThat(second.raw()).isNotSameAs(first.raw());
            assertThat(second.lease().generation()).isEqualTo(nextEpoch);
            second.raw().complete(request());
        } finally { second.lease().close(); }
        assertThat(created).hasSize(2);
        assertThat(timeouts).containsExactly(Duration.ofSeconds(300), Duration.ofSeconds(2));
        assertThat(leases.activeCount(ID)).isZero();
    }

    @Test void cleanupFailureKeepsLeaseAndExactPiFenceWithoutDrainingLegacy() {
        login(); closeFails = true;
        var legacy = leases.acquire("openai", "default", 1, () -> { });
        var pi = pi();
        var opened = pi.open(selected(), Duration.ofSeconds(1));
        opened.lease().close();
        assertThat(leases.activeCount(ID)).isOne();
        assertThat(leases.fenced(ID)).isTrue();
        assertThat(leases.fenced("openai", "default")).isFalse();
        assertThat(leases.activeCount("openai", "default")).isOne();
        assertThat(leases.fenceAndDrain(ID, Duration.ZERO, NONE)).isFalse();
        assertThatThrownBy(() -> pi.open(selected(), Duration.ofSeconds(1))).isInstanceOf(ProviderAuthException.class);
        assertThat(created).hasSize(1);
        legacy.close();
    }

    @Test void oneJavaRetryLayerReusesGatewayAndNeverFallsBack() throws Exception {
        login(); transientFailures = 2;
        AtomicInteger fallback = new AtomicInteger();
        var gateway = routes(pi()).lazyGateway(() -> Optional.of(selected()), request -> {
            fallback.incrementAndGet(); return ModelTurn.text("wrong");
        }, (request, cancellation) -> Optional.empty());
        try (var run = gateway.openRun()) { gateway.complete(request()); }
        assertThat(created).hasSize(1);
        assertThat(created.getFirst().calls).isEqualTo(3);
        assertThat(created.getFirst().closes).isOne();
        assertThat(fallback).hasValue(0);
        assertThat(legacyReads).hasValue(0);
    }

    @Test void summaryContractBlockerIsExplicitAndNeverCallsStartupSummary() throws Exception {
        login(); AtomicInteger fallback = new AtomicInteger();
        var gateway = routes(pi()).lazyGateway(() -> Optional.of(selected()), request -> ModelTurn.text("wrong"),
                (request, token) -> { fallback.incrementAndGet(); return Optional.empty(); });
        try (var run = gateway.openRun()) {
            gateway.complete(request());
            assertThatThrownBy(() -> ((ContextSummarizer) gateway).summarize(null, NONE))
                    .hasMessage("PI_SUMMARY_ADAPTER_UNAVAILABLE");
        }
        assertThat(created).hasSize(1);
        assertThat(fallback).hasValue(0);
    }

    @Test void logoutCancelsExactPiRunAndLateBindingStillReceivesCancellation() throws Exception {
        login();
        var legacy = leases.acquire("openai", "default", 1, () -> { });
        var gateway = routes(pi()).lazyGateway(() -> Optional.of(selected()));
        var run = gateway.openRun();
        AtomicInteger cancelled = new AtomicInteger();
        try {
            assertThat(leases.fenceAndDrain(ID, Duration.ZERO, NONE)).isFalse();
            run.bindCancellation(cancelled::incrementAndGet);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (cancelled.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(cancelled).hasValue(1);
            assertThat(leases.fenced("openai", "default")).isFalse();
            assertThat(leases.activeCount("openai", "default")).isOne();
        } finally { run.close(); legacy.close(); }
        assertThat(leases.fenceAndDrain(ID, Duration.ofSeconds(3), NONE)).isTrue();
    }

    @Test void configurationCreationFailureReleasesRegisteredSlotWithoutResolvingSecrets() {
        login();
        var pi = new PiSelectedProviderRouteFactory(store(), leases, catalog,
                () -> { throw new IllegalStateException("PI_CONFIGURATION_UNAVAILABLE"); },
                name -> { throw new AssertionError("environment accessed"); });
        assertThatThrownBy(() -> pi.open(selected(), Duration.ofSeconds(1)))
                .hasMessage("PI_CONFIGURATION_UNAVAILABLE");
        assertThat(leases.activeCount(ID)).isZero();
        assertThat(leases.fenced(ID)).isFalse();
    }

    @Test void capabilitiesAreDeclarationsNotAccountObservations() {
        login(); var opened = pi().open(selected(), Duration.ofSeconds(1));
        try {
            assertThat(opened.capabilities().supports(ModelCapability.TEXT)).isTrue();
            assertThat(opened.capabilities().supports(ModelCapability.TOOL_CALLING)).isTrue();
            assertThat(opened.capabilities().observed().values()).containsOnly(CapabilitySupport.UNKNOWN);
            assertThat(opened.capabilities().supports(ModelCapability.REASONING)).isFalse();
        } finally { opened.lease().close(); }
    }

    private static ModelRequest request() {
        return new ModelRequest(new SessionId("session"), new RunId("run"), 1, List.of(), List.of());
    }

    /** 每回合通过生产 RPC 读取合成 ENV_REF；不缓存或检查秘密正文。 */
    private static final class FakeGateway implements StreamingModelGateway, AutoCloseable {
        private final PiCredentialSessionFactory sessions;
        private final boolean closeFails;
        private final int failures;
        private int calls;
        private int closes;
        private boolean closed;
        private FakeGateway(PiCredentialSessionFactory sessions, boolean closeFails, int failures) {
            this.sessions = sessions; this.closeFails = closeFails; this.failures = failures;
        }
        @Override public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
            return complete(request, delta -> { }, NONE);
        }
        @Override public ModelTurn complete(ModelRequest request, ModelStreamObserver observer, CancellationToken token)
                throws ModelGatewayException {
            if (closed) throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT, "closed");
            calls++;
            if (calls <= failures) throw new ModelGatewayException(ModelGatewayException.FailureKind.RETRYABLE, "retry");
            String operation = "operation-" + calls;
            try (var session = sessions.open(operation, token)) {
                var payload = JsonMapper.builder().build().createObjectNode().put("requestId", 1).put("action", "read");
                payload.putObject("arguments");
                var response = session.handle(new ProtocolFrame(operation, 1, "credential.request", payload));
                if (!response.get("ok").asBoolean())
                    throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT, "credential unavailable");
                return ModelTurn.text("ok");
            } catch (ModelGatewayException failure) { throw failure; }
            catch (Exception failure) { throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT, "rpc failed"); }
        }
        @Override public void close() {
            closes++; closed = true;
            if (closeFails) throw new IllegalStateException("synthetic cleanup failure");
        }
    }
}
