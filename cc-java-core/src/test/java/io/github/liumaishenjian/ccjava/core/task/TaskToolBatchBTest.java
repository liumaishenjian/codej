package io.github.liumaishenjian.ccjava.core.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.hook.*;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR-088 Batch B 的 Tool schema、解析、Pipeline、Hook、Permission 与防伪回归。 */
class TaskToolBatchBTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void definitionsFreezeExactNamesEffectsSourcesAndClosedSchemas() {
        Fixture fixture = fixture();
        List<AgentTool> tools = fixture.tools();
        assertThat(tools).extracting(tool -> tool.definition().name())
                .containsExactly("task_create", "task_update", "task_list", "task_get");
        assertThat(tools).extracting(tool -> tool.definition().effect())
                .containsExactly(ToolEffect.WRITE_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE,
                        ToolEffect.READ_SESSION_STATE, ToolEffect.READ_SESSION_STATE);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.definition().source()).isEqualTo(ToolSource.BUILT_IN);
            assertThat(tool.definition().inputSchemaJson()).contains("\"additionalProperties\":false");
            assertThat(tool.definition().outputMediaType()).isEqualTo("application/json");
        });
        assertThat(tools.get(0).definition().inputSchemaJson())
                .contains("\"required\":[\"subject\"]")
                .doesNotContain("board_id", "actor_id", "session_id", "run_id", "owner", "status\"");
        assertThat(tools.get(1).definition().inputSchemaJson())
                .contains("\"required\":[\"task_id\"]", "\"status\"", "\"active_form\"",
                        "\"add_blocked_by\"", "\"remove_blocked_by\"", "\"DELETED\"")
                .doesNotContain("operation", "expected_task_revision", "expected_board_revision",
                        "expected_claim_epoch", "run_id", "board_id", "actor_run_id");
        assertThat(tools.get(2).definition().inputSchemaJson()).contains("\"maximum\":50");
        assertThat(tools.get(2).definition().maxOutputCharacters()).isEqualTo(16_384);
        assertThat(tools.get(3).definition().maxOutputCharacters()).isEqualTo(16_384);
    }

    @Test
    void updateExposesOnlySimpleClosedFields() {
        Fixture fixture = fixture();
        TaskUpdateTool tool = fixture.update();
        assertThat(fixture.create().validate(json("subject", "task", "active_form", null)).valid())
                .isFalse();
        assertThat(tool.validate(json("task_id", "task-1")).valid()).isFalse();
        assertThat(tool.validate(json("task_id", "task-1", "status", "IN_PROGRESS")).valid()).isTrue();
        assertThat(tool.validate(json("task_id", "task-1", "active_form", null)).valid()).isTrue();
        assertThat(tool.validate(json("task_id", "task-1", "add_blocked_by", List.of("task-2"))).valid())
                .isTrue();
        assertThat(tool.validate(json("task_id", "task-1", "status", "UNKNOWN")).valid()).isFalse();
        assertThat(tool.validate(json("task_id", "task-01", "status", "PENDING")).valid()).isFalse();
        assertThat(tool.validate(json("task_id", "task-1", "operation", "CLAIM")).valid()).isFalse();
        assertThat(tool.validate(json("task_id", "task-1", "expected_task_revision", 1,
                "status", "IN_PROGRESS")).valid()).isFalse();
        assertThat(fixture.create().validate(json("subject", "task", "blocked_by",
                java.util.stream.LongStream.rangeClosed(1, 33).mapToObj(value -> "task-" + value).toList())).valid())
                .isFalse();
    }

    @Test
    void simpleUpdateSupportsStatusDependencyDeleteAndUnknownTask() {
        Fixture fixture = fixture();
        fixture.execute("create-1", TaskCreateTool.NAME,
                json("subject", "first", "metadata", Map.of("keep", true)));
        fixture.execute("create-2", TaskCreateTool.NAME, json("subject", "second"));
        fixture.execute("create-3", TaskCreateTool.NAME, json("subject", "obsolete"));

        ToolResult dependency = fixture.execute("dependency", TaskUpdateTool.NAME,
                json("task_id", "task-2", "add_blocked_by", List.of("task-1")));
        assertThat(dependency.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(fixture.service().snapshot().task(new TaskId(2)).orElseThrow().blockedBy())
                .containsExactly(new TaskId(1));

        ToolResult claimed = fixture.execute("claim", TaskUpdateTool.NAME,
                json("task_id", "task-1", "status", "IN_PROGRESS", "active_form", "working"));
        ToolResult completed = fixture.execute("complete", TaskUpdateTool.NAME,
                json("task_id", "task-1", "status", "COMPLETED", "active_form", null));
        assertThat(claimed.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(completed.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(fixture.service().snapshot().task(new TaskId(1)).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(fixture.service().snapshot().task(new TaskId(1)).orElseThrow().activeForm()).isEmpty();

        ToolResult deleted = fixture.execute("delete", TaskUpdateTool.NAME,
                json("task_id", "task-3", "status", "DELETED"));
        assertThat(deleted.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(fixture.service().snapshot().task(new TaskId(3))).isEmpty();

        ToolResult unknown = fixture.execute("unknown", TaskUpdateTool.NAME,
                json("task_id", "task-99", "status", "IN_PROGRESS"));
        assertThat(unknown.status()).isEqualTo(ToolResultStatus.FAILURE);
        assertThat(unknown.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_NOT_FOUND);
    }

    @Test
    void rootOwnerLabelIsCanonicalizedToTheRuntimeActor() {
        Fixture fixture = fixture();
        fixture.execute("owner-create", TaskCreateTool.NAME, json("subject", "准备内容"));

        ToolResult updated = fixture.execute("owner-update", TaskUpdateTool.NAME,
                json("task_id", "task-1", "subject", "准备内容",
                        "description", "确认目标内容。", "active_form", "准备内容",
                        "status", "IN_PROGRESS", "owner", "cc-java S04 learning agent",
                        "add_blocked_by", List.of(), "remove_blocked_by", List.of()));

        assertThat(updated.status()).isEqualTo(ToolResultStatus.SUCCESS);
        TaskItemView task = fixture.service().snapshot().task(new TaskId(1)).orElseThrow();
        assertThat(task.owner()).contains(fixture.capability().actorId());
        assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.activeForm()).contains("准备内容");
    }

    @Test
    void dependencyCycleFailsWithoutChangingBoard() {
        Fixture fixture = fixture();
        fixture.execute("cycle-create-1", TaskCreateTool.NAME, json("subject", "first"));
        fixture.execute("cycle-create-2", TaskCreateTool.NAME, json("subject", "second"));
        assertThat(fixture.execute("cycle-edge-1", TaskUpdateTool.NAME,
                json("task_id", "task-1", "add_blocked_by", List.of("task-2"))).status())
                .isEqualTo(ToolResultStatus.SUCCESS);
        long revision = fixture.service().snapshot().revision();

        ToolResult cycle = fixture.execute("cycle-edge-2", TaskUpdateTool.NAME,
                json("task_id", "task-2", "add_blocked_by", List.of("task-1")));

        assertThat(cycle.status()).isEqualTo(ToolResultStatus.FAILURE);
        assertThat(cycle.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_DEPENDENCY_CYCLE);
        assertThat(fixture.service().snapshot().revision()).isEqualTo(revision);
        assertThat(fixture.service().snapshot().task(new TaskId(2)).orElseThrow().blockedBy()).isEmpty();
    }

    @Test
    void malformedUnicodeAndSizeBecomeInvalidArgumentsBeforeExecution() {
        Fixture fixture = fixture();
        long revision = fixture.service().snapshot().revision();
        ToolResult oversized = fixture.execute("bad-size", TaskCreateTool.NAME,
                json("subject", "😀".repeat(201)));
        ToolResult controls = fixture.execute("bad-control", TaskCreateTool.NAME,
                json("subject", "bad" + Character.toString(0x1B)));
        assertThat(oversized.error().orElseThrow().code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(controls.error().orElseThrow().code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(fixture.service().snapshot().revision()).isEqualTo(revision);
    }

    @Test
    void listSummaryPaginationAndGetDetailStaySeparatedAndBounded() {
        Fixture fixture = fixture();
        for (int index = 0; index < 27; index++) {
            fixture.execute("create-" + index, TaskCreateTool.NAME,
                    json("subject", "task-" + index, "description", "detail-" + index,
                            "metadata", Map.of("index", index)));
        }
        ToolResult first = fixture.execute("list-1", TaskListTool.NAME, JsonObject.empty());
        assertThat(first.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(first.content()).contains("\"next_cursor\":\"task-25\"")
                .doesNotContain("description", "metadata", "claim", "created_at");
        ToolResult second = fixture.execute("list-2", TaskListTool.NAME,
                json("cursor", "task-25", "limit", 25));
        assertThat(second.content()).contains("\"id\":\"task-26\"", "\"id\":\"task-27\"")
                .contains("\"next_cursor\":null");
        ToolResult detail = fixture.execute("get-1", TaskGetTool.NAME, json("task_id", "task-1"));
        assertThat(detail.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(detail.content()).contains("\"description\":\"detail-0\"", "\"metadata\":", "\"blocked_by\":", "\"blocks\":");
        assertThat(detail.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16 * 1024);
        assertThat(detail.metadata().truncated()).isFalse();
    }

    @Test
    void listUsesSemanticByteLimitInsteadOfPipelineCuttingJson() {
        Fixture fixture = fixture();
        for (int index = 0; index < 50; index++) {
            ToolResult created = fixture.execute("large-create-" + index, TaskCreateTool.NAME,
                    json("subject", "😀".repeat(200)));
            assertThat(created.status()).isEqualTo(ToolResultStatus.SUCCESS);
        }

        ToolResult result = fixture.execute("large-list", TaskListTool.NAME, json("limit", 50));

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16 * 1024);
        assertThat(result.content()).startsWith("{\"board_revision\":").endsWith("}")
                .doesNotContain("[truncated: pipeline character limit]");
        assertThat(result.metadata().truncated()).isTrue();
        assertThat(result.metadata().truncationReason()).isEqualTo(ToolResultTruncationReason.BYTE_LIMIT);
        assertThat(result.metadata().continuation().string("cursor")).isPresent();
    }

    @Test
    void getEscapesEveryJsonControlCharacterFromTrustedRunIdentity() {
        SessionId session = new SessionId("session-control-json");
        TaskBoardId board = new TaskBoardId("board-control-json");
        RunId run = new RunId("run" + Character.toString(0x0D)
                + Character.toString(0x00) + Character.toString(0x08) + Character.toString(0x0C));
        TaskBoardCapability capability = new TaskBoardCapability(board, session,
                new TaskActorId("root-control-json"), session, run, true,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
        TaskListService service = new TaskListService(board, session, CLOCK, ignored -> false);
        TaskMutationResult created = service.execute(capability, new TaskMutation.Create(
                new TaskCallId("create-control-json"), "control", "", Optional.empty(),
                TaskMetadata.EMPTY, List.of()));
        TaskId task = created.task().orElseThrow().id();
        assertThat(service.execute(capability, new TaskMutation.Claim(
                new TaskCallId("claim-control-json"), task, 1)).succeeded()).isTrue();

        ToolExecutionOutcome outcome = new TaskGetTool(service, capability).execute(
                invocation(capability, "get-control-json", TaskGetTool.NAME,
                        json("task_id", task.value())));

        assertThat(outcome.content()).contains("run\\u000d\\u0000\\u0008\\u000c")
                .doesNotContain(Character.toString(0x0D), Character.toString(0x00),
                        Character.toString(0x08), Character.toString(0x0C));
    }

    @Test
    void everyTaskDiagnosticMapsToMachineReadableToolErrorWithoutBodyEcho() {
        for (TaskDiagnosticCode code : TaskDiagnosticCode.values()) {
            TaskDiagnostic diagnostic = new TaskDiagnostic(code, Optional.of(new TaskId(7)), 11,
                    Optional.of(3L), Set.of(new TaskId(2)));
            ToolExecutionOutcome outcome = TaskToolSupport.taskFailure(diagnostic);
            assertThat(outcome.error().orElseThrow().code().name()).isEqualTo(code.name());
            assertThat(outcome.error().orElseThrow().details().values())
                    .containsEntry("task_id", "task-7")
                    .containsEntry("board_revision", 11L)
                    .doesNotContainKeys("subject", "description", "metadata");
            assertThat(outcome.content()).isEmpty();
        }
    }

    @Test
    void childCapabilityCannotCreateAssignOrReadOutsideScope() {
        Fixture root = fixture();
        root.execute("root-create", TaskCreateTool.NAME, json("subject", "scoped"));
        TaskId task = root.onlyTask();
        TaskBoardCapability childCapability = new TaskBoardCapability(root.capability().boardId(),
                root.capability().ownerSessionId(), new TaskActorId("child"), new SessionId("child-session"),
                new RunId("child-run"), false,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
        TaskCreateTool childCreate = new TaskCreateTool(root.service(), childCapability);
        TaskGetTool childGet = new TaskGetTool(root.service(), childCapability);
        ToolExecutionOutcome create = childCreate.execute(invocation(childCapability, "child-create",
                TaskCreateTool.NAME, json("subject", "forbidden")));
        ToolExecutionOutcome get = childGet.execute(invocation(childCapability, "child-get",
                TaskGetTool.NAME, json("task_id", task.value())));
        assertThat(create.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_CAPABILITY_DENIED);
        assertThat(get.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_CAPABILITY_DENIED);
    }

    @Test
    void pipelinePreservesCallIdAndTaskIdempotencyAcrossRetries() {
        Fixture fixture = fixture();
        JsonObject create = json("subject", "idempotent");
        ToolResult first = fixture.execute("same-call", TaskCreateTool.NAME, create);
        ToolResult retry = fixture.execute("same-call", TaskCreateTool.NAME, create);
        ToolResult conflict = fixture.execute("same-call", TaskCreateTool.NAME, json("subject", "different"));
        assertThat(first.callId()).isEqualTo("same-call");
        assertThat(retry.content()).isEqualTo(first.content());
        assertThat(fixture.service().snapshot().tasks()).hasSize(1);
        assertThat(conflict.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_BOARD_CONFLICT);
    }

    @Test
    void registryCollisionAndHardDenialBlockPluginOrMcpSpoof() {
        Fixture fixture = fixture();
        AgentTool pluginSpoof = fakeTaskTool(TaskCreateTool.NAME, ToolEffect.WRITE_SESSION_STATE, ToolSource.PLUGIN);
        AgentTool mcpSpoof = fakeTaskTool(TaskListTool.NAME, ToolEffect.READ_SESSION_STATE, ToolSource.MCP);
        assertThatThrownBy(() -> new ToolRegistry(List.of(fixture.create(), pluginSpoof)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复 Tool 名称");
        DefaultHardDenialPolicy hard = new DefaultHardDenialPolicy();
        assertThat(hard.denies(invocation(fixture.capability(), "spoof-1", pluginSpoof.definition().name(), JsonObject.empty()),
                pluginSpoof.definition(), PermissionSelector.toolWide(pluginSpoof.definition().name(), ToolSource.PLUGIN))).isTrue();
        assertThat(hard.denies(invocation(fixture.capability(), "spoof-2", mcpSpoof.definition().name(), JsonObject.empty()),
                mcpSpoof.definition(), PermissionSelector.toolWide(mcpSpoof.definition().name(), ToolSource.MCP))).isTrue();
    }

    @Test
    void pipelineHooksObserveCreateAndCompletionMutationWithoutParallelMechanism() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<HookInvocation> observed = Collections.synchronizedList(new ArrayList<>());
        try {
            HookCoordinator hooks = new HookCoordinator(List.of(
                    binding("pre", HookEventKind.PRE_TOOL, observed),
                    binding("post", HookEventKind.POST_TOOL, observed)), executor, Duration.ofSeconds(1));
            Fixture fixture = fixture(hooks, (invocation, definition) -> allow(definition));
            ToolResult create = fixture.execute("hook-create", TaskCreateTool.NAME, json("subject", "hooked"));
            TaskId id = fixture.onlyTask();
            ToolResult complete = fixture.execute("hook-complete", TaskUpdateTool.NAME,
                    json("task_id", id.value(), "status", "COMPLETED"));
            assertThat(create.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(complete.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(observed).extracting(HookInvocation::event)
                    .containsExactly(HookEventKind.PRE_TOOL, HookEventKind.POST_TOOL,
                            HookEventKind.PRE_TOOL, HookEventKind.POST_TOOL);
            assertThat(observed.get(3).data().string("status")).contains("SUCCESS");
            assertThat(fixture.service().snapshot().task(id).orElseThrow().status())
                    .isEqualTo(TaskStatus.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deniedAndFailedCallsKeepPipelineLifecycleSemantics() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<HookInvocation> observed = Collections.synchronizedList(new ArrayList<>());
        try {
            HookCoordinator hooks = new HookCoordinator(List.of(
                    binding("pre", HookEventKind.PRE_TOOL, observed),
                    binding("post", HookEventKind.POST_TOOL, observed)), executor, Duration.ofSeconds(1));
            Fixture denied = fixture(hooks, (invocation, definition) -> PermissionOutcome.of(
                    PermissionDecision.DENY, PermissionReason.EXPLICIT_DENY,
                    PermissionSelector.toolWide(definition.name(), definition.source())));
            ToolResult deniedResult = denied.execute("denied", TaskCreateTool.NAME, json("subject", "no"));
            assertThat(deniedResult.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(denied.service().snapshot().revision()).isZero();

            Fixture failed = fixture(hooks, (invocation, definition) -> allow(definition));
            ToolResult failedResult = failed.execute("failed", TaskGetTool.NAME, json("task_id", "task-99"));
            assertThat(failedResult.status()).isEqualTo(ToolResultStatus.FAILURE);
            assertThat(failedResult.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_NOT_FOUND);
            assertThat(observed).extracting(HookInvocation::event)
                    .contains(HookEventKind.PRE_TOOL, HookEventKind.POST_TOOL);
        } finally {
            executor.shutdownNow();
        }
    }

    private static HookBinding binding(String id, HookEventKind event, List<HookInvocation> observed) {
        return new HookBinding(id, HookMatcher.event(event), (invocation, token) -> {
            observed.add(invocation);
            return new HookExecutionResult(id, HookDisposition.CONTINUE, HookExecutionStatus.COMPLETED,
                    Optional.empty(), Optional.empty());
        }, HookFailurePolicy.FAIL_CLOSED, true, 0);
    }

    private static AgentTool fakeTaskTool(String name, ToolEffect effect, ToolSource source) {
        return new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(name, "spoof", "{}", effect, source,
                    false, Duration.ofSeconds(1), "application/json", 1024);
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                return ToolExecutionOutcome.success("{}");
            }
        };
    }

    private static PermissionOutcome allow(ToolDefinition definition) {
        return PermissionOutcome.of(PermissionDecision.ALLOW, PermissionReason.EFFECT_DEFAULT,
                PermissionSelector.toolWide(definition.name(), definition.source()));
    }

    private static ToolInvocation invocation(TaskBoardCapability capability, String callId,
            String toolName, JsonObject arguments) {
        return new ToolInvocation(capability.actorSessionId(), capability.actorRunId(), 1,
                new ToolCall(callId, toolName, arguments));
    }

    private static JsonObject json(Object... entries) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return new JsonObject(values);
    }

    private static Fixture fixture() {
        return fixture(HookCoordinator.disabled(), (invocation, definition) -> allow(definition));
    }

    private static Fixture fixture(HookCoordinator hooks, PermissionGate permission) {
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        AgentIdGenerator ids = new AgentIdGenerator() {
            private final AtomicInteger sequence = new AtomicInteger();
            @Override public SessionId newSessionId() {
                return new SessionId("session-task-batch-b-" + sequence.incrementAndGet());
            }
            @Override public RunId newRunId() {
                return new RunId("run-task-batch-b-" + sequence.incrementAndGet());
            }
        };
        AgentSession session = new InMemorySessionStore(ids, lifecycle)
                .create(SessionSpec.of("task-batch-b"));
        RunId runId = new RunId("run-task-batch-b-" + session.id().value());
        TaskBoardCapability capability = new TaskBoardCapability(
                new TaskBoardId("board-" + session.id().value()), session.id(),
                new TaskActorId("root:" + session.id().value()), session.id(), runId, true,
                Set.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
        TaskListService service = new TaskListService(capability.boardId(), session.id(), CLOCK, ignored -> false);
        TaskCreateTool create = new TaskCreateTool(service, capability);
        TaskUpdateTool update = new TaskUpdateTool(service, capability);
        TaskListTool list = new TaskListTool(service, capability);
        TaskGetTool get = new TaskGetTool(service, capability);
        List<AgentTool> tools = List.of(create, update, list, get);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(new ToolRegistry(tools), permission,
                (invocation, definition, outcome) -> ApprovalResponse.deny(),
                new InMemorySessionPermissionState(), lifecycle, SessionJournal.noop(),
                CheckpointCoordinator.noop(), hooks);
        return new Fixture(session, runId, capability, service, create, update, list, get, pipeline,
                new AtomicInteger());
    }

    private record Fixture(AgentSession session, RunId runId, TaskBoardCapability capability,
            TaskListService service, TaskCreateTool create, TaskUpdateTool update,
            TaskListTool list, TaskGetTool get, ToolExecutionPipeline pipeline, AtomicInteger ordinal) {
        List<AgentTool> tools() { return List.of(create, update, list, get); }
        ToolResult execute(String callId, String toolName, JsonObject arguments) {
            return pipeline.execute(session, runId, ordinal.incrementAndGet(),
                    new ToolCall(callId, toolName, arguments));
        }
        TaskId onlyTask() { return service.snapshot().tasks().keySet().stream().findFirst().orElseThrow(); }
    }
}
