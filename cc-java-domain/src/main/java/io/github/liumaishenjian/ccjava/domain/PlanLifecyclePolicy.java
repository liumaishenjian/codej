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

    private static boolean terminalFailure(PlanStatus status) {
        return status == PlanStatus.FAILED
                || status == PlanStatus.CANCELLED
                || status == PlanStatus.TIMED_OUT
                || status == PlanStatus.LIMIT_EXCEEDED
                || status == PlanStatus.DIGEST_CONFLICT;
    }
}
