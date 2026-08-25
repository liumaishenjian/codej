package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import io.github.liumaishenjian.ccjava.domain.task.TaskCallId;
import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import io.github.liumaishenjian.ccjava.domain.task.TaskItemView;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadataPatch;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadataValue;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * 为已批准 Plan 暴露窄化的任务状态更新协议。
 *
 * <p>模型只选择批准步骤的 {@code task_id}、目标状态和可选 {@code active_form}；当前 Task revision、
 * claim epoch、Board/Session/Run/actor capability 以及 Plan identity 均由宿主从 canonical 状态注入。
 * Adapter 只允许 {@code PENDING -> IN_PROGRESS -> COMPLETED}，并验证目标 Task 仍精确属于构造时绑定的
 * Plan revision，因此模型不能借此编辑标题、描述、依赖、顺序或 metadata。</p>
 *
 * @since 0.15.0
 */
public final class ApprovedPlanTaskUpdateTool implements AgentTool {
    private static final Set<String> FIELDS = Set.of("task_id", "status", "active_form");
    private static final ToolDefinition DEFINITION = new ToolDefinition(TaskUpdateTool.NAME,
            "Update one frozen approved-plan task. Use status IN_PROGRESS before work, then COMPLETED only after "
                    + "that task is actually verified. active_form is optional and only valid with IN_PROGRESS. "
                    + "Runtime injects Plan identity, revisions, claim epoch, Session, Run and actor capability.",
            """
            {"type":"object","additionalProperties":false,"required":["task_id","status"],"properties":{"task_id":{"type":"string","pattern":"^task-[1-9][0-9]*$"},"status":{"type":"string","enum":["IN_PROGRESS","COMPLETED"]},"active_form":{"type":"string","minLength":1,"maxLength":200}}}
            """, ToolEffect.WRITE_SESSION_STATE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "application/json", 4_096);

    private final TaskListService service;
    private final Function<ToolInvocation, TaskBoardCapability> capabilities;
    private final PlanBinding plan;

