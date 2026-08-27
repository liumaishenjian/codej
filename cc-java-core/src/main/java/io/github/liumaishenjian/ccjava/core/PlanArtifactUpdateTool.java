package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 以 application-owned CAS 替换当前 Session 的受控 Markdown PlanArtifact。
 *
 * <p>模型面对的稳定契约只接受 Markdown；revision、digest、Session 与 Plan 身份均由构造时绑定的
 * trusted control plane 在执行时重新读取。Store 仍以 revision+digest CAS 提交，因此真正的并发漂移
 * 会失败关闭，但普通 evidence mutation 不会把模型此前看到的 bookkeeping 变成陈旧参数。</p>
 *
 * <p>旧三字段 payload 仅作为未宣传的输入兼容边界接受，两个 CAS 字段不参与提交，也不会出现在
 * {@link #definition()} 的 schema 中。成功结果只返回新的安全 revision，不回显 Markdown 或内部 JSON。</p>
 *
 * @since 0.1.0
 */
public final class PlanArtifactUpdateTool implements AgentTool {
    /** 供模型调用的独立稳定名称。 */
    public static final String NAME = "revise_plan_artifact";
    private static final Set<String> MODEL_FIELDS = Set.of("markdown");
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "markdown", "expectedRevision", "expectedContentDigest");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Create or replace the current session's user-readable Markdown plan. Keep runtime identity and bookkeeping out of the document; the runtime owns those details and concurrency control.",
            """
            {"type":"object","additionalProperties":false,"required":["markdown"],"properties":{"markdown":{"type":"string","minLength":1,"maxLength":1048576}}}
            """,
            ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "text/plain", 256,
            Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final String planId;
    private final Clock clock;
    private volatile PlanArtifact latest;

    /** 绑定单个 Session 与稳定 Plan 身份。 */
    public PlanArtifactUpdateTool(PlanArtifactStore store,
                                  io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                                  String planId, Clock clock) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.planId = Objects.requireNonNull(planId, "planId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.latest = store.load(sessionId).orElse(null);
        requireIdentity(latest);
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            Set<String> fields = arguments.values().keySet();
            if (!fields.equals(MODEL_FIELDS) && !fields.equals(LEGACY_FIELDS)) {
                return ToolValidationResult.invalid("字段集合无效；只需提供 markdown");
            }
            String markdown = arguments.string("markdown").orElse("");
            PlanArtifact.create(planId, sessionId, markdown, PlanStatus.DRAFT, clock.instant());
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("Markdown PlanArtifact 参数无效");
        }
    }

    @Override
    public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        String markdown = invocation.call().arguments().string("markdown").orElseThrow();
        PlanArtifact current = store.load(sessionId).orElse(null);
        requireIdentity(current);
        if (current != null && current.status() != PlanStatus.DRAFT) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.INVALID_STATE);
        }
        PlanArtifact candidate = current == null
                ? PlanArtifact.create(planId, sessionId, markdown, PlanStatus.DRAFT, clock.instant())
                : current.nextRevision(markdown, PlanStatus.DRAFT, clock.instant());
        latest = store.save(candidate, current == null ? 0 : current.revision(),
                current == null ? "" : current.contentDigest());
        return ToolExecutionOutcome.success("Plan artifact revision %d committed".formatted(latest.revision()));
    }

    /** 返回当前 Run 已提交或从 durable store 读取的最新工件。 */
    public synchronized java.util.Optional<PlanArtifact> latest() {
        return java.util.Optional.ofNullable(latest);
    }

    private void requireIdentity(PlanArtifact artifact) {
        if (artifact != null && !artifact.planId().equals(planId)) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
        }
    }
}
