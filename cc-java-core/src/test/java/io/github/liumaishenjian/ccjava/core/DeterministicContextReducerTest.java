package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ContextReductionReason;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStatus;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStrategy;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalogRevision;
import io.github.liumaishenjian.ccjava.domain.MemoryContextMessage;
import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionItem;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserFileAttachment;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicContextReducerTest {

    private static final ContextTokenEstimator ESTIMATOR =
            new CodePointContextTokenEstimator();

    @Test
    void estimatorTreatsExplicitJsonNullAsJsonNullInsteadOfUnsupportedType() {
        java.util.LinkedHashMap<String, Object> patch = new java.util.LinkedHashMap<>();
        patch.put("remove", null);
        AssistantMessage message = AssistantMessage.tools(List.of(new ToolCall(
                "call-null", "task_update", new JsonObject(java.util.Map.of("metadata_patch", patch)))));

        var usage = ESTIMATOR.estimate(List.of(message), new ContextCapacity("fake", 1_000, 10, 10));

        assertThat(usage.totalTokens()).isPositive();
    }

    @Test
    void estimatorClassifiesMemorySeparatelyAndIncludesItInTotal() {
        String body = "memory-body";
        MemoryContextMessage memory = new MemoryContextMessage(
                new MemoryCatalogRevision("c".repeat(64)),
                List.of(new MemoryProjectionItem(
                        "topic", MemoryKind.PROJECT_STATE, "hook", body,
                        "d".repeat(64), body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
        ContextCapacity capacity = new ContextCapacity("fake", 1_000, 10, 10);

        var usage = ESTIMATOR.estimate(
                List.of(new SystemMessage("sys"), new UserMessage("user"), memory),
                capacity);

        assertThat(usage.memoryTokens()).isPositive();
        assertThat(usage.transcriptTokens()).isEqualTo(4);
        assertThat(usage.totalTokens()).isEqualTo(
                usage.systemTokens()
                        + usage.instructionTokens()
                        + usage.transcriptTokens()
                        + usage.toolTokens()
                        + usage.memoryTokens());
    }

    @Test
    void estimatorConservativelyAccountsForBase64FileEnvelope() {
        UserFileAttachment attachment = new UserFileAttachment(
                "src/说明.java", "内容".repeat(100), "a".repeat(64), 1, 100, false);
        ContextCapacity capacity = new ContextCapacity("fake", 10_000, 10, 10);

        var plain = ESTIMATOR.estimate(List.of(new UserMessage("检查")), capacity);
        var withFile = ESTIMATOR.estimate(
                List.of(new UserMessage("检查", List.of(attachment))), capacity);

        assertThat(withFile.transcriptTokens()).isGreaterThan(
                plain.transcriptTokens() + attachment.textSnapshot().codePointCount(
                        0, attachment.textSnapshot().length()));
    }

    @Test
    void appliesNoStrategyUnderLowPressure() {
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("small request"));
        ProjectionRequest request = request(canonical, 200, 0);

        ContextReductionOutcome outcome = reducer(40).reduce(request, CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.UNCHANGED);
        assertThat(outcome.reason()).isEqualTo(ContextReductionReason.WITHIN_CAPACITY);
        assertThat(outcome.projection().messages()).isEqualTo(canonical);
        assertThat(outcome.projection().appliedReductions()).isEmpty();
    }

    @Test
    void appliesOnlyC1ForSingleLargePayload() {
        ToolCall call = call("large-1");
        ToolResultMessage large = result(call, "x".repeat(120));
        List<AgentMessage> canonical = List.of(
                new UserMessage("inspect"),
                AssistantMessage.tools(List.of(call)),
                large);
        ProjectionRequest request = request(canonical, 100, 0);

        ContextReductionOutcome outcome = reducer(80).reduce(request, CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.REDUCED);
        assertThat(outcome.projection().appliedReductions())
                .extracting(reduction -> reduction.strategy())
                .containsExactly(ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION);
        assertThat(projectedResult(outcome, 2).result().callId()).isEqualTo(call.id());
        assertThat(projectedResult(outcome, 2).result().content()).contains("C1");
        assertThat(canonical.get(2)).isSameAs(large);
        assertThat(large.result().content()).isEqualTo("x".repeat(120));
    }

    @Test
    void appliesOnlyC2ForOldToolPressure() {
        ToolCall first = call("old-1");
        ToolCall second = call("old-2");
        List<AgentMessage> canonical = List.of(
                new UserMessage("inspect old output"),
                AssistantMessage.tools(List.of(first, second)),
                result(first, "a".repeat(45)),
                result(second, "b".repeat(45)),
                new UserMessage("continue"));
        ProjectionRequest request = request(canonical, 185, 1);

        ContextReductionOutcome outcome = reducer(100).reduce(request, CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.REDUCED);
        assertThat(outcome.projection().appliedReductions())
                .extracting(reduction -> reduction.strategy())
                .containsExactly(ContextReductionStrategy.OLD_TOOL_RESULT_CLEANUP);
        assertThat(projectedResult(outcome, 2).result().content()).contains("C2");
        assertThat(projectedResult(outcome, 3).result().content()).contains("C2");
        assertNoProtocolOrphans(outcome.projection().messages());
    }

    @Test
    void combinesC1AndC2ThenStopsWhenBudgetFits() {
        ToolCall large = call("combo-large");
        ToolCall oldOne = call("combo-old-1");
        ToolCall oldTwo = call("combo-old-2");
        ToolCall untouched = call("combo-untouched");
        List<AgentMessage> canonical = List.of(
                new UserMessage("combined"),
                AssistantMessage.tools(List.of(large)),
                result(large, "L".repeat(130)),
                AssistantMessage.tools(List.of(oldOne, oldTwo)),
                result(oldOne, "a".repeat(35)),
                result(oldTwo, "b".repeat(35)),
                AssistantMessage.tools(List.of(untouched)),
                result(untouched, "keep-me"));
        long initialUsage = ESTIMATOR.estimate(canonical, capacity(10_000)).totalTokens();
        ProjectionRequest request = request(canonical, initialUsage - 115, 0);

        ContextReductionOutcome outcome = reducer(100).reduce(request, CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.REDUCED);
        assertThat(outcome.projection().appliedReductions())
                .extracting(reduction -> reduction.strategy())
                .containsExactly(
                        ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION,
                        ContextReductionStrategy.OLD_TOOL_RESULT_CLEANUP);
        assertThat(projectedResult(outcome, 7).result().content()).isEqualTo("keep-me");
        assertThat(outcome.finalUsage().fits()).isTrue();
        assertNoProtocolOrphans(outcome.projection().messages());
    }

    @Test
    void preservesMultiToolBatchIdsAndOrder() {
        ToolCall first = call("batch-1");
        ToolCall second = call("batch-2");
        AssistantMessage batch = AssistantMessage.tools(List.of(first, second));
        List<AgentMessage> canonical = List.of(
                new UserMessage("batch"),
                batch,
                result(first, "x".repeat(60)),
                result(second, "y".repeat(60)));
        ProjectionRequest request = request(canonical, 130, 0);

        ContextReductionOutcome outcome = reducer(100).reduce(request, CancellationToken.none());

        AssistantMessage projectedBatch = (AssistantMessage) outcome.projection().messages().get(1);
        assertThat(projectedBatch).isSameAs(batch);
        assertThat(projectedBatch.toolCalls())
                .extracting(ToolCall::id)
                .containsExactly("batch-1", "batch-2");
        assertThat(outcome.projection().messages().stream()
                        .filter(ToolResultMessage.class::isInstance)
                        .map(ToolResultMessage.class::cast)
                        .map(message -> message.result().callId()))
                .containsExactly("batch-1", "batch-2");
        assertNoProtocolOrphans(outcome.projection().messages());
    }

    @Test
    void neverReducesProtectedActiveBatch() {
        ToolCall completed = call("completed");
        ToolCall active = call("active");
        ToolResultMessage activeResult = result(active, "A".repeat(140));
        List<AgentMessage> canonical = List.of(
                new UserMessage("active"),
                AssistantMessage.tools(List.of(completed)),
                result(completed, "old".repeat(35)),
                AssistantMessage.tools(List.of(active)),
                activeResult);
        ProjectionRequest request = request(canonical, 70, 2);

        ContextReductionOutcome outcome = reducer(80).reduce(request, CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.CONTEXT_LIMIT_REACHED);
        assertThat(outcome.reason())
                .isEqualTo(ContextReductionReason.ACTIVE_OR_PROTECTED_TOOL_BATCH);
        assertThat(outcome.projection().messages()).isEqualTo(canonical);
        assertThat(outcome.projection().appliedReductions()).isEmpty();
        assertThat(activeResult.result().content()).isEqualTo("A".repeat(140));
    }

    @Test
    void returnsStructuredLimitWhenIncompleteToolCannotBeReduced() {
        ToolCall incomplete = call("unfinished");
        List<AgentMessage> canonical = List.of(
                new UserMessage("unfinished"),
                AssistantMessage.tools(List.of(incomplete)),
                new UserMessage("still waiting ".repeat(20)));
        ProjectionRequest request = request(canonical, 60, 0);

        ContextReductionOutcome outcome = reducer(30).reduce(request, CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.CONTEXT_LIMIT_REACHED);
        assertThat(outcome.reason())
                .isEqualTo(ContextReductionReason.INVALID_TOOL_PROTOCOL);
        assertThat(outcome.projection().messages()).isEqualTo(canonical);
        assertThat(outcome.initialUsage()).isEqualTo(outcome.finalUsage());
    }

    @Test
    void rejectsInvalidToolProtocolsBeforeReduction() {
        ToolCall a = call("a");
        ToolCall b = call("b");
        List<List<AgentMessage>> invalid = List.of(
                List.of(new UserMessage("x".repeat(100)), result(a, "orphan")),
                List.of(result(a, "before"), AssistantMessage.tools(List.of(a))),
                List.of(AssistantMessage.tools(List.of(a)), AssistantMessage.tools(List.of(a)), result(a, "x")),
                List.of(AssistantMessage.tools(List.of(a)), result(a, "x"), result(a, "duplicate")),
                List.of(AssistantMessage.tools(List.of(a, b)), result(b, "wrong-order"), result(a, "x")),
                List.of(AssistantMessage.tools(List.of(a, b)), result(a, "x"), AssistantMessage.tools(List.of(call("c")))),
                List.of(AssistantMessage.tools(List.of(a))));

        for (List<AgentMessage> messages : invalid) {
            ContextReductionOutcome outcome = reducer(10).reduce(request(messages, 1, 0), CancellationToken.none());
            assertThat(outcome.status()).isEqualTo(ContextReductionStatus.CONTEXT_LIMIT_REACHED);
            assertThat(outcome.reason()).isEqualTo(ContextReductionReason.INVALID_TOOL_PROTOCOL);
            assertThat(outcome.projection().messages()).isEqualTo(messages);
        }
    }

    @Test
    void rejectsInvalidToolProtocolEvenWhenUsageFits() {
        ToolCall orphan = call("low-pressure-orphan");
        List<AgentMessage> canonical = List.of(result(orphan, "small"));

        ContextReductionOutcome outcome = reducer(10).reduce(
                request(canonical, 10_000, 0),
                CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(ContextReductionStatus.CONTEXT_LIMIT_REACHED);
        assertThat(outcome.reason()).isEqualTo(ContextReductionReason.INVALID_TOOL_PROTOCOL);
        assertThat(outcome.projection().messages()).isEqualTo(canonical);
        assertThat(outcome.initialUsage()).isEqualTo(outcome.finalUsage());
    }

    @Test
    void rejectsCapacityArithmeticOverflow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ContextCapacity("model", Long.MAX_VALUE, Long.MAX_VALUE, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("溢出");
    }

    private DeterministicContextReducer reducer(long c1Threshold) {
        return new DeterministicContextReducer(ESTIMATOR, c1Threshold);
    }

    private ProjectionRequest request(
            List<AgentMessage> messages,
            long availableTokens,
            int protectedMessages) {
        return new ProjectionRequest(
                messages,
                capacity(availableTokens),
                7,
                protectedMessages,
                true);
    }

    private ContextCapacity capacity(long availableTokens) {
        return new ContextCapacity("offline-model", availableTokens + 2, 1, 1);
    }

    private ToolCall call(String id) {
        return new ToolCall(id, "read_file", JsonObject.empty());
    }

    private ToolResultMessage result(ToolCall call, String content) {
        return new ToolResultMessage(ToolResult.success(call.id(), call.name(), content));
    }

    private ToolResultMessage projectedResult(ContextReductionOutcome outcome, int index) {
        return (ToolResultMessage) outcome.projection().messages().get(index);
    }

    private void assertNoProtocolOrphans(List<AgentMessage> messages) {
        Set<String> calls = new HashSet<>();
        List<String> results = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.toolCalls().forEach(call -> calls.add(call.id()));
            } else if (message instanceof ToolResultMessage result) {
                results.add(result.result().callId());
            }
        }
        assertThat(results).doesNotHaveDuplicates();
        assertThat(new HashSet<>(results)).isEqualTo(calls);
    }
}
