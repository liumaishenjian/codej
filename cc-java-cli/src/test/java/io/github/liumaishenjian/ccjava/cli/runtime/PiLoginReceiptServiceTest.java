package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-100 私有回执与 ENV 共用协调链；只用临时存储及合成引用，不启动 Node。 */
class PiLoginReceiptServiceTest {
    private static final CancellationToken NONE = CancellationToken.none();
    private static final PiCredentialIdentity ID = new PiCredentialIdentity("pi", "openai",
            PiCredentialIdentity.AuthMethod.API_KEY, "default");
    private static final String ENV = "CODEJ_SYNTHETIC_REFERENCE_NOT_RESOLVED";
    private static final PiLoginOperation.Interaction UNUSED = new PiLoginOperation.Interaction() {
        public PiLoginOperation.PromptHandle request(PiLoginOperation.Prompt prompt) {
            throw new AssertionError("INPUT_FORBIDDEN");
        }
        public void authorizationUrl(URI url) { throw new AssertionError("URL_FORBIDDEN"); }
    };
    @TempDir Path temporary;

    @Test void exactOriginalReceiptSurvivesOtherIdentityIndexAdvanceAndLegacyFacadeStillWorks() throws Exception {
        AtomicReference<PiLoginOperation.Receipt> original = new AtomicReference<>();
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> operation(() -> {
            assertThat(timeout).isEqualTo(Duration.ofSeconds(300));
            var receipt = save(store, identity, expected, token);
            original.set(receipt);
            save(store, new PiCredentialIdentity("pi", "deepseek", PiCredentialIdentity.AuthMethod.API_KEY, "default"),
                    store.snapshot(token).generation(), token);
            return receipt;
        }, () -> { }));
        var result = f.service.loginPiWithReceipt(ID, UNUSED, NONE);
        assertThat(result.receipt()).isSameAs(original.get());
        assertThat(result.receipt().authEpoch()).isLessThan(f.store.snapshot(NONE).generation());
        assertThat(result.summary().toString()).doesNotContain("authEpoch", "generation", ENV, "secretRef");
        assertThat(result.toString()).doesNotContain("authEpoch", "receipt");
        assertThat(f.service.loginPi(ID, UNUSED, NONE)).isEqualTo(result.summary());
        assertThat(f.service.nextSelection()).isEmpty();
        assertThat(f.store.snapshot(NONE).providerDefaults()).isEmpty();
    }

    @Test void resultRejectsNullAndMismatchedIdentity() {
        var summary = new ProviderAuthApplicationService.PiProfileSummary("pi", "openai", "default", "API_KEY",
                "ENV_REF", "CONFIGURED_UNVERIFIED");
        var receipt = new PiLoginOperation.Receipt(ID, 1);
        assertThatThrownBy(() -> new ProviderAuthApplicationService.PiLoginResult(null, receipt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProviderAuthApplicationService.PiLoginResult(summary, null))
                .isInstanceOf(NullPointerException.class);
        for (var identity : new PiCredentialIdentity[] {
                new PiCredentialIdentity("pi", "deepseek", PiCredentialIdentity.AuthMethod.API_KEY, "default"),
                new PiCredentialIdentity("pi", "openai", PiCredentialIdentity.AuthMethod.API_KEY, "other"),
                new PiCredentialIdentity("pi", "openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, "default") }) {
            assertThatThrownBy(() -> new ProviderAuthApplicationService.PiLoginResult(summary,
                    new PiLoginOperation.Receipt(identity, 1))).isInstanceOf(ProviderAuthException.class);
        }
    }

    @Test void environmentOnlyStoresReferenceAndNeverCallsConfigurationOrNode() throws Exception {
        Fixture f = fixture(null);
        var previous = f.service.loginPiEnvironment(ID, ENV, NONE);
        // 只有真实删除回执水位才能被新登录解除；单独 fenceAndDrain 不是一次成功退出。
        f.service.logoutPi(ID, NONE);
        assertThat(f.leases.fenced(ID)).isTrue();
        var result = f.service.loginPiEnvironment(ID, ENV, NONE);
        assertThat(result.receipt().authEpoch()).isGreaterThan(previous.receipt().authEpoch());
        var current = f.store.snapshot(NONE).find(ID).orElseThrow();
        assertThat(result.receipt().identity()).isEqualTo(ID);
        assertThat(result.receipt().authEpoch()).isEqualTo(current.authEpoch());
        assertThat(current.kind()).isEqualTo(PiCredentialMaterial.Kind.ENV_REF);
        assertThat(current.variableName()).contains(ENV);
        assertThat(current.secretRef()).isEmpty();
        assertThat(result.summary().refKind()).isEqualTo("ENV_REF");
        assertThat(result.summary().toString()).doesNotContain(ENV, "authEpoch", "generation");
        assertThat(f.leases.fenced(ID)).isFalse();
        assertThat(f.service.nextSelection()).isEmpty();
        assertThat(f.definitions.snapshot(NONE).defaultSelection()).isEmpty();
        assertThat(f.store.snapshot(NONE).providerDefaults()).isEmpty();
        try (var material = f.store.read(ID, result.receipt().authEpoch(), NONE)) {
            assertThat(material.kind()).isEqualTo(PiCredentialMaterial.Kind.ENV_REF);
            assertThat(material.variableName()).isEqualTo(ENV);
        }
    }

    @Test void environmentSaveCannotClearFenceWithoutConfirmedDeletionReceipt() throws Exception {
        Fixture f = fixture(null);
        f.leases.fenceAndDrain(ID, Duration.ZERO, NONE);
        assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, NONE))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(f.leases.fenced(ID)).isTrue();
        // 保存可能已提交，但缺少退出证据绝不能被当成自动解除 fence 的权限。
        assertThat(f.store.snapshot(NONE).find(ID)).isPresent();
        assertThat(f.service.nextSelection()).isEmpty();
    }

    @Test void environmentRejectsOAuthInvalidNamesAndCancelledBudgetWithoutWriting() throws Exception {
        Fixture f = fixture(null);
        var oauth = new PiCredentialIdentity("pi", "openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, "default");
        assertThatThrownBy(() -> f.service.loginPiEnvironment(oauth, ENV, NONE)).isInstanceOf(IllegalArgumentException.class);
        for (String name : new String[] {"", "1KEY", "KEY=value", "A B", "A\nB", "A".repeat(129)}) {
            assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, name, NONE)).isInstanceOf(IllegalArgumentException.class);
        }
        var cancelled = new CancellationSource(); cancelled.cancel();
        assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, cancelled.token())).isInstanceOf(ProviderAuthException.class);
        CancellationToken expired = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public Registration onCancellation(Runnable action) { return () -> { }; }
            public Optional<Duration> remainingTime() { return Optional.of(Duration.ZERO); }
        };
        assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, expired)).isInstanceOf(ProviderAuthException.class);
        assertThat(f.store.snapshot(NONE).profiles()).isEmpty();
        try (var ignored = f.service.beginRun()) { assertThat(f.service.nextSelection()).isEmpty(); }
    }

    @Test void sharedReceiptPathRejectsStaleEpochRatherThanReturningReplacement() throws Exception {
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> operation(() -> {
            var original = save(store, identity, expected, token);
            save(store, identity, store.snapshot(token).generation(), token);
            return original;
        }, () -> { }));
        assertThatThrownBy(() -> f.service.loginPiWithReceipt(ID, UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
        assertThat(f.store.snapshot(NONE).find(ID)).isPresent();
    }

    @Test void environmentUsesPreOperationSnapshotCas() throws Exception {
        Fixture f = fixture(null);
        AtomicBoolean injected = new AtomicBoolean();
        CancellationToken token = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public Registration onCancellation(Runnable action) { return () -> { }; }
            public Optional<Duration> remainingTime() {
                // ENV 操作构造器在快照之后读取调用方预算；只在该确定边界注入另一提交。
                boolean constructor = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().endsWith("$PiEnvironmentOperation") && frame.getMethodName().equals("<init>")));
                if (constructor && injected.compareAndSet(false, true))
                    save(f.store, ID, f.store.snapshot(NONE).generation(), NONE);
                return Optional.of(Duration.ofSeconds(30));
            }
        };
        assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, token)).isInstanceOf(ProviderAuthException.class);
        assertThat(injected).isTrue();
        assertThat(f.store.snapshot(NONE).find(ID).orElseThrow().authEpoch()).isEqualTo(1);
        try (var ignored = f.service.beginRun()) { assertThat(f.service.nextSelection()).isEmpty(); }
    }

    @Test void environmentAndRootRunAndWorkerLoginUseSameExclusiveSlot() throws Exception {
        Fixture f = fixture(null);
        try (var ignored = f.service.beginRun()) {
            assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, NONE)).isInstanceOf(ProviderAuthException.class);
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        publicationBarrier(f.store, entered, release);
        var running = CompletableFuture.supplyAsync(() -> f.service.loginPiEnvironment(ID, ENV, NONE));
        try {
            await(entered);
            assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(() -> f.service.loginPiWithReceipt(ID, UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, NONE)).isInstanceOf(ProviderAuthException.class);
        } finally { release.countDown(); }
        assertThat(running.get(5, TimeUnit.SECONDS).summary().status()).isEqualTo("CONFIGURED_UNVERIFIED");
        try (var ignored = f.service.beginRun()) { assertThat(f.service.nextSelection()).isEmpty(); }
    }

    @Test void activeWorkerLoginAlsoBlocksEnvironmentUntilItsSlotIsReleased() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> operation(() -> {
            entered.countDown(); await(release);
            return save(store, identity, expected, token);
        }, () -> { }));
        var running = CompletableFuture.supplyAsync(() -> f.service.loginPiWithReceipt(ID, UNUSED, NONE));
        try {
            await(entered);
            assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, NONE)).isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
        } finally { release.countDown(); }
        running.get(5, TimeUnit.SECONDS);
        assertThat(f.service.loginPiEnvironment(ID, ENV, NONE).summary().refKind()).isEqualTo("ENV_REF");
    }

    @Test void closeWaitsForLateEnvironmentCommitAndNeverReportsSuccessOrRollback() throws Exception {
        Fixture f = fixture(null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        publicationBarrier(f.store, entered, release);
        var running = CompletableFuture.supplyAsync(() -> f.service.loginPiEnvironment(ID, ENV, NONE));
        await(entered);
        var closing = CompletableFuture.runAsync(f.service::closePiLogin);
        try {
            awaitClosed(f.service);
            assertThat(closing.isDone()).isFalse();
        } finally { release.countDown(); }
        closing.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ProviderAuthException.class);
        assertThat(f.store.snapshot(NONE).find(ID)).isPresent();
        assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
    }

    @Test void logoutRacingPublishedEnvironmentReplacementCannotDeleteNewEpoch() throws Exception {
        Fixture f = fixture(null);
        var initial = f.service.loginPiEnvironment(ID, ENV, NONE);
        var ticket = f.service.preparePiLogout(ID, "session", NONE);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        publicationBarrier(f.store, entered, release);
        var running = CompletableFuture.supplyAsync(() -> f.service.loginPiEnvironment(ID, ENV, NONE));
        await(entered);
        var logout = CompletableFuture.runAsync(() -> f.service.commitLogout(ticket.confirmationId(), "session", NONE));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!f.leases.fenced(ID) && System.nanoTime() < deadline) Thread.yield();
            assertThat(f.leases.fenced(ID)).isTrue();
            assertThat(logout.isDone()).isFalse();
        } finally { release.countDown(); }
        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> logout.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ProviderAuthException.class);
        assertThat(f.store.snapshot(NONE).find(ID).orElseThrow().authEpoch()).isGreaterThan(initial.receipt().authEpoch());
        assertThat(f.leases.fenced(ID)).isTrue();
    }

    @Test void interruptedEnvironmentCloseKeepsStickyCleanupAndFenceAfterCommitConverges() throws Exception {
        Fixture f = fixture(null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        publicationBarrier(f.store, entered, release);
        var running = CompletableFuture.supplyAsync(() -> f.service.loginPiEnvironment(ID, ENV, NONE));
        await(entered);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(f.service::closePiLogin).isInstanceOf(PiWorkerException.class);
        } finally { Thread.interrupted(); release.countDown(); }
        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PiWorkerException.class);
        assertThat(f.store.snapshot(NONE).find(ID)).isPresent();
        assertThat(f.leases.fenced(ID)).isTrue();
        assertThatThrownBy(f.service::closePiLogin).isInstanceOf(PiWorkerException.class);
        assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
    }

    @Test void operationCleanupFailureBlocksReceiptAndRemainsStickyWithoutDeletingCommit() throws Exception {
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> operation(
                () -> save(store, identity, expected, token),
                () -> { throw new PiWorkerException(PiWorkerException.Code.CLEANUP_FAILED); }));
        assertThatThrownBy(() -> f.service.loginPiWithReceipt(ID, UNUSED, NONE)).isInstanceOf(PiWorkerException.class);
        assertThat(f.store.snapshot(NONE).find(ID)).isPresent();
        assertThat(f.leases.fenced(ID)).isTrue();
        assertThatThrownBy(f.service::closePiLogin).isInstanceOf(PiWorkerException.class);
        assertThatThrownBy(() -> f.service.loginPiEnvironment(ID, ENV, NONE)).isInstanceOf(ProviderAuthException.class);
    }

    private Fixture fixture(ProviderAuthApplicationService.PiOperationFactory factory) throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home-" + java.util.UUID.randomUUID()));
        Path repo = Files.createDirectory(temporary.resolve("repo-" + java.util.UUID.randomUUID()));
        var definitions = new ProviderDefinitionStore(home);
        var legacy = new RestrictedFileCredentialStore(home);
        var migration = new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(repo), definitions, legacy);
        var store = new PiCredentialStore(home);
        var leases = new CredentialLeaseRegistry();
        var service = new ProviderAuthApplicationService(definitions, legacy, migration, Map.of(), leases,
                (d, m, s, timeout, token) -> { throw new AssertionError("PROBE_FORBIDDEN"); }, Clock.systemUTC(), store,
                () -> { if (factory == null) throw new AssertionError("CONFIG_OR_NODE_FORBIDDEN"); return null; },
                factory == null ? (c, s, i, e, interaction, token, timeout) -> {
                    throw new AssertionError("NODE_OPERATION_FORBIDDEN");
                } : factory);
        return new Fixture(service, store, leases, definitions);
    }

    private static PiLoginOperation.Receipt save(PiCredentialStore store, PiCredentialIdentity identity,
                                                long expected, CancellationToken token) {
        try (var material = PiCredentialMaterial.envRef(ENV)) {
            return new PiLoginOperation.Receipt(identity, store.saveLogin(identity, material, expected, false, token).authEpoch());
        }
    }

    private static ProviderAuthApplicationService.PiOperation operation(
            java.util.function.Supplier<PiLoginOperation.Receipt> run, Runnable close) {
        return new ProviderAuthApplicationService.PiOperation() {
            public PiLoginOperation.Receipt run() { return run.get(); }
            public void close() { close.run(); }
        };
    }

    /** 仅测试安装已有 Store 故障接缝，在实际索引发布后暂停；不增加生产存储 API。 */
    private static void publicationBarrier(PiCredentialStore store, CountDownLatch entered, CountDownLatch release)
            throws Exception {
        var field = PiCredentialStore.class.getDeclaredField("faults"); field.setAccessible(true);
        Object hook = Proxy.newProxyInstance(field.getType().getClassLoader(), new Class<?>[] {field.getType()},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("after") && arguments[0].toString().equals("INDEX_PUBLISHED")) {
                        entered.countDown(); await(release);
                    }
                    return null;
                });
        field.set(store, hook);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    /** 读取共享监视器下的关闭位，以确定外部 close 已经过最终 Gate，而非依赖 sleep 调度。 */
    private static void awaitClosed(ProviderAuthApplicationService service) throws Exception {
        var closed = ProviderAuthApplicationService.class.getDeclaredField("authClosed"); closed.setAccessible(true);
        var monitor = ProviderAuthApplicationService.class.getDeclaredField("selectionMonitor"); monitor.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (monitor.get(service)) { if (closed.getBoolean(service)) return; }
            Thread.yield();
        }
        throw new AssertionError("CLOSE_NOT_STARTED");
    }

    private record Fixture(ProviderAuthApplicationService service, PiCredentialStore store,
                           CredentialLeaseRegistry leases, ProviderDefinitionStore definitions) { }
}
