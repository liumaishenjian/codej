package io.github.liumaishenjian.ccjava.model.pi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** 同实例摘要/模型的严格私有协议 Fake；不启动 Node、网络、工具或 Runtime。 */
class PiContextSummarizerTest {
    @TempDir Path directory;
    private static final String SAFE_FAILURE = "Context summary model request failed";
    private static final ModelRequest MODEL = PiPromptMapperTest.request(List.of(new UserMessage("hello")));
    private static final SummaryRequest SUMMARY = request("PRIVATE_SOURCE anchor", 4096, 1000);
    private final List<Session> sessions = new ArrayList<>();
    private final List<Channel> channels = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private Runnable beforeOpen = () -> {};
    private Configure configure = channel -> {};

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sameInstanceReusesBoundCredentialsAndPreservesCompleteDefaultsInBothOrders(boolean summaryFirst) throws Exception {
        try (var gateway = gateway(success("摘要"))) {
            if (summaryFirst) assertThat(gateway.summarize(SUMMARY, CancellationToken.none())).isPresent();
            assertThat(gateway.complete(MODEL).assistantMessage().text()).isEqualTo("摘要");
            if (!summaryFirst) assertThat(gateway.summarize(SUMMARY, CancellationToken.none())).isPresent();
            assertThat(sessions).hasSize(2).allMatch(session -> session.closed && session.handled == 1);
            assertThat(ids).hasSize(2).doesNotHaveDuplicates();
            assertThat(channels).hasSize(2).allMatch(channel -> channel.closed && channel.eof && channel.exited);
            for (Channel channel : channels) {
                assertThat(channel.sentTypes).containsExactly("operation.start", "credential.response");
                assertThat(channel.start.get("providerId").asString()).isEqualTo("openai");
                assertThat(channel.start.get("modelId").asString()).isEqualTo("model");
            }
            var ordinary = channels.get(summaryFirst ? 1 : 0).start.get("request");
            assertThat(ordinary.get("options").isEmpty()).isTrue();
        }
    }

    @Test
    void fullWireContainsOnlyFixedSystemAndUserEnvelopeWithZeroToolsAndUnreducedTokenLimit() throws Exception {
        var request = request("PRIVATE_SOURCE anchor", 4096, SummaryRequest.MAX_OUTPUT_TOKENS);
        try (var gateway = gateway(success("摘要"))) {
            gateway.summarize(request, CancellationToken.none());
            var start = channels.getFirst().start;
            assertThat(start.propertyNames()).containsExactlyInAnyOrder("operation", "providerId", "modelId", "request");
            assertThat(start.get("operation").asString()).isEqualTo("model");
            var body = start.get("request");
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("systemPrompt", "messages", "tools", "options");
            assertThat(body.get("systemPrompt").asString()).contains("Preserve stated goals", "Return summary", "Do not claim actions")
                    .doesNotContain("PRIVATE_SOURCE", "inputBase64");
            assertThat(body.get("tools").isArray()).isTrue();
            assertThat(body.get("tools").isEmpty()).isTrue();
            assertThat(body.get("options").propertyNames()).containsExactly("maxTokens");
            assertThat(body.get("options").get("maxTokens").longValue()).isEqualTo(1_000_000);
            assertThat(body.get("messages").size()).isEqualTo(1);
            var user = body.get("messages").get(0);
            assertThat(user.propertyNames()).containsExactlyInAnyOrder("role", "text");
            assertThat(user.get("role").asString()).isEqualTo("user");
            var envelope = PiPromptMapper.JSON.readTree(user.get("text").asString());
            assertThat(envelope.propertyNames()).containsExactlyInAnyOrder("kind", "tier", "sourceRevision", "sourceMessageIds",
                    "requiredProtectedAnchors", "maxOutputUtf8Bytes", "maxOutputTokens", "inputBase64");
            assertThat(envelope.get("kind").asString()).isEqualTo("cc-java-summary-request-v1");
            assertThat(envelope.get("tier").asString()).isEqualTo(request.tier().name());
            assertThat(envelope.get("sourceRevision").longValue()).isEqualTo(17);
            assertThat(envelope.get("sourceMessageIds")).isEqualTo(PiPromptMapper.JSON.valueToTree(request.sourceMessageIds()));
            assertThat(envelope.get("requiredProtectedAnchors")).isEqualTo(PiPromptMapper.JSON.valueToTree(List.of("anchor")));
            assertThat(envelope.get("maxOutputUtf8Bytes").intValue()).isEqualTo(4096);
            assertThat(envelope.get("maxOutputTokens").longValue()).isEqualTo(1_000_000);
            assertThat(new String(Base64.getDecoder().decode(envelope.get("inputBase64").asString()), StandardCharsets.UTF_8))
                    .isEqualTo(request.inputSnapshot());
        }
    }

