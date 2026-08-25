package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * 在无 Tool Call 的最终 Assistant 回合形成 Run 终态前执行确定性验证或投影。
 *
 * <p>默认 Agent Run 使用 {@link #acceptAll()}。Plan Headless Runtime 可在这一线性化点验证
 * Session-owned evidence，并决定接受、拒绝或继续同一 Run；处理器不能调用模型、执行 Tool、
 * 自动重放副作用或建立第二份 transcript。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface FinalAssistantHandler {
    /**
     * 处理即将完成的最终 Assistant Message。
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param assistant 已聚合且不含 Tool Call 的消息
     * @return 接受时完成 Run；拒绝时以 INVALID_MODEL_RESPONSE 停止
     */
    boolean handle(SessionId sessionId, RunId runId, AssistantMessage assistant);

    /**
     * 返回形成终态前的完整决定。
     *
     * <p>兼容处理器继续把 {@link #handle(SessionId, RunId, AssistantMessage)} 的布尔结果映射为
     * ACCEPT/REJECT。需要在同一 Run 内执行有界纠正 continuation 的宿主可以覆写本方法；当前
     * Assistant 不会在 CONTINUE 时写入 canonical transcript 或作为完成文本展示。</p>
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param assistant 已聚合且不含 Tool Call 的消息
     * @return 确定性的接受、拒绝或继续决定
     */
    default FinalAssistantDecision decide(SessionId sessionId, RunId runId, AssistantMessage assistant) {
        return handle(sessionId, runId, assistant)
                ? FinalAssistantDecision.accept()
                : FinalAssistantDecision.reject();
    }

    /**
     * 判断当前聚合响应中的 Tool Call 是否仍被宿主允许。
     *
     * <p>普通 Run 默认允许，并继续走统一 Tool Pipeline。批准 Plan 在进入一次性 final-only 回合后
     * 可覆写为拒绝，使模型即使违反零 Tool 定义也立即以 INVALID_MODEL_RESPONSE 收口，而不是把
     * unknown tool 结果送回模型形成无界循环。</p>
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param assistant 含一个或多个 Tool Call 的完整 Assistant Message
     * @return 允许执行时为 {@code true}；拒绝时 Runtime 不追加消息、不执行 Tool
     */
    default boolean allowToolCalls(SessionId sessionId, RunId runId, AssistantMessage assistant) {
        return true;
    }

    /** 返回保持历史 Agent Runtime 语义的兼容处理器。 */
    static FinalAssistantHandler acceptAll() {
        return (ignoredSession, ignoredRun, ignoredAssistant) -> true;
    }
}
