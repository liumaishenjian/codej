package io.github.liumaishenjian.ccjava.model.springai;

import com.openai.errors.OpenAIRetryableException;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelDiagnosticRecorder;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelRetryPolicy;
import io.github.liumaishenjian.ccjava.core.RetryingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.ModelFailureReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureStage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Spring Adapter 只按已知类型和验证点映射封闭模型诊断。 */
class SpringAiModelDiagnosticTest {

    @Test
    void mapsAllValidationStagesAndClosedReasons() {
        assertFailure(Flux.error(new OpenAIRetryableException("EXCEPTION_SENTINEL")),
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.NETWORK_IO, false, false);
        assertFailure(Flux.error(new TimeoutException("EXCEPTION_SENTINEL")),
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.TIMEOUT, false, false);
        assertFailure(Flux.error(new IllegalStateException("EXCEPTION_SENTINEL")),
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.UNKNOWN, false, false);
        assertFailure(Flux.concat(Flux.just(response("partial", null)),
                        Flux.error(new OpenAIRetryableException("EXCEPTION_SENTINEL"))),
                ModelFailureStage.STREAM_TRANSPORT, ModelFailureReason.TRANSPORT_CLOSED, true, true);
        assertFailure(Flux.concat(Flux.just(metadataOnlyResponse()),
                        Flux.error(new TimeoutException("EXCEPTION_SENTINEL"))),
                ModelFailureStage.STREAM_TRANSPORT, ModelFailureReason.TIMEOUT, true, false);
        assertFailure(Flux.concat(Flux.just(response("partial", null)),
                        Flux.error(new TimeoutException("EXCEPTION_SENTINEL"))),
                ModelFailureStage.STREAM_TRANSPORT, ModelFailureReason.TIMEOUT, true, true);
        assertFailure(Flux.just(metadataOnlyResponse()), ModelFailureStage.RESPONSE_DECODE,
                ModelFailureReason.INVALID_RESPONSE, true, false);
        assertFailure(Flux.just(response("partial", null)), ModelFailureStage.FINISH_METADATA,
                ModelFailureReason.FINISH_MISSING, true, true);
        assertFailure(Flux.just(inconsistentToolResponse()), ModelFailureStage.FINISH_METADATA,
                ModelFailureReason.FINISH_INCONSISTENT, true, false);
        assertFailure(Flux.just(invalidToolJsonResponse()), ModelFailureStage.TOOL_ARGUMENTS,
                ModelFailureReason.TOOL_JSON_INVALID, true, false);
    }

