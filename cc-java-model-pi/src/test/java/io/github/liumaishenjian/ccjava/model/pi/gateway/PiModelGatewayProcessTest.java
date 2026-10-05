package io.github.liumaishenjian.ccjava.model.pi.gateway;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.model.pi.process.PiWorkerConfiguration;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实Java/Node/Pi SDK，但地址只由受信Fixture工厂改为loopback；不验证官方端点或账号。 */
class PiModelGatewayProcessTest {
    @TempDir Path temporary;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest @ValueSource(ints = {200, 429})
    void actualEofAndExitAreRequiredForSuccessAndTypedFailure(int status) throws Exception {
        String node = System.getProperty("codej.test.nodeExecutable");
        assumeTrue(node != null && !node.isBlank(), "Explicit Node opt-in required");
        var attempts = new AtomicInteger(); var credentialCloses = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            String body;
            if (status == 200) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                body = "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n"
                    + "data: [DONE]\n\n";
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Retry-After", "1");
                body = "{\"error\":{\"message\":\"DO_NOT_LEAK_UPSTREAM\"}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            Path component = Path.of("../cc-java-provider-pi").toAbsolutePath().normalize();
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            Path worker = temporary.resolve("worker.mjs");
            String script = "import {runWorker} from " + JSON.writeValueAsString(component.resolve("worker-runtime.mjs").toUri().toString()) + ";\n"
                + "import {createModelOperation} from " + JSON.writeValueAsString(component.resolve("model-operation.mjs").toUri().toString()) + ";\n"
                + "import {createRegisteredProviders} from " + JSON.writeValueAsString(component.resolve("provider-registry.mjs").toUri().toString()) + ";\n"
                + "const origin=" + JSON.writeValueAsString(origin) + ";\n"
                + "const providersFactory=()=>createRegisteredProviders().map(p=>({...p,getModels:()=>p.getModels().filter(m=>m.api==='openai-completions').slice(0,1).map(m=>({...m,id:'fixture-model',baseUrl:origin+'/v1'}))}));\n"
                + "const model=o=>createModelOperation({...o,providersFactory,fetchImpl:(url,init)=>{if(new URL(typeof url==='string'?url:url.url).origin!==origin)throw Error('NETWORK_FORBIDDEN');return fetch(url,init);}});\n"
                + "process.exit(await runWorker({input:process.stdin,output:process.stdout,catalog:()=>({}),model,timeoutMillis:10000}));\n";
            Files.writeString(worker, script, StandardCharsets.UTF_8);
            var config = new PiWorkerConfiguration(Path.of(node), worker, Map.of());
            try (var gateway = new PiModelGateway(config, "deepseek", "fixture-model", Duration.ofSeconds(15),
                    (id, token) -> new PiCredentialSession() {
                        public ObjectNode handle(ProtocolFrame frame) {
                            assertThat(frame.operationId()).isEqualTo(id);
                            assertThat(frame.payload().path("action").asString()).isEqualTo("read");
                            var reply = JSON.createObjectNode();
                            reply.put("requestId", frame.payload().path("requestId").asLong()).put("ok", true);
                            reply.putObject("result").putObject("credential").put("type", "api_key").put("key", "synthetic");
                            return reply;
                        }
                        public void close() { credentialCloses.incrementAndGet(); }
                    })) {
                var request = new ModelRequest(new SessionId("session"), new RunId("run"), 1,
                        List.of(new UserMessage("Independent loopback fixture")), List.of());
                if (status == 200) {
                    var result = gateway.complete(request);
                    assertThat(result.assistantMessage().text()).isEqualTo("hello");
                    assertThat(result.assistantMessage().continuation()).isPresent();
                    assertThat(result.metadata().providerModel()).isEmpty();
                    assertThat(result.metadata().usage()).contains(new ModelUsage(5, 2, 7));
                    assertThat(attempts.get()).isEqualTo(1);
                    var second = gateway.complete(new ModelRequest(request.sessionId(), request.runId(), 2,
                            List.of(request.messages().getFirst(), result.assistantMessage(), new UserMessage("Continue")), List.of()));
                    assertThat(second.assistantMessage().text()).isEqualTo("hello");
                    assertThat(second.assistantMessage().continuation()).isPresent();
                } else {
                    assertThatThrownBy(() -> gateway.complete(request)).isInstanceOfSatisfying(ModelGatewayException.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(ModelGatewayException.FailureKind.RETRYABLE);
                        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(1));
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getMessage()).doesNotContain("DO_NOT_LEAK_UPSTREAM");
                    });
                }
            }
            assertThat(attempts.get()).isEqualTo(status == 200 ? 2 : 1);
            assertThat(credentialCloses.get()).isEqualTo(status == 200 ? 2 : 1);
        } finally { server.stop(0); }
    }
}
