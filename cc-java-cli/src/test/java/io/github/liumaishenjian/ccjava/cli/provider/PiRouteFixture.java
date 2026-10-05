package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialStore;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import java.util.function.Function;

/** 跨包装配测试桥；不扩大生产GatewayFactory的可见性，不提供真实配置或凭据。 */
public final class PiRouteFixture {
    private PiRouteFixture() { }

    /** 使用包内Fake seam保留真实目录、epoch与lease路径，任何秘密导出均使测试失败。 */
    public static PiSelectedProviderRouteFactory create(PiCredentialStore store, CredentialLeaseRegistry leases,
            PiProviderCatalog catalog, Function<String, ModelGateway> gateways) {
        return new PiSelectedProviderRouteFactory(store, leases, catalog, () -> null,
                ignored -> { throw new AssertionError("Fake must not export credentials"); },
                (configuration, provider, model, timeout, sessions) -> gateways.apply(model));
    }
}
