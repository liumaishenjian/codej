package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStatus;
import io.github.liumaishenjian.ccjava.domain.ContextUsageReasonCode;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 ContextAssembler 生成的 Canonical ModelRequest 构建单回合短生命周期 Projection。
 *
 * <p>默认实例是 no-op，保持 S01-S06 构造器行为。启用实例先执行 C1/C2；仍超容量时条件式
 * 执行 C3/C4。服务不修改 Session、Journal 或输入请求，并在 Run 结束时关闭冷却状态。</p>
 *
 * @since 0.7.0
 */
public final class ContextPreparationService {

    private final ContextPreparationConfig config;
    private final ContextSummarizer summarizer;
    private final ContextUsageObserver usageObserver;
    private final boolean enabled;
    private final AtomicReference<InstalledProjection> installedProjection;
    private final ConcurrentMap<RunId, RunState> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<io.github.liumaishenjian.ccjava.domain.SessionId, String> pendingExternalContext;

    /**
     * 创建启用 C1-C4 的准备服务，默认不发布 Context Usage View。
     *
     * @param config 已校验的容量、保护尾部和 reduction 限制
     * @param summarizer C3/C4 只返回候选数据的摘要 Port
     */
    public ContextPreparationService(
            ContextPreparationConfig config,
            ContextSummarizer summarizer) {
        this(config, summarizer, ContextUsageObserver.noop());
    }

    /**
     * 创建启用 C1-C4 与旁路 Context Usage 观察的准备服务。
     *
     * <p>观察者仅接收数值化 View；其异常由本服务隔离，不能改变 Runtime 的请求、恢复或终态。</p>
     *
     * @param config 已校验的容量与 reduction 配置
     * @param summarizer C3/C4 摘要 Port
     * @param usageObserver 非权威的 Usage View 观察端口
     */
    public ContextPreparationService(
            ContextPreparationConfig config,
            ContextSummarizer summarizer,
            ContextUsageObserver usageObserver) {
        this(config, summarizer, usageObserver, new AtomicReference<>(), new ConcurrentHashMap<>());
    }

    private ContextPreparationService(
            ContextPreparationConfig config,
            ContextSummarizer summarizer,
            ContextUsageObserver usageObserver,
            AtomicReference<InstalledProjection> installedProjection,
            ConcurrentMap<io.github.liumaishenjian.ccjava.domain.SessionId, String> pendingExternalContext) {
        this.installedProjection = Objects.requireNonNull(installedProjection, "installedProjection 不能为空");
        this.pendingExternalContext = Objects.requireNonNull(pendingExternalContext, "pendingExternalContext 不能为空");
        this.config = Objects.requireNonNull(config, "config 不能为空");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不能为空");
        this.usageObserver = Objects.requireNonNull(usageObserver, "usageObserver 不能为空");
        this.enabled = true;
    }

    private ContextPreparationService() {
        this(new AtomicReference<>(), new ConcurrentHashMap<>());
    }

    private ContextPreparationService(
            AtomicReference<InstalledProjection> installedProjection,
            ConcurrentMap<io.github.liumaishenjian.ccjava.domain.SessionId, String> pendingExternalContext) {
        this.installedProjection = installedProjection;
        this.pendingExternalContext = pendingExternalContext;
        this.config = null;
        this.summarizer = null;
        this.usageObserver = ContextUsageObserver.noop();
        this.enabled = false;
    }

    /**
     * 返回保持原 ModelRequest 不变且不保留 Run 状态的兼容路径。
     *
     * @return 不调用 reduction、摘要或 Usage 观察端口的 no-op 服务
     */
    public static ContextPreparationService noop() {
        return new ContextPreparationService();
    }

    /**
     * 将 Root 摘要端口显式绑定到当前模型作用域，保留容量及 Root 观察者。
     *
     * @param bound 本作用域的摘要端口
     * @return 共享待消费上下文容器但拥有独立 Run 状态的新视图
     */
    public ContextPreparationService withSummarizer(ContextSummarizer bound) {
        return withSummarizer(bound, Optional.empty(), OptionalLong.empty());
    }

