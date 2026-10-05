package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialVersion;
import io.github.liumaishenjian.ccjava.core.CapturedRunSource;
import io.github.liumaishenjian.ccjava.core.ContextSummarizer;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.RunModelBinding;
import java.time.Duration;
import java.util.OptionalLong;
import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.ModelRetryPolicy;
import io.github.liumaishenjian.ccjava.core.ModelRetryRuntime;
import io.github.liumaishenjian.ccjava.core.RetryingModelGateway;
import io.github.liumaishenjian.ccjava.core.RunScopedModelGateway;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.core.model.ModelProviderRoute;
import io.github.liumaishenjian.ccjava.core.model.ProviderRouter;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.model.CapabilitySupport;
import io.github.liumaishenjian.ccjava.domain.model.ModelCapability;
import io.github.liumaishenjian.ccjava.domain.model.ModelProviderCapabilitySnapshot;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayConfiguration;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.Function;

/**
 * 从 run 边界冻结的 selection、definition、profile generation 与 secret 创建单一路由。
 *
 * <p>每个 run 只创建一个 {@link ModelProviderRoute}，并始终以 {@link ProviderRouter} 作为
 * {@code ModelGateway}。RunScope.binding 显式持有独立端口，跨线程调用不依赖线程继承；
 * 普通 ThreadLocal 仅服务旧同步 facade。缺失 secret、鉴权失败、429 或 timeout
 * 都不会改选 profile/provider。</p>
 */
public final class SelectedProviderRouteFactory {
    private final ProviderDefinitionStore definitions;
    private final CredentialResolver resolver;
    private final CredentialLeaseRegistry leases;
    private final ProviderGatewayFactoryRegistry factories;
    private final ModelRetryPolicy retryPolicy;
    private final ModelRetryRuntime retryRuntime;
    private final PiSelectedProviderRouteFactory piFactory;

    /**
     * 创建无 fallback 的 selected-route factory。
     *
     * @param definitions Provider 定义快照来源
     * @param resolver 鉴权凭证解析器
     * @param leases 凭证租约注册表
     * @param factories Provider Gateway 工厂注册表
     */
    public SelectedProviderRouteFactory(ProviderDefinitionStore definitions, CredentialResolver resolver,
                                        CredentialLeaseRegistry leases, ProviderGatewayFactoryRegistry factories) {
        this(definitions, resolver, leases, factories,
                ModelRetryPolicy.PRODUCTION_DEFAULT, ModelRetryRuntime.system());
    }

    /** 包级测试 seam：保留生产默认策略，只替换等待与随机数，避免测试真实休眠。 */
    SelectedProviderRouteFactory(
            ProviderDefinitionStore definitions,
            CredentialResolver resolver,
            CredentialLeaseRegistry leases,
            ProviderGatewayFactoryRegistry factories,
            ModelRetryRuntime retryRuntime) {
        this(definitions, resolver, leases, factories,
                ModelRetryPolicy.PRODUCTION_DEFAULT, retryRuntime);
    }

    /** 包级测试 seam：仅供更小 attempt 策略的聚焦行为测试。 */
    SelectedProviderRouteFactory(
            ProviderDefinitionStore definitions,
            CredentialResolver resolver,
            CredentialLeaseRegistry leases,
            ProviderGatewayFactoryRegistry factories,
            ModelRetryPolicy retryPolicy,
            ModelRetryRuntime retryRuntime) {
        this(definitions, resolver, leases, factories, retryPolicy, retryRuntime, null);
    }

    /**
     * 显式启用 Pi 边缘装配；旧构造器仍严格拒绝 Pi 标签。
     * @param definitions 旧 Provider 定义来源
     * @param resolver 仅用于旧后端的凭证解析器
     * @param leases Root 共享租约表
     * @param factories 旧后端网关注册表
     * @param piFactory 显式可选 Pi 工厂；null 表示不可用，绝不回退
     */
    public SelectedProviderRouteFactory(ProviderDefinitionStore definitions, CredentialResolver resolver,
            CredentialLeaseRegistry leases, ProviderGatewayFactoryRegistry factories,
            PiSelectedProviderRouteFactory piFactory) {
        this(definitions, resolver, leases, factories, ModelRetryPolicy.PRODUCTION_DEFAULT,
                ModelRetryRuntime.system(), piFactory);
    }

