package io.github.liumaishenjian.ccjava.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 表示由单个 Session 独占、可持久恢复的 Markdown 规划工件。
 *
 * <p>工件正文是用户可读 Markdown，而不是模型专用 JSON 或可执行步骤协议。{@code revision}
 * 与 {@code contentDigest} 共同构成修改前置条件：架构边缘只有在两者都匹配时才能替换工件，
 * 从而阻止迟到编辑覆盖新版本。该值对象不包含文件路径、JSON 节点、锁或存储实现。</p>
 *
 * <p>工件状态只描述规划生命周期，不能授予 Tool 权限、解除 Session Recovery Gate，
 * 也不能被解释为可以自动重放副作用。</p>
 *
 * @param planId 工件稳定身份；Fork 必须生成新身份
 * @param sessionId 唯一拥有并可修改该工件的 Session
 * @param revision 从 1 开始、每次成功修改严格递增的版本
 * @param markdownContent 用户可读的完整 Markdown 正文
 * @param contentDigest Markdown 正文的小写 SHA-256
 * @param status 当前规划生命周期状态
 * @param createdAt 首次创建时间；后续 revision 必须保持不变
 * @param updatedAt 当前 revision 的提交时间，不得早于创建时间
 * @param executionBrief 批准后持久保存的不可变执行交接；规划态必须为空
 * @param verificationResumeReview verification-required 显式再审批的最小 durable 展示上下文
 * @param evidenceLedger 与 Plan/brief/workspace revision 绑定的 durable 证据账本
 * @since 0.1.0
 */
