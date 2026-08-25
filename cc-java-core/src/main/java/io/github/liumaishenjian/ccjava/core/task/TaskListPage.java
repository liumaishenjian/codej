package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 稳定 TaskId 顺序的有界 Task List 页面。
 *
 * @param boardRevision 读取时 Board revision
 * @param tasks 最多 50 条紧凑摘要
 * @param nextCursor 仍有后续项时返回本页最后一个 TaskId
 * @since 0.15.0
 */
public record TaskListPage(long boardRevision, List<TaskSummary> tasks, Optional<TaskId> nextCursor) {
    /** 校验页面边界和稳定升序。 */
    public TaskListPage {
        if (boardRevision < 0) throw new IllegalArgumentException("boardRevision 不能为负数");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks 不能为空"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor 不能为空");
        if (tasks.size() > 50) throw new IllegalArgumentException("Task list page 超过上限");
        for (int index = 1; index < tasks.size(); index++) {
            if (tasks.get(index - 1).id().compareTo(tasks.get(index).id()) >= 0) {
                throw new IllegalArgumentException("Task list page 必须严格按 TaskId 升序");
            }
        }
    }
}
