package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenRouterBrowserLoginTest {
    private static final Path NODE = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("node.exe");
    private static final Path ENTRY = NODE.getParent().resolve("provider-pi/login.mjs");
    private static final String URL = "https://openrouter.ai/auth?callback_url=http%3A%2F%2F127.0.0.1%3A3000%2Foauth%2Fcallback%2Ffake-id"
            + "&code_challenge=" + "a".repeat(43) + "&code_challenge_method=S256";

    @Test void returnsOnlyOwnedSecretAndBuildsPrivateCleanProcess() {
        var process = new FakeProcess("{\"type\":\"ready\"}\n{\"type\":\"auth_url\",\"url\":\"" + URL
                + "\"}\n{\"type\":\"success\",\"key\":\"synthetic-key\"}\n");
        var notices = new java.util.ArrayList<String>();
        var login = new OpenRouterBrowserLogin(NODE, ENTRY, Duration.ofSeconds(1), System::nanoTime, builder -> {
            assertThat(builder.command()).containsExactly(NODE.toString(), ENTRY.toString());
            assertThat(builder.directory()).isEqualTo(ENTRY.getParent().toFile());
            assertThat(builder.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
            assertThat(builder.environment()).containsEntry("PI_OAUTH_CALLBACK_HOST", "127.0.0.1")
                    .containsEntry("DO_NOT_TRACK", "1").containsEntry("OTEL_SDK_DISABLED", "true");
            assertThat(builder.environment().keySet()).allMatch(key -> java.util.Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP",
                    "TMPDIR", "PATH", "PI_OAUTH_CALLBACK_HOST", "DO_NOT_TRACK", "OTEL_SDK_DISABLED").contains(key.toUpperCase(java.util.Locale.ROOT)));
            return process;
        });
        try (SecretMaterial result = login.login(CancellationToken.none(), notices::add)) {
            char[] copy = result.copyChars();
            try { assertThat(copy).isEqualTo("synthetic-key".toCharArray()); }
            finally { java.util.Arrays.fill(copy, '\0'); }
            assertThat(result.toString()).doesNotContain("synthetic-key");
        }
        assertThat(notices).containsExactly(URL);
        assertThat(process.closed).isTrue();
        assertThat(process.sent.toString(StandardCharsets.UTF_8)).contains("start", "login").doesNotContain("synthetic-key");
    }

    @Test void invalidProtocolOversizeSecretsAndDuplicateTerminalAreSanitized() {
        for (String output : new String[] {
                "{\"type\":\"error\",\"code\":\"SECRET_ERROR\"}\n",
                "SECRET_ERROR\n", "x".repeat(32769) + "\n",
                "{\"type\":\"ready\",\"type\":\"ready\"}\n",
                "{\"type\":\"success\",\"key\":\"SECRET_ERROR\"}\n",
                "{\"type\":\"ready\"}\n{\"type\":\"success\",\"key\":\"bad\\nkey\"}\n",
                "{\"type\":\"ready\"}\n{\"type\":\"success\",\"key\":\"   \"}\n",
                "{\"type\":\"ready\"}\n{\"type\":\"success\",\"key\":\"synthetic\"}\n{\"type\":\"success\",\"key\":\"late\"}\n",
                "{\"type\":\"ready\"}\n{\"type\":\"success\",\"key\":\"" + "a".repeat(16385) + "\"}\n",
                "{\"type\":\"ready\"}\n{\"type\":\"success\",\"key\":\"synthetic\"}" }) {
            var process = new FakeProcess(output);
            var login = adapter(process);
            assertThatThrownBy(() -> login.login(CancellationToken.none(), ignored -> {}))
                    .isInstanceOf(IllegalStateException.class).hasMessage("OpenRouter 浏览器登录未完成").hasNoCause();
            assertThat(process.closed).isTrue();
        }
    }

    @Test void stdoutTotalIsBoundedEvenForManyValidUrls() {
        String frame = "{\"type\":\"auth_url\",\"url\":\"" + URL + "\"}\n";
        var process = new FakeProcess("{\"type\":\"ready\"}\n" + frame.repeat(700)
                + "{\"type\":\"success\",\"key\":\"synthetic\"}\n");
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> adapter(process).login(CancellationToken.none(), ignored -> calls.incrementAndGet()))
                .hasMessage("OpenRouter 浏览器登录未完成");
        assertThat(calls.get()).isLessThan(700);
    }

    @Test void strictAuthorizationUrlNeverNotifiesUntrustedEndpoints() {
        assertThat(OpenRouterBrowserLogin.validateAuthorizationUrl(URL)).isEqualTo(URL);
        for (String url : new String[] { URL.replace("https:", "http:"), URL.replace("openrouter.ai", "openrouter.ai.evil"),
                URL.replace("127.0.0.1", "localhost"), URL + "&key=SECRET", URL + "#SECRET", URL.replace("S256", "plain") }) {
            assertThatThrownBy(() -> OpenRouterBrowserLogin.validateAuthorizationUrl(url)).hasMessage("OpenRouter 浏览器登录未完成");
        }
    }

    @Test void cancellationAndMonotonicDeadlineStopWaitingProcess() {
        for (boolean cancel : new boolean[] { true, false }) {
            var process = new FakeProcess("");
            process.block = true;
            var clock = new AtomicLong();
            var token = new CancellationToken() {
                @Override public boolean isCancellationRequested() { return cancel && clock.get() > 60_000_000; }
                @Override public Registration onCancellation(Runnable action) { return () -> {}; }
            };
            var login = new OpenRouterBrowserLogin(NODE, ENTRY, Duration.ofMillis(100),
                    () -> clock.getAndAdd(30_000_000), builder -> process);
            assertThatThrownBy(() -> login.login(token, ignored -> {})).hasMessage("OpenRouter 浏览器登录未完成");
            assertThat(process.closed).isTrue();
        }
    }

    @Test void rejectsShellWrappersAndLaunchErrorsWithoutOriginalCause() {
        assertThatThrownBy(() -> new OpenRouterBrowserLogin(NODE.resolveSibling("node.cmd"), ENTRY)).isInstanceOf(IllegalStateException.class);
        var login = new OpenRouterBrowserLogin(NODE, ENTRY, Duration.ofSeconds(1), System::nanoTime,
                builder -> { throw new java.io.IOException("SECRET_ERROR"); });
        assertThatThrownBy(() -> login.login(CancellationToken.none(), ignored -> {}))
                .hasMessage("OpenRouter 浏览器登录未完成").hasNoCause();
    }

    @Test void consumedFrameIsWipedOnSuccessAndException() {
        byte[] success = "synthetic-frame-key".getBytes(StandardCharsets.UTF_8);
        try (var frame = new OpenRouterBrowserLogin.Frame(success, false, false)) {
            assertThat(frame.line()).isSameAs(success);
        }
        assertThat(success).containsOnly((byte) 0);
        byte[] rejected = "synthetic-invalid-frame".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> {
            try (var ignored = new OpenRouterBrowserLogin.Frame(rejected, false, false)) {
                throw new IllegalStateException("rejected");
            }
        }).hasMessage("rejected");
        assertThat(rejected).containsOnly((byte) 0);
    }

    @Test void closingChannelWipesPartialLineAndPendingFrame() throws Exception {
        var channel = new OpenRouterBrowserLogin.FrameChannel();
        var field = channel.getClass().getDeclaredField("line"); field.setAccessible(true);
        byte[] buffer = (byte[]) field.get(channel);
        channel.append('s'); channel.append('k');
        assertThat(buffer[0]).isEqualTo((byte) 's');
        channel.close(); assertThat(buffer).containsOnly((byte) 0);
        assertThatThrownBy(() -> channel.append('x')).isInstanceOf(InterruptedException.class);

        var pendingChannel = new OpenRouterBrowserLogin.FrameChannel();
        var pending = secretFrame(pendingChannel);
        pendingChannel.close(); assertThat(pending.line()).containsOnly((byte) 0);
        assertThat(pendingChannel.publish(pending)).isFalse();
    }

    @Test void cancellationOfFullQueueWipesQueuedAndUnpublishedFrames() throws Exception {
        var channel = new OpenRouterBrowserLogin.FrameChannel();
        var bytes = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < 8; i++) {
            var frame = secretFrame(channel); bytes.add(frame.line());
            assertThat(channel.publish(frame)).isTrue();
        }
        var pending = secretFrame(channel); bytes.add(pending.line());
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CompletableFuture<Boolean>();
        Thread producer = Thread.ofVirtual().start(() -> {
            started.countDown();
            try { finished.complete(channel.publish(pending)); }
            catch (Exception failure) { finished.completeExceptionally(failure); }
        });
        try {
            assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            channel.close();
            assertThat(finished.get(1, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            for (byte[] value : bytes) assertThat(value).containsOnly((byte) 0);
        } finally { channel.close(); producer.interrupt(); producer.join(1000); }
    }

    @Test void malformedStreamWipesQueuedAndPartialData() throws Exception {
        var channel = new OpenRouterBrowserLogin.FrameChannel();
        var queued = secretFrame(channel); channel.publish(queued);
        channel.append('s');
        var field = channel.getClass().getDeclaredField("line"); field.setAccessible(true);
        byte[] buffer = (byte[]) field.get(channel);
        channel.fail();
        assertThat(queued.line()).containsOnly((byte) 0);
        assertThat(buffer).containsOnly((byte) 0);
        assertThatThrownBy(() -> channel.append('k')).isInstanceOf(InterruptedException.class);
        channel.close();
    }

    private static OpenRouterBrowserLogin.Frame secretFrame(OpenRouterBrowserLogin.FrameChannel channel) throws Exception {
        for (byte value : "synthetic-frame-key".getBytes(StandardCharsets.UTF_8)) channel.append(value);
        return channel.append('\n');
    }

    private static OpenRouterBrowserLogin adapter(FakeProcess process) {
        return new OpenRouterBrowserLogin(NODE, ENTRY, Duration.ofSeconds(2), System::nanoTime, builder -> process);
    }

    /** 内存私有 pipe，不启动 Node、不访问网络；block 用于证明 deadline 不依赖 EOF。 */
    private static final class FakeProcess extends Process {
        private final byte[] output;
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        private volatile boolean closed;
        private boolean block;
        private FakeProcess(String output) { this.output = output.getBytes(StandardCharsets.UTF_8); }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public InputStream getInputStream() {
            if (!block) return new ByteArrayInputStream(output);
            return new InputStream() {
                @Override public int read() {
                    while (!closed) {
                        try { Thread.sleep(5); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return -1; }
                    }
                    return -1;
                }
            };
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream("SECRET_STDERR".getBytes(StandardCharsets.UTF_8)); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { closed = true; }
        @Override public Process destroyForcibly() { closed = true; return this; }
        @Override public boolean isAlive() { return block && !closed; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
