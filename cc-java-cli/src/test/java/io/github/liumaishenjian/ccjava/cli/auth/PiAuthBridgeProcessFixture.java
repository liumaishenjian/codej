package io.github.liumaishenjian.ccjava.cli.auth;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 独立 Java 子进程 Fake 登录；只使用合成输入，不读取 home 或真实 OAuth 配置。 */
public final class PiAuthBridgeProcessFixture {
    private PiAuthBridgeProcessFixture() { }

    /**
     * 在真实 stdin/stdout 上运行桥并以真实退出码交付终态。
     * @param args 不使用，不接受秘密或路径
     */
    public static void main(String[] args) {
        var identity = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "default");
        PiAuthBridge bridge = new PiAuthBridge("fixture", identity, System.in, System.out,
                (interaction, cancellation) -> {
                    try (var handle = interaction.request(new PiLoginOperation.Prompt("inner", 1, "secret"));
                         var material = handle.result().toCompletableFuture().get(2, TimeUnit.SECONDS)) {
                        char[] owned = material.copyChars();
                        try { if (owned.length == 0) throw new IllegalStateException("FAKE_REJECTED"); }
                        finally { java.util.Arrays.fill(owned, '\0'); }
                    }
                    return new PiLoginOperation.Receipt(identity, 9007199254740993L);
                }, () -> { }, Duration.ofSeconds(5), Duration.ofSeconds(1));
        System.exit(bridge.run());
    }
}
