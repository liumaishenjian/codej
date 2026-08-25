package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 以公平有界队列管理同一父 Session 拥有的所有子 Agent。
 *
 * <p>默认 active=4、queue=32、depth=2；所有嵌套委托必须复用同一实例。Supervisor 只重新装配并
 * 调用既有 AgentRuntime，不实现第二套模型/Tool Loop。任务终态 CAS 先于摘要、通知和清理，后台不
 * 表示 detached；关闭会取消所有非终态任务并等待有界收敛。</p>
 *
 * @since 0.12.0
 */
public final class AgentSupervisor implements AutoCloseable {
    /** 同一父 Session 默认最大活动 child 数。 */
    public static final int DEFAULT_MAX_ACTIVE = 4;
    /** 同一父 Session 默认最大等待 child 数。 */
    public static final int DEFAULT_MAX_QUEUE = 32;
    /** 根任务以下默认最大委托深度。 */
    public static final int DEFAULT_MAX_DEPTH = 2;

    private final AgentDefinitionCatalog catalog;
    private final ChildRuntimeScopeFactory scopeFactory;
    private final ChildBudgetLedger ledger;
    private final AgentDefinitionNarrower narrower;
    private final ChildTaskJournal journal;
    private final ChildTaskObserver observer;
    private final ChildTaskLifecycle taskLifecycle;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor notifications;
    private final ConcurrentMap<ChildTaskId, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChildTaskId, ChildTaskHandle> recoveredTasks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.function.Consumer<String> parentContextSink;
    private final int maxDepth;

    /**
     * 使用默认 ceiling 和 no-op lifecycle 创建 Supervisor。
     *
     * @param catalog Session 冻结 definition catalog
     * @param scopeFactory child Runtime 装配端口
     * @param ledger 父预算 ledger
     */
    public AgentSupervisor(AgentDefinitionCatalog catalog, ChildRuntimeScopeFactory scopeFactory,
            ChildBudgetLedger ledger) {
        this(catalog, scopeFactory, ledger, AgentDefinitionNarrower.identity(), ChildTaskJournal.noop(),
                ChildTaskObserver.noop(), ChildTaskLifecycle.noop(), ignored -> { }, Clock.systemUTC(), DEFAULT_MAX_ACTIVE,
                DEFAULT_MAX_QUEUE, DEFAULT_MAX_DEPTH);
    }

    /**
     * 创建带显式安全边界和测试时钟的 Supervisor。
     *
     * @param catalog Session 冻结 definition catalog
     * @param scopeFactory child Runtime 装配端口
     * @param ledger 父预算 ledger
     * @param narrower 宿主可信纯收窄 seam
     * @param journal 必须成功的 task journal
     * @param observer 可失败的终态观察端
     * @param taskLifecycle start/stop Hook 协调器
     * @param clock deadline 与报告时钟
     * @param maxActive 活动 child ceiling
     * @param maxQueue 等待队列 ceiling
     * @param maxDepth 委托深度 ceiling
     */
    public AgentSupervisor(AgentDefinitionCatalog catalog, ChildRuntimeScopeFactory scopeFactory,
            ChildBudgetLedger ledger, AgentDefinitionNarrower narrower, ChildTaskJournal journal,
            ChildTaskObserver observer, ChildTaskLifecycle taskLifecycle, Clock clock, int maxActive,
            int maxQueue, int maxDepth) {
        this(catalog, scopeFactory, ledger, narrower, journal, observer, taskLifecycle, ignored -> { }, clock,
                maxActive, maxQueue, maxDepth);
    }

