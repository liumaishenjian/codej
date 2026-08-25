package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskItemView;
import java.util.Objects;

/**
 * 在同一 Board 临界区内捕获 Get 所需的 Board revision 与 Task detail。
 *
 * <p>该投影避免 Adapter 先读 revision、再读任务造成并发撕裂；它仍是瞬时只读值，
 * 不承担持久化或稳定外部协议职责。</p>
 *
 * @param boardRevision 生成 detail 时的 Board revision
 * @param task canonical/derived detail
 * @since 0.15.0
 */
public record TaskGetProjection(long boardRevision, TaskItemView task) {
    /** 校验投影内容。 */
    public TaskGetProjection {
        if (boardRevision < 0) throw new IllegalArgumentException("boardRevision 不能为负数");
        task = Objects.requireNonNull(task, "task 不能为空");
    }
}
