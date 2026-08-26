package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;

class RuntimeStdioCommandHandlerTest {

    @TempDir
    Path temporaryRoot;

    private Path workspace() throws java.io.IOException {
        Path workspace = temporaryRoot.resolve("workspace");
        Files.createDirectories(workspace);
        return workspace;
    }

    private HeadlessRuntimeOptions testOptions() throws java.io.IOException {
        return testOptions(Duration.ofSeconds(3));
    }

    private HeadlessRuntimeOptions testOptions(Duration timeout) throws java.io.IOException {
        return new HeadlessRuntimeOptions(
                workspace(),
                "fake-model",
                timeout,
                PermissionMode.DEFAULT,
                List.of(),
                SessionOpenRequest.create(),
                temporaryRoot.resolve("sessions"));
    }

    @Test
    void terminalCallbackImmediateSubmissionGetsQueuedDispositionThenStartsExactlyOnce() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<RuntimeStdioCommandHandler> handlerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<StdioProtocol.EventEmitter> emitterRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> submissionFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            events.add(new CapturedEvent(type, requestId, sessionId, runId, payload.deepCopy()));
            if (type.equals("run.completed") && requestId.equals("first")
                    && submitted.compareAndSet(false, true)) {
                try {
                    handlerRef.get().handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                            + "\"requestId\":\"second\",\"sessionId\":\"%s\",\"sequence\":3,"
                            + "\"payload\":{\"prompt\":\"second\"}}").formatted(
                                    sessionId.orElseThrow())), emitterRef.get());
                } catch (Throwable failure) {
                    submissionFailure.set(failure);
                }
            }
        };
        emitterRef.set(emitter);
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handlerRef.set(handler);
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"),
                    emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"first\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"first\"}}").formatted(sessionId)), emitter);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && events.stream()
                    .noneMatch(event -> event.type().equals("run.completed")
                            && event.runId().isPresent() && event.requestId().equals("second"))) {
                Thread.sleep(10);
            }
        }

        assertThat(submissionFailure.get()).isNull();
        assertThat(events.stream()
                .filter(event -> event.type().equals("run.command.result")
                        && event.requestId().equals("second"))
                .map(event -> event.payload().get("disposition").stringValue()))
                .containsExactly("queued");
        assertThat(events.stream()
                .filter(event -> event.type().equals("run.started") && event.requestId().equals("second")))
                .hasSize(1);
        assertThat(events.stream()
                .filter(event -> event.type().equals("run.completed") && event.requestId().equals("second")))
                .hasSize(1);
    }

    @Test
    void successfulTaskMutationsPushAuthoritativeSnapshotsAfterToolCompletion() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, requestId, sessionId, runId, payload.deepCopy()));
        AtomicInteger calls = new AtomicInteger();
        HeadlessRuntimeOptions options = testOptions();
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create-task", "task_create",
                    new JsonObject(java.util.Map.of("subject", "实时刷新")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("complete-task", "task_update",
                    new JsonObject(java.util.Map.of("task_id", "task-1", "status", "COMPLETED")))));
            default -> ModelTurn.text("done");
        };

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler((eventSink, approvals) ->
                io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                        model, eventSink, options, approvals))) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"),
                    emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"完成复杂任务\"}}").formatted(sessionId)), emitter);
            awaitTerminal(events);
        }

        List<CapturedEvent> mutations = events.stream()
                .filter(event -> event.type().equals("tool.completed")
                        || event.type().equals("task.board.snapshot"))
                .toList();
        assertThat(mutations).extracting(CapturedEvent::type).containsExactly(
                "tool.completed", "task.board.snapshot", "tool.completed", "task.board.snapshot");
        assertThat(mutations).filteredOn(event -> event.type().equals("task.board.snapshot"))
                .allSatisfy(event -> {
                    assertThat(event.sessionId()).isPresent();
                    assertThat(event.runId()).isPresent();
                    assertThat(event.payload().properties().stream().map(java.util.Map.Entry::getKey).toList())
                            .containsExactlyInAnyOrder("boardRevision", "totalTasks", "truncated", "tasks");
                });
        assertThat(mutations.get(1).payload().get("boardRevision").longValue()).isEqualTo(1);
        assertThat(mutations.get(1).payload().get("tasks").get(0).get("status").stringValue())
                .isEqualTo("PENDING");
        assertThat(mutations.get(3).payload().get("boardRevision").longValue()).isEqualTo(2);
        assertThat(mutations.get(3).payload().get("tasks").get(0).get("status").stringValue())
                .isEqualTo("COMPLETED");
    }

    @Test
    void failedTaskMutationDoesNotPushTaskBoardSnapshot() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, requestId, sessionId, runId, payload.deepCopy()));
        HeadlessRuntimeOptions options = testOptions();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> calls.getAndIncrement() == 0
                ? ModelTurn.tools(List.of(new ToolCall("missing-task", "task_update",
                        new JsonObject(java.util.Map.of(
                                "task_id", "task-99", "status", "COMPLETED")))))
                : ModelTurn.text("done");

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler((eventSink, approvals) ->
                io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                        model, eventSink, options, approvals))) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"),
                    emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"更新不存在任务\"}}").formatted(sessionId)), emitter);
            awaitTerminal(events);
        }

        assertThat(events).anyMatch(event -> event.type().equals("tool.failed")
                && event.payload().get("toolName").stringValue().equals("task_update"));
        assertThat(events).noneMatch(event -> event.type().equals("task.board.snapshot"));
    }

    @Test
    void continuousPlanQuestionAndReviewUseSafeCorrelatedStdioEventsWithoutJsonLeak() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger calls = new AtomicInteger();
        String markdown = "# Plan\n\nUse the selected rollout.\n";
        String digest = io.github.liumaishenjian.ccjava.domain.PlanArtifact.digest(markdown);
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request ->
                switch (calls.getAndIncrement()) {
                    case 0 -> ModelTurn.tools(List.of(new ToolCall("ask-stdio", "ask_plan_question",
                            new JsonObject(java.util.Map.of("question", "Choose rollout", "options", List.of(
                                    java.util.Map.of("optionId", "safe", "label", "Safe", "description", "Staged"),
                                    java.util.Map.of("optionId", "fast", "label", "Fast", "description", "Direct")))))));
                    case 1 -> ModelTurn.tools(List.of(new ToolCall("update-stdio", "revise_plan_artifact",
                            new JsonObject(java.util.Map.of("markdown", markdown)))));
                    case 2 -> ModelTurn.tools(List.of(new ToolCall("review-stdio", "request_plan_review",
                            JsonObject.empty())));
                    default -> ModelTurn.text("{\"internal\":\"must-not-leak\"}");
                }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"设计安全登录流程\"}}").formatted(sessionId)), emitter);
            CapturedEvent question = awaitEvent(events, "question.requested");
            assertThat(question.payload().toString()).contains("ask-stdio", "Choose rollout", "safe", "fast")
                    .doesNotContain("ask_plan_question", "expectedDigest", "objective", "title", "detail");
            String runId = question.runId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"question.resolve\","
                    + "\"requestId\":\"answer\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"callId\":\"ask-stdio\",\"optionId\":\"safe\"}}")
                    .formatted(sessionId, runId)), emitter);
            CapturedEvent review = awaitEvent(events, "plan.review.requested");
            awaitTerminal(events);
            assertThat(review.payload().toString()).contains("# Plan", "awaiting_approval", digest)
                    .doesNotContain("objective", "title", "detail", "expectedDigest");
            assertThat(events).noneMatch(event -> event.type().equals("model.text.delta"));
            CapturedEvent terminal = events.stream().filter(event -> event.type().equals("run.completed"))
                    .findFirst().orElseThrow();
            assertThat(terminal.payload().toString()).doesNotContain("internal", "must-not-leak", "finalText");
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"question.resolve\","
                    + "\"requestId\":\"late\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":4,\"payload\":{\"callId\":\"ask-stdio\",\"optionId\":\"safe\"}}")
                    .formatted(sessionId, runId)), emitter)).isInstanceOf(StdioProtocolException.class);
        }
    }

    @Test
    void durableReviewResolveIsOneCommandAndStartsRealExecutionWithoutLegacyExecute() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger calls = new AtomicInteger();
        String markdown = "# Plan\n\n## 拟定步骤\n1. 执行已批准的中文步骤。\n";
        String digest = io.github.liumaishenjian.ccjava.domain.PlanArtifact.digest(markdown);
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request ->
                switch (calls.getAndIncrement()) {
                    case 0 -> ModelTurn.tools(List.of(new ToolCall("update-review", "revise_plan_artifact",
                            new JsonObject(java.util.Map.of("markdown", markdown)))));
                    case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                            JsonObject.empty())));
                    case 2 -> ModelTurn.text("plan complete");
                    default -> ModelTurn.text("execution complete");
                }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"plan\"}}").formatted(sessionId)), emitter);
            CapturedEvent review = awaitEvent(events, "plan.review.requested");
            awaitTerminal(events);
            String planId = review.payload().get("planId").stringValue();
            long revision = review.payload().get("revision").longValue();
            String workspaceDigest = review.payload().get("workspaceDigest").stringValue();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.review.resolve\","
                    + "\"requestId\":\"decision\",\"sessionId\":\"%s\",\"sequence\":3,"
                    + "\"payload\":{\"planId\":\"%s\",\"revision\":%d,\"contentDigest\":\"%s\","
                    + "\"workspaceDigest\":\"%s\",\"decision\":\"APPROVE_USER\","
                    + "\"contextPolicy\":\"KEEP\",\"feedback\":\"\"}}").formatted(
                            sessionId, planId, revision, digest, workspaceDigest)), emitter);
            CapturedEvent accepted = awaitEvent(events, "plan.execution.accepted");
            assertThat(accepted.payload().get("approvalReviewer").stringValue()).isEqualTo("user");
            awaitEvent(events, "run.started");
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && events.stream()
                    .filter(event -> event.type().equals("run.completed")).count() < 2L) {
                Thread.sleep(10);
            }
            assertThat(events.stream().filter(event -> event.type().equals("run.completed")).count())
                    .isGreaterThanOrEqualTo(2L);
            int acceptedIndex = java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).type().equals("plan.execution.accepted"))
                    .findFirst().orElseThrow();
            int executionStartedIndex = java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).type().equals("run.started"))
                    .reduce((first, second) -> second).orElseThrow();
            assertThat(acceptedIndex).isLessThan(executionStartedIndex);
            assertThat(events).noneMatch(event -> event.type().equals("plan.proposed"));
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(("{\"version\":0,"
                    + "\"type\":\"plan.execute\",\"requestId\":\"legacy\",\"sessionId\":\"%s\","
                    + "\"sequence\":4,\"payload\":{\"planId\":\"%s\",\"workspaceDigest\":\"%s\"}}")
                    .formatted(sessionId, planId, workspaceDigest)), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        }
    }

    @Test
    void evidenceCorrectionIsObservableAndWithholdsUnverifiedFinalTextUntilTerminalDecision() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger calls = new AtomicInteger();
        String markdown = "# Plan\n\nCreate the exact deliverable.\n";
        String digest = io.github.liumaishenjian.ccjava.domain.PlanArtifact.digest(markdown);
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request ->
                switch (calls.getAndIncrement()) {
                    case 0 -> ModelTurn.tools(List.of(new ToolCall("update-correction", "revise_plan_artifact",
                            new JsonObject(java.util.Map.of("markdown", markdown)))));
                    case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence-correction", "declare_plan_evidence",
                            new JsonObject(java.util.Map.of("requirementId", "exact-file", "kind", "DELIVERABLE",
                                    "locator", "exact.txt", "label", "exact file", "required", true)))));
                    case 2 -> ModelTurn.tools(List.of(new ToolCall("review-correction", "request_plan_review",
                            JsonObject.empty())));
                    case 3 -> ModelTurn.text("plan ready");
                    case 4 -> ModelTurn.text("FIRST_UNVERIFIED_FINAL");
                    default -> ModelTurn.text("SECOND_UNVERIFIED_FINAL");
                }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"plan\"}}").formatted(sessionId)), emitter);
            CapturedEvent review = awaitEvent(events, "plan.review.requested");
            awaitTerminal(events);
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.review.resolve\","
                    + "\"requestId\":\"decision\",\"sessionId\":\"%s\",\"sequence\":3,"
                    + "\"payload\":{\"planId\":\"%s\",\"revision\":%d,\"contentDigest\":\"%s\","
                    + "\"workspaceDigest\":\"%s\",\"decision\":\"APPROVE_USER\","
                    + "\"contextPolicy\":\"KEEP\",\"feedback\":\"\"}}").formatted(
                            sessionId,
                            review.payload().get("planId").stringValue(),
                            review.payload().get("revision").longValue(),
                            digest,
                            review.payload().get("workspaceDigest").stringValue())), emitter);

            CapturedEvent correction = awaitEvent(events, "plan.verification.correction");
            CapturedEvent required = awaitEvent(events, "plan.verification.required");
            awaitTerminalCount(events, 2);
            CapturedEvent executionTerminal = events.stream().filter(event -> event.type().equals("run.completed"))
                    .reduce((first, second) -> second).orElseThrow();

            assertThat(correction.payload().toString()).isEqualTo("{\"attempt\":1,\"maxAttempts\":2,"
                    + "\"incompleteTaskCount\":0,\"incompleteTaskIds\":[],"
                    + "\"failures\":[{\"requirementId\":\"exact-file\",\"kind\":\"deliverable\","
                    + "\"locator\":\"exact.txt\",\"reason\":\"FILE_MISSING_OR_UNSAFE\"}]}");
            assertThat(required.payload().toString()).contains("\"status\":\"needs_verification\"", "\"requiredEvidence\":1", "\"satisfiedEvidence\":0");
            assertThat(executionTerminal.payload().toString())
                    .doesNotContain("finalText", "FIRST_UNVERIFIED_FINAL", "SECOND_UNVERIFIED_FINAL");
            assertThat(events).noneMatch(event -> event.type().equals("model.text.delta")
                    && (event.payload().toString().contains("FIRST_UNVERIFIED_FINAL")
                            || event.payload().toString().contains("SECOND_UNVERIFIED_FINAL")));
            int requiredIndex = events.indexOf(required);
            int terminalIndex = events.indexOf(executionTerminal);
            assertThat(requiredIndex).isLessThan(terminalIndex);
            assertThat(events).filteredOn(event -> event.type().equals("plan.verification.correction")).hasSize(1);
            assertThat(calls).hasValue(6);
        }
    }

    @Test
    void acceptedPlanModelFailureIsNotProjectedAsVerificationRequired() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger calls = new AtomicInteger();
        String markdown = "# Plan\n\nFail safely during execution.\n";
        String digest = io.github.liumaishenjian.ccjava.domain.PlanArtifact.digest(markdown);
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request ->
                switch (calls.getAndIncrement()) {
                    case 0 -> ModelTurn.tools(List.of(new ToolCall("update-failure", "revise_plan_artifact",
                            new JsonObject(java.util.Map.of("markdown", markdown)))));
                    case 1 -> ModelTurn.tools(List.of(new ToolCall("review-failure", "request_plan_review",
                            JsonObject.empty())));
                    case 2 -> ModelTurn.text("plan ready");
                    default -> throw new io.github.liumaishenjian.ccjava.core.ModelGatewayException(
                            io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.PERMANENT,
                            "SECRET_PROVIDER_BODY",
                            new io.github.liumaishenjian.ccjava.domain.ModelFailureSummary(
                                    io.github.liumaishenjian.ccjava.domain.ModelFailureCategory.NETWORK_ERROR,
                                    Optional.empty(), 1, false));
                }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"plan\"}}").formatted(sessionId)), emitter);
            CapturedEvent review = awaitEvent(events, "plan.review.requested");
            awaitTerminal(events);
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.review.resolve\","
                    + "\"requestId\":\"decision\",\"sessionId\":\"%s\",\"sequence\":3,"
                    + "\"payload\":{\"planId\":\"%s\",\"revision\":%d,\"contentDigest\":\"%s\","
                    + "\"workspaceDigest\":\"%s\",\"decision\":\"APPROVE_USER\","
                    + "\"contextPolicy\":\"KEEP\",\"feedback\":\"\"}}").formatted(
                            sessionId,
                            review.payload().get("planId").stringValue(),
                            review.payload().get("revision").longValue(),
                            digest,
                            review.payload().get("workspaceDigest").stringValue())), emitter);

            CapturedEvent failed = awaitEvent(events, "plan.execution.failed");
            assertThat(failed.payload().get("status").stringValue()).isEqualTo("failed");
            assertThat(failed.payload().get("stopReason").stringValue()).isEqualTo("model_error");
            assertThat(failed.payload().get("modelFailure").toString())
                    .contains("network_error", "\"attempts\":1")
                    .doesNotContain("SECRET_PROVIDER_BODY");
            assertThat(events).noneMatch(event -> event.type().equals("plan.verification.required"));
            assertThat(calls).hasValue(4);
        }
    }

    @Test
    void approvedPlanWorkerWaitsUntilAcceptedEventReturns() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger planningCalls = new AtomicInteger();
        AtomicInteger executionCalls = new AtomicInteger();
        String markdown = "# Plan\n\nVerify accepted handoff.\n";
        CountDownLatch acceptedEntered = new CountDownLatch(1);
        CountDownLatch releaseAccepted = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            if (type.equals("plan.execution.accepted")) {
                acceptedEntered.countDown();
                try {
                    if (!releaseAccepted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("测试未释放 accepted 事件");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        };
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            boolean executing = request.messages().stream()
                    .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                    .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                    .anyMatch(message -> message.content().contains("Implement the approved plan"));
            if (executing) {
                executionCalls.incrementAndGet();
                return ModelTurn.text("execution complete");
            }
            return switch (planningCalls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("update", "revise_plan_artifact",
                        new JsonObject(java.util.Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                        JsonObject.empty())));
                default -> ModelTurn.text("planning complete");
            };
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"plan\"}}").formatted(sessionId)), emitter);
            CapturedEvent review = awaitEvent(events, "plan.review.requested");
            awaitTerminal(events);
            long completedBeforeExecution = events.stream()
                    .filter(event -> event.type().equals("run.completed")).count();
            ObjectNode payload = review.payload();
            StdioProtocol.Command approve = codec.decodeCommand(("{\"version\":0,"
                    + "\"type\":\"plan.review.resolve\",\"requestId\":\"decision\","
                    + "\"sessionId\":\"%s\",\"sequence\":3,\"payload\":{"
                    + "\"planId\":\"%s\",\"revision\":%d,\"contentDigest\":\"%s\","
                    + "\"workspaceDigest\":\"%s\",\"decision\":\"APPROVE_USER\","
                    + "\"contextPolicy\":\"KEEP\",\"feedback\":\"\"}}")
                    .formatted(sessionId, payload.get("planId").stringValue(),
                            payload.get("revision").longValue(), payload.get("contentDigest").stringValue(),
                            payload.get("workspaceDigest").stringValue()));
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread commandThread = Thread.ofPlatform().start(() -> {
                try {
                    handler.handle(approve, emitter);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            assertThat(acceptedEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executionCalls).hasValue(0);
            assertThat(events.stream().filter(event -> event.type().equals("run.completed")).count())
                    .isEqualTo(completedBeforeExecution);
            releaseAccepted.countDown();
            commandThread.join(2_000);
            assertThat(commandThread.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            awaitEvent(events, "run.started");
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && events.stream()
                    .filter(event -> event.type().equals("run.completed")).count() == completedBeforeExecution) {
                Thread.sleep(10);
            }
            assertThat(executionCalls).hasValue(1);
            int acceptedIndex = java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).type().equals("plan.execution.accepted"))
                    .findFirst().orElseThrow();
            int executionStartedIndex = java.util.stream.IntStream.range(acceptedIndex + 1, events.size())
                    .filter(index -> events.get(index).type().equals("run.started"))
                    .findFirst().orElseThrow();
            assertThat(acceptedIndex).isLessThan(executionStartedIndex);
        } finally {
            releaseAccepted.countDown();
        }
    }

    @Test
    void acceptedEmissionFailureAbortsWorkerAndReleasesExecutorWait() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger planningCalls = new AtomicInteger();
        AtomicInteger executionCalls = new AtomicInteger();
        String markdown = "# Plan\n\nFail accepted transport.\n";
        RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            boolean executing = request.messages().stream()
                    .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                    .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                    .anyMatch(message -> message.content().contains("Implement the approved plan"));
            if (executing) {
                executionCalls.incrementAndGet();
                return ModelTurn.text("must not execute");
            }
            return switch (planningCalls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("update", "revise_plan_artifact",
                        new JsonObject(java.util.Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                        JsonObject.empty())));
                default -> ModelTurn.text("planning complete");
            };
        }, testOptions());
        StdioProtocol.EventEmitter collecting = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), collecting);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"plan\"}}").formatted(sessionId)), collecting);
            CapturedEvent review = awaitEvent(events, "plan.review.requested");
            awaitTerminal(events);
            ObjectNode payload = review.payload();
            StdioProtocol.EventEmitter failing = (type, requestId, eventSessionId, runId, eventPayload) -> {
                if (type.equals("plan.execution.accepted")) {
                    throw new IllegalStateException("transport closed");
                }
                events.add(new CapturedEvent(type, eventSessionId, runId, eventPayload.deepCopy()));
            };
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(("{\"version\":0,"
                    + "\"type\":\"plan.review.resolve\",\"requestId\":\"decision\","
                    + "\"sessionId\":\"%s\",\"sequence\":3,\"payload\":{"
                    + "\"planId\":\"%s\",\"revision\":%d,\"contentDigest\":\"%s\","
                    + "\"workspaceDigest\":\"%s\",\"decision\":\"APPROVE_USER\","
                    + "\"contextPolicy\":\"KEEP\",\"feedback\":\"\"}}")
                    .formatted(sessionId, payload.get("planId").stringValue(),
                            payload.get("revision").longValue(), payload.get("contentDigest").stringValue(),
                            payload.get("workspaceDigest").stringValue())), failing))
                    .isInstanceOf(RuntimeStdioCommandHandler.AcceptedRunTransportException.class);
        } finally {
            handler.close();
        }
        assertThat(executionCalls).hasValue(0);
        assertThat(events).noneMatch(event -> event.type().equals("plan.execution.accepted"));
    }

    @Test
    void terminalContainsProviderUsageAndPrivacySafeTimingProjection()
            throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> new ModelTurn(
                        AssistantMessage.text("COMPLETION_SENTINEL"),
                        new ModelTurnMetadata(
                                ModelFinishReason.STOP,
                                Optional.of(new ModelUsage(12, 3, 15)),
                                Optional.of("MODEL_SENTINEL"))),
                testOptions())) {
            handler.handle(
                    codec.decodeCommand(
                            "{\"version\":0,\"type\":\"initialize\","
                                    + "\"requestId\":\"req-1\",\"sequence\":1,\"payload\":{}}"),
                    emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(
                    codec.decodeCommand(
                            ("{\"version\":0,\"type\":\"run.start\","
                                    + "\"requestId\":\"req-2\",\"sessionId\":\"%s\","
                                    + "\"sequence\":2,"
                                    + "\"payload\":{\"prompt\":\"PROMPT_SENTINEL\"}}")
                                    .formatted(sessionId)),
                    emitter);

            CapturedEvent terminal = awaitTerminal(events);
            ObjectNode telemetry = (ObjectNode) terminal.payload().get("telemetry");
            assertThat(telemetry).isNotNull();
            assertThat(telemetry.get("elapsedMillis").longValue()).isGreaterThanOrEqualTo(0);
            assertThat(telemetry.get("usageReportedTurns").intValue()).isOne();
            assertThat(telemetry.get("usageMissingTurns").intValue()).isZero();
            assertThat(telemetry.get("modelTurns").size()).isOne();
            assertThat(telemetry.get("toolCalls").isEmpty()).isTrue();
            assertThat(telemetry.get("totalUsage").get("inputTokens").longValue())
                    .isEqualTo(12);
            assertThat(telemetry.get("totalUsage").get("outputTokens").longValue())
                    .isEqualTo(3);
            assertThat(telemetry.get("totalUsage").get("totalTokens").longValue())
                    .isEqualTo(15);
            assertThat(telemetry.toString())
                    .doesNotContain(
                            "PROMPT_SENTINEL",
                            "COMPLETION_SENTINEL",
                            "MODEL_SENTINEL",
                            "finalText",
                            "apiKey",
                            "baseUrl");
            assertThat(terminal.payload().get("finalText").stringValue())
                    .isEqualTo("COMPLETION_SENTINEL");
            CapturedEvent turnStarted = events.stream()
                    .filter(event -> event.type().equals("model.turn.started"))
                    .findFirst().orElseThrow();
            CapturedEvent turnCompleted = events.stream()
                    .filter(event -> event.type().equals("model.turn.completed"))
                    .findFirst().orElseThrow();
            assertThat(turnStarted.payload().toString()).isEqualTo("{\"turn\":1}");
            assertThat(turnCompleted.payload().toString())
                    .contains(
                            "\"turn\":1",
                            "\"finishReason\":\"stop\"",
                            "\"inputTokens\":12",
                            "\"outputTokens\":3",
                            "\"totalTokens\":15")
                    .doesNotContain("PROMPT_SENTINEL", "COMPLETION_SENTINEL", "MODEL_SENTINEL");
        }
    }

    @Test
    void terminalProjectsOnlyWhitelistedModelFailureFields() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        ModelFailureSummary summary = new ModelFailureSummary(
                ModelFailureCategory.PROVIDER_UNAVAILABLE,
                Optional.of(ModelHttpStatusClass.SERVER_ERROR),
                1,
                false);

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.RETRYABLE,
                    "SECRET_PROVIDER_RESPONSE https://secret.invalid sk-secret",
                    summary);
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"PROMPT_SECRET\"}}").formatted(sessionId)), emitter);

            CapturedEvent terminal = awaitAnyTerminal(events);
            assertThat(terminal.type()).isEqualTo("run.failed");
            assertThat(terminal.payload().toString())
                    .contains(
                            "\"category\":\"provider_unavailable\"",
                            "\"statusClass\":\"5xx\"",
                            "\"attempts\":1",
                            "\"receivedOutput\":false")
                    .doesNotContain(
                            "SECRET_PROVIDER_RESPONSE",
                            "secret.invalid",
                            "sk-secret",
                            "PROMPT_SECRET");
        }
    }

    @Test
    void projectsBoundedToolActivityWithoutArgumentsOrContentBodies() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (turns.incrementAndGet() == 1) {
                return new ModelTurn(
                        AssistantMessage.tools(List.of(new ToolCall(
                                "call-1", "read_file",
                                new JsonObject(java.util.Map.of("path", "MISSING_SECRET_PATH"))))),
                        ModelTurnMetadata.unknown());
            }
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"PROMPT_SECRET\"}}").formatted(sessionId)), emitter);
            awaitTerminal(events);
        }

        CapturedEvent started = events.stream()
                .filter(event -> event.type().equals("tool.started"))
                .findFirst().orElseThrow();
        CapturedEvent failed = events.stream()
                .filter(event -> event.type().equals("tool.failed"))
                .findFirst().orElseThrow();
        assertThat(started.payload().toString())
                .contains("read_file", "ordinal", "\"activity\":\"读取 MISSING_SECRET_PATH\"")
                .doesNotContain("PROMPT_SECRET", "arguments", "content");
        assertThat(failed.payload().toString())
                .contains(
                        "sensitive_path",
                        "\"failureCategory\":\"validation\"",
                        "\"retryable\":false",
                        "returnedCharacters",
                        "\"returnedItems\":0",
                        "\"truncationReason\":\"none\"")
                .doesNotContain("MISSING_SECRET_PATH", "PROMPT_SECRET", "arguments", "content");
    }

    @Test
    void projectsOnlyWhitelistedArgumentCorrectionActions() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (turns.incrementAndGet() == 1) {
                return ModelTurn.tools(List.of(new ToolCall(
                        "call-invalid", "search_text",
                        new JsonObject(java.util.Map.of(
                                "query", "PRIVATE_QUERY", "limit", 1, "maxResults", 1)))));
            }
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"PROMPT_SECRET\"}}").formatted(sessionId)), emitter);
            awaitTerminal(events);
        }

        CapturedEvent failed = events.stream()
                .filter(event -> event.type().equals("tool.failed"))
                .findFirst().orElseThrow();
        assertThat(failed.payload().toString())
                .contains("\"argumentChangeRequired\":true", "invalid_arguments", "validation")
                .doesNotContain("PRIVATE_QUERY", "PROMPT_SECRET", "violations", "preferredField", "removeFields");
    }

    @Test
    void commandExitCodeUsesOnlyStructuredRunCommandFacts() {
        var success = io.github.liumaishenjian.ccjava.domain.ToolResult.success(
                "call-success", "run_command", "ok");
        var failed = io.github.liumaishenjian.ccjava.domain.ToolResult.failure(
                "call-failed",
                "run_command",
                io.github.liumaishenjian.ccjava.domain.ToolError.classified(
                        io.github.liumaishenjian.ccjava.domain.ToolErrorCode.PROCESS_EXIT,
                        io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.PROCESS_EXIT,
                        false,
                        "命令失败",
                        new JsonObject(java.util.Map.of("exitCode", 9))));
        var unknown = io.github.liumaishenjian.ccjava.domain.ToolResult.failure(
                "call-unknown",
                "run_command",
                io.github.liumaishenjian.ccjava.domain.ToolError.classified(
                        io.github.liumaishenjian.ccjava.domain.ToolErrorCode.OPERATION_TIMED_OUT,
                        io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.TIMEOUT,
                        false,
                        "命令超时",
                        new JsonObject(java.util.Map.of("exitCode", -1))));
        var denied = io.github.liumaishenjian.ccjava.domain.ToolResult.denied(
                "call-denied", "run_command", "未执行");
        var other = io.github.liumaishenjian.ccjava.domain.ToolResult.success(
                "call-read", "read_file", "exitCode: 7");

        assertThat(RuntimeStdioCommandHandler.safeCommandExitCode(success)).contains(0);
        assertThat(RuntimeStdioCommandHandler.safeCommandExitCode(failed)).contains(9);
        assertThat(RuntimeStdioCommandHandler.safeCommandExitCode(unknown)).isEmpty();
        assertThat(RuntimeStdioCommandHandler.safeCommandExitCode(denied)).isEmpty();
        assertThat(RuntimeStdioCommandHandler.safeCommandExitCode(other)).isEmpty();
    }

    @Test
    void toolActivityUsesOnlyWhitelistedBoundedFieldsAndHidesAbsoluteTargets() {
        ToolCall patch = new ToolCall(
                "call-patch",
                "apply_patch",
                new JsonObject(java.util.Map.of(
                        "path", "src/App.java",
                        "oldText", "OLD_BODY_SECRET",
                        "newText", "NEW_BODY_SECRET")));
        ToolCall absolute = new ToolCall(
                "call-absolute",
                "read_file",
                new JsonObject(java.util.Map.of("path", "C:\\Users\\private\\secret.txt")));
        ToolCall external = new ToolCall(
                "call-external",
                "external_tool",
                new JsonObject(java.util.Map.of("query", "PRIVATE_QUERY")));
        ToolCall traversal = new ToolCall(
                "call-traversal",
                "search_text",
                new JsonObject(java.util.Map.of("query", "needle", "path", "../private")));

        assertThat(RuntimeStdioCommandHandler.safeToolActivity(patch))
                .contains("修改 src/App.java")
                .get().asString()
                .doesNotContain("OLD_BODY_SECRET", "NEW_BODY_SECRET");
        assertThat(RuntimeStdioCommandHandler.safeToolActivity(absolute))
                .contains("读取 工作区目标")
                .get().asString()
                .doesNotContain("Users", "private", "secret.txt");
        assertThat(RuntimeStdioCommandHandler.safeToolActivity(external)).isEmpty();
        assertThat(RuntimeStdioCommandHandler.safeToolActivity(traversal))
                .contains("搜索 “needle” · 工作区目标")
                .get().asString().doesNotContain("../private");
    }

    @Test
    void extractsOnlyFixedSearchModeForPresentation() {
        ToolCall files = new ToolCall(
                "call-files",
                "search_text",
                new JsonObject(java.util.Map.of(
                        "query", "PRIVATE_QUERY",
                        "path", "PRIVATE_PATH",
                        "mode", "files")));
        ToolCall defaults = new ToolCall(
                "call-content",
                "search_text",
                new JsonObject(java.util.Map.of("query", "PRIVATE_QUERY")));
        ToolCall invalid = new ToolCall(
                "call-invalid",
                "search_text",
                new JsonObject(java.util.Map.of("query", "PRIVATE_QUERY", "mode", "raw")));

        assertThat(RuntimeStdioCommandHandler.safeToolMode(files)).contains("files");
        assertThat(RuntimeStdioCommandHandler.safeToolMode(defaults)).contains("content");
        assertThat(RuntimeStdioCommandHandler.safeToolMode(invalid)).isEmpty();
        assertThat(RuntimeStdioCommandHandler.safeToolMode(new ToolCall(
                "call-read",
                "read_file",
                new JsonObject(java.util.Map.of("path", "PRIVATE_PATH"))))).isEmpty();
    }

    @Test
    void approvalEventShowsSafePatchSummaryAndMatchingAllowWritesFile()
            throws Exception {
        Path file = Files.writeString(workspace().resolve("sample.txt"), "old\n");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> switch (turns.incrementAndGet()) {
                    case 1 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-read", "read_file",
                                    new JsonObject(java.util.Map.of("path", "sample.txt"))))),
                            ModelTurnMetadata.unknown());
                    case 2 -> new ModelTurn(
                                AssistantMessage.tools(List.of(new ToolCall(
                                        "call-patch",
                                        "apply_patch",
                                        new JsonObject(java.util.Map.of(
                                                "path", "sample.txt",
                                                "oldText", "old",
                                                "newText", "new"))))),
                                ModelTurnMetadata.unknown());
                    default -> ModelTurn.text("done");
                },
                testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"patch\"}}").formatted(sessionId)), emitter);

            CapturedEvent approval = awaitEvent(events, "approval.requested");
            assertThat(approval.payload().toString())
                    .contains(
                            "\"target\":\"sample.txt\"",
                            "\"operation\":\"modify\"",
                            "\"removedLines\":1",
                            "\"addedLines\":1")
                    .doesNotContain("\"oldText\"", "\"newText\"", "\"arguments\"");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_once\"}}")
                    .formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);

            awaitTerminal(events);
        }

        assertThat(Files.readString(file)).isEqualTo("new\n");
    }

    @Test
    void allowSessionSkipsSecondApprovalForSameScope() throws Exception {
        Path file = Files.writeString(workspace().resolve("sample.txt"), "old\n");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> switch (turns.incrementAndGet()) {
                    case 1 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-read", "read_file",
                                    new JsonObject(java.util.Map.of("path", "sample.txt"))))),
                            ModelTurnMetadata.unknown());
                    case 2 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-patch-1",
                                    "apply_patch",
                                    new JsonObject(java.util.Map.of(
                                            "path", "sample.txt",
                                            "oldText", "old",
                                            "newText", "middle"))))),
                            ModelTurnMetadata.unknown());
                    case 3 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-patch-2",
                                    "apply_patch",
                                    new JsonObject(java.util.Map.of(
                                            "path", "sample.txt",
                                            "oldText", "middle",
                                            "newText", "new"))))),
                            ModelTurnMetadata.unknown());
                    default -> ModelTurn.text("done");
                },
                testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"patch twice\"}}")
                    .formatted(sessionId)), emitter);

            CapturedEvent approval = awaitEvent(events, "approval.requested");
            assertThat(approval.payload().get("sessionScope").booleanValue()).isTrue();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_session\"}}")
                    .formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);

            awaitTerminal(events);
        }

        assertThat(Files.readString(file)).isEqualTo("new\n");
        assertThat(events).filteredOn(event -> event.type().equals("approval.requested"))
                .hasSize(1);
    }

    @Test
    void exposesCheckpointListDiffAndExplicitUndoThroughStdio() throws Exception {
        Path file = Files.writeString(workspace().resolve("sample.txt"), "old\n");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> switch (turns.incrementAndGet()) {
                    case 1 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-read", "read_file",
                                    new JsonObject(java.util.Map.of("path", "sample.txt"))))),
                            ModelTurnMetadata.unknown());
                    case 2 -> new ModelTurn(
                                AssistantMessage.tools(List.of(new ToolCall(
                                        "call-patch",
                                        "apply_patch",
                                        new JsonObject(java.util.Map.of(
                                                "path", "sample.txt",
                                                "oldText", "old",
                                                "newText", "new"))))),
                                ModelTurnMetadata.unknown());
                    default -> ModelTurn.text("done");
                },
                testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"patch\"}}").formatted(sessionId)), emitter);
            CapturedEvent approval = awaitEvent(events, "approval.requested");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_once\"}}").formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);
            awaitTerminal(events);

            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"checkpoint.list\","
                    + "\"requestId\":\"list\",\"sessionId\":\"%s\",\"sequence\":4,"
                    + "\"payload\":{}}").formatted(sessionId)), emitter);
            CapturedEvent listed = awaitEvent(events, "checkpoint.listed");
            String checkpointId = listed.payload()
                    .get("checkpoints").get(0).get("checkpointId").stringValue();
            assertThat(listed.payload().toString())
                    .contains("completed_present", "\"undoable\":true", "sample.txt")
                    .doesNotContain(workspace().toString(), temporaryRoot.resolve("sessions").toString());

            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"checkpoint.diff\","
                    + "\"requestId\":\"diff\",\"sessionId\":\"%s\",\"sequence\":5,"
                    + "\"payload\":{\"checkpointId\":\"%s\"}}").formatted(
                            sessionId, checkpointId)), emitter);
            CapturedEvent diffed = awaitEvent(events, "checkpoint.diffed");
            assertThat(diffed.payload().toString())
                    .contains("changed", "checkpoint/sample.txt", "workspace/sample.txt");

            assertThatThrownBy(() -> handler.handle(codec.decodeCommand((
                    "{\"version\":0,\"type\":\"checkpoint.undo\","
                            + "\"requestId\":\"undo-denied\",\"sessionId\":\"%s\",\"sequence\":6,"
                            + "\"payload\":{\"checkpointId\":\"%s\",\"confirmed\":false}}")
                            .formatted(sessionId, checkpointId)), emitter))
                    .isInstanceOf(io.github.liumaishenjian.ccjava.cli.session.SessionOpenException.class);
            assertThat(Files.readString(file)).isEqualTo("new\n");

            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"checkpoint.undo\","
                    + "\"requestId\":\"undo\",\"sessionId\":\"%s\",\"sequence\":7,"
                    + "\"payload\":{\"checkpointId\":\"%s\",\"confirmed\":true}}")
                    .formatted(sessionId, checkpointId)), emitter);
            CapturedEvent undone = awaitEvent(events, "checkpoint.undone");
            assertThat(undone.payload().toString()).contains("restored", "sample.txt");
            assertThat(Files.readString(file)).isEqualTo("old\n");
        }
    }

    @Test
    void commandApprovalShowsExactExecutionAndStreamsOutput() throws Exception {
        String command = CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                ? "Write-Output 'command-stream'; Set-Content -Path command.txt -Value ok"
                : "printf 'command-stream\\n'; printf 'ok\\n' > command.txt";
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminalReceived = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
            if (type.equals("run.completed") || type.equals("run.failed") || type.equals("run.cancelled")) {
                terminalReceived.countDown();
            }
        };
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> turns.incrementAndGet() == 1
                        ? new ModelTurn(
                                AssistantMessage.tools(List.of(new ToolCall(
                                        "call-command",
                                        "run_command",
                                        new JsonObject(java.util.Map.of(
                                                "command", command,
                                                "timeoutSeconds", 5))))),
                                ModelTurnMetadata.unknown())
                        : ModelTurn.text("done"),
                testOptions(Duration.ofSeconds(10)))) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"run verification\"}}")
                    .formatted(sessionId)), emitter);

            CapturedEvent approval = awaitEvent(events, "approval.requested");
            assertThat(approval.payload().get("command").stringValue()).isEqualTo(command);
            assertThat(approval.payload().toString())
                    .contains("\"operation\":\"execute\"", "\"workingDirectory\":\".\"")
                    .contains("\"shell\":\"" + CommandShell.current().id() + "\"");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_once\"}}")
                    .formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);

            assertThat(terminalReceived.await(15, TimeUnit.SECONDS)).isTrue();
            CapturedEvent output = events.stream()
                    .filter(event -> event.type().equals("tool.output"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "命令完成前未收到 stdout 事件: " + eventDiagnostics(events)));
            assertThat(output.payload().toString())
                    .contains("\"stream\":\"stdout\"", "command-stream")
                    .doesNotContain(workspace().toString());
            awaitTerminal(events);
            CapturedEvent completed = events.stream()
                    .filter(event -> event.type().equals("tool.completed"))
                    .findFirst().orElseThrow();
            assertThat(completed.payload().get("exitCode").intValue()).isZero();
        }

        assertThat(Files.readString(workspace().resolve("command.txt"))).contains("ok");
    }

    @Test
    void blockingInteractiveModelIgnoresFormerTotalDeadlineUntilCancelled() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (calls.incrementAndGet() == 1) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    throw new io.github.liumaishenjian.ccjava.core.ModelGatewayException(
                            io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CANCELLED,
                            "fixed interrupted model");
                }
            }
            return ModelTurn.text("recovered");
        }, testOptions(Duration.ofMillis(80)))) {
            StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                    events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("timeout-request", sessionId, 2, "timeout prompt")), emitter);
            CapturedEvent started = awaitEvent(events, "run.started");
            Thread.sleep(160);
            assertThat(events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal)).isEmpty();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.cancel\",\"requestId\":\"cancel\","
                    + "\"sessionId\":\"%s\",\"runId\":\"%s\",\"sequence\":3,\"payload\":{}}")
                    .formatted(sessionId, started.runId().orElseThrow())), emitter);
            awaitAnyTerminal(events);
            assertThat(events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal).toList())
                    .singleElement().satisfies(terminal -> {
                        assertThat(terminal.type()).isEqualTo("run.cancelled");
                        assertThat(terminal.payload().get("stopReason").stringValue())
                                .isEqualTo("user_cancelled");
                    });

        }
        assertThat(events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal)).hasSize(1);
    }

    @Test
    void queuesSteeringUntilTheCurrentRunHasReachedItsTerminalBoundary() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
                return ModelTurn.text("first");
            }
            return ModelTurn.text("second");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first-request", sessionId, 2, "first prompt")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("steering-request", sessionId, 3, "steering prompt")), emitter);

            assertThat(events).filteredOn(event -> event.type().equals("steering.queued")).hasSize(1);
            assertThat(events).filteredOn(event -> event.type().equals("run.started")).hasSize(1);
            assertThat(events.toString()).doesNotContain("steering prompt");
            releaseFirst.countDown();
            awaitTerminalCount(events, 2);
        }
        List<CapturedEvent> terminals = events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal).toList();
        assertThat(terminals).hasSize(2);
        assertThat(terminals.get(0).payload().get("finalText").stringValue()).isEqualTo("first");
        assertThat(terminals.get(1).payload().get("finalText").stringValue()).isEqualTo("second");
        assertThat(calls).hasValue(2);
    }

    @Test
    void clearDiscardsQueuedSteeringWithoutPersistingItsText() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("first");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first-request", sessionId, 2, "first prompt")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("steering-request", sessionId, 3, "UNSENT_STEERING_SECRET")), emitter);
            try (var paths = Files.walk(temporaryRoot.resolve("sessions"))) {
                assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .map(path -> {
                            try {
                                return Files.readString(path);
                            } catch (java.io.IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }).toList().toString()).doesNotContain("UNSENT_STEERING_SECRET");
            }
            handler.handle(codec.decodeCommand(sessionCommand("clear", sessionId, 4, "clear-steering", "clear", "{}")), emitter);
            releaseFirst.countDown();
            awaitTerminal(events);
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().toString()).contains("\"reason\":\"clear\"");
        assertThat(events.toString()).doesNotContain("UNSENT_STEERING_SECRET");
    }

    @Test
    void cancellationDiscardsQueuedSteering() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("unexpected");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first-request", sessionId, 2, "first prompt")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("steering-request", sessionId, 3, "CANCELLED_STEERING")), emitter);
            handler.handle(codec.decodeCommand(inputBegin(
                    "cancel-input", "logical-cancel", sessionId, 4, 3, 1, sha256("abc"))), emitter);
            CapturedEvent started = awaitEvent(events, "run.started");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.cancel\",\"requestId\":\"cancel\","
                    + "\"sessionId\":\"%s\",\"runId\":\"%s\",\"sequence\":5,\"payload\":{}}")
                    .formatted(sessionId, started.runId().orElseThrow())), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputChunk("cancel-replay", sessionId, 6, "cancel-input", 0, "abc")), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_REPLAY");
                        assertThat(failure.requestId()).isEqualTo("cancel-replay");
                    });
            releaseFirst.countDown();
            awaitAnyTerminal(events);
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().toString()).contains("\"reason\":\"cancelled\"");
        assertThat(events.toString()).doesNotContain("CANCELLED_STEERING");
    }

    @Test
    void sessionCommandResumeSwitchesToCleanCandidateWithOnlySafeIdentifiers() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        io.github.liumaishenjian.ccjava.domain.SessionId candidateId;
        try (io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession candidate =
                     new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                             ignored -> ModelTurn.text("candidate"), io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                             testOptions())) {
            candidateId = candidate.open();
            candidate.run("candidate history");
        }
        String previousId;
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            previousId = events.getFirst().sessionId().orElseThrow();
            String request = "{\"version\":0,\"type\":\"session.command\",\"requestId\":\"resume\",\"sessionId\":\"%s\",\"sequence\":2,\"payload\":{\"protocolVersion\":0,\"commandId\":\"resume-command\",\"intent\":\"resume\",\"arguments\":{\"sessionId\":\"%s\"}}}";
            handler.handle(codec.decodeCommand(request.formatted(previousId, candidateId.value())), emitter);
        }
        CapturedEvent result = events.stream().filter(event -> event.type().equals("session.command.result"))
                .findFirst().orElseThrow();
        assertThat(result.sessionId()).contains(candidateId.value());
        assertThat(result.payload().toString()).contains("succeeded", "ok", "previousSessionId", "resumedSessionId",
                        candidateId.value(), previousId)
                .doesNotContain(temporaryRoot.toString(), "candidate history", "session.jsonl");
    }

    @Test
    void permissionsSelectionUsesTheStdioDispatcherAndSettingsApplicationPath() throws Exception {
        HeadlessRuntimeOptions options = testOptions();
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler((runtimeEvents, approvals) ->
                io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                        ignored -> ModelTurn.text("unused"), runtimeEvents, options, approvals))) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();

            assertPermissionsQuery(handler, codec, events, sessionId, 2, "select-plan",
                    "{\"selection\":\"PLAN\"}", "PLAN", "USER", "PLAN");
            assertPermissionsQuery(handler, codec, events, sessionId, 4, "select-ask",
                    "{\"selection\":\"ASK\"}", "DEFAULT", "USER", "ASK");
            assertPermissionsQuery(handler, codec, events, sessionId, 6, "select-auto",
                    "{\"selection\":\"AUTO\"}", "DEFAULT", "AUTO_REVIEW", "AUTO");
            assertPermissionsQuery(handler, codec, events, sessionId, 8, "legacy-mode",
                    "{\"mode\":\"ACCEPT_EDITS\"}", "ACCEPT_EDITS", "USER", "ADVANCED");
        }
    }

    @Test
    void sessionCommandEmitsOnePrivacySafeTerminalForDuplicateCommandId() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            String request = "{\"version\":0,\"type\":\"session.command\",\"requestId\":\"command\",\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"protocolVersion\":0,\"commandId\":\"same-command\",\"intent\":\"doctor\",\"arguments\":{}}}";
            handler.handle(codec.decodeCommand(request.formatted(sessionId, 2)), emitter);
            handler.handle(codec.decodeCommand(request.formatted(sessionId, 3)), emitter);
        }
        assertThat(events).filteredOn(event -> event.type().equals("session.command.result")).hasSize(1);
        CapturedEvent result = events.stream().filter(event -> event.type().equals("session.command.result")).findFirst().orElseThrow();
        assertThat(result.payload().toString()).contains("same-command", "doctor", "succeeded", "ok")
                .doesNotContain("apiKey", "baseUrl", "prompt", "absolute");
    }

    @Test
    void sessionCommandEmitsOneBudgetTerminalThenShutsDownWithoutTrackingUnlimitedIds() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            String request = "{\"version\":0,\"type\":\"session.command\",\"requestId\":\"command-%d\",\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"protocolVersion\":0,\"commandId\":\"command-%d\",\"intent\":\"doctor\",\"arguments\":{}}}";
            for (int index = 1; index <= 256; index++) {
                assertThat(handler.handle(codec.decodeCommand(request.formatted(index, sessionId, index + 1, index)), emitter))
                        .isEqualTo(StdioProtocol.Disposition.CONTINUE);
            }
            assertThat(handler.handle(codec.decodeCommand(request.formatted(257, sessionId, 258, 257)), emitter))
                    .isEqualTo(StdioProtocol.Disposition.SHUTDOWN);
        }
        var results = events.stream().filter(event -> event.type().equals("session.command.result")).toList();
        assertThat(results).hasSize(257);
        assertThat(results.getLast().payload().toString()).contains("request_budget_exhausted", "command-257");
    }

    @Test
    void sessionCommandRejectsSessionMismatchAndActiveRunWithoutMutation() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"session.command\",\"requestId\":\"bad\",\"sessionId\":\"other\",\"sequence\":2,\"payload\":{\"protocolVersion\":0,\"commandId\":\"bad-command\",\"intent\":\"context\",\"arguments\":{}}}"), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        }
        assertThat(events).filteredOn(event -> event.type().equals("session.command.result")).isEmpty();
    }

    @Test
    void consumesMultipleQueuedSteeringInStrictFifoOrder() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
            }
            return ModelTurn.text("done-" + calls.get());
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("second", sessionId, 3, "second")), emitter);
            handler.handle(codec.decodeCommand(runStart("third", sessionId, 4, "third")), emitter);
            releaseFirst.countDown();
            awaitTerminalCount(events, 3);
        }
        assertThat(events.stream().filter(event -> event.type().equals("run.started"))
                .map(CapturedEvent::runId).toList()).hasSize(3);
        assertThat(events.stream().filter(event -> isTerminal(event))
                .map(event -> event.payload().get("finalText").stringValue()).toList())
                .containsExactly("done-1", "done-2", "done-3");
        assertThat(events.stream().filter(event -> event.type().equals("steering.queued"))
                .map(event -> event.payload().get("queueDepth").intValue()).toList())
                .containsExactly(1, 2);
    }

    @Test
    void rejectsTheOneHundredAndFirstQueuedSteeringWithoutChangingTheQueue() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            for (int index = 1; index <= RuntimeStdioCommandHandler.MAX_STEERING_MESSAGES; index++) {
                handler.handle(codec.decodeCommand(runStart("queued-" + index, sessionId, index + 2, "queued")), emitter);
            }
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("overflow", sessionId, 103, "overflow")), emitter))
                    .isInstanceOf(StdioProtocolException.class)
                    .hasMessageContaining("steering 队列已满");
            handler.handle(codec.decodeCommand(sessionCommand("clear", sessionId, 104, "clear", "clear", "{}")), emitter);
            releaseFirst.countDown();
            awaitTerminal(events);
        }
        assertThat(events).filteredOn(event -> event.type().equals("steering.queued")).hasSize(100);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(100);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded"))
                .allSatisfy(event -> assertThat(event.payload().get("reason").stringValue()).isEqualTo("clear"));
    }

    @Test
    void shutdownAndCloseDiscardQueuedSteeringExactlyOnce() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "queued")), emitter);
            assertThat(handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"shutdown\",\"requestId\":\"stop\",\"sequence\":4,\"payload\":{}}"), emitter))
                    .isEqualTo(StdioProtocol.Disposition.SHUTDOWN);
            releaseFirst.countDown();
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().get("reason").stringValue()).isEqualTo("shutdown");
    }

    @Test
    void closeDiscardsQueuedSteeringExactlyOnce() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions());
        try {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "queued")), emitter);
            releaseFirst.countDown();
            handler.close();
        } finally {
            releaseFirst.countDown();
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().get("reason").stringValue()).isEqualTo("shutdown");
    }

    @Test
    void discardedEmissionFailureStillCancelsRunClearsQueueAndClosesResources() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            if (type.equals("steering.discarded")) {
                throw new IllegalStateException("discard transport closed");
            }
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        };
        RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions());
        try {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "queued")), emitter);
            assertThatThrownBy(handler::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("discard transport closed");
            releaseFirst.countDown();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("later", sessionId, 4, "later")), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        } finally {
            releaseFirst.countDown();
            try {
                handler.close();
            } catch (RuntimeException ignored) {
                // First close has already asserted the transport failure; cleanup remains idempotent.
            }
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("run.started")).hasSize(1);
        assertThat(events.toString()).doesNotContain("later");
    }

    @Test
    void eventEmitterFailureDiscardsUnsentSteeringAndPreventsLaterRuns() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            if (type.equals("steering.queued")) {
                throw new IllegalStateException("transport closed");
            }
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        };
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "UNSENT")), emitter))
                    .isInstanceOf(RuntimeStdioCommandHandler.AcceptedRunTransportException.class)
                    .hasMessageContaining("transport closed");
            releaseFirst.countDown();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("later", sessionId, 4, "later")), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        }
        assertThat(calls).hasValue(1);
        assertThat(events.toString()).doesNotContain("UNSENT");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Fake Model 等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void conflictingBeginTerminatesBothIdsCancelsTimerAndCorrelatesOriginal() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        FakeAssemblyScheduler scheduler = new FakeAssemblyScheduler();
        AtomicInteger calls = new AtomicInteger();
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet(); return ModelTurn.text("unexpected");
        }, testOptions(), Clock.systemUTC(), scheduler)) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(inputBegin("first-id", "logical-first", sessionId, 2, 3, 1, sha256("abc"))), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputBegin("second-id", "logical-second", sessionId, 3, 3, 1, sha256("xyz"))), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_IN_FLIGHT");
                        assertThat(failure.requestId()).isEqualTo("logical-first");
                    });
            assertThat(scheduler.cancelled()).isOne();
            for (String id : List.of("first-id", "second-id")) {
                assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                        inputCommit("replay-" + id, sessionId, id.equals("first-id") ? 4 : 5, id)), emitter))
                        .isInstanceOfSatisfying(StdioProtocolException.class, failure ->
                                assertThat(failure.code()).isEqualTo("INPUT_REPLAY"));
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void mismatchedIdTerminatesBothIdsCancelsTimerAndCorrelatesOriginal() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        FakeAssemblyScheduler scheduler = new FakeAssemblyScheduler();
        AtomicInteger calls = new AtomicInteger();
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet(); return ModelTurn.text("unexpected");
        }, testOptions(), Clock.systemUTC(), scheduler)) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(inputBegin("owned-id", "logical-owned", sessionId, 2, 3, 1, sha256("abc"))), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputChunk("wrong-chunk", sessionId, 3, "foreign-id", 0, "abc")), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_ID_MISMATCH");
                        assertThat(failure.requestId()).isEqualTo("logical-owned");
                    });
            assertThat(scheduler.cancelled()).isOne();
            for (String id : List.of("owned-id", "foreign-id")) {
                assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                        inputCommit("replay-" + id, sessionId, id.equals("owned-id") ? 4 : 5, id)), emitter))
                        .isInstanceOfSatisfying(StdioProtocolException.class, failure ->
                                assertThat(failure.code()).isEqualTo("INPUT_REPLAY"));
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void activeExpiryTombstonesInputAndCorrelatesReplayToLogicalRequest() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        FakeAssemblyScheduler scheduler = new FakeAssemblyScheduler();
        Clock clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC);
        AtomicInteger calls = new AtomicInteger();
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet(); return ModelTurn.text("unexpected");
        }, testOptions(), clock, scheduler)) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(inputBegin("expire", "logical-expire", sessionId, 2, 3, 1, sha256("abc"))), emitter);
            assertThat(scheduler.pending()).isOne();
            scheduler.fireFirst();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputChunk("chunk-request", sessionId, 3, "expire", 0, "abc")), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_REPLAY");
                        assertThat(failure.requestId()).isEqualTo("chunk-request");
                    });
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputBegin("expire", "logical-replay", sessionId, 4, 3, 1, sha256("abc"))), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_REPLAY");
                        assertThat(failure.requestId()).isEqualTo("logical-replay");
                    });
            assertThat(calls).hasValue(0);
            assertThat(events).noneMatch(RuntimeStdioCommandHandlerTest::isTerminal);
        }
    }

    @Test
    void atomicallyCommitsLargeUtf8InputAndRejectsTamperingBeforeRun() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        String text = "中文😀".repeat(20_000);
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> ModelTurn.text(((io.github.liumaishenjian.ccjava.domain.UserMessage)
                        request.messages().getLast()).content()), testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            String first = text.substring(0, text.length() / 2);
            String second = text.substring(text.length() / 2);
            handler.handle(codec.decodeCommand(inputBegin("large", "begin-large", sessionId, 2, bytes.length, 2, digest)), emitter);
            handler.handle(codec.decodeCommand(inputChunk("chunk-0", sessionId, 3, "large", 0, first)), emitter);
            handler.handle(codec.decodeCommand(inputChunk("chunk-1", sessionId, 4, "large", 1, second)), emitter);
            handler.handle(codec.decodeCommand(inputCommit("commit", sessionId, 5, "large")), emitter);
            Thread.sleep(200);
            assertThat(events).withFailMessage(eventDiagnostics(events))
                    .anyMatch(event -> event.type().equals("run.started"));
            CapturedEvent terminal = awaitAnyTerminal(events);
            assertThat(terminal.type()).withFailMessage(eventDiagnostics(events)).isEqualTo("run.completed");
            assertThat(terminal.payload().get("finalText").stringValue()).isEqualTo(text);

            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputCommit("duplicate", sessionId, 6, "large")), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_REPLAY");
                        assertThat(failure.requestId()).isEqualTo("duplicate");
                    });
        }
    }

    @Test
    void rejectsOutOfOrderAndDigestMismatchWithoutStartingRun() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger modelCalls = new AtomicInteger();
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            modelCalls.incrementAndGet(); return ModelTurn.text("unexpected");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(inputBegin("bad-order", "begin-bad-order", sessionId, 2, 3, 1, "0".repeat(64))), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputChunk("chunk", sessionId, 3, "bad-order", 1, "abc")), emitter))
                    .isInstanceOf(StdioProtocolException.class)
                    .extracting(error -> ((StdioProtocolException) error).code()).isEqualTo("INPUT_CHUNK_ORDER");
            handler.handle(codec.decodeCommand(inputBegin("bad-digest", "begin-bad-digest", sessionId, 4, 3, 1, "0".repeat(64))), emitter);
            handler.handle(codec.decodeCommand(inputChunk("chunk", sessionId, 5, "bad-digest", 0, "abc")), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputCommit("commit", sessionId, 6, "bad-digest")), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_COMMIT_MISMATCH");
                        assertThat(failure.requestId()).isEqualTo("begin-bad-digest");
                    });
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    inputCommit("failed-replay", sessionId, 7, "bad-digest")), emitter))
                    .isInstanceOfSatisfying(StdioProtocolException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INPUT_REPLAY");
                        assertThat(failure.requestId()).isEqualTo("failed-replay");
                    });
            assertThat(modelCalls).hasValue(0);
            assertThat(events).noneMatch(RuntimeStdioCommandHandlerTest::isTerminal);
        }
    }

    private static String sha256(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String inputBegin(String id, String requestId, String sessionId, long sequence, int bytes, int chunks, String digest) {
        return "{\"version\":0,\"type\":\"input.begin\",\"requestId\":\"" + requestId
                + "\",\"sessionId\":\"" + sessionId + "\",\"sequence\":" + sequence
                + ",\"payload\":{\"inputId\":\"" + id + "\",\"byteCount\":" + bytes
                + ",\"chunkCount\":" + chunks + ",\"sha256\":\"" + digest + "\"}}";
    }

    private static String inputChunk(String requestId, String sessionId, long sequence, String id, int ordinal, String text) {
        return "{\"version\":0,\"type\":\"input.chunk\",\"requestId\":\"" + requestId
                + "\",\"sessionId\":\"" + sessionId + "\",\"sequence\":" + sequence
                + ",\"payload\":{\"inputId\":\"" + id + "\",\"ordinal\":" + ordinal
                + ",\"text\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(text) + "}}";
    }

    private static String inputCommit(String requestId, String sessionId, long sequence, String id) {
        return "{\"version\":0,\"type\":\"input.commit\",\"requestId\":\"" + requestId
                + "\",\"sessionId\":\"" + sessionId + "\",\"sequence\":" + sequence
                + ",\"payload\":{\"inputId\":\"" + id + "\"}}";
    }

    private static final class FakeAssemblyScheduler
            implements RuntimeStdioCommandHandler.InputAssemblyScheduler {
        private final List<FakeExpiry> tasks = new ArrayList<>();

        @Override
        public RuntimeStdioCommandHandler.ExpiryHandle schedule(Duration delay, Runnable task) {
            FakeExpiry expiry = new FakeExpiry(task);
            tasks.add(expiry);
            return expiry::cancel;
        }

        int pending() {
            return (int) tasks.stream().filter(task -> !task.cancelled && !task.fired).count();
        }

        int cancelled() {
            return (int) tasks.stream().filter(task -> task.cancelled).count();
        }

        void fireFirst() {
            tasks.stream().filter(task -> !task.cancelled && !task.fired).findFirst().orElseThrow().fire();
        }

        @Override
        public void close() {
            tasks.forEach(FakeExpiry::cancel);
        }
    }

    private static final class FakeExpiry {
        private final Runnable task;
        private boolean cancelled;
        private boolean fired;

        private FakeExpiry(Runnable task) {
            this.task = task;
        }

        private void cancel() {
            cancelled = true;
        }

        private void fire() {
            fired = true;
            task.run();
        }
    }

    private static String runStart(String requestId, String sessionId, long sequence, String prompt) {
        return ("{\"version\":0,\"type\":\"run.start\",\"requestId\":\"%s\",\"sessionId\":\"%s\","
                + "\"sequence\":%d,\"payload\":{\"prompt\":\"%s\"}}")
                .formatted(requestId, sessionId, sequence, prompt);
    }

    private static String sessionCommand(
            String requestId, String sessionId, long sequence, String commandId, String intent, String arguments) {
        return ("{\"version\":0,\"type\":\"session.command\",\"requestId\":\"%s\",\"sessionId\":\"%s\","
                + "\"sequence\":%d,\"payload\":{\"protocolVersion\":0,\"commandId\":\"%s\","
                + "\"intent\":\"%s\",\"arguments\":%s}}")
                .formatted(requestId, sessionId, sequence, commandId, intent, arguments);
    }

    private static void assertPermissionsQuery(
            RuntimeStdioCommandHandler handler,
            StdioProtocolCodec codec,
            List<CapturedEvent> events,
            String sessionId,
            long sequence,
            String commandId,
            String arguments,
            String expectedMode,
            String expectedReviewer,
            String expectedSelection) throws Exception {
        handler.handle(codec.decodeCommand(sessionCommand(
                commandId + "-request", sessionId, sequence, commandId, "permissions", arguments)),
                (type, requestId, eventSessionId, runId, payload) ->
                        events.add(new CapturedEvent(type, eventSessionId, runId, payload.deepCopy())));
        CapturedEvent mutation = events.stream()
                .filter(event -> event.type().equals("session.command.result"))
                .filter(event -> event.payload().get("commandId").stringValue().equals(commandId))
                .findFirst().orElseThrow();
        assertThat(mutation.payload().at("/result/effectiveSelection").stringValue()).isEqualTo(expectedSelection);
        String queryId = commandId + "-query";
        handler.handle(codec.decodeCommand(sessionCommand(
                queryId + "-request", sessionId, sequence + 1, queryId, "permissions", "{}")),
                (type, requestId, eventSessionId, runId, payload) ->
                        events.add(new CapturedEvent(type, eventSessionId, runId, payload.deepCopy())));
        CapturedEvent query = events.stream()
                .filter(event -> event.type().equals("session.command.result"))
                .filter(event -> event.payload().get("commandId").stringValue().equals(queryId))
                .findFirst().orElseThrow();
        assertThat(query.payload().at("/result/effectiveMode").stringValue()).isEqualTo(expectedMode);
        assertThat(query.payload().at("/result/effectiveReviewer").stringValue()).isEqualTo(expectedReviewer);
        assertThat(query.payload().at("/result/effectiveSelection").stringValue()).isEqualTo(expectedSelection);
    }

    @Test
    void emptyProviderProfilesProduceImmediateStdioTerminalWithSafeConfigurationSummary() throws Exception {
        Path home = Files.createDirectory(temporaryRoot.resolve("empty-provider-home"));
        Path repository = Files.createDirectory(temporaryRoot.resolve("empty-provider-repository"));
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocolCodec codec = new StdioProtocolCodec();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        java.util.concurrent.atomic.AtomicReference<io.github.liumaishenjian.ccjava.core.AgentEventSink>
                runtimeEvents = new java.util.concurrent.atomic.AtomicReference<>(
                        io.github.liumaishenjian.ccjava.core.AgentEventSink.noop());
        try (var auth = io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources.open(
                home, repository, java.util.Map.of());
             var application = new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                     auth.modelGateway(), envelope -> runtimeEvents.get().publish(envelope),
                     new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                             repository, "provider-not-configured", Duration.ofSeconds(5)));
             var handler = new RuntimeStdioCommandHandler(application, auth.service())) {
            runtimeEvents.set(handler);
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"missing-init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            long started = System.nanoTime();
            handler.handle(codec.decodeCommand(runStart(
                    "missing-run", sessionId, 2, "weather")), emitter);

            CapturedEvent terminal = awaitAnyTerminal(events);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(terminal.type()).isEqualTo("run.failed");
            assertThat(terminal.payload().get("stopReason").stringValue()).isEqualTo("model_error");
            assertThat(terminal.payload().at("/modelFailure/category").stringValue())
                    .isEqualTo("configuration_required");
            assertThat(events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal)).hasSize(1);
        }
    }

    private static boolean isTerminal(CapturedEvent event) {
        return event.type().equals("run.completed")
                || event.type().equals("run.failed")
                || event.type().equals("run.cancelled");
    }

    private void awaitTerminalCount(List<CapturedEvent> events, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal).count() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到预期数量的 stdio Run 终态事件");
    }

    private static String eventDiagnostics(List<CapturedEvent> events) {
        return events.stream()
                .map(event -> event.type() + "[status=" + stringField(event, "status")
                        + ", errorCode=" + stringField(event, "errorCode")
                        + ", stopReason=" + stringField(event, "stopReason")
                        + ", stream=" + stringField(event, "stream")
                        + ", toolName=" + stringField(event, "toolName") + "]")
                .toList()
                .toString();
    }

    private static String stringField(CapturedEvent event, String field) {
        var value = event.payload().get(field);
        return value != null && value.isString() ? value.stringValue() : "-";
    }

    private CapturedEvent awaitTerminal(List<CapturedEvent> events)
            throws InterruptedException {
        return awaitEvent(events, "run.completed");
    }

    private CapturedEvent awaitAnyTerminal(List<CapturedEvent> events)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CapturedEvent> matched = events.stream()
                    .filter(event -> event.type().equals("run.completed")
                            || event.type().equals("run.failed")
                            || event.type().equals("run.cancelled"))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到 stdio Run 终态事件");
    }

    @Test
    void providerControlAddsCompatibleProviderThroughApplicationServiceAndListsItsModel() throws Exception {
        Path home = Files.createDirectory(temporaryRoot.resolve("provider-add-home"));
        Path repository = Files.createDirectory(temporaryRoot.resolve("provider-add-repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(home);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(home);
        var migration = new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(repository),
                definitions, credentials);
        var service = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials, migration, java.util.Map.of());
        var application = new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                ignored -> ModelTurn.text("unused"), io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                testOptions());
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(application, service)) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            assertThat(events.getFirst().payload().get("modelConfigured").booleanValue()).isFalse();
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(providerControl(sessionId, 2, "provider-add", "providers.add",
                    "{\"providerId\":\"team\",\"displayName\":\"Team Gateway\","
                            + "\"baseUrl\":\"https://gateway.example/v1\",\"modelId\":\"model-x\"}")), emitter);
            handler.handle(codec.decodeCommand(providerControl(sessionId, 3, "models", "models.list",
                    "{\"providerId\":\"team\"}")), emitter);
            handler.handle(codec.decodeCommand(providerControl(sessionId, 4, "quick", "providers.configure",
                    "{\"baseUrl\":\"https://codej.example/v1\",\"modelId\":\"codej-model\"}")), emitter);

            assertThat(service.listModels(Optional.of("team"), io.github.liumaishenjian.ccjava.core.CancellationToken.none()))
                    .extracting(io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService
                            .ModelSummary::modelId).containsExactly("model-x");
            assertThat(service.listModels(Optional.of("codej-custom"),
                    io.github.liumaishenjian.ccjava.core.CancellationToken.none()))
                    .extracting(io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService
                            .ModelSummary::modelId).containsExactly("codej-model");
            CapturedEvent added = events.stream().filter(event -> event.type().equals("provider.control.result")
                    && event.payload().get("intent").stringValue().equals("providers.add")).findFirst().orElseThrow();
            assertThat(added.payload().get("result").properties().stream()
                    .map(java.util.Map.Entry::getKey).toList())
                    .containsExactlyInAnyOrder("providerId", "displayName", "modelId");
            assertThat(added.payload().toString()).doesNotContain("gateway.example");
            CapturedEvent configured = events.stream().filter(event -> event.type().equals("provider.control.result")
                    && event.payload().get("intent").stringValue().equals("providers.configure"))
                    .findFirst().orElseThrow();
            assertThat(configured.payload().toString()).contains("codej-custom", "codej-model")
                    .doesNotContain("codej.example");
            assertThat(Files.exists(home.resolve(".cc-java/providers.v1.json"))).isTrue();
        }
    }

    @Test
    void providerControlRejectsInvalidOrDuplicateProviderWithoutChangingStore() throws Exception {
        Path home = Files.createDirectory(temporaryRoot.resolve("provider-reject-home"));
        Path repository = Files.createDirectory(temporaryRoot.resolve("provider-reject-repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(home);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(home);
        var service = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials, new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(repository),
                definitions, credentials), java.util.Map.of());
        service.addCompatibleProvider(new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService
                .AddProviderRequest("team", "Team", "https://gateway.example/v1", "model-x"),
                io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        long generation = definitions.snapshot(io.github.liumaishenjian.ccjava.core.CancellationToken.none()).generation();
        var application = new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                ignored -> ModelTurn.text("unused"), io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                testOptions());
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(application, service)) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(providerControl(sessionId, 2, "http", "providers.add",
                    "{\"providerId\":\"plain\",\"displayName\":\"Plain\","
                            + "\"baseUrl\":\"http://gateway.example/v1\",\"modelId\":\"m\"}")), emitter);
            handler.handle(codec.decodeCommand(providerControl(sessionId, 3, "duplicate", "providers.add",
                    "{\"providerId\":\"team\",\"displayName\":\"Other\","
                            + "\"baseUrl\":\"https://other.example/v1\",\"modelId\":\"m\"}")), emitter);
        }
        assertThat(definitions.snapshot(io.github.liumaishenjian.ccjava.core.CancellationToken.none()).generation()).isEqualTo(generation);
        assertThat(events.stream().filter(event -> event.type().equals("provider.control.result"))
                .map(event -> event.payload().get("status").stringValue())).containsExactly("rejected", "rejected");
    }

    @Test
    void providerControlAddsRemovesModelsAndOnlyPersistsExplicitUseDefault() throws Exception {
        Path home = Files.createDirectory(temporaryRoot.resolve("provider-home"));
        Path repository = Files.createDirectory(temporaryRoot.resolve("provider-repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(home);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(home);
        var migration = new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(repository),
                definitions, credentials);
        var service = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials, migration, java.util.Map.of("CC_TEST", "value"));
        service.login(new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                "anthropic", "personal", io.github.liumaishenjian.ccjava.cli.runtime
                        .ProviderAuthApplicationService.RefKind.ENV, "CC_TEST", true), null,
                io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        var application = new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                ignored -> ModelTurn.text("unused"), io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                testOptions());
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(application, service)) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(providerControl(sessionId, 2, "add-remove", "models.add",
                    "{\"providerId\":\"openrouter\",\"modelId\":\"stdio-remove\"}")), emitter);
            handler.handle(codec.decodeCommand(providerControl(sessionId, 3, "remove", "models.remove",
                    "{\"providerId\":\"openrouter\",\"modelId\":\"stdio-remove\"}")), emitter);
            handler.handle(codec.decodeCommand(providerControl(sessionId, 4, "add", "models.add",
                    "{\"providerId\":\"anthropic\",\"modelId\":\"stdio-overlay\"}")), emitter);
            handler.handle(codec.decodeCommand(providerControl(sessionId, 5, "use-session", "models.use",
                    "{\"providerId\":\"anthropic\",\"modelId\":\"stdio-overlay\","
                            + "\"profileId\":\"personal\"}")), emitter);
            assertThat(definitions.snapshot(io.github.liumaishenjian.ccjava.core.CancellationToken.none())
                    .defaultSelection()).isEmpty();
            handler.handle(codec.decodeCommand(providerControl(sessionId, 6, "use-default", "models.use",
                    "{\"providerId\":\"anthropic\",\"modelId\":\"stdio-overlay\","
                            + "\"profileId\":\"personal\",\"setDefault\":true}")), emitter);
            assertThat(definitions.snapshot(io.github.liumaishenjian.ccjava.core.CancellationToken.none())
                    .defaultSelection()).get()
                    .extracting(io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore
                            .DefaultSelection::modelId).isEqualTo("stdio-overlay");
            assertThat(events.stream().filter(event -> event.type().equals("provider.control.result")))
                    .hasSize(5).allSatisfy(event -> assertThat(event.payload().get("status").stringValue())
                            .isEqualTo("succeeded"));
        }
    }

    private static String providerControl(String sessionId, int sequence, String controlId,
                                          String intent, String arguments) {
        return ("{\"version\":0,\"type\":\"provider.control\",\"requestId\":\"request-%d\","
                + "\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"controlId\":\"%s\","
                + "\"intent\":\"%s\",\"arguments\":%s}}")
                .formatted(sequence, sessionId, sequence, controlId, intent, arguments);
    }

    @Test
    void fileSuggestEmitsBoundedCandidatesWithoutRunOrModelWork() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/notes.md"), "content");
        Files.writeString(workspace.resolve(".env"), "SECRET_TOKEN=abc");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger modelCalls = new AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            modelCalls.incrementAndGet();
            throw new AssertionError("file.suggest 不得触发模型请求");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"file.suggest\","
                    + "\"requestId\":\"suggest\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"query\":\"src\"}}").formatted(sessionId)), emitter);

            CapturedEvent suggestions = awaitEvent(events, "file.suggestions");
            assertThat(suggestions.runId()).isEmpty();
            assertThat(suggestions.payload().get("query").stringValue()).isEqualTo("src");
            assertThat(suggestions.payload().get("candidates").size()).isOne();
            assertThat(suggestions.payload().get("candidates").get(0).stringValue())
                    .isEqualTo("src/notes.md");
            assertThat(suggestions.payload().toString())
                    .doesNotContain("SECRET_TOKEN", ".env", workspace.toString());
            assertThat(events).noneMatch(event -> event.type().startsWith("run."));
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test
    void skillInvokeEmitsPrivacySafeLifecycleEvenWhenCatalogRejectsUnknownSkill() throws Exception {
        workspace();
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger modelCalls = new AtomicInteger();
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.text("unused");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"skill.invoke\","
                    + "\"requestId\":\"skill\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"name\":\"missing-skill\",\"arguments\":\"ARG_SENTINEL\"}}")
                    .formatted(sessionId)), emitter);

            CapturedEvent invoked = awaitEvent(events, "skill.invoked");
            CapturedEvent completed = awaitEvent(events, "skill.completed");
            int acceptedIndex = java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).type().equals("run.command.result"))
                    .findFirst().orElseThrow();
            int invokedIndex = java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).type().equals("skill.invoked"))
                    .findFirst().orElseThrow();
            int startedIndex = java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).type().equals("run.started"))
                    .findFirst().orElseThrow();
            assertThat(acceptedIndex).isLessThan(invokedIndex);
            assertThat(invokedIndex).isLessThan(startedIndex);
            assertThat(invoked.runId()).isEmpty();
            assertThat(invoked.payload().toString()).contains("missing-skill", "explicit")
                    .doesNotContain("ARG_SENTINEL");
            assertThat(completed.runId()).isPresent();
            assertThat(completed.payload().toString()).contains("failed")
                    .doesNotContain("ARG_SENTINEL");
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test
    void skillLifecycleEmissionFailureAbortsBeforeRuntimeAndDoesNotLeakExecutorWorker() throws Exception {
        workspace();
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.text("must not execute");
        }, testOptions());
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            if (type.equals("skill.invoked")) {
                throw new IllegalStateException("transport closed");
            }
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        };
        try {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\"," +
                            "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(("{\"version\":0," +
                    "\"type\":\"skill.invoke\",\"requestId\":\"skill\",\"sessionId\":\"%s\"," +
                    "\"sequence\":2,\"payload\":{\"name\":\"missing-skill\",\"arguments\":\"args\"}}")
                    .formatted(sessionId)), emitter))
                    .isInstanceOf(RuntimeStdioCommandHandler.AcceptedRunTransportException.class)
                    .hasMessageContaining("transport closed");
        } finally {
            handler.close();
        }
        assertThat(modelCalls).hasValue(0);
        assertThat(events).filteredOn(event -> event.type().equals("run.command.result")).hasSize(1);
        assertThat(events).noneMatch(event -> event.type().equals("run.started"));
    }

    @Test
    void invalidExplicitMentionIsRejectedBeforeAnyRunOrModelRequest() throws Exception {
        workspace();
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        AtomicInteger modelCalls = new AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            modelCalls.incrementAndGet();
            throw new AssertionError("无效提及不得触发模型请求");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();

            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(
                    ("{\"version\":0,\"type\":\"run.start\",\"requestId\":\"run\","
                            + "\"sessionId\":\"%s\",\"sequence\":2,"
                            + "\"payload\":{\"prompt\":\"look at @../outside/secret.txt\"}}")
                            .formatted(sessionId)), emitter))
                    .isInstanceOf(StdioProtocolException.class)
                    .extracting(failure -> ((StdioProtocolException) failure).code())
                    .isEqualTo("FILE_MENTION_INVALID");

            assertThat(events).noneMatch(event -> event.type().startsWith("run."));
            assertThat(modelCalls).hasValue(0);
        }
    }

    private CapturedEvent awaitEvent(List<CapturedEvent> events, String type)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CapturedEvent> matched = events.stream()
                    .filter(event -> event.type().equals(type))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到 stdio 事件: " + type);
    }

    private record CapturedEvent(
            String type,
            String requestId,
            Optional<String> sessionId,
            Optional<String> runId,
            ObjectNode payload) {
        private CapturedEvent(
                String type,
                Optional<String> sessionId,
                Optional<String> runId,
                ObjectNode payload) {
            this(type, "unavailable", sessionId, runId, payload);
        }
    }
}
