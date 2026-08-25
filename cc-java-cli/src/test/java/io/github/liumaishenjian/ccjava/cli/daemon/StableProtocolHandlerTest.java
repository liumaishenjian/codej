package io.github.liumaishenjian.ccjava.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.task.TaskListService;
import io.github.liumaishenjian.ccjava.core.task.TaskMutation;
import io.github.liumaishenjian.ccjava.core.task.TaskRunState;
import io.github.liumaishenjian.ccjava.core.task.TaskBoardCapabilityFactory;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardId;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot;
import io.github.liumaishenjian.ccjava.domain.task.TaskCallId;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadata;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.protocol.ProtocolCodecException;
import io.github.liumaishenjian.ccjava.protocol.ProtocolEnvelope;
import io.github.liumaishenjian.ccjava.protocol.ProtocolFeature;
import io.github.liumaishenjian.ccjava.protocol.ProtocolMessageKind;
import io.github.liumaishenjian.ccjava.protocol.ProtocolVersion;
import io.github.liumaishenjian.ccjava.protocol.StableProtocolCodec;
import io.github.liumaishenjian.ccjava.sdk.AgentApplicationService;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class StableProtocolHandlerTest {
    private final StableProtocolCodec codec = new StableProtocolCodec();

    @Test
    void initializeRunEventsTerminalCancelAndDrainUseStableStateMachine() throws Exception {
        CapabilityToken token = CapabilityToken.generate();
        FakeApplication application = new FakeApplication();
        try (StableProtocolHandler handler = new StableProtocolHandler(
                token, Set.of(ProtocolFeature.RUN, ProtocolFeature.CANCEL, ProtocolFeature.DAEMON), application)) {
            handler.receive(encoded(initialize(token, 1)));
            ProtocolEnvelope initialized = next(handler);
            assertThat(initialized.type()).isEqualTo("initialized");
            assertThat(initialized.payload().path("features").toString()).contains("RUN", "CANCEL");

            ProtocolEnvelope run = request("run.start", "r1", 2, Optional.of("idem-run"),
                    codec.objectNode().put("prompt", "hello"));
            handler.receive(encoded(run));
            assertThat(next(handler).type()).isEqualTo("run.accepted");
            assertThat(application.started.await(2, TimeUnit.SECONDS)).isTrue();

            ProtocolEnvelope event = next(handler);
            assertThat(event.type()).isEqualTo("run.event");
            ProtocolEnvelope cancel = request("run.cancel", "c1", 3, Optional.empty(),
                    codec.objectNode().put("runId", "run-1"));
            handler.receive(encoded(cancel));
            ProtocolEnvelope cancelResponse = null;
            ProtocolEnvelope terminal = null;
            for (int i = 0; i < 10 && (cancelResponse == null || terminal == null); i++) {
                ProtocolEnvelope output = next(handler);
                if ("run.cancelled".equals(output.type())) cancelResponse = output;
                if ("run.terminal".equals(output.type())) terminal = output;
            }
            assertThat(cancelResponse).isNotNull();
            assertThat(cancelResponse.payload().path("cancelled").asBoolean()).isTrue();
            assertThat(terminal).isNotNull();
            assertThat(terminal.payload().path("status").asText()).isEqualTo("CANCELLED");

            handler.receive(encoded(request("drain", "d1", 4, Optional.empty(), codec.objectNode())));
            assertThat(next(handler).type()).isEqualTo("draining");
            assertThatThrownBy(() -> handler.receive(encoded(request(
                    "run.start", "late", 5, Optional.empty(), codec.objectNode().put("prompt", "late")))))
                    .isInstanceOf(ProtocolCodecException.class);
        }
    }

    @Test
    void unauthorizedOutOfOrderDuplicateAndIdempotencyConflictFailClosed() throws Exception {
        CapabilityToken token = CapabilityToken.generate();
        try (StableProtocolHandler unauthorized = handler(token, new FakeApplication())) {
            ProtocolEnvelope bad = initialize(CapabilityToken.generate(), 1);
            assertThatThrownBy(() -> unauthorized.receive(encoded(bad)))
                    .isInstanceOf(ProtocolCodecException.class)
                    .extracting(failure -> ((ProtocolCodecException) failure).code())
                    .isEqualTo("UNAUTHORIZED");
        }

        FakeApplication application = new FakeApplication();
        try (StableProtocolHandler handler = handler(token, application)) {
            handler.receive(encoded(initialize(token, 1)));
            next(handler);
            assertThatThrownBy(() -> handler.receive(encoded(request(
                    "run.start", "bad-seq", 3, Optional.empty(), codec.objectNode().put("prompt", "x")))))
                    .isInstanceOf(ProtocolCodecException.class);
        }

        FakeApplication replayApplication = new FakeApplication();
        replayApplication.finish.countDown();
        try (StableProtocolHandler handler = handler(token, replayApplication)) {
            handler.receive(encoded(initialize(token, 1)));
            next(handler);
            ProtocolEnvelope first = request("run.start", "first", 2, Optional.of("same"),
                    codec.objectNode().put("prompt", "one"));
            handler.receive(encoded(first));
            ProtocolEnvelope accepted = next(handler);
            awaitType(handler, "run.terminal");
            ProtocolEnvelope replay = request("run.start", "replay", 3, Optional.of("same"),
                    codec.objectNode().put("prompt", "one"));
            handler.receive(encoded(replay));
            assertThat(next(handler).type()).isEqualTo(accepted.type());
            assertThat(replayApplication.runCount).isOne();
            assertThatThrownBy(() -> handler.receive(encoded(request(
                    "run.start", "conflict", 4, Optional.of("same"),
                    codec.objectNode().put("prompt", "different")))))
                    .isInstanceOf(ProtocolCodecException.class)
                    .extracting(failure -> ((ProtocolCodecException) failure).code())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
        }
    }

    @Test
    void backpressurePreservesUniqueTerminalAndDisconnectFencesLateEvents() throws Exception {
        CapabilityToken token = CapabilityToken.generate();
        FloodApplication application = new FloodApplication(StableProtocolHandler.MAX_EGRESS_MESSAGES + 50);
        StableProtocolHandler handler = handler(token, application);
        handler.receive(encoded(initialize(token, 1)));
        next(handler);
        handler.receive(encoded(request("run.start", "run", 2, Optional.empty(),
                codec.objectNode().put("prompt", "flood"))));
        next(handler);
        assertThat(application.finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.pendingOutputCount()).isLessThanOrEqualTo(
                StableProtocolHandler.MAX_EGRESS_MESSAGES);
        List<ProtocolEnvelope> outputs = drain(handler);
        assertThat(outputs.stream().filter(message -> "run.terminal".equals(message.type())).count()).isOne();
        handler.close();
        application.publishLate();
        assertThat(handler.takeOutput(Duration.ofMillis(10))).isEmpty();
    }

    @Test
    void taskListV1UsesWireCapabilityAndReturnsBoundedCanonicalSnapshot() throws Exception {
        CapabilityToken token = CapabilityToken.generate();
        TaskSnapshotApplication application = new TaskSnapshotApplication();
        try (StableProtocolHandler handler = new StableProtocolHandler(
                token, Set.of(ProtocolFeature.TASK_LIST_V1), application)) {
            ObjectNode initialize = codec.objectNode().put("token", token.reveal()).put("version", "1.0");
            initialize.putArray("features").add("task-list-v1");
            handler.receive(encoded(request("initialize", "init-task", 1, Optional.empty(), initialize)));
            ProtocolEnvelope initialized = next(handler);
            assertThat(initialized.payload().path("features").toString()).contains("task-list-v1");

            handler.receive(encoded(request("task.snapshot", "tasks", 2, Optional.of("task-page"),
                    codec.objectNode().put("limit", 1))));
            ProtocolEnvelope snapshot = next(handler);
            assertThat(snapshot.type()).isEqualTo("task.snapshot");
            assertThat(snapshot.payload().path("schema").asText()).isEqualTo("task-list-v1");
            assertThat(snapshot.payload().path("tasks")).hasSize(1);
            assertThat(snapshot.payload().path("tasks").get(0).path("taskId").asText()).isEqualTo("task-1");
            assertThat(snapshot.payload().path("hasMore").asBoolean()).isTrue();
            assertThat(snapshot.payload().path("nextCursor").asText()).isEqualTo("task-1");

            handler.receive(encoded(request("task.snapshot", "tasks-2", 3, Optional.empty(),
                    codec.objectNode().put("cursor", "task-1").put("limit", 25))));
            assertThat(next(handler).payload().path("tasks").get(0).path("taskId").asText())
                    .isEqualTo("task-2");
        }
    }

    private StableProtocolHandler handler(CapabilityToken token, AgentApplicationService application) {
        return new StableProtocolHandler(token,
                Set.of(ProtocolFeature.RUN, ProtocolFeature.CANCEL), application);
    }

    private ProtocolEnvelope initialize(CapabilityToken token, long sequence) {
        ObjectNode payload = codec.objectNode().put("token", token.reveal()).put("version", "1.0");
        payload.putArray("features").add("RUN").add("CANCEL").add("DAEMON");
        return request("initialize", "init", sequence, Optional.empty(), payload);
    }

    private ProtocolEnvelope request(
            String type, String id, long sequence, Optional<String> idempotency, ObjectNode payload) {
        return new ProtocolEnvelope(
                ProtocolVersion.V1_0, ProtocolMessageKind.REQUEST, type, id, "client",
                Optional.of("session-1"), Optional.empty(), sequence, idempotency, payload);
    }

    private byte[] encoded(ProtocolEnvelope envelope) {
        return codec.encode(envelope);
    }

    private ProtocolEnvelope next(StableProtocolHandler handler) throws Exception {
        return codec.decode(handler.takeOutput(Duration.ofSeconds(2)).orElseThrow());
    }

    private ProtocolEnvelope awaitType(StableProtocolHandler handler, String type) throws Exception {
        for (int i = 0; i < 300; i++) {
            Optional<byte[]> output = handler.takeOutput(Duration.ofMillis(20));
            if (output.isPresent()) {
                ProtocolEnvelope decoded = codec.decode(output.orElseThrow());
                if (type.equals(decoded.type())) return decoded;
            }
        }
        throw new AssertionError(type + " 未发布");
    }

    private List<ProtocolEnvelope> drain(StableProtocolHandler handler) throws Exception {
        ArrayList<ProtocolEnvelope> outputs = new ArrayList<>();
        for (int i = 0; i < StableProtocolHandler.MAX_EGRESS_MESSAGES + 10; i++) {
            Optional<byte[]> next = handler.takeOutput(Duration.ofMillis(5));
            if (next.isEmpty()) break;
            outputs.add(codec.decode(next.orElseThrow()));
        }
        return outputs;
    }

    private static class FakeApplication implements AgentApplicationService {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        volatile RunId active;
        volatile boolean cancelled;
        volatile boolean draining;
        volatile int runCount;

        @Override
        public AgentRunResult run(AgentRunRequest request, AgentEventSink sink) {
            runCount++;
            active = new RunId("run-1");
            sink.publish(new AgentEventEnvelope(1, Instant.EPOCH, new SessionId("session-1"),
                    Optional.of(active), new LifecycleEvent.RunStarted(request)));
            started.countDown();
            try {
                finish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            active = null;
            return cancelled
                    ? AgentRunResult.stopped(new SessionId("session-1"), new RunId("run-1"),
                    io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED, 0, 0)
                    : AgentRunResult.completed(new SessionId("session-1"), new RunId("run-1"),
                    "done", 1, 0);
        }

        @Override public boolean cancel(RunId runId) {
            if (!runId.equals(active)) return false;
            cancelled = true;
            finish.countDown();
            return true;
        }
        @Override public void beginDrain() { draining = true; }
        @Override public boolean awaitTermination(Duration timeout) { return active == null; }
        @Override public Optional<RunId> activeRun() { return Optional.ofNullable(active); }
        @Override public void close() { beginDrain(); finish.countDown(); }
    }

    private static final class TaskSnapshotApplication extends FakeApplication {
        private final TaskListService tasks;

        private TaskSnapshotApplication() {
            SessionId sessionId = new SessionId("session-1");
            tasks = new TaskListService(new TaskBoardId("board-1"), sessionId,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), TaskRunState.noneTerminated());
            var capability = TaskBoardCapabilityFactory.root(
                    tasks.snapshot().boardId(), sessionId, new RunId("run-task"));
            tasks.execute(capability, new TaskMutation.Create(new TaskCallId("create-1"), "first", "",
                    Optional.empty(), TaskMetadata.EMPTY, List.of()));
            tasks.execute(capability, new TaskMutation.Create(new TaskCallId("create-2"), "second", "",
                    Optional.empty(), TaskMetadata.EMPTY, List.of()));
        }

        @Override
        public Optional<TaskBoardSnapshot> taskBoardSnapshot() { return Optional.of(tasks.snapshot()); }
    }

    private static final class FloodApplication extends FakeApplication {
        private final int count;
        private AgentEventSink sink;
        private final CountDownLatch finished = new CountDownLatch(1);

        private FloodApplication(int count) {
            this.count = count;
            finish.countDown();
        }

        @Override
        public AgentRunResult run(AgentRunRequest request, AgentEventSink sink) {
            this.sink = sink;
            runCount++;
            active = new RunId("run-1");
            for (int i = 1; i <= count; i++) {
                sink.publish(new AgentEventEnvelope(i, Instant.EPOCH, new SessionId("session-1"),
                        Optional.of(active), new LifecycleEvent.RunStarted(request)));
            }
            active = null;
            finished.countDown();
            return AgentRunResult.completed(new SessionId("session-1"), new RunId("run-1"),
                    "done", 1, 0);
        }

        private void publishLate() {
            if (sink != null) {
                sink.publish(new AgentEventEnvelope(9999, Instant.EPOCH, new SessionId("session-1"),
                        Optional.empty(), new LifecycleEvent.SessionEnded()));
            }
        }
    }
}