    /**
     * 重绑 Root 模型摘要；共享已安装 compact 和晚到外部上下文的容器，不复制快照。
     *
     * <p>新视图独占 Run 冷却和恢复状态，closeRun 不影响其他视图。声明窗口只能收窄，
     * 输出保留和安全余量保持不变，剩余输入不足时拒绝创建；不会暗中扩大预算。</p>
     *
     * @param bound 本作用域的摘要端口
     * @param modelId 可选目标模型标识
     * @param declaredContextWindow 可选模型声明窗口
     * @return 新 Root 视图；用户明确关闭 Context 时仍为 no-op
     * @throws IllegalArgumentException 窗口非法或不足以容纳保留空间时
     */
    public ContextPreparationService withSummarizer(
            ContextSummarizer bound, Optional<String> modelId, OptionalLong declaredContextWindow) {
        Objects.requireNonNull(bound, "bound 不能为空");
        Objects.requireNonNull(modelId, "modelId 不能为空");
        Objects.requireNonNull(declaredContextWindow, "declaredContextWindow 不能为空");
        if (!enabled) return new ContextPreparationService(installedProjection, pendingExternalContext);
        return new ContextPreparationService(narrowConfig(modelId, declaredContextWindow), bound,
                usageObserver, installedProjection, pendingExternalContext);
    }

    /**
     * 创建独立子 Context，继承容量策略而不继承任何父状态或父 Usage 观察者。
     *
     * @param bound 子模型摘要端口
     * @return 全部状态独立的子服务
     */
    public ContextPreparationService forkForChild(ContextSummarizer bound) {
        return forkForChild(bound, Optional.empty(), OptionalLong.empty());
    }

    /**
     * 按目标模型收窄子 Context；默认关闭子 Usage 旁路，避免污染父观察者。
     *
     * @param bound 子模型摘要端口
     * @param modelId 可选目标模型标识
     * @param declaredContextWindow 可选声明窗口，只能收窄
     * @return 全部状态独立的子服务；父级明确 no-op 时保持 no-op
     */
    public ContextPreparationService forkForChild(
            ContextSummarizer bound, Optional<String> modelId, OptionalLong declaredContextWindow) {
        return forkForChild(bound, modelId, declaredContextWindow, ContextUsageObserver.noop());
    }

    /**
     * 创建具有显式子观察者的独立 Context，不共享父 compact、外部上下文或 Run 状态。
     *
     * @param bound 子模型摘要端口
     * @param modelId 可选目标模型标识
     * @param declaredContextWindow 可选声明窗口，只能收窄且不削减保留空间
     * @param childObserver 只接收该子服务 Usage 的旁路观察者
     * @return 子服务；父级明确 no-op 时保持 no-op
     * @throws IllegalArgumentException 窗口非法或保留空间耗尽输入时
     */
    public ContextPreparationService forkForChild(
            ContextSummarizer bound, Optional<String> modelId, OptionalLong declaredContextWindow,
            ContextUsageObserver childObserver) {
        Objects.requireNonNull(bound, "bound 不能为空");
        Objects.requireNonNull(modelId, "modelId 不能为空");
        Objects.requireNonNull(declaredContextWindow, "declaredContextWindow 不能为空");
        Objects.requireNonNull(childObserver, "childObserver 不能为空");
        if (!enabled) return noop();
        return new ContextPreparationService(narrowConfig(modelId, declaredContextWindow), bound, childObserver);
    }

    private ContextPreparationConfig narrowConfig(Optional<String> modelId, OptionalLong declaredContextWindow) {
        ContextCapacity current = config.capacity();
        long maximum = declaredContextWindow.isPresent()
                ? Math.min(current.maximumInputTokens(), declaredContextWindow.getAsLong())
                : current.maximumInputTokens();
        // current 已通过容量不变量校验；仅分类正窗口收窄耗尽保留预算，不改写非法窗口语义。
        if (declaredContextWindow.isPresent() && maximum > 0
                && current.reservedOutputTokens() + current.safetyMarginTokens() >= maximum) {
            throw new ModelContextBudgetException();
        }
        ContextCapacity narrowed = new ContextCapacity(modelId.orElse(current.modelId()), maximum,
                current.reservedOutputTokens(), current.safetyMarginTokens());
        return new ContextPreparationConfig(narrowed, config.largePayloadTokenThreshold(),
                config.protectedMessageCount(), config.maxSummaryUtf8Bytes(), config.maxSummaryTokens());
    }

