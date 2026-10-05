package io.github.liumaishenjian.ccjava.domain;

/**
 * 一次作用域声明拥有的资源的独立清理证据，不改变运行终态。
 *
 * <p>释放仅指本次声明的 owned 资源；不证明 OS Sandbox、任意后代退出或 Worktree 删除。
 * 正常返回的 close 和成功运行均不能代替明确的释放证据。旧协议或恢复缺少证据时使用 UNKNOWN。</p>
 */
public enum ResourceCleanupStatus {
    /** 没有可用的清理证据，包括旧构造和旧恢复记录。 */
    UNKNOWN,
    /** 已登记作用域所有权，尚未开始清理。 */
    NOT_STARTED,
    /** 已请求清理，正在关闭或等待确认；撤销可与Run执行重叠，不推断运行终态。 */
    CLEANING,
    /** 本次声明的所有资源均有明确释放证据。 */
    RELEASED,
    /** 清理失败或冻结快照时仍无法确认释放；不追踪迟到成功。 */
    UNCONFIRMED
}
