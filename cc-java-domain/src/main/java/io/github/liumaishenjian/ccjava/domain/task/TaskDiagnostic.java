package io.github.liumaishenjian.ccjava.domain.task;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 不回显任务正文、metadata、路径或底层异常的 Task 诊断。
 *
 * @param code 封闭错误码
 * @param taskId 已验证目标 identity
 * @param boardRevision 当前 Board revision
 * @param taskRevision 当前 Task revision
 * @param relatedTaskIds 有界依赖或冲突 identity
 * @since 0.15.0
 */
public record TaskDiagnostic(TaskDiagnosticCode code, Optional<TaskId> taskId, long boardRevision,
        Optional<Long> taskRevision, Set<TaskId> relatedTaskIds) {
    /** 复制安全诊断并限制 identity 数量。 */
    public TaskDiagnostic {
        code = Objects.requireNonNull(code, "code 不能为空");
        taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        if (boardRevision < 0) throw new IllegalArgumentException("boardRevision 不能为负数");
        taskRevision = Objects.requireNonNull(taskRevision, "taskRevision 不能为空");
        taskRevision.ifPresent(value -> { if (value < 1) throw new IllegalArgumentException("taskRevision 必须大于 0"); });
        relatedTaskIds = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(relatedTaskIds, "relatedTaskIds 不能为空")));
        if (relatedTaskIds.size() > 32) throw new IllegalArgumentException("Task 诊断 identity 超过上限");
    }
}
