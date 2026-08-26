package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.cli.stdio.RuntimeStdioCommandHandler;
import io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolServer;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException;
import io.github.liumaishenjian.ccjava.model.springai.config.ProviderSettingsLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Java Headless 各运行模式的生产装配器。
 *
 * <p>所有用户可见诊断都是不含 API Key、端点、Prompt 和 Provider 原始响应的稳定文本。
 * Print 通过 Shutdown Hook 把进程中断传播到 Core；Core 会用可中断模型工作线程和 Provider
 * request timeout 保证 Run deadline 收敛。stdio/TUI 的取消仍由协议命令驱动，所有 Surface 只消费
 * Runtime 发布的唯一终态。</p>
 *
 * @since 0.1.0
 */
final class DefaultCliModeRunner implements CliModeRunner {

    private final Path repositoryRoot;
    private final InputStream input;
    private final OutputStream output;
    private final PrintWriter printOutput;
    private final PrintWriter errorOutput;
    private final ProviderSettingsLoader settingsLoader = new ProviderSettingsLoader();
    private final Path userHome;
    private final java.util.Map<String, String> environment;

    DefaultCliModeRunner(
            Path repositoryRoot,
            InputStream input,
            OutputStream output,
            PrintWriter printOutput,
            PrintWriter errorOutput) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        this.input = Objects.requireNonNull(input, "input 不能为空");
        this.output = Objects.requireNonNull(output, "output 不能为空");
        this.printOutput = Objects.requireNonNull(printOutput, "printOutput 不能为空");
        this.errorOutput = Objects.requireNonNull(errorOutput, "errorOutput 不能为空");
        this.userHome = Path.of(Objects.requireNonNull(System.getProperty("user.home"), "user.home 不能为空"));
        this.environment = java.util.Map.copyOf(System.getenv());
    }

    /** 包级测试 seam：固定 user home 与环境快照，避免访问宿主 credential。 */
    DefaultCliModeRunner(Path repositoryRoot, InputStream input, OutputStream output, PrintWriter printOutput,
                         PrintWriter errorOutput, Path userHome, java.util.Map<String, String> environment) {
        this.repositoryRoot=Objects.requireNonNull(repositoryRoot); this.input=Objects.requireNonNull(input);
        this.output=Objects.requireNonNull(output); this.printOutput=Objects.requireNonNull(printOutput);
        this.errorOutput=Objects.requireNonNull(errorOutput); this.userHome=Objects.requireNonNull(userHome);
        this.environment=java.util.Map.copyOf(Objects.requireNonNull(environment));
    }

    @Override
    public int runPrint(String prompt, CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        if (prompt == null
                || prompt.isBlank()
                || prompt.length() > HeadlessRuntimeSession.MAX_PROMPT_CHARS) {
            errorOutput.println("cc-java: --print prompt is empty or too long");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        }

        try {
            PreparedRun prepared = prepare(overrides);
            PrintEventSink events = new PrintEventSink(printOutput);
            try (var auth = io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources.open(
                    userHome, repositoryRoot, environment);
                 HeadlessRuntimeSession application = selectedSession(
                         auth, prepared, overrides, events,
                         (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                                 io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny())) {
                application.open();
                Thread shutdownHook = Thread.ofPlatform()
                        .name("cc-java-print-cancel")
                        .unstarted(application::cancelActive);
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                try {
                    AgentRunResult result = explicitSkill(prompt)
                            .map(application::runPrintSkill)
                            .orElseGet(() -> application.runPrint(prompt));
                    events.finish(result);
                    return exitCode(result, errorOutput);
                } finally {
                    removeShutdownHook(shutdownHook);
                }
            }
        } catch (ProviderConfigurationException exception) {
            errorOutput.println(
                    "cc-java: provider configuration invalid (" + exception.code() + "); run /connect or codej auth login");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException exception) {
            errorOutput.println("cc-java: provider/auth not ready (" + exception.code()
                    + "); run /connect or codej auth login");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (WorkspaceConfigurationException exception) {
            errorOutput.println("cc-java: workspace is not an accessible directory");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (RuntimeException exception) {
            errorOutput.println("cc-java: runtime failed");
            return CliExitCode.RUNTIME_FAILURE;
        }
    }

    private static java.util.Optional<io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation> explicitSkill(
            String prompt) {
        if (!prompt.startsWith("/") || prompt.startsWith("//")) return java.util.Optional.empty();
        int split = prompt.indexOf(' ');
        String name = split < 0 ? prompt.substring(1) : prompt.substring(1, split);
        String arguments = split < 0 ? "" : prompt.substring(split + 1).strip();
        try {
            return java.util.Optional.of(new io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation(
                    new io.github.liumaishenjian.ccjava.domain.skill.SkillId(name), arguments));
        } catch (IllegalArgumentException invalid) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public int runStdio(CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        try {
            PreparedRun prepared = prepare(overrides);
            StdioProtocolServer.ExitReason reason;
            try (var auth = io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources.open(
                    userHome, repositoryRoot, environment)) {
                try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                        (events, approvals) -> selectedSession(
                                auth, prepared, overrides, events, approvals),
                        auth.service())) {
                    reason = new StdioProtocolServer(input, output, handler).run();
                }
            }
            return reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR
                    ? CliExitCode.RUNTIME_FAILURE
                    : CliExitCode.SUCCESS;
        } catch (ProviderConfigurationException exception) {
            errorOutput.println(
                    "cc-java: provider configuration invalid (" + exception.code() + "); run /connect or codej auth login");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException exception) {
            errorOutput.println("cc-java: provider/auth not ready (" + exception.code()
                    + "); run /connect or codej auth login");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (WorkspaceConfigurationException exception) {
            errorOutput.println("cc-java: workspace is not an accessible directory");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (Exception exception) {
            errorOutput.println("cc-java: headless runtime failed");
            return CliExitCode.RUNTIME_FAILURE;
        }
    }

    @Override
    public int runStableStdio(CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        try {
            PreparedRun prepared = prepare(overrides);
            io.github.liumaishenjian.ccjava.protocol.CapabilityToken token =
                    io.github.liumaishenjian.ccjava.protocol.CapabilityToken.generate();
            errorOutput.println("cc-java: stable v1 capability token=" + token.reveal());
            errorOutput.flush();
            try (var handler = io.github.liumaishenjian.ccjava.cli.runtime.ProductionHarnessFactory
                    .openStableHandler(
                            prepared.settings(), runtimeOptions(prepared, overrides), token,
                            io.github.liumaishenjian.ccjava.cli.runtime.ProductionHarnessFactory.stableFeatures())) {
                var reason = new io.github.liumaishenjian.ccjava.cli.daemon.StableProtocolStdioServer(
                        input, output, handler).run();
                return reason == io.github.liumaishenjian.ccjava.cli.daemon.StableProtocolStdioServer.ExitReason.IO_ERROR
                        ? CliExitCode.RUNTIME_FAILURE : CliExitCode.SUCCESS;
            }
        } catch (ProviderConfigurationException | WorkspaceConfigurationException failure) {
            errorOutput.println("cc-java: stable v1 configuration invalid");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (RuntimeException failure) {
            errorOutput.println("cc-java: stable v1 runtime failed");
            return CliExitCode.RUNTIME_FAILURE;
        }
    }

    @Override
    public int runDaemon(CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        try {
            PreparedRun prepared = prepare(overrides);
            Path root = SessionStorage.defaultRoot().resolve("daemon");
            try (var ownership = io.github.liumaishenjian.ccjava.cli.daemon.DaemonOwnership.acquire(root);
                    var handler = io.github.liumaishenjian.ccjava.cli.runtime.ProductionHarnessFactory
                            .openStableHandler(prepared.settings(), runtimeOptions(prepared, overrides),
                                    ownership.token(), io.github.liumaishenjian.ccjava.cli.runtime
                                            .ProductionHarnessFactory.stableFeatures());
                    var daemon = new io.github.liumaishenjian.ccjava.cli.daemon.StableLoopbackDaemon(
                            0, ownership.token(), handler)) {
                daemon.start();
                errorOutput.println("cc-java: stable daemon port=" + daemon.port());
                errorOutput.println("cc-java: stable daemon token=" + ownership.token().reveal());
                errorOutput.flush();
                Thread hook = Thread.ofPlatform().name("cc-java-daemon-stop").unstarted(daemon::close);
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    daemon.awaitClosed();
                } finally {
                    removeShutdownHook(hook);
                }
                return CliExitCode.SUCCESS;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CliExitCode.USER_CANCELLED;
        } catch (Exception failure) {
            errorOutput.println("cc-java: stable daemon failed");
            return CliExitCode.RUNTIME_FAILURE;
        }
    }

    private HeadlessRuntimeSession selectedSession(
            io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources auth,
            PreparedRun prepared, CliOverrides overrides, io.github.liumaishenjian.ccjava.core.AgentEventSink events,
            io.github.liumaishenjian.ccjava.core.ApprovalHandler approvals) {
        java.util.Optional<io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot> selection;
        try {
            selection = auth.service().effectiveSelection();
        } catch (io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException invalidSelection) {
            selection = java.util.Optional.empty();
        }
        if (selection.isPresent() || prepared.settings() == null) {
            String model = selection.map(
                    io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot::modelId)
                    .orElse("provider-not-configured");
            HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                    prepared.workspace(), model, overrides.timeout(), overrides.permissionMode(),
                    java.util.List.of(), overrides.sessionOpenRequest(), SessionStorage.defaultRoot(),
                    overrides.contextPreparation(), overrides.diagnosticMode(), overrides.diagnosticDirectory(),
                    overrides.executionBackend(), overrides.executionShell());
            return HeadlessRuntimeSession.production(auth.modelGateway(), events, options, approvals);
        }
        return new HeadlessRuntimeSession(prepared.settings(), events, runtimeOptions(prepared, overrides), approvals);
    }
    private HeadlessRuntimeOptions runtimeOptions(PreparedRun prepared, CliOverrides overrides) {
        return new HeadlessRuntimeOptions(
                prepared.workspace(), prepared.settings().model(), overrides.timeout(),
                overrides.permissionMode(), java.util.List.of(), overrides.sessionOpenRequest(),
                SessionStorage.defaultRoot(), overrides.contextPreparation(), overrides.diagnosticMode(),
                overrides.diagnosticDirectory(), overrides.executionBackend(), overrides.executionShell());
    }

    @Override
    public int runExtensions(boolean approve, CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        try {
            Path workspace = resolveWorkspace(overrides);
            var bootstrap = io.github.liumaishenjian.ccjava.tools.local.LocalWorkspaceBootstrap.open(workspace);
            Path userHome = Path.of(Objects.requireNonNull(System.getProperty("user.home"), "user.home 不能为空"));
            var loader = new io.github.liumaishenjian.ccjava.cli.extensions.ExtensionConfigurationLoader(
                    userHome, bootstrap.workspaceGuard());
            if (approve) {
                var result = loader.approveProject();
                if (!result.successful()) {
                    errorOutput.println("cc-java: extension trust failed (" + result.code() + ")");
                    return CliExitCode.USAGE_OR_CONFIGURATION;
                }
                printOutput.println("cc-java: project extensions trusted; restart required");
                printOutput.println("fingerprint=" + result.fingerprint().orElseThrow());
                return CliExitCode.SUCCESS;
            }
            var runtime = loader.load();
            try {
                var status = runtime.status();
                printOutput.println("userLoaded=" + status.userLoaded());
                printOutput.println("projectPresent=" + status.projectPresent());
                printOutput.println("projectTrusted=" + status.projectTrusted());
                printOutput.println("hooks=" + status.hookCount());
                printOutput.println("mcpServers=" + status.mcpServerCount());
                status.projectFingerprint().ifPresent(value -> printOutput.println("projectFingerprint=" + value));
                status.diagnosticCode().ifPresent(value -> printOutput.println("diagnostic=" + value));
                runtime.mcpSnapshots().forEach(server -> printOutput.println(
                        "mcp=" + server.serverName() + ",status=" + server.status() + ",tools=" + server.toolCount()));
                return CliExitCode.SUCCESS;
            } finally {
                runtime.close();
            }
        } catch (Exception failure) {
            errorOutput.println("cc-java: extension status failed");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        }
    }

    private PreparedRun prepare(CliOverrides overrides) {
        Path workspace = resolveWorkspace(overrides);
        OpenAiCompatibleSettings settings = null;
        try {
            settings = settingsLoader.load(repositoryRoot);
            if (overrides.model().isPresent()) settings = settings.withModel(overrides.model().orElseThrow());
        } catch (ProviderConfigurationException absentOrInvalidLegacy) {
            // BYOK selection 不依赖 legacy provider.local.properties；只有未选择时才重抛。
        }
        return new PreparedRun(workspace, settings);
    }

    private Path resolveWorkspace(CliOverrides overrides) {
        try {
            if (!Files.isDirectory(overrides.workspace())) {
                throw new WorkspaceConfigurationException();
            }
            return overrides.workspace().toRealPath();
        } catch (IOException exception) {
            throw new WorkspaceConfigurationException();
        }
    }

    static int exitCode(AgentRunResult result, PrintWriter errorOutput) {
        if (result.stopReason() == StopReason.COMPLETED) {
            return CliExitCode.SUCCESS;
        }
        if (result.stopReason() == StopReason.USER_CANCELLED) {
            return CliExitCode.USER_CANCELLED;
        }
        if (result.stopReason() == StopReason.TIME_LIMIT_REACHED) {
            errorOutput.println("cc-java: run timed out");
        } else if (result.stopReason() == StopReason.OUTPUT_LIMIT_REACHED) {
            errorOutput.println("cc-java: output limit reached");
        } else {
            errorOutput.println("cc-java: run failed (" + result.stopReason() + ")");
        }
        result.modelFailure().ifPresent(summary ->
                errorOutput.println("cc-java: " + ModelFailureFormatter.format(summary)));
        return CliExitCode.RUNTIME_FAILURE;
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignoredDuringShutdown) {
            // JVM 已进入关闭序列时，Hook 正在负责传播取消，无需再次移除。
        }
    }

    private record PreparedRun(
            Path workspace,
            OpenAiCompatibleSettings settings) {
    }

    private static final class WorkspaceConfigurationException extends RuntimeException {
    }
}
