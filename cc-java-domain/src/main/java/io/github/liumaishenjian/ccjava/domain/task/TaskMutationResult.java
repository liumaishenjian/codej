package io.github.liumaishenjian.ccjava.domain.task;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Task mutation 的确定性结果。
 *
 * <p>成功结果携带提交后的快照；拒绝结果携带未改变的当前快照和唯一结构诊断。同 actor/callId
 * 的完全相同重试可以返回同一个结果对象。</p>
 *
 * @param snapshot mutation 后或拒绝时的 Board 快照
 * @param task 成功 mutation 关联的 Task；删除成功为空
 * @param diagnostic 拒绝原因；成功为空
 * @since 0.15.0
 */
public record TaskMutationResult(TaskBoardSnapshot snapshot, Optional<TaskItemView> task,
        Optional<TaskDiagnostic> diagnostic) {
    /** 校验成功与拒绝结果互斥。 */
    public TaskMutationResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        task = Objects.requireNonNull(task, "task 不能为空");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic 不能为空");
        if (diagnostic.isPresent() && task.isPresent()) throw new IllegalArgumentException("拒绝结果不能携带 Task");
    }

    /** mutation 是否已经成功提交。 */
    public boolean succeeded() { return diagnostic.isEmpty(); }
}
