package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import java.time.Duration;
import java.util.Objects;

/**
 * 返回稳定 TaskId 顺序紧凑摘要的内置只读 Tool。
 *
 * <p>List 不返回 description、metadata、claim 或时间字段；child capability 只能看到固定 scope。
 * Tool 不持有文件或 Session persistence，production 注册延期到 Batch C。</p>
 *
 * @since 0.15.0
 */
public final class TaskListTool implements AgentTool {
    /** 稳定内置 Tool 名称。 */
    public static final String NAME = "task_list";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "List bounded session task summaries in stable task-id order.",
            """
            {"type":"object","additionalProperties":false,"properties":{"status":{"type":"string","enum":["PENDING","IN_PROGRESS","COMPLETED"]},"filter":{"type":"string","minLength":1,"maxLength":200},"cursor":{"type":"string","pattern":"^task-[1-9][0-9]*$"},"limit":{"type":"integer","minimum":1,"maximum":50}}}
            """, ToolEffect.READ_SESSION_STATE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "application/json", 16_384);

    private final TaskListService service;
    private final java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities;

    /** 绑定宿主持有的 Board 服务与只读 capability。 */
    public TaskListTool(TaskListService service, TaskBoardCapability capability) {
        this(service, ignored -> capability);
    }

    /** 绑定按真实 ToolInvocation Session/Run 生成 capability 的宿主工厂。 */
    public TaskListTool(TaskListService service,
            java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            TaskToolSupport.listQuery(arguments);
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("task_list arguments are invalid");
        }
    }

    @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
        TaskBoardCapability capability = capabilities.apply(invocation);
        return TaskToolSupport.identityFailure(invocation, capability, service.snapshot().revision())
                .orElseGet(() -> TaskToolSupport.listOutcome(service.list(capability,
                        TaskToolSupport.listQuery(invocation.call().arguments()))));
    }
}
