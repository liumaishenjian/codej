package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonNull;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.task.TaskActorId;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability;
import io.github.liumaishenjian.ccjava.domain.task.TaskCallId;
import io.github.liumaishenjian.ccjava.domain.task.TaskDiagnostic;
import io.github.liumaishenjian.ccjava.domain.task.TaskDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import io.github.liumaishenjian.ccjava.domain.task.TaskItemView;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadataPatch;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * 为模型暴露宿主托管并发控制的简单 Task 更新契约。
 *
 * <p>模型只提交目标 Task 与希望改变的业务字段，不维护 operation、Task/Board revision、claim epoch、
 * Session、Run 或 actor identity。Adapter 在同一 Board 临界区读取 canonical Task，再把一次源式更新
 * 收敛为 Core 的强 CAS mutation；Service 的 revision、线性化、DAG、claim 和幂等不变量因此仍完整保留。
 * 显式强 CAS mutation 只属于 Java Core/API，不再作为 LLM Tool wire contract。</p>
 *
 * <p>单次调用可组合正文、活动文案、owner、依赖和状态。内部阶段 call ID 由 Provider call ID 稳定派生，
 * partial retry 会跳过已实现字段并继续剩余阶段，不重复推进 revision。</p>
 *
 * <p>当前 Session-local root 只有一个可分配 actor，模型提交的 owner 是自分配意图而不是可信身份；
 * Adapter 必须把任意非空标签规范化为 capability 中的当前 root actor。child/未来协作目录仍按宿主
 * 可分配 actor 精确校验，不能借 owner 文本扩大 capability。</p>
 *
 * @since 0.15.0
 */