public record PlanArtifact(
        String planId,
        SessionId sessionId,
        long revision,
        String markdownContent,
        String contentDigest,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt,
        java.util.Optional<ExecutionBrief> executionBrief,
        java.util.Optional<PlanVerificationResumeReview> verificationResumeReview,
        PlanEvidenceLedger evidenceLedger) {

    /** 单份规划正文的独立 UTF-8 上限。 */
    public static final int MAX_CONTENT_UTF8_BYTES = 1_048_576;
    private static final Pattern PLAN_ID = Pattern.compile("plan-[A-Za-z0-9][A-Za-z0-9-]{0,126}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** 验证身份、摘要、时间与正文上限。 */
    public PlanArtifact {
        planId = Objects.requireNonNull(planId, "planId 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        markdownContent = Objects.requireNonNull(markdownContent, "markdownContent 不能为空");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        executionBrief = Objects.requireNonNull(executionBrief, "executionBrief 不能为空");
        verificationResumeReview = Objects.requireNonNull(
                verificationResumeReview, "verificationResumeReview 不能为空");
        evidenceLedger = Objects.requireNonNull(evidenceLedger, "evidenceLedger 不能为空");
        if (!PLAN_ID.matcher(planId).matches()) {
            throw new IllegalArgumentException("planId 格式无效");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须为正数");
        }
        int bytes = markdownContent.getBytes(StandardCharsets.UTF_8).length;
        if (markdownContent.isBlank() || bytes > MAX_CONTENT_UTF8_BYTES || markdownContent.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Markdown 规划正文无效或超过上限");
        }
        if (!SHA_256.matcher(contentDigest).matches() || !contentDigest.equals(digest(markdownContent))) {
            throw new IllegalArgumentException("contentDigest 与 Markdown 正文不匹配");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
        if (!evidenceLedger.planId().equals(planId) || !evidenceLedger.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("EvidenceLedger 与 PlanArtifact 身份不匹配");
        }
        if (executionBrief.isPresent() && (status == PlanStatus.DRAFT
                || status == PlanStatus.AWAITING_APPROVAL || status == PlanStatus.REJECTED
                || status == PlanStatus.DIGEST_CONFLICT)) {
            throw new IllegalArgumentException("规划态不能携带 ExecutionBrief");
        }
        if (verificationResumeReview.isPresent()
                && (status != PlanStatus.AWAITING_APPROVAL || executionBrief.isPresent())) {
            throw new IllegalArgumentException("显式验证再审批上下文只允许出现在独立等待审批态");
        }
        if (executionBrief.isPresent()) {
            ExecutionBrief brief = executionBrief.orElseThrow();
            if (!brief.planId().equals(planId) || !brief.sessionId().equals(sessionId)
                    || !brief.contentDigest().equals(contentDigest)
                    || !brief.markdownSnapshot().equals(markdownContent)
                    || evidenceLedger.approvedPlanRevision() != brief.approvedRevision()
                    || !evidenceLedger.executionBriefDigest().equals(brief.evidenceBindingDigest())
                    || !evidenceLedger.approvedWorkspaceDigest().equals(brief.workspaceDigest())) {
                throw new IllegalArgumentException("ExecutionBrief 与 PlanArtifact 不匹配");
            }
        }
    }

    /**
     * 创建首个 revision，并由确定性应用代码计算正文摘要。
     *
     * @param planId 新工件身份
     * @param sessionId 所属 Session
     * @param markdownContent Markdown 正文
     * @param status 初始状态
     * @param createdAt 创建时间
     * @return revision 为 1 的工件
     */
    public static PlanArtifact create(
            String planId, SessionId sessionId, String markdownContent, PlanStatus status, Instant createdAt) {
        return new PlanArtifact(planId, sessionId, 1, markdownContent, digest(markdownContent),
                status, createdAt, createdAt, java.util.Optional.empty(), java.util.Optional.empty(),
                PlanEvidenceLedger.planning(sessionId, planId, createdAt));
    }

    /**
     * 构造下一 revision；真正的 CAS 仍由 {@code PlanArtifactStore} 在持久边缘执行。
     *
     * @param content 新 Markdown 正文
     * @param nextStatus 新状态
     * @param timestamp 提交时间
     * @return revision 严格加一且保留创建时间的新值
     */
    public PlanArtifact nextRevision(String content, PlanStatus nextStatus, Instant timestamp) {
        Instant requested = Objects.requireNonNull(timestamp, "timestamp 不能为空");
        Instant monotonic = requested.isBefore(updatedAt) ? updatedAt : requested;
        return new PlanArtifact(planId, sessionId, Math.addExact(revision, 1), content, digest(content),
                nextStatus, createdAt, monotonic, executionBrief,
                nextStatus == PlanStatus.AWAITING_APPROVAL
                        ? verificationResumeReview : java.util.Optional.empty(), evidenceLedger);
    }


    /**
     * 以批准工件快照绑定 ExecutionBrief 并生成下一 revision。
     *
     * @param brief 已验证执行交接
     * @param timestamp 批准提交时间
     * @return 状态为 APPROVED 且携带同一快照的工件
     */
    public PlanArtifact approve(ExecutionBrief brief, String executionBriefDigest, Instant timestamp) {
        ExecutionBrief checked = Objects.requireNonNull(brief, "brief 不能为空");
        Instant requested = Objects.requireNonNull(timestamp, "timestamp 不能为空");
        Instant monotonic = requested.isBefore(updatedAt) ? updatedAt : requested;
        PlanEvidenceLedger bound = evidenceLedger.bind(checked.approvedRevision(), executionBriefDigest,
                checked.workspaceDigest(), monotonic);
        return new PlanArtifact(planId, sessionId, Math.addExact(revision, 1), markdownContent,
                contentDigest, PlanStatus.APPROVED, createdAt, monotonic, java.util.Optional.of(checked),
                java.util.Optional.empty(), bound);
    }

    /**
     * 在执行前发现 Workspace 漂移时撤销旧执行交接并重新打开审批。
     *
     * <p>正文与 requirement 保持不变；ExecutionBrief、审批绑定和旧证据全部清除，避免旧批准
     * 在新 Workspace 上被恢复或重放。</p>
     *
     * @param timestamp 漂移被确定性 Gate 发现的时间
     * @return 状态为 AWAITING_APPROVAL 的下一 revision
     */
    public PlanArtifact reopenApprovalAfterWorkspaceDrift(Instant timestamp) {
        if (status != PlanStatus.APPROVED || executionBrief.isEmpty()) {
            throw new IllegalStateException("只有已批准且未执行的工件可以因 Workspace 漂移重新审批");
        }
        return reopenApproval(timestamp, java.util.Optional.empty());
    }

    /**
     * 为有界验证失败后的显式继续重新打开审批。
     *
     * <p>保留同一 Plan、Markdown、Evidence requirement 与 Task cohort identity，但撤销旧 ExecutionBrief、
     * Workspace 绑定和旧 reference。用户必须基于当前 Workspace 再次批准，Runtime 才能启动新的执行 Run；
     * 该转换本身不执行或重放任何 Tool。</p>
     *
     * @param timestamp 显式恢复请求时间
     * @return 状态为 AWAITING_APPROVAL 的下一 revision
     */
    public PlanArtifact reopenApprovalForVerificationResume(Instant timestamp) {
        if (status != PlanStatus.NEEDS_VERIFICATION || executionBrief.isEmpty()) {
            throw new IllegalStateException("只有等待验证且保留旧执行绑定的工件可以请求继续");
        }
        ExecutionBrief previous = executionBrief.orElseThrow();
        return reopenApproval(timestamp, java.util.Optional.of(new PlanVerificationResumeReview(
                previous.originalPermissionMode(), previous.contextPolicy())));
    }

    private PlanArtifact reopenApproval(
            Instant timestamp, java.util.Optional<PlanVerificationResumeReview> resumeReview) {
        Instant requested = Objects.requireNonNull(timestamp, "timestamp 不能为空");
        Instant monotonic = requested.isBefore(updatedAt) ? updatedAt : requested;
        return new PlanArtifact(planId, sessionId, Math.addExact(revision, 1), markdownContent, contentDigest,
                PlanStatus.AWAITING_APPROVAL, createdAt, monotonic, java.util.Optional.empty(), resumeReview,
                evidenceLedger.resetForReapproval(monotonic));
    }

    /**
     * 以已经验证的 Ledger 生成下一 revision；调用方仍须通过 store CAS 持久化。
     *
     * @param ledger 同一 Plan/Session/brief 绑定的新 Ledger
     * @param nextStatus 证据记录后的状态
     * @param timestamp durable 更新时间
     * @return 带新 Ledger 的下一工件 revision
     */
    public PlanArtifact withEvidenceLedger(PlanEvidenceLedger ledger, PlanStatus nextStatus, Instant timestamp) {
        Instant requested = Objects.requireNonNull(timestamp, "timestamp 不能为空");
        Instant monotonic = requested.isBefore(updatedAt) ? updatedAt : requested;
        return new PlanArtifact(planId, sessionId, Math.addExact(revision, 1), markdownContent, contentDigest,
                nextStatus, createdAt, monotonic, executionBrief,
                nextStatus == PlanStatus.AWAITING_APPROVAL
                        ? verificationResumeReview : java.util.Optional.empty(),
                Objects.requireNonNull(ledger, "ledger 不能为空"));
    }

    /**
     * 为 Fork 创建内容相同但身份、Session 与版本链完全独立的工件。
     *
     * @param newPlanId Fork 工件的新身份
     * @param newSessionId Fork Session
     * @param timestamp Fork 创建时间
     * @return revision 重新从 1 开始、重新等待批准的独立工件；不继承源审批或终态
     */
    public PlanArtifact fork(String newPlanId, SessionId newSessionId, Instant timestamp) {
        return create(newPlanId, newSessionId, markdownContent, PlanStatus.AWAITING_APPROVAL, timestamp);
    }

    /**
     * 计算 UTF-8 Markdown 的小写 SHA-256。
     *
     * @param content Markdown 内容
     * @return 64 位小写十六进制摘要
     */
    public static String digest(String content) {
        Objects.requireNonNull(content, "content 不能为空");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
