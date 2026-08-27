package io.github.liumaishenjian.ccjava.domain;

/**
 * Agent Run 的稳定终止原因。
 *
 * <p>该枚举保留后续 Stage 所需的协议值，但 S01 只主动产生
 * {@link #COMPLETED}、{@link #MODEL_ERROR}、{@link #INVALID_MODEL_RESPONSE}、
 * {@link #TURN_LIMIT_REACHED}、{@link #TOOL_LIMIT_REACHED} 和
 * {@link #INTERNAL_ERROR}。</p>
 *
 * @since 0.1.0
 */
public enum StopReason {

    /** 模型给出不含 Tool Call 的最终回复。 */
    COMPLETED,

    /** 用户取消当前 Run。 */
    USER_CANCELLED,

    /** 模型 Provider 调用失败。 */
    MODEL_ERROR,

    /** 模型瞬时错误已经耗尽本回合的有界重试。 */
    MODEL_RETRY_EXHAUSTED,

    /** 模型流在完整终态前结束，残缺内容未进入规范历史。 */
    INCOMPLETE_MODEL_STREAM,

    /** 模型输出达到长度上限，本阶段选择明确停止而不自动续写。 */
    OUTPUT_LIMIT_REACHED,

    /** 模型既未返回文本，也未返回有效 Tool Call。 */
    INVALID_MODEL_RESPONSE,

    /** 已达到模型回合上限。 */
    TURN_LIMIT_REACHED,

    /** 下一批 Tool Call 超出工具数量上限。 */
    TOOL_LIMIT_REACHED,

    /** 已达到 Run 时间限制。 */
    TIME_LIMIT_REACHED,

    /** Context 无法在安全预算内继续组装。 */
    CONTEXT_LIMIT_REACHED,

    /** 已批准 Plan 的精确 Evidence 或 Task Gate 在有界纠正后仍未满足，可显式恢复。 */
    PLAN_VERIFICATION_REQUIRED,

    /** 关键操作被拒绝且 Run 无法继续。 */
    PERMISSION_DENIED,

    /** 用户 Hook 在 Run 建立前阻断了本次用户请求。 */
    HOOK_BLOCKED,

    /** 自动审查连续 non-allow 达到 Run 阈值，当前拒绝已完成后停止。 */
    AUTO_REVIEW_CIRCUIT_OPEN,

    /** 不可恢复的 Tool 错误。 */
    TOOL_ERROR,

    /** Runtime 不变量被破坏或出现未分类错误。 */
    INTERNAL_ERROR
}
