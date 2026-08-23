package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenMode;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 定义 Java Headless 的稳定命令行契约。
 *
 * <p>交互式终端由 React/Ink 提供，因此本命令只允许一次性 Print 或内部 stdio
 * 二选一。该类型不创建模型、不执行 Agent Loop，也不解释 Runtime 终态。</p>
 *
 * @since 0.1.0
 */
@Command(
        name = "cc-java",
        mixinStandardHelpOptions = true,
        version = "cc-java 0.1.1",
        description = "Java Headless coding-agent runtime")
final class CcJavaCommand implements Callable<Integer> {

    /** CLI 数值只作为防误配边界，不代表任何 Provider 的真实容量。 */
    private static final long MAX_CONTEXT_TOKEN_OPTION = Integer.MAX_VALUE;
    private static final long LARGE_PAYLOAD_TOKEN_THRESHOLD = 8_192L;
    private static final int PROTECTED_MESSAGE_COUNT = 8;
    private static final int MAX_SUMMARY_UTF8_BYTES = 32 * 1_024;
    private static final long MAX_SUMMARY_TOKENS = 8_192L;
    private static final String UNRESOLVED_MODEL_ID = "startup-selected-model";

    private final CliModeRunner runner;

    @ArgGroup(exclusive = true, multiplicity = "1")
    private Mode mode;

    @Option(
            names = "--workspace",
            paramLabel = "<path>",
            description = "Workspace 目录；默认当前目录")
    private Path workspace = Path.of("");

    @Option(
            names = "--model",
            paramLabel = "<name>",
            description = "覆盖本次进程使用的模型名；不接受 API Key")
    private String model;

    @Option(
            names = "--timeout",
            paramLabel = "<duration>",
            converter = CliDurationConverter.class,
            description = "每个 Run 的墙钟限制，例如 250ms、30s、5m；默认 5m")
    private Duration timeout = CliOverrides.DEFAULT_TIMEOUT;

    @Option(
            names = "--permission-mode",
            paramLabel = "<mode>",
            converter = PermissionModeConverter.class,
            description = "Permission 模式：default、plan 或 accept-edits；默认 default")
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    @Option(
            names = "--model-diagnostics",
            paramLabel = "<mode>",
            converter = ModelDiagnosticModeConverter.class,
            description = "本机模型诊断：off、safe 或 verbose；默认 off")
    private ModelDiagnosticMode diagnosticMode = ModelDiagnosticMode.OFF;

    @Option(
            names = "--model-diagnostics-dir",
            paramLabel = "<path>",
            description = "可信本机诊断目录；仅在 safe/verbose 模式使用")
    private Path diagnosticDirectory;

    @Option(names = "--execution-backend", paramLabel = "<local|sandbox|container>",
            description = "进程后端；sandbox/container 必须同时显式 --execution-shell linux-sh")
    private ExecutionBackendPreference executionBackend =
            ExecutionBackendPreference.LOCAL;

    @Option(names = "--execution-shell", paramLabel = "<platform|linux-sh>",
            description = "命令语义；默认 platform，绝不隐式转换 PowerShell/cmd")
    private String executionShell = "platform";

    @ArgGroup(exclusive = true)
    private SessionSelection sessionSelection;

    @ArgGroup(exclusive = false, multiplicity = "0..1")
    private ContextCapacityOptions contextCapacity;

    @Spec
    private CommandSpec commandSpec;

    CcJavaCommand(CliModeRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner 不能为空");
    }

    @Override
    public Integer call() {
        CliOverrides overrides;
        try {
            overrides = new CliOverrides(
                    workspace,
                    Optional.ofNullable(model),
                    timeout,
                    permissionMode,
                    sessionOpenRequest(),
                    contextPreparation(),
                    diagnosticMode,
                    Optional.ofNullable(diagnosticDirectory),
                    executionBackend,
                    parseExecutionShell());
        } catch (IllegalArgumentException exception) {
            throw new ParameterException(
                    commandSpec.commandLine(),
                    exception.getMessage());
        }
        if (mode.printPrompt != null) {
            return runner.runPrint(mode.printPrompt, overrides);
        }
        if (mode.extensionStatus || mode.trustProjectExtensions) {
            return runner.runExtensions(mode.trustProjectExtensions, overrides);
        }
        if (mode.daemon) return runner.runDaemon(overrides);
        return mode.stdioV1 ? runner.runStableStdio(overrides) : runner.runStdio(overrides);
    }

