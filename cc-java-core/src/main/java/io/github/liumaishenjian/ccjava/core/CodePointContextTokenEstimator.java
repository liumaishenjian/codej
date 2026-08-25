package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextEstimateKind;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.MemoryContextMessage;
import io.github.liumaishenjian.ccjava.domain.JsonNull;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionItem;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以 Unicode Code Point 数量作为确定性 Token 代理的离线 Estimator。
 *
 * <p>该实现用于 G3-A 的可证伪 Planner 与 Fake 测试，不声称等于任何 Provider tokenizer。
 * 它对文本、Tool 名称、Call ID 和结构化参数递归计数，使同一输入始终产生相同 Usage。</p>
 *
 * @since 0.7.0
 */
public final class CodePointContextTokenEstimator implements ContextTokenEstimator {

    /** 创建无状态 Estimator。 */
    public CodePointContextTokenEstimator() {
    }

    @Override
    public ContextUsage estimate(List<AgentMessage> messages, ContextCapacity capacity) {
        Objects.requireNonNull(messages, "messages 不能为空");
        Objects.requireNonNull(capacity, "capacity 不能为空");
        long system = 0;
        long transcript = 0;
        long tool = 0;
        long memory = 0;
        for (AgentMessage message : messages) {
            Objects.requireNonNull(message, "消息元素不能为空");
            if (message instanceof SystemMessage systemMessage) {
                system = Math.addExact(system, textSize(systemMessage.content()));
            } else if (message instanceof UserMessage userMessage) {
                transcript = Math.addExact(transcript, userMessageSize(userMessage));
            } else if (message instanceof AssistantMessage assistant) {
                transcript = Math.addExact(transcript, textSize(assistant.text()));
                for (ToolCall call : assistant.toolCalls()) {
                    tool = Math.addExact(tool, toolCallSize(call));
                }
            } else if (message instanceof ToolResultMessage toolResult) {
                tool = Math.addExact(tool, toolResultSize(toolResult));
            } else if (message instanceof ContextSummaryMessage summary) {
                transcript = Math.addExact(transcript, textSize(summary.content()));
            } else if (message instanceof MemoryContextMessage memoryContext) {
                memory = Math.addExact(memory, memorySize(memoryContext));
            } else {
                throw new IllegalArgumentException("不支持的 Context 消息类型: " + message.getClass());
            }
        }
        long total = Math.addExact(
                system,
                Math.addExact(Math.addExact(transcript, tool), memory));
        return new ContextUsage(
                system,
                0,
                transcript,
                tool,
                memory,
                total,
                capacity.availableInputTokens() - total,
                ContextEstimateKind.ESTIMATED);
    }

    private long memorySize(MemoryContextMessage message) {
        long size = Math.addExact(
                textSize(message.source()),
                textSize(message.catalogRevision().value()));
        for (MemoryProjectionItem item : message.items()) {
            size = Math.addExact(size, textSize(item.name()));
            size = Math.addExact(size, textSize(item.kind().name()));
            size = Math.addExact(size, textSize(item.description()));
            size = Math.addExact(size, textSize(item.body()));
            size = Math.addExact(size, textSize(item.contentDigest()));
        }
        return size;
    }

    /**
     * 对附件消息按生产 Adapter 的 Base64 信封做保守估算，避免大附件在压缩阈值前被低估。
     * 固定开销覆盖 JSON key、标点、行号与布尔值；无附件消息保持既有纯文本估算。
     */
    private long userMessageSize(UserMessage message) {
        if (message.attachments().isEmpty()) {
            return textSize(message.content());
        }
        long size = Math.addExact(160, base64Size(message.content()));
        for (var attachment : message.attachments()) {
            size = Math.addExact(size, 160);
            size = Math.addExact(size, base64Size(attachment.protocolPath()));
            size = Math.addExact(size, textSize(attachment.sha256Digest()));
            size = Math.addExact(size, base64Size(attachment.textSnapshot()));
            size = Math.addExact(size, textSize(Integer.toString(attachment.startLine())));
            size = Math.addExact(size, textSize(Integer.toString(attachment.endLine())));
        }
        return size;
    }

    private long base64Size(String text) {
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.multiplyExact(4, Math.floorDiv(Math.addExact(bytes, 2), 3));
    }

    private long toolCallSize(ToolCall call) {
        return Math.addExact(
                Math.addExact(textSize(call.id()), textSize(call.name())),
                jsonSize(call.arguments().values()));
    }

    private long toolResultSize(ToolResultMessage message) {
        var result = message.result();
        long size = Math.addExact(textSize(result.callId()), textSize(result.toolName()));
        size = Math.addExact(size, textSize(result.status().name()));
        size = Math.addExact(size, textSize(result.content()));
        if (result.error().isPresent()) {
            size = Math.addExact(size, textSize(result.error().get().code().name()));
            size = Math.addExact(size, textSize(result.error().get().message()));
        }
        size = Math.addExact(size, textSize(result.metadata().truncationReason().name()));
        return size;
    }

    private long jsonSize(Map<String, Object> values) {
        long size = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            size = Math.addExact(size, textSize(entry.getKey()));
            size = Math.addExact(size, valueSize(entry.getValue()));
        }
        return size;
    }

    @SuppressWarnings("unchecked")
    private long valueSize(Object value) {
        if (value instanceof String text) {
            return textSize(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return textSize(value.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return jsonSize((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            long size = 0;
            for (Object item : list) {
                size = Math.addExact(size, valueSize(item));
            }
            return size;
        }
        if (value == null || value == JsonNull.INSTANCE) {
            return 1;
        }
        throw new IllegalArgumentException("JsonObject 包含不支持的值类型: " + value.getClass());
    }

    private long textSize(String text) {
        return text.codePointCount(0, text.length());
    }
}
