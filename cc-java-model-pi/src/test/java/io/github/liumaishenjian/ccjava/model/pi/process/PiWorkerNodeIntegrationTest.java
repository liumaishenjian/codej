package io.github.liumaishenjian.ccjava.model.pi.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 显式 opt-in 的真实 Java→生产 Node Worker→Pi 目录链；目录不需要网络或用户凭证。
 * 未设置绝对 codej.test.nodeExecutable 时明确跳过，不作为真实验证成功。
 */
class PiWorkerNodeIntegrationTest {
    @Test
    void productionCatalogHasThreeBrandsFourNonemptyRoutesAndCleanExit() {
        String executable = System.getProperty("codej.test.nodeExecutable");
        assumeTrue(executable != null && !executable.isBlank(), "opt-in requires absolute codej.test.nodeExecutable");
        Path node = Path.of(executable);
        assertThat(node.isAbsolute()).as("explicit Node executable must be absolute").isTrue();
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("cc-java-provider-pi"))) root = root.getParent();
        Path worker = root.resolve("cc-java-provider-pi/worker.mjs");
        var configuration = new PiWorkerConfiguration(node, worker, Map.of());
        try (var connection = PiWorkerConnection.start(configuration, "catalog_integration",
                CancellationToken.none(), Duration.ofSeconds(20))) {
            connection.send("operation.start", JsonMapper.builder().build().createObjectNode().put("operation", "catalog"));
            var ready = connection.receive();
            assertThat(ready.type()).isEqualTo("operation.ready");
            assertThat(ready.payload().path("piVersion").asString()).isEqualTo("0.85.1");
            var result = connection.receive();
            assertThat(result.type()).isEqualTo("catalog.result");
            var payload = result.payload();
            assertThat(payload.path("brands").size()).isEqualTo(3);
            assertThat(payload.path("providers").size()).isEqualTo(4);
            Set<String> routes = new HashSet<>();
            for (var provider : payload.path("providers")) {
                routes.add(provider.path("id").asString());
                assertThat(provider.path("models").size()).isPositive();
            }
            assertThat(routes).containsExactlyInAnyOrder("openai", "openai-codex", "deepseek", "qwen-token-plan-cn");
            assertThat(connection.receive().type()).isEqualTo("operation.completed");
            assertThat(connection.awaitExit()).isZero();
        }
    }
}
