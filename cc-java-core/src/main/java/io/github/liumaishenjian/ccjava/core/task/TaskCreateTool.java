package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import java.time.Duration;
import java.util.Objects;

/**
 * 创建 Session-local Task 的内置模型 Tool Adapter。
 *
 * <p>模型只能提交任务正文、依赖与封闭 metadata；Task/Board/actor/Session/Run/owner/status
 * identity 均由 Core 状态机和宿主 capability 决定。该类型不自行注册到 production Runtime，
 * Batch C durable Session composition 才能持有并装配它。</p>
 *
 * @since 0.15.0
 */
public final class TaskCreateTool implements AgentTool {
    /** 稳定内置 Tool 名称。 */
    public static final String NAME = "task_create";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Create a pending session task. Identity, status, owner, session and run are runtime-owned.",
            """
            {"type":"object","additionalProperties":false,"required":["subject"],"properties":{"subject":{"type":"string","minLength":1,"maxLength":200},"description":{"type":"string","maxLength":4096},"active_form":{"type":"string","minLength":1,"maxLength":200},"blocked_by":{"type":"array","maxItems":32,"items":{"type":"string","pattern":"^task-[1-9][0-9]*$"}},"metadata":{"type":"object","maxProperties":16,"additionalProperties":{"anyOf":[{"type":"boolean"},{"type":"integer"},{"type":"string","maxLength":512}]}}}}
            """, ToolEffect.WRITE_SESSION_STATE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "application/json", 4_096);

    private final TaskListService service;
    private final java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities;

    /** 绑定宿主持有的 Board 服务与不可由模型提交的 capability。 */
    public TaskCreateTool(TaskListService service, TaskBoardCapability capability) {
        this(service, ignored -> capability);
    }

    /** 绑定按真实 ToolInvocation Session/Run 生成 capability 的宿主工厂。 */
    public TaskCreateTool(TaskListService service,
            java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            TaskToolSupport.createMutation(arguments, "validation-call");
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("task_create arguments are invalid");
        }
    }

    @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
        TaskBoardCapability capability = capabilities.apply(invocation);
        return TaskToolSupport.identityFailure(invocation, capability, service.snapshot().revision())
                .orElseGet(() -> TaskToolSupport.mutationOutcome(service.execute(capability,
                        TaskToolSupport.createMutation(invocation.call().arguments(), invocation.call().id()))));
    }
}
