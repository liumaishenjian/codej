package io.github.liumaishenjian.ccjava.domain.task;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 宿主注入给 root 或独立 child Session 的 Task Board 权限能力。
 *
 * <p>该值不是模型参数。root 可管理整个 Board；child 只能访问固定 scope，且不能借此读取父 Transcript
 * 或继承父 Permission Grant。</p>
 *
 * @param boardId 目标 parent-owned Board
 * @param ownerSessionId Board 的 root Session owner
 * @param actorId 当前可信 actor
 * @param actorSessionId actor 自己的 Session；child 与 owner 不同
 * @param actorRunId 宿主当前 Run identity；claim 与幂等域只能使用此可信值
 * @param root 是否具有 Board 管理权
 * @param effects 宿主授予的 Session state 读写 effect
 * @param taskScope child 可访问的 Task；root 必须为空
 * @since 0.15.0
 */
public record TaskBoardCapability(TaskBoardId boardId, SessionId ownerSessionId, TaskActorId actorId,
        SessionId actorSessionId, RunId actorRunId, boolean root, Set<ToolEffect> effects, Set<TaskId> taskScope) {
    /** 验证 identity、effect 和 root/child scope 不变量。 */
    public TaskBoardCapability {
        boardId = Objects.requireNonNull(boardId, "boardId 不能为空");
        ownerSessionId = Objects.requireNonNull(ownerSessionId, "ownerSessionId 不能为空");
        actorId = Objects.requireNonNull(actorId, "actorId 不能为空");
        actorSessionId = Objects.requireNonNull(actorSessionId, "actorSessionId 不能为空");
        actorRunId = Objects.requireNonNull(actorRunId, "actorRunId 不能为空");
        effects = Collections.unmodifiableSet(EnumSet.copyOf(Objects.requireNonNull(effects, "effects 不能为空")));
        taskScope = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(taskScope, "taskScope 不能为空")));
        if (effects.isEmpty() || effects.stream().anyMatch(effect -> effect != ToolEffect.READ_SESSION_STATE
                && effect != ToolEffect.WRITE_SESSION_STATE)) {
            throw new IllegalArgumentException("Task capability effect 无效");
        }
        if (taskScope.size() > 256) throw new IllegalArgumentException("Task capability scope 超过上限");
        if (root && !taskScope.isEmpty()) throw new IllegalArgumentException("Root capability 不应携带 Task scope");
        if (root && !ownerSessionId.equals(actorSessionId)) {
            throw new IllegalArgumentException("Root actor Session 必须等于 Board owner Session");
        }
        if (!root && ownerSessionId.equals(actorSessionId)) {
            throw new IllegalArgumentException("Child actor Session 必须独立于 Board owner Session");
        }
    }

    /**
     * 判断目标 Task 是否位于 capability 的身份范围内，不替代 effect 或 mutation Gate。
     * @param taskId 目标任务身份
     * @return root 或显式 scope 包含该任务时为 true
     */
    public boolean allows(TaskId taskId) { return root || taskScope.contains(taskId); }

    /**
     * 当前 capability 是否包含 Task Board 只读投影所需的读 effect。
     * @return 含 READ_SESSION_STATE 时为 true，仍需校验身份范围
     */
    public boolean canRead() { return effects.contains(ToolEffect.READ_SESSION_STATE); }

    /**
     * 当前 capability 是否包含 Task Board mutation 所需的写 effect。
     * @return 含 WRITE_SESSION_STATE 时为 true，仍需校验 mutation 与领取规则
     */
    public boolean canWrite() { return effects.contains(ToolEffect.WRITE_SESSION_STATE); }
}
