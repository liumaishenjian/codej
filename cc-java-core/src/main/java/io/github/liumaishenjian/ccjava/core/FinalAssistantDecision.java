package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.StopReason;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示最终 Assistant 回合在形成 Run 终态前的确定性处理决定。
 *
 * <p>{@link Outcome#CONTINUE} 只允许宿主在同一个 Agent Run 内请求下一模型回合；它不会
 * 自动执行 Tool、重放既有副作用或接受当前模型 prose。{@link Outcome#STOP} 用于宿主已经
 * 得到确定性、非成功且可恢复的业务终态；当前候选 prose 同样不会写入 canonical transcript。</p>
 *
 * @param outcome 接受、拒绝、停止或继续同一 Run
 * @param stopReason STOP 时由宿主选择的非 COMPLETED typed 原因
 * @since 0.1.0
 */
public record FinalAssistantDecision(Outcome outcome, Optional<StopReason> stopReason) {
    /** 最终 Assistant 的四种确定性处理结果。 */
    public enum Outcome {
        /** 接受候选正文并正常完成。 */
        ACCEPT,
        /** 将候选响应判为无效，不写入规范历史。 */
        REJECT,
        /** 丢弃候选正文，以指定的非成功原因停止。 */
        STOP,
        /** 丢弃候选正文，在当前 Run 内继续模型回合。 */
        CONTINUE
    }

    /** 验证决定与可选停止原因的一致性。 */
    public FinalAssistantDecision {
        outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
        stopReason = Objects.requireNonNull(stopReason, "stopReason 不能为空");
        if (outcome == Outcome.STOP) {
            if (stopReason.isEmpty() || stopReason.orElseThrow() == StopReason.COMPLETED) {
                throw new IllegalArgumentException("STOP 必须携带非 COMPLETED 原因");
            }
        } else if (stopReason.isPresent()) {
            throw new IllegalArgumentException("只有 STOP 可以携带 stopReason");
        }
    }

    /**
     * 保持旧单字段构造调用兼容。
     * @param outcome 不需要停止原因的决定；STOP 应使用双字段构造器
     */
    public FinalAssistantDecision(Outcome outcome) {
        this(outcome, Optional.empty());
    }

    /**
     * 接受当前最终 Assistant 并形成正常完成终态。
     * @return 不携带停止原因的 ACCEPT 决定
     */
    public static FinalAssistantDecision accept() {
        return new FinalAssistantDecision(Outcome.ACCEPT);
    }

    /**
     * 拒绝当前最终 Assistant，并按无效模型响应停止。
     * @return 不接受当前正文的 REJECT 决定
     */
    public static FinalAssistantDecision reject() {
        return new FinalAssistantDecision(Outcome.REJECT);
    }

    /**
     * 丢弃当前 prose，并以宿主确定的 typed 非成功原因停止。
     * @param reason 非空且不能为 COMPLETED 的停止原因
     * @return 携带指定原因的 STOP 决定
     */
    public static FinalAssistantDecision stop(StopReason reason) {
        return new FinalAssistantDecision(Outcome.STOP, Optional.of(Objects.requireNonNull(reason, "reason 不能为空")));
    }

    /**
     * 暂不接受当前 prose，在同一 Run 内继续下一模型回合。
     * @return 不自动执行工具或重放副作用的 CONTINUE 决定
     */
    public static FinalAssistantDecision continueRun() {
        return new FinalAssistantDecision(Outcome.CONTINUE);
    }
}
