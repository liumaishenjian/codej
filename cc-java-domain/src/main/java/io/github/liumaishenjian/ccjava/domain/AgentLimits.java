package io.github.liumaishenjian.ccjava.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 描述调用方是否为单次 Agent Run 提供总模型回合、总 Tool Call 和总墙钟硬限制。
 *
 * <p>三个维度分别建模为 presence/absence：Print、API 或 SDK 可以显式提供一个或多个硬限制；
 * 普通 Interactive、Plan 与 approved-plan 使用 {@link #interactive()}，不会隐式装配总次数或
 * Run deadline。用户取消、Provider/Tool 单次 timeout 与重复失败熔断不属于本值对象。</p>
 *
 * @param totalModelTurns 总模型回合硬上限；空表示调用方未提供
 * @param totalToolCalls 总 Tool Call 硬上限；空表示调用方未提供
 * @param runDeadline 单次 Run 总墙钟硬限制；空表示调用方未提供
 * @since 0.1.0
 */
public record AgentLimits(OptionalInt totalModelTurns,
                          OptionalInt totalToolCalls,
                          Optional<Duration> runDeadline) {
    /** 保守的兼容显式限制。 */
    public static final AgentLimits DEFAULT = new AgentLimits(16, 32, Duration.ofMinutes(5));

    /**
     * 使用默认五分钟创建显式硬预算。
     *
     * @param maxModelTurns 模型回合硬上限
     * @param maxToolCalls Tool Call 硬上限
     */
    public AgentLimits(int maxModelTurns, int maxToolCalls) {
        this(maxModelTurns, maxToolCalls, DEFAULT.runDeadline().orElseThrow());
    }

    /**
     * 创建三个维度均存在的显式硬预算。
     *
     * @param maxModelTurns 模型回合硬上限
     * @param maxToolCalls Tool Call 硬上限
     * @param maxDuration Run 最大墙钟时间
     */
    public AgentLimits(int maxModelTurns, int maxToolCalls, Duration maxDuration) {
        this(OptionalInt.of(maxModelTurns), OptionalInt.of(maxToolCalls), Optional.of(maxDuration));
    }

    /**
     * 为普通 Interactive、Plan 与 approved-plan 创建无隐式总量预算。
     *
     * @return 三个总量维度均 absent 的限制对象
     */
    public static AgentLimits interactive() {
        return new AgentLimits(OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
    }

    /** 校验所有 present 的硬边界。 */
    public AgentLimits {
        totalModelTurns = Objects.requireNonNull(totalModelTurns, "totalModelTurns 不能为空");
        totalToolCalls = Objects.requireNonNull(totalToolCalls, "totalToolCalls 不能为空");
        runDeadline = Objects.requireNonNull(runDeadline, "runDeadline 不能为空");
        if (totalModelTurns.isPresent() && totalModelTurns.getAsInt() < 1) {
            throw new IllegalArgumentException("totalModelTurns 必须大于 0");
        }
        if (totalToolCalls.isPresent() && totalToolCalls.getAsInt() < 0) {
            throw new IllegalArgumentException("totalToolCalls 不能小于 0");
        }
        runDeadline.ifPresent(duration -> {
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("runDeadline 必须大于 0");
            }
        });
    }
}
