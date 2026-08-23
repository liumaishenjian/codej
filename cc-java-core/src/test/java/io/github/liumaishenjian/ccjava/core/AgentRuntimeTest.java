package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewContextItem;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.RunStatus;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

    private static final String SYSTEM_INSTRUCTIONS = "你是一个只执行离线协议测试的 Agent。";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-28T00:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void adaptiveInteractiveBudgetCompletesAfterMoreThanThirtyTwoProgressingToolCalls() {
        ModelTurn[] turns = new ModelTurn[35];
        for (int index = 0; index < 34; index++) {
            turns[index] = ModelTurn.tools(List.of(call("adaptive-" + index, "echo")));
        }
        turns[34] = ModelTurn.text("done after progress");
        ScriptedModelGateway model = ScriptedModelGateway.of(turns);
        AgentTool tool = successfulTool("echo");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("long interactive",
                AgentLimits.interactive(Duration.ofMinutes(1)));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(34);
        assertThat(result.modelTurns()).isEqualTo(35);
        assertThat(harness.events().envelopes()).extracting(AgentEventEnvelope::event)
                .filteredOn(LifecycleEvent.BudgetGoverned.class::isInstance)
                .map(LifecycleEvent.BudgetGoverned.class::cast)
                .extracting(LifecycleEvent.BudgetGoverned::reason)
                .contains(io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.PROGRESS_EXTENDED);
    }

    @Test
    void adaptiveToolBudgetRenewsAcrossMultipleStepsBeforeExecutingLargeBatch() {
        List<ToolCall> largeBatch = java.util.stream.IntStream.range(0, 48)
                .mapToObj(index -> call("large-" + index, "echo"))
                .toList();
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("progress", "echo"))),
                ModelTurn.tools(largeBatch),
                ModelTurn.text("done after large batch"));
        Harness harness = newHarness(model, successfulTool("echo"));

        AgentRunResult result = harness.run("large progressing batch",
                AgentLimits.interactive(Duration.ofMinutes(1)));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(49);
        assertThat(toolResults(harness.session())).hasSize(49);
        assertThat(harness.events().envelopes()).extracting(AgentEventEnvelope::event)
                .filteredOn(LifecycleEvent.BudgetGoverned.class::isInstance)
                .map(LifecycleEvent.BudgetGoverned.class::cast)
                .filteredOn(event -> event.reason()
                        == io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.PROGRESS_EXTENDED)
                .singleElement()
                .satisfies(event -> assertThat(event.effectiveToolLimit()).isEqualTo(64));
    }

    @Test
    void adaptiveToolBatchStopsBeforeAppendingWhenAbsoluteCeilingCannotFitWholeBatch() {
        List<ToolCall> batchNearCeiling = java.util.stream.IntStream.range(0, 46)
                .mapToObj(index -> call("near-ceiling-" + index, "echo"))
                .toList();
        List<ToolCall> batchBeyondCeiling = List.of(
                call("beyond-ceiling-1", "echo"), call("beyond-ceiling-2", "echo"));
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("progress", "echo"))),
                ModelTurn.tools(batchNearCeiling),
                ModelTurn.tools(batchBeyondCeiling));
        Harness harness = newHarness(model, successfulTool("echo"));
        AgentLimits bounded = new AgentLimits(4, 32, Duration.ofMinutes(1),
                io.github.liumaishenjian.ccjava.domain.AgentBudgetPolicy.INTERACTIVE_ADAPTIVE, 4, 48);

        AgentRunResult result = harness.run("absolute batch ceiling", bounded);

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_LIMIT_REACHED);
        assertThat(result.toolCalls()).isEqualTo(47);
        assertThat(toolResults(harness.session())).hasSize(47);
        assertThat(harness.session().messages()).filteredOn(AssistantMessage.class::isInstance).hasSize(2);
        assertThat(harness.events().envelopes()).extracting(AgentEventEnvelope::event)
                .filteredOn(LifecycleEvent.BudgetGoverned.class::isInstance)
                .map(LifecycleEvent.BudgetGoverned.class::cast)
                .extracting(LifecycleEvent.BudgetGoverned::reason)
                .contains(io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.ABSOLUTE_LIMIT);
    }

    @Test
    void adaptiveAbsoluteCeilingStillTerminatesWithExplicitReason() {
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("absolute-1", "echo"))),
                ModelTurn.tools(List.of(call("absolute-2", "echo"))),
                ModelTurn.tools(List.of(call("absolute-3", "echo"))),
                ModelTurn.text("must not be reached"));
        Harness harness = newHarness(model, successfulTool("echo"));
        AgentLimits bounded = new AgentLimits(2, 2, Duration.ofMinutes(1),
                io.github.liumaishenjian.ccjava.domain.AgentBudgetPolicy.INTERACTIVE_ADAPTIVE, 3, 3);

        AgentRunResult result = harness.run("absolute cap", bounded);

        assertThat(result.stopReason()).isEqualTo(StopReason.TURN_LIMIT_REACHED);
        assertThat(result.modelTurns()).isEqualTo(3);
        assertThat(harness.events().envelopes()).extracting(AgentEventEnvelope::event)
                .filteredOn(LifecycleEvent.BudgetGoverned.class::isInstance)
                .map(LifecycleEvent.BudgetGoverned.class::cast)
                .extracting(LifecycleEvent.BudgetGoverned::reason)
                .contains(io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.ABSOLUTE_LIMIT);
    }

    @Test
    void explicitToolCapStillTerminatesExactBatch() {
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("hard-1", "echo"))),
                ModelTurn.tools(List.of(call("hard-2", "echo"))));
        Harness harness = newHarness(model,
                successfulTool("echo"));

        AgentRunResult result = harness.run("explicit cap", new AgentLimits(3, 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_LIMIT_REACHED);
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void explicitToolCapRejectsWholeOversizedBatchWithoutPartialProtocol() {
        List<ToolCall> batch = List.of(call("hard-batch-1", "echo"), call("hard-batch-2", "echo"));
        Harness harness = newHarness(ScriptedModelGateway.of(ModelTurn.tools(batch)), successfulTool("echo"));

        AgentRunResult result = harness.run("explicit batch cap", new AgentLimits(2, 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_LIMIT_REACHED);
        assertThat(result.toolCalls()).isZero();
        assertThat(toolResults(harness.session())).isEmpty();
        assertThat(harness.session().messages()).filteredOn(AssistantMessage.class::isInstance).isEmpty();
        assertThat(harness.events().envelopes()).extracting(AgentEventEnvelope::event)
                .filteredOn(LifecycleEvent.BudgetGoverned.class::isInstance)
                .map(LifecycleEvent.BudgetGoverned.class::cast)
                .extracting(LifecycleEvent.BudgetGoverned::reason)
                .containsExactly(io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason.EXPLICIT_LIMIT);
    }

    @Test
    void runtimeKeepsForbiddenFingerprintAcrossUnrelatedSuccessAndRecoversAfterSameToolSuccess() {
        AtomicInteger webExecutions = new AtomicInteger();
        AgentTool webSearch = new AgentTool() {
            @Override public io.github.liumaishenjian.ccjava.domain.ToolDefinition definition() {
                return io.github.liumaishenjian.ccjava.domain.ToolDefinition.readOnlyText(
                        "web_search", "fingerprint fixture", "{\"type\":\"object\"}");
            }
            @Override public ToolValidationResult validate(JsonObject arguments) {
                return ToolValidationResult.validResult();
            }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                webExecutions.incrementAndGet();
                return invocation.call().arguments().values().get("query").equals("blocked")
                        ? ToolExecutionOutcome.failure(ToolError.of(
                                ToolErrorCode.WEB_SEARCH_FORBIDDEN, "typed 403"))
                        : ToolExecutionOutcome.success("strategy succeeded");
            }
        };
        ToolCall blocked = new ToolCall("blocked-1", "web_search",
                new JsonObject(Map.of("query", "blocked")));
        ToolCall status = new ToolCall("status", "git_status", JsonObject.empty());
        ToolCall repeated = new ToolCall("blocked-2", "web_search",
                new JsonObject(Map.of("query", "blocked")));
        ToolCall changed = new ToolCall("changed", "web_search",
                new JsonObject(Map.of("query", "allowed")));
        ToolCall retriedAfterSuccess = new ToolCall("blocked-3", "web_search",
                new JsonObject(Map.of("query", "blocked")));
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(blocked)),
                ModelTurn.tools(List.of(status)),
                ModelTurn.tools(List.of(repeated)),
                ModelTurn.tools(List.of(changed)),
                ModelTurn.tools(List.of(retriedAfterSuccess)),
                ModelTurn.text("done"));
        Harness harness = newHarness(model, webSearch, successfulTool("git_status"));

        AgentRunResult result = harness.run("fingerprint runtime", new AgentLimits(8, 8));
        List<ToolResult> results = toolResults(harness.session());

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(results).extracting(value -> value.error().map(ToolError::code).orElse(null))
                .containsExactly(ToolErrorCode.WEB_SEARCH_FORBIDDEN, null,
                        ToolErrorCode.REPEATED_FAILURE, null, ToolErrorCode.WEB_SEARCH_FORBIDDEN);
        assertThat(webExecutions).hasValue(3);
    }

    @Test
    void validationFeedbackAllowsModelToCorrectArgumentsOnNextTurn() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new RecordingAgentTool(
                "search_text",
                arguments -> arguments.values().containsKey("maxResults")
                        ? ToolValidationResult.invalid(
                                "删除 maxResults，仅使用 limit",
                                new JsonObject(Map.of(
                                        "preferredField", "limit",
                                        "removeFields", List.of("maxResults"))),
                                new JsonObject(Map.of(
                                        "violation", "limit_max_results_conflict")))
                        : ToolValidationResult.validResult(),
                ignored -> {
                    executions.incrementAndGet();
                    return ToolExecutionOutcome.success("corrected search");
                });
        ToolCall invalid = new ToolCall("search-invalid", "search_text",
                new JsonObject(Map.of("query", "needle", "limit", 10, "maxResults", 10)));
        ToolCall corrected = new ToolCall("search-corrected", "search_text",
                new JsonObject(Map.of("query", "needle", "limit", 10)));
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(invalid)),
                ModelTurn.tools(List.of(corrected)),
                ModelTurn.text("done"));
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("correct invalid search", AgentLimits.interactive(Duration.ofMinutes(1)));
        List<ToolResult> results = toolResults(harness.session());

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
            assertThat(error.details().values())
                    .containsEntry("argumentChangeRequired", true)
                    .containsEntry("preferredField", "limit")
                    .containsEntry("removeFields", List.of("maxResults"));
        });
        assertThat(results.getLast().status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(executions).hasValue(1);
    }

    @Test
    void changedQueriesWithSameValidationCorrectionShapeOpenCircuitWithCompleteResults() {
        AgentTool tool = new RecordingAgentTool(
                "search_text",
                ignored -> ToolValidationResult.invalid(
                        "remove maxResults",
                        new JsonObject(Map.of("removeFields", List.of("maxResults"))),
                        new JsonObject(Map.of("violation", "limit_max_results_conflict"))),
                ignored -> { throw new AssertionError("invalid Tool must not execute"); });
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(new ToolCall("invalid-1", "search_text",
                        new JsonObject(Map.of("query", "first", "limit", 1, "maxResults", 1))))),
                ModelTurn.tools(List.of(
                        new ToolCall("repeated-1a", "search_text",
                                new JsonObject(Map.of("query", "second-a", "limit", 1, "maxResults", 1))),
                        new ToolCall("repeated-1b", "search_text",
                                new JsonObject(Map.of("query", "second-b", "limit", 1, "maxResults", 1))))),
                ModelTurn.tools(List.of(
                        new ToolCall("repeated-2a", "search_text",
                                new JsonObject(Map.of("query", "third-a", "limit", 1, "maxResults", 1))),
                        new ToolCall("repeated-2b", "search_text",
                                new JsonObject(Map.of("query", "third-b", "limit", 1, "maxResults", 1))))),
                ModelTurn.text("must not be reached"));
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("repeat invalid search", AgentLimits.interactive(Duration.ofMinutes(1)));
        List<ToolResult> results = toolResults(harness.session());

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_ERROR);
        assertThat(result.modelTurns()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(5);
        assertThat(results).extracting(value -> value.error().map(ToolError::code).orElse(null))
                .containsExactly(ToolErrorCode.INVALID_ARGUMENTS,
                        ToolErrorCode.REPEATED_FAILURE, ToolErrorCode.REPEATED_FAILURE,
                        ToolErrorCode.REPEATED_FAILURE, ToolErrorCode.REPEATED_FAILURE);
        assertThat(results).extracting(ToolResult::callId)
                .containsExactly("invalid-1", "repeated-1a", "repeated-1b",
                        "repeated-2a", "repeated-2b");
        assertThat(model.requests()).hasSize(3);
    }

    @Test
    void genericInvalidWithoutSignatureStillOpensExactArgumentsCircuit() {
        AgentTool tool = new RecordingAgentTool(
                "generic_invalid",
                ignored -> ToolValidationResult.invalid("change arguments"),
                ignored -> { throw new AssertionError("invalid Tool must not execute"); });
        JsonObject arguments = new JsonObject(Map.of("query", "same", "path", "same.txt"));
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(new ToolCall("generic-invalid", "generic_invalid", arguments))),
                ModelTurn.tools(List.of(new ToolCall("generic-repeated-1", "generic_invalid", arguments))),
                ModelTurn.tools(List.of(new ToolCall("generic-repeated-2", "generic_invalid", arguments))),
                ModelTurn.text("must not be reached"));
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run(
                "repeat generic invalid", AgentLimits.interactive(Duration.ofMinutes(1)));
        List<ToolResult> results = toolResults(harness.session());

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_ERROR);
        assertThat(result.modelTurns()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(results).extracting(value -> value.error().map(ToolError::code).orElse(null))
                .containsExactly(ToolErrorCode.INVALID_ARGUMENTS,
                        ToolErrorCode.REPEATED_FAILURE, ToolErrorCode.REPEATED_FAILURE);
        assertThat(results).extracting(ToolResult::callId)
                .containsExactly("generic-invalid", "generic-repeated-1", "generic-repeated-2");
    }

    @Test
    void completesDirectlyWhenModelReturnsFinalText() {
        ScriptedModelGateway model = ScriptedModelGateway.of(ModelTurn.text("任务完成"));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("直接回答", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.finalText()).contains("任务完成");
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(model.requests()).hasSize(1);
        assertThat(harness.session().messages()).containsExactly(
                new UserMessage("直接回答"),
                AssistantMessage.text("任务完成"));
    }

    @Test
    void autoReviewCircuitStopsAfterCompleteFourCallBatchWithoutRequestingAnotherModelTurn() {
        List<ToolCall> calls = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> call("auto-runtime-" + index, "reviewed"))
                .toList();
        ScriptedModelGateway model = ScriptedModelGateway.of(ModelTurn.tools(calls));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        AgentTool tool = reviewedTool(executions);
        Harness harness = newAutoReviewHarness(model, reviews, approvals, tool);

        AgentRunResult result = harness.run("auto review batch", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason()).isEqualTo(StopReason.AUTO_REVIEW_CIRCUIT_OPEN);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isEqualTo(4);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingTurns()).isZero();
        assertThat(executions).hasValue(0);
        assertThat(approvals).hasValue(0);
        assertThat(reviews).hasValue(3);
        assertThat(toolResults(harness.session())).extracting(ToolResult::callId)
                .containsExactly("auto-runtime-0", "auto-runtime-1", "auto-runtime-2", "auto-runtime-3");
        assertThat(toolResults(harness.session())).extracting(ToolResult::toolName)
                .containsOnly("reviewed");
        assertThat(toolResults(harness.session())).allMatch(resultItem ->
                resultItem.status() == ToolResultStatus.DENIED);
    }

    @Test
    void autoReviewRequestKeepsRecentContextRolesWhenHistoryExceedsMaximum() {
        AtomicReference<io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest> captured =
                new AtomicReference<>();
        AtomicInteger reviews = new AtomicInteger();
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("seed-1", "seed"))), ModelTurn.text("seed one"),
                ModelTurn.tools(List.of(call("seed-2", "seed"))), ModelTurn.text("seed two"),
                ModelTurn.tools(List.of(call("seed-3", "seed"))), ModelTurn.text("seed three"),
                ModelTurn.tools(List.of(
                        call("recent-1", "reviewed"), call("recent-2", "reviewed"), call("recent-3", "reviewed"))));
        Harness harness = newAutoReviewHarness(model, reviews, new AtomicInteger(),
                (request, token) -> {
                    if (request.callId().equals("recent-3")) {
                        captured.set(request);
                    }
                    return ApprovalReviewResult.deny();
                }, RecordingAgentTool.succeeding("seed", "seed result"), reviewedTool(new AtomicInteger()));
        for (int index = 1; index <= 3; index++) {
            assertThat(harness.run("seed " + index, AgentLimits.DEFAULT).stopReason())
                    .isEqualTo(StopReason.COMPLETED);
        }

        AgentRunResult result = harness.run("current", AgentLimits.DEFAULT);

        assertThat(result.stopReason()).isEqualTo(StopReason.AUTO_REVIEW_CIRCUIT_OPEN);
        assertThat(captured.get().recentContext()).hasSize(
                io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest.MAX_CONTEXT_ITEMS);
        assertThat(captured.get().recentContext()).extracting(ApprovalReviewContextItem::role)
                .containsExactly(
                        ApprovalReviewContextItem.Role.TOOL_RESULT,
                        ApprovalReviewContextItem.Role.ASSISTANT,
                        ApprovalReviewContextItem.Role.USER,
                        ApprovalReviewContextItem.Role.ASSISTANT,
                        ApprovalReviewContextItem.Role.TOOL_RESULT,
                        ApprovalReviewContextItem.Role.ASSISTANT,
                        ApprovalReviewContextItem.Role.USER,
                        ApprovalReviewContextItem.Role.ASSISTANT);
    }

    @Test
    void continuesUntilFinalResponseAcrossMultipleToolTurns() {
        ToolCall firstCall = call("call-1", "echo");
        ToolCall secondCall = call("call-2", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(firstCall)),
                ModelTurn.tools(List.of(secondCall)),
                ModelTurn.text("两轮工具均已处理"));
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "echo-result");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("连续调用工具", AgentLimits.DEFAULT);

        ToolResult firstResult = ToolResult.success("call-1", "echo", "echo-result");
        ToolResult secondResult = ToolResult.success("call-2", "echo", "echo-result");
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.modelTurns()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(tool.invocations())
                .extracting(invocation -> invocation.call().id())
                .containsExactly("call-1", "call-2");
        assertThat(harness.session().messages()).containsExactly(
                new UserMessage("连续调用工具"),
                AssistantMessage.tools(List.of(firstCall)),
                new ToolResultMessage(firstResult),
                AssistantMessage.tools(List.of(secondCall)),
                new ToolResultMessage(secondResult),
                AssistantMessage.text("两轮工具均已处理"));
        assertContextHistory(
                model.requests().get(1),
                new UserMessage("连续调用工具"),
                AssistantMessage.tools(List.of(firstCall)),
                new ToolResultMessage(firstResult));
        assertContextHistory(
                model.requests().get(2),
                new UserMessage("连续调用工具"),
                AssistantMessage.tools(List.of(firstCall)),
                new ToolResultMessage(firstResult),
                AssistantMessage.tools(List.of(secondCall)),
                new ToolResultMessage(secondResult));
    }

    @Test
    void executesAllCallsInOneTurnBeforeNextModelRequest() {
        ToolCall firstCall = call("batch-1", "echo");
        ToolCall secondCall = call("batch-2", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(firstCall, secondCall)),
                ModelTurn.text("批次完成"));
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "ok");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("一次调用两个工具", AgentLimits.DEFAULT);

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(model.requests()).hasSize(2);
        assertThat(tool.invocations())
                .extracting(invocation -> invocation.call().id())
                .containsExactly("batch-1", "batch-2");
        assertContextHistory(
                model.requests().get(1),
                new UserMessage("一次调用两个工具"),
                AssistantMessage.tools(List.of(firstCall, secondCall)),
                new ToolResultMessage(ToolResult.success("batch-1", "echo", "ok")),
                new ToolResultMessage(ToolResult.success("batch-2", "echo", "ok")));

        List<AgentEventEnvelope> events = harness.events().envelopes();
        int secondTurnStarted = indexOfEvent(
                events,
                LifecycleEvent.ModelTurnStarted.class,
                2);
        int lastToolCompleted = lastIndexOfEvent(events, LifecycleEvent.AfterTool.class);
        assertThat(secondTurnStarted).isGreaterThan(lastToolCompleted);
    }

    @Test
    void appendsMultiCallAssistantMessageExactlyOnce() {
        ToolCall firstCall = call("single-message-1", "echo");
        ToolCall secondCall = call("single-message-2", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(firstCall, secondCall)),
                ModelTurn.text("完成"));
        Harness harness = newHarness(model, RecordingAgentTool.succeeding("echo", "ok"));

        harness.run("验证 Assistant 追加次数", AgentLimits.DEFAULT);

        List<AssistantMessage> toolCallingMessages = harness.session().messages().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> !message.toolCalls().isEmpty())
                .toList();
        assertThat(toolCallingMessages)
                .singleElement()
                .isEqualTo(AssistantMessage.tools(List.of(firstCall, secondCall)));
    }

    @Test
    void preservesCallIdsForSuccessfulAndFailedToolResults() {
        ToolCall successCall = call("success-id", "mixed");
        ToolCall failureCall = call("failure-id", "mixed");
        RecordingAgentTool tool = new RecordingAgentTool(
                "mixed",
                ignored -> ToolValidationResult.validResult(),
                invocation -> invocation.call().id().equals("success-id")
                        ? ToolExecutionOutcome.success("成功")
                        : ToolExecutionOutcome.failure(ToolError.of(
                                ToolErrorCode.EXECUTION_FAILED,
                                "预期失败")));
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(successCall, failureCall)),
                ModelTurn.text("已观察两个结果"));
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("校验 Call ID", AgentLimits.DEFAULT);

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(toolResults(harness.session()))
                .extracting(ToolResult::callId)
                .containsExactly("success-id", "failure-id");
        assertThat(toolResults(harness.session()))
                .extracting(ToolResult::status)
                .containsExactly(ToolResultStatus.SUCCESS, ToolResultStatus.FAILURE);
        assertThat(toolResults(harness.session()))
                .extracting(ToolResult::toolName)
                .containsExactly("mixed", "mixed");
    }

    @Test
    void returnsStructuredUnknownToolResultAndLetsModelRecover() {
        ToolCall unknownCall = call("unknown-1", "missing_tool");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(unknownCall)),
                ModelTurn.text("未知工具已被纠正"));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("调用不存在的工具", AgentLimits.DEFAULT);

        ToolResult errorResult = toolResults(harness.session()).get(0);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(errorResult.callId()).isEqualTo("unknown-1");
        assertThat(errorResult.toolName()).isEqualTo("missing_tool");
        assertThat(errorResult.status()).isEqualTo(ToolResultStatus.FAILURE);
        assertThat(errorResult.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.UNKNOWN_TOOL);
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(1).messages())
                .contains(new ToolResultMessage(errorResult));
    }

    @Test
    void returnsStructuredInvalidArgumentsAndDoesNotExecuteTool() {
        ToolCall invalidCall = call("invalid-1", "validated");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(invalidCall)),
                ModelTurn.text("参数错误已修正"));
        RecordingAgentTool tool = RecordingAgentTool.invalid(
                "validated",
                "缺少必填参数 value");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("使用无效参数", AgentLimits.DEFAULT);

        ToolResult errorResult = toolResults(harness.session()).get(0);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(tool.invocations()).isEmpty();
        assertThat(errorResult.callId()).isEqualTo("invalid-1");
        assertThat(errorResult.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(errorResult.error().orElseThrow().details().values())
                .containsEntry("violations", List.of("缺少必填参数 value"));
        assertThat(model.requests().get(1).messages())
                .contains(new ToolResultMessage(errorResult));
    }

    @Test
    void normalizesToolExceptionAndLetsModelRecover() {
        ToolCall failedCall = call("exception-1", "throwing");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(failedCall)),
                ModelTurn.text("执行异常已被处理"));
        RecordingAgentTool tool = RecordingAgentTool.throwing(
                "throwing",
                new IllegalStateException("boom"));
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("触发工具异常", AgentLimits.DEFAULT);

        ToolResult errorResult = toolResults(harness.session()).get(0);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(tool.invocations()).hasSize(1);
        assertThat(errorResult.callId()).isEqualTo("exception-1");
        assertThat(errorResult.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(errorResult.error().orElseThrow().message())
                .isEqualTo("Tool 执行失败")
                .doesNotContain("boom");
        assertThat(model.requests().get(1).messages())
                .contains(new ToolResultMessage(errorResult));
    }

    @Test
    void stopsOnEmptyModelTurnWithoutAppendingAssistantMessage() {
        ScriptedModelGateway model = ScriptedModelGateway.of(
                new ModelTurn(new AssistantMessage("", List.of())));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("返回空响应", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_MODEL_RESPONSE);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(model.requests()).hasSize(1);
        assertThat(harness.session().messages())
                .containsExactly(new UserMessage("返回空响应"));
    }

    @Test
    void stopsBeforeRequestingModelBeyondTurnLimit() {
        ToolCall call = call("last-turn-call", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call)));
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "ok");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run(
                "只有一个模型回合",
                new AgentLimits(1, 2));

        assertThat(result.stopReason()).isEqualTo(StopReason.TURN_LIMIT_REACHED);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingTurns()).isZero();
        assertThat(tool.invocations()).hasSize(1);
        assertThat(harness.session().messages()).containsExactly(
                new UserMessage("只有一个模型回合"),
                AssistantMessage.tools(List.of(call)),
                new ToolResultMessage(ToolResult.success(
                        "last-turn-call",
                        "echo",
                        "ok")));
    }

    @Test
    void completesNormallyOnLastAllowedTurn() {
        ScriptedModelGateway model = ScriptedModelGateway.of(ModelTurn.text("刚好完成"));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "最后允许回合完成",
                new AgentLimits(1, 0));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void rejectsEntireMultiCallBatchWhenRemainingBudgetIsInsufficient() {
        ToolCall firstCall = call("over-budget-1", "echo");
        ToolCall secondCall = call("over-budget-2", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(firstCall, secondCall)));
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "不应执行");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run(
                "整批超过工具额度",
                new AgentLimits(2, 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_LIMIT_REACHED);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(tool.invocations()).isEmpty();
        assertThat(harness.session().messages())
                .containsExactly(new UserMessage("整批超过工具额度"));
        assertThat(harness.events().envelopes())
                .noneMatch(envelope -> envelope.event() instanceof LifecycleEvent.BeforeTool);
    }

    @Test
    void stopsBeforeAcceptingNextToolBatchAfterBudgetConsumed() {
        ToolCall acceptedCall = call("accepted-1", "echo");
        ToolCall rejectedCall = call("rejected-2", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(acceptedCall)),
                ModelTurn.tools(List.of(rejectedCall)));
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "ok");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run(
                "第二批超过工具额度",
                new AgentLimits(3, 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_LIMIT_REACHED);
        assertThat(result.modelTurns()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(model.requests()).hasSize(2);
        assertThat(tool.invocations())
                .extracting(invocation -> invocation.call().id())
                .containsExactly("accepted-1");
        assertThat(harness.session().messages()).containsExactly(
                new UserMessage("第二批超过工具额度"),
                AssistantMessage.tools(List.of(acceptedCall)),
                new ToolResultMessage(ToolResult.success(
                        "accepted-1",
                        "echo",
                        "ok")));
    }

    @Test
    void emitsOrderedEventsForToolLoop() {
        ToolCall call = call("event-call", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call)),
                ModelTurn.text("完成"));
        Harness harness = newHarness(model, RecordingAgentTool.succeeding("echo", "ok"));

        AgentRunResult result = harness.run("验证事件顺序", AgentLimits.DEFAULT);

        List<AgentEventEnvelope> events = harness.events().envelopes();
        assertThat(events)
                .extracting(envelope -> envelope.event().getClass().getSimpleName())
                .containsExactly(
                        "SessionStarted",
                        "RunStarted",
                        "ModelTurnStarted",
                        "ModelTurnCompleted",
                        "BeforeTool",
                        "PermissionEvaluationStarted",
                        "PermissionEvaluated",
                        "PermissionDecided",
                        "AfterTool",
                        "ModelTurnStarted",
                        "ModelTurnCompleted",
                        "RunFinished");
        assertThat(events)
                .extracting(AgentEventEnvelope::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        assertThat(events.get(0).runId()).isEmpty();
        assertThat(events.subList(1, events.size()))
                .allSatisfy(envelope -> assertThat(envelope.runId()).contains(result.runId()));
        assertThat(events.get(events.size() - 1).event())
                .isEqualTo(new LifecycleEvent.RunFinished(result));
        assertThat(events.stream()
                .filter(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished))
                .hasSize(1);
    }

    @Test
    void emitsExactlyOneTerminalEventOnInvalidModelResponse() {
        ScriptedModelGateway model = ScriptedModelGateway.of(
                new ModelTurn(new AssistantMessage(" ", List.of())));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("验证唯一终态", AgentLimits.DEFAULT);

        List<AgentEventEnvelope> events = harness.events().envelopes();
        List<LifecycleEvent.RunFinished> terminalEvents = events.stream()
                .map(AgentEventEnvelope::event)
                .filter(LifecycleEvent.RunFinished.class::isInstance)
                .map(LifecycleEvent.RunFinished.class::cast)
                .toList();
        assertThat(terminalEvents)
                .singleElement()
                .isEqualTo(new LifecycleEvent.RunFinished(result));
        assertThat(events.get(events.size() - 1).event()).isEqualTo(terminalEvents.get(0));
        assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_MODEL_RESPONSE);
    }

    @Test
    void keepsConversationAcrossRunsInSameInMemorySession() {
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.text("第一轮回答"),
                ModelTurn.text("第二轮回答"));
        Harness harness = newHarness(model);

        AgentRunResult firstResult = harness.run("第一轮问题", AgentLimits.DEFAULT);
        List<AgentMessage> firstSnapshot = harness.session().messages();
        AgentRunResult secondResult = harness.run("第二轮追问", AgentLimits.DEFAULT);

        assertThat(firstResult.sessionId()).isEqualTo(secondResult.sessionId());
        assertThat(firstResult.runId()).isNotEqualTo(secondResult.runId());
        assertThat(harness.session().messages()).containsExactly(
                new UserMessage("第一轮问题"),
                AssistantMessage.text("第一轮回答"),
                new UserMessage("第二轮追问"),
                AssistantMessage.text("第二轮回答"));
        assertContextHistory(
                model.requests().get(0),
                new UserMessage("第一轮问题"));
        assertContextHistory(
                model.requests().get(1),
                new UserMessage("第一轮问题"),
                AssistantMessage.text("第一轮回答"),
                new UserMessage("第二轮追问"));
        assertThat(firstSnapshot).containsExactly(
                new UserMessage("第一轮问题"),
                AssistantMessage.text("第一轮回答"));
        assertThatThrownBy(() -> firstSnapshot.add(new UserMessage("不能修改")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(harness.events().envelopes().stream()
                .filter(envelope -> envelope.event() instanceof LifecycleEvent.RunStarted))
                .hasSize(2);
        assertThat(harness.events().envelopes().stream()
                .filter(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished))
                .hasSize(2);
    }

    @Test
    void mapsModelGatewayExceptionToModelErrorAndEmitsOneTerminalEvent() {
        ModelGateway model = ignored -> {
            throw new ModelGatewayException("模拟 Provider 故障");
        };
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("触发模型异常", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_ERROR);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(harness.session().messages())
                .containsExactly(new UserMessage("触发模型异常"));
        List<LifecycleEvent.RunFinished> terminalEvents = harness.events().envelopes().stream()
                .map(AgentEventEnvelope::event)
                .filter(LifecycleEvent.RunFinished.class::isInstance)
                .map(LifecycleEvent.RunFinished.class::cast)
                .toList();
        assertThat(terminalEvents)
                .singleElement()
                .isEqualTo(new LifecycleEvent.RunFinished(result));
        List<AgentEventEnvelope> events = harness.events().envelopes();
        assertThat(events.get(events.size() - 1).event())
                .isEqualTo(terminalEvents.get(0));
        assertThat(harness.events().envelopes())
                .noneMatch(envelope ->
                        envelope.event() instanceof LifecycleEvent.ModelTurnCompleted);
    }

    @Test
    void rejectsDuplicateToolCallIdsInSameBatchWithoutAppendingOrExecuting() {
        ToolCall firstCall = call("duplicate-call-id", "echo");
        ToolCall secondCall = call("duplicate-call-id", "echo");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(firstCall, secondCall)));
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "不应执行");
        Harness harness = newHarness(model, tool);

        AgentRunResult result = harness.run("返回重复 Call ID", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_MODEL_RESPONSE);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(model.requests()).hasSize(1);
        assertThat(tool.invocations()).isEmpty();
        assertThat(harness.session().messages())
                .containsExactly(new UserMessage("返回重复 Call ID"));
        assertThat(harness.events().envelopes())
                .noneMatch(envelope -> envelope.event() instanceof LifecycleEvent.BeforeTool);
    }

    @Test
    void returnsDeniedToolResultToModelWhenApprovalRejectsAskDecision() {
        ToolCall deniedCall = call("denied-call", "approval_tool");
        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(deniedCall)),
                ModelTurn.text("已根据拒绝结果调整"));
        RecordingAgentTool tool = RecordingAgentTool.succeeding(
                "approval_tool",
                "不应执行");
        AtomicInteger approvalRequests = new AtomicInteger();
        PermissionGate askGate = (ignoredInvocation, definition) ->
                io.github.liumaishenjian.ccjava.domain.PermissionOutcome.of(
                        PermissionDecision.ASK,
                        io.github.liumaishenjian.ccjava.domain.PermissionReason.EFFECT_DEFAULT,
                        io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                                definition.name(), definition.source()));
        ApprovalHandler denyApproval = (ignoredInvocation, ignoredDefinition, ignoredOutcome) -> {
            approvalRequests.incrementAndGet();
            return io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny();
        };
        Harness harness = newHarness(model, askGate, denyApproval, tool);

        AgentRunResult result = harness.run("请求需要审批的 Tool", AgentLimits.DEFAULT);

        ToolResult deniedResult = toolResults(harness.session()).get(0);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(approvalRequests).hasValue(1);
        assertThat(tool.invocations()).isEmpty();
        assertThat(deniedResult.callId()).isEqualTo("denied-call");
        assertThat(deniedResult.toolName()).isEqualTo("approval_tool");
        assertThat(deniedResult.status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(deniedResult.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(1).messages())
                .contains(new ToolResultMessage(deniedResult));
    }

    @Test
    void publishesStreamingDeltasBeforeTheAggregatedTurnCompletes() {
        StreamingModelGateway model = (request, observer, cancellation) -> {
            observer.onTextDelta("alpha");
            observer.onTextDelta(" beta");
            return ModelTurn.text("alpha beta");
        };
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("流式回答", AgentLimits.DEFAULT);

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(harness.events().envelopes())
                .extracting(AgentEventEnvelope::event)
                .filteredOn(ModelTextDelta.class::isInstance)
                .containsExactly(
                        new ModelTextDelta(1, "alpha"),
                        new ModelTextDelta(1, " beta"));
        assertThat(indexOfEvent(
                harness.events().envelopes(),
                LifecycleEvent.ModelTurnStarted.class,
                1)).isLessThan(harness.events().envelopes().stream()
                .map(AgentEventEnvelope::event)
                .toList()
                .indexOf(new ModelTextDelta(1, "alpha")));
        assertThat(harness.events().envelopes().stream()
                .map(AgentEventEnvelope::event)
                .toList()
                .indexOf(new ModelTextDelta(1, " beta")))
                .isLessThan(indexOfEvent(
                        harness.events().envelopes(),
                        LifecycleEvent.ModelTurnCompleted.class,
                        1));
    }

    @Test
    void exactRunCancellationStopsTheModelStreamWithOneCancelledTerminal() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        AtomicReference<RunId> requestedRunId = new AtomicReference<>();
        StreamingModelGateway model = (request, observer, cancellation) -> {
            requestedRunId.set(request.runId());
            modelStarted.countDown();
            try (CancellationToken.Registration ignored =
                         cancellation.onCancellation(cancellationObserved::countDown)) {
                if (!cancellationObserved.await(2, TimeUnit.SECONDS)) {
                    throw new ModelGatewayException("Test cancellation timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ModelGatewayException("Test interrupted");
            }
            throw new ModelGatewayException("Model request cancelled");
        };
        Harness harness = newHarness(model);

        CompletableFuture<AgentRunResult> running =
                CompletableFuture.supplyAsync(() -> harness.run("等待取消", AgentLimits.DEFAULT));
        assertThat(modelStarted.await(2, TimeUnit.SECONDS)).isTrue();
        RunId runId = requestedRunId.get();
        assertThat(harness.runtime().cancel(harness.session().id(), new RunId("wrong-run")))
                .isFalse();
        assertThat(harness.runtime().cancel(harness.session().id(), runId)).isTrue();

        AgentRunResult result = running.get(2, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.stopReason()).isEqualTo(StopReason.USER_CANCELLED);
        assertThat(result.runId()).isEqualTo(runId);
        assertThat(harness.runtime().cancel(harness.session().id(), runId)).isFalse();
        assertThat(harness.events().envelopes())
                .noneMatch(envelope ->
                        envelope.event() instanceof LifecycleEvent.ModelTurnCompleted);
        assertThat(harness.events().envelopes())
                .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished)
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.event())
                        .isEqualTo(new LifecycleEvent.RunFinished(result)));
    }

    @Test
    void cancellationCallbacksAreFailureIsolatedAndRunKeepsOneTerminal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        StreamingModelGateway blocking = (request, observer, cancellation) -> {
            cancellation.onCancellation(() -> { throw new IllegalStateException("callback failure"); });
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
                throw new ModelGatewayException(ModelGatewayException.FailureKind.CANCELLED, "interrupted");
            }
            throw new AssertionError("unreachable");
        };
        Harness harness = newHarness(blocking);
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                () -> harness.run("callback isolation", AgentLimits.DEFAULT));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        RunId runId = harness.events().envelopes().stream()
                .filter(envelope -> envelope.event() instanceof LifecycleEvent.RunStarted)
                .findFirst().orElseThrow().runId().orElseThrow();

        assertThat(harness.runtime().cancel(harness.session().id(), runId)).isTrue();
        AgentRunResult result = running.get(2, TimeUnit.SECONDS);

        assertThat(result.stopReason()).isEqualTo(StopReason.USER_CANCELLED);
        assertThat(harness.events().envelopes())
                .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished)
                .singleElement();
    }

    @Test
    void deadlineReturnsEvenWhenBlockingGatewayIgnoresCancellationCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ModelGateway blocking = request -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("阻塞 Gateway 不应自行返回");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.CANCELLED, "Blocking gateway interrupted");
            }
        };
        Harness harness = newHarness(blocking);

        AgentRunResult result = harness.run(
                "阻塞调用 deadline",
                new AgentLimits(16, 32, Duration.ofMillis(50)));

        assertThat(entered.getCount()).isZero();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result.stopReason()).isEqualTo(StopReason.TIME_LIMIT_REACHED);
        assertThat(harness.events().envelopes())
                .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished)
                .singleElement();
    }

    @Test
    void wallClockDeadlineCancelsModelAndSuppressesLateDelta() {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        StreamingModelGateway model = (request, observer, cancellation) -> {
            observer.onTextDelta("before-timeout");
            modelStarted.countDown();
            try (CancellationToken.Registration ignored =
                         cancellation.onCancellation(cancellationObserved::countDown)) {
                if (!cancellationObserved.await(2, TimeUnit.SECONDS)) {
                    throw new ModelGatewayException("Test deadline timeout");
                }
                observer.onTextDelta("late-after-timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ModelGatewayException("Test interrupted");
            }
            throw new ModelGatewayException("Model request timed out");
        };
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "等待墙钟限制",
                new AgentLimits(16, 32, Duration.ofMillis(50)));

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason()).isEqualTo(StopReason.TIME_LIMIT_REACHED);
        assertThat(modelStarted.getCount()).isZero();
        assertThat(cancellationObserved.getCount()).isZero();
        assertThat(harness.events().envelopes())
                .extracting(AgentEventEnvelope::event)
                .filteredOn(ModelTextDelta.class::isInstance)
                .containsExactly(new ModelTextDelta(1, "before-timeout"));
        assertThat(harness.events().envelopes())
                .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished)
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.event())
                        .isEqualTo(new LifecycleEvent.RunFinished(result)));
    }

    @Test
    void lengthFinishReasonStopsWithoutAppendingTruncatedAssistant() {
        ModelTurn truncated = new ModelTurn(
                AssistantMessage.text("partial"),
                new ModelTurnMetadata(
                        ModelFinishReason.LENGTH,
                        Optional.empty(),
                        Optional.of("test-model")));
        Harness harness = newHarness(ScriptedModelGateway.of(truncated));

        AgentRunResult result = harness.run("生成较长回答", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason()).isEqualTo(StopReason.OUTPUT_LIMIT_REACHED);
        assertThat(result.finalText()).isEmpty();
        assertThat(harness.session().messages())
                .containsExactly(new UserMessage("生成较长回答"));
    }

    @Test
    void mapsIncompleteModelStreamToDedicatedFailureReason() {
        ModelGateway model = request -> {
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.INCOMPLETE_STREAM,
                    "incomplete");
        };
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("触发断流", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.stopReason()).isEqualTo(StopReason.INCOMPLETE_MODEL_STREAM);
    }

    @Test
    void retryExhaustionProducesOneRuntimeTerminalEvent() {
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway provider = (request, observer, cancellation) -> {
            attempts.incrementAndGet();
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.RETRYABLE,
                    "busy",
                    new io.github.liumaishenjian.ccjava.domain.ModelFailureSummary(
                            io.github.liumaishenjian.ccjava.domain.ModelFailureCategory.PROVIDER_UNAVAILABLE,
                            java.util.Optional.of(
                                    io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass.SERVER_ERROR),
                            1,
                            false));
        };
        RetryingModelGateway model = new RetryingModelGateway(
                provider,
                new ModelRetryPolicy(
                        3,
                        List.of(Duration.ZERO, Duration.ZERO)));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("触发重试耗尽", AgentLimits.DEFAULT);

        assertThat(attempts).hasValue(3);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_RETRY_EXHAUSTED);
        assertThat(result.modelFailure()).contains(
                new io.github.liumaishenjian.ccjava.domain.ModelFailureSummary(
                        io.github.liumaishenjian.ccjava.domain.ModelFailureCategory.PROVIDER_UNAVAILABLE,
                        java.util.Optional.of(
                                io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass.SERVER_ERROR),
                        3,
                        false));
        assertThat(harness.events().envelopes())
                .filteredOn(envelope ->
                        envelope.event() instanceof LifecycleEvent.RunFinished)
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.event())
                        .isEqualTo(new LifecycleEvent.RunFinished(result)));
    }

    private static AgentTool successfulTool(String name) {
        return new AgentTool() {
            @Override public io.github.liumaishenjian.ccjava.domain.ToolDefinition definition() {
                return io.github.liumaishenjian.ccjava.domain.ToolDefinition.readOnlyText(
                        name, "progress fixture", "{\"type\":\"object\"}");
            }
            @Override public ToolValidationResult validate(JsonObject arguments) {
                return ToolValidationResult.validResult();
            }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                return ToolExecutionOutcome.success("ok");
            }
        };
    }

    private static AgentTool reviewedTool(AtomicInteger executions) {
        return new AgentTool() {
            @Override
            public io.github.liumaishenjian.ccjava.domain.ToolDefinition definition() {
                return io.github.liumaishenjian.ccjava.domain.ToolDefinition.readOnlyText(
                        "reviewed", "auto review fixture", "{\"type\":\"object\"}");
            }

            @Override
            public ToolValidationResult validate(JsonObject arguments) {
                return ToolValidationResult.validResult();
            }

            @Override
            public ToolExecutionOutcome execute(ToolInvocation invocation) {
                executions.incrementAndGet();
                return ToolExecutionOutcome.success(invocation.call().id());
            }
        };
    }

    private static Harness newAutoReviewHarness(
            ModelGateway model,
            AtomicInteger reviews,
            AtomicInteger approvals,
            AgentTool... tools) {
        return newAutoReviewHarness(model, reviews, approvals,
                (request, token) -> ApprovalReviewResult.deny(), tools);
    }

    private static Harness newAutoReviewHarness(
            ModelGateway model,
            AtomicInteger reviews,
            AtomicInteger approvals,
            ApprovalReviewGateway reviewer,
            AgentTool... tools) {
        RecordingAgentEventSink eventSink = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(FIXED_CLOCK, eventSink);
        SequentialAgentIdGenerator idGenerator = new SequentialAgentIdGenerator();
        InMemorySessionStore sessionStore = new InMemorySessionStore(idGenerator, lifecycle);
        ToolRegistry registry = new ToolRegistry(Arrays.asList(tools));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry,
                (invocation, definition) -> io.github.liumaishenjian.ccjava.domain.PermissionOutcome.of(
                        PermissionDecision.ASK,
                        io.github.liumaishenjian.ccjava.domain.PermissionReason.EFFECT_DEFAULT,
                        io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                                definition.name(), definition.source())),
                (invocation, definition, outcome) -> {
                    approvals.incrementAndGet();
                    return io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce();
                },
                new InMemorySessionPermissionState(), lifecycle, SessionJournal.noop(), CheckpointCoordinator.noop(),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(),
                io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator.disabled(),
                ApprovalReviewer.AUTO_REVIEW,
                new AutoReviewCoordinator((request, token) -> {
                    reviews.incrementAndGet();
                    return reviewer.review(request, token);
                }));
        AgentRuntime runtime = new AgentRuntime(sessionStore, idGenerator, model, new DefaultContextAssembler(),
                registry, pipeline, lifecycle);
        AgentSession session = sessionStore.create(SessionSpec.of(SYSTEM_INSTRUCTIONS));
        return new Harness(runtime, session, eventSink);
    }

    private static Harness newHarness(
            ModelGateway model,
            AgentTool... tools) {
        return newHarness(
                model,
                (ignoredInvocation, definition) ->
                        io.github.liumaishenjian.ccjava.domain.PermissionOutcome.of(
                                PermissionDecision.ALLOW,
                                io.github.liumaishenjian.ccjava.domain.PermissionReason.EFFECT_DEFAULT,
                                io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                                        definition.name(), definition.source())),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                tools);
    }

    private static Harness newHarness(
            ModelGateway model,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            AgentTool... tools) {
        RecordingAgentEventSink eventSink = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(FIXED_CLOCK, eventSink);
        SequentialAgentIdGenerator idGenerator = new SequentialAgentIdGenerator();
        InMemorySessionStore sessionStore = new InMemorySessionStore(
                idGenerator,
                lifecycle);
        ToolRegistry registry = new ToolRegistry(Arrays.asList(tools));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry,
                permissionGate,
                approvalHandler,
                lifecycle);
        AgentRuntime runtime = new AgentRuntime(
                sessionStore,
                idGenerator,
                model,
                new DefaultContextAssembler(),
                registry,
                pipeline,
                lifecycle);
        AgentSession session = sessionStore.create(SessionSpec.of(SYSTEM_INSTRUCTIONS));
        return new Harness(runtime, session, eventSink);
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(
                id,
                name,
                new JsonObject(Map.of("value", id)));
    }

    private static List<ToolResult> toolResults(AgentSession session) {
        return session.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .map(ToolResultMessage::result)
                .toList();
    }

    private static void assertContextHistory(
            ModelRequest request,
            AgentMessage... expectedHistory) {
        assertThat(request.messages().get(0))
                .isEqualTo(new SystemMessage(SYSTEM_INSTRUCTIONS));
        assertThat(request.messages().subList(1, request.messages().size()))
                .containsExactly(expectedHistory);
    }

    private static int indexOfEvent(
            List<AgentEventEnvelope> events,
            Class<? extends LifecycleEvent> eventType,
            int occurrence) {
        int seen = 0;
        for (int index = 0; index < events.size(); index++) {
            if (eventType.isInstance(events.get(index).event()) && ++seen == occurrence) {
                return index;
            }
        }
        return -1;
    }

    private static int lastIndexOfEvent(
            List<AgentEventEnvelope> events,
            Class<? extends LifecycleEvent> eventType) {
        for (int index = events.size() - 1; index >= 0; index--) {
            if (eventType.isInstance(events.get(index).event())) {
                return index;
            }
        }
        return -1;
    }

    private record Harness(
            AgentRuntime runtime,
            AgentSession session,
            RecordingAgentEventSink events) {

        AgentRunResult run(String userMessage, AgentLimits limits) {
            return runtime.run(
                    session.id(),
                    new AgentRunRequest(new UserMessage(userMessage), limits));
        }
    }
}