    /** 包级测试 seam：两个后端共享同一确定性重试策略。 */
    SelectedProviderRouteFactory(ProviderDefinitionStore definitions, CredentialResolver resolver,
            CredentialLeaseRegistry leases, ProviderGatewayFactoryRegistry factories,
            ModelRetryPolicy retryPolicy, ModelRetryRuntime retryRuntime,
            PiSelectedProviderRouteFactory piFactory) {
        this.piFactory = piFactory;
        this.definitions=Objects.requireNonNull(definitions); this.resolver=Objects.requireNonNull(resolver);
        this.leases=Objects.requireNonNull(leases); this.factories=Objects.requireNonNull(factories);
        this.retryPolicy=Objects.requireNonNull(retryPolicy); this.retryRuntime=Objects.requireNonNull(retryRuntime);
    }

    /**
     * 创建延迟到 run 边界才解析 selection 与 secret 的 Gateway。
     *
     * @param selection 当前 Provider 选择快照来源
     * @return 按 run 隔离路由与凭证租约的延迟 Gateway
     */
    public RunScopedModelGateway lazyGateway(Supplier<Optional<ProviderSelectionSnapshot>> selection) {
        return lazyGateway(selection, null, null);
    }

    /**
     * 初始配置兼容入口：仅严格选择返回 empty 时采用启动时的 legacy 身份。
     *
     * <p>选择或凭证异常绝不回退；legacy 的 client 生命周期由调用方持有，摘要必须来自同一个 client。</p>
     * @param selection 严格选择来源；显式选择失效必须抛出异常
     * @param legacyGateway 可空的初始 legacy 模型端口，已具有唯一一层 retry
     * @param legacySummarizer 与 legacyGateway 同源的摘要端口，两者必须同时存在
     * @return 每 Run 冻结模型与摘要身份的 Gateway
     */
    public RunScopedModelGateway lazyGateway(Supplier<Optional<ProviderSelectionSnapshot>> selection,
            io.github.liumaishenjian.ccjava.core.ModelGateway legacyGateway,
            io.github.liumaishenjian.ccjava.core.ContextSummarizer legacySummarizer) {
        Objects.requireNonNull(selection);
        return lazyGatewayWithCancellation(ignored -> selection.get(), legacyGateway, legacySummarizer);
    }

    /**
     * 创建连默认选择读取也传播启动取消的生产路由。
     * @param selection 接收同一启动token的严格选择来源
     * @return 显式绑定路由，不设置任何静态fallback
     */
    public RunScopedModelGateway lazyGatewayWithCancellation(
            Function<CancellationToken, Optional<ProviderSelectionSnapshot>> selection) {
        return lazyGatewayWithCancellation(selection, null, null);
    }

    /**
     * 创建有明确启动兼容源的可取消路由；只有严格选择返回empty才使用兼容源。
     * @param selection 可取消的严格选择来源
     * @param legacyGateway 外部管理的旧启动模型端口
     * @param legacySummarizer 同一旧client的摘要端口，必须成对提供
     * @return 每Run显式冻结的模型路由
     */
    public RunScopedModelGateway lazyGatewayWithCancellation(
            Function<CancellationToken, Optional<ProviderSelectionSnapshot>> selection,
            ModelGateway legacyGateway, ContextSummarizer legacySummarizer) {
        if ((legacyGateway == null) != (legacySummarizer == null)) {
            throw new IllegalArgumentException("Legacy model and summary must share one source");
        }
        return new LazyRunGateway(Objects.requireNonNull(selection), legacyGateway, legacySummarizer);
    }

