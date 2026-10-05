package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.liumaishenjian.ccjava.cli.auth.PiCredentialRpcSession.Purpose.*;
import static org.assertj.core.api.Assertions.*;

class PiCredentialRpcSessionTest {
    @TempDir Path home;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CancellationToken NONE = CancellationToken.none();
    private static final String OP = "private-operation";
    private static final PiCredentialIdentity OAUTH = identity("openai-codex");
    private static final PiCredentialIdentity API = identity("openai");
    private static final Function<String, byte[]> NO_ENV = name -> { throw new AssertionError("must not resolve ENV"); };

    @ParameterizedTest @ValueSource(strings = {"openai", "openai-codex", "deepseek", "qwen-token-plan-cn"})
    void fourRoutesLoginAcknowledgesAuthoritativeMaterialAndModelReadsOnlyBoundIdentity(String provider) {
        var store = new PiCredentialStore(home);
        var id = identity(provider);
        try (var rpc = session(store, id, LOGIN, 0)) {
            assertThat(ok(call(rpc, 1, "list")).get("entries")).isEmpty();
            assertThat(ok(call(rpc, 2, "read")).get("credential").isNull()).isTrue();
            String tx = tx(call(rpc, 3, "begin"));
            JsonNode expected = materialNode(id, "first", "account");
            assertThat(ok(finish(rpc, 4, tx, put(expected))).get("credential")).isEqualTo(expected);
        }
        var saved = store.snapshot(NONE).find(id).orElseThrow();
        assertThat(saved.authEpoch()).isEqualTo(1);
        assertThat(store.snapshot(NONE).providerDefaults()).containsEntry(provider, "default");
        try (var rpc = session(store, id, MODEL, saved.authEpoch())) {
            var entries = ok(call(rpc, 1, "list")).get("entries");
            assertThat(entries.size()).isEqualTo(1);
            assertThat(entries.get(0).propertyNames()).containsExactlyInAnyOrder("providerId", "type");
            assertThat(entries.get(0).get("providerId").asString()).isEqualTo(provider);
            assertThat(ok(call(rpc, 2, "read")).get("credential")).isEqualTo(materialNode(id, "first", "account"));
        }
    }

    @Test void listIsMetadataOnlyEvenWhenSecretMissingAndOtherProfilesExist() throws Exception {
        var store = new PiCredentialStore(home);
        var saved = save(store, OAUTH, "first");
        save(store, API, "unrelated");
        Files.delete(secret(saved));
        try (var rpc = session(store, OAUTH, MODEL, saved.authEpoch())) {
            var entries = ok(call(rpc, 1, "list")).get("entries");
            assertThat(entries.size()).isEqualTo(1);
            assertThat(entries.get(0).get("type").asString()).isEqualTo("oauth");
            error(call(rpc, 2, "read"), "UNAVAILABLE");
        }
    }

    @Test void listDoesNotParseCorruptSecret() throws Exception {
        var store = new PiCredentialStore(home);
        var saved = save(store, API, "first");
        Files.writeString(secret(saved), "not-json", StandardOpenOption.TRUNCATE_EXISTING);
        try (var rpc = session(store, API, MODEL, saved.authEpoch())) {
            assertThat(ok(call(rpc, 1, "list")).get("entries").size()).isEqualTo(1);
            error(call(rpc, 2, "read"), "STORE_FAILED");
        }
    }

    @Test void refreshPreservesEpochAndMergesOtherIdentityUpdateBeforeAuthoritativeAck() {
        var store = new PiCredentialStore(home);
        var initial = save(store, OAUTH, "first");
        try (var rpc = session(store, OAUTH, MODEL, initial.authEpoch())) {
            var begun = ok(call(rpc, 1, "begin"));
            assertThat(begun.get("credential")).isEqualTo(materialNode(OAUTH, "first", "account"));
            save(store, API, "other");
            JsonNode candidate = materialNode(OAUTH, "rotated", "account");
            assertThat(ok(finish(rpc, 2, begun.get("transactionId").asString(), put(candidate))).get("credential")).isEqualTo(candidate);
            var snapshot = store.snapshot(NONE);
            assertThat(snapshot.profiles()).hasSize(2);
            var next = snapshot.find(OAUTH).orElseThrow();
            assertThat(next.authEpoch()).isEqualTo(initial.authEpoch());
            assertThat(next.materialRevision()).isEqualTo(2);
            assertThat(ok(call(rpc, 3, "read")).get("credential")).isEqualTo(candidate);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"read", "begin"})
    void loginChecksCapturedGlobalGenerationEvenForOtherIdentityUpdate(String action) {
        var store = new PiCredentialStore(home);
        try (var rpc = session(store, OAUTH, LOGIN, 0)) {
            save(store, API, "other");
            error(call(rpc, 1, action), "CONFLICT");
        }
    }