    private ExecutionShell parseExecutionShell() {
        if ("linux-sh".equalsIgnoreCase(executionShell)) {
            return ExecutionShell.LINUX_SH;
        }
        if (!"platform".equalsIgnoreCase(executionShell)) {
            throw new IllegalArgumentException("execution-shell 只接受 platform 或 linux-sh");
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? ExecutionShell.WINDOWS_PLATFORM
                : ExecutionShell.POSIX_PLATFORM;
    }

    private Optional<ContextPreparationConfig> contextPreparation() {
        if (contextCapacity == null) {
            return Optional.empty();
        }
        long maximum = boundedPositive(
                contextCapacity.maximumInputTokens,
                "--context-maximum-input-tokens");
        long reserved = boundedPositive(
                contextCapacity.reservedOutputTokens,
                "--context-reserved-output-tokens");
        long margin = boundedPositive(
                contextCapacity.safetyMarginTokens,
                "--context-safety-margin-tokens");
        String modelId = model == null ? UNRESOLVED_MODEL_ID : model.trim();
        ContextCapacity capacity = new ContextCapacity(modelId, maximum, reserved, margin);
        long summaryTokens = Math.min(MAX_SUMMARY_TOKENS, reserved);
        int summaryUtf8Bytes = Math.toIntExact(Math.min(
                MAX_SUMMARY_UTF8_BYTES,
                Math.multiplyExact(summaryTokens, 4L)));
        return Optional.of(new ContextPreparationConfig(
                capacity,
                LARGE_PAYLOAD_TOKEN_THRESHOLD,
                PROTECTED_MESSAGE_COUNT,
                summaryUtf8Bytes,
                summaryTokens));
    }

    private long boundedPositive(Long value, String optionName) {
        if (value == null || value <= 0 || value > MAX_CONTEXT_TOKEN_OPTION) {
            throw new IllegalArgumentException(
                    optionName + " 必须在 1 到 " + MAX_CONTEXT_TOKEN_OPTION + " 之间");
        }
        return value;
    }

    private SessionOpenRequest sessionOpenRequest() {
        if (sessionSelection == null) {
            return SessionOpenRequest.create();
        }
        if (sessionSelection.continueLatest) {
            return SessionOpenRequest.continueLatest();
        }
        if (sessionSelection.resumeId != null) {
            return new SessionOpenRequest(
                    SessionOpenMode.RESUME,
                    Optional.of(parseSessionId(sessionSelection.resumeId)));
        }
        return new SessionOpenRequest(
                SessionOpenMode.FORK,
                Optional.of(parseSessionId(sessionSelection.forkId)));
    }

    private SessionId parseSessionId(String value) {
        if (value == null
                || value.length() > 128
                || !value.matches("session-[A-Za-z0-9-]+")) {
            throw new ParameterException(
                    commandSpec.commandLine(),
                    "Session ID 格式无效");
        }
        return new SessionId(value);
    }

    /**
     * 显式启动容量元组；Picocli 要求三个可信参数同时出现。
     *
     * <p>这些值不从模型名、Provider 响应或 Workspace 指令推断，也不是 S08 持久设置。</p>
     */
    private static final class ContextCapacityOptions {

        @Option(
                names = "--context-maximum-input-tokens",
                required = true,
                paramLabel = "<tokens>",
                description = "显式模型输入窗口上限；必须与另外两个 Context 参数一起提供")
        private Long maximumInputTokens;

        @Option(
                names = "--context-reserved-output-tokens",
                required = true,
                paramLabel = "<tokens>",
                description = "显式输出保留 Token；必须大于 0")
        private Long reservedOutputTokens;

        @Option(
                names = "--context-safety-margin-tokens",
                required = true,
                paramLabel = "<tokens>",
                description = "显式估算安全余量 Token；必须大于 0")
        private Long safetyMarginTokens;
    }

    private static final class SessionSelection {

        @Option(
                names = "--continue",
                description = "继续当前 Workspace 最近的完整 Session")
        private boolean continueLatest;

        @Option(
                names = "--resume",
                paramLabel = "<session-id>",
                description = "恢复指定 Session ID")
        private String resumeId;

        @Option(
                names = "--fork",
                paramLabel = "<session-id>",
                description = "从指定 Session 历史创建新 Session")
        private String forkId;
    }

    private static final class Mode {

        @Option(
                names = "--print",
                paramLabel = "<prompt>",
                description = "执行一次 Agent Run，并把 Assistant 文本写到 stdout")
        private String printPrompt;

        @Option(
                names = "--stdio",
                description = "启动供 React/Ink TUI 使用的内部 NDJSON stdio v0")
        private boolean stdio;

        @Option(
                names = "--stdio-v1",
                description = "启动 stable v1 NDJSON stdio；首条 initialize 需 capability token")
        private boolean stdioV1;

        @Option(
                names = "--daemon",
                description = "启动仅绑定 loopback 的 stable v1 daemon；stderr 一次性输出端口/token")
        private boolean daemon;

        @Option(
                names = "--extension-status",
                description = "检查固定 Hook/MCP 配置与 Project trust 状态；不加载 Provider")
        private boolean extensionStatus;

        @Option(
                names = "--trust-project-extensions",
                description = "显式信任当前 Project Hook/MCP 配置的精确指纹；配置变化后失效")
        private boolean trustProjectExtensions;
    }
}
