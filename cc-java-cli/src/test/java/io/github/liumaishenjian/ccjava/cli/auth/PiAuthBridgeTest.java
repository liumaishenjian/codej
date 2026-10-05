package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameCodec;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

/**
 * S15 / MODEL-13、CLI-09 / ADR-100 私有桥的独立合成反例。
 * Fake 只替换登录和流；真实 Java fixture 验证 stdin 停止握手、stdout EOF 与 exit。
 * 不运行 Node、真实 OAuth 或用户 home；测试定义不等于已运行证据，不提升 Capability。
 */
@Timeout(20)
class PiAuthBridgeTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OP = "bridge-test";
    private static final long LARGE_EPOCH = 9007199254740993L;
    private static final URI URL = URI.create("https://auth.openai.com/oauth/authorize?state=synthetic&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback");
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"secret", "manual_code"})
    void nonblockingSlotTransfersOwnedMaterialAndExactEpochOnlyAfterStopEofClose(String kind) throws Exception {
        var input = new ControlledInput();
        var output = new Capture();
        var identity = identity(kind.equals("manual_code"));
        AtomicReference<SecretMaterial> transferred = new AtomicReference<>();
        AtomicInteger resourcesClosed = new AtomicInteger();
        var run = start(identity, input, output, (interaction, token) -> {
            if (kind.equals("manual_code")) interaction.authorizationUrl(URL);
            try (var handle = interaction.request(new PiLoginOperation.Prompt("worker-private", 1, kind));
                 var material = material(handle, token)) {
                transferred.set(material);
                char[] copy = material.copyChars();
                try { assertThat(copy).containsExactly("synthetic-input".toCharArray()); }
                finally { Arrays.fill(copy, '\0'); }
            }
            return receipt(identity);
        }, () -> {
            assertThat(input.eofObserved).isTrue();
            assertThat(input.closeCalls.get()).isEqualTo(1);
            resourcesClosed.incrementAndGet();
        });
        if (kind.equals("manual_code")) assertThat(output.next("auth.url").payload().path("url").asString()).isEqualTo(URL.toASCIIString());
        assertThat(output.next("auth.prompt").payload().path("kind").asString()).isEqualTo(kind);
        input.send(response(OP, 0, 1, "synthetic-input"));
        output.next("auth.input_stop");
        assertThat(run.isDone()).isFalse();
        assertThat(resourcesClosed.get()).isZero();
        assertThat(output.types()).doesNotContain("auth.stored");
        input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isZero();
        var stored = output.next("auth.stored");
        assertThat(stored.payload().size()).isEqualTo(5);
        assertThat(stored.payload().path("authEpoch").isString()).isTrue();
        assertThat(stored.payload().path("authEpoch").asString()).isEqualTo(Long.toString(LARGE_EPOCH));
        assertThat(stored.payload().path("backend").asString()).isEqualTo("pi");
        assertThat(stored.payload().path("providerId").asString()).isEqualTo(identity.providerId());
        assertThat(resourcesClosed.get()).isEqualTo(1);
        assertThat(output.types().stream().filter("auth.stored"::equals).count()).isEqualTo(1);
        assertThat(output.closed).isTrue();
        assertThatThrownBy(() -> transferred.get().copyChars()).isInstanceOf(IllegalStateException.class);
        var valueField = SecretMaterial.class.getDeclaredField("value");
        valueField.setAccessible(true);
        for (char value : (char[]) valueField.get(transferred.get())) assertThat(value).isEqualTo('\0');
        assertZero(input.borrowedBuffer);
        output.assertOwnedBuffersErased();
        assertThat(output.text()).doesNotContain("synthetic-input", "worker-private", "credential.");
        long sequence = 0;
        for (var frame : output.snapshot()) {
            assertThat(frame.operationId()).isEqualTo(OP);
            assertThat(frame.sequence()).isEqualTo(sequence++);
        }
    }

    @Test void closedPromptLateResponseCannotCompleteTheNewPrompt() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        AtomicReference<PiLoginOperation.PromptHandle> cancelled = new AtomicReference<>();
        var run = start(identity(false), input, output, (interaction, token) -> {
            var old = interaction.request(new PiLoginOperation.Prompt("internal", 1, "secret"));
            cancelled.set(old); old.close(); old.close();
            try (var next = interaction.request(new PiLoginOperation.Prompt("internal", 2, "secret"));
                 var value = material(next, token)) {
                char[] owned = value.copyChars();
                try { assertThat(owned).containsExactly("new-value".toCharArray()); }
                finally { Arrays.fill(owned, '\0'); }
            }
            return receipt(identity(false));
        }, () -> { });
        output.next("auth.prompt"); output.next("auth.prompt_cancelled"); output.next("auth.prompt");
        input.send(response(OP, 0, 1, "late-old-value"));
        input.send(response(OP, 1, 2, "new-value"));
        output.next("auth.input_stop"); input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isZero();
        assertThat(cancelled.get().result().toCompletableFuture().isDone()).isFalse();
        assertThat(output.text()).doesNotContain("late-old-value", "new-value");
        assertZero(input.borrowedBuffer);
    }

    @Test void cancelledPromptLateResponseIsConsumedDuringStopButSecondLateFails() throws Exception {
        for (boolean duplicate : List.of(false, true)) {
            var input = new ControlledInput(); var output = new Capture();
            var run = start(identity(false), input, output, (interaction, token) -> {
                interaction.request(new PiLoginOperation.Prompt("internal", 1, "secret")).close();
                return receipt(identity(false));
            }, () -> { });
            output.next("auth.prompt"); output.next("auth.prompt_cancelled"); output.next("auth.input_stop");
            input.send(response(OP, 0, 1, "late"));
            if (duplicate) input.send(response(OP, 1, 1, "late-again"));
            input.end();
            assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(duplicate ? 1 : 0);
            if (duplicate) assertThat(output.types()).doesNotContain("auth.stored");
        }
    }

    static Stream<String> invalidFrames() {
        String valid = new String(response(OP, 0, 1, "synthetic"), StandardCharsets.UTF_8);
        return Stream.of(
                valid.replace(OP, "different-operation"),
                valid.replace("\"sequence\":0", "\"sequence\":1"),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
                valid.replace("\"value\":", "\"value\":\"a\",\"v\\u0061lue\":"),
                valid.replace("\"version\":1", "\"version\":1,\"unknown\":true"),
                valid.replace("\"value\":", "\"extra\":true,\"value\":"),
                valid.replace("\"promptId\":1", "\"promptId\":2"),
                valid.replace("\"promptId\":1", "\"promptId\":1.5"),
                valid.replace("\"promptId\":1", "\"promptId\":9007199254740993"),
                valid.replace("\"synthetic\"", "true"),
                valid.replace("auth.response", "credential.request"),
                valid.replace("synthetic", "\\ud800"),
                valid.substring(0, valid.length() - 1),
                valid + valid.replace("\"sequence\":0", "\"sequence\":1"));
    }

    @ParameterizedTest @MethodSource("invalidFrames")
    void malformedCorrelationAndRepeatedCurrentResponseFailClosed(String wire) throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        // 即使首个响应合法，也保持当前 slot 打开，重复当前响应必须失败而非新提示输入。
        var run = start(identity(false), input, output, (interaction, token) -> {
            try (var handle = interaction.request(new PiLoginOperation.Prompt("internal", 1, "secret"))) {
                // Fake 同样承担已转交材料的清理责任；不等待输入或取消 result 冒充 handle 关闭。
                handle.result().thenAccept(SecretMaterial::close);
                waitCancelled(token);
            }
            throw new IOException("synthetic-private-diagnostic");
        }, () -> { });
        output.next("auth.prompt"); input.send(wire.getBytes(StandardCharsets.UTF_8)); input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(output.types()).doesNotContain("auth.stored");
        assertThat(output.text()).doesNotContain("synthetic-private-diagnostic", "synthetic\"");
        assertThat(input.closeCalls.get()).isEqualTo(1);
        assertZero(input.borrowedBuffer);
        output.assertOwnedBuffersErased();
    }

    @ParameterizedTest @ValueSource(strings = {"utf8", "line", "cancel", "cancel-fields", "eof"})
    void byteLimitInvalidUtf8CancelAndPrematureEofNeverStore(String scenario) throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        var run = start(identity(false), input, output, waitingLogin(), () -> { });
        output.next("auth.prompt");
        switch (scenario) {
            case "utf8" -> input.send(new byte[]{(byte) 0xc3, 0x28, '\n'});
            case "line" -> input.send((" ".repeat(32 * 1024) + "\n").getBytes(StandardCharsets.UTF_8));
            case "cancel" -> input.send(frame(OP, 0, "auth.cancel", JSON.createObjectNode()));
            case "cancel-fields" -> input.send(frame(OP, 0, "auth.cancel", JSON.createObjectNode().put("value", "private")));
            default -> { }
        }
        input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(output.types()).doesNotContain("auth.stored");
        String expected = switch (scenario) { case "line" -> "LIMIT"; case "eof", "cancel" -> "CANCELLED"; default -> "PROTOCOL"; };
        assertThat(output.next("auth.failed").payload().path("code").asString()).isEqualTo(expected);
    }

    @Test void lateMaterialsStillConsumeSharedRawByteBudget() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        var run = start(identity(false), input, output, (interaction, token) -> {
            for (int id = 1; id <= 8; id++) interaction.request(new PiLoginOperation.Prompt("internal", id, "secret")).close();
            waitCancelled(token); throw new IOException("private");
        }, () -> { });
        for (int id = 1; id <= 8; id++) { output.next("auth.prompt"); output.next("auth.prompt_cancelled"); }
        for (int id = 1; id <= 8; id++) input.send(response(OP, id - 1, id, "x".repeat(16384)));
        input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(output.next("auth.failed").payload().path("code").asString()).isEqualTo("LIMIT");
        assertThat(output.types()).doesNotContain("auth.stored");
    }

    @Test void outputAndInputShareBudgetRatherThanTwoIndependent128KiBLimits() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        URI padded = URI.create(URL + "&padding=" + "x".repeat(16000));
        var run = start(identity(true), input, output, (interaction, token) -> {
            for (int id = 1; id <= 4; id++) {
                interaction.authorizationUrl(padded);
                interaction.request(new PiLoginOperation.Prompt("internal", id, "manual_code")).close();
            }
            waitCancelled(token); throw new IOException("private");
        }, () -> { });
        for (int id = 1; id <= 4; id++) { output.next("auth.url"); output.next("auth.prompt"); output.next("auth.prompt_cancelled"); }
        // 原始空白也计费，每个方向本身均低于 128KiB。
        for (int id = 1; id <= 4; id++) {
            String response = new String(response(OP, id - 1, id, "x".repeat(16384)), StandardCharsets.UTF_8);
            input.send((" ".repeat(256) + response).getBytes(StandardCharsets.UTF_8));
        }
        input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(output.types()).doesNotContain("auth.stored");
    }

    @Test void fiveHundredTwelveFrameBudgetCannotBeResetBySuccessfulWrites() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        var run = start(identity(true), input, output, (interaction, token) -> {
            for (int index = 0; index < 512; index++) {
                interaction.authorizationUrl(URI.create("https://auth.openai.com/"));
                if (!output.writes.tryAcquire(2, TimeUnit.SECONDS)) throw new IOException("write not delivered");
            }
            return receipt(identity(true));
        }, () -> { });
        assertThat(run.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(output.snapshot()).hasSize(512);
        assertThat(output.types()).doesNotContain("auth.stored", "auth.input_stop");
    }

    @ParameterizedTest @ValueSource(strings = {"no-eof", "input-close-fails", "input-close-blocks", "resources-fail", "resources-block"})
    void inputStopDoesNotPermitStoredWithoutRealEofAndConfirmedClose(String scenario) throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        CountDownLatch release = new CountDownLatch(1);
        input.failClose = scenario.equals("input-close-fails");
        if (scenario.equals("input-close-blocks")) input.closeBlock = release;
        var run = start(identity(false), input, output, (interaction, token) -> receipt(identity(false)), () -> {
            if (scenario.equals("resources-fail")) throw new IOException("private-close-diagnostic");
            if (scenario.equals("resources-block")) uninterruptible(release);
        });
        try {
            output.next("auth.input_stop");
            if (!scenario.equals("no-eof")) input.end();
            assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(output.types()).doesNotContain("auth.stored");
            assertThat(output.text()).doesNotContain("private-close-diagnostic");
            assertThat(input.closeCalls.get()).isEqualTo(1);
        } finally { release.countDown(); input.end(); }
    }

    @Test void cancellationAfterInputStopRejectsOtherwiseValidReceipt() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        var run = start(identity(false), input, output, (interaction, token) -> receipt(identity(false)), () -> { });
        output.next("auth.input_stop");
        input.send(frame(OP, 0, "auth.cancel", JSON.createObjectNode())); input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(output.types()).doesNotContain("auth.stored");
    }

    @Test void loginExceptionHasOnlyClosedClassificationAndBridgeCannotBeReused() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        AtomicInteger calls = new AtomicInteger();
        var bridge = new PiAuthBridge(OP, identity(false), input, output, (interaction, token) -> {
            calls.incrementAndGet(); throw new IOException("synthetic-secret-original-diagnostic");
        }, () -> { }, Duration.ofSeconds(5), Duration.ofMillis(500));
        assertThat(bridge.run()).isEqualTo(1);
        assertThat(bridge.run()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
        var failed = output.next("auth.failed");
        assertThat(failed.payload().size()).isEqualTo(1);
        assertThat(failed.payload().path("code").asString()).isEqualTo("LOGIN");
        assertThat(output.text()).doesNotContain("synthetic-secret-original-diagnostic", "IOException", "auth.stored");
    }

    @Test void badReceiptIdentityNullAndNonpositiveEpochAreNotRepairedBySnapshot() throws Exception {
        for (var value : Arrays.asList(null, new PiLoginOperation.Receipt(identity(true), 1),
                new PiLoginOperation.Receipt(identity(false), 0), new PiLoginOperation.Receipt(identity(false), -1))) {
            var input = new ControlledInput(); var output = new Capture();
            var run = start(identity(false), input, output, (interaction, token) -> value, () -> { });
            assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(output.next("auth.failed").payload().path("code").asString()).isEqualTo("LOGIN");
            assertThat(output.types()).doesNotContain("auth.stored", "auth.input_stop");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"write-fails", "write-blocks", "close-fails", "close-blocks"})
    void outputFailureOrUninterruptibleBlockHasBoundedNonzeroExit(String scenario) throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        CountDownLatch release = new CountDownLatch(1);
        output.failWrite = scenario.equals("write-fails");
        output.failClose = scenario.equals("close-fails");
        if (scenario.equals("write-blocks")) output.writeBlock = release;
        if (scenario.equals("close-blocks")) output.closeBlock = release;
        var run = start(identity(false), input, output,
                scenario.startsWith("write") ? waitingLogin() : (interaction, token) -> receipt(identity(false)), () -> { });
        try {
            if (scenario.startsWith("close")) { output.next("auth.input_stop"); input.end(); }
            assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
            if (scenario.startsWith("write")) assertThat(output.types()).doesNotContain("auth.stored");
            // stdout close 发生在 stored flush 后，客户端仍必须等 EOF 和 exit0；不能虚构撤回已发帧。
            assertThat(output.text()).doesNotContain("private-output-diagnostic");
            output.assertOwnedBuffersErased();
        } finally { release.countDown(); input.end(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void printStreamSwallowedWriteOrCloseIOExceptionStillPreventsZeroExit(boolean failOnClose) throws Exception {
        var input = new ControlledInput();
        var capture = new ByteArrayOutputStream();
        OutputStream failing = new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (!failOnClose) throw new IOException("private-printstream-diagnostic");
                capture.write(value);
                if (value == '\n' && capture.toString(StandardCharsets.UTF_8).contains("auth.input_stop")) input.end();
            }
            @Override public void close() throws IOException { throw new IOException("private-close-diagnostic"); }
        };
        var output = new java.io.PrintStream(failing, true, StandardCharsets.UTF_8);
        var bridge = new PiAuthBridge(OP, identity(false), input, output,
                (interaction, token) -> receipt(identity(false)), () -> { }, Duration.ofSeconds(5), Duration.ofMillis(500));
        assertThat(bridge.run()).isEqualTo(1);
        assertThat(capture.toString(StandardCharsets.UTF_8)).doesNotContain("private-printstream-diagnostic", "private-close-diagnostic");
    }

    @Test void processFixtureRequiresStdinHandshakeThenRealStdoutEofAndExitZero() throws Exception {
        Process process = process(PiAuthBridgeProcessFixture.class);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(readFrame(reader).type()).isEqualTo("auth.prompt");
            process.getOutputStream().write(response("fixture", 0, 1, "synthetic-process-input"));
            process.getOutputStream().flush();
            assertThat(readFrame(reader).type()).isEqualTo("auth.input_stop");
            assertThat(process.isAlive()).isTrue();
            process.getOutputStream().close();
            var stored = readFrame(reader);
            assertThat(stored.type()).isEqualTo("auth.stored");
            assertThat(stored.payload().path("authEpoch").asString()).isEqualTo(Long.toString(LARGE_EPOCH));
            assertThat(reader.readLine()).isNull();
            assertThat(process.waitFor(4, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(process.getErrorStream().readAllBytes()).isEmpty();
        } finally { terminate(process); }
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "no-eof"})
    void processFixtureCannotSucceedOnCancelOrUnclosedClientStdin(String scenario) throws Exception {
        Process process = process(PiAuthBridgeProcessFixture.class);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(readFrame(reader).type()).isEqualTo("auth.prompt");
            process.getOutputStream().write(scenario.equals("cancel")
                    ? frame("fixture", 0, "auth.cancel", JSON.createObjectNode())
                    : response("fixture", 0, 1, "synthetic-process-input"));
            process.getOutputStream().flush();
            if (scenario.equals("no-eof")) assertThat(readFrame(reader).type()).isEqualTo("auth.input_stop");
            assertThat(process.waitFor(6, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            String rest = reader.lines().reduce("", (a, b) -> a + b);
            assertThat(rest).doesNotContain("auth.stored", "synthetic-process-input");
            assertThat(process.getErrorStream().readAllBytes()).isEmpty();
        } finally { terminate(process); }
    }

    @Test void productionMainEnvPathWorksWithoutPiConfigurationAndDoesNotReadEnvironmentValue() throws Exception {
        Process process = process(PiAuthBridgeMain.class, "--operation-id", OP, "--provider", "deepseek",
                "--profile", "default", "--auth-method", "API_KEY", "--environment-name", "SYNTHETIC_KEY_REF");
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(readFrame(reader).type()).isEqualTo("auth.input_stop");
            process.getOutputStream().close();
            ProtocolFrame stored = readFrame(reader);
            assertThat(stored.type()).isEqualTo("auth.stored");
            assertThat(stored.payload().path("authEpoch").asString()).isEqualTo("1");
            assertThat(stored.payload().path("providerId").asString()).isEqualTo("deepseek");
            assertThat(reader.readLine()).isNull();
            assertThat(process.waitFor(4, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(process.getErrorStream().readAllBytes()).isEmpty();
        } finally { terminate(process); }
    }

    @Test void unclosedPromptAndLateUnsolicitedInputAfterStopFailClosed() throws Exception {
        for (boolean leavePromptOpen : List.of(true, false)) {
            var input = new ControlledInput(); var output = new Capture();
            var run = start(identity(false), input, output, (interaction, token) -> {
                if (leavePromptOpen) interaction.request(new PiLoginOperation.Prompt("internal", 1, "secret"));
                return receipt(identity(false));
            }, () -> { });
            if (!leavePromptOpen) {
                output.next("auth.input_stop");
                input.send(response(OP, 0, 1, "unsolicited-after-stop")); input.end();
            }
            assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(output.types()).doesNotContain("auth.stored");
        }
    }

    @Test void oversizedOutputFailsWithoutSkippingSequenceOrLeakingOriginalDiagnostic() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        var run = start(identity(true), input, output, (interaction, token) -> {
            interaction.authorizationUrl(URI.create(URL + "&padding=" + "x".repeat(32768)));
            throw new IOException("private-login-diagnostic");
        }, () -> { });
        assertThat(run.get(4, TimeUnit.SECONDS)).isEqualTo(1);
        var failed = output.next("auth.failed");
        assertThat(failed.sequence()).isZero();
        assertThat(failed.payload().path("code").asString()).isEqualTo("LIMIT");
        assertThat(output.text()).doesNotContain("private-login-diagnostic", "padding");
    }

    @Test void largestPositiveEpochIsStillAnExactDecimalString() throws Exception {
        var input = new ControlledInput(); var output = new Capture();
        var run = start(identity(false), input, output,
                (interaction, token) -> new PiLoginOperation.Receipt(identity(false), Long.MAX_VALUE), () -> { });
        output.next("auth.input_stop"); input.end();
        assertThat(run.get(4, TimeUnit.SECONDS)).isZero();
        var epoch = output.next("auth.stored").payload().path("authEpoch");
        assertThat(epoch.isString()).isTrue();
        assertThat(epoch.asString()).isEqualTo("9223372036854775807");
    }

    @Test void mainAcceptsOnlyFixedIdentityAndOptionalEnvReferenceName() {
        var args = PiAuthBridgeMain.parse(new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY"});
        assertThat(args.identity()).isEqualTo(identity(false));
        assertThat(args.environmentName()).isNull();
        var env = PiAuthBridgeMain.parse(new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY", "--environment-name", "MY_KEY"});
        assertThat(env.environmentName()).isEqualTo("MY_KEY");
        for (String[] invalid : List.of(
                new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "OAUTH"},
                new String[]{"--operation-id", OP, "--provider", "openai-codex", "--auth-method", "OAUTH", "--environment-name", "MY_KEY"},
                new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY", "--environment-name", "key=value"},
                new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY", "--environment-name", "x".repeat(129)},
                new String[]{"--operation-id", "bad/id", "--provider", "openai", "--auth-method", "API_KEY"},
                new String[]{"--operation-id", OP, "--provider", "unknown", "--auth-method", "API_KEY"},
                new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY", "--profile", "../bad"},
                new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY", "--provider", "openai"},
                new String[]{"--operation-id", OP, "--provider", "openai", "--auth-method", "API_KEY", "--secret", "synthetic"})) {
            assertThatThrownBy(() -> PiAuthBridgeMain.parse(invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("PI_BRIDGE_CONFIGURATION").hasNoCause();
        }
    }

    private static PiCredentialIdentity identity(boolean oauth) {
        return new PiCredentialIdentity(oauth ? "openai-codex" : "openai",
                oauth ? PiCredentialIdentity.AuthMethod.OAUTH : PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }
    private static PiLoginOperation.Receipt receipt(PiCredentialIdentity identity) { return new PiLoginOperation.Receipt(identity, LARGE_EPOCH); }
    private static byte[] response(String operation, long sequence, int prompt, String value) {
        return frame(operation, sequence, "auth.response", JSON.createObjectNode().put("promptId", prompt).put("value", value));
    }
    private static byte[] frame(String operation, long sequence, String type, ObjectNode payload) {
        return FrameCodec.encode(new ProtocolFrame(operation, sequence, type, payload));
    }
    private static CompletableFuture<Integer> start(PiCredentialIdentity identity, ControlledInput input, Capture output,
                                                    PiAuthBridge.Login login, AutoCloseable resources) {
        var bridge = new PiAuthBridge(OP, identity, input, output, login, resources, Duration.ofSeconds(5), Duration.ofMillis(500));
        var result = new CompletableFuture<Integer>();
        Thread thread = new Thread(() -> {
            try { result.complete(bridge.run()); } catch (Throwable failed) { result.completeExceptionally(failed); }
        }, "pi-bridge-test-host");
        thread.setDaemon(true); thread.start();
        return result;
    }
    private static SecretMaterial material(PiLoginOperation.PromptHandle handle, CancellationToken token) throws Exception {
        while (!token.isCancellationRequested()) {
            try { return handle.result().toCompletableFuture().get(20, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException ignored) { }
        }
        throw new IOException("synthetic-cancelled");
    }
    private static PiAuthBridge.Login waitingLogin() {
        return (interaction, token) -> {
            try (var handle = interaction.request(new PiLoginOperation.Prompt("internal", 1, "secret"));
                 var ignored = material(handle, token)) {
                waitCancelled(token);
            }
            throw new IOException("synthetic-private-diagnostic");
        };
    }
    private static void waitCancelled(CancellationToken token) throws InterruptedException {
        while (!token.isCancellationRequested()) Thread.sleep(5);
    }
    private static void uninterruptible(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { latch.await(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void assertZero(byte[] bytes) {
        assertThat(bytes).isNotNull();
        for (byte value : bytes) assertThat(value).isZero();
    }
    private Process process(Class<?> entry, String... args) throws IOException {
        Path home = Files.createTempDirectory(temporary, "home-");
        Path directory = Files.createTempDirectory(temporary, "workspace-");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        // Surefire 的显式 classpath 优先；绝对化后才改变子进程工作目录。
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        classpath = String.join(java.io.File.pathSeparator, Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString()).toList());
        var command = new ArrayList<>(List.of(executable, "-Duser.home=" + home, "-Duser.dir=" + directory,
                "-cp", classpath, entry.getName()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        // 不继承 JVM 注入、代理、Pi 配置或用户 credential；仅保留 Windows JVM 所需系统定位。
        String systemRoot = builder.environment().get("SystemRoot");
        builder.environment().clear();
        if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
        builder.environment().put("TEMP", temporary.toString());
        builder.environment().put("TMP", temporary.toString());
        return builder.start();
    }
    private static ProtocolFrame readFrame(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        assertThat(line).as("real child frame before EOF").isNotNull();
        return FrameCodec.decodeLine((line + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void terminate(Process process) throws InterruptedException {
        if (process.isAlive()) { process.destroyForcibly(); assertThat(process.waitFor(4, TimeUnit.SECONDS)).isTrue(); }
    }

    /** 可控 EOF 与不可中断 close；只检查桥借入的自有数组，不要求擦除测试拥有的源数据。 */
    private static final class ControlledInput extends InputStream {
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current;
        private int offset;
        private volatile boolean ended;
        volatile boolean eofObserved;
        volatile byte[] borrowedBuffer;
        volatile boolean failClose;
        volatile CountDownLatch closeBlock;
        final AtomicInteger closeCalls = new AtomicInteger();
        void send(byte[] bytes) { chunks.add(bytes); }
        void end() { chunks.add(new byte[0]); }
        @Override public int read() { throw new AssertionError("bulk read required"); }
        @Override public int read(byte[] target, int start, int length) throws IOException {
            borrowedBuffer = target;
            if (ended) { eofObserved = true; return -1; }
            if (current == null || offset == current.length) {
                try { current = chunks.take(); offset = 0; }
                catch (InterruptedException ignored) { throw new IOException("synthetic-read-interrupted"); }
                if (current.length == 0) { ended = true; eofObserved = true; return -1; }
            }
            int count = Math.min(length, current.length - offset);
            System.arraycopy(current, offset, target, start, count); offset += count;
            return count;
        }
        @Override public void close() throws IOException {
            closeCalls.incrementAndGet();
            if (closeBlock != null) uninterruptible(closeBlock);
            end();
            if (failClose) throw new IOException("private-close-diagnostic");
        }
    }

    /** 保存接收帧副本与原数组引用：前者验证 wire，后者验证 owned 缓冲擦除。 */
    private static final class Capture extends OutputStream {
        private final BlockingQueue<ProtocolFrame> received = new LinkedBlockingQueue<>();
        private final List<ProtocolFrame> frames = new ArrayList<>();
        private final List<byte[]> borrowed = new ArrayList<>();
        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
        final Semaphore writes = new Semaphore(0);
        volatile CountDownLatch writeBlock;
        volatile CountDownLatch closeBlock;
        volatile boolean failWrite;
        volatile boolean failClose;
        volatile boolean closed;
        @Override public void write(int value) { throw new AssertionError("whole frame required"); }
        @Override public void write(byte[] bytes) throws IOException {
            synchronized (this) { borrowed.add(bytes); }
            if (writeBlock != null) uninterruptible(writeBlock);
            if (failWrite) throw new IOException("private-output-diagnostic");
            ProtocolFrame frame = FrameCodec.decodeLine(bytes.clone());
            synchronized (this) { frames.add(frame); copy.writeBytes(bytes); }
            received.add(frame); writes.release();
        }
        @Override public void close() throws IOException {
            if (closeBlock != null) uninterruptible(closeBlock);
            if (failClose) throw new IOException("private-output-diagnostic");
            closed = true;
        }
        ProtocolFrame next(String type) throws InterruptedException {
            ProtocolFrame frame = received.poll(3, TimeUnit.SECONDS);
            assertThat(frame).as(type).isNotNull();
            assertThat(frame.type()).isEqualTo(type);
            return frame;
        }
        synchronized List<ProtocolFrame> snapshot() { return List.copyOf(frames); }
        synchronized List<String> types() { return frames.stream().map(ProtocolFrame::type).toList(); }
        synchronized String text() { return copy.toString(StandardCharsets.UTF_8); }
        synchronized void assertOwnedBuffersErased() { for (byte[] value : borrowed) assertZero(value); }
    }
}
