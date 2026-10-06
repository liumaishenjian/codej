package io.github.liumaishenjian.ccjava.cli.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiRuntimeConfigurationTest {
    @TempDir Path directory;

    @Test
    void explicitAbsoluteInstallationResolvesWithoutExecutingNode() throws Exception {
        Properties properties = installation();
        // 合成普通文件不是可执行 Node；resolve 不允许通过启动进程来探测。
        var configuration = PiRuntimeConfiguration.resolve(properties, Map.of());
        assertThat(configuration).isInstanceOf(PiWorkerConfiguration.class);
        assertThat(PiRuntimeConfiguration.available(properties, Map.of())).isTrue();
        assertThat(configuration.toString()).isEqualTo("PI_WORKER_CONFIGURATION");
    }

    @Test
    void onlyExplicitUppercaseProxiesPassAndRepresentationHidesSecrets() throws Exception {
        Properties properties = installation();
        Map<String, String> environment = new HashMap<>(Map.of(
                "HTTP_PROXY", "http://private-user:private-password@127.0.0.1:8080",
                "HTTPS_PROXY", "http://127.0.0.1:8081", "NO_PROXY", "localhost,127.0.0.1",
                "NODE_OPTIONS", "--require private-script", "OPENAI_API_KEY", "private-key",
                "PATH", "private-path"));
        var configuration = PiRuntimeConfiguration.resolve(properties, environment);
        // 不扩展生产 API、不启动进程；检查进程配置拥有的防御复制快照。
        var field = PiWorkerConfiguration.class.getDeclaredField("proxies");
        field.setAccessible(true);
        assertThat(field.get(configuration)).isEqualTo(Map.of(
                "HTTP_PROXY", environment.get("HTTP_PROXY"), "HTTPS_PROXY", environment.get("HTTPS_PROXY"),
                "NO_PROXY", environment.get("NO_PROXY")));
        environment.clear();
        assertThat((Map<?, ?>) field.get(configuration)).hasSize(3);
        assertThat(configuration.toString()).doesNotContain(directory.toString(), "private", "127.0.0.1");
    }

    @Test
    void windowsStyleLowercaseProxyNamesAreCanonicalized() throws Exception {
        Properties properties = installation();
        Map<String, String> environment = Map.of(
                "http_proxy", "http://127.0.0.1:8080",
                "https_proxy", "http://127.0.0.1:8081",
                "no_proxy", "localhost,127.0.0.1");
        var configuration = PiRuntimeConfiguration.resolve(properties, environment);
        var field = PiWorkerConfiguration.class.getDeclaredField("proxies");
        field.setAccessible(true);
        assertThat(field.get(configuration)).isEqualTo(Map.of(
                "HTTP_PROXY", environment.get("http_proxy"),
                "HTTPS_PROXY", environment.get("https_proxy"),
                "NO_PROXY", environment.get("no_proxy")));
    }

    @Test
    void proxyNamesThatOnlyDifferByCaseAreRejected() {
        Map<String, String> environment = Map.of(
                "HTTP_PROXY", "http://one",
                "http_proxy", "http://two");
        assertThatThrownBy(() -> PiRuntimeConfiguration.normalizeProxyEnvironment(environment))
                .isInstanceOf(PiWorkerException.class)
                .hasMessage("CONFIGURATION_INVALID")
                .hasNoCause();
    }

    @Test
    void legacyBridgeSiblingWorksOnlyWhenWorkerPropertyIsAbsent() throws Exception {
        Properties properties = installation();
        properties.remove("codej.piWorker");
        properties.setProperty("codej.piBridge", Files.writeString(directory.resolve("login.mjs"), "fixture").toString());
        assertThat(PiRuntimeConfiguration.available(properties, Map.of())).isTrue();
        for (String bad : List.of("", " ", "worker.mjs", directory.resolve("missing/worker.mjs").toString(),
                directory.resolve("login.mjs").toString())) {
            properties.setProperty("codej.piWorker", bad);
            rejects(properties, Map.of());
        }
        properties.put("codej.piWorker", 123);
        rejects(properties, Map.of());
    }

    @Test
    void explicitWorkerDoesNotDependOnBrokenLegacyBridge() throws Exception {
        Properties properties = installation();
        properties.setProperty("codej.piBridge", "bad-legacy-value");
        assertThat(PiRuntimeConfiguration.available(properties, Map.of())).isTrue();
    }

    @Test
    void missingRelativeWrongNameAndNonRegularPathsFailClosed() throws Exception {
        Properties valid = installation();
        rejects(new Properties(), Map.of());
        rejects(null, Map.of());
        rejects(valid, null);
        for (String key : List.of("codej.nodeExecutable", "codej.piWorker")) {
            for (String value : List.of("", "relative-file", directory.toString(), directory.resolve("missing").toString(), "bad\0path")) {
                Properties properties = copy(valid);
                properties.setProperty(key, value);
                rejects(properties, Map.of());
            }
            Properties absent = copy(valid);
            absent.remove(key);
            rejects(absent, Map.of());
        }
        Path wrong = Files.writeString(directory.resolve("not-worker.mjs"), "fixture");
        valid.setProperty("codej.piWorker", wrong.toString());
        rejects(valid, Map.of());
    }

    @Test
    void legacyBridgeMustItselfBeExplicitAbsoluteRegularLoginFile() throws Exception {
        Properties properties = installation();
        properties.remove("codej.piWorker");
        for (String value : List.of("login.mjs", directory.resolve("login.mjs").toString(),
                directory.resolve("worker.mjs").toString())) {
            properties.setProperty("codej.piBridge", value);
            rejects(properties, Map.of());
        }
    }

    @Test
    void missingOrNonRegularDependencyManifestFailsWithoutInstallingAnything() throws Exception {
        Properties properties = installation();
        Path manifest = manifest();
        Files.delete(manifest);
        rejects(properties, Map.of());
        assertThat(Files.exists(manifest)).isFalse();
        Files.createDirectory(manifest);
        rejects(properties, Map.of());
    }

    @Test
    void invalidProxyValuesFailWithClosedErrorAndNoOriginalCause() throws Exception {
        Properties properties = installation();
        for (String value : List.of("http://private:password@host\nheader", "private\rvalue", "private\0value", "x".repeat(8193))) {
            rejects(properties, Map.of("HTTPS_PROXY", value));
        }
        Map<String, String> nullProxy = new HashMap<>();
        nullProxy.put("HTTP_PROXY", null);
        rejects(properties, nullProxy);
        assertThat(PiRuntimeConfiguration.available(properties, Map.of("NODE_OPTIONS", "bad\0ignored"))).isTrue();
    }

    @Test
    void linkedNodeWorkerBridgeAndManifestAreRejectedWhenSymlinkCreationIsAvailable() throws Exception {
        Properties properties = installation();
        Path target = Files.writeString(directory.resolve("target-fixture"), "fixture");
        Path probe = directory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, target);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Host does not permit creating test symlinks");
            return;
        }
        Files.delete(probe);
        for (Path file : List.of(Path.of(properties.getProperty("codej.nodeExecutable")),
                Path.of(properties.getProperty("codej.piWorker")), manifest())) {
            Files.delete(file);
            Files.createSymbolicLink(file, target);
            rejects(properties, Map.of());
            Files.delete(file);
            Files.writeString(file, "fixture");
        }
        Path bridge = directory.resolve("login.mjs");
        Files.createSymbolicLink(bridge, target);
        properties.remove("codej.piWorker");
        properties.setProperty("codej.piBridge", bridge.toString());
        rejects(properties, Map.of());
    }

    @Test
    void propertyDefaultsRemainExplicitAndInvalidPresentValueCannotMaskThem() throws Exception {
        Properties defaults = installation();
        Properties properties = new Properties(defaults);
        assertThat(PiRuntimeConfiguration.available(properties, Map.of())).isTrue();
        properties.put("codej.piWorker", new Object());
        // 非 String 的本地属性不能借由 Properties.getProperty 的 defaults 回退掩盖错误。
        rejects(properties, Map.of());
    }

    private Properties installation() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("codej.nodeExecutable", Files.writeString(directory.resolve("node.exe"), "not executable").toString());
        properties.setProperty("codej.piWorker", Files.writeString(directory.resolve("worker.mjs"), "fixture").toString());
        Files.createDirectories(manifest().getParent());
        Files.writeString(manifest(), "{}");
        return properties;
    }

    private Path manifest() { return directory.resolve("node_modules/@earendil-works/pi-ai/package.json"); }

    private static Properties copy(Properties original) {
        Properties result = new Properties();
        result.putAll(original);
        return result;
    }

    private static void rejects(Properties properties, Map<String, String> environment) {
        assertThat(PiRuntimeConfiguration.available(properties, environment)).isFalse();
        assertThatThrownBy(() -> PiRuntimeConfiguration.resolve(properties, environment))
                .isInstanceOf(PiWorkerException.class).hasMessage("CONFIGURATION_INVALID").hasNoCause();
    }
}
