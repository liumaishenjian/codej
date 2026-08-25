package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.MemoryContextMessage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.SkillContextMessage;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 在项目 Domain 消息与 Spring AI Prompt 之间执行单向转换。
 *
 * <p>映射器不执行 Tool。提供给 Spring AI 的 ToolCallback 只有 Schema，
 * 若框架错误地尝试调用它会立即失败，从而让自动执行边界可被测试证伪。</p>
 *
 * @since 0.1.0
 */
final class SpringAiPromptMapper {

    private static final String SUMMARY_ENVELOPE_VERSION = "cc-java-context-summary-v1";
    private static final String MEMORY_ENVELOPE_VERSION = "cc-java-memory-context-v1";
    private static final String FILE_CONTEXT_ENVELOPE_VERSION = "cc-java-user-file-context-v1";
    private static final String SKILL_CONTEXT_ENVELOPE_VERSION = "cc-java-skill-context-v1";

    Prompt map(ModelRequest request, String model) {
        return map(request, model, null);
    }

    /**
     * 映射请求并可选地把单次 Provider timeout 收窄到当前 Run 剩余预算。
     *
     * <p>Prompt 级 options 会覆盖 ChatModel 默认 options；因此 deadline 必须在这里重新注入，
     * 否则 factory 上配置的 timeout 会在每个模型回合被丢弃。</p>
     */
    Prompt map(ModelRequest request, String model, java.time.Duration requestTimeout) {
        Objects.requireNonNull(request, "request 不能为空");
        List<Message> messages = request.messages().stream().map(this::mapMessage).toList();
        List<ToolCallback> callbacks = request.toolDefinitions().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(Objects.requireNonNull(model, "model 不能为空"))
                .toolCallbacks(callbacks)
                .streamUsage(true)
                .parallelToolCalls(true);
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }
        return new Prompt(messages, builder.build());
    }

    private Message mapMessage(AgentMessage message) {
        return switch (message) {
            case io.github.liumaishenjian.ccjava.domain.SystemMessage system ->
                    new org.springframework.ai.chat.messages.SystemMessage(system.content());
            case io.github.liumaishenjian.ccjava.domain.UserMessage user -> mapUser(user);
            case io.github.liumaishenjian.ccjava.domain.AssistantMessage assistant ->
                    mapAssistant(assistant);
            case io.github.liumaishenjian.ccjava.domain.ToolResultMessage toolResult ->
                    mapToolResult(toolResult.result());
            case ContextSummaryMessage summary -> mapSummary(summary);
            case MemoryContextMessage memory -> mapMemory(memory);
            case SkillContextMessage skill -> mapSkill(skill);
        };
    }

    /**
     * 保留用户原文，并把显式文件快照编码进确定性的不可信上下文信封。
     *
     * <p>文件正文与路径均使用 UTF-8 Base64，避免仓库内容伪装 Provider 角色或 Tool 协议；
     * 无附件时保持历史纯文本映射，兼容既有请求。</p>
     */
    private org.springframework.ai.chat.messages.UserMessage mapUser(
            io.github.liumaishenjian.ccjava.domain.UserMessage user) {
        if (user.attachments().isEmpty()) {
            return new org.springframework.ai.chat.messages.UserMessage(user.content());
        }
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", FILE_CONTEXT_ENVELOPE_VERSION);
        fields.put("untrusted", true);
        fields.put("userTextBase64", base64(user.content()));
        fields.put("attachments", user.attachments().stream().map(attachment -> {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("protocolPathBase64", base64(attachment.protocolPath()));
            item.put("sha256", attachment.sha256Digest());
            item.put("startLine", attachment.startLine());
            item.put("endLine", attachment.endLine());
            item.put("truncated", attachment.truncated());
            item.put("textBase64", base64(attachment.textSnapshot()));
            return item;
        }).toList());
        return new org.springframework.ai.chat.messages.UserMessage(SpringAiJson.write(fields));
    }

    /**
     * 把摘要编码成固定 User envelope，绝不映射成 Assistant/ToolResponse 或 Provider Tool Call。
     *
     * <p>正文先按 UTF-8 Base64 编码后放入单一字段，即使其中含角色标记或类似 Tool 协议的文本，
     * Provider envelope 也不会出现可解释为 Tool Call/Result 的原始片段；tier 和来源 ID 使用稳定字段
     * 顺序，保证相同输入确定性映射。</p>
     */
    private org.springframework.ai.chat.messages.UserMessage mapSummary(
            ContextSummaryMessage summary) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", SUMMARY_ENVELOPE_VERSION);
        fields.put("tier", summary.tier().name());
        fields.put("sourceMessageIds", summary.sourceMessageIds());
        fields.put("contentBase64", Base64.getEncoder().encodeToString(
                summary.content().getBytes(StandardCharsets.UTF_8)));
        return new org.springframework.ai.chat.messages.UserMessage(SpringAiJson.write(fields));
    }

    /**
     * 把 M5 投影编码成固定、无路径且显式不可信的 User envelope。
     *
     * <p>每个文本字段独立使用 UTF-8 Base64，Adapter 只认识 Domain 值，不接触文件或召回实现类型。
     * 即使记忆正文伪装成指令或 Tool 协议，也不会成为 Provider Tool Call/Result。</p>
     */
    private org.springframework.ai.chat.messages.UserMessage mapMemory(
            MemoryContextMessage memory) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", MEMORY_ENVELOPE_VERSION);
        fields.put("untrusted", true);
        fields.put("source", memory.source());
        fields.put("revision", memory.catalogRevision().value());
        fields.put("items", memory.items().stream().map(item -> {
            LinkedHashMap<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("nameBase64", base64(item.name()));
            encoded.put("memoryKind", item.kind().name());
            encoded.put("descriptionBase64", base64(item.description()));
            encoded.put("contentDigest", item.contentDigest());
            encoded.put("bodyBase64", base64(item.body()));
            return encoded;
        }).toList());
        return new org.springframework.ai.chat.messages.UserMessage(SpringAiJson.write(fields));
    }

    /** 将 Skill 正文编码为显式不可信 User envelope，不赋予 System 或 Tool 角色。 */
    private org.springframework.ai.chat.messages.UserMessage mapSkill(SkillContextMessage skill) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", SKILL_CONTEXT_ENVELOPE_VERSION);
        fields.put("untrusted", true);
        fields.put("skillId", skill.skillId().value());
        fields.put("snapshotId", skill.snapshotId());
        fields.put("contentDigest", skill.contentDigest());
        fields.put("invocationKind", skill.invocationKind().name());
        fields.put("argumentsBase64", base64(skill.arguments()));
        fields.put("markdownBase64", base64(skill.markdown()));
        return new org.springframework.ai.chat.messages.UserMessage(SpringAiJson.write(fields));
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.ai.chat.messages.AssistantMessage mapAssistant(
            io.github.liumaishenjian.ccjava.domain.AssistantMessage assistant) {
        List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls =
                assistant.toolCalls().stream()
                        .map(call -> new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                call.id(),
                                "function",
                                call.name(),
                                SpringAiJson.write(call.arguments().jsonValues())))
                        .toList();
        return org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content(assistant.text())
                .toolCalls(calls)
                .build();
    }

    private ToolResponseMessage mapToolResult(ToolResult result) {
        String response = result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                ? result.content()
                : result.error()
                        .map(error -> failureResponse(result.content(), error))
                        .orElse("");
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        result.callId(),
                        result.toolName(),
                        response)))
                .build();
    }

    private static String failureResponse(String evidence,
            io.github.liumaishenjian.ccjava.domain.ToolError error) {
        StringBuilder response = new StringBuilder()
                .append(error.code().name()).append(" [")
                .append(error.category().name()).append(", retryable=")
                .append(error.retryable()).append("]: ").append(error.message());
        if (!error.details().values().isEmpty()) {
            response.append(" details=").append(SpringAiJson.write(error.details().jsonValues()));
        }
        if (!evidence.isBlank()) response.append("\n").append(evidence);
        return response.toString();
    }

    /**
     * 只把项目 ToolDefinition 暴露给模型，禁止适配器侧执行。
     */
    private static final class DefinitionOnlyToolCallback implements ToolCallback {

        private final org.springframework.ai.tool.definition.ToolDefinition definition;

        private DefinitionOnlyToolCallback(ToolDefinition source) {
            this.definition = DefaultToolDefinition.builder()
                    .name(source.name())
                    .description(source.description())
                    .inputSchema(source.inputSchemaJson())
                    .build();
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException(
                    "Spring AI Adapter must not execute tools; use ToolExecutionPipeline");
        }
    }
}