    /**
     * 兼容旧调用方，仅提取消息并委托无 Run 身份的显式 compact。
     *
     * @param canonical 已组装的规范请求，不使用其 Run 或 Session 身份
     * @param protectedAnchors 摘要 Gate 保护的锚点
     * @param cancellationToken 协作式取消边界
     * @return 类型化候选终态
     */
    public ExplicitCompactResult compact(
            ModelRequest canonical, List<String> protectedAnchors, CancellationToken cancellationToken) {
        return compact(Objects.requireNonNull(canonical, "canonical 不能为空").messages(),
                protectedAnchors, cancellationToken);
    }

    /**
     * 不执行普通 Agent 模型回合，仅通过摘要端口构造显式 compact 的短生命周期候选。
     * <p>摘要端口可能请求模型，因此本操作不是无网络保证；预算、取消和来源绑定仍由宿主管理。</p>
     *
     * <p>每次调用先无条件执行确定性 C1/C2；显式请求即使 C1/C2 已满足预算，仍可在既有 Gate
     * 下尝试 C3/C4。调用方只可在 idle 生命周期边界将已采用候选安装给下一 Run 的首个模型请求；
     * 本方法每次创建独立 Guard，故不会把冷却或 revision 状态泄漏到普通 Run，也不会修改 Canonical
     * 或 durable Session。</p>
     *
     * @param canonical Canonical 消息快照；此操作没有 Run 身份
     * @param protectedAnchors 已校验、仅供摘要 Gate 保护的锚点
     * @param cancellationToken 协作式取消边界
     * @return 不含正文的类型化候选终态
     */
    public ExplicitCompactResult compact(
            List<AgentMessage> canonical,
            List<String> protectedAnchors,
            CancellationToken cancellationToken) {
        canonical = List.copyOf(Objects.requireNonNull(canonical, "canonical 不能为空"));
        protectedAnchors = List.copyOf(Objects.requireNonNull(protectedAnchors, "protectedAnchors 不能为空"));
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (!enabled) return new ExplicitCompactResult(ExplicitCompactStatus.UNAVAILABLE, java.util.Optional.empty());
        if (cancellationToken.isCancellationRequested()) {
            return new ExplicitCompactResult(ExplicitCompactStatus.CANCELLED, java.util.Optional.empty());
        }
        int protectedCount = Math.min(config.protectedMessageCount(), canonical.size());
        long revision = canonical.size();
        ProjectionRequest request = new ProjectionRequest(
                canonical, config.capacity(), revision, protectedCount, true);
        ContextTokenEstimator estimator = new CodePointContextTokenEstimator();
        DeterministicContextReducer reducer = new DeterministicContextReducer(
                estimator, config.largePayloadTokenThreshold());
        ContextReductionOutcome reduced = reducer.reduce(request, cancellationToken);
        if (reduced.status() == ContextReductionStatus.CANCELLED || cancellationToken.isCancellationRequested()) {
            return new ExplicitCompactResult(ExplicitCompactStatus.CANCELLED, java.util.Optional.empty());
        }
        if (reduced.status() == ContextReductionStatus.REDUCED) {
            return new ExplicitCompactResult(ExplicitCompactStatus.ADOPTED, java.util.Optional.of(reduced.projection()));
        }
        if (reduced.status() != ContextReductionStatus.UNCHANGED
                && reduced.status() != ContextReductionStatus.CONTEXT_LIMIT_REACHED) {
            return new ExplicitCompactResult(ExplicitCompactStatus.REJECTED, java.util.Optional.empty());
        }
        SummaryAttemptGuard guard = SummaryAttemptGuard.operationLocal();
        try (SummaryReductionCoordinator coordinator = new SummaryReductionCoordinator(summarizer, estimator, guard)) {
            SummaryReductionPolicy policy = new SummaryReductionPolicy(
                    rollingEnd(reduced.projection(), protectedCount), true, protectedAnchors, protectedAnchors,
                    config.maxSummaryUtf8Bytes(), config.maxSummaryTokens());
            SummaryOutcome outcome = coordinator.reduceExplicitly(
                    request, reduced.projection(), policy, cancellationToken);
            if (cancellationToken.isCancellationRequested() || outcome.status() == SummaryOutcome.Status.CANCELLED) {
                return new ExplicitCompactResult(ExplicitCompactStatus.CANCELLED, java.util.Optional.empty());
            }
            return outcome.status() == SummaryOutcome.Status.ADOPTED
                    ? new ExplicitCompactResult(ExplicitCompactStatus.ADOPTED, java.util.Optional.of(outcome.projection()))
                    : new ExplicitCompactResult(ExplicitCompactStatus.SUMMARIZER_REJECTED, java.util.Optional.empty());
        } catch (RuntimeException failure) {
            return new ExplicitCompactResult(ExplicitCompactStatus.SUMMARIZER_FAILURE, java.util.Optional.empty());
        }
    }

