package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class PiCredentialStoreTest {
    @TempDir Path home;
    private static final CancellationToken NONE = CancellationToken.none();
    private static final PiCredentialIdentity OAUTH = oauthIdentity("default");
    private static final PiCredentialIdentity API = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "default");

    @Test void swappedSameKindSecretsCannotCrossProfiles() throws Exception {
        var store = new PiCredentialStore(home);
        var first = login(store, OAUTH, "first");
        var second = login(store, oauthIdentity("other"), "second");
        byte[] a = Files.readAllBytes(secret(first));
        byte[] b = Files.readAllBytes(secret(second));
        try {
            Files.write(secret(first), b, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(secret(second), a, StandardOpenOption.TRUNCATE_EXISTING);
            assertThat(store.list(NONE)).containsExactly(first, second);
            for (var entry : List.of(first, second)) {
                assertThatThrownBy(() -> {
                    try (var ignored = store.read(entry.identity(), entry.authEpoch(), NONE)) { }
                }).hasMessageContaining("AUTH_STORE_CORRUPT").hasNoCause();
            }
        } finally { java.util.Arrays.fill(a, (byte) 0); java.util.Arrays.fill(b, (byte) 0); }
    }

    enum SecretFault { REF, PROFILE, EPOCH, REVISION, VERSION, UNKNOWN, DUPLICATE, TRAILING, UTF16, BOM, RAW, INNER_BUDGET }

    @ParameterizedTest @EnumSource(SecretFault.class)
    void strictSecretEnvelopeRejectsUnboundOrMalformedContent(SecretFault fault) throws Exception {
        var store = new PiCredentialStore(home);
        var saved = login(store, OAUTH, "one");
        String original = Files.readString(secret(saved));
        String changed = switch (fault) {
            case REF -> original.replace(saved.secretRef().orElseThrow(), "0".repeat(32));
            case PROFILE -> original.replace("\"profileId\":\"default\"", "\"profileId\":\"other\"");
            case EPOCH -> original.replace("\"authEpoch\":1", "\"authEpoch\":2");
            case REVISION -> original.replace("\"materialRevision\":1", "\"materialRevision\":2");
            case VERSION -> original.replace("\"version\":1", "\"version\":2");
            case UNKNOWN -> original.replace("\"version\":1", "\"unknown\":1,\"version\":1");
            case DUPLICATE -> original.replace("\"version\":1", "\"version\":1,\"version\":1");
            case TRAILING -> original + " {}";
            case UTF16 -> original;
            case BOM -> "\ufeff" + original;
            case RAW -> "{\"type\":\"oauth\",\"access\":\"a\",\"refresh\":\"r\",\"expires\":1,\"accountId\":\"a\"}";
            case INNER_BUDGET -> original.replace("one-access", "a".repeat(16384)).replace("one-refresh", "r".repeat(9000));
        };
        byte[] bytes = changed.getBytes(fault == SecretFault.UTF16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
        try { Files.write(secret(saved), bytes, StandardOpenOption.TRUNCATE_EXISTING); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
        assertThat(store.list(NONE)).containsExactly(saved);
        assertThatThrownBy(() -> {
            try (var ignored = store.read(OAUTH, saved.authEpoch(), NONE)) { }
        }).hasMessageContaining("AUTH_STORE_CORRUPT").hasNoCause();
    }

    @Test void secretEnvelopeFileBudgetIs32KiBAndInnerBudgetIsUnchanged() throws Exception {
        var store = new PiCredentialStore(home);
        var saved = login(store, OAUTH, "one");
        String original = Files.readString(secret(saved));
        Files.writeString(secret(saved), original + " ".repeat(32768 - original.length()), StandardOpenOption.TRUNCATE_EXISTING);
        try (var material = store.read(OAUTH, saved.authEpoch(), NONE)) { assertMaterial(material, "one-access"); }
        Files.writeString(secret(saved), " ", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> store.read(OAUTH, saved.authEpoch(), NONE)).hasMessageContaining("AUTH_STORE_INSECURE");
        assertThat(PiCredentialMaterial.MAX_BYTES).isEqualTo(24576);
    }

    enum LayoutWait { CANCEL, RUNTIME_DEADLINE, STORE_DEADLINE }

    @ParameterizedTest @EnumSource(LayoutWait.class)
    void layoutQueueObservesCancellationAndDeadlineBeforeHolderReleases(LayoutWait mode) throws Exception {
        var field = PiCredentialStore.class.getDeclaredField("LAYOUT_MONITOR");
        field.setAccessible(true);
        Object layout = field.get(null);
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        var cancelled = new CancellationSource();
        CancellationToken token = mode == LayoutWait.STORE_DEADLINE ? NONE : mode == LayoutWait.CANCEL ? cancelled.token() : new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public java.util.Optional<Duration> remainingTime() {
                return java.util.Optional.of(expired.get() ? Duration.ZERO : Duration.ofSeconds(30));
            }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        var waiterThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                try {
                    if (layout instanceof java.util.concurrent.Semaphore semaphore) {
                        semaphore.acquire();
                        try { held.countDown(); release.await(); } finally { semaphore.release(); }
                    } else {
                        synchronized (layout) { held.countDown(); release.await(); }
                    }
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            try {
                assertThat(held.await(3, TimeUnit.SECONDS)).isTrue();
                var waiting = executor.submit(() -> {
                    waiterThread.set(Thread.currentThread());
                    return store(mode == LayoutWait.STORE_DEADLINE ? Duration.ofMillis(500) : Duration.ofSeconds(10),
                            point -> { }).snapshot(token);
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!(layout instanceof java.util.concurrent.Semaphore semaphore ? semaphore.hasQueuedThreads()
                        : waiterThread.get() != null && waiterThread.get().getState() == Thread.State.BLOCKED)) {
                    if (System.nanoTime() > deadline) fail("waiter did not reach layout queue");
                    Thread.sleep(1);
                }
                cancelled.cancel(); expired.set(true);
                assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(ProviderAuthException.class)
                        .cause().hasMessageContaining(mode == LayoutWait.STORE_DEADLINE ? "AUTH_STORE_LOCKED" : "AUTH_CANCELLED");
                assertThat(release.getCount()).isEqualTo(1);
            } finally { release.countDown(); holder.get(3, TimeUnit.SECONDS); }
        }
        assertThat(new PiCredentialStore(home).snapshot(NONE).generation()).isZero();
    }

    @Test void exhaustedRuntimeDeadlinePreventsCredentialPublication() {
        CancellationToken expired = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public java.util.Optional<Duration> remainingTime() { return java.util.Optional.of(Duration.ZERO); }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        var store = new PiCredentialStore(home);
        try (var value = oauth("expired")) {
            assertThatThrownBy(() -> store.saveLogin(OAUTH, value, 0, true, expired))
                    .hasMessageContaining("AUTH_CANCELLED");
        }
        assertThat(store.snapshot(NONE).generation()).isZero();
    }

    @Test void namespacesAreIsolatedAndMetadataNeverReadsSecrets() throws Exception {
        var old = new RestrictedFileCredentialStore(home);
        var legacy = old.saveStore("anthropic", "default", new SecretMaterial("old-synthetic-key".toCharArray()), true, NONE);
        Path oldIndex = home.resolve(".cc-java/auth/profiles.v1.json");
        Path oldSecret = home.resolve(".cc-java/auth/secrets/" + ((SecretRef.Store) legacy.secretRef()).secretId() + ".json");
        byte[] before = Files.readAllBytes(oldIndex);
        byte[] oldSecretBefore = Files.readAllBytes(oldSecret);
        var store = new PiCredentialStore(home);
        var entry = login(store, OAUTH, "initial");
        Path secret = secret(entry);
        Files.writeString(secret, "not-json synthetic-corruption", StandardOpenOption.TRUNCATE_EXISTING);
        assertThat(store.snapshot(NONE).find(OAUTH)).contains(entry);
        assertThat(store.list(NONE)).containsExactly(entry);
        assertThatThrownBy(() -> store.read(OAUTH, entry.authEpoch(), NONE)).hasNoCause();
        Files.delete(secret);
        assertThat(store.list(NONE)).containsExactly(entry);
        assertThatThrownBy(() -> store.read(OAUTH, entry.authEpoch(), NONE)).hasMessageContaining("AUTH_SECRET_UNAVAILABLE");
        assertThat(Files.readAllBytes(oldIndex)).isEqualTo(before);
        assertThat(Files.readAllBytes(oldSecret)).isEqualTo(oldSecretBefore);
        assertThat(new String(Files.readAllBytes(home.resolve(".cc-java/auth/pi/index.v1.json")), StandardCharsets.UTF_8))
                .doesNotContain("initial-access", "initial-refresh", "account-synthetic");
        assertThat(entry.toString() + store.snapshot(NONE)).doesNotContain(home.toString(), "default", "openai");
    }

    @Test void loginEpochRefreshRevisionAndConservativeLoginCasAreSeparate() {
        var store = new PiCredentialStore(home);
        var initial = login(store, OAUTH, "one");
        long captured = store.snapshot(NONE).generation();
        try (var transaction = store.beginModify(OAUTH, initial.authEpoch(), NONE);
             var rotation = oauth("two");
             var ack = transaction.finish(PiCredentialStore.Change.PUT, rotation, NONE)) {
            assertMaterial(ack, "two-access");
        }
        var refreshed = store.snapshot(NONE).find(OAUTH).orElseThrow();
        assertThat(refreshed.authEpoch()).isEqualTo(initial.authEpoch());
        assertThat(refreshed.materialRevision()).isEqualTo(initial.materialRevision() + 1);
        assertThat(store.snapshot(NONE).generation()).isEqualTo(captured + 1);
        try (var late = oauth("late")) {
            assertThatThrownBy(() -> store.saveLogin(OAUTH, late, captured, true, NONE)).hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
            assertThatThrownBy(() -> store.saveLogin(OAUTH, late, -1, true, NONE)).hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
        }
        var newLogin = login(store, OAUTH, "login-again");
        assertThat(newLogin.authEpoch()).isGreaterThan(refreshed.authEpoch());
        assertThat(newLogin.materialRevision()).isEqualTo(1);
    }

    @Test void logoutBypassesNetworkLockAndLateKeepAndPutCannotResurrect() {
        for (var change : PiCredentialStore.Change.values()) {
            var store = new PiCredentialStore(home);
            var initial = login(store, OAUTH, "one");
            var transaction = store.beginModify(OAUTH, initial.authEpoch(), NONE);
            new PiCredentialStore(home).delete(OAUTH, initial.authEpoch(), NONE);
            try (var candidate = change == PiCredentialStore.Change.PUT ? oauth("late") : null) {
                assertThatThrownBy(() -> transaction.finish(change, candidate, NONE)).hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
            }
            assertThat(store.snapshot(NONE).profiles()).isEmpty();
            assertThatThrownBy(transaction::snapshot).hasMessage("PI_TRANSACTION_CLOSED");
            try (var stale = oauth("stale")) {
                assertThatThrownBy(() -> store.saveLogin(OAUTH, stale, initial.authEpoch(), true, NONE)).hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
            }
        }
    }

    @Test void reloginWhileNetworkPendingInvalidatesOldEpochEvenWhenSameAccount() {
        var store = new PiCredentialStore(home);
        var initial = login(store, OAUTH, "one");
        try (var pending = store.beginModify(OAUTH, initial.authEpoch(), NONE);
             var candidate = oauth("late")) {
            var replacement = login(new PiCredentialStore(home), OAUTH, "two");
            assertThatThrownBy(() -> pending.finish(PiCredentialStore.Change.PUT, candidate, NONE))
                    .hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
            try (var actual = store.read(OAUTH, replacement.authEpoch(), NONE)) { assertMaterial(actual, "two-access"); }
        }
    }

    @Test void differentIdentityRefreshMergesNewestIndexAndKeepsDefaults() {
        var store = new PiCredentialStore(home);
        var other = distinctStripeIdentity();
        var a = login(store, OAUTH, "a"); var b = login(store, other, "b");
        try (var first = store.beginModify(OAUTH, a.authEpoch(), NONE);
             var second = new PiCredentialStore(home).beginModify(other, b.authEpoch(), NONE);
             var aNext = oauth("a-next"); var bNext = oauth("b-next");
             var bAck = second.finish(PiCredentialStore.Change.PUT, bNext, NONE);
             var aAck = first.finish(PiCredentialStore.Change.PUT, aNext, NONE)) {
            assertMaterial(aAck, "a-next-access"); assertMaterial(bAck, "b-next-access");
        }
        var truth = store.snapshot(NONE);
        assertThat(truth.generation()).isEqualTo(4);
        assertThat(truth.find(OAUTH).orElseThrow().materialRevision()).isEqualTo(2);
        assertThat(truth.find(other).orElseThrow().materialRevision()).isEqualTo(2);
        assertThat(truth.providerDefaults()).containsEntry("openai-codex", other.profileId());
    }

    @Test void apiEnvAndOauthCannotCrossKindsOrChangeAccountInRefresh() {
        var store = new PiCredentialStore(home);
        for (boolean env : List.of(false, true)) {
            try (var input = env ? PiCredentialMaterial.envRef("PI_TEST_ENV") : PiCredentialMaterial.apiKey(bytes("key"))) {
                var saved = store.saveLogin(API, input, store.snapshot(NONE).generation(), true, NONE);
                try (var transaction = store.beginModify(API, saved.authEpoch(), NONE);
                     var candidate = PiCredentialMaterial.apiKey(bytes("other-key"))) {
                    assertThatThrownBy(() -> transaction.finish(PiCredentialStore.Change.PUT, candidate, NONE))
                            .hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
                }
                try (var transaction = store.beginModify(API, saved.authEpoch(), NONE);
                     var kept = transaction.finish(PiCredentialStore.Change.KEEP, null, NONE)) {
                    assertThat(kept.kind()).isEqualTo(input.kind());
                }
                assertThatThrownBy(() -> store.saveLogin(OAUTH, input, store.snapshot(NONE).generation(), true, NONE))
                        .hasMessage("PI_MATERIAL_INVALID");
            }
        }
        var saved = login(store, OAUTH, "one");
        try (var transaction = store.beginModify(OAUTH, saved.authEpoch(), NONE);
             var changed = PiCredentialMaterial.oauth(bytes("a"), bytes("r"), 42, bytes("different-account"))) {
            assertThatThrownBy(() -> transaction.finish(PiCredentialStore.Change.PUT, changed, NONE))
                    .hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
        }
        assertThat(store.snapshot(NONE).find(OAUTH)).contains(saved);
    }

    @Test void snapshotsAreIndependentlyOwnedAndTransactionCanFinishOnDifferentThread() throws Exception {
        var store = new PiCredentialStore(home);
        var saved = login(store, OAUTH, "one");
        var transaction = store.beginModify(OAUTH, saved.authEpoch(), NONE);
        try (var independent = transaction.snapshot(); var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(transaction::close).get(5, TimeUnit.SECONDS);
            assertMaterial(independent, "one-access");
            try (var next = store.beginModify(OAUTH, saved.authEpoch(), NONE)) {
                try (var ack = executor.submit(() -> next.finish(PiCredentialStore.Change.KEEP, null, NONE)).get(5, TimeUnit.SECONDS)) {
                    assertMaterial(ack, "one-access");
                }
            }
        }
    }

    @Test void sameJvmStoresSerializeWithCancellationAndTimeoutAndLockFilesStayBounded() throws Exception {
        var store = new PiCredentialStore(home); var saved = login(store, OAUTH, "one");
        try (var held = store.beginModify(OAUTH, saved.authEpoch(), NONE)) {
            var impatient = store(Duration.ofMillis(150), point -> { });
            assertThatThrownBy(() -> impatient.beginModify(OAUTH, saved.authEpoch(), NONE)).hasMessageContaining("AUTH_STORE_LOCKED");
            var source = new CancellationSource();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting = executor.submit(() -> new PiCredentialStore(home).beginModify(OAUTH, saved.authEpoch(), source.token()));
                Thread.sleep(60); source.cancel();
                assertThatThrownBy(() -> waiting.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(ProviderAuthException.class)
                        .cause().hasMessageContaining("AUTH_CANCELLED");
            }
        }
        try (var reopened = store.beginModify(OAUTH, saved.authEpoch(), NONE)) { assertThat(reopened.metadata()).isEqualTo(saved); }
        try (var files = Files.list(home.resolve(".cc-java/auth/pi"))) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith(".modify-")).count()).isEqualTo(1);
        }
        for (int i = 0; i < 1000; i++) assertThat(PiCredentialStore.stripe(oauthIdentity("p" + i))).isBetween(0, 63);
    }

    @Test void waitingModifyRechecksRevisionAfterFirstRefreshPublishes() throws Exception {
        var store = new PiCredentialStore(home); var saved = login(store, OAUTH, "one");
        try (var held = store.beginModify(OAUTH, saved.authEpoch(), NONE);
             var executor = Executors.newSingleThreadExecutor(); var rotation = oauth("rotated")) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var waiting = executor.submit(() -> {
                entered.countDown();
                try (var next = new PiCredentialStore(home).beginModify(OAUTH, saved.authEpoch(), NONE);
                     var value = next.snapshot()) {
                    assertMaterial(value, "rotated-access");
                    return next.metadata().materialRevision();
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(waiting.isDone()).isFalse();
            try (var ack = held.finish(PiCredentialStore.Change.PUT, rotation, NONE)) { assertMaterial(ack, "rotated-access"); }
            assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        }
    }

    @Test void cancellationAfterJournalNeverPublishesAndRecoveryCleansNewSecret() throws Exception {
        var normal = new PiCredentialStore(home); var saved = login(normal, OAUTH, "old");
        var cancel = new CancellationSource();
        var interrupted = store(Duration.ofSeconds(5), point -> {
            if (point == PiCredentialStore.CrashPoint.JOURNAL_SECRET_DURABLE) cancel.cancel();
        });
        try (var value = oauth("new")) {
            assertThatThrownBy(() -> interrupted.saveLogin(OAUTH, value, 1, true, cancel.token()))
                    .hasMessageContaining("AUTH_CANCELLED");
        }
        assertThat(normal.snapshot(NONE).find(OAUTH)).contains(saved);
        assertClean(1);
    }

    @ParameterizedTest @EnumSource(value = PiCredentialStore.CrashPoint.class, names = "NEW_SECRET_DURABLE", mode = EnumSource.Mode.EXCLUDE)
    void envReplacementJournalRecoveryNeverCreatesAnEnvValueFile(PiCredentialStore.CrashPoint point) throws Exception {
        var normal = new PiCredentialStore(home);
        try (var key = PiCredentialMaterial.apiKey(bytes("old-key"))) { normal.saveLogin(API, key, 0, true, NONE); }
        try (var env = PiCredentialMaterial.envRef("PI_ENV_NEVER_RESOLVED")) {
            assertThatThrownBy(() -> crashing(point).saveLogin(API, env, 1, true, NONE)).isInstanceOf(Crash.class);
        }
        var value = normal.snapshot(NONE).find(API).orElseThrow();
        assertThat(value.kind()).isEqualTo(published(point) ? PiCredentialMaterial.Kind.ENV_REF : PiCredentialMaterial.Kind.API_KEY);
        assertClean(published(point) ? 0 : 1);
    }

    @ParameterizedTest @EnumSource(PiCredentialStore.CrashPoint.class)
    void initialLoginRecoveryNeverGuessesOrReplaysAnUnpublishedIdentity(PiCredentialStore.CrashPoint point) throws Exception {
        try (var value = oauth("first")) {
            assertThatThrownBy(() -> crashing(point).saveLogin(OAUTH, value, 0, true, NONE)).isInstanceOf(Crash.class);
        }
        var truth = new PiCredentialStore(home).snapshot(NONE);
        assertThat(truth.find(OAUTH).isPresent()).isEqualTo(published(point));
        assertThat(truth.generation()).isEqualTo(published(point) ? 1 : 0);
        assertClean(published(point) ? 1 : 0);
    }

    @Test void timedOutSameJvmWaiterMustNotReleaseOriginalOperatingSystemLock() throws Exception {
        var normal = new PiCredentialStore(home); var saved = login(normal, OAUTH, "one");
        try (var held = normal.beginModify(OAUTH, saved.authEpoch(), NONE)) {
            assertThatThrownBy(() -> store(Duration.ofMillis(100), point -> { }).beginModify(OAUTH, saved.authEpoch(), NONE))
                    .hasMessageContaining("AUTH_STORE_LOCKED");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    PiCredentialLockProcess.class.getName(), home.toString(), OAUTH.profileId(), Long.toString(saved.authEpoch()), "probe")
                    .redirectErrorStream(true).start();
            try {
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).isZero();
                assertThat(child.inputReader(StandardCharsets.UTF_8).readLine()).isEqualTo("BLOCKED");
            } finally { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Test void realDifferentJvmHoldsStripeWhileDeleteStillPublishes() throws Exception {
        var store = new PiCredentialStore(home); var saved = login(store, OAUTH, "one");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PiCredentialLockProcess.class.getName(), home.toString(), OAUTH.profileId(), Long.toString(saved.authEpoch()))
                .redirectErrorStream(true).start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = process.inputReader(StandardCharsets.UTF_8);
            assertThat(executor.submit(reader::readLine).get(15, TimeUnit.SECONDS)).isEqualTo("LOCKED");
            assertThatThrownBy(() -> store(Duration.ofMillis(150), point -> { }).beginModify(OAUTH, saved.authEpoch(), NONE))
                    .hasMessageContaining("AUTH_STORE_LOCKED");
            store.delete(OAUTH, saved.authEpoch(), NONE);
            assertThat(store.snapshot(NONE).profiles()).isEmpty();
            process.getOutputStream().close();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
        } finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }

    @ParameterizedTest @EnumSource(PiCredentialStore.CrashPoint.class)
    void recoversEveryDurableReplacementPhase(PiCredentialStore.CrashPoint point) throws Exception {
        var store = new PiCredentialStore(home); var first = login(store, OAUTH, "old");
        var crashing = crashing(point);
        try (var replacement = oauth("new")) {
            assertThatThrownBy(() -> crashing.saveLogin(OAUTH, replacement, 1, true, NONE)).isInstanceOf(Crash.class);
        }
        var truth = new PiCredentialStore(home).snapshot(NONE);
        boolean published = published(point);
        assertThat(truth.generation()).isEqualTo(published ? 2 : 1);
        assertThat(truth.find(OAUTH).orElseThrow().authEpoch()).isEqualTo(published ? 2 : first.authEpoch());
        try (var value = store.read(OAUTH, truth.find(OAUTH).orElseThrow().authEpoch(), NONE)) {
            assertMaterial(value, published ? "new-access" : "old-access");
        }
        assertClean(1);
    }

    @ParameterizedTest @EnumSource(PiCredentialStore.CrashPoint.class)
    void recoversEveryDurableRefreshPhase(PiCredentialStore.CrashPoint point) throws Exception {
        var store = new PiCredentialStore(home); var initial = login(store, OAUTH, "old");
        try (var pending = crashing(point).beginModify(OAUTH, initial.authEpoch(), NONE); var next = oauth("new")) {
            assertThatThrownBy(() -> pending.finish(PiCredentialStore.Change.PUT, next, NONE)).isInstanceOf(Crash.class);
        }
        var truth = store.snapshot(NONE).find(OAUTH).orElseThrow();
        assertThat(truth.authEpoch()).isEqualTo(initial.authEpoch());
        assertThat(truth.materialRevision()).isEqualTo(published(point) ? 2 : 1);
        try (var material = store.read(OAUTH, initial.authEpoch(), NONE)) { assertMaterial(material, published(point) ? "new-access" : "old-access"); }
        assertClean(1);
    }

    @ParameterizedTest @EnumSource(value = PiCredentialStore.CrashPoint.class, names = "NEW_SECRET_DURABLE", mode = EnumSource.Mode.EXCLUDE)
    void recoversEveryDurableDeletePhase(PiCredentialStore.CrashPoint point) throws Exception {
        var store = new PiCredentialStore(home); var initial = login(store, OAUTH, "old");
        assertThatThrownBy(() -> crashing(point).delete(OAUTH, initial.authEpoch(), NONE)).isInstanceOf(Crash.class);
        assertThat(store.snapshot(NONE).find(OAUTH).isEmpty()).isEqualTo(published(point));
        assertClean(published(point) ? 0 : 1);
    }

    @Test void rejectsMalformedIndexOverflowAndCapacityWithoutNewSecret() throws Exception {
        var store = new PiCredentialStore(home); store.snapshot(NONE);
        for (int i = 0; i < 16; i++) login(store, oauthIdentity("p" + i), "s");
        try (var extra = oauth("extra")) {
            assertThatThrownBy(() -> store.saveLogin(oauthIdentity("overflow"), extra, 16, false, NONE))
                    .hasMessageContaining("AUTH_PROFILE_CONFLICT");
        }
        Path index = home.resolve(".cc-java/auth/pi/index.v1.json");
        String contents = Files.readString(index);
        Files.writeString(index, contents.replace("\"generation\":16", "\"generation\":9223372036854775807"), StandardOpenOption.TRUNCATE_EXISTING);
        try (var extra = oauth("extra")) {
            assertThatThrownBy(() -> store.saveLogin(oauthIdentity("p0"), extra, Long.MAX_VALUE, false, NONE))
                    .hasMessageContaining("AUTH_TRANSACTION_CONFLICT");
        }
        Files.writeString(index, contents.replace("\"version\":1", "\"version\":1,\"version\":1"), StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> store.snapshot(NONE)).hasMessageContaining("AUTH_STORE_CORRUPT").hasNoCause();
        Files.writeString(index, " ".repeat(PiCredentialStore.MAX_INDEX_BYTES + 1), StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> store.snapshot(NONE)).hasMessageContaining("AUTH_STORE_INSECURE").hasNoCause();
    }

    @Test void nonAtomicMoveAndCancellationFailClosed() {
        var security = new RestrictedFileSecurity(home);
        var bad = new PiCredentialStore(security, Duration.ofSeconds(1),
                (source, target) -> { throw new java.nio.file.AtomicMoveNotSupportedException("secret-path", "secret-path", "secret"); }, point -> { });
        try (var value = oauth("one")) {
            assertThatThrownBy(() -> bad.saveLogin(OAUTH, value, 0, true, NONE)).hasMessageContaining("AUTH_STORE_INSECURE").hasNoCause();
            var cancelled = new CancellationSource(); cancelled.cancel();
            assertThatThrownBy(() -> new PiCredentialStore(home).saveLogin(OAUTH, value, 0, true, cancelled.token()))
                    .hasMessageContaining("AUTH_CANCELLED");
        }
        assertThat(new PiCredentialStore(home).snapshot(NONE).generation()).isZero();
    }

    private PiCredentialStore store(Duration timeout, PiCredentialStore.FaultInjector fault) {
        return new PiCredentialStore(new RestrictedFileSecurity(home), timeout, RestrictedFileSecurity.AtomicMover.system(), fault);
    }
    private PiCredentialStore crashing(PiCredentialStore.CrashPoint point) {
        return store(Duration.ofSeconds(5), observed -> { if (observed == point) throw new Crash(); });
    }
    private static boolean published(PiCredentialStore.CrashPoint point) { return point.ordinal() >= PiCredentialStore.CrashPoint.INDEX_PUBLISHED.ordinal(); }
    private static PiCredentialStore.Metadata login(PiCredentialStore store, PiCredentialIdentity identity, String label) {
        try (var value = oauth(label)) { return store.saveLogin(identity, value, store.snapshot(NONE).generation(), true, NONE); }
    }
    private static PiCredentialIdentity oauthIdentity(String profile) {
        return new PiCredentialIdentity("openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, profile);
    }
    private static PiCredentialIdentity distinctStripeIdentity() {
        for (int i = 0; i < 1000; i++) {
            var candidate = oauthIdentity("other" + i);
            if (PiCredentialStore.stripe(candidate) != PiCredentialStore.stripe(OAUTH)) return candidate;
        }
        throw new AssertionError("No distinct stripe");
    }
    private static PiCredentialMaterial oauth(String label) {
        return PiCredentialMaterial.oauth(bytes(label + "-access"), bytes(label + "-refresh"), 1000, bytes("account-synthetic"));
    }
    private Path secret(PiCredentialStore.Metadata entry) { return home.resolve(".cc-java/auth/pi/secrets/" + entry.secretRef().orElseThrow() + ".json"); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void assertMaterial(PiCredentialMaterial material, String expected) {
        byte[] copy = material.copyJson();
        try { assertThat(new String(copy, StandardCharsets.UTF_8)).contains(expected); }
        finally { java.util.Arrays.fill(copy, (byte) 0); }
    }
    private void assertClean(long expected) throws Exception {
        assertThat(Files.exists(home.resolve(".cc-java/auth/pi/.txn.v1.json"))).isFalse();
        try (var files = Files.list(home.resolve(".cc-java/auth/pi/secrets"))) { assertThat(files.count()).isEqualTo(expected); }
    }
    private static final class Crash extends RuntimeException { }
}
