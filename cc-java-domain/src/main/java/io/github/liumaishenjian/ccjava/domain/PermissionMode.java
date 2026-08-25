package io.github.liumaishenjian.ccjava.domain;

/**
 * S05 Permission Policy Kernel 的运行模式。
 *
 * <p>{@link #DEFAULT} 允许读取并询问 Workspace 写入与本地进程；
 * {@link #PLAN} 只允许读取、Plan control 与 Session Task metadata；{@link #ACCEPT_EDITS} 自动允许已经通过参数校验和
 * Hard Denial 的 Workspace Write，但仍询问不透明进程调用。模式只是默认策略，
 * 不能覆盖 Hard Denial、显式 Deny 或 PLAN 限制。</p>
 *
 * @since 0.1.0
 */
public enum PermissionMode {

    /** 普通交互模式，副作用操作必须经过审批。 */
    DEFAULT,

    /** 固定安全规划模式，拒绝写入、进程、网络和系统副作用。 */
    PLAN,

    /** 只自动允许经过安全校验的 Workspace Write；进程仍须审批。 */
    ACCEPT_EDITS
}
