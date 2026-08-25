package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.SessionJournal;
import io.github.liumaishenjian.ccjava.core.SessionRecoverySnapshot;
import io.github.liumaishenjian.ccjava.core.SessionStore;
import io.github.liumaishenjian.ccjava.core.SessionStoreAccess;
import io.github.liumaishenjian.ccjava.core.ToolResolutionReason;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

/**
 * 使用项目自有 append-only JSONL 和本机 exclusive file lock 的 S06 Session Store。
 *
 * <p>Store root 必须位于 Workspace 外部。每个可写打开持有 Session 专属 lock channel；journal 每条
 * 记录在返回前 {@link FileChannel#force(boolean)}。损坏读取、未知版本和 Workspace 错配均 Fail
 * Closed，Store 不自动截断或修复原文件。</p>
 *
 * @since 0.6.0
 */
public final class FileSessionStore implements SessionStore, SessionJournal,
        io.github.liumaishenjian.ccjava.core.task.TaskMutationJournal, AutoCloseable {

    private final Path root;
    private final Path workspace;
    private final String workspaceIdentity;
    private final AgentIdGenerator ids;
    private final LifecycleDispatcher lifecycle;
    private final HookCoordinator hooks;
    private final NewSessionFault newSessionFault;
    private final Clock clock;
    private final JsonlSessionCodec codec = new JsonlSessionCodec();
    private final Map<SessionId, OpenSession> writerSessions = new LinkedHashMap<>();
    private final Map<SessionId, io.github.liumaishenjian.ccjava.core.task.TaskListService> taskBoards =
            new LinkedHashMap<>();
    private final Map<SessionId, java.util.Set<RunId>> terminatedTaskRuns = new LinkedHashMap<>();
    private final List<OpenSession> inspectedSessions = new ArrayList<>();

    /**
     * 创建可注入 root 的持久 Store。
     *
     * @param root Workspace 外的本地私有存储根
     * @param workspace 已解析真实 Workspace
     * @param ids Session ID 来源
     * @param lifecycle 观察事件分发器
     * @param clock 时间来源
     */
    public FileSessionStore(
            Path root,
            Path workspace,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            Clock clock) {
        this(root, workspace, ids, lifecycle, clock, HookCoordinator.disabled(), NewSessionFault.none());
    }

    /**
     * 创建可选接入 S09 Session Start/End Hook 的持久 Store。
     *
     * <p>Hook 只接收 Session ID 摘要；它不参与 JSONL 写入、file lock 或恢复 Gate，
     * 也不能阻断 Session 创建和关闭。</p>
     *
     * @param root Workspace 外的本地私有存储根
     * @param workspace 已解析真实 Workspace
     * @param ids Session ID 来源
     * @param lifecycle 观察事件分发器
     * @param clock 时间来源
     * @param hooks Session Start/End Hook 协调器
     */
    public FileSessionStore(
            Path root,
            Path workspace,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            Clock clock,
            HookCoordinator hooks) {
        this(root, workspace, ids, lifecycle, clock, hooks, NewSessionFault.none());
    }

    /** 仅供同包故障注入测试模拟新 Session journal 已提交后的失败。 */
    FileSessionStore(
            Path root,
            Path workspace,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            Clock clock,
            HookCoordinator hooks,
            NewSessionFault newSessionFault) {
        this.root = Objects.requireNonNull(root, "root 不能为空")
                .toAbsolutePath().normalize();
        Path checkedWorkspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        try {
            this.workspace = checkedWorkspace.toRealPath();
        } catch (IOException failure) {
            throw new SessionOpenException("STORE_IO", "无法解析 Session Workspace");
        }
        this.workspaceIdentity = workspaceIdentity(this.workspace);
        this.ids = Objects.requireNonNull(ids, "ids 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        this.hooks = Objects.requireNonNull(hooks, "hooks 不能为空");
        this.newSessionFault = Objects.requireNonNull(newSessionFault, "newSessionFault 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        initializeRoot(checkedWorkspace);
    }

    /**
     * 按请求创建、继续、恢复、分叉或只读检查 Session。
     *
     * @param request 打开请求
     * @param createSpec Create/Fork 使用的当前安全 Session 配置
     * @return 打开结果
     */
    public synchronized SessionOpenResult open(
            SessionOpenRequest request,
            SessionSpec createSpec) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(createSpec, "createSpec 不能为空");
        return switch (request.mode()) {
            case CREATE -> createResult(createSpec, Optional.empty(), SessionOpenMode.CREATE);
            case CONTINUE -> resumeResult(latestSessionId(), SessionOpenMode.CONTINUE, false);
            case RESUME -> resumeResult(request.sessionId().orElseThrow(), SessionOpenMode.RESUME, false);
            case INSPECT -> resumeResult(request.sessionId().orElseThrow(), SessionOpenMode.INSPECT, true);
            case FORK -> forkResult(request.sessionId().orElseThrow(), createSpec);
        };
    }

    @Override
    public synchronized AgentSession create(SessionSpec spec) {
        return createResult(spec, Optional.empty(), SessionOpenMode.CREATE).session();
    }

    @Override
    public synchronized Optional<AgentSession> find(SessionId id) {
        OpenSession opened = writerSessions.get(Objects.requireNonNull(id, "id 不能为空"));
        return opened == null ? Optional.empty() : Optional.of(opened.session);
    }

    @Override
    public synchronized void close(SessionId id) {
        SessionId checkedId = Objects.requireNonNull(id, "id 不能为空");
        OpenSession opened = writerSessions.remove(checkedId);
        taskBoards.remove(checkedId);
        terminatedTaskRuns.remove(checkedId);
        if (opened == null) {
            throw new IllegalArgumentException("Session 不存在: " + id.value());
        }
        try {
            if (!opened.session.isClosed()) {
                SessionStoreAccess.closeSession(opened.session, lifecycle);
                hooks.evaluate(
                        new HookInvocation(
                                HookEventKind.SESSION_END,
                                opened.session.id(),
                                Optional.empty(),
                                opened.session.id().value(),
                                new JsonObject(Map.of(
                                        "sessionId", opened.session.id().value()))),
                        CancellationToken.none());
            }
        } finally {
            opened.release();
        }
    }

    @Override
    public synchronized void runStarted(SessionId sessionId, RunId runId, UserMessage message) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeRunStarted(opened.nextSequence, runId, message));
    }

    @Override
    public synchronized void assistantAppended(
            SessionId sessionId,
            RunId runId,
            AssistantMessage message) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeAssistant(opened.nextSequence, runId, message));
    }

    @Override
    public synchronized void toolResolved(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            ToolResult result,
            ToolResolutionReason reason) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeToolResolved(
                opened.nextSequence, runId, ordinal, result, reason.name()));
    }

    @Override
    public synchronized void toolStarted(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            String callId,
            String toolName,
            ToolEffect effect) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeToolStarted(
                opened.nextSequence, runId, ordinal, callId, toolName, effect));
    }

    @Override
    public synchronized void toolCompleted(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            ToolResult result) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeToolCompleted(
                opened.nextSequence, runId, ordinal, result));
    }

    @Override
    public synchronized void skillInvoked(SessionId sessionId, RunId runId, SkillInvocationKind kind,
            SkillRecoveryRecord record) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeSkillInvoked(opened.nextSequence, runId, kind, record));
    }

    @Override
    public synchronized void skillCompleted(SessionId sessionId, RunId runId, SkillId skillId,
            SkillInvocationKind kind, SkillErrorCode errorCode) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeSkillCompleted(
                opened.nextSequence, runId, skillId, kind, errorCode));
    }

    @Override
    public synchronized void planArtifactSaved(SessionId sessionId, PlanArtifact artifact) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodePlanArtifact(opened.nextSequence, artifact));
    }

    @Override
    public synchronized void runCompleted(
            SessionId sessionId,
            RunId runId,
            StopReason stopReason) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeRunCompleted(
                opened.nextSequence, runId, stopReason.name()));
        terminatedTaskRuns.computeIfAbsent(sessionId, ignored -> new java.util.HashSet<>()).add(runId);
    }

    /**
     * 在所属 Session writer lease 内可靠追加成功 Task mutation；失败时 fence Session。
     */
    @Override
    public synchronized void append(SessionId ownerSessionId,
            io.github.liumaishenjian.ccjava.core.task.TaskMutationEvent event) {
        OpenSession opened = writer(ownerSessionId);
        try {
            appendAndAdvance(opened, codec.encodeTaskMutation(opened.nextSequence, event));
        } catch (RuntimeException failure) {
            SessionStoreAccess.fenceSession(opened.session);
            throw failure;
        }
    }

    /** 返回仅在 durable writer 可用时存在的 Session-owned Task Board 服务。 */
    public synchronized Optional<io.github.liumaishenjian.ccjava.core.task.TaskListService> taskBoard(SessionId id) {
        OpenSession opened = writerSessions.get(Objects.requireNonNull(id, "id 不能为空"));
        if (opened == null || opened.readOnly || opened.session.isFenced()) return Optional.empty();
        return Optional.ofNullable(taskBoards.get(id));
    }

    /**
     * 在 Tool started 前追加 durable Checkpoint 创建事实。
     *
     * @param sessionId 目标 Session
     * @param runId 当前 Run
     * @param ordinal Tool ordinal
     * @param summary 安全 Checkpoint 摘要
     * @param preDigest pre-image digest 或固定 {@code ABSENT}
     */
    /** 持久化 Session-owned Plan 的完整安全投影；调用者必须在每次状态迁移后调用。 */
    public synchronized void planSnapshot(
            SessionId sessionId, PlanDocument document, PlanExecutionState state) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodePlanSnapshot(opened.nextSequence, document, state));
    }

    public synchronized void checkpointCreated(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            io.github.liumaishenjian.ccjava.domain.CheckpointSummary summary,
            String preDigest) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeCheckpointCreated(
                opened.nextSequence, runId, ordinal, summary, preDigest));
    }

    /**
     * 在 Tool completed 前追加类型化 post-state 事实。
     *
     * @param sessionId 目标 Session
     * @param runId 当前 Run
     * @param ordinal Tool ordinal
     * @param checkpointId 对应 Checkpoint
     * @param postDigest 普通文件 post-image digest
     * @param postAbsent post-state 已知为不存在时为 {@code true}
     */
    public synchronized void checkpointCompleted(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            io.github.liumaishenjian.ccjava.domain.CheckpointId checkpointId,
            Optional<String> postDigest,
            boolean postAbsent) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeCheckpointCompleted(
                opened.nextSequence,
                runId,
                ordinal,
                checkpointId,
                postDigest,
                postAbsent));
    }

    /**
     * 显式 Undo 成功后追加唯一 durable 事实。
     *
     * @param sessionId 目标 Session
     * @param checkpointId 已恢复的 Checkpoint
     */
    public synchronized void checkpointUndoCompleted(
            SessionId sessionId,
            io.github.liumaishenjian.ccjava.domain.CheckpointId checkpointId) {
        OpenSession opened = writer(sessionId);
        appendAndAdvance(opened, codec.encodeCheckpointUndoCompleted(
                opened.nextSequence, checkpointId));
    }

    /**
     * 关闭所有本进程打开的 Session 并释放锁。
     */
    @Override
    public synchronized void close() {
        for (OpenSession session : new ArrayList<>(writerSessions.values())) {
            try {
                close(session.session.id());
            } catch (RuntimeException ignored) {
                session.release();
            }
        }
        writerSessions.clear();
        for (OpenSession inspected : new ArrayList<>(inspectedSessions)) {
            if (!inspected.session.isClosed()) {
                SessionStoreAccess.discardRecoveredSession(inspected.session);
            }
            inspected.release();
        }
        inspectedSessions.clear();
    }

    private SessionOpenResult createResult(
            SessionSpec spec,
            Optional<SessionId> parent,
            SessionOpenMode mode) {
        SessionId id = Objects.requireNonNull(ids.newSessionId(), "newSessionId 返回 null");
        validateSessionId(id);
        if (Files.exists(sessionDirectory(id), LinkOption.NOFOLLOW_LINKS)) {
            throw new SessionOpenException("DUPLICATE_ID", "Session ID 已存在");
        }
        OpenSession opened = acquireWriter(id, AgentSession.create(id, spec), 1);
        try {
            append(opened, codec.encodeSessionCreated(
                    opened.nextSequence, id, spec, workspaceIdentity, parent));
            opened.nextSequence++;
            newSessionFault.afterJournalWritten(id);
            writerSessions.put(id, opened);
            terminatedTaskRuns.put(id, new java.util.HashSet<>());
            taskBoards.put(id, newTaskBoard(id, List.of(), Optional.empty()));
            lifecycle.dispatch(opened.session, new LifecycleEvent.SessionStarted(spec));
            hooks.evaluate(
                    new HookInvocation(
                            HookEventKind.SESSION_START,
                            id,
                            Optional.empty(),
                            id.value(),
                            new JsonObject(Map.of("sessionId", id.value()))),
                    CancellationToken.none());
            newSessionFault.afterSessionStarted(id);
            return new SessionOpenResult(opened.session, mode, parent, false, List.of(), List.of());
        } catch (RuntimeException failure) {
            writerSessions.remove(id, opened);
            taskBoards.remove(id);
            terminatedTaskRuns.remove(id);
            opened.release();
            rollbackNewSession(id);
            throw failure;
        }
    }

    private SessionOpenResult resumeResult(
            SessionId id,
            SessionOpenMode mode,
            boolean readOnly) {
        validateSessionId(id);
        JournalRead read = readJournal(id);
        SessionRecoverySnapshot snapshot = codec.replay(
                read.lines, read.damagedTail, workspaceIdentity);
        synchronizePlanArtifact(snapshot);
        if (snapshot.planArtifact().filter(artifact -> artifact.status() == PlanStatus.EXECUTING).isPresent()) {
            List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> merged =
                    new ArrayList<>(snapshot.issues());
            merged.add(io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue.session(
                    io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind.PLAN_EXECUTION_RECOVERY));
            snapshot = new SessionRecoverySnapshot(snapshot.sessionId(), snapshot.spec(), snapshot.messages(),
                    snapshot.runIds(), snapshot.parentSessionId(), merged, snapshot.skillRecords(), snapshot.plan(),
                    snapshot.planArtifact());
        }
        if (snapshot.plan().isPresent()
                && snapshot.plan().orElseThrow().state().activeStep() != null) {
            List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> merged =
                    new ArrayList<>(snapshot.issues());
            merged.add(io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue.session(
                    io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind.PLAN_ACTIVE_STEP_RECOVERY));
            snapshot = new SessionRecoverySnapshot(snapshot.sessionId(), snapshot.spec(), snapshot.messages(),
                    snapshot.runIds(), snapshot.parentSessionId(), merged, snapshot.skillRecords(), snapshot.plan(),
                    snapshot.planArtifact());
        }
        List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> checkpointIssues =
                new FileCheckpointCoordinator(root, workspaceGuard(), this)
                        .recoveryIssues(id);
        if (!checkpointIssues.isEmpty()) {
            List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> merged =
                    new ArrayList<>(snapshot.issues());
            merged.addAll(checkpointIssues);
            snapshot = new SessionRecoverySnapshot(
                    snapshot.sessionId(),
                    snapshot.spec(),
                    snapshot.messages(),
                    snapshot.runIds(),
                    snapshot.parentSessionId(),
                    merged,
                    snapshot.skillRecords(),
                    snapshot.plan(),
                    snapshot.planArtifact());
        }
        AgentSession session = AgentSession.restore(snapshot);
        if (readOnly) {
            List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> issues =
                    inspectIssues(snapshot);
            session = AgentSession.restore(new SessionRecoverySnapshot(
                    snapshot.sessionId(),
                    snapshot.spec(),
                    snapshot.messages(),
                    snapshot.runIds(),
                    snapshot.parentSessionId(),
                    issues,
                    snapshot.skillRecords(),
                    snapshot.plan(),
                    snapshot.planArtifact()));
            OpenSession inspected = OpenSession.readOnly(session, read.nextSequence);
            inspectedSessions.add(inspected);
            return new SessionOpenResult(
                    session,
                    mode,
                    snapshot.parentSessionId(),
                    true,
                    issues,
                    snapshot.skillRecords());
        }
        if (!snapshot.issues().isEmpty()) {
            throw new SessionOpenException(
                    "RECOVERY_REQUIRED",
                    "Session 存在未完成或损坏记录，只能只读检查");
        }
        OpenSession opened = acquireWriter(id, session, read.nextSequence);
        writerSessions.put(id, opened);
        terminatedTaskRuns.put(id, new java.util.HashSet<>(snapshot.runIds()));
        JsonlSessionCodec.TaskJournalProjection taskProjection = codec.replayTaskBoard(read.lines);
        taskBoards.put(id, newTaskBoard(id, taskProjection.events(), taskProjection.seed()));
        return new SessionOpenResult(
                session, mode, snapshot.parentSessionId(), false, snapshot.issues(), snapshot.skillRecords());
    }

    private List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> inspectIssues(
            SessionRecoverySnapshot snapshot) {
        List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> issues =
                new ArrayList<>(snapshot.issues());
        issues.add(io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue.session(
                io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind.READ_ONLY_INSPECT));
        return List.copyOf(issues);
    }

    private SessionOpenResult forkResult(SessionId sourceId, SessionSpec createSpec) {
        validateSessionId(sourceId);
        JournalRead read = readJournal(sourceId);
        SessionRecoverySnapshot source = codec.replay(
                read.lines, read.damagedTail, workspaceIdentity);
        List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> checkpointIssues =
                new FileCheckpointCoordinator(root, workspaceGuard(), this)
                        .recoveryIssues(sourceId);
        if (!checkpointIssues.isEmpty()) {
            List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue> merged =
                    new ArrayList<>(source.issues());
            merged.addAll(checkpointIssues);
            source = new SessionRecoverySnapshot(
                    source.sessionId(),
                    source.spec(),
                    source.messages(),
                    source.runIds(),
                    source.parentSessionId(),
                    merged,
                    source.skillRecords(),
                    source.plan(),
                    source.planArtifact());
        }
        if (!source.issues().isEmpty()) {
            throw new SessionOpenException(
                    "RECOVERY_REQUIRED", "存在恢复问题的 Session 不能直接 Fork");
        }
        SessionId targetId = Objects.requireNonNull(ids.newSessionId(), "newSessionId 返回 null");
        validateSessionId(targetId);
        String forkPlanId = "plan-" + targetId.value().substring("session-".length());
        Optional<PlanArtifact> forkArtifact = source.planArtifact().map(artifact ->
                artifact.fork(forkPlanId, targetId, java.time.Instant.now()));
        Optional<io.github.liumaishenjian.ccjava.core.PlanRecoveryProjection> forkPlan =
                source.plan().map(projection -> {
                    PlanDocument sourceDocument = projection.document();
                    PlanDocument document = new PlanDocument(
                            forkPlanId,
                            sourceDocument.objective(),
                            sourceDocument.steps(),
                            PlanStatus.AWAITING_APPROVAL,
                            sourceDocument.workspaceDigest());
                    PlanExecutionState state = new PlanExecutionState(
                            forkPlanId,
                            io.github.liumaishenjian.ccjava.domain.PlanApprovalGate.PENDING,
                            1,
                            null,
                            PlanStatus.AWAITING_APPROVAL,
                            sourceDocument.workspaceDigest());
                    return new io.github.liumaishenjian.ccjava.core.PlanRecoveryProjection(document, state);
                });
        if (Files.exists(sessionDirectory(targetId), LinkOption.NOFOLLOW_LINKS)) {
            throw new SessionOpenException("DUPLICATE_ID", "Session ID 已存在");
        }
        JsonlSessionCodec.TaskJournalProjection sourceTasks = codec.replayTaskBoard(read.lines);
        io.github.liumaishenjian.ccjava.core.task.TaskListService sourceBoard =
                newTaskBoard(sourceId, sourceTasks.events(), sourceTasks.seed());
        io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed forkSeed =
                io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed.fork(sourceBoard.snapshot(),
                        boardId(targetId), targetId);
        List<ObjectNode> records = new ArrayList<>(codec.forkRecords(
                read.lines,
                targetId,
                createSpec,
                workspaceIdentity,
                sourceId));
        records.removeIf(record -> {
            String type = record.path("recordType").stringValue();
            return "plan.artifact.saved".equals(type) || "plan.snapshot".equals(type)
                    || "task.mutation.succeeded".equals(type) || "task.board.forked".equals(type);
        });
        for (int index = 0; index < records.size(); index++) records.get(index).put("sequence", index + 1L);
        if (forkArtifact.isPresent() && forkPlan.isPresent()) {
            var projection = forkPlan.orElseThrow();
            records.add(codec.encodePlanCommit(records.size() + 1L, forkArtifact.orElseThrow(),
                    projection.document(), projection.state()));
        } else {
            forkArtifact.ifPresent(artifact ->
                    records.add(codec.encodePlanArtifact(records.size() + 1L, artifact)));
            forkPlan.ifPresent(projection -> records.add(codec.encodePlanSnapshot(
                    records.size() + 1L, projection.document(), projection.state())));
        }
        records.add(codec.encodeTaskBoardSeed(records.size() + 1L, forkSeed));
        SessionRecoverySnapshot targetSnapshot = codec.replay(
                records.stream().map(codec::encode).toList(),
                false,
                workspaceIdentity);
        AgentSession targetSession = AgentSession.restore(targetSnapshot);
        OpenSession target = acquireWriter(targetId, targetSession, 1);
        try {
            for (ObjectNode record : records) {
                append(target, record);
                target.nextSequence++;
            }
            newSessionFault.afterJournalWritten(targetId);
            writerSessions.put(targetId, target);
            terminatedTaskRuns.put(targetId, new java.util.HashSet<>(targetSnapshot.runIds()));
            taskBoards.put(targetId, io.github.liumaishenjian.ccjava.core.task.TaskListService.fromSeed(
                    forkSeed, clock, runId -> terminatedTaskRuns.getOrDefault(targetId, Set.of()).contains(runId), this));
            forkArtifact.ifPresent(artifact -> planArtifacts(targetId).restoreMissing(artifact));
            newSessionFault.afterArtifactRestored(targetId);
            lifecycle.dispatch(targetSession, new LifecycleEvent.SessionStarted(createSpec));
            hooks.evaluate(
                    new HookInvocation(
                            HookEventKind.SESSION_START,
                            targetId,
                            Optional.empty(),
                            targetId.value(),
                            new JsonObject(Map.of("sessionId", targetId.value()))),
                    CancellationToken.none());
            newSessionFault.afterSessionStarted(targetId);
            return new SessionOpenResult(
                    targetSession,
                    SessionOpenMode.FORK,
                    Optional.of(sourceId),
                    false,
                    List.of(),
                    targetSnapshot.skillRecords());
        } catch (RuntimeException failure) {
            writerSessions.remove(targetId, target);
            taskBoards.remove(targetId);
            terminatedTaskRuns.remove(targetId);
            target.release();
            rollbackNewSession(targetId);
            throw failure;
        }
    }

    /**
     * 先 durable 准备不可变 generation，再提交 canonical journal，最后原子切换本地 manifest；
     * 任一步失败都会 fence 当前 Session，恢复时由 journal 确定 projection。
     *
     * @param artifact 完整新 revision
     * @param expectedRevision 预期旧 revision；创建为 0
     * @param expectedDigest 预期旧正文摘要；创建为空
     * @return 已提交并重读的工件
     */
    public synchronized PlanArtifact savePlanArtifact(
            PlanArtifact artifact, long expectedRevision, String expectedDigest) {
        return savePlanArtifact(artifact, null, null, expectedRevision, expectedDigest);
    }

    /**
     * 把 artifact 与兼容 Plan projection 作为单条 canonical journal 事实提交，消除两个
     * JSONL append 之间的永久不一致窗口；随后再切换可重建 manifest。
     *
     * @param artifact 完整新 revision
     * @param document 同一状态的兼容 PlanDocument；可为空表示只有 Markdown artifact
     * @param state 与 document 匹配的执行状态；document 为空时也必须为空
     * @param expectedRevision 预期旧 revision；创建为 0
     * @param expectedDigest 预期旧正文摘要；创建为空
     * @return 已经进入 canonical journal 并发布 projection 的工件
     */
    public synchronized PlanArtifact savePlanArtifact(
            PlanArtifact artifact,
            PlanDocument document,
            PlanExecutionState state,
            long expectedRevision,
            String expectedDigest) {
        if ((document == null) != (state == null)) {
            throw new IllegalArgumentException("document 与 state 必须同时存在或同时缺失");
        }
        OpenSession opened = writer(artifact.sessionId());
        FilePlanArtifactStore store = planArtifacts(artifact.sessionId());
        // prepare 与编码都发生在 journal append 前；确定性拒绝不制造提交不确定性，也不 fence Writer。
        FilePlanArtifactStore.PreparedArtifact prepared =
                store.prepare(artifact, expectedRevision, expectedDigest);
        ObjectNode commit = document == null
                ? codec.encodePlanArtifact(opened.nextSequence, artifact)
                : codec.encodePlanCommit(opened.nextSequence, artifact, document, state);
        try {
            appendAndAdvance(opened, commit);
            store.commit(prepared);
            return artifact;
        } catch (RuntimeException failure) {
            SessionStoreAccess.fenceSession(opened.session);
            throw failure;
        }
    }

    /**
     * 返回绑定指定 Session 私有目录的 PlanArtifact store。
     *
     * @param id 已验证的 Session 身份
     * @return 不接受调用方路径的固定布局 store
     */
    public synchronized FilePlanArtifactStore planArtifacts(SessionId id) {
        validateSessionId(id);
        return new FilePlanArtifactStore(sessionDirectory(id), id);
    }

    private void synchronizePlanArtifact(SessionRecoverySnapshot snapshot) {
        FilePlanArtifactStore artifacts = planArtifacts(snapshot.sessionId());
        Optional<PlanArtifact> local;
        try {
            local = artifacts.load(snapshot.sessionId());
        } catch (io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException corrupt) {
            throw new SessionOpenException("PLAN_ARTIFACT_CORRUPT", "Plan artifact 投影损坏，拒绝恢复");
        }
        try {
            if (snapshot.planArtifact().isEmpty()) {
                // Journal 没有 Plan 事实时，合法本地 manifest 只能来自未完成提交；移除权威指针，
                // generation 作为安全 orphan 留待有界清理，不能永久阻塞普通 Session 恢复。
                local.ifPresent(artifacts::discardUnjournaled);
                return;
            }
            PlanArtifact authoritative = snapshot.planArtifact().orElseThrow();
            if (!local.equals(snapshot.planArtifact())) {
                // Journal 是跨文件崩溃恢复的 canonical source。缺失、落后或领先投影都只重建指针，
                // 不执行 Plan、不恢复活动 Tool，也不自动重放任何副作用。
                artifacts.restoreAuthoritative(authoritative);
            }
        } catch (io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException failure) {
            throw new SessionOpenException("PLAN_ARTIFACT_RECOVERY_FAILED", "Plan artifact 投影无法从 journal 收敛");
        }
    }

    private OpenSession acquireWriter(
            SessionId id,
            AgentSession session,
            long nextSequence) {
        if (writerSessions.containsKey(id)) {
            throw new SessionOpenException("SESSION_ACTIVE", "Session 已由当前 Store 的 Writer 打开");
        }
        FileChannel lockChannel = null;
        FileLock lock = null;
        FileChannel journalChannel = null;
        try {
            Files.createDirectories(sessionDirectory(id));
            rejectLink(sessionDirectory(id));
            lockChannel = FileChannel.open(
                    lockPath(id),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                lock = null;
            }
            if (lock == null) {
                throw new SessionOpenException("SESSION_ACTIVE", "Session 已由另一个 Writer 打开");
            }
            journalChannel = FileChannel.open(
                    journalPath(id),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            OpenSession opened = new OpenSession(
                    session, false, nextSequence, journalChannel, lockChannel, lock);
            journalChannel = null;
            lockChannel = null;
            lock = null;
            return opened;
        } catch (SessionOpenException known) {
            releaseOpenResources(journalChannel, lock, lockChannel);
            throw known;
        } catch (IOException exception) {
            releaseOpenResources(journalChannel, lock, lockChannel);
            throw new SessionOpenException("STORE_IO", "无法安全打开 Session Store");
        } catch (RuntimeException failure) {
            releaseOpenResources(journalChannel, lock, lockChannel);
            throw failure;
        }
    }

    private static void releaseOpenResources(
            FileChannel journalChannel,
            FileLock lock,
            FileChannel lockChannel) {
        try {
            if (journalChannel != null) {
                journalChannel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
        }
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
        }
    }

    private JournalRead readJournal(SessionId id) {
        Path journal = journalPath(id);
        rejectLink(sessionDirectory(id));
        rejectLink(journal);
        try {
            if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                throw new SessionOpenException("SESSION_NOT_FOUND", "Session 不存在");
            }
            long size = Files.size(journal);
            if (size > JsonlSessionCodec.MAX_FILE_BYTES) {
                throw new SessionOpenException("LIMIT_EXCEEDED", "Session journal 超过大小上限");
            }
            byte[] bytes = Files.readAllBytes(journal);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new SessionOpenException("INVALID_UTF8", "Session journal 不是严格 UTF-8");
            }
            boolean endsWithNewline = bytes.length == 0 || bytes[bytes.length - 1] == '\n';
            String[] raw = text.split("\\n", -1);
            List<String> lines = new ArrayList<>();
            boolean damagedTail = false;
            int last = endsWithNewline ? raw.length - 1 : raw.length;
            for (int index = 0; index < last; index++) {
                String line = raw[index].endsWith("\r")
                        ? raw[index].substring(0, raw[index].length() - 1)
                        : raw[index];
                if (line.isEmpty()) {
                    throw new SessionOpenException("INVALID_RECORD", "Session journal 包含空记录");
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > JsonlSessionCodec.MAX_LINE_BYTES) {
                    throw new SessionOpenException("LIMIT_EXCEEDED", "Session record 超过行大小上限");
                }
                if (!endsWithNewline && index == last - 1) {
                    try {
                        codec.decode(line);
                    } catch (SessionOpenException malformed) {
                        damagedTail = true;
                        break;
                    }
                }
                lines.add(line);
            }
            long nextSequence = lines.size() + 1L;
            return new JournalRead(lines, damagedTail, nextSequence);
        } catch (SessionOpenException known) {
            throw known;
        } catch (IOException exception) {
            throw new SessionOpenException("STORE_IO", "无法安全读取 Session journal");
        }
    }

    private SessionId latestSessionId() {
        try (var paths = Files.list(root)) {
            return paths
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> Files.isRegularFile(
                            path.resolve("session.jsonl"), LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .map(path -> new SessionId(path.getFileName().toString()))
                    .filter(id -> {
                        try {
                            JournalRead read = readJournal(id);
                            SessionRecoverySnapshot snapshot = codec.replay(
                                    read.lines,
                                    read.damagedTail,
                                    workspaceIdentity);
                            return snapshot.issues().isEmpty()
                                    && new FileCheckpointCoordinator(root, workspaceGuard(), this)
                                            .recoveryIssues(id)
                                            .isEmpty();
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElseThrow(() -> new SessionOpenException(
                            "SESSION_NOT_FOUND", "当前 Workspace 没有可继续 Session"));
        } catch (IOException exception) {
            throw new SessionOpenException("STORE_IO", "无法枚举 Session Store");
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path.resolve("session.jsonl")).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard workspaceGuard() {
        try {
            return new io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard(workspace);
        } catch (IOException failure) {
            throw new SessionOpenException("STORE_IO", "无法验证 Session Workspace");
        }
    }

    /**
     * 在执行显式 Undo 前验证 Writer lease、fence 与活动 Run 互斥。
     *
     * @param id 目标 Session
     */
    public synchronized void requireUndoAllowed(SessionId id) {
        OpenSession opened = writer(id);
        if (SessionStoreAccess.hasActiveRun(opened.session)) {
            throw new SessionOpenException("SESSION_ACTIVE_RUN", "存在活动 Run 时不能 Undo");
        }
    }

    private OpenSession writer(SessionId id) {
        OpenSession opened = writerSessions.get(Objects.requireNonNull(id, "id 不能为空"));
        if (opened == null || opened.readOnly || opened.session.isFenced()) {
            throw new SessionOpenException("SESSION_FENCED", "Session 没有可写 lease");
        }
        return opened;
    }

    private void appendAndAdvance(OpenSession opened, ObjectNode record) {
        append(opened, record);
        opened.nextSequence++;
    }

    private void append(OpenSession opened, ObjectNode record) {
        byte[] bytes = (codec.encode(record) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JsonlSessionCodec.MAX_LINE_BYTES) {
            throw new SessionOpenException("LIMIT_EXCEEDED", "Session record 超过行大小上限");
        }
        try {
            if (opened.channel.size() + bytes.length > JsonlSessionCodec.MAX_FILE_BYTES) {
                throw new SessionOpenException("LIMIT_EXCEEDED", "Session journal 超过大小上限");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                opened.channel.write(buffer);
            }
            opened.channel.force(true);
        } catch (SessionOpenException known) {
            throw known;
        } catch (IOException exception) {
            throw new SessionOpenException("STORE_IO", "Session record 未可靠持久化");
        }
    }

    private void initializeRoot(Path workspace) {
        try {
            Path realWorkspace = workspace.toRealPath();
            Files.createDirectories(root);
            rejectLink(root);
            Path realRoot = root.toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new SessionOpenException("INVALID_ROOT", "Session Store root 不是普通目录");
            }
            if (realRoot.startsWith(realWorkspace)) {
                throw new SessionOpenException("INVALID_ROOT", "Session Store root 必须位于 Workspace 外");
            }
        } catch (SessionOpenException known) {
            throw known;
        } catch (IOException exception) {
            throw new SessionOpenException("STORE_IO", "无法初始化 Session Store");
        }
    }

    private Path sessionDirectory(SessionId id) {
        Path path = root.resolve(id.value()).normalize();
        if (!path.getParent().equals(root)) {
            throw new SessionOpenException("INVALID_SESSION_ID", "Session ID 不能形成路径");
        }
        return path;
    }

    private Path journalPath(SessionId id) {
        return sessionDirectory(id).resolve("session.jsonl");
    }

    private Path lockPath(SessionId id) {
        return sessionDirectory(id).resolve("writer.lock");
    }

    private void validateSessionId(SessionId id) {
        String value = Objects.requireNonNull(id, "id 不能为空").value();
        if (value.length() > 128 || !value.matches("session-[A-Za-z0-9-]+")) {
            throw new SessionOpenException("INVALID_SESSION_ID", "Session ID 格式无效");
        }
    }

    private void rejectLink(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            throw new SessionOpenException("LINK_NOT_ALLOWED", "Session Store 不接受链接");
        }
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        boolean regularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!directory && !regularFile) {
            throw new SessionOpenException("PATH_TYPE", "Session Store 路径类型不受支持");
        }
        try {
            Path real = path.toRealPath();
            if (!real.equals(path.toAbsolutePath().normalize())) {
                throw new SessionOpenException("LINK_NOT_ALLOWED", "Session Store 不接受 Junction 或重解析路径");
            }
        } catch (SessionOpenException known) {
            throw known;
        } catch (IOException exception) {
            throw new SessionOpenException("STORE_IO", "无法验证 Session Store 路径");
        }
    }

    /**
     * 回滚本次调用独占创建、但尚未成功返回的新 Session 目录。
     *
     * <p>调用方在创建前已确认整个目标目录不存在。本方法只枚举该一级目录，并只接受本 Store
     * 可能创建的固定文件名；遇到链接、重解析点、未知文件或删除失败立即停止。journal 最后删除，
     * 因而不能完成精确回滚时仍保留可由 Resume 发现的 canonical Session，而不会递归宽删、
     * 触碰 Fork source 或自动重放任何副作用。</p>
     */
    private void rollbackNewSession(SessionId id) {
        Path directory = sessionDirectory(id);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            rejectLink(directory);
            List<Path> entries;
            try (var listed = Files.list(directory)) {
                entries = listed.limit(129).toList();
            }
            if (entries.size() > 128 || entries.stream().anyMatch(path -> !rollbackFileAllowed(directory, path))) return;
            Path journal = journalPath(id);
            Path lock = lockPath(id);
            Path manifest = directory.resolve(FilePlanArtifactStore.MANIFEST_FILE);
            // 先撤掉非 canonical 指针。之后即使 generation 删除中断，journal 仍可重建投影。
            Files.deleteIfExists(manifest);
            for (Path entry : entries) {
                if (!entry.equals(journal) && !entry.equals(lock) && !entry.equals(manifest)) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(lock);
            Files.deleteIfExists(journal);
            Files.deleteIfExists(directory);
        } catch (RuntimeException | IOException ignored) {
            // journal 在已知 projection 文件之前不会删除；失败时保留可发现、可恢复的新 Session。
        }
    }

    private boolean rollbackFileAllowed(Path directory, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(directory) || !normalized.equals(path)) return false;
        String name = path.getFileName().toString();
        boolean known = name.equals("session.jsonl") || name.equals("writer.lock")
                || name.equals(FilePlanArtifactStore.MANIFEST_FILE)
                || name.matches("plan-r[1-9][0-9]{0,18}-[0-9a-f]{64}\\.md")
                || name.matches("\\.plan-(?:content|manifest|discard)-[A-Za-z0-9-]+\\.tmp");
        if (!known || Files.isSymbolicLink(path)) return false;
        rejectLink(path);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private io.github.liumaishenjian.ccjava.core.task.TaskListService newTaskBoard(SessionId id,
            List<io.github.liumaishenjian.ccjava.core.task.TaskMutationEvent> events,
            Optional<io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed> seed) {
        io.github.liumaishenjian.ccjava.core.task.TaskRunState runState = runId ->
                terminatedTaskRuns.getOrDefault(id, Set.of()).contains(runId);
        if (seed.isPresent()) {
            io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed value = seed.orElseThrow();
            if (!value.snapshot().ownerSessionId().equals(id) || !value.snapshot().boardId().equals(boardId(id))) {
                throw new SessionOpenException("INVALID_RECORD", "Task fork Board identity 无效");
            }
            return io.github.liumaishenjian.ccjava.core.task.TaskListService.fromSeed(
                    value, clock, runState, this, events);
        }
        return new io.github.liumaishenjian.ccjava.core.task.TaskListService(
                boardId(id), id, clock, runState, this, events);
    }

    private static io.github.liumaishenjian.ccjava.domain.task.TaskBoardId boardId(SessionId id) {
        String suffix = id.value().startsWith("session-") ? id.value().substring("session-".length()) : id.value();
        return new io.github.liumaishenjian.ccjava.domain.task.TaskBoardId("board-" + suffix);
    }

    private static String workspaceIdentity(Path workspace) {
        try {
            Path real = workspace.toRealPath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(real.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new SessionOpenException("WORKSPACE_IDENTITY", "无法计算 Workspace identity");
        }
    }

    @FunctionalInterface
    interface NewSessionFault {
        void afterJournalWritten(SessionId sessionId);

        default void afterArtifactRestored(SessionId sessionId) {
        }

        default void afterSessionStarted(SessionId sessionId) {
        }

        static NewSessionFault none() {
            return ignored -> { };
        }
    }

    private record JournalRead(List<String> lines, boolean damagedTail, long nextSequence) {
        private JournalRead {
            lines = List.copyOf(lines);
        }
    }

    private static final class OpenSession {
        private final AgentSession session;
        private final boolean readOnly;
        private long nextSequence;
        private final FileChannel channel;
        private final FileChannel lockChannel;
        private final FileLock lock;

        private OpenSession(
                AgentSession session,
                boolean readOnly,
                long nextSequence,
                FileChannel channel,
                FileChannel lockChannel,
                FileLock lock) {
            this.session = session;
            this.readOnly = readOnly;
            this.nextSequence = nextSequence;
            this.channel = channel;
            this.lockChannel = lockChannel;
            this.lock = lock;
        }

        private static OpenSession readOnly(AgentSession session, long nextSequence) {
            return new OpenSession(session, true, nextSequence, null, null, null);
        }

        private void release() {
            try {
                if (lock != null && lock.isValid()) {
                    lock.release();
                }
            } catch (IOException ignored) {
            }
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (lockChannel != null) {
                    lockChannel.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}
