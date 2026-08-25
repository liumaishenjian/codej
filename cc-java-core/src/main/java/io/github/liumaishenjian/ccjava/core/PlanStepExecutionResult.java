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
    public enum Status { SUCCESS, FAILURE, DENIED, CANCELLED, TIMED_OUT, LIMIT_EXCEEDED, CONFLICT }

    public PlanStepExecutionResult {
        status = Objects.requireNonNull(status, "status 不能为空");
        workspaceDigest = Objects.requireNonNull(workspaceDigest, "workspaceDigest 不能为空");
        detail = Objects.requireNonNull(detail, "detail 不能为空");
        if (workspaceDigest.isBlank() || workspaceDigest.length() > 256) {
            throw new IllegalArgumentException("workspaceDigest 无效");
        }
        if (detail.length() > 8_000) throw new IllegalArgumentException("detail 过长");
    }

    public static PlanStepExecutionResult success(String digest, String detail) {
        return new PlanStepExecutionResult(Status.SUCCESS, digest, detail);
    }

    public static PlanStepExecutionResult failure(String digest, String detail) {
        return new PlanStepExecutionResult(Status.FAILURE, digest, detail);
    }
}
