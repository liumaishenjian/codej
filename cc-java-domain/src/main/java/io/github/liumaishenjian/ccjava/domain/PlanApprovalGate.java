package io.github.liumaishenjian.ccjava.domain;

/** 规划审批结果；未批准前任何普通副作用 Tool 都不得执行。 */
public enum PlanApprovalGate {
    /** 尚无批准决定，禁止普通副作用工具执行。 */
    PENDING,
    /** 用户已批准计划，执行仍须通过逐工具权限与实时摘要 Gate。 */
    APPROVED,
    /** 用户已拒绝计划，不授予执行权限。 */
    REJECTED
}
