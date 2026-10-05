package io.github.liumaishenjian.ccjava.cli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class BrowserAuthConfigurationTest {
    @TempDir Path root;
    @Test void onlyProductionEntrypointCanBeAdvertisedAndMissingDependencyFailsClosed() throws Exception {
        String oldNode = System.getProperty("codej.nodeExecutable"), oldBridge = System.getProperty("codej.piBridge");
        try {
            Path node = Files.writeString(root.resolve("node-fixture.exe"), "not executed");
            Path entry = Files.writeString(root.resolve("login.mjs"), "// production entry fixture");
            Path module = Files.writeString(root.resolve("bridge.mjs"), "// library only");
            System.setProperty("codej.nodeExecutable", node.toString());
            System.setProperty("codej.piBridge", entry.toString());
            assertThat(BrowserAuthConfiguration.available()).isFalse();
            Path dependency = root.resolve("node_modules/@earendil-works/pi-ai/package.json");
            Files.createDirectories(dependency.getParent()); Files.writeString(dependency, "{}");
            assertThat(BrowserAuthConfiguration.resolve().bridgeEntrypoint()).isEqualTo(entry.toRealPath());
            System.setProperty("codej.piBridge", module.toString());
            assertThat(BrowserAuthConfiguration.available()).isFalse();
            assertThatThrownBy(BrowserAuthConfiguration::resolve).isInstanceOf(ProviderAuthException.class);
        } finally {
            restore("codej.nodeExecutable", oldNode); restore("codej.piBridge", oldBridge);
        }
    }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
