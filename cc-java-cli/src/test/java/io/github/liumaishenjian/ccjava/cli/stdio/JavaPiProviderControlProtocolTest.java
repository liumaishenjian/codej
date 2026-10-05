package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.runtime.*;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** ADR-100 真实 Handler 与临时服务/存储的离线控制边界；不运行模型、不启动 Node。 */
class JavaPiProviderControlProtocolTest {
    private static final CancellationToken NONE = CancellationToken.none();
    private static final String ENV = "CODEJ_SYNTHETIC_ENV_CANARY";
    private static final PiCredentialIdentity ID = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "default");
    private final StdioProtocolCodec codec = new StdioProtocolCodec();
    @TempDir Path temporary;
    private int next;

    @Test void noServiceDoesNotAdvertisePiOrAuthLifecycle() throws Exception {
        try (var handler = new RuntimeStdioCommandHandler(r -> ModelTurn.text("unused"), options())) {
            var events = new Capture();
            handler.handle(init("{\"authLifecycleV1\":true,\"piProviderV1\":true}"), events);
            assertThat(events.payload.has("authLifecycleV1")).isFalse();
            assertThat(events.payload.has("piProviderV1")).isFalse();
            assertThat(events.payload.has("piWorkerAvailable")).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"authLifecycleV1\":true}", "{\"piProviderV1\":true}",
            "{\"authLifecycleV1\":false,\"piProviderV1\":true}"})
    void capabilityMustBeRequestedWithDependency(String flags) throws Exception {
        var f = fixture(true);
        try (var handler = handler(f.service)) {
            var events = new Capture(); handler.handle(init(flags), events);
            assertThat(events.payload.has("piProviderV1")).isFalse();
            assertThat(events.payload.has("piWorkerAvailable")).isFalse();
            assertThat(f.configurationReads.get()).isZero();
            assertThatThrownBy(() -> handler.handle(control(events.session, "auth.list", "{\"backend\":\"pi\"}"), events))
                    .isInstanceOf(StdioProtocolException.class);
        }
    }

    @Test void legacyOnlyServiceCannotNegotiatePi() throws Exception {
        var f = fixture(false);
        try (var handler = handler(f.service)) {
            var events = new Capture(); handler.handle(init("{\"authLifecycleV1\":true,\"piProviderV1\":true}"), events);
            assertThat(events.payload.get("authLifecycleV1").booleanValue()).isTrue();
            assertThat(events.payload.has("piProviderV1")).isFalse();
            assertThat(events.payload.has("piWorkerAvailable")).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "1", "\"true\""})
    void piInitializeFlagMustBeBoolean(String flag) throws Exception {
        try (var handler = new RuntimeStdioCommandHandler(r -> ModelTurn.text("unused"), options())) {
            assertThatThrownBy(() -> handler.handle(init("{\"piProviderV1\":" + flag + "}"), new Capture()))
                    .isInstanceOf(StdioProtocolException.class);
        }
    }

    @Test void missingComponentStillAdvertisesAllFourRoutesAndModelsWithoutSecrets() throws Exception {
        var f = fixture(true);
        try (var handler = handler(f.service)) {
            var events = initialized(handler);
            assertThat(events.payload.get("piProviderV1").booleanValue()).isTrue();
            assertThat(events.payload.get("piWorkerAvailable").booleanValue()).isFalse();
            handler.handle(control(events.session, "providers.catalog", "{\"backend\":\"pi\"}"), events);
            assertThat(events.payload.get("status").stringValue()).isEqualTo("succeeded");
            var providers = events.payload.get("result").get("providers");
            assertThat(providers.size()).isEqualTo(4);
            var ids = new ArrayList<String>();
            for (var provider : providers) {
                ids.add(provider.get("providerId").stringValue());
                assertThat(provider.get("backend").stringValue()).isEqualTo("pi");
                assertThat(provider.get("componentAvailable").booleanValue()).isFalse();
                assertThat(provider.get("authMethods").get(0).stringValue()).isEqualTo(
                        provider.get("providerId").stringValue().equals("openai-codex") ? "OAUTH" : "API_KEY");
                assertThat(provider.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                        "backend", "providerId", "brandId", "label", "componentAvailable", "authMethods");
            }
            assertThat(ids).containsExactly("openai", "openai-codex", "deepseek", "qwen-token-plan-cn");
            handler.handle(control(events.session, "models.list", "{\"backend\":\"pi\"}"), events);
            assertThat(events.payload.get("result").get("models").size()).isGreaterThan(0);
            assertThat(events.payload.toString()).doesNotContain("authEpoch", ENV, "accountId", "https://", "secret");
            assertThat(f.configurationReads.get()).isEqualTo(2);
        }
    }

    @Test void backendSameNameProfilesStayIsolatedAndMetadataNeverReadsSecrets() throws Exception {
        var f = fixture(true);
        f.legacy.saveEnv("openai", "default", ENV, false, NONE);
        f.service.loginPiEnvironment(ID, ENV, NONE);
        var oauth = new PiCredentialIdentity("openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, "default");
        try (var material = PiCredentialMaterial.oauth(bytes("access-canary"), bytes("refresh-canary"),
                4_000_000_000_000L, bytes("account-canary"))) {
            f.store.saveLogin(oauth, material, f.store.snapshot(NONE).generation(), false, NONE);
        }
        // 删除秘密后列表仍可读；元数据查询不能暗中验证或刷新 OAuth。
        var metadata = f.store.snapshot(NONE).find(oauth).orElseThrow();
        Files.delete(f.home.resolve(".cc-java/auth/pi/secrets/" + metadata.secretRef().orElseThrow() + ".json"));
        try (var handler = handler(f.service)) {
            var events = initialized(handler);
            handler.handle(control(events.session, "auth.list", "{}"), events);
            var legacy = events.payload.get("result").get("profiles");
            assertThat(legacy.size()).isEqualTo(1);
            assertThat(legacy.get(0).has("backend")).isFalse();
            assertThat(legacy.get(0).get("refKind").stringValue()).isEqualTo("ENV");
            handler.handle(control(events.session, "auth.list", "{\"backend\":\"pi\"}"), events);
            var profiles = events.payload.get("result").get("profiles");
            assertThat(profiles.size()).isEqualTo(2);
            for (var item : profiles) {
                assertThat(item.get("backend").stringValue()).isEqualTo("pi");
                assertThat(item.get("providerDefault").booleanValue()).isFalse();
                assertThat(item.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                        "backend", "providerId", "profileId", "authMethod", "refKind", "localStatus", "providerDefault");
            }
            assertThat(events.payload.toString()).doesNotContain(ENV, "canary", "authEpoch", "accountId", "url", "secretRef");
        }
    }

    @Test void selectionProjectsOnlyIdentityModelAndDefaultAndNeverFallsBackToLegacy() throws Exception {
        var f = fixture(true);
        f.legacy.saveEnv("openai", "default", ENV, false, NONE);
        String model = new io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog().require("openai").models().getFirst().id();
        String args = identity("\"modelId\":\"" + model + "\",\"setDefault\":false");
        try (var handler = handler(f.service)) {
            var events = initialized(handler);
            handler.handle(control(events.session, "models.use", args), events);
            assertThat(events.payload.get("status").stringValue()).isEqualTo("rejected");
            assertThat(f.service.nextSelection()).isEmpty();
            f.service.loginPiEnvironment(ID, ENV, NONE);
            handler.handle(control(events.session, "models.use", args), events);
            assertThat(events.payload.get("status").stringValue()).isEqualTo("succeeded");
            assertThat(events.payload.get("result").properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                    "backend", "providerId", "profileId", "authMethod", "modelId", "setDefault");
            assertThat(events.payload.get("result").get("modelId").stringValue()).isEqualTo(model);
            assertThat(events.payload.get("result").get("setDefault").booleanValue()).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"openai", "openai-codex", "deepseek", "qwen-token-plan-cn"})
    void fourRouteIdentityMethodsAreExactAtCodecBoundary(String provider) throws Exception {
        String method = provider.equals("openai-codex") ? "OAUTH" : "API_KEY";
        String target = "{\"backend\":\"pi\",\"providerId\":\"" + provider
                + "\",\"profileId\":\"default\",\"authMethod\":\"" + method + "\",\"authEpoch\":\"9223372036854775807\"}";
        assertThat(control("s", "auth.activate", target).type()).isEqualTo("provider.control");
        assertThatThrownBy(() -> control("s", "auth.activate", target.replace(method, method.equals("OAUTH") ? "API_KEY" : "OAUTH")))
                .isInstanceOf(StdioProtocolException.class);
    }

    @Test void exactEpochBeyondJavascriptIntegerActivatesAndNeverEchoesVersion() throws Exception {
        var f = fixture(true); f.service.loginPiEnvironment(ID, ENV, NONE);
        Path index = f.home.resolve(".cc-java/auth/pi/index.v1.json");
        String original = Files.readString(index);
        String changed = original.replace("\"generation\":1", "\"generation\":9007199254740993")
                .replace("\"authEpoch\":1", "\"authEpoch\":9007199254740993");
        assertThat(changed).isNotEqualTo(original);
        Files.writeString(index, changed, StandardOpenOption.TRUNCATE_EXISTING);
        assertThat(f.store.snapshot(NONE).find(ID).orElseThrow().authEpoch()).isEqualTo(9007199254740993L);
        try (var handler = handler(f.service)) {
            var events = initialized(handler);
            handler.handle(control(events.session, "auth.activate", identity("\"authEpoch\":\"9007199254740993\"")), events);
            assertThat(events.payload.get("status").stringValue()).isEqualTo("succeeded");
            assertThat(events.payload.get("result").properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                    "backend", "providerId", "profileId", "authMethod", "localStatus");
            handler.handle(control(events.session, "auth.activate", identity("\"authEpoch\":\"9007199254740992\"")), events);
            assertThat(events.payload.get("status").stringValue()).isEqualTo("rejected");
        }
    }

    @Test void logoutTicketCannotCrossSessionsOrDeleteLegacySameName() throws Exception {
        var f = fixture(true); f.service.loginPiEnvironment(ID, ENV, NONE);
        f.legacy.saveEnv("openai", "default", ENV, false, NONE);
        try (var first = handler(f.service); var second = handler(f.service)) {
            var a = initialized(first); var b = initialized(second);
            first.handle(control(a.session, "auth.logout.prepare", identity("")), a);
            String ticket = a.payload.get("result").get("confirmationId").stringValue();
            String args = "{\"confirmationId\":\"" + ticket + "\",\"confirmed\":true}";
            second.handle(control(b.session, "auth.logout.commit", args), b);
            assertThat(b.payload.get("status").stringValue()).isEqualTo("rejected");
            assertThat(f.store.snapshot(NONE).find(ID)).isPresent();
            // 错会话可能消耗票据；重新准备，不能自动重试原有副作用。
            first.handle(control(a.session, "auth.logout.prepare", identity("")), a);
            ticket = a.payload.get("result").get("confirmationId").stringValue();
            args = "{\"confirmationId\":\"" + ticket + "\",\"confirmed\":true}";
            first.handle(control(a.session, "auth.logout.commit", args), a);
            assertThat(a.payload.get("status").stringValue()).isEqualTo("succeeded");
            assertThat(a.payload.get("result").properties()).extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("providerId", "profileId", "remoteRevoked");
            assertThat(a.payload.get("result").get("remoteRevoked").booleanValue()).isFalse();
            assertThat(f.store.snapshot(NONE).find(ID)).isEmpty();
            assertThat(f.legacy.snapshot(NONE).profiles()).hasSize(1);
            first.handle(control(a.session, "auth.logout.commit", args), a);
            assertThat(a.payload.get("status").stringValue()).isEqualTo("rejected");
        }
    }

    @Test void codecRejectsInvalidMethodsEpochsSecretFieldsAndUnsupportedPiIntents() {
        for (String epoch : List.of("1", "0", "\"01\"", "\"0\"", "\"1e2\"", "\"9223372036854775808\"")) {
            assertThatThrownBy(() -> control("s", "auth.activate", identity("\"authEpoch\":" + epoch)))
                    .isInstanceOf(StdioProtocolException.class);
        }
        for (String intent : List.of("providers.configure", "providers.add", "models.add", "models.remove", "auth.probe", "auth.logout"))
            assertThatThrownBy(() -> control("s", intent, "{\"backend\":\"pi\"}")) .isInstanceOf(StdioProtocolException.class);
        for (String field : List.of("secret", "environmentName", "accountId", "url"))
            assertThatThrownBy(() -> control("s", "auth.activate", identity("\"authEpoch\":\"1\",\"" + field + "\":\"canary\"")))
                    .isInstanceOf(StdioProtocolException.class);
        assertThatThrownBy(() -> control("s", "auth.activate", identity("\"authEpoch\":\"1\"").replace("API_KEY", "OAUTH")))
                .isInstanceOf(StdioProtocolException.class);
    }

    @Test void directHandlerCallsCannotBypassBackendOrCommitValidation() throws Exception {
        var f = fixture(true);
        try (var handler = handler(f.service)) {
            var events = initialized(handler);
            for (String backend : List.of("unknown", "PI")) {
                var command = control(events.session, "auth.list", "{}");
                ((ObjectNode) command.payload().get("arguments")).put("backend", backend);
                assertThatThrownBy(() -> handler.handle(command, events)).isInstanceOf(StdioProtocolException.class);
            }
            for (String field : List.of("backend", "authMethod", "authEpoch", "secret", "providerId")) {
                var command = control(events.session, "auth.logout.commit", "{\"confirmationId\":\"ticket\",\"confirmed\":true}");
                ((ObjectNode) command.payload().get("arguments")).put(field, field.equals("backend") ? "pi" : "canary");
                assertThatThrownBy(() -> handler.handle(command, events)).isInstanceOf(StdioProtocolException.class);
            }
            var command = control(events.session, "auth.logout.commit", "{\"confirmationId\":\"ticket\",\"confirmed\":true}");
            ((ObjectNode) command.payload().get("arguments")).put("confirmed", false);
            assertThatThrownBy(() -> handler.handle(command, events)).isInstanceOf(StdioProtocolException.class);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String identity(String extra) {
        return "{\"backend\":\"pi\",\"providerId\":\"openai\",\"profileId\":\"default\",\"authMethod\":\"API_KEY\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }
    private StdioProtocol.Command init(String flags) throws Exception {
        return codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":" + flags + "}");
    }
    private StdioProtocol.Command control(String session, String intent, String args) throws StdioProtocolException {
        return codec.decodeCommand("{\"version\":0,\"type\":\"provider.control\",\"requestId\":\"r\",\"sessionId\":\"" + session
                + "\",\"sequence\":2,\"payload\":{\"controlId\":\"c\",\"intent\":\"" + intent + "\",\"arguments\":" + args + "}}");
    }
    private Capture initialized(RuntimeStdioCommandHandler handler) throws Exception {
        var events = new Capture(); handler.handle(init("{\"authLifecycleV1\":true,\"piProviderV1\":true}"), events); return events;
    }
    private HeadlessRuntimeOptions options() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace-" + next++));
        return new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(3), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), temporary.resolve("sessions-" + next++));
    }
    private RuntimeStdioCommandHandler handler(ProviderAuthApplicationService service) throws Exception {
        var options = options();
        return new RuntimeStdioCommandHandler((events, approvals) -> new HeadlessRuntimeSession(
                request -> { throw new AssertionError("MODEL_FORBIDDEN"); }, events, options, approvals), service);
    }
    private Fixture fixture(boolean pi) throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home-" + next++));
        Path repo = Files.createDirectories(temporary.resolve("repo-" + next++));
        var definitions = new ProviderDefinitionStore(home);
        var legacy = new RestrictedFileCredentialStore(home);
        var migration = new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(repo), definitions, legacy);
        var store = new PiCredentialStore(home);
        var reads = new AtomicInteger();
        var service = new ProviderAuthApplicationService(definitions, legacy, migration, Map.of(), new CredentialLeaseRegistry(),
                (d, m, s, timeout, token) -> { throw new AssertionError("PROBE_FORBIDDEN"); }, Clock.systemUTC(),
                pi ? store : null, pi ? () -> { reads.incrementAndGet(); return null; } : null);
        return new Fixture(service, store, legacy, home, reads);
    }
    private record Fixture(ProviderAuthApplicationService service, PiCredentialStore store,
                           RestrictedFileCredentialStore legacy, Path home, AtomicInteger configurationReads) { }
    private static final class Capture implements StdioProtocol.EventEmitter {
        ObjectNode payload; String session;
        public void emit(String type, String requestId, Optional<String> sessionId, Optional<String> runId, ObjectNode value) {
            payload = value.deepCopy(); session = sessionId.orElse(null);
        }
    }
}
