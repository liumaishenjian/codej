package io.github.liumaishenjian.ccjava.tools.local.command;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolOutputSink;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend;
import io.github.liumaishenjian.ccjava.domain.execution.EnvironmentPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import io.github.liumaishenjian.ccjava.domain.execution.FileAccessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.NetworkPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.PolicyProvenance;
import io.github.liumaishenjian.ccjava.domain.execution.ProcessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.SecretPolicy;
import io.github.liumaishenjian.ccjava.tools.local.execution.LocalExecutionBackend;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把既有 run_command 契约适配到唯一 {@link ExecutionBackend} seam。
 *
 * <p>兼容构造器仍选择明确 UNSANDBOXED 的 Local backend；Sandbox 或 Container 由可信
 * Composition Root 注入，模型不能选择或静默 fallback。Permission 和 Approval 仍先于本适配器。</p>
 *
 * @since 0.4.0
 */
public final class LocalCommandExecutor {
    private final Path workspace;
    private final ExecutionBackend backend;
    private final ExecutionShell shell;

    /**
     * 为固定 Workspace 创建保持 S04 行为的未隔离 Local 适配器。
     *
     * @param workspace 已校验的 Workspace 根
     */
    public LocalCommandExecutor(Path workspace) {
        this(
                workspace,
                new LocalExecutionBackend(workspace),
                platformShell());
    }

    /**
     * 创建由可信 Composition Root 选择后端与显式 shell 语义的适配器。
     *
     * @param workspace 已校验的 Workspace 根
     * @param backend 进程执行后端
     * @param shell 不得被后端隐式转换的 shell 语义
     */
    public LocalCommandExecutor(
            Path workspace,
            ExecutionBackend backend,
            ExecutionShell shell) {
        this.workspace = Objects.requireNonNull(workspace);
        this.backend = Objects.requireNonNull(backend);
        this.shell = Objects.requireNonNull(shell);
    }

    /**
     * 返回与当前真实后端、固定 Workspace 同源的显示事实。
     *
     * <p>该方法不启动进程，也不表示命令已经获准或执行成功。</p>
     *
     * @return 可安全投影的 Shell ID 与 Workspace-relative cwd
     */
    public CommandExecutionDisplay display() {
        return new CommandExecutionDisplay(shellId(backend.id()), ".");
    }

    /**
     * 执行已通过参数校验与审批的完整命令。
     *
     * @param command 完整命令文本
     * @param timeout 执行期限
     * @param cancellation 取消信号
     * @param outputSink 流式输出接收端
     * @return 兼容 run_command 协议的执行结果
     * @throws IOException 后端启动、通信或清理失败
     */
    public CommandExecutionResult execute(
            String command,
            Duration timeout,
            CancellationToken cancellation,
            ToolOutputSink outputSink) throws IOException {
        return execute(
                "run-command",
                command,
                timeout,
                cancellation,
                outputSink);
    }

    /**
     * 执行绑定当前 Call ID 的命令。
     *
     * @param callId 当前 Tool Call 身份
     * @param command 完整命令文本
     * @param timeout 执行期限
     * @param cancellation 取消信号
     * @param outputSink 流式输出接收端
     * @return 兼容 run_command 协议的执行结果
     * @throws IOException 后端启动、通信或清理失败
     */
    public CommandExecutionResult execute(
            String callId,
            String command,
            Duration timeout,
            CancellationToken cancellation,
            ToolOutputSink outputSink) throws IOException {
        ExecutionPolicy policy = new ExecutionPolicy(
                new FileAccessPolicy(
                        List.of(workspace.toString()),
                        List.of(workspace.toString()),
                        List.of(
                                ".git",
                                ".cc-java",
                                ".claude",
                                ".mcp.json",
                                "config/provider.local.properties")),
                ProcessPolicy.restricted(),
                NetworkPolicy.denyAllNetwork(),
                new EnvironmentPolicy(CommandEnvironment.minimal()),
                SecretPolicy.common(),
                backend.id() != ExecutionBackendId.LOCAL,
                List.of(new PolicyProvenance(
                        PolicyProvenance.Kind.HOST,
                        "s13-host-baseline")));
        ExecutionRequest request = new ExecutionRequest(
                callId,
                shell,
                "",
                List.of(command),
                workspace.toString(),
                timeout,
                LocalToolLimits.MAX_COMMAND_OUTPUT_CHARACTERS,
                policy);
        var result = backend.execute(request, cancellation, outputSink);
        return new CommandExecutionResult(
                shellId(result.enforcement().backend()),
                result.exitCode(),
                result.timedOut(),
                result.cancelled(),
                result.stdout(),
                result.stderr(),
                result.truncated(),
                result.originalCharacters(),
                result.enforcement());
    }

    private static ExecutionShell platformShell() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win")
                ? ExecutionShell.WINDOWS_PLATFORM
                : ExecutionShell.POSIX_PLATFORM;
    }

    private static String shellId(ExecutionBackendId id) {
        return switch (id) {
            case LOCAL -> CommandShell.current().id();
            case WSL2_BWRAP -> "linux-sh/wsl2-bwrap";
            case DOCKER_CONTAINER -> "linux-sh/docker";
            case NATIVE_WINDOWS -> "windows-native";
            case MACOS_SANDBOX -> "macos-sandbox";
        };
    }
}
