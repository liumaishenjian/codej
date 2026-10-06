package io.github.liumaishenjian.ccjava.model.pi.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Node 原生 fetch 代理开关的固定环境回归；不启动 Node 或访问网络。 */
class PiWorkerConfigurationTest {
    @TempDir Path directory;

    @Test
    void enablesNodeEnvironmentProxyOnlyWithExplicitHttpProxy() throws Exception {
        Path node = Files.writeString(directory.resolve("node"), "fixture");
        Path worker = Files.writeString(directory.resolve("worker.mjs"), "fixture");

        var withProxy = new PiWorkerConfiguration(node, worker,
                Map.of("HTTPS_PROXY", "http://127.0.0.1:7890", "NO_PROXY", "localhost"));
        assertThat(withProxy.builder().environment()).containsEntry("NODE_USE_ENV_PROXY", "1");

        var withoutHttpProxy = new PiWorkerConfiguration(node, worker, Map.of("NO_PROXY", "localhost"));
        assertThat(withoutHttpProxy.builder().environment()).doesNotContainKey("NODE_USE_ENV_PROXY");
    }
}
