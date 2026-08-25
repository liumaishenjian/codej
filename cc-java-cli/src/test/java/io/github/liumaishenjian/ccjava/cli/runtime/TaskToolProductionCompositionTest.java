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
    void approvedPlanConvergesToSingleFinalOnlyTurnAfterTasksAndEvidenceComplete(@TempDir Path root)
            throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-final-only"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 完成中文任务并验证。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "task-transition", "kind", "VERIFICATION",
                                "locator", "task_update", "label", "任务状态已验证", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 3 -> ModelTurn.text("规划完成");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("claim", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "operation", "CLAIM", "expected_task_revision", 1)))));
                case 5 -> ModelTurn.tools(List.of(new ToolCall("complete", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "operation", "TRANSITION", "expected_task_revision", 2,
                        "target_status", "COMPLETED", "expected_claim_epoch", 1)))));
                case 6 -> ModelTurn.text("中文任务与验证均已完成");
                default -> throw new IllegalStateException("final-only 后不得继续模型循环");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-final-only"));

        try (HeadlessRuntimeSession runtime = productionRuntime(root.resolve("home-final-only"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");
            var result = runtime.runAcceptedPlan(acceptance);
            assertThat(result.stopReason()).isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED);
            assertThat(result.finalText()).contains("中文任务与验证均已完成");
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.COMPLETED);
        }
        assertThat(calls).hasValue(7);
        assertThat(requests.getLast().toolDefinitions()).isEmpty();
    }

    @Test
    void finalOnlyTurnRejectsModelToolCallWithoutExecutingIt(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-final-only-reject"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 完成唯一任务。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "task-transition", "kind", "VERIFICATION",
                                "locator", "task_update", "label", "任务状态已验证", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 3 -> ModelTurn.text("规划完成");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("claim", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "operation", "CLAIM", "expected_task_revision", 1)))));
                case 5 -> ModelTurn.tools(List.of(new ToolCall("complete", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "operation", "TRANSITION", "expected_task_revision", 2,
                        "target_status", "COMPLETED", "expected_claim_epoch", 1)))));
                case 6 -> ModelTurn.tools(List.of(new ToolCall("forbidden-list", "task_list", JsonObject.empty())));
                default -> throw new IllegalStateException("违反 final-only 后不得继续模型循环");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-final-only-reject"));

        try (HeadlessRuntimeSession runtime = productionRuntime(
                root.resolve("home-final-only-reject"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.stopReason())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.INVALID_MODEL_RESPONSE);
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.FAILED);
            assertThat(result.toolCalls()).isEqualTo(2);
        }
        assertThat(calls).hasValue(7);
        assertThat(requests.getLast().toolDefinitions()).isEmpty();
    }

    @Test
    void incompleteAuthoritativeTaskCannotBeClosedByRepeatedFinalText(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-incomplete-task"));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String markdown = "# 中文执行计划\n\n## 拟定步骤\n1. 完成唯一任务。\n";
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "task-transition", "kind", "VERIFICATION",
                                "locator", "task_update", "label", "任务状态已验证", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 3 -> ModelTurn.text("规划完成");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("claim", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "operation", "CLAIM", "expected_task_revision", 1)))));
                case 5 -> ModelTurn.text("任务已经完成");
                case 6 -> ModelTurn.text("再次声称任务已经完成");
                default -> throw new IllegalStateException("未完成 Task 的纠正必须有界");
            };
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), root.resolve("sessions-incomplete-task"));

        try (HeadlessRuntimeSession runtime = productionRuntime(root.resolve("home-incomplete-task"), model, options)) {
            runtime.open();
            runtime.runPlan("制定中文执行计划");
            var awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(),
                    io.github.liumaishenjian.ccjava.domain.PlanReviewDecision.APPROVE_USER,
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.stopReason())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.INVALID_MODEL_RESPONSE);
            assertThat(runtime.planArtifact().orElseThrow().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.PlanStatus.FAILED);
            assertThat(runtime.taskBoardSnapshot().orElseThrow().tasks().get(
                    new io.github.liumaishenjian.ccjava.domain.task.TaskId(1)).status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.task.TaskStatus.IN_PROGRESS);
        }
        assertThat(calls).hasValue(7);
        assertThat(requests.getLast().toolDefinitions())
                .extracting(definition -> definition.name()).contains("task_update");
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
                        "task_id", "task-1", "operation", "CLAIM", "expected_task_revision", 1)))));
                case 5 -> ModelTurn.tools(List.of(new ToolCall("complete", "task_update", new JsonObject(Map.of(
                        "task_id", "task-1", "operation", "TRANSITION", "expected_task_revision", 2,
                        "target_status", "COMPLETED", "expected_claim_epoch", 1)))));
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
