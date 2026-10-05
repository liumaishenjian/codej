package io.github.liumaishenjian.ccjava.domain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-100：旧报告保持未知，独立清理投影不能伪造或改变运行结果。 */
class ChildTaskReportCleanupStatusTest {
    @Test
    void bothCompatibilityConstructorsDefaultToUnknown() {
        ChildTaskReport withoutWorktree = new ChildTaskReport(new ChildTaskId("task-old"),
                new AgentDefinitionId("test"), ChildTaskStatus.SUCCEEDED, ChildTaskFailureCode.NONE,
                1, 2, 3, Duration.ofSeconds(4), "completed", true);
        ChildTaskReport withWorktree = legacy();
        assertThat(withoutWorktree.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
        assertThat(withoutWorktree.worktreeDisposition()).isEmpty();
        assertThat(withWorktree.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
        assertThat(withWorktree.worktreeDisposition()).contains("RETAINED");
    }

    @Test
    void overlayPreservesAllOtherFieldsAndOriginalSnapshot() {
        ChildTaskReport old = legacy();
        for (ResourceCleanupStatus status : ResourceCleanupStatus.values()) {
            ChildTaskReport projected = old.withCleanupStatus(status);
            assertThat(projected.cleanupStatus()).isEqualTo(status);
            assertThat(projected).usingRecursiveComparison().ignoringFields("cleanupStatus").isEqualTo(old);
        }
        assertThat(old.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
    }

    @Test
    void cleanupStatusMustBePresentAndExistingValidationStillApplies() {
        assertThatThrownBy(() -> legacy().withCleanupStatus(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChildTaskReport(new ChildTaskId("task-invalid"),
                new AgentDefinitionId("test"), ChildTaskStatus.FAILED, ChildTaskFailureCode.RUNTIME_FAILED,
                -1, 0, 0, Duration.ZERO, "runtime_failed", false, Optional.empty(), ResourceCleanupStatus.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChildTaskReport(new ChildTaskId("task-invalid"),
                new AgentDefinitionId("test"), ChildTaskStatus.SUCCEEDED, ChildTaskFailureCode.NONE,
                1, 0, 0, Duration.ZERO, "completed", true, Optional.of("C:/private"), ResourceCleanupStatus.RELEASED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChildTaskReport legacy() {
        return new ChildTaskReport(new ChildTaskId("task-old"), new AgentDefinitionId("test"),
                ChildTaskStatus.SUCCEEDED, ChildTaskFailureCode.NONE, 1, 2, 3,
                Duration.ofSeconds(4), "completed", true, Optional.of("RETAINED"));
    }
}
