package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.auth.PiRuntimeConfiguration;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-100 的独立 CLI 输入进程边缘；Java 不读取 Console、Key 或 OAuth 回调。
 *
 * <p>受信启动属性不是模型/stdio 参数。Node 负责输入恢复以及 stored/EOF/exit Gate；
 * 本类只确认子进程退出，不激活主进程身份、不选择默认模型。路径检查不是安装签名或 OS 隔离。</p>
 */
final class PiCliLogin {
    private static final String MAIN = "io.github.liumaishenjian.ccjava.cli.CcJavaCliMain";
    private static final Set<String> ENVIRONMENT = Set.of("SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");
    private final Runner runner;

    PiCliLogin() { this(PiCliLogin::spawn); }
    PiCliLogin(Runner runner) { this.runner = java.util.Objects.requireNonNull(runner); }

    void login(PiCredentialIdentity identity, boolean stdin, CancellationToken cancellation) {
        LaunchSpec spec;
        try {
            PiRuntimeConfiguration.resolve(System.getenv());
            spec = launchSpec(System.getProperties(), System.getenv(), Path.of("").toAbsolutePath(), identity, stdin);
        } catch (RuntimeException failure) { throw new Failure(Code.PI_COMPONENT_UNAVAILABLE); }
        run(spec, cancellation);
    }

