package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** 纯 Fake 双工传输与真实受限 Store；不需要 Node、账号或任何网络。 */
class PiLoginOperationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CancellationToken NONE = CancellationToken.none();
    @TempDir Path home;
    private PiWorkerConfiguration config;
    private PiCredentialStore store;
    private final Handle handle = new Handle();
    private final Handle secondHandle = new Handle();
    private PiCredentialIdentity identity = identity("openai");
    private final Fake fake = new Fake();
    private Consumer<PiLoginOperation.Prompt> onPrompt = prompt -> { };
    private final AtomicInteger urls = new AtomicInteger();
    private long expected;

    @BeforeEach void setup() throws Exception {
        Path node = Files.writeString(home.resolve("node"), "fake");
        Path worker = Files.writeString(home.resolve("worker.mjs"), "fake");
        config = new PiWorkerConfiguration(node, worker, Map.of());
        store = new PiCredentialStore(home);
    }

    @ParameterizedTest @ValueSource(strings = {"openai", "deepseek", "qwen-token-plan-cn"})
    void asyncSecretLoginReturnsExactEpochWithoutChangingDefault(String provider) {
        identity = identity(provider);
        try (var previous = PiCredentialMaterial.apiKey("prior".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            store.saveLogin(new PiCredentialIdentity(provider, PiCredentialIdentity.AuthMethod.API_KEY, "prior"), previous, 0, true, NONE);
        }
        expected = 1;
        SecretMaterial material = secret();
        onPrompt = prompt -> {
            assertThat(prompt.operationId()).isEqualTo(fake.operationId);
            assertThat(prompt.promptId()).isEqualTo(1);
            handle.result.complete(material);
        };
        try (var operation = operation()) {
            assertThat(operation.run()).isEqualTo(new PiLoginOperation.Receipt(identity, 2));
            assertThatThrownBy(operation::run).isInstanceOf(PiLoginOperation.LoginException.class);
        }
        assertErased(material);
        assertThat(fake.responses.get()).isEqualTo(1);
        assertThat(handle.closes.get()).isEqualTo(1);
        assertThat(fake.exitConfirmed).isTrue();
        assertThat(store.snapshot(NONE).providerDefaults()).containsExactly(entry(provider, "prior"));
    }

    @Test void externalCloseDuringFinalReceiptCheckCannotReturnSuccess() {
        var reference = new java.util.concurrent.atomic.AtomicReference<PiLoginOperation>();
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        CancellationToken token = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public java.util.Optional<Duration> remainingTime() {
                // 在内部清理已结束、最终回执尚未发布的确定位置模拟外部关闭。
                if (fake.closed && once.compareAndSet(false, true)) reference.get().close();
                return java.util.Optional.empty();
            }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        onPrompt = prompt -> handle.result.complete(secret());
        try (var operation = operation(token)) {
            reference.set(operation);
            assertSafeFailure(operation, PiLoginOperation.Code.CANCELLED);
        }
        assertThat(once.get()).isTrue();
        // 关闭不等于回滚：本次材料已通过持久ACK发布。
        assertThat(store.snapshot(NONE).find(identity)).isPresent();
    }

    @Test void hangingManualInputCancelledByCallbackDoesNotBlockCredentialRpc() {
        identity = identity("openai-codex");
        fake.callback = true;
        try (var operation = operation()) {
            assertThat(operation.run().authEpoch()).isEqualTo(1);
        }
        assertThat(handle.result).isNotDone();
        assertThat(handle.closes.get()).isEqualTo(1);
        assertThat(fake.responses.get()).isZero();
        assertThat(fake.acks.get()).isEqualTo(1);
        SecretMaterial late = secret();
        handle.result.complete(late);
        assertErased(late);
        assertThat(store.snapshot(NONE).providerDefaults()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"skip-id", "zero-id", "fraction-id", "wrong-kind", "extra-field",
            "duplicate-prompt", "unknown-cancel", "duplicate-cancel", "before-ready", "duplicate-ready", "wrong-version",
            "duplicate-capability", "missing-capability", "wrong-operation", "wrong-sequence", "false-stored",
            "wrong-provider", "wrong-auth", "extra-result", "duplicate-result", "premature-completed", "duplicate-completed"})
    void malformedProtocolFailsClosed(String mode) {
        fake.mode = mode;
        fake.callback = true;
        assertSafeFailure(operation(), PiLoginOperation.Code.PROTOCOL_INVALID);
    }

    @Test void ackThenDisconnectDoesNotClaimRollback() {
        fake.callback = true; fake.mode = "disconnect";
        assertSafeFailure(operation(), PiLoginOperation.Code.FAILED);
        assertThat(fake.acks.get()).isEqualTo(1);
        assertThat(store.snapshot(NONE).find(identity)).isPresent();
    }

    @ParameterizedTest @ValueSource(strings = {"nonzero-exit", "missing-eof"})
    void storedAndCompletedDoNotReplaceRealSuccessfulExit(String mode) {
        fake.callback = true; fake.mode = mode;
        assertSafeFailure(operation(), PiLoginOperation.Code.FAILED);
        assertThat(store.snapshot(NONE).find(identity)).isPresent();
    }

    @Test void laterSameIdentityLoginCannotMasqueradeAsThisReceipt() {
        fake.callback = true;
        fake.beforeExit = () -> {
            try (var material = PiCredentialMaterial.apiKey("newer".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                store.saveLogin(identity, material, 1, false, NONE);
            }
        };
        assertSafeFailure(operation(), PiLoginOperation.Code.CONFLICT);
        assertThat(store.snapshot(NONE).find(identity).orElseThrow().authEpoch()).isEqualTo(2);
    }

    @Test void inputTimeIndexConflictDoesNotRebaseOrSetDefault() {
        fake.callback = true;
        try (var material = PiCredentialMaterial.apiKey("other".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            store.saveLogin(identity("deepseek"), material, 0, false, NONE);
        }
        assertSafeFailure(operation(), PiLoginOperation.Code.FAILED);
        assertThat(store.snapshot(NONE).find(identity)).isEmpty();
        assertThat(store.snapshot(NONE).providerDefaults()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"handle", "transport"})
    void cleanupFailuresRemainSticky(String resource) {
        fake.callback = true;
        handle.failClose = resource.equals("handle");
        fake.failClose = resource.equals("transport");
        var operation = operation();
        assertSafeFailure(operation, PiLoginOperation.Code.CLEANUP_FAILED);
        assertThatThrownBy(operation::close).isInstanceOfSatisfying(PiLoginOperation.LoginException.class,
                failure -> assertThat(failure.code()).isEqualTo(PiLoginOperation.Code.CLEANUP_FAILED));
    }

    @Test void uncooperativeHandleIsNotReportedDrainedAndFailureStaysSticky() {
        fake.callback = true;
        handle.closeGate = new CountDownLatch(1);
        var operation = operation();
        try {
            assertSafeFailure(operation, PiLoginOperation.Code.CLEANUP_FAILED);
            assertThatThrownBy(operation::close).isInstanceOfSatisfying(PiLoginOperation.LoginException.class,
                    failure -> assertThat(failure.code()).isEqualTo(PiLoginOperation.Code.CLEANUP_FAILED));
        } finally { handle.closeGate.countDown(); }
    }

    @Test void cancelledOldMaterialCannotAnswerNextPrompt() {
        fake.callback = true; fake.mode = "second-prompt";
        SecretMaterial old = secret(), next = secret();
        onPrompt = prompt -> {
            if (prompt.promptId() == 2) {
                handle.result.complete(old);
                assertErased(old);
                secondHandle.result.complete(next);
            }
        };
        try (var operation = operation()) { assertThat(operation.run().authEpoch()).isEqualTo(1); }
        assertThat(fake.responses.get()).isEqualTo(1);
        assertThat(handle.closes.get()).isEqualTo(1);
        assertThat(secondHandle.closes.get()).isEqualTo(1);
        assertErased(next);
    }

    @ParameterizedTest @ValueSource(strings = {"openai", "deepseek", "qwen-token-plan-cn"})
    void optInProductionWorkerStoresOnlySyntheticApiKey(String provider) throws Exception {
        String executable = System.getProperty("codej.test.nodeExecutable");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank(), "REAL_NODE_NOT_ENABLED");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("cc-java-provider-pi"))) root = root.getParent();
        assertThat(root).isNotNull();
        var production = new PiWorkerConfiguration(Path.of(executable), root.resolve("cc-java-provider-pi/worker.mjs"), Map.of());
        identity = identity(provider);
        SecretMaterial material = secret();
        onPrompt = prompt -> handle.result.complete(material);
        try (var operation = new PiLoginOperation(production, store, identity, 0, interaction(), NONE, Duration.ofSeconds(20))) {
            assertThat(operation.run()).isEqualTo(new PiLoginOperation.Receipt(identity, 1));
        }
        assertErased(material);
        assertThat(store.snapshot(NONE).providerDefaults()).isEmpty();
    }

    @Test void closeBeforeRunNeverStartsTransportAndErasesNoUnownedInput() {
        var operation = operation();
        operation.close();
        assertThatThrownBy(operation::run).isInstanceOf(PiLoginOperation.LoginException.class);
        assertThat(fake.operationId).isNull();
    }

    @Test void externalCancelOnlySignalsWhileHandleCloseRunsOnOwnedThread() throws Exception {
        var source = new CancellationSource();
        CountDownLatch entered = new CountDownLatch(1);
        handle.closeGate = new CountDownLatch(1);
        onPrompt = prompt -> entered.countDown();
        var operation = operation(source.token());
        var result = CompletableFuture.supplyAsync(operation::run);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        source.cancel();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        assertThat(handle.closeEntered.await(3, TimeUnit.SECONDS)).isTrue();
        handle.closeGate.countDown();
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiLoginOperation.LoginException.class);
        SecretMaterial late = secret(); handle.result.complete(late); assertErased(late);
        operation.close();
    }

    @Test void startedSendAndCloseRaceErasesOwnedMaterialAndDrainsSender() throws Exception {
        SecretMaterial material = secret();
        fake.sendGate = new CountDownLatch(1);
        onPrompt = prompt -> handle.result.complete(material);
        var operation = operation();
        var result = CompletableFuture.supplyAsync(operation::run);
        assertThat(fake.sendEntered.await(3, TimeUnit.SECONDS)).isTrue();
        operation.close(); // Fake close 解除已开始发送，不让新帧写入。
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiLoginOperation.LoginException.class);
        assertErased(material);
        assertThat(fake.responses.get()).isZero();
        assertThat(store.snapshot(NONE).find(identity)).isEmpty();
    }

    @Test void uncooperativeStartedSendRemainsOwnedAndCleanupFailureIsSticky() throws Exception {
        SecretMaterial material = secret();
        fake.sendGate = new CountDownLatch(1); fake.ignoreCloseDuringSend = true;
        onPrompt = prompt -> handle.result.complete(material);
        var operation = operation();
        var result = CompletableFuture.supplyAsync(operation::run);
        assertThat(fake.sendEntered.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(operation::close).isInstanceOfSatisfying(PiLoginOperation.LoginException.class,
                    failure -> assertThat(failure.code()).isEqualTo(PiLoginOperation.Code.CLEANUP_FAILED));
        } finally { fake.sendGate.countDown(); }
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiLoginOperation.LoginException.class);
        assertErased(material);
        assertThatThrownBy(operation::close).isInstanceOfSatisfying(PiLoginOperation.LoginException.class,
                failure -> assertThat(failure.code()).isEqualTo(PiLoginOperation.Code.CLEANUP_FAILED));
    }

    @Test void futureCompletionRacingCloseCannotLeaveQueuedMaterial() throws Exception {
        CountDownLatch requested = new CountDownLatch(1), race = new CountDownLatch(1);
        onPrompt = prompt -> requested.countDown();
        var operation = operation();
        var result = CompletableFuture.supplyAsync(operation::run);
        assertThat(requested.await(3, TimeUnit.SECONDS)).isTrue();
        SecretMaterial material = secret();
        var completing = CompletableFuture.runAsync(() -> { await(race); handle.result.complete(material); });
        var closing = CompletableFuture.runAsync(() -> { await(race); operation.close(); });
        race.countDown(); completing.get(5, TimeUnit.SECONDS); closing.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiLoginOperation.LoginException.class);
        assertErased(material);
        assertThat(handle.closes.get()).isEqualTo(1);
    }

    @Test void requestHandleReturningDuringCloseIsOwnedAndLateMaterialErased() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        onPrompt = prompt -> { entered.countDown(); await(release); };
        var operation = operation();
        var result = CompletableFuture.supplyAsync(operation::run);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var closing = CompletableFuture.runAsync(operation::close);
        Thread.sleep(50);
        release.countDown();
        closing.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiLoginOperation.LoginException.class);
        assertThat(handle.closes.get()).isEqualTo(1);
        SecretMaterial late = secret(); handle.result.complete(late); assertErased(late);
    }

    @Test void factoryReturningDuringCloseCannotLeakConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var operation = new PiLoginOperation(config, store, identity, 0, interaction(), NONE, Duration.ofSeconds(10),
                (configuration, id, token, timeout) -> {
                    entered.countDown(); await(release); fake.operationId = id; fake.token = token; return fake;
                });
        var result = CompletableFuture.supplyAsync(operation::run);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var closing = CompletableFuture.runAsync(operation::close);
        Thread.sleep(50); release.countDown();
        closing.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiLoginOperation.LoginException.class);
        assertThat(fake.closed).isTrue();
        assertThat(fake.starts.get()).isZero();
    }

    @Test void officialUrlIsDeliveredOnlyToNonblockingInteraction() {
        identity = identity("openai-codex"); fake.callback = true; fake.url = officialUrl();
        try (var operation = operation()) { assertThat(operation.run().authEpoch()).isEqualTo(1); }
        assertThat(urls.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"http", "host", "userinfo", "port", "empty-port", "fragment", "redirect", "duplicate-state",
            "duplicate-redirect", "empty-state", "missing-state", "path", "too-long", "api-key"})
    void rejectsUnsafeAuthorizationUrlsIndependentlyOfWorker(String mode) {
        identity = identity(mode.equals("api-key") ? "openai" : "openai-codex");
        fake.callback = true;
        fake.url = switch (mode) {
            case "http" -> officialUrl().replace("https:", "http:");
            case "host" -> officialUrl().replace("auth.openai.com", "auth.openai.com.invalid");
            case "userinfo" -> officialUrl().replace("auth.openai.com", "user@auth.openai.com");
            case "port" -> officialUrl().replace("auth.openai.com", "auth.openai.com:443");
            case "empty-port" -> officialUrl().replace("auth.openai.com", "auth.openai.com:");
            case "fragment" -> officialUrl() + "#hidden";
            case "redirect" -> officialUrl().replace("1455", "1456");
            case "duplicate-state" -> officialUrl() + "&%73tate=second";
            case "duplicate-redirect" -> officialUrl() + "&redirect_uri=http://localhost:1455/auth/callback";
            case "empty-state" -> officialUrl().replace("state=opaque", "state=");
            case "missing-state" -> officialUrl().replace("&state=opaque", "");
            case "path" -> officialUrl().replace("/oauth/authorize?", "/oauth/authorize/extra?");
            case "too-long" -> officialUrl() + "&extra=" + "x".repeat(16384);
            default -> officialUrl();
        };
        assertSafeFailure(operation(), PiLoginOperation.Code.PROTOCOL_INVALID);
        assertThat(urls.get()).isZero();
    }

    private PiLoginOperation operation() { return operation(NONE); }
    private PiLoginOperation operation(CancellationToken token) {
        return new PiLoginOperation(config, store, identity, expected, interaction(), token, Duration.ofSeconds(10),
                (configuration, id, cancellation, timeout) -> { fake.operationId = id; fake.token = cancellation; return fake; });
    }
    private PiLoginOperation.Interaction interaction() {
        return new PiLoginOperation.Interaction() {
            public PiLoginOperation.PromptHandle request(PiLoginOperation.Prompt prompt) {
                onPrompt.accept(prompt); return prompt.promptId() == 1 ? handle : secondHandle;
            }
            public void authorizationUrl(URI url) { assertThat(url.getHost()).isEqualTo("auth.openai.com"); urls.incrementAndGet(); }
        };
    }
    private static String officialUrl() { return "https://auth.openai.com/oauth/authorize?redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback&state=opaque"; }
    private static PiCredentialIdentity identity(String provider) {
        return new PiCredentialIdentity(provider, provider.equals("openai-codex") ? PiCredentialIdentity.AuthMethod.OAUTH
                : PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }
    private static SecretMaterial secret() { return new SecretMaterial(new char[] {'f', 'a', 'k', 'e'}); }
    private static void assertErased(SecretMaterial material) {
        assertThatThrownBy(material::copyChars).isInstanceOf(IllegalStateException.class);
        try {
            var value = SecretMaterial.class.getDeclaredField("value"); value.setAccessible(true);
            assertThat((char[]) value.get(material)).containsOnly('\0');
        } catch (ReflectiveOperationException failed) { throw new AssertionError("ERASURE_NOT_OBSERVABLE"); }
    }
    private static void assertSafeFailure(PiLoginOperation operation, PiLoginOperation.Code code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(PiLoginOperation.LoginException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).isEqualTo(code.name());
            assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        });
    }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static void await(CountDownLatch gate) {
        boolean interrupted = false;
        try {
            while (true) {
                try { if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("GATE_TIMEOUT"); return; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static final class Handle implements PiLoginOperation.PromptHandle {
        final CompletableFuture<SecretMaterial> result = new CompletableFuture<>();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        volatile CountDownLatch closeGate;
        boolean failClose;
        public CompletableFuture<SecretMaterial> result() { return result; }
        public void close() {
            closes.incrementAndGet(); closeEntered.countDown();
            if (closeGate != null) await(closeGate);
            if (failClose) throw new IllegalStateException("SECRET_UPSTREAM");
        }
    }

    /** awaitExit 模拟 Connection 的真实 EOF/唯一终态校验，而不是伪造第二条完成事件。 */
    private final class Fake implements PiLoginOperation.Channel {
        final LinkedBlockingQueue<ProtocolFrame> incoming = new LinkedBlockingQueue<>();
        final AtomicInteger responses = new AtomicInteger(), acks = new AtomicInteger(), starts = new AtomicInteger();
        final CountDownLatch sendEntered = new CountDownLatch(1);
        volatile CountDownLatch sendGate;
        volatile boolean closed, disconnected;
        boolean callback, failClose, exitConfirmed, ignoreCloseDuringSend;
        String mode = "", url, operationId;
        long sequence;
        CancellationToken token;
        Runnable beforeExit = () -> { };
        public void send(String type, ObjectNode payload) {
            if (closed) throw new IllegalStateException("PRIVATE_CLOSED");
            if (type.equals("operation.start")) {
                starts.incrementAndGet();
                if (mode.equals("before-ready")) emit("auth.prompt", prompt());
                ObjectNode ready = object().put("piVersion", mode.equals("wrong-version") ? "0.85.2" : "0.85.1");
                var operations = ready.putArray("operations"); operations.add(mode.equals("missing-capability") ? "catalog" : "auth.login");
                if (mode.equals("duplicate-capability")) operations.add("auth.login");
                emit("operation.ready", ready);
                if (mode.equals("duplicate-ready")) emit("operation.ready", ready);
                if (url != null) emit("auth.url", object().put("url", url));
                if (mode.equals("premature-completed")) { emit("operation.completed", object().put("status", "completed")); return; }
                if (mode.equals("false-stored")) { result(); return; }
                emit("auth.prompt", prompt());
                if (mode.equals("duplicate-prompt")) emit("auth.prompt", prompt());
                if (callback) {
                    emit("auth.prompt_cancelled", object().put("promptId", mode.equals("unknown-cancel") ? 2 : 1));
                    if (mode.equals("duplicate-cancel")) emit("auth.prompt_cancelled", object().put("promptId", 1));
                    if (mode.equals("second-prompt")) emit("auth.prompt", prompt().put("promptId", 2));
                    else begin();
                }
            } else if (type.equals("auth.response")) {
                sendEntered.countDown();
                if (sendGate != null) await(sendGate);
                if (closed) throw new IllegalStateException("PRIVATE_CLOSED");
                responses.incrementAndGet(); begin();
            } else if (type.equals("credential.response")) {
                if (!payload.path("ok").asBoolean()) { emit("operation.failed", object()); return; }
                if (payload.path("requestId").asInt() == 1) {
                    ObjectNode material = identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH
                            ? object().put("type", "oauth").put("access", "fake-access").put("refresh", "fake-refresh")
                                .put("expires", 100000).put("accountId", "fake-account")
                            : object().put("type", "api_key").put("key", "fake");
                    ObjectNode arguments = object().put("transactionId", payload.path("result").path("transactionId").asString());
                    arguments.set("change", object().put("kind", "put").set("credential", material));
                    emit("credential.request", object().put("requestId", 2).put("action", "finish").set("arguments", arguments));
                } else {
                    acks.incrementAndGet();
                    if (mode.equals("disconnect")) { disconnected = true; return; }
                    result();
                    if (mode.equals("duplicate-result")) result();
                    emit("operation.completed", object().put("status", "completed"));
                    if (mode.equals("duplicate-completed")) emit("operation.completed", object().put("status", "completed"));
                }
            }
        }
        private ObjectNode prompt() {
            ObjectNode result = object().put("promptId", 1).put("kind", identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH ? "manual_code" : "secret");
            switch (mode) {
                case "skip-id" -> result.put("promptId", 2);
                case "zero-id" -> result.put("promptId", 0);
                case "fraction-id" -> result.put("promptId", 1.5);
                case "wrong-kind" -> result.put("kind", "password");
                case "extra-field" -> result.put("unexpected", true);
                default -> { }
            }
            return result;
        }
        private void begin() { emit("credential.request", object().put("requestId", 1).put("action", "begin").set("arguments", object())); }
        private void result() {
            ObjectNode result = object().put("providerId", mode.equals("wrong-provider") ? "invalid" : identity.providerId())
                    .put("authType", mode.equals("wrong-auth") ? "invalid" : identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH ? "oauth" : "api_key")
                    .put("status", "stored");
            if (mode.equals("extra-result")) result.put("extra", true);
            emit("auth.result", result);
        }
        private synchronized void emit(String type, ObjectNode payload) {
            incoming.add(new ProtocolFrame(mode.equals("wrong-operation") ? "other" : operationId,
                    mode.equals("wrong-sequence") ? 42 : sequence++, type, payload));
        }
        public ProtocolFrame receive() {
            try {
                while (!closed && !disconnected && !token.isCancellationRequested()) {
                    ProtocolFrame frame = incoming.poll(10, TimeUnit.MILLISECONDS);
                    if (frame != null) return frame;
                }
                throw new IllegalStateException("PRIVATE_CLOSED");
            } catch (InterruptedException failed) { Thread.currentThread().interrupt(); throw new IllegalStateException("PRIVATE_INTERRUPT"); }
        }
        public int awaitExit() {
            if (!incoming.isEmpty()) throw newProtocolFailure();
            if (mode.equals("missing-eof")) throw new IllegalStateException("PRIVATE_EOF_MISSING");
            beforeExit.run(); exitConfirmed = true; return mode.equals("nonzero-exit") ? 1 : 0;
        }
        public void close() {
            closed = true;
            if (sendGate != null && !ignoreCloseDuringSend) sendGate.countDown();
            if (failClose) throw new IllegalStateException("SECRET_UPSTREAM");
        }
        private RuntimeException newProtocolFailure() {
            // 与真实 Connection 一样，终态后数据不能被业务层忽略。
            return new io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException(
                    io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException.Code.PROTOCOL_INVALID);
        }
    }
}
