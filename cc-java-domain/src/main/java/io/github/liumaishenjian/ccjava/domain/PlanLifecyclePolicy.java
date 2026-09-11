package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 定义持久 Plan revision 唯一、确定性的生命周期状态链。
 *
 * <p>该策略同时供写入前检查与 journal 重放使用，避免持久边缘和恢复边缘各自维护一份
 * 容易漂移的状态表。它只判断状态链，不授予 Tool 权限，也不表示状态变化可以自动重放
 * 已发生或未完成的副作用。</p>
 *
 * <p>普通 Markdown 修订可以保持非终态不变；终态不能再产生新 revision。重复审批或拒绝
 * 必须由调用方识别为幂等决定并跳过持久化，而不能用终态自环伪造一次新变化。
 * 用户反馈通过 {@code AWAITING_APPROVAL -> DRAFT} 恢复同一 planId/revision 链继续规划；
 * 验证未收敛时只能由显式继续请求经 {@code NEEDS_VERIFICATION -> AWAITING_APPROVAL}
 * 重新审批，不能直接回到执行态或自动重放副作用。</p>
 *
 * @since 0.1.0
 */
public final class PlanLifecyclePolicy {
    private PlanLifecyclePolicy() {
    }

    /**
     * 判断首个持久 revision 是否具有合法初态。
     *
     * @param status 首个 revision 的状态
     * @return 仅 {@link PlanStatus#DRAFT} 或 {@link PlanStatus#AWAITING_APPROVAL} 返回 {@code true}
     */
    public static boolean validInitial(PlanStatus status) {
        PlanStatus checked = Objects.requireNonNull(status, "status 不能为空");
        return checked == PlanStatus.DRAFT || checked == PlanStatus.AWAITING_APPROVAL;
    }

    /**
     * 判断相邻两个持久 revision 是否构成合法状态迁移。
     *
     * @param previous 已提交 revision 的状态
     * @param next 待提交 revision 的状态
     * @return 状态链合法时为 {@code true}
     */
    public static boolean validTransition(PlanStatus previous, PlanStatus next) {
        PlanStatus from = Objects.requireNonNull(previous, "previous 不能为空");
        PlanStatus to = Objects.requireNonNull(next, "next 不能为空");
        if (from == to) return !terminal(from);
        return switch (from) {
            case DRAFT -> to == PlanStatus.AWAITING_APPROVAL;
            case AWAITING_APPROVAL -> to == PlanStatus.DRAFT
                    || to == PlanStatus.APPROVED
                    || to == PlanStatus.REJECTED
                    || to == PlanStatus.DIGEST_CONFLICT;
            case APPROVED -> to == PlanStatus.EXECUTING
                    || to == PlanStatus.AWAITING_APPROVAL
                    || to == PlanStatus.REJECTED
                    || to == PlanStatus.DIGEST_CONFLICT;
            case EXECUTING -> to == PlanStatus.APPROVED
                    || to == PlanStatus.PAUSED
                    || to == PlanStatus.NEEDS_VERIFICATION
                    || to == PlanStatus.COMPLETED
                    || terminalFailure(to);
            case PAUSED -> to == PlanStatus.APPROVED || terminalFailure(to);
            case NEEDS_VERIFICATION -> to == PlanStatus.AWAITING_APPROVAL
                    || to == PlanStatus.COMPLETED
                    || terminalFailure(to);
            case COMPLETED, REJECTED, DIGEST_CONFLICT, FAILED, CANCELLED, TIMED_OUT, LIMIT_EXCEEDED -> false;
        };
    }

    /**
     * 判断状态是否封闭当前 revision 链。
     *
     * @param status 待判断状态
     * @return 完成、拒绝、冲突或执行失败终态为 {@code true}
     */
    public static boolean terminal(PlanStatus status) {
        PlanStatus checked = Objects.requireNonNull(status, "status 不能为空");
        return checked == PlanStatus.COMPLETED
                || checked == PlanStatus.REJECTED
                || terminalFailure(checked);
    }

    /**
     * 判断旧 revision 链是否已经结束到可以由用户显式开启全新 Plan identity。
     *
     * <p>{@link PlanStatus#DIGEST_CONFLICT} 虽会封闭当前 revision 链，但仍表示工作区身份冲突需要
     * 用户先核对，不能被新计划静默覆盖。其他完成、拒绝或执行失败终态没有待恢复副作用，可在完整
     * CAS 下保留历史并轮换为新的 revision 1 DRAFT。</p>
     *
     * @param status 当前 durable Plan 状态
     * @return 允许开启全新 identity 时为 {@code true}
     */
    public static boolean replaceableTerminal(PlanStatus status) {
        PlanStatus checked = Objects.requireNonNull(status, "status 不能为空");
        return terminal(checked) && checked != PlanStatus.DIGEST_CONFLICT;
    }

    /**
     * 判断旧终态到全新 Plan identity 的替换是否为干净首版。
     *
     * <p>新工件不能继承旧 Evidence、执行摘要或验证恢复标记，且创建时间不得早于旧终态更新时间。
     * 写入、journal 重放与 manifest 恢复必须共用该判断，避免只在当前进程接受、重启后却拒绝。</p>
     *
     * @param previous 当前已提交的旧终态工件
     * @param candidate 待提交的新 identity 首版
     * @return 满足全新 revision 1 DRAFT 不变量时为 {@code true}
     */
    public static boolean validIdentityRotation(PlanArtifact previous, PlanArtifact candidate) {
        PlanArtifact old = Objects.requireNonNull(previous, "previous 不能为空");
        PlanArtifact fresh = Objects.requireNonNull(candidate, "candidate 不能为空");
        return replaceableTerminal(old.status())
                && old.sessionId().equals(fresh.sessionId())
                && !old.planId().equals(fresh.planId())
                && fresh.revision() == 1
                && fresh.status() == PlanStatus.DRAFT
                && fresh.createdAt().equals(fresh.updatedAt())
                && !fresh.createdAt().isBefore(old.updatedAt())
                && fresh.executionBrief().isEmpty()
                && fresh.verificationResumeReview().isEmpty()
                && fresh.evidenceLedger().sessionId().equals(fresh.sessionId())
                && fresh.evidenceLedger().planId().equals(fresh.planId())
                && fresh.evidenceLedger().approvedPlanRevision() == 0
                && fresh.evidenceLedger().executionBriefDigest().isEmpty()
                && fresh.evidenceLedger().approvedWorkspaceDigest().isEmpty()
                && fresh.evidenceLedger().requirements().isEmpty()
                && fresh.evidenceLedger().references().isEmpty()
                && fresh.evidenceLedger().createdAt().equals(fresh.createdAt())
                && fresh.evidenceLedger().updatedAt().equals(fresh.updatedAt());
    }

    private static boolean terminalFailure(PlanStatus status) {
        return status == PlanStatus.FAILED
                || status == PlanStatus.CANCELLED
                || status == PlanStatus.TIMED_OUT
                || status == PlanStatus.LIMIT_EXCEEDED
                || status == PlanStatus.DIGEST_CONFLICT;
    }
}
