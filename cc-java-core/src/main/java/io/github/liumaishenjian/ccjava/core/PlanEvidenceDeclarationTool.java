package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceKind;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceRequirement;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 规划期受控声明或纠正交付物和验证要求；不写 Workspace，也不解析 Markdown。
 *
 * <p>Tool 只能更新 Session-owned Ledger。DELIVERABLE locator 是后续由 WorkspaceGuard 验证的
 * 相对文件；VERIFICATION locator 必须同时满足稳定名称格式，并存在于当前 Runtime 注册的可信
 * BUILT_IN Tool 集合。相同 requirementId 在 DRAFT 中确定性原位替换，坏 locator 不会被提交或永久
 * 占用该身份。</p>
 *
 * @since 0.1.0
 */
public final class PlanEvidenceDeclarationTool implements AgentTool {
    /** 稳定 Tool 名。 */
    public static final String NAME = "declare_plan_evidence";
    /** 允许 stdio Surface 投影的安全失败原因字段。 */
    public static final String FAILURE_REASON_DETAIL = "failureReasonCode";
    /** 验证要求引用的 Tool 不在当前可信注册表中。 */
    public static final String VERIFICATION_TOOL_UNAVAILABLE = "verification_tool_unavailable";
    /** 仅供宿主在当前 Run 内关联失败的规范 requirement 身份；不得投影到 Surface。 */
    public static final String RECOVERY_REQUIREMENT_DETAIL = "recoveryRequirementId";
    private static final Set<String> FIELDS = Set.of("requirementId", "kind", "locator", "label", "required");
    private static final String INPUT_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["requirementId","kind","locator","label","required"],"properties":{"requirementId":{"type":"string","pattern":"^[a-z][a-z0-9-]{0,63}$"},"kind":{"type":"string","enum":["DELIVERABLE","VERIFICATION"]},"locator":{"type":"string","minLength":1,"maxLength":512},"label":{"type":"string","minLength":1,"maxLength":512},"required":{"type":"boolean"}}}
            """;

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final Clock clock;
    private final Set<String> trustedVerificationTools;
    private final ToolDefinition definition;

    /**
     * 绑定当前 Session 的 ledger store 与该 Runtime 实际注册的可信验证 Tool。
     *
     * @param store durable Plan store
     * @param sessionId 当前 Session
     * @param clock durable 时间源
     * @param trustedVerificationTools 当前 Runtime 注册、可在批准后执行的 BUILT_IN Tool 名
     */
    public PlanEvidenceDeclarationTool(PlanArtifactStore store,
            io.github.liumaishenjian.ccjava.domain.SessionId sessionId, Clock clock,
            Set<String> trustedVerificationTools) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        TreeSet<String> normalized = new TreeSet<>(Objects.requireNonNull(
                trustedVerificationTools, "trustedVerificationTools 不能为空"));
        if (normalized.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("可信验证 Tool 名无效");
        }
        this.trustedVerificationTools = Set.copyOf(normalized);
        this.definition = new ToolDefinition(NAME,
                "Declare or correct one required deliverable or registered-tool verification item for deterministic completion validation. "
                        + "VERIFICATION locator must be one of the currently registered trusted tools: "
                        + allowedAlternatives() + ".",
                INPUT_SCHEMA, ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false,
                Duration.ofSeconds(5), "text/plain", 256,
                Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));
    }

    @Override public ToolDefinition definition() { return definition; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            if (!arguments.values().keySet().equals(FIELDS)) {
                return ToolValidationResult.invalid("字段集合无效");
            }
            PlanEvidenceRequirement requirement = requirement(arguments);
            if (verificationToolUnavailable(requirement)) {
                return ToolValidationResult.invalid("VERIFICATION locator 未注册为可信 Tool；可用: "
                                + allowedAlternatives(),
                        unavailableDetails(requirement.requirementId()));
            }
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("证据要求无效；VERIFICATION locator 必须使用已注册可信 Tool 名");
        }
    }

    @Override public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        PlanArtifact current = store.load(sessionId).orElseThrow(
                () -> new PlanArtifactStoreException(PlanArtifactStoreException.Code.NOT_FOUND));
        if (current.status() != PlanStatus.DRAFT) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.INVALID_STATE);
        }
        PlanEvidenceRequirement requirement = requirement(invocation.call().arguments());
        if (verificationToolUnavailable(requirement)) {
            return ToolExecutionOutcome.failure(ToolError.classified(
                    ToolErrorCode.INVALID_ARGUMENTS, ToolFailureCategory.VALIDATION, false,
                    "VERIFICATION locator 未注册为可信 Tool",
                    unavailableDetails(requirement.requirementId())));
        }
        var existingLedger = current.evidenceLedger();
        var ledger = existingLedger.declare(requirement, clock.instant());
        if (ledger == existingLedger) {
            return ToolExecutionOutcome.success("Plan evidence requirement already committed: "
                    + existingLedger.requirements().size());
        }
        PlanArtifact candidate = current.withEvidenceLedger(ledger, current.status(), clock.instant());
        PlanArtifact saved = store.save(candidate, current.revision(), current.contentDigest());
        return ToolExecutionOutcome.success("Plan evidence requirement committed: "
                + saved.evidenceLedger().requirements().size());
    }

    private boolean verificationToolUnavailable(PlanEvidenceRequirement requirement) {
        return requirement.kind() == PlanEvidenceKind.VERIFICATION
                && !trustedVerificationTools.contains(requirement.locator());
    }

    private static JsonObject unavailableDetails(String requirementId) {
        return new JsonObject(Map.of(
                FAILURE_REASON_DETAIL, VERIFICATION_TOOL_UNAVAILABLE,
                RECOVERY_REQUIREMENT_DETAIL, requirementId));
    }

    private String allowedAlternatives() {
        List<String> alternatives = trustedVerificationTools.stream().sorted().toList();
        return alternatives.isEmpty() ? "none" : String.join(", ", alternatives);
    }

    private static PlanEvidenceRequirement requirement(JsonObject arguments) {
        Map<String, Object> values = arguments.values();
        Object required = values.get("required");
        if (!(required instanceof Boolean booleanValue)) throw new IllegalArgumentException("required 无效");
        return new PlanEvidenceRequirement(arguments.string("requirementId").orElseThrow(),
                PlanEvidenceKind.valueOf(arguments.string("kind").orElseThrow()),
                arguments.string("locator").orElseThrow(), arguments.string("label").orElseThrow(), booleanValue);
    }
}
