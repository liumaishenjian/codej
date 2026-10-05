package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileToolTest {

    @TempDir
    Path workspace;

    @Test
    void createsNewFileUnderExistingRealParent() throws Exception {
        Files.createDirectories(workspace.resolve("src/main"));
        WriteFileTool tool = new WriteFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of(
                "path", "src/main/New.java",
                "content", "class New {}\n"));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readString(workspace.resolve("src/main/New.java")))
                .isEqualTo("class New {}\n");
        assertThat(outcome.content())
                .contains("operation: created", "path: src/main/New.java");
        assertThat(outcome.metadata().continuation().values())
                .containsEntry("verification", "verified");
    }

    @Test
    void neverOverwritesExistingFileOrCreatesMissingParent() throws Exception {
        Path existing = Files.writeString(workspace.resolve("existing.txt"), "user");
        WriteFileTool tool = new WriteFileTool(new WorkspaceGuard(workspace));

        assertError(tool, Map.of("path", "existing.txt", "content", "agent"),
                ToolErrorCode.FILE_CONFLICT);
        assertError(tool, Map.of("path", "missing/new.txt", "content", "agent"),
                ToolErrorCode.PATH_NOT_FOUND);

        assertThat(Files.readString(existing)).isEqualTo("user");
        assertThat(workspace.resolve("missing")).doesNotExist();
    }

    @Test
    void rejectsSensitiveTraversalBinaryAndOversizedContent() throws Exception {
        WriteFileTool tool = new WriteFileTool(new WorkspaceGuard(workspace));

        assertError(tool, Map.of("path", ".env", "content", "secret=x"),
                ToolErrorCode.SENSITIVE_PATH);
        assertError(tool, Map.of("path", "../outside.txt", "content", "x"),
                ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
        assertThat(tool.validate(new JsonObject(Map.of(
                "path", "null.txt", "content", "a\0b"))).valid()).isFalse();
        assertThat(tool.validate(new JsonObject(Map.of(
                "path", "large.txt", "content", "界".repeat(800_000)))).valid()).isFalse();
    }

    private static void assertError(
            WriteFileTool tool,
            Map<String, ?> arguments,
            ToolErrorCode expected) {
        ToolExecutionOutcome outcome = execute(tool, arguments);
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code()).isEqualTo(expected);
    }

    private static ToolExecutionOutcome execute(
            WriteFileTool tool,
            Map<String, ?> arguments) {
        ToolCall call = new ToolCall("call-1", "write_file", new JsonObject(arguments));
        return tool.execute(new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                call));
    }
}
