package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentEvent;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 保存当前进程内 Session 的规范消息历史和有序事件。
 *
 * <p>该类型不提供持久化、锁或崩溃恢复。所有修改方法保持包内可见，
 * 外部只能读取不可变快照；规范写入由 {@link AgentRuntime}、
 * {@link ToolExecutionPipeline} 和 {@link LifecycleDispatcher} 协作完成。</p>
 *
 * @since 0.1.0
 */
public final class AgentSession {

    private final SessionId id;
    private final SessionSpec spec;
    private final List<AgentMessage> messages = new ArrayList<>();
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final Map<String, String> toolNamesByCallId = new HashMap<>();
    private final Set<String> toolResultIds = new HashSet<>();
    private final Set<RunId> runIds = new HashSet<>();
    private long nextEventSequence = 1;
    private RunId activeRunId;
    private boolean closed;
    private boolean fenced;
    /** Session-owned Plan 状态；不得由 Surface dispatcher 复制持有。 */
    private PlanModeCoordinator plan;

    AgentSession(SessionId id, SessionSpec spec) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.spec = Objects.requireNonNull(spec, "spec 不能为空");
    }

    /**
     * 供架构边缘 SessionStore 创建尚未包含历史的新 Session。
     *
     * @param id Store 已验证且保证唯一的 Session ID
     * @param spec 创建配置
     * @return 空规范历史 Session
     */
    public static AgentSession create(SessionId id, SessionSpec spec) {
        return new AgentSession(id, spec);
    }

    /**
     * 从已经由 Session Adapter 完整验证的快照重建规范 Core 状态。
     *
     * <p>恢复仍逐条走与在线追加等价的 Call ID/Tool Result 校验，不能让文件 Adapter 直接填充
     * 内部集合而绕过协议不变量。只有完整结束的 Run 消息可以进入快照。</p>
     *
     * @param snapshot 已校验恢复快照
     * @return 没有活动 Run 的可继续 Session
     */
    public static AgentSession restore(SessionRecoverySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        AgentSession session = new AgentSession(snapshot.sessionId(), snapshot.spec());
        session.runIds.addAll(snapshot.runIds());
        session.fenced = !snapshot.issues().isEmpty();
        snapshot.plan().ifPresent(projection ->
                session.plan = PlanModeCoordinator.restore(projection.document(), projection.state()));
        for (AgentMessage message : snapshot.messages()) {
            if (message instanceof UserMessage) {
                session.messages.add(message);
            } else if (message instanceof AssistantMessage assistant) {
                session.restoreAssistant(assistant);
            } else if (message instanceof ToolResultMessage toolResult) {
                session.restoreToolResult(toolResult);
            } else {
                throw new IllegalArgumentException(
                        "恢复快照包含不支持的规范消息: " + message.getClass().getName());
            }
        }
        return session;
    }

    /**
     * 返回 Session ID。
     *
     * @return 稳定标识
     */
    public SessionId id() {
        return id;
    }

    /**
     * 返回创建 Session 时的不可变配置。
     *
     * @return Session 配置
     */
    public SessionSpec spec() {
        return spec;
    }

    /**
     * 返回当前规范消息历史快照。
     *
     * @return 与真实追加顺序一致的不可变列表
     */
    public List<AgentMessage> messages() {
        return List.copyOf(messages);
    }

    /**
     * 返回 Session 中已经记录的事件快照。
     *
     * @return 按 sequence 排序的不可变列表
     */
    public List<AgentEventEnvelope> events() {
        return List.copyOf(events);
    }

    /**
     * 判断 Session 是否已显式关闭。
     *
     * @return 关闭后返回 {@code true}
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 判断 Session 是否因 durable journal 不确定而被禁止继续执行。
     *
     * @return 只能 Inspect/恢复时为 {@code true}
     */
    public boolean isFenced() {
        return fenced;
    }

    /**
     * 判断是否存在正在执行的 Run，供显式恢复操作实施互斥 Gate。
     *
     * @return 活动 Run 尚未结束时为 {@code true}
     */
    public boolean hasActiveRun() {
        return activeRunId != null;
    }

    /**
     * 获取 Session 拥有的计划协调器，不复制其状态。
     * @return 未安装计划时为空
     */
    public synchronized Optional<PlanModeCoordinator> plan() { return Optional.ofNullable(plan); }

    /**
     * 在无活动 Run 时安装计划；活动 Run 内应使用带 Run 身份的入口。
     * @param value 已验证且非空的计划协调器
     * @return 安装后的计划；存在活动 Run 时为空
     */
    public synchronized Optional<PlanModeCoordinator> createPlan(PlanModeCoordinator value) {
        ensureOpen();
        if (activeRunId != null) return Optional.empty();
        plan = Objects.requireNonNull(value, "plan 不能为空");
        return Optional.of(plan);
    }

    /**
     * 在当前 Plan Run 仍活动时安装由该 Run 规范化的提案。
     *
     * <p>该入口只改变 Session-owned Plan 状态，不追加消息或建立第二份 transcript；调用方必须
     * 精确匹配活动 Run，因而迟到模型结果不能覆盖后续 Run 的计划。</p>
     *
     * @param runId 当前活动的 Plan Run
     * @param value 已严格验证的计划协调器
     * @return Run 匹配且安装成功时为当前计划
     */
    public synchronized Optional<PlanModeCoordinator> createPlanDuringRun(
            RunId runId, PlanModeCoordinator value) {
        ensureOpen();
        if (!Objects.requireNonNull(runId, "runId 不能为空").equals(activeRunId)) return Optional.empty();
        plan = Objects.requireNonNull(value, "plan 不能为空");
        return Optional.of(plan);
    }

    /**
     * 按当前工作区摘要批准计划，具体审批 Gate 由协调器验证。
     * @param digest 当前工作区摘要
     * @return 当前计划；未安装时为空
     */
    public synchronized Optional<PlanModeCoordinator> approvePlan(String digest) {
        if (plan == null) return Optional.empty();
        plan.approve(digest); return Optional.of(plan);
    }
    /**
     * 将当前计划交给协调器执行拒绝状态迁移。
     * @return 拒绝后的计划；未安装时为空
     */
    public synchronized Optional<PlanModeCoordinator> rejectPlan() {
        if (plan == null) return Optional.empty();
        plan.reject(); return Optional.of(plan);
    }
    /**
     * 通过摘要和审批 Gate 领取下一可执行步骤。
     * @param digest 执行前工作区摘要
     * @return 可开始的步骤；无计划或 Gate 不允许时为空
     */
    public synchronized Optional<PlanStep> beginPlanStep(String digest) {
        return plan == null ? Optional.empty() : plan.beginNext(digest);
    }
    /**
     * 用执行后的摘要完成当前步骤，后续步骤以该工作区状态为基线。
     * @param digest 步骤完成后的工作区摘要
     * @return 当前计划；未安装时为空
     */
    public synchronized Optional<PlanModeCoordinator> completePlanStep(String digest) {
        if (plan == null) return Optional.empty();
        plan.completeStep(digest); return Optional.of(plan);
    }

    /**
     * 为 Plan Pipeline 调用追加唯一 Assistant Tool Call，确保 Tool Result 能绑定规范历史。
     * @param runId 必须精确匹配当前活动 Run 的身份
     * @param call 要记录的非空工具意图
     */
    public synchronized void appendPlanToolCall(RunId runId, ToolCall call) {
        ensureActiveRun();
        if (!Objects.requireNonNull(runId, "runId 不能为空").equals(activeRunId)) {
            throw new IllegalStateException("Plan Tool Call 不属于当前 Run");
        }
        appendAssistant(AssistantMessage.tools(List.of(Objects.requireNonNull(call, "call 不能为空"))));
    }

    /**
     * 为 Plan Pipeline 调用追加 Tool Result，保持规范历史 ID 配对。
     * @param runId 必须精确匹配当前活动 Run 的身份
     * @param result 与已记录 Call 配对的非空结果
     */
    public synchronized void appendPlanToolResult(RunId runId, io.github.liumaishenjian.ccjava.domain.ToolResult result) {
        ensureActiveRun();
        if (!Objects.requireNonNull(runId, "runId 不能为空").equals(activeRunId)) {
            throw new IllegalStateException("Plan Tool Result 不属于当前 Run");
        }
        appendToolResult(new ToolResultMessage(Objects.requireNonNull(result, "result 不能为空")));
    }

    /**
     * 执行当前已批准 Plan 的剩余步骤；Session fence 或恢复问题时不得调用此入口。
     * @param executor 将计划步骤交给统一执行边界的执行器
     * @param cancellationToken 步骤间及执行中的取消信号
     * @param maxSteps 本次允许执行的步骤上限
     * @return 当前计划；已 fence 或无计划时为空，审批未通过时保持原状态
     */
    public synchronized Optional<PlanModeCoordinator> executePlan(
            PlanStepExecutor executor, CancellationToken cancellationToken, int maxSteps) {
        ensureOpen();
        if (fenced) return Optional.empty();
        if (plan == null) return Optional.empty();
        if (plan.state().nextStep() == null || plan.state().approvalGate()
                != io.github.liumaishenjian.ccjava.domain.PlanApprovalGate.APPROVED) return Optional.of(plan);
        RunId executionRun = new RunId("plan-execute-" + id.value());
        beginRun(executionRun, new UserMessage("Plan execution"));
        try {
            plan.executeAll(executor, cancellationToken, maxSteps);
            return Optional.of(plan);
        } finally {
            endRun(executionRun);
        }
    }

    /**
     * 旧版无参数兼容入口显式失败，避免静默 no-op。
     *
     * @return 此兼容入口始终抛出异常，不返回计划
     * @throws IllegalArgumentException 缺少完成后的工作区摘要
     */
    @Deprecated
    public synchronized Optional<PlanModeCoordinator> completePlanStep() {
        throw new IllegalArgumentException("completePlanStep 必须携带 workspaceDigest");
    }

    void ensureRunnable() {
        ensureOpen();
        if (activeRunId != null) {
            throw new IllegalStateException("Session 已有正在执行的 Run: " + activeRunId.value());
        }
    }

    void beginRun(RunId runId, UserMessage userMessage) {
        ensureRunnable();
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(userMessage, "userMessage 不能为空");
        if (!runIds.add(runId)) {
            throw new IllegalStateException("Session 中出现重复 Run ID: " + runId.value());
        }
        activeRunId = runId;
        messages.add(userMessage);
    }

    void appendAssistant(AssistantMessage message) {
        ensureActiveRun();
        Objects.requireNonNull(message, "message 不能为空");
        HashSet<String> batchIds = new HashSet<>();
        for (ToolCall call : message.toolCalls()) {
            if (!batchIds.add(call.id())) {
                throw new IllegalArgumentException("同一模型回合包含重复 Tool Call ID: " + call.id());
            }
            if (toolNamesByCallId.containsKey(call.id())) {
                throw new IllegalArgumentException("Session 历史包含重复 Tool Call ID: " + call.id());
            }
        }
        message.toolCalls().forEach(call -> toolNamesByCallId.put(call.id(), call.name()));
        messages.add(message);
    }

    void appendToolResult(ToolResultMessage message) {
        ensureActiveRun();
        Objects.requireNonNull(message, "message 不能为空");
        String expectedToolName = toolNamesByCallId.get(message.result().callId());
        if (expectedToolName == null) {
            throw new IllegalArgumentException(
                    "Tool Result 没有对应的 Tool Call ID: " + message.result().callId());
        }
        if (!expectedToolName.equals(message.result().toolName())) {
            throw new IllegalArgumentException(
                    "Tool Result 名称与原始 Tool Call 不一致，期望 "
                            + expectedToolName
                            + "，实际 "
                            + message.result().toolName());
        }
        if (!toolResultIds.add(message.result().callId())) {
            throw new IllegalArgumentException(
                    "同一 Tool Call 不能追加多个 Result: " + message.result().callId());
        }
        messages.add(message);
    }

    void endRun(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (!runId.equals(activeRunId)) {
            throw new IllegalStateException("尝试结束非当前 Run: " + runId.value());
        }
        activeRunId = null;
    }

    void close() {
        ensureOpen();
        if (activeRunId != null) {
            throw new IllegalStateException("存在活动 Run 时不能关闭 Session");
        }
        closed = true;
    }

    void closeRecoveredProjection() {
        if (!fenced) {
            throw new IllegalStateException("只能关闭 fenced 的恢复投影");
        }
        if (activeRunId != null) {
            throw new IllegalStateException("存在活动 Run 时不能关闭 Session");
        }
        closed = true;
    }

    void fence() {
        fenced = true;
    }

    AgentEventEnvelope recordEvent(
            Instant occurredAt,
            Optional<RunId> runId,
            AgentEvent event) {
        AgentEventEnvelope envelope = new AgentEventEnvelope(
                nextEventSequence++,
                occurredAt,
                id,
                runId,
                event);
        events.add(envelope);
        return envelope;
    }

    boolean hasToolCallId(String callId) {
        return toolNamesByCallId.containsKey(callId);
    }

    private void restoreAssistant(AssistantMessage message) {
        HashSet<String> batchIds = new HashSet<>();
        for (ToolCall call : message.toolCalls()) {
            if (!batchIds.add(call.id()) || toolNamesByCallId.containsKey(call.id())) {
                throw new IllegalArgumentException(
                        "恢复历史包含重复 Tool Call ID: " + call.id());
            }
        }
        message.toolCalls().forEach(call -> toolNamesByCallId.put(call.id(), call.name()));
        messages.add(message);
    }

    private void restoreToolResult(ToolResultMessage message) {
        String expectedToolName = toolNamesByCallId.get(message.result().callId());
        if (expectedToolName == null || !expectedToolName.equals(message.result().toolName())) {
            throw new IllegalArgumentException(
                    "恢复历史的 Tool Result 没有匹配的 Tool Call: " + message.result().callId());
        }
        if (!toolResultIds.add(message.result().callId())) {
            throw new IllegalArgumentException(
                    "恢复历史包含重复 Tool Result: " + message.result().callId());
        }
        messages.add(message);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Session 已关闭: " + id.value());
        }
        if (fenced) {
            throw new IllegalStateException("Session 持久状态不确定，只能进入恢复检查: " + id.value());
        }
    }

    private void ensureActiveRun() {
        ensureOpen();
        if (activeRunId == null) {
            throw new IllegalStateException("当前没有活动 Run");
        }
    }
}
