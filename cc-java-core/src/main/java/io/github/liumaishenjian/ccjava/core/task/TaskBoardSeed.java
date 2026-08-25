package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fork 目标 Session 的 canonical Task Board lineage seed。
 *
 * <p>seed 不是成功 mutation，不进入幂等缓存。COMPLETED/PENDING 项保留正文与依赖；IN_PROGRESS
 * 项显式回到 PENDING 并清除 owner/claim，所有派生 recovery 与调用幂等事实均不跨 Fork。</p>
 *
 * @param parentBoardId 来源 Board identity
 * @param snapshot 新 Session 拥有的新 Board 初始投影
 * @since 0.15.0
 */
public record TaskBoardSeed(TaskBoardId parentBoardId, TaskBoardSnapshot snapshot) {
    /** 验证 lineage identity。 */
    public TaskBoardSeed {
        parentBoardId = Objects.requireNonNull(parentBoardId, "parentBoardId 不能为空");
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if (parentBoardId.equals(snapshot.boardId())) throw new IllegalArgumentException("Fork Board 必须使用新 identity");
    }

    /** 从 source 快照构造新 owner 的安全 Fork seed。 */
    public static TaskBoardSeed fork(TaskBoardSnapshot source, TaskBoardId targetBoardId, SessionId targetOwner) {
        Map<TaskId, TaskItemView> tasks = new LinkedHashMap<>();
        source.tasks().forEach((id, view) -> {
            TaskItem item = view.item();
            TaskItem copied = item;
            if (item.status() == TaskStatus.IN_PROGRESS) {
                copied = new TaskItem(item.id(), item.revision(), TaskStatus.PENDING, item.subject(),
                        item.description(), item.activeForm(), item.metadata(), item.blockedBy(), Optional.empty(),
                        Optional.empty(), item.lastClaimEpoch(), item.createdAt(), item.updatedAt());
            }
            tasks.put(id, new TaskItemView(copied, view.blocks(), view.blocked(), view.activeBlockers(), false));
        });
        return new TaskBoardSeed(source.boardId(), new TaskBoardSnapshot(targetBoardId, targetOwner,
                source.revision(), source.highWaterMark(), tasks, source.tombstones()));
    }
}