    @Test void loginFinishUsesCasAndDoesNotPersistUnacknowledgedCandidate() {
        var store = new PiCredentialStore(home);
        try (var rpc = session(store, OAUTH, LOGIN, 0)) {
            String tx = tx(call(rpc, 1, "begin"));
            save(store, API, "other");
            error(finish(rpc, 2, tx, put(materialNode(OAUTH, "rejected", "account"))), "CONFLICT");
            assertThat(store.snapshot(NONE).find(OAUTH)).isEmpty();
        }
    }

    @Test void loginReplacesEpochAndOldModelCannotReadOrFinish() {
        var store = new PiCredentialStore(home);
        var saved = save(store, OAUTH, "old");
        try (var oldModel = session(store, OAUTH, MODEL, saved.authEpoch());
                var login = session(store, OAUTH, LOGIN, store.snapshot(NONE).generation())) {
            String refreshTx = tx(call(oldModel, 1, "begin"));
            String loginTx = tx(call(login, 1, "begin"));
            var next = materialNode(OAUTH, "new-login", "new-account");
            assertThat(ok(finish(login, 2, loginTx, put(next))).get("credential")).isEqualTo(next);
            assertThat(store.snapshot(NONE).find(OAUTH).orElseThrow().authEpoch()).isGreaterThan(saved.authEpoch());
            error(finish(oldModel, 2, refreshTx, put(materialNode(OAUTH, "late", "account"))), "CONFLICT");
        }
        try (var stale = session(store, OAUTH, MODEL, saved.authEpoch())) {
            error(call(stale, 1, "read"), "CONFLICT");
        }
    }

    @Test void logoutRejectsLateRefreshAndReleasesLock() {
        var store = new PiCredentialStore(home);
        var saved = save(store, OAUTH, "first");
        try (var rpc = session(store, OAUTH, MODEL, saved.authEpoch())) {
            String tx = tx(call(rpc, 1, "begin"));
            store.delete(OAUTH, saved.authEpoch(), NONE);
            error(finish(rpc, 2, tx, put(materialNode(OAUTH, "late", "account"))), "CONFLICT");
            assertThat(store.snapshot(NONE).find(OAUTH)).isEmpty();
        }
        var next = save(store, OAUTH, "again");
        try (var transaction = store.beginModify(OAUTH, next.authEpoch(), NONE)) { }
    }

    @ParameterizedTest @ValueSource(strings = {"account", "api"})
    void modelCannotChangeAccountOrPutApiKey(String variant) {
        var store = new PiCredentialStore(home);
        var id = variant.equals("api") ? API : OAUTH;
        var saved = save(store, id, "first");
        try (var rpc = session(store, id, MODEL, saved.authEpoch())) {
            String tx = tx(call(rpc, 1, "begin"));
            error(finish(rpc, 2, tx, put(materialNode(id, "bad", "other-account"))), "CONFLICT");
        }
        assertThat(store.snapshot(NONE).find(id)).contains(saved);
    }

    @Test void keepReturnsStoreMaterialWithoutRevisionChange() {
        var store = new PiCredentialStore(home);
        var saved = save(store, OAUTH, "first");
        try (var rpc = session(store, OAUTH, MODEL, saved.authEpoch())) {
            String tx = tx(call(rpc, 1, "begin"));
            assertThat(ok(finish(rpc, 2, tx, object().put("kind", "keep"))).get("credential"))
                    .isEqualTo(materialNode(OAUTH, "first", "account"));
            assertThat(store.snapshot(NONE).find(OAUTH)).contains(saved);
        }
    }

