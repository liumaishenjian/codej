package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.task.TaskActorId;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import java.time.Duration;
import java.util.Objects;

/**
 * 执行 ADR-088 封闭 mutation 集的内置模型 Tool Adapter。
 *
 * <p>Provider schema 使用 flat object，运行时按 operation 精确拒绝缺失或无关字段；CLAIM 与
 * RESUME_CLAIM 没有 run_id 字段，Core 只使用宿主 capability.actorRunId。metadata_patch 中
 * JSON null 仅表示删除同名 key，任意 nested object/array 均拒绝。</p>
 *
 * @since 0.15.0
 */
public final class TaskUpdateTool implements AgentTool {
    /** 稳定内置 Tool 名称。 */
    public static final String NAME = "task_update";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Apply one typed task mutation. Runtime-owned board, actor, session and run identities cannot be supplied.",
            """
            {"type":"object","additionalProperties":false,"required":["task_id","operation","expected_task_revision"],"properties":{"task_id":{"type":"string","pattern":"^task-[1-9][0-9]*$"},"operation":{"type":"string","enum":["EDIT","TRANSITION","CLAIM","RESUME_CLAIM","RELEASE","ASSIGN","REASSIGN","DEPENDENCY","DELETE"]},"expected_task_revision":{"type":"integer","minimum":1},"expected_board_revision":{"type":"integer","minimum":0},"expected_claim_epoch":{"type":"integer","minimum":1},"subject":{"type":"string","minLength":1,"maxLength":200},"description":{"type":"string","maxLength":4096},"active_form":{"anyOf":[{"type":"string","minLength":1,"maxLength":200},{"type":"null"}]},"metadata_patch":{"type":"object","maxProperties":16,"additionalProperties":{"anyOf":[{"type":"boolean"},{"type":"integer"},{"type":"string","maxLength":512},{"type":"null"}]}},"target_status":{"type":"string","enum":["PENDING","COMPLETED"]},"target_actor":{"type":"string","minLength":1,"maxLength":128},"add_blocked_by":{"type":"array","maxItems":32,"items":{"type":"string","pattern":"^task-[1-9][0-9]*$"}},"remove_blocked_by":{"type":"array","maxItems":32,"items":{"type":"string","pattern":"^task-[1-9][0-9]*$"}}}}
            """, ToolEffect.WRITE_SESSION_STATE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "application/json", 4_096);

    private final TaskListService service;
    private final java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities;
    private final TaskActorDirectory actors;

    /**
     * 绑定宿主持有的 Board 服务与不可由模型提交的 capability。
     *
     * <p>安全默认只认可 capability 当前 actor；Batch C 若需 Root 分配 Child，必须显式注入可信 actor 目录。</p>
     */
    public TaskUpdateTool(TaskListService service, TaskBoardCapability capability) {
        this(service, ignored -> capability, candidate -> candidate.equals(capability.actorId()));
    }

    /** 绑定宿主持有的 Board、capability 与可分配 actor 目录。 */
    public TaskUpdateTool(TaskListService service, TaskBoardCapability capability, TaskActorDirectory actors) {
        this(service, ignored -> capability, actors);
    }

    /** 绑定动态可信 capability 与可分配 actor 目录。 */
    public TaskUpdateTool(TaskListService service,
            java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities,
            TaskActorDirectory actors) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
        this.actors = Objects.requireNonNull(actors, "actors 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            parse(arguments, "validation-call");
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("task_update arguments are invalid");
        }
    }

    @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
        TaskBoardCapability capability = capabilities.apply(invocation);
        return TaskToolSupport.identityFailure(invocation, capability, service.snapshot().revision())
                .orElseGet(() -> TaskToolSupport.mutationOutcome(service.execute(capability,
                        parse(invocation.call().arguments(), invocation.call().id()))));
    }

    private TaskMutation parse(JsonObject arguments, String callId) {
        TaskMutation mutation = TaskToolSupport.updateMutation(arguments, callId);
        TaskActorId target = switch (mutation) {
            case TaskMutation.Assign command -> command.targetActor();
            case TaskMutation.Reassign command -> command.targetActor();
            default -> null;
        };
        if (target != null && !actors.assignable(target)) {
            throw new IllegalArgumentException("target_actor 不在宿主可分配 actor 目录中");
        }
        return mutation;
    }
}
