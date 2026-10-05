package io.github.liumaishenjian.ccjava.domain;

/** 单项 Plan 证据的 durable 生命周期状态。 */
public enum PlanEvidenceStatus {
    /** 已预期但尚未取得满足要求的验证结果。 */
    EXPECTED,
    /** 确定性验证器已记录通过证据。 */
    PASSED,
    /** 已验证但结果不满足要求，继续阻止必需项完成。 */
    FAILED,
    /** 由可信用户决定显式跳过，不等于验证通过。 */
    SKIPPED
}
