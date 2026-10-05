package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.model.*;
import io.github.liumaishenjian.ccjava.model.pi.gateway.*;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pi 的 Run 边缘装配：只读取目录和登录元数据，秘密始终由逐回合 RPC 临时持有。
 *
 * <p>每次打开创建独立网关，固定完整身份与 authEpoch；不读取旧存储，不探测账号，
 * 不提供默认路由或网络重试。租约先登记资源槽，再构造惰性网关，避免撤销竞争遗留资源。</p>
 */
public final class PiSelectedProviderRouteFactory {
    private final PiCredentialStore store;
    private final CredentialLeaseRegistry leases;
    private final PiProviderCatalog catalog;
    private final Supplier<PiWorkerConfiguration> configuration;
    private final Function<String, byte[]> environment;
    private final GatewayFactory gateways;

    /**
     * 装配生产 Pi 网关；配置 supplier 仅在显式 Pi Run 打开时求值。
     * @param store 与登录共用的 Java 权威存储
     * @param leases 与 Root 共用的租约表
     * @param catalog 静态公开声明，不是账号权益
     * @param configuration 可信安装配置的惰性来源
     * @param environment 只为已保存 ENV_REF 导出临时字节，所有权交给 RPC
     */
    public PiSelectedProviderRouteFactory(PiCredentialStore store, CredentialLeaseRegistry leases,
            PiProviderCatalog catalog, Supplier<PiWorkerConfiguration> configuration,
            Function<String, byte[]> environment) {
        this(store, leases, catalog, configuration, environment, PiModelGateway::new);
    }

    /** 包级 Fake seam；生产入口始终使用 PiModelGateway。 */
    PiSelectedProviderRouteFactory(PiCredentialStore store, CredentialLeaseRegistry leases,
            PiProviderCatalog catalog, Supplier<PiWorkerConfiguration> configuration,
            Function<String, byte[]> environment, GatewayFactory gateways) {
        this.store = Objects.requireNonNull(store);
        this.leases = Objects.requireNonNull(leases);
        this.catalog = Objects.requireNonNull(catalog);
        this.configuration = Objects.requireNonNull(configuration);
        this.environment = Objects.requireNonNull(environment);
        this.gateways = Objects.requireNonNull(gateways);
    }

    /** 打开单个 Run 的原始端口和租约；由外层统一添加唯一 retry/router。 */
    Opened open(ProviderSelectionSnapshot selected, Duration runBudget) {
        return open(selected, runBudget, java.util.OptionalLong.empty(), CancellationToken.none());
    }

    /** 打开捕获身份，绝不改用最新 epoch；模型覆盖已由同 provider 目录校验。 */
    Opened openCaptured(ProviderSelectionSnapshot selected, CredentialVersion.PiAuthEpoch epoch,
            Duration runBudget, CancellationToken cancellation) {
        return open(selected, runBudget, java.util.OptionalLong.of(epoch.value()), cancellation);
    }

    /** Root 与 child 共用登记/构造顺序；等待元数据锁时传播调用方取消。 */
    Opened open(ProviderSelectionSnapshot selected, Duration runBudget, CancellationToken cancellation) {
        return open(selected, runBudget, java.util.OptionalLong.empty(), cancellation);
    }

    private Opened open(ProviderSelectionSnapshot selected, Duration runBudget,
            java.util.OptionalLong capturedEpoch, CancellationToken cancellation) {
        Objects.requireNonNull(cancellation);
        if (runBudget == null || runBudget.isZero() || runBudget.isNegative()) throw invalid();
        checkCancellation(cancellation);
        PiCredentialIdentity identity;
        PiProviderCatalog.Model model;
        try {
            identity = new PiCredentialIdentity(selected.backend(), selected.providerId(),
                    PiCredentialIdentity.AuthMethod.valueOf(selected.authMethod()), selected.profileId());
            var provider = catalog.require(identity.providerId());
            String method = identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH ? "oauth" : "api_key";
            if (provider.authMethods().stream().noneMatch(value -> value.id().equals(method))) throw invalid();
            model = catalog.requireModel(identity.providerId(), selected.modelId());
        } catch (IllegalArgumentException failure) { throw invalid(); }
        long currentEpoch = store.snapshot(cancellation).find(identity)
                .orElseThrow(() -> new ProviderAuthException(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE,
                        ProviderAuthException.Action.LOGIN, false)).authEpoch();
        long epoch = capturedEpoch.orElse(currentEpoch);
        if (currentEpoch != epoch) throw revoked();
        Duration timeout = runBudget.compareTo(Duration.ofSeconds(300)) < 0 ? runBudget : Duration.ofSeconds(300);
        var capabilities = capabilities(identity, model);
        ResourceSlot resource = new ResourceSlot();
        var lease = leases.acquire(identity, new CredentialVersion.PiAuthEpoch(epoch), resource);
        try {
            PiCredentialSessionFactory sessions = (operationId, token) -> {
                var rpc = new PiCredentialRpcSession(operationId, identity, PiCredentialRpcSession.Purpose.MODEL,
                        store, epoch, false, environment, token);
                return new PiCredentialSession() {
                    @Override public ObjectNode handle(ProtocolFrame frame) { return rpc.handle(frame); }
                    @Override public void close() { rpc.close(); }
                };
            };
            ModelGateway raw;
            synchronized (resource) {
                if (resource.closed) throw revoked();
                checkEpoch(identity, epoch, cancellation);
                var configured = configuration.get();
                checkEpoch(identity, epoch, cancellation);
                raw = Objects.requireNonNull(gateways.create(configured, identity.providerId(),
                        model.id(), timeout, sessions));
                resource.gateway = raw;
                if (!(raw instanceof AutoCloseable)) throw invalid();
                checkEpoch(identity, epoch, cancellation);
            }
            // 摘要只能由这个 Run 的同一原始端口提供；不完整或测试端口明确失败，禁止借用 legacy。
            ContextSummarizer summary = raw instanceof ContextSummarizer sameSource ? sameSource
                    : (request, token) -> {
                        throw new IllegalStateException("PI_SUMMARY_ADAPTER_UNAVAILABLE");
                    };
            return new Opened(raw, summary, capabilities, lease,
                    new CredentialVersion.PiAuthEpoch(epoch), java.util.OptionalLong.of(model.contextWindow()));
        } catch (RuntimeException | Error failure) {
            lease.close(); // 注册表保留 cleanup 失败资源并 fence，不伪造排空。
            throw failure;
        }
    }

