package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.cli.daemon.DaemonOwnership;
import io.github.liumaishenjian.ccjava.cli.daemon.LoopbackApplicationPrototype;
import io.github.liumaishenjian.ccjava.cli.plugins.PluginTransactionJournal;
import io.github.liumaishenjian.ccjava.cli.plugins.PluginTransactionOperation;
import io.github.liumaishenjian.ccjava.cli.plugins.PluginTransactionPhase;
import io.github.liumaishenjian.ccjava.cli.plugins.PluginTransactionRecord;
import io.github.liumaishenjian.ccjava.cli.plugins.PluginTransactionRecovery;
import io.github.liumaishenjian.ccjava.cli.session.FileSessionIndex;
import io.github.liumaishenjian.ccjava.cli.session.SessionExportService;
import io.github.liumaishenjian.ccjava.cli.session.SessionMigrationCoordinator;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.session.SessionExportPolicy;
import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import io.github.liumaishenjian.ccjava.core.session.SessionLifecycleStatus;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.sdk.AgentApplicationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class S14InfrastructureTest {
    @TempDir
    Path temp;

    @Test
    void fileIndexRoundTripsTenThousandRecordsAndReportsMeasuredLatency() {
        FileSessionIndex index = new FileSessionIndex(temp);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        ArrayList<SessionIndexEntry> entries = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            entries.add(new SessionIndexEntry(
                    "s" + i, "w", "demo-" + i, base.plusSeconds(i),
                    SessionLifecycleStatus.CLOSED));
        }
        long rebuildStart = System.nanoTime();
        index.rebuild(entries);
        long rebuildMillis = Duration.ofNanos(System.nanoTime() - rebuildStart).toMillis();
        FileSessionIndex reopened = new FileSessionIndex(temp);
        assertThat(reopened.find("s9999")).isPresent();
        assertThat(reopened.list(9000, 1000)).hasSize(1000);
        long listStart = System.nanoTime();
        reopened.list(0, 1000);
        long listMillis = Duration.ofNanos(System.nanoTime() - listStart).toMillis();
        assertThat(rebuildMillis).isLessThan(30_000);
        assertThat(listMillis).isLessThan(250);
    }

    @Test
    void fileIndexRejectsCorruptionAndLinkedFileWhenSupported() throws Exception {
        Files.writeString(temp.resolve("session-index-v1.tsv"), "broken\n");
        assertThatThrownBy(() -> new FileSessionIndex(temp)).isInstanceOf(IllegalStateException.class);
        Files.delete(temp.resolve("session-index-v1.tsv"));
        Path outside = temp.resolveSibling("outside-index-" + System.nanoTime());
        Files.writeString(outside, "outside");
        try {
            try {
                Files.createSymbolicLink(temp.resolve("session-index-v1.tsv"), outside);
                assertThatThrownBy(() -> new FileSessionIndex(temp)).isInstanceOf(IllegalStateException.class);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                // 当前主机无创建链接权限时不把平台限制计作安全通过。
            }
        } finally {
            Files.deleteIfExists(temp.resolve("session-index-v1.tsv"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void metadataExportExcludesContentAndContentRequiresConfirmedPolicy() {
        SessionExportService exports = new SessionExportService();
        String metadata = new String(exports.export(
                "s1", "w1", List.of("SECRET_SENTINEL"), SessionExportPolicy.metadataOnly()),
                StandardCharsets.UTF_8);
        assertThat(metadata).doesNotContain("SECRET_SENTINEL");
        String content = new String(exports.export(
                "s1", "w1", List.of("redacted"), new SessionExportPolicy(true, true, true)),
                StandardCharsets.UTF_8);
        assertThat(content).contains("redacted");
    }

    @Test
    void migrationStreamsPublishesKeepsSourceAndRejectsLinkedTarget() throws Exception {
        Path source = temp.resolve("source.jsonl");
        Path target = temp.resolve("target.jsonl");
        Files.writeString(source, "one\ntwo\n");
        var result = new SessionMigrationCoordinator().migrate(
                source, target, 1, 2, line -> "v2:" + line);
        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).contains("v2:one");
        assertThat(Files.readString(source)).isEqualTo("one\ntwo\n");

        Path outside = temp.resolveSibling("migration-outside-" + System.nanoTime());
        Files.writeString(outside, "outside");
        try {
            Files.delete(target);
            try {
                Files.createSymbolicLink(target, outside);
                assertThat(new SessionMigrationCoordinator().migrate(
                        source, target, 1, 2, line -> line).success()).isFalse();
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                // 当前主机无创建链接权限。
            }
        } finally {
            Files.deleteIfExists(target);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void pluginTransactionJournalRoundTripsAndIgnoresTruncatedTail() throws Exception {
        PluginTransactionJournal journal = new PluginTransactionJournal(temp);
        var record = new PluginTransactionRecord(
                "tx1", "demo", PluginTransactionOperation.INSTALL,
                PluginTransactionPhase.PREPARED, "a".repeat(64));
        journal.append(record);
        Files.writeString(temp.resolve("transactions-v1.log"), "truncated",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThat(journal.replay()).containsExactly(record);
    }

    @Test
    void failedPreservedPluginTransactionIsNeverAutoCompleted() throws Exception {
        Files.writeString(temp.resolve("registry.v1"), "schema=1\n");
        PluginTransactionJournal journal = new PluginTransactionJournal(temp);
        var failed = new PluginTransactionRecord(
                "tx-preserved", "demo", PluginTransactionOperation.INSTALL,
                PluginTransactionPhase.FAILED_PRESERVED, "b".repeat(64));
        journal.append(failed);

        var result = new PluginTransactionRecovery(temp).recover();

        assertThat(result.clean()).isFalse();
        assertThat(result.preserved()).isEqualTo(1);
        assertThat(journal.replay()).last().isEqualTo(failed);
    }

    @Test
    void loopbackPrototypeProvidesTokenProtectedRunEventsCancelAndDrain() throws Exception {
        try (DaemonOwnership ownership = DaemonOwnership.acquire(temp)) {
            assertThatThrownBy(() -> DaemonOwnership.acquire(temp)).isInstanceOf(Exception.class);
            FakeApplication application = new FakeApplication();
            try (LoopbackApplicationPrototype daemon = new LoopbackApplicationPrototype(0, ownership.token(), application)) {
                daemon.start();
                HttpClient client = HttpClient.newHttpClient();
                URI base = URI.create("http://127.0.0.1:" + daemon.port());
                assertThat(send(client, base.resolve("/v1/health"), "GET", null, null).statusCode())
                        .isEqualTo(401);
                assertThat(send(client, base.resolve("/v1/health"), "POST", token(ownership), null)
                        .statusCode()).isEqualTo(405);
                HttpResponse<String> initialized = send(
                        client, base.resolve("/v1/initialize"), "POST", token(ownership), "{}");
                assertThat(initialized.statusCode()).isEqualTo(200);
                assertThat(initialized.body()).contains("loopback-http-json-v0", "\"stableProtocol\":false");
                assertThat(send(client, base.resolve("/v1/initialize"), "POST", token(ownership),
                        "{\"unknown\":true}").statusCode()).isEqualTo(400);
                HttpResponse<String> accepted = send(
                        client, base.resolve("/v1/run"), "POST", token(ownership),
                        "{\"prompt\":\"hello\",\"maxModelTurns\":2,"
                                + "\"maxToolCalls\":3,\"timeoutMillis\":1000}");
                assertThat(accepted.statusCode()).isEqualTo(200);
                assertThat(application.started.await(2, TimeUnit.SECONDS)).isTrue();
                HttpResponse<String> events = send(
                        client, base.resolve("/v1/events"), "GET", token(ownership), null);
                assertThat(events.body()).contains("activeRunId");
                HttpResponse<String> cancelled = send(
                        client, base.resolve("/v1/cancel"), "POST", token(ownership),
                        "{\"runId\":\"run-1\"}");
                assertThat(cancelled.body()).contains("true");
                application.finish.countDown();
                assertThat(application.finished.await(2, TimeUnit.SECONDS)).isTrue();
                JsonNode eventBody = pollTerminalEvents(client, base, token(ownership));
                assertThat(eventBody.path("events").size()).isGreaterThanOrEqualTo(2);
                List<String> eventTypes = new ArrayList<>();
                eventBody.path("events").forEach(event ->
                        eventTypes.add(event.path("eventType").asText()));
                assertThat(eventTypes).contains("RunStarted", "terminal");
                assertThat(eventTypes.stream().filter("terminal"::equals).count()).isOne();
                assertThat(eventBody.path("terminal").path("status").asText()).isEqualTo("CANCELLED");
                assertThat(application.request.limits().totalModelTurns()).hasValue(2);
                assertThat(daemon.shutdown(Duration.ofSeconds(1))).isTrue();
                assertThatThrownBy(() -> send(
                        client, base.resolve("/v1/run"), "POST", token(ownership),
                        "{\"prompt\":\"late\"}"))
                        .isInstanceOf(java.net.ConnectException.class);
            }
        }
    }

    private static JsonNode pollTerminalEvents(HttpClient client, URI base, String token)
            throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpResponse<String> response = send(
                    client, base.resolve("/v1/events"), "GET", token, null);
            JsonNode body = mapper.readTree(response.body());
            if (body.has("terminal")) {
                return body;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("terminal event 未在期限内发布");
    }

    private static HttpResponse<String> send(
            HttpClient client, URI uri, String method, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if (token != null) {
            builder.header("Authorization", token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(DaemonOwnership ownership) {
        return "Bearer " + ownership.token().reveal();
    }

    private static final class FakeApplication implements AgentApplicationService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile AgentRunRequest request;
        private volatile RunId active;
        private volatile boolean draining;
        private volatile boolean cancelled;

        @Override
        public AgentRunResult run(AgentRunRequest request, AgentEventSink sink) {
            if (draining) {
                throw new IllegalStateException("draining");
            }
            this.request = request;
            active = new RunId("run-1");
            sink.publish(new AgentEventEnvelope(
                    1, Instant.EPOCH, new SessionId("session-1"), Optional.of(active),
                    new io.github.liumaishenjian.ccjava.domain.LifecycleEvent.RunStarted(request)));
            started.countDown();
            try {
                finish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                active = null;
                finished.countDown();
            }
            return cancelled
                    ? AgentRunResult.stopped(
                            new SessionId("session-1"), new RunId("run-1"),
                            io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED, 0, 0)
                    : AgentRunResult.completed(
                            new SessionId("session-1"), new RunId("run-1"), "done", 1, 0);
        }

        @Override
        public boolean cancel(RunId id) {
            if (!id.equals(active)) {
                return false;
            }
            cancelled = true;
            finish.countDown();
            return true;
        }

        @Override
        public void beginDrain() {
            draining = true;
        }

        @Override
        public boolean awaitTermination(Duration timeout) {
            try {
                return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || active == null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public Optional<RunId> activeRun() {
            return Optional.ofNullable(active);
        }

        @Override
        public void close() {
            beginDrain();
            finish.countDown();
        }
    }
}
