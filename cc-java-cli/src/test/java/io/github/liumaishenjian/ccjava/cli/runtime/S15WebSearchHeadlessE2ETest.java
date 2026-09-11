package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.stdio.RuntimeStdioCommandHandler;
import io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocol;
import io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolCodec;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.tools.web.WebSearchConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/** TOOL-18 外部配置 Gate 与 Headless 生产扩展装配的专项 E2E。 */
class S15WebSearchHeadlessE2ETest {

    @Test
    void defaultDisabledCompositionDoesNotRegisterWebSearch(@TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        var resources = WebSearchRuntimeResources.fromConfiguration(WebSearchConfiguration.disabled());
        try (HeadlessRuntimeSession runtime = runtime(workspace, root, request -> ModelTurn.text("done"),
                resources, (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            assertThat(runtime.builtinToolRegistry().definitions()).extracting(definition -> definition.name()).doesNotContain("web_search");
        }
    }

    @Test
    void externallyEnabledCompositionRegistersAndExecutesRealLoopbackSearch(@TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        AtomicInteger hits = new AtomicInteger();
        try (Loopback fixture = new Loopback(hits)) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider.EXA, fixture.uri(), Optional.empty(), Duration.ofSeconds(3));
            CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
            ModelGateway model = request -> {
                requests.add(request);
                if (requests.size() == 1) return new ModelTurn(AssistantMessage.tools(List.of(new ToolCall("call-headless-web", "web_search", new JsonObject(Map.of("query", "bounded", "result_limit", 1))))), ModelTurnMetadata.unknown());
                return ModelTurn.text("done");
            };
            try (HeadlessRuntimeSession runtime = runtime(workspace, root, model, WebSearchRuntimeResources.fromConfiguration(configuration), (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
                runtime.open();
                assertThat(runtime.builtinToolRegistry().definitions()).extracting(definition -> definition.name()).contains("web_search");
                assertThat(runtime.run("search").stopReason()).isEqualTo(StopReason.COMPLETED);
            }
            assertThat(hits).hasValue(1);
            assertThat(requests.getLast().messages()).filteredOn(ToolResultMessage.class::isInstance).singleElement().satisfies(message -> assertThat(((ToolResultMessage) message).result().content()).contains("provenance: external-web-search", "providerHost: 127.0.0.1", "Headless"));
        }
    }

    @Test
    void planNetworkReadRemainsPermissionGatedBeforeExecution(@TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        AtomicInteger hits = new AtomicInteger();
        try (Loopback fixture = new Loopback(hits)) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(
                    io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider.EXA,
                    fixture.uri(), Optional.empty(), Duration.ofSeconds(3));
            WebSearchRuntimeResources resources = WebSearchRuntimeResources.fromConfiguration(configuration);
            AtomicInteger turns = new AtomicInteger();
            CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
            StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                    events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
            StdioProtocolCodec codec = new StdioProtocolCodec();
            HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(workspace, "fake-model",
                    Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(),
                    root.resolve("sessions"));
            try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler((eventSink, approvals) ->
                    new HeadlessRuntimeSession(request -> turns.incrementAndGet() == 1
                            ? ModelTurn.tools(List.of(new ToolCall("plan-web", "web_search",
                                    new JsonObject(Map.of("query", "planning fact", "result_limit", 1)))))
                            : ModelTurn.text("stop"), eventSink, options, approvals,
                            ContextPreparationService.noop(), null,
                            HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                            HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")),
                            null, true, resources))) {
                handler.handle(codec.decodeCommand(
                        "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
                String sessionId = events.getFirst().sessionId().orElseThrow();
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                        + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                        + "\"payload\":{\"prompt\":\"plan with current fact\"}}").formatted(sessionId)), emitter);
                CapturedEvent approval = awaitEvent(events, "approval.requested");
                assertThat(approval.payload().toString()).contains("network_or_remote", "planning fact");
                assertThat(hits).hasValue(0);
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                        + "\"requestId\":\"deny\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                        + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\",\"decision\":\"deny\"}}")
                        .formatted(sessionId, approval.runId().orElseThrow(),
                                approval.payload().get("approvalId").stringValue())), emitter);
                CapturedEvent failed = awaitEvent(events, "run.failed");
                assertThat(failed.payload().get("stopReason").stringValue())
                        .isEqualTo("invalid_model_response");
                assertThat(failed.payload().get("modelTurns").intValue()).isEqualTo(3);
                assertThat(failed.payload().get("toolCalls").intValue()).isEqualTo(1);
                assertThat(events).filteredOn(event -> event.type().equals("tool.failed")).hasSize(1);
                assertThat(events).noneMatch(event -> event.type().equals("plan.review.requested"));
                assertThat(hits).hasValue(0);
            }
        }
    }

    @Test
    void stdioAllowsTwoNetworkRequestsWithOnceThenSession(@TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        AtomicInteger hits = new AtomicInteger();
        try (Loopback fixture = new Loopback(hits)) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider.EXA, fixture.uri(), Optional.empty(), Duration.ofSeconds(3));
            WebSearchRuntimeResources resources = WebSearchRuntimeResources.fromConfiguration(configuration);
            AtomicInteger turns = new AtomicInteger();
            CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
            StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
            StdioProtocolCodec codec = new StdioProtocolCodec();
            HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(), root.resolve("sessions"));
            try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler((eventSink, approvals) -> new HeadlessRuntimeSession(request -> switch (turns.incrementAndGet()) {
                case 1 -> ModelTurn.tools(List.of(new ToolCall("call-first-web", "web_search", new JsonObject(Map.of("query", "first", "result_limit", 1)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("call-second-web", "web_search", new JsonObject(Map.of("query", "second", "result_limit", 1)))));
                case 3 -> ModelTurn.tools(List.of(new ToolCall("call-third-web", "web_search", new JsonObject(Map.of("query", "third", "result_limit", 1)))));
                default -> ModelTurn.text("done");
            }, eventSink, options, approvals, ContextPreparationService.noop(), null, HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(), HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")), null, true, resources))) {
                handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
                String sessionId = events.getFirst().sessionId().orElseThrow();
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\",\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,\"payload\":{\"prompt\":\"search twice\"}}").formatted(sessionId)), emitter);
                CapturedEvent first = awaitEvent(events, "approval.requested");
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\",\"requestId\":\"approve-1\",\"sessionId\":\"%s\",\"runId\":\"%s\",\"sequence\":3,\"payload\":{\"approvalId\":\"%s\",\"decision\":\"allow_once\"}}").formatted(sessionId, first.runId().orElseThrow(), first.payload().get("approvalId").stringValue())), emitter);
                CapturedEvent second = awaitSecondApproval(events, first.payload().get("approvalId").stringValue());
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\",\"requestId\":\"approve-2\",\"sessionId\":\"%s\",\"runId\":\"%s\",\"sequence\":4,\"payload\":{\"approvalId\":\"%s\",\"decision\":\"allow_session\"}}").formatted(sessionId, second.runId().orElseThrow(), second.payload().get("approvalId").stringValue())), emitter);
                assertThat(awaitEvent(events, "run.completed").type()).isEqualTo("run.completed");
                assertThat(events).filteredOn(event -> event.type().equals("approval.requested")).hasSize(2);
            }
            assertThat(hits).hasValue(3);
        }
    }

    @Test
    void stdioFactoryWaitsForNetworkApprovalBeforeExecutingSearch(@TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        AtomicInteger hits = new AtomicInteger();
        try (Loopback fixture = new Loopback(hits)) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider.EXA, fixture.uri(), Optional.empty(), Duration.ofSeconds(3));
            WebSearchRuntimeResources resources = WebSearchRuntimeResources.fromConfiguration(configuration);
            AtomicInteger turns = new AtomicInteger();
            CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
            StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
            StdioProtocolCodec codec = new StdioProtocolCodec();
            HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(), root.resolve("sessions"));
            try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler((eventSink, approvals) -> new HeadlessRuntimeSession(request -> turns.incrementAndGet() == 1 ? new ModelTurn(AssistantMessage.tools(List.of(new ToolCall("call-stdio-web", "web_search", new JsonObject(Map.of("query", "明天杭州天气", "result_limit", 1))))), ModelTurnMetadata.unknown()) : ModelTurn.text("明天晴"), eventSink, options, approvals, ContextPreparationService.noop(), null, HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(), HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")), null, true, resources))) {
                handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
                String sessionId = events.getFirst().sessionId().orElseThrow();
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\",\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,\"payload\":{\"prompt\":\"weather\"}}").formatted(sessionId)), emitter);
                CapturedEvent approval = awaitEvent(events, "approval.requested");
                assertThat(approval.payload().toString()).contains("\"effect\":\"network_or_remote\"", "\"destination\":\"configured_web_search_provider\"", "\"query\":\"明天杭州天气\"", "\"operation\":\"search\"").doesNotContain("http://", "Authorization", "api-key");
                assertThat(hits).hasValue(0);
                handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\",\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\",\"sequence\":3,\"payload\":{\"approvalId\":\"%s\",\"decision\":\"allow_once\"}}").formatted(sessionId, approval.runId().orElseThrow(), approval.payload().get("approvalId").stringValue())), emitter);
                assertThat(awaitEvent(events, "run.completed").type()).isEqualTo("run.completed");
            }
            assertThat(hits).hasValue(1);
        }
    }

    private static HeadlessRuntimeSession runtime(java.nio.file.Path workspace, java.nio.file.Path root, ModelGateway model, WebSearchRuntimeResources webResources, io.github.liumaishenjian.ccjava.core.ApprovalHandler approvals) {
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(), root.resolve("sessions"));
        return new HeadlessRuntimeSession(model, AgentEventSink.noop(), options, approvals, ContextPreparationService.noop(), null, HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(), HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")), null, true, webResources);
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;
        Loopback(AtomicInteger hits) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/search", exchange -> {
                hits.incrementAndGet();
                byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Headless with https://example.com/source\"}]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }
        java.net.URI uri() { return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"); }
        @Override public void close() { server.stop(0); }
    }

    private static CapturedEvent awaitSecondApproval(CopyOnWriteArrayList<CapturedEvent> events, String firstApprovalId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (CapturedEvent event : events) if (event.type().equals("approval.requested") && !event.payload().get("approvalId").stringValue().equals(firstApprovalId)) return event;
            Thread.sleep(10);
        }
        throw new AssertionError("未收到第二个审批请求");
    }

    private static CapturedEvent awaitEvent(CopyOnWriteArrayList<CapturedEvent> events, String type) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (CapturedEvent event : events) if (event.type().equals(type)) return event;
            Thread.sleep(10);
        }
        throw new AssertionError("未收到事件: " + type + ", actual=" + events.stream().map(CapturedEvent::type).toList());
    }

    private record CapturedEvent(String type, Optional<String> sessionId, Optional<String> runId, ObjectNode payload) {}
}
