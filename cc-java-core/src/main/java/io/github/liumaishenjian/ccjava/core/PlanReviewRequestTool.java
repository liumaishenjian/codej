package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 将当前 durable DRAFT 工件推进为 AWAITING_APPROVAL，并标记本次 Plan Run 已请求 review。
 *
 * <p>模型面对的契约为空对象。Tool 在受信、run-scoped 边界重新读取当前 DRAFT，并以读取到的
 * revision+digest 执行原子 CAS；因此 evidence declaration 推进 revision 后仍会 review 最新工件。
 * Store 在读取与提交之间发生真正并发漂移时仍失败关闭。</p>
 *
 * <p>旧 revision/digest payload 只作为未宣传兼容输入接受并忽略，不再让模型提供的 bookkeeping
 * 决定审批对象。Surface 必须读取 {@link #reviewArtifact()} 返回的已提交 durable revision。</p>
 *
 * @since 0.1.0
 */
public final class PlanReviewRequestTool implements AgentTool {
    /** 供模型调用的独立稳定名称。 */
    public static final String NAME = "request_plan_review";
    private static final Set<String> LEGACY_FIELDS = Set.of("revision", "contentDigest");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Submit the latest durable Markdown plan for user review after all exploration and clarification is complete.",
            """
            {"type":"object","additionalProperties":false,"properties":{}}
            """,
            ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "text/plain", 256,
            Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final Clock clock;
    private final Supplier<Optional<String>> reviewBlockReason;
    private volatile PlanArtifact reviewArtifact;

    /** 绑定当前 Session 的 durable 工件 store，不增加额外的 review readiness Gate。 */
    public PlanReviewRequestTool(PlanArtifactStore store,
                                 io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                                 Clock clock) {
        this(store, sessionId, clock, Optional::empty);
    }

    /**
     * 绑定当前 Session 的 durable 工件 store 与宿主持有的 review readiness Gate。
     *
     * <p>Gate 只返回有界、可反馈给模型的纠正原因，不得执行副作用或依赖模型参数。返回非空时
     * Tool 保持 DRAFT，不推进 revision；模型可先通过同一 Run 中已注册的 Tool 修正前置状态后重试。</p>
     *
     * @param store Plan 工件 store
     * @param sessionId 当前 Session
     * @param clock durable revision 时间源
     * @param reviewBlockReason 当前 review 被阻止时的安全原因；可提交时返回 empty
     */
    public PlanReviewRequestTool(PlanArtifactStore store,
                                 io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                                 Clock clock,
                                 Supplier<Optional<String>> reviewBlockReason) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.reviewBlockReason = Objects.requireNonNull(reviewBlockReason, "reviewBlockReason 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        Set<String> fields = arguments.values().keySet();
        if (!fields.isEmpty() && !fields.equals(LEGACY_FIELDS)) {
            return ToolValidationResult.invalid("字段集合无效；request_plan_review 不需要参数");
        }
        return ToolValidationResult.validResult();
    }

    @Override
    public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        PlanArtifact current = store.load(sessionId).orElseThrow(
                () -> new PlanArtifactStoreException(PlanArtifactStoreException.Code.NOT_FOUND));
        if (current.status() != PlanStatus.DRAFT) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.INVALID_STATE);
        }
        Optional<String> blocked = Objects.requireNonNull(reviewBlockReason.get(),
                "reviewBlockReason 返回值不能为空");
        if (blocked.isPresent()) {
            String reason = blocked.orElseThrow();
            return ToolExecutionOutcome.failure(new ToolError(
                    ToolErrorCode.PLAN_GATE_BLOCKED,
                    "Plan review prerequisites are not satisfied",
                    new JsonObject(Map.of(
                            "reason", reason,
                            "action", "maintain_execution_tasks_then_retry_review"))));
        }
        PlanArtifact candidate = current.nextRevision(current.markdownContent(),
                PlanStatus.AWAITING_APPROVAL, clock.instant());
        reviewArtifact = store.save(candidate, current.revision(), current.contentDigest());
        return ToolExecutionOutcome.success("Plan review requested for revision %d".formatted(
                reviewArtifact.revision()));
    }

    /** 返回本次 Run 成功提交的 review revision。 */
    public synchronized java.util.Optional<PlanArtifact> reviewArtifact() {
        return java.util.Optional.ofNullable(reviewArtifact);
    }
}