    @ParameterizedTest @EnumSource(PiCredentialRpcSession.Purpose.class)
    void deleteNeverGrantsLogoutAuthority(PiCredentialRpcSession.Purpose purpose) {
        var store = new PiCredentialStore(home);
        var saved = save(store, OAUTH, "first");
        try (var rpc = session(store, OAUTH, purpose, 1)) {
            String tx = tx(call(rpc, 1, "begin"));
            error(finish(rpc, 2, tx, object().put("kind", "delete")), "PROTOCOL_INVALID");
        }
        assertThat(store.snapshot(NONE).find(OAUTH)).contains(saved);
        try (var transaction = store.beginModify(OAUTH, 1, NONE)) { }
    }

    @Test void loginKeepIsNotACommit() {
        var store = new PiCredentialStore(home);
        try (var rpc = session(store, API, LOGIN, 0)) {
            error(finish(rpc, 2, tx(call(rpc, 1, "begin")), object().put("kind", "keep")), "PROTOCOL_INVALID");
        }
        assertThat(store.snapshot(NONE).generation()).isZero();
    }

    @Test void envListDoesNotResolveAndReadBeginKeepEraseOwnedArraysWithoutPersistence() throws Exception {
        var store = new PiCredentialStore(home);
        try (var env = PiCredentialMaterial.envRef("SYNTHETIC_KEY")) { store.saveLogin(API, env, 0, false, NONE); }
        var calls = new AtomicInteger();
        var arrays = new java.util.ArrayList<byte[]>();
        try (var rpc = new PiCredentialRpcSession(OP, API, MODEL, store, 1, false, name -> {
            assertThat(name).isEqualTo("SYNTHETIC_KEY");
            calls.incrementAndGet();
            byte[] bytes = ascii("ENV_TEST_SECRET"); arrays.add(bytes); return bytes;
        }, NONE)) {
            ok(call(rpc, 1, "list")); assertThat(calls).hasValue(0);
            assertThat(ok(call(rpc, 2, "read")).get("credential").get("key").asString()).isEqualTo("ENV_TEST_SECRET");
            String tx = tx(call(rpc, 3, "begin"));
            ok(finish(rpc, 4, tx, object().put("kind", "keep")));
            assertThat(calls).hasValue(3);
            for (byte[] bytes : arrays) assertThat(bytes).containsOnly((byte) 0);
        }
        assertThat(store.snapshot(NONE).find(API).orElseThrow().kind()).isEqualTo(PiCredentialMaterial.Kind.ENV_REF);
        assertThat(Files.readString(home.resolve(".cc-java/auth/pi/index.v1.json"))).doesNotContain("ENV_TEST_SECRET");
    }

    @Test void invalidEnvBytesAreErasedAndResolverExceptionCannotLeak() {
        var store = new PiCredentialStore(home);
        try (var env = PiCredentialMaterial.envRef("SYNTHETIC_KEY")) { store.saveLogin(API, env, 0, false, NONE); }
        byte[] invalid = new byte[] {0, 1, 2};
        try (var rpc = new PiCredentialRpcSession(OP, API, MODEL, store, 1, false, name -> invalid, NONE)) {
            error(call(rpc, 1, "read"), "PROTOCOL_INVALID");
            assertThat(invalid).containsOnly((byte) 0);
        }
        try (var rpc = new PiCredentialRpcSession(OP, API, MODEL, store, 1, false,
                name -> { throw new IllegalStateException("SECRET:/private/path"); }, NONE)) {
            error(call(rpc, 1, "read"), "UNAVAILABLE");
        }
    }

    enum InvalidRequest { OPERATION, TYPE, EXTRA, ID_ZERO, ID_SKIP, ID_STRING, ACTION, ARG_IDENTITY, ARG_PROVIDER, ARG_NULL }
    @ParameterizedTest @EnumSource(InvalidRequest.class)
    void malformedOrCrossWiredRequestFailsClosed(InvalidRequest invalid) {
        var store = new PiCredentialStore(home);
        ObjectNode payload = payload(1, "read", object());
        switch (invalid) {
            case EXTRA -> payload.put("unknown", "private");
            case ID_ZERO -> payload.put("requestId", 0);
            case ID_SKIP -> payload.put("requestId", 2);
            case ID_STRING -> payload.put("requestId", "1");
            case ACTION -> payload.put("action", "delete");
            case ARG_IDENTITY -> payload.set("arguments", object().put("identity", "other"));
            case ARG_PROVIDER -> payload.set("arguments", object().put("providerId", "deepseek"));
            case ARG_NULL -> payload.putNull("arguments");
            default -> { }
        }
        try (var rpc = session(store, API, LOGIN, 0)) {
            error(rpc.handle(new ProtocolFrame(invalid == InvalidRequest.OPERATION ? "other" : OP, 0,
                    invalid == InvalidRequest.TYPE ? "model.start" : "credential.request", payload)), "PROTOCOL_INVALID");
            error(call(rpc, 2, "read"), "CLOSED");
        }
    }

