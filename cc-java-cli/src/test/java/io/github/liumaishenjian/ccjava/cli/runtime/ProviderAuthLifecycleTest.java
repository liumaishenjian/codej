package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class ProviderAuthLifecycleTest {
    @TempDir Path root;
    private static final CancellationToken NONE = CancellationToken.none();

    private record Fixture(Path home, RestrictedFileCredentialStore store, CredentialLeaseRegistry leases,
                           ProviderAuthApplicationService service) { }
    private Fixture fixture() throws Exception { return fixture(Clock.systemUTC()); }
    private Fixture fixture(Clock clock) throws Exception {
        Path home = Files.createDirectory(root.resolve("home"));
        var store = new RestrictedFileCredentialStore(home);
        var definitions = new ProviderDefinitionStore(home);
        var leases = new CredentialLeaseRegistry();
        var migration = new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(root), definitions, store);
        var service = new ProviderAuthApplicationService(definitions, store, migration, Map.of("FIXTURE_KEY", "canary-value"),
                leases, (d,m,s,t,c) -> io.github.liumaishenjian.ccjava.cli.provider.probe.ProviderProbePort.ProbeOutcome.SUCCESS,
                clock);
        return new Fixture(home, store, leases, service);
    }
    private void save(Fixture f) {
        f.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "default",
                ProviderAuthApplicationService.RefKind.STORE, null, true),
                () -> new SecretMaterial("fixture-only-key".toCharArray()), NONE);
    }
    @Test void logoutThenLoginInSameHostRequiresExplicitNewGenerationActivation() throws Exception {
        var f = fixture();
        try (var leases = f.leases) {
            save(f);
            long old = f.store.snapshot(NONE).generation();
            var ticket = f.service.prepareLogout("anthropic", "default", "session-a", NONE);
            assertThat(f.service.commitLogout(ticket.confirmationId(), "session-a", NONE).remoteRevoked()).isFalse();
            assertThatThrownBy(f.service::routingSelection).isInstanceOf(ProviderAuthException.class);
            save(f);
            assertThat(f.leases.fenced("anthropic", "default")).isTrue();
            f.service.listProfiles(Optional.empty(), NONE);
            assertThat(f.leases.fenced("anthropic", "default")).isTrue();
            f.service.activateLogin("anthropic", "default", NONE);
            assertThat(f.leases.fenced("anthropic", "default")).isFalse();
            assertThatThrownBy(() -> leases.acquire("anthropic", "default", old, () -> {})).isInstanceOf(ProviderAuthException.class);
            try (var lease = leases.acquire("anthropic", "default", f.store.snapshot(NONE).generation(), () -> {})) {
                assertThat(lease.generation()).isGreaterThan(old);
            }
        }
    }
    @Test void confirmationCannotDeleteReplacementOrReplayAfterRejection() throws Exception {
        var f = fixture();
        try (var ignored = f.leases) {
            save(f);
            var ticket = f.service.prepareLogout("anthropic", "default", "a", NONE);
            save(f);
            assertThatThrownBy(() -> f.service.commitLogout(ticket.confirmationId(), "a", NONE)).isInstanceOf(ProviderAuthException.class);
            assertThat(f.store.snapshot(NONE).profiles()).hasSize(1);
            assertThat(f.leases.fenced("anthropic", "default")).isFalse();
            assertThatThrownBy(() -> f.service.commitLogout(ticket.confirmationId(), "a", NONE)).isInstanceOf(ProviderAuthException.class);
        }
    }
    @Test void confirmationBelongsToOriginalSessionAndIsBounded() throws Exception {
        var f = fixture();
        try (var ignored = f.leases) {
            save(f);
            var ticket = f.service.prepareLogout("anthropic", "default", "a", NONE);
            assertThatThrownBy(() -> f.service.commitLogout(ticket.confirmationId(), "b", NONE)).isInstanceOf(ProviderAuthException.class);
            for (int i = 0; i < 16; i++) f.service.prepareLogout("anthropic", "default", "a", NONE);
            assertThatThrownBy(() -> f.service.prepareLogout("anthropic", "default", "a", NONE)).isInstanceOf(ProviderAuthException.class);
            assertThat(f.store.snapshot(NONE).profiles()).hasSize(1);
        }
    }
    @Test void loginCapturesGenerationBeforeWaitingForInputAcrossStoreInstances() throws Exception {
        var f = fixture();
        try (var ignored = f.leases) {
            save(f);
            var request = new ProviderAuthApplicationService.LoginRequest("anthropic", "default",
                    ProviderAuthApplicationService.RefKind.STORE, null, true);
            assertThatThrownBy(() -> f.service.login(request, () -> {
                var other = new RestrictedFileCredentialStore(f.home);
                other.delete("anthropic", "default", other.snapshot(NONE).generation(), NONE);
                return new SecretMaterial("late-canary".toCharArray());
            }, NONE)).isInstanceOf(ProviderAuthException.class);
            assertThat(new RestrictedFileCredentialStore(f.home).snapshot(NONE).profiles()).isEmpty();
        }
    }
    @Test void cancellingBeforeConfirmationDoesNotDeleteAndEnvironmentValueIsNotModified() throws Exception {
        var f = fixture();
        try (var ignored = f.leases) {
            f.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "default",
                    ProviderAuthApplicationService.RefKind.ENV, "FIXTURE_KEY", true), null, NONE);
            f.service.prepareLogout("anthropic", "default", "a", NONE);
            assertThat(f.store.snapshot(NONE).profiles()).hasSize(1);
            f.service.logout("anthropic", "default", NONE);
            assertThat(f.store.snapshot(NONE).profiles()).isEmpty();
            assertThatThrownBy(f.service::routingSelection).isInstanceOf(ProviderAuthException.class);
        }
    }
    @Test void oldEnvProbeCannotWriteMetadataAfterLogoutAndSameReferenceRelogin() throws Exception {
        var atPublication = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() {
                Runnable action = atPublication.getAndSet(null);
                if (action != null) action.run();
                return java.time.Instant.now();
            }
        };
        var f = fixture(clock);
        try (var ignored = f.leases) {
            var login = new ProviderAuthApplicationService.LoginRequest("anthropic", "default",
                    ProviderAuthApplicationService.RefKind.ENV, "FIXTURE_KEY", true);
            f.service.login(login, null, NONE);
            String model = f.service.listModels(Optional.of("anthropic"), NONE).getFirst().modelId();
            // service在释放probe lease后取时钟；精确插入退出与同名ENV重新登录，无sleep或线程碰运气。
            atPublication.set(() -> {
                assertThat(f.leases.activeCount("anthropic", "default")).isZero();
                f.service.logout("anthropic", "default", NONE);
                f.service.login(login, null, NONE);
            });
            assertThatThrownBy(() -> f.service.probe(new ProviderAuthApplicationService.ProbeRequest(
                    "anthropic", "default", model, java.time.Duration.ofSeconds(2)), NONE))
                    .isInstanceOfSatisfying(ProviderAuthException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
            assertThat(f.store.snapshot(NONE).find("anthropic", "default")).hasValueSatisfying(
                    profile -> assertThat(profile.lastProbe()).isEmpty());
        }
    }
    @Test void activeRunRejectsActivationAndLoginBeforeConsumingSecret() throws Exception {
        var f = fixture();
        try (var ignored = f.leases) {
            save(f);
            try (var run = f.service.beginRun()) {
                assertThatThrownBy(() -> f.service.activateLogin("anthropic", "default", NONE)).isInstanceOf(ProviderAuthException.class);
                assertThatThrownBy(() -> f.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "default",
                        ProviderAuthApplicationService.RefKind.STORE, null, true),
                        () -> { throw new AssertionError("must not read input"); }, NONE)).isInstanceOf(ProviderAuthException.class);
            }
        }
    }
}