    /** 显式 compact 的固定终态，绝不携带摘要、Prompt 或异常文本。 */
    public enum ExplicitCompactStatus {
        /** 候选 Projection 已通过 Gate。 */
        ADOPTED,
        /** 调用在候选提交前已取消。 */
        CANCELLED,
        /** 当前运行时未装配 Context Projection。 */
        UNAVAILABLE,
        /** 请求不满足显式 compact 的前置条件。 */
        REJECTED,
        /** 摘要候选未通过既有 S07 Gate。 */
        SUMMARIZER_REJECTED,
        /** 摘要器发生内部失败，细节不向命令结果暴露。 */
        SUMMARIZER_FAILURE
    }

    /**
     * 显式 compact 的不可变结果；仅 {@link ExplicitCompactStatus#ADOPTED} 可以携带候选 Projection。
     *
     * @param status 不暴露摘要正文或内部异常的固定终态
     * @param projection 已通过 Gate 的短生命周期候选；其他终态为空
     */
    public record ExplicitCompactResult(ExplicitCompactStatus status, java.util.Optional<ContextProjection> projection) {
        /** 确保终态与候选的存在性严格一致。 */
        public ExplicitCompactResult {
            status = Objects.requireNonNull(status, "status 不能为空");
            projection = Objects.requireNonNull(projection, "projection 不能为空");
            if ((status == ExplicitCompactStatus.ADOPTED) != projection.isPresent()) {
                throw new IllegalArgumentException("compact status 与 projection 不一致");
            }
        }
    }

