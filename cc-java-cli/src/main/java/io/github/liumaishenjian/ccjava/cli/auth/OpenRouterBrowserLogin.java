package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 单次 OpenRouter 浏览器登录的私有进程适配器，不保存秘密、不自动打开浏览器。
 *
 * <p>调用方提供受信原生 Node 与入口路径，不能由模型参数覆盖。Windows 的 .cmd/.bat
 * 不是原生 Node，不允许通过 Shell 启动。子进程使用固定包目录和最小环境；这只是本地用户
 * 进程，不是 OS Sandbox。回调由 Pi 管理，终止时同时释放进程树与私有管道。</p>
 * <p>返回值所有权交给调用方，必须 close；保存必须由应用服务以登录前 generation CAS
 * 执行。URL consumer 只能接收严格白名单 URL，不能接收 key 或原始异常。</p>
 * @since 0.1.0
 */
public final class OpenRouterBrowserLogin {
    private static final int LINE_LIMIT = 32 * 1024;
    private static final int TOTAL_LIMIT = 128 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path node;
    private final Path entrypoint;
    private final Duration timeout;
    private final LongSupplier clock;
    private final ProcessLauncher launcher;

    /**
     * 建立最长五分钟的单次登录适配器。
     * @param nodeExecutable 受信任原生 Node 绝对路径
     * @param bridgeEntrypoint 受信任 login.mjs 绝对路径，其父目录为固定 cwd
     */
    public OpenRouterBrowserLogin(Path nodeExecutable, Path bridgeEntrypoint) {
        this(nodeExecutable, bridgeEntrypoint, Duration.ofMinutes(5), System::nanoTime, ProcessBuilder::start);
    }

    /**
     * 注入单调时钟和有界期限；路径仍必须来自可信启动配置。
     * @param nodeExecutable 原生 Node 绝对路径
     * @param bridgeEntrypoint 桥入口绝对路径
     * @param timeout 正数且不超过五分钟
     * @param clock 单调纳秒时钟
     */
    public OpenRouterBrowserLogin(Path nodeExecutable, Path bridgeEntrypoint, Duration timeout, LongSupplier clock) {
        this(nodeExecutable, bridgeEntrypoint, timeout, clock, ProcessBuilder::start);
    }

