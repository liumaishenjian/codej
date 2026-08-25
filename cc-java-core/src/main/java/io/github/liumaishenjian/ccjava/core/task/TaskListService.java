package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * 线性化 Session-local Task Board mutation 的确定性 Core 服务。
 *
 * <p>服务只维护内存中的 canonical Task 状态、Board/Task revision、high-water mark、tombstone 与
 * actor/session/run/callId 幂等结果；它不访问文件系统，不执行 Tool，不决定 Permission 默认值，也不读取父/子
 * Transcript。单个 Board 最多接受 4096 次成功 mutation，幂等缓存只保存这些成功结果，因此具有同一上限。
 * 外层持久 Adapter 必须把一次成功 mutation 表达为单条 durable 事件，并在 replay 时重建成功计数与幂等索引，
 * 不能依赖本内存实例跨 Resume 保留状态。</p>
 *
 * <p>所有公开方法同步，保证同一 root-owned Board 内 claim、CAS、DAG 校验和提交具有单一线性化点。
 * 该同步不替代后续 FileSessionStore 的单 Writer lock。</p>
 *
 * @since 0.15.0
 */
public final class TaskListService {
    private static final int MAX_LIVE_TASKS = 256;
    private static final int MAX_EDGE_CHANGES = 32;
    private static final int MAX_SUCCESSFUL_MUTATIONS = 4_096;

    private final TaskBoardId boardId;
    private final SessionId ownerSessionId;
    private final Clock clock;
    private final TaskRunState runState;
    private final TaskMutationJournal journal;
    private final NavigableMap<TaskId, TaskItem> tasks = new TreeMap<>();
    private final NavigableSet<TaskId> tombstones = new TreeSet<>();
    private final Map<InvocationKey, StoredResult> idempotency = new LinkedHashMap<>();
    private long boardRevision;
    private long highWaterMark;
    private int successfulMutations;

    /**
     * 创建空的 root-owned Board。
     *
     * @param boardId Board identity
     * @param ownerSessionId root owner Session
     * @param clock 确定性时间源
     * @param runState 只回答 claim Run 是否终止的查询端口
     */
    public TaskListService(TaskBoardId boardId, SessionId ownerSessionId, Clock clock, TaskRunState runState) {
        this(boardId, ownerSessionId, clock, runState, TaskMutationJournal.volatileOnly(), List.of());
    }

