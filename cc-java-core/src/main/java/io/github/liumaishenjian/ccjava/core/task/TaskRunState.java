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
    /** 目标 Run 是否已经到达任一终态。 */
    boolean terminated(RunId runId);

    /** 默认没有 Run 被判定终止的实现。 */
    static TaskRunState noneTerminated() { return ignored -> false; }
}
