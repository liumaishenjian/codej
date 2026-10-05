package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialProfile;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialVersion;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.PiLoginOperation;
import io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import java.util.function.Supplier;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.SecretMaterial;
import io.github.liumaishenjian.ccjava.cli.auth.SecretRef;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.provider.probe.ProviderProbePort;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.model.ProviderAuthStatusCode;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CLI、stdio 与 TUI 共用的 Provider/Auth 本地应用服务。
 *
 * <p>服务是 store mutation 的唯一入口。list/status/models 全部零网络且不读取 secret value；login
 * 消费 {@link SecretInput}，logout 只删除本机 credential；model selection 用 CAS 更新默认值并以
 * last-known-good 进程快照决定下一 Run，active Run 时拒绝切换。本服务不创建或调用 ModelGateway。</p>
 *
 * <p>Pi 入口使用独立 Store、完整身份和非阻塞 Interaction；只共享进程内租约与确认票据池，
 * 认证操作不修改选择或默认值；显式模型选择另由 generation CAS 发布。
 * Pi 列表仅投影元数据，CONFIGURED_UNVERIFIED 不证明材料或账号有效。
 * Pi Worker 与用户提示从不在选择锁内等待，清理未确认时保留唯一登录槽及身份 fence。</p>
 */
public final class ProviderAuthApplicationService {
    /** CodeJ 首次连接向导隐藏使用的稳定 Provider ID。 */
    public static final String CODEJ_CUSTOM_PROVIDER_ID = "codej-custom";
    private final ProviderDefinitionStore definitions;
    private final CredentialStore credentials;
    private final LegacyCredentialMigrationService migration;
    private final Map<String, String> environment;
    private final AtomicReference<ProviderSelectionSnapshot> nextSelection = new AtomicReference<>();
    private final CredentialLeaseRegistry leases;
    private final ProviderProbePort probePort;
    private final java.time.Clock clock;
    private final AtomicBoolean runActive = new AtomicBoolean();
    private final Object selectionMonitor = new Object();
    private volatile boolean selectionRequired;
    private final Map<String, LogoutConfirmation> logoutConfirmations = new java.util.HashMap<>();
    private final PiCredentialStore piCredentials;
    private final Supplier<PiWorkerConfiguration> piConfiguration;
    private final PiOperationFactory piOperations;
    // Pi 短状态只在 selectionMonitor 内修改；Pi 提示、配置、Store 与 Worker 均在锁外运行。
    private PiLoginSlot piLogin;
    private boolean piControlBusy;
    private boolean authClosed;

    private record LogoutConfirmation(String provider, String profile, String session, long generation,
                                      long expires, Optional<PiCredentialIdentity> piIdentity) {
        private LogoutConfirmation(String provider, String profile, String session, long generation, long expires) {
            this(provider, profile, session, generation, expires, Optional.empty());
        }
    }

    /** 包级确定性测试接缝；生产装配始终委派真正的一次性 PiLoginOperation。 */
    interface PiOperation extends AutoCloseable {
        PiLoginOperation.Receipt run();
        @Override void close();
    }
    /** 只替换协调器，不向公共控制面开放任意 Worker 或秘密输入。 */
    @FunctionalInterface interface PiOperationFactory {
        PiOperation create(PiWorkerConfiguration configuration, PiCredentialStore store,
                PiCredentialIdentity identity, long expected, PiLoginOperation.Interaction interaction,
                CancellationToken cancellation, Duration timeout);
    }
    /**
     * 进程内唯一登录所有权：reserved → ready → finished；cleanupFailed 为不可逆状态。
     * ready/finished 只确认资源移交与关闭，不代表登录成功，迟到工厂仍由原槽负责清理。
     */
    private static final class PiLoginSlot {
        private final PiCredentialIdentity identity;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private PiOperation operation;
        private boolean stopping;
        private boolean cleanupFailed;
        private PiLoginSlot(PiCredentialIdentity identity) { this.identity = identity; }
    }

    /** 不含凭证值的退出确认投影；票据只能在原会话使用一次。
     * @param providerId 服务商标识
     * @param profileId 本地账号标识
     * @param confirmationId 有界时效的一次性确认标识
     */
    public record LogoutPreview(String providerId, String profileId, String confirmationId) { }

    /**
     * 创建使用独立 lease registry 的测试兼容控制面。
     *
     * @param definitions Provider 定义存储
     * @param credentials credential profile 存储
     * @param migration legacy credential 迁移服务
     * @param environment 用于解析 ENV credential 的环境变量快照
     */
    public ProviderAuthApplicationService(ProviderDefinitionStore definitions, CredentialStore credentials,
                                          LegacyCredentialMigrationService migration, Map<String, String> environment) {
        this(definitions, credentials, migration, environment, new CredentialLeaseRegistry(),
                (definition, model, secret, timeout, cancellation) -> ProviderProbePort.ProbeOutcome.UNSUPPORTED,
                java.time.Clock.systemUTC());
    }

    /**
     * 创建接入共享 lease registry 的生产控制面。
     *
     * @param definitions Provider 定义存储
     * @param credentials credential profile 存储
     * @param migration legacy credential 迁移服务
     * @param environment 用于解析 ENV credential 的环境变量快照
     * @param leases 跨 Run 共享的 credential lease registry
     */
    public ProviderAuthApplicationService(ProviderDefinitionStore definitions, CredentialStore credentials,
                                          LegacyCredentialMigrationService migration, Map<String, String> environment,
                                          CredentialLeaseRegistry leases) {
        this(definitions, credentials, migration, environment, leases,
                (definition, model, secret, timeout, cancellation) -> ProviderProbePort.ProbeOutcome.UNSUPPORTED,
                java.time.Clock.systemUTC());
    }

    /**
     * 创建接入受控 probe transport 与固定时钟的完整应用服务。
     *
     * @param definitions Provider 定义存储
     * @param credentials credential profile 存储
     * @param migration legacy credential 迁移服务
     * @param environment 用于解析 ENV credential 的环境变量快照
     * @param leases 跨 Run 共享的 credential lease registry
     * @param probePort 受控的 Provider 探测端口
     * @param clock 记录探测时间的时钟
     */
    public ProviderAuthApplicationService(ProviderDefinitionStore definitions, CredentialStore credentials,
                                          LegacyCredentialMigrationService migration, Map<String, String> environment,
                                          CredentialLeaseRegistry leases, ProviderProbePort probePort,
                                          java.time.Clock clock) {
        this(definitions, credentials, migration, environment, leases, probePort, clock, null, null);
    }

    /**
     * 装配明确隔离的 Pi 认证入口；旧构造器不具备 Pi 能力，绝不回退 legacy。
     * @param definitions 旧 Provider 定义
     * @param credentials 旧凭证存储
     * @param migration 显式旧配置迁移
     * @param environment 旧 ENV 解析快照
     * @param leases 两种后端共享但按完整身份隔离的进程内租约
     * @param probePort 旧受控探测端口
     * @param clock 票据时钟
     * @param piCredentials 独立 Pi 存储；兼容构造时为 null
     * @param piConfiguration 延迟解析的可信配置；列表不会调用它
     */
    public ProviderAuthApplicationService(ProviderDefinitionStore definitions, CredentialStore credentials,
            LegacyCredentialMigrationService migration, Map<String, String> environment,
            CredentialLeaseRegistry leases, ProviderProbePort probePort, java.time.Clock clock,
            PiCredentialStore piCredentials, Supplier<PiWorkerConfiguration> piConfiguration) {
        this(definitions, credentials, migration, environment, leases, probePort, clock,
                piCredentials, piConfiguration, (configuration, store, identity, expected, interaction, token, timeout) -> {
                    PiLoginOperation operation = new PiLoginOperation(configuration, store, identity, expected,
                            interaction, token, timeout);
                    return new PiOperation() {
                        public PiLoginOperation.Receipt run() { return operation.run(); }
                        public void close() { operation.close(); }
                    };
                });
    }

