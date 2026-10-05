package io.github.liumaishenjian.ccjava.model.pi.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 所有普通测试使用独立 JVM 假 Worker；不依赖 Node 安装或任何网络/真实凭证。 */
class PiWorkerConnectionTest {
    @TempDir Path directory;
    private final AtomicReference<Process> child = new AtomicReference<>();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toAbsolutePath();

    @AfterEach
    void ownedProcessHasExited() {
        Process process = child.get();
        if (process != null) {
            try { assertThat(process.isAlive()).as("owned fake process must be confirmed dead").isFalse(); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    @Test
    void authenticationRejectsOversizedOutgoingFrameBeforePublishing() throws Exception {
        try (var connection = openAuthentication("sink")) {
            expect(PiWorkerException.Code.LIMIT_EXCEEDED,
                    () -> connection.send("event", payload().put("value", "x".repeat(32768))));
        }
    }

    @Test
    void authenticationCountsRawWhitespaceRatherThanReencodedObjects() throws Exception {
        try (var connection = openAuthentication("auth-space")) {
            expect(PiWorkerException.Code.LIMIT_EXCEEDED, connection::receive);
        }
    }

    @Test
    void authenticationCombinesBothDirectionsInsteadOfAllowing128KiBEach() throws Exception {
        try (var connection = openAuthentication("echo")) {
            expect(PiWorkerException.Code.LIMIT_EXCEEDED, () -> {
                for (int i = 0; i < 3; i++) {
                    connection.send("event", payload().put("value", "x".repeat(24000)));
                    connection.receive();
                }
            });
        }
    }

    @Test
    void authenticationIncludesDiscardedStderrInSharedPhysicalBudget() throws Exception {
        try (var connection = openAuthentication("auth-stderr")) {
            expect(PiWorkerException.Code.LIMIT_EXCEEDED, () -> {
                connection.send("event", payload().put("value", "x".repeat(24000)));
                connection.receive();
            });
        }
    }

    private PiWorkerConnection openAuthentication(String mode) throws Exception {
        return PiWorkerConnection.startAuthentication(new PiWorkerConfiguration(JAVA, fixture(mode), Map.of()),
                "test_operation", CancellationToken.none(), TIMEOUT, this::startFake);
    }

    @Test
    void terminalAndRealEofThenExitAreRequired() throws Exception {
        try (var connection = open("success", CancellationToken.none(), TIMEOUT)) {
            assertThat(connection.toString()).isEqualTo("PI_WORKER_CONNECTION");
            assertThat(connection.receive().payload().path("value").asString()).isEqualTo("synthetic-secret");
            assertThat(connection.receive().type()).isEqualTo("operation.completed");
            assertThat(connection.awaitExit()).isZero();
            expect(PiWorkerException.Code.CLOSED, connection::receive);
        }
    }

    @Test
    void nonzeroFailedTerminalIsReturnedWithoutClaimingSuccess() throws Exception {
        try (var connection = open("failed", CancellationToken.none(), TIMEOUT)) {
            assertThat(connection.receive().type()).isEqualTo("operation.failed");
            assertThat(connection.awaitExit()).isEqualTo(4);
        }
    }

    @Test
    void environmentIsClearedAndOnlyExplicitProxyIsPassed() throws Exception {
        Path fixture = fixture("environment");
        var config = new PiWorkerConfiguration(JAVA, fixture,
                Map.of("HTTP_PROXY", "http://synthetic:credential@127.0.0.1:9"));
        assertThat(config.toString()).doesNotContain("credential", directory.toString());
        assertThat(config.builder().command()).containsExactly(JAVA.toRealPath().toString(), fixture.toRealPath().toString());
        assertThat(config.builder().environment()).doesNotContainKeys("PATH", "NODE_OPTIONS", "OPENAI_API_KEY", "HOME", "USERPROFILE");
        try (var connection = PiWorkerConnection.start(config, "test_operation", CancellationToken.none(), TIMEOUT, this::startFake)) {
            ObjectNode payload = connection.receive().payload();
            assertThat(payload.path("clean").asBoolean()).isTrue();
            assertThat(payload.path("proxy").asBoolean()).isTrue();
            assertThat(payload.path("cwd").asBoolean()).isTrue();
            assertThat(connection.receive().type()).isEqualTo("operation.completed");
            assertThat(connection.awaitExit()).isZero();
        }
        assertThat(new PiWorkerConfiguration(JAVA, fixture, Map.of()).builder().environment())
                .doesNotContainKeys("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NODE_OPTIONS", "OPENAI_API_KEY", "PATH", "http_proxy", "PI_CONFIG_DIR"})
    void rejectsUntrustedEnvironmentNames(String name) throws Exception {
        Path fixture = fixture("success");
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(JAVA, fixture, Map.of(name, "synthetic-secret")));
    }

    @Test
    void rejectsInvalidExecutableEntrypointAndProxy() throws Exception {
        Path fixture = fixture("success");
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(Path.of("node"), fixture, Map.of()));
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(directory, fixture, Map.of()));
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(JAVA, directory.resolve("other.mjs"), Map.of()));
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(JAVA, fixture, Map.of("HTTPS_PROXY", "secret\nvalue")));
    }

    @Test
    void rechecksFileBeforeLaunchAndSanitizesStartFailure() throws Exception {
        Path fixture = fixture("success");
        var config = new PiWorkerConfiguration(JAVA, fixture, Map.of());
        Files.delete(fixture);
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> PiWorkerConnection.start(config, "test_operation", CancellationToken.none(), TIMEOUT));
        Files.writeString(fixture, "success");
        expect(PiWorkerException.Code.START_FAILED,
                () -> PiWorkerConnection.start(config, "test_operation", CancellationToken.none(), TIMEOUT,
                        builder -> { throw new java.io.IOException("synthetic-secret"); }));
    }

    @Test
    void nonExecutableOrdinaryFileFailsClosed() throws Exception {
        Path fixture = fixture("success");
        Path executable = directory.resolve("not-node.exe");
        Files.writeString(executable, "not an executable synthetic-secret");
        var config = new PiWorkerConfiguration(executable, fixture, Map.of());
        expect(PiWorkerException.Code.START_FAILED,
                () -> PiWorkerConnection.start(config, "test_operation", CancellationToken.none(), TIMEOUT));
    }

    @ParameterizedTest
    @CsvSource({"cross,PROTOCOL_INVALID", "skip,PROTOCOL_INVALID", "double,PROTOCOL_INVALID",
            "half,PROTOCOL_INVALID", "missing,PROTOCOL_INVALID", "crash,PROTOCOL_INVALID",
            "after,PROTOCOL_INVALID", "stderr,LIMIT_EXCEEDED", "huge,LIMIT_EXCEEDED",
            "total,LIMIT_EXCEEDED", "frames,LIMIT_EXCEEDED"})
    void rejectsMalformedAndOverBudgetWorkers(String mode, PiWorkerException.Code code) {
        expect(code, () -> {
            try (var connection = open(mode, CancellationToken.none(), Duration.ofSeconds(40))) {
                while (true) {
                    ProtocolFrame frame = connection.receive();
                    if (frame.type().startsWith("operation.")) connection.awaitExit();
                }
            }
        });
    }

    @Test
    void automaticSequenceAndBidirectionalPipe() throws Exception {
        try (var connection = open("echo", CancellationToken.none(), TIMEOUT)) {
            for (int i = 0; i < 3; i++) {
                String type = i == 2 ? "operation.completed" : "event";
                connection.send(type, payload().put("synthetic", "secret"));
                ProtocolFrame frame = connection.receive();
                assertThat(frame.sequence()).isEqualTo(i);
                assertThat(frame.type()).isEqualTo(type);
            }
            assertThat(connection.awaitExit()).isZero();
        }
    }

    @Test
    void invalidSendPoisonsConnectionWithoutLeakingInput() throws Exception {
        try (var connection = open("idle", CancellationToken.none(), TIMEOUT)) {
            expect(PiWorkerException.Code.PROTOCOL_INVALID, () -> connection.send("INVALID-secret", payload()));
            expect(PiWorkerException.Code.PROTOCOL_INVALID, connection::receive);
        }
    }

    @Test
    void closeDuringEncodingCannotLeaveSecretBytesQueuedAfterCleanup() throws Exception {
        try (var connection = open("idle", CancellationToken.none(), TIMEOUT)) {
            // 确定性地把关闭安排在发送前校验之后、入队之前；不依赖线程调度碰运气。
            ObjectNode value = new ObjectNode(tools.jackson.databind.node.JsonNodeFactory.instance) {
                @Override public ObjectNode deepCopy() {
                    connection.close();
                    return super.deepCopy();
                }
            };
            value.put("value", "SYNTHETIC_PRIVATE_MATERIAL");
            expect(PiWorkerException.Code.CLOSED, () -> connection.send("event", value));
            var field = PiWorkerConnection.class.getDeclaredField("outgoing");
            field.setAccessible(true);
            assertThat((java.util.Collection<?>) field.get(connection)).isEmpty();
        }
    }

    @Test
    void oversizedSendIsRejectedBeforeWriting() throws Exception {
        try (var connection = open("idle", CancellationToken.none(), TIMEOUT)) {
            expect(PiWorkerException.Code.LIMIT_EXCEEDED,
                    () -> connection.send("event", payload().put("value", "x".repeat(1024 * 1024))));
        }
    }

    @Test
    void awaitExitCannotSkipTerminalConsumption() throws Exception {
        try (var connection = open("idle", CancellationToken.none(), TIMEOUT)) {
            expect(PiWorkerException.Code.PROTOCOL_INVALID, connection::awaitExit);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "terminal-hang"})
    void eofOrProcessThatNeverEndsIsBoundedByTotalDeadline(String mode) {
        expect(PiWorkerException.Code.DEADLINE_EXCEEDED, () -> {
            try (var connection = open(mode, CancellationToken.none(), Duration.ofSeconds(2))) {
                connection.receive();
                connection.awaitExit();
            }
        });
    }

    @Test
    void fullReceiveQueueIsCancelledWithoutConsumerDrain() throws Exception {
        var token = new TestToken();
        try (var connection = open("flood", token, TIMEOUT)) {
            Thread.sleep(600);
            long start = System.nanoTime();
            token.cancel();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
            expect(PiWorkerException.Code.CANCELLED, connection::receive);
        }
        assertThat(token.callbacks).isEmpty();
    }

    @Test
    void fullReceiveQueueReleasesOnDeadlineWithoutAnotherApiCall() throws Exception {
        try (var connection = open("flood", CancellationToken.none(), Duration.ofSeconds(2))) {
            assertThat(child.get().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            expect(PiWorkerException.Code.DEADLINE_EXCEEDED, connection::receive);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedWriterIsReleasedByCancellationOrDeadline(boolean cancel) throws Exception {
        var token = new TestToken();
        try (var connection = open("no-read", token, Duration.ofSeconds(2))) {
            Thread canceller = cancel ? Thread.ofVirtual().start(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                token.cancel();
            }) : null;
            try {
                expect(cancel ? PiWorkerException.Code.CANCELLED : PiWorkerException.Code.DEADLINE_EXCEEDED,
                        () -> connection.send("event", payload().put("value", "x".repeat(900000))));
            } finally {
                if (canceller != null) canceller.join();
            }
        }
    }

    @Test
    void concurrentSendLockWaitAlsoObservesCancellation() throws Exception {
        var token = new TestToken();
        try (var connection = open("no-read", token, TIMEOUT)) {
            AtomicReference<Throwable> first = new AtomicReference<>();
            AtomicReference<Throwable> second = new AtomicReference<>();
            Runnable sendFirst = () -> {
                try { connection.send("event", payload().put("value", "x".repeat(900000))); }
                catch (Throwable failure) { first.set(failure); }
            };
            Thread writer = Thread.ofVirtual().start(sendFirst);
            Thread.sleep(150);
            Thread waiting = Thread.ofVirtual().start(() -> {
                try { connection.send("event", payload()); }
                catch (Throwable failure) { second.set(failure); }
            });
            Thread.sleep(150);
            token.cancel();
            assertThat(writer.join(Duration.ofSeconds(3))).isTrue();
            assertThat(waiting.join(Duration.ofSeconds(3))).isTrue();
            assertThat(first.get()).isInstanceOfSatisfying(PiWorkerException.class,
                    failure -> assertThat(failure.code()).isEqualTo(PiWorkerException.Code.CANCELLED));
            assertThat(second.get()).isInstanceOfSatisfying(PiWorkerException.class,
                    failure -> assertThat(failure.code()).isEqualTo(PiWorkerException.Code.CANCELLED));
        }
    }

    @Test
    void delayedStartupStillUsesOriginalDeadlineAndCleansLateOwnedProcess() throws Exception {
        var config = new PiWorkerConfiguration(JAVA, fixture("idle"), Map.of());
        expect(PiWorkerException.Code.DEADLINE_EXCEEDED,
                () -> PiWorkerConnection.start(config, "test_operation", CancellationToken.none(), Duration.ofMillis(100),
                        builder -> {
                            try { Thread.sleep(250); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            return startFake(builder);
                        }));
        assertThat(child.get()).isNotNull();
        assertThat(child.get().isAlive()).isFalse();
    }

    @Test
    void tokenRemainingTimeNarrowsExplicitTimeoutAndNeverResets() {
        var token = new TestToken();
        token.remaining = Duration.ofSeconds(1);
        long begun = System.nanoTime();
        expect(PiWorkerException.Code.DEADLINE_EXCEEDED, () -> {
            try (var connection = open("idle", token, Duration.ofSeconds(20))) { connection.receive(); }
        });
        assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void cancelledBeforeStartDoesNotLaunch() throws Exception {
        var token = new TestToken();
        token.cancel();
        expect(PiWorkerException.Code.CANCELLED, () -> open("idle", token, TIMEOUT));
        assertThat(child.get()).isNull();
        assertThat(token.callbacks).isEmpty();
    }

    @Test
    void closeIsIdempotentAndConfirmsOwnedIoThreadsStop() throws Exception {
        var connection = open("no-read", CancellationToken.none(), TIMEOUT);
        connection.close();
        connection.close();
        expect(PiWorkerException.Code.CLOSED, connection::receive);
        expect(PiWorkerException.Code.CLOSED, () -> connection.send("event", payload()));
        assertThat(child.get().isAlive()).isFalse();
    }

    @Test
    void totalInputBudgetDoesNotResetBetweenSends() throws Exception {
        try (var connection = open("sink", CancellationToken.none(), TIMEOUT)) {
            var data = payload().put("value", "x".repeat(900000));
            for (int i = 0; i < 37; i++) connection.send("event", data);
            expect(PiWorkerException.Code.LIMIT_EXCEEDED, () -> connection.send("event", data));
        }
    }

    @Test
    void interruptedReceiveClosesWithoutLosingInterruptStatus() throws Exception {
        try (var connection = open("idle", CancellationToken.none(), TIMEOUT)) {
            Thread.currentThread().interrupt();
            try {
                expect(PiWorkerException.Code.CANCELLED, connection::receive);
                connection.close();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    @Test
    void failedCloseIsNeverReportedAsCleanedSuccessfully() throws Exception {
        var config = new PiWorkerConfiguration(JAVA, fixture("idle"), Map.of());
        var connection = PiWorkerConnection.start(config, "test_operation", CancellationToken.none(), TIMEOUT,
                builder -> new CloseFailureProcess(startFake(builder)));
        expect(PiWorkerException.Code.CLEANUP_FAILED, connection::close);
        expect(PiWorkerException.Code.CLEANUP_FAILED, connection::close);
        assertThat(child.get().isAlive()).isFalse();
    }

    @Test
    void symbolicLinkFilesAreRejectedWhenPlatformAllowsFixture() throws Exception {
        Path target = fixture("success");
        Path linkDirectory = Files.createDirectory(directory.resolve("link"));
        Path link = linkDirectory.resolve("worker.mjs");
        try { Files.createSymbolicLink(link, target); }
        catch (java.io.IOException | UnsupportedOperationException denied) {
            org.junit.jupiter.api.Assumptions.abort("platform cannot create symlink fixture");
        }
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(JAVA, link, Map.of()));
        expect(PiWorkerException.Code.CONFIGURATION_INVALID,
                () -> new PiWorkerConfiguration(link, target, Map.of()));
    }

    /** 故意让流关闭失败；直接子进程仍由生产清理路径销毁。 */
    private static final class CloseFailureProcess extends Process {
        private final Process delegate;
        private CloseFailureProcess(Process delegate) { this.delegate = delegate; }
        @Override public java.io.OutputStream getOutputStream() { return delegate.getOutputStream(); }
        @Override public java.io.InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public java.io.InputStream getInputStream() {
            return new java.io.FilterInputStream(delegate.getInputStream()) {
                @Override public void close() throws java.io.IOException {
                    super.close();
                    throw new java.io.IOException("synthetic-secret");
                }
            };
        }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public void destroy() { delegate.destroy(); }
        @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
        @Override public boolean isAlive() { return delegate.isAlive(); }
    }

    private Path fixture(String mode) throws Exception {
        Path fixture = directory.resolve("worker.mjs");
        Files.writeString(fixture, mode);
        return fixture;
    }

    private PiWorkerConnection open(String mode, CancellationToken token, Duration timeout) throws Exception {
        return PiWorkerConnection.start(new PiWorkerConfiguration(JAVA, fixture(mode), Map.of()),
                "test_operation", token, timeout, this::startFake);
    }

    private Process startFake(ProcessBuilder builder) throws java.io.IOException {
        String fixture = builder.command().get(1);
        String classes;
        try { classes = Path.of(FakePiWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception invalid) { throw new java.io.IOException("FAKE_SETUP_FAILED"); }
        builder.command(JAVA.toString(), "-cp", classes, FakePiWorker.class.getName(), fixture);
        Process process = builder.start();
        child.set(process);
        return process;
    }

    private static ObjectNode payload() { return JsonMapper.builder().build().createObjectNode(); }

    private static void expect(PiWorkerException.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PiWorkerException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.toString()).isEqualTo(code.name());
            assertThat(failure.getMessage()).doesNotContain("synthetic-secret", "credential");
        });
    }

    private static final class TestToken implements CancellationToken {
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        private Duration remaining;
        public boolean isCancellationRequested() { return cancelled; }
        public Optional<Duration> remainingTime() { return Optional.ofNullable(remaining); }
        public Registration onCancellation(Runnable action) {
            callbacks.add(action);
            if (cancelled) action.run();
            return () -> callbacks.remove(action);
        }
        private void cancel() {
            cancelled = true;
            callbacks.forEach(Runnable::run);
        }
    }
}
