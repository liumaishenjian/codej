package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 只从宿主身份生成不可由模型伪造的 root/child Task Board capability。
 *
 * <p>root actor 对 owner Session 生命周期稳定；Run 每次调用单独绑定。child 保持自己的 Session、Run
 * 与 Permission，只得到宿主明确给出的 parent-board Task scope。</p>
 *
 * @since 0.15.0
 */
public final class TaskBoardCapabilityFactory {
    private TaskBoardCapabilityFactory() { }

    /**
     * 创建当前 root Run 的完整 Board 读写能力。
     * @param boardId 宿主持有的 Board 身份
     * @param ownerSessionId Board 所有者，同时作为 root actor 的 Session
     * @param runId 当前 root Run 身份
     * @return 不限制单个 Task scope、但仍绑定 Session/Run 的读写能力
     */
    public static TaskBoardCapability root(TaskBoardId boardId, SessionId ownerSessionId, RunId runId) {
        Objects.requireNonNull(ownerSessionId, "ownerSessionId 不能为空");
        return new TaskBoardCapability(boardId, ownerSessionId,
                new TaskActorId("root:" + ownerSessionId.value()), ownerSessionId, runId, true,
                EnumSet.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
    }

    /**
     * 创建独立 child Run 对 parent-owned Board 的宿主收窄能力。
     * @param boardId 父 Session 拥有的 Board 身份
     * @param ownerSessionId 父 Board 所有者
     * @param childActorId 宿主分配的子 actor 身份
     * @param childSessionId 必须与父所有者不同的子 Session
     * @param childRunId 当前子 Run 身份
     * @param taskScope 明确允许子 Run 访问的 Task 集合
     * @return 仅在指定 Task scope 内有效的非 root 读写能力
     */
    public static TaskBoardCapability child(TaskBoardId boardId, SessionId ownerSessionId,
            TaskActorId childActorId, SessionId childSessionId, RunId childRunId, Set<TaskId> taskScope) {
        return new TaskBoardCapability(boardId, ownerSessionId, childActorId, childSessionId, childRunId, false,
                EnumSet.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), taskScope);
    }
}
