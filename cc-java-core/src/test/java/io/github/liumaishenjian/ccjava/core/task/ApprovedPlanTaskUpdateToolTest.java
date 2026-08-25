package io.github.liumaishenjian.ccjava.core.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardId;
import io.github.liumaishenjian.ccjava.domain.task.TaskCallId;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadata;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadataValue;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证批准 Plan 窄 Task Adapter 的短幂等键、身份绑定与严格三态工作流。 */
class ApprovedPlanTaskUpdateToolTest {
    private static final String PLAN_ID = "plan-approved";
    private static final String PLAN_DIGEST = "a".repeat(64);
    private static final long PLAN_REVISION = 7;

    @Test
    void longUnicodeProviderCallIdSupportsPartialReplayAndCompletionWithoutRevisionDrift() {
        Fixture fixture = fixture();
        String longCallId = "😀".repeat(128);
        ToolInvocation start = fixture.invocation(longCallId, json(
                "task_id", "task-1", "status", "IN_PROGRESS", "active_form", "正在生成126条记录"));

        ToolExecutionOutcome started = fixture.tool().execute(start);
        long startedRevision = fixture.service().snapshot().revision();
        ToolExecutionOutcome replayed = fixture.tool().execute(start);

        assertThat(started.successful()).isTrue();
        assertThat(replayed.successful()).isTrue();
        assertThat(fixture.service().snapshot().revision()).isEqualTo(startedRevision);
        assertThat(fixture.service().snapshot().task(new io.github.liumaishenjian.ccjava.domain.task.TaskId(1))
                .orElseThrow()).satisfies(task -> {
                    assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
                    assertThat(task.activeForm()).contains("正在生成126条记录");
                });

        ToolExecutionOutcome completed = fixture.tool().execute(fixture.invocation("完成-" + longCallId,
                json("task_id", "task-1", "status", "COMPLETED")));
        assertThat(completed.successful()).isTrue();
        assertThat(fixture.service().snapshot().task(new io.github.liumaishenjian.ccjava.domain.task.TaskId(1))
                .orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void rejectsForeignPlanTaskAndInvalidStatusJumpWithoutMutation() {
        Fixture fixture = fixture();
        long revision = fixture.service().snapshot().revision();

        ToolExecutionOutcome foreign = fixture.tool().execute(fixture.invocation("foreign",
                json("task_id", "task-2", "status", "IN_PROGRESS")));
        ToolExecutionOutcome invalidJump = fixture.tool().execute(fixture.invocation("jump",
                json("task_id", "task-1", "status", "COMPLETED")));

        assertThat(foreign.successful()).isFalse();
        assertThat(foreign.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_CAPABILITY_DENIED);
        assertThat(invalidJump.successful()).isFalse();
        assertThat(invalidJump.error().orElseThrow().code()).isEqualTo(ToolErrorCode.TASK_INVALID_TRANSITION);
        assertThat(fixture.service().snapshot().revision()).isEqualTo(revision);
    }

    private static Fixture fixture() {
        SessionId sessionId = new SessionId("session-approved-task-test");
        RunId runId = new RunId("run-approved-task-test");
        TaskListService service = new TaskListService(new TaskBoardId("board-approved-task-test"), sessionId,
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), ignored -> false);
        TaskBoardCapability capability = TaskBoardCapabilityFactory.root(
                service.snapshot().boardId(), sessionId, runId);
        service.execute(capability, new TaskMutation.Create(new TaskCallId("seed-approved"), "生成天气工作簿", "",
                Optional.empty(), approvedMetadata(), List.of()));
        service.execute(capability, new TaskMutation.Create(new TaskCallId("seed-foreign"), "其他任务", "",
                Optional.empty(), TaskMetadata.EMPTY, List.of()));
        ApprovedPlanTaskUpdateTool tool = new ApprovedPlanTaskUpdateTool(service, ignored -> capability,
                PLAN_ID, PLAN_DIGEST, PLAN_REVISION);
        return new Fixture(service, capability, tool);
    }

    private static TaskMetadata approvedMetadata() {
        return new TaskMetadata(Map.of(
                "plan.source", new TaskMetadataValue.StringValue("approved-plan"),
                "plan.id", new TaskMetadataValue.StringValue(PLAN_ID),
                "plan.digest", new TaskMetadataValue.StringValue(PLAN_DIGEST),
                "plan.revision", new TaskMetadataValue.IntegerValue(PLAN_REVISION),
                "plan.step", new TaskMetadataValue.IntegerValue(1)));
    }

    private static JsonObject json(Object... entries) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return new JsonObject(values);
    }

    private record Fixture(TaskListService service, TaskBoardCapability capability,
            ApprovedPlanTaskUpdateTool tool) {
        ToolInvocation invocation(String callId, JsonObject arguments) {
            return new ToolInvocation(capability.actorSessionId(), capability.actorRunId(), 1,
                    new ToolCall(callId, TaskUpdateTool.NAME, arguments));
        }
    }
}