    private OpenedRoute open(ProviderSelectionSnapshot selection, Duration runBudget,
            CredentialVersion capturedVersion, CancellationToken cancellation) {
        checkOpening(runBudget, cancellation);
        // 必须在任何旧目录/凭证解析之前分派；显式 Pi 失败不进入 legacy 分支。
        if ("pi".equals(selection.backend())) {
            if (piFactory == null) throw failure(ProviderAuthException.Code.PROVIDER_UNKNOWN);
            if (capturedVersion != null && !(capturedVersion instanceof CredentialVersion.PiAuthEpoch)) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            var opened = capturedVersion instanceof CredentialVersion.PiAuthEpoch epoch
                    ? piFactory.openCaptured(selection, epoch, runBudget, cancellation)
                    : piFactory.open(selection, runBudget, cancellation);
            try {
                var retrying = new RetryingModelGateway(opened.raw(), retryPolicy, retryRuntime);
                var router = new ProviderRouter(List.of(new ModelProviderRoute(selection.providerId(),
                        retrying, opened.capabilities())),
                        new io.github.liumaishenjian.ccjava.core.model.ProviderRoutePolicy(1,
                                java.time.Duration.ofMinutes(30), java.time.Duration.ZERO, -1, 1,
                                java.time.Clock.systemUTC()));
                return new OpenedRoute(router, opened.summarizer(), opened.lease(),
                        capturedSource(selection, opened.authEpoch()), Optional.of(selection), opened.contextWindowTokens());
            } catch (RuntimeException | Error failure) {
                opened.lease().close();
                throw failure;
            }
        }
        // 未知标签也不得借同名配置跨后端解析秘密。
        if (!"spring-ai".equals(selection.backend()) || !"API_KEY".equals(selection.authMethod())) {
            throw failure(ProviderAuthException.Code.PROVIDER_UNKNOWN);
        }
        if (capturedVersion != null && !(capturedVersion instanceof CredentialVersion.LegacyGeneration)) {
            throw failure(ProviderAuthException.Code.AUTH_REVOKED);
        }
        ProviderDefinition definition=definitions.snapshot(cancellation).catalog().require(selection.providerId());
        if(!definition.models().contains(selection.modelId())) throw failure(ProviderAuthException.Code.MODEL_UNKNOWN);
        CredentialResolver.ResolvedCredential credential=resolver.resolve(selection.providerId(),
                Optional.of(selection.profileId()),cancellation);
        var version = new CredentialVersion.LegacyGeneration(credential.generation());
        // 旧解析器返回本次读到的材料；版本不匹配必须先擦除并拒绝，不能默许换账号。
        if (capturedVersion != null && !capturedVersion.equals(version)) {
            credential.close();
            throw failure(ProviderAuthException.Code.AUTH_REVOKED);
        }
        LegacyResourceSlot resource = new LegacyResourceSlot(credential);
        CredentialLeaseRegistry.Lease lease = null;
        try {
            lease=leases.acquire(selection.providerId(),selection.profileId(),version.value(), resource);
            ModelGateway provider;
            synchronized (resource) {
                checkOpening(runBudget, cancellation);
                if (resource.closed || leases.fenced(selection.providerId(), selection.profileId())) {
                    throw failure(ProviderAuthException.Code.AUTH_REVOKED);
                }
                char[] chars=credential.secret().copyChars();
                try {
                    provider=Objects.requireNonNull(factories.require(kind(definition)).create(new ProviderGatewayConfiguration(
                            definition.providerId(),definition.baseUri(),selection.modelId(),definition.staticHeaders(),
                            narrowerTimeout(definition.requestTimeout(), runBudget),chars)));
                    resource.gateway = provider;
                } finally { java.util.Arrays.fill(chars,'\0'); }
                checkOpening(runBudget, cancellation);
            }
            ModelProviderCapabilitySnapshot capabilities=capabilities(selection);
            RetryingModelGateway retrying = new RetryingModelGateway(provider, retryPolicy, retryRuntime);
            ProviderRouter router=new ProviderRouter(List.of(new ModelProviderRoute(selection.providerId(),retrying,capabilities)),
                    new io.github.liumaishenjian.ccjava.core.model.ProviderRoutePolicy(1,
                            java.time.Duration.ofMinutes(30),java.time.Duration.ZERO,-1,1,java.time.Clock.systemUTC()));
            io.github.liumaishenjian.ccjava.core.ContextSummarizer summarizer =
                    provider instanceof io.github.liumaishenjian.ccjava.core.ContextSummarizer sameSource
                            ? sameSource : (request, token) -> {
                                throw new IllegalStateException("Provider summary unavailable");
                            };
            return new OpenedRoute(router, summarizer, lease, capturedSource(selection, version),
                    Optional.of(selection), OptionalLong.empty());
        } catch (RuntimeException | Error failure) {
            if (lease != null) {
                lease.close();
            } else {
                credential.close(); // acquire 尚未成功，未构造任何网关。
            }
            throw failure;
        }
    }
    private static java.time.Duration narrowerTimeout(
            java.time.Duration providerTimeout, java.time.Duration runBudget) {
        Objects.requireNonNull(runBudget, "runBudget 不能为空");
        if (runBudget.isNegative() || runBudget.isZero()) {
            throw new IllegalArgumentException("runBudget 必须大于 0");
        }
        return providerTimeout.compareTo(runBudget) <= 0 ? providerTimeout : runBudget;
    }

