package io.github.liumaishenjian.ccjava.model.pi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** Fake 单回合端到端状态机，不启动 Node、网络、Agent 或 Tool Pipeline。 */
class PiModelGatewayTest {
    @TempDir Path directory;
    private final List<FakeChannel> channels = new ArrayList<>();
    private final List<Session> sessions = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();
    private final AtomicInteger starts = new AtomicInteger();
    private static final ModelRequest REQUEST = PiPromptMapperTest.request(List.of(new UserMessage("hello")));

    @Test
    void completePreservesTextMultipleToolsJsonNullAndContinuationOnlyAfterEofExitAndCleanup() throws Exception {
        var output = result("ab");
        var calls = output.putArray("toolCalls");
        calls.addObject().put("id", "c2").put("name", "second").putObject("arguments")
                .putNull("__proto__").put("constructor", "data").put("toString", false);
        calls.addObject().put("id", "c1").put("name", "first").putObject("arguments");
        output.putObject("usage").put("input", 2).put("output", 3).put("cacheRead", 5).put("cacheWrite", 7);
        try (var gateway = gateway(success(output))) {
            List<String> deltas = new ArrayList<>();
            var turn = gateway.complete(REQUEST, deltas::add, CancellationToken.none());
            assertThat(deltas).containsExactly("a", "b");
            assertThat(turn.assistantMessage().text()).isEqualTo("ab");
            assertThat(turn.assistantMessage().toolCalls()).extracting(ToolCall::id).containsExactly("c2", "c1");
            var args = turn.assistantMessage().toolCalls().getFirst().arguments().jsonValues();
            assertThat(args).containsEntry("__proto__", null).containsEntry("constructor", "data").containsEntry("toString", false);
            assertThat(turn.assistantMessage().continuation()).hasValue(new ModelContinuation("pi", "openai", "model", "{\"opaque\":true}"));
            assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
            assertThat(turn.metadata().usage()).hasValue(new ModelUsage(14, 3, 17));
            assertThat(turn.metadata().providerModel()).isEmpty();
            assertThat(channels.getFirst().eof).isTrue();
            assertThat(channels.getFirst().exited).isTrue();
            assertThat(channels.getFirst().closed).isTrue();
            assertThat(sessions.getFirst().closed).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void workerDeadlineWithoutModelErrorKeepsTimeoutMeaningAndNeverGrantsRetry(boolean receivedContent) throws Exception {
        var script = new ArrayList<Event>(); script.add(ready());
        if (receivedContent) script.add(event("model.frame", object()));
        script.add(event("operation.failed", object().put("code", "TIMEOUT")));
        try (var gateway = gateway(script)) {
            assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class)
                    .satisfies(error -> {
                        var failure = (ModelGatewayException) error;
                        assertThat(failure.kind()).isEqualTo(receivedContent
                                ? FailureKind.INCOMPLETE_STREAM : FailureKind.PERMANENT);
                        assertThat(failure.summary().orElseThrow().category()).isEqualTo(receivedContent
                                ? ModelFailureCategory.INCOMPLETE_STREAM : ModelFailureCategory.REQUEST_TIMEOUT);
                    });
            assertThat(starts).hasValue(1);
            assertThat(channels.getFirst().eof).isTrue();
            assertThat(channels.getFirst().exited).isTrue();
            assertThat(channels.getFirst().closed).isTrue();
        }
    }

    @Test
    void reusableGatewayUsesNewOperationProcessAndCredentialSessionEachTurnWithoutAttemptNotifications() throws Exception {
        try (var gateway = gateway(success(result("ab")))) {
            var observer = new ModelStreamObserver() {
                public void onTextDelta(String value) { throw new IllegalStateException("OBSERVER_PRIVATE"); }
                public void onAttemptStarted(int attempt, int max) { throw new AssertionError("duplicate attempt"); }
            };
            assertThat(gateway.complete(REQUEST, observer, CancellationToken.none()).assistantMessage().text()).isEqualTo("ab");
            assertThat(gateway.complete(REQUEST, observer, CancellationToken.none()).assistantMessage().text()).isEqualTo("ab");
            assertThat(starts).hasValue(2);
            assertThat(operations).hasSize(2).doesNotHaveDuplicates();
            assertThat(sessions).hasSize(2).allMatch(session -> session.closed);
            assertThat(channels).hasSize(2).allMatch(channel -> channel.closed);
            assertThat(channels.getFirst().sent).containsExactly("operation.start");
        }
    }

    @Test
    void credentialRequestsOnlyAreDispatchedAndResponsesStayOnPrivateChannel() throws Exception {
        var script = success(result("ab"));
        script.add(1, event("credential.request", object().put("requestId", 1).put("action", "read").set("arguments", object())));
        try (var gateway = gateway(script)) {
            gateway.complete(REQUEST);
            assertThat(sessions.getFirst().handled).isEqualTo(1);
            assertThat(channels.getFirst().sent).containsExactly("operation.start", "credential.response");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-ready", "wrong-version", "missing-model", "unknown-ready-field", "duplicate-ready",
            "delta-before-frame", "duplicate-frame", "bad-frame", "empty-delta", "bad-delta", "result-before-frame",
            "duplicate-result", "wrong-text", "duplicate-call", "array-arguments", "unknown-result-field",
            "wrong-continuation-route", "bad-continuation-field", "missing-terminal", "wrong-terminal", "tail",
            "delta-after-result", "credential-after-result", "wrong-sequence", "wrong-operation", "eof-after-ready",
            "zero-exit-failure", "nonzero-success", "unknown-event", "bad-error", "error-extra", "invalid-usage"})
    void malformedOrIncompleteProtocolNeverReturnsCandidate(String scenario) throws Exception {
        var script = success(result("ab"));
        switch (scenario) {
            case "missing-ready" -> script.removeFirst();
            case "wrong-version" -> script.getFirst().payload.put("piVersion", "0.85.2");
            case "missing-model" -> script.getFirst().payload.putArray("operations").add("catalog");
            case "unknown-ready-field" -> script.getFirst().payload.put("unknown", true);
            case "duplicate-ready" -> script.add(1, ready());
            case "delta-before-frame" -> script.remove(1);
            case "duplicate-frame" -> script.add(2, event("model.frame", object()));
            case "bad-frame" -> script.get(1).payload.put("extra", true);
            case "empty-delta" -> script.get(2).payload.put("text", "");
            case "bad-delta" -> script.get(2).payload.put("extra", true);
            case "result-before-frame" -> script = new ArrayList<>(List.of(ready(), event("model.result", result("")), completed()));
            case "duplicate-result" -> script.add(5, event("model.result", result("ab")));
            case "wrong-text" -> script.get(4).payload.put("text", "different");
            case "duplicate-call" -> {
                var calls = script.get(4).payload.putArray("toolCalls");
                for (int i = 0; i < 2; i++) calls.addObject().put("id", "same").put("name", "read").putObject("arguments");
            }
            case "array-arguments" -> script.get(4).payload.putArray("toolCalls").addObject().put("id", "id").put("name", "read").putArray("arguments");
            case "unknown-result-field" -> script.get(4).payload.put("private", "must-not-escape");
            case "wrong-continuation-route" -> ((ObjectNode) script.get(4).payload.get("continuation")).put("providerId", "deepseek");
            case "bad-continuation-field" -> ((ObjectNode) script.get(4).payload.get("continuation")).put("extra", true);
            case "missing-terminal" -> script.removeLast();
            case "wrong-terminal" -> script.set(5, event("operation.failed", object().put("code", "WORKER_FAILED")));
            case "tail" -> script.add(event("model.frame", object()));
            case "delta-after-result" -> script.add(5, event("model.delta", object().put("text", "x")));
            case "credential-after-result" -> script.add(5, event("credential.request", object()));
            case "eof-after-ready" -> script = new ArrayList<>(List.of(ready()));
            case "unknown-event" -> script.add(1, event("auth.prompt", object()));
            case "bad-error", "error-extra", "zero-exit-failure" -> {
                var value = error("TRANSIENT", true, false);
                if (scenario.equals("bad-error")) value.put("code", "PRIVATE_ERROR");
                if (scenario.equals("error-extra")) value.put("message", "PRIVATE_PROVIDER_BODY");
                script = errorScript(value);
            }
            case "invalid-usage" -> ((ObjectNode) script.get(4).payload.get("usage")).put("input", -1);
            default -> { }
        }
        final var selected = script;
        try (var gateway = gateway(selected, (channel, token) -> {
            if (scenario.equals("wrong-sequence")) channel.sequenceOffset = 1;
            if (scenario.equals("wrong-operation")) channel.wrongOperation = true;
            if (scenario.equals("nonzero-success")) channel.exit = 1;
            if (scenario.equals("zero-exit-failure")) channel.exit = 0;
        })) {
            assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class)
                    .hasCause(null).hasMessageNotContaining("PRIVATE");
            assertThat(starts).hasValue(1);
            assertThat(channels.getFirst().closed).isTrue();
            assertThat(sessions.getFirst().closed).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"AUTH,false,PERMANENT", "CANCELLED,false,CANCELLED", "TIMEOUT,true,RETRYABLE",
            "TIMEOUT,false,PERMANENT", "RATE_LIMIT,true,RETRYABLE", "TRANSIENT,true,RETRYABLE",
            "CONTEXT_OVERFLOW,false,CONTEXT_OVERFLOW", "INCOMPLETE,false,PERMANENT", "PROTOCOL,false,PERMANENT",
            "PERMANENT,false,PERMANENT", "UNSUPPORTED,false,PERMANENT", "LIMIT,false,PERMANENT"})
    void typedFailureHasNoInventedHttpStatusOrAutomaticRetry(String code, boolean retryable, FailureKind expected) throws Exception {
        try (var gateway = gateway(errorScript(error(code, retryable, false)))) {
            var failure = failure(gateway, CancellationToken.none());
            assertThat(failure.kind()).isEqualTo(expected);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.summary()).isPresent();
            assertThat(failure.summary().orElseThrow().statusClass()).isEmpty();
            assertThat(failure.summary().orElseThrow().attempts()).isEqualTo(1);
            assertThat(failure.summary().orElseThrow().receivedOutput()).isFalse();
            assertThat(starts).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local-frame", "local-delta", "remote-frame"})
    void receivedContentPreventsOverflowRecoveryAndRetry(String evidence) throws Exception {
        var script = errorScript(error("CONTEXT_OVERFLOW", false, evidence.equals("remote-frame")));
        if (!evidence.equals("remote-frame")) script.add(1, event("model.frame", object()));
        if (evidence.equals("local-delta")) script.add(2, event("model.delta", object().put("text", "partial")));
        try (var gateway = gateway(script)) {
            var failure = failure(gateway, CancellationToken.none());
            assertThat(failure.kind()).isEqualTo(FailureKind.INCOMPLETE_STREAM);
            assertThat(failure.summary().orElseThrow().receivedOutput()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1234,1234", "300000,300000", "86400000,300000"})
    void retryAfterIsConservativelyCappedToDomainFiveMinutes(long wire, long expected) throws Exception {
        try (var gateway = gateway(errorScript(error("RATE_LIMIT", true, false).put("retryAfterMs", wire)))) {
            assertThat(failure(gateway, CancellationToken.none()).retryAfter()).hasValue(Duration.ofMillis(expected));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 86400001, 9007199254740991L})
    void retryAfterOutsideNodeRangeIsProtocolFailure(long wire) throws Exception {
        try (var gateway = gateway(errorScript(error("RATE_LIMIT", true, false).put("retryAfterMs", wire)))) {
            var failure = failure(gateway, CancellationToken.none());
            assertThat(failure.kind()).isEqualTo(FailureKind.PERMANENT);
            assertThat(failure.retryAfter()).isEmpty();
        }
    }

    @Test
    void workerCleanupOrCrashExitCannotPreserveTypedRetryPermission() throws Exception {
        try (var gateway = gateway(errorScript(error("TRANSIENT", true, false)), (channel, token) -> channel.exit = 2)) {
            assertThat(failure(gateway, CancellationToken.none()).kind()).isEqualTo(FailureKind.PERMANENT);
            assertThat(starts).hasValue(1);
        }
    }

    @Test
    void fractionalRetryAfterIsNotSilentlyRounded() throws Exception {
        try (var gateway = gateway(errorScript(error("RATE_LIMIT", true, false).put("retryAfterMs", 1.5)))) {
            assertThat(failure(gateway, CancellationToken.none()).retryAfter()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "input-sum", "total-sum", "fraction", "rounded-fraction", "underflow-fraction", "unknown-total"})
    void usageOverflowAndUnknownFieldsAreRejectedWithoutSaturation(String scenario) throws Exception {
        var result = result("ab");
        ObjectNode usage = (ObjectNode) result.get("usage");
        switch (scenario) {
            case "field" -> usage.put("input", 2147483648L);
            case "input-sum" -> usage.put("input", Integer.MAX_VALUE).put("cacheRead", 1);
            case "total-sum" -> usage.put("input", Integer.MAX_VALUE).put("output", 1);
            case "fraction" -> usage.put("input", 1.5);
            case "rounded-fraction" -> usage.put("input", new java.math.BigDecimal("1.00000000000000000001"));
            case "underflow-fraction" -> usage.put("input", new java.math.BigDecimal("1e-400"));
            case "unknown-total" -> usage.put("total", 4);
            default -> throw new AssertionError();
        }
        try (var gateway = gateway(success(result))) {
            assertThat(failure(gateway, CancellationToken.none()).kind()).isEqualTo(FailureKind.INCOMPLETE_STREAM);
        }
    }

    @Test
    void toolOnlyTurnRequiresFrameButNoSyntheticTextDelta() throws Exception {
        ObjectNode output = result("");
        output.putArray("toolCalls").addObject().put("id", "call").put("name", "read").putObject("arguments");
        try (var gateway = gateway(List.of(ready(), event("model.frame", object()), event("model.result", output), completed()))) {
            var deltas = new ArrayList<String>();
            var turn = gateway.complete(REQUEST, deltas::add, CancellationToken.none());
            assertThat(deltas).isEmpty();
            assertThat(turn.assistantMessage().text()).isEmpty();
            assertThat(turn.assistantMessage().toolCalls()).hasSize(1);
            assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unicode", "oversized", "wrong-backend"})
    void continuationCannotBypassDomainUnicodeSizeOrBackendBoundary(String scenario) throws Exception {
        ObjectNode output = result("ab");
        ObjectNode continuation = (ObjectNode) output.get("continuation");
        switch (scenario) {
            case "unicode" -> continuation.put("payload", "\ud800");
            case "oversized" -> continuation.put("payload", "x".repeat(ModelContinuation.MAX_PAYLOAD_BYTES + 1));
            case "wrong-backend" -> continuation.put("backend", "spring-ai");
            default -> throw new AssertionError();
        }
        try (var gateway = gateway(success(output))) {
            assertThat(failure(gateway, CancellationToken.none()).kind()).isEqualTo(FailureKind.INCOMPLETE_STREAM);
        }
    }

    @Test
    void allZeroUsageAndActualResponseModelRemainUnknown() throws Exception {
        try (var gateway = gateway(success(result("ab")))) {
            var turn = gateway.complete(REQUEST);
            assertThat(turn.metadata().usage()).isEmpty();
            assertThat(turn.metadata().providerModel()).isEmpty();
            assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.STOP);
        }
    }

    @Test
    void cancelledAndExpiredRequestsDoNotCreateCredentialsOrTransport() throws Exception {
        try (var gateway = gateway(success(result("ab")))) {
            var cancellation = new CancellationSource(); cancellation.cancel();
            assertThat(failure(gateway, cancellation.token()).kind()).isEqualTo(FailureKind.CANCELLED);
            CancellationToken expired = new CancellationToken() {
                public boolean isCancellationRequested() { return false; }
                public Registration onCancellation(Runnable action) { return () -> {}; }
                public Optional<Duration> remainingTime() { return Optional.of(Duration.ZERO); }
            };
            assertThat(failure(gateway, expired).kind()).isEqualTo(FailureKind.PERMANENT);
            assertThat(starts).hasValue(0); assertThat(sessions).isEmpty();
        }
    }

    @Test
    void cancellationAfterDeltaWinsAndDoesNotRetry() throws Exception {
        var cancellation = new CancellationSource();
        try (var gateway = gateway(success(result("ab")))) {
            assertThatThrownBy(() -> gateway.complete(REQUEST, text -> cancellation.cancel(), cancellation.token()))
                    .isInstanceOfSatisfying(ModelGatewayException.class, error -> assertThat(error.kind()).isEqualTo(FailureKind.CANCELLED));
            assertThat(starts).hasValue(1);
            assertThat(channels.getFirst().closed).isTrue();
        }
    }

    @Test
    void oversizedRequestFailsBeforeOpeningAnyResource() throws Exception {
        try (var gateway = gateway(success(result("ab")))) {
            var request = PiPromptMapperTest.request(List.of(new UserMessage("x".repeat(1024 * 1024))));
            assertThatThrownBy(() -> gateway.complete(request)).isInstanceOf(ModelGatewayException.class).hasCause(null);
            assertThat(starts).hasValue(0); assertThat(sessions).isEmpty();
        }
    }

    @Test
    void workerCrashIsPermanentBeforeContentAndNeverAutomaticallyRestarted() throws Exception {
        try (var gateway = gateway(success(result("ab")), (channel, token) -> channel.readFailure = true)) {
            assertThat(failure(gateway, CancellationToken.none()).kind()).isEqualTo(FailureKind.PERMANENT);
            assertThat(starts).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"channel", "session"})
    void cleanupFailureIsStickyAndDoesNotReleaseOrPretendSuccess(String resource) throws Exception {
        var gateway = gateway(success(result("ab")), (channel, token) -> {
            channel.closeFailure = resource.equals("channel");
            sessions.getLast().closeFailure = resource.equals("session");
        });
        assertThat(failure(gateway, CancellationToken.none()).getMessage()).contains("CLEANUP_FAILED");
        assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class);
        assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
        assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
        assertThat(starts).hasValue(1);
        assertThat(sessions.getFirst().closed).isTrue();
        assertThat(channels.getFirst().closed).isTrue();
    }

    @Test
    void externalCloseDuringFinalResourceCleanupSuppressesAlreadyValidatedCandidate() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1), release = new CountDownLatch(1);
        var gateway = gateway(success(result("ab")), (channel, token) -> channel.onClose = () -> {
            cleaning.countDown(); await(release);
        });
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread complete = Thread.ofVirtual().start(() -> capture(outcome, () -> gateway.complete(REQUEST)));
        assertThat(cleaning.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = Thread.ofVirtual().start(() -> capture(closeFailure, gateway::close));
        // 等待明确的取消可观察条件，而非 sleep 猜测 close 已线性化。
        waitCancelled(channels.getFirst().token);
        release.countDown(); complete.join(5000); closer.join(5000);
        assertThat(complete.isAlive()).isFalse(); assertThat(closer.isAlive()).isFalse();
        assertThat(closeFailure.get()).isNull();
        assertThat(outcome.get()).isInstanceOfSatisfying(ModelGatewayException.class,
                error -> assertThat(error.kind()).isEqualTo(FailureKind.CANCELLED));
        assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"credentials", "transport"})
    void cancellationDuringFactoryClosesLateResourceAndConcurrentOperationIsRejected(String stage) throws Exception {
        var configuration = configuration();
        var cancellation = new CancellationSource();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Session session = new Session();
        AtomicReference<FakeChannel> created = new AtomicReference<>();
        var gateway = new PiModelGateway(configuration, "openai", "model", Duration.ofSeconds(20), (id, token) -> {
            if (stage.equals("credentials")) { entered.countDown(); await(release); }
            return session;
        }, (config, id, token, timeout) -> {
            var channel = new FakeChannel(id, success(result("ab")), token); created.set(channel);
            if (stage.equals("transport")) { entered.countDown(); await(release); }
            return channel;
        });
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> capture(outcome, () -> gateway.complete(REQUEST,
                ModelStreamObserver.noop(), cancellation.token())));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure(gateway, CancellationToken.none()).kind()).isEqualTo(FailureKind.PERMANENT);
        cancellation.cancel(); release.countDown(); thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOfSatisfying(ModelGatewayException.class,
                error -> assertThat(error.kind()).isEqualTo(FailureKind.CANCELLED));
        assertThat(session.closed).isTrue();
        if (stage.equals("transport")) assertThat(created.get().closed).isTrue();
        else assertThat(created.get()).isNull();
        gateway.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"credentials", "transport"})
    void externalCloseDuringFactoryWaitsForLateBindingCleanup(String stage) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var tokenRef = new AtomicReference<CancellationToken>();
        var session = new Session();
        var channelRef = new AtomicReference<FakeChannel>();
        var gateway = new PiModelGateway(configuration(), "openai", "model", Duration.ofSeconds(20), (id, token) -> {
            tokenRef.set(token);
            if (stage.equals("credentials")) { entered.countDown(); await(release); }
            return session;
        }, (config, id, token, timeout) -> {
            entered.countDown(); await(release);
            var channel = new FakeChannel(id, success(result("ab")), token); channelRef.set(channel); return channel;
        });
        var outcome = new AtomicReference<Throwable>();
        Thread caller = Thread.ofVirtual().start(() -> capture(outcome, () -> gateway.complete(REQUEST)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var closing = new AtomicReference<Throwable>();
        Thread closer = Thread.ofVirtual().start(() -> capture(closing, gateway::close));
        waitCancelled(tokenRef.get()); release.countDown(); caller.join(5000); closer.join(5000);
        assertThat(caller.isAlive()).isFalse(); assertThat(closer.isAlive()).isFalse();
        assertThat(closing.get()).isNull(); assertThat(session.closed).isTrue();
        assertThat(outcome.get()).isInstanceOfSatisfying(ModelGatewayException.class,
                error -> assertThat(error.kind()).isEqualTo(FailureKind.CANCELLED));
        if (stage.equals("transport")) assertThat(channelRef.get().closed).isTrue();
        else assertThat(channelRef.get()).isNull();
    }

    @Test
    void unconfirmedCloseRemainsStickyEvenWhenFactoryEventuallyReturnsAndCleans() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Session session = new Session();
        var gateway = new PiModelGateway(configuration(), "openai", "model", Duration.ofSeconds(20), (id, token) -> {
            entered.countDown(); await(release); return session;
        }, (config, id, token, timeout) -> { throw new AssertionError("Closed factory must not start transport"); });
        var outcome = new AtomicReference<Throwable>();
        Thread caller = Thread.ofVirtual().start(() -> capture(outcome, () -> gateway.complete(REQUEST)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
        } finally { release.countDown(); }
        caller.join(5000);
        assertThat(caller.isAlive()).isFalse(); assertThat(session.closed).isTrue();
        assertThat(outcome.get()).isInstanceOf(ModelGatewayException.class).hasMessageContaining("CLEANUP_FAILED");
        assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class).hasMessage("CLEANUP_FAILED");
        assertThatThrownBy(() -> gateway.complete(REQUEST)).isInstanceOf(ModelGatewayException.class);
    }