public final class TaskUpdateTool implements AgentTool {
    /** 稳定内置 Tool 名称。 */
    public static final String NAME = "task_update";
    private static final Set<String> FIELDS = Set.of(
            "task_id", "subject", "description", "active_form", "status", "owner",
            "add_blocked_by", "remove_blocked_by");
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Update one task using task_id and only the fields that should change. Runtime owns revisions, claims, board, session, run and actor identities. In the session-local root runtime, any owner label means assign to the current runtime actor.",
            """
            {"type":"object","additionalProperties":false,"required":["task_id"],"properties":{"task_id":{"type":"string","pattern":"^task-[1-9][0-9]*$"},"subject":{"type":"string","minLength":1,"maxLength":200},"description":{"type":"string","maxLength":4096},"active_form":{"anyOf":[{"type":"string","minLength":1,"maxLength":200},{"type":"null"}]},"status":{"type":"string","enum":["PENDING","IN_PROGRESS","COMPLETED","DELETED"]},"owner":{"type":"string","minLength":1,"maxLength":128},"add_blocked_by":{"type":"array","maxItems":32,"items":{"type":"string","pattern":"^task-[1-9][0-9]*$"}},"remove_blocked_by":{"type":"array","maxItems":32,"items":{"type":"string","pattern":"^task-[1-9][0-9]*$"}}}}
            """, ToolEffect.WRITE_SESSION_STATE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "application/json", 4_096);

    private final TaskListService service;
    private final Function<ToolInvocation, TaskBoardCapability> capabilities;
    private final TaskActorDirectory actors;

    /** 绑定宿主持有的 Board 服务与不可由模型提交的 capability。 */
    public TaskUpdateTool(TaskListService service, TaskBoardCapability capability) {
        this(service, ignored -> capability, candidate -> candidate.equals(capability.actorId()));
    }

    /** 绑定宿主持有的 Board、capability 与可分配 actor 目录。 */
    public TaskUpdateTool(TaskListService service, TaskBoardCapability capability, TaskActorDirectory actors) {
        this(service, ignored -> capability, actors);
    }

    /** 绑定动态可信 capability 与可分配 actor 目录。 */
    public TaskUpdateTool(TaskListService service,
            Function<ToolInvocation, TaskBoardCapability> capabilities,
            TaskActorDirectory actors) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
        this.actors = Objects.requireNonNull(actors, "actors 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            parse(arguments);
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("task_update arguments are invalid");
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        TaskBoardCapability capability = capabilities.apply(invocation);
        synchronized (service) {
            long boardRevision = service.snapshot().revision();
            return TaskToolSupport.identityFailure(invocation, capability, boardRevision)
                    .orElseGet(() -> executeLocked(invocation, capability));
        }
    }

    private ToolExecutionOutcome executeLocked(ToolInvocation invocation, TaskBoardCapability capability) {
        Request request = parse(invocation.call().arguments());
        TaskItemView current = service.snapshot().task(request.taskId()).orElse(null);
        if (current == null) {
            return TaskToolSupport.taskFailure(new TaskDiagnostic(TaskDiagnosticCode.TASK_NOT_FOUND,
                    Optional.of(request.taskId()), service.snapshot().revision(), Optional.empty(), Set.of()));
        }
        if (!capability.allows(current.id())) {
            return TaskToolSupport.taskFailure(new TaskDiagnostic(TaskDiagnosticCode.TASK_CAPABILITY_DENIED,
                    Optional.of(current.id()), service.snapshot().revision(), Optional.empty(), Set.of()));
        }
        try {
            TaskItemView candidate = applyEdit(invocation.call().id(), capability, current, request);
            candidate = applyOwner(invocation.call().id(), capability, candidate, request);
            candidate = applyDependencies(invocation.call().id(), capability, candidate, request);
            Optional<TaskItemView> terminal = applyStatus(invocation.call().id(), capability, candidate, request);
            return terminal.map(this::snapshotOutcome)
                    .orElseGet(() -> ToolExecutionOutcome.success("{\"board_revision\":"
                            + service.snapshot().revision() + ",\"task\":null}"));
        } catch (StageFailure failure) {
            return failure.outcome();
        }
    }

    private TaskItemView applyEdit(String callId, TaskBoardCapability capability,
            TaskItemView current, Request request) {
        boolean activeSpecified = request.activeFormSpecified();
        boolean changed = request.subject().filter(value -> !value.equals(current.subject())).isPresent()
                || request.description().filter(value -> !value.equals(current.description())).isPresent()
                || activeSpecified && !request.activeForm().equals(current.activeForm());
        if (!changed) return current;
        OptionalLong epoch = current.claim().map(claim -> OptionalLong.of(claim.epoch())).orElseGet(OptionalLong::empty);
        var result = service.execute(capability, new TaskMutation.Edit(
                new TaskCallId(phaseCallId(callId, "edit")), current.id(), current.revision(), epoch,
                request.subject(), request.description(), activeSpecified, request.activeForm(), TaskMetadataPatch.empty()));
        if (!result.succeeded()) {
            throw new StageFailure(TaskToolSupport.mutationOutcome(result));
        }
        return result.task().orElseThrow();
    }

    private TaskItemView applyOwner(String callId, TaskBoardCapability capability,
            TaskItemView current, Request request) {
        if (request.owner().isEmpty()) return current;
        TaskActorId requestedOwner = request.owner().orElseThrow();
        TaskActorId owner = capability.root() ? capability.actorId() : requestedOwner;
        if (!capability.root() && !actors.assignable(owner)) {
            throw new StageFailure(TaskToolSupport.taskFailure(new TaskDiagnostic(
                    TaskDiagnosticCode.TASK_CAPABILITY_DENIED, Optional.of(current.id()),
                    service.snapshot().revision(), Optional.empty(), Set.of())));
        }
        if (current.owner().filter(owner::equals).isPresent()) return current;
        TaskMutation mutation;
        if (current.owner().isEmpty()) {
            mutation = new TaskMutation.Assign(new TaskCallId(phaseCallId(callId, "owner")),
                    current.id(), current.revision(), owner);
        } else {
            OptionalLong epoch = current.claim().map(claim -> OptionalLong.of(claim.epoch())).orElseGet(OptionalLong::empty);
            mutation = new TaskMutation.Reassign(new TaskCallId(phaseCallId(callId, "owner")),
                    current.id(), current.revision(), owner, epoch);
        }
        var result = service.execute(capability, mutation);
        if (!result.succeeded()) {
            throw new StageFailure(TaskToolSupport.mutationOutcome(result));
        }
        return result.task().orElseThrow();
    }

    private TaskItemView applyDependencies(String callId, TaskBoardCapability capability,
            TaskItemView current, Request request) {
        List<TaskId> add = request.addBlockedBy().stream().filter(id -> !current.blockedBy().contains(id)).toList();
        List<TaskId> remove = request.removeBlockedBy().stream().filter(current.blockedBy()::contains).toList();
        if (add.isEmpty() && remove.isEmpty()) return current;
        var result = service.execute(capability, new TaskMutation.Dependency(
                new TaskCallId(phaseCallId(callId, "dependencies")), current.id(), current.revision(),
                service.snapshot().revision(), add, remove));
        if (!result.succeeded()) {
            throw new StageFailure(TaskToolSupport.mutationOutcome(result));
        }
        return result.task().orElseThrow();
    }

    private Optional<TaskItemView> applyStatus(String callId, TaskBoardCapability capability,
            TaskItemView current, Request request) {
        if (request.status().isEmpty()) return Optional.of(current);
        UpdateStatus target = request.status().orElseThrow();
        if (target.matches(current.status())) return Optional.of(current);
        TaskMutation mutation;
        if (target == UpdateStatus.IN_PROGRESS) {
            if (current.status() != TaskStatus.PENDING) throw statusFailure(current);
            mutation = new TaskMutation.Claim(new TaskCallId(phaseCallId(callId, "status")),
                    current.id(), current.revision());
        } else if (target == UpdateStatus.COMPLETED) {
            OptionalLong epoch = current.claim().map(claim -> OptionalLong.of(claim.epoch())).orElseGet(OptionalLong::empty);
            mutation = new TaskMutation.Transition(new TaskCallId(phaseCallId(callId, "status")),
                    current.id(), current.revision(), TaskStatus.COMPLETED, epoch);
        } else if (target == UpdateStatus.DELETED) {
            if (current.status() == TaskStatus.IN_PROGRESS) throw statusFailure(current);
            mutation = new TaskMutation.Delete(new TaskCallId(phaseCallId(callId, "status")),
                    current.id(), current.revision(), service.snapshot().revision());
        } else if (current.status() == TaskStatus.COMPLETED) {
            mutation = new TaskMutation.Transition(new TaskCallId(phaseCallId(callId, "status")),
                    current.id(), current.revision(), TaskStatus.PENDING, OptionalLong.empty());
        } else if (current.status() == TaskStatus.IN_PROGRESS && current.claim().isPresent()) {
            mutation = new TaskMutation.Release(new TaskCallId(phaseCallId(callId, "status")),
                    current.id(), current.revision(), current.claim().orElseThrow().epoch());
        } else throw statusFailure(current);
        var result = service.execute(capability, mutation);
        if (!result.succeeded()) throw new StageFailure(TaskToolSupport.mutationOutcome(result));
        return target == UpdateStatus.DELETED ? Optional.empty() : result.task();
    }

    private StageFailure statusFailure(TaskItemView current) {
        return new StageFailure(TaskToolSupport.taskFailure(new TaskDiagnostic(
                TaskDiagnosticCode.TASK_INVALID_TRANSITION, Optional.of(current.id()),
                service.snapshot().revision(), Optional.of(current.revision()), Set.of())));
    }

    private ToolExecutionOutcome snapshotOutcome(TaskItemView current) {
        return ToolExecutionOutcome.success("{\"board_revision\":" + service.snapshot().revision()
                + ",\"task\":" + TaskToolSupport.summaryJson(current) + "}");
    }

    private Request parse(JsonObject arguments) {
        if (!FIELDS.containsAll(arguments.values().keySet()) || !arguments.values().containsKey("task_id")
                || arguments.values().size() == 1) {
            throw new IllegalArgumentException("字段集合无效");
        }
        TaskId taskId = parseTaskId(arguments.values().get("task_id"));
        Optional<String> subject = optionalText(arguments, "subject", 200, false);
        Optional<String> description = optionalText(arguments, "description", Integer.MAX_VALUE, true);
        boolean activeSpecified = arguments.values().containsKey("active_form");
        Optional<String> activeForm = Optional.empty();
        if (activeSpecified && arguments.values().get("active_form") != JsonNull.INSTANCE) {
            activeForm = optionalText(arguments, "active_form", 200, false);
        }
        Optional<UpdateStatus> status = Optional.ofNullable(arguments.values().get("status")).map(value -> {
            if (!(value instanceof String text)) throw new IllegalArgumentException("status 无效");
            return UpdateStatus.valueOf(text);
        });
        Optional<TaskActorId> owner = Optional.ofNullable(arguments.values().get("owner")).map(value -> {
            if (!(value instanceof String text)) throw new IllegalArgumentException("owner 无效");
            return new TaskActorId(text);
        });
        List<TaskId> add = taskIds(arguments.values().get("add_blocked_by"));
        List<TaskId> remove = taskIds(arguments.values().get("remove_blocked_by"));
        if (add.stream().anyMatch(remove::contains)) throw new IllegalArgumentException("依赖增删冲突");
        return new Request(taskId, subject, description, activeSpecified, activeForm, status, owner, add, remove);
    }

    private static TaskId parseTaskId(Object value) {
        if (!(value instanceof String text) || !text.matches("^task-[1-9][0-9]*$")) {
            throw new IllegalArgumentException("task_id 无效");
        }
        TaskId id = new TaskId(Long.parseLong(text.substring(5)));
        if (!id.value().equals(text)) throw new IllegalArgumentException("task_id 无效");
        return id;
    }

    private static Optional<String> optionalText(JsonObject arguments, String field, int maxCodePoints,
            boolean multiline) {
        Object value = arguments.values().get(field);
        if (value == null) return Optional.empty();
        if (!(value instanceof String text) || (!field.equals("description") && text.isBlank())
                || text.codePointCount(0, text.length()) > maxCodePoints
                || field.equals("description") && text.getBytes(StandardCharsets.UTF_8).length > 4_096
                || text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                        && !(multiline && (codePoint == '\n' || codePoint == '\t')))) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return Optional.of(text);
    }

    private static List<TaskId> taskIds(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> source) || source.size() > 32) {
            throw new IllegalArgumentException("Task dependency 无效");
        }
        ArrayList<TaskId> ids = new ArrayList<>(source.size());
        for (Object item : source) ids.add(parseTaskId(item));
        if (Set.copyOf(ids).size() != ids.size()) throw new IllegalArgumentException("Task dependency 重复");
        return List.copyOf(ids);
    }

    private static String phaseCallId(String callId, String phase) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((callId + '\0' + phase).getBytes(StandardCharsets.UTF_8));
            return "task-update-" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private enum UpdateStatus {
        PENDING, IN_PROGRESS, COMPLETED, DELETED;

        private boolean matches(TaskStatus status) {
            return this != DELETED && name().equals(status.name());
        }
    }

    private record Request(TaskId taskId, Optional<String> subject, Optional<String> description,
            boolean activeFormSpecified, Optional<String> activeForm, Optional<UpdateStatus> status,
            Optional<TaskActorId> owner, List<TaskId> addBlockedBy, List<TaskId> removeBlockedBy) { }

    private static final class StageFailure extends RuntimeException {
        private final ToolExecutionOutcome outcome;
        private StageFailure(ToolExecutionOutcome outcome) {
            super(null, null, false, false);
            this.outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
        }
        private ToolExecutionOutcome outcome() { return outcome; }
    }
}
