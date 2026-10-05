package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.cli.provider.RunModelSourceRegistry;
import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.RunModelBinding;
import io.github.liumaishenjian.ccjava.core.RunScopedModelGateway;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 单个出队child的模型、Context和启动取消所有权；不调用Root认证互斥门。
 * <p>墙钟预算从打开子模型scope开始，覆盖之后的workspace装配与Runtime；排队和已有Start Hook
 * 仍遵循Supervisor原契约。只有真实RunInitializer可以登记后代来源及精确Runtime取消。</p>
 */
final class ChildModelRun implements AutoCloseable {
    private final CancellationSource cancellation;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("cc-java-child-model-deadline").factory());
    private final RunModelSourceRegistry sources;
    private final String model;
    private CancellationToken.Registration parentRegistration = () -> { };
    private CancellationToken.Registration runCancellation = () -> { };
    private RunModelSourceRegistry.Registration registration = () -> { };
    private ScheduledFuture<?> timeout;
    private RunScopedModelGateway.RunScope scope;
    private RunModelBinding binding;
    private ContextPreparationService context;
    private boolean initialized;
    private boolean closed;
    private RuntimeException cleanupFailure;
    private volatile ResourceCleanupStatus cleanupState = ResourceCleanupStatus.NOT_STARTED;

    private ChildModelRun(RunModelSourceRegistry sources, String model, Duration budget) {
        this.sources = Objects.requireNonNull(sources);
        this.model = Objects.requireNonNull(model);
        cancellation = new CancellationSource(budget);
    }

    /** 捕获已在入队前完成；这里只打开固定来源，失败先清理所有已取得的资源。 */
    static ChildModelRun open(RunModelSourceRegistry sources, RunModelSourceRegistry.Prepared prepared,
            Duration budget, CancellationToken parent) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(parent);
        ChildModelRun owned = new ChildModelRun(sources, prepared.modelName(), budget);
        try {
            owned.parentRegistration = parent.onCancellation(owned.cancellation::cancel);
            long nanos;
            try { nanos = budget.toNanos(); } catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
            owned.timeout = owned.timer.schedule(owned.cancellation::cancel, nanos, TimeUnit.NANOSECONDS);
            owned.checkActive();
            Duration remaining = owned.cancellation.token().remainingTime().orElse(budget);
            owned.scope = prepared.source().open(Optional.empty(), remaining, owned.cancellation.token());
            owned.binding = owned.scope.binding().orElseThrow(
                    () -> new IllegalStateException("Child model binding unavailable"));
            if (owned.binding.selection().isPresent()
                    && !owned.binding.selection().orElseThrow().modelId().equals(prepared.modelName())) {
                throw new IllegalStateException("Child model binding mismatch");
            }
            owned.scope.bindCancellation(owned.cancellation::cancel);
            owned.context = prepared.contextTemplate().withSummarizer(owned.binding.summarizer(),
                    Optional.of(prepared.modelName()), owned.binding.contextWindowTokens());
            owned.checkActive();
            return owned;
        } catch (RuntimeException | Error failure) {
            try { owned.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    RunModelBinding binding() { return binding; }
    ContextPreparationService context() { return context; }
    String model() { return model; }
    CancellationToken cancellation() { return cancellation.token(); }
    /** 独立清理快照，不因运行正文成功或close正常返回推定租约排空。 */
    ResourceCleanupStatus cleanupStatus() { return cleanupState; }

    /** 在返回scope前也复查，以拒绝打开/装配期间取消后的迟到成功。 */
    void checkActive() {
        if (cancellation.token().remainingTime().map(value -> value.isZero() || value.isNegative()).orElse(false)) {
            cancellation.cancel();
        }
        if (cancellation.token().isCancellationRequested()) throw new IllegalStateException("Child model cancelled");
    }

    /** 只由真实RunInitializer调用；不猜RunId，也不绑定整个Session取消。 */
    synchronized void initialize(AgentRuntime runtime, SessionId sessionId, RunId runId) {
        if (closed || initialized) throw new IllegalStateException("Child model initialization unavailable");
        initialized = true;
        registration = sources.register(sessionId, runId, binding, context, Optional.of(model));
        runCancellation = cancellation.token().onCancellation(() -> runtime.cancel(sessionId, runId));
    }

    /** 幂等收回来源登记、取消回调、模型scope与调度器；失败保持sticky，不称为已释放。 */
    @Override public synchronized void close() {
        if (closed) {
            if (cleanupFailure != null) throw cleanupFailure;
            return;
        }
        closed = true;
        cleanupState = ResourceCleanupStatus.CLEANING;
        if (timeout != null) timeout.cancel(false);
        boolean failed = false;
        for (AutoCloseable resource : new AutoCloseable[] {registration, runCancellation, parentRegistration,
                scope == null ? () -> { } : scope}) {
            try { resource.close(); } catch (Exception failure) { failed = true; }
        }
        timer.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            if (!timer.awaitTermination(1, TimeUnit.SECONDS)) failed = true;
        } catch (InterruptedException interruption) {
            interrupted = true;
            failed = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        ResourceCleanupStatus modelState = ResourceCleanupStatus.UNKNOWN;
        try { if (scope != null) modelState = Objects.requireNonNull(scope.cleanupStatus()); }
        catch (RuntimeException invalidReceipt) { failed = true; }
        cleanupState = !failed && modelState == ResourceCleanupStatus.RELEASED
                ? ResourceCleanupStatus.RELEASED : ResourceCleanupStatus.UNCONFIRMED;
        if (failed) {
            cleanupFailure = new IllegalStateException("Child model cleanup unconfirmed");
            throw cleanupFailure;
        }
    }
}
