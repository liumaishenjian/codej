package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.provider.SelectedProviderRouteFactory;
import io.github.liumaishenjian.ccjava.cli.provider.probe.JdkProviderProbeTransport;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason;
import io.github.liumaishenjian.ccjava.core.network.NetworkPurpose;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.RunScopedModelGateway;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Provider/Auth 控制面、gateway factory 与 lease registry 的用户级资源所有者。
 *
 * <p>Composition Root 只解析一次 user home，并让 CLI/stdio/TUI 共享同一应用服务。模型 Gateway
 * 延迟到每个 run 边界解析 secret；资源关闭会 fence 并关闭所有进程内 route。</p>
 */
public final class ProviderAuthRuntimeResources implements AutoCloseable {
    private final ProviderAuthApplicationService service;
    private final CredentialLeaseRegistry leases;
    private final SelectedProviderRouteFactory routes;
    private final JdkProviderProbeTransport probeTransport;
    private final java.util.List<AutoCloseable> legacyResources = new java.util.ArrayList<>();

    private ProviderAuthRuntimeResources(ProviderAuthApplicationService service, CredentialLeaseRegistry leases,
                                         SelectedProviderRouteFactory routes,
                                         JdkProviderProbeTransport probeTransport) {
        this.service=Objects.requireNonNull(service); this.leases=Objects.requireNonNull(leases);
        this.routes=Objects.requireNonNull(routes); this.probeTransport=Objects.requireNonNull(probeTransport);
    }

