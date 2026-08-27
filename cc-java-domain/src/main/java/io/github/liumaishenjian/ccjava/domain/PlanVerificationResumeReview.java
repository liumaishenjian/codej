package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 保存 verification-required Plan 显式再审批所需的最小 durable 上下文。
 *
 * <p>该值只标记由 {@code NEEDS_VERIFICATION} 显式打开的 {@code AWAITING_APPROVAL}，
 * 使传输失败或进程重启后的重复 {@code plan.resume} 能原样重新投影同一 revision。
 * 它不包含旧 ExecutionBrief、Workspace 绑定、Tool 调用或自动重放许可。</p>
 *
 * @param originalPermissionMode 进入原 planning 前的权限模式
 * @param contextPolicy 用户上次批准时选择的上下文策略
 * @since 0.15.0
 */
public record PlanVerificationResumeReview(
        PermissionMode originalPermissionMode,
        PlanContextPolicy contextPolicy) {

    /** 验证再审批展示策略完整且执行权限不会停留在 PLAN。 */
    public PlanVerificationResumeReview {
        originalPermissionMode = Objects.requireNonNull(originalPermissionMode,
                "originalPermissionMode 不能为空");
        contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy 不能为空");
        if (originalPermissionMode == PermissionMode.PLAN) {
            throw new IllegalArgumentException("再审批原权限模式不能是 PLAN");
        }
    }
}
