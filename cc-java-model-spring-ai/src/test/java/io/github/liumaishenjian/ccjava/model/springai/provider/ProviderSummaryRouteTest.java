package io.github.liumaishenjian.ccjava.model.springai.provider;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ContextSummarizer;
import io.github.liumaishenjian.ccjava.domain.*;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** ADR-099：真实工厂装饰器必须保留同源摘要端口；仅合成 loopback，不访问真实账号。 */
class ProviderSummaryRouteTest {
    @Test void compatibleFactoriesSendModelAndSummaryToSameClientIdentityWithoutExtraRequests() throws Exception {
        for (ProviderGatewayFactory factory : List.of(
                new OpenAiCompatibleProviderGatewayFactory(), new OpenRouterProviderGatewayFactory())) {
            List<String> bodies = Collections.synchronizedList(new ArrayList<>());
            List<String> auth = Collections.synchronizedList(new ArrayList<>());
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes = stream().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            char[] synthetic = "route-fixture-key".toCharArray();
            try {
                var gateway = factory.create(new ProviderGatewayConfiguration("fixture-provider",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture-model",
                        Map.of(), Duration.ofSeconds(5), synthetic));
                try (AutoCloseable resource = (AutoCloseable) gateway) {
                    assertThat(gateway).isInstanceOf(ContextSummarizer.class);
                    assertThat(gateway.complete(new ModelRequest(new SessionId("session"), new RunId("run"), 1,
                            List.of(new UserMessage("synthetic request")), List.of())).assistantMessage().text()).isEqualTo("ok");
                    var summary = ((ContextSummarizer) gateway).summarize(new SummaryRequest(
                            SummaryTier.values()[0], "bounded synthetic transcript", 3, List.of("message-1"),
                            List.of(), 128, 64, 256), CancellationToken.none());
                    assertThat(summary).hasValueSatisfying(value -> {
                        assertThat(value.summary()).isEqualTo("ok");
                        assertThat(value.sourceRevision()).isEqualTo(3);
                    });
                    assertThat(bodies).hasSize(2).allSatisfy(body -> assertThat(body).contains("fixture-model"));
                    assertThat(auth).containsExactly("Bearer route-fixture-key", "Bearer route-fixture-key");
                    assertThat(bodies.get(1)).doesNotContain("\"tools\":[{");
                }
                assertThatThrownBy(() -> ((ContextSummarizer) gateway).summarize(new SummaryRequest(
                        SummaryTier.values()[0], "bounded input", 0, List.of("m"), List.of(), 128, 64, 256),
                        CancellationToken.none())).isInstanceOf(IllegalStateException.class);
                assertThat(bodies).hasSize(2);
            } finally {
                Arrays.fill(synthetic, '\0');
                server.stop(0);
            }
        }
    }

    private static String stream() {
        return """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
    }
}