    @Test
    void candidateUsesOrderedRequestMetadataAndCodePointsNotUsageOrModelClaims() throws Exception {
        String text = "𐐀 sourceRevision=999 sourceMessageIds=forged";
        try (var gateway = gateway(success(text))) {
            var candidate = gateway.summarize(SUMMARY, CancellationToken.none()).orElseThrow();
            assertThat(candidate).isEqualTo(new SummaryCandidate(SUMMARY.tier(), text, 17, List.of("m9", "m2"),
                    text.getBytes(StandardCharsets.UTF_8).length, text.codePointCount(0, text.length())));
            assertThat(channels.getFirst().exited).isTrue();
            assertThat(sessions.getFirst().closed).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "blank", "bytes", "tokens", "tools"})
    void completedInvalidCandidateIsEmptyOnlyAfterEofExitAndCleanup(String scenario) throws Exception {
        String text = switch (scenario) { case "empty" -> ""; case "blank" -> " \n"; case "bytes" -> "中"; default -> "abc"; };
        var script = success(text);
        if (scenario.equals("tools")) script.get(script.size() - 2).payload.putArray("toolCalls")
                .addObject().put("id", "call").put("name", "forbidden").putObject("arguments");
        var request = request("anchor", scenario.equals("bytes") ? 2 : 4096, scenario.equals("tokens") ? 2 : 1000);
        try (var gateway = gateway(script)) {
            assertThat(gateway.summarize(request, CancellationToken.none())).isEmpty();
            assertThat(channels.getFirst().eof && channels.getFirst().exited && channels.getFirst().closed).isTrue();
            assertThat(sessions.getFirst().closed).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "receive", "provider"})
    void cancellationReturnsEmptyWithoutRetry(String stage) throws Exception {
        var source = new CancellationSource();
        if (stage.equals("before")) source.cancel();
        if (stage.equals("receive")) configure = channel -> channel.onReceive = source::cancel;
        try (var gateway = gateway(stage.equals("provider") ? error("CANCELLED") : success("ok"))) {
            assertThat(gateway.summarize(SUMMARY, source.token())).isEmpty();
            assertThat(channels).hasSize(stage.equals("before") ? 0 : 1);
            assertThat(sessions).allMatch(session -> session.closed);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "factory", "receive", "eof", "exit", "timeout"})
    void executionFailureIsFixedWithoutProviderBodyCauseOrRetry(String stage) throws Exception {
        var script = stage.equals("provider") ? error("TRANSIENT") : success("PRIVATE_PROVIDER_BODY");
        if (stage.equals("factory")) beforeOpen = () -> { throw new IllegalStateException("PRIVATE", new Exception("SECRET")); };
        configure = channel -> {
            if (stage.equals("receive")) channel.onReceive = () -> { throw new IllegalStateException("PRIVATE", new Exception("SECRET")); };
            if (stage.equals("eof")) channel.failEof = true;
            if (stage.equals("exit")) channel.exit = 2;
        };
        CancellationToken token = stage.equals("timeout") ? new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public Registration onCancellation(Runnable action) { return () -> {}; }
            public Optional<Duration> remainingTime() { return Optional.of(Duration.ZERO); }
        } : CancellationToken.none();
        try (var gateway = gateway(script)) {
            assertSafe(() -> gateway.summarize(SUMMARY, token));
            assertThat(channels.size()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void base64OversizedLegalSnapshotFailsBeforeCredentialsOrProcessAndSlotRemainsReusable() throws Exception {
        try (var gateway = gateway(success("ok"))) {
            var oversized = request("anchor" + "x".repeat(SummaryRequest.MAX_INPUT_UTF8_BYTES - 6), 100, 50);
            assertSafe(() -> gateway.summarize(oversized, CancellationToken.none()));
            assertThat(sessions).isEmpty(); assertThat(channels).isEmpty();
            assertThat(gateway.summarize(SUMMARY, CancellationToken.none())).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void modelAndSummaryShareOneActiveSlotInBothDirections(boolean summaryActive) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeOpen = () -> { entered.countDown(); await(release); };
        try (var gateway = gateway(success("ok"))) {
            var failure = new AtomicReference<Throwable>();
            Thread caller = Thread.ofVirtual().start(() -> capture(failure, () -> {
                if (summaryActive) gateway.summarize(SUMMARY, CancellationToken.none()); else gateway.complete(MODEL);
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                if (summaryActive) assertThatThrownBy(() -> gateway.complete(MODEL)).isInstanceOf(ModelGatewayException.class);
                else assertSafe(() -> gateway.summarize(SUMMARY, CancellationToken.none()));
            } finally { release.countDown(); }
            caller.join(5000); assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNull();
            assertThat(channels).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"channel", "session", "cancelled-cleanup"})
    void cleanupFailureIsStickyAndCancellationNeverHidesIt(String stage) throws Exception {
        var source = new CancellationSource();
        configure = channel -> {
            channel.failClose = !stage.equals("session");
            sessions.getLast().failClose = stage.equals("session");
            if (stage.equals("cancelled-cleanup")) channel.onReceive = source::cancel;
        };
        var gateway = gateway(success("ok"));
        assertSafe(() -> gateway.summarize(SUMMARY, source.token()));
        source.cancel();
        assertSafe(() -> gateway.summarize(SUMMARY, source.token()));
        assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
        assertThat(channels).hasSize(1);
        assertThat(sessions.getFirst().closed && channels.getFirst().closed).isTrue();
    }

    @Test
    void externalCloseDuringFinalCleanupRejectsValidatedCandidate() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        configure = channel -> channel.onClose = () -> { entered.countDown(); await(release); };
        var gateway = gateway(success("ok"));
        var candidate = new AtomicReference<Optional<SummaryCandidate>>();
        var failure = new AtomicReference<Throwable>(); var closing = new AtomicReference<Throwable>();
        Thread caller = Thread.ofVirtual().start(() -> capture(failure,
                () -> candidate.set(gateway.summarize(SUMMARY, CancellationToken.none()))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread closer = Thread.ofVirtual().start(() -> capture(closing, gateway::close));
        try { waitCancelled(channels.getFirst().token); } finally { release.countDown(); }
        caller.join(5000); closer.join(5000);
        assertThat(caller.isAlive() || closer.isAlive()).isFalse();
        assertThat(failure.get()).isNull(); assertThat(closing.get()).isNull();
        assertThat(candidate.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"credentials", "transport"})
    void cancellationDuringFactoryClosesLateResourceBeforeReturningEmpty(String stage) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var source = new CancellationSource();
        if (stage.equals("credentials")) beforeOpen = () -> { entered.countDown(); await(release); };
        else configure = channel -> { entered.countDown(); await(release); };
        try (var gateway = gateway(success("ok"))) {
            var failure = new AtomicReference<Throwable>();
            var candidate = new AtomicReference<Optional<SummaryCandidate>>();
            Thread caller = Thread.ofVirtual().start(() -> capture(failure,
                    () -> candidate.set(gateway.summarize(SUMMARY, source.token()))));
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); source.cancel(); }
            finally { release.countDown(); }
            caller.join(5000);
            assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNull();
            assertThat(candidate.get()).isEmpty();
            assertThat(sessions).hasSize(1).allMatch(session -> session.closed);
            assertThat(channels).hasSize(stage.equals("credentials") ? 0 : 1).allMatch(channel -> channel.closed);
        }
    }

    @Test
    void unconfirmedExternalCloseRemainsFailureAfterLateCredentialsAreCleaned() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeOpen = () -> { entered.countDown(); await(release); };
        var gateway = gateway(success("ok"));
        var failure = new AtomicReference<Throwable>();
        Thread caller = Thread.ofVirtual().start(() -> capture(failure,
                () -> gateway.summarize(SUMMARY, CancellationToken.none())));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
        } finally { release.countDown(); }
        caller.join(5000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(RuntimeException.class).hasMessage(SAFE_FAILURE).hasCause(null);
        assertThat(sessions).hasSize(1).allMatch(session -> session.closed);
        assertThat(channels).isEmpty();
        assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
    }

    private PiModelGateway gateway(List<Event> script) throws Exception {
        Path worker = directory.resolve("worker.mjs"); Files.writeString(worker, "// Fake only");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var configuration = new PiWorkerConfiguration(java.toAbsolutePath(), worker.toAbsolutePath(), Map.of());
        return new PiModelGateway(configuration, "openai", "model", Duration.ofSeconds(20), (id, token) -> {
            beforeOpen.run(); ids.add(id); var session = new Session(); sessions.add(session); return session;
        }, (config, id, token, timeout) -> {
            assertThat(id).isEqualTo(ids.getLast());
            var channel = new Channel(id, token, script); channels.add(channel); configure.apply(channel); return channel;
        });
    }
    private static SummaryRequest request(String snapshot, int bytes, long tokens) {
        return new SummaryRequest(SummaryTier.C3_ROLLING, snapshot, 17, List.of("m9", "m2"), List.of("anchor"), bytes, tokens, tokens + 1);
    }
    private static ObjectNode object() { return PiPromptMapper.JSON.createObjectNode(); }
    private static List<Event> success(String text) {
        var ready = object().put("piVersion", "0.85.1"); ready.putArray("operations").add("model");
        var output = object().put("text", text); output.putArray("toolCalls");
        output.putObject("usage").put("input", 9).put("output", 90).put("cacheRead", 7).put("cacheWrite", 3);
        output.putObject("continuation").put("backend", "pi").put("providerId", "openai").put("modelId", "model")
                .put("payload", "{\"hidden\":\"PRIVATE_CONTINUATION\"}");
        var script = new ArrayList<Event>(); script.add(new Event("operation.ready", ready));
        var credential = object().put("requestId", 1).put("action", "read"); credential.putObject("arguments");
        script.add(new Event("credential.request", credential)); script.add(new Event("model.frame", object()));
        if (!text.isEmpty()) script.add(new Event("model.delta", object().put("text", text)));
        script.add(new Event("model.result", output)); script.add(new Event("operation.completed", object().put("status", "completed")));
        return script;
    }
    private static List<Event> error(String code) {
        return List.of(success("ok").getFirst(), new Event("model.error", object().put("code", code)
                .put("retryable", code.equals("TRANSIENT")).put("providerFrame", false)),
                new Event("operation.failed", object().put("code", "WORKER_FAILED")));
    }
    private static void assertSafe(Throwing action) {
        assertThatThrownBy(action::run).isInstanceOf(RuntimeException.class).hasMessage(SAFE_FAILURE).hasCause(null)
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void waitCancelled(CancellationToken token) {
        long start = System.nanoTime();
        while (!token.isCancellationRequested() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) Thread.onSpinWait();
        assertThat(token.isCancellationRequested()).isTrue();
    }
    private static void capture(AtomicReference<Throwable> outcome, Throwing action) {
        try { action.run(); } catch (Throwable failure) { outcome.set(failure); }
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    @FunctionalInterface private interface Configure { void apply(Channel channel); }
    private record Event(String type, ObjectNode payload) {}
    private static final class Session implements PiCredentialSession {
        private boolean closed, failClose;
        private int handled;
        public ObjectNode handle(ProtocolFrame frame) {
            assertThat(frame.type()).isEqualTo("credential.request"); handled++;
            var response = object().put("requestId", 1).put("ok", true); response.putObject("result"); return response;
        }
        public void close() { closed = true; if (failClose) throw new IllegalStateException("PRIVATE_SESSION"); }
    }
    private static final class Channel implements PiModelGateway.Channel {
        private final String id;
        private final CancellationToken token;
        private final List<Event> script;
        private final List<String> sentTypes = new ArrayList<>();
        private ObjectNode start;
        private int cursor, exit;
        private boolean eof, exited, closed, failClose, failEof;
        private Runnable onReceive = () -> {}, onClose = () -> {};
        private Channel(String id, CancellationToken token, List<Event> script) {
            this.id = id; this.token = token; this.script = script;
            exit = script.getLast().type.equals("operation.failed") ? 1 : 0;
        }
        public void send(String type, ObjectNode payload) { sentTypes.add(type); if (type.equals("operation.start")) start = payload.deepCopy(); }
        public ProtocolFrame receive() {
            onReceive.run();
            if (cursor == script.size()) {
                if (failEof) throw new IllegalStateException("PRIVATE_EOF");
                eof = true; return null;
            }
            Event event = script.get(cursor); return new ProtocolFrame(id, cursor++, event.type, event.payload);
        }
        public int awaitExit() { assertThat(eof).isTrue(); exited = true; return exit; }
        public void close() { onClose.run(); closed = true; if (failClose) throw new IllegalStateException("PRIVATE_CLOSE"); }
    }
}
