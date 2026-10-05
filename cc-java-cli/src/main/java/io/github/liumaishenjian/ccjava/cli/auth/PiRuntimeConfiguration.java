package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * ADR-100 的可信 JVM 启动属性装配器，仅检查本地安装，不启动 Node 或探测账号。
 *
 * <p>不搜索 PATH、不读取工作区配置或 provider.local.properties。仅在新 worker 属性完全
 * 缺失时，兼容显式旧桥的同目录 worker.mjs；这不是 Provider 失败回退。调用方负责保证
 * JVM 属性和安装目录可信，文件检查不是签名验证或 OS sandbox。</p>
 */
public final class PiRuntimeConfiguration {
    private static final Set<String> PROXIES = Set.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");

    private PiRuntimeConfiguration() { }

    /**
     * 从可信 JVM 属性和显式环境解析固定 Worker 配置，不继承密钥或 Node 启动选项。
     * @param environment 调用方显式提供的环境；只转交三个大写代理变量，不允许 null
     * @return 复用进程层真实路径与环境校验的配置
     * @throws PiWorkerException 配置缺失、不合法或依赖缺失；不保留路径、代理值或原异常
     */
    public static PiWorkerConfiguration resolve(Map<String, String> environment) {
        try {
            return resolve(System.getProperties(), environment);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 检查本地配置是否完整；不表示账号已登录、模型可用或 Node 版本已验证。
     * @param environment 显式环境白名单的来源
     * @return 本地文件与环境校验通过时为 true
     */
    public static boolean available(Map<String, String> environment) {
        try { resolve(environment); return true; }
        catch (RuntimeException failure) { return false; }
    }

    // 包级测试 seam：测试不改 JVM 全局属性，也不执行真实 Node。
    static PiWorkerConfiguration resolve(Properties properties, Map<String, String> environment) {
        try {
            if (properties == null || environment == null) throw invalid();
            Path node = Path.of(property(properties, "codej.nodeExecutable"));
            Path worker;
            // Properties 允许非字符串值；存在但非法的值也必须失败关闭，不能被旧配置掩盖。
            if (properties.containsKey("codej.piWorker") || properties.getProperty("codej.piWorker") != null) {
                worker = Path.of(property(properties, "codej.piWorker"));
            } else {
                Path bridge = Path.of(property(properties, "codej.piBridge"));
                if (!"login.mjs".equals(bridge.getFileName().toString())) throw invalid();
                worker = realFile(bridge).getParent().resolve("worker.mjs");
            }
            Map<String, String> proxies = new HashMap<>();
            for (String name : PROXIES) {
                if (environment.containsKey(name)) proxies.put(name, environment.get(name));
            }
            PiWorkerConfiguration configuration = new PiWorkerConfiguration(node, worker, proxies);
            Path realWorker = realFile(worker);
            realFile(realWorker.getParent().resolve("node_modules/@earendil-works/pi-ai/package.json"));
            return configuration;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    static boolean available(Properties properties, Map<String, String> environment) {
        try { resolve(properties, environment); return true; }
        catch (RuntimeException failure) { return false; }
    }

    private static String property(Properties properties, String key) {
        if (properties.containsKey(key) && !(properties.get(key) instanceof String)) throw invalid();
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static Path realFile(Path path) throws java.io.IOException {
        if (path == null || !path.isAbsolute()) throw invalid();
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) throw invalid();
        Path real = path.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw invalid();
        return real;
    }

    private static PiWorkerException invalid() {
        return new PiWorkerException(PiWorkerException.Code.CONFIGURATION_INVALID);
    }
}
