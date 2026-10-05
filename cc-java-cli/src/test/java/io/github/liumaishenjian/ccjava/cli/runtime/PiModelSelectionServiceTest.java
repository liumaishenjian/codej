package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialMaterial;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.PiLoginOperation;
import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MODEL-13 元数据选择测试；legacy/Node 接缝碰触即失败，不访问真实 home 或网络。 */
class PiModelSelectionServiceTest {
    private static final CancellationToken NONE = CancellationToken.none();
    private static final PiLoginOperation.Interaction UNUSED = new PiLoginOperation.Interaction() {
        public PiLoginOperation.PromptHandle request(PiLoginOperation.Prompt prompt) { throw new AssertionError("INPUT_FORBIDDEN"); }
        public void authorizationUrl(URI uri) { throw new AssertionError("NETWORK_FORBIDDEN"); }
    };
    @TempDir Path home;

    @Test void persistedSelectionLockWaitObservesStartupCancellationWithoutBecomingEmpty() throws Exception {
        var f = fixture(false);
        f.definitions.snapshot(NONE);
        var cancellation = new io.github.liumaishenjian.ccjava.core.CancellationSource();
        var entered = new java.util.concurrent.CountDownLatch(1);
        CancellationToken token = new CancellationToken() {
            public boolean isCancellationRequested() {
                entered.countDown();
                return cancellation.token().isCancellationRequested();
            }
            public Registration onCancellation(Runnable action) { return cancellation.token().onCancellation(action); }
        };
        Path lockPath = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileSecurity(home).root().resolve(".providers.lock");
        try (var ignored = f.leases;
             var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
             var channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var future = worker.submit(() -> f.service.routingSelection(token));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            cancellation.cancel();
            assertThatThrownBy(() -> future.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class, failure ->
                            assertThat(failure.getCause()).isInstanceOfSatisfying(ProviderAuthException.class,
                                    auth -> assertThat(auth.code()).isEqualTo(ProviderAuthException.Code.AUTH_CANCELLED)));
            assertThat(f.service.nextSelection()).isEmpty();
        }
    }

