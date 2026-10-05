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
    void modelDefinitionUsesSameShellAsApprovalWithoutStartingAnyProcess() {
        for (var id : io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId.values()) {
            var backend = new io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend() {
                public io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId id() { return id; }
                public io.github.liumaishenjian.ccjava.domain.execution.ExecutionOutcome execute(
                        io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest request,
                        CancellationToken cancellation, io.github.liumaishenjian.ccjava.core.ToolOutputSink sink) {
                    throw new AssertionError("读取工具定义不应启动进程");
                }
            };
            var tool = new RunCommandTool(new LocalCommandExecutor(workspace, backend,
                    io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell.LINUX_SH));
            String shell = tool.commandDisplay().shell();
            assertThat(tool.definition()).isSameAs(tool.definition());
            assertThat(tool.definition().description()).contains("Configured shell: " + shell + ".")
                    .doesNotContain(workspace.toString());
            if (shell.equals("powershell")) {
                assertThat(tool.definition().description()).contains("Get-Content", "$LASTEXITCODE");
            } else if (shell.equals("sh") || shell.startsWith("linux-sh/")) {
                assertThat(tool.definition().description()).contains("POSIX sh").doesNotContain("PowerShell");
            }
            assertThat(tool.validate(new JsonObject(Map.of("command", "echo sample", "shell", "bash"))).valid()).isFalse();
            assertThat(tool.definition().source()).isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN);
            assertThat(tool.definition().effect()).isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolEffect.EXECUTE_PROCESS);
        }
    }

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
