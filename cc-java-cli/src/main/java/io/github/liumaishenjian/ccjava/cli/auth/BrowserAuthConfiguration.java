package io.github.liumaishenjian.ccjava.cli.auth;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从受信启动器的JVM属性解析可选认证桥位置，不从工作区、模型参数或PATH发现可执行文件。
 * <p>缺少桥或依赖时只关闭浏览器入口；不影响既有API Key登录。不证明OS隔离或第三方发布签名。</p>
 * @param nodeExecutable 启动器提供的Node常规文件路径
 * @param bridgeEntrypoint 包内固定认证桥文件路径
 */
public record BrowserAuthConfiguration(Path nodeExecutable, Path bridgeEntrypoint) {
    /** 返回当前受信启动配置是否完整；只读路径检查，不启动进程或网络。
     * @return 可选组件存在且路径合法时为true
     */
    public static boolean available() {
        try { resolve(); return true; } catch (RuntimeException unavailable) { return false; }
    }

    /** 解析并realpath验证启动器显式提供的Node和固定桥文件。
     * @return 已验证的组件绝对路径
     * @throws ProviderAuthException 配置缺失或文件不安全可用时失败关闭
     */
    public static BrowserAuthConfiguration resolve() {
        try {
            Path node = Path.of(System.getProperty("codej.nodeExecutable", ""));
            Path bridge = Path.of(System.getProperty("codej.piBridge", ""));
            if (!node.isAbsolute() || !bridge.isAbsolute() || !Files.isRegularFile(node)
                    || !Files.isRegularFile(bridge) || !bridge.getFileName().toString().equals("login.mjs")) {
                throw new IllegalArgumentException();
            }
            node = node.toRealPath(); bridge = bridge.toRealPath();
            if (!Files.isRegularFile(bridge.getParent().resolve("node_modules/@earendil-works/pi-ai/package.json"))) {
                throw new IllegalArgumentException();
            }
            return new BrowserAuthConfiguration(node, bridge);
        } catch (Exception unavailable) {
            throw new ProviderAuthException(ProviderAuthException.Code.AUTH_SECRET_INPUT_REQUIRED,
                    ProviderAuthException.Action.LOGIN, false);
        }
    }
}
