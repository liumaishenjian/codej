package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConnection;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.liumaishenjian.ccjava.cli.auth.PiCredentialRpcSession.Purpose.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S15 / MODEL-13 / ADR-100 §3.2 的真实 Java+Node+公开 Pi SDK 进程集成证据。
 *
 * <p>仅显式绝对 Node 路径 opt-in；缺失则 skip，绝不算真实通过。所有材料与 userHome 合成。
 * 使用生产 Connection、双端 RPC 和持久 Store；fixture 不是生产 catalog worker 的认证业务。
 * 公开 Provider 的 OAuth refresh 为独立 Fake，不证明真实 OAuth/网络认证。
 * LOGIN 场景使用生产 startAuthentication 的物理限额；这不证明生产 Worker 已接入认证业务。
 * 本批不提升能力等级；参考授权机制研究沿用主任务 ADR-100，不复制参考表达。</p>
 */
class PiCredentialRpcProcessTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CancellationToken NONE = CancellationToken.none();
    private static final String OP = "credential-process-test";
    private static final Function<String, byte[]> NO_ENV = name -> { throw new AssertionError("ENV_FORBIDDEN"); };
    @TempDir Path temporary;
    private Path home;
    private PiWorkerConfiguration configuration;

    @BeforeEach void prepareTrustedFixtureOnlyWithExplicitNodeOptIn() throws Exception {
        String executable = System.getProperty("codej.test.nodeExecutable");
        assumeTrue(executable != null && !executable.isBlank(),
                "REAL_NODE_NOT_ENABLED: set absolute codej.test.nodeExecutable; skip is not integration evidence");
        Path node = Path.of(executable);
        assertThat(node.isAbsolute() && Files.isRegularFile(node)).as("EXPLICIT_NODE_VALID").isTrue();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("cc-java-provider-pi"))) root = root.getParent();
        assertThat(root != null).as("COMPONENT_FOUND").isTrue();
        Path component = root.resolve("cc-java-provider-pi").toRealPath();
        Path fixture = Files.createDirectory(temporary.resolve("trusted-fixture"));
        for (String name : List.of("worker.mjs", "resolve-public.mjs")) {
            try (var input = getClass().getResourceAsStream("/pi-credential-process/" + name)) {
                assertThat(input != null).isTrue();
                Files.copy(input, fixture.resolve(name));
            }
        }
        // 只生成受信安装 URL，不含凭证、用户配置或运行时环境。
        Files.writeString(fixture.resolve("bindings.mjs"),
                "export const component=" + JSON.writeValueAsString(component.toString()) + ";\n"
                + binding("rpcUrl", component.resolve("credential-rpc.mjs"))
                + binding("storeUrl", component.resolve("credential-store.mjs"))
                + binding("protocolUrl", component.resolve("worker-protocol.mjs"))
                + binding("registryUrl", component.resolve("provider-registry.mjs")));
        configuration = new PiWorkerConfiguration(node, fixture.resolve("worker.mjs"), Map.of());
        home = Files.createDirectory(temporary.resolve("isolated-home"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "deepseek", "qwen-token-plan-cn"})
    void publicApiLoginPersistsAfterHostAckAndSurvivesFreshStore(String providerId) throws Exception {
        var store = new PiCredentialStore(home);
        var identity = new PiCredentialIdentity("pi", providerId, PiCredentialIdentity.AuthMethod.API_KEY, "default");
        var result = exchange("login", store, identity, LOGIN, 0, NO_ENV, () -> {});
        completed(result, 0, 1);
        assertThat(result.actions()).containsExactly("begin", "finish");
        var reopened = new PiCredentialStore(home);
        var metadata = reopened.snapshot(NONE).find(identity).orElseThrow();
        assertThat(metadata.authEpoch()).isEqualTo(1);
        assertThat(metadata.materialRevision()).isEqualTo(1);
        assertThat(reopened.snapshot(NONE).providerDefaults().get(providerId).equals("default")).isTrue();
        materialMatches(reopened, identity, metadata.authEpoch(), "key", "synthetic-process-key");
        artifacts(1, 1);
    }

    @Test void readAfterLoginAckFailsFixedGenerationButDoesNotPretendPublishedMaterialWasRolledBack() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(false);
        var result = exchange("login-read-after-ack", store, identity, LOGIN, 0, NO_ENV, () -> {});
        failed(result);
        assertThat(result.actions()).containsExactly("begin", "finish", "read");
        assertThat(result.rejections()).containsExactly("CONFLICT");
        var reopened = new PiCredentialStore(home);
        var metadata = reopened.snapshot(NONE).find(identity).orElseThrow();
        materialMatches(reopened, identity, metadata.authEpoch(), "key", "synthetic-process-key");
        artifacts(1, 1);
    }

    @Test void publicModelsGetAuthRefreshUsesRealReadModifyAndAuthoritativePersistentAck() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(true);
        var before = seedOAuth(store, identity);
        var result = exchange("refresh", store, identity, MODEL, before.authEpoch(), NO_ENV, () -> {});
        completed(result, 1, 0);
        assertThat(result.actions()).containsSubsequence("read", "begin", "finish", "read");
        var reopened = new PiCredentialStore(home);
        var after = reopened.snapshot(NONE).find(identity).orElseThrow();
        assertThat(after.authEpoch()).isEqualTo(before.authEpoch());
        assertThat(after.materialRevision()).isEqualTo(before.materialRevision() + 1);
        assertThat(after.secretRef().equals(before.secretRef())).isFalse();
        materialMatches(reopened, identity, after.authEpoch(), "access", "synthetic-new-access");
        materialMatches(reopened, identity, after.authEpoch(), "refresh", "synthetic-new-refresh");
        artifacts(1, 1);
    }

    @Test void deleteBeforeNodeSendsFinishRejectsLateRefreshAndCannotResurrectMaterial() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(true);
        var before = seedOAuth(store, identity);
        var result = exchange("late-delete", store, identity, MODEL, before.authEpoch(), NO_ENV,
                () -> store.delete(identity, before.authEpoch(), NONE));
        failed(result);
        assertThat(result.rejections()).contains("CONFLICT");
        assertThat(result.actions()).containsSubsequence("begin", "finish").doesNotContain("abort");
        assertThat(new PiCredentialStore(home).snapshot(NONE).find(identity).isEmpty()).isTrue();
        artifacts(0, 0);
    }

    @Test void suddenNodeExitAfterBeginIsNotCompletedAndCloseReleasesIdentityTransaction() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(true);
        var before = seedOAuth(store, identity);
        var result = exchange("crash", store, identity, MODEL, before.authEpoch(), NO_ENV, () -> {});
        assertThat(result.terminal()).isEqualTo("NONE");
        assertThat(result.transportFailure()).isEqualTo("PROTOCOL_INVALID");
        assertThat(result.actions()).contains("begin").doesNotContain("finish");
        // exchange 的 finally 已关闭 RPC 与进程；新 store 的同身份条带必须可再次取得。
        try (var transaction = new PiCredentialStore(home).beginModify(identity, before.authEpoch(), NONE);
                var acknowledged = transaction.finish(PiCredentialStore.Change.KEEP, null, NONE)) {
            assertThat(acknowledged.kind()).isEqualTo(PiCredentialMaterial.Kind.OAUTH);
        }
        materialMatches(store, identity, before.authEpoch(), "access", "synthetic-old-access");
        artifacts(1, 1);
    }

    @Test void explicitEnvironmentReferenceExportsTemporarilyWithoutSecretPersistence() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(false);
        PiCredentialStore.Metadata before;
        try (var material = PiCredentialMaterial.envRef("SYNTHETIC_TEST_KEY")) {
            before = store.saveLogin(identity, material, 0, true, NONE);
        }
        var exports = new ArrayList<byte[]>();
        var result = exchange("env", store, identity, MODEL, before.authEpoch(), name -> {
            assertThat(name.equals("SYNTHETIC_TEST_KEY")).isTrue();
            byte[] bytes = ascii("synthetic-process-key"); exports.add(bytes); return bytes;
        }, () -> {});
        completed(result, 0, 0);
        assertThat(result.actions()).doesNotContain("begin", "finish");
        assertThat(exports.size()).isGreaterThanOrEqualTo(2);
        assertThat(exports.stream().allMatch(value -> Arrays.equals(value, new byte[value.length]))).isTrue();
        try (var persisted = new PiCredentialStore(home).read(identity, before.authEpoch(), NONE)) {
            assertThat(persisted.kind()).isEqualTo(PiCredentialMaterial.Kind.ENV_REF);
        }
        assertThat(store.snapshot(NONE).generation()).isEqualTo(1);
        artifacts(1, 0);
    }

    @Test void fakeProviderRefreshFailureAbortsAndNeverReportsCompleted() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(true);
        var before = seedOAuth(store, identity);
        var result = exchange("refresh-failure", store, identity, MODEL, before.authEpoch(), NO_ENV, () -> {});
        failed(result);
        assertThat(result.actions()).containsSubsequence("begin", "abort").doesNotContain("finish");
        assertThat(store.snapshot(NONE).find(identity).orElseThrow().materialRevision()).isEqualTo(1);
        materialMatches(store, identity, before.authEpoch(), "access", "synthetic-old-access");
        artifacts(1, 1);
    }

    @Test void unavailableEnvironmentFailsClosedWithoutCompletingOrPersisting() throws Exception {
        var store = new PiCredentialStore(home);
        var identity = identity(false);
        try (var material = PiCredentialMaterial.envRef("SYNTHETIC_TEST_KEY")) {
            store.saveLogin(identity, material, 0, true, NONE);
        }
        var result = exchange("env", store, identity, MODEL, 1, name -> null, () -> {});
        failed(result);
        assertThat(result.rejections()).contains("UNAVAILABLE");
        artifacts(1, 0);
    }

    /** 实际串行生产分发；不伪造 credential.response，异常路径也必须关闭双方。 */
    private Outcome exchange(String scenario, PiCredentialStore store, PiCredentialIdentity identity,
            PiCredentialRpcSession.Purpose purpose, long epoch, Function<String, byte[]> environment,
            Runnable beforeFinish) {
        List<String> actions = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        String terminal = "NONE", transportFailure = "NONE";
        ObjectNode summary = JSON.createObjectNode();
        int exit = -1;
        try (var session = new PiCredentialRpcSession(OP, identity, purpose, store, epoch,
                    purpose == LOGIN, environment, NONE);
                var connection = purpose == LOGIN
                        ? PiWorkerConnection.startAuthentication(configuration, OP, NONE, Duration.ofSeconds(25))
                        : PiWorkerConnection.start(configuration, OP, NONE, Duration.ofSeconds(25))) {
            connection.send("operation.start", JSON.createObjectNode().put("scenario", scenario)
                    .put("providerId", identity.providerId()));
            try {
                while (true) {
                    var frame = connection.receive();
                    if (frame.type().equals("credential.request")) {
                        actions.add(frame.payload().path("action").asString());
                        ObjectNode response = session.handle(frame);
                        if (!response.path("ok").asBoolean()) rejections.add(response.path("code").asString());
                        connection.send("credential.response", response);
                    } else if (frame.type().equals("fixture.before_finish")) {
                        assertThat(scenario).isEqualTo("late-delete");
                        beforeFinish.run();
                        connection.send("fixture.continue", JSON.createObjectNode());
                    } else {
                        assertThat(frame.type()).isIn("operation.completed", "operation.failed");
                        terminal = frame.type(); summary = frame.payload();
                        // 不把正文/材料节点交给断言诊断；终态严格只有布尔、计数和封闭码。
                        assertThat(summary.properties().stream().allMatch(entry -> entry.getValue().isBoolean()
                                || entry.getValue().isIntegralNumber()
                                || entry.getKey().equals("code") && entry.getValue().asString().equals("FIXTURE_FAILED"))).isTrue();
                        exit = connection.awaitExit();
                        break;
                    }
                }
            } catch (PiWorkerException failure) {
                transportFailure = failure.code().name();
            }
        }
        return new Outcome(terminal, exit, summary, List.copyOf(actions), List.copyOf(rejections), transportFailure);
    }

    private static void completed(Outcome result, int refreshes, int prompts) {
        assertThat(result.transportFailure()).isEqualTo("NONE");
        assertThat(result.terminal()).as("terminal; actions=%s; closedCodes=%s; counters=%s",
                result.actions(), result.rejections(), result.summary()).isEqualTo("operation.completed");
        assertThat(result.exit()).isZero();
        assertThat(result.summary().path("verified").asBoolean()).isTrue();
        assertThat(result.summary().path("networkCalls").asInt(-1)).isZero();
        assertThat(result.summary().path("refreshCalls").asInt(-1)).isEqualTo(refreshes);
        assertThat(result.summary().path("prompts").asInt(-1)).isEqualTo(prompts);
        assertThat(result.summary().path("reads").asInt(-1)).isEqualTo(prompts == 1 ? 0 : 1);
        assertThat(result.rejections()).isEmpty();
    }
    private static void failed(Outcome result) {
        assertThat(result.transportFailure()).isEqualTo("NONE");
        assertThat(result.terminal()).isEqualTo("operation.failed");
        assertThat(result.exit()).isEqualTo(7);
        assertThat(result.summary().path("verified").asBoolean()).isFalse();
        assertThat(result.summary().path("networkCalls").asInt(-1)).isZero();
    }
    private static PiCredentialIdentity identity(boolean oauth) {
        return new PiCredentialIdentity("pi", oauth ? "openai-codex" : "openai",
                oauth ? PiCredentialIdentity.AuthMethod.OAUTH : PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }
    private static PiCredentialStore.Metadata seedOAuth(PiCredentialStore store, PiCredentialIdentity identity) {
        try (var material = PiCredentialMaterial.oauth(ascii("synthetic-old-access"), ascii("synthetic-old-refresh"),
                1, ascii("synthetic-account"))) {
            return store.saveLogin(identity, material, 0, true, NONE);
        }
    }
    private static void materialMatches(PiCredentialStore store, PiCredentialIdentity identity, long epoch,
            String field, String expected) {
        try (var material = store.read(identity, epoch, NONE)) {
            byte[] bytes = material.copyJson();
            try { assertThat(JSON.readTree(bytes).path(field).asString().equals(expected)).as("MATERIAL_MATCH").isTrue(); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }
    }
    /** 核对真正最终文件：索引无秘密、引用数等于材料数，无 journal 或孤儿材料。 */
    private void artifacts(int profiles, int secrets) throws Exception {
        Path auth = new RestrictedFileSecurity(home).root().resolve("auth/pi");
        byte[] index = Files.readAllBytes(auth.resolve("index.v1.json"));
        try {
            String text = new String(index, StandardCharsets.UTF_8);
            assertThat(!text.contains("synthetic-process-key") && !text.contains("synthetic-old-access")
                    && !text.contains("synthetic-new-access") && !text.contains("synthetic-new-refresh")
                    && !text.contains("synthetic-old-refresh")).as("INDEX_NO_SECRET").isTrue();
            assertThat(JSON.readTree(index).path("profiles").size()).isEqualTo(profiles);
        } finally { Arrays.fill(index, (byte) 0); }
        try (var files = Files.list(auth.resolve("secrets"))) { assertThat(files.count()).isEqualTo(secrets); }
        assertThat(Files.exists(auth.resolve(".txn.v1.json"))).isFalse();
    }
    private static String binding(String name, Path path) {
        return "export const " + name + "=" + JSON.writeValueAsString(path.toUri().toString()) + ";\n";
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private record Outcome(String terminal, int exit, ObjectNode summary, List<String> actions,
            List<String> rejections, String transportFailure) { }
}
