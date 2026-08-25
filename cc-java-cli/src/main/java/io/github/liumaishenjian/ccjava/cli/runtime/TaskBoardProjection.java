package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot;
import io.github.liumaishenjian.ccjava.domain.task.TaskItemView;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * 将 canonical Task Board 转换为内部 stdio/TUI 共用的有界安全投影。
 *
 * <p>该类型只做确定性选择与字段裁剪，不拥有 Task 状态，也不读取 description、metadata、
 * claim 或时间戳正文。活动任务优先，最近完成最多保留五项，最终投影最多五十项。</p>
 *
 * @since 0.1.1
 */
public final class TaskBoardProjection {
    private TaskBoardProjection() { }

    /**
     * 构造一次不可变展示快照。
     *
     * @param snapshot Java Runtime 权威 Board 快照
     * @return 有界 Task List payload
     */
    public static SessionCommandEvent.TaskListPayload project(TaskBoardSnapshot snapshot) {
        var active = snapshot.tasks().values().stream()
                .filter(view -> view.status() != TaskStatus.COMPLETED)
                .sorted(Comparator.comparingInt(TaskBoardProjection::displayRank)
                        .thenComparing(TaskItemView::id))
                .toList();
        var completed = snapshot.tasks().values().stream()
                .filter(view -> view.status() == TaskStatus.COMPLETED)
                .sorted(Comparator.comparing((TaskItemView view) -> view.item().updatedAt()).reversed()
                        .thenComparing(TaskItemView::id))
                .limit(5)
                .toList();
        ArrayList<TaskItemView> selected = new ArrayList<>(50);
        active.stream().limit(Math.max(0, 50 - completed.size())).forEach(selected::add);
        completed.forEach(selected::add);
        selected.sort(Comparator.comparing(TaskItemView::id));
        var rows = selected.stream().map(view -> new SessionCommandEvent.TaskView(
                view.id().value(), view.revision(), view.status().name(), view.subject(), view.blocked(),
                view.activeBlockers().stream().map(taskId -> taskId.value()).toList(),
                view.owner().map(actor -> actor.value()).orElse(null), view.activeForm().orElse(null),
                view.recoveryRequired())).toList();
        return new SessionCommandEvent.TaskListPayload(snapshot.revision(), snapshot.tasks().size(),
                snapshot.tasks().size() > rows.size(), rows);
    }

    private static int displayRank(TaskItemView task) {
        if (task.status() == TaskStatus.IN_PROGRESS) return task.recoveryRequired() ? 0 : 1;
        return task.blocked() ? 3 : 2;
    }
}
