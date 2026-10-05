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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S15 / ADR-100 / MODEL-13 L1→L1：四路真实 SDK 的离线编码闭环，不代表 CLI/TUI 或在线账号验收。
 *
 * <p>对照入口：本项目 PiRouteProcessTest 的 Selected→PiStore/RPC→Worker；公开 Pi 0.85.1
 * providers/{openai,openai-codex,deepseek,qwen-token-plan-cn}→各自 API→Responses/Completions SSE。
 * 分类为公开依赖源码 Observed；本批不使用商业源码。保持各路 API，不替换 modelsFactory，
 * 第一轮只提出 read_file 意图，Java Runtime/Pipeline 执行后第二轮才允许交付。
 * Responses 的复合调用 ID 在 Java 内原样配对，在 HTTP 上按公开协议拆成 call_id/item id。
 * 独立场景终态由 canonical、两次 HTTP、最终正文及 lease 排空共同证伪，不能用工具成功替代交付。
 * 未验证项由主任务运行本测试后登记，不据新增测试提升等级。</p>
 */
class PiCodingLoopProcessTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration BUDGET = Duration.ofSeconds(45);
    private static final String CALL = "call_codej_read";
    private static final String ITEM = "fc_codej_read";
    private static final String ARGUMENTS = "{\"path\":\"fixture.txt\"}";
    private static final String KEY = "synthetic-coding-loop-key";
    private static final String ACCOUNT = "synthetic-coding-loop-account";
    @TempDir Path temporary;

    @ParameterizedTest(name = "{0}: production route, RPC, SDK and Java read_file loop")
    @ValueSource(strings = {"openai", "openai-codex", "deepseek", "qwen-token-plan-cn"})
    void fourRoutesDeliverOnlyAfterRealReadPipeline(String provider) throws Exception {
        Path node = explicitNode();
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path sessions = temporary.resolve("sessions");
        String marker = "file-only-" + UUID.randomUUID();
        String delivery = "codej-delivered-" + provider + "-" + marker;
        Files.writeString(workspace.resolve("fixture.txt"), marker + "\n", StandardCharsets.UTF_8);
        boolean codex = provider.equals("openai-codex");
        boolean responses = provider.equals("openai") || codex;
        String api = codex ? "openai-codex-responses" : responses ? "openai-responses" : "openai-completions";
        String endpoint = codex ? "/v1/codex/responses" : responses ? "/v1/responses" : "/v1/chat/completions";
        var catalog = new PiProviderCatalog();
        // 从生产目录选择对应 API，绝不把 OpenAI/Codex 强制转成 Completions。
        var model = catalog.require(provider).models().stream().filter(m -> m.api().equals(api)).findFirst().orElseThrow();
        String access = syntheticAccessToken();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        // 只记录固定阶段与单调耗时；读体未结束时也能区分“没有HTTP”和“已经进入handler”。
        List<String> httpPhases = new CopyOnWriteArrayList<>();
        long httpStarted = System.nanoTime();
        java.util.function.Consumer<String> phase = name -> httpPhases.add(name + "@"
                + Duration.ofNanos(System.nanoTime() - httpStarted).toMillis());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                phase.accept("HANDLER_ENTERED");
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                phase.accept("BODY_READ");
                JsonNode request = JSON.readTree(requestBody);
                requests.add(request);
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestURI().toString()).isEqualTo(endpoint);
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer " + (codex ? access : KEY));
                if (codex) assertThat(exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id")).isEqualTo(ACCOUNT);
                assertThat(request.path("model").asString()).isEqualTo(model.id());
                assertThat(request.path("stream").asBoolean()).isTrue();
                assertThat(request.path("tools").toString()).contains("read_file", "path");
                assertThat(requests.size()).as("No hidden SDK/Java retry or third model turn").isLessThanOrEqualTo(2);
                boolean first = requests.size() == 1;
                if (first) assertThat(request.toString()).doesNotContain(marker);
                else assertWireToolPair(request, responses, marker);
                byte[] body = (responses ? responsesSse(first, delivery) : completionsSse(first, delivery))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                phase.accept("HEADERS_SENT");
                exchange.getResponseBody().write(body);
                phase.accept("BODY_WRITTEN");
            } catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                // 固定失败响应，不把不满足工具证据的请求转换成成功正文。
                exchange.sendResponseHeaders(400, -1);
            } finally {
                try { exchange.close(); phase.accept("EXCHANGE_CLOSED"); }
                catch (Throwable closeFailure) { serverFailure.compareAndSet(null, closeFailure); }
            }
        });
        server.start();
        try (var leases = new CredentialLeaseRegistry()) {
            var identity = new PiCredentialIdentity(provider, codex ? PiCredentialIdentity.AuthMethod.OAUTH
                    : PiCredentialIdentity.AuthMethod.API_KEY, "loop-fixture");
            var credentials = new PiCredentialStore(home);
            try (var material = codex ? PiCredentialMaterial.oauth(bytes(access), bytes("synthetic-never-refresh"),
                    Instant.now().plus(Duration.ofDays(365)).toEpochMilli(), bytes(ACCOUNT))
                    : PiCredentialMaterial.envRef("CODEJ_LOOP_FIXTURE_KEY")) {
                credentials.saveLogin(identity, material, credentials.snapshot(CancellationToken.none()).generation(),
                        false, CancellationToken.none());
            }
            long generation = credentials.snapshot(CancellationToken.none()).generation();
            Path audit = temporary.resolve("fetch-audit.txt");
            Path worker = writeWorker(home, audit, "http://127.0.0.1:" + server.getAddress().getPort());
            var configuration = new PiWorkerConfiguration(node, worker, Map.of());
            List<byte[]> exports = new CopyOnWriteArrayList<>();
            var pi = new PiSelectedProviderRouteFactory(credentials, leases, catalog, () -> configuration, name -> {
                assertThat(codex).as("OAuth never falls back to environment").isFalse();
                assertThat(name).isEqualTo("CODEJ_LOOP_FIXTURE_KEY");
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
            AgentIdGenerator ids = new AgentIdGenerator() {
                @Override public SessionId newSessionId() { return new SessionId("session-coding-loop"); }
                @Override public RunId newRunId() { return new RunId("run-coding-loop"); }
            };
            var spec = new SessionSpec("Read the requested workspace fixture, then deliver its content.",
                    Map.of("model", model.id()));
            SessionId sessionId;
            List<AgentMessage> canonical;
            try (var store = new FileSessionStore(sessions, workspace, ids, lifecycle, Clock.systemUTC());
                 var scope = routed.openRun(BUDGET)) {
                var binding = scope.binding().orElseThrow();
                assertThat(binding.selection()).contains(selection);
                assertThat(leases.activeCount(identity)).isEqualTo(1);
                assertThat(exports).isEmpty();
                var tools = new ToolRegistry(List.of(new ReadFileTool(new WorkspaceGuard(workspace))));
                var pipeline = new ToolExecutionPipeline(tools, new FixedPermissionGate(PermissionMode.PLAN),
                        (invocation, definition, outcome) -> { throw new AssertionError("Read must not ask approval"); },
                        new InMemorySessionPermissionState(), lifecycle, store);
                var runtime = new AgentRuntime(store, ids, binding.gateway(), new DefaultContextAssembler(),
                        tools, pipeline, lifecycle, store);
                var session = store.create(spec);
                sessionId = session.id();
                var result = runtime.run(sessionId, new AgentRunRequest(
                        new UserMessage("Read fixture.txt with read_file, then report the file marker."),
                        new AgentLimits(3, 1, BUDGET)));
                assertThat(serverFailure.get()).as("Independent HTTP fixture assertions").isNull();
                assertThat(result.stopReason()).as("provider=%s; HTTP count=%s; phases=%s; safe failure=%s",
                        provider, requests.size(), httpPhases, result.modelFailure()).isEqualTo(StopReason.COMPLETED);
                assertThat(result.finalText()).contains(delivery);
                assertThat(result.modelTurns()).isEqualTo(2);
                assertThat(result.toolCalls()).isEqualTo(1);
                assertThat(session.hasActiveRun()).isFalse();
                canonical = List.copyOf(session.messages());
                assertCanonical(canonical, provider, model.id(), api, responses, marker, delivery);
            }
            assertThat(leases.activeCount(identity)).isZero();
            assertThat(requests).hasSize(2);
            assertThat(serverFailure.get()).isNull();
            assertThat(Files.readAllLines(audit)).containsExactly("allowed", "allowed");
            assertThat(exports).hasSize(codex ? 0 : 2)
                    .allSatisfy(value -> assertThat(value).containsOnly((byte) 0));
            assertThat(credentials.snapshot(CancellationToken.none()).generation()).isEqualTo(generation);
            assertThat(legacy.resolve(".cc-java")).doesNotExist();
            // 再次走真实 JSONL 恢复，不能仅凭内存 transcript 宣称 continuation 已持久化。
            try (var reopened = new FileSessionStore(sessions, workspace, ids, lifecycle, Clock.systemUTC())) {
                var restored = reopened.open(new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(sessionId)), spec);
                assertThat(restored.issues()).isEmpty();
                assertThat(restored.session().hasActiveRun()).isFalse();
                assertThat(restored.session().messages()).containsExactlyElementsOf(canonical);
                assertCanonical(restored.session().messages(), provider, model.id(), api, responses, marker, delivery);
            }
        } finally { server.stop(0); }
    }

    /** 只允许显式路径或维护者指定的固定本机 Node；缺失时明确跳过，不搜索 PATH、不安装。 */
    private static Path explicitNode() {
        String configured = System.getProperty("codej.test.nodeExecutable");
        Path node = configured == null || configured.isBlank() ? Path.of("D:/node/node.exe") : Path.of(configured);
        assumeTrue(node.isAbsolute() && Files.isRegularFile(node),
                "Real Pi process skipped: provide codej.test.nodeExecutable or D:/node/node.exe");
        return node;
    }

    /** 合成无签名效力 JWT 仅满足公开 Codex SDK 的 account claim 解析，不进行登录或刷新。 */
    private static String syntheticAccessToken() {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes("{\"alg\":\"none\"}")) + "."
                + encoder.encodeToString(JSON.writeValueAsBytes(Map.of("https://api.openai.com/auth",
                        Map.of("chatgpt_account_id", ACCOUNT)))) + ".synthetic";
    }

    /** Worker 仅替换受信 baseUrl/fetch 接缝；在导入 SDK 前封闭全局 fetch，重定向一律失败。 */
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
                  // JDK fixture没有zstd解码器；仅在本测试fetch seam还原SDK压缩的JSON，不替换API或模型。
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

    private static void assertCanonical(List<AgentMessage> messages, String provider, String model, String api,
            boolean responses, String marker, String delivery) {
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        AssistantMessage intent = (AssistantMessage) messages.get(1);
        assertThat(intent.toolCalls()).hasSize(1);
        ToolCall call = intent.toolCalls().getFirst();
        assertThat(call.id()).isEqualTo(responses ? CALL + "|" + ITEM : CALL);
        assertThat(call.name()).isEqualTo("read_file");
        assertThat(call.arguments()).isEqualTo(new JsonObject(Map.of("path", "fixture.txt")));
        assertThat(messages.get(2)).isInstanceOf(ToolResultMessage.class);
        ToolResult result = ((ToolResultMessage) messages.get(2)).result();
        assertThat(result.callId()).isEqualTo(call.id());
        assertThat(result.toolName()).isEqualTo(call.name());
        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.error()).isEmpty();
        assertThat(result.content()).contains(marker);
        assertThat(messages.get(3)).isInstanceOf(AssistantMessage.class);
        AssistantMessage last = (AssistantMessage) messages.get(3);
        assertThat(last.text()).isEqualTo(delivery);
        assertThat(last.toolCalls()).isEmpty();
        for (AssistantMessage assistant : List.of(intent, last)) {
            var continuation = assistant.continuation().orElseThrow();
            assertThat(continuation.backend()).isEqualTo("pi");
            assertThat(continuation.providerId()).isEqualTo(provider);
            assertThat(continuation.modelId()).isEqualTo(model);
            assertThat(JSON.readTree(continuation.payload()).path("api").asString()).isEqualTo(api);
        }
    }

    private static void assertWireToolPair(JsonNode request, boolean responses, String marker) {
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
        assertThat(calls).hasSize(1);
        assertThat(results).hasSize(1);
        assertThat(calls.getFirst().path(responses ? "call_id" : "id").asString()).isEqualTo(CALL);
        assertThat(results.getFirst().path(responses ? "call_id" : "tool_call_id").asString()).isEqualTo(CALL);
        assertThat(results.getFirst().path(responses ? "output" : "content").toString()).contains(marker);
    }

    /** 本项目独立生成的最小 Completions SSE；仅第二轮已核对真实工具输出后返回正文。 */
    private static String completionsSse(boolean first, String delivery) {
        Object delta = first ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                "id", CALL, "type", "function", "function", Map.of("name", "read_file", "arguments", ARGUMENTS))))
                : Map.of("role", "assistant", "content", delivery);
        return "data: " + JSON.writeValueAsString(Map.of("id", "codej-completion", "choices", List.of(
                Map.of("index", 0, "delta", delta)))) + "\n\n"
                + "data: " + JSON.writeValueAsString(Map.of("id", "codej-completion", "choices", List.of(
                Map.of("index", 0, "delta", Map.of(), "finish_reason", first ? "tool_calls" : "stop")),
                "usage", Map.of("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30)))
                + "\n\ndata: [DONE]\n\n";
    }

    /** Responses/Codex 保留各自生产 API；两者共同消费公开 Responses 事件，不生成私有 Worker 终态。 */
    private static String responsesSse(boolean first, String delivery) {
        StringBuilder sse = new StringBuilder();
        event(sse, "response.created", Map.of("response", Map.of("id", "resp_codej", "status", "in_progress")));
        Map<String, Object> completed;
        if (first) {
            var item = Map.<String, Object>of("type", "function_call", "id", ITEM, "call_id", CALL,
                    "name", "read_file", "arguments", "");
            event(sse, "response.output_item.added", Map.of("output_index", 0, "item", item));
            event(sse, "response.function_call_arguments.delta", Map.of("output_index", 0, "item_id", ITEM,
                    "delta", ARGUMENTS));
            completed = new LinkedHashMap<>(item); completed.put("arguments", ARGUMENTS);
        } else {
            event(sse, "response.output_item.added", Map.of("output_index", 0, "item", Map.of("type", "message",
                    "id", "msg_codej", "role", "assistant", "content", List.of())));
            event(sse, "response.content_part.added", Map.of("output_index", 0, "content_index", 0,
                    "item_id", "msg_codej", "part", Map.of("type", "output_text", "text", "", "annotations", List.of())));
            event(sse, "response.output_text.delta", Map.of("output_index", 0, "content_index", 0,
                    "item_id", "msg_codej", "delta", delivery));
            completed = Map.of("type", "message", "id", "msg_codej", "role", "assistant", "content",
                    List.of(Map.of("type", "output_text", "text", delivery, "annotations", List.of())));
        }
        event(sse, "response.output_item.done", Map.of("output_index", 0, "item", completed));
        event(sse, "response.completed", Map.of("response", Map.of("id", "resp_codej", "status", "completed",
                "output", List.of(completed), "usage", Map.of("input_tokens", 20, "output_tokens", 10, "total_tokens", 30))));
        return sse.toString();
    }

    private static void event(StringBuilder sse, String type, Map<String, Object> fields) {
        var data = new LinkedHashMap<String, Object>(fields); data.put("type", type);
        sse.append("event: ").append(type).append("\ndata: ").append(JSON.writeValueAsString(data)).append("\n\n");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
