package io.github.liumaishenjian.ccjava.cli.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.subagent.ChildRuntimeScope;
import io.github.liumaishenjian.ccjava.core.task.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 child 保持独立 Runtime scope，同时只访问宿主冻结的 parent Board Task 集。 */
class HeadlessChildTaskBoardCompositionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC);
    private static final ChildBudget BUDGET = new ChildBudget(4, 4, 4096, 4096, Duration.ofSeconds(10));

    @Test
    void childUsesOwnSessionAndRunButListsOnlyHostScopedParentTasks(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        SessionId parentSession = new SessionId("session-parent");
        TaskListService board = new TaskListService(new TaskBoardId("board-parent"), parentSession, CLOCK,
                TaskRunState.noneTerminated());
        TaskBoardCapability rootCapability = TaskBoardCapabilityFactory.root(
                board.snapshot().boardId(), parentSession, new RunId("run-parent"));
        board.execute(rootCapability, create("call-1", "visible"));
        board.execute(rootCapability, create("call-2", "hidden"));

        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        ModelGateway gateway = request -> {
            requests.add(request);
            if (requests.size() == 1) {
                return ModelTurn.tools(List.of(new ToolCall(
                        "call-list", "task_list", JsonObject.empty())));
            }
            return ModelTurn.text("done");
        };
        TestIds ids = new TestIds();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        TaskActorId childActor = new TaskActorId("child:delegation-1");
        HeadlessChildRuntimeScopeFactory factory = new HeadlessChildRuntimeScopeFactory(
                workspace, root.resolve("sessions"), gateway,
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce(), ids, lifecycle,
                HookCoordinator.disabled(), () -> null, ExecutionBackendPreference.LOCAL,
                platformShell(), ignored -> Optional.of(new ChildTaskBoardAccess(
                        board, childActor, Set.of(new TaskId(1)), candidate -> candidate.equals(childActor))));
        AgentDefinitionSnapshot definition = new AgentDefinitionSnapshot(
                new AgentDefinitionId("task-child"), "task child", "use scoped tasks",
                Set.of("task_list"), PermissionMode.DEFAULT, "fake", BUDGET, false,
                "a".repeat(64), "project");
        ChildTaskRequest childRequest = new ChildTaskRequest(
                new DelegationId("delegation-1"), definition.id(), "list assigned tasks",
                Set.of("task_list"), BUDGET, false, 1, false);

        try (ChildRuntimeScope scope = factory.create(definition, childRequest, CancellationToken.none())) {
            assertThat(scope.sessionId()).isNotEqualTo(parentSession);
            assertThat(scope.runtime().run(scope.sessionId(), AgentRunRequest.of("list"))
                    .stopReason()).isEqualTo(StopReason.COMPLETED);
        }

        assertThat(requests.getFirst().toolDefinitions())
                .extracting(definitionValue -> definitionValue.name())
                .containsExactly("task_list");
        assertThat(requests.getLast().messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> assertThat(((ToolResultMessage) message).result().content())
                        .contains("task-1", "visible")
                        .doesNotContain("task-2", "hidden"));
    }

    private static TaskMutation.Create create(String call, String subject) {
        return new TaskMutation.Create(new TaskCallId(call), subject, "", Optional.empty(),
                TaskMetadata.EMPTY, List.of());
    }

    private static ExecutionShell platformShell() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? ExecutionShell.WINDOWS_PLATFORM : ExecutionShell.POSIX_PLATFORM;
    }

    private static final class TestIds implements AgentIdGenerator {
        private final AtomicInteger sessions = new AtomicInteger();
        private final AtomicInteger runs = new AtomicInteger();
        @Override public SessionId newSessionId() { return new SessionId("session-child-" + sessions.incrementAndGet()); }
        @Override public RunId newRunId() { return new RunId("run-child-" + runs.incrementAndGet()); }
    }
}
