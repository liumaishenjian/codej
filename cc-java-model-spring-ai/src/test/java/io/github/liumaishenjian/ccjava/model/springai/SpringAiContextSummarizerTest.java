package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SummaryTier;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** 验证摘要 Adapter 的零 Tool、纯数据候选、拒绝边界与取消传播。 */
class SpringAiContextSummarizerTest {

    @Test
    void returnsCandidateWithExactSourceIdentityAndZeroToolPrompt() {
        RecordingChatModel model = new RecordingChatModel(Flux.just(response("summary", "stop")));
        SpringAiContextSummarizer summarizer = new SpringAiContextSummarizer(model, "summary-model");
        SummaryRequest request = request();

        Optional<SummaryCandidate> result = summarizer.summarize(request, CancellationToken.none());

        assertThat(result).hasValueSatisfying(candidate -> {
            assertThat(candidate.tier()).isEqualTo(request.tier());
            assertThat(candidate.sourceRevision()).isEqualTo(request.sourceRevision());
            assertThat(candidate.sourceMessageIds()).containsExactlyElementsOf(request.sourceMessageIds());
            assertThat(candidate.summary()).isEqualTo("summary");
        });
        assertThat(model.prompt().getInstructions()).hasSize(2);
        OpenAiChatOptions options = (OpenAiChatOptions) model.prompt().getOptions();
        assertThat(options.getModel()).isEqualTo("summary-model");
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(model.prompt().getUserMessage().getText())
                .contains("cc-java-summary-request-v1")
                .contains("inputBase64")
                .doesNotContain("snapshot secret");
    }

    @Test
    void acceptsAnthropicNormalEndTurnButRejectsIncompleteOrToolTerminals() {
        assertThat(summarize(response("summary", "end_turn"))).hasValueSatisfying(
                candidate -> assertThat(candidate.summary()).isEqualTo("summary"));
        for (String reason : List.of("max_tokens", "length", "tool_use", "tool_calls", "refusal", "unknown")) {
            assertThat(summarize(response("partial", reason))).isEmpty();
        }
    }

    @Test
    void aggregatesStreamingTextBeforeBuildingCandidate() {
        RecordingChatModel model = new RecordingChatModel(Flux.just(
                response("sum", null), response("mary", "stop")));

        Optional<SummaryCandidate> result = new SpringAiContextSummarizer(
                model, "summary-model").summarize(request(), CancellationToken.none());

        assertThat(result).hasValueSatisfying(candidate ->
                assertThat(candidate.summary()).isEqualTo("summary"));
    }

    @Test
    void rejectsToolCallsUnsupportedFinishBlankAndOversizeOutput() {
        AssistantMessage toolOutput = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "forbidden", "{}")))
                .build();
        ChatResponse toolResponse = new ChatResponse(
                List.of(new Generation(toolOutput, ChatGenerationMetadata.builder()
                        .finishReason("tool_calls").build())),
                ChatResponseMetadata.builder().build());

        assertThat(summarize(toolResponse)).isEmpty();
        assertThat(summarize(response("partial", null))).isEmpty();
        assertThat(summarize(response("   ", "stop"))).isEmpty();
        assertThat(summarize(response("x".repeat(500), "stop"))).isEmpty();
    }

    @Test
    void providerFailureThrowsSanitizedControlledException() {
        String sensitive = "provider secret body token-123";
        SpringAiContextSummarizer summarizer = new SpringAiContextSummarizer(
                new RecordingChatModel(Flux.error(new IllegalStateException(sensitive))),
                "summary-model");

        assertThatThrownBy(() -> summarizer.summarize(request(), CancellationToken.none()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Context summary model request failed")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("provider secret")
                        .doesNotContain("token-123"));
    }

    @Test
    void cancellationDisposesDirectProviderRequestAndReturnsNoCandidate() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        RecordingChatModel model = new RecordingChatModel(
                Flux.<ChatResponse>never().doOnCancel(() -> disposed.set(true)));
        SpringAiContextSummarizer summarizer = new SpringAiContextSummarizer(model, "summary-model");
        CancellationSource cancellation = new CancellationSource();
        CompletableFuture<Optional<SummaryCandidate>> running = CompletableFuture.supplyAsync(() ->
                summarizer.summarize(request(), cancellation.token()));
        awaitPrompt(model);

        cancellation.cancel();

        assertThat(running.get(2, TimeUnit.SECONDS)).isEmpty();
        assertThat(disposed).isTrue();
    }

    private Optional<SummaryCandidate> summarize(ChatResponse response) {
        return new SpringAiContextSummarizer(
                new RecordingChatModel(Flux.just(response)), "summary-model")
                .summarize(request(), CancellationToken.none());
    }

    private SummaryRequest request() {
        return new SummaryRequest(
                SummaryTier.C3_ROLLING,
                "snapshot secret",
                7,
                List.of("m-1", "m-2"),
                List.of("snapshot"),
                100,
                20,
                100);
    }

    private ChatResponse response(String text, String finishReason) {
        return new ChatResponse(
                List.of(new Generation(
                        new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().build());
    }

    private void awaitPrompt(RecordingChatModel model) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (model.prompt() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(model.prompt()).isNotNull();
    }

    private static final class RecordingChatModel implements ChatModel {
        private final Flux<ChatResponse> responses;
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

        private RecordingChatModel(Flux<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.prompt.set(prompt);
            return responses;
        }

        private Prompt prompt() {
            return prompt.get();
        }
    }
}