    private static ProviderGatewayKind kind(ProviderDefinition definition) {
        return switch(definition.kind()) {
            case OPENAI_COMPATIBLE -> ProviderGatewayKind.OPENAI_COMPATIBLE;
            case ANTHROPIC -> ProviderGatewayKind.ANTHROPIC;
            case OPENROUTER -> ProviderGatewayKind.OPENROUTER;
        };
    }
    private static ModelProviderCapabilitySnapshot capabilities(ProviderSelectionSnapshot selected) {
        EnumMap<ModelCapability,CapabilitySupport> values=new EnumMap<>(ModelCapability.class);
        values.put(ModelCapability.TEXT,CapabilitySupport.SUPPORTED);
        values.put(ModelCapability.TOOL_CALLING,CapabilitySupport.SUPPORTED);
        return ModelProviderCapabilitySnapshot.resolve(selected.providerId(),selected.modelId(),values,values);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code code) {
        return new ProviderAuthException(code,ProviderAuthException.Action.SELECT_PROFILE,false);
    }
    private record OpenedRoute(ModelGateway gateway, ContextSummarizer summarizer,
            CredentialLeaseRegistry.Lease lease, CapturedRunSource source,
            Optional<ProviderSelectionSnapshot> selection, OptionalLong window) { }

    /** 来源只捕获选择与有来源标签的版本，不捕获 PendingRun、父 lease 或父 gateway。 */
    private CapturedRunSource capturedSource(ProviderSelectionSnapshot selected, CredentialVersion version) {
        return new CapturedRunSource() {
            @Override public CapturedRunSource forModel(Optional<String> modelOverride) {
                Objects.requireNonNull(modelOverride);
                var target = modelOverride.map(model -> new ProviderSelectionSnapshot(selected.providerId(),
                        selected.profileId(), model, selected.backend(), selected.authMethod())).orElse(selected);
                if ("pi".equals(target.backend())) {
                    // 只校验Java嵌入目录，不为排队声明读取凭据或提前占用子租约。
                    new ProviderDefinitionStore.DefaultSelection(target.providerId(), target.modelId(),
                            target.backend(), target.authMethod(), Optional.of(target.profileId()));
                } else if (!definitions.snapshot(CancellationToken.none()).catalog().require(target.providerId())
                        .models().contains(target.modelId())) {
                    throw failure(ProviderAuthException.Code.MODEL_UNKNOWN);
                }
                return capturedSource(target, version);
            }
            @Override public RunScopedModelGateway.RunScope open(Optional<String> modelOverride, Duration budget) {
                return open(modelOverride, budget, CancellationToken.none());
            }
            @Override public RunScopedModelGateway.RunScope open(Optional<String> modelOverride, Duration budget,
                    CancellationToken cancellation) {
                Objects.requireNonNull(modelOverride);
                checkOpening(budget, cancellation);
                var target = modelOverride.map(model -> new ProviderSelectionSnapshot(selected.providerId(),
                        selected.profileId(), model, selected.backend(), selected.authMethod())).orElse(selected);
                PendingRun pending = new PendingRun();
                try {
                    pending.capture(SelectedProviderRouteFactory.this.open(target, budget, version, cancellation));
                    checkOpening(budget, cancellation);
                    return scope(pending, () -> { });
                } catch (RuntimeException | Error failure) {
                    pending.close();
                    throw failure;
                }
            }
        };
    }

