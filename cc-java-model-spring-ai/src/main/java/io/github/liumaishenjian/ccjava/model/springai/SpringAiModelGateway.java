package io.github.liumaishenjian.ccjava.model.springai;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelDiagnosticRecorder;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureStage;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.Disposable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CANCELLED;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CONTEXT_OVERFLOW;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.INCOMPLETE_STREAM;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.PERMANENT;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRYABLE;

/**
 * 使用 Spring AI 2.0 执行一个 OpenAI-compatible 流式模型回合。
 *
 * <p>该 Adapter 只转换消息、发布文本增量并聚合最终 Tool Call；它不执行 Tool、
 * 不追加 Session 历史，也不驱动后续 Agent Loop。Reactor 与 Spring AI 类型不会
 * 穿过本类进入 Domain/Core。</p>
 *
 * @since 0.1.0
 */
public final class SpringAiModelGateway implements StreamingModelGateway {

    private final ChatModel chatModel;
    private final String model;
    private final SpringAiPromptMapper promptMapper;
    private final ModelDiagnosticRecorder diagnostics;

    /**
     * 创建真实或测试用 Spring AI Adapter。
     *
     * @param chatModel Spring AI ChatModel；生产使用 OpenAiChatModel
     * @param model 每次请求显式指定的模型名
     */
    public SpringAiModelGateway(ChatModel chatModel, String model) {
        this(chatModel, model, ModelDiagnosticRecorder.off());
    }

