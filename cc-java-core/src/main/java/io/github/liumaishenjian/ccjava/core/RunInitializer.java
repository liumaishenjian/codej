package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * 在 Agent Runtime 已生成真实 Run identity 并取得活动 Run 所有权后初始化绑定事实。
 *
 * <p>此时 canonical Run/User Message 已写入，但模型、Plugin 和 Tool 尚未运行。
 * 实现不得请求模型或执行 Tool；抛出异常会以成对的 RunStarted/RunFinished
 * lifecycle 和 {@code INTERNAL_ERROR} journal 终态收口，因此适合 fail-closed 地持久化
 * 必须在首个模型回合前存在的应用事实。</p>
 *
 * @since 0.15.0
 */
@FunctionalInterface
public interface RunInitializer {

    /**
     * 使用 Runtime 生成且调用方不可选择的身份完成单次初始化。
     *
     * @param sessionId 即将执行的 Session
     * @param runId 即将执行的真实 Run identity
     */
    void initialize(SessionId sessionId, RunId runId);

    /** 返回不创建额外应用事实的默认初始化器。 */
    static RunInitializer noop() {
        return (ignoredSession, ignoredRun) -> { };
    }
}
