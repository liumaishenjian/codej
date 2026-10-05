package io.github.liumaishenjian.ccjava.cli.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.subagent.*;
import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/** S12 task journal 的 no-replay recovery 与唯一聚合终态回归。 */
class FileChildTaskJournalTest {
    @TempDir Path temp;

    @Test
    void recoversOnlyIncompleteIdentityAsInterruptedUnknown() {
        ChildTaskId incomplete = new ChildTaskId("task-incomplete");
        ChildTaskId complete = new ChildTaskId("task-complete");
        try (FileChildTaskJournal journal = new FileChildTaskJournal(temp)) {
            journal.requested(incomplete); journal.started(incomplete);
            journal.requested(complete); journal.started(complete);
            journal.terminal(new ChildTaskReport(complete, new AgentDefinitionId("research"),
                    ChildTaskStatus.SUCCEEDED, ChildTaskFailureCode.NONE, 1, 0, 0,
                    Duration.ofMillis(1), "completed", true, Optional.empty()));
            assertThat(journal.interruptedUnknown()).singleElement().satisfies(report -> {
                assertThat(report.taskId()).isEqualTo(incomplete);
                assertThat(report.status()).isEqualTo(ChildTaskStatus.INTERRUPTED_UNKNOWN);
                assertThat(report.verified()).isFalse();
                assertThat(report.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNKNOWN);
            });
        }
    }

    @Test
    void terminalPersistsOnlyItsCleanupSnapshotAndDoesNotPermitLaterCleanupEvents() throws Exception {
        var id = new ChildTaskId("task-cleanup-snapshot");
        try (var journal = new FileChildTaskJournal(temp)) {
            journal.requested(id); journal.started(id);
            journal.terminal(new ChildTaskReport(id, new AgentDefinitionId("research"), ChildTaskStatus.SUCCEEDED,
                    ChildTaskFailureCode.NONE, 1, 0, 0, Duration.ZERO, "completed", true, Optional.empty())
                    .withCleanupStatus(ResourceCleanupStatus.CLEANING));
            assertThat(journal.interruptedUnknown()).isEmpty();
        }
        var path = temp.resolve("child-tasks.jsonl");
        var lines = java.nio.file.Files.readAllLines(path);
        assertThat(lines).hasSize(3);
        assertThat(lines.getLast()).contains("\"cleanupStatus\":\"CLEANING\"")
                .doesNotContain("RELEASED", "private close detail");
        java.nio.file.Files.writeString(path, "{\"taskId\":\"task-cleanup-snapshot\",\"event\":\"cleanup\"}\n",
                java.nio.file.StandardOpenOption.APPEND);
        try (var journal = new FileChildTaskJournal(temp)) {
            assertThatThrownBy(journal::interruptedUnknown).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void ignoresDamagedTailButRejectsDuplicateAndInvalidSequence() throws Exception {
        ChildTaskId damaged = new ChildTaskId("task-damaged-tail");
        try (FileChildTaskJournal journal = new FileChildTaskJournal(temp)) {
            journal.requested(damaged);
        }
        java.nio.file.Files.writeString(temp.resolve("child-tasks.jsonl"), "{\"v\":1,\"taskId\":\"broken",
                java.nio.file.StandardOpenOption.APPEND);
        try (FileChildTaskJournal reopened = new FileChildTaskJournal(temp)) {
            assertThat(reopened.interruptedUnknown()).extracting(ChildTaskReport::taskId).containsExactly(damaged);
        }

        Path duplicateRoot = java.nio.file.Files.createDirectories(temp.resolve("duplicate"));
        String duplicate = "{\"taskId\":\"task-dup\",\"event\":\"requested\"}\n"
                + "{\"taskId\":\"task-dup\",\"event\":\"requested\"}\n";
        java.nio.file.Files.writeString(duplicateRoot.resolve("child-tasks.jsonl"), duplicate);
        try (FileChildTaskJournal duplicateJournal = new FileChildTaskJournal(duplicateRoot)) {
            assertThatThrownBy(duplicateJournal::interruptedUnknown).isInstanceOf(IllegalStateException.class);
        }

        Path invalidRoot = java.nio.file.Files.createDirectories(temp.resolve("invalid"));
        java.nio.file.Files.writeString(invalidRoot.resolve("child-tasks.jsonl"),
                "{\"taskId\":\"task-invalid\",\"event\":\"started\"}\n");
        try (FileChildTaskJournal invalidJournal = new FileChildTaskJournal(invalidRoot)) {
            assertThatThrownBy(invalidJournal::interruptedUnknown).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsOversizeAndWritesAfterClose() throws Exception {
        Path oversized = java.nio.file.Files.createDirectories(temp.resolve("oversized"));
        java.nio.file.Files.write(oversized.resolve("child-tasks.jsonl"), new byte[4 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> new FileChildTaskJournal(oversized)).isInstanceOf(IllegalArgumentException.class);

        FileChildTaskJournal closed = new FileChildTaskJournal(temp.resolve("closed"));
        closed.close();
        assertThatThrownBy(() -> closed.requested(new ChildTaskId("task-after-close")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void durableFailureMarkerPreventsIncompleteTaskFromBeingRecoveredAsReplayable() {
        ChildTaskId failed = new ChildTaskId("task-failed-marker");
        try (FileChildTaskJournal journal = new FileChildTaskJournal(temp)) {
            journal.requested(failed);
            journal.started(failed);
            journal.terminalFailure(new ChildTaskReport(failed, new AgentDefinitionId("research"),
                    ChildTaskStatus.FAILED, ChildTaskFailureCode.JOURNAL_FAILED, 1, 0, 0,
                    Duration.ofMillis(1), "journal_failed", false, Optional.empty()));
            assertThat(journal.interruptedUnknown()).isEmpty();
        }
        try (FileChildTaskJournal reopened = new FileChildTaskJournal(temp)) {
            assertThat(reopened.interruptedUnknown()).isEmpty();
        }
    }
}
