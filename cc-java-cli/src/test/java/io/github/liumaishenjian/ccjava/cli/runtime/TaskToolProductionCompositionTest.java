package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 ADR-088 Batch C 只在 durable production composition 暴露四个 Task Tool。 */
class TaskToolProductionCompositionTest {
    private static final List<String> TASK_TOOLS = List.of(
            "task_create", "task_update", "task_list", "task_get");

    @Test
    void productionRegistersExactlyFourTaskToolsAndProjectsThemToModel(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return ModelTurn.text("done");
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions"));

        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                model, AgentEventSink.noop(), options,
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                ContextPreparationService.noop(), null,
                HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")),
                null, true, WebSearchRuntimeResources.disabled())) {
            runtime.open();
            assertThat(runtime.builtinToolRegistry().definitions())
                    .filteredOn(definition -> definition.name().startsWith("task_"))
                    .extracting(definition -> definition.name())
                    .containsExactlyInAnyOrderElementsOf(TASK_TOOLS);
            assertThat(runtime.builtinToolRegistry().definitions())
                    .filteredOn(definition -> TASK_TOOLS.contains(definition.name()))
                    .allSatisfy(definition -> assertThat(definition.source()).isEqualTo(ToolSource.BUILT_IN));
            runtime.run("inspect task tools");
        }

        assertThat(requests).singleElement().satisfies(request ->
                assertThat(request.toolDefinitions())
                        .filteredOn(definition -> definition.name().startsWith("task_"))
                        .extracting(definition -> definition.name())
                        .containsExactlyInAnyOrderElementsOf(TASK_TOOLS));
    }

    @Test
    void approvedPlanReusesTaskCreatedDuringPlanningAfterListAndGetDiscovery(@TempDir Path root)
            throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-discovery"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 检查已有任务。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "task_create",
                        new JsonObject(Map.of("subject", "检查已有任务。")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "task-read", "kind", "VERIFICATION",
                                "locator", "task_get", "label", "已读取规划任务", "required", true)))));
                case 3 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 4 -> ModelTurn.text("规划完成");
                case 5 -> ModelTurn.tools(List.of(new ToolCall("list", "task_list", JsonObject.empty())));
                case 6 -> ModelTurn.tools(List.of(new ToolCall("get", "task_get",
                        new JsonObject(Map.of("task_id", "task-1")))));
                case 7 -> ModelTurn.text("已使用规划阶段创建的任务身份");
                default -> throw new IllegalStateException("任务发现流程不得额外调用模型");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-discovery"));

        try (HeadlessRuntimeSession runtime = productionRuntime(root.resolve("home-discovery"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");
            var result = runtime.runAcceptedPlan(acceptance);
            assertThat(result.stopReason()).isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED);
            assertThat(result.finalText().orElseThrow()).contains("规划阶段创建的任务身份");
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.COMPLETED);
        }
        assertThat(calls).hasValue(8);
        assertFourTaskToolsEveryTurn(requests);
        assertThat(requests.get(6).messages()).filteredOn(ToolResultMessage.class::isInstance)
                .anySatisfy(message -> assertThat(((ToolResultMessage) message).result().content())
                        .contains("task-1", "检查已有任务。"));
        assertThat(requests.get(7).messages()).filteredOn(ToolResultMessage.class::isInstance)
                .anySatisfy(message -> assertThat(((ToolResultMessage) message).result().callId()).isEqualTo("get"));
    }

    @Test
    void completedTaskCanBeListedBeforeEvidenceValidFinalResponse(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-post-completion-list"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 完成唯一任务。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "task_create",
                        new JsonObject(Map.of("subject", "完成唯一任务。")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "task-transition", "kind", "VERIFICATION",
                                "locator", "task_update", "label", "任务状态已验证", "required", true)))));
                case 3 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 4 -> ModelTurn.text("规划完成");
                case 5 -> ModelTurn.tools(List.of(new ToolCall("list-before", "task_list", JsonObject.empty())));
                case 6 -> ModelTurn.tools(List.of(new ToolCall("get", "task_get",
                        new JsonObject(Map.of("task_id", "task-1")))));
                case 7 -> ModelTurn.tools(List.of(new ToolCall("claim", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "status", "IN_PROGRESS", "active_form", "正在执行中文任务")))));
                case 8 -> ModelTurn.tools(List.of(new ToolCall("complete", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "status", "COMPLETED")))));
                case 9 -> ModelTurn.tools(List.of(new ToolCall("list-after", "task_list", JsonObject.empty())));
                case 10 -> ModelTurn.text("任务完成后仍可读取并正常结束");
                default -> throw new IllegalStateException("完成后读取流程不得额外调用模型");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-post-completion-list"));

        try (HeadlessRuntimeSession runtime = productionRuntime(
                root.resolve("home-post-completion-list"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.stopReason()).isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED);
            assertThat(result.finalText().orElseThrow()).contains("完成后仍可读取");
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.COMPLETED);
        }
        assertThat(calls).hasValue(11);
        assertFourTaskToolsEveryTurn(requests);
        assertThat(requests.get(10).messages()).filteredOn(ToolResultMessage.class::isInstance)
                .anySatisfy(message -> {
                    ToolResultMessage result = (ToolResultMessage) message;
                    assertThat(result.result().callId()).isEqualTo("list-after");
                    assertThat(result.result().content()).contains("task-1", "\"status\":\"completed\"");
                });
    }

    @Test
    void inProgressTaskIsAdvisoryAndDoesNotBlockEvidenceValidPlanCompletion(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-advisory-task"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 完成唯一任务。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "task_create",
                        new JsonObject(Map.of("subject", "完成唯一任务。")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "task-transition", "kind", "VERIFICATION",
                                "locator", "task_update", "label", "任务状态已验证", "required", true)))));
                case 3 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 4 -> ModelTurn.text("规划完成");
                case 5 -> ModelTurn.tools(List.of(new ToolCall("list", "task_list", JsonObject.empty())));
                case 6 -> ModelTurn.tools(List.of(new ToolCall("get", "task_get",
                        new JsonObject(Map.of("task_id", "task-1")))));
                case 7 -> ModelTurn.tools(List.of(new ToolCall("claim", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "status", "IN_PROGRESS", "active_form", "正在执行中文任务")))));
                case 8 -> ModelTurn.text("证据有效，Task 进度仅作为协作投影");
                default -> throw new IllegalStateException("协作 Task 不得触发额外纠正回合");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-advisory-task"));

        try (HeadlessRuntimeSession runtime = productionRuntime(root.resolve("home-advisory-task"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.stopReason()).isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED);
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.COMPLETED);
            assertThat(runtime.taskBoardSnapshot().orElseThrow().tasks().get(
                    new io.github.liumaishenjian.ccjava.domain.task.TaskId(1)).status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.task.TaskStatus.IN_PROGRESS);
        }
        assertThat(calls).hasValue(9);
        assertFourTaskToolsEveryTurn(requests);
    }

    @Test
    void completedTasksWithoutRequiredEvidenceKeepToolsAndDoNotCompletePlan(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-missing-evidence"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 完成唯一任务。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "missing-command", "kind", "VERIFICATION",
                                "locator", "run_command", "label", "命令验证成功", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 3 -> ModelTurn.text("规划完成");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("claim", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "status", "IN_PROGRESS", "active_form", "正在执行中文任务")))));
                case 5 -> ModelTurn.tools(List.of(new ToolCall("complete", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "status", "COMPLETED")))));
                case 6 -> ModelTurn.text("错误声称验证完成");
                case 7 -> ModelTurn.text("仍未补齐验证");
                default -> throw new IllegalStateException("缺失证据纠正必须有界");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-missing-evidence"));

        try (HeadlessRuntimeSession runtime = productionRuntime(root.resolve("home-missing-evidence"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");
            var result = runtime.runAcceptedPlan(acceptance);
            assertThat(result.stopReason()).isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED);
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.NEEDS_VERIFICATION);
        }
        assertThat(requests.get(6).toolDefinitions()).extracting(definition -> definition.name())
                .contains("task_update", "run_command");
        assertThat(calls).hasValue(8);
    }

    @Test
    void offlineCompositionDoesNotRegisterVolatileTaskTools(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions"));

        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                request -> ModelTurn.text("done"), AgentEventSink.noop(), options)) {
            runtime.open();
            assertThat(runtime.builtinToolRegistry().definitions())
                    .extracting(definition -> definition.name())
                    .doesNotContainAnyElementsOf(TASK_TOOLS);
        }
    }

    @Test
    void productionDelegateFreezesValidatedParentTaskScopeForIndependentChild(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path definitions = Files.createDirectories(root.resolve("home").resolve(".cc-java").resolve("agents"));
        Files.writeString(definitions.resolve("task-worker.agent"), String.join("\n",
                "id=task-worker", "description=bounded task worker", "instructions=list assigned task",
                "tools=task_list", "permission=PLAN", "model=fake-model", "max-model-turns=3",
                "max-tool-calls=3", "max-input-tokens=4000", "max-output-characters=1024",
                "timeout-seconds=10", "background=false", ""));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger rootTurns = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger childTurns = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway model = request -> {
            requests.add(request);
            boolean child = request.toolDefinitions().size() == 1
                    && request.toolDefinitions().getFirst().name().equals("task_list");
            if (child) {
                return childTurns.getAndIncrement() == 0
                        ? ModelTurn.tools(List.of(new ToolCall("child-list", "task_list", JsonObject.empty())))
                        : ModelTurn.text("child done");
            }
            return switch (rootTurns.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("root-create", "task_create",
                        new JsonObject(Map.of("subject", "delegated work")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("root-delegate", "delegate_agent",
                        new JsonObject(Map.of("definition", "task-worker", "prompt", "list assigned task",
                                "tools", List.of("task_list"), "taskIds", List.of("task-1"),
                                "maxModelTurns", 3, "maxToolCalls", 3, "maxInputTokens", 4_000L,
                                "maxOutputCharacters", 1_024, "timeoutSeconds", 10)))));
                default -> ModelTurn.text("root done");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(20), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions"));

        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                model, AgentEventSink.noop(), options,
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                ContextPreparationService.noop(), null,
                HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")),
                null, true, WebSearchRuntimeResources.disabled())) {
            runtime.open();
            assertThat(runtime.run("create then delegate one task").stopReason())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED);
        }

        assertThat(childTurns).withFailMessage(() -> "requests=" + requests.stream()
                .map(request -> request.toolDefinitions().stream().map(definition -> definition.name()).toList()
                        + " messages=" + request.messages())
                .toList()).hasValue(2);
        assertThat(requests).anySatisfy(request -> assertThat(request.messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .anySatisfy(message -> assertThat(((ToolResultMessage) message).result().content())
                        .contains("task-1", "delegated work")));
    }

    private static void assertFourTaskToolsEveryTurn(List<ModelRequest> requests) {
        assertThat(requests).allSatisfy(request -> assertThat(request.toolDefinitions())
                .filteredOn(definition -> definition.name().startsWith("task_"))
                .extracting(definition -> definition.name())
                .containsExactlyInAnyOrderElementsOf(TASK_TOOLS));
    }

    private static HeadlessRuntimeSession productionRuntime(
            Path home, ModelGateway model, HeadlessRuntimeOptions options) {
        return new HeadlessRuntimeSession(
                model, AgentEventSink.noop(), options,
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                ContextPreparationService.noop(), null,
                HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> home),
                null, true, WebSearchRuntimeResources.disabled());
    }
}
