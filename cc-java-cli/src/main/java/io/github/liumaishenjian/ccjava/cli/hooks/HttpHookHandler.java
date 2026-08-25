package io.github.liumaishenjian.ccjava.cli.hooks;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.hook.HookHandler;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 只允许 loopback 目标的 S09 HTTP Hook Adapter。
 *
 * <p>当前阶段不允许远程 Hook、重定向、代理认证或自定义 Header。每次请求前重新解析
 * host 并要求所有地址都是 loopback，以抵抗 DNS 重绑定；响应正文、墙钟和取消均有硬边界。
 * 这不是通用网络访问能力，远程 HTTP Hook 留到 S13。</p>
 *
 * @since 0.9.0
 */
public final class HttpHookHandler implements HookHandler {
    private static final int MAX_BODY_BYTES = 64 * 1_024;
    private static final Set<String> OUTPUT_FIELDS = Set.of("disposition", "reason", "additionalContext");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String handlerId;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;

    /**
     * 创建不跟随重定向的 loopback HTTP Handler。
     *
     * @param handlerId 稳定 Hook ID
     * @param endpoint 每次调用都重新校验解析结果的 loopback URI
     * @param timeout 连接和请求的墙钟上限
     */
    public HttpHookHandler(String handlerId, URI endpoint, Duration timeout) {
        this(handlerId, endpoint, timeout, HttpClient.newBuilder()
                .connectTimeout(validateTimeout(timeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    HttpHookHandler(String handlerId, URI endpoint, Duration timeout, HttpClient client) {
        this.handlerId = requireText(handlerId, "handlerId");
        this.endpoint = validateEndpoint(endpoint);
        this.timeout = validateTimeout(timeout);
        this.client = Objects.requireNonNull(client, "client 不能为空");
    }

    @Override
    public String id() {
        return handlerId;
    }

    @Override
    public HookExecutionResult execute(HookInvocation invocation, CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) {
            return failure(HookExecutionStatus.CANCELLED, "Hook 调用已取消");
        }
        try {
            requireLoopbackResolution(endpoint);
            byte[] requestBody = encode(invocation);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            try (CancellationToken.Registration registration = cancellationToken.onCancellation(
                    () -> future.cancel(true))) {
                HttpResponse<InputStream> response;
                try {
                    response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException failure) {
                    future.cancel(true);
                    throw failure;
                } catch (InterruptedException failure) {
                    future.cancel(true);
                    throw failure;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    close(response.body());
                    return failure(HookExecutionStatus.FAILED, "Hook HTTP 返回非成功状态");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                    close(response.body());
                    return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook HTTP Content-Type 无效");
                }
                byte[] responseBody = readBounded(response.body());
                return decode(responseBody);
            }
        } catch (java.util.concurrent.TimeoutException failure) {
            return failure(HookExecutionStatus.TIMED_OUT, "Hook 超过时间上限");
        } catch (java.util.concurrent.CancellationException failure) {
            return failure(HookExecutionStatus.CANCELLED, "Hook 调用已取消");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure(HookExecutionStatus.CANCELLED, "Hook 调用被中断");
        } catch (Exception failure) {
            return failure(HookExecutionStatus.FAILED, "Hook HTTP 调用失败");
        }
    }

    private byte[] encode(HookInvocation invocation) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", invocation.event().name());
        root.put("sessionId", invocation.sessionId().value());
        invocation.runId().ifPresent(run -> root.put("runId", run.value()));
        root.put("subject", invocation.subject());
        root.set("data", JSON.valueToTree(invocation.data().jsonValues()));
        return JSON.writeValueAsBytes(root);
    }

    private HookExecutionResult decode(byte[] bytes) {
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook HTTP body 必须是 JSON Object");
            }
            ObjectNode object = (ObjectNode) node;
            var fields = object.properties().stream().map(entry -> entry.getKey()).toList();
            if (!OUTPUT_FIELDS.containsAll(fields) || !fields.contains("disposition")) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook HTTP 字段不符合协议");
            }
            JsonNode disposition = object.get("disposition");
            if (disposition == null || !disposition.isTextual()) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook HTTP disposition 无效");
            }
            HookDisposition decision = HookDisposition.valueOf(disposition.asText());
            Optional<String> reason = optionalText(object, "reason");
            Optional<String> context = optionalText(object, "additionalContext");
            return new HookExecutionResult(handlerId, decision, HookExecutionStatus.COMPLETED, reason, context);
        } catch (Exception failure) {
            return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook HTTP body 不是合法协议");
        }
    }

    private static Optional<String> optionalText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isTextual() || value.asText().codePointCount(0, value.asText().length())
                > HookExecutionResult.MAX_TEXT_CHARACTERS) {
            throw new IllegalArgumentException("Hook HTTP 摘要字段无效");
        }
        return Optional.of(value.asText());
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("Hook HTTP body 超过上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private HookExecutionResult failure(HookExecutionStatus status, String reason) {
        return new HookExecutionResult(handlerId, HookDisposition.CONTINUE, status,
                Optional.of(reason), Optional.empty());
    }

    private static URI validateEndpoint(URI value) {
        URI endpoint = Objects.requireNonNull(value, "endpoint 不能为空").normalize();
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("HTTP Hook 只接受 loopback http URI");
        }
        requireLoopbackResolution(endpoint);
        return endpoint;
    }

    private static void requireLoopbackResolution(URI endpoint) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(endpoint.getHost());
            if (addresses.length == 0 || java.util.Arrays.stream(addresses).anyMatch(address -> !address.isLoopbackAddress())) {
                throw new IllegalArgumentException("HTTP Hook host 不是 loopback");
            }
        } catch (java.net.UnknownHostException failure) {
            throw new IllegalArgumentException("HTTP Hook host 无法解析");
        }
    }

    private static Duration validateTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout 不能为空");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("timeout 必须在 1ms 到 30s 之间");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 256) {
            throw new IllegalArgumentException(field + " 长度无效");
        }
        return value;
    }

    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
