package io.github.liumaishenjian.ccjava.domain;

/**
 * 描述 Tool 可能产生的最高副作用等级。
 *
 * <p>S01 只把该值作为 Tool 契约和权限端口的输入，不实现完整权限策略。
 * Effect 不能替代 Tool 对路径、命令或业务参数的自身校验。</p>
 *
 * @since 0.1.0
 */
public enum ToolEffect {

    /** 仅在受控 Workspace 内读取信息。 */
    READ_WORKSPACE,

    /** 在受控 Workspace 内创建、修改或删除内容。 */
    WRITE_WORKSPACE,

    /** 只写当前 Session 独占的 PlanArtifact，不触碰 Workspace。 */
    PLAN_ARTIFACT_WRITE,

    /** 读取当前 Session 内部状态，不读取 Workspace；仅可信内置 Task Tool 可默认使用。 */
    READ_SESSION_STATE,

    /** 修改当前 Session 内部状态，不触碰 Workspace；仅可信内置 Task Tool 可默认使用。 */
    WRITE_SESSION_STATE,

    /** 暂停当前模型循环并等待结构化用户选择。 */
    USER_INTERACTION,

    /** 启动本地进程。 */
    EXECUTE_PROCESS,

    /** 访问网络或改变远程系统状态。 */
    NETWORK_OR_REMOTE,

    /** 修改系统范围状态或执行破坏性操作。 */
    SYSTEM_OR_DESTRUCTIVE
}