    @Test
    void outputAggregateBudgetIsEnforcedBeforeCandidateCanBeReturned() throws Exception {
        var script = new ArrayList<Event>(); script.add(ready()); script.add(event("model.frame", object()));
        for (int i = 0; i < 18; i++) script.add(event("model.delta", object().put("text", "x".repeat(1_000_000))));
        try (var gateway = gateway(script)) {
            var error = failure(gateway, CancellationToken.none());
            assertThat(error.kind()).isEqualTo(FailureKind.INCOMPLETE_STREAM);
            assertThat(error.getMessage()).contains("LIMIT");
        }
    }

    @Test
    void cleanupFailureCannotRestoreTypedRetryEligibility() throws Exception {
        var gateway = gateway(errorScript(error("TRANSIENT", true, false)), (channel, token) -> channel.closeFailure = true);
        var error = failure(gateway, CancellationToken.none());
        assertThat(error.kind()).isEqualTo(FailureKind.PERMANENT);
        assertThat(error.getMessage()).contains("CLEANUP_FAILED");
        assertThatThrownBy(gateway::close).isInstanceOf(PiWorkerException.class);
    }

    private static void waitCancelled(CancellationToken token) {
        long start = System.nanoTime();
        while (!token.isCancellationRequested() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) Thread.onSpinWait();
        assertThat(token.isCancellationRequested()).isTrue();
    }

