package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CANCELLED;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.INCOMPLETE_STREAM;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRYABLE;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRY_EXHAUSTED;

/**
 * 为单个模型回合提供可取消、受 deadline 约束且禁止重复 Delta 的有界重试。
 *
 * <p>只有当前 attempt 尚未发布文本，且 Adapter 明确分类为 {@link
 * ModelGatewayException.FailureKind#RETRYABLE} 时才会重试。Adapter 还必须把收到任意 Provider frame、
 * Tool intent 或不确定完成状态后的失败归一为 {@code INCOMPLETE_STREAM}；本类的文本 fence 是第二道保护。
 * attempt budget 包含首次请求，所有等待都受同一 {@link CancellationToken} 的剩余 Run 预算约束。</p>
 *
 * @since 0.1.0
 */
public final class RetryingModelGateway implements StreamingModelGateway {

    private final ModelGateway delegate;
    private final ModelRetryPolicy policy;
    private final ModelRetryRuntime runtime;

    /**
     * 使用生产随机与可取消等待实现创建重试装饰器。
     * @param delegate 非空的实际 Provider Gateway
     * @param policy 包含首次请求的有界尝试与退避策略
     */
    public RetryingModelGateway(ModelGateway delegate, ModelRetryPolicy policy) {
        this(delegate, policy, ModelRetryRuntime.system());
    }

    /**
     * 使用显式运行时 seam 创建重试装饰器。
     *
     * @param delegate 实际 Provider Gateway
     * @param policy 有界 attempt/退避策略
     * @param runtime random 与可取消等待实现
     */
    public RetryingModelGateway(
            ModelGateway delegate,
            ModelRetryPolicy policy,
            ModelRetryRuntime runtime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
    }

    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelStreamObserver observer,
            CancellationToken cancellation) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(observer, "observer 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");

        ModelGatewayException lastFailure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            throwIfCancelledOrExpired(cancellation);
            notifyAttemptStarted(observer, attempt);
            AtomicBoolean emittedText = new AtomicBoolean();
            try (ModelDiagnosticAttempt.Scope ignored = ModelDiagnosticAttempt.open(attempt)) {
                if (delegate instanceof StreamingModelGateway streaming) {
                    return streaming.complete(
                            request,
                            delta -> {
                                emittedText.set(true);
                                observer.onTextDelta(delta);
                            },
                            cancellation);
                }
                return delegate.complete(request);
            } catch (ModelGatewayException failure) {
                if (cancellation.isCancellationRequested() || failure.kind() == CANCELLED) {
                    throw cancelled(failure);
                }
                if (emittedText.get()) {
                    ModelFailureSummary summary = failure.summary()
                            .map(ModelFailureSummary::withReceivedOutput)
                            .orElseGet(() -> ModelFailureSummary.firstAttempt(
                                    ModelFailureCategory.INCOMPLETE_STREAM,
                                    java.util.Optional.empty(),
                                    true));
                    throw new ModelGatewayException(
                            INCOMPLETE_STREAM,
                            "Model stream failed after publishing output",
                            summary.withAttempts(attempt),
                            failure);
                }
                if (failure.kind() != RETRYABLE) {
                    throw withAttempt(failure, attempt);
                }
                lastFailure = withAttempt(failure, attempt);
                if (attempt == policy.maxAttempts()) {
                    throw exhausted(lastFailure, attempt);
                }
                Duration delay = retryDelay(failure, attempt);
                ensureDelayFitsDeadline(delay, cancellation);
                notifyRetryScheduled(observer, failure, attempt, delay);
                runtime.await(delay, cancellation);
                throwIfCancelledOrExpired(cancellation);
            }
        }
        throw exhausted(lastFailure, policy.maxAttempts());
    }

    private Duration retryDelay(ModelGatewayException failure, int attempt) {
        Duration policyDelay = policy.delayAfter(attempt, runtime.nextRandom());
        Duration providerDelay = failure.retryAfter().orElse(Duration.ZERO);
        Duration selected = providerDelay.compareTo(policyDelay) > 0 ? providerDelay : policyDelay;
        return selected.compareTo(policy.maxRetryDelay()) > 0 ? policy.maxRetryDelay() : selected;
    }

    private void ensureDelayFitsDeadline(Duration delay, CancellationToken cancellation)
            throws ModelGatewayException {
        Duration remaining = cancellation.remainingTime().orElse(null);
        if (remaining != null
                && (remaining.isZero() || remaining.isNegative() || delay.compareTo(remaining) >= 0)) {
            throw deadline(null);
        }
    }

    private void notifyAttemptStarted(ModelStreamObserver observer, int attempt) {
        try {
            observer.onAttemptStarted(attempt, policy.maxAttempts());
        } catch (RuntimeException ignored) {
            // Retry progress 是观察旁路，不能改变模型请求。
        }
    }

    private void notifyRetryScheduled(
            ModelStreamObserver observer,
            ModelGatewayException failure,
            int failedAttempt,
            Duration delay) {
        ModelFailureCategory category = failure.summary()
                .map(ModelFailureSummary::category)
                .orElse(ModelFailureCategory.PROVIDER_ERROR);
        try {
            observer.onRetryScheduled(
                    failedAttempt,
                    failedAttempt + 1,
                    policy.maxAttempts(),
                    delay,
                    category);
        } catch (RuntimeException ignored) {
            // Retry progress 是观察旁路，不能改变模型请求。
        }
    }

    private static ModelGatewayException withAttempt(ModelGatewayException failure, int attempt) {
        if (failure.summary().isEmpty()) {
            return failure;
        }
        ModelFailureSummary summary = failure.summary().orElseThrow().withAttempts(attempt);
        if (failure.retryAfter().isPresent()) {
            return new ModelGatewayException(
                    failure.kind(),
                    failure.getMessage(),
                    summary,
                    failure.retryAfter().orElseThrow(),
                    failure.getCause());
        }
        return new ModelGatewayException(
                failure.kind(),
                failure.getMessage(),
                summary,
                failure.getCause());
    }

    private static ModelGatewayException exhausted(ModelGatewayException lastFailure, int attempts) {
        ModelFailureSummary summary = lastFailure == null
                ? ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.PROVIDER_ERROR,
                        java.util.Optional.empty(),
                        false)
                : lastFailure.summary().orElseGet(() -> ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.PROVIDER_ERROR,
                        java.util.Optional.empty(),
                        false));
        return new ModelGatewayException(
                RETRY_EXHAUSTED,
                "Model retry attempts exhausted",
                summary.withAttempts(attempts),
                lastFailure);
    }

    private static void throwIfCancelledOrExpired(CancellationToken cancellation)
            throws ModelGatewayException {
        if (cancellation.isCancellationRequested()) {
            throw cancelled(null);
        }
        Duration remaining = cancellation.remainingTime().orElse(null);
        if (remaining != null && (remaining.isZero() || remaining.isNegative())) {
            throw deadline(null);
        }
    }

    private static ModelGatewayException cancelled(Throwable cause) {
        return new ModelGatewayException(CANCELLED, "Model request cancelled", cause);
    }

    private static ModelGatewayException deadline(Throwable cause) {
        return new ModelGatewayException(CANCELLED, "Model request deadline reached", cause);
    }

}
