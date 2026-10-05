package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 独立 Pi 认证 helper 的固定生产入口；不构造 Agent Runtime 或 Session。
 *
 * <p>仅从可信启动 JVM 的 home/dir 与 PiRuntimeConfiguration 固定属性装配共享服务。
 * argv 只含关联、身份和可选 ENV_REF 名称，不接受秘密值、URL、任意路径或 Node 参数。启动失败不含诊断；
 * System.exit 保证私有 stdout 真正 EOF，不把进程退出当作资源清理成功证据。</p>
 */
public final class PiAuthBridgeMain {
    private PiAuthBridgeMain() { }

    /**
     * 启动一次认证；成功与失败都结束独立进程，绝不自动重试。
     * @param args --operation-id、--provider、--profile、--auth-method 及可选 --environment-name 的分离值形式
     */
    public static void main(String[] args) {
        int status = 1;
        ProviderAuthRuntimeResources resources = null;
        boolean delegated = false;
        try {
            Arguments parsed = parse(args);
            Path home = trustedPath("user.home");
            Path directory = trustedPath("user.dir");
            // ENV 仅保存名称，连代理/Pi 配置也不解析；缺少 Node 不阻止该本地事务。
            Map<String, String> environment = new HashMap<>();
            if (parsed.environmentName() == null) {
                // 交互认证也不读取 ENV 密钥，只把可信代理白名单交给固定装配器。
                for (String name : Set.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")) {
                    String value = System.getenv(name);
                    if (value != null) environment.put(name, value);
                }
                PiRuntimeConfiguration.resolve(environment);
            }
            resources = ProviderAuthRuntimeResources.open(home, directory, environment);
            ProviderAuthRuntimeResources owned = resources;
            PiAuthBridge bridge = new PiAuthBridge(parsed.operationId(), parsed.identity(), System.in, System.out,
                    (interaction, cancellation) -> parsed.environmentName() == null
                            ? owned.service().loginPiWithReceipt(parsed.identity(), interaction, cancellation).receipt()
                            : owned.service().loginPiEnvironment(parsed.identity(), parsed.environmentName(), cancellation).receipt(), owned);
            delegated = true;
            status = bridge.run();
        } catch (Throwable ignored) {
            // 启动前失败仅由固定退出码报告；stderr 也可能阻塞，不在这里尝试写入。
        } finally {
            if (resources != null && !delegated) {
                // 构造桥之前的失败仍有界尝试关闭，不能无限卡住独立 helper。
                ProviderAuthRuntimeResources owned = resources;
                Thread closer = new Thread(() -> { try { owned.close(); } catch (Throwable ignored) { } },
                        "pi-auth-bridge-startup-close");
                closer.setDaemon(true); closer.start();
                try { closer.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        System.exit(status);
    }

    static Arguments parse(String[] args) {
        if (args == null || args.length < 6 || args.length > 10 || args.length % 2 != 0) throw invalid();
        Map<String, String> values = new HashMap<>();
        Set<String> allowed = Set.of("--operation-id", "--provider", "--profile", "--auth-method", "--environment-name");
        for (int i = 0; i < args.length; i += 2) {
            if (args[i] == null || !allowed.contains(args[i]) || args[i + 1] == null || values.putIfAbsent(args[i], args[i + 1]) != null) throw invalid();
        }
        String operation = values.get("--operation-id");
        if (operation == null || !operation.matches("[a-zA-Z0-9_-]{1,96}") || !values.containsKey("--provider")
                || !values.containsKey("--auth-method")) throw invalid();
        try {
            PiCredentialIdentity identity = new PiCredentialIdentity(values.get("--provider"),
                    PiCredentialIdentity.AuthMethod.valueOf(values.get("--auth-method")),
                    values.getOrDefault("--profile", "default"));
            String environmentName = values.get("--environment-name");
            if (environmentName != null) {
                if (identity.authMethod() != PiCredentialIdentity.AuthMethod.API_KEY) throw invalid();
                try (var ignored = PiCredentialMaterial.envRef(environmentName)) { /* 只校验名称，不查环境。 */ }
            }
            return new Arguments(operation, identity, environmentName);
        } catch (RuntimeException ignored) { throw invalid(); }
    }
    private static Path trustedPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw invalid();
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw invalid();
        return path.normalize();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PI_BRIDGE_CONFIGURATION"); }
    record Arguments(String operationId, PiCredentialIdentity identity, String environmentName) { }
}