    /** 明确的启动兼容来源；外部调用方拥有 client，本工厂不借它修复 selected 失败。 */
    private static final class StartupSource implements CapturedRunSource {
        private final ModelGateway gateway;
        private final ContextSummarizer summarizer;
        private StartupSource(ModelGateway gateway, ContextSummarizer summarizer) {
            this.gateway = gateway;
            this.summarizer = summarizer;
        }
        private OpenedRoute route() {
            return new OpenedRoute(gateway, summarizer, null, this, Optional.empty(), OptionalLong.empty());
        }
        @Override public RunScopedModelGateway.RunScope open(Optional<String> override, Duration budget) {
            return open(override, budget, CancellationToken.none());
        }
        @Override public RunScopedModelGateway.RunScope open(Optional<String> override, Duration budget,
                CancellationToken cancellation) {
            checkOpening(budget, cancellation);
            // 旧入口没有足够模型身份，连“同名覆盖”也不得推测或暗中扩展选模能力。
            if (Objects.requireNonNull(override).isPresent()) throw failure(ProviderAuthException.Code.MODEL_UNKNOWN);
            PendingRun pending = new PendingRun();
            pending.capture(route());
            try {
                checkOpening(budget, cancellation);
                return scope(pending, () -> { });
            } catch (RuntimeException | Error failure) {
                pending.close();
                throw failure;
            }
        }
    }

    private static void checkOpening(Duration budget, CancellationToken cancellation) {
        Objects.requireNonNull(budget);
        Objects.requireNonNull(cancellation);
        if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Run budget must be positive");
        if (cancellation.isCancellationRequested()) throw failure(ProviderAuthException.Code.AUTH_REVOKED);
    }

    /** 绑定闭包只引用自己的 PendingRun，关闭动作不会清空其他 Run 的状态。 */
    private static RunScopedModelGateway.RunScope scope(PendingRun pending, Runnable detachFacade) {
        OpenedRoute opened = pending.route.get();
        CapturedRunSource unavailable = (override, budget) -> {
            throw failure(ProviderAuthException.Code.AUTH_PROFILE_REQUIRED);
        };
        RunModelBinding binding = new RunModelBinding(pending, pending,
                opened == null ? unavailable : opened.source(),
                opened == null ? pending.selection : opened.selection(),
                opened == null ? OptionalLong.empty() : opened.window());
        return new RunScopedModelGateway.RunScope() {
            @Override public Optional<RunModelBinding> binding() { return Optional.of(binding); }
            @Override public io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus cleanupStatus() {
                if (opened != null && opened.lease() != null) return opened.lease().cleanupStatus();
                // 外部管理的启动client或打开失败没有本scope的完整清理回执。
                return pending.failure.get() != null
                        ? io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.UNCONFIRMED
                        : io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.UNKNOWN;
            }
            @Override public void bindCancellation(Runnable cancellation) { pending.bindCancellation(cancellation); }
            @Override public void close() {
                detachFacade.run();
                pending.close();
            }
        };
    }