    @Test
    void configurationRejectsTimeoutGreaterThanThreeHundredSeconds() throws Exception {
        var config = configuration();
        assertThatThrownBy(() -> new PiModelGateway(config, "openai", "model", Duration.ofSeconds(301), (id, token) -> new Session()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PiModelGateway gateway(List<Event> script) throws Exception { return gateway(script, (channel, token) -> {}); }
    private PiModelGateway gateway(List<Event> script, Configure configure) throws Exception {
        return new PiModelGateway(configuration(), "openai", "model", Duration.ofSeconds(20), (id, token) -> {
            operations.add(id); var session = new Session(); sessions.add(session); return session;
        }, (config, id, token, timeout) -> {
            starts.incrementAndGet(); var channel = new FakeChannel(id, script, token); channels.add(channel);
            configure.apply(channel, token); return channel;
        });
    }
    private PiWorkerConfiguration configuration() throws Exception {
        Path worker = directory.resolve("worker.mjs");
        Files.writeString(worker, "// Fake transport never executes this file");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new PiWorkerConfiguration(java.toAbsolutePath(), worker.toAbsolutePath(), Map.of());
    }
    private static ModelGatewayException failure(PiModelGateway gateway, CancellationToken token) throws Exception {
        try { gateway.complete(REQUEST, ModelStreamObserver.noop(), token); throw new AssertionError("Expected failure"); }
        catch (ModelGatewayException failure) { return failure; }
    }
    private static ObjectNode object() { return PiPromptMapper.JSON.createObjectNode(); }
    private static Event event(String type, ObjectNode payload) { return new Event(type, payload); }
    private static Event ready() {
        var payload = object().put("piVersion", "0.85.1"); payload.putArray("operations").add("catalog").add("model");
        return event("operation.ready", payload);
    }
    private static Event completed() { return event("operation.completed", object().put("status", "completed")); }
    private static ObjectNode result(String text) {
        var value = object().put("text", text); value.putArray("toolCalls");
        value.putObject("usage").put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0);
        value.putObject("continuation").put("backend", "pi").put("providerId", "openai").put("modelId", "model")
                .put("payload", "{\"opaque\":true}");
        return value;
    }
    private static ArrayList<Event> success(ObjectNode result) {
        return new ArrayList<>(List.of(ready(), event("model.frame", object()), event("model.delta", object().put("text", "a")),
                event("model.delta", object().put("text", "b")), event("model.result", result), completed()));
    }
    private static ObjectNode error(String code, boolean retry, boolean frame) {
        return object().put("code", code).put("retryable", retry).put("providerFrame", frame);
    }
    private static ArrayList<Event> errorScript(ObjectNode error) {
        return new ArrayList<>(List.of(ready(), event("model.error", error), event("operation.failed", object().put("code", "WORKER_FAILED"))));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture latch timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void capture(AtomicReference<Throwable> outcome, Throwing action) {
        try { action.run(); } catch (Throwable failure) { outcome.set(failure); }
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    @FunctionalInterface private interface Configure { void apply(FakeChannel channel, CancellationToken token); }
    private record Event(String type, ObjectNode payload) { }

    private static final class Session implements PiCredentialSession {
        private boolean closed, closeFailure;
        private int handled;
        public ObjectNode handle(ProtocolFrame frame) {
            assertThat(frame.type()).isEqualTo("credential.request"); handled++;
            var result = object().put("requestId", 1).put("ok", true); result.putObject("result"); return result;
        }
        public void close() { closed = true; if (closeFailure) throw new IllegalStateException("PRIVATE_CREDENTIAL_CAUSE"); }
    }
    private static final class FakeChannel implements PiModelGateway.Channel {
        private final String id;
        private final List<Event> script;
        private final CancellationToken token;
        private final List<String> sent = new ArrayList<>();
        private int cursor, sequenceOffset, exit;
        private boolean wrongOperation, eof, exited, closed, closeFailure, readFailure;
        private Runnable onClose = () -> {};
        private FakeChannel(String id, List<Event> script, CancellationToken token) {
            this.id = id; this.script = script; this.token = token;
            exit = script.stream().anyMatch(e -> e.type.equals("operation.failed")) ? 1 : 0;
        }
        public void send(String type, ObjectNode payload) { sent.add(type); }
        public ProtocolFrame receive() {
            if (readFailure) throw new PiWorkerException(PiWorkerException.Code.IO_FAILED);
            if (cursor == script.size()) { eof = true; return null; }
            Event event = script.get(cursor);
            return new ProtocolFrame(wrongOperation ? "other" : id, cursor++ + sequenceOffset, event.type, event.payload);
        }
        public int awaitExit() { assertThat(eof).isTrue(); exited = true; return exit; }
        public void close() { onClose.run(); closed = true; if (closeFailure) throw new IllegalStateException("PRIVATE_PROCESS_CAUSE"); }
    }
}