    /** 元数据复查不会读取秘密；RPC 仍在每回合以捕获 epoch 执行最终凭证 fence。 */
    private void checkEpoch(PiCredentialIdentity identity, long epoch, CancellationToken cancellation) {
        checkCancellation(cancellation);
        if (store.snapshot(cancellation).find(identity).map(value -> value.authEpoch() != epoch).orElse(true)
                || leases.fenced(identity)) throw revoked();
        checkCancellation(cancellation);
    }

    private static void checkCancellation(CancellationToken cancellation) {
        if (cancellation.isCancellationRequested()) throw revoked();
    }

    private static ProviderAuthException revoked() {
        return new ProviderAuthException(ProviderAuthException.Code.AUTH_REVOKED,
                ProviderAuthException.Action.LOGIN, false);
    }

    private static ModelProviderCapabilitySnapshot capabilities(PiCredentialIdentity identity,
            PiProviderCatalog.Model model) {
        EnumMap<ModelCapability, CapabilitySupport> declared = new EnumMap<>(ModelCapability.class);
        declared.put(ModelCapability.TEXT, model.input().contains("text")
                ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED);
        // 三种目录协议均走已支持的文本/工具投影。这里声明适配器可接收工具请求，
        // 并不保证账号权益或特定模型一定选工具；observed 留空，绝不伪装真实探测。
        if (java.util.Set.of("openai-responses", "openai-codex-responses", "openai-completions")
                .contains(model.api())) {
            declared.put(ModelCapability.TOOL_CALLING, CapabilitySupport.SUPPORTED);
            declared.put(ModelCapability.STREAMING, CapabilitySupport.SUPPORTED);
            declared.put(ModelCapability.CANCELLATION, CapabilitySupport.SUPPORTED);
        }
        // 未提供公开 reasoning 通道；usage/缓存等逐模型保证保持 UNKNOWN。
        declared.put(ModelCapability.REASONING, CapabilitySupport.UNSUPPORTED);
        declared.put(ModelCapability.NATIVE_CONTEXT_EDITING, CapabilitySupport.UNSUPPORTED);
        return ModelProviderCapabilitySnapshot.resolve(identity.providerId(), model.id(), declared, Map.of());
    }

    private static ProviderAuthException invalid() {
        return new ProviderAuthException(ProviderAuthException.Code.PROVIDER_UNKNOWN,
                ProviderAuthException.Action.SELECT_PROFILE, false);
    }

    /** 单 Run 组合结果；raw 不经过重试，summary 不得换身份。 */
    record Opened(ModelGateway raw, ContextSummarizer summarizer,
            ModelProviderCapabilitySnapshot capabilities, CredentialLeaseRegistry.Lease lease,
            CredentialVersion.PiAuthEpoch authEpoch, java.util.OptionalLong contextWindowTokens) { }

    /** 测试替换创建，不改变逐回合凭证会话或租约策略；抛出前负责清理自己的部分资源。 */
    @FunctionalInterface
    interface GatewayFactory {
        ModelGateway create(PiWorkerConfiguration configuration, String provider, String model,
                Duration timeout, PiCredentialSessionFactory sessions);
    }

    /** 同步资源发布和撤销；关闭失败保留网关引用，交由租约保持失败状态。 */
    private static final class ResourceSlot implements AutoCloseable {
        private ModelGateway gateway;
        private boolean closed;
        @Override public synchronized void close() throws Exception {
            closed = true;
            if (gateway instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