    @Test
    void preservesAttemptTurnCorrelationAndPreOutputRetryInvariant() {
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ChatResponse> failures = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.error(new OpenAIRetryableException("EXCEPTION_SENTINEL"));
        });
        SpringAiModelGateway adapter = gateway(failures, events);
        RetryingModelGateway retrying = new RetryingModelGateway(
                adapter,
                new ModelRetryPolicy(3, List.of(Duration.ZERO, Duration.ZERO)));

        assertThatThrownBy(() -> retrying.complete(request(), ignored -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class);

        assertThat(subscriptions).hasValue(3);
        assertThat(events).extracting(ModelDiagnosticEvent::attemptNumber)
                .containsExactly(1, 2, 3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.sessionCorrelation()).isEqualTo(events.getFirst().sessionCorrelation());
            assertThat(event.runCorrelation()).isEqualTo(events.getFirst().runCorrelation());
            assertThat(event.sessionCorrelation()).isNotEqualTo(event.runCorrelation());
            assertThat(event.turnNumber()).isEqualTo(4);
            assertThat(event.emittedUserText()).isFalse();
            assertThat(event.toString()).doesNotContain(
                    "PROMPT_SENTINEL", "RESPONSE_SENTINEL", "ENDPOINT_SENTINEL",
                    "HEADER_SENTINEL", "REQUEST_ID_SENTINEL", "PATH_SENTINEL",
                    "EXCEPTION_SENTINEL", "OpenAIRetryableException");
        });
    }

    @Test
    void cleanEmptyStopRemainsAValidSuccessfulTurn() throws Exception {
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new FixedChatModel(Flux.just(response("", "stop"))), "model");

        var turn = gateway.complete(request(), ignored -> { }, CancellationToken.none());

        assertThat(turn.assistantMessage().text()).isEmpty();
        assertThat(turn.assistantMessage().toolCalls()).isEmpty();
        assertThat(turn.metadata().finishReason().name()).isEqualTo("STOP");
    }

    @Test
    void faultyDiagnosticClockCannotAbortProviderWork() throws Exception {
        ModelDiagnosticRecorder failing = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                ignored -> { },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> { throw new IllegalStateException("CLOCK_SENTINEL"); });
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new FixedChatModel(Flux.just(response("ok", "stop"))), "model", failing);

        assertThat(gateway.complete(request(), ignored -> { }, CancellationToken.none())
                .assistantMessage().text()).isEqualTo("ok");
    }

    @Test
    void sinkFailureCannotChangeSuccessfulOrFailedModelSemantics() throws Exception {
        ModelDiagnosticRecorder failing = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                ignored -> { throw new IllegalStateException("SINK_SENTINEL"); });
        SpringAiModelGateway success = new SpringAiModelGateway(
                new FixedChatModel(Flux.just(response("ok", "stop"))), "model", failing);
        SpringAiModelGateway failure = new SpringAiModelGateway(
                new FixedChatModel(Flux.error(new OpenAIRetryableException("failure"))),
                "model", failing);

        assertThat(success.complete(request(), ignored -> { }, CancellationToken.none())
                .assistantMessage().text()).isEqualTo("ok");
        assertThatThrownBy(() -> failure.complete(request(), ignored -> { }, CancellationToken.none()))
                .isInstanceOfSatisfying(ModelGatewayException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ModelGatewayException.FailureKind.RETRYABLE));
    }

    @Test
    void wrapsUnexpectedChatModelRuntimeAsSanitizedProviderError() {
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new ThrowingChatModel(),
                "model",
                new ModelDiagnosticRecorder(
                        ModelDiagnosticMode.SAFE,
                        events::add,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        () -> 1_000_000L));

        assertThatThrownBy(() -> gateway.complete(
                request(), ignored -> { }, CancellationToken.none()))
                .isInstanceOfSatisfying(ModelGatewayException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(ModelGatewayException.FailureKind.PERMANENT);
                    assertThat(failure.summary()).hasValueSatisfying(summary -> {
                        assertThat(summary.category())
                                .isEqualTo(io.github.liumaishenjian.ccjava.domain.ModelFailureCategory.PROVIDER_ERROR);
                        assertThat(summary.statusClass()).isEmpty();
                        assertThat(summary.attempts()).isEqualTo(1);
                        assertThat(summary.receivedOutput()).isFalse();
                        assertThat(summary.toString()).doesNotContain("UNCLASSIFIED_PROVIDER_SENTINEL");
                    });
                    assertThat(failure.getMessage()).isEqualTo("Model request failed");
                });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo(ModelFailureStage.REQUEST_TRANSPORT);
            assertThat(event.reason()).isEqualTo(ModelFailureReason.UNKNOWN);
            assertThat(event.receivedProviderFrame()).isFalse();
            assertThat(event.emittedUserText()).isFalse();
        });
    }

    private static void assertFailure(
            Flux<ChatResponse> responses,
            ModelFailureStage stage,
            ModelFailureReason reason,
            boolean receivedFrame,
            boolean emittedText) {
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        SpringAiModelGateway gateway = gateway(responses, events);

        assertThatThrownBy(() -> gateway.complete(request(), ignored -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo(stage);
            assertThat(event.reason()).isEqualTo(reason);
            assertThat(event.receivedProviderFrame()).isEqualTo(receivedFrame);
            assertThat(event.emittedUserText()).isEqualTo(emittedText);
        });
    }

    private static SpringAiModelGateway gateway(
            Flux<ChatResponse> responses,
            List<ModelDiagnosticEvent> events) {
        return new SpringAiModelGateway(
                new FixedChatModel(responses),
                "model",
                new ModelDiagnosticRecorder(
                        ModelDiagnosticMode.SAFE,
                        events::add,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        () -> 1_000_000L));
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new SessionId("session-diagnostic"),
                new RunId("run-diagnostic"),
                4,
                List.of(new UserMessage("PROMPT_SENTINEL")),
                List.of());
    }

    private static ChatResponse response(String text, String finishReason) {
        return new ChatResponse(
                List.of(new Generation(
                        new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().model("provider-model").build());
    }

    private static ChatResponse metadataOnlyResponse() {
        return new ChatResponse(
                List.of(),
                ChatResponseMetadata.builder().model("provider-model").build());
    }

    private static ChatResponse inconsistentToolResponse() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call", "function", "tool", "{}");
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return new ChatResponse(
                List.of(new Generation(output,
                        ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder().model("provider-model").build());
    }

    private static ChatResponse invalidToolJsonResponse() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call", "function", "tool", "RESPONSE_SENTINEL");
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return new ChatResponse(
                List.of(new Generation(output,
                        ChatGenerationMetadata.builder().finishReason("tool_calls").build())),
                ChatResponseMetadata.builder().model("provider-model").build());
    }

    private record FixedChatModel(Flux<ChatResponse> responses) implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return responses.blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return responses;
        }
    }

    private static final class ThrowingChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException("UNCLASSIFIED_PROVIDER_SENTINEL");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new IllegalStateException("UNCLASSIFIED_PROVIDER_SENTINEL");
        }
    }
}
