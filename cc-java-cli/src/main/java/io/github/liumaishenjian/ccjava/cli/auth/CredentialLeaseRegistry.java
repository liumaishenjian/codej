package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 按backend/authMethod/Provider/profile及带来源版本管理进程内模型route租约。
 *
 * <p>显式注销先拒绝新lease，再取消并关闭已有route。普通资源关闭失败也立即阻止同身份新lease，
 * 保留失败资源；其他已获取租约仍按原生命周期终止。drain超时或store删除失败时fence保持关闭，
 * 只有已确认持久删除并排空的profile才能通过显式新代次登录解除fence。
 * 本类型仅约束同进程，不提供跨进程撤销或远端 revoke。</p>
 */
public final class CredentialLeaseRegistry implements AutoCloseable {
    /**
     * 全进程共享的有界清理设施：至多四个 daemon 和 128 个排队动作，无每次注销新建线程。
     * 第三方取消/close 永久阻塞时不强杀线程；饱和动作保留未完成状态，可由再次 drain/close 重试，
     * 绝不转为调用线程执行或伪造 terminal。daemon 无任务时回收，注册表关闭不关闭共享池。
     */
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "credential-lease-cleanup");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { CLEANUP.allowCoreThreadTimeOut(true); }
    private final Object monitor = new Object();
    private final Map<ProfileKey, ProfileState> profiles = new HashMap<>();
    private boolean closed;

    /**
     * 创建空的进程内凭据租约注册表。
     */
    public CredentialLeaseRegistry() {
    }

    /**
     * 原子获取 generation 匹配的活动 lease；已 fence 时返回 AUTH_REVOKED。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @param generation 凭据代次
     * @param resource 与本次 lease 绑定、终止时需要关闭的 route 资源
     * @return 已登记的活动 lease
     */
    public Lease acquire(String providerId, String profileId, long generation, AutoCloseable resource) {
        return acquire(new ProfileKey(providerId, profileId), new CredentialVersion.LegacyGeneration(generation), resource);
    }

    /**
     * 为Pi完整身份获取模型请求租约；authEpoch不与legacy索引代次混比。
     * @param identity 可信Pi身份
     * @param authEpoch 已读取的正登录代次，不是materialRevision
     * @param resource 必须确认关闭的请求资源
     * @return 此身份绑定的租约
     */
    public Lease acquire(PiCredentialIdentity identity, CredentialVersion.PiAuthEpoch authEpoch, AutoCloseable resource) {
        Objects.requireNonNull(authEpoch);
        return acquire(ProfileKey.pi(identity), authEpoch, resource);
    }

    private Lease acquire(ProfileKey key, CredentialVersion generation, AutoCloseable resource) {
        synchronized (monitor) {
            if (closed) throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            ProfileState state = profiles.computeIfAbsent(key, ignored -> new ProfileState());
            if (state.fenced || generation.value() < state.minimumGeneration) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            if (state.activeGeneration != null && !state.activeGeneration.equals(generation)) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            state.activeGeneration = generation;
            Lease lease = new Lease(this, key, generation, Objects.requireNonNull(resource, "resource 不能为空"));
            state.leases.add(lease);
            return lease;
        }
    }

    /**
     * 返回不暴露 generation、run identity 或资源细节的 active 数量。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @return 当前活动 lease 数量；profile 尚未登记时返回 {@code 0}
     */
    public int activeCount(String providerId, String profileId) {
        synchronized (monitor) {
            ProfileState state = profiles.get(new ProfileKey(providerId, profileId));
            return state == null ? 0 : state.leases.size();
        }
    }

    /**
     * 发布 logout fence，取消并关闭当前 leases，并在共享 deadline 内等待 terminal callback。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @param timeout 等待全部 lease 终止的最长时限
     * @param cancellation logout drain 的取消信号
     * @return 全部 lease 已终止时为 {@code true}；{@code false} 表示 REVOKING_BLOCKED，fence 仍保留
     */
    public boolean fenceAndDrain(String providerId, String profileId, Duration timeout,
                                 CancellationToken cancellation) {
        return fenceAndDrain(new ProfileKey(providerId, profileId), timeout, cancellation);
    }

    /**
     * 只撤销指定Pi完整身份，不影响同名legacy配置。
     * @param identity 可信Pi身份
     * @param timeout 有界排空时间
     * @param cancellation 取消信号
     * @return 已确认全部相关租约终止时为true
     */
    public boolean fenceAndDrain(PiCredentialIdentity identity, Duration timeout, CancellationToken cancellation) {
        return fenceAndDrain(ProfileKey.pi(identity), timeout, cancellation);
    }

    private boolean fenceAndDrain(ProfileKey key, Duration timeout, CancellationToken cancellation) {
        long started = System.nanoTime();
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout 不能为负");
        long budget = timeout.toNanos();
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        List<Lease> captured;
        synchronized (monitor) {
            ProfileState state = profiles.computeIfAbsent(key, ignored -> new ProfileState());
            if (!state.fenced) state.deletionGeneration = null;
            state.fenced = true;
            state.drained = false;
            captured = List.copyOf(state.leases);
        }
        captured.forEach(Lease::requestCancellationAndClose);

        synchronized (monitor) {
            while (activeCountLocked(key) > 0) {
                if (cancellation.isCancellationRequested()) return false;
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return false;
                try {
                    long millis = Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), 50));
                    monitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (cancellation.isCancellationRequested()) return false;
            profiles.get(key).drained = true;
            return true;
        }
    }

    /**
     * 标记本地删除完成；fence 永久保留到进程关闭。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     */
    public void markRevoked(String providerId, String profileId) {
        synchronized (monitor) {
            ProfileState state = profiles.computeIfAbsent(new ProfileKey(providerId, profileId), ignored -> new ProfileState());
            state.fenced = true;
            state.permanentFence = true;
            state.deletionGeneration = null;
        }
    }

    /**
     * 记录调用方已成功持久删除的代次；必须先 fence 且排空。失败不得授权后续激活。
     *
     * @param providerId 提供方标识
     * @param profileId 配置标识
     * @param deletionGeneration 删除事务发布后的非负索引代次
     */
    public void markRevoked(String providerId, String profileId, long deletionGeneration) {
        markRevoked(new ProfileKey(providerId, profileId), deletionGeneration);
    }

    /**
     * 记录Pi删除事务实际发布的索引水位，不使用随后重读的其他事务代次。
     * @param identity 可信Pi身份
     * @param deletionGeneration deleteWithReceipt返回的索引水位
     */
    public void markRevoked(PiCredentialIdentity identity, long deletionGeneration) {
        markRevoked(ProfileKey.pi(identity), deletionGeneration);
    }

    private void markRevoked(ProfileKey key, long deletionGeneration) {
        synchronized (monitor) {
            ProfileState state = profiles.get(key);
            if (closed || deletionGeneration < 0 || state == null || !state.fenced
                    || !state.drained || !state.leases.isEmpty() || state.permanentFence
                    || deletionGeneration < state.minimumGeneration) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            if (state.deletionGeneration != null && deletionGeneration < state.deletionGeneration) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            state.deletionGeneration = deletionGeneration;
        }
    }

    /**
     * 仅在调用方重新读取 store 并确认目标存在后调用；list/status 不得调用本方法。
     * 已确认删除、已排空且严格更新的代次才解除 fence，并永久拒绝该新 epoch 之前的代次。
     * 未 fenced 的普通登录是 no-op；失败 fence 和旧版永久撤销不能借登录复活。
     *
     * @param providerId 提供方标识
     * @param profileId 配置标识
     * @param currentGeneration 重读 store 得到的当前代次
     */
    public void activateAfterLogin(String providerId, String profileId, long currentGeneration) {
        activateAfterLogin(new ProfileKey(providerId, profileId), currentGeneration);
    }

    /**
     * 显式确认Pi重新登录后才解除该身份fence；刷新修订不能替代新epoch。
     * @param identity 可信Pi身份
     * @param authEpoch 重新读取并确认的登录代次
     */
    public void activateAfterLogin(PiCredentialIdentity identity, CredentialVersion.PiAuthEpoch authEpoch) {
        activateAfterLogin(ProfileKey.pi(identity), Objects.requireNonNull(authEpoch).value());
    }

    private void activateAfterLogin(ProfileKey key, long currentGeneration) {
        synchronized (monitor) {
            if (closed) throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            ProfileState state = profiles.get(key);
            if (state == null || !state.fenced) return;
            if (state.permanentFence || !state.drained || state.deletionGeneration == null || !state.leases.isEmpty()
                    || currentGeneration <= state.deletionGeneration || currentGeneration < state.minimumGeneration) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            state.minimumGeneration = currentGeneration;
            state.deletionGeneration = null;
            state.activeGeneration = null;
            state.fenced = false;
            state.drained = false;
        }
    }

    /**
     * 查询当前进程是否已 fence profile；只用于本地 status 投影。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @return profile 已被 fence 时为 {@code true}，否则为 {@code false}
     */
    public boolean fenced(String providerId, String profileId) {
        synchronized (monitor) {
            ProfileState state = profiles.get(new ProfileKey(providerId, profileId));
            return state != null && state.fenced;
        }
    }

    /**
     * 查询Pi身份的活动资源数，不暴露其版本或秘密。
     * @param identity 可信Pi身份
     * @return 活动租约数
     */
    public int activeCount(PiCredentialIdentity identity) {
        synchronized (monitor) { return activeCountLocked(ProfileKey.pi(identity)); }
    }

    /**
     * 查询Pi身份fence，不隐式重新激活。
     * @param identity 可信Pi身份
     * @return 已fence时为true
     */
    public boolean fenced(PiCredentialIdentity identity) {
        synchronized (monitor) {
            ProfileState state = profiles.get(ProfileKey.pi(identity));
            return state != null && state.fenced;
        }
    }

    private int activeCountLocked(ProfileKey key) {
        ProfileState state = profiles.get(key);
        return state == null ? 0 : state.leases.size();
    }
    /** 资源关闭未确认时立即阻止同身份后续Run，不等用户显式注销才发布fence。 */
    private void cleanupFailed(Lease lease) {
        synchronized (monitor) {
            ProfileState state = profiles.get(lease.key);
            if (state != null && state.leases.contains(lease)) {
                state.fenced = true;
                state.drained = false;
                state.deletionGeneration = null;
                monitor.notifyAll();
            }
        }
    }
    private void terminal(Lease lease) {
        synchronized (monitor) {
            ProfileState state = profiles.get(lease.key);
            if (state != null && state.leases.remove(lease)) {
                if (state.leases.isEmpty()) state.activeGeneration = null;
                monitor.notifyAll();
            }
        }
    }
    @Override public void close() {
        List<Lease> leases = new ArrayList<>();
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            profiles.values().forEach(state -> { state.fenced = true; leases.addAll(state.leases); });
        }
        leases.forEach(Lease::requestCancellationAndClose);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code code) {
        return new ProviderAuthException(code, ProviderAuthException.Action.LOGIN, false);
    }
    private record ProfileKey(String backend, String authMethod, String providerId, String profileId) {
        private ProfileKey(String providerId, String profileId) { this("spring-ai", "API_KEY", providerId, profileId); }
        private ProfileKey {
            Objects.requireNonNull(backend); Objects.requireNonNull(authMethod);
            Objects.requireNonNull(providerId); Objects.requireNonNull(profileId);
        }
        private static ProfileKey pi(PiCredentialIdentity identity) {
            Objects.requireNonNull(identity);
            return new ProfileKey(identity.backend(), identity.authMethod().name(), identity.providerId(), identity.profileId());
        }
    }
    private static final class ProfileState {
        private boolean fenced;
        private boolean permanentFence;
        private boolean drained;
        private long minimumGeneration;
        private Long deletionGeneration;
        private CredentialVersion activeGeneration;
        private final List<Lease> leases = new ArrayList<>();
    }

    /** 单个 Run route 的幂等 lease；resource close 与 terminal 分开支持协作式 drain。 */
    public static final class Lease implements AutoCloseable {
        private final CredentialLeaseRegistry owner;
        private final ProfileKey key;
        private final CredentialVersion generation;
        private final AutoCloseable resource;
        private boolean resourceStarted;
        private boolean resourceFinished;
        private boolean resourceFailed;
        private boolean cancellationRequested;
        private boolean cancellationStarted;
        private boolean cancellationFinished;
        private boolean terminalRequested;
        private boolean terminal;
        private Runnable cancellation;
        private Lease(CredentialLeaseRegistry owner, ProfileKey key, CredentialVersion generation, AutoCloseable resource) {
            this.owner=owner; this.key=key; this.generation=generation; this.resource=resource;
        }
        /**
         * 一次性绑定同一 Run 的协作式取消动作；此前已请求取消时立即调度，不丢失晚绑定。
         * 动作与资源关闭独立调度，任一阻塞都不会阻塞注销调用线程；饱和时后续 drain/close 重试。
         *
         * @param value fence 发布时执行的协作式取消动作
         */
        public synchronized void bindCancellation(Runnable value) {
            Objects.requireNonNull(value);
            if (cancellation != null) throw new IllegalStateException("取消动作只能绑定一次");
            cancellation = value;
            if (cancellationRequested) scheduleCancellation();
        }
        /**
         * 返回只供 generation fence 测试的 generation，不进入 surface。
         *
         * @return 当前 lease 绑定的凭据代次
         */
        public long generation() { return generation.value(); }
        /** 返回租约版本的明确来源。
         * @return 带来源标签的版本，不包含材料修订号 */
        public CredentialVersion version() { return generation; }
        /**
         * 返回当前lease资源的清理证据，不以close返回或Run成功推断排空。
         * @return 仅terminal确认为RELEASED；失败sticky，后台关闭未完成仍为CLEANING
         */
        public synchronized io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus cleanupStatus() {
            if (resourceFailed) return io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.UNCONFIRMED;
            if (terminal) return io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.RELEASED;
            if (terminalRequested || resourceStarted || cancellationRequested) {
                return io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.CLEANING;
            }
            return io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.NOT_STARTED;
        }
        private synchronized void requestCancellationAndClose() {
            cancellationRequested = true;
            scheduleCancellation();
            scheduleResourceClose();
        }
        private void scheduleCancellation() {
            if (cancellation == null || cancellationStarted) return;
            cancellationStarted = true;
            if (!submit(() -> {
                try { cancellation.run(); }
                catch (RuntimeException ignored) { /* 不把外部取消异常及潜在敏感文案写入 stderr。 */ }
                finally {
                    synchronized (this) { cancellationFinished = true; publishTerminal(); }
                }
            })) cancellationStarted = false;
        }
        private void scheduleResourceClose() {
            if (resourceStarted) return;
            resourceStarted = true;
            if (!submit(this::closeResource)) resourceStarted = false;
        }
        private static boolean submit(Runnable action) {
            try { CLEANUP.execute(action); return true; }
            catch (RejectedExecutionException saturated) { return false; }
        }
        private void closeResource() {
            boolean succeeded = false;
            try { resource.close(); succeeded = true; }
            catch (Exception ignored) { /* close 失败无法证明资源已终止，保持 fence。 */ }
            finally {
                synchronized (this) {
                    resourceFinished = succeeded;
                    if (!succeeded) {
                        resourceFailed = true;
                        owner.cleanupFailed(this);
                    }
                    publishTerminal();
                }
            }
        }
        private void publishTerminal() {
            if (!terminal && terminalRequested && resourceFinished
                    && (!cancellationRequested || cancellation == null || cancellationFinished)) {
                terminal = true;
                owner.terminal(this);
            }
        }
        /**
         * 声明 Run 已结束；仅在资源 close 真正返回且已绑定取消动作返回后发布一次 terminal。
         * 普通完成同步清理以保留旧契约；已经请求撤销则始终交给有界后台池，避免阻塞 drain 调用方。
         * 与正在进行的 close 竞争不会重复关闭，也不会提前移除仍在清理的 lease。
         */
        @Override public void close() {
            boolean direct = false;
            synchronized (this) {
                terminalRequested = true;
                if (cancellationRequested) {
                    scheduleCancellation();
                    scheduleResourceClose();
                } else if (!resourceStarted) {
                    resourceStarted = true;
                    direct = true;
                }
                publishTerminal();
            }
            if (direct) closeResource();
        }
    }
}
