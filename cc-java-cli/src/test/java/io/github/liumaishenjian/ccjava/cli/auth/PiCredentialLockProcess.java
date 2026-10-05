package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.nio.file.Path;

/** 只用于合成临时目录的不同 JVM 文件锁验证，不访问网络或真实用户凭证。 */
public final class PiCredentialLockProcess {
    private PiCredentialLockProcess() { }
    /**
     * 获取同一身份事务后以固定行握手；父进程关闭 stdin 时释放。
     * @param arguments 临时 home、profile 和预期代次
     * @throws Exception 合成测试进程失败时退出非零
     */
    public static void main(String[] arguments) throws Exception {
        boolean probe = arguments.length == 4 && "probe".equals(arguments[3]);
        var store = probe ? new PiCredentialStore(new RestrictedFileSecurity(Path.of(arguments[0])),
                java.time.Duration.ofMillis(250), RestrictedFileSecurity.AtomicMover.system(), point -> { })
                : new PiCredentialStore(Path.of(arguments[0]));
        var identity = new PiCredentialIdentity("openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, arguments[1]);
        try (var transaction = store.beginModify(identity, Long.parseLong(arguments[2]), CancellationToken.none())) {
            System.out.println("LOCKED");
            System.out.flush();
            if (!probe) System.in.read();
        } catch (ProviderAuthException failure) {
            if (!probe || failure.code() != ProviderAuthException.Code.AUTH_STORE_LOCKED) throw failure;
            System.out.println("BLOCKED");
        }
    }
}