    /**
     * 为一个模型回合准备 Projection，并保持 Session/Run/turn/Tool 定义原样。
     *
     * @param canonical ContextAssembler 构造的不可变规范请求
     * @param cancellationToken 本回合取消令牌
     * @return 仅供当前模型回合使用的请求；no-op 时返回原请求
     */
    public ModelRequest prepare(
            ModelRequest canonical,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(canonical, "canonical 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        canonical = projectExternalContext(canonical);
        if (!enabled) {
            return canonical;
        }
        RunState state = runs.computeIfAbsent(canonical.runId(), this::newRunState);
        int protectedCount = Math.min(
                config.protectedMessageCount(), canonical.messages().size());
        ModelRequest installed = consumeInstalled(canonical);
        if (installed != null) {
            ContextProjection projection = new ContextProjection(installed.messages(),
                    state.estimator().estimate(installed.messages(), config.capacity()),
                    List.of(), canonical.messages().size());
            ProjectionRequest recoveryRequest = new ProjectionRequest(
                    canonical.messages(), recoveryCapacity(projection), canonical.messages().size(), protectedCount, true);
            ContextProjection recoveryProjection = new ContextProjection(
                    projection.messages(), state.estimator().estimate(projection.messages(), recoveryRequest.capacity()),
                    projection.appliedReductions(), projection.sourceRevision());
            state.prepared().set(new PreparedContext(
                    recoveryRequest, recoveryProjection, policyFor(recoveryProjection, protectedCount)));
            publish(ContextUsageView.prepared(projection, config.capacity()));
            return installed;
        }
        long revision = canonical.messages().size();
        ProjectionRequest request = new ProjectionRequest(
                canonical.messages(), config.capacity(), revision, protectedCount, true);
        ContextReductionOutcome reduced = state.reducer().reduce(request, cancellationToken);
        ContextProjection projection = reduced.projection();
        if (reduced.status() == ContextReductionStatus.CONTEXT_LIMIT_REACHED
                && !cancellationToken.isCancellationRequested()) {
            SummaryReductionPolicy policy = new SummaryReductionPolicy(
                    rollingEnd(projection, protectedCount),
                    true,
                    List.of(),
                    List.of(),
                    config.maxSummaryUtf8Bytes(),
                    config.maxSummaryTokens());
            SummaryOutcome summary = state.summary().reduce(
                    canonical.runId(), request, projection, policy, cancellationToken);
            projection = summary.projection();
        }
        ModelRequest prepared = new ModelRequest(
                canonical.sessionId(),
                canonical.runId(),
                canonical.turnNumber(),
                projection.messages(),
                canonical.toolDefinitions());
        ContextCapacity recoveryCapacity = recoveryCapacity(projection);
        ProjectionRequest recoveryRequest = new ProjectionRequest(
                canonical.messages(), recoveryCapacity, revision, protectedCount, true);
        ContextProjection recoveryProjection = new ContextProjection(
                projection.messages(),
                state.estimator().estimate(projection.messages(), recoveryCapacity),
                projection.appliedReductions(),
                projection.sourceRevision());
        state.prepared().set(new PreparedContext(
                recoveryRequest,
                recoveryProjection,
                policyFor(recoveryProjection, protectedCount)));
        publish(ContextUsageView.prepared(projection, config.capacity()));
        return prepared;
    }

    /**
     * 原子安装一份仅供下一 Run 消费的 Projection。
     *
     * <p>候选必须来自同一 Canonical 快照；下一次 prepare 只有在其消息前缀精确匹配时才会
     * 消费并保留新追加消息。任何来源变化都会丢弃候选，绝不重排或重复 Canonical 消息。</p>
     *
     * @param sourceCanonical 建立候选时的完整 Canonical 消息
     * @param projection 已通过 C3/C4 Gate 的候选
     */
    public void installForNextRun(List<AgentMessage> sourceCanonical, ContextProjection projection) {
        Objects.requireNonNull(sourceCanonical, "sourceCanonical 不能为空");
        Objects.requireNonNull(projection, "projection 不能为空");
        if (!enabled || projection.sourceRevision() != sourceCanonical.size()) {
            throw new IllegalArgumentException("Projection 来源 revision 不匹配");
        }
        installedProjection.set(new InstalledProjection(List.copyOf(sourceCanonical), projection));
    }

    private ModelRequest consumeInstalled(ModelRequest canonical) {
        InstalledProjection installed = installedProjection.getAndSet(null);
        if (installed == null || canonical.messages().size() < installed.sourceCanonical().size()
                || !canonical.messages().subList(0, installed.sourceCanonical().size())
                .equals(installed.sourceCanonical())) {
            return null;
        }
        List<AgentMessage> messages = new java.util.ArrayList<>(installed.projection().messages());
        messages.addAll(canonical.messages().subList(installed.sourceCanonical().size(), canonical.messages().size()));
        return new ModelRequest(canonical.sessionId(), canonical.runId(), canonical.turnNumber(), messages,
                canonical.toolDefinitions());
    }

    /**
     * 执行首次请求，并仅在 typed context overflow 后强制摘要与重试一次。
     *
     * <p>no-op 路径直接执行一次。启用路径把既有 Coordinator 的无循环尝试结果重新映射为
     * 原始 {@link ModelGatewayException}，从而保持非 overflow 失败和第二次 overflow 的精确分类。</p>
     *
     * @param <T> 单次模型请求成功结果的数据类型
     * @param prepared 已准备、尚未发送的短生命周期请求
     * @param cancellationToken 本回合取消令牌
     * @param attempt 不自行重试的实际模型调用
     * @return 首次调用或唯一恢复调用的成功结果
     * @throws ModelGatewayException 模型调用失败、取消或第二次 overflow 时
     */
    public <T> T executePrepared(
            ModelRequest prepared,
            CancellationToken cancellationToken,
            PreparedAttempt<T> attempt) throws ModelGatewayException {
        Objects.requireNonNull(prepared, "prepared 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Objects.requireNonNull(attempt, "attempt 不能为空");
        if (!enabled) {
            return attempt.execute(prepared);
        }
        RunState state = runs.get(prepared.runId());
        PreparedContext context = state == null ? null : state.prepared().getAndSet(null);
        if (state == null || context == null) {
            return attempt.execute(prepared);
        }
        AtomicReference<ModelGatewayException> failure = new AtomicReference<>();
        AtomicBoolean attempted = new AtomicBoolean();
        ContextOverflowRetryCoordinator.Outcome<AttemptValue<T>> outcome =
                state.overflow().execute(
                        prepared.runId(),
                        context.request(),
                        context.projection(),
                        context.policy(),
                        cancellationToken,
                        projection -> {
                    attempted.set(true);
                    try {
                        ModelRequest request = new ModelRequest(
                                prepared.sessionId(), prepared.runId(), prepared.turnNumber(),
                                projection.messages(), prepared.toolDefinitions());
                        return ContextOverflowRetryCoordinator.AttemptResult.success(
                                new AttemptValue<>(attempt.execute(request)));
                    } catch (ModelGatewayException exception) {
                        failure.set(exception);
                        return exception.kind() == ModelGatewayException.FailureKind.CONTEXT_OVERFLOW
                                ? ContextOverflowRetryCoordinator.AttemptResult.overflow()
                                : ContextOverflowRetryCoordinator.AttemptResult.failed();
                    }
                });
        publishRecovery(context, outcome);
        if (outcome.result().status()
                == ContextOverflowRetryCoordinator.AttemptStatus.SUCCESS) {
            return outcome.result().value().orElseThrow().value();
        }
        if (!attempted.get()) {
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.CANCELLED,
                    "Model request cancelled");
        }
        ModelGatewayException exception = failure.get();
        if (exception != null) {
            throw exception;
        }
        throw new ModelGatewayException("Model request failed");
    }

    private <T> void publishRecovery(
            PreparedContext context,
            ContextOverflowRetryCoordinator.Outcome<T> outcome) {
        if (outcome.modelRequestAttempts() == 0) {
            return;
        }
        java.util.ArrayList<ContextUsageReasonCode> codes = new java.util.ArrayList<>();
        if (outcome.result().status() == ContextOverflowRetryCoordinator.AttemptStatus.OVERFLOW
                || outcome.summaryOutcome().isPresent()
                || outcome.modelRequestAttempts() == 2) {
            codes.add(ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW);
        }
        outcome.summaryOutcome().ifPresent(summary -> {
            if (summary.status() == SummaryOutcome.Status.ADOPTED) {
                codes.add(ContextUsageReasonCode.OVERFLOW_SUMMARY_ADOPTED);
            } else if (summary.status() == SummaryOutcome.Status.CANCELLED) {
                codes.add(ContextUsageReasonCode.OVERFLOW_RECOVERY_CANCELLED);
            } else {
                codes.add(ContextUsageReasonCode.OVERFLOW_SUMMARY_UNCHANGED);
            }
        });
        if (outcome.result().status() == ContextOverflowRetryCoordinator.AttemptStatus.CANCELLED) {
            codes.add(ContextUsageReasonCode.OVERFLOW_RECOVERY_CANCELLED);
        }
        if (codes.isEmpty()) {
            return;
        }
        publish(ContextUsageView.recovered(
                outcome.projection(), context.request().capacity(), codes,
                outcome.modelRequestAttempts()));
    }

    private void publish(ContextUsageView view) {
        try {
            usageObserver.publish(view);
        } catch (RuntimeException ignored) {
            // Usage View 是旁路诊断，观察端故障不能影响模型请求或恢复。
        }
    }

    /**
     * 记录只供指定 Session 下一模型回合消费的有界非可信外部 Context。
     *
     * <p>用于把 durable 子任务 Stop Hook 的 additional context 投影回父级；该内容不写入
     * Canonical transcript，且最多保留一个固定上限的 pending 值。</p>
     *
     * @param sessionId 接收下一回合投影的父 Session
     * @param context durable Stop Hook 返回的有界不可信文本
     */
    public void recordExternalContext(io.github.liumaishenjian.ccjava.domain.SessionId sessionId, String context) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        if (context.isBlank()) return;
        int maximum = io.github.liumaishenjian.ccjava.domain.hook.HookAggregateResult.MAX_CONTEXT_CHARACTERS;
        String bounded = context.codePointCount(0, context.length()) <= maximum
                ? context : context.substring(0, context.offsetByCodePoints(0, maximum));
        pendingExternalContext.put(sessionId, bounded);
    }

