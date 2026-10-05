package io.github.liumaishenjian.ccjava.tools.local.workspace;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceWriteVerificationTest {

    @TempDir
    Path workspace;

    @Test
    void rereadsTheExpectedBytesAtTheSameRealPath() throws Exception {
        Path file = Files.writeString(workspace.resolve("sample.txt"), "expected").toRealPath();
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        WorkspaceWriteVerification.requireMatches(
                guard,
                "sample.txt",
                file,
                "expected".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsContentThatChangedAfterTheWrite() throws Exception {
        Path file = Files.writeString(workspace.resolve("sample.txt"), "external").toRealPath();
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        assertThatThrownBy(() -> WorkspaceWriteVerification.requireMatches(
                guard,
                "sample.txt",
                file,
                "expected".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessageContaining("内容与预期不一致");
    }

    @Test
    void turnsDeletionAfterTheWriteIntoAFileConflict() throws Exception {
        Path file = Files.writeString(workspace.resolve("sample.txt"), "expected").toRealPath();
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        Files.delete(file);

        assertThatThrownBy(() -> WorkspaceWriteVerification.requireMatches(
                guard,
                "sample.txt",
                file,
                "expected".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessageContaining("目标文件已改变");
    }
}
