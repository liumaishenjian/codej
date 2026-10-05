package io.github.liumaishenjian.ccjava.core;

import java.util.Objects;

/**
 * 单个 Plan 步骤的有界、不可变执行结果。
 *
 * @param status 封闭执行状态
 * @param workspaceDigest 执行后重新观察到的工作区摘要
 * @param detail 有界且不含原始 Tool 参数的说明
 */
public record PlanStepExecutionResult(Status status, String workspaceDigest, String detail) {
    /** 步骤执行的封闭结果，用于确定性映射计划终态。 */
    public enum Status {
        /** 步骤已成功，允许以结果摘要推进下一步。 */ SUCCESS,
        /** 执行失败，停止后续步骤。 */ FAILURE,
        /** 权限或审批拒绝，并未授予操作权限。 */ DENIED,
        /** 已观察到取消，不承诺回滚既有副作用。 */ CANCELLED,
        /** 时间预算耗尽。 */ TIMED_OUT,
        /** 资源或调用上限阻止继续执行。 */ LIMIT_EXCEEDED,
        /** 执行基线摘要失配。 */ CONFLICT
    }

    /** 校验非空状态和有界摘要、说明；摘要真实性仍由执行适配器保证。 */
    public PlanStepExecutionResult {
        status = Objects.requireNonNull(status, "status 不能为空");
        workspaceDigest = Objects.requireNonNull(workspaceDigest, "workspaceDigest 不能为空");
        detail = Objects.requireNonNull(detail, "detail 不能为空");
        if (workspaceDigest.isBlank() || workspaceDigest.length() > 256) {
            throw new IllegalArgumentException("workspaceDigest 无效");
        }
        if (detail.length() > 8_000) throw new IllegalArgumentException("detail 过长");
    }

    /**
     * 构造可供协调器推进步骤的成功结果。
     * @param digest 执行适配器提供的工作区摘要
     * @param detail 不含原始工具参数的有界说明
     * @return SUCCESS 结果，不在此检查工作区
     */
    public static PlanStepExecutionResult success(String digest, String detail) {
        return new PlanStepExecutionResult(Status.SUCCESS, digest, detail);
    }

    /**
     * 构造阻止继续执行的普通失败结果。
     * @param digest 失败时执行适配器已知的工作区摘要
     * @param detail 不含原始工具参数的有界失败说明
     * @return FAILURE 结果
     */
    public static PlanStepExecutionResult failure(String digest, String detail) {
        return new PlanStepExecutionResult(Status.FAILURE, digest, detail);
    }
}