    /**
     * 创建带独立本机模型诊断出口的 Adapter。
     *
     * @param chatModel Spring AI ChatModel
     * @param model 每次请求显式指定的模型名
     * @param diagnostics 不进入请求、Session 或用户事件的诊断记录器
     */
    public SpringAiModelGateway(
            ChatModel chatModel,
            String model,
            ModelDiagnosticRecorder diagnostics) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不能为空");
        this.model = requireText(model, "model");
        this.promptMapper = new SpringAiPromptMapper();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics 不能为空");
    }

    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelStreamObserver observer,
            CancellationToken cancellation) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(observer, "observer 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        if (cancellation.isCancellationRequested()) {
            throw new ModelGatewayException(CANCELLED, "Model request cancelled");
        }

        long startedNanos = diagnostics.startNanos();
        AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean receivedResponse = new AtomicBoolean();
        AtomicBoolean receivedProviderOutput = new AtomicBoolean();
        AtomicBoolean emittedUserText = new AtomicBoolean();
        CompletableFuture<Void> terminal = new CompletableFuture<>();

        java.time.Duration remaining = remainingRequestTimeout(cancellation);
        try (CancellationToken.Registration ignored = cancellation.onCancellation(() -> {
            cancelled.set(true);
            Disposable disposable = subscription.get();
            if (disposable != null) {
                disposable.dispose();
            }
            terminal.completeExceptionally(new ModelCancelledException());
        })) {
            var rawResponses = chatModel.stream(promptMapper.map(request, model, remaining))
                    .doOnNext(response -> {
                        receivedResponse.set(true);
                        if (hasProviderOutput(response)) {
                            receivedProviderOutput.set(true);
                        }
                    });
            var responses = new MessageAggregator().aggregate(
                    rawResponses,
                    aggregate::set);
            Disposable disposable = responses.subscribe(
                    response -> publishDelta(response, observer, emittedUserText),
                    terminal::completeExceptionally,
                    () -> terminal.complete(null));
            subscription.set(disposable);
            if (cancelled.get()) {
                disposable.dispose();
            }
            terminal.join();
        } catch (CompletionException exception) {
            if (cancelled.get() || exception.getCause() instanceof ModelCancelledException) {
                throw new ModelGatewayException(CANCELLED, "Model request cancelled");
            }
            Throwable cause = unwrap(exception);
            FailureClassification classification = classify(cause);
            if (receivedResponse.get()) {
                // 任意 Provider frame 都是重放 fence；timeout 也不能在该边界后重新发起请求。
                classification = incompleteStream(classification.reason());
            }
            recordFailure(
                    request,
                    receivedResponse.get()
                            ? ModelFailureStage.STREAM_TRANSPORT
                            : ModelFailureStage.REQUEST_TRANSPORT,
                    classification.reason(),
                    classification.statusClass(),
                    receivedResponse.get(),
                    emittedUserText.get(),
                    startedNanos);
            String safeMessage = classification.kind() == INCOMPLETE_STREAM
                    ? "Model stream ended before a complete response"
                    : "Model request failed";
            if (classification.retryAfter().isPresent()) {
                throw new ModelGatewayException(
                        classification.kind(),
                        safeMessage,
                        classification.summary(),
                        classification.retryAfter().orElseThrow(),
                        cause);
            }
            throw new ModelGatewayException(
                    classification.kind(),
                    safeMessage,
                    classification.summary(),
                    cause);
        } catch (RuntimeException failure) {
            if (cancelled.get() || cancellation.isCancellationRequested()) {
                throw new ModelGatewayException(CANCELLED, "Model request cancelled");
            }
            throw unexpectedProviderFailure(
                    request,
                    receivedResponse.get()
                            ? ModelFailureStage.STREAM_TRANSPORT
                            : ModelFailureStage.REQUEST_TRANSPORT,
                    receivedResponse.get(),
                    emittedUserText.get(),
                    startedNanos,
                    failure);
        }

        ChatResponse response = aggregate.get();
        if (response == null || response.getResult() == null) {
            recordFailure(
                    request,
                    ModelFailureStage.RESPONSE_DECODE,
                    ModelFailureReason.INVALID_RESPONSE,
                    ModelDiagnosticStatusClass.NONE,
                    receivedResponse.get(),
                    emittedUserText.get(),
                    startedNanos);
            throw invalidResponse(
                    "Provider returned an incomplete model stream",
                    receivedResponse.get());
        }
        try {
            return mapTurn(
                    request,
                    response,
                    receivedResponse.get(),
                    receivedProviderOutput.get(),
                    emittedUserText.get(),
                    startedNanos);
        } catch (RuntimeException failure) {
            throw unexpectedProviderFailure(
                    request,
                    ModelFailureStage.RESPONSE_DECODE,
                    receivedResponse.get(),
                    emittedUserText.get(),
                    startedNanos,
                    failure);
        }
    }

    /**
     * 把 Provider 单请求上限绑定到当前 Run 剩余预算。
     *
     * <p>未绑定 deadline 的测试/兼容调用返回 {@code null}，沿用 ChatModel factory 的默认 timeout；
     * 已到期时先拒绝调用，避免用零值触发 SDK 的“无限”或非法配置语义。</p>
     */
    private static java.time.Duration remainingRequestTimeout(CancellationToken cancellation)
            throws ModelGatewayException {
        Optional<java.time.Duration> remaining = cancellation.remainingTime();
        if (remaining.isEmpty()) {
            return null;
        }
        java.time.Duration value = remaining.orElseThrow();
        if (value.isZero() || value.isNegative()) {
            throw new ModelGatewayException(CANCELLED, "Model request deadline reached");
        }
        return value;
    }

    private static boolean hasProviderOutput(ChatResponse response) {
        if (response == null) {
            return false;
        }
        Generation result = response.getResult();
        if (result == null || result.getOutput() == null) {
            return false;
        }
        var output = result.getOutput();
        String text = output.getText();
        return (text != null && !text.isEmpty()) || !output.getToolCalls().isEmpty();
    }

    private static boolean isCleanEmptyStop(Generation result) {
        if (result == null || result.getOutput() == null) {
            return false;
        }
        var output = result.getOutput();
        String text = output.getText();
        return (text == null || text.isEmpty())
                && output.getToolCalls().isEmpty()
                && mapFinishReason(result.getMetadata().getFinishReason()) == ModelFinishReason.STOP;
    }

    private static void publishDelta(
            ChatResponse response,
            ModelStreamObserver observer,
            AtomicBoolean emittedUserText) {
        if (!hasProviderOutput(response)) {
            return;
        }
        Generation result = response.getResult();
        String text = result.getOutput().getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            observer.onTextDelta(text);
            emittedUserText.set(true);
        } catch (RuntimeException ignored) {
            // 观察者故障不能改变模型回合。
        }
    }

    private ModelTurn mapTurn(
            ModelRequest request,
            ChatResponse response,
            boolean receivedProviderFrame,
            boolean receivedProviderOutput,
            boolean emittedUserText,
            long startedNanos) throws ModelGatewayException {
        Generation result = response.getResult();
        org.springframework.ai.chat.messages.AssistantMessage output = result.getOutput();
        if (!receivedProviderOutput && !isCleanEmptyStop(result)) {
            recordFailure(
                    request,
                    ModelFailureStage.RESPONSE_DECODE,
                    ModelFailureReason.INVALID_RESPONSE,
                    ModelDiagnosticStatusClass.NONE,
                    receivedProviderFrame,
                    emittedUserText,
                    startedNanos);
            throw invalidResponse(
                    "Provider returned a model response without output",
                    receivedProviderFrame);
        }
        if (output == null) {
            recordFailure(
                    request,
                    ModelFailureStage.RESPONSE_DECODE,
                    ModelFailureReason.INVALID_RESPONSE,
                    ModelDiagnosticStatusClass.NONE,
                    receivedProviderFrame,
                    emittedUserText,
                    startedNanos);
            throw invalidResponse(
                    "Provider returned a model response without output",
                    receivedProviderFrame);
        }
        List<ToolCall> calls;
        try {
            calls = output.getToolCalls().stream()
                    .map(SpringAiModelGateway::mapToolCall)
                    .toList();
        } catch (ToolArgumentsMappingException exception) {
            recordFailure(
                    request,
                    ModelFailureStage.TOOL_ARGUMENTS,
                    ModelFailureReason.TOOL_JSON_INVALID,
                    ModelDiagnosticStatusClass.NONE,
                    receivedProviderFrame,
                    emittedUserText,
                    startedNanos);
            throw (ModelGatewayException) exception.getCause();
        }
        AssistantMessage assistant = new AssistantMessage(
                Objects.requireNonNullElse(output.getText(), ""),
                calls);

        Usage springUsage = response.getMetadata().getUsage();
        Optional<ModelUsage> usage = mapUsage(springUsage);
        String providerModel = response.getMetadata().getModel();
        ModelTurnMetadata metadata = new ModelTurnMetadata(
                mapFinishReason(result.getMetadata().getFinishReason()),
                usage,
                Optional.ofNullable(providerModel));
        validateCompleteTurn(
                request,
                assistant,
                metadata.finishReason(),
                receivedProviderFrame,
                emittedUserText,
                startedNanos);
        return new ModelTurn(assistant, metadata);
    }

    private void validateCompleteTurn(
            ModelRequest request,
            AssistantMessage assistant,
            ModelFinishReason finishReason,
            boolean receivedProviderFrame,
            boolean emittedUserText,
            long startedNanos) throws ModelGatewayException {
        if (finishReason == ModelFinishReason.UNKNOWN
                || finishReason == ModelFinishReason.OTHER) {
            recordFailure(
                    request,
                    ModelFailureStage.FINISH_METADATA,
                    ModelFailureReason.FINISH_MISSING,
                    ModelDiagnosticStatusClass.NONE,
                    receivedProviderFrame,
                    emittedUserText,
                    startedNanos);
            throw invalidResponse(
                    "Provider stream ended without a supported finish reason",
                    receivedProviderFrame);
        }
        boolean hasToolCalls = !assistant.toolCalls().isEmpty();
        if (hasToolCalls != (finishReason == ModelFinishReason.TOOL_CALLS)) {
            recordFailure(
                    request,
                    ModelFailureStage.FINISH_METADATA,
                    ModelFailureReason.FINISH_INCONSISTENT,
                    ModelDiagnosticStatusClass.NONE,
                    receivedProviderFrame,
                    emittedUserText,
                    startedNanos);
            throw invalidResponse(
                    "Provider returned inconsistent Tool Call completion metadata",
                    receivedProviderFrame);
        }
    }

    private static ToolCall mapToolCall(
            org.springframework.ai.chat.messages.AssistantMessage.ToolCall call) {
        try {
            return new ToolCall(
                    call.id(),
                    call.name(),
                    SpringAiJson.readArguments(call.arguments()));
        } catch (ModelGatewayException exception) {
            throw new ToolArgumentsMappingException(exception);
        }
    }

    private static Optional<ModelUsage> mapUsage(Usage usage) {
        if (usage == null) {
            return Optional.empty();
        }
        int input = nonNegative(usage.getPromptTokens());
        int output = nonNegative(usage.getCompletionTokens());
        int total = nonNegative(usage.getTotalTokens());
        if (input == 0 && output == 0 && total == 0) {
            return Optional.empty();
        }
        return Optional.of(new ModelUsage(input, output, Math.max(total, input + output)));
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static ModelFinishReason mapFinishReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ModelFinishReason.UNKNOWN;
        }
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "stop", "end_turn", "stop_sequence" -> ModelFinishReason.STOP;
            case "tool_calls", "tool_call", "tool_use" -> ModelFinishReason.TOOL_CALLS;
            case "length", "max_tokens" -> ModelFinishReason.LENGTH;
            case "content_filter" -> ModelFinishReason.CONTENT_FILTER;
            default -> ModelFinishReason.OTHER;
        };
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static FailureClassification classify(Throwable throwable) {
        for (Throwable current = throwable;
                current != null;
                current = current.getCause()) {
            if (current instanceof OpenAIServiceException service) {
                return classifyService(service);
            }
            if (current instanceof com.anthropic.errors.AnthropicServiceException service) {
                return classifyAnthropicService(service);
            }
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return retryable(
                        ModelFailureCategory.REQUEST_TIMEOUT,
                        Optional.empty(),
                        ModelFailureReason.TIMEOUT);
            }
            if (current instanceof OpenAIRetryableException
                    || current instanceof OpenAIIoException
                    || current instanceof java.io.IOException) {
                return retryable(
                        ModelFailureCategory.NETWORK_ERROR,
                        Optional.empty(),
                        ModelFailureReason.NETWORK_IO);
            }
        }
        return permanent(ModelFailureCategory.PROVIDER_ERROR, Optional.empty());
    }

    private static FailureClassification classifyService(OpenAIServiceException service) {
        Optional<ModelHttpStatusClass> statusClass = Optional.of(
                service.statusCode() >= 500
                        ? ModelHttpStatusClass.SERVER_ERROR
                        : ModelHttpStatusClass.CLIENT_ERROR);
        if (service.statusCode() == 400
                && service.code().filter("context_length_exceeded"::equals).isPresent()) {
            return new FailureClassification(
                    CONTEXT_OVERFLOW,
                    ModelFailureSummary.firstAttempt(
                            ModelFailureCategory.INVALID_REQUEST,
                            statusClass,
                            false),
                    ModelFailureReason.UNKNOWN,
                    ModelDiagnosticStatusClass.CLIENT_ERROR,
                    Optional.empty());
        }
        return classifyStatus(service.statusCode(), parseRetryAfter(service.headers().values("retry-after")));
    }

    private static FailureClassification classifyAnthropicService(
            com.anthropic.errors.AnthropicServiceException service) {
        return classifyStatus(service.statusCode(), parseRetryAfter(service.headers().values("retry-after")));
    }

    private static FailureClassification classifyStatus(int status) {
        return classifyStatus(status, Optional.empty());
    }

    private static FailureClassification classifyStatus(
            int status,
            Optional<java.time.Duration> retryAfter) {
        Optional<ModelHttpStatusClass> statusClass = status >= 500
                ? Optional.of(ModelHttpStatusClass.SERVER_ERROR)
                : Optional.of(ModelHttpStatusClass.CLIENT_ERROR);
        if (status >= 500) {
            return retryable(ModelFailureCategory.PROVIDER_UNAVAILABLE, statusClass, retryAfter);
        }
        return switch (status) {
            case 408 -> retryable(ModelFailureCategory.REQUEST_TIMEOUT, statusClass, retryAfter);
            case 409 -> retryable(ModelFailureCategory.REQUEST_CONFLICT, statusClass, retryAfter);
            case 429 -> retryable(ModelFailureCategory.RATE_LIMITED, statusClass, retryAfter);
            case 401, 403 -> permanent(ModelFailureCategory.AUTHENTICATION_FAILED, statusClass);
            default -> permanent(ModelFailureCategory.INVALID_REQUEST, statusClass);
        };
    }

    private static FailureClassification retryable(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass) {
        return retryable(category, statusClass, Optional.empty());
    }

    private static FailureClassification retryable(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass,
            Optional<java.time.Duration> retryAfter) {
        ModelFailureReason reason = category == ModelFailureCategory.REQUEST_TIMEOUT
                ? ModelFailureReason.TIMEOUT
                : ModelFailureReason.UNKNOWN;
        return retryable(category, statusClass, reason, retryAfter);
    }

    private static FailureClassification retryable(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass,
            ModelFailureReason reason) {
        return retryable(category, statusClass, reason, Optional.empty());
    }

    private static FailureClassification retryable(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass,
            ModelFailureReason reason,
            Optional<java.time.Duration> retryAfter) {
        return new FailureClassification(
                RETRYABLE,
                ModelFailureSummary.firstAttempt(category, statusClass, false),
                reason,
                diagnosticStatus(statusClass),
                retryAfter);
    }

    private static FailureClassification permanent(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass) {
        return new FailureClassification(
                PERMANENT,
                ModelFailureSummary.firstAttempt(category, statusClass, false),
                ModelFailureReason.UNKNOWN,
                diagnosticStatus(statusClass),
                Optional.empty());
    }

    private static FailureClassification incompleteStream(ModelFailureReason rootReason) {
        ModelFailureReason reason = rootReason == ModelFailureReason.TIMEOUT
                ? ModelFailureReason.TIMEOUT
                : ModelFailureReason.TRANSPORT_CLOSED;
        return new FailureClassification(
                INCOMPLETE_STREAM,
                ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.INCOMPLETE_STREAM,
                        Optional.empty(),
                        true),
                reason,
                ModelDiagnosticStatusClass.NONE,
                Optional.empty());
    }

    /**
     * 把 SDK/适配器未分类的运行时异常收敛成安全 Provider 故障。
     *
     * <p>OpenAI-compatible SDK 在建立流、订阅或解码响应时仍可能抛出不属于其公开异常
     * 体系的 {@link RuntimeException}。若任其穿过 Adapter，Core 只能生成没有摘要的裸
     * {@code model_error}，TUI 也无法给出可操作提示。这里不读取异常文本，只记录固定
     * {@code PROVIDER_ERROR} 与阶段，并保留原异常作为 JVM 内部 cause。</p>
     */
    private ModelGatewayException unexpectedProviderFailure(
            ModelRequest request,
            ModelFailureStage stage,
            boolean receivedProviderFrame,
            boolean emittedUserText,
            long startedNanos,
            RuntimeException failure) {
        recordFailure(
                request,
                stage,
                ModelFailureReason.UNKNOWN,
                ModelDiagnosticStatusClass.NONE,
                receivedProviderFrame,
                emittedUserText,
                startedNanos);
        return new ModelGatewayException(
                PERMANENT,
                "Model request failed",
                ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.PROVIDER_ERROR,
                        Optional.empty(),
                        receivedProviderFrame || emittedUserText),
                failure);
    }

    /**
     * 只解析 RFC 允许的 delta-seconds 形式；SDK Header 值重复、非法或溢出时忽略。
     */
    private static Optional<java.time.Duration> parseRetryAfter(List<String> values) {
        if (values == null || values.size() != 1) {
            return Optional.empty();
        }
        String value = values.getFirst();
        if (value == null || !value.matches("[0-9]{1,10}")) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(value);
            java.time.Duration parsed = java.time.Duration.ofSeconds(seconds);
            java.time.Duration maximum = java.time.Duration.ofMinutes(5);
            return Optional.of(parsed.compareTo(maximum) > 0 ? maximum : parsed);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static ModelDiagnosticStatusClass diagnosticStatus(
            Optional<ModelHttpStatusClass> statusClass) {
        return statusClass.map(value -> switch (value) {
            case CLIENT_ERROR -> ModelDiagnosticStatusClass.CLIENT_ERROR;
            case SERVER_ERROR -> ModelDiagnosticStatusClass.SERVER_ERROR;
        }).orElse(ModelDiagnosticStatusClass.NONE);
    }

    private static ModelGatewayException invalidResponse(
            String message,
            boolean receivedProviderFrame) {
        return new ModelGatewayException(
                INCOMPLETE_STREAM,
                message,
                ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.INVALID_RESPONSE,
                        Optional.empty(),
                        receivedProviderFrame));
    }

    private void recordFailure(
            ModelRequest request,
            ModelFailureStage stage,
            ModelFailureReason reason,
            ModelDiagnosticStatusClass statusClass,
            boolean receivedProviderFrame,
            boolean emittedUserText,
            long startedNanos) {
        diagnostics.record(
                ModelDiagnosticKind.FAILURE,
                request,
                stage,
                reason,
                statusClass,
                receivedProviderFrame,
                emittedUserText,
                startedNanos);
    }

    private record FailureClassification(
            ModelGatewayException.FailureKind kind,
            ModelFailureSummary summary,
            ModelFailureReason reason,
            ModelDiagnosticStatusClass statusClass,
            Optional<java.time.Duration> retryAfter) {
        private FailureClassification {
            retryAfter = Objects.requireNonNull(retryAfter, "retryAfter 不能为空");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }

    private static final class ModelCancelledException extends RuntimeException {
    }

    private static final class ToolArgumentsMappingException extends RuntimeException {
        private ToolArgumentsMappingException(ModelGatewayException cause) {
            super(cause);
        }
    }
}
