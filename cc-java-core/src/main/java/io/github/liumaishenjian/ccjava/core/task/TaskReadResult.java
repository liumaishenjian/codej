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

    /** 创建成功读取。 */
    public static <T> TaskReadResult<T> success(T value) {
        return new TaskReadResult<>(Optional.of(Objects.requireNonNull(value)), Optional.empty());
    }

    /** 创建安全拒绝。 */
    public static <T> TaskReadResult<T> rejected(TaskDiagnostic diagnostic) {
        return new TaskReadResult<>(Optional.empty(), Optional.of(Objects.requireNonNull(diagnostic)));
    }

    /** 读取是否成功。 */
    public boolean succeeded() { return value.isPresent(); }
}
