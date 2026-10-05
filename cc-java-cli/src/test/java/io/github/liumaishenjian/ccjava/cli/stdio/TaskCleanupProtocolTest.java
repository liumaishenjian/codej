package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskFailureCode;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskStatus;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-100 独立清理快照的纯协议回归，不创建 Runtime、访问 home 或启动进程。 */
class TaskCleanupProtocolTest {
    @ParameterizedTest
    @EnumSource(ResourceCleanupStatus.class)
    void projectsActualCleanupSnapshotWithoutRewritingTerminal(ResourceCleanupStatus cleanupStatus) {
        ChildTaskReport report = new ChildTaskReport(new ChildTaskId("task-cleanup"),
                new AgentDefinitionId("reader"), ChildTaskStatus.SUCCEEDED, ChildTaskFailureCode.NONE,
                2, 3, 40, Duration.ofMillis(50), "bounded result", true,
                Optional.of("KEPT"), cleanupStatus);

        var payload = RuntimeStdioCommandHandler.taskPayload(report);

        assertThat(payload.get("cleanupStatus").stringValue())
                .isEqualTo(cleanupStatus.name().toLowerCase(Locale.ROOT));
        assertThat(payload.get("status").stringValue()).isEqualTo("succeeded");
        assertThat(payload.get("verified").booleanValue()).isTrue();
        assertThat(payload.get("worktreeDisposition").stringValue()).isEqualTo("kept");
        assertThat(payload.get("summary").stringValue()).isEqualTo("bounded result");
        assertThat(payload.get("modelTurns").intValue()).isEqualTo(2);
        assertThat(payload.get("toolCalls").intValue()).isEqualTo(3);
        assertThat(payload.get("estimatedTokens").longValue()).isEqualTo(40);
        assertThat(payload.get("elapsedMillis").longValue()).isEqualTo(50);
    }

    @Test
    void oldReportConstructorDoesNotImplyResourcesReleased() {
        ChildTaskReport report = new ChildTaskReport(new ChildTaskId("task-legacy"),
                new AgentDefinitionId("reader"), ChildTaskStatus.SUCCEEDED, ChildTaskFailureCode.NONE,
                1, 0, 0, Duration.ZERO, "", true, Optional.empty());

        var payload = RuntimeStdioCommandHandler.taskPayload(report);

        assertThat(payload.get("cleanupStatus").stringValue()).isEqualTo("unknown");
        assertThat(payload.get("status").stringValue()).isEqualTo("succeeded");
        assertThat(payload.get("worktreeDisposition").isNull()).isTrue();
    }
}
