package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** generation、fence、cancel/close 与 terminal 生命周期契约测试。 */
class CredentialLeaseRegistryTest {
    @Test void reactivationRequiresConfirmedDeletionAndPermanentlyRejectsOldEpoch() {
        try (var registry = new CredentialLeaseRegistry()) {
            registry.activateAfterLogin("openrouter", "work", 1); // 普通未 fenced 登录不建立限制。
            try (var lease = registry.acquire("openrouter", "work", 0, () -> { })) { }
            assertThat(registry.fenceAndDrain("openrouter", "work", Duration.ZERO, CancellationToken.none())).isTrue();
            assertThatThrownBy(() -> registry.activateAfterLogin("openrouter", "work", 10))
                    .isInstanceOf(ProviderAuthException.class);
            registry.markRevoked("openrouter", "work", 2);
            assertThatThrownBy(() -> registry.activateAfterLogin("openrouter", "work", 2))
                    .isInstanceOf(ProviderAuthException.class);
            registry.activateAfterLogin("openrouter", "work", 3);
            try (var lease = registry.acquire("openrouter", "work", 3, () -> { })) { }
            assertThatThrownBy(() -> registry.acquire("openrouter", "work", 2, () -> { }))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(registry.fenceAndDrain("openrouter", "work", Duration.ZERO, CancellationToken.none())).isTrue();
            assertThatThrownBy(() -> registry.activateAfterLogin("openrouter", "work", 100))
                    .isInstanceOf(ProviderAuthException.class); // 上一 epoch 的删除证明不能复用。
            registry.markRevoked("openrouter", "work");
            assertThatThrownBy(() -> registry.markRevoked("openrouter", "work", 4))
                    .isInstanceOf(ProviderAuthException.class);
        }
    }

