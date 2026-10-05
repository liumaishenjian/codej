package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.provider.SelectedProviderRouteFactory;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.model.springai.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** ADR-099：同一宿主的 legacy→登录→选择→退出闭环；Fake 不绕过生产 resolver/lease/fence。 */
class ProviderLoginRouteTest {
    @TempDir Path temporary;
    private static final CancellationToken NONE = CancellationToken.none();
    private static final ModelRequest REQUEST = new ModelRequest(
            new SessionId("session"), new RunId("run"), 1, List.of(), List.of());
    private static final SummaryRequest SUMMARY = new SummaryRequest(
            SummaryTier.values()[0], "bounded input", 0, List.of("message-1"), List.of(), 128, 64, 256);

    @Test void legacyThenLoginChangesActualGatewayAndSummaryOnNextRunWithoutNewRuntime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RunScopedModelGateway gateway = fixture.gateway(true);
            try (var run = gateway.openRun()) {
                assertThat(gateway.complete(REQUEST).assistantMessage().text()).isEqualTo("legacy");
                assertSummary(gateway, "legacy");
                assertThatThrownBy(() -> fixture.service.selectModel(fixture.selection(), NONE))
                        .isInstanceOfSatisfying(ProviderAuthException.class,
                                failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
            }
            fixture.loginAndSelect();
            try (var run = gateway.openRun()) {
                assertThat(gateway.complete(REQUEST).assistantMessage().text()).isEqualTo("selected");
                int reads = fixture.store.snapshots.get();
                assertSummary(gateway, "selected");
                assertThat(fixture.store.snapshots).hasValue(reads);
                assertThat(fixture.creates).hasValue(1);
                assertThat(fixture.leases.activeCount("anthropic", "personal")).isOne();
            }
            assertThat(fixture.legacy.calls).hasValue(1);
            assertThat(fixture.legacy.summaries).hasValue(1);
            assertThat(fixture.selected.closes).hasValue(1);
            assertThat(fixture.leases.activeCount("anthropic", "personal")).isZero();
        }
    }

    @Test void logoutBlocksOldAndLegacyForModelAndSummaryThenExplicitReloginWorks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RunScopedModelGateway gateway = fixture.gateway(true);
            fixture.loginAndSelect();
            try (var run = gateway.openRun()) { gateway.complete(REQUEST); }
            fixture.service.logout("anthropic", "personal", NONE);
            try (var run = gateway.openRun()) {
                assertThatThrownBy(() -> gateway.complete(REQUEST))
                        .isInstanceOfSatisfying(ModelGatewayException.class, failure -> {
                            assertThat(failure.summary()).hasValueSatisfying(summary ->
                                    assertThat(summary.category()).isEqualTo(ModelFailureCategory.CONFIGURATION_REQUIRED));
                            assertThat(failure.getCause()).isInstanceOfSatisfying(ProviderAuthException.class,
                                    auth -> assertThat(auth.code()).isEqualTo(ProviderAuthException.Code.AUTH_PROFILE_REQUIRED));
                        });
                assertThatThrownBy(() -> ((ContextSummarizer) gateway).summarize(SUMMARY, NONE))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(fixture.legacy.calls).hasValue(0);
            assertThat(fixture.legacy.summaries).hasValue(0);
            assertThat(fixture.creates).hasValue(1);
            fixture.loginAndSelect();
            try (var run = gateway.openRun()) {
                assertThat(gateway.complete(REQUEST).assistantMessage().text()).isEqualTo("selected");
                assertSummary(gateway, "selected");
            }
            assertThat(fixture.creates).hasValue(2);
        }
    }

    @Test void runFenceRejectsSwitchAndIsReleasedOnFailedRoute() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RunScopedModelGateway gateway = fixture.gateway(false);
            try (var run = gateway.openRun()) {
                assertThatThrownBy(() -> fixture.service.selectModel(fixture.selection(), NONE))
                        .isInstanceOfSatisfying(ProviderAuthException.class,
                                failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
                assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class);
            }
            fixture.loginAndSelect();
            try (var run = gateway.openRun()) {
                assertThatThrownBy(() -> fixture.service.selectModel(fixture.selection(), NONE))
                        .isInstanceOfSatisfying(ProviderAuthException.class,
                                failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
                assertThat(gateway.complete(REQUEST).assistantMessage().text()).isEqualTo("selected");
            }
            assertThat(fixture.service.selectModel(fixture.selection(), NONE)).isNotNull();
        }
    }

    @Test void deletedSelectedProfileNeverFallsBackToConfiguredLegacy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RunScopedModelGateway gateway = fixture.gateway(true);
            fixture.loginAndSelect();
            // 模拟另一进程删除选中 profile，而不是把错误伪造成 Optional.empty。
            fixture.store.delete("anthropic", "personal", fixture.store.generation, NONE);
            try (var run = gateway.openRun()) {
                assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class);
                assertThatThrownBy(() -> ((ContextSummarizer) gateway).summarize(SUMMARY, NONE))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(fixture.legacy.calls).hasValue(0);
            assertThat(fixture.creates).hasValue(0);
        }
    }

    @Test void oneHeadlessSessionSurvivesUnconfiguredOrLegacyStartupLoginAndLogout() throws Exception {
        for (boolean includeLegacy : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                Path workspace = Files.createTempDirectory(temporary, "workspace-");
                Path home = Files.createTempDirectory(temporary, "instructions-");
                HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(workspace, "startup-label",
                        java.time.Duration.ofSeconds(5), PermissionMode.DEFAULT, List.of(),
                        io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest.create(),
                        Files.createTempDirectory(temporary, "sessions-"));
                try (HeadlessRuntimeSession session = new HeadlessRuntimeSession(fixture.gateway(includeLegacy),
                        AgentEventSink.noop(), options, (invocation, definition, outcome) -> ApprovalResponse.deny(),
                        ContextPreparationService.noop(), null, HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                        HeadlessRuntimeSession.HeadlessInstructionLayout.forHome(home))) {
                    session.open();
                    assertThat(session.run("initial synthetic run").stopReason())
                            .isEqualTo(includeLegacy ? StopReason.COMPLETED : StopReason.MODEL_ERROR);
                    fixture.loginAndSelect();
                    AgentRunResult selected = session.run("after login synthetic run");
                    assertThat(selected.stopReason()).isEqualTo(StopReason.COMPLETED);
                    assertThat(selected.finalText()).contains("selected");
                    fixture.service.logout("anthropic", "personal", NONE);
                    assertThat(session.run("after logout synthetic run").stopReason()).isEqualTo(StopReason.MODEL_ERROR);
                }
                assertThat(fixture.legacy.calls).hasValue(includeLegacy ? 1 : 0);
                assertThat(fixture.selected.calls).hasValue(1);
            }
        }
    }

    private static void assertSummary(RunScopedModelGateway gateway, String expected) {
        assertThat(((ContextSummarizer) gateway).summarize(SUMMARY, NONE))
                .hasValueSatisfying(candidate -> assertThat(candidate.summary()).isEqualTo(expected));
    }

    private final class Fixture implements AutoCloseable {
        final MemoryStore store = new MemoryStore();
        final CredentialLeaseRegistry leases = new CredentialLeaseRegistry();
        final CountingGateway legacy = new CountingGateway("legacy");
        final CountingGateway selected = new CountingGateway("selected");
        final AtomicInteger creates = new AtomicInteger();
        final ProviderAuthApplicationService service;
        final SelectedProviderRouteFactory routes;
        final String model;

        Fixture() throws Exception {
            Path home = Files.createTempDirectory(temporary, "home-");
            var definitions = new ProviderDefinitionStore(home);
            Map<String, String> environment = Map.of("CC_ROUTE_TEST_KEY", "synthetic-route-secret");
            service = new ProviderAuthApplicationService(definitions, store,
                    new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(home), definitions, store),
                    environment, leases);
            model = definitions.snapshot(NONE).catalog().require("anthropic").defaultModelId();
            var factories = Arrays.stream(ProviderGatewayKind.values()).map(kind -> new ProviderGatewayFactory() {
                public ProviderGatewayKind kind() { return kind; }
                public ModelGateway create(ProviderGatewayConfiguration configuration) {
                    creates.incrementAndGet();
                    assertThat(configuration.modelId()).isEqualTo(model);
                    return selected;
                }
            }).map(ProviderGatewayFactory.class::cast).toList();
            routes = new SelectedProviderRouteFactory(definitions, new CredentialResolver(store, environment), leases,
                    new ProviderGatewayFactoryRegistry(factories));
        }
        RunScopedModelGateway gateway(boolean includeLegacy) {
            return ProviderAuthRuntimeResources.fencedGateway(routes.lazyGateway(service::routingSelection,
                    includeLegacy ? legacy : null, includeLegacy ? legacy : null), service);
        }
        ProviderAuthApplicationService.ModelSelectionRequest selection() {
            return new ProviderAuthApplicationService.ModelSelectionRequest("anthropic", model, Optional.of("personal"), false);
        }
        void loginAndSelect() {
            service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                    ProviderAuthApplicationService.RefKind.ENV, "CC_ROUTE_TEST_KEY", true), null, NONE);
            service.activateLogin("anthropic", "personal", NONE);
            service.selectModel(selection(), NONE);
        }
        public void close() { leases.close(); }
    }

    private static final class CountingGateway implements ModelGateway, ContextSummarizer, AutoCloseable {
        final String identity;
        final AtomicInteger calls = new AtomicInteger(), summaries = new AtomicInteger(), closes = new AtomicInteger();
        CountingGateway(String identity) { this.identity = identity; }
        public ModelTurn complete(ModelRequest request) { calls.incrementAndGet(); return ModelTurn.text(identity); }
        public Optional<SummaryCandidate> summarize(SummaryRequest request, CancellationToken cancellation) {
            summaries.incrementAndGet();
            return Optional.of(new SummaryCandidate(request.tier(), identity, request.sourceRevision(),
                    request.sourceMessageIds(), identity.length(), identity.length()));
        }
        public void close() { closes.incrementAndGet(); }
    }

    /** 内存事务 Fake：只使用 ENV 引用；保存与删除验证 generation，绝不放松生产文件权限规则。 */
    private static final class MemoryStore implements CredentialStore {
        final AtomicInteger snapshots = new AtomicInteger();
        long generation;
        CredentialProfile profile;
        public Snapshot snapshot(CancellationToken cancellation) {
            snapshots.incrementAndGet();
            return new Snapshot(generation, profile == null ? List.of() : List.of(profile),
                    profile == null ? Map.of() : Map.of("anthropic", "personal"));
        }
        public CredentialProfile saveEnv(String provider, String name, String env, boolean useDefault, CancellationToken c) {
            return saveEnv(provider, name, env, useDefault, generation, c);
        }
        public CredentialProfile saveEnv(String provider, String name, String env, boolean useDefault, long expected, CancellationToken c) {
            assertThat(expected).isEqualTo(generation);
            profile = new CredentialProfile(name, provider, new SecretRef.Env(env), Instant.EPOCH, Instant.EPOCH, Optional.empty());
            generation++;
            return profile;
        }
        public void delete(String provider, String name, long expected, CancellationToken c) {
            assertThat(expected).isEqualTo(generation);
            profile = null;
            generation++;
        }
        public CredentialProfile saveStore(String a, String b, SecretMaterial secret, boolean d, CancellationToken c) {
            throw new AssertionError("ENV-only test");
        }
        public boolean secretExists(SecretRef.Store ref, CancellationToken c) { throw new AssertionError("ENV-only test"); }
        public SecretMaterial readSecret(SecretRef.Store ref, CancellationToken c) { throw new AssertionError("ENV-only test"); }
    }
}
