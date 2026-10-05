package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.RunId;

/**
 * Task Board 用于派生 interrupted claim 的最小 Run 状态查询端口。
 *
 * <p>实现只回答 Run 是否已终止，不提供 Transcript、Tool 结果或重放能力。</p>
 *
 * @since 0.15.0
 */
@FunctionalInterface
public interface TaskRunState {
    /**
     * 查询目标 Run 是否已经到达任一终态，不触发恢复或重放。
     * @param runId claim 绑定的 Run 身份
     * @return 已确定终止时为 true，不把未知状态当作终止
     */
    boolean terminated(RunId runId);

    /**
     * 返回不把任何 Run 判定为终止的默认实现。
     * @return 始终返回 false 的保守查询端口
     */
    static TaskRunState noneTerminated() { return ignored -> false; }
}
