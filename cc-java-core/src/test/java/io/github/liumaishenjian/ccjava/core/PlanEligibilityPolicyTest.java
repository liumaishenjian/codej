package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanEligibilityPolicyTest {
    private final PlanEligibilityPolicy policy = new PlanEligibilityPolicy();

    @Test
    void allowsDeclaredCapabilitiesAndDeterministicallyRejectsMutationProcessAndImplicitExternalTools() {
        assertThat(policy.eligible(definition(ToolEffect.READ_WORKSPACE, ToolSource.BUILT_IN,
                Set.of(PlanToolCapability.READ_ONLY_LOCAL)))).isTrue();
        assertThat(policy.eligible(definition(ToolEffect.NETWORK_OR_REMOTE, ToolSource.BUILT_IN,
                Set.of(PlanToolCapability.READ_ONLY_NETWORK)))).isTrue();
        assertThat(policy.eligible(definition(ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN,
                Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE)))).isTrue();
        assertThat(policy.eligible(definition(ToolEffect.USER_INTERACTION, ToolSource.BUILT_IN,
                Set.of(PlanToolCapability.USER_QUESTION)))).isTrue();
        assertThat(policy.eligible(definition(ToolEffect.WRITE_WORKSPACE, ToolSource.BUILT_IN, Set.of()))).isFalse();
        assertThat(policy.eligible(definition(ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, Set.of()))).isFalse();
        assertThat(policy.eligible(definition(ToolEffect.READ_WORKSPACE, ToolSource.PLUGIN, Set.of()))).isFalse();
        assertThat(policy.eligible(definition(ToolEffect.NETWORK_OR_REMOTE, ToolSource.MCP, Set.of()))).isFalse();
        assertThat(policy.eligible(definition("task_list", ToolEffect.READ_SESSION_STATE,
                ToolSource.BUILT_IN, Set.of()))).isTrue();
        assertThat(policy.eligible(definition("task_update", ToolEffect.WRITE_SESSION_STATE,
                ToolSource.BUILT_IN, Set.of()))).isTrue();
        assertThat(policy.eligible(definition("task_update", ToolEffect.WRITE_SESSION_STATE,
                ToolSource.PLUGIN, Set.of()))).isFalse();
        assertThat(policy.eligible(definition("safe_tool", ToolEffect.WRITE_SESSION_STATE,
                ToolSource.BUILT_IN, Set.of()))).isFalse();
    }

    private static ToolDefinition definition(ToolEffect effect, ToolSource source,
                                             Set<PlanToolCapability> capabilities) {
        return definition("safe_tool", effect, source, capabilities);
    }

    private static ToolDefinition definition(String name, ToolEffect effect, ToolSource source,
                                             Set<PlanToolCapability> capabilities) {
        return new ToolDefinition(name, "safe tool", "{}", effect, source, false,
                Duration.ofSeconds(1), "text/plain", 100, capabilities);
    }
}
