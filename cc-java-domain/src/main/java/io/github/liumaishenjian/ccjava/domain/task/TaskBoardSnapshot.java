package io.github.liumaishenjian.ccjava.domain.task;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 一次线性化 mutation 后的不可变 Task Board 投影。
 *
 * @param boardId Board identity
 * @param ownerSessionId root owner Session
 * @param revision Board CAS revision
 * @param highWaterMark 已分配过的最大 Task sequence
 * @param tasks 当前 live Task views
 * @param tombstones 已删除且不得复用的 Task IDs
 * @since 0.15.0
 */
public record TaskBoardSnapshot(TaskBoardId boardId, SessionId ownerSessionId, long revision,
        long highWaterMark, Map<TaskId, TaskItemView> tasks, Set<TaskId> tombstones) {
    /** 复制 Board 投影并验证 identity/revision/high-water 不变量。 */
    public TaskBoardSnapshot {
        boardId = Objects.requireNonNull(boardId, "boardId 不能为空");
        ownerSessionId = Objects.requireNonNull(ownerSessionId, "ownerSessionId 不能为空");
        if (revision < 0 || highWaterMark < 0) throw new IllegalArgumentException("Board revision/highWaterMark 不能为负数");
        tasks = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(tasks, "tasks 不能为空")));
        tombstones = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(tombstones, "tombstones 不能为空")));
        if (tasks.size() > 256 || tasks.keySet().stream().anyMatch(tombstones::contains)
                || tasks.keySet().stream().anyMatch(id -> id.sequence() > highWaterMark)
                || tombstones.stream().anyMatch(id -> id.sequence() > highWaterMark)) {
            throw new IllegalArgumentException("Task Board 快照不变量无效");
        }
    }

    /** 按安全 identity 返回 Task view。 */
    public Optional<TaskItemView> task(TaskId id) { return Optional.ofNullable(tasks.get(id)); }
}
