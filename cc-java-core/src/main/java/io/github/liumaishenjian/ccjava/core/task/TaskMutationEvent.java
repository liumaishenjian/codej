package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.TaskActorId;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot;
import io.github.liumaishenjian.ccjava.domain.task.TaskMutationResult;
import java.util.Objects;

/**
 * 一次已成功 Task mutation 的 canonical durable 事实。
 *
 * <p>事件同时保存可信调用域、封闭命令和提交后的完整 Board 投影。恢复时无需重新读取时钟、
 * Run 活性或外部 actor 目录，即可精确重建 revision、tombstone、claim 与成功幂等索引。</p>
 *
 * @param actorId 执行 mutation 的可信 actor
 * @param actorSessionId actor 自己的 Session
 * @param actorRunId actor 当前 Run
 * @param mutation 已通过状态机的封闭命令
 * @param result 已提交结果；必须成功且与 snapshot 相同
 * @since 0.15.0
 */
public record TaskMutationEvent(TaskActorId actorId, SessionId actorSessionId, RunId actorRunId,
        TaskMutation mutation, TaskMutationResult result) {
    /** 验证事件只能表达成功 mutation。 */
    public TaskMutationEvent {
        actorId = Objects.requireNonNull(actorId, "actorId 不能为空");
        actorSessionId = Objects.requireNonNull(actorSessionId, "actorSessionId 不能为空");
        actorRunId = Objects.requireNonNull(actorRunId, "actorRunId 不能为空");
        mutation = Objects.requireNonNull(mutation, "mutation 不能为空");
        result = Objects.requireNonNull(result, "result 不能为空");
        if (!result.succeeded()) throw new IllegalArgumentException("Task mutation event 必须是成功结果");
    }

    /** 返回 canonical post-state。 */
    public TaskBoardSnapshot snapshot() { return result.snapshot(); }
}
