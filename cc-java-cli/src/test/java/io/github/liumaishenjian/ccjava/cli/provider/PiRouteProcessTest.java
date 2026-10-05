package io.github.liumaishenjian.ccjava.cli.provider;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 生产路由/Store/RPC/Node/公开SDK的loopback；工具结果由Fixture提供，不冒充AgentRuntime执行。 */
class PiRouteProcessTest {
    @TempDir Path home;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest @ValueSource(strings = {"deepseek", "qwen-token-plan-cn"})
    void realCredentialRpcAndToolPairSurviveTwoTurns(String provider) throws Exception {
        String node = System.getProperty("codej.test.nodeExecutable");
        assumeTrue(node != null && !node.isBlank(), "Explicit Node opt-in required");
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        List<String> headers = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean first = requests.size() == 1;
            String delta = first
                    ? "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"fixture-call\",\"type\":\"function\",\"function\":{\"name\":\"fixture_read\",\"arguments\":\"{}\"}}]}"
                    : "{\"role\":\"assistant\",\"content\":\"delivered\"}";
            String body = "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                    + (first ? "tool_calls" : "stop") + "\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var catalog = new PiProviderCatalog();
            String model = catalog.require(provider).models().getFirst().id();
            var identity = new PiCredentialIdentity(provider, PiCredentialIdentity.AuthMethod.API_KEY, "fixture-profile");
            var store = new PiCredentialStore(home);
            try (var material = PiCredentialMaterial.envRef("CODEJ_FIXTURE_KEY")) {
                store.saveLogin(identity, material, store.snapshot(CancellationToken.none()).generation(), false,
                        CancellationToken.none());
            }
            Path component = Path.of("../cc-java-provider-pi").toAbsolutePath().normalize();
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            Path worker = home.resolve("worker.mjs");
            Files.writeString(worker,
                    "import {runWorker} from " + JSON.writeValueAsString(component.resolve("worker-runtime.mjs").toUri().toString()) + ";\n"
                    + "import {createModelOperation} from " + JSON.writeValueAsString(component.resolve("model-operation.mjs").toUri().toString()) + ";\n"
                    + "import {createRegisteredProviders} from " + JSON.writeValueAsString(component.resolve("provider-registry.mjs").toUri().toString()) + ";\n"
                    + "const origin=" + JSON.writeValueAsString(origin) + ";\n"
                    + "const providersFactory=()=>createRegisteredProviders().map(p=>({...p,getModels:()=>p.getModels().map(m=>({...m,baseUrl:origin+'/v1'}))}));\n"
                    + "const model=o=>createModelOperation({...o,providersFactory,fetchImpl:(url,init)=>{if(new URL(typeof url==='string'?url:url.url).origin!==origin)throw Error('NETWORK_FORBIDDEN');return fetch(url,init);}});\n"
                    + "process.exit(await runWorker({input:process.stdin,output:process.stdout,catalog:()=>({}),model,timeoutMillis:15000}));\n",
                    StandardCharsets.UTF_8);
            var config = new PiWorkerConfiguration(Path.of(node), worker, Map.of());
            var leases = new CredentialLeaseRegistry();
            List<byte[]> exported = new ArrayList<>();
            var pi = new PiSelectedProviderRouteFactory(store, leases, catalog, () -> config, name -> {
                assertThat(name).isEqualTo("CODEJ_FIXTURE_KEY");
                byte[] value = "synthetic-route-fixture".getBytes(StandardCharsets.UTF_8);
                exported.add(value);
                return value;
            });
            var routes = new SelectedProviderRouteFactory(new ProviderDefinitionStore(home),
                    new CredentialResolver(new RestrictedFileCredentialStore(Files.createDirectory(home.resolve("legacy"))), Map.of()),
                    leases, ProviderGatewayFactoryRegistry.production(), pi);
            var selected = new ProviderSelectionSnapshot(provider, identity.profileId(), model, "pi", "API_KEY");
            var gateway = routes.lazyGateway(() -> Optional.of(selected));
            var definitions = List.of(ToolDefinition.readOnlyText("fixture_read", "Independent fixture", "{\"type\":\"object\"}"));
            var user = new UserMessage("Read the independent fixture then deliver the answer");
            try (var scope = gateway.openRun(Duration.ofSeconds(30))) {
                scope.bindCancellation(() -> { });
                assertThat(exported).isEmpty();
                assertThat(leases.activeCount(identity)).isEqualTo(1);
                var first = gateway.complete(new ModelRequest(new SessionId("fixture-session"), new RunId("fixture-run"),
                        1, List.of(user), definitions));
                assertThat(first.assistantMessage().toolCalls()).hasSize(1);
                assertThat(first.assistantMessage().continuation()).isPresent();
                var call = first.assistantMessage().toolCalls().getFirst();
                assertThat(call.id()).isEqualTo("fixture-call");
                assertThat(call.name()).isEqualTo("fixture_read");
                // 这里只验证工具消息往返；真实Tool Pipeline/权限与产物验收另行覆盖。
                var tool = new ToolResultMessage(ToolResult.success(call.id(), call.name(), "independent fixture result"));
                var last = gateway.complete(new ModelRequest(new SessionId("fixture-session"), new RunId("fixture-run"),
                        2, List.of(user, first.assistantMessage(), tool), definitions));
                assertThat(last.assistantMessage().text()).isEqualTo("delivered");
                assertThat(last.assistantMessage().toolCalls()).isEmpty();
                var summaryRequest = new SummaryRequest(SummaryTier.C3_ROLLING, "Keep delivered as an anchor",
                        9, List.of("source-one", "source-two"), List.of("delivered"), 512, 64, 200);
                var candidate = ((ContextSummarizer) gateway).summarize(summaryRequest, CancellationToken.none());
                assertThat(candidate).contains(new SummaryCandidate(SummaryTier.C3_ROLLING, "delivered", 9,
                        summaryRequest.sourceMessageIds(), 9, 9));
            }
            assertThat(leases.activeCount(identity)).isZero();
            assertThat(home.resolve("legacy/.cc-java")).doesNotExist();
            assertThat(exported).hasSize(3).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
            assertThat(headers).hasSize(3).containsOnly("Bearer synthetic-route-fixture");
            assertThat(requests).hasSize(3);
            var messages = requests.get(1).path("messages");
            assertThat(messages.toString()).contains("fixture-call", "independent fixture result");
            var summaryHttp = requests.getLast();
            assertThat(!summaryHttp.has("tools") || summaryHttp.path("tools").isEmpty()).isTrue();
            assertThat(summaryHttp.has("max_tokens") ^ summaryHttp.has("max_completion_tokens")).isTrue();
            assertThat(summaryHttp.path(summaryHttp.has("max_tokens") ? "max_tokens" : "max_completion_tokens")
                    .asLong()).isEqualTo(64);
            var summaryMessages = summaryHttp.path("messages");
            var content = summaryMessages.get(summaryMessages.size() - 1).path("content");
            String envelopeText = content.isString() ? content.asString() : content.get(0).path("text").asString();
            assertThat(JSON.readTree(envelopeText).path("kind").asString()).isEqualTo("cc-java-summary-request-v1");
        } finally { server.stop(0); }
    }
}
