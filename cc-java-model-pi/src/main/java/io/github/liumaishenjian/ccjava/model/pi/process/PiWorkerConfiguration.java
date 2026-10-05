package io.github.liumaishenjian.ccjava.model.pi.process;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 仅由可信 composition root 创建的固定 Worker 配置；不寻找 PATH，不读取 Pi 用户文件。
 *
 * <p>入口必须名为 worker.mjs。两文件拒绝自身链接/非普通文件，父目录解析为真实路径，
 * 启动时再次校验。此应用层校验不能阻止恶意主体并发替换受信安装目录，亦非 OS sandbox。
 * 代理值可以包含秘密，因此不参与字符串表示、argv 或错误信息。</p>
 */
public final class PiWorkerConfiguration {
    private static final Set<String> PROXIES = Set.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");
    private static final Set<String> OS_ENVIRONMENT = Set.of("SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR");
    private final Path node;
    private final Path worker;
    private final Map<String, String> proxies;

    /**
     * 固定可执行文件和入口，只接受显式代理白名单，不自动继承任何代理或 Provider Key。
     * @param nodeExecutable 绝对 Node 可执行文件路径
     * @param workerEntrypoint 绝对 worker.mjs 文件路径
     * @param proxyEnvironment 仅 HTTP_PROXY/HTTPS_PROXY/NO_PROXY，可为空 Map
     * @throws PiWorkerException 路径或环境不合法；不保留原异常
     */
    public PiWorkerConfiguration(Path nodeExecutable, Path workerEntrypoint, Map<String, String> proxyEnvironment) {
        try {
            if (workerEntrypoint == null || !"worker.mjs".equals(workerEntrypoint.getFileName().toString())
                    || proxyEnvironment == null || !PROXIES.containsAll(proxyEnvironment.keySet())) throw invalid();
            Map<String, String> copy = new HashMap<>();
            for (var entry : proxyEnvironment.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.length() > 8192 || value.indexOf('\0') >= 0
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw invalid();
                copy.put(entry.getKey(), value);
            }
            node = realFile(nodeExecutable);
            worker = realFile(workerEntrypoint);
            proxies = Map.copyOf(copy);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    ProcessBuilder builder() {
        try {
            // 保留 canonical 路径但重新拒绝文件本身链接；受信安装目录应由部署方保护。
            if (!realFile(node).equals(node) || !realFile(worker).equals(worker)) throw invalid();
            ProcessBuilder builder = new ProcessBuilder(node.toString(), worker.toString());
            builder.directory(worker.getParent().toFile());
            Map<String, String> environment = builder.environment();
            environment.clear();
            for (String name : OS_ENVIRONMENT) {
                String value = System.getenv(name);
                if (value != null) environment.put(name, value);
            }
            environment.putAll(proxies);
            return builder;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static Path realFile(Path path) throws java.io.IOException {
        if (path == null || !path.isAbsolute()) throw invalid();
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) throw invalid();
        Path real = path.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw invalid();
        return real;
    }

    private static PiWorkerException invalid() {
        return new PiWorkerException(PiWorkerException.Code.CONFIGURATION_INVALID);
    }

    @Override
    public String toString() { return "PI_WORKER_CONFIGURATION"; }
}
