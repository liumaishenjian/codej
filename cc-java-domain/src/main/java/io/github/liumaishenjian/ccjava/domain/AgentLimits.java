package io.github.liumaishenjian.ccjava.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 限制单次 Run 可以消耗的模型回合、Tool Call 数量和墙钟时间。
 *
 * <p>兼容构造器始终产生 {@link AgentBudgetPolicy#EXPLICIT_HARD}。普通交互必须由 Composition
 * Root 显式选择 adaptive factory；16/32 仅为软检查点，持续成功进展可以续租，但仍受绝对
 * ceiling、墙钟和其他既有预算约束。</p>
 *
 * @param maxModelTurns 初始模型回合检查点或显式硬上限
 * @param maxToolCalls 初始 Tool Call 检查点或显式硬上限
 * @param maxDuration 单次 Run 最大墙钟时间
 * @param budgetPolicy 数量预算策略
 * @param absoluteMaxModelTurns adaptive 策略的模型回合绝对上限
 * @param absoluteMaxToolCalls adaptive 策略的 Tool Call 绝对上限
 * @since 0.1.0
 */
public record AgentLimits(int maxModelTurns, int maxToolCalls, Duration maxDuration,
                          AgentBudgetPolicy budgetPolicy,
                          int absoluteMaxModelTurns, int absoluteMaxToolCalls) {
    /** 保守的兼容显式限制。 */
    public static final AgentLimits DEFAULT = new AgentLimits(16, 32, Duration.ofMinutes(5));
    /** 普通交互的独立绝对回合 ceiling。 */
    public static final int INTERACTIVE_ABSOLUTE_MODEL_TURNS = 128;
    /** 普通交互的独立绝对 Tool ceiling。 */
    public static final int INTERACTIVE_ABSOLUTE_TOOL_CALLS = 256;

    /**
     * 使用默认五分钟创建显式硬预算。
     *
     * @param maxModelTurns 模型回合硬上限
     * @param maxToolCalls Tool Call 硬上限
     */
    public AgentLimits(int maxModelTurns, int maxToolCalls) {
        this(maxModelTurns, maxToolCalls, DEFAULT.maxDuration);
    }

    /**
     * 创建显式硬预算。
     *
     * @param maxModelTurns 模型回合硬上限
     * @param maxToolCalls Tool Call 硬上限
     * @param maxDuration Run 最大墙钟时间
     */
    public AgentLimits(int maxModelTurns, int maxToolCalls, Duration maxDuration) {
        this(maxModelTurns, maxToolCalls, maxDuration, AgentBudgetPolicy.EXPLICIT_HARD,
                maxModelTurns, maxToolCalls);
    }

    /**
     * 为普通交互创建进展感知软预算。
     *
     * @param maxDuration 单次 Run 最大墙钟时间
     * @return 使用独立绝对 ceiling 的交互预算
     */
    public static AgentLimits interactive(Duration maxDuration) {
        return new AgentLimits(DEFAULT.maxModelTurns, DEFAULT.maxToolCalls, maxDuration,
                AgentBudgetPolicy.INTERACTIVE_ADAPTIVE,
                INTERACTIVE_ABSOLUTE_MODEL_TURNS, INTERACTIVE_ABSOLUTE_TOOL_CALLS);
    }

    /** 校验所有软硬边界。 */
    public AgentLimits {
        maxDuration = Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy 不能为空");
        if (maxModelTurns < 1 || maxToolCalls < 0) throw new IllegalArgumentException("数量预算非法");
        if (maxDuration.isZero() || maxDuration.isNegative()) throw new IllegalArgumentException("maxDuration 必须大于 0");
        if (absoluteMaxModelTurns < maxModelTurns || absoluteMaxToolCalls < maxToolCalls) {
            throw new IllegalArgumentException("绝对上限不能小于初始检查点");
        }
        if (budgetPolicy == AgentBudgetPolicy.EXPLICIT_HARD
                && (absoluteMaxModelTurns != maxModelTurns || absoluteMaxToolCalls != maxToolCalls)) {
            throw new IllegalArgumentException("显式硬预算不能携带更高隐式 ceiling");
        }
    }
}
