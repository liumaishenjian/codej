package io.github.liumaishenjian.ccjava.domain;

/** 规划文档在审批与执行生命周期中的状态。 */
public enum PlanStatus {
    /** 可继续编辑的草稿，尚不能执行。 */
    DRAFT,
    /** 已提交精确修订供用户审核，等待显式决定。 */
    AWAITING_APPROVAL,
    /** 已批准但尚未进入执行或等待下一步骤。 */
    APPROVED,
    /** 已领取执行权，存在活动执行。 */
    EXECUTING,
    /** 执行暂停，恢复前仍须核对工作区状态。 */
    PAUSED,
    /** 交付尚缺验证，不能作为成功完成显示。 */
    NEEDS_VERIFICATION,
    /** 当前执行契约要求的完成条件已经满足。 */
    COMPLETED,
    /** 用户拒绝计划，不授予执行权限。 */
    REJECTED,
    /** 工作区摘要不再匹配执行基线，禁止继续自动执行。 */
    DIGEST_CONFLICT,
    /** 执行失败，不能以模型正文替代成功证据。 */
    FAILED,
    /** 用户取消执行，不表示已发生副作用被回滚。 */
    CANCELLED,
    /** 执行时间预算耗尽。 */
    TIMED_OUT,
    /** 回合、工具、输出或其他资源上限阻止完成。 */
    LIMIT_EXCEEDED
}