    @Test void persistedApiKeyAndOauthRestoreExactProfileWithoutLegacyOrNode() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            for (String provider : List.of("openai", "openai-codex")) {
                var identity = identity(provider, "explicit");
                save(f, identity);
                save(f, identity(provider, "other"));
                var selected = f.service.selectPiModel(identity, model(provider), true, NONE);
                assertThat(selected.profileId()).isEqualTo("explicit");
                assertThat(selected.authMethod()).isEqualTo(identity.authMethod().name());
                assertThat(f.pi.snapshot(NONE).providerDefaults()).isEmpty();
                var reopened = service(f.definitions, f.pi, f.leases, false);
                assertThat(reopened.routingSelection()).contains(selected);
                assertThat(reopened.hasUsableDefaultSelection(NONE)).isTrue();
            }
        }
    }

    @Test void missingMaterialAndUnsetEnvRemainConfiguredUnverifiedButAbsentMetadataNeverFallsBack() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            var identity = identity("openai", "explicit");
            save(f, identity);
            f.service.selectPiModel(identity, model("openai"), true, NONE);
            var metadata = f.pi.snapshot(NONE).find(identity).orElseThrow();
            Files.delete(home.resolve(".cc-java/auth/pi/secrets/" + metadata.secretRef().orElseThrow() + ".json"));
            var reopened = service(f.definitions, f.pi, f.leases, false);
            assertThat(reopened.hasUsableDefaultSelection(NONE)).isTrue();
            assertThat(reopened.routingSelection()).isPresent();
            var env = identity("deepseek", "env-only");
            try (var material = PiCredentialMaterial.envRef("SYNTHETIC_UNSET_ENV")) {
                f.pi.saveLogin(env, material, f.pi.snapshot(NONE).generation(), false, NONE);
            }
            reopened.selectPiModel(env, model("deepseek"), true, NONE);
            assertThat(service(f.definitions, f.pi, f.leases, false).hasUsableDefaultSelection(NONE)).isTrue();
            f.service.logoutPi(env, NONE);
            var afterLogout = service(f.definitions, f.pi, f.leases, false);
            assertThat(afterLogout.hasUsableDefaultSelection(NONE)).isFalse();
            assertThatThrownBy(afterLogout::routingSelection).isInstanceOf(ProviderAuthException.class);
            // 已配置 Pi 默认也不能由不具备 Pi Store 的旧构造器回退。
            var legacyOnly = new ProviderAuthApplicationService(f.definitions, forbiddenLegacy(),
                    migration(f.definitions), Map.of());
            assertThatThrownBy(legacyOnly::routingSelection).isInstanceOf(ProviderAuthException.class);
        }
    }

    @Test void missingExactProfileAndCorruptPiTagNeverUseAnotherProfileOrLegacy() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            save(f, identity("openai", "other"));
            var missing = new ProviderDefinitionStore.DefaultSelection("openai", model("openai"),
                    "pi", "API_KEY", Optional.of("missing"));
            f.definitions.selectDefault(Optional.of(missing), 0, NONE);
            assertThat(f.service.hasUsableDefaultSelection(NONE)).isFalse();
            assertThatThrownBy(f.service::routingSelection).isInstanceOfSatisfying(ProviderAuthException.class,
                    error -> { assertThat(error.code()).isEqualTo(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN);
                        assertThat(error.getCause()).isNull(); });
            Path file = home.resolve(".cc-java/providers.v1.json");
            String json = Files.readString(file);
            Files.writeString(file, json.replace("\"backend\":\"pi\"", "\"backend\":\"spring-ai\""));
            assertThatThrownBy(f.service::routingSelection).isInstanceOfSatisfying(ProviderAuthException.class,
                    error -> assertThat(error.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_CORRUPT));
            assertThat(f.service.nextSelection()).isEmpty();
        }
    }

    @Test void revokedDefaultDoesNotActivateOrFallbackAndKeepsLkg() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            var identity = identity("openai", "explicit");
            save(f, identity);
            var selected = f.service.selectPiModel(identity, model("openai"), true, NONE);
            f.leases.fenceAndDrain(identity, Duration.ZERO, NONE);
            var reopened = service(f.definitions, f.pi, f.leases, false);
            assertThat(reopened.hasUsableDefaultSelection(NONE)).isFalse();
            assertThatThrownBy(reopened::routingSelection).isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(() -> f.service.selectPiModel(identity, model("openai"), true, NONE))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(f.service.nextSelection()).contains(selected);
            assertThat(f.leases.fenced(identity)).isTrue();
        }
    }

    @Test void definitionGenerationCasConflictKeepsBothLkgAndWinningDefault() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            var first = identity("openai", "first");
            var second = identity("openai", "second");
            save(f, first); save(f, second);
            var lkg = f.service.selectPiModel(first, model("openai"), true, NONE);
            AtomicBoolean raced = new AtomicBoolean();
            // 在 Definition 快照之后、Pi 元数据读取取消检查处注入竞争写；不依赖计时或秘密版本。
            CancellationToken racing = new CancellationToken() {
                public boolean isCancellationRequested() {
                    boolean piRead = StackWalker.getInstance().walk(frames -> frames.anyMatch(
                            frame -> frame.getClassName().equals(PiCredentialStore.class.getName())));
                    if (piRead && raced.compareAndSet(false, true)) {
                        var current = f.definitions.snapshot(NONE);
                        f.definitions.selectDefault(current.defaultSelection(), current.generation(), NONE);
                    }
                    return false;
                }
                public Registration onCancellation(Runnable action) { return () -> { }; }
            };
            assertThatThrownBy(() -> f.service.selectPiModel(second, model("openai"), true, racing))
                    .isInstanceOfSatisfying(ProviderAuthException.class,
                            error -> assertThat(error.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
            assertThat(raced).isTrue();
            assertThat(f.service.nextSelection()).contains(lkg);
            assertThat(f.definitions.snapshot(NONE).defaultSelection().orElseThrow().profileId()).contains("first");
        }
    }

    @Test void activeRunAndClosedAuthRejectSelectionWithoutPublishingDefault() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            var identity = identity("openai", "explicit");
            save(f, identity);
            var before = f.definitions.snapshot(NONE);
            try (var run = f.service.beginRun()) {
                assertThatThrownBy(() -> f.service.selectPiModel(identity, model("openai"), true, NONE))
                        .isInstanceOf(ProviderAuthException.class);
                assertThat(f.service.nextSelection()).isEmpty();
            }
            f.service.closePiLogin();
            assertThatThrownBy(() -> f.service.selectPiModel(identity, model("openai"), true, NONE))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(f.definitions.snapshot(NONE)).isEqualTo(before);
        }
    }

    @Test void loginAndLogoutDoNotRewritePiSelectionOrDefault() throws Exception {
        var f = fixture(true);
        try (var ignored = f.leases) {
            var first = identity("openai", "first");
            var second = identity("openai", "second");
            save(f, first);
            var selected = f.service.selectPiModel(first, model("openai"), true, NONE);
            var before = f.definitions.snapshot(NONE);
            f.service.loginPi(second, UNUSED, NONE);
            assertThat(f.service.nextSelection()).contains(selected);
            f.service.logoutPi(first, NONE);
            assertThat(f.service.nextSelection()).contains(selected);
            assertThat(f.definitions.snapshot(NONE)).isEqualTo(before);
            assertThat(f.pi.snapshot(NONE).providerDefaults()).isEmpty();
        }
    }

    @Test void sameNamedLegacyProviderIsNotMarkedDefaultAndUnknownModelDoesNotChangeLkg() throws Exception {
        var f = fixture(false);
        try (var ignored = f.leases) {
            f.service.addCompatibleProvider(new ProviderAuthApplicationService.AddProviderRequest(
                    "openai", "Synthetic", "https://synthetic.example/v1", model("openai")), NONE);
            var identity = identity("openai", "explicit");
            save(f, identity);
            var selected = f.service.selectPiModel(identity, model("openai"), true, NONE);
            assertThat(f.service.listProviders(NONE).stream().filter(value -> value.providerId().equals("openai")))
                    .allSatisfy(value -> assertThat(value.selectedDefault()).isFalse());
            assertThatThrownBy(() -> f.service.selectPiModel(identity, "unknown-model", true, NONE))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(f.service.nextSelection()).contains(selected);
        }
    }

    private Fixture fixture(boolean fakeLogin) throws Exception {
        var definitions = new ProviderDefinitionStore(home);
        var pi = new PiCredentialStore(home);
        var leases = new CredentialLeaseRegistry();
        return new Fixture(definitions, pi, leases, service(definitions, pi, leases, fakeLogin));
    }
    private ProviderAuthApplicationService service(ProviderDefinitionStore definitions, PiCredentialStore pi,
                                                    CredentialLeaseRegistry leases, boolean fakeLogin) {
        return new ProviderAuthApplicationService(definitions, forbiddenLegacy(), migration(definitions), Map.of(),
                leases, (d, m, s, timeout, token) -> { throw new AssertionError("PROBE_FORBIDDEN"); }, Clock.systemUTC(),
                pi, () -> { if (fakeLogin) return null; throw new AssertionError("NODE_CONFIG_FORBIDDEN"); },
                (configuration, store, identity, expected, interaction, token, timeout) -> {
                    if (!fakeLogin) throw new AssertionError("NODE_FORBIDDEN");
                    return new ProviderAuthApplicationService.PiOperation() {
                        public PiLoginOperation.Receipt run() {
                            try (var material = PiCredentialMaterial.apiKey(bytes())) {
                                var saved = store.saveLogin(identity, material, expected, false, token);
                                return new PiLoginOperation.Receipt(identity, saved.authEpoch());
                            }
                        }
                        public void close() { }
                    };
                });
    }
    private LegacyCredentialMigrationService migration(ProviderDefinitionStore definitions) {
        return new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(home), definitions, forbiddenLegacy());
    }
    private static CredentialStore forbiddenLegacy() {
        return (CredentialStore) Proxy.newProxyInstance(CredentialStore.class.getClassLoader(), new Class<?>[]{CredentialStore.class},
                (proxy, method, arguments) -> { throw new AssertionError("LEGACY_STORE_FORBIDDEN"); });
    }
    private static void save(Fixture f, PiCredentialIdentity identity) {
        try (var material = identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH
                ? PiCredentialMaterial.oauth(bytes(), bytes(), 2_000_000_000_000L, bytes()) : PiCredentialMaterial.apiKey(bytes())) {
            f.pi.saveLogin(identity, material, f.pi.snapshot(NONE).generation(), false, NONE);
        }
    }
    private static byte[] bytes() { return "synthetic-material".getBytes(StandardCharsets.US_ASCII); }
    private static PiCredentialIdentity identity(String provider, String profile) {
        return new PiCredentialIdentity(provider, provider.equals("openai-codex")
                ? PiCredentialIdentity.AuthMethod.OAUTH : PiCredentialIdentity.AuthMethod.API_KEY, profile);
    }
    private static String model(String provider) { return new PiProviderCatalog().require(provider).models().getFirst().id(); }
    private record Fixture(ProviderDefinitionStore definitions, PiCredentialStore pi, CredentialLeaseRegistry leases,
                           ProviderAuthApplicationService service) { }
}
