package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryTier;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 保存单个 Agent Run 或一次显式操作内 C3/C4 摘要尝试的并发安全冷却状态。
 *
 * <p>公开构造器绑定唯一真实 Run ID；{@link #operationLocal()} 则创建无 Run 身份的局部模式。
 * 两种入口严格隔离，只保留所属生命周期的 source revision/tier 组合。
 * {@link #close()} 与 {@link #tryAcquire(RunId, long, SummaryTier)} 使用同一把锁，因此
 * close 和 acquire 竞态只会产生“占用在线性化点前完成”或“关闭后拒绝”两种结果；关闭
 * 完成后不会残留跨 Run Key，也不会接受新的摘要尝试。</p>
 *
 * <p>Owner 必须在 Run 终止时关闭本对象。关闭不会撤销已经发起的外部摘要调用；候选只有经
 * {@link #commitIfOpen(Supplier)} 在同一生命周期锁内提交才可发布。提交先获得锁时，采用在
 * close 前线性化；close 先获得锁时，提交返回空且候选必须丢弃。</p>
 *
 * @since 0.7.0
 */
public final class SummaryAttemptGuard implements AutoCloseable {

    private final Object lifecycleLock = new Object();
    private final Optional<RunId> runId;
    private final Set<Key> attempts = new HashSet<>();
    private boolean closed;

    /**
     * 创建绑定单个 Run 的空尝试记录。
     *
     * @param runId 唯一所有者 Run
     */
    public SummaryAttemptGuard(RunId runId) {
        this.runId = Optional.of(Objects.requireNonNull(runId, "runId 不能为空"));
    }

    private SummaryAttemptGuard() {
        this.runId = Optional.empty();
    }

    /**
     * 创建一次显式操作独占的 Guard；它不是 Run，不能接受任何 Run ID。
     *
     * @return 每次操作必须新建并在结束时关闭的局部 Guard
     */
    public static SummaryAttemptGuard operationLocal() {
        return new SummaryAttemptGuard();
    }

    /**
     * 校验真实 Run 所有权及开放状态，即使本次 reduction 无需摘要也不能跳过。
     *
     * @param requestedRunId 必须与构造时绑定的真实 Run 一致
     */
    public void checkRun(RunId requestedRunId) {
        Objects.requireNonNull(requestedRunId, "requestedRunId 不能为空");
        if (runId.isEmpty() || !runId.orElseThrow().equals(requestedRunId)) {
            throw new IllegalArgumentException("摘要尝试不属于当前 Guard 绑定的 Run");
        }
        synchronized (lifecycleLock) {
            ensureOpen();
        }
    }

    /** 校验操作局部模式及开放状态；普通 Run Guard 不能借此省略身份校验。 */
    public void checkOperationLocal() {
        if (runId.isPresent()) {
            throw new IllegalStateException("Run Guard 不能用作操作局部 Guard");
        }
        synchronized (lifecycleLock) {
            ensureOpen();
        }
    }

    /**
     * 不带 Run ID 地占用当前显式操作的一次摘要尝试。
     *
     * @param sourceRevision 当前规范历史 revision
     * @param tier 摘要层级
     * @return 当前操作中该 revision/tier 首次占用时为 true
     * @throws IllegalStateException 非局部模式或已关闭时
     */
    public boolean tryAcquire(long sourceRevision, SummaryTier tier) {
        checkOperationLocal();
        return acquire(sourceRevision, tier);
    }

    /**
     * 原子占用一次摘要尝试。
     *
     * @param requestedRunId 必须与构造时绑定的 Run 一致
     * @param sourceRevision 当前规范历史 revision
     * @param tier 摘要层级
     * @return 该 revision/tier 首次占用时为 {@code true}
     * @throws IllegalArgumentException Run 不匹配或 revision 为负数时
     * @throws IllegalStateException Guard 已关闭时
     */
    public boolean tryAcquire(
            RunId requestedRunId,
            long sourceRevision,
            SummaryTier tier) {
        checkRun(requestedRunId);
        return acquire(sourceRevision, tier);
    }

    private boolean acquire(long sourceRevision, SummaryTier tier) {
        Objects.requireNonNull(tier, "tier 不能为空");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision 不能为负数");
        }
        synchronized (lifecycleLock) {
            ensureOpen();
            return attempts.add(new Key(sourceRevision, tier));
        }
    }

    /**
     * 在与 {@link #close()} 相同的生命周期锁内提交最终采用决策。
     *
     * <p>返回非空值表示采用在线性化点上先于 close 完成；返回空表示 close 已先完成，
     * 此时 {@code adoption} 不会被调用。回调必须只构造内存终态，不能执行 Provider、文件或
     * 其他外部副作用，也不能重入本 Guard。</p>
     *
     * @param adoption 仅在 Run 仍打开时执行的终态构造器
     * @param <T> 采用终态类型
     * @return 已采用终态，或 close 胜出时的空值
     */
    public <T> Optional<T> commitIfOpen(Supplier<T> adoption) {
        Objects.requireNonNull(adoption, "adoption 不能为空");
        synchronized (lifecycleLock) {
            if (closed) {
                return Optional.empty();
            }
            return Optional.of(Objects.requireNonNull(
                    adoption.get(), "adoption result 不能为空"));
        }
    }

    /**
     * 关闭 Run 级冷却状态并清除全部 revision/tier Key。
     *
     * <p>该操作幂等；返回后任何 acquire 和 adoption commit 都会 fail-closed。</p>
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            attempts.clear();
        }
    }

    /** 返回测试和同包生命周期诊断使用的当前保留 Key 数。 */
    int retainedAttemptCount() {
        synchronized (lifecycleLock) {
            return attempts.size();
        }
    }

    /** 返回当前 Run 的 Guard 是否仍接受候选提交。 */
    boolean isOpen() {
        synchronized (lifecycleLock) {
            return !closed;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SummaryAttemptGuard 已关闭");
        }
    }

    private record Key(long sourceRevision, SummaryTier tier) {
    }
}
