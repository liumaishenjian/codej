package io.github.liumaishenjian.ccjava.model.pi.gateway;

import io.github.liumaishenjian.ccjava.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 项目消息到私有 Pi 请求的独立映射，不依赖 Spring AI，也不执行工具。
 * <p>沿用项目 cc-java-*-v1 Base64 User 信封；只有显式 SystemMessage 可进入 systemPrompt。
 * 隐藏续接只放入 assistant.continuation，绝不混入正文或元数据。</p>
 */
final class PiPromptMapper {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(1_048_576).maxDocumentLength(1_048_576).build()).build()).build();

    ObjectNode map(ModelRequest request, String providerId, String modelId) {
        ObjectNode start = JSON.createObjectNode().put("operation", "model")
                .put("providerId", providerId).put("modelId", modelId);
        ObjectNode body = start.putObject("request");
        body.put("systemPrompt", String.join("\n\n", request.messages().stream()
                .filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .map(SystemMessage::content).toList()));
        var messages = body.putArray("messages");
        for (AgentMessage message : request.messages()) {
            if (!(message instanceof SystemMessage)) messages.add(mapMessage(message));
        }
        var tools = body.putArray("tools");
        for (ToolDefinition definition : request.toolDefinitions()) {
            JsonNode schema;
            try (var parser = JSON.createParser(definition.inputSchemaJson())) {
                schema = JSON.readTree(parser);
                if (schema == null || !schema.isObject() || parser.nextToken() != null)
                    throw new IllegalArgumentException("PI_SCHEMA_INVALID");
            }
            tools.addObject().put("name", definition.name()).put("description", definition.description())
                    .set("parameters", schema);
        }
        body.putObject("options");
        return start;
    }

    private ObjectNode mapMessage(AgentMessage message) {
        return switch (message) {
            case UserMessage user -> user.attachments().isEmpty() ? user(user.content()) : attachments(user);
            case AssistantMessage assistant -> assistant(assistant);
            case ToolResultMessage result -> tool(result.result());
            case ContextSummaryMessage summary -> summary(summary);
            case MemoryContextMessage memory -> memory(memory);
            case SkillContextMessage skill -> skill(skill);
            case SystemMessage ignored -> throw new IllegalArgumentException("PI_SYSTEM_MAPPING_INVALID");
        };
    }

    private ObjectNode user(String text) { return JSON.createObjectNode().put("role", "user").put("text", text); }
    private ObjectNode envelope(ObjectNode value) { return user(JSON.writeValueAsString(value)); }
    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode attachments(UserMessage user) {
        ObjectNode value = JSON.createObjectNode().put("kind", "cc-java-user-file-context-v1")
                .put("untrusted", true).put("userTextBase64", base64(user.content()));
        var items = value.putArray("attachments");
        for (var item : user.attachments()) items.addObject()
                .put("protocolPathBase64", base64(item.protocolPath())).put("sha256", item.sha256Digest())
                .put("startLine", item.startLine()).put("endLine", item.endLine())
                .put("truncated", item.truncated()).put("textBase64", base64(item.textSnapshot()));
        return envelope(value);
    }

    private ObjectNode summary(ContextSummaryMessage summary) {
        ObjectNode value = JSON.createObjectNode().put("kind", "cc-java-context-summary-v1")
                .put("tier", summary.tier().name());
        value.set("sourceMessageIds", JSON.valueToTree(summary.sourceMessageIds()));
        value.put("contentBase64", base64(summary.content()));
        return envelope(value);
    }

    private ObjectNode memory(MemoryContextMessage memory) {
        ObjectNode value = JSON.createObjectNode().put("kind", "cc-java-memory-context-v1")
                .put("untrusted", true).put("source", memory.source()).put("revision", memory.catalogRevision().value());
        var items = value.putArray("items");
        for (var item : memory.items()) items.addObject().put("nameBase64", base64(item.name()))
                .put("memoryKind", item.kind().name()).put("descriptionBase64", base64(item.description()))
                .put("contentDigest", item.contentDigest()).put("bodyBase64", base64(item.body()));
        return envelope(value);
    }

    private ObjectNode skill(SkillContextMessage skill) {
        return envelope(JSON.createObjectNode().put("kind", "cc-java-skill-context-v1").put("untrusted", true)
                .put("skillId", skill.skillId().value()).put("snapshotId", skill.snapshotId())
                .put("contentDigest", skill.contentDigest()).put("invocationKind", skill.invocationKind().name())
                .put("argumentsBase64", base64(skill.arguments())).put("markdownBase64", base64(skill.markdown())));
    }

    private ObjectNode assistant(AssistantMessage assistant) {
        ObjectNode value = JSON.createObjectNode().put("role", "assistant").put("text", assistant.text());
        value.set("toolCalls", calls(assistant.toolCalls()));
        assistant.continuation().ifPresent(c -> value.putObject("continuation").put("backend", c.backend())
                .put("providerId", c.providerId()).put("modelId", c.modelId()).put("payload", c.payload()));
        return value;
    }

    static JsonNode calls(List<ToolCall> calls) {
        var values = JSON.createArrayNode();
        for (ToolCall call : calls) values.addObject().put("id", call.id()).put("name", call.name())
                .set("arguments", JSON.valueToTree(call.arguments().jsonValues()));
        return values;
    }

    private ObjectNode tool(ToolResult result) {
        String text = result.status() == ToolResultStatus.SUCCESS ? result.content()
                : result.error().map(error -> failure(result.content(), error)).orElse("");
        return JSON.createObjectNode().put("role", "toolResult").put("toolCallId", result.callId())
                .put("toolName", result.toolName()).put("text", text).put("isError", result.status() != ToolResultStatus.SUCCESS);
    }

    private String failure(String evidence, ToolError error) {
        String result = error.code().name() + " [" + error.category().name() + ", retryable="
                + error.retryable() + "]: " + error.message();
        if (!error.details().values().isEmpty()) result += " details=" + JSON.writeValueAsString(error.details().jsonValues());
        if (!evidence.isBlank()) result += "\n" + evidence;
        return result;
    }
}
