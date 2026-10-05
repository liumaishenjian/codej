package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** 确定性复现线程池继承过期Run；只用显式Fake兼容端口，不读取材料或访问网络。 */
class SelectedRunScopeOwnershipTest {
    @TempDir Path home;

    @Test void reusedWorkerCanOpenItsOwnRunAfterCreatorRunEnds() throws Exception {
        var routes = new SelectedProviderRouteFactory(new ProviderDefinitionStore(home),
                new CredentialResolver(new RestrictedFileCredentialStore(home), Map.of()),
                new CredentialLeaseRegistry(), ProviderGatewayFactoryRegistry.production());
        var gateway = routes.lazyGateway(Optional::empty, ignored -> ModelTurn.text("independent run"),
                (request, token) -> Optional.empty());
        try (var worker = Executors.newSingleThreadExecutor()) {
            try (var creator = gateway.openRun()) {
                // 只固定工作线程的创建时机；不让它调用模型或借用创建者的作用域。
                assertThat(worker.submit(() -> true).get(5, TimeUnit.SECONDS)).isTrue();
            }
            String text = worker.submit(() -> {
                try (var independent = gateway.openRun()) {
                    independent.bindCancellation(() -> { });
                    return gateway.complete(new ModelRequest(new SessionId("fixture-session"),
                            new RunId("independent-run"), 1, List.of(new UserMessage("fixture")), List.of()))
                            .assistantMessage().text();
                }
            }).get(5, TimeUnit.SECONDS);
            assertThat(text).isEqualTo("independent run");
        }
    }
}
