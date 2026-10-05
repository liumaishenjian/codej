package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 定义单个模型回合的有界 attempt、退避、jitter 与 Provider 等待上限。
 *
 * <p>{@code maxAttempts} 包含首次请求。兼容构造器接受每次失败后的固定基准等待；
 * 生产策略使用指数基准列表并在运行时加入正 jitter。所有等待最终仍受
 * {@link CancellationToken#remainingTime()} 和取消信号约束。</p>
 *
 * @since 0.1.0
 */
public final class ModelRetryPolicy {

    /** 早期 S02 测试兼容策略：首次请求加两次短等待。 */
    public static final ModelRetryPolicy S02_DEFAULT = new ModelRetryPolicy(
            3,
            List.of(Duration.ofMillis(100), Duration.ofMillis(250)));

    /** 生产策略：初次请求失败后最多再重试十次，即最多十一 attempts。 */
    public static final ModelRetryPolicy PRODUCTION_DEFAULT = exponential(
            11,
            Duration.ofMillis(500),
            Duration.ofSeconds(32),
            0.25d,
            Duration.ofMinutes(5));

    private final int maxAttempts;
    private final List<Duration> delays;
    private final double jitterRatio;
    private final Duration maxRetryDelay;

    /**
     * 创建不带 jitter 的固定等待策略。
     *
     * @param maxAttempts 包含首次请求在内的最大尝试次数
     * @param delays 每次重试前的基准等待
     */
    public ModelRetryPolicy(int maxAttempts, List<Duration> delays) {
        this(maxAttempts, delays, 0d, Duration.ofMinutes(5));
    }

    /**
     * 创建显式策略。
     *
     * @param maxAttempts 包含首次请求在内的最大尝试次数
     * @param delays 每次重试前的基准等待
     * @param jitterRatio 加在基准等待上的最大正 jitter 比例
     * @param maxRetryDelay policy 与 Provider 建议合并后的单次等待上限
     */
    public ModelRetryPolicy(
            int maxAttempts,
            List<Duration> delays,
            double jitterRatio,
            Duration maxRetryDelay) {
        this.delays = List.copyOf(Objects.requireNonNull(delays, "delays 不能为空"));
        this.maxRetryDelay = Objects.requireNonNull(maxRetryDelay, "maxRetryDelay 不能为空");
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts 必须在 1 到 100 之间");
        }
        if (this.delays.size() != maxAttempts - 1) {
            throw new IllegalArgumentException("delays 数量必须等于 maxAttempts - 1");
        }
        if (this.delays.stream().anyMatch(delay -> delay == null || delay.isNegative())) {
            throw new IllegalArgumentException("重试等待不能为 null 或负数");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0d || jitterRatio > 1d) {
            throw new IllegalArgumentException("jitterRatio 必须在 0 到 1 之间");
        }
        if (maxRetryDelay.isNegative()) {
            throw new IllegalArgumentException("maxRetryDelay 不能为负数");
        }
        this.maxAttempts = maxAttempts;
        this.jitterRatio = jitterRatio;
    }

    /**
     * 创建逐次翻倍且有上限的基准退避策略；实际等待仍由取消预算约束。
     * @param maxAttempts 包含首次请求的尝试总上限，范围为 1 到 100
     * @param baseDelay 第一次失败后的非负基准等待
     * @param maxBackoff 翻倍序列的非负上限，不包含 jitter
     * @param jitterRatio 基准等待之上的最大正 jitter 比例，范围为 0 到 1
     * @param maxRetryDelay 合并 jitter 和 Provider 建议后的非负单次等待上限
     * @return 包含不可变基准等待序列的策略
     */
    public static ModelRetryPolicy exponential(
            int maxAttempts,
            Duration baseDelay,
            Duration maxBackoff,
            double jitterRatio,
            Duration maxRetryDelay) {
        Objects.requireNonNull(baseDelay, "baseDelay 不能为空");
        Objects.requireNonNull(maxBackoff, "maxBackoff 不能为空");
        if (baseDelay.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("退避时间不能为负数");
        }
        java.util.ArrayList<Duration> delays = new java.util.ArrayList<>(Math.max(0, maxAttempts - 1));
        Duration current = baseDelay.compareTo(maxBackoff) > 0 ? maxBackoff : baseDelay;
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            delays.add(current);
            if (current.compareTo(maxBackoff) < 0) {
                try {
                    Duration doubled = current.multipliedBy(2);
                    current = doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
                } catch (ArithmeticException overflow) {
                    current = maxBackoff;
                }
            }
        }
        return new ModelRetryPolicy(maxAttempts, delays, jitterRatio, maxRetryDelay);
    }

    /**
     * 返回单个模型回合允许发起请求的总上限。
     * @return 包含首次请求在内的最大 attempt 数
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 计算指定失败后的 policy 等待并加入有界正 jitter。
     *
     * @param failedAttempt 从 1 开始的失败 attempt
     * @param randomValue {@code [0,1)} 的确定性或生产随机值
     * @return 不超过 policy 上限的等待
     */
    public Duration delayAfter(int failedAttempt, double randomValue) {
        if (failedAttempt < 1 || failedAttempt >= maxAttempts) {
            throw new IllegalArgumentException("failedAttempt 不存在对应重试");
        }
        if (!Double.isFinite(randomValue) || randomValue < 0d || randomValue >= 1d) {
            throw new IllegalArgumentException("randomValue 必须在 [0,1) 内");
        }
        Duration base = delays.get(failedAttempt - 1);
        if (base.isZero() || jitterRatio == 0d) {
            return cap(base);
        }
        long baseNanos;
        try {
            baseNanos = base.toNanos();
        } catch (ArithmeticException overflow) {
            return maxRetryDelay;
        }
        double extraNanos = baseNanos * jitterRatio * randomValue;
        long extra = extraNanos >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) extraNanos;
        Duration jittered;
        try {
            jittered = base.plusNanos(extra);
        } catch (ArithmeticException overflow) {
            jittered = maxRetryDelay;
        }
        return cap(jittered);
    }

    /**
     * 兼容旧调用：不加入 jitter，但仍应用单次等待上限。
     * @param failedAttempt 从 1 开始且必须存在下一次重试的失败序号
     * @return 经上限裁剪的基准等待
     */
    public Duration delayAfter(int failedAttempt) {
        return delayAfter(failedAttempt, 0d);
    }

    /**
     * 返回策略允许的单次重试等待上限；不替代 Run 剩余时间限制。
     * @return 单次等待上限
     */
    public Duration maxRetryDelay() {
        return maxRetryDelay;
    }

    /**
     * 返回加在基准等待之上的正向随机扰动比例上限。
     * @return jitter 最大比例
     */
    public double jitterRatio() {
        return jitterRatio;
    }

    private Duration cap(Duration value) {
        return value.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : value;
    }
}
