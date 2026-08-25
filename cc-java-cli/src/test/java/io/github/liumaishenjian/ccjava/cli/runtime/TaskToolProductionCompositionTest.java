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
}
