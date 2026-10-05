package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialVersion;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialMaterial;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.PiLoginOperation;
import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.SecretMaterial;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.provider.probe.ProviderProbePort;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-100 Batch B 共享服务边界；临时 home、合成材料，不访问真实账号或模型。 */
class PiProviderAuthServiceTest {
    private static final CancellationToken NONE = CancellationToken.none();
    private static final PiCredentialIdentity OPENAI = identity("openai");
    private static final PiLoginOperation.Interaction UNUSED = new PiLoginOperation.Interaction() {
        public PiLoginOperation.PromptHandle request(PiLoginOperation.Prompt prompt) {
            throw new AssertionError("PROMPT_FORBIDDEN");
        }
        public void authorizationUrl(URI url) { throw new AssertionError("NETWORK_FORBIDDEN"); }
    };
    @TempDir Path temporary;

    @Test void explicitBackendKeepsSameNamedLegacyCredentialsAndSelectionUntouched() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_FORBIDDEN"); });
        legacy(f);
        var selected = f.service.selectModel(new ProviderAuthApplicationService.ModelSelectionRequest(
                "openai", "synthetic-model", Optional.of("default"), true), NONE);
        long epoch = save(f, OPENAI);
        var preview = f.service.preparePiLogout(OPENAI, "session", NONE);
        f.service.commitLogout(preview.confirmationId(), "session", NONE);
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isEmpty();
        assertThat(f.legacy.snapshot(NONE).find("openai", "default")).isPresent();
        assertThat(f.service.nextSelection()).contains(selected);
        assertThat(f.definitions.snapshot(NONE).defaultSelection()).isPresent();
        assertThat(f.leases.fenced(OPENAI)).isTrue();
        assertThat(f.leases.fenced("openai", "default")).isFalse();
        assertThatThrownBy(() -> f.service.activatePiLogin(OPENAI, new CredentialVersion.PiAuthEpoch(epoch), NONE))
                .isInstanceOf(ProviderAuthException.class);
    }

    @Test void legacyConstructorRejectsPiRatherThanFallingBack() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_FORBIDDEN"); });
        legacy(f);
        var old = new ProviderAuthApplicationService(f.definitions, f.legacy, f.migration, Map.of());
        assertThatThrownBy(() -> old.loginPi(OPENAI, UNUSED, NONE)).isInstanceOf(PiWorkerException.class);
        assertThatThrownBy(() -> old.listPiProfiles(Optional.empty(), NONE)).isInstanceOf(PiWorkerException.class);
        assertThatThrownBy(() -> old.logoutPi(OPENAI, NONE)).isInstanceOf(PiWorkerException.class);
        assertThat(f.legacy.snapshot(NONE).find("openai", "default")).isPresent();
    }

    @Test void missingRuntimeFailsBeforeInputAndReleasesConfirmedEmptySlot() throws Exception {
        Fixture f = fixture(null, () -> { throw new PiWorkerException(PiWorkerException.Code.CONFIGURATION_INVALID); });
        legacy(f);
        assertThatThrownBy(() -> f.service.loginPi(OPENAI, UNUSED, NONE)).isInstanceOf(PiWorkerException.class);
        assertThat(f.pi.snapshot(NONE).profiles()).isEmpty();
        assertThat(f.legacy.snapshot(NONE).find("openai", "default")).isPresent();
        try (var ignored = f.service.beginRun()) { assertThat(f.service.nextSelection()).isEmpty(); }
    }

    @Test void listDoesNotReadSecretsEnvironmentOrRuntimeConfiguration() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_OR_NODE_FORBIDDEN"); });
        save(f, OPENAI);
        var metadata = f.pi.snapshot(NONE).find(OPENAI).orElseThrow();
        Files.delete(secretPath(f, metadata));
        var envIdentity = identity("deepseek");
        try (var material = PiCredentialMaterial.envRef("SYNTHETIC_ENV_MUST_NOT_BE_READ")) {
            f.pi.saveLogin(envIdentity, material, f.pi.snapshot(NONE).generation(), false, NONE);
        }
        var summaries = f.service.listPiProfiles(Optional.empty(), NONE);
        assertThat(summaries).hasSize(2).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo("CONFIGURED_UNVERIFIED");
            assertThat(value.toString()).doesNotContain("SYNTHETIC_ENV", metadata.secretRef().orElseThrow(), "authEpoch");
        });
        f.leases.fenceAndDrain(OPENAI, Duration.ZERO, NONE);
        assertThat(f.service.listPiProfiles(Optional.of("openai"), NONE)).singleElement()
                .extracting(ProviderAuthApplicationService.PiProfileSummary::status).isEqualTo("REVOKED_IN_PROCESS");
    }

    @Test void activateRequiresExactEpochAndSafeMaterialThenDoesNotSetDefaults() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_FORBIDDEN"); });
        long original = save(f, OPENAI);
        f.service.logoutPi(OPENAI, NONE);
        long current = save(f, OPENAI);
        assertThatThrownBy(() -> f.service.activatePiLogin(OPENAI, new CredentialVersion.PiAuthEpoch(original), NONE))
                .isInstanceOf(ProviderAuthException.class);
        var result = f.service.activatePiLogin(OPENAI, new CredentialVersion.PiAuthEpoch(current), NONE);
        assertThat(result.status()).isEqualTo("CONFIGURED_UNVERIFIED");
        assertThat(f.leases.fenced(OPENAI)).isFalse();
        assertThat(f.service.nextSelection()).isEmpty();
        assertThat(f.pi.snapshot(NONE).providerDefaults()).isEmpty();
        assertThat(f.definitions.snapshot(NONE).defaultSelection()).isEmpty();
        f.service.logoutPi(OPENAI, NONE);
        long missing = save(f, OPENAI);
        Files.delete(secretPath(f, f.pi.snapshot(NONE).find(OPENAI).orElseThrow()));
        assertThatThrownBy(() -> f.service.activatePiLogin(OPENAI, new CredentialVersion.PiAuthEpoch(missing), NONE))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(f.leases.fenced(OPENAI)).isTrue();
    }

    @Test void logoutUsesIdentityEpochNotLatestIndexAndRejectsLateSave() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_FORBIDDEN"); });
        long epoch = save(f, OPENAI);
        var ticket = f.service.preparePiLogout(OPENAI, "session", NONE);
        save(f, identity("deepseek")); // 别的身份更新索引不应使本身份确认票据失效。
        long inputGeneration = f.pi.snapshot(NONE).generation();
        try (var transaction = f.pi.beginModify(OPENAI, epoch, NONE)) {
            f.service.commitLogout(ticket.confirmationId(), "session", NONE);
            assertThatThrownBy(() -> transaction.finish(PiCredentialStore.Change.KEEP, null, NONE))
                    .isInstanceOf(ProviderAuthException.class);
        }
        try (var material = synthetic()) {
            assertThatThrownBy(() -> f.pi.saveLogin(OPENAI, material, inputGeneration, false, NONE))
                    .isInstanceOf(ProviderAuthException.class);
        }
        assertThat(f.pi.snapshot(NONE).find(identity("deepseek"))).isPresent();
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isEmpty();
    }

    @Test void staleDeleteCasAndCancelledDrainKeepFenceWithoutActivationPermission() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_FORBIDDEN"); });
        save(f, OPENAI);
        var ticket = f.service.preparePiLogout(OPENAI, "session", NONE);
        long replacement = save(f, OPENAI);
        assertThatThrownBy(() -> f.service.commitLogout(ticket.confirmationId(), "session", NONE))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(f.leases.fenced(OPENAI)).isTrue();
        assertThatThrownBy(() -> f.service.activatePiLogin(OPENAI, new CredentialVersion.PiAuthEpoch(replacement), NONE))
                .isInstanceOf(ProviderAuthException.class);
        var cancelled = new io.github.liumaishenjian.ccjava.core.CancellationSource(Duration.ofSeconds(5));
        var second = f.service.preparePiLogout(OPENAI, "session", NONE);
        cancelled.cancel();
        assertThatThrownBy(() -> f.service.commitLogout(second.confirmationId(), "session", cancelled.token()))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isPresent();
        assertThat(f.leases.fenced(OPENAI)).isTrue();
    }

    @Test void legacyAndPiTicketsShareCapacityExpiryAndOneShotSessionBinding() throws Exception {
        Fixture f = fixture(null, () -> { throw new AssertionError("CONFIG_FORBIDDEN"); });
        legacy(f); save(f, OPENAI);
        var wrong = f.service.preparePiLogout(OPENAI, "original", NONE);
        assertThatThrownBy(() -> f.service.commitLogout(wrong.confirmationId(), "wrong", NONE))
                .isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> f.service.commitLogout(wrong.confirmationId(), "original", NONE))
                .isInstanceOf(ProviderAuthException.class);
        for (int i = 0; i < 8; i++) {
            f.service.prepareLogout("openai", "default", "session", NONE);
            f.service.preparePiLogout(OPENAI, "session", NONE);
        }
        assertThatThrownBy(() -> f.service.preparePiLogout(OPENAI, "session", NONE)).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> f.service.prepareLogout("openai", "default", "session", NONE)).isInstanceOf(ProviderAuthException.class);
        f.clock.advance(120_000);
        var expired = f.service.preparePiLogout(OPENAI, "session", NONE);
        f.clock.advance(120_000);
        assertThatThrownBy(() -> f.service.commitLogout(expired.confirmationId(), "session", NONE))
                .isInstanceOf(ProviderAuthException.class);
        var valid = f.service.preparePiLogout(OPENAI, "session", NONE);
        f.service.commitLogout(valid.confirmationId(), "session", NONE);
        assertThatThrownBy(() -> f.service.commitLogout(valid.confirmationId(), "session", NONE))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(f.legacy.snapshot(NONE).find("openai", "default")).isPresent();
    }

    @Test void externalCloseAfterSlotReleaseCannotReturnLoginSuccess() throws Exception {
        Fixture[] owner = new Fixture[1];
        Fixture f = fixture((configuration, store, identity, expected, interaction, token, timeout) ->
                new ProviderAuthApplicationService.PiOperation() {
                    public PiLoginOperation.Receipt run() {
                        try {
                            // 只在测试中把finished通知作为精确调度点，不修改生产状态迁移。
                            var slotField = ProviderAuthApplicationService.class.getDeclaredField("piLogin");
                            slotField.setAccessible(true);
                            Object slot = slotField.get(owner[0].service);
                            var finished = slot.getClass().getDeclaredField("finished");
                            finished.setAccessible(true);
                            finished.set(slot, new CountDownLatch(1) {
                                @Override public void countDown() {
                                    owner[0].service.closePiLogin();
                                    super.countDown();
                                }
                            });
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                        try (var material = PiCredentialMaterial.apiKey("synthetic".getBytes(StandardCharsets.US_ASCII))) {
                            var saved = store.saveLogin(identity, material, expected, false, token);
                            return new PiLoginOperation.Receipt(identity, saved.authEpoch());
                        }
                    }
                    public void close() { }
                }, () -> null);
        owner[0] = f;
        assertThatThrownBy(() -> f.service.loginPi(OPENAI, UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
        // 已发布的材料不虚构回滚；只拒绝把关闭后的宿主报告为登录成功。
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isPresent();
    }

    @Test void activeRunAndSingleLoginAreMutuallyExclusiveAndNoMonitorWaitsForWorker() throws Exception {
        BlockingOperation operation = new BlockingOperation(false);
        Fixture f = fixture((configuration, store, identity, expected, interaction, token, timeout) -> {
            assertThat(timeout).isEqualTo(Duration.ofSeconds(300));
            return operation;
        }, () -> null);
        legacy(f);
        try (var ignored = f.service.beginRun()) {
            assertThatThrownBy(() -> f.service.loginPi(OPENAI, UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
        }
        CompletableFuture<?> running = CompletableFuture.runAsync(() -> f.service.loginPi(OPENAI, UNUSED, NONE));
        assertThat(operation.entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(() -> f.service.loginPi(identity("deepseek"), UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(() -> f.service.login(new ProviderAuthApplicationService.LoginRequest(
                    "openai", "default", ProviderAuthApplicationService.RefKind.ENV, "SYNTHETIC_UNUSED_ENV", false),
                    null, NONE)).isInstanceOf(ProviderAuthException.class);
            // 若持 selectionMonitor 等待 Worker，此读取本身或 close 将无法结束。
            f.service.closePiLogin();
            assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        } finally { operation.release.countDown(); }
    }

    @Test void logoutFencesBeforeClosingLoginAndBlocksNewLoginUntilDeletion() throws Exception {
        BlockingOperation operation = new BlockingOperation(false);
        Fixture f = fixture((c, s, i, e, interaction, token, timeout) -> operation, () -> null);
        save(f, OPENAI);
        operation.onClose = () -> {
            assertThat(f.leases.fenced(OPENAI)).isTrue();
            assertThatThrownBy(() -> f.service.loginPi(identity("deepseek"), UNUSED, NONE))
                    .isInstanceOf(ProviderAuthException.class);
        };
        CompletableFuture<?> running = CompletableFuture.runAsync(() -> f.service.loginPi(OPENAI, UNUSED, NONE));
        assertThat(operation.entered.await(5, TimeUnit.SECONDS)).isTrue();
        f.service.logoutPi(OPENAI, NONE);
        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isEmpty();
        assertThat(f.leases.fenced(OPENAI)).isTrue();
    }

    @Test void cleanupFailureRetainsSlotFenceAndStickyCloseEvenAfterRunFinishes() throws Exception {
        BlockingOperation operation = new BlockingOperation(true);
        Fixture f = fixture((c, s, i, e, interaction, token, timeout) -> operation, () -> null);
        save(f, OPENAI);
        CompletableFuture<?> running = CompletableFuture.runAsync(() -> f.service.loginPi(OPENAI, UNUSED, NONE));
        assertThat(operation.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> f.service.logoutPi(OPENAI, NONE)).isInstanceOf(PiWorkerException.class);
        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isPresent();
        assertThat(f.leases.fenced(OPENAI)).isTrue();
        assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> f.service.loginPi(identity("deepseek"), UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(f.service::closePiLogin).isInstanceOf(PiWorkerException.class);
        assertThatThrownBy(f.service::closePiLogin).isInstanceOf(PiWorkerException.class);
    }

    @Test void unconfirmedFactoryCleanupKeepsSlotAfterLateResourceIsClosed() throws Exception {
        CountDownLatch acquiring = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> {
            acquiring.countDown();
            try { if (!releaseFactory.await(10, TimeUnit.SECONDS)) throw new AssertionError("WAIT_TIMEOUT"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new ProviderAuthApplicationService.PiOperation() {
                public PiLoginOperation.Receipt run() { throw new AssertionError("LATE_RUN_FORBIDDEN"); }
                public void close() { closes.incrementAndGet(); }
            };
        }, () -> null);
        save(f, OPENAI);
        CompletableFuture<?> running = CompletableFuture.runAsync(() -> f.service.loginPi(OPENAI, UNUSED, NONE));
        assertThat(acquiring.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> f.service.logoutPi(OPENAI, NONE)).isInstanceOf(PiWorkerException.class);
        } finally { releaseFactory.countDown(); }
        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(closes.get()).isPositive();
        assertThat(f.leases.fenced(OPENAI)).isTrue();
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isPresent();
        assertThatThrownBy(f.service::beginRun).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> f.service.loginPi(identity("deepseek"), UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(f.service::closePiLogin).isInstanceOf(PiWorkerException.class);
    }

    @Test void confirmedOrdinaryFailureReleasesSlotAndAckFailureDoesNotPretendRollback() throws Exception {
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> new ProviderAuthApplicationService.PiOperation() {
            public PiLoginOperation.Receipt run() {
                try (var material = synthetic()) { store.saveLogin(identity, material, expected, false, token); }
                throw new PiWorkerException(PiWorkerException.Code.CLOSED);
            }
            public void close() { }
        }, () -> null);
        assertThatThrownBy(() -> f.service.loginPi(OPENAI, UNUSED, NONE)).isInstanceOf(PiWorkerException.class);
        assertThat(f.pi.snapshot(NONE).find(OPENAI)).isPresent();
        assertThat(f.service.nextSelection()).isEmpty();
        try (var ignored = f.service.beginRun()) { assertThat(f.pi.snapshot(NONE).providerDefaults()).isEmpty(); }
    }

    @Test void serviceVerifiesReceiptEpochInsteadOfActivatingReplacement() throws Exception {
        Fixture f = fixture((c, store, identity, expected, interaction, token, timeout) -> new ProviderAuthApplicationService.PiOperation() {
            public PiLoginOperation.Receipt run() {
                try (var material = synthetic()) {
                    long original = store.saveLogin(identity, material, expected, false, token).authEpoch();
                    store.saveLogin(identity, material, store.snapshot(token).generation(), false, token);
                    return new PiLoginOperation.Receipt(identity, original);
                }
            }
            public void close() { }
        }, () -> null);
        save(f, OPENAI); f.service.logoutPi(OPENAI, NONE);
        assertThatThrownBy(() -> f.service.loginPi(OPENAI, UNUSED, NONE)).isInstanceOf(ProviderAuthException.class);
        assertThat(f.leases.fenced(OPENAI)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "deepseek", "qwen-token-plan-cn"})
    void realOptInWorkerSyntheticApiKeyThroughSharedService(String provider) throws Exception {
        String executable = System.getProperty("codej.test.nodeExecutable");
        assumeTrue(executable != null && !executable.isBlank(), "REAL_NODE_NOT_ENABLED");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("cc-java-provider-pi"))) root = root.getParent();
        assertThat(root).isNotNull();
        var configuration = new PiWorkerConfiguration(Path.of(executable), root.resolve("cc-java-provider-pi/worker.mjs"), Map.of());
        Fixture f = fixture(null, () -> configuration);
        AtomicInteger prompts = new AtomicInteger();
        PiLoginOperation.Interaction interaction = new PiLoginOperation.Interaction() {
            public PiLoginOperation.PromptHandle request(PiLoginOperation.Prompt prompt) {
                prompts.incrementAndGet();
                assertThat(prompt.kind()).isEqualTo("secret");
                return new PiLoginOperation.PromptHandle() {
                    public java.util.concurrent.CompletionStage<SecretMaterial> result() {
                        char[] input = "synthetic-service-key".toCharArray();
                        try { return CompletableFuture.completedFuture(new SecretMaterial(input)); }
                        finally { java.util.Arrays.fill(input, '\0'); }
                    }
                    public void close() { }
                };
            }
            public void authorizationUrl(URI uri) { throw new AssertionError("OAUTH_FORBIDDEN"); }
        };
        try {
            var result = f.service.loginPi(identity(provider), interaction, NONE);
            assertThat(result.backend()).isEqualTo("pi");
            assertThat(result.status()).isEqualTo("CONFIGURED_UNVERIFIED");
            assertThat(result.toString()).doesNotContain("synthetic-service-key", "authEpoch", "secretRef");
            assertThat(prompts).hasValue(1);
            assertThat(f.pi.snapshot(NONE).find(identity(provider))).isPresent();
            assertThat(f.pi.snapshot(NONE).providerDefaults()).isEmpty();
            assertThat(f.legacy.snapshot(NONE).profiles()).isEmpty();
            assertThat(f.definitions.snapshot(NONE).defaultSelection()).isEmpty();
            assertThat(f.service.nextSelection()).isEmpty();
            f.service.logoutPi(identity(provider), NONE);
            assertThat(f.pi.snapshot(NONE).find(identity(provider))).isEmpty();
            assertThat(f.service.loginPi(identity(provider), interaction, NONE).status()).isEqualTo("CONFIGURED_UNVERIFIED");
            assertThat(f.leases.fenced(identity(provider))).isFalse();
            assertThat(prompts).hasValue(2);
            assertThat(f.service.nextSelection()).isEmpty();
            assertThat(f.pi.snapshot(NONE).providerDefaults()).isEmpty();
        } finally { f.service.closePiLogin(); f.leases.close(); }
    }

    private Fixture fixture(ProviderAuthApplicationService.PiOperationFactory factory,
                            Supplier<PiWorkerConfiguration> configuration) throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home-" + java.util.UUID.randomUUID()));
        Path repository = Files.createDirectory(temporary.resolve("repo-" + java.util.UUID.randomUUID()));
        var definitions = new ProviderDefinitionStore(home);
        var legacy = new RestrictedFileCredentialStore(home);
        var migration = new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(repository), definitions, legacy);
        var pi = new PiCredentialStore(home);
        var leases = new CredentialLeaseRegistry();
        var clock = new MutableClock();
        ProviderProbePort probe = (d, m, s, timeout, token) -> { throw new AssertionError("PROBE_FORBIDDEN"); };
        var service = factory == null
                ? new ProviderAuthApplicationService(definitions, legacy, migration, Map.of(), leases, probe, clock, pi, configuration)
                : new ProviderAuthApplicationService(definitions, legacy, migration, Map.of(), leases, probe, clock, pi, configuration, factory);
        return new Fixture(home, definitions, legacy, migration, pi, leases, clock, service);
    }
    private static void legacy(Fixture f) {
        f.service.addCompatibleProvider(new ProviderAuthApplicationService.AddProviderRequest(
                "openai", "Synthetic", "https://synthetic.example/v1", "synthetic-model"), NONE);
        f.service.login(new ProviderAuthApplicationService.LoginRequest("openai", "default",
                ProviderAuthApplicationService.RefKind.ENV, "SYNTHETIC_UNUSED_ENV", true), null, NONE);
    }
    private static long save(Fixture f, PiCredentialIdentity identity) {
        try (var material = synthetic()) {
            return f.pi.saveLogin(identity, material, f.pi.snapshot(NONE).generation(), false, NONE).authEpoch();
        }
    }
    private static PiCredentialMaterial synthetic() {
        return PiCredentialMaterial.apiKey("synthetic-service-key".getBytes(StandardCharsets.US_ASCII));
    }
    private static Path secretPath(Fixture f, PiCredentialStore.Metadata metadata) {
        return f.home.resolve(".cc-java/auth/pi/secrets/" + metadata.secretRef().orElseThrow() + ".json");
    }
    private static PiCredentialIdentity identity(String provider) {
        return new PiCredentialIdentity("pi", provider, PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }
    private record Fixture(Path home, ProviderDefinitionStore definitions, RestrictedFileCredentialStore legacy,
            LegacyCredentialMigrationService migration, PiCredentialStore pi, CredentialLeaseRegistry leases,
            MutableClock clock, ProviderAuthApplicationService service) { }
    private static final class MutableClock extends Clock {
        private long millis;
        void advance(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
    /** 可确认关闭或故意失败的本地协调器替身；不创建进程，不触碰输入材料。 */
    private static final class BlockingOperation implements ProviderAuthApplicationService.PiOperation {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean failClose;
        private Runnable onClose = () -> { };
        private BlockingOperation(boolean failClose) { this.failClose = failClose; }
        public PiLoginOperation.Receipt run() {
            entered.countDown();
            try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("WAIT_TIMEOUT"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new PiWorkerException(PiWorkerException.Code.CLOSED);
        }
        public void close() {
            try { onClose.run(); }
            finally { release.countDown(); }
            if (failClose) throw new PiWorkerException(PiWorkerException.Code.CLEANUP_FAILED);
        }
    }
}