    /**
     * 创建绑定 durable journal 并从成功事件恢复的 Board。
     *
     * @param boardId Board identity
     * @param ownerSessionId root owner Session
     * @param clock 后续 mutation 时间源
     * @param runState claim Run 终止查询
     * @param journal durable-before-visible append 端口
     * @param recoveredEvents canonical replay 结果
     */
    public TaskListService(TaskBoardId boardId, SessionId ownerSessionId, Clock clock, TaskRunState runState,
            TaskMutationJournal journal, List<TaskMutationEvent> recoveredEvents) {
        this.boardId = Objects.requireNonNull(boardId, "boardId 不能为空");
        this.ownerSessionId = Objects.requireNonNull(ownerSessionId, "ownerSessionId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.runState = Objects.requireNonNull(runState, "runState 不能为空");
        this.journal = Objects.requireNonNull(journal, "journal 不能为空");
        restore(Objects.requireNonNull(recoveredEvents, "recoveredEvents 不能为空"));
    }

    /** 从 Fork seed 创建无历史幂等索引的新 Board。 */
    public static TaskListService fromSeed(TaskBoardSeed seed, Clock clock, TaskRunState runState,
            TaskMutationJournal journal) {
        return fromSeed(seed, clock, runState, journal, List.of());
    }

    /** 从 Fork seed 与其后 canonical 成功事件恢复 Board。 */
    public static TaskListService fromSeed(TaskBoardSeed seed, Clock clock, TaskRunState runState,
            TaskMutationJournal journal, List<TaskMutationEvent> events) {
        TaskBoardSnapshot snapshot = Objects.requireNonNull(seed, "seed 不能为空").snapshot();
        TaskListService service = new TaskListService(snapshot.boardId(), snapshot.ownerSessionId(), clock,
                runState, journal, List.of());
        service.install(snapshot);
        service.restore(events);
        return service;
    }

    /** 返回当前不可变 Board 投影，供可信装配、持久化和测试使用。 */
    public synchronized TaskBoardSnapshot snapshot() { return snapshotView(); }

    /**
     * 按稳定 TaskId 顺序读取有界摘要页面。
     *
     * <p>child 只看到 capability scope 内的 Task；filter 只匹配 subject，不读取或返回
     * description/metadata。读取不推进 revision，也不进入 mutation 幂等缓存。</p>
     *
     * @param capability 宿主注入的可信 Board/actor scope
     * @param query 有界过滤、游标和条目上限
     * @return 页面或安全 capability 诊断
     */
    public synchronized TaskReadResult<TaskListPage> list(
            TaskBoardCapability capability, TaskListQuery query) {
        Objects.requireNonNull(capability, "capability 不能为空");
        Objects.requireNonNull(query, "query 不能为空");
        if (!readAllowed(capability)) return TaskReadResult.rejected(readDenied(Optional.empty()));
        String foldedFilter = query.filter().map(value -> value.toLowerCase(Locale.ROOT)).orElse(null);
        ArrayList<TaskSummary> matches = new ArrayList<>(query.limit() + 1);
        for (TaskItemView view : snapshotView().tasks().values()) {
            if (!capability.allows(view.id())) continue;
            if (query.cursor().isPresent() && view.id().compareTo(query.cursor().orElseThrow()) <= 0) continue;
            if (query.status().isPresent() && view.status() != query.status().orElseThrow()) continue;
            if (foldedFilter != null && !view.subject().toLowerCase(Locale.ROOT).contains(foldedFilter)) continue;
            matches.add(summary(view));
            if (matches.size() > query.limit()) break;
        }
        boolean hasMore = matches.size() > query.limit();
        if (hasMore) matches.remove(matches.size() - 1);
        Optional<TaskId> nextCursor = hasMore && !matches.isEmpty()
                ? Optional.of(matches.get(matches.size() - 1).id()) : Optional.empty();
        return TaskReadResult.success(new TaskListPage(boardRevision, matches, nextCursor));
    }

    /**
     * 读取一个完整 Task detail 投影。
     *
     * @param capability 宿主注入的可信 Board/actor scope
     * @param taskId 唯一模型参数
     * @return detail 或 missing/tombstone/capability 诊断
     */
    public synchronized TaskReadResult<TaskGetProjection> get(
            TaskBoardCapability capability, TaskId taskId) {
        Objects.requireNonNull(capability, "capability 不能为空");
        Objects.requireNonNull(taskId, "taskId 不能为空");
        if (!readAllowed(capability) || !capability.allows(taskId)) {
            return TaskReadResult.rejected(readDenied(Optional.of(taskId)));
        }
        TaskItem item = tasks.get(taskId);
        if (item == null) {
            TaskDiagnosticCode code = tombstones.contains(taskId)
                    ? TaskDiagnosticCode.TASK_DELETED : TaskDiagnosticCode.TASK_NOT_FOUND;
            return TaskReadResult.rejected(new TaskDiagnostic(code, Optional.of(taskId), boardRevision,
                    Optional.empty(), Set.of()));
        }
        return TaskReadResult.success(new TaskGetProjection(
                boardRevision, snapshotView().task(taskId).orElseThrow()));
    }

    /**
     * 在 capability、幂等、CAS、状态机和 DAG Gate 后执行一次 mutation。
     *
     * @param capability 宿主注入的可信 actor/Board scope
     * @param mutation 封闭 mutation 命令
     * @return 已提交的新快照，或未改变快照与结构诊断
     */
    public synchronized TaskMutationResult execute(TaskBoardCapability capability, TaskMutation mutation) {
        Objects.requireNonNull(capability, "capability 不能为空");
        Objects.requireNonNull(mutation, "mutation 不能为空");
        if (!boardId.equals(capability.boardId()) || !ownerSessionId.equals(capability.ownerSessionId())
                || !capability.canWrite()) {
            return rejected(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, Optional.empty(), Set.of());
        }
        InvocationKey key = new InvocationKey(capability.actorId(), capability.actorSessionId(),
                capability.actorRunId(), mutation.callId());
        StoredResult stored = idempotency.get(key);
        if (stored != null) {
            if (stored.mutation().equals(mutation)) return stored.result();
            return rejected(TaskDiagnosticCode.TASK_BOARD_CONFLICT, Optional.empty(), Set.of());
        }
        if (successfulMutations >= MAX_SUCCESSFUL_MUTATIONS) {
            return rejected(TaskDiagnosticCode.TASK_LIMIT_EXCEEDED, mutation.targetId(), Set.of());
        }
        StateMemento before = memento();
        TaskMutationResult result;
        try {
            result = apply(capability, mutation);
        } catch (Rejected rejection) {
            result = rejected(rejection.code, rejection.taskId, rejection.related);
        } catch (IllegalArgumentException limitOrShape) {
            result = rejected(TaskDiagnosticCode.TASK_LIMIT_EXCEEDED, mutation.targetId(), Set.of());
        }
        if (result.succeeded()) {
            TaskMutationEvent event = new TaskMutationEvent(capability.actorId(), capability.actorSessionId(),
                    capability.actorRunId(), mutation, result);
            try {
                journal.append(ownerSessionId, event);
            } catch (RuntimeException persistenceFailure) {
                restore(before);
                throw persistenceFailure;
            }
            successfulMutations++;
            idempotency.put(key, new StoredResult(mutation, result));
        }
        return result;
    }

    private TaskMutationResult apply(TaskBoardCapability capability, TaskMutation mutation) {
        return switch (mutation) {
            case TaskMutation.Create command -> create(capability, command);
            case TaskMutation.Edit command -> edit(capability, command);
            case TaskMutation.Transition command -> transition(capability, command);
            case TaskMutation.Claim command -> claim(capability, command);
            case TaskMutation.ResumeClaim command -> resumeClaim(capability, command);
            case TaskMutation.Release command -> release(capability, command);
            case TaskMutation.Assign command -> assign(capability, command);
            case TaskMutation.Reassign command -> reassign(capability, command);
            case TaskMutation.Dependency command -> dependency(capability, command);
            case TaskMutation.Delete command -> delete(capability, command);
        };
    }

    private TaskMutationResult create(TaskBoardCapability capability, TaskMutation.Create command) {
        requireRoot(capability);
        if (tasks.size() >= MAX_LIVE_TASKS) reject(TaskDiagnosticCode.TASK_LIMIT_EXCEEDED, Optional.empty(), Set.of());
        requireDistinct(command.blockedBy());
        if (command.blockedBy().size() > 32) reject(TaskDiagnosticCode.TASK_LIMIT_EXCEEDED, Optional.empty(), Set.of());
        for (TaskId blocker : command.blockedBy()) requireExistingDependency(blocker);
        TaskId id = new TaskId(highWaterMark + 1);
        Instant now = clock.instant();
        TaskItem item = new TaskItem(id, 1, TaskStatus.PENDING, command.subject(), command.description(),
                command.activeForm(), command.metadata(), new LinkedHashSet<>(command.blockedBy()),
                Optional.empty(), Optional.empty(), 0, now, now);
        NavigableMap<TaskId, TaskItem> candidate = new TreeMap<>(tasks);
        candidate.put(id, item);
        ensureAcyclic(candidate);
        highWaterMark = id.sequence();
        tasks.put(id, item);
        boardRevision++;
        return succeeded(id);
    }

    private TaskMutationResult edit(TaskBoardCapability capability, TaskMutation.Edit command) {
        TaskItem current = current(capability, command.taskId());
        requireTaskAccess(capability, current, true);
        requireTaskRevision(current, command.expectedTaskRevision());
        requireCurrentClaimEpoch(current, command.expectedClaimEpoch());
        String subject = command.subject().orElse(current.subject());
        String description = command.description().orElse(current.description());
        Optional<String> activeForm = command.activeFormSpecified() ? command.activeForm() : current.activeForm();
        TaskMetadata metadata = current.metadata().apply(command.metadataPatch());
        if (subject.equals(current.subject()) && description.equals(current.description())
                && activeForm.equals(current.activeForm()) && metadata.equals(current.metadata())) {
            reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
        }
        TaskItem updated = copy(current, current.revision() + 1, current.status(), subject, description, activeForm,
                metadata, current.blockedBy(), current.owner(), current.claim(), current.lastClaimEpoch());
        return commit(updated);
    }

    private TaskMutationResult transition(TaskBoardCapability capability, TaskMutation.Transition command) {
        TaskItem current = current(capability, command.taskId());
        requireTaskAccess(capability, current, false);
        requireTaskRevision(current, command.expectedTaskRevision());
        if (command.target() == TaskStatus.PENDING) {
            requireRoot(capability);
            if (current.status() != TaskStatus.COMPLETED) reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
            if (command.expectedClaimEpoch().isPresent()) reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
            TaskItem reopened = copy(current, current.revision() + 1, TaskStatus.PENDING,
                    current.subject(), current.description(), current.activeForm(), current.metadata(),
                    current.blockedBy(), current.owner(), Optional.empty(), current.lastClaimEpoch());
            return commit(reopened);
        }
        if (current.status() == TaskStatus.COMPLETED) reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
        Set<TaskId> blockers = activeBlockers(current);
        if (!blockers.isEmpty()) reject(TaskDiagnosticCode.TASK_BLOCKED, Optional.of(current.id()), blockers);
        if (current.status() == TaskStatus.PENDING) {
            requireRoot(capability);
            if (command.expectedClaimEpoch().isPresent()) reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
        } else {
            if (recoveryRequired(current)) reject(TaskDiagnosticCode.TASK_RECOVERY_REQUIRED, current);
            TaskClaim claim = current.claim().orElseThrow();
            if (command.expectedClaimEpoch().isEmpty()
                    || command.expectedClaimEpoch().getAsLong() != claim.epoch()) {
                reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
            }
            if (!capability.root() && !claim.actorId().equals(capability.actorId())) {
                reject(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, current);
            }
        }
        TaskItem completed = copy(current, current.revision() + 1, TaskStatus.COMPLETED,
                current.subject(), current.description(), current.activeForm(), current.metadata(),
                current.blockedBy(), current.owner(), Optional.empty(), current.lastClaimEpoch());
        return commit(completed);
    }

    private TaskMutationResult claim(TaskBoardCapability capability, TaskMutation.Claim command) {
        TaskItem current = current(capability, command.taskId());
        requireTaskAccess(capability, current, false);
        requireTaskRevision(current, command.expectedTaskRevision());
        if (current.status() == TaskStatus.IN_PROGRESS && recoveryRequired(current)) {
            reject(TaskDiagnosticCode.TASK_RECOVERY_REQUIRED, current);
        }
        if (current.status() != TaskStatus.PENDING || current.claim().isPresent()) {
            reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
        }
        Set<TaskId> blockers = activeBlockers(current);
        if (!blockers.isEmpty()) reject(TaskDiagnosticCode.TASK_BLOCKED, Optional.of(current.id()), blockers);
        if (current.owner().isPresent() && !current.owner().orElseThrow().equals(capability.actorId())) {
            reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
        }
        long epoch = current.lastClaimEpoch() + 1;
        Instant now = monotonicNow(current);
        TaskClaim claim = new TaskClaim(capability.actorId(), capability.actorRunId(), epoch, now);
        TaskItem claimed = new TaskItem(current.id(), current.revision() + 1, TaskStatus.IN_PROGRESS,
                current.subject(), current.description(), current.activeForm(), current.metadata(), current.blockedBy(),
                Optional.of(capability.actorId()), Optional.of(claim), epoch, current.createdAt(), now);
        return commit(claimed);
    }

    private TaskMutationResult resumeClaim(TaskBoardCapability capability, TaskMutation.ResumeClaim command) {
        requireRoot(capability);
        TaskItem current = current(capability, command.taskId());
        requireTaskRevision(current, command.expectedTaskRevision());
        TaskClaim previous = current.claim().orElseGet(() -> { reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current); return null; });
        if (current.status() != TaskStatus.IN_PROGRESS || !recoveryRequired(current)) {
            reject(TaskDiagnosticCode.TASK_RECOVERY_REQUIRED, current);
        }
        if (previous.epoch() != command.expectedClaimEpoch()) reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
        Set<TaskId> blockers = activeBlockers(current);
        if (!blockers.isEmpty()) reject(TaskDiagnosticCode.TASK_BLOCKED, Optional.of(current.id()), blockers);
        long epoch = current.lastClaimEpoch() + 1;
        Instant now = monotonicNow(current);
        TaskClaim resumed = new TaskClaim(capability.actorId(), capability.actorRunId(), epoch, now);
        TaskItem updated = new TaskItem(current.id(), current.revision() + 1, TaskStatus.IN_PROGRESS,
                current.subject(), current.description(), current.activeForm(), current.metadata(), current.blockedBy(),
                Optional.of(capability.actorId()), Optional.of(resumed), epoch, current.createdAt(), now);
        return commit(updated);
    }