    /** 先登记槽再创建 client；构造失败及 close 失败仍交给 Registry 保留 fence。 */
    private static final class LegacyResourceSlot implements AutoCloseable {
        private final CredentialResolver.ResolvedCredential credential;
        private ModelGateway gateway;
        private boolean closed;
        private LegacyResourceSlot(CredentialResolver.ResolvedCredential credential) { this.credential = credential; }
        @Override public synchronized void close() throws Exception {
            closed = true;
            credential.close();
            if (gateway instanceof AutoCloseable closeable) closeable.close();
        }
    }

    private final class LazyRunGateway implements RunScopedModelGateway, StreamingModelGateway,
            io.github.liumaishenjian.ccjava.core.ContextSummarizer {
        private final Function<CancellationToken, Optional<ProviderSelectionSnapshot>> selection;
        private final ThreadLocal<PendingRun> current = new ThreadLocal<>();
        private final io.github.liumaishenjian.ccjava.core.ModelGateway legacyGateway;
        private final io.github.liumaishenjian.ccjava.core.ContextSummarizer legacySummarizer;
        private LazyRunGateway(Function<CancellationToken, Optional<ProviderSelectionSnapshot>> selection,
                io.github.liumaishenjian.ccjava.core.ModelGateway legacyGateway,
                io.github.liumaishenjian.ccjava.core.ContextSummarizer legacySummarizer) {
            this.selection=selection;
            this.legacyGateway=legacyGateway;
            this.legacySummarizer=legacySummarizer;
        }
        @Override public boolean providesRunBindings() { return true; }
        @Override public RunScope openRun() {
            return openRun(java.time.Duration.ofMinutes(30));
        }
        @Override public RunScope openRun(CancellationToken cancellation) {
            return openRun(java.time.Duration.ofMinutes(30), cancellation);
        }
        @Override public RunScope openRun(java.time.Duration runBudget) {
            return openRun(runBudget, CancellationToken.none());
        }
        /** 启动期目录和凭据读取沿用调用者的同一取消边界。 */
        @Override public RunScope openRun(java.time.Duration runBudget, CancellationToken cancellation) {
            PendingRun pending = new PendingRun();
            current.set(pending);
            try {
                checkOpening(runBudget, cancellation);
                Optional<ProviderSelectionSnapshot> selected = Objects.requireNonNull(selection.apply(cancellation));
                checkOpening(runBudget, cancellation);
                pending.selection = selected;
                if (selected.isPresent()) {
                    pending.capture(open(selected.orElseThrow(), runBudget, null, cancellation));
                } else if (legacyGateway != null) {
                    pending.capture(new StartupSource(legacyGateway, legacySummarizer).route());
                } else {
                    throw failure(ProviderAuthException.Code.AUTH_PROFILE_REQUIRED);
                }
            } catch (RuntimeException failure) {
                pending.captureFailure(failure);
            } catch (Error failure) {
                current.remove();
                pending.close();
                throw failure;
            }
            return scope(pending, () -> { if (current.get() == pending) current.remove(); });
        }
        @Override public ModelTurn complete(ModelRequest request,ModelStreamObserver observer,CancellationToken cancellation)
                throws ModelGatewayException {
            PendingRun pending=current.get();
            if(pending==null)throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,"Run route not open");
            return pending.complete(request, observer, cancellation);
        }
        @Override public ModelTurn complete(ModelRequest request)throws ModelGatewayException{
            return complete(request,ignored->{},CancellationToken.none());
        }
        /** 摘要只使用当前 Run 捕获的原始 provider，不经过 retry/router 再解析或改选身份。 */
        @Override public Optional<io.github.liumaishenjian.ccjava.domain.SummaryCandidate> summarize(
                io.github.liumaishenjian.ccjava.domain.SummaryRequest request, CancellationToken cancellation) {
            PendingRun pending=current.get();
            if (pending == null) throw new IllegalStateException("Provider summary route unavailable");
            return pending.summarize(request, cancellation);
        }
    }

        /** 单个 Run 的跨线程端口；结束前后均检查 closed，拒绝关闭竞争中的迟到成功。 */
        private static final class PendingRun implements StreamingModelGateway, ContextSummarizer {
            private Optional<ProviderSelectionSnapshot> selection = Optional.empty();

            private void checkModel() throws ModelGatewayException {
                if (closed.get() || cancellationRequested.get()) throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.PERMANENT, "Run route closed");
                if (failure.get() != null) throw providerFailure(failure.get());
                if (route.get() == null) throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.PERMANENT, "Run route not ready");
            }
            @Override public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
                return complete(request, ignored -> { }, CancellationToken.none());
            }
            @Override public ModelTurn complete(ModelRequest request, ModelStreamObserver observer,
                    CancellationToken cancellation) throws ModelGatewayException {
                checkModel();
                ModelGateway gateway = route.get().gateway();
                ModelTurn result = gateway instanceof StreamingModelGateway streaming
                        ? streaming.complete(request, observer, cancellation) : gateway.complete(request);
                checkModel();
                return result;
            }
            private void checkSummary() {
                if (closed.get() || cancellationRequested.get() || failure.get() != null || route.get() == null) {
                    throw new IllegalStateException("Provider summary route unavailable");
                }
            }
            @Override public Optional<io.github.liumaishenjian.ccjava.domain.SummaryCandidate> summarize(
                    io.github.liumaishenjian.ccjava.domain.SummaryRequest request, CancellationToken cancellation) {
                checkSummary();
                var result = route.get().summarizer().summarize(request, cancellation);
                checkSummary();
                return result;
            }
            private void close() {
                if (!closed.compareAndSet(false, true)) return;
                OpenedRoute opened = route.get();
                if (opened != null && opened.lease() != null) opened.lease().close();
            }
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            private final AtomicReference<OpenedRoute> route = new AtomicReference<>();
            private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
            private final AtomicReference<Runnable> cancellation = new AtomicReference<>();
            private final java.util.concurrent.atomic.AtomicBoolean cancellationRequested =
                    new java.util.concurrent.atomic.AtomicBoolean();
            private final java.util.concurrent.atomic.AtomicBoolean cancellationDelivered =
                    new java.util.concurrent.atomic.AtomicBoolean();

            /** 撤销可早于 Runtime 绑定；晚到的绑定必须仍收到一次取消，不能消耗在空动作上。 */
            private void bindCancellation(Runnable value) {
                if (!cancellation.compareAndSet(null, Objects.requireNonNull(value))) {
                    throw new IllegalStateException("取消动作只能绑定一次");
                }
                deliverCancellation();
            }

            private void deliverCancellation() {
                Runnable action = cancellation.get();
                if (cancellationRequested.get() && action != null
                        && cancellationDelivered.compareAndSet(false, true)) action.run();
            }

            private void capture(OpenedRoute opened) {
                route.set(opened);
                if (opened.lease() != null) opened.lease().bindCancellation(() -> {
                    cancellationRequested.set(true);
                    deliverCancellation();
                });
            }

            private void captureFailure(RuntimeException value) {
                failure.set(Objects.requireNonNull(value));
            }
        }

        private static ModelGatewayException providerFailure(RuntimeException failure) {
            if (failure instanceof ProviderAuthException authFailure) {
                // 这些失败发生在本地 selection/profile/secret 解析阶段，并没有收到 Provider HTTP 响应。
                // 因而不能伪造 4xx；使用无 HTTP status 的保守 Provider 配置错误摘要。
                var summary = new io.github.liumaishenjian.ccjava.domain.ModelFailureSummary(
                        io.github.liumaishenjian.ccjava.domain.ModelFailureCategory.CONFIGURATION_REQUIRED,
                        Optional.empty(), 1, false);
                return new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,
                        "Provider configuration unavailable", summary, authFailure);
            }
            return new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,
                    "Provider configuration unavailable", failure);
        }
}
