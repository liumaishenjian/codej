package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 通知 Surface 从 durable PlanArtifact 打开审批视图。
 *
 * <p>事件携带身份、revision、摘要与审批所需投影。Markdown 正文来自同一已提交工件的只读投影，
 * 不能从模型 Tool payload 或最终 JSON 重建。</p>
 *
 * @param planId 稳定 Plan 身份
 * @param revision 已提交 revision
 * @param contentDigest 已提交正文摘要
 * @param markdownContent 用户可读 Markdown 正文
 * @param workspaceDigest review 发布时的工作区快照；与 contentDigest 语义严格分离
 * @param originalPermissionMode 进入 Plan 前应在执行时恢复的模式
 * @param suggestedContextPolicy 基于明确设置或 Context 使用率给出的可覆盖建议
 * @since 0.1.0
 */
public record PlanReviewEvent(String planId, long revision, String contentDigest,
                              String markdownContent, String workspaceDigest,
                              PermissionMode originalPermissionMode,
                              PlanContextPolicy suggestedContextPolicy) implements AgentEvent {
    /** 验证事件与 durable 工件字段的基本边界。 */
    public PlanReviewEvent {
        planId = Objects.requireNonNull(planId, "planId 不能为空");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        markdownContent = Objects.requireNonNull(markdownContent, "markdownContent 不能为空");
        workspaceDigest = Objects.requireNonNull(workspaceDigest, "workspaceDigest 不能为空");
        originalPermissionMode = Objects.requireNonNull(originalPermissionMode, "originalPermissionMode 不能为空");
        suggestedContextPolicy = Objects.requireNonNull(suggestedContextPolicy, "suggestedContextPolicy 不能为空");
        if (planId.isBlank() || revision < 1 || contentDigest.length() != 64 || markdownContent.isBlank()
                || workspaceDigest.isBlank() || originalPermissionMode == PermissionMode.PLAN) {
            throw new IllegalArgumentException("Plan review 事件无效");
        }
    }

    /**
     * 旧测试兼容入口；生产必须使用携带真实 workspace/permission/context 的重载。
     * @param artifact 待审批的已提交工件
     * @return 使用正文摘要占位工作区摘要、DEFAULT 与 KEEP 的兼容事件，不是实时执行绑定
     */
    @Deprecated
    public static PlanReviewEvent from(PlanArtifact artifact) {
        return from(artifact, artifact.contentDigest(), PermissionMode.DEFAULT, PlanContextPolicy.KEEP);
    }

    /**
     * 从已提交工件生成事件，避免调用方拼接正文或版本。
     * @param artifact 非空且处于 AWAITING_APPROVAL 的工件
     * @param workspaceDigest 发布审批时的真实工作区摘要
     * @param originalPermissionMode 执行时待恢复的非 PLAN 模式
     * @param suggestedContextPolicy 用户可覆盖的上下文保留或清理建议
     * @return 绑定同一持久化修订正文与审批上下文的事件
     */
    public static PlanReviewEvent from(PlanArtifact artifact, String workspaceDigest,
                                       PermissionMode originalPermissionMode,
                                       PlanContextPolicy suggestedContextPolicy) {
        Objects.requireNonNull(artifact, "artifact 不能为空");
        if (artifact.status() != PlanStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("只有等待审批的工件可以发布 review 事件");
        }
        return new PlanReviewEvent(artifact.planId(), artifact.revision(), artifact.contentDigest(),
                artifact.markdownContent(), workspaceDigest, originalPermissionMode, suggestedContextPolicy);
    }
}