    ProviderAuthApplicationService(ProviderDefinitionStore definitions, CredentialStore credentials,
            LegacyCredentialMigrationService migration, Map<String, String> environment,
            CredentialLeaseRegistry leases, ProviderProbePort probePort, java.time.Clock clock,
            PiCredentialStore piCredentials, Supplier<PiWorkerConfiguration> piConfiguration,
            PiOperationFactory piOperations) {
        this.piCredentials = piCredentials;
        this.piConfiguration = piConfiguration;
        this.piOperations = Objects.requireNonNull(piOperations);
        this.definitions = Objects.requireNonNull(definitions);
        this.credentials = Objects.requireNonNull(credentials);
        this.migration = Objects.requireNonNull(migration);
        this.environment = Map.copyOf(Objects.requireNonNull(environment));
        this.leases = Objects.requireNonNull(leases);
        this.probePort = Objects.requireNonNull(probePort);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 稳定排序列出本地 Provider 摘要，不输出 URI 或 Header。
     *
     * @param cancellation 取消令牌
     * @return 按稳定顺序排列的 Provider 安全摘要
     */
    public List<ProviderSummary> listProviders(CancellationToken cancellation) {
        ProviderDefinitionStore.Snapshot snapshot = definitions.snapshot(cancellation);
        return snapshot.catalog().list().stream().map(value -> new ProviderSummary(value.providerId(),
                value.kind(), value.models().size(), value.defaultModelId(),
                snapshot.defaultSelection().filter(selected -> selected.backend().equals("spring-ai")
                        && selected.providerId().equals(value.providerId()))
                        .isPresent())).toList();
    }

    /**
     * 新增 custom compatible definition。
     *
     * @param definition 要新增的 Provider 定义
     * @param cancellation 取消令牌
     */
    public void addProvider(ProviderDefinition definition, CancellationToken cancellation) {
        ProviderDefinitionStore.Snapshot current = definitions.snapshot(cancellation);
        definitions.add(definition, current.generation(), cancellation);
    }

    /**
     * 以固定 OpenAI-compatible Chat Completions 契约新增自定义 Provider。
     *
     * <p>该入口供不可信 Surface 使用，因此 Surface 只能提供四个非秘密字段；协议类别、API 变体、
     * Header 与 timeout 均由应用服务固定。实际 ID、显示名、HTTPS URI、模型、重复定义、文件安全和
     * generation CAS 继续复用 {@link ProviderDefinition} 与 {@link #addProvider(ProviderDefinition, CancellationToken)}，
     * 不在 stdio/TUI 建立较弱校验。</p>
     *
     * @param request 自定义 Provider 的四字段请求
     * @param cancellation 取消令牌
     * @return 仅含安全展示字段的新增结果
     */
    public ProviderAddedSummary addCompatibleProvider(AddProviderRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request 不能为空");
        if (runActive.get()) throw failure(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT);
        ProviderDefinition definition = new ProviderDefinition(
                request.providerId(), ProviderDefinition.Kind.OPENAI_COMPATIBLE, request.displayName(),
                java.net.URI.create(request.baseUrl()), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                List.of(request.modelId()), request.modelId(), Map.of(),
                java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(300));
        addProvider(definition, cancellation);
        return new ProviderAddedSummary(
                definition.providerId(), definition.displayName(), definition.defaultModelId());
    }

    /**
     * 幂等保存 CodeJ 唯一的 OpenAI-compatible 自定义连接。
     *
     * <p>Surface 只提供 Base URL 与模型名；Provider/Profile/默认选择均由应用层固定。
     * 该方法不接收也不处理 API Key，secret 仍由独立的 masked login 进程保存。</p>
     *
     * @param request 首次连接表单中的非秘密字段
     * @param cancellation 取消令牌
     * @return 不含 endpoint 的安全结果
     */
    public ProviderAddedSummary configureCodejProvider(
            ConfigureProviderRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request 不能为空");
        if (runActive.get()) throw failure(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT);
        ProviderDefinition definition = new ProviderDefinition(
                CODEJ_CUSTOM_PROVIDER_ID, ProviderDefinition.Kind.OPENAI_COMPATIBLE, "CodeJ Custom",
                java.net.URI.create(request.baseUrl()), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                List.of(request.modelId()), request.modelId(), Map.of(),
                java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(300));
        ProviderDefinitionStore.Snapshot current = definitions.snapshot(cancellation);
        definitions.configure(definition, current.generation(), cancellation);
        return new ProviderAddedSummary(
                definition.providerId(), definition.displayName(), definition.defaultModelId());
    }

    /**
     * 判断下一次 Run 是否已有可用的默认模型与本机 credential。
     *
     * <p>检查完全离线，不读取 secret value；STORE 文件不存在或 ENV 值为空时返回 false，
     * 让首次启动重新进入连接表单。Pi 分支只证明精确身份元数据已配置且未被本进程撤销
     * （CONFIGURED_UNVERIFIED），不读取材料/ENV，也不证明账号或组件可用。</p>
     *
     * @param cancellation 读取本地状态时使用的取消令牌
     * @return legacy 本地可用或 Pi 精确身份元数据已配置且无 fence 时为 true
     */
    public boolean hasUsableDefaultSelection(CancellationToken cancellation) {
        Optional<ProviderDefinitionStore.DefaultSelection> selected =
                definitions.snapshot(cancellation).defaultSelection();
        if (selected.isEmpty()) return false;
        ProviderDefinitionStore.DefaultSelection value = selected.orElseThrow();
        if (value.backend().equals("pi")) {
            PiCredentialIdentity identity = piSelectionIdentity(value);
            return piCredentials != null && piCredentials.snapshot(cancellation).find(identity).isPresent()
                    && !leases.fenced(identity);
        }
        String providerId = value.providerId();
        return listProfiles(Optional.of(providerId), cancellation).stream()
                .anyMatch(profile -> profile.providerDefault()
                        && profile.status() == ProviderAuthStatusCode.AVAILABLE_LOCAL);
    }

    /**
     * 以当前 generation CAS 给 built-in Provider 增加模型 overlay。
     *
     * @param providerId Provider 标识
     * @param modelId 要增加的模型标识
     * @param cancellation 取消令牌
     */
    public void addModel(String providerId, String modelId, CancellationToken cancellation) {
        addModel(providerId, modelId, false, cancellation);
    }

    /**
     * 增加模型 overlay，并可在下一 generation CAS 中把它设为持久默认。
     *
     * @param providerId Provider 标识
     * @param modelId 要增加的模型标识
     * @param setDefault 是否同时设为持久默认模型
     * @param cancellation 取消令牌
     */
    public void addModel(String providerId, String modelId, boolean setDefault, CancellationToken cancellation) {
        ProviderDefinitionStore.Snapshot current = definitions.snapshot(cancellation);
        requireProvider(current.catalog(), providerId);
        ProviderDefinitionStore.Snapshot added = definitions.addModel(
                providerId, modelId, current.generation(), cancellation);
        if (setDefault) {
            definitions.selectDefault(Optional.of(new ProviderDefinitionStore.DefaultSelection(providerId, modelId)),
                    added.generation(), cancellation);
        }
    }

    /**
     * 以当前 generation CAS 从 built-in Provider 隐藏模型 overlay。
     *
     * @param providerId Provider 标识
     * @param modelId 要隐藏的模型标识
     * @param cancellation 取消令牌
     */
    public void removeModel(String providerId, String modelId, CancellationToken cancellation) {
        ProviderDefinitionStore.Snapshot current = definitions.snapshot(cancellation);
        requireProvider(current.catalog(), providerId);
        definitions.removeModel(providerId, modelId, current.generation(), cancellation);
    }

    /**
     * 删除没有默认选择和 credential 引用的 custom Provider。
     *
     * @param providerId 要删除的 Provider 标识
     * @param cancellation 取消令牌
     */
    public void removeProvider(String providerId, CancellationToken cancellation) {
        if (credentials.snapshot(cancellation).profiles().stream()
                .anyMatch(profile -> profile.providerId().equals(providerId))) throw conflict();
        ProviderDefinitionStore.Snapshot current = definitions.snapshot(cancellation);
        definitions.remove(providerId, current.generation(), cancellation);
    }

    /**
     * 保存 STORE/ENV profile；STORE secret 在调用返回前被消费并清零。
     *
     * @param request 不含 raw secret 的登录请求
     * @param input STORE 模式下提供 secret 的一次性输入；ENV 模式可为空
     * @param cancellation 取消令牌
     * @return 已保存 profile 的安全摘要
     */
    public ProfileSummary login(LoginRequest request, SecretInput input, CancellationToken cancellation) {
        requireProvider(definitions.snapshot(cancellation).catalog(), request.providerId());
        synchronized (selectionMonitor) {
            if (runActive.get() || piLogin != null || piControlBusy || authClosed) throw conflict();
        }
        // 必须在等待秘密/浏览器输入前取代次；另一进程退出后，迟到提交不能恢复磁盘凭证。
        long expected = credentials.snapshot(cancellation).generation();
        CredentialProfile saved;
        if (request.refKind() == RefKind.STORE) {
            try (SecretMaterial material = Objects.requireNonNull(input, "secret input 不能为空").read()) {
                synchronized (selectionMonitor) {
                    if (runActive.get() || piLogin != null || piControlBusy || authClosed) throw conflict();
                    saved = credentials.saveStore(request.providerId(), request.profileId(), material,
                            request.setDefault(), expected, cancellation);
                }
            }
        } else {
            synchronized (selectionMonitor) {
                if (runActive.get() || piLogin != null || piControlBusy || authClosed) throw conflict();
                saved = credentials.saveEnv(request.providerId(), request.profileId(), request.environmentName(),
                        request.setDefault(), expected, cancellation);
            }
        }
        return summarize(saved, request.setDefault(), localStatus(saved));
    }

    /**
     * 异步输入端口驱动一次 Pi 登录；总预算三百秒，输入前捕获索引 CAS。
     * 不设置默认、不切换下一 Run；ACK 后失败不代表持久提交已回滚。
     * @param identity 明确的 Pi 完整身份
     * @param interaction 非阻塞提示端口，不能替换为同步 SecretInput
     * @param cancellation 取消令牌
     * @return 仅表示已配置、未在线验证的安全摘要
     */
    public PiProfileSummary loginPi(PiCredentialIdentity identity, PiLoginOperation.Interaction interaction,
                                    CancellationToken cancellation) {
        return loginPiWithReceipt(identity, interaction, cancellation).summary();
    }

    /**
     * 返回原操作的精确存储回执，供私有认证 helper 桥使用；不从保存后的索引推测代次。
     * 清理或宿主关闭失败阻止成功返回，但不虚构已持久化材料的回滚。
     * @param identity 完整 Pi 身份
     * @param interaction 非阻塞输入端口
     * @param cancellation 调用方取消令牌
     * @return 安全摘要与仅供私有 helper 通道传递的精确回执
     */
    public PiLoginResult loginPiWithReceipt(PiCredentialIdentity identity, PiLoginOperation.Interaction interaction,
                                           CancellationToken cancellation) {
        Objects.requireNonNull(interaction);
        return coordinatePiLogin(identity, cancellation, expected -> piOperations.create(piConfiguration.get(),
                piCredentials, identity, expected, interaction, cancellation, Duration.ofSeconds(300)));
    }

    /**
     * 仅保存 API_KEY 身份的 ENV_REF 名称，不读取环境值、不解析 Pi 配置、不启动 Node。
     * 复用唯一登录槽及输入前 CAS；不修改模型选择或默认值。
     * @param identity API_KEY 完整身份，OAuth 被拒绝
     * @param environmentName 合法的 ENV_REF 名称
     * @param cancellation 调用方取消令牌
     * @return 经相同关闭 Gate 确认的摘要与实际提交回执
     */
    public PiLoginResult loginPiEnvironment(PiCredentialIdentity identity, String environmentName,
                                           CancellationToken cancellation) {
        requirePi(); Objects.requireNonNull(identity); Objects.requireNonNull(cancellation);
        if (identity.authMethod() != PiCredentialIdentity.AuthMethod.API_KEY)
            throw new IllegalArgumentException("PI_MATERIAL_INVALID");
        // 复用材料工厂的名称校验，不复制另一份 ENV 语法或权限边界。
        try (var ignored = io.github.liumaishenjian.ccjava.cli.auth.PiCredentialMaterial.envRef(environmentName)) { }
        return coordinatePiLogin(identity, cancellation,
                expected -> new PiEnvironmentOperation(identity, environmentName, expected, cancellation));
    }

    /** 所有登录共享所有权、提交验证、本进程激活和最终清理 Gate；创建器只替换操作资源。 */
    private PiLoginResult coordinatePiLogin(PiCredentialIdentity identity, CancellationToken cancellation,
                                            java.util.function.LongFunction<PiOperation> creator) {
        requirePi(); Objects.requireNonNull(identity); Objects.requireNonNull(cancellation);
        PiLoginSlot slot = new PiLoginSlot(identity);
        synchronized (selectionMonitor) {
            if (authClosed || piLogin != null || piControlBusy || runActive.get()) throw conflict();
            piLogin = slot;
        }
        try {
            try {
                long expected = piCredentials.snapshot(cancellation).generation();
                PiOperation operation = Objects.requireNonNull(creator.apply(expected));
                synchronized (selectionMonitor) { slot.operation = operation; }
            } finally { slot.ready.countDown(); }
            synchronized (selectionMonitor) { if (slot.stopping || slot.cleanupFailed) throw conflict(); }
            PiLoginOperation.Receipt receipt = slot.operation.run();
            if (!identity.equals(receipt.identity())) throw conflict();
            PiCredentialStore.Metadata current = requirePiEpoch(identity, receipt.authEpoch(), cancellation);
            synchronized (selectionMonitor) {
                if (slot.stopping || slot.cleanupFailed || authClosed || piControlBusy) throw conflict();
                leases.activateAfterLogin(identity, new CredentialVersion.PiAuthEpoch(receipt.authEpoch()));
            }
            return new PiLoginResult(summarizePi(current), receipt);
        } finally {
            try {
                if (slot.operation != null) slot.operation.close();
            } catch (RuntimeException | Error failure) {
                failPiCleanup(slot);
                throw piCleanupFailure();
            } finally {
                synchronized (selectionMonitor) {
                    if (!slot.cleanupFailed && piLogin == slot) piLogin = null;
                }
                slot.finished.countDown();
                synchronized (selectionMonitor) {
                    if (slot.cleanupFailed) throw piCleanupFailure();
                    // 槽位释放后的外部close同样必须参加最终回执判定。
                    if (slot.stopping || authClosed) throw conflict();
                }
            }
        }
    }

    /**
     * 本地 ENV 单次事务资源。状态锁只保护启动/关闭交接，不包围 Store IO；close 取消后
     * 有界等待实际 run 收敛，未确认时交给共享槽保存 sticky failure，不声称强制中断文件 IO。
     */
    private final class PiEnvironmentOperation implements PiOperation {
        private final PiCredentialIdentity identity;
        private final String environmentName;
        private final long expected;
        private final io.github.liumaishenjian.ccjava.core.CancellationSource source;
        private final CancellationToken.Registration registration;
        private final CountDownLatch completed = new CountDownLatch(1);
        private boolean started;
        private boolean closed;

        private PiEnvironmentOperation(PiCredentialIdentity identity, String environmentName, long expected,
                                       CancellationToken caller) {
            this.identity = identity; this.environmentName = environmentName; this.expected = expected;
            Duration budget = caller.remainingTime().filter(value -> value.compareTo(Duration.ofSeconds(300)) < 0)
                    .orElse(Duration.ofSeconds(300));
            if (budget.isZero() || budget.isNegative()) throw failure(ProviderAuthException.Code.AUTH_CANCELLED);
            source = new io.github.liumaishenjian.ccjava.core.CancellationSource(budget);
            registration = caller.onCancellation(source::cancel);
        }

        @Override public PiLoginOperation.Receipt run() {
            synchronized (this) {
                if (closed || started) throw conflict();
                started = true;
            }
            try (var material = io.github.liumaishenjian.ccjava.cli.auth.PiCredentialMaterial.envRef(environmentName)) {
                var saved = piCredentials.saveLogin(identity, material, expected, false, source.token());
                if (source.token().isCancellationRequested()
                        || source.token().remainingTime().filter(value -> value.isZero() || value.isNegative()).isPresent())
                    throw failure(ProviderAuthException.Code.AUTH_CANCELLED);
                return new PiLoginOperation.Receipt(identity, saved.authEpoch());
            } finally { completed.countDown(); }
        }

        @Override public void close() {
            synchronized (this) {
                closed = true;
                if (!started) completed.countDown();
            }
            source.cancel();
            try {
                if (!completed.await(10, TimeUnit.SECONDS)) throw piCleanupFailure();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw piCleanupFailure();
            } finally { registration.close(); }
        }
    }

    /**
     * 只读 Pi 元数据与进程 fence；不解析配置、不启动 Node、不读取秘密或 ENV 值。
     * @param provider 可选的精确 Pi 路由过滤器
     * @param cancellation 本地读取取消令牌
     * @return 不含代次、引用、环境名或账户 ID 的稳定排序摘要
     */
    public List<PiProfileSummary> listPiProfiles(Optional<String> provider, CancellationToken cancellation) {
        requirePi(); Objects.requireNonNull(provider);
        provider.ifPresent(value -> new PiProviderCatalog().require(value));
        return piCredentials.snapshot(cancellation).profiles().stream()
                .filter(value -> provider.isEmpty() || provider.orElseThrow().equals(value.identity().providerId()))
                .sorted(Comparator.comparing((PiCredentialStore.Metadata value) -> value.identity().providerId())
                        .thenComparing(value -> value.identity().profileId())
                        .thenComparing(value -> value.identity().authMethod().name()))
                .map(this::summarizePi).toList();
    }

    /**
     * 独立 helper 完成后显式激活精确 epoch；验证材料存在并立即擦除，不刷新、不设置默认。
     * @param identity 明确的 Pi 身份，不能用同名旧配置替代
     * @param expected helper 返回的确切登录代次
     * @param cancellation 本地校验取消令牌
     * @return 配置未在线验证的安全摘要
     */
    public PiProfileSummary activatePiLogin(PiCredentialIdentity identity, CredentialVersion.PiAuthEpoch expected,
                                           CancellationToken cancellation) {
        requirePi(); Objects.requireNonNull(identity); Objects.requireNonNull(expected);
        synchronized (selectionMonitor) {
            if (authClosed || piControlBusy || piLogin != null || runActive.get()) throw conflict();
            piControlBusy = true;
        }
        try {
            requirePiEpoch(identity, expected.value(), cancellation);
            // beginModify 内部读取并验证 typed 材料；不取得 snapshot 副本，不调用 finish/刷新。
            try (var ignored = piCredentials.beginModify(identity, expected.value(), cancellation)) { }
            PiCredentialStore.Metadata current = requirePiEpoch(identity, expected.value(), cancellation);
            synchronized (selectionMonitor) {
                if (authClosed) throw conflict();
                leases.activateAfterLogin(identity, expected);
            }
            return summarizePi(current);
        } finally { synchronized (selectionMonitor) { piControlBusy = false; } }
    }

    /**
     * 按当前精确登录代次退出 Pi 身份；只承诺本进程租约和跨进程 Store CAS。
     * @param identity 明确的 Pi 身份
     * @param cancellation 排空与删除取消令牌
     * @return 不表示远端 revoke 的本地结果
     */
    public LogoutResult logoutPi(PiCredentialIdentity identity, CancellationToken cancellation) {
        requirePi();
        Objects.requireNonNull(identity);
        var current = piCredentials.snapshot(cancellation).find(identity);
        // 首次登录尚未发布时也必须先阻止该本地操作；不存在的 epoch 不会获得删除回执。
        return logoutPiAtEpoch(identity, current.map(PiCredentialStore.Metadata::authEpoch).orElse(0L), cancellation);
    }

    private LogoutResult logoutPiAtEpoch(PiCredentialIdentity identity, long expected, CancellationToken cancellation) {
        requirePi();
        PiLoginSlot captured;
        synchronized (selectionMonitor) {
            if (authClosed || piControlBusy) throw conflict();
            piControlBusy = true;
            captured = piLogin != null && piLogin.identity.equals(identity) ? piLogin : null;
            if (captured != null) captured.stopping = true;
        }
        try {
            // 先发布 fence，零等待阶段的 false 不是排空证据；本地登录随后必须真正关闭。
            leases.fenceAndDrain(identity, Duration.ZERO, CancellationToken.none());
            if (captured != null) stopPiLogin(captured);
            if (!leases.fenceAndDrain(identity, Duration.ofSeconds(10), cancellation))
                throw failure(ProviderAuthException.Code.AUTH_LOGOUT_DRAIN_FAILED);
            long deleted = piCredentials.deleteWithReceipt(identity, expected, cancellation);
            leases.markRevoked(identity, deleted);
            return new LogoutResult(identity.providerId(), identity.profileId(), false);
        } finally { synchronized (selectionMonitor) { piControlBusy = false; } }
    }

    /**
     * 与旧入口共用十六张、两分钟、会话绑定的一次性票据池；backend 只来自受信票据。
     * @param identity 待退出的完整 Pi 身份
     * @param sessionIdentity 当前可信宿主会话
     * @param cancellation 元数据读取取消令牌
     * @return 不含材料和代次的确认投影
     */
    public LogoutPreview preparePiLogout(PiCredentialIdentity identity, String sessionIdentity,
                                         CancellationToken cancellation) {
        requirePi(); Objects.requireNonNull(sessionIdentity);
        var current = piCredentials.snapshot(cancellation).find(identity)
                .orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
        synchronized (selectionMonitor) {
            logoutConfirmations.entrySet().removeIf(entry -> entry.getValue().expires() <= clock.millis());
            if (logoutConfirmations.size() >= 16) throw conflict();
            String id = java.util.UUID.randomUUID().toString();
            logoutConfirmations.put(id, new LogoutConfirmation(identity.providerId(), identity.profileId(),
                    sessionIdentity, current.authEpoch(), clock.millis() + 120_000, Optional.of(identity)));
            return new LogoutPreview(identity.providerId(), identity.profileId(), id);
        }
    }

    /**
     * 宿主永久关闭认证入口并确认当前登录资源收敛；失败 sticky，不能释放槽或吞掉异常。
     * 不在 selectionMonitor 中等待输入、配置工厂或 Worker；重复关闭仍报告未确认清理。
     */
    public void closePiLogin() {
        PiLoginSlot captured;
        synchronized (selectionMonitor) {
            authClosed = true;
            captured = piLogin;
            if (captured != null) captured.stopping = true;
        }
        if (captured != null) stopPiLogin(captured);
    }

    private void stopPiLogin(PiLoginSlot slot) {
        try {
            if (!slot.ready.await(2, TimeUnit.SECONDS)) throw piCleanupFailure();
            PiOperation operation;
            synchronized (selectionMonitor) { operation = slot.operation; }
            if (operation != null) operation.close();
            if (!slot.finished.await(10, TimeUnit.SECONDS)) throw piCleanupFailure();
            synchronized (selectionMonitor) { if (slot.cleanupFailed) throw piCleanupFailure(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); failPiCleanup(slot); throw piCleanupFailure();
        } catch (RuntimeException | Error failure) {
            failPiCleanup(slot); throw piCleanupFailure();
        }
    }

    private void failPiCleanup(PiLoginSlot slot) {
        synchronized (selectionMonitor) {
            slot.cleanupFailed = true; slot.stopping = true;
            // 外部 close 与 run finally 竞争时，也不能遗失失败资源的所有权。
            if (piLogin == null) piLogin = slot;
        }
        leases.fenceAndDrain(slot.identity, Duration.ZERO, CancellationToken.none());
    }
    private static PiWorkerException piCleanupFailure() {
        return new PiWorkerException(PiWorkerException.Code.CLEANUP_FAILED);
    }
    /**
     * 只查询已装配的Pi控制端口，不读取配置、凭据或文件，也不表示Worker已安装。
     * @return 宿主是否能够协商Pi控制协议
     */
    public boolean supportsPiControl() { return piCredentials != null && piConfiguration != null; }

    /**
     * 验证可信Worker路径配置的可用性，不启动进程、不解析凭据、不进行联网探测。
     * @return 配置不可用时为false，不触发legacy fallback
     */
    public boolean piComponentAvailable() {
        if (!supportsPiControl()) return false;
        try { return piConfiguration.get() != null; }
        catch (RuntimeException unavailable) { return false; }
    }

    private void requirePi() {
        if (piCredentials == null || piConfiguration == null)
            throw new PiWorkerException(PiWorkerException.Code.CONFIGURATION_INVALID);
    }
    private PiCredentialStore.Metadata requirePiEpoch(PiCredentialIdentity identity, long expected,
                                                       CancellationToken cancellation) {
        var current = piCredentials.snapshot(cancellation).find(identity).orElseThrow(ProviderAuthApplicationService::conflict);
        if (current.authEpoch() != expected) throw conflict();
        return current;
    }
    private PiProfileSummary summarizePi(PiCredentialStore.Metadata metadata) {
        PiCredentialIdentity identity = metadata.identity();
        return new PiProfileSummary(identity.backend(), identity.providerId(), identity.profileId(),
                identity.authMethod().name(), metadata.kind().name(),
                leases.fenced(identity) ? "REVOKED_IN_PROCESS" : "CONFIGURED_UNVERIFIED");
    }

    /**
     * Pi 安全投影：元数据存在不等于密钥或账号已验证；不包含任何内部引用。
     * @param backend 固定 pi
     * @param providerId 公开路由 ID
     * @param profileId 本地 profile ID
     * @param authMethod API_KEY 或 OAUTH
     * @param refKind API_KEY、ENV_REF 或 OAUTH 材料种类
     * @param status CONFIGURED_UNVERIFIED 或 REVOKED_IN_PROCESS
     */
    public record PiProfileSummary(String backend, String providerId, String profileId, String authMethod,
                                   String refKind, String status) { }

    /**
     * 登录成功的双投影：summary 不含版本；receipt 仅供私有 helper 通道和精确激活使用，
     * 不得整体序列化到普通控制面、Session 或日志。身份不一致时拒绝构造。
     * @param summary 不含代次、环境名或秘密的安全摘要
     * @param receipt 原操作返回的确切回执，不是事后快照推测值
     */
    public record PiLoginResult(PiProfileSummary summary, PiLoginOperation.Receipt receipt) {
        /**
         * 保留原回执对象并校验双投影的完整身份，拒绝拼接不同登录的摘要和回执。
         * @param summary 非空安全摘要
         * @param receipt 非空原始存储回执
         */
        public PiLoginResult {
            Objects.requireNonNull(summary); Objects.requireNonNull(receipt);
            PiCredentialIdentity identity = receipt.identity();
            if (!identity.backend().equals(summary.backend()) || !identity.providerId().equals(summary.providerId())
                    || !identity.profileId().equals(summary.profileId())
                    || !identity.authMethod().name().equals(summary.authMethod())) throw conflict();
        }
        @Override public String toString() { return "PiLoginResult[summary=" + summary + "]"; }
    }

    /**
     * 稳定排序列出 profile metadata；不读取 secret value。
     *
     * @param provider 可选的 Provider 标识过滤条件
     * @param cancellation 取消令牌
     * @return 按 Provider 和 profile 稳定排序的安全摘要
     */
    public List<ProfileSummary> listProfiles(Optional<String> provider, CancellationToken cancellation) {
        provider.ifPresent(providerId -> requireProvider(definitions.snapshot(cancellation).catalog(), providerId));
        CredentialStore.Snapshot snapshot = credentials.snapshot(cancellation);
        return snapshot.profiles().stream()
                .filter(value -> provider.isEmpty() || provider.orElseThrow().equals(value.providerId()))
                .sorted(Comparator.comparing(CredentialProfile::providerId).thenComparing(CredentialProfile::profileId))
                .map(value -> summarize(value,
                        value.profileId().equals(snapshot.providerDefaults().get(value.providerId())), localStatus(value)))
                .toList();
    }

    /**
     * 返回单个 profile 本机状态；零网络且 ENV value 不缓存。
     *
     * @param providerId Provider 标识
     * @param profileId profile 标识
     * @param cancellation 取消令牌
     * @return profile 的本机安全状态摘要
     */
    public ProfileSummary status(String providerId, String profileId, CancellationToken cancellation) {
        requireProvider(definitions.snapshot(cancellation).catalog(), providerId);
        CredentialStore.Snapshot snapshot = credentials.snapshot(cancellation);
        CredentialProfile profile = snapshot.find(providerId, profileId)
                .orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
        return summarize(profile, profileId.equals(snapshot.providerDefaults().get(providerId)), localStatus(profile));
    }

    /**
     * 显式探测一个确定 profile/model，恰好一次调用受控 transport，并只保存 privacy-safe metadata。
     *
     * <p>失败状态同样保存稳定 code，随后以结构化错误返回；不保存 endpoint、body、request ID、异常或
     * secret fingerprint。probe 与 logout/replace 竞争时以 SecretRef identity CAS 阻止写到新 credential。</p>
     *
     * @param request 明确指定 profile、模型和超时的探测请求
     * @param cancellation 取消令牌
     * @return 探测成功时的隐私安全结果
     */
    public ProbeResult probe(ProbeRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request); Objects.requireNonNull(cancellation);
        ProviderDefinition definition = requireProvider(definitions.snapshot(cancellation).catalog(), request.providerId());
        if (!definition.models().contains(request.modelId())) throw failure(ProviderAuthException.Code.MODEL_UNKNOWN);
        CredentialStore.Snapshot snapshot = credentials.snapshot(cancellation);
        CredentialProfile profile = snapshot.find(request.providerId(), request.profileId())
                .orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
        io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver resolver =
                new io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver(credentials, environment);
        ProviderProbePort.ProbeOutcome outcome;
        var source = new io.github.liumaishenjian.ccjava.core.CancellationSource(request.timeout());
        try (var registration = cancellation.onCancellation(source::cancel);
             var lease = leases.acquire(request.providerId(), request.profileId(), snapshot.generation(), source::cancel);
             var resolved = resolver.resolve(request.providerId(), Optional.of(request.profileId()), source.token())) {
            lease.bindCancellation(source::cancel);
            if (source.token().isCancellationRequested()) throw conflict();
            char[] secret = resolved.secret().copyChars();
            try { outcome = probePort.probe(definition, request.modelId(), secret, request.timeout(), source.token()); }
            finally { java.util.Arrays.fill(secret, '\0'); }
        }
        java.time.Instant now = clock.instant();
        CredentialProfile.ProbeRecord record = new CredentialProfile.ProbeRecord(
                outcome.name(), now, definitionDigest(definition), request.modelId());
        credentials.saveProbe(request.providerId(), request.profileId(), record, profile.secretRef(), snapshot.generation(), cancellation);
        ProbeResult result = new ProbeResult(request.providerId(), request.profileId(), request.modelId(), outcome, now);
        if (outcome != ProviderProbePort.ProbeOutcome.SUCCESS) throw probeFailure(outcome);
        return result;
    }

    /**
     * fence、drain 后删除本机 profile；远端 revoke 始终不在本方法范围内。
     *
     * @param providerId Provider 标识
     * @param profileId 要删除的 profile 标识
     * @param cancellation 取消令牌
     * @return 仅描述本机删除结果的安全摘要
     */
    public LogoutResult logout(String providerId, String profileId, CancellationToken cancellation) {
        return logoutAtGeneration(providerId, profileId, credentials.snapshot(cancellation).generation(), cancellation);
    }

    private LogoutResult logoutAtGeneration(String providerId, String profileId, long expected,
                                             CancellationToken cancellation) {
        requireProvider(definitions.snapshot(cancellation).catalog(), providerId);
        CredentialStore.Snapshot snapshot = credentials.snapshot(cancellation);
        if (snapshot.generation() != expected) throw conflict();
        snapshot.find(providerId, profileId).orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
        selectionRequired = true;
        if (!leases.fenceAndDrain(providerId, profileId, java.time.Duration.ofSeconds(10), cancellation)) {
            throw failure(ProviderAuthException.Code.AUTH_LOGOUT_DRAIN_FAILED);
        }
        try {
            credentials.delete(providerId, profileId, snapshot.generation(), cancellation);
            leases.markRevoked(providerId, profileId, snapshot.generation() + 1);
        } catch (ProviderAuthException storeFailure) {
            if (storeFailure.code() == ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT) {
                throw new ProviderAuthException(ProviderAuthException.Code.AUTH_STORE_DELETE_FAILED,
                        ProviderAuthException.Action.CHECK_LOCAL_STORE, true);
            }
            throw storeFailure;
        }
        nextSelection.updateAndGet(value -> value != null && value.providerId().equals(providerId)
                && value.profileId().equals(profileId) ? null : value);
        return new LogoutResult(providerId, profileId, false);
    }

    /** 准备与展示时凭证索引绑定的退出确认，不读取secret、不执行删除。
     * @param providerId 服务商标识
     * @param profileId 待退出账号
     * @param sessionIdentity 当前可信宿主会话身份
     * @param cancellation 取消信号
     * @return 两分钟内有效的一次性确认投影
     */
    public LogoutPreview prepareLogout(String providerId, String profileId, String sessionIdentity,
                                       CancellationToken cancellation) {
        Objects.requireNonNull(sessionIdentity);
        CredentialStore.Snapshot snapshot = credentials.snapshot(cancellation);
        snapshot.find(providerId, profileId).orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
        synchronized (selectionMonitor) {
            logoutConfirmations.entrySet().removeIf(entry -> entry.getValue().expires() <= clock.millis());
            if (logoutConfirmations.size() >= 16) throw conflict();
            String id = java.util.UUID.randomUUID().toString();
            logoutConfirmations.put(id, new LogoutConfirmation(providerId, profileId, sessionIdentity,
                    snapshot.generation(), clock.millis() + 120_000));
            return new LogoutPreview(providerId, profileId, id);
        }
    }

    /** 消费原会话确认，按展示时的索引CAS删除；目标被替换时必须重新确认。
     * @param confirmationId 原确认标识
     * @param sessionIdentity 当前可信宿主会话身份
     * @param cancellation 取消信号
     * @return 本地退出结果，不表示远端撤销
     */
    public LogoutResult commitLogout(String confirmationId, String sessionIdentity, CancellationToken cancellation) {
        LogoutConfirmation confirmation;
        synchronized (selectionMonitor) {
            confirmation = logoutConfirmations.remove(confirmationId);
        }
        if (confirmation == null || !confirmation.session().equals(sessionIdentity)
                || confirmation.expires() <= clock.millis()) throw conflict();
        if (confirmation.piIdentity().isPresent()) {
            return logoutPiAtEpoch(confirmation.piIdentity().orElseThrow(), confirmation.generation(), cancellation);
        }
        return logoutAtGeneration(confirmation.provider(), confirmation.profile(), confirmation.generation(), cancellation);
    }

    /** 独立认证进程提交后，由当前宿主显式验证新凭证并恢复可用代次；list/status绝不隐式执行此动作。
     * @param providerId 服务商标识
     * @param profileId 明确登录的本地账号
     * @param cancellation 取消信号
     * @return 本机安全状态；不代表在线验证成功
     */
    public ProfileSummary activateLogin(String providerId, String profileId, CancellationToken cancellation) {
        synchronized (selectionMonitor) {
            if (runActive.get()) throw conflict();
            requireProvider(definitions.snapshot(cancellation).catalog(), providerId);
            CredentialStore.Snapshot snapshot = credentials.snapshot(cancellation);
            CredentialProfile profile = snapshot.find(providerId, profileId)
                    .orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
            if (profile.secretRef() instanceof SecretRef.Store store && !credentials.secretExists(store, cancellation)) {
                throw failure(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE);
            }
            if (profile.secretRef() instanceof SecretRef.Env env
                    && (environment.get(env.variableName()) == null || environment.get(env.variableName()).isBlank())) {
                throw failure(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE);
            }
            leases.activateAfterLogin(providerId, profileId, snapshot.generation());
            return summarize(profile, profileId.equals(snapshot.providerDefaults().get(providerId)), localStatus(profile));
        }
    }

    /**
     * 本地列出模型；不发 DNS/HTTP/ModelGateway 请求。
     *
     * @param providerId 可选的 Provider 标识过滤条件
     * @param cancellation 取消令牌
     * @return 本地模型安全摘要列表
     */
    public List<ModelSummary> listModels(Optional<String> providerId, CancellationToken cancellation) {
        ProviderCatalog catalog = definitions.snapshot(cancellation).catalog();
        providerId.ifPresent(value -> requireProvider(catalog, value));
        return catalog.list().stream().filter(value -> providerId.isEmpty()
                        || providerId.orElseThrow().equals(value.providerId()))
                .flatMap(value -> value.models().stream().map(model -> new ModelSummary(value.providerId(), model,
                        model.equals(value.defaultModelId())))).toList();
    }

    /**
     * 设置下一 Run 的选择；失败保持 last-known-good，active Run 时拒绝切换。
     *
     * @param request 模型、profile 与持久默认选项
     * @param cancellation 取消令牌
     * @return 已生效的下一 Run 选择快照
     */
    public ProviderSelectionSnapshot selectModel(ModelSelectionRequest request, CancellationToken cancellation) {
        if (runActive.get()) throw failure(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT);
        ProviderDefinitionStore.Snapshot providerSnapshot = definitions.snapshot(cancellation);
        ProviderDefinition definition = requireProvider(providerSnapshot.catalog(), request.providerId());
        if (!definition.models().contains(request.modelId())) {
            throw failure(ProviderAuthException.Code.MODEL_UNKNOWN);
        }
        CredentialStore.Snapshot credentialSnapshot = credentials.snapshot(cancellation);
        String profileId = request.profileId().orElseGet(() -> {
            String value = credentialSnapshot.providerDefaults().get(request.providerId());
            if (value == null) throw failure(ProviderAuthException.Code.AUTH_PROFILE_REQUIRED);
            return value;
        });
        credentialSnapshot.find(request.providerId(), profileId)
                .orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
        ProviderSelectionSnapshot selected = new ProviderSelectionSnapshot(
                request.providerId(), profileId, request.modelId());
        synchronized (selectionMonitor) {
            if (runActive.get()) throw conflict();
            if (request.setDefault()) {
                definitions.selectDefault(Optional.of(new ProviderDefinitionStore.DefaultSelection(
                        request.providerId(), request.modelId())), providerSnapshot.generation(), cancellation);
            }
            selectionRequired = true;
            nextSelection.set(selected);
        }
        return selected;
    }

    /**
     * 选择下一 Run 的 Pi 完整身份；仅检查目录、元数据和进程 fence，不激活账号。
     *
     * <p>DefinitionStore generation 仅用于持久默认 CAS，不与 authEpoch 或材料版本比较。
     * CAS 成功后才更新 LKG；Run 打开时由路由工厂另行固定认证租约。这里不启动 Node、
     * 不读取材料/ENV，也不修改 PiStore 默认。元数据存在只表示配置未验证。</p>
     *
     * @param identity 明确的 Pi 认证身份
     * @param modelId 静态目录中的精确模型
     * @param setDefault 是否持久化为默认选择
     * @param cancellation 本地存储等待的取消令牌
     * @return 下一 Run 的五字段选择，不影响当前 Run
     */
    public ProviderSelectionSnapshot selectPiModel(PiCredentialIdentity identity, String modelId,
            boolean setDefault, CancellationToken cancellation) {
        ProviderDefinitionStore.Snapshot snapshot = definitions.snapshot(cancellation);
        if (identity == null) throw failure(ProviderAuthException.Code.PROVIDER_DEFINITION_INVALID);
        var value = new ProviderDefinitionStore.DefaultSelection(identity.providerId(), modelId,
                identity.backend(), identity.authMethod().name(), Optional.of(identity.profileId()));
        requirePiSelection(value, cancellation);
        ProviderSelectionSnapshot selected = new ProviderSelectionSnapshot(identity.providerId(), identity.profileId(),
                modelId, identity.backend(), identity.authMethod().name());
        synchronized (selectionMonitor) {
            if (runActive.get() || authClosed || piLogin != null || piControlBusy) throw conflict();
            if (leases.fenced(identity)) throw conflict();
            if (setDefault) definitions.selectDefault(Optional.of(value), snapshot.generation(), cancellation);
            selectionRequired = true;
            nextSelection.set(selected);
        }
        return selected;
    }

    private static PiCredentialIdentity piSelectionIdentity(ProviderDefinitionStore.DefaultSelection value) {
        try {
            return new PiCredentialIdentity(value.backend(), value.providerId(),
                    PiCredentialIdentity.AuthMethod.valueOf(value.authMethod()), value.profileId().orElseThrow());
        } catch (RuntimeException invalid) {
            throw failure(ProviderAuthException.Code.PROVIDER_DEFINITION_INVALID);
        }
    }

    private ProviderSelectionSnapshot requirePiSelection(ProviderDefinitionStore.DefaultSelection value,
                                                          CancellationToken cancellation) {
        PiCredentialIdentity identity = piSelectionIdentity(value);
        if (piCredentials == null || piCredentials.snapshot(cancellation).find(identity).isEmpty()) {
            throw failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN);
        }
        if (leases.fenced(identity)) throw conflict();
        return new ProviderSelectionSnapshot(value.providerId(), identity.profileId(), value.modelId(),
                value.backend(), value.authMethod());
    }