    @Test void duplicateRequestIdIsRejected() {
        try (var rpc = session(new PiCredentialStore(home), API, LOGIN, 0)) {
            ok(call(rpc, 1, "read")); error(call(rpc, 1, "read"), "PROTOCOL_INVALID");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"begin", "unknown", "repeat", "finish-repeat", "bad-change"})
    void transactionMisuseFailsClosedAndReleasesStoreLock(String variant) {
        var store = new PiCredentialStore(home); save(store, OAUTH, "first");
        try (var rpc = session(store, OAUTH, MODEL, 1)) {
            String tx = tx(call(rpc, 1, "begin"));
            assertThat(tx).matches("[A-Za-z0-9_-]{1,96}");
            switch (variant) {
                case "begin" -> error(call(rpc, 2, "begin"), "CONFLICT");
                case "unknown" -> error(call(rpc, 2, "abort", object().put("transactionId", "unknown")), "CONFLICT");
                case "repeat" -> {
                    assertThat(ok(call(rpc, 2, "abort", object().put("transactionId", tx)))).isEmpty();
                    error(call(rpc, 3, "abort", object().put("transactionId", tx)), "CONFLICT");
                }
                case "finish-repeat" -> {
                    ok(finish(rpc, 2, tx, object().put("kind", "keep")));
                    error(finish(rpc, 3, tx, object().put("kind", "keep")), "CONFLICT");
                }
                case "bad-change" -> error(finish(rpc, 2, tx, object().put("kind", "keep").put("extra", true)), "PROTOCOL_INVALID");
                default -> throw new AssertionError();
            }
        }
        try (var transaction = store.beginModify(OAUTH, 1, NONE)) { }
    }

    @Test void abortAllowsNewUnrelatedTransactionIdAndDisconnectReleasesIt() {
        var store = new PiCredentialStore(home); save(store, OAUTH, "first");
        var rpc = session(store, OAUTH, MODEL, 1);
        String first = tx(call(rpc, 1, "begin"));
        ok(call(rpc, 2, "abort", object().put("transactionId", first)));
        assertThat(tx(call(rpc, 3, "begin"))).isNotEqualTo(first);
        rpc.close(); rpc.close();
        error(call(rpc, 4, "read"), "CLOSED");
        try (var transaction = store.beginModify(OAUTH, 1, NONE)) { }
    }

    @Test void modelRequestCountStopsAfter512IndependentOfTransportSequence() {
        var store = new PiCredentialStore(home); save(store, API, "first");
        try (var rpc = session(store, API, MODEL, 1)) {
            for (int i = 1; i <= 512; i++) ok(call(rpc, i, "list"));
            error(call(rpc, 513, "list"), "LIMIT");
        }
    }

