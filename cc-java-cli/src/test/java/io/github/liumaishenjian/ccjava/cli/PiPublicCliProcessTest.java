package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-100 公开Java CLI→Node输入壳→Java私有桥→生产Worker/公开SDK的离线进程回归。
 *
 * <p>曾用真实Windows入口复现直接JSON argv丢引号并拆成24个参数；Fake Runner无法发现。
 * 本测试使用有空格的临时路径和真实classpath，必须通过Base64url参数与真实EOF/退出交付。
 * 只录入合成API Key，不打开浏览器、不probe，Worker的fetch入口另外拒绝网络。
 * TTY按键、真实账号及在线OAuth不属于本证据。</p>
 */
class PiPublicCliProcessTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "synthetic-offline-public-cli-key";
    @TempDir Path temporary;

    @ParameterizedTest(name = "{0}: public private-bridge process chain")
    @ValueSource(strings = {"openai", "deepseek", "qwen-token-plan-cn"})
    void stdinLoginStatusLogoutUseActualEntryAndNeverExposeMaterial(String provider) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("cc-java-provider-pi"))) root = Path.of("").toAbsolutePath();
        Path node = Path.of(System.getProperty("codej.test.nodeExecutable", "D:/node/node.exe"));
        Path cli = root.resolve("cc-java-tui/dist/src/pi-auth-cli.js");
        assumeTrue(node.isAbsolute() && Files.isRegularFile(node) && Files.isRegularFile(cli),
                "Requires explicit Node and current compiled TUI; compile npm build before entry acceptance");
        Path home = Files.createDirectories(temporary.resolve("isolated home"));
        Path workspace = Files.createDirectories(temporary.resolve("empty workspace"));
        Path component = Files.createDirectories(temporary.resolve("trusted component"));
        Path metadata = component.resolve("node_modules/@earendil-works/pi-ai/package.json");
        Files.createDirectories(metadata.getParent());
        Files.copy(root.resolve("cc-java-provider-pi/node_modules/@earendil-works/pi-ai/package.json"), metadata);
        Path network = temporary.resolve("network-attempt.txt"), pid = temporary.resolve("owned-worker-pid.txt");
        Path worker = component.resolve("worker.mjs");
        Files.writeString(worker, """
                import {appendFileSync, writeFileSync} from 'node:fs';
                writeFileSync(%s, String(process.pid));
                globalThis.fetch = () => {
                  appendFileSync(%s, 'blocked\\n');
                  throw new Error('NETWORK_FORBIDDEN');
                };
                await import(%s);
                """.formatted(JSON.writeValueAsString(pid.toString()), JSON.writeValueAsString(network.toString()),
                JSON.writeValueAsString(root.resolve("cc-java-provider-pi/worker.mjs").toUri().toString())), StandardCharsets.UTF_8);
        List<String> prefix = List.of(javaExecutable(), "-Dfile.encoding=UTF-8", "-Duser.home=" + home,
                "-Dcodej.nodeExecutable=" + node, "-Dcodej.piWorker=" + worker, "-Dcodej.piAuthCli=" + cli,
                "-cp", System.getProperty("java.class.path"), "io.github.liumaishenjian.ccjava.cli.CcJavaCliMain");
        List<String> identity = List.of("--backend", "pi", "--provider", provider, "--profile", "default", "--auth-method", "API_KEY");
        byte[] input = (KEY + "\n").getBytes(StandardCharsets.US_ASCII);
        try {
            String login = invoke(prefix, workspace, command("login", identity, "--api-key-stdin"), input);
            assertThat(login).contains("configured, unverified", "pi/" + provider + "/default");
        } finally { Arrays.fill(input, (byte) 0); }
        assertThat(pid).isRegularFile();
        long workerPid = Long.parseLong(Files.readString(pid));
        assertThat(ProcessHandle.of(workerPid).map(ProcessHandle::isAlive).orElse(false))
                .as("Owned worker must be gone before successful public login exit").isFalse();
        var status = JSON.readTree(invoke(prefix, workspace, command("status", identity, "--json"), new byte[0]));
        assertThat(status.path("profile").path("providerId").asString()).isEqualTo(provider);
        assertThat(status.toString()).contains("CONFIGURED_UNVERIFIED").doesNotContain(KEY, "authEpoch", "secretRef", "accountId");
        invoke(prefix, workspace, command("logout", identity, "--yes"), new byte[0]);
        var profiles = JSON.readTree(invoke(prefix, workspace,
                List.of("auth", "list", "--backend", "pi", "--json"), new byte[0]));
        assertThat(profiles.toString()).doesNotContain(provider, KEY, "authEpoch");
        assertThat(network).doesNotExist();
    }

    private static List<String> command(String operation, List<String> identity, String tail) {
        List<String> result = new ArrayList<>(List.of("auth", operation)); result.addAll(identity); result.add(tail); return result;
    }

    private static String invoke(List<String> prefix, Path workspace, List<String> args, byte[] input) throws Exception {
        List<String> command = new ArrayList<>(prefix); command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile());
        builder.environment().clear();
        for (String name : List.of("SystemRoot", "WINDIR", "TEMP", "TMP")) {
            String value = System.getenv(name); if (value != null) builder.environment().put(name, value);
        }
        builder.environment().put("CC_JAVA_REPOSITORY_ROOT", workspace.toString());
        Process process = builder.start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> bounded(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> bounded(process.getErrorStream()));
        try {
            try (var stream = process.getOutputStream()) { stream.write(input); }
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("Bounded public CLI process").isTrue();
            byte[] out = stdout.get(2, TimeUnit.SECONDS), err = stderr.get(2, TimeUnit.SECONDS);
            try {
                String text = new String(out, StandardCharsets.UTF_8), diagnostic = new String(err, StandardCharsets.UTF_8);
                assertThat(text.contains(KEY) || diagnostic.contains(KEY)).as("Synthetic input must not be echoed").isFalse();
                assertThat(process.exitValue()).as("Public CLI exit; stderr bytes=%s", err.length).isZero();
                assertThat(diagnostic).isEmpty();
                return text;
            } finally {Arrays.fill(out, (byte) 0); Arrays.fill(err, (byte) 0);}
        } finally {
            // 只清理本测试仍可见的自有后代；不触碰用户进程，也不声称任意脱离后代被覆盖。
            var descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
        }
    }

    private static byte[] bounded(java.io.InputStream stream) {
        try {
            byte[] bytes = stream.readNBytes(65_537);
            if (bytes.length > 65_536) {Arrays.fill(bytes, (byte) 0); throw new IllegalStateException("OUTPUT_LIMIT");}
            return bytes;
        } catch (java.io.IOException failure) {throw new IllegalStateException("OUTPUT_IO");}
    }
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java").toString();
    }
}