    private ModelRequest projectExternalContext(ModelRequest canonical) {
        String context = pendingExternalContext.remove(canonical.sessionId());
        if (context == null) return canonical;
        List<AgentMessage> messages = new java.util.ArrayList<>(canonical.messages());
        messages.add(new io.github.liumaishenjian.ccjava.domain.SystemMessage(
                "<sub-agent-stop-context trust=\"untrusted\">\n" + context
                        + "\n</sub-agent-stop-context>"));
        return new ModelRequest(canonical.sessionId(), canonical.runId(), canonical.turnNumber(),
                messages, canonical.toolDefinitions());
    }

    /**
     * 关闭并移除一个 Run 的所有摘要冷却和 overflow retry 状态。
     *
     * @param runId 生命周期已结束的唯一 Run
     */
    public void closeRun(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        RunState state = runs.remove(runId);
        if (state != null) {
            state.overflow().close();
        }
    }

    /** 返回测试可观察的活动 Run 数。 */
    int activeRunCount() {
        return runs.size();
    }

    private RunState newRunState(RunId runId) {
        ContextTokenEstimator estimator = new CodePointContextTokenEstimator();
        DeterministicContextReducer reducer = new DeterministicContextReducer(
                estimator, config.largePayloadTokenThreshold());
        SummaryAttemptGuard guard = new SummaryAttemptGuard(runId);
        SummaryReductionCoordinator summary = new SummaryReductionCoordinator(
                summarizer, estimator, guard);
        ContextOverflowRetryCoordinator overflow = new ContextOverflowRetryCoordinator(
                runId, summary);
        return new RunState(
                estimator, reducer, summary, overflow, new AtomicReference<>());
    }

