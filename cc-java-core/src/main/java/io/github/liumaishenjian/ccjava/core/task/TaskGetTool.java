package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import java.time.Duration;
import java.util.Objects;

/**
 * 返回单个 Task canonical/derived detail 的内置只读 Tool。
 *
 * <p>模型参数只有 task_id；Board/actor/Session/Run scope 由宿主绑定。Adapter 先生成完整合法 JSON，
 * 再按 UTF-8 16KiB Gate 返回成功或 OUTPUT_LIMIT_EXCEEDED，绝不让通用字符裁剪切断 JSON。</p>
 *
 * @since 0.15.0
 */
public final class TaskGetTool implements AgentTool {
    /** 稳定内置 Tool 名称。 */
    public static final String NAME = "task_get";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Get bounded canonical and derived detail for one session task.",
            """
            {"type":"object","additionalProperties":false,"required":["task_id"],"properties":{"task_id":{"type":"string","pattern":"^task-[1-9][0-9]*$"}}}
            """, ToolEffect.READ_SESSION_STATE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "application/json", 16_384);

    private final TaskListService service;
    private final java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities;

    /** 绑定宿主持有的 Board 服务与只读 capability。 */
    public TaskGetTool(TaskListService service, TaskBoardCapability capability) {
        this(service, ignored -> capability);
    }

    /** 绑定按真实 ToolInvocation Session/Run 生成 capability 的宿主工厂。 */
    public TaskGetTool(TaskListService service,
            java.util.function.Function<ToolInvocation, TaskBoardCapability> capabilities) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            TaskToolSupport.getTaskId(arguments);
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("task_get arguments are invalid");
        }
    }

    @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
        TaskBoardCapability capability = capabilities.apply(invocation);
        long revision = service.snapshot().revision();
        return TaskToolSupport.identityFailure(invocation, capability, revision)
                .orElseGet(() -> TaskToolSupport.getOutcome(service.get(capability,
                        TaskToolSupport.getTaskId(invocation.call().arguments()))));
    }
}
