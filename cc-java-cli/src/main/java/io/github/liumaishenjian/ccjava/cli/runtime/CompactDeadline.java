package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 显式 compact 的操作级单调墙钟边界；不是 HTTP timeout，也不创建虚假 Run 身份。
 * 开启、Hook、准备、摘要及提交检查共用同一 token；关闭时解除调用取消订阅并停止计时任务。
 */
final class CompactDeadline implements AutoCloseable {
    private final long started = System.nanoTime();
    private final long budgetNanos;
    private final CancellationSource source = new CancellationSource();
    private final CancellationToken parent;
    private final CancellationToken.Registration registration;
    private final ScheduledExecutorService timer;
    private final CancellationToken token = new CancellationToken() {
        @Override public boolean isCancellationRequested() {
            if (parent.isCancellationRequested() || remaining().isZero()) source.cancel();
            return source.token().isCancellationRequested();
        }
        @Override public Registration onCancellation(Runnable action) {
            isCancellationRequested();
            return source.token().onCancellation(action);
        }
        @Override public Optional<Duration> remainingTime() { return Optional.of(remaining()); }
    };

    CompactDeadline(Duration configuredTimeout, CancellationToken parent) {
        this.parent = Objects.requireNonNull(parent);
        Duration budget = budgetLimit(Objects.requireNonNull(configuredTimeout));
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("compact timeout 必须为正");
        budget = parent.remainingTime().filter(value -> value.compareTo(budgetLimit(configuredTimeout)) < 0)
                .orElse(budget);
        budgetNanos = Math.max(0, budget.toNanos());
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("compact-deadline").factory());
        CancellationToken.Registration registered = null;
        try {
            registered = parent.onCancellation(source::cancel);
            timer.schedule(() -> { source.cancel(); },
                    Math.max(0, budgetNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            registration = registered;
        } catch (RuntimeException | Error failure) {
            if (registered != null) registered.close();
            timer.shutdownNow();
            throw failure;
        }
    }

    private static Duration budgetLimit(Duration timeout) {
        return timeout.compareTo(Duration.ofSeconds(300)) < 0 ? timeout : Duration.ofSeconds(300);
    }

    private Duration remaining() {
        long left = Math.max(0, budgetNanos - (System.nanoTime() - started));
        Duration own = Duration.ofNanos(left);
        return parent.remainingTime().filter(value -> value.compareTo(own) < 0).orElse(own);
    }

    CancellationToken token() { return token; }
    void cancel() { source.cancel(); }

    @Override public void close() {
        registration.close();
        timer.shutdownNow();
    }
}