    @Test void authenticationReencodingSingleFrameAndAggregateBudgetsAreNotPhysicalLimits() {
        var store = new PiCredentialStore(home);
        try (var rpc = session(store, API, LOGIN, 0)) {
            error(call(rpc, 1, "read", object().put("padding", "x".repeat(32768))), "LIMIT");
        }
        try (var material = PiCredentialMaterial.apiKey(ascii("k".repeat(16000)))) { store.saveLogin(API, material, 0, false, NONE); }
        try (var rpc = session(store, API, LOGIN, 1)) {
            for (int i = 1; i <= 8; i++) ok(call(rpc, i, "read"));
            error(call(rpc, 9, "read"), "LIMIT");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closeOrExternalCancellationInterruptsQueuedBeginWithoutLateLock(boolean external) throws Exception {
        var store = new PiCredentialStore(home); save(store, OAUTH, "first");
        var cancellation = new CancellationSource();
        var reached = new CountDownLatch(1);
        CancellationToken token = new CancellationToken() {
            public boolean isCancellationRequested() { reached.countDown(); return cancellation.token().isCancellationRequested(); }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        var rpc = new PiCredentialRpcSession(OP, OAUTH, MODEL, store, 1, false, NO_ENV, token);
        try (var held = store.beginModify(OAUTH, 1, NONE); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(() -> call(rpc, 1, "begin"));
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
            var locksField = PiCredentialStore.class.getDeclaredField("PROCESS_LOCKS"); locksField.setAccessible(true);
            var stripe = ((java.util.concurrent.Semaphore[]) locksField.get(null))[PiCredentialStore.stripe(OAUTH)];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!stripe.hasQueuedThreads() && System.nanoTime() < deadline) Thread.sleep(1);
            assertThat(stripe.hasQueuedThreads()).isTrue();
            if (external) cancellation.cancel();
            else executor.submit(rpc::close).get(3, TimeUnit.SECONDS);
            var response = waiting.get(3, TimeUnit.SECONDS);
            assertThat(response.get("ok").asBoolean()).isFalse();
            assertThat(response.get("code").asString()).isIn("CANCELLED", "CLOSED");
        } finally { rpc.close(); }
        try (var transaction = store.beginModify(OAUTH, 1, NONE)) { }
    }

    @Test void closeRacingPublishedFinishNeverAcknowledgesCandidateAndWaitsForRelease() throws Exception {
        var enabled = new AtomicBoolean();
        var published = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new PiCredentialStore(new RestrictedFileSecurity(home), Duration.ofSeconds(5),
                RestrictedFileSecurity.AtomicMover.system(), point -> {
                    if (enabled.get() && point == PiCredentialStore.CrashPoint.INDEX_PUBLISHED) {
                        published.countDown(); await(release);
                    }
                });
        save(store, OAUTH, "first");
        var rpc = session(store, OAUTH, MODEL, 1);
        String tx = tx(call(rpc, 1, "begin"));
        enabled.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var finishing = executor.submit(() -> finish(rpc, 2, tx, put(materialNode(OAUTH, "rotated", "account"))));
            assertThat(published.await(3, TimeUnit.SECONDS)).isTrue();
            var closer = executor.submit(rpc::close);
            try {
                var field = PiCredentialRpcSession.class.getDeclaredField("closeRequested"); field.setAccessible(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!field.getBoolean(rpc) && System.nanoTime() < deadline) Thread.sleep(1);
                assertThat(field.getBoolean(rpc)).isTrue(); assertThat(closer.isDone()).isFalse();
            } finally { release.countDown(); }
            error(finishing.get(3, TimeUnit.SECONDS), "CLOSED"); closer.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); rpc.close(); }
        assertThat(store.snapshot(NONE).find(OAUTH).orElseThrow().materialRevision()).isEqualTo(2);
        try (var transaction = store.beginModify(OAUTH, 1, NONE)) { }
    }

    @ParameterizedTest @EnumSource(PiCredentialRpcSession.Purpose.class)
    void publicationFaultDoesNotReturnCandidateAsAck(PiCredentialRpcSession.Purpose purpose) {
        var enabled = new AtomicBoolean();
        var store = new PiCredentialStore(new RestrictedFileSecurity(home), Duration.ofSeconds(5),
                RestrictedFileSecurity.AtomicMover.system(), point -> {
                    if (enabled.get() && point == PiCredentialStore.CrashPoint.INDEX_PUBLISHED)
                        throw new IllegalStateException("PRIVATE/path/token");
                });
        save(store, OAUTH, "first");
        try (var rpc = session(store, OAUTH, purpose, 1)) {
            String tx = tx(call(rpc, 1, "begin")); enabled.set(true);
            error(finish(rpc, 2, tx, put(materialNode(OAUTH, "published", "account"))), "STORE_FAILED");
        }
        enabled.set(false);
        var authoritative = store.snapshot(NONE).find(OAUTH).orElseThrow();
        assertThat(authoritative.materialRevision()).isEqualTo(purpose == MODEL ? 2 : 1);
        assertThat(authoritative.authEpoch()).isEqualTo(purpose == MODEL ? 1 : 2);
        try (var transaction = store.beginModify(OAUTH, authoritative.authEpoch(), NONE)) { }
    }

