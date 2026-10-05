package io.github.liumaishenjian.ccjava.cli.provider;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.session.*;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import io.github.liumaishenjian.ccjava.tools.local.tool.ReadFileTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.WriteFileTool;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S15 / ADR-100 / MODEL-13 L1→L1、CTX-07/09：补齐关闭 Writer 后的新 Run 与 Fork 编码、同源摘要采纳。
 *
 * <p>本批沿用 ADR-100 的 Session/Context 授权职责研究，不重做未变机制；新增参考为公开 Pi 0.85.1。
 * 对照链：PiCodingLoopProcessTest 的 Selected→PiStore/RPC→Worker→public registry/SDK；
 * PiRouteProcessTest 的摘要 User 信封；ContextModelBindingTest 的 compact→install→prepare Gate。
 * 源码分类 Observed，不代表公开 CLI/TUI、真实账号或在线验收。独立 HTTP 脚本严格为
 * read/final、RESUME read/final、FORK read/write/read/final、summary，共九请求；不重放旧副作用。
 * 工具只作用于临时 workspace；DEFAULT+精确 Allow Once 是测试策略，Checkpoint 使用已有 no-op 构造接缝，
 * 不是完整生产 Checkpoint 装配证据。所有最终产物必须由真实 write_file 创建。
 *
 * <p>明确测试偏差：Node 24 Codex zstd 只在双 fetch guard 接缝解压给 JDK fixture。
 * 公开 SDK api/openai-codex-responses.js#buildRequestBody 不序列化 maxTokens；故 Codex 仅核对
 * SummaryRequest/HTTP User 信封的固定预算及 Core 采纳，明确断言缺少 wire 输出上限，不能声称该路
 * HTTP 原生上限已覆盖；其他三路严格核对原生上限。不得为消除这一 gap 改 API 或注入请求字段。
 * 实际运行结果由主任务登记；新增测试本身不提升等级。
 */
class PiSessionCodingProcessTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration BUDGET = Duration.ofSeconds(45);
    private static final String KEY = "synthetic-session-coding-key";
    private static final String ACCOUNT = "synthetic-session-coding-account";
    private static final int SUMMARY_TOKENS = 128;
    private static final List<Integer> TOOL_ROUNDS = List.of(1, 3, 5, 6, 7);
    @TempDir Path temporary;

    @ParameterizedTest(name = "{0}: durable resume/fork coding and SDK summary adoption")
    @ValueSource(strings = {"openai", "openai-codex", "deepseek", "qwen-token-plan-cn"})
    void resumedAndForkedRunsDeliverBeforeBoundSummaryIsAdopted(String provider) throws Exception {
        Path node = explicitNode();
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path sessions = temporary.resolve("sessions");
        String marker = "file-only-" + UUID.randomUUID();
        String artifact = "artifact:" + marker;
        String summary = "kept " + marker;
        // 唯一测试预写是公开输入；artifact.txt 不得由 fixture 的 Java/Node 代码写入。
        Files.writeString(workspace.resolve("fixture.txt"), marker + "\n", StandardCharsets.UTF_8);
        boolean codex = provider.equals("openai-codex");
        boolean responses = codex || provider.equals("openai");
        String api = codex ? "openai-codex-responses" : responses ? "openai-responses" : "openai-completions";
        String endpoint = codex ? "/v1/codex/responses" : responses ? "/v1/responses" : "/v1/chat/completions";
        var catalog = new PiProviderCatalog();
        var model = catalog.require(provider).models().stream().filter(m -> m.api().equals(api)).findFirst().orElseThrow();
        String access = syntheticAccessToken();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicReference<SummaryRequest> summarySource = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                requests.add(request);
                int round = requests.size();
                assertThat(round).as("No hidden retry or extra HTTP turn").isBetween(1, 9);
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestURI().toString()).isEqualTo(endpoint);
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer " + (codex ? access : KEY));
                if (codex) assertThat(exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id")).isEqualTo(ACCOUNT);
                assertThat(request.path("model").asString()).isEqualTo(model.id());
                assertThat(request.path("stream").asBoolean()).isTrue();
                if (round == 9) {
                    assertSummaryWire(request, responses, codex, summarySource.get(), marker);
                } else {
                    assertThat(request.path("tools").toString()).contains("read_file", "write_file");
                    assertWireHistory(request, responses, round, marker);
                    if (round == 1) assertThat(request.toString()).doesNotContain(marker);
                    if (round >= 7) assertThat(Files.readString(workspace.resolve("artifact.txt"))).isEqualTo(artifact);
                }
                String text = round == 9 ? summary : delivery(round, marker);
                String body = responses ? responsesSse(round, text, artifact) : completionsSse(round, text, artifact);
                byte[] encoded = bytes(body);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, encoded.length);
                exchange.getResponseBody().write(encoded);
            } catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                exchange.sendResponseHeaders(400, -1);
            } finally { exchange.close(); }
        });
        server.start();
        try (var leases = new CredentialLeaseRegistry()) {
            var identity = new PiCredentialIdentity(provider, codex ? PiCredentialIdentity.AuthMethod.OAUTH
                    : PiCredentialIdentity.AuthMethod.API_KEY, "session-fixture");
            var credentials = new PiCredentialStore(home);
            try (var material = codex ? PiCredentialMaterial.oauth(bytes(access), bytes("synthetic-never-refresh"),
                    Instant.now().plus(Duration.ofDays(365)).toEpochMilli(), bytes(ACCOUNT))
                    : PiCredentialMaterial.envRef("CODEJ_SESSION_FIXTURE_KEY")) {
                credentials.saveLogin(identity, material, credentials.snapshot(CancellationToken.none()).generation(),
                        false, CancellationToken.none());
            }
            long generation = credentials.snapshot(CancellationToken.none()).generation();
            Path audit = temporary.resolve("fetch-audit.txt");
            Path worker = writeWorker(home, audit, "http://127.0.0.1:" + server.getAddress().getPort());
            var configuration = new PiWorkerConfiguration(node, worker, Map.of());
            List<byte[]> exports = new CopyOnWriteArrayList<>();
            var pi = new PiSelectedProviderRouteFactory(credentials, leases, catalog, () -> configuration, name -> {
                assertThat(codex).as("OAuth must not fall back to ENV").isFalse();
                assertThat(name).isEqualTo("CODEJ_SESSION_FIXTURE_KEY");
                byte[] value = bytes(KEY); exports.add(value); return value;
            });
            Path legacy = Files.createDirectory(temporary.resolve("legacy"));
            var routes = new SelectedProviderRouteFactory(new ProviderDefinitionStore(home),
                    new CredentialResolver(new RestrictedFileCredentialStore(legacy), Map.of()), leases,
                    ProviderGatewayFactoryRegistry.production(), pi);
            var selection = new ProviderSelectionSnapshot(provider, identity.profileId(), model.id(), "pi",
                    identity.authMethod().name());
            var routed = routes.lazyGateway(() -> Optional.of(selection));
            var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
            AtomicInteger sequence = new AtomicInteger();
            AgentIdGenerator ids = new AgentIdGenerator() {
                public SessionId newSessionId() { return new SessionId("session-pi-" + sequence.incrementAndGet()); }
                public RunId newRunId() { return new RunId("run-pi-" + sequence.incrementAndGet()); }
            };
            var spec = new SessionSpec("Use workspace tools; report only observed file content.", Map.of("model", model.id()));
            SessionId original = null, fork = null;
            Set<RunId> runs = new HashSet<>();
            List<AgentMessage> canonical = List.of(), originalCanonical = List.of();
            AtomicInteger approvals = new AtomicInteger();
            for (int stage = 0; stage < 3; stage++) {
                // 每次先关闭 Writer/model scope，再打开新 Store，确保不是内存 Resume/Fork。
                try (var store = new FileSessionStore(sessions, workspace, ids, lifecycle, Clock.systemUTC());
                     var scope = routed.openRun(BUDGET)) {
                    var binding = scope.binding().orElseThrow();
                    assertThat(binding.selection()).contains(selection);
                    assertThat(leases.activeCount(identity)).isEqualTo(1);
                    AgentSession session;
                    if (stage == 0) {
                        session = store.create(spec); original = session.id();
                    } else {
                        var opened = store.open(new SessionOpenRequest(stage == 1 ? SessionOpenMode.RESUME
                                : SessionOpenMode.FORK, Optional.of(original)), spec);
                        assertThat(opened.issues()).isEmpty();
                        session = opened.session();
                        assertThat(session.messages()).containsExactlyElementsOf(canonical);
                        assertThat(session.hasActiveRun()).isFalse();
                        if (stage == 1) assertThat(session.id()).isEqualTo(original);
                        else {
                            fork = session.id();
                            assertThat(fork).isNotEqualTo(original);
                            assertThat(fork.value()).startsWith("session-");
                            originalCanonical = canonical;
                            assertThat(workspace.resolve("artifact.txt")).doesNotExist();
                        }
                    }
                    var guard = new WorkspaceGuard(workspace);
                    var tools = new ToolRegistry(List.of(new ReadFileTool(guard), new WriteFileTool(guard)));
                    var pipeline = new ToolExecutionPipeline(tools, new FixedPermissionGate(PermissionMode.DEFAULT),
                            (invocation, definition, outcome) -> {
                                assertThat(definition.name()).isEqualTo("write_file");
                                assertThat(invocation.call().arguments()).isEqualTo(new JsonObject(
                                        Map.of("path", "artifact.txt", "content", artifact)));
                                assertThat(approvals.incrementAndGet()).isEqualTo(1);
                                return ApprovalResponse.allowOnce();
                            }, new InMemorySessionPermissionState(), lifecycle, store);
                    var runtime = new AgentRuntime(store, ids, binding.gateway(), new DefaultContextAssembler(),
                            tools, pipeline, lifecycle, store);
                    int finalRound = stage == 0 ? 2 : stage == 1 ? 4 : 8;
                    var result = runtime.run(session.id(), new AgentRunRequest(new UserMessage(stage == 2
                            ? "Read fixture.txt, create artifact.txt from its marker using write_file, read artifact.txt, then report."
                            : "Read fixture.txt with read_file again and report its marker."), new AgentLimits(5, 3, BUDGET)));
                    assertThat(serverFailure.get()).as("HTTP script assertions").isNull();
                    assertThat(result.stopReason()).as("provider=%s, HTTP=%s, safe failure=%s", provider,
                            requests.size(), result.modelFailure()).isEqualTo(StopReason.COMPLETED);
                    assertThat(result.finalText()).contains(delivery(finalRound, marker));
                    assertThat(result.modelTurns()).isEqualTo(stage == 2 ? 4 : 2);
                    assertThat(result.toolCalls()).isEqualTo(stage == 2 ? 3 : 1);
                    assertThat(runs.add(result.runId())).as("Each actual Run has a new identity").isTrue();
                    assertThat(session.hasActiveRun()).isFalse();
                    assertThat(session.messages().subList(0, canonical.size())).containsExactlyElementsOf(canonical);
                    canonical = List.copyOf(session.messages());
                    assertCanonical(canonical, provider, model.id(), api, responses, stage == 2 ? 5 : stage + 1, marker);
                    assertThat(requests).hasSize(finalRound);
                }
                assertThat(leases.activeCount(identity)).isZero();
            }
            assertThat(approvals).hasValue(1);
            assertThat(runs).hasSize(3);
            assertThat(Files.readString(workspace.resolve("artifact.txt"))).isEqualTo(artifact);
            // 真实摘要端口只被透明记录，不返回手造 SummaryCandidate。
            List<AgentMessage> source = List.copyOf(canonical);
            try (var scope = routed.openRun(BUDGET)) {
                var binding = scope.binding().orElseThrow();
                assertThat(binding.selection()).contains(selection);
                var root = new ContextPreparationService(new ContextPreparationConfig(
                        new ContextCapacity(model.id(), 100_000, 256, 128), 10_000, 1, 512, SUMMARY_TOKENS),
                        (request, token) -> { throw new AssertionError("Unbound summary used"); });
                var context = root.withSummarizer((request, token) -> {
                    assertThat(summarySource.compareAndSet(null, request)).as("Exactly one real summary request").isTrue();
                    assertThat(request.sourceRevision()).isEqualTo(source.size());
                    assertThat(request.maxOutputTokens()).isEqualTo(SUMMARY_TOKENS);
                    return binding.summarizer().summarize(request, token);
                });
                var compact = context.compact(source, List.of(marker), CancellationToken.none());
                assertThat(serverFailure.get()).as("Summary HTTP assertions").isNull();
                assertThat(compact.status()).isEqualTo(ContextPreparationService.ExplicitCompactStatus.ADOPTED);
                var projection = compact.projection().orElseThrow();
                assertThat(projection.messages().stream().filter(ContextSummaryMessage.class::isInstance)
                        .map(ContextSummaryMessage.class::cast)).singleElement().satisfies(message -> {
                            assertThat(message.content()).isEqualTo(summary);
                            assertThat(message.tier()).isEqualTo(summarySource.get().tier());
                            assertThat(message.sourceMessageIds()).isEqualTo(summarySource.get().sourceMessageIds());
                        });
                assertThat(projection.messages().getLast()).isEqualTo(source.getLast());
                assertThat(source).containsExactlyElementsOf(canonical);
                context.installForNextRun(source, projection);
                RunId projectionRun = ids.newRunId();
                try {
                    var request = new ModelRequest(fork, projectionRun, 1, source, List.of());
                    assertThat(context.prepare(request, CancellationToken.none()).messages())
                            .containsExactlyElementsOf(projection.messages());
                    assertThat(context.prepare(request, CancellationToken.none()).messages()).containsExactlyElementsOf(source);
                } finally { context.closeRun(projectionRun); }
            }
            assertThat(leases.activeCount(identity)).isZero();
            // 再取得两个 Writer，证明没有 activeRun/Writer 遗留，摘要没有污染任何 durable canonical。
            try (var store = new FileSessionStore(sessions, workspace, ids, lifecycle, Clock.systemUTC())) {
                var parent = store.open(new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(original)), spec);
                var child = store.open(new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(fork)), spec);
                assertThat(parent.issues()).isEmpty(); assertThat(child.issues()).isEmpty();
                assertThat(parent.session().hasActiveRun()).isFalse(); assertThat(child.session().hasActiveRun()).isFalse();
                assertThat(parent.session().messages()).containsExactlyElementsOf(originalCanonical);
                assertThat(child.session().messages()).containsExactlyElementsOf(canonical);
            }
            assertThat(requests).hasSize(9);
            assertThat(serverFailure.get()).isNull();
            assertThat(Files.readAllLines(audit)).hasSize(9).containsOnly("allowed");
            assertThat(exports).hasSize(codex ? 0 : 9).allSatisfy(value -> assertThat(value).containsOnly((byte) 0));
            assertThat(credentials.snapshot(CancellationToken.none()).generation()).isEqualTo(generation);
            assertThat(legacy.resolve(".cc-java")).doesNotExist();
        } finally { server.stop(0); }
    }

    /** 检查完整历史的严格顺序与唯一 ID；下一响应只在所有实际 Tool Result 含证据时发送。 */
    private static void assertWireHistory(JsonNode request, boolean responses, int round, String marker) {
        List<JsonNode> calls = new ArrayList<>(), results = new ArrayList<>();
        for (JsonNode message : request.path(responses ? "input" : "messages")) {
            if (responses) {
                if (message.path("type").asString().equals("function_call")) calls.add(message);
                if (message.path("type").asString().equals("function_call_output")) results.add(message);
            } else {
                for (JsonNode call : message.path("tool_calls")) calls.add(call);
                if (message.path("role").asString().equals("tool")) results.add(message);
            }
        }
        List<Integer> expected = TOOL_ROUNDS.stream().filter(n -> n < round).toList();
        assertThat(calls).hasSize(expected.size()); assertThat(results).hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            int n = expected.get(index);
            assertThat(calls.get(index).path(responses ? "call_id" : "id").asString()).isEqualTo(call(n));
            assertThat(results.get(index).path(responses ? "call_id" : "tool_call_id").asString()).isEqualTo(call(n));
            assertThat(results.get(index).path(responses ? "output" : "content").toString()).contains(marker);
        }
    }

    private static void assertCanonical(List<AgentMessage> messages, String provider, String model, String api,
            boolean responses, int expectedCalls, String marker) {
        List<ToolCall> calls = new ArrayList<>(); List<ToolResult> results = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message instanceof AssistantMessage assistant) {
                calls.addAll(assistant.toolCalls());
                var continuation = assistant.continuation().orElseThrow();
                assertThat(continuation.backend()).isEqualTo("pi");
                assertThat(continuation.providerId()).isEqualTo(provider);
                assertThat(continuation.modelId()).isEqualTo(model);
                assertThat(JSON.readTree(continuation.payload()).path("api").asString()).isEqualTo(api);
            }
            if (message instanceof ToolResultMessage result) results.add(result.result());
        }
        assertThat(calls).hasSize(expectedCalls); assertThat(results).hasSize(expectedCalls);
        assertThat(calls.stream().map(ToolCall::id)).doesNotHaveDuplicates();
        for (int i = 0; i < expectedCalls; i++) {
            int n = TOOL_ROUNDS.get(i);
            assertThat(calls.get(i).id()).isEqualTo(call(n) + (responses ? "|" + item(n) : ""));
            assertThat(calls.get(i).name()).isEqualTo(tool(n));
            assertThat(results.get(i).callId()).isEqualTo(calls.get(i).id());
            assertThat(results.get(i).toolName()).isEqualTo(calls.get(i).name());
            assertThat(results.get(i).status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(results.get(i).error()).isEmpty();
            assertThat(results.get(i).content()).contains(marker);
        }
    }

    private static void assertSummaryWire(JsonNode request, boolean responses, boolean codex,
            SummaryRequest source, String marker) {
        assertThat(source).isNotNull();
        assertThat(!request.has("tools") || request.path("tools").isEmpty()).isTrue();
        if (codex) {
            // 公开 Codex API 不序列化此选项；这是明确缺口，不注入虚假的 API 上限。
            assertThat(request.has("max_output_tokens")).isFalse();
        } else {
            String field = responses ? "max_output_tokens" : request.has("max_tokens") ? "max_tokens" : "max_completion_tokens";
            assertThat(request.path(field).asLong()).isEqualTo(SUMMARY_TOKENS);
        }
        List<JsonNode> users = new ArrayList<>();
        for (JsonNode message : request.path(responses ? "input" : "messages")) {
            String role = message.path("role").asString();
            if (role.equals("system") || role.equals("developer")) {
                assertThat(message.toString()).doesNotContain(marker, "inputBase64", "cc-java-summary-request-v1");
            } else {
                assertThat(role).isEqualTo("user"); users.add(message);
            }
        }
        assertThat(request.path("instructions").toString()).doesNotContain(marker, "inputBase64");
        assertThat(users).hasSize(1);
        JsonNode content = users.getFirst().path("content");
        String text = content.isString() ? content.asString() : content.get(0).path("text").asString();
        JsonNode envelope = JSON.readTree(text);
        assertThat(envelope.path("kind").asString()).isEqualTo("cc-java-summary-request-v1");
        assertThat(envelope.path("maxOutputTokens").asLong()).isEqualTo(SUMMARY_TOKENS);
        assertThat(envelope.path("sourceRevision").asLong()).isEqualTo(source.sourceRevision());
        assertThat(envelope.path("tier").asString()).isEqualTo(source.tier().name());
        assertThat(envelope.path("maxOutputUtf8Bytes").asLong()).isEqualTo(source.maxOutputUtf8Bytes());
        assertThat(envelope.path("sourceMessageIds")).isEqualTo(JSON.valueToTree(source.sourceMessageIds()));
        assertThat(new String(Base64.getDecoder().decode(envelope.path("inputBase64").asString()), StandardCharsets.UTF_8))
                .isEqualTo(source.inputSnapshot());
    }

    private static String call(int round) { return "call_session_" + round; }
    private static String item(int round) { return "fc_session_" + round; }
    private static String tool(int round) { return round == 6 ? "write_file" : "read_file"; }
    private static String arguments(int round, String artifact) {
        return JSON.writeValueAsString(round == 6 ? Map.of("path", "artifact.txt", "content", artifact)
                : Map.of("path", round == 7 ? "artifact.txt" : "fixture.txt"));
    }
    private static String delivery(int round, String marker) { return "delivered-" + round + ":" + marker; }

    /** 每个 HTTP 回合独立标识，保留 Provider 原生 SSE，绝不生成私有 Worker 终态。 */
    private static String completionsSse(int round, String text, String artifact) {
        boolean tools = TOOL_ROUNDS.contains(round);
        Object delta = tools ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                "id", call(round), "type", "function", "function", Map.of("name", tool(round), "arguments", arguments(round, artifact)))))
                : Map.of("role", "assistant", "content", text);
        return "data: " + JSON.writeValueAsString(Map.of("id", "completion_" + round, "choices", List.of(
                Map.of("index", 0, "delta", delta)))) + "\n\n"
                + "data: " + JSON.writeValueAsString(Map.of("id", "completion_" + round, "choices", List.of(
                Map.of("index", 0, "delta", Map.of(), "finish_reason", tools ? "tool_calls" : "stop")),
                "usage", Map.of("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30)))
                + "\n\ndata: [DONE]\n\n";
    }

    private static String responsesSse(int round, String text, String artifact) {
        StringBuilder sse = new StringBuilder();
        String response = "resp_session_" + round, message = "msg_session_" + round;
        event(sse, "response.created", Map.of("response", Map.of("id", response, "status", "in_progress")));
        Map<String, Object> completed;
        if (TOOL_ROUNDS.contains(round)) {
            var added = Map.<String, Object>of("type", "function_call", "id", item(round), "call_id", call(round),
                    "name", tool(round), "arguments", "");
            event(sse, "response.output_item.added", Map.of("output_index", 0, "item", added));
            event(sse, "response.function_call_arguments.delta", Map.of("output_index", 0, "item_id", item(round),
                    "delta", arguments(round, artifact)));
            completed = new LinkedHashMap<>(added); completed.put("arguments", arguments(round, artifact));
        } else {
            event(sse, "response.output_item.added", Map.of("output_index", 0, "item", Map.of("type", "message",
                    "id", message, "role", "assistant", "content", List.of())));
            event(sse, "response.content_part.added", Map.of("output_index", 0, "content_index", 0,
                    "item_id", message, "part", Map.of("type", "output_text", "text", "", "annotations", List.of())));
            event(sse, "response.output_text.delta", Map.of("output_index", 0, "content_index", 0,
                    "item_id", message, "delta", text));
            completed = Map.of("type", "message", "id", message, "role", "assistant", "content",
                    List.of(Map.of("type", "output_text", "text", text, "annotations", List.of())));
        }
        event(sse, "response.output_item.done", Map.of("output_index", 0, "item", completed));
        event(sse, "response.completed", Map.of("response", Map.of("id", response, "status", "completed",
                "output", List.of(completed), "usage", Map.of("input_tokens", 20, "output_tokens", 10, "total_tokens", 30))));
        return sse.toString();
    }

    private static void event(StringBuilder sse, String type, Map<String, Object> fields) {
        var data = new LinkedHashMap<String, Object>(fields); data.put("type", type);
        sse.append("event: ").append(type).append("\ndata: ").append(JSON.writeValueAsString(data)).append("\n\n");
    }

    /** 仅允许显式绝对路径或固定 D:/node；不搜索 PATH、不下载、不访问真实账号。 */
    private static Path explicitNode() {
        String configured = System.getProperty("codej.test.nodeExecutable");
        Path node = configured == null || configured.isBlank() ? Path.of("D:/node/node.exe") : Path.of(configured);
        assumeTrue(node.isAbsolute() && Files.isRegularFile(node),
                "Real Pi session process skipped: provide codej.test.nodeExecutable or D:/node/node.exe");
        return node;
    }

    private static String syntheticAccessToken() {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes("{\"alg\":\"none\"}")) + "."
                + encoder.encodeToString(JSON.writeValueAsBytes(Map.of("https://api.openai.com/auth",
                        Map.of("chatgpt_account_id", ACCOUNT)))) + ".synthetic";
    }

    /** SDK 导入前封闭 global fetch，并把同一精确 origin guard 注入生产 model operation。 */
    private static Path writeWorker(Path home, Path audit, String origin) throws Exception {
        Path component = Path.of("../cc-java-provider-pi").toAbsolutePath().normalize();
        if (!Files.isRegularFile(component.resolve("worker-runtime.mjs"))) {
            component = Path.of("cc-java-provider-pi").toAbsolutePath().normalize();
        }
        assertThat(component.resolve("worker-runtime.mjs")).isRegularFile();
        Path worker = home.resolve("worker.mjs");
        Files.writeString(worker, """
                import {appendFileSync} from 'node:fs';
                import * as zlib from 'node:zlib';
                const origin = %s;
                const audit = %s;
                process.env.HOME = %s;
                process.env.USERPROFILE = process.env.HOME;
                process.env.XDG_CONFIG_HOME = process.env.HOME;
                const nativeFetch = globalThis.fetch.bind(globalThis);
                const restrictedFetch = (input, init) => {
                  const url = new URL(input instanceof Request ? input.url : String(input));
                  if (url.origin !== origin || url.username || url.password) {
                    appendFileSync(audit, 'forbidden\\n');
                    throw Error('NETWORK_FORBIDDEN');
                  }
                  appendFileSync(audit, 'allowed\\n');
                  // 仅fixture解压：原生Codex API、生产Worker和公开registry均保持不变。
                  const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
                  let body = init?.body;
                  if (headers.get('content-encoding') === 'zstd') {
                    body = zlib.zstdDecompressSync(body);
                    headers.delete('content-encoding');
                    headers.delete('content-length');
                  }
                  return nativeFetch(input, {...init, headers, body, redirect: 'error'});
                };
                globalThis.fetch = restrictedFetch;
                const {runWorker} = await import(%s);
                const {createModelOperation} = await import(%s);
                const {createRegisteredProviders} = await import(%s);
                const providersFactory = () => createRegisteredProviders().map(provider => ({
                  ...provider,
                  getModels: () => provider.getModels().map(model => ({...model, baseUrl: origin + '/v1'}))
                }));
                const model = options => createModelOperation({...options, providersFactory, fetchImpl: restrictedFetch});
                process.exit(await runWorker({input: process.stdin, output: process.stdout,
                  catalog: () => ({}), model, timeoutMillis: 30000}));
                """.formatted(JSON.writeValueAsString(origin), JSON.writeValueAsString(audit.toString()),
                JSON.writeValueAsString(home.toString()),
                JSON.writeValueAsString(component.resolve("worker-runtime.mjs").toUri().toString()),
                JSON.writeValueAsString(component.resolve("model-operation.mjs").toUri().toString()),
                JSON.writeValueAsString(component.resolve("provider-registry.mjs").toUri().toString())), StandardCharsets.UTF_8);
        return worker;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
