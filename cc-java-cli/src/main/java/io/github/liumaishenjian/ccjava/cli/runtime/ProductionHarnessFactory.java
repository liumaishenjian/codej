package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.daemon.StableProtocolHandler;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.protocol.ProtocolFeature;
import io.github.liumaishenjian.ccjava.sdk.CcJavaSdkClient;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * CLI、SDK 与 stable v1 共用的公开 production composition factory。
 *
 * <p>每次创建一个拥有唯一 {@link HeadlessRuntimeSession} 的 Application Service；SDK 和协议
 * handler 都包裹该同一 composition，而不是复制 Agent Loop。调用方必须关闭返回对象。</p>
 *
 * @since 0.1.0
 */
public final class ProductionHarnessFactory {
    private ProductionHarnessFactory() {
    }

    /**
     * 创建已打开的 SDK Client。
     *
     * @param settings Provider 连接设置
     * @param options Headless Runtime 与 Session 选项
     * @return 拥有独立 Application Service 生命周期的 SDK Client
     */
    public static CcJavaSdkClient openSdk(
            OpenAiCompatibleSettings settings, HeadlessRuntimeOptions options) {
        return new CcJavaSdkClient(openApplication(settings, options));
    }

    /**
     * 创建已完成 Runtime/Session 打开的 Application Service。
     *
     * @param settings Provider 连接设置
     * @param options Headless Runtime 与 Session 选项
     * @return 必须由调用方关闭的唯一生产 Application Service
     */
    public static HeadlessAgentApplicationService openApplication(
            OpenAiCompatibleSettings settings, HeadlessRuntimeOptions options) {
        HeadlessRuntimeSession session = new HeadlessRuntimeSession(
                Objects.requireNonNull(settings, "settings 不能为空"),
                AgentEventSink.noop(), Objects.requireNonNull(options, "options 不能为空"));
        try {
            session.open();
            java.nio.file.Path home = java.nio.file.Path.of(Objects.requireNonNull(
                    System.getProperty("user.home"), "user.home 不能为空"));
            return new HeadlessAgentApplicationService(session, new ProductionControlApi(
                    new io.github.liumaishenjian.ccjava.cli.session.SessionLifecycleService(
                            options.sessionStoreRoot()),
                    io.github.liumaishenjian.ccjava.cli.governance.ManagedGovernance.production(home)));
        } catch (RuntimeException failure) {
            session.close();
            throw failure;
        }
    }

    /**
     * 创建使用真实 stable codec/state 的 v1 handler；handler 关闭时拥有 Application 生命周期。
     *
     * @param settings Provider 连接设置
     * @param options Headless Runtime 与 Session 选项
     * @param token stable 连接初始化所需 capability token
     * @param features Server 可协商能力
     * @return 拥有唯一 Application Service 的 stable v1 handler
     */
    public static StableProtocolHandler openStableHandler(
            OpenAiCompatibleSettings settings,
            HeadlessRuntimeOptions options,
            CapabilityToken token,
            Set<ProtocolFeature> features) {
        return new StableProtocolHandler(
                Objects.requireNonNull(token, "token 不能为空"),
                Set.copyOf(Objects.requireNonNull(features, "features 不能为空")),
                openApplication(settings, options));
    }

    /**
     * 返回当前 production stable capability 集合；实验 gate 不会改变 schema 语义。
     *
     * @return 经过机器治理收窄的 stable capabilities
     */
    public static Set<ProtocolFeature> stableFeatures() {
        return stableFeatures(io.github.liumaishenjian.ccjava.cli.governance.ManagedGovernance
                .production(java.nio.file.Path.of(Objects.requireNonNull(
                        System.getProperty("user.home"), "user.home 不能为空"))));
    }

    /**
     * 按 Managed deny 与 Feature Gate 收窄 negotiated capabilities。
     *
     * @param governance 已验证的机器治理快照
     * @return 不可被治理放宽的 stable capability 集合
     */
    public static Set<ProtocolFeature> stableFeatures(
            io.github.liumaishenjian.ccjava.cli.governance.ManagedGovernance governance) {
        Set<ProtocolFeature> defaults = Set.copyOf(EnumSet.of(
                ProtocolFeature.RUN,
                ProtocolFeature.CANCEL,
                ProtocolFeature.SESSION_RESUME,
                ProtocolFeature.SESSION_EXPORT,
                ProtocolFeature.SESSION_RETENTION,
                ProtocolFeature.SESSION_MIGRATION,
                ProtocolFeature.SESSION_INDEX,
                ProtocolFeature.GOVERNANCE,
                ProtocolFeature.CHECKPOINT,
                ProtocolFeature.TASK_LIST_V1,
                ProtocolFeature.DAEMON));
        return Objects.requireNonNull(governance, "governance 不能为空")
                .negotiatedFeatures(defaults);
    }
}