    /**
     * 显式执行 legacy 文件复制迁移。
     *
     * @param providerId 迁移目标 Provider 标识
     * @param profileId 迁移后使用的 profile 标识
     * @param setDefault 是否将迁移后的 profile 设为 Provider 默认项
     * @param cancellation 取消令牌
     * @return 迁移结果及其安全摘要
     */
    public LegacyCredentialMigrationService.MigrationResult migrateLegacy(
            String providerId, String profileId, boolean setDefault, CancellationToken cancellation) {
        return migration.migrate(providerId, profileId, setDefault, cancellation);
    }

    /**
     * 标记 active Run；成功后 selection 在该 Run 生命周期内固定。
     *
     * @return 持有本次 Run 固定选择及 active fence 的作用域
     */
    public RunSelection beginRun() {
        synchronized (selectionMonitor) {
            if (authClosed || piLogin != null || piControlBusy) throw conflict();
            if (!runActive.compareAndSet(false, true)) throw new IllegalStateException("RUN_ACTIVE");
            ProviderSelectionSnapshot captured = nextSelection.get();
            return new RunSelection(captured, runActive);
        }
    }

    /**
     * 返回下一 Run 的 LKG 选择。
     *
     * @return 当前内存中的下一 Run 选择；尚未选择时为空
     */
    public Optional<ProviderSelectionSnapshot> nextSelection() { return Optional.ofNullable(nextSelection.get()); }