    @Test void lateBeginDuringEnvExportIsClosedBeforeLockCanEscape() throws Exception {
        var store = new PiCredentialStore(home);
        try (var env = PiCredentialMaterial.envRef("SYNTHETIC_KEY")) { store.saveLogin(API, env, 0, false, NONE); }
        var resolving = new CountDownLatch(1); var release = new CountDownLatch(1);
        byte[] owned = ascii("synthetic-value");
        var rpc = new PiCredentialRpcSession(OP, API, MODEL, store, 1, false, name -> {
            resolving.countDown(); await(release); return owned;
        }, NONE);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var begin = executor.submit(() -> call(rpc, 1, "begin"));
            assertThat(resolving.await(3, TimeUnit.SECONDS)).isTrue();
            var closer = executor.submit(rpc::close);
            try {
                var field = PiCredentialRpcSession.class.getDeclaredField("closeRequested"); field.setAccessible(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!field.getBoolean(rpc) && System.nanoTime() < deadline) Thread.sleep(1);
                assertThat(field.getBoolean(rpc)).isTrue(); assertThat(closer.isDone()).isFalse();
            } finally { release.countDown(); }
            error(begin.get(3, TimeUnit.SECONDS), "CLOSED"); closer.get(3, TimeUnit.SECONDS);
            assertThat(owned).containsOnly((byte) 0);
        } finally { release.countDown(); rpc.close(); }
        try (var transaction = store.beginModify(API, 1, NONE)) { }
    }

    @Test void envResolverErrorIsSanitizedAndBeginReleasesItsLock() {
        var store = new PiCredentialStore(home);
        try (var env = PiCredentialMaterial.envRef("SYNTHETIC_KEY")) { store.saveLogin(API, env, 0, false, NONE); }
        try (var rpc = new PiCredentialRpcSession(OP, API, MODEL, store, 1, false,
                name -> { throw new AssertionError("PRIVATE/path/token"); }, NONE)) {
            error(call(rpc, 1, "begin"), "UNAVAILABLE");
        }
        try (var transaction = store.beginModify(API, 1, NONE)) { }
    }

    @Test void loginEnvReferenceIsExportedOnlyTemporarilyAndAbortDoesNotPersistValue() {
        var store = new PiCredentialStore(home);
        try (var env = PiCredentialMaterial.envRef("SYNTHETIC_KEY")) { store.saveLogin(API, env, 0, false, NONE); }
        byte[] owned = ascii("synthetic-value");
        try (var rpc = new PiCredentialRpcSession(OP, API, LOGIN, store, 1, false, name -> owned, NONE)) {
            String tx = tx(call(rpc, 1, "begin"));
            assertThat(owned).containsOnly((byte) 0);
            ok(call(rpc, 2, "abort", object().put("transactionId", tx)));
        }
        assertThat(store.snapshot(NONE).generation()).isEqualTo(1);
        assertThat(store.snapshot(NONE).find(API).orElseThrow().kind()).isEqualTo(PiCredentialMaterial.Kind.ENV_REF);
    }

    @Test void workerCannotStoreEnvReferenceOrOverrideFinishIdentity() {
        var store = new PiCredentialStore(home);
        try (var rpc = session(store, API, LOGIN, 0)) {
            error(finish(rpc, 2, tx(call(rpc, 1, "begin")),
                    put(object().put("type", "env_ref").put("variableName", "ATTACKER_ENV"))), "PROTOCOL_INVALID");
        }
        try (var rpc = session(store, API, LOGIN, 0)) {
            String tx = tx(call(rpc, 1, "begin"));
            ObjectNode args = object().put("transactionId", tx).put("providerId", "deepseek");
            args.set("change", put(materialNode(API, "candidate", "account")));
            error(call(rpc, 2, "finish", args), "PROTOCOL_INVALID");
        }
        assertThat(store.snapshot(NONE).generation()).isZero();
    }

    @Test void expiredRuntimeDeadlinePreventsAnyLoginPublication() {
        CancellationToken expired = new CancellationToken() {
            public boolean isCancellationRequested() { return false; }
            public java.util.Optional<Duration> remainingTime() { return java.util.Optional.of(Duration.ZERO); }
            public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        var store = new PiCredentialStore(home);
        try (var rpc = new PiCredentialRpcSession(OP, API, LOGIN, store, 0, false, NO_ENV, expired)) {
            error(call(rpc, 1, "begin"), "CANCELLED");
        }
        assertThat(store.snapshot(NONE).generation()).isZero();
    }

    @Test void repeatedClosePreservesUnderlyingCleanupFailure() throws Exception {
        var store = new PiCredentialStore(home); save(store, OAUTH, "first");
        var rpc = session(store, OAUTH, MODEL, 1); tx(call(rpc, 1, "begin"));
        var transactionField = PiCredentialRpcSession.class.getDeclaredField("transaction"); transactionField.setAccessible(true);
        var transaction = transactionField.get(rpc);
        var stripeField = transaction.getClass().getDeclaredField("stripe"); stripeField.setAccessible(true);
        var stripe = stripeField.get(transaction);
        // 先真实关闭资源，再注入与 store 已确认的重复 close 失败状态等价的故障。
        ((AutoCloseable) transaction).close();
        var failedField = stripe.getClass().getDeclaredField("closeFailed"); failedField.setAccessible(true); failedField.setBoolean(stripe, true);
        for (int i = 0; i < 2; i++) assertThatThrownBy(rpc::close)
                .isInstanceOf(PiCredentialRpcSession.RpcException.class).hasMessage("CLEANUP_FAILED").hasNoCause();
        error(call(rpc, 2, "read"), "CLEANUP_FAILED");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private PiCredentialRpcSession session(PiCredentialStore store, PiCredentialIdentity id,
            PiCredentialRpcSession.Purpose purpose, long binding) {
        return new PiCredentialRpcSession(OP, id, purpose, store, binding, purpose == LOGIN, NO_ENV, NONE);
    }
    private static PiCredentialIdentity identity(String provider) {
        return new PiCredentialIdentity(provider, provider.equals("openai-codex")
                ? PiCredentialIdentity.AuthMethod.OAUTH : PiCredentialIdentity.AuthMethod.API_KEY, "default");
    }
    private PiCredentialStore.Metadata save(PiCredentialStore store, PiCredentialIdentity id, String value) {
        byte[] bytes = JSON.writeValueAsBytes(materialNode(id, value, "account"));
        try (var material = PiCredentialMaterial.fromJson(bytes)) {
            return store.saveLogin(id, material, store.snapshot(NONE).generation(), false, NONE);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static JsonNode materialNode(PiCredentialIdentity id, String value, String account) {
        return id.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH
                ? object().put("type", "oauth").put("access", value + "-access").put("refresh", value + "-refresh")
                    .put("expires", 100000).put("accountId", account)
                : object().put("type", "api_key").put("key", value);
    }
    private Path secret(PiCredentialStore.Metadata metadata) {
        return home.resolve(".cc-java/auth/pi/secrets").resolve(metadata.secretRef().orElseThrow() + ".json");
    }
    private static ObjectNode put(JsonNode credential) { return object().put("kind", "put").set("credential", credential); }
    private static ObjectNode finish(PiCredentialRpcSession rpc, int id, String tx, ObjectNode change) {
        return call(rpc, id, "finish", object().put("transactionId", tx).set("change", change));
    }
    private static ObjectNode call(PiCredentialRpcSession rpc, int id, String action) { return call(rpc, id, action, object()); }
    private static ObjectNode call(PiCredentialRpcSession rpc, int id, String action, ObjectNode arguments) {
        return rpc.handle(new ProtocolFrame(OP, id + 10L, "credential.request", payload(id, action, arguments)));
    }
    private static ObjectNode payload(int id, String action, ObjectNode arguments) {
        return object().put("requestId", id).put("action", action).set("arguments", arguments);
    }
    private static String tx(ObjectNode response) { return ok(response).get("transactionId").asString(); }
    private static JsonNode ok(ObjectNode response) {
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("requestId", "ok", "result");
        assertThat(response.get("ok").asBoolean()).isTrue(); return response.get("result");
    }
    private static void error(ObjectNode response, String code) {
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("requestId", "ok", "code");
        assertThat(response.get("ok").asBoolean()).isFalse(); assertThat(response.get("code").asString()).isEqualTo(code);
    }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
