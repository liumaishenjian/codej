package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Java Headless 单次进程的类型化 CLI Override。
 *
 * <p>该值只携带非 Secret 配置。API Key 和 Base URL 继续来自 Git 忽略文件或环境变量；
 * Workspace 在生产 Runner 中解析为真实目录后才进入 Session Metadata。</p>
 *
 * @param workspace 用户选择的 Workspace
 * @param model 可选模型名覆盖
 * @param timeout 每个 Run 的墙钟限制
 * @param permissionMode 当前 S05 Permission Mode
 * @param sessionOpenRequest S06 Session 选择
 * @param contextPreparation S07 显式启动容量配置；空表示保持 Canonical no-op 路径
 * @param diagnosticMode 本机模型诊断模式，默认 OFF
 * @param diagnosticDirectory 可选可信诊断目录；不进入协议或 Session
 * @param executionBackend 可信 CLI 控制面显式选择的执行后端
 * @param executionShell 不得被后端隐式转换的 shell 语义
 * @since 0.1.0
 */
record CliOverrides(
        Path workspace,
        Optional<String> model,
        Duration timeout,
        PermissionMode permissionMode,
        SessionOpenRequest sessionOpenRequest,
        Optional<ContextPreparationConfig> contextPreparation,
        ModelDiagnosticMode diagnosticMode,
        Optional<Path> diagnosticDirectory,
        ExecutionBackendPreference executionBackend,
        ExecutionShell executionShell) {

    CliOverrides(
            Path workspace,
            Optional<String> model,
            Duration timeout,
            PermissionMode permissionMode) {
        this(
                workspace,
                model,
                timeout,
                permissionMode,
                SessionOpenRequest.create(),
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty(),
                ExecutionBackendPreference.LOCAL,
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? ExecutionShell.WINDOWS_PLATFORM
                        : ExecutionShell.POSIX_PLATFORM);
    }

    CliOverrides(
            Path workspace,
            Optional<String> model,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest) {
        this(
                workspace,
                model,
                timeout,
                permissionMode,
                sessionOpenRequest,
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty(),
                ExecutionBackendPreference.LOCAL,
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? ExecutionShell.WINDOWS_PLATFORM
                        : ExecutionShell.POSIX_PLATFORM);
    }

    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    static final Duration MIN_TIMEOUT = Duration.ofMillis(10);
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_MODEL_LENGTH = 200;

    CliOverrides {
        workspace = Objects.requireNonNull(workspace, "workspace 不能为空")
                .toAbsolutePath()
                .normalize();
        model = Objects.requireNonNull(model, "model 不能为空")
                .map(String::trim);
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        permissionMode = Objects.requireNonNull(permissionMode, "permissionMode 不能为空");
        sessionOpenRequest = Objects.requireNonNull(
                sessionOpenRequest, "sessionOpenRequest 不能为空");
        contextPreparation = Objects.requireNonNull(
                contextPreparation, "contextPreparation 不能为空");
        diagnosticMode = Objects.requireNonNull(diagnosticMode, "diagnosticMode 不能为空");
        diagnosticDirectory = Objects.requireNonNull(
                diagnosticDirectory, "diagnosticDirectory 不能为空")
                .map(path -> path.toAbsolutePath().normalize());
        executionBackend = Objects.requireNonNull(executionBackend, "executionBackend 不能为空");
        executionShell = Objects.requireNonNull(executionShell, "executionShell 不能为空");
        if (executionBackend != ExecutionBackendPreference.LOCAL
                && executionShell != ExecutionShell.LINUX_SH) {
            throw new IllegalArgumentException("Sandbox/Container 必须显式选择 linux-sh，不能隐式转换平台 Shell");
        }
        if (diagnosticMode == ModelDiagnosticMode.OFF && diagnosticDirectory.isPresent()) {
            throw new IllegalArgumentException("diagnostics-dir 仅可与 safe 或 verbose 一起使用");
        }
        if (model.isPresent()) {
            String value = model.orElseThrow();
            if (value.isBlank()
                    || value.length() > MAX_MODEL_LENGTH
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("model 为空或包含不支持的字符");
            }
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 必须在 10ms 到 30m 之间");
        }
    }
}
