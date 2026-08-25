package io.github.liumaishenjian.ccjava.domain.task;

/**
 * Task Board 对外公开的唯一三种任务状态。
 *
 * <p>阻塞和恢复要求均由依赖与 claim/Run 投影，不得扩张为额外持久状态。</p>
 *
 * @since 0.15.0
 */
public enum TaskStatus {
    /** 尚未开始，可在未阻塞时被领取。 */
    PENDING,
    /** 已存在活动或待恢复 claim。 */
    IN_PROGRESS,
    /** 已由显式 mutation 标记完成。 */
    COMPLETED
}
