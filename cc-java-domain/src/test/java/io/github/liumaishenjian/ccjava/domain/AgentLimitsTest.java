package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentLimitsTest {

    @Test
    void keepsCompatibilityConstructorsAsPresentHardLimits() {
        AgentLimits compatible = new AgentLimits(2, 3);
        AgentLimits explicit = new AgentLimits(2, 3, Duration.ofSeconds(4));

        assertThat(compatible.totalModelTurns()).hasValue(2);
        assertThat(compatible.totalToolCalls()).hasValue(3);
        assertThat(compatible.runDeadline()).contains(Duration.ofMinutes(5));
        assertThat(explicit.totalModelTurns()).hasValue(2);
        assertThat(explicit.totalToolCalls()).hasValue(3);
        assertThat(explicit.runDeadline()).contains(Duration.ofSeconds(4));
    }

    @Test
    void modelsInteractiveTotalLimitsAsAbsentWithoutSentinels() {
        AgentLimits interactive = AgentLimits.interactive();

        assertThat(interactive.totalModelTurns()).isEmpty();
        assertThat(interactive.totalToolCalls()).isEmpty();
        assertThat(interactive.runDeadline()).isEmpty();
    }

    @Test
    void validatesOnlyPresentHardLimits() {
        assertThatThrownBy(() -> new AgentLimits(0, 3, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLimits(2, -1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLimits(2, 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLimits(2, 3, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