    private TaskMutationResult release(TaskBoardCapability capability, TaskMutation.Release command) {
        TaskItem current = current(capability, command.taskId());
        requireTaskAccess(capability, current, false);
        requireTaskRevision(current, command.expectedTaskRevision());
        TaskClaim claim = current.claim().orElseGet(() -> { reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current); return null; });
        if (claim.epoch() != command.expectedClaimEpoch()) reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
        if (!capability.root() && !claim.actorId().equals(capability.actorId())) {
            reject(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, current);
        }
        TaskItem released = copy(current, current.revision() + 1, TaskStatus.PENDING,
                current.subject(), current.description(), current.activeForm(), current.metadata(),
                current.blockedBy(), current.owner(), Optional.empty(), current.lastClaimEpoch());
        return commit(released);
    }

    private TaskMutationResult assign(TaskBoardCapability capability, TaskMutation.Assign command) {
        requireRoot(capability);
        TaskItem current = current(capability, command.taskId());
        requireTaskRevision(current, command.expectedTaskRevision());
        if (current.status() != TaskStatus.PENDING || current.owner().isPresent() || current.claim().isPresent()) {
            reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
        }
        TaskItem assigned = copy(current, current.revision() + 1, current.status(), current.subject(),
                current.description(), current.activeForm(), current.metadata(), current.blockedBy(),
                Optional.of(command.targetActor()), Optional.empty(), current.lastClaimEpoch());
        return commit(assigned);
    }

    private TaskMutationResult reassign(TaskBoardCapability capability, TaskMutation.Reassign command) {
        requireRoot(capability);
        TaskItem current = current(capability, command.taskId());
        requireTaskRevision(current, command.expectedTaskRevision());
        if (current.status() == TaskStatus.PENDING) {
            if (current.owner().isEmpty()) reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
            if (command.expectedClaimEpoch().isPresent()) reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
            TaskItem reassigned = copy(current, current.revision() + 1, TaskStatus.PENDING,
                    current.subject(), current.description(), current.activeForm(), current.metadata(),
                    current.blockedBy(), Optional.of(command.targetActor()), Optional.empty(), current.lastClaimEpoch());
            return commit(reassigned);
        }
        TaskClaim claim = current.claim().orElseGet(() -> { reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current); return null; });
        if (current.status() != TaskStatus.IN_PROGRESS || !recoveryRequired(current)) {
            reject(TaskDiagnosticCode.TASK_RECOVERY_REQUIRED, current);
        }
        if (command.expectedClaimEpoch().isEmpty() || command.expectedClaimEpoch().getAsLong() != claim.epoch()) {
            reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, current);
        }
        TaskItem reassigned = copy(current, current.revision() + 1, TaskStatus.PENDING,
                current.subject(), current.description(), current.activeForm(), current.metadata(), current.blockedBy(),
                Optional.of(command.targetActor()), Optional.empty(), current.lastClaimEpoch());
        return commit(reassigned);
    }

    private TaskMutationResult dependency(TaskBoardCapability capability, TaskMutation.Dependency command) {
        requireRoot(capability);
        TaskItem current = current(capability, command.taskId());
        requireTaskRevision(current, command.expectedTaskRevision());
        requireBoardRevision(command.expectedBoardRevision());
        if (current.status() != TaskStatus.PENDING) reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
        if (command.addBlockedBy().size() + command.removeBlockedBy().size() > MAX_EDGE_CHANGES) {
            reject(TaskDiagnosticCode.TASK_LIMIT_EXCEEDED, current);
        }
        requireDistinct(command.addBlockedBy()); requireDistinct(command.removeBlockedBy());
        if (command.addBlockedBy().contains(current.id()) || command.removeBlockedBy().contains(current.id())) {
            reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, Optional.of(current.id()), Set.of(current.id()));
        }
        if (command.addBlockedBy().stream().anyMatch(command.removeBlockedBy()::contains)) {
            reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, current);
        }
        TreeSet<TaskId> nextEdges = new TreeSet<>(current.blockedBy());
        for (TaskId remove : command.removeBlockedBy()) {
            if (!nextEdges.remove(remove)) reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, Optional.of(current.id()), Set.of(remove));
        }
        for (TaskId add : command.addBlockedBy()) {
            requireExistingDependency(add);
            if (!nextEdges.add(add)) reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, Optional.of(current.id()), Set.of(add));
        }
        if (nextEdges.size() > 32) reject(TaskDiagnosticCode.TASK_LIMIT_EXCEEDED, current);
        if (nextEdges.equals(current.blockedBy())) reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, current);
        TaskItem updated = copy(current, current.revision() + 1, current.status(), current.subject(),
                current.description(), current.activeForm(), current.metadata(), nextEdges,
                current.owner(), current.claim(), current.lastClaimEpoch());
        NavigableMap<TaskId, TaskItem> candidate = new TreeMap<>(tasks);
        candidate.put(updated.id(), updated);
        try { ensureAcyclic(candidate); }
        catch (Rejected cycle) { reject(TaskDiagnosticCode.TASK_DEPENDENCY_CYCLE, Optional.of(current.id()), nextEdges); }
        return commit(updated);
    }

    private TaskMutationResult delete(TaskBoardCapability capability, TaskMutation.Delete command) {
        requireRoot(capability);
        TaskItem current = current(capability, command.taskId());
        requireTaskRevision(current, command.expectedTaskRevision());
        requireBoardRevision(command.expectedBoardRevision());
        if (current.status() == TaskStatus.IN_PROGRESS) reject(TaskDiagnosticCode.TASK_INVALID_TRANSITION, current);
        Set<TaskId> inbound = new TreeSet<>();
        tasks.values().stream().filter(task -> task.blockedBy().contains(current.id())).forEach(task -> inbound.add(task.id()));
        if (!inbound.isEmpty()) reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, Optional.of(current.id()), inbound);
        tasks.remove(current.id());
        tombstones.add(current.id());
        boardRevision++;
        return new TaskMutationResult(snapshotView(), Optional.empty(), Optional.empty());
    }

    private TaskMutationResult commit(TaskItem updated) {
        tasks.put(updated.id(), updated);
        boardRevision++;
        return succeeded(updated.id());
    }

    private TaskMutationResult succeeded(TaskId id) {
        TaskBoardSnapshot snapshot = snapshotView();
        return new TaskMutationResult(snapshot, snapshot.task(id), Optional.empty());
    }

    private TaskMutationResult rejected(TaskDiagnosticCode code, Optional<TaskId> taskId, Set<TaskId> related) {
        TaskBoardSnapshot snapshot = snapshotView();
        Optional<Long> taskRevision = code == TaskDiagnosticCode.TASK_CAPABILITY_DENIED
                ? Optional.empty() : taskId.map(tasks::get).map(TaskItem::revision);
        return new TaskMutationResult(snapshot, Optional.empty(), Optional.of(
                new TaskDiagnostic(code, taskId, boardRevision, taskRevision, related)));
    }

    private TaskBoardSnapshot snapshotView() {
        NavigableMap<TaskId, Set<TaskId>> blocks = new TreeMap<>();
        tasks.keySet().forEach(id -> blocks.put(id, new TreeSet<>()));
        tasks.values().forEach(task -> task.blockedBy().forEach(blocker -> {
            Set<TaskId> targets = blocks.get(blocker);
            if (targets != null) targets.add(task.id());
        }));
        LinkedHashMap<TaskId, TaskItemView> views = new LinkedHashMap<>();
        tasks.forEach((id, item) -> {
            Set<TaskId> active = activeBlockers(item);
            views.put(id, new TaskItemView(item, blocks.getOrDefault(id, Set.of()), !active.isEmpty(),
                    active, recoveryRequired(item)));
        });
        return new TaskBoardSnapshot(boardId, ownerSessionId, boardRevision, highWaterMark, views, tombstones);
    }

    private Set<TaskId> activeBlockers(TaskItem task) {
        TreeSet<TaskId> active = new TreeSet<>();
        for (TaskId blockerId : task.blockedBy()) {
            TaskItem blocker = tasks.get(blockerId);
            if (blocker != null && blocker.status() != TaskStatus.COMPLETED) active.add(blockerId);
        }
        return Collections.unmodifiableSet(active);
    }

    private boolean recoveryRequired(TaskItem task) {
        return task.status() == TaskStatus.IN_PROGRESS && task.claim().isPresent()
                && runState.terminated(task.claim().orElseThrow().runId());
    }

    private boolean readAllowed(TaskBoardCapability capability) {
        return boardId.equals(capability.boardId())
                && ownerSessionId.equals(capability.ownerSessionId())
                && capability.canRead();
    }

    private TaskDiagnostic readDenied(Optional<TaskId> taskId) {
        return new TaskDiagnostic(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, taskId,
                boardRevision, Optional.empty(), Set.of());
    }

    private static TaskSummary summary(TaskItemView view) {
        return new TaskSummary(view.id(), view.revision(), view.status(), view.subject(),
                view.blocked(), view.activeBlockers(), view.owner(), view.activeForm(),
                view.recoveryRequired());
    }

    private TaskItem current(TaskBoardCapability capability, TaskId id) {
        if (!capability.allows(id)) {
            reject(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, Optional.of(id), Set.of());
        }
        TaskItem task = tasks.get(id);
        if (task != null) return task;
        if (tombstones.contains(id)) reject(TaskDiagnosticCode.TASK_DELETED, Optional.of(id), Set.of());
        reject(TaskDiagnosticCode.TASK_NOT_FOUND, Optional.of(id), Set.of());
        throw new IllegalStateException("unreachable");
    }

    private void requireExistingDependency(TaskId id) {
        if (tombstones.contains(id)) reject(TaskDiagnosticCode.TASK_DELETED, Optional.of(id), Set.of());
        if (!tasks.containsKey(id)) reject(TaskDiagnosticCode.TASK_NOT_FOUND, Optional.of(id), Set.of());
    }

    private void requireTaskRevision(TaskItem task, long expected) {
        if (expected != task.revision()) reject(TaskDiagnosticCode.TASK_REVISION_CONFLICT, task);
    }

    private void requireBoardRevision(long expected) {
        if (expected != boardRevision) reject(TaskDiagnosticCode.TASK_BOARD_CONFLICT, Optional.empty(), Set.of());
    }

    private void requireCurrentClaimEpoch(TaskItem task, OptionalLong expectedClaimEpoch) {
        if (task.status() == TaskStatus.IN_PROGRESS) {
            TaskClaim claim = task.claim().orElseThrow();
            if (expectedClaimEpoch.isEmpty() || expectedClaimEpoch.getAsLong() != claim.epoch()) {
                reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, task);
            }
            if (recoveryRequired(task)) reject(TaskDiagnosticCode.TASK_RECOVERY_REQUIRED, task);
        } else if (expectedClaimEpoch.isPresent()) {
            reject(TaskDiagnosticCode.TASK_CLAIM_CONFLICT, task);
        }
    }

    private void requireRoot(TaskBoardCapability capability) {
        if (!capability.root()) reject(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, Optional.empty(), Set.of());
    }

    private void requireTaskAccess(TaskBoardCapability capability, TaskItem task, boolean requireOwnershipForChild) {
        if (!capability.allows(task.id())) reject(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, task);
        if (!capability.root() && requireOwnershipForChild
                && (task.owner().isEmpty() || !task.owner().orElseThrow().equals(capability.actorId()))) {
            reject(TaskDiagnosticCode.TASK_CAPABILITY_DENIED, task);
        }
    }

    private void requireDistinct(List<TaskId> ids) {
        if (new HashSet<>(ids).size() != ids.size()) {
            reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, Optional.empty(), Set.copyOf(ids));
        }
    }

    private void ensureAcyclic(Map<TaskId, TaskItem> candidate) {
        Map<TaskId, Visit> visits = new HashMap<>();
        for (TaskId id : candidate.keySet()) visit(id, candidate, visits);
    }

    private void visit(TaskId id, Map<TaskId, TaskItem> candidate, Map<TaskId, Visit> visits) {
        Visit state = visits.get(id);
        if (state == Visit.ACTIVE) reject(TaskDiagnosticCode.TASK_DEPENDENCY_CYCLE, Optional.of(id), Set.of(id));
        if (state == Visit.DONE) return;
        visits.put(id, Visit.ACTIVE);
        for (TaskId blocker : candidate.get(id).blockedBy()) {
            if (!candidate.containsKey(blocker)) reject(TaskDiagnosticCode.TASK_DEPENDENCY_INVALID, Optional.of(id), Set.of(blocker));
            visit(blocker, candidate, visits);
        }
        visits.put(id, Visit.DONE);
    }

    private void restore(List<TaskMutationEvent> events) {
        long expectedRevision = boardRevision;
        for (TaskMutationEvent event : events) {
            TaskBoardSnapshot snapshot = event.snapshot();
            if (!boardId.equals(snapshot.boardId()) || !ownerSessionId.equals(snapshot.ownerSessionId())
                    || snapshot.revision() != expectedRevision + 1) {
                throw new IllegalArgumentException("Task journal revision 或 identity 无效");
            }
            InvocationKey key = new InvocationKey(event.actorId(), event.actorSessionId(), event.actorRunId(),
                    event.mutation().callId());
            if (idempotency.putIfAbsent(key, new StoredResult(event.mutation(), event.result())) != null) {
                throw new IllegalArgumentException("Task journal 幂等 identity 重复");
            }
            install(snapshot);
            successfulMutations++;
            expectedRevision = snapshot.revision();
        }
    }

    private void install(TaskBoardSnapshot snapshot) {
        if (!boardId.equals(snapshot.boardId()) || !ownerSessionId.equals(snapshot.ownerSessionId())) {
            throw new IllegalArgumentException("Task snapshot identity 不匹配");
        }
        tasks.clear();
        snapshot.tasks().forEach((id, view) -> tasks.put(id, view.item()));
        tombstones.clear();
        tombstones.addAll(snapshot.tombstones());
        boardRevision = snapshot.revision();
        highWaterMark = snapshot.highWaterMark();
    }

    private StateMemento memento() {
        return new StateMemento(new TreeMap<>(tasks), new TreeSet<>(tombstones), boardRevision,
                highWaterMark, successfulMutations, new LinkedHashMap<>(idempotency));
    }

    private void restore(StateMemento state) {
        tasks.clear(); tasks.putAll(state.tasks());
        tombstones.clear(); tombstones.addAll(state.tombstones());
        boardRevision = state.boardRevision();
        highWaterMark = state.highWaterMark();
        successfulMutations = state.successfulMutations();
        idempotency.clear(); idempotency.putAll(state.idempotency());
    }

    private TaskItem copy(TaskItem current, long revision, TaskStatus status, String subject, String description,
            Optional<String> activeForm, TaskMetadata metadata, Set<TaskId> blockedBy,
            Optional<TaskActorId> owner, Optional<TaskClaim> claim, long lastClaimEpoch) {
        return new TaskItem(current.id(), revision, status, subject, description, activeForm, metadata, blockedBy,
                owner, claim, lastClaimEpoch, current.createdAt(), monotonicNow(current));
    }

    private Instant monotonicNow(TaskItem current) {
        Instant now = clock.instant();
        return now.isBefore(current.updatedAt()) ? current.updatedAt() : now;
    }

    private static void reject(TaskDiagnosticCode code, TaskItem task) {
        reject(code, Optional.of(task.id()), Set.of());
    }

    private static void reject(TaskDiagnosticCode code, Optional<TaskId> taskId, Set<TaskId> related) {
        throw new Rejected(code, taskId, related);
    }

    private enum Visit { ACTIVE, DONE }
    private record InvocationKey(TaskActorId actorId, SessionId actorSessionId, RunId actorRunId,
            TaskCallId callId) { }
    private record StoredResult(TaskMutation mutation, TaskMutationResult result) { }
    private record StateMemento(NavigableMap<TaskId, TaskItem> tasks, NavigableSet<TaskId> tombstones,
            long boardRevision, long highWaterMark, int successfulMutations,
            Map<InvocationKey, StoredResult> idempotency) { }
    private static final class Rejected extends RuntimeException {
        private final TaskDiagnosticCode code;
        private final Optional<TaskId> taskId;
        private final Set<TaskId> related;
        private Rejected(TaskDiagnosticCode code, Optional<TaskId> taskId, Set<TaskId> related) {
            this.code = code; this.taskId = taskId; this.related = Set.copyOf(related);
        }
    }
}
