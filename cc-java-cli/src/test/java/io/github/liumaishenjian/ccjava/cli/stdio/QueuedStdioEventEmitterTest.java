package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class QueuedStdioEventEmitterTest {

    private final StdioProtocolCodec codec = new StdioProtocolCodec();

    @Test
    void assignsMonotonicSequenceAndWritesOnlyNdjson() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QueuedStdioEventEmitter emitter = new QueuedStdioEventEmitter(
                codec,
                output,
                4,
                Duration.ofMillis(500));

        emitter.emit(
                "initialized",
                "req-1",
                Optional.of("session-1"),
                Optional.empty(),
                payload("value", "first"));
        emitter.emit(
                "run.started",
                "req-2",
                Optional.of("session-1"),
                Optional.of("run-1"),
                payload("value", "second"));
        emitter.close();

        String[] lines = output.toString(StandardCharsets.UTF_8)
                .strip()
                .split("\\R");
        assertThat(lines).hasSize(2);
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode first = mapper.readTree(lines[0]);
        JsonNode second = mapper.readTree(lines[1]);
        assertThat(first.get("sequence").longValue()).isEqualTo(1);
        assertThat(second.get("sequence").longValue()).isEqualTo(2);
        assertThat(first.get("payload").get("value").stringValue()).isEqualTo("first");
        assertThat(second.get("payload").get("value").stringValue()).isEqualTo("second");
    }

    @Test
    void failsWithinBoundWhenClientStopsReading() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        QueuedStdioEventEmitter emitter = new QueuedStdioEventEmitter(
                codec,
                output,
                1,
                Duration.ofMillis(50));
        try {
            emitter.emit(
                    "run.started",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("status", "running"));
            assertThat(output.writeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            emitter.emit(
                    "model.text.delta",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("text", "second"));

            assertThatThrownBy(() -> emitter.emit(
                    "model.text.delta",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("text", "third")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("队列已满");
        } finally {
            output.releaseWrite.countDown();
            emitter.close();
        }
    }

    @Test
    void acceptsDetachedResumeReviewButStillValidatesRunBoundReview() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QueuedStdioEventEmitter emitter = new QueuedStdioEventEmitter(
                codec,
                output,
                4,
                Duration.ofMillis(500));
        try {
            emitter.emit(
                    "plan.review.requested",
                    "resume-1",
                    Optional.of("session-1"),
                    Optional.empty(),
                    payload("planId", "plan-1"));
            assertThatThrownBy(() -> emitter.emit(
                    "plan.review.requested",
                    "decision-1",
                    Optional.of("session-1"),
                    Optional.of("run-missing"),
                    payload("planId", "plan-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("run.started");
        } finally {
            emitter.close();
        }
    }

    @Test
    void rejectsSecondTerminalAndEventsAfterTerminal() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QueuedStdioEventEmitter emitter = new QueuedStdioEventEmitter(
                codec,
                output,
                4,
                Duration.ofMillis(500));
        try {
            emitter.emit(
                    "run.started",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("state", "started"));
            emitter.emit(
                    "run.completed",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("state", "completed"));

            assertThatThrownBy(() -> emitter.emit(
                    "run.cancelled",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("state", "cancelled")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("终态后");
            assertThatThrownBy(() -> emitter.emit(
                    "model.text.delta",
                    "req-1",
                    Optional.of("session-1"),
                    Optional.of("run-1"),
                    payload("text", "late")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("终态后");
        } finally {
            emitter.close();
        }
    }

    private ObjectNode payload(String field, String value) {
        ObjectNode payload = codec.objectNode();
        payload.put(field, value);
        return payload;
    }

    private static final class BlockingOutputStream extends OutputStream {

        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException {
            awaitRelease();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            awaitRelease();
        }

        private void awaitRelease() throws IOException {
            writeEntered.countDown();
            try {
                if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("测试输出未释放");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("测试输出被中断", exception);
            }
        }
    }
}
