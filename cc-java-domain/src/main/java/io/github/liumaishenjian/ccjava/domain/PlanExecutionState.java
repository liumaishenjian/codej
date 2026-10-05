package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 规划执行的最小状态快照，保证同一时间最多一个活动步骤。
 *
 * @param planId 规划工件
 * @param approvalGate 审批 Gate
 * @param nextStep 下一个待执行步骤，完成后为空
 * @param activeStep 当前唯一活动步骤，通常为空
 * @param status 规划状态
 * @param workspaceDigest 执行前重新计算的摘要
 */
public record PlanExecutionState(String planId, PlanApprovalGate approvalGate,
                                 Integer nextStep, Integer activeStep,
                                 PlanStatus status, String workspaceDigest) {
    /** 校验摘要、正数步骤游标及审批前无活动步骤的不变量。 */
    public PlanExecutionState {
        planId = require(planId, "planId", 128);
        approvalGate = Objects.requireNonNull(approvalGate, "approvalGate 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        workspaceDigest = require(workspaceDigest, "workspaceDigest", 256);
        if (nextStep != null && nextStep < 1) throw new IllegalArgumentException("nextStep 无效");
        if (activeStep != null && activeStep < 1) throw new IllegalArgumentException("activeStep 无效");
        if (activeStep != null && nextStep != null && activeStep.equals(nextStep)) {
            throw new IllegalArgumentException("activeStep 与 nextStep 不能重复");
        }
        if (approvalGate != PlanApprovalGate.APPROVED && activeStep != null) {
            throw new IllegalArgumentException("未批准不能有活动步骤");
        }
    }
    /**
     * 判断计划审批 Gate 是否允许进入副作用执行阶段，不替代逐工具权限和摘要检查。
     * @return Gate 为 APPROVED 时为 true
     */
    public boolean sideEffectsAllowed() { return approvalGate == PlanApprovalGate.APPROVED; }
    private static String require(String value, String name, int max) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " 无效");
        return value;
    }
}
