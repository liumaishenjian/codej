package io.github.liumaishenjian.ccjava.cli.stdio;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 供跨进程测试启动的 Fake stdio 进程，不进入生产制品。
 */
public final class StdioProtocolFixtureMain {

    private StdioProtocolFixtureMain() {
    }

    /**
     * 启动确定性 Fake Server。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        boolean failed = false;
        try {
            StdioProtocol.CommandHandler handler = args.length == 1 && args[0].equals("provider-control")
                    ? providerControlHandler()
                    : args.length == 2 && args[0].equals("permission-runtime")
                            ? permissionRuntimeHandler(Path.of(args[1]))
                    : args.length == 2 && args[0].equals("plan-runtime")
                            ? planRuntimeHandler(Path.of(args[1]))
                    : args.length == 2 && args[0].equals("xlsx-plan-runtime")
                            ? xlsxPlanRuntimeHandler(Path.of(args[1]))
                    : args.length == 2 && args[0].equals("task-timeout-runtime")
                            ? taskTimeoutRuntimeHandler(Path.of(args[1]))
                    : args.length == 2 && args[0].equals("task-runtime")
                            ? taskRuntimeHandler(Path.of(args[1]))
                            : new FakeStdioCommandHandler(List.of("alpha ", "beta"), Duration.ofMillis(250));
            StdioProtocolServer.ExitReason reason =
                    new StdioProtocolServer(System.in, System.out, handler).run();
            failed = reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR;
        } catch (Exception exception) {
            failed = true;
        }
        if (failed) System.exit(2);
    }

    private static StdioProtocol.CommandHandler planRuntimeHandler(Path parent) throws Exception {
        Path expectedParent = parent.toAbsolutePath().normalize();
        Path expectedRealParent = expectedParent.toRealPath();
        Path fixtureRoot = Files.createTempDirectory(expectedRealParent, "plan-runtime-");
        try {
            Path workspace = Files.createDirectory(fixtureRoot.resolve("workspace"));
            initializeGitRepository(workspace);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger executionCalls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean directExecution = new java.util.concurrent.atomic.AtomicBoolean();
            String markdown = "# 跨进程实施计划\n\n## 拟定步骤\n1. 生成精确命名的河南天气工作簿。\n";
            String revisedMarkdown = "# 跨进程实施计划\n\n## 拟定步骤\n1. 生成精确命名的河南天气工作簿。\n2. 验证纠正后的工作簿与回滚结果。\n";
            String expectedWorkbook = "河南各市7天天气.xlsx";
            String wrongWorkbook = "河南各市7天天气预报.xlsx";
            io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> {
                String latestUser = request.messages().stream()
                        .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                        .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                        .map(io.github.liumaishenjian.ccjava.domain.UserMessage::content)
                        .reduce((previous, current) -> current)
                        .orElse("");
                if (directExecution.get() && latestUser.contains("普通输入")) {
                    return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("follow-up completed");
                }
                boolean executing = directExecution.get()
                        || latestUser.contains("Implement the approved plan");
                boolean secondStepApproved = request.messages().stream().anyMatch(message ->
                        message instanceof io.github.liumaishenjian.ccjava.domain.UserMessage user
                                && user.content().contains("验证纠正后的工作簿与回滚结果")
                        || message instanceof io.github.liumaishenjian.ccjava.domain.SystemMessage system
                                && system.content().contains("验证纠正后的工作簿与回滚结果"));
                if (executing) {
                    directExecution.set(true);
                    int executionCall = executionCalls.getAndIncrement();
                    if (request.toolDefinitions().stream().anyMatch(tool -> tool.name().equals("task_create"))) {
                        throw new IllegalStateException("批准 Plan execution 不得暴露 task_create");
                    }
                    if (executionCall == 0) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "execution-task-claim-1", "task_update",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "task_id", "task-1", "status", "IN_PROGRESS",
                                            "active_form", "正在生成正确工作簿")))));
                    if (executionCall == 1) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "wrong-workbook", "write_file",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "path", wrongWorkbook, "content", "wrong-name")))));
                    if (executionCall == 2) {
                        return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("FIRST_UNVERIFIED_FINAL");
                    }
                    if (executionCall == 3) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "correct-workbook", "write_file",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "path", expectedWorkbook, "content", "correct-name")))));
                    if (executionCall == 4) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "execution-task-complete-1", "task_update",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "task_id", "task-1", "status", "COMPLETED")))));
                    if (executionCall == 5 && !secondStepApproved) {
                        if (!request.toolDefinitions().isEmpty()) {
                            throw new IllegalStateException("Plan final-only 回合仍暴露 Tool definition");
                        }
                        return io.github.liumaishenjian.ccjava.domain.ModelTurn.text(
                                "approved plan corrected and verified");
                    }
                    if (executionCall == 5) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "execution-task-claim-2", "task_update",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "task_id", "task-2", "status", "IN_PROGRESS",
                                            "active_form", "正在验证交付结果")))));
                    if (executionCall == 6) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "execution-task-complete-2", "task_update",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "task_id", "task-2", "status", "COMPLETED")))));
                    if (executionCall == 7) {
                        if (!request.toolDefinitions().isEmpty()) {
                            throw new IllegalStateException("Plan final-only 回合仍暴露 Tool definition");
                        }
                        return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("approved plan corrected and verified");
                    }
                    throw new IllegalStateException("Plan fixture 收到过多执行请求");
                }
                if (directExecution.get()) {
                    return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("follow-up completed");
                }
                int call = calls.getAndIncrement();
                if (call == 0) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-update", "revise_plan_artifact",
                                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of("markdown", markdown)))));
                if (call == 1) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-evidence", "declare_plan_evidence",
                                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                        "requirementId", "weather-xlsx", "kind", "DELIVERABLE", "locator", expectedWorkbook,
                                        "label", "exact weather workbook", "required", true)))));
                if (call == 2) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-review", "request_plan_review",
                                io.github.liumaishenjian.ccjava.domain.JsonObject.empty())));
                if (call == 3) return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("planning finished");
                if (call == 4) {
                    boolean feedbackReachedModel = request.messages().stream()
                            .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                            .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                            .anyMatch(message -> message.content().equals("add rollback verification"));
                    if (!feedbackReachedModel) throw new IllegalStateException("Plan feedback 未进入新的模型回合");
                    return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                            new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                    "plan-revise-feedback", "revise_plan_artifact",
                                    new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                            "markdown", revisedMarkdown)))));
                }
                if (call == 5) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                "plan-review-feedback", "request_plan_review",
                                io.github.liumaishenjian.ccjava.domain.JsonObject.empty())));
                if (call == 6) return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("replanning finished");
                throw new IllegalStateException("Plan fixture 收到过多规划请求");
            };
            Path providerRoot = Files.createDirectory(fixtureRoot.resolve("provider"));
            Path providerHome = Files.createDirectory(providerRoot.resolve("home"));
            Path providerRepository = Files.createDirectory(providerRoot.resolve("repository"));
            var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(providerHome);
            var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(providerHome);
            var providerAuth = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                    definitions, credentials,
                    new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                            new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(
                                    providerRepository),
                            definitions, credentials),
                    Map.of("CC_JAVA_PLAN_FIXTURE_KEY", "fixture-provider-sentinel"));
            providerAuth.login(
                    new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                            "anthropic", "fixture", io.github.liumaishenjian.ccjava.cli.runtime
                                    .ProviderAuthApplicationService.RefKind.ENV,
                            "CC_JAVA_PLAN_FIXTURE_KEY", true),
                    null, io.github.liumaishenjian.ccjava.core.CancellationToken.none());
            providerAuth.addModel("anthropic", "fixture-model", true,
                    io.github.liumaishenjian.ccjava.core.CancellationToken.none());
            Path sessionStore = Files.createDirectory(fixtureRoot.resolve("sessions"));
            RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler((events, approvals) ->
                    io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                            model, events,
                            new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                    workspace.toAbsolutePath().normalize(),
                                    "fixture-model",
                                    Duration.ofSeconds(5),
                                    io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT,
                                    List.of(),
                                    io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest.create(),
                                    sessionStore),
                            approvals), providerAuth);
            return ownedFixtureHandler(delegate, expectedParent, expectedRealParent, fixtureRoot,
                    "plan-runtime-");
        } catch (Exception failure) {
            try {
                deleteFixtureTree(expectedParent, expectedRealParent, fixtureRoot, "plan-runtime-");
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 为五步中文批准 Plan 建立真实 run_command → OpenXML 工作簿的跨进程 Fixture。 */
    private static StdioProtocol.CommandHandler xlsxPlanRuntimeHandler(Path parent) throws Exception {
        Path expectedParent = parent.toAbsolutePath().normalize();
        Path expectedRealParent = expectedParent.toRealPath();
        Path fixtureRoot = Files.createTempDirectory(expectedRealParent, "xlsx-plan-runtime-");
        try {
            Path workspace = Files.createDirectory(fixtureRoot.resolve("workspace"));
            initializeGitRepository(workspace);
            Path sessionStore = Files.createDirectory(fixtureRoot.resolve("sessions"));
            String workbook = "河南各市7天天气.xlsx";
            String markdown = """
                    # 河南天气工作簿交付计划

                    ## 实施步骤
                    1. 创建独立的工作簿生成器。
                    2. 生成真实的河南天气 XLSX 文件。
                    3. 校验 OpenXML 工作簿结构与中文数据。
                    4. 执行长耗时质量检查。
                    5. 汇总真实交付与验证证据。
                    """;
            String javaCommand = fixtureJavaCommand(WorkbookMakerFixtureMain.class);
            java.util.concurrent.atomic.AtomicInteger planningCalls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger executionCalls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean();
            io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> {
                String latestUser = request.messages().stream()
                        .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                        .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                        .map(io.github.liumaishenjian.ccjava.domain.UserMessage::content)
                        .reduce((left, right) -> right).orElse("");
                if (executing.get() || latestUser.contains("Implement the approved plan")) {
                    executing.set(true);
                    int call = executionCalls.getAndIncrement();
                    if (request.toolDefinitions().stream().anyMatch(tool -> tool.name().equals("task_create"))) {
                        throw new IllegalStateException("批准 Plan execution 不得暴露 task_create");
                    }
                    return switch (call) {
                        case 0 -> approvedTaskUpdate("xlsx-claim-1", "task-1", "IN_PROGRESS", "正在创建工作簿生成器");
                        case 1 -> tool("xlsx-generator", "write_file", Map.of(
                                "path", "workbook-request.json",
                                "content", "{\"province\":\"河南\",\"days\":7,\"format\":\"xlsx\"}\n"));
                        case 2 -> approvedTaskUpdate("xlsx-complete-1", "task-1", "COMPLETED", null);
                        case 3 -> approvedTaskUpdate("xlsx-claim-2", "task-2", "IN_PROGRESS", "正在生成126条天气记录");
                        case 4 -> tool("xlsx-generate", "run_command", Map.of(
                                "command", javaCommand + " generate " + shellQuote(workbook), "timeoutSeconds", 20));
                        case 5 -> approvedTaskUpdate("xlsx-complete-2", "task-2", "COMPLETED", null);
                        case 6 -> approvedTaskUpdate("xlsx-claim-3", "task-3", "IN_PROGRESS", "正在校验OpenXML结构");
                        case 7 -> tool("xlsx-verify", "run_command", Map.of(
                                "command", javaCommand + " verify " + shellQuote(workbook), "timeoutSeconds", 20));
                        case 8 -> approvedTaskUpdate("xlsx-complete-3", "task-3", "COMPLETED", null);
                        case 9 -> approvedTaskUpdate("xlsx-claim-4", "task-4", "IN_PROGRESS", "正在执行长耗时质量检查");
                        case 10 -> tool("xlsx-slow-verify", "run_command", Map.of(
                                "command", javaCommand + " slow-verify " + shellQuote(workbook), "timeoutSeconds", 20));
                        case 11 -> approvedTaskUpdate("xlsx-complete-4", "task-4", "COMPLETED", null);
                        case 12 -> approvedTaskUpdate("xlsx-claim-5", "task-5", "IN_PROGRESS", "正在汇总交付证据");
                        case 13 -> approvedTaskUpdate("xlsx-complete-5", "task-5", "COMPLETED", null);
                        case 14 -> {
                            if (!request.toolDefinitions().isEmpty()) {
                                throw new IllegalStateException("全部 Task 与 Evidence 满足后必须进入 final-only");
                            }
                            yield io.github.liumaishenjian.ccjava.domain.ModelTurn.text(
                                    "河南天气工作簿已真实生成并通过 OpenXML 与长耗时检查");
                        }
                        default -> throw new IllegalStateException("XLSX Plan fixture 收到过多执行请求");
                    };
                }
                return switch (planningCalls.getAndIncrement()) {
                    case 0 -> tool("xlsx-plan", "revise_plan_artifact", Map.of("markdown", markdown));
                    case 1 -> tool("xlsx-deliverable", "declare_plan_evidence", Map.of(
                            "requirementId", "xlsx-file", "kind", "DELIVERABLE", "locator", workbook,
                            "label", "real OpenXML workbook", "required", true));
                    case 2 -> tool("xlsx-verification", "declare_plan_evidence", Map.of(
                            "requirementId", "xlsx-check", "kind", "VERIFICATION", "locator", "run_command",
                            "label", "OpenXML verification command", "required", true));
                    case 3 -> tool("xlsx-review", "request_plan_review", Map.of());
                    case 4 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("planning finished");
                    default -> throw new IllegalStateException("XLSX Plan fixture 收到过多规划请求");
                };
            };
            RuntimeStdioCommandHandler delegate = fixtureRuntimeHandler(
                    model, workspace, sessionStore, Duration.ofSeconds(30), fixtureRoot, "XLSX");
            return ownedFixtureHandler(delegate, expectedParent, expectedRealParent, fixtureRoot,
                    "xlsx-plan-runtime-");
        } catch (Exception failure) {
            try {
                deleteFixtureTree(expectedParent, expectedRealParent, fixtureRoot, "xlsx-plan-runtime-");
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 为真实 run_command 超时后的 Run/Task 一致性建立跨进程 Fixture。 */
    private static StdioProtocol.CommandHandler taskTimeoutRuntimeHandler(Path parent) throws Exception {
        Path expectedParent = parent.toAbsolutePath().normalize();
        Path expectedRealParent = expectedParent.toRealPath();
        Path fixtureRoot = Files.createTempDirectory(expectedRealParent, "task-timeout-runtime-");
        try {
            Path workspace = Files.createDirectory(fixtureRoot.resolve("workspace"));
            initializeGitRepository(workspace);
            Path sessionStore = Files.createDirectory(fixtureRoot.resolve("sessions"));
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            String timeoutCommand = isWindows() ? "Start-Sleep -Seconds 3" : "sleep 3";
            io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> switch (calls.getAndIncrement()) {
                case 0 -> tool("timeout-create", "task_create", Map.of(
                        "subject", "执行超时命令", "active_form", "等待长耗时命令"));
                case 1 -> taskUpdate("timeout-claim", "task-1", "CLAIM", 1, null);
                case 2 -> tool("timeout-command", "run_command", Map.of(
                        "command", timeoutCommand, "timeoutSeconds", 1));
                case 3 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("命令已超时，任务保持待恢复");
                default -> throw new IllegalStateException("Task timeout fixture 收到过多模型请求");
            };
            RuntimeStdioCommandHandler delegate = fixtureRuntimeHandler(
                    model, workspace, sessionStore, Duration.ofSeconds(10), fixtureRoot, "TIMEOUT");
            return ownedFixtureHandler(delegate, expectedParent, expectedRealParent, fixtureRoot,
                    "task-timeout-runtime-");
        } catch (Exception failure) {
            try {
                deleteFixtureTree(expectedParent, expectedRealParent, fixtureRoot, "task-timeout-runtime-");
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static io.github.liumaishenjian.ccjava.domain.ModelTurn approvedTaskUpdate(
            String callId, String taskId, String status, String activeForm) {
        java.util.LinkedHashMap<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("task_id", taskId);
        arguments.put("status", status);
        if (activeForm != null) arguments.put("active_form", activeForm);
        return tool(callId, "task_update", arguments);
    }

    private static io.github.liumaishenjian.ccjava.domain.ModelTurn taskUpdate(
            String callId, String taskId, String operation, long revision, Integer claimEpoch) {
        java.util.LinkedHashMap<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("task_id", taskId);
        arguments.put("operation", operation);
        arguments.put("expected_task_revision", revision);
        if (claimEpoch != null) {
            arguments.put("target_status", "COMPLETED");
            arguments.put("expected_claim_epoch", claimEpoch);
        }
        return tool(callId, "task_update", arguments);
    }

    private static io.github.liumaishenjian.ccjava.domain.ModelTurn tool(
            String callId, String name, Map<String, ?> arguments) {
        return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                new io.github.liumaishenjian.ccjava.domain.ToolCall(callId, name,
                        new io.github.liumaishenjian.ccjava.domain.JsonObject(arguments))));
    }

    private static RuntimeStdioCommandHandler fixtureRuntimeHandler(
            io.github.liumaishenjian.ccjava.core.ModelGateway model, Path workspace, Path sessionStore,
            Duration timeout, Path fixtureRoot, String prefix) throws Exception {
        Path providerHome = Files.createDirectories(fixtureRoot.resolve("provider/home"));
        Path providerRepository = Files.createDirectories(fixtureRoot.resolve("provider/repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(providerHome);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(providerHome);
        var providerAuth = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials,
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                        new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(providerRepository),
                        definitions, credentials),
                Map.of("CC_JAVA_" + prefix + "_FIXTURE_KEY", "fixture-provider-sentinel"));
        providerAuth.login(new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                        "anthropic", "fixture", io.github.liumaishenjian.ccjava.cli.runtime
                        .ProviderAuthApplicationService.RefKind.ENV,
                        "CC_JAVA_" + prefix + "_FIXTURE_KEY", true), null,
                io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        providerAuth.addModel("anthropic", "fixture-model", true,
                io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        return new RuntimeStdioCommandHandler((events, approvals) ->
                io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                        model, events, new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                workspace.toAbsolutePath().normalize(), "fixture-model", timeout,
                                io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT, List.of(),
                                io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest.create(), sessionStore),
                        approvals), providerAuth);
    }

    private static String fixtureJavaCommand(Class<?> mainClass) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Path fixtureClasses = Path.of(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
        return (isWindows() ? "& " : "") + shellQuote(java.toString()) + " -cp "
                + shellQuote(fixtureClasses.toString()) + " " + shellQuote(mainClass.getName());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** 为普通复杂任务建立真实 durable Task Tool → stdio snapshot 的跨进程 Fixture。 */
    private static StdioProtocol.CommandHandler taskRuntimeHandler(Path parent) throws Exception {
        Path expectedParent = parent.toAbsolutePath().normalize();
        Path expectedRealParent = expectedParent.toRealPath();
        Path fixtureRoot = Files.createTempDirectory(expectedRealParent, "task-runtime-");
        try {
            Path workspace = Files.createDirectory(fixtureRoot.resolve("workspace"));
            initializeGitRepository(workspace);
            Path sessionStore = Files.createDirectory(fixtureRoot.resolve("sessions"));
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> switch (calls.getAndIncrement()) {
                case 0 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                "task-create", "task_create",
                                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                        "subject", "完成真实 Task 闭环", "active_form", "验证实时面板")))));
                case 1 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                "task-claim", "task_update",
                                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                        "task_id", "task-1", "operation", "CLAIM",
                                        "expected_task_revision", 1)))));
                case 2 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                "task-complete", "task_update",
                                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                        "task_id", "task-1", "operation", "TRANSITION",
                                        "expected_task_revision", 2, "target_status", "COMPLETED",
                                        "expected_claim_epoch", 1)))));
                case 3 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("task lifecycle verified");
                default -> throw new IllegalStateException("Task fixture 收到过多模型请求");
            };
            Path providerRoot = Files.createDirectory(fixtureRoot.resolve("provider"));
            Path providerHome = Files.createDirectory(providerRoot.resolve("home"));
            Path providerRepository = Files.createDirectory(providerRoot.resolve("repository"));
            var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(providerHome);
            var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(providerHome);
            var providerAuth = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                    definitions, credentials,
                    new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                            new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(
                                    providerRepository), definitions, credentials),
                    Map.of("CC_JAVA_TASK_FIXTURE_KEY", "fixture-provider-sentinel"));
            providerAuth.login(
                    new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                            "anthropic", "fixture", io.github.liumaishenjian.ccjava.cli.runtime
                                    .ProviderAuthApplicationService.RefKind.ENV,
                            "CC_JAVA_TASK_FIXTURE_KEY", true),
                    null, io.github.liumaishenjian.ccjava.core.CancellationToken.none());
            providerAuth.addModel("anthropic", "fixture-model", true,
                    io.github.liumaishenjian.ccjava.core.CancellationToken.none());
            RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler((events, approvals) ->
                    io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                            model, events,
                            new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                    workspace.toAbsolutePath().normalize(), "fixture-model", Duration.ofSeconds(5),
                                    io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT, List.of(),
                                    io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest.create(),
                                    sessionStore), approvals), providerAuth);
            return ownedFixtureHandler(delegate, expectedParent, expectedRealParent, fixtureRoot,
                    "task-runtime-");
        } catch (Exception failure) {
            try {
                deleteFixtureTree(expectedParent, expectedRealParent, fixtureRoot, "task-runtime-");
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 使用固定 argv 为 Plan E2E 创建一个最小、独立的本地 Git Workspace。 */
    private static void initializeGitRepository(Path workspace) throws Exception {
        Process process = new ProcessBuilder("git", "init", "--quiet", workspace.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new java.io.IOException("Plan fixture Git 初始化超时");
        }
        if (process.exitValue() != 0) {
            throw new java.io.IOException("Plan fixture Git 初始化失败");
        }
    }

    static StdioProtocol.CommandHandler planRuntimeHandlerForTest(Path workspace) throws Exception {
        return planRuntimeHandler(workspace);
    }

    /** 为 TUI→真实 Java 权限测试提供三个同 source/selector Patch 的确定性 Runtime。 */
    private static StdioProtocol.CommandHandler permissionRuntimeHandler(Path parent) throws Exception {
        Path expectedParent = parent.toAbsolutePath().normalize();
        Path expectedRealParent = expectedParent.toRealPath();
        Path workspace = Files.createTempDirectory(expectedRealParent, "permission-runtime-");
        Path target = workspace.resolve("permission-e2e.txt");
        Files.writeString(target, "old" + System.lineSeparator());
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("read-before-patch", "read_file",
                            new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                    "path", "permission-e2e.txt")))));
            case 1 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    patch("patch-once", "old", "middle")));
            case 2 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    patch("patch-session", "middle", "session")));
            case 3 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    patch("patch-session-reused", "session", "new")));
            default -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("permission fixture completed");
        };
        RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler((events, approvals) ->
                new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                        model, events,
                        new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                workspace.toAbsolutePath().normalize(), "fixture-model", Duration.ofSeconds(5)),
                        approvals));
        return ownedFixtureHandler(delegate, expectedParent, expectedRealParent, workspace,
                "permission-runtime-");
    }

    private static io.github.liumaishenjian.ccjava.domain.ToolCall patch(
            String id, String oldText, String newText) {
        return new io.github.liumaishenjian.ccjava.domain.ToolCall(id, "apply_patch",
                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                        "path", "permission-e2e.txt", "oldText", oldText, "newText", newText)));
    }

    private static StdioProtocol.CommandHandler ownedFixtureHandler(
            RuntimeStdioCommandHandler delegate, Path parent, Path target, String expectedPrefix)
            throws java.io.IOException {
        Path expectedParent = parent.toAbsolutePath().normalize();
        return ownedFixtureHandler(delegate, expectedParent, expectedParent.toRealPath(), target, expectedPrefix);
    }

    private static StdioProtocol.CommandHandler ownedFixtureHandler(
            RuntimeStdioCommandHandler delegate,
            Path expectedParent,
            Path expectedRealParent,
            Path target,
            String expectedPrefix) {
        return new StdioProtocol.CommandHandler() {
            @Override public StdioProtocol.Disposition handle(
                    StdioProtocol.Command command, StdioProtocol.EventEmitter events)
                    throws StdioProtocolException {
                return delegate.handle(command, events);
            }

            @Override public void close() throws Exception {
                try {
                    delegate.close();
                } finally {
                    deleteFixtureTree(expectedParent, expectedRealParent, target, expectedPrefix);
                }
            }
        };
    }

    /**
     * 删除测试 Fixture 前重新证明目标仍是预期父目录内无链接歧义的专用临时目录。
     *
     * @param expectedParent 创建前固定的调用方父目录归一化路径
     * @param expectedRealParent 创建前固定的调用方父目录 real path
     * @param target 待删除的 Fixture 目录
     * @param expectedPrefix 由调用点固定的临时目录名前缀
     * @throws java.io.IOException 目标不安全或删除失败时；安全校验失败绝不吞掉
     */
    static void deleteFixtureTree(
            Path expectedParent,
            Path expectedRealParent,
            Path target,
            String expectedPrefix) throws java.io.IOException {
        Path normalizedParent = expectedParent.toAbsolutePath().normalize();
        Path realParent = expectedRealParent.toAbsolutePath().normalize();
        if (!normalizedParent.equals(expectedParent) || !realParent.equals(expectedRealParent)
                || !normalizedParent.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(realParent)
                || isLinkOrReparse(normalizedParent)) {
            throw new java.io.IOException("Fixture parent identity changed");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        String fileName = normalizedTarget.getFileName() == null ? "" : normalizedTarget.getFileName().toString();
        Path targetParent = normalizedTarget.getParent();
        if (!normalizedParent.equals(targetParent) || normalizedTarget.equals(normalizedParent)
                || expectedPrefix == null || expectedPrefix.isBlank() || !fileName.startsWith(expectedPrefix)
                || isLinkOrReparse(normalizedTarget)) {
            throw new java.io.IOException("Unsafe fixture cleanup target");
        }
        Path realTarget = normalizedTarget.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realTarget.getParent().equals(realParent) || !realTarget.equals(normalizedTarget)) {
            throw new java.io.IOException("Ambiguous fixture cleanup target");
        }
        java.util.List<Path> entries;
        try (var paths = Files.walk(normalizedTarget)) {
            entries = paths.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) {
            BasicFileAttributes attributes = Files.readAttributes(
                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path realEntry = entry.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (isLinkOrReparse(entry) || (!attributes.isDirectory() && !attributes.isRegularFile())
                    || !realEntry.startsWith(realTarget)) {
                throw new java.io.IOException("Fixture cleanup contains a link or escape");
            }
        }
        for (Path entry : entries) Files.delete(entry);
    }

    private static boolean isLinkOrReparse(Path path) throws java.io.IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes basic = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (basic.isOther()) return true;
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        try {
            return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    static StdioProtocol.CommandHandler providerControlHandlerForTest() throws Exception {
        return providerControlHandler();
    }

    private static StdioProtocol.CommandHandler providerControlHandler() throws Exception {
        Path root = Files.createTempDirectory("cc-java-provider-control-fixture-");
        Path home = Files.createDirectory(root.resolve("home"));
        Path repository = Files.createDirectory(root.resolve("repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(home);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(home);
        var migration = new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(repository),
                definitions, credentials);
        var service = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials, migration, Map.of("CC_JAVA_FIXTURE_KEY", "fixture-provider-sentinel"));
        service.login(new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                "anthropic", "fixture", io.github.liumaishenjian.ccjava.cli.runtime
                        .ProviderAuthApplicationService.RefKind.ENV, "CC_JAVA_FIXTURE_KEY", true),
                null, io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        var application = new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                ignored -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("unused"),
                io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                        repository, "fixture-model", Duration.ofSeconds(5)));
        RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler(application, service);
        return ownedFixtureHandler(delegate, root.getParent(), root,
                "cc-java-provider-control-fixture-");
    }
}