    /**
     * 按内存 LKG→用户默认的顺序确定下一 Run；只读本地 store，不读取 secret。
     *
     * @return 可供下一 Run 使用的有效选择；本地尚无完整选择时为空
     */
    public Optional<ProviderSelectionSnapshot> effectiveSelection() {
        return effectiveSelection(CancellationToken.none());
    }

    /**
     * 在同一启动预算内读取LKG或持久默认选择，不读取Pi秘密材料。
     * @param cancellation 贯穿默认定义、账号元数据与存在性检查的取消边界
     * @return 固定选择，或从未配置时的empty
     */
    public Optional<ProviderSelectionSnapshot> effectiveSelection(CancellationToken cancellation) {
        checkSelectionCancellation(cancellation);
        ProviderSelectionSnapshot selected = nextSelection.get();
        if (selected != null) return Optional.of(selected);
        var definitionSnapshot = definitions.snapshot(cancellation);
        checkSelectionCancellation(cancellation);
        return definitionSnapshot.defaultSelection().flatMap(value -> {
            if (value.backend().equals("pi")) {
                var piSelection = requirePiSelection(value, cancellation);
                checkSelectionCancellation(cancellation);
                return Optional.of(piSelection);
            }
            var credentialSnapshot = credentials.snapshot(cancellation);
            checkSelectionCancellation(cancellation);
            String profile = credentialSnapshot.providerDefaults().get(value.providerId());
            if (profile == null) return Optional.empty();
            CredentialProfile credential = credentialSnapshot.find(value.providerId(), profile)
                    .orElseThrow(() -> failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
            ProviderAuthStatusCode status = localStatus(credential, cancellation);
            if (status != ProviderAuthStatusCode.AVAILABLE_LOCAL) {
                throw switch (status) {
                    case MISSING_SECRET -> failure(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE);
                    case INSECURE_STORE -> failure(ProviderAuthException.Code.AUTH_STORE_INSECURE);
                    case CORRUPT_STORE -> failure(ProviderAuthException.Code.AUTH_STORE_CORRUPT);
                    case REVOKED_IN_PROCESS -> failure(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT);
                    default -> failure(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE);
                };
            }
            return Optional.of(new ProviderSelectionSnapshot(value.providerId(), profile, value.modelId()));
        });
    }
    /** 请求路径使用的严格路由选择；显式配置/退出之后缺失账号不得回退启动时legacy。
     * @return 从未选择时允许为空，否则返回有效选择或抛出类型化认证错误
     */
    public Optional<ProviderSelectionSnapshot> routingSelection() {
        return routingSelection(CancellationToken.none());
    }

    /**
     * 严格且可取消的生产选择；任何异常都不能换成启动legacy。
     * @param cancellation 启动期取消边界
     * @return 尚未配置时可为空，否则为选择或类型化错误
     */
    public Optional<ProviderSelectionSnapshot> routingSelection(CancellationToken cancellation) {
        Optional<ProviderSelectionSnapshot> selected = effectiveSelection(cancellation);
        checkSelectionCancellation(cancellation);
        if (selected.isEmpty() && selectionRequired) throw failure(ProviderAuthException.Code.AUTH_PROFILE_REQUIRED);
        return selected;
    }

    private static void checkSelectionCancellation(CancellationToken cancellation) {
        Objects.requireNonNull(cancellation);
        if (cancellation.isCancellationRequested()
                || cancellation.remainingTime().map(time -> time.isZero() || time.isNegative()).orElse(false)) {
            throw failure(ProviderAuthException.Code.AUTH_CANCELLED);
        }
    }

    private ProviderAuthStatusCode localStatus(CredentialProfile profile) {
        return localStatus(profile, CancellationToken.none());
    }

    private ProviderAuthStatusCode localStatus(CredentialProfile profile, CancellationToken cancellation) {
        checkSelectionCancellation(cancellation);
        if (leases.fenced(profile.providerId(), profile.profileId())) {
            return ProviderAuthStatusCode.REVOKED_IN_PROCESS;
        }
        if (profile.secretRef() instanceof SecretRef.Env env) {
            String value = environment.get(env.variableName());
            return value == null || value.isBlank() ? ProviderAuthStatusCode.MISSING_SECRET
                    : ProviderAuthStatusCode.AVAILABLE_LOCAL;
        }
        try {
            boolean exists = credentials.secretExists((SecretRef.Store) profile.secretRef(), cancellation);
            checkSelectionCancellation(cancellation);
            return exists ? ProviderAuthStatusCode.AVAILABLE_LOCAL : ProviderAuthStatusCode.MISSING_SECRET;
        } catch (ProviderAuthException failure) {
            if (failure.code() == ProviderAuthException.Code.AUTH_CANCELLED) throw failure;
            checkSelectionCancellation(cancellation);
            return failure.code() == ProviderAuthException.Code.AUTH_STORE_INSECURE
                    ? ProviderAuthStatusCode.INSECURE_STORE : ProviderAuthStatusCode.CORRUPT_STORE;
        }
    }

    private static ProfileSummary summarize(CredentialProfile value, boolean providerDefault,
                                            ProviderAuthStatusCode status) {
        String refKind = value.secretRef() instanceof SecretRef.Store ? "STORE" : "ENV";
        return new ProfileSummary(value.providerId(), value.profileId(), value.authMethod(), refKind, status,
                providerDefault, value.lastProbe().map(CredentialProfile.ProbeRecord::code),
                value.lastProbe().map(CredentialProfile.ProbeRecord::probedAt));
    }
    private static String definitionDigest(ProviderDefinition definition) {
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            String canonical=definition.providerId()+"\n"+definition.kind()+"\n"+definition.baseUri()+"\n"
                    +definition.apiVariant()+"\n"+String.join("\n",definition.models());
            return java.util.HexFormat.of().formatHex(digest.digest(
                    canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
    private static ProviderAuthException probeFailure(ProviderProbePort.ProbeOutcome outcome) {
        ProviderAuthException.Code code=switch(outcome) {
            case REJECTED -> ProviderAuthException.Code.AUTH_PROBE_REJECTED;
            case RATE_LIMITED -> ProviderAuthException.Code.AUTH_PROBE_RATE_LIMITED;
            case UNSUPPORTED -> ProviderAuthException.Code.AUTH_PROBE_UNSUPPORTED;
            case UNREACHABLE -> ProviderAuthException.Code.AUTH_PROBE_UNREACHABLE;
            case TIMED_OUT -> ProviderAuthException.Code.AUTH_PROBE_TIMED_OUT;
            case CANCELLED -> ProviderAuthException.Code.AUTH_CANCELLED;
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS 不是失败");
        };
        return new ProviderAuthException(code, outcome==ProviderProbePort.ProbeOutcome.REJECTED
                ? ProviderAuthException.Action.ROTATE_AT_PROVIDER : ProviderAuthException.Action.RETRY,
                outcome!=ProviderProbePort.ProbeOutcome.REJECTED && outcome!=ProviderProbePort.ProbeOutcome.UNSUPPORTED);
    }
    private static ProviderDefinition requireProvider(ProviderCatalog catalog, String providerId) {
        try {
            return catalog.require(providerId);
        } catch (IllegalArgumentException unknown) {
            throw failure(ProviderAuthException.Code.PROVIDER_UNKNOWN);
        }
    }
    private static ProviderAuthException conflict() {
        return failure(ProviderAuthException.Code.AUTH_PROFILE_CONFLICT);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code code) {
        return new ProviderAuthException(code, ProviderAuthException.Action.NONE, false);
    }

    /** secret ingress；实现必须只返回一次并转移可清零数组所有权。 */
    @FunctionalInterface public interface SecretInput {
        /**
         * 读取一次性 secret material，并将其所有权转移给调用方。
         *
         * @return 可在使用后清零并关闭的 secret material
         */
        SecretMaterial read();
    }
    /** credential 引用类型。 */
    public enum RefKind {
        /** secret 由本地受限 credential store 持有。 */
        STORE,
        /** secret 在使用时从环境变量解析。 */
        ENV
    }
    /**
     * 不可信 Surface 新增 compatible Provider 的最小请求。
     *
     * @param providerId 稳定 Provider 标识
     * @param displayName 用户可见名称
     * @param baseUrl absolute HTTPS 服务根地址
     * @param modelId 初始且默认的模型标识
     */
    public record AddProviderRequest(String providerId, String displayName, String baseUrl, String modelId) {
        /** 只做空值所有权检查；完整字段约束由 ProviderDefinition 权威执行。 */
        public AddProviderRequest {
            Objects.requireNonNull(providerId, "providerId 不能为空");
            Objects.requireNonNull(displayName, "displayName 不能为空");
            Objects.requireNonNull(baseUrl, "baseUrl 不能为空");
            Objects.requireNonNull(modelId, "modelId 不能为空");
        }
    }

    /**
     * CodeJ 首次连接表单的最小非秘密输入。
     *
     * @param baseUrl OpenAI-compatible absolute HTTPS 服务根地址
     * @param modelId 默认模型标识
     */
    public record ConfigureProviderRequest(String baseUrl, String modelId) {
        /** 完整 URL 与模型约束继续由 ProviderDefinition 权威校验。 */
        public ConfigureProviderRequest {
            Objects.requireNonNull(baseUrl, "baseUrl 不能为空");
            Objects.requireNonNull(modelId, "modelId 不能为空");
        }
    }

    /**
     * 新增 Provider 的非秘密安全投影。
     *
     * @param providerId 已保存的稳定 Provider 标识
     * @param displayName 已保存的用户可见名称
     * @param modelId 已保存的初始默认模型
     */
    public record ProviderAddedSummary(String providerId, String displayName, String modelId) { }

    /**
     * login 输入；禁止携带 raw secret。
     *
     * @param providerId Provider 标识
     * @param profileId profile 标识
     * @param refKind credential 引用类型
     * @param environmentName ENV 模式下的环境变量名
     * @param setDefault 是否设为 Provider 默认 profile
     */
    public record LoginRequest(String providerId, String profileId, RefKind refKind,
                               String environmentName, boolean setDefault) {
        /** 校验 ENV/STORE 参数互斥。 */
        public LoginRequest {
            Objects.requireNonNull(providerId); Objects.requireNonNull(profileId); Objects.requireNonNull(refKind);
            if (refKind == RefKind.ENV) new SecretRef.Env(environmentName);
            else if (environmentName != null) throw new IllegalArgumentException("STORE 不接受 environmentName");
        }
    }
    /**
     * model selection 输入。
     *
     * @param providerId Provider 标识
     * @param modelId 模型标识
     * @param profileId 可选的 profile 标识
     * @param setDefault 是否设为持久默认模型
     */
    public record ModelSelectionRequest(String providerId, String modelId, Optional<String> profileId,
                                        boolean setDefault) {
        /** 防御性校验 Optional。 */ public ModelSelectionRequest { Objects.requireNonNull(profileId); }
    }
    /**
     * Provider list 安全摘要。
     *
     * @param providerId Provider 标识
     * @param kind Provider 类型
     * @param modelCount 模型数量
     * @param defaultModelId Provider 默认模型标识
     * @param selectedDefault 是否为当前持久默认 Provider
     */
    public record ProviderSummary(String providerId, ProviderDefinition.Kind kind, int modelCount,
                                  String defaultModelId, boolean selectedDefault) { }
    /**
     * Profile list/status 安全摘要。
     *
     * @param providerId Provider 标识
     * @param profileId profile 标识
     * @param authMethod 认证方式
     * @param refKind credential 引用类型
     * @param status 本机认证状态
     * @param providerDefault 是否为 Provider 默认 profile
     * @param lastProbeCode 最近一次探测状态码
     * @param lastProbeAt 最近一次探测时间
     */
    public record ProfileSummary(String providerId, String profileId, String authMethod, String refKind,
                                 ProviderAuthStatusCode status, boolean providerDefault,
                                 Optional<String> lastProbeCode, Optional<java.time.Instant> lastProbeAt) { }
    /**
     * Model list 安全摘要。
     *
     * @param providerId Provider 标识
     * @param modelId 模型标识
     * @param providerDefault 是否为 Provider 默认模型
     */
    public record ModelSummary(String providerId, String modelId, boolean providerDefault) { }
    /**
     * probe 输入；不接受 URL、Header 或 raw secret。
     *
     * @param providerId Provider 标识
     * @param profileId profile 标识
     * @param modelId 模型标识
     * @param timeout 探测超时时间
     */
    public record ProbeRequest(String providerId, String profileId, String modelId, java.time.Duration timeout) {
        /** 默认 5 秒且最大 30 秒。 */
        public ProbeRequest {
            Objects.requireNonNull(providerId); Objects.requireNonNull(profileId); Objects.requireNonNull(modelId);
            timeout=Objects.requireNonNull(timeout);
            if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(java.time.Duration.ofSeconds(30))>0)
                throw new IllegalArgumentException("probe timeout 非法");
        }
    }
    /**
     * 不含 endpoint/remote payload 的 probe 安全结果。
     *
     * @param providerId Provider 标识
     * @param profileId profile 标识
     * @param modelId 模型标识
     * @param outcome 探测结果
     * @param probedAt 探测时间
     */
    public record ProbeResult(String providerId, String profileId, String modelId,
                              ProviderProbePort.ProbeOutcome outcome, java.time.Instant probedAt) { }
    /**
     * logout 安全结果；remoteRevoked 恒为 false。
     *
     * @param providerId Provider 标识
     * @param profileId profile 标识
     * @param remoteRevoked 是否已撤销远端凭据，恒为 false
     */
    public record LogoutResult(String providerId, String profileId, boolean remoteRevoked) { }

    /** active Run 捕获值；关闭只清 active fence，不改变捕获 selection。 */
    public static final class RunSelection implements AutoCloseable {
        private final ProviderSelectionSnapshot snapshot;
        private final AtomicBoolean active;
        private final AtomicBoolean closed = new AtomicBoolean();
        private RunSelection(ProviderSelectionSnapshot snapshot, AtomicBoolean active) {
            this.snapshot = snapshot; this.active = active;
        }
        /**
         * 返回该 Run 固定的可选选择。
         *
         * @return Run 开始时捕获的选择；开始时尚无有效选择则为空
         */
        public Optional<ProviderSelectionSnapshot> snapshot() {
            return Optional.ofNullable(snapshot);
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) active.set(false); }
    }
}
