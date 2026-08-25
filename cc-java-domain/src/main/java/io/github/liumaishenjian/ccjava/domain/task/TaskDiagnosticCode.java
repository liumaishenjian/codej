package io.github.liumaishenjian.ccjava.domain.task;

/**
 * Task Board mutation 可对外稳定映射的封闭结构错误码。
 *
 * <p>错误码只表达拒绝类别，不携带正文、路径、Tool 参数或底层异常信息。</p>
 *
 * @since 0.15.0
 */
public enum TaskDiagnosticCode {
    /** 目标 Task 从未存在。 */
    TASK_NOT_FOUND,
    /** 目标 Task 已形成 tombstone。 */
    TASK_DELETED,
    /** Task CAS revision 已过期。 */
    TASK_REVISION_CONFLICT,
    /** Board CAS 或 actor/callId 参数发生冲突。 */
    TASK_BOARD_CONFLICT,
    /** 状态或操作组合不在冻结 operation matrix 内。 */
    TASK_INVALID_TRANSITION,
    /** 仍存在未完成依赖。 */
    TASK_BLOCKED,
    /** 依赖节点、边或删除前置条件无效。 */
    TASK_DEPENDENCY_INVALID,
    /** 候选依赖图会形成环。 */
    TASK_DEPENDENCY_CYCLE,
    /** claim owner、状态或 epoch 冲突。 */
    TASK_CLAIM_CONFLICT,
    /** 终止 Run 留下的 claim 必须显式恢复。 */
    TASK_RECOVERY_REQUIRED,
    /** capability 的 Board、Session、effect、scope 或角色不允许操作。 */
    TASK_CAPABILITY_DENIED,
    /** 文本、metadata、任务数或边数超过资源上限。 */
    TASK_LIMIT_EXCEEDED
}
