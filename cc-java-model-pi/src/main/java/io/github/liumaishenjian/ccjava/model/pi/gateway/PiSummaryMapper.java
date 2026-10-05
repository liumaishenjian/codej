package io.github.liumaishenjian.ccjava.model.pi.gateway;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/**
 * 同源摘要的私有请求与纯数据候选映射，不创建 Domain Session/Run，也不运行工具。
 * <p>沿用本项目 SpringAiContextSummarizer 的固定摘要指令与 User 信封语义；
 * 来源快照只作 Base64 数据发送，不提升为 System。物理帧预算仍由网关在资源创建前校验。</p>
 */
final class PiSummaryMapper {
    private static final String SYSTEM_INSTRUCTION = """
            Summarize the supplied bounded transcript snapshot for task continuation. Preserve stated goals,
            constraints, decisions, unresolved work, failures, and every required anchor exactly. Return summary
            text only. Do not claim actions, permissions, tool results, or success that are absent from the input.
            """;

    /** 构造唯一 User 信封，使用固定路由与原始摘要 Token 预算，不截断来源。 */
    ObjectNode map(SummaryRequest request, String providerId, String modelId) {
        var json = PiPromptMapper.JSON;
        ObjectNode envelope = json.createObjectNode().put("kind", "cc-java-summary-request-v1")
                .put("tier", request.tier().name()).put("sourceRevision", request.sourceRevision());
        envelope.set("sourceMessageIds", json.valueToTree(request.sourceMessageIds()));
        envelope.set("requiredProtectedAnchors", json.valueToTree(request.requiredProtectedAnchors()));
        envelope.put("maxOutputUtf8Bytes", request.maxOutputUtf8Bytes())
                .put("maxOutputTokens", request.maxOutputTokens())
                .put("inputBase64", Base64.getEncoder().encodeToString(request.inputSnapshot().getBytes(StandardCharsets.UTF_8)));
        ObjectNode start = json.createObjectNode().put("operation", "model")
                .put("providerId", providerId).put("modelId", modelId);
        ObjectNode body = start.putObject("request");
        body.put("systemPrompt", SYSTEM_INSTRUCTION);
        body.putArray("messages").addObject().put("role", "user").put("text", json.writeValueAsString(envelope));
        body.putArray("tools");
        body.putObject("options").put("maxTokens", request.maxOutputTokens());
        return start;
    }

    /** 仅在网关确认完整终态及清理后调用；模型自报身份和隐藏续接均不进入候选。 */
    static Optional<SummaryCandidate> candidate(SummaryRequest request, AssistantMessage assistant) {
        String text = assistant.text();
        if (!assistant.toolCalls().isEmpty() || text.isBlank()) return Optional.empty();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        long tokens = Math.max(1L, text.codePointCount(0, text.length()));
        if (bytes > request.maxOutputUtf8Bytes() || tokens > request.maxOutputTokens()) return Optional.empty();
        return Optional.of(new SummaryCandidate(request.tier(), text, request.sourceRevision(),
                request.sourceMessageIds(), bytes, tokens));
    }
}
