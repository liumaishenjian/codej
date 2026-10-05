package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLI 子进程窄接缝回归；只造普通文件，不执行 Node、Java 子进程或 OAuth。 */
class PiCliLoginTest {
    @TempDir Path temporary;
    private static final PiCredentialIdentity ID = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "work");

    @Test void fixedArgvRetainsOnlyTrustedJavaArgumentsAndNeverEnvironmentMaterial() throws Exception {
        Properties properties = configuration();
        properties.setProperty("sun.java.command", "untrusted.Main --secret=sentinel");
        properties.setProperty("codej.arbitrary", "sentinel");
        var spec = PiCliLogin.launchSpec(properties, Map.of("SYNTHETIC_API_KEY", "secret-sentinel", "NODE_OPTIONS", "--require=evil"),
                temporary, ID, true);
        assertThat(spec.argv()).hasSize(15);
        assertThat(spec.argv().get(0)).isEqualTo(Path.of(properties.getProperty("codej.nodeExecutable")).toRealPath().toString());
        assertThat(spec.argv().get(1)).endsWith("pi-auth-cli.js");
        assertThat(spec.argv().subList(2, 5)).containsExactly("--java", javaExecutable(properties).toRealPath().toString(), "--java-args-base64");
        var javaArgs = JsonMapper.builder().build().readTree(java.util.Base64.getUrlDecoder().decode(spec.argv().get(5)));
        assertThat(javaArgs.size()).isEqualTo(7);
        assertThat(javaArgs.get(0).asText()).isEqualTo("-Duser.home="+temporary);
        assertThat(javaArgs.get(3).asText()).isEqualTo("-classpath");
        assertThat(javaArgs.get(4).asText()).isEqualTo(properties.getProperty("java.class.path"));
        assertThat(javaArgs.get(5).asText()).isEqualTo("io.github.liumaishenjian.ccjava.cli.CcJavaCliMain");
        assertThat(javaArgs.get(6).asText()).isEqualTo("--stdio");
        assertThat(spec.argv().subList(6, 15)).containsExactly("--cwd", temporary.toString(), "--provider", "openai",
                "--profile", "work", "--auth-method", "API_KEY", "--api-key-stdin");
        assertThat(spec.environment()).isEmpty();
        assertThat(spec.argv().toString()+spec).doesNotContain("sentinel", "untrusted.Main", "NODE_OPTIONS", "codej.arbitrary");
    }

    @Test void oauthHasNoStdinAndNeverInventsBrowserOrDeviceArguments() throws Exception {
        var properties = configuration();
        var id = new PiCredentialIdentity("openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, "work");
        var spec = PiCliLogin.launchSpec(properties, Map.of(), temporary, id, false);
        assertThat(spec.argv()).contains("OAUTH", "openai-codex").doesNotContain("--browser", "--device", "--api-key-stdin");
        assertThatThrownBy(() -> PiCliLogin.launchSpec(properties, Map.of(), temporary, id, true))
                .hasMessage("PI_COMPONENT_UNAVAILABLE");
    }

    @Test void helperIsExplicitAndMustHaveFixedNameAndOrdinaryAbsolutePath() throws Exception {
        var properties = configuration();
        for (String key : List.of("codej.nodeExecutable", "codej.piWorker", "codej.piAuthCli")) {
            var copy = new Properties(); copy.putAll(properties); copy.remove(key);
            assertThatThrownBy(() -> PiCliLogin.launchSpec(copy, Map.of(), temporary, ID, false))
                    .hasMessage("PI_COMPONENT_UNAVAILABLE");
            copy.setProperty(key, "relative-file");
            assertThatThrownBy(() -> PiCliLogin.launchSpec(copy, Map.of(), temporary, ID, false))
                    .hasMessage("PI_COMPONENT_UNAVAILABLE");
            copy.setProperty(key, temporary.toString());
            assertThatThrownBy(() -> PiCliLogin.launchSpec(copy, Map.of(), temporary, ID, false))
                    .hasMessage("PI_COMPONENT_UNAVAILABLE");
        }
        Path wrong = Files.writeString(temporary.resolve("other.js"), "synthetic");
        properties.setProperty("codej.piAuthCli", wrong.toString());
        assertThatThrownBy(() -> PiCliLogin.launchSpec(properties, Map.of(), temporary, ID, false))
                .hasMessage("PI_COMPONENT_UNAVAILABLE");
    }

    @Test void missingDependencyIsClosedBeforeSpawn() throws Exception {
        var properties = configuration();
        Files.delete(temporary.resolve("worker/node_modules/@earendil-works/pi-ai/package.json"));
        assertThatThrownBy(() -> PiCliLogin.launchSpec(properties, Map.of(), temporary, ID, false))
                .hasMessage("PI_COMPONENT_UNAVAILABLE");
    }

    @Test void exitZeroNeedsConfirmedCloseAndNonzeroCannotSucceed() {
        var zero = new FakeChild(0, true);
        new PiCliLogin(spec -> zero).run(fakeSpec(), CancellationToken.none());
        assertThat(zero.closed).isEqualTo(1);
        var nonzero = new FakeChild(7, true);
        assertThatThrownBy(() -> new PiCliLogin(spec -> nonzero).run(fakeSpec(), CancellationToken.none()))
                .hasMessage("PI_AUTH_FAILED");
        assertThat(nonzero.closed).isEqualTo(1);
        var uncertain = new FakeChild(0, false);
        assertThatThrownBy(() -> new PiCliLogin(spec -> uncertain).run(fakeSpec(), CancellationToken.none()))
                .hasMessage("PI_AUTH_CLOSE_UNCONFIRMED");
    }

    @Test void cancellationDeadlineAndInterruptedWaitRemainBoundedAndCloseOwnedChild() {
        var calls = new AtomicInteger();
        var cancelled = token(true, Duration.ofSeconds(1));
        var login = new PiCliLogin(spec -> { calls.incrementAndGet(); return new FakeChild(0, true); });
        assertThatThrownBy(() -> login.run(fakeSpec(), cancelled)).hasMessage("PI_AUTH_CANCELLED");
        assertThatThrownBy(() -> login.run(fakeSpec(), token(false, Duration.ZERO))).hasMessage("PI_AUTH_CANCELLED");
        assertThat(calls).hasValue(0);
        var child = new FakeChild(0, true) {
            @Override public boolean await(long millis) throws InterruptedException { throw new InterruptedException(); }
        };
        try {
            assertThatThrownBy(() -> new PiCliLogin(spec -> child).run(fakeSpec(), CancellationToken.none()))
                    .hasMessage("PI_AUTH_CANCELLED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); assertThat(child.closed).isEqualTo(1);
        } finally { Thread.interrupted(); }
    }

    private Properties configuration() throws Exception {
        var properties = new Properties();
        Path node = Files.writeString(temporary.resolve("node executable"), "synthetic");
        Path worker = Files.createDirectories(temporary.resolve("worker")).resolve("worker.mjs");
        Files.writeString(worker, "synthetic");
        Files.writeString(Files.createDirectories(worker.getParent().resolve("node_modules/@earendil-works/pi-ai")).resolve("package.json"), "{}");
        Path helper = Files.writeString(temporary.resolve("pi-auth-cli.js"), "synthetic");
        Path javaHome = Files.createDirectories(temporary.resolve("java home/bin")).getParent();
        properties.setProperty("java.home", javaHome.toString()); Files.writeString(javaExecutable(properties), "synthetic");
        properties.setProperty("user.home", temporary.toString());
        properties.setProperty("java.class.path", "classes with spaces"+java.io.File.pathSeparator+"deps.jar");
        properties.setProperty("codej.nodeExecutable", node.toString());
        properties.setProperty("codej.piWorker", worker.toString());
        properties.setProperty("codej.piAuthCli", helper.toString()); return properties;
    }
    private static Path javaExecutable(Properties properties) {
        return Path.of(properties.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }
    private PiCliLogin.LaunchSpec fakeSpec() { return new PiCliLogin.LaunchSpec(List.of("never-executed"), temporary, Map.of()); }
    private static CancellationToken token(boolean cancelled, Duration remaining) {
        return new CancellationToken() {
            @Override public boolean isCancellationRequested() { return cancelled; }
            @Override public Registration onCancellation(Runnable action) { return () -> { }; }
            @Override public Optional<Duration> remainingTime() { return Optional.of(remaining); }
        };
    }
    private static class FakeChild implements PiCliLogin.ChildProcess {
        final int exit; final boolean confirmed; int closed;
        FakeChild(int exit, boolean confirmed) { this.exit = exit; this.confirmed = confirmed; }
        @Override public boolean await(long millis) throws InterruptedException { return true; }
        @Override public int exitCode() { return exit; }
        @Override public boolean stop() { closed++; return confirmed; }
    }
}