    /** 绑定 canonical Board、动态可信 capability 与精确批准 Plan identity。 */
    public ApprovedPlanTaskUpdateTool(TaskListService service,
            Function<ToolInvocation, TaskBoardCapability> capabilities,
            String planId, String planDigest, long planRevision) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
        this.plan = new PlanBinding(planId, planDigest, planRevision);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            parse(arguments);
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("approved task_update arguments are invalid");
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        TaskBoardCapability capability = capabilities.apply(invocation);
        return TaskToolSupport.identityFailure(invocation, capability, service.snapshot().revision())
                .orElseGet(() -> executeBound(invocation, capability));
    }

    private ToolExecutionOutcome executeBound(ToolInvocation invocation, TaskBoardCapability capability) {
        Request request = parse(invocation.call().arguments());
        synchronized (service) {
            TaskItemView current = service.snapshot().task(request.taskId())
                    .orElse(null);
            if (current == null || !matchesPlan(current)) {
                return TaskToolSupport.taskFailure(new io.github.liumaishenjian.ccjava.domain.task.TaskDiagnostic(
                        io.github.liumaishenjian.ccjava.domain.task.TaskDiagnosticCode.TASK_CAPABILITY_DENIED,
                        Optional.of(request.taskId()), service.snapshot().revision(), Optional.empty(), Set.of()));
            }
            if (alreadyApplied(current, request, capability)) {
                return snapshotOutcome(current);
            }
            if (request.status() == TaskStatus.IN_PROGRESS) {
                return startTask(invocation.call().id(), capability, current, request.activeForm());
            }
            return completeTask(invocation.call().id(), capability, current);
        }
    }

    private ToolExecutionOutcome startTask(String callId, TaskBoardCapability capability,
            TaskItemView current, Optional<String> activeForm) {
        if (current.status() != TaskStatus.PENDING) {
            return rejectedTransition(current);
        }
        TaskItemView candidate = current;
        if (activeForm.isPresent() && !activeForm.equals(current.activeForm())) {
            TaskMutation.Edit edit = new TaskMutation.Edit(new TaskCallId(stablePhaseCallId(callId, "active")), current.id(),
                    current.revision(), OptionalLong.empty(), Optional.empty(), Optional.empty(), true,
                    activeForm, TaskMetadataPatch.empty());
            var edited = service.execute(capability, edit);
            if (!edited.succeeded()) return TaskToolSupport.mutationOutcome(edited);
            candidate = edited.task().orElseThrow();
        }
        var claimed = service.execute(capability, new TaskMutation.Claim(
                new TaskCallId(stablePhaseCallId(callId, "status")), candidate.id(), candidate.revision()));
        return TaskToolSupport.mutationOutcome(claimed);
    }

    private ToolExecutionOutcome completeTask(String callId, TaskBoardCapability capability,
            TaskItemView current) {
        if (current.status() != TaskStatus.IN_PROGRESS || current.claim().isEmpty()
                || current.recoveryRequired()) {
            return rejectedTransition(current);
        }
        var completed = service.execute(capability, new TaskMutation.Transition(
                new TaskCallId(stablePhaseCallId(callId, "status")), current.id(), current.revision(), TaskStatus.COMPLETED,
                OptionalLong.of(current.claim().orElseThrow().epoch())));
        return TaskToolSupport.mutationOutcome(completed);
    }

    private ToolExecutionOutcome rejectedTransition(TaskItemView current) {
        return TaskToolSupport.taskFailure(new io.github.liumaishenjian.ccjava.domain.task.TaskDiagnostic(
                io.github.liumaishenjian.ccjava.domain.task.TaskDiagnosticCode.TASK_INVALID_TRANSITION,
                Optional.of(current.id()), service.snapshot().revision(), Optional.of(current.revision()), Set.of()));
    }

    private boolean alreadyApplied(TaskItemView current, Request request, TaskBoardCapability capability) {
        if (request.status() == TaskStatus.COMPLETED) return current.status() == TaskStatus.COMPLETED;
        return current.status() == TaskStatus.IN_PROGRESS
                && current.claim().filter(claim -> claim.actorId().equals(capability.actorId())
                        && claim.runId().equals(capability.actorRunId())).isPresent()
                && (request.activeForm().isEmpty() || request.activeForm().equals(current.activeForm()));
    }

    private ToolExecutionOutcome snapshotOutcome(TaskItemView current) {
        String content = "{\"board_revision\":" + service.snapshot().revision()
                + ",\"task\":" + TaskToolSupport.summaryJson(current) + "}";
        return ToolExecutionOutcome.success(content);
    }

    private boolean matchesPlan(TaskItemView task) {
        Map<String, TaskMetadataValue> metadata = task.metadata().values();
        return stringValue(metadata.get("plan.source")).filter("approved-plan"::equals).isPresent()
                && stringValue(metadata.get("plan.id")).filter(plan.planId()::equals).isPresent()
                && stringValue(metadata.get("plan.digest")).filter(plan.planDigest()::equals).isPresent()
                && integerValue(metadata.get("plan.revision")).filter(value -> value == plan.planRevision()).isPresent();
    }

    private Request parse(JsonObject arguments) {
        if (!FIELDS.containsAll(arguments.values().keySet())
                || !arguments.values().keySet().containsAll(Set.of("task_id", "status"))) {
            throw new IllegalArgumentException("字段集合无效");
        }
        Object rawId = arguments.values().get("task_id");
        Object rawStatus = arguments.values().get("status");
        if (!(rawId instanceof String id) || !id.matches("^task-[1-9][0-9]*$")) {
            throw new IllegalArgumentException("task_id 无效");
        }
        TaskId taskId = new TaskId(Long.parseLong(id.substring(5)));
        if (!taskId.value().equals(id) || !(rawStatus instanceof String status)) {
            throw new IllegalArgumentException("批准任务状态参数无效");
        }
        TaskStatus target = TaskStatus.valueOf(status);
        if (target != TaskStatus.IN_PROGRESS && target != TaskStatus.COMPLETED) {
            throw new IllegalArgumentException("只允许 IN_PROGRESS 或 COMPLETED");
        }
        Optional<String> activeForm = Optional.ofNullable(arguments.values().get("active_form")).map(value -> {
            if (!(value instanceof String text) || text.isBlank() || !validTaskText(text)) {
                throw new IllegalArgumentException("active_form 无效");
            }
            return text;
        });
        if (target == TaskStatus.COMPLETED && activeForm.isPresent()) {
            throw new IllegalArgumentException("COMPLETED 不接受 active_form");
        }
        return new Request(taskId, target, activeForm);
    }

    /**
     * 把 Provider callId 与内部 mutation phase 收敛为固定短 ASCII 幂等键。
     *
     * <p>Provider identity 可合法包含 128 个非 BMP code point；不能直接追加 phase 后交给更窄的
     * {@link TaskCallId}。NUL 分隔防止字段边界歧义，SHA-256 保留同 callId+phase 的稳定重放语义，
     * 同一模型 Tool 内的 active edit 与 status transition 又拥有不同 identity。</p>
     */
    private static String stablePhaseCallId(String callId, String phase) {
        Objects.requireNonNull(callId, "callId 不能为空");
        Objects.requireNonNull(phase, "phase 不能为空");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((callId + '\0' + phase).getBytes(StandardCharsets.UTF_8));
            return "approved-task-" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static boolean validTaskText(String value) {
        if (value.codePointCount(0, value.length()) > 200
                || value.getBytes(StandardCharsets.UTF_8).length > 800
                || value.codePoints().anyMatch(Character::isISOControl)) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    private static Optional<String> stringValue(TaskMetadataValue value) {
        return value instanceof TaskMetadataValue.StringValue string ? Optional.of(string.value()) : Optional.empty();
    }

    private static Optional<Long> integerValue(TaskMetadataValue value) {
        return value instanceof TaskMetadataValue.IntegerValue integer ? Optional.of(integer.value()) : Optional.empty();
    }

    private record Request(TaskId taskId, TaskStatus status, Optional<String> activeForm) { }

    private record PlanBinding(String planId, String planDigest, long planRevision) {
        private PlanBinding {
            if (planId == null || planId.isBlank() || planDigest == null || !planDigest.matches("[0-9a-f]{64}")
                    || planRevision < 1) {
                throw new IllegalArgumentException("批准 Plan identity 无效");
            }
        }
    }
}
