package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;
import io.github.liumaishenjian.ccjava.tools.local.command.LocalCommandExecutor;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandToolTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsUnknownAndOutOfRangeArguments() {
        RunCommandTool tool = new RunCommandTool(new LocalCommandExecutor(workspace));

        assertThat(tool.validate(new JsonObject(Map.of(
                "command", "echo ok",
                "timeoutSeconds", 121))).valid()).isFalse();
        assertThat(tool.validate(new JsonObject(Map.of(
                "command", "echo ok",
                "shell", "other"))).valid()).isFalse();
        assertThat(tool.validate(new JsonObject(Map.of(
                "command", "😀".repeat(8_192)))).valid()).isTrue();
        assertThat(tool.validate(new JsonObject(Map.of(
                "command", "😀".repeat(8_193)))).valid()).isFalse();
    }

    @Test
    void returnsNonZeroExitAsTypedProcessFailureWithBoundedEvidence() {
        RunCommandTool tool = new RunCommandTool(new LocalCommandExecutor(workspace));
        String command = CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                ? "Write-Output 'failed-test'; exit 9"
                : "printf 'failed-test\\n'; exit 9";

        ToolExecutionOutcome result = tool.execute(invocation(command));

        assertThat(result.successful()).isFalse();
        assertThat(result.error().orElseThrow().code())
                .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolErrorCode.PROCESS_EXIT);
        assertThat(result.error().orElseThrow().category())
                .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.PROCESS_EXIT);
        assertThat(result.error().orElseThrow().retryable()).isFalse();
        assertThat(result.error().orElseThrow().details().values())
                .containsEntry("exitCode", 9);
        assertThat(result.content())
                .contains("workingDirectory: .", "exitCode: 9", "failed-test");
    }

    @Test
    void omitsUnknownTerminatedExitCodeButKeepsObservedCode() {
        assertThat(RunCommandTool.commandExitDetails(-1).values()).isEmpty();
        assertThat(RunCommandTool.commandExitDetails(137).values())
                .containsEntry("exitCode", 137);
    }

    private static ToolInvocation invocation(String command) {
        return new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall(
                        "call-1",
                        "run_command",
                        new JsonObject(Map.of("command", command, "timeoutSeconds", 5))),
                CancellationToken.none());
    }
}
