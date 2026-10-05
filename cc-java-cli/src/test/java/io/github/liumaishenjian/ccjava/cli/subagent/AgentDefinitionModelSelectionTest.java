package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 模型覆盖声明与继承分离；该测试不把声明目录当成运行时模型授权。 */
class AgentDefinitionModelSelectionTest {
    @TempDir Path root;
    private static final CancellationToken NONE = CancellationToken.none();

    @Test void oldConstructorRemainsExplicitAndMissingModelIsNotAFakeName() {
        var budget = new ChildBudget(1, 0, 100, 100, Duration.ofSeconds(10));
        var explicit = new AgentDefinitionSnapshot(new AgentDefinitionId("reader"), "reader", "fixture",
                Set.of(), PermissionMode.DEFAULT, "fixed-model", budget, false, "a".repeat(64), "user");
        var inherited = new AgentDefinitionSnapshot(new AgentDefinitionId("reader"), "reader", "fixture",
                Set.of(), PermissionMode.DEFAULT, Optional.empty(), budget, false, "a".repeat(64), "user");
        assertThat(explicit.modelOverride()).contains("fixed-model");
        assertThat(explicit.modelName()).isEqualTo("fixed-model");
        assertThat(inherited.modelOverride()).isEmpty();
        assertThatThrownBy(inherited::modelName).isInstanceOf(IllegalStateException.class);
        assertThat(inherited).isNotEqualTo(explicit);
    }

    @Test void omissionIsAcceptedOnlyByCapturedRouteLoader() throws Exception {
        Files.writeString(root.resolve("reader.agent"), definition(""));
        assertThat(FileAgentDefinitionCatalog.load(root, null, Set.of(), Set.of("fixed-model"), NONE)
                .snapshots()).isEmpty();
        var captured = FileAgentDefinitionCatalog.loadForCapturedRoutes(root, null, Set.of(), NONE, false);
        assertThat(captured.snapshots()).hasSize(1);
        assertThat(captured.snapshots().getFirst().modelOverride()).isEmpty();
    }

    @Test void explicitOverrideRemainsUnresolvedDeclarationAndLegacyStillChecksItsCatalog() throws Exception {
        Files.writeString(root.resolve("reader.agent"), definition("model=another-model"));
        assertThat(FileAgentDefinitionCatalog.load(root, null, Set.of(), Set.of("fixed-model"), NONE)
                .snapshots()).isEmpty();
        var captured = FileAgentDefinitionCatalog.loadForCapturedRoutes(root, null, Set.of(), NONE, false);
        assertThat(captured.snapshots()).hasSize(1);
        assertThat(captured.snapshots().getFirst().modelOverride()).contains("another-model");
        // 新入口只接受声明；是否属于捕获的provider必须在委托入队前另行核验。
        assertThat(FileAgentDefinitionCatalog.load(root, null, Set.of(), Set.of("another-model"), NONE)
                .snapshots().getFirst()).isEqualTo(captured.snapshots().getFirst());
    }

    @Test void blankUnknownFieldsAndUntrustedProjectAreStillRejected() throws Exception {
        Path file = root.resolve("reader.agent");
        Files.writeString(file, definition("model=   "));
        assertThat(FileAgentDefinitionCatalog.loadForCapturedRoutes(root, null, Set.of(), NONE, false)
                .snapshots()).isEmpty();
        Files.writeString(file, definition("") + "unexpected=value\n");
        assertThat(FileAgentDefinitionCatalog.loadForCapturedRoutes(root, null, Set.of(), NONE, false)
                .snapshots()).isEmpty();
        Files.writeString(file, definition(""));
        var untrusted = FileAgentDefinitionCatalog.loadForCapturedRoutes(null, root, Set.of(), NONE, false);
        assertThat(untrusted.snapshots()).isEmpty();
        assertThat(untrusted.diagnostics()).contains("project:trust_required");
    }

    private static String definition(String model) {
        return """
                id=reader
                description=Independent reader
                instructions=Independent fixture only
                tools=
                permission=DEFAULT
                %s
                max-model-turns=1
                max-tool-calls=0
                max-input-tokens=100
                max-output-characters=100
                timeout-seconds=10
                background=false
                """.formatted(model);
    }
}
