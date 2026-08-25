package io.github.liumaishenjian.ccjava.domain;

/**
 * 模型可以据此采取纠正动作的稳定 Tool 错误分类。
 *
 * @since 0.1.0
 */
public enum ToolErrorCode {

    /** Registry 中不存在请求的 Tool。 */
    UNKNOWN_TOOL,

    /** Tool 参数不满足确定性校验。 */
    INVALID_ARGUMENTS,

    /** Tool 实现执行失败。 */
    EXECUTION_FAILED,

    /** 权限或人工审批拒绝调用。 */
    PERMISSION_DENIED,

    /** 用户 Hook 在 Permission 前明确阻断了调用。 */
    HOOK_BLOCKED,

    /** 当前 Plan 尚未通过审批或没有活动步骤。 */
    PLAN_GATE_BLOCKED,

    /** Tool 实现返回了违反协议的结果。 */
    RESULT_PROTOCOL_VIOLATION,

    /** 模型传入了绝对、UNC、drive-relative 或其他禁止的路径。 */
    INVALID_PATH,

    /** 规范化后的逻辑路径越过 Workspace。 */
    WORKSPACE_BOUNDARY_VIOLATION,

    /** 请求的路径不存在。 */
    PATH_NOT_FOUND,

    /** 请求路径的文件类型不满足 Tool 契约。 */
    PATH_TYPE_MISMATCH,

    /** Symlink 或 Junction 的真实目标逃出 Workspace。 */
    LINK_ESCAPE,

    /** 固定安全策略拒绝访问敏感路径。 */
    SENSITIVE_PATH,

    /** 普通文本文件超过当前 Stage 的读取上限。 */
    FILE_TOO_LARGE,

    /** 文件内容或存在状态不再满足写入前置条件。 */
    FILE_CONFLICT,

    /** 文件不是受支持的严格 UTF-8 文本。 */
    UNSUPPORTED_ENCODING,

    /** Workspace 不是 Git 仓库。 */
    NOT_A_GIT_REPOSITORY,

    /** 当前平台找不到可用 Git 程序。 */
    GIT_UNAVAILABLE,

    /** 固定只读 Git 操作失败。 */
    GIT_READ_FAILED,

    /** 当前平台找不到可用的精确文本搜索引擎。 */
    SEARCH_UNAVAILABLE,

    /** 精确搜索的机器输出违反约定协议。 */
    SEARCH_PROTOCOL_VIOLATION,

    /** Tool Adapter 已响应当前 Run 的取消信号。 */
    OPERATION_CANCELLED,

    /** 固定只读操作达到墙钟期限。 */
    OPERATION_TIMED_OUT,

    /** Tool 或 Adapter 的有界输出超过允许预算。 */
    OUTPUT_LIMIT_EXCEEDED,

    /** Web 搜索未由可信本地配置启用。 */
    WEB_SEARCH_DISABLED,

    /** NetworkAccessPort 拒绝当前受控出站。 */
    NETWORK_ACCESS_DENIED,

    /** 当前出站路径无法由 NetworkAccessPort 完整控制。 */
    NETWORK_CONTROL_UNAVAILABLE,

    /** Web 搜索实际目标与固定授权目标不一致。 */
    WEB_SEARCH_INVALID_TARGET,

    /** Web 搜索服务要求重定向但当前契约禁止跟随。 */
    WEB_SEARCH_REDIRECT_REFUSED,

    /** Web 搜索服务返回 403；具体原因只有受信 Adapter 信号存在时才细分。 */
    WEB_SEARCH_FORBIDDEN,

    /** Web 搜索服务返回 429 限流。 */
    WEB_SEARCH_RATE_LIMITED,

    /** Web 搜索服务返回其他 4xx。 */
    WEB_SEARCH_REMOTE_CLIENT_ERROR,

    /** Web 搜索服务返回 5xx。 */
    WEB_SEARCH_REMOTE_SERVER_ERROR,

    /** hosted MCP 返回 JSON-RPC error。 */
    WEB_SEARCH_REMOTE_PROTOCOL_ERROR,

    /** Web 搜索响应声明了不受支持的 media type。 */
    WEB_SEARCH_UNSUPPORTED_MEDIA_TYPE,

    /** Web 搜索响应不满足严格、有界 JSON-RPC JSON/SSE 契约。 */
    WEB_SEARCH_MALFORMED_RESPONSE,

    /** hosted MCP 没有返回可用搜索内容。 */
    WEB_SEARCH_NO_RESULTS,

    /** 子进程已启动但以非零状态退出。 */
    PROCESS_EXIT,

    /** Plan 工件在可信控制面提交前发生并发漂移或生命周期前置条件变化。 */
    PLAN_ARTIFACT_CONFLICT,

    /** Plan 工件持久层损坏、不可用或无法安全收敛。 */
    PLAN_ARTIFACT_UNAVAILABLE,

    /** Task identity 不存在。 */
    TASK_NOT_FOUND,

    /** Task identity 已被删除并保留 tombstone。 */
    TASK_DELETED,

    /** Task revision CAS 冲突。 */
    TASK_REVISION_CONFLICT,

    /** Task Board revision 或幂等参数冲突。 */
    TASK_BOARD_CONFLICT,

    /** Task 状态迁移或语义 no-op 无效。 */
    TASK_INVALID_TRANSITION,

    /** Task 仍被未完成依赖阻塞。 */
    TASK_BLOCKED,

    /** Task 依赖边无效。 */
    TASK_DEPENDENCY_INVALID,

    /** Task 依赖图形成环。 */
    TASK_DEPENDENCY_CYCLE,

    /** Task claim owner 或 epoch 冲突。 */
    TASK_CLAIM_CONFLICT,

    /** Task claim 所属 Run 已终止，必须显式恢复。 */
    TASK_RECOVERY_REQUIRED,

    /** Task Board capability、scope 或可信身份拒绝访问。 */
    TASK_CAPABILITY_DENIED,

    /** Task Board 资源预算已耗尽。 */
    TASK_LIMIT_EXCEEDED,

    /** Runtime 阻止了没有策略变化的相同失败调用。 */
    REPEATED_FAILURE,

    /** Runtime 无法归类的内部错误。 */
    INTERNAL_ERROR
}
