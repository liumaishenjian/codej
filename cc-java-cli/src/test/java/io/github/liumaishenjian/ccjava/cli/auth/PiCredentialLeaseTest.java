package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Pi身份版本不进入legacy键空间；删除水位来自实际事务，不借用后续snapshot。 */
class PiCredentialLeaseTest {
    private static final CancellationToken NONE = CancellationToken.none();
    @TempDir Path home;
    private static PiCredentialIdentity pi(String provider) {
        return new PiCredentialIdentity(provider, PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }

    @Test void sameNamesInLegacyAndPiNeverShareFenceOrGeneration() {
        var closed = new AtomicInteger();
        try (var registry = new CredentialLeaseRegistry();
                var legacy = registry.acquire("openai", "default", 99, closed::incrementAndGet)) {
            try (var lease = registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(1), () -> {})) {
                assertThat(registry.activeCount("openai", "default")).isEqualTo(1);
                assertThat(registry.activeCount(pi("openai"))).isEqualTo(1);
            }
            assertThat(registry.fenceAndDrain(pi("openai"), Duration.ofSeconds(1), NONE)).isTrue();
            registry.markRevoked(pi("openai"), 2);
            assertThat(registry.fenced("openai", "default")).isFalse();
            assertThat(closed.get()).isZero();
            assertThatThrownBy(() -> registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(3), () -> {})).isInstanceOf(ProviderAuthException.class);
            registry.activateAfterLogin(pi("openai"), new CredentialVersion.PiAuthEpoch(3));
            try (var fresh = registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(3), () -> {})) {
                assertThat(fresh.generation()).isEqualTo(3);
            }
            assertThatThrownBy(() -> registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(1), () -> {})).isInstanceOf(ProviderAuthException.class);
        }
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test void cleanupFailureCannotConfirmPiDrainOrAffectLegacyIdentity() {
        try (var registry = new CredentialLeaseRegistry()) {
            var failed = registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(1), () -> {throw new IllegalStateException("synthetic");});
            failed.close();
            assertThat(failed.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
            failed.close();
            assertThat(failed.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
            assertThat(registry.fenced(pi("openai"))).isTrue();
            assertThatThrownBy(() -> registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(1), () -> {}))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(registry.fenceAndDrain(pi("openai"), Duration.ofMillis(20), NONE)).isFalse();
            assertThat(registry.activeCount(pi("openai"))).isEqualTo(1);
            assertThatThrownBy(() -> registry.markRevoked(pi("openai"), 2)).isInstanceOf(ProviderAuthException.class);
            assertThat(registry.fenced("openai", "default")).isFalse();
        }
    }

    @Test void normalCloseHasExplicitReceiptRatherThanOnlyReturningNormally() {
        try (var registry = new CredentialLeaseRegistry()) {
            var lease = registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(1), () -> {});
            assertThat(lease.cleanupStatus()).isEqualTo(ResourceCleanupStatus.NOT_STARTED);
            lease.close();
            assertThat(lease.cleanupStatus()).isEqualTo(ResourceCleanupStatus.RELEASED);
            assertThat(registry.activeCount(pi("openai"))).isZero();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void blockedResourceOrCancellationIsCleaningUntilBothFinish(boolean blockCancellation) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var resourceReturned = new CountDownLatch(1);
        Runnable block = () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture cleanup wait expired");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        try (var registry = new CredentialLeaseRegistry()) {
            var lease = registry.acquire(pi("openai"), new CredentialVersion.PiAuthEpoch(1), () -> {
                if (!blockCancellation) block.run();
                resourceReturned.countDown();
            });
            lease.bindCancellation(blockCancellation ? block : () -> {});
            try {
                assertThat(registry.fenceAndDrain(pi("openai"), Duration.ZERO, NONE)).isFalse();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                lease.close();
                if (blockCancellation) assertThat(resourceReturned.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(lease.cleanupStatus()).isEqualTo(ResourceCleanupStatus.CLEANING);
                assertThat(registry.activeCount(pi("openai"))).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(registry.fenceAndDrain(pi("openai"), Duration.ofSeconds(2), NONE)).isTrue();
            assertThat(lease.cleanupStatus()).isEqualTo(ResourceCleanupStatus.RELEASED);
        }
    }

    @Test void deletionReceiptIsNotAConcurrentLaterIndexGeneration() {
        var store = new PiCredentialStore(home);
        var identity = pi("openai");
        try (var material = PiCredentialMaterial.apiKey("synthetic".getBytes(StandardCharsets.US_ASCII))) {
            store.saveLogin(identity, material, 0, false, NONE);
            long deleted = store.deleteWithReceipt(identity, 1, NONE);
            store.saveLogin(pi("deepseek"), material, 2, false, NONE);
            assertThat(deleted).isEqualTo(2);
            assertThat(store.snapshot(NONE).generation()).isEqualTo(3);
            var fresh = store.saveLogin(identity, material, 3, false, NONE);
            try (var registry = new CredentialLeaseRegistry()) {
                assertThat(registry.fenceAndDrain(identity, Duration.ZERO, NONE)).isTrue();
                registry.markRevoked(identity, deleted);
                registry.activateAfterLogin(identity, new CredentialVersion.PiAuthEpoch(fresh.authEpoch()));
                try (var lease = registry.acquire(identity, new CredentialVersion.PiAuthEpoch(fresh.authEpoch()), () -> {})) {
                    assertThat(lease.generation()).isEqualTo(4);
                }
            }
        }
    }
}