    /**
     * 从固定 home、repository root 与环境快照装配共享服务、route factory 与 lease registry。
     *
     * @param userHome 已解析的用户主目录
     * @param repositoryRoot 用于读取 legacy Provider 配置的仓库根目录
     * @param environment 用于解析 ENV credential 的环境变量快照
     * @return 持有共享 Provider/Auth 运行时资源的可关闭对象
     */
    public static ProviderAuthRuntimeResources open(Path userHome, Path repositoryRoot,
                                                    Map<String, String> environment) {
        Path fixedHome=Objects.requireNonNull(userHome).toAbsolutePath().normalize();
        RestrictedFileCredentialStore credentials=new RestrictedFileCredentialStore(fixedHome);
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(fixedHome);
        LegacyCredentialMigrationService migration=new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(repositoryRoot),definitions,credentials);
        CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        JdkProviderProbeTransport probe=new JdkProviderProbeTransport((request,cancellation) -> {
            if(cancellation.isCancellationRequested()) return NetworkAccessDecision.deny(NetworkAccessReason.CANCELLED);
            boolean fixed=request.purpose()==NetworkPurpose.PROVIDER_AUTH_PROBE
                    && !request.redirectsAllowed()
                    && "https".equals(request.scheme());
            return fixed?NetworkAccessDecision.allow():NetworkAccessDecision.deny(NetworkAccessReason.INVALID_TARGET);
        });
        Map<String, String> fixedEnvironment = Map.copyOf(environment);
        var piCredentials = new io.github.liumaishenjian.ccjava.cli.auth.PiCredentialStore(fixedHome);
        java.util.function.Supplier<io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration>
                piConfiguration = () -> io.github.liumaishenjian.ccjava.cli.auth.PiRuntimeConfiguration.resolve(fixedEnvironment);
        ProviderAuthApplicationService service=new ProviderAuthApplicationService(
                definitions,credentials,migration,fixedEnvironment,leases,probe,java.time.Clock.systemUTC(),
                piCredentials, piConfiguration);
        var piRoutes = new io.github.liumaishenjian.ccjava.cli.provider.PiSelectedProviderRouteFactory(
                piCredentials, leases, new io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog(),
                piConfiguration, name -> {
                    String value = fixedEnvironment.get(name);
                    return value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                });
        SelectedProviderRouteFactory routes=new SelectedProviderRouteFactory(definitions,
                new CredentialResolver(credentials,fixedEnvironment),leases,
                ProviderGatewayFactoryRegistry.production(), piRoutes);
        return new ProviderAuthRuntimeResources(service,leases,routes,probe);
    }

    /**
     * 返回所有 surface 共用的应用服务。
     *
     * @return 共享的 Provider/Auth 应用服务
     */
    public ProviderAuthApplicationService service(){return service;}

    /**
     * 返回延迟到 Run 边界冻结 selection 的唯一 Router 路径。
     *
     * <p>包装层同时取得应用服务的 active-run fence，因此 {@code /models} 与 stdio
     * {@code provider.control models.use} 在真实 Headless Run 期间确定性拒绝。两个 scope 按逆序关闭；
     * 即使底层 route 关闭失败，应用 fence 也不会泄漏。</p>
     *
     * @return 在每次 Run 开始时冻结选择并管理 fence 的模型 Gateway
     */
    public RunScopedModelGateway modelGateway() {
        return fencedGateway(routes.lazyGatewayWithCancellation(token -> service.routingSelection(token)), service);
    }

    /**
     * 创建可从 legacy 启动但随后每 Run 动态选择 Provider 的生产 Gateway。
     *
     * <p>legacy 模型和摘要共用一个 client；这里只装配初始兼容资源，不捕获或吞掉严格选择异常。
     * 资源由本对象关闭，因此 Session 构造失败也不会遗留 HTTP client。</p>
     * @param legacySettings 可空的合法初始 legacy 配置
     * @param options 非 secret Runtime 选项
     * @return 带 active-run fence 的动态模型/摘要 Gateway
     */
    public synchronized RunScopedModelGateway modelGateway(
            io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings legacySettings,
            HeadlessRuntimeOptions options) {
        Objects.requireNonNull(options);
        if (legacySettings == null) return modelGateway();
        var resource = new io.github.liumaishenjian.ccjava.model.springai.OpenAiCompatibleModelFactory()
                .createResource(legacySettings, Map.of(), options.timeout());
        io.github.liumaishenjian.ccjava.cli.diagnostics.ModelDiagnostics diagnostics = null;
        try {
            diagnostics = io.github.liumaishenjian.ccjava.cli.diagnostics.ModelDiagnostics.open(
                    options.diagnosticMode(), options.diagnosticDirectory());
            var provider = new io.github.liumaishenjian.ccjava.model.springai.SpringAiModelGateway(
                    resource.chatModel(), legacySettings.model(), diagnostics.recorder());
            var retrying = new io.github.liumaishenjian.ccjava.core.RetryingModelGateway(
                    provider, io.github.liumaishenjian.ccjava.core.ModelRetryPolicy.PRODUCTION_DEFAULT);
            RunScopedModelGateway gateway = fencedGateway(
                    routes.lazyGatewayWithCancellation(token -> service.routingSelection(token), retrying, provider), service);
            legacyResources.add(resource);
            legacyResources.add(diagnostics);
            return gateway;
        } catch (RuntimeException | Error failure) {
            try { resource.close(); } catch (Exception ignored) { }
            if (diagnostics != null) diagnostics.close();
            throw failure;
        }
    }

    /** 包级测试 seam：只组合 Run fence，不替换生产 route factory。 */
    static RunScopedModelGateway fencedGateway(RunScopedModelGateway delegate,
                                               ProviderAuthApplicationService service) {
        return new FencedRunGateway(delegate, service);
    }

    private static final class FencedRunGateway implements RunScopedModelGateway, StreamingModelGateway,
            io.github.liumaishenjian.ccjava.core.ContextSummarizer {
        private final RunScopedModelGateway delegate;
        private final ProviderAuthApplicationService service;

        private FencedRunGateway(RunScopedModelGateway delegate, ProviderAuthApplicationService service) {
            this.delegate = Objects.requireNonNull(delegate);
            this.service = Objects.requireNonNull(service);
        }

        @Override public boolean providesRunBindings() { return delegate.providesRunBindings(); }

        @Override
        public RunScope openRun() {
            return openRun(java.time.Duration.ofMinutes(30));
        }

        @Override
        public RunScope openRun(CancellationToken cancellation) {
            return openRun(java.time.Duration.ofMinutes(30), cancellation);
        }

        @Override
        public RunScope openRun(java.time.Duration maxDuration) {
            return openRun(maxDuration, CancellationToken.none());
        }

        /** Root 保留 active-run 互斥，凭据读取与开启失败共享调用取消和释放边界。 */
        @Override
        public RunScope openRun(java.time.Duration maxDuration, CancellationToken cancellation) {
            Objects.requireNonNull(cancellation);
            ProviderAuthApplicationService.RunSelection selection = service.beginRun();
            try {
                RunScope route = delegate.openRun(maxDuration, cancellation);
                return new RunScope() {
                    private final java.util.concurrent.atomic.AtomicBoolean closed =
                            new java.util.concurrent.atomic.AtomicBoolean();

                    @Override
                    public java.util.Optional<io.github.liumaishenjian.ccjava.core.RunModelBinding> binding() {
                        // 只转发已决定的端口；childSource 直接打开 factory，不再次取得 Root 互斥门。
                        return route.binding();
                    }

                    @Override
                    public io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus cleanupStatus() {
                        return route.cleanupStatus();
                    }

                    @Override
                    public void bindCancellation(Runnable cancellation) {
                        route.bindCancellation(cancellation);
                    }

                    @Override
                    public void close() {
                        if (!closed.compareAndSet(false, true)) return;
                        try {
                            route.close();
                        } finally {
                            selection.close();
                        }
                    }
                };
            } catch (RuntimeException | Error failure) {
                selection.close();
                throw failure;
            }
        }

        @Override
        public ModelTurn complete(ModelRequest request, ModelStreamObserver observer,
                                  CancellationToken cancellation) throws ModelGatewayException {
            if (!(delegate instanceof StreamingModelGateway streaming)) {
                return delegate.complete(request);
            }
            return streaming.complete(request, observer, cancellation);
        }

        @Override
        public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
            return delegate.complete(request);
        }

        @Override
        public java.util.Optional<io.github.liumaishenjian.ccjava.domain.SummaryCandidate> summarize(
                io.github.liumaishenjian.ccjava.domain.SummaryRequest request, CancellationToken cancellation) {
            if (!(delegate instanceof io.github.liumaishenjian.ccjava.core.ContextSummarizer summarizer)) {
                throw new IllegalStateException("Provider summary unavailable");
            }
            return summarizer.summarize(request, cancellation);
        }
    }

    /**
     * 先确认 Pi 登录清理，再关闭原有 lease/probe/legacy 资源。
     * Pi 清理失败优先向宿主传播，不能被其他清理异常覆盖；重复关闭继续报告 sticky 失败。
     */
    @Override public synchronized void close() {
        Throwable failure = null;
        try { service.closePiLogin(); } catch (RuntimeException | Error failed) { failure = failed; }
        try { leases.close(); } catch (RuntimeException | Error failed) { if (failure == null) failure = failed; }
        try { probeTransport.close(); } catch (RuntimeException | Error failed) { if (failure == null) failure = failed; }
        for (int index = legacyResources.size() - 1; index >= 0; index--) {
            try { legacyResources.get(index).close(); }
            catch (Exception ignored) { /* 保持旧资源关闭契约，不覆盖 Pi 清理失败。 */ }
            catch (Error failed) { if (failure == null) failure = failed; }
        }
        legacyResources.clear();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
