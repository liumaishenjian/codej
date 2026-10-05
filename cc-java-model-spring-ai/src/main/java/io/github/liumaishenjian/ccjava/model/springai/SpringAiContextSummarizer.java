package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ContextSummarizer;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用 Spring AI 执行无 Tool、无 AgentRuntime 递归的有界 Context 摘要请求。
 *
 * <p>Adapter 为每次调用创建固定 System/User envelope，显式把 Tool Definitions 设为空，并只返回携带
 * 原请求 tier、revision 与 source IDs 的纯数据候选。已完成但非法的响应和取消 fail closed 为空；Provider
 * 或 Adapter 执行失败抛出隐私安全的受控异常，使 Core 能区分 SUMMARIZER_FAILED 与 EMPTY_CANDIDATE。
 * 候选仍必须经过 Core Adoption Gate。</p>
 *
 * @since 0.7.0
 */
public final class SpringAiContextSummarizer implements ContextSummarizer {

    private static final String SYSTEM_INSTRUCTION = """
            Summarize the supplied bounded transcript snapshot for task continuation. Preserve stated goals,
            constraints, decisions, unresolved work, failures, and every required anchor exactly. Return summary
            text only. Do not claim actions, permissions, tool results, or success that are absent from the input.
            """;
    private static final String ENVELOPE_VERSION = "cc-java-summary-request-v1";

    private final ChatModel chatModel;
    private final String model;

    /**
     * 创建直接调用模型的摘要 Adapter。
     *
     * @param chatModel Spring AI ChatModel
     * @param model 每次请求显式指定的模型名
     */
    public SpringAiContextSummarizer(ChatModel chatModel, String model) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不能为空");
        this.model = requireText(model, "model");
    }

    @Override
    public Optional<SummaryCandidate> summarize(
            SummaryRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) {
            return Optional.empty();
        }

        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Void> terminal = new CompletableFuture<>();
        try (CancellationToken.Registration ignored = cancellationToken.onCancellation(() -> {
            cancelled.set(true);
            Disposable disposable = subscription.get();
            if (disposable != null) {
                disposable.dispose();
            }
            terminal.completeExceptionally(new SummaryCancelledException());
        })) {
            var responses = new MessageAggregator().aggregate(
                    chatModel.stream(prompt(request)),
                    aggregate::set);
            Disposable disposable = responses.subscribe(
                    ignoredResponse -> { },
                    terminal::completeExceptionally,
                    () -> terminal.complete(null));
            subscription.set(disposable);
            if (cancelled.get()) {
                disposable.dispose();
            }
            terminal.join();
            if (cancelled.get() || cancellationToken.isCancellationRequested()) {
                return Optional.empty();
            }
            return mapCandidate(request, aggregate.get());
        } catch (RuntimeException failure) {
            if (cancelled.get()
                    || cancellationToken.isCancellationRequested()
                    || causedByCancellation(failure)) {
                return Optional.empty();
            }
            throw new SummaryExecutionException();
        }
    }

    private Prompt prompt(SummaryRequest request) {
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", ENVELOPE_VERSION);
        envelope.put("tier", request.tier().name());
        envelope.put("sourceRevision", request.sourceRevision());
        envelope.put("sourceMessageIds", request.sourceMessageIds());
        envelope.put("requiredProtectedAnchors", request.requiredProtectedAnchors());
        envelope.put("maxOutputUtf8Bytes", request.maxOutputUtf8Bytes());
        envelope.put("maxOutputTokens", request.maxOutputTokens());
        envelope.put("inputBase64", Base64.getEncoder().encodeToString(
                request.inputSnapshot().getBytes(StandardCharsets.UTF_8)));
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .toolCallbacks(List.of())
                .parallelToolCalls(false)
                .maxCompletionTokens(Math.toIntExact(Math.min(
                        request.maxOutputTokens(), Integer.MAX_VALUE)))
                .build();
        return new Prompt(
                List.of(
                        new SystemMessage(SYSTEM_INSTRUCTION),
                        new UserMessage(SpringAiJson.write(envelope))),
                options);
    }

    private Optional<SummaryCandidate> mapCandidate(
            SummaryRequest request,
            ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return Optional.empty();
        }
        Generation generation = response.getResult();
        String finish = generation.getMetadata().getFinishReason();
        // 同源Anthropic摘要使用end_turn表示正常完成；不把长度截断或工具终态当作可用摘要。
        boolean completed = "stop".equalsIgnoreCase(finish) || "end_turn".equalsIgnoreCase(finish);
        if (generation.getOutput() == null
                || !generation.getOutput().getToolCalls().isEmpty()
                || !completed) {
            return Optional.empty();
        }
        String text = generation.getOutput().getText();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        long estimatedTokens = Math.max(1L, text.codePointCount(0, text.length()));
        if (utf8Bytes > request.maxOutputUtf8Bytes()
                || estimatedTokens > request.maxOutputTokens()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SummaryCandidate(
                    request.tier(),
                    text,
                    request.sourceRevision(),
                    request.sourceMessageIds(),
                    utf8Bytes,
                    estimatedTokens));
        } catch (IllegalArgumentException invalidCandidate) {
            return Optional.empty();
        }
    }

    private static boolean causedByCancellation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SummaryCancelledException) {
                return true;
            }
        }
        return false;
    }

    private static String requireText(String value, String fieldName) {
        String checked = Objects.requireNonNull(value, fieldName + " 不能为空");
        if (checked.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return checked;
    }

    private static final class SummaryCancelledException extends RuntimeException {
    }

    /** 不携带底层 cause 或消息，避免 Provider 正文经异常链泄漏。 */
    private static final class SummaryExecutionException extends RuntimeException {
        private SummaryExecutionException() {
            super("Context summary model request failed");
        }
    }
}