    // 包级纯配置 seam，不接受任意主类、JVM flags 或环境材料。
    static LaunchSpec launchSpec(Properties properties, Map<String,String> environment, Path cwd,
                                 PiCredentialIdentity identity, boolean stdin) {
        try {
            if (stdin && identity.authMethod() != PiCredentialIdentity.AuthMethod.API_KEY) throw new IOException();
            Path node = regular(Path.of(required(properties, "codej.nodeExecutable")));
            Path worker = regular(Path.of(required(properties, "codej.piWorker")));
            Path helper = regular(Path.of(required(properties, "codej.piAuthCli")));
            if (!"pi-auth-cli.js".equals(helper.getFileName().toString())) throw new IOException();
            Map<String,String> env = new HashMap<>();
            for (String name : ENVIRONMENT) if (environment.containsKey(name)) env.put(name, environment.get(name));
            Map<String,String> proxies = new HashMap<>();
            for (String name : Set.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY"))
                if (env.containsKey(name)) proxies.put(name, env.get(name));
            new PiWorkerConfiguration(node, worker, proxies);
            regular(worker.getParent().resolve("node_modules/@earendil-works/pi-ai/package.json"));
            Path java = regular(Path.of(required(properties, "java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java"));
            Path home = Path.of(required(properties, "user.home"));
            if (!home.isAbsolute() || !cwd.isAbsolute() || !Files.isDirectory(cwd)) throw new IOException();
            List<String> javaArgs = List.of("-Duser.home="+home, "-Dcodej.nodeExecutable="+node,
                    "-Dcodej.piWorker="+worker, "-classpath", required(properties, "java.class.path"), MAIN, "--stdio");
            List<String> argv = new ArrayList<>(List.of(node.toString(), helper.toString(), "--java", java.toString(),
                    "--java-args-base64", Base64.getUrlEncoder().withoutPadding().encodeToString(
                            JsonMapper.builder().build().writeValueAsBytes(javaArgs)),
                    "--cwd", cwd.toString(), "--provider", identity.providerId(), "--profile", identity.profileId(),
                    "--auth-method", identity.authMethod().name()));
            if (stdin) argv.add("--api-key-stdin");
            return new LaunchSpec(argv, cwd, env);
        } catch (Exception failure) { throw new Failure(Code.PI_COMPONENT_UNAVAILABLE); }
    }

    // 运行预算预留两秒确认关闭；不会无限等待子进程或 Console 输入。
    void run(LaunchSpec spec, CancellationToken cancellation) {
        ChildProcess child = null;
        Thread shutdown = null;
        boolean interrupted = false;
        try {
            if (cancellation.isCancellationRequested()) throw new Failure(Code.PI_AUTH_CANCELLED);
            long nanos = Math.min(Duration.ofSeconds(298).toNanos(), cancellation.remainingTime()
                    .orElse(Duration.ofSeconds(298)).toNanos());
            if (nanos <= 0) throw new Failure(Code.PI_AUTH_CANCELLED);
            long start = System.nanoTime();
            child = runner.start(spec);
            ChildProcess owned = child;
            shutdown = new Thread(owned::stop, "pi-cli-auth-close");
            Runtime.getRuntime().addShutdownHook(shutdown);
            while (true) {
                if (cancellation.isCancellationRequested() || System.nanoTime()-start >= nanos)
                    throw new Failure(Code.PI_AUTH_CANCELLED);
                if (child.await(100)) {
                    if (cancellation.isCancellationRequested()) throw new Failure(Code.PI_AUTH_CANCELLED);
                    if (child.exitCode() != 0) throw new Failure(Code.PI_AUTH_FAILED);
                    break;
                }
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new Failure(Code.PI_AUTH_CANCELLED);
        } catch (IOException failure) { throw new Failure(Code.PI_COMPONENT_UNAVAILABLE); }
        finally {
            boolean closed = child == null || child.stop();
            if (shutdown != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* JVM 已进入关闭，原 hook 继续拥有子进程。 */ }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (!closed) throw new Failure(Code.PI_AUTH_CLOSE_UNCONFIRMED);
        }
    }

    private static ChildProcess spawn(LaunchSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(spec.argv()).directory(spec.cwd().toFile()).inheritIO();
        builder.environment().clear(); builder.environment().putAll(spec.environment());
        Process process = builder.start();
        return new ChildProcess() {
            @Override public boolean await(long millis) throws InterruptedException { return process.waitFor(millis, TimeUnit.MILLISECONDS); }
            @Override public int exitCode() { return process.exitValue(); }
            @Override public synchronized boolean stop() {
                var descendants = process.descendants().toList();
                descendants.forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                boolean interrupted = Thread.interrupted();
                try {
                    while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                            && System.nanoTime() < deadline) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) { interrupted = true; }
                    }
                    return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        };
    }

    private static String required(Properties properties, String name) throws IOException {
        if (!(properties.get(name) instanceof String value) || value.isBlank() || value.indexOf('\0') >= 0)
            throw new IOException();
        return value;
    }

    // 包括祖先在内拒绝链接/reparse；不把规范化路径掩盖的跳转当成可信安装文件。
    private static Path regular(Path path) throws IOException {
        if (!path.isAbsolute()) throw new IOException();
        for (Path part = path; part != null; part = part.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        return path.toRealPath();
    }

    /**
     * 受信封闭启动参数；环境可能含代理凭据，因此字符串表示固定且不用于日志。
     * @param argv 固定 helper 与固定 Java 主类参数
     * @param cwd 当前命令工作目录
     * @param environment 操作系统及显式代理白名单
     */
    record LaunchSpec(List<String> argv, Path cwd, Map<String,String> environment) {
        LaunchSpec { argv = List.copyOf(argv); environment = Map.copyOf(environment); }
        @Override public String toString() { return "PI_CLI_AUTH_LAUNCH"; }
    }
    /** 包级测试端口，不向普通 stdio 或模型开放进程参数。 */
    @FunctionalInterface interface Runner {
        /**
         * 以 shell-free argv 和继承终端启动唯一 helper。
         * @param spec 已验证的启动说明
         * @return 由调用方关闭的子进程
         * @throws IOException 组件无法启动
         */
        ChildProcess start(LaunchSpec spec) throws IOException;
    }
    /** 等待与关闭的窄端口；不得通过该接口取得或读取输入流。 */
    interface ChildProcess {
        /**
         * 至多等待指定毫秒数。
         * @param millis 本次轮询上限
         * @return 子进程实际退出时为 true
         * @throws InterruptedException 宿主等待被中断
         */
        boolean await(long millis) throws InterruptedException;
        /**
         * 仅在已确认退出后取得退出码。
         * @return 子进程原始退出码
         */
        int exitCode();
        /**
         * 在两秒内停止自身及当前可见后代；不证明任意脱离进程树的进程已结束。
         * @return 已确认这些资源不再运行时为 true
         */
        boolean stop();
    }
    enum Code { PI_COMPONENT_UNAVAILABLE, PI_AUTH_FAILED, PI_AUTH_CANCELLED, PI_AUTH_CLOSE_UNCONFIRMED }
    static final class Failure extends RuntimeException {
        private final Code code;
        Failure(Code code) { super(code.name()); this.code = code; }
        Code code() { return code; }
    }
}
