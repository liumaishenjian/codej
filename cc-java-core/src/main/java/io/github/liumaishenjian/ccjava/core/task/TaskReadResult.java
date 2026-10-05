package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskDiagnostic;
import java.util.Objects;
import java.util.Optional;

/**
 * Task List/Get 的只读结果，成功值与安全诊断互斥。
 *
 * @param value 成功投影
 * @param diagnostic capability、missing 或 tombstone 诊断
 * @param <T> 只读投影类型
 * @since 0.15.0
 */
public record TaskReadResult<T>(Optional<T> value, Optional<TaskDiagnostic> diagnostic) {
    /** 校验成功与拒绝结果互斥且恰有其一。 */
    public TaskReadResult {
        value = Objects.requireNonNull(value, "value 不能为空");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic 不能为空");
        if (value.isPresent() == diagnostic.isPresent()) {
            throw new IllegalArgumentException("Task read 必须恰好包含 value 或 diagnostic");
        }
    }

    /**
     * 创建只含成功投影的读取结果。
     * @param <T> 只读投影类型
     * @param value 非空的读取投影
     * @return 无诊断的成功结果
     */
    public static <T> TaskReadResult<T> success(T value) {
        return new TaskReadResult<>(Optional.of(Objects.requireNonNull(value)), Optional.empty());
    }

    /**
     * 创建不携带数据投影的安全拒绝。
     * @param <T> 调用方预期的只读投影类型
     * @param diagnostic 非空且不暴露敏感数据的结构化诊断
     * @return 无数据的拒绝结果
     */
    public static <T> TaskReadResult<T> rejected(TaskDiagnostic diagnostic) {
        return new TaskReadResult<>(Optional.empty(), Optional.of(Objects.requireNonNull(diagnostic)));
    }

    /**
     * 根据互斥结果分支判断读取是否成功。
     * @return 存在成功投影时为 true
     */
    public boolean succeeded() { return value.isPresent(); }
}