    /**
     * 创建可将 Stop Hook 附加上下文投影给父级下一回合的 Supervisor。
     *
     * @param catalog Session 冻结 definition catalog
     * @param scopeFactory child Runtime 装配端口
     * @param ledger 父预算 ledger
     * @param narrower 宿主可信纯收窄 seam
     * @param journal 必须成功的 task journal
     * @param observer 可失败的终态观察端
     * @param taskLifecycle start/stop Hook 协调器
     * @param parentContextSink 父下一回合一次性有界 Context sink
     * @param clock deadline 与报告时钟
     * @param maxActive 活动 child ceiling
     * @param maxQueue 等待队列 ceiling
     * @param maxDepth 委托深度 ceiling
     */
    public AgentSupervisor(AgentDefinitionCatalog catalog, ChildRuntimeScopeFactory scopeFactory,
            ChildBudgetLedger ledger, AgentDefinitionNarrower narrower, ChildTaskJournal journal,
            ChildTaskObserver observer, ChildTaskLifecycle taskLifecycle,
            java.util.function.Consumer<String> parentContextSink, Clock clock, int maxActive,
            int maxQueue, int maxDepth) {
        this.catalog = Objects.requireNonNull(catalog); this.scopeFactory = Objects.requireNonNull(scopeFactory);
        this.ledger = Objects.requireNonNull(ledger); this.narrower = Objects.requireNonNull(narrower);
        this.journal = Objects.requireNonNull(journal); this.observer = Objects.requireNonNull(observer);
        this.taskLifecycle = Objects.requireNonNull(taskLifecycle);
        this.parentContextSink = Objects.requireNonNull(parentContextSink);
        this.clock = Objects.requireNonNull(clock);
        if (maxActive < 1 || maxActive > DEFAULT_MAX_ACTIVE || maxQueue < 0 || maxQueue > DEFAULT_MAX_QUEUE
                || maxDepth < 1 || maxDepth > DEFAULT_MAX_DEPTH) throw new IllegalArgumentException("Supervisor ceiling 无效");
        this.maxDepth = maxDepth;
        this.workers = new ThreadPoolExecutor(maxActive, maxActive, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueue, true),
                Thread.ofPlatform().name("cc-java-child-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
        this.notifications = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DEFAULT_MAX_QUEUE, true),
                Thread.ofPlatform().name("cc-java-child-notify").daemon(true).factory(),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * 原子预留预算并提交一个前台或后台子任务。
     *
     * @param request 已受父级 scope ceiling 约束的委托请求
     * @param parentCancellation 父 Run 取消身份
     * @return 可 inspect/wait/cancel 的任务句柄
     * @throws RejectedExecutionException 队列、深度、定义、Hook 或预算拒绝时
     */
    public ChildTaskHandle submit(ChildTaskRequest request, CancellationToken parentCancellation) {
        Objects.requireNonNull(request); Objects.requireNonNull(parentCancellation);
        if (closed.get()) throw new RejectedExecutionException("Supervisor 已关闭");
        if (request.depth() > maxDepth) throw new RejectedExecutionException("委托深度超过上限");
        AgentDefinitionSnapshot original = catalog.find(request.definitionId())
                .orElseThrow(() -> new RejectedExecutionException("Agent definition 不存在"));
        AgentDefinitionSnapshot definition = validateNarrowing(original, narrower.narrow(original, request), request);
        ChildBudgetLedger.Reservation reservation = ledger.reserve(request.requestedBudget())
                .orElseThrow(() -> new RejectedExecutionException("父预算不足"));
        ChildTaskId id = new ChildTaskId("task-" + Long.toUnsignedString(sequence.incrementAndGet(), 36));
        Task task = new Task(id, definition, request, reservation, parentCancellation, clock.instant());
        tasks.put(id, task);
        try {
            journal.requested(id);
            workers.execute(task);
            return task;
        } catch (RuntimeException failure) {
            tasks.remove(id, task); reservation.close();
            throw new RejectedExecutionException("子任务队列已满或 journal 不可用", failure);
        }
    }

    /**
     * 查询活动或 no-replay 恢复任务。
     *
     * @param id child task identity
     * @return 匹配句柄
     */
    public java.util.Optional<ChildTaskHandle> find(ChildTaskId id) {
        Objects.requireNonNull(id);
        ChildTaskHandle active = tasks.get(id);
        return active == null ? java.util.Optional.ofNullable(recoveredTasks.get(id))
                : java.util.Optional.of(active);
    }

    /**
     * 注册 no-replay 恢复终态；重复 identity 或非终态会 Fail Closed。
     *
     * @param report durable recovery 推导的隐私安全终态
     */
    public void registerRecovered(ChildTaskReport report) {
        Objects.requireNonNull(report, "report 不能为空");
        if (!report.status().terminal()) throw new IllegalArgumentException("恢复结果必须为终态");
        if (tasks.containsKey(report.taskId())
                || recoveredTasks.putIfAbsent(report.taskId(), new RecoveredChildTaskHandle(report)) != null) {
            throw new IllegalStateException("恢复任务 identity 重复");
        }
    }

    private AgentDefinitionSnapshot validateNarrowing(AgentDefinitionSnapshot original,
            AgentDefinitionSnapshot narrowed, ChildTaskRequest request) {
        Objects.requireNonNull(narrowed, "narrower 返回 null");
        if (!original.id().equals(narrowed.id()) || !original.contentDigest().equals(narrowed.contentDigest())
                || !original.visibleTools().containsAll(narrowed.visibleTools())
                || !narrowed.visibleTools().containsAll(request.requestedTools())
                || !original.modelName().equals(narrowed.modelName())
                || !permissionWithin(narrowed.permissionCeiling(), original.permissionCeiling())
                || !within(narrowed.budget(), original.budget()) || !within(request.requestedBudget(), narrowed.budget())) {
            throw new RejectedExecutionException("Agent Hook 或委托企图放宽 scope");
        }
        return narrowed;
    }

    private static boolean permissionWithin(io.github.liumaishenjian.ccjava.domain.PermissionMode value,
            io.github.liumaishenjian.ccjava.domain.PermissionMode ceiling) {
        if (value == io.github.liumaishenjian.ccjava.domain.PermissionMode.PLAN) return true;
        return value == ceiling;
    }

    private static boolean within(ChildBudget value, ChildBudget ceiling) {
        return value.modelTurns() <= ceiling.modelTurns() && value.toolCalls() <= ceiling.toolCalls()
                && value.inputTokens() <= ceiling.inputTokens() && value.outputCharacters() <= ceiling.outputCharacters()
                && value.duration().compareTo(ceiling.duration()) <= 0;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        tasks.values().forEach(Task::cancel);
        workers.shutdownNow(); notifications.shutdown();
        try { workers.awaitTermination(5, TimeUnit.SECONDS); notifications.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private final class Task implements Runnable, ChildTaskHandle {
        private final ChildTaskId id; private final AgentDefinitionSnapshot definition;
        private final ChildTaskRequest request; private final ChildBudgetLedger.Reservation reservation;
        private final CancellationToken parent; private final CancellationSource cancellation = new CancellationSource();
        private final CancellationToken.Registration parentRegistration;
        private final Instant submitted; private final CountDownLatch terminalLatch = new CountDownLatch(1);
        private final AtomicReference<ChildTaskReport> report;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Thread runner;
        private volatile boolean started;

        private Task(ChildTaskId id, AgentDefinitionSnapshot definition, ChildTaskRequest request,
                ChildBudgetLedger.Reservation reservation, CancellationToken parent, Instant submitted) {
            this.id=id; this.definition=definition; this.request=request; this.reservation=reservation;
            this.parent=parent; this.submitted=submitted;
            report = new AtomicReference<>(report(ChildTaskStatus.QUEUED, ChildTaskFailureCode.NONE, 0, 0, "queued", false));
            parentRegistration = parent.onCancellation(this::cancel);
        }
        @Override public ChildTaskId id() { return id; }
        @Override public ChildTaskReport inspect() { return report.get(); }
        @Override public ChildTaskReport await(Duration timeout) throws InterruptedException {
            if (!terminalLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) return inspect();
            return inspect();
        }
        @Override public boolean cancel() {
            boolean first = cancellation.cancel();
            Thread current = runner; if (current != null) current.interrupt();
            if (workers.remove(this)) finish(ChildTaskStatus.CANCELLED, ChildTaskFailureCode.CANCELLED, 0, 0, "cancelled", false);
            return first;
        }
        @Override public java.util.Optional<String> keepWorktree() {
            return terminal.get() ? scopeFactory.keepWorktree(request.delegationId()) : java.util.Optional.empty();
        }
        @Override public java.util.Optional<String> removeWorktree() {
            return terminal.get() ? scopeFactory.removeWorktree(request.delegationId()) : java.util.Optional.empty();
        }
        @Override public void run() {
            runner = Thread.currentThread();
            started = true;
            if (parent.isCancellationRequested() || cancellation.token().isCancellationRequested()) {
                finish(ChildTaskStatus.CANCELLED, ChildTaskFailureCode.CANCELLED, 0, 0, "cancelled", false); return;
            }
            report.set(report(ChildTaskStatus.STARTING, ChildTaskFailureCode.NONE, 0, 0, "starting", false));
            try {
                java.util.Optional<String> startContext = taskLifecycle.beforeStart(request, cancellation.token());
                ChildTaskRequest effectiveRequest = startContext.map(context -> new ChildTaskRequest(
                        request.delegationId(), request.definitionId(), request.prompt()
                                + "\n<sub-agent-hook-context trust=\"untrusted\">\n" + context
                        + "\n</sub-agent-hook-context>",
                        request.requestedTools(), request.requestedBudget(), request.background(), request.depth(),
                        request.worktree(), request.taskScope())).orElse(request);
                journal.started(id);
                try (ChildRuntimeScope scope = scopeFactory.create(definition, effectiveRequest, cancellation.token())) {
                    report.set(report(ChildTaskStatus.RUNNING, ChildTaskFailureCode.NONE, 0, 0, "running", false));
                    AgentRunResult result = scope.runtime().run(scope.sessionId(), new AgentRunRequest(
                            new UserMessage(effectiveRequest.prompt()), new AgentLimits(request.requestedBudget().modelTurns(),
                            request.requestedBudget().toolCalls(), request.requestedBudget().duration())));
                    boolean cancelled = cancellation.token().isCancellationRequested() || parent.isCancellationRequested()
                            || result.stopReason() == StopReason.USER_CANCELLED;
                    finish(cancelled ? ChildTaskStatus.CANCELLED : result.stopReason() == StopReason.COMPLETED
                                    ? ChildTaskStatus.SUCCEEDED : ChildTaskStatus.FAILED,
                            cancelled ? ChildTaskFailureCode.CANCELLED : result.stopReason() == StopReason.COMPLETED
                                    ? ChildTaskFailureCode.NONE : ChildTaskFailureCode.RUNTIME_FAILED,
                            result.modelTurns(), result.toolCalls(), safeSummary(result), true,
                            scope.worktreeDisposition().get());
                }
            } catch (RuntimeException failure) {
                boolean blocked = failure instanceof ChildTaskStartBlockedException;
                finish(cancellation.token().isCancellationRequested() ? ChildTaskStatus.CANCELLED : ChildTaskStatus.FAILED,
                        cancellation.token().isCancellationRequested() ? ChildTaskFailureCode.CANCELLED
                                : blocked ? ChildTaskFailureCode.START_HOOK_BLOCKED : ChildTaskFailureCode.RUNTIME_FAILED,
                        0, 0, blocked ? "start_hook_blocked" : "runtime_failed", false);
            } finally { runner = null; }
        }
        private String safeSummary(AgentRunResult result) {
            // 子模型正文与 Tool 输出均属于不可信/可能敏感内容；父级只接收可验证的确定性计数。
            String fixed = result.stopReason() == StopReason.COMPLETED
                    ? "completed" : result.stopReason().name().toLowerCase(java.util.Locale.ROOT);
            return fixed + "; modelTurns=" + result.modelTurns() + "; toolCalls=" + result.toolCalls();
        }
        private ChildTaskReport report(ChildTaskStatus status, ChildTaskFailureCode code, int turns, int calls,
                String summary, boolean verified) {
            return report(status, code, turns, calls, summary, verified, java.util.Optional.empty());
        }
        private ChildTaskReport report(ChildTaskStatus status, ChildTaskFailureCode code, int turns, int calls,
                String summary, boolean verified, java.util.Optional<String> worktreeDisposition) {
            return new ChildTaskReport(id, definition.id(), status, code, turns, calls, 0,
                    Duration.between(submitted, clock.instant()), summary, verified, worktreeDisposition);
        }
        private void finish(ChildTaskStatus status, ChildTaskFailureCode code, int turns, int calls,
                String summary, boolean verified) {
            finish(status, code, turns, calls, summary, verified, java.util.Optional.empty());
        }
        private void finish(ChildTaskStatus status, ChildTaskFailureCode code, int turns, int calls,
                String summary, boolean verified, java.util.Optional<String> worktreeDisposition) {
            if (!terminal.compareAndSet(false, true)) return;
            ChildTaskReport intended = report(status, code, turns, calls, summary, verified, worktreeDisposition);
            ChildTaskReport durable = intended;
            try {
                journal.terminal(intended);
            } catch (RuntimeException persistenceFailure) {
                durable = report(ChildTaskStatus.FAILED, ChildTaskFailureCode.JOURNAL_FAILED,
                        turns, calls, "journal_failed", false, worktreeDisposition);
                try {
                    journal.terminalFailure(durable);
                } catch (RuntimeException markerFailure) {
                    persistenceFailure.addSuppressed(markerFailure);
                }
            }
            report.set(durable);
            terminalLatch.countDown();
            parentRegistration.close();
            settleReservation(turns, calls, durable.elapsed());
            ChildTaskReport observable = durable;
            try {
                taskLifecycle.afterTerminal(observable).ifPresent(parentContextSink);
            } catch (RuntimeException ignored) {
                // Stop Hook 只观察，不得改写 durable terminal；投影失败也不能影响 await。
            }
            try {
                notifications.execute(() -> {
                    try {
                        observer.onTerminal(observable);
                    } catch (RuntimeException ignored) {
                        // Observer 与 durable 状态隔离。
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // close fence 后不再接受通知；终态仍可由 inspect/wait 读取。
            }
            tasks.entrySet().removeIf(entry -> entry.getValue() != this
                    && entry.getValue().inspect().status().terminal()
                    && tasks.size() > DEFAULT_MAX_QUEUE);
        }

        private void settleReservation(int turns, int calls, Duration elapsed) {
            if (!started) {
                reservation.close();
                return;
            }
            ChildBudget limit = reservation.budget();
            long estimatedTokens = Math.min(limit.inputTokens(), Math.max(1L, turns * 256L));
            int outputCharacters = Math.min(limit.outputCharacters(), Math.max(1, summaryCharacters()));
            Duration actualDuration = elapsed.compareTo(limit.duration()) > 0 ? limit.duration()
                    : elapsed.isZero() ? Duration.ofMillis(1) : elapsed;
            reservation.settle(new ChildBudget(
                    Math.max(1, Math.min(turns, limit.modelTurns())),
                    Math.min(calls, limit.toolCalls()),
                    estimatedTokens,
                    outputCharacters,
                    actualDuration));
        }

        private int summaryCharacters() {
            String value = report.get().summary();
            return value.codePointCount(0, value.length());
        }
    }
}