    @Test void lateCancellationIsDeliveredOnceAndCloseIsIdempotent() throws Exception {
        try (var registry = new CredentialLeaseRegistry()) {
            AtomicInteger closes = new AtomicInteger();
            AtomicInteger cancels = new AtomicInteger();
            CountDownLatch delivered = new CountDownLatch(1);
            var lease = registry.acquire("openrouter", "late", 1, closes::incrementAndGet);
            assertThat(registry.fenceAndDrain("openrouter", "late", Duration.ZERO, CancellationToken.none())).isFalse();
            lease.bindCancellation(() -> { cancels.incrementAndGet(); delivered.countDown(); });
            assertThat(delivered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            lease.close(); lease.close();
            assertThat(registry.fenceAndDrain("openrouter", "late", Duration.ofSeconds(2), CancellationToken.none())).isTrue();
            assertThat(cancels).hasValue(1);
            assertThat(closes).hasValue(1);
        }
    }

    @Test void blockingCloseAndCancellationRespectDrainDeadlineWithoutFalseTerminal() throws Exception {
        try (var registry = new CredentialLeaseRegistry()) {
            CountDownLatch enteredClose = new CountDownLatch(1), enteredCancel = new CountDownLatch(1);
            CountDownLatch releaseClose = new CountDownLatch(1), releaseCancel = new CountDownLatch(1);
            AtomicInteger closes = new AtomicInteger();
            var lease = registry.acquire("openrouter", "blocked", 1, () -> {
                closes.incrementAndGet(); enteredClose.countDown(); releaseClose.await();
            });
            lease.bindCancellation(() -> {
                enteredCancel.countDown();
                try { releaseCancel.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            try {
                long started = System.nanoTime();
                assertThat(registry.fenceAndDrain("openrouter", "blocked", Duration.ofMillis(100),
                        CancellationToken.none())).isFalse();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(enteredClose.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(enteredCancel.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                lease.close(); lease.close();
                assertThat(registry.activeCount("openrouter", "blocked")).isOne();
                assertThat(registry.fenced("openrouter", "blocked")).isTrue();
                assertThatThrownBy(() -> registry.markRevoked("openrouter", "blocked", 2))
                        .isInstanceOf(ProviderAuthException.class);
                assertThatThrownBy(() -> registry.activateAfterLogin("openrouter", "blocked", 3))
                        .isInstanceOf(ProviderAuthException.class);
            } finally { releaseClose.countDown(); releaseCancel.countDown(); lease.close(); }
            assertThat(registry.fenceAndDrain("openrouter", "blocked", Duration.ofSeconds(2),
                    CancellationToken.none())).isTrue();
            assertThat(closes).hasValue(1);
        }
    }

    @Test void cancelledDrainKeepsFenceAndCannotActivateWithoutDeletionProof() {
        try (var registry = new CredentialLeaseRegistry()) {
            var source = new io.github.liumaishenjian.ccjava.core.CancellationSource();
            source.cancel();
            assertThat(registry.fenceAndDrain("anthropic", "personal", Duration.ofSeconds(1), source.token())).isFalse();
            assertThat(registry.fenced("anthropic", "personal")).isTrue();
            assertThatThrownBy(() -> registry.markRevoked("anthropic", "personal", 9))
                    .isInstanceOf(ProviderAuthException.class);
            assertThatThrownBy(() -> registry.activateAfterLogin("anthropic", "personal", 10))
                    .isInstanceOf(ProviderAuthException.class);
        }
    }

    @Test void failedResourceCloseCannotClaimTerminal() {
        try (var registry = new CredentialLeaseRegistry()) {
            var lease = registry.acquire("openrouter", "failed", 1, () -> { throw new Exception("synthetic"); });
            lease.close(); lease.close();
            assertThat(registry.fenceAndDrain("openrouter", "failed", Duration.ofMillis(10),
                    CancellationToken.none())).isFalse();
            assertThat(registry.activeCount("openrouter", "failed")).isOne();
        }
    }

    @Test void generationCannotChangeWhileProfileHasActiveRuns() {
        CredentialLeaseRegistry registry=new CredentialLeaseRegistry();
        var first=registry.acquire("anthropic","personal",4,()->{});
        assertThatThrownBy(()->registry.acquire("anthropic","personal",5,()->{}))
                .isInstanceOf(ProviderAuthException.class);
        first.close();
        try(var next=registry.acquire("anthropic","personal",5,()->{})) {
            assertThat(next.generation()).isEqualTo(5);
        }
    }

    @Test void finalLeaseCloseAllowsRepeatedFutureGenerationsWithoutStaleState() {
        try (CredentialLeaseRegistry registry = new CredentialLeaseRegistry()) {
            for (long generation = 1; generation <= 100; generation++) {
                long current = generation;
                var first = registry.acquire("anthropic", "personal", current, () -> { });
                var second = registry.acquire("anthropic", "personal", current, () -> { });
                first.close();
                assertThatThrownBy(() -> registry.acquire("anthropic", "personal", current + 1, () -> { }))
                        .isInstanceOf(ProviderAuthException.class);
                second.close();
                try (var next = registry.acquire("anthropic", "personal", current + 1, () -> { })) {
                    assertThat(next.generation()).isEqualTo(current + 1);
                }
            }
        }
    }

    @Test void fenceCancelsClosesAndWaitsForExactlyOneTerminal() throws Exception {
        CredentialLeaseRegistry registry=new CredentialLeaseRegistry();
        AtomicInteger cancelled=new AtomicInteger(),closed=new AtomicInteger();
        var lease=registry.acquire("openrouter","work",7,closed::incrementAndGet);
        lease.bindCancellation(cancelled::incrementAndGet);
        CountDownLatch started=new CountDownLatch(1);
        var drainer=Thread.startVirtualThread(()->{started.countDown();assertThat(registry.fenceAndDrain(
                "openrouter","work",Duration.ofSeconds(2),CancellationToken.none())).isTrue();});
        started.await();
        while(cancelled.get()==0) Thread.onSpinWait();
        assertThat(registry.activeCount("openrouter","work")).isOne();
        lease.close(); lease.close(); drainer.join();
        assertThat(cancelled).hasValue(1); assertThat(closed).hasValue(1);
        assertThatThrownBy(()->registry.acquire("openrouter","work",7,()->{}))
                .isInstanceOf(ProviderAuthException.class);
    }
}