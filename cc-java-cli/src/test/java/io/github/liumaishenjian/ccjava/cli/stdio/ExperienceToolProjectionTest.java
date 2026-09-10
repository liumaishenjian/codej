package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

class ExperienceToolProjectionTest {
    @TempDir Path temporaryRoot;

    @Test
    void previewPreservesUnicodeAndLinesButRemovesTerminalControlsAndBoundsJson() {
        String input = "中文😀\n\t\u001b\u009b" + "😀".repeat(5000);
        String preview = RuntimeStdioCommandHandler.boundedToolContent(input);
        assertThat(preview).startsWith("中文😀\n\t").doesNotContain("\u001b", "\u009b");
        assertThat(preview.codePointCount(0, preview.length())).isLessThanOrEqualTo(4096);
        var payload = new StdioProtocolCodec().objectNode();
        payload.put("content", RuntimeStdioCommandHandler.boundedToolContent("\\".repeat(5000)));
        assertThat(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(32768);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void onlyNegotiatedClientsReceiveRealRequestModelAndToolContent(boolean enabled, boolean sensitive) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Files.writeString(workspace.resolve("sample.txt"), sensitive ? "api_key=fake-test-value" : "content-marker 中文\n");
        var options = new HeadlessRuntimeOptions(workspace, "fixture-request-model", Duration.ofSeconds(3),
                PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(), temporaryRoot.resolve("sessions"));
        var codec = new StdioProtocolCodec();
        var events = new CopyOnWriteArrayList<Event>();
        var done = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, request, session, run, payload) -> {
            events.add(new Event(type, session, payload.deepCopy()));
            if (type.equals("run.completed") || type.equals("run.failed")) done.countDown();
        };
        var calls = new AtomicInteger();
        try (var handler = new RuntimeStdioCommandHandler(request -> calls.getAndIncrement() == 0
                ? ModelTurn.tools(List.of(new ToolCall("read-call", "read_file",
                        new JsonObject(Map.of("path", "sample.txt"))))) : ModelTurn.text("done"), options)) {
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                    + "\"sequence\":1,\"payload\":{\"experienceV1\":%s}}").formatted(enabled)), emitter);
            String session = events.getFirst().session().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\",\"requestId\":\"run\","
                    + "\"sessionId\":\"%s\",\"sequence\":2,\"payload\":{\"prompt\":\"read sample\"}}")
                    .formatted(session)), emitter);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).noneMatch(event -> event.type().equals("run.failed"));
            ObjectNode run = payload(events, "run.started");
            ObjectNode start = payload(events, "tool.started");
            ObjectNode result = payload(events, "tool.completed");
            assertThat(run.has("requestModel")).isEqualTo(enabled);
            assertThat(start.has("callId")).isEqualTo(enabled);
            assertThat(result.has("content")).isEqualTo(enabled);
            if (enabled) {
                assertThat(run.get("requestModel").stringValue()).isEqualTo("fixture-request-model");
                assertThat(start.get("callId").stringValue()).isEqualTo("read-call");
                assertThat(start.get("turn").intValue()).isEqualTo(1);
                assertThat(start.get("parametersPreview").stringValue()).contains("sample.txt");
                assertThat(result.get("callId").stringValue()).isEqualTo("read-call");
                assertThat(result.get("contentRedacted").booleanValue()).isEqualTo(sensitive);
                if (sensitive) assertThat(result.get("content").stringValue()).isEmpty();
                else assertThat(result.get("content").stringValue()).contains("content-marker 中文");
                assertThat(result.get("contentTruncated").booleanValue()).isFalse();
            }
        }
    }

    private static ObjectNode payload(List<Event> events, String type) {
        return events.stream().filter(event -> event.type().equals(type)).findFirst().orElseThrow().payload();
    }
    private record Event(String type, Optional<String> session, ObjectNode payload) {}
}
