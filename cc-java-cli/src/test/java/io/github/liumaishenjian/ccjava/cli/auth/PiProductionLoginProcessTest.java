package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 使用真正worker.mjs的三家API_KEY本地录入链路；不使用账户、不运行模型或OAuth网络流程。
 * <p>公开Pi0.85.1的API Key登录只提示和保存，前序带网络禁用的SDK Fixture已验证这一边界。
 * 本测试使用合成输入与真实Java持久RPC；Java循环仍为测试装配，不能描述为CLI/TUI已接入。</p>
 */
class PiProductionLoginProcessTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CancellationToken NONE = CancellationToken.none();
    private static final String OP = "production-login-test";
    @TempDir Path home;
    private PiWorkerConfiguration configuration;

    @BeforeEach void explicitTrustedNodeOnly() throws Exception {
        String executable = System.getProperty("codej.test.nodeExecutable");
        assumeTrue(executable != null && !executable.isBlank(), "REAL_NODE_NOT_ENABLED");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("cc-java-provider-pi"))) root = root.getParent();
        assertThat(root).isNotNull();
        configuration = new PiWorkerConfiguration(Path.of(executable),
                root.resolve("cc-java-provider-pi/worker.mjs"), Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "deepseek", "qwen-token-plan-cn"})
    void productionWorkerStoresSyntheticKeyThroughJavaAckAndRealEof(String providerId) {
        var identity = identity(providerId);
        Outcome outcome = run(identity, "save");
        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.exit()).isZero();
        assertThat(outcome.prompts()).isEqualTo(1);
        assertThat(outcome.commits()).isEqualTo(1);
        var reopened = new PiCredentialStore(home);
        var metadata = reopened.snapshot(NONE).find(identity).orElseThrow();
        assertThat(metadata.authEpoch()).isEqualTo(1);
        try (var material = reopened.read(identity, metadata.authEpoch(), NONE)) {
            byte[] bytes = material.copyJson();
            try {assertThat(JSON.readTree(bytes).path("key").asString().equals("synthetic-production-key")).isTrue();}
            finally {Arrays.fill(bytes, (byte) 0);}
        }
    }

    @Test void cancelledSecretPromptDoesNotCreateCredentialsOrComplete() {
        var identity = identity("openai");
        var outcome = run(identity, "cancel");
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(outcome.commits()).isZero();
        assertThat(new PiCredentialStore(home).snapshot(NONE).find(identity)).isEmpty();
    }

    @Test void inputTimeGenerationConflictFailsWithoutSavingTargetIdentity() {
        var identity = identity("openai");
        var outcome = run(identity, "conflict");
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.exit()).isNotZero();
        assertThat(outcome.commits()).isZero();
        assertThat(new PiCredentialStore(home).snapshot(NONE).find(identity)).isEmpty();
    }

    private Outcome run(PiCredentialIdentity identity, String mode) {
        var store = new PiCredentialStore(home);
        int prompts = 0, commits = 0;
        boolean ready = false, stored = false;
        try (var session = new PiCredentialRpcSession(OP, identity, PiCredentialRpcSession.Purpose.LOGIN,
                    store, 0, false, name -> {throw new AssertionError("ENV_FORBIDDEN");}, NONE);
                var connection = PiWorkerConnection.startAuthentication(configuration, OP, NONE, Duration.ofSeconds(20))) {
            connection.send("operation.start", JSON.createObjectNode().put("operation", "auth.login")
                    .put("providerId", identity.providerId()).put("authType", "api_key"));
            while (true) {
                var frame = connection.receive();
                ObjectNode payload = frame.payload();
                switch (frame.type()) {
                    case "operation.ready" -> {
                        assertThat(ready).isFalse(); ready = true;
                        assertThat(payload.path("piVersion").asString()).isEqualTo("0.85.1");
                        assertThat(payload.path("operations").toString().contains("auth.login")).isTrue();
                    }
                    case "auth.prompt" -> {
                        assertThat(ready).isTrue(); prompts++;
                        assertThat(payload.path("kind").asString()).isEqualTo("secret");
                        assertThat(prompts).isEqualTo(1);
                        if (mode.equals("cancel")) connection.send("operation.cancel", JSON.createObjectNode());
                        else {
                            if (mode.equals("conflict")) {
                                try (var material = PiCredentialMaterial.apiKey("synthetic-other".getBytes(StandardCharsets.US_ASCII))) {
                                    store.saveLogin(identity("deepseek"), material, 0, false, NONE);
                                }
                            }
                            connection.send("auth.response", JSON.createObjectNode()
                                    .put("promptId", payload.path("promptId").asInt()).put("value", "synthetic-production-key"));
                        }
                    }
                    case "credential.request" -> {
                        assertThat(ready).isTrue();
                        var response = session.handle(frame);
                        if (payload.path("action").asString().equals("finish") && response.path("ok").asBoolean()) commits++;
                        connection.send("credential.response", response);
                    }
                    case "auth.result" -> {
                        assertThat(stored).isFalse(); stored = true;
                        assertThat(commits).isEqualTo(1);
                        assertThat(payload.size()).isEqualTo(3);
                        assertThat(payload.path("providerId").asString()).isEqualTo(identity.providerId());
                        assertThat(payload.path("authType").asString()).isEqualTo("api_key");
                        assertThat(payload.path("status").asString()).isEqualTo("stored");
                    }
                    case "operation.completed", "operation.failed" -> {
                        boolean completed = frame.type().equals("operation.completed");
                        if (completed) assertThat(stored).isTrue();
                        else assertThat(stored).isFalse();
                        return new Outcome(completed, connection.awaitExit(), prompts, commits);
                    }
                    default -> throw new AssertionError("UNEXPECTED_PRIVATE_FRAME");
                }
            }
        }
    }
    private static PiCredentialIdentity identity(String providerId) {
        return new PiCredentialIdentity("pi", providerId, PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }
    private record Outcome(boolean completed, int exit, int prompts, int commits) { }
}
