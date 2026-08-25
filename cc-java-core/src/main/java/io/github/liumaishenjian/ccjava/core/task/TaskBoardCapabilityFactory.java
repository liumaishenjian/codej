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

    /** 创建当前 root Run 的完整 Board 读写能力。 */
    public static TaskBoardCapability root(TaskBoardId boardId, SessionId ownerSessionId, RunId runId) {
        Objects.requireNonNull(ownerSessionId, "ownerSessionId 不能为空");
        return new TaskBoardCapability(boardId, ownerSessionId,
                new TaskActorId("root:" + ownerSessionId.value()), ownerSessionId, runId, true,
                EnumSet.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), Set.of());
    }

    /** 创建独立 child Run 对 parent-owned Board 的宿主收窄能力。 */
    public static TaskBoardCapability child(TaskBoardId boardId, SessionId ownerSessionId,
            TaskActorId childActorId, SessionId childSessionId, RunId childRunId, Set<TaskId> taskScope) {
        return new TaskBoardCapability(boardId, ownerSessionId, childActorId, childSessionId, childRunId, false,
                EnumSet.of(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE), taskScope);
    }
}
