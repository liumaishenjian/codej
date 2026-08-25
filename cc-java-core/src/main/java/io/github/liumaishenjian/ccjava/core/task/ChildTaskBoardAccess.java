package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskActorId;
import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 宿主为一次 child 委托冻结的 parent-owned Task Board 访问范围。
 *
 * <p>该值只由可信 composition root 构造，不能从 {@code ChildTaskRequest} 的模型字段推导。
 * child 仍使用自己的 Session、Run、Permission 与 Pipeline；这里只共享 parent Board service，
 * 并把访问范围固定为有界 Task ID 集。</p>
 *
 * @param board parent Session 持有的 durable Board service
 * @param actorId 宿主分配给 child invocation 的稳定 actor identity
 * @param taskScope child 可读写的固定 Task 集
 * @param actorDirectory ASSIGN/REASSIGN 使用的可信 actor directory
 * @since 0.15.0
 */
public record ChildTaskBoardAccess(TaskListService board, TaskActorId actorId, Set<TaskId> taskScope,
        TaskActorDirectory actorDirectory) {
    /** 复制并验证宿主提供的访问范围。 */
    public ChildTaskBoardAccess {
        board = Objects.requireNonNull(board, "board 不能为空");
        actorId = Objects.requireNonNull(actorId, "actorId 不能为空");
        taskScope = Set.copyOf(new TreeSet<>(Objects.requireNonNull(taskScope, "taskScope 不能为空")));
        actorDirectory = Objects.requireNonNull(actorDirectory, "actorDirectory 不能为空");
        if (taskScope.size() > 256) {
            throw new IllegalArgumentException("child Task scope 超过上限");
        }
    }
}