    OpenRouterBrowserLogin(Path nodeExecutable, Path bridgeEntrypoint, Duration timeout,
                           LongSupplier clock, ProcessLauncher launcher) {
        node = Objects.requireNonNull(nodeExecutable).normalize();
        entrypoint = Objects.requireNonNull(bridgeEntrypoint).normalize();
        String name = node.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!node.isAbsolute() || !entrypoint.isAbsolute() || entrypoint.getParent() == null
                || name.endsWith(".cmd") || name.endsWith(".bat")) throw failure();
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) throw failure();
        this.clock = Objects.requireNonNull(clock);
        this.launcher = Objects.requireNonNull(launcher);
    }

    /**
     * 等待回调并返回长期 API key；不支持手工粘贴回调、不提供刷新凭证。
     * @param cancellation 取消与 Runtime 剩余预算，只能缩短本次期限
     * @param authorizationUrl 快速非阻塞的已校验 URL 通知，不自动启动浏览器
     * @return 由调用方 close 的秘密，不写日志、事件或 argv
     * @throws IllegalStateException 超时、取消、协议或进程失败；不携带原始异常
     */
    public SecretMaterial login(CancellationToken cancellation, Consumer<String> authorizationUrl) {
        Objects.requireNonNull(cancellation);
        Objects.requireNonNull(authorizationUrl);
        Process process = null;
        SecretMaterial secret = null;
        Thread reader = null;
        Thread shutdownHook = null;
        FrameChannel frames = new FrameChannel();
        long start = clock.getAsLong();
        long budget = Math.min(timeout.toNanos(), cancellation.remainingTime().orElse(timeout).toNanos());
        try {
            check(cancellation, start, budget);
            ProcessBuilder builder = new ProcessBuilder(node.toString(), entrypoint.toString());
            builder.directory(entrypoint.getParent().toFile());
            var environment = builder.environment();
            var inherited = new HashMap<>(environment);
            environment.clear();
            for (var item : inherited.entrySet()) {
                if (List.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "PATH")
                        .contains(item.getKey().toUpperCase(Locale.ROOT))) environment.put(item.getKey(), item.getValue());
            }
            environment.put("PI_OAUTH_CALLBACK_HOST", "127.0.0.1");
            environment.put("DO_NOT_TRACK", "1");
            environment.put("OTEL_SDK_DISABLED", "true");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = launcher.start(builder);
            Process running = process;
            shutdownHook = new Thread(() -> stop(running), "codej-openrouter-login-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            reader = Thread.ofVirtual().start(() -> readFrames(running.getInputStream(), frames));
            boolean ready = false;
            boolean terminal = false;
            send(process, "start");
            while (true) {
                check(cancellation, start, budget);
                try (Frame frame = frames.queue.poll(20, TimeUnit.MILLISECONDS)) {
                if (frame == null) continue;
                if (frame.failed()) throw failure();
                if (frame.eof()) {
                    if (!terminal || secret == null || !process.waitFor(200, TimeUnit.MILLISECONDS)
                            || process.exitValue() != 0) throw failure();
                    check(cancellation, start, budget);
                    SecretMaterial result = secret;
                    secret = null;
                    return result;
                }
                if (terminal) throw failure();
                JsonNode value = JSON.readTree(frame.line());
                if (value == null || !value.isObject() || !value.path("type").isString()) throw failure();
                String type = value.path("type").asText();
                switch (type) {
                    case "ready" -> {
                        if (ready || value.size() != 1) throw failure();
                        ready = true;
                        send(process, "login");
                    }
                    case "auth_url" -> {
                        if (!ready || value.size() != 2 || !value.path("url").isString()) throw failure();
                        authorizationUrl.accept(validateAuthorizationUrl(value.path("url").asText()));
                    }
                    case "success" -> {
                        if (!ready || value.size() != 2 || !value.path("key").isString()) throw failure();
                        char[] chars = value.path("key").asText().toCharArray();
                        try {
                            if (chars.length < 1 || chars.length > 16384) throw failure();
                            for (char c : chars) if (c < 32 || c > 126) throw failure();
                            secret = new SecretMaterial(chars);
                        } finally { Arrays.fill(chars, '\0'); }
                        terminal = true;
                    }
                    default -> throw failure();
                }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (Exception ignored) {
            throw failure();
        } finally {
            // 先关发布入口并清零部分行/未入队/排队帧，防止reader在取消之后重新留下副本。
            frames.close();
            if (secret != null) secret.close();
            if (reader != null) reader.interrupt();
            if (process != null) stop(process);
            if (reader != null) {
                try { reader.join(200); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (shutdownHook != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) { /* JVM shutdown 已接管资源释放。 */ }
            }
        }
    }

    private void check(CancellationToken token, long start, long budget) {
        if (token.isCancellationRequested() || Thread.currentThread().isInterrupted()
                || clock.getAsLong() - start >= budget
                || token.remainingTime().map(t -> t.isZero() || t.isNegative()).orElse(false)) throw failure();
    }

    private static void send(Process process, String type) throws IOException {
        process.getOutputStream().write(("{\"type\":\"" + type + "\"}\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private static void readFrames(InputStream stream, FrameChannel frames) {
        try {
            int next;
            while ((next = stream.read()) != -1) {
                Frame frame = frames.append(next);
                if (frame != null && !frames.publish(frame)) return;
            }
            frames.requireCompleteLine();
            frames.publish(new Frame(null, true, false));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            frames.fail();
        } catch (Exception ignored) {
            frames.fail();
        } finally { frames.eraseLine(); }
    }

    /** 单reader所有权通道；关闭与发布同锁，取消后不能重新入队秘密。 */
    static final class FrameChannel implements AutoCloseable {
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        private final byte[] line = new byte[LINE_LIMIT];
        private int size;
        private int total;
        private boolean closed;
        private Frame pending;

        synchronized Frame append(int next) throws InterruptedException {
            if (closed) throw new InterruptedException();
            if (pending != null || ++total > TOTAL_LIMIT || size + 1 > LINE_LIMIT) throw failure();
            if (next != '\n') { line[size++] = (byte) next; return null; }
            pending = new Frame(Arrays.copyOf(line, size), false, false);
            eraseLine();
            return pending;
        }

        boolean publish(Frame frame) throws InterruptedException {
            boolean handedOff = false;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    synchronized (this) {
                        if (closed) return false;
                        if (queue.offer(frame)) {
                            if (pending == frame) pending = null;
                            handedOff = true;
                            return true;
                        }
                    }
                    Thread.sleep(5);
                }
                throw new InterruptedException();
            } finally {
                if (!handedOff) {
                    frame.close();
                    synchronized (this) { if (pending == frame) pending = null; }
                }
            }
        }

        synchronized void requireCompleteLine() {
            if (size != 0) throw failure();
        }

        synchronized void eraseLine() { Arrays.fill(line, (byte) 0); size = 0; }

        synchronized void fail() {
            if (closed) return;
            close();
            queue.offer(new Frame(null, false, true));
        }

        private void clearQueued() {
            Frame frame;
            while ((frame = queue.poll()) != null) frame.close();
        }

        @Override public synchronized void close() {
            closed = true;
            eraseLine();
            if (pending != null) { pending.close(); pending = null; }
            clearQueued();
        }
    }

    static String validateAuthorizationUrl(String value) {
        try {
            if (value.length() > 4096 || !value.matches("[\\x21-\\x7e]+")) throw failure();
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"openrouter.ai".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !"/auth".equals(uri.getRawPath())) throw failure();
            var query = new HashMap<String, String>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw failure();
            }
            if (query.size() != 3 || !"S256".equals(query.get("code_challenge_method"))
                    || !query.getOrDefault("code_challenge", "").matches("[A-Za-z0-9_-]{43}")) throw failure();
            URI callback = URI.create(query.get("callback_url"));
            if (!"http".equals(callback.getScheme()) || !"127.0.0.1".equals(callback.getHost())
                    || callback.getPort() < 1 || callback.getPort() > 65535 || callback.getRawUserInfo() != null
                    || callback.getRawQuery() != null || callback.getRawFragment() != null
                    || !callback.getRawPath().matches("/oauth/callback/[A-Za-z0-9-]{1,80}")) throw failure();
            return value;
        } catch (RuntimeException ignored) { throw failure(); }
    }

    private static void stop(Process process) {
        try { send(process, "cancel"); } catch (Exception ignored) { }
        try {
            var children = process.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            process.destroy();
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
        } catch (RuntimeException ignored) {
            process.destroyForcibly();
        }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    private static IllegalStateException failure() { return new IllegalStateException("OpenRouter 浏览器登录未完成"); }
    /** 帧被消费或丢弃后立即擦除其可变字节；不声称能擦除JSON解析器的不可变字符串。 */
    record Frame(byte[] line, boolean eof, boolean failed) implements AutoCloseable {
        @Override public void close() { if (line != null) Arrays.fill(line, (byte) 0); }
    }
    @FunctionalInterface
    interface ProcessLauncher { Process start(ProcessBuilder builder) throws IOException; }
}
