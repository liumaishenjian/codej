package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandExecutionDisplay;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandExecutionResult;
import io.github.liumaishenjian.ccjava.tools.local.command.LocalCommandExecutor;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 经统一 Permission/Approval Pipeline 执行一个前台 Shell 命令。
 *
 * <p>Tool 只接受命令正文和有限 timeout；Shell、Workspace、环境、stdin、输出预算与
 * 进程树策略不可由模型覆盖。非零退出码通过 Pipeline 返回 {@code FAILURE/PROCESS_EXIT}，
 * 同时保留有界 stdout/stderr 作为纠正证据。Shell HTTP 请求应显式使用让 HTTP 错误产生非零退出
 * 且保留正文的选项（例如 curl 的 {@code --fail-with-body}）；Runtime 不抓取 HTML 猜测状态。
 * 该 Tool 不支持后台执行、TTY、Commit、Push 或部署特权。</p>
 *
 * @since 0.4.0
 */
public final class RunCommandTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of("command", "timeoutSeconds");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "run_command",
            "Run one approved foreground command in the fixed workspace and platform shell. "
                    + "Non-zero exit is a typed failure with bounded stdout/stderr. For shell HTTP, use an "
                    + "explicit fail-with-body option so HTTP errors produce a non-zero exit; HTML is not scraped.",
            "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"required\":[\"command\"],\"properties\":{"
                    + "\"command\":{\"type\":\"string\",\"minLength\":1},"
                    + "\"timeoutSeconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":120}}}",
            ToolEffect.EXECUTE_PROCESS,
            ToolSource.BUILT_IN,
            true,
            Duration.ofSeconds(LocalToolLimits.DEFAULT_COMMAND_TIMEOUT_SECONDS),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final LocalCommandExecutor executor;

    /**
     * 创建固定命令执行器的 Tool Adapter。
     *
     * @param executor 平台命令执行端口
     */
    public RunCommandTool(LocalCommandExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor 不能为空");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 返回与本 Tool 实际执行器同源的非 Secret Shell/cwd 描述。
     *
     * @return 仅描述已装配执行配置，不表示命令已开始或成功
     */
    public CommandExecutionDisplay commandDisplay() {
        return executor.display();
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            ToolArguments.rejectUnknown(arguments, ARGUMENTS);
            String command = ToolArguments.string(arguments, "command", "");
            ToolArguments.requireNonBlank("command", command);
            ToolArguments.rejectBinaryNull("command", command);
            ToolArguments.requireMaximumCharacters(
                    "command", command, LocalToolLimits.MAX_COMMAND_CHARACTERS);
            if (command.codePoints().anyMatch(character ->
                    Character.isISOControl(character)
                            && character != '\r'
                            && character != '\n'
                            && character != '\t')) {
                throw new IllegalArgumentException("command 包含不支持的控制字符");
            }
            int timeout = ToolArguments.integer(
                    arguments,
                    "timeoutSeconds",
                    LocalToolLimits.DEFAULT_COMMAND_TIMEOUT_SECONDS);
            ToolArguments.requireRange(
                    "timeoutSeconds", timeout, 1, LocalToolLimits.MAX_COMMAND_TIMEOUT_SECONDS);
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        String command = ToolArguments.string(invocation.call().arguments(), "command", "");
        int timeoutSeconds = ToolArguments.integer(
                invocation.call().arguments(),
                "timeoutSeconds",
                LocalToolLimits.DEFAULT_COMMAND_TIMEOUT_SECONDS);
        try {
            CommandExecutionResult result = executor.execute(
                    invocation.call().id(),
                    command,
                    Duration.ofSeconds(timeoutSeconds),
                    invocation.cancellationToken(),
                    invocation.outputSink());
            String content = render(result);
            ToolResultMetadata metadata = new ToolResultMetadata(
                    result.truncated(),
                    result.truncated()
                            ? ToolResultTruncationReason.BYTE_LIMIT
                            : ToolResultTruncationReason.NONE,
                    content.codePointCount(0, content.length()),
                    result.truncated()
                            ? OptionalLong.of(Math.max(
                                    result.originalCharacters(),
                                    content.codePointCount(0, content.length())))
                            : OptionalLong.of(content.codePointCount(0, content.length())),
                    2,
                    0,
                    JsonObject.empty());
            if (result.cancelled()) {
                return ToolExecutionOutcome.failure(content,
                        ToolError.classified(ToolErrorCode.OPERATION_CANCELLED,
                                io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.CANCELLATION,
                                false, "命令已取消",
                                commandExitDetails(result.exitCode())), metadata);
            }
            if (result.timedOut()) {
                return ToolExecutionOutcome.failure(content,
                        ToolError.classified(ToolErrorCode.OPERATION_TIMED_OUT,
                                io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.TIMEOUT,
                                false, "命令达到墙钟期限",
                                commandExitDetails(result.exitCode())), metadata);
            }
            if (result.exitCode() != 0) {
                return ToolExecutionOutcome.failure(content,
                        ToolError.classified(ToolErrorCode.PROCESS_EXIT,
                                io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.PROCESS_EXIT,
                                false, "命令以非零状态退出",
                                commandExitDetails(result.exitCode())), metadata);
            }
            return ToolExecutionOutcome.success(content, metadata);
        } catch (IOException exception) {
            return ToolExecutionOutcome.failure(ToolError.of(
                    ToolErrorCode.EXECUTION_FAILED,
                    "命令进程无法启动"));
        }
    }

    /**
     * 只在执行后端取得真实退出码时投影结构化事实；{@code -1} 表示终止后未知。
     */
    static JsonObject commandExitDetails(int exitCode) {
        return exitCode == -1
                ? JsonObject.empty()
                : new JsonObject(java.util.Map.of("exitCode", exitCode));
    }

    private static String render(CommandExecutionResult result) {
        StringBuilder output = new StringBuilder()
                .append("shell: ").append(result.shell()).append('\n')
                .append("backend: ").append(result.enforcement().backend()).append('\n')
                .append("enforcement: ").append(result.enforcement().reasonCode()).append('\n')
                .append("fallback: ").append(result.enforcement().fallback()).append('\n')
                .append("workingDirectory: .\n")
                .append("exitCode: ").append(result.exitCode()).append('\n')
                .append("timedOut: ").append(result.timedOut()).append('\n')
                .append("cancelled: ").append(result.cancelled()).append('\n')
                .append("stdout:\n").append(result.stdout());
        if (!result.stdout().endsWith("\n")) {
            output.append('\n');
        }
        output.append("stderr:\n").append(result.stderr());
        if (!result.stderr().endsWith("\n")) {
            output.append('\n');
        }
        if (result.truncated()) {
            output.append("[truncated: command output limit]\n");
        }
        return output.toString();
    }
}