    private ContextCapacity recoveryCapacity(ContextProjection projection) {
        long reducedBudget = Math.max(1L, projection.usage().totalTokens() - 1L);
        long reserved = Math.addExact(
                config.capacity().reservedOutputTokens(),
                config.capacity().safetyMarginTokens());
        long maximum = Math.addExact(reducedBudget, reserved);
        return new ContextCapacity(
                config.capacity().modelId(),
                maximum,
                config.capacity().reservedOutputTokens(),
                config.capacity().safetyMarginTokens());
    }

    private SummaryReductionPolicy policyFor(
            ContextProjection projection,
            int protectedCount) {
        return new SummaryReductionPolicy(
                rollingEnd(projection, protectedCount),
                true,
                List.of(),
                List.of(),
                config.maxSummaryUtf8Bytes(),
                config.maxSummaryTokens());
    }

    private int rollingEnd(ContextProjection projection, int protectedCount) {
        int protectedStart = projection.messages().size() - protectedCount;
        int first = !projection.messages().isEmpty()
                && projection.messages().getFirst()
                        instanceof io.github.liumaishenjian.ccjava.domain.SystemMessage
                ? 1 : 0;
        int available = protectedStart - first;
        return available <= 1 ? 0 : first + Math.max(1, available / 2);
    }

    /**
     * 单次 ModelRequest 尝试；实现不得自行循环。
     *
     * @param <T> 成功响应的数据类型
     */
    @FunctionalInterface
    public interface PreparedAttempt<T> {
        /**
         * 执行一次 prepared 或 recovered 请求。
         *
         * @param request 本次唯一允许发送的模型请求
         * @return 模型调用成功后的响应
         * @throws ModelGatewayException 模型端口无法完成本次调用时
         */
        T execute(ModelRequest request) throws ModelGatewayException;
    }

    private record InstalledProjection(List<AgentMessage> sourceCanonical, ContextProjection projection) {
        private InstalledProjection {
            sourceCanonical = List.copyOf(sourceCanonical);
            projection = Objects.requireNonNull(projection, "projection 不能为空");
        }
    }

    private record AttemptValue<T>(T value) {
    }

    private record PreparedContext(
            ProjectionRequest request,
            ContextProjection projection,
            SummaryReductionPolicy policy) {
    }

    private record RunState(
            ContextTokenEstimator estimator,
            DeterministicContextReducer reducer,
            SummaryReductionCoordinator summary,
            ContextOverflowRetryCoordinator overflow,
            AtomicReference<PreparedContext> prepared) {
    }
}
