package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanContextPolicy;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionBlockedEvent;
import io.github.liumaishenjian.ccjava.domain.PlanVerificationSkipDecision;
import io.github.liumaishenjian.ccjava.domain.PlanReviewDecision;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 durable Plan 一键批准、策略交接、恢复与终态。 */
class DurablePlanExecutionHandoffTest {
    @TempDir Path temporary;

    @Test
    void approveAutoAtomicallyBindsArtifactAndExecutesMarkdownThroughNormalPipeline() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-auto"));
        Path root = temporary.resolve("sessions-auto");
        String markdown = "# Approved plan\n\nCreate `result.txt` with the word done.\n";
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.ModelRequest> requests = new ArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown)))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "result-file", "kind", "DELIVERABLE",
                                "locator", "result.txt", "label", "result file exists", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                        JsonObject.empty())));
                case 3 -> ModelTurn.text("planning complete");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("write", "write_file",
                        new JsonObject(Map.of("path", "result.txt", "content", "done\n")))));
                case 5 -> ModelTurn.text("{\"verdict\":\"ALLOW_ONCE\"}");
                default -> ModelTurn.text("implemented");
            };
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            runtime.runPlan("prepare a durable plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            String workspaceDigest = runtime.currentWorkspaceDigest();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), workspaceDigest, PlanReviewDecision.APPROVE_AUTO,
                    PlanContextPolicy.KEEP, "also verify the file");
            PlanArtifact approved = runtime.planArtifact().orElseThrow();
            assertThat(approved.status()).isEqualTo(PlanStatus.APPROVED);
            assertThat(approved.executionBrief()).contains(acceptance.brief());
            assertThat(acceptance.brief().approvalReviewer()).isEqualTo(ApprovalReviewer.AUTO_REVIEW);
            assertThat(acceptance.brief().markdownSnapshot()).isEqualTo(markdown);
            assertThat(runtime.runAcceptedPlan(acceptance).stopReason().name()).isEqualTo("COMPLETED");
            assertThat(Files.readString(workspace.resolve("result.txt"))).isEqualTo("done\n");
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.COMPLETED);
            var executionMessages = requests.get(4).messages();
            assertThat(executionMessages.toString())
                    .contains(markdown)
                    .contains("also verify the file")
                    .contains("prepare a durable plan")
                    .doesNotContain("expectedDigest");
        }
    }

    @Test
    void normalApprovalClearContextAndDuplicateDecisionFailClosed() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-user"));
        Path root = temporary.resolve("sessions-user");
        String markdown = "# Plan\n\nInspect only.\n";
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.ModelRequest> requests = new ArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", markdown)))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                    JsonObject.empty())));
            default -> ModelTurn.text("done");
            };
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var accepted = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.CLEAR, "");
            assertThat(accepted.brief().approvalReviewer()).isEqualTo(ApprovalReviewer.USER);
            assertThat(accepted.brief().contextPolicy()).isEqualTo(PlanContextPolicy.CLEAR);
            runtime.runAcceptedPlan(accepted);
            var executionMessages = requests.getLast().messages();
            assertThat(executionMessages).hasSize(3);
            assertThat(executionMessages.get(1).toString()).contains(markdown);
            assertThat(executionMessages.getLast().toString())
                    .contains("Implement the approved plan and report verified results.")
                    .doesNotContain("Additional user feedback");
            assertThat(executionMessages.toString()).doesNotContain("UserMessage[content=plan,");
            assertThatThrownBy(() -> runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.CLEAR, "")).isInstanceOf(IllegalStateException.class);
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);
        }
    }

    @Test
    void deliverableFilenameMismatchContinuesSameRunAndWithholdsFirstFinalUntilCorrected() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-correction"));
        Path root = temporary.resolve("sessions-correction");
        String expected = "河南 各市`7天天气.xlsx";
        String wrong = "河南 各市`7天天气预报.xlsx";
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.ModelRequest> requests = new ArrayList<>();
        List<io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope> events = new ArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", "# Plan\n\nCreate the weather workbook.\n")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "weather-xlsx", "kind", "DELIVERABLE",
                                "locator", expected, "label", "weather workbook", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 3 -> ModelTurn.text("planning complete");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("wrong-file", "write_file",
                        new JsonObject(Map.of("path", wrong, "content", "wrong-name")))));
                case 5 -> ModelTurn.text("已完成并交付天气工作簿");
                case 6 -> ModelTurn.tools(List.of(new ToolCall("correct-file", "write_file",
                        new JsonObject(Map.of("path", expected, "content", "correct-name")))));
                default -> ModelTurn.text("已按精确文件名完成并验证");
            };
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, events::add,
                options(workspace, root, SessionOpenRequest.create()),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            runtime.runPlan("prepare weather workbook plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.KEEP, "");
            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.finalText()).hasValueSatisfying(text -> assertThat(text).contains("精确文件名"));
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.COMPLETED);
            assertThat(workspace.resolve(wrong)).exists();
            assertThat(workspace.resolve(expected)).exists();
            assertThat(events).extracting(io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope::event)
                    .anyMatch(io.github.liumaishenjian.ccjava.domain.LifecycleEvent.PlanVerificationCorrectionRequested.class::isInstance);
            assertThat(requests.get(6).messages().toString())
                    .contains("weather-xlsx", "exactLocator=" + expected, "FILE_MISSING_OR_UNSAFE")
                    .doesNotContain("actualLocator=", wrong + " reason=")
                    .contains("Do not repeat already successful side effects");
            assertThat(requests).filteredOn(request -> request.messages().toString().contains("已完成并交付天气工作簿"))
                    .isEmpty();
            assertThat(runtime.planArtifact().orElseThrow().evidenceLedger().references())
                    .filteredOn(reference -> reference.requirementId().equals("weather-xlsx"))
                    .singleElement().satisfies(reference -> {
                        assertThat(reference.status().name()).isEqualTo("PASSED");
                        assertThat(reference.contentDigest()).isPresent();
                        assertThat(reference.sourceReference()).isEqualTo(expected);
                    });
        }
    }

    @Test
    void repeatedMissingDeliverableStopsThenExplicitReapprovalResumesSamePlan() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-correction-bounded"));
        Path root = temporary.resolve("sessions-correction-bounded");
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope> events = new ArrayList<>();
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", "# Plan\n\nCreate `missing.txt`.\n")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                    new JsonObject(Map.of("requirementId", "missing-file", "kind", "DELIVERABLE",
                            "locator", "missing.txt", "label", "missing file", "required", true)))));
            case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
            case 3 -> ModelTurn.text("planning complete");
            case 4 -> ModelTurn.text("unverified first final");
            case 5 -> ModelTurn.text("unverified repeated final");
            case 6 -> ModelTurn.tools(List.of(new ToolCall("resume-file", "write_file",
                    new JsonObject(Map.of("path", "missing.txt", "content", "resumed")))));
            default -> ModelTurn.text("verified after explicit resume");
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, events::add,
                options(workspace, root, SessionOpenRequest.create()),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            runtime.runPlan("prepare bounded correction plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.stopReason().name()).isEqualTo("PLAN_VERIFICATION_REQUIRED");
            assertThat(result.finalText()).isEmpty();
            assertThat(result.status().name()).isEqualTo("STOPPED");
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);
            assertThat(workspace.resolve("missing.txt")).doesNotExist();
            assertThat(calls).hasValue(6);
            assertThat(events).extracting(io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope::event)
                    .filteredOn(io.github.liumaishenjian.ccjava.domain.LifecycleEvent.PlanVerificationCorrectionRequested.class::isInstance)
                    .hasSize(1);

            var review = runtime.requestPlanVerificationResume().orElseThrow();
            assertThat(review.planId()).isEqualTo(awaiting.planId());
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
            var resumedAcceptance = runtime.acceptPlanExecution(review.planId(), review.revision(),
                    review.contentDigest(), review.workspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.KEEP, "continue exact missing deliverable");
            var resumed = runtime.runAcceptedPlan(resumedAcceptance);

            assertThat(resumed.stopReason().name()).isEqualTo("COMPLETED");
            assertThat(resumed.finalText()).contains("verified after explicit resume");
            assertThat(workspace.resolve("missing.txt")).hasContent("resumed");
            assertThat(runtime.planArtifact().orElseThrow()).satisfies(artifact -> {
                assertThat(artifact.planId()).isEqualTo(awaiting.planId());
                assertThat(artifact.status()).isEqualTo(PlanStatus.COMPLETED);
            });
            assertThat(calls).hasValue(8);
        }
    }

    @Test
    void failedVerificationToolContinuesAndRequiresLaterSuccessfulToolResult() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-verification-correction"));
        Path root = temporary.resolve("sessions-verification-correction");
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.ModelRequest> requests = new ArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", "# Plan\n\nRun a write verification.\n")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "write-check", "kind", "VERIFICATION",
                                "locator", "write_file", "label", "write succeeds", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
                case 3 -> ModelTurn.text("planning complete");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("unsafe-write", "write_file",
                        new JsonObject(Map.of("path", "../escape.txt", "content", "unsafe")))));
                case 5 -> ModelTurn.text("incorrectly claimed verification success");
                case 6 -> ModelTurn.tools(List.of(new ToolCall("safe-write", "write_file",
                        new JsonObject(Map.of("path", "verified.txt", "content", "verified")))));
                default -> ModelTurn.text("verification corrected");
            };
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            runtime.runPlan("prepare verification correction plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.finalText()).contains("verification corrected");
            assertThat(workspace.resolve("verified.txt")).hasContent("verified");
            assertThat(temporary.resolve("escape.txt")).doesNotExist();
            assertThat(requests.get(6).messages().toString())
                    .contains("write-check", "SUCCESSFUL_TOOL_RESULT_MISSING")
                    .doesNotContain("incorrectly claimed verification success");
            assertThat(runtime.planArtifact().orElseThrow()).satisfies(artifact -> {
                assertThat(artifact.status()).isEqualTo(PlanStatus.COMPLETED);
                assertThat(artifact.evidenceLedger().references()).filteredOn(reference ->
                        reference.requirementId().equals("write-check")).singleElement().satisfies(reference -> {
                            assertThat(reference.status().name()).isEqualTo("PASSED");
                            assertThat(reference.sourceReference()).isEqualTo("safe-write");
                            assertThat(reference.contentDigest()).isPresent();
                        });
            });
        }
    }

    @Test
    void modelFailureDuringCorrectionTerminatesPlanWithoutAcceptingTheWithheldFinal() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-correction-model-error"));
        Path root = temporary.resolve("sessions-correction-model-error");
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.ModelRequest> requests = new ArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", "# Plan\n\nCreate `result.txt`.\n")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                    new JsonObject(Map.of("requirementId", "result-file", "kind", "DELIVERABLE",
                            "locator", "result.txt", "label", "result file", "required", true)))));
            case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
            case 3 -> ModelTurn.text("planning complete");
            case 4 -> ModelTurn.text("withheld completion claim");
            default -> throw new io.github.liumaishenjian.ccjava.core.ModelGatewayException("correction failed");
            };
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            runtime.open();
            runtime.runPlan("prepare model failure plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.KEEP, "");

            var result = runtime.runAcceptedPlan(acceptance);

            assertThat(result.stopReason().name()).isEqualTo("MODEL_ERROR");
            assertThat(result.finalText()).isEmpty();
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.FAILED);
            assertThat(requests.getLast().messages().toString()).doesNotContain("withheld completion claim");
        }
    }

    @Test
    void cancellationDuringCorrectionTerminatesWithoutReplayingCompletedSideEffects() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-correction-cancel"));
        Path root = temporary.resolve("sessions-correction-cancel");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch correctionEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("plan", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", "# Plan\n\nCreate exact deliverable.\n")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                    new JsonObject(Map.of("requirementId", "exact-file", "kind", "DELIVERABLE",
                            "locator", "exact.txt", "label", "exact file", "required", true)))));
            case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review", JsonObject.empty())));
            case 3 -> ModelTurn.text("planning complete");
            case 4 -> ModelTurn.tools(List.of(new ToolCall("side-effect", "write_file",
                    new JsonObject(Map.of("path", "already-written.txt", "content", "once")))));
            case 5 -> ModelTurn.text("withheld completion claim");
            default -> {
                correctionEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                yield ModelTurn.text("must not complete");
            }
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            runtime.runPlan("prepare cancellation plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.KEEP, "");
            AtomicReference<io.github.liumaishenjian.ccjava.domain.AgentRunResult> result = new AtomicReference<>();
            Thread runner = Thread.ofPlatform().start(() -> result.set(runtime.runAcceptedPlan(acceptance)));
            assertThat(correctionEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(runtime.cancelActive()).isTrue();
            release.countDown();
            runner.join(5_000);

            assertThat(result.get().stopReason().name()).isEqualTo("USER_CANCELLED");
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.CANCELLED);
            assertThat(workspace.resolve("already-written.txt")).hasContent("once");
            assertThat(workspace.resolve("exact.txt")).doesNotExist();
            assertThat(calls).hasValue(7);
        }
    }

    @Test
    void approvedRestartIsExplicitWhileExecutingRestartRequiresRecoveryGate() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-recovery"));
        Path root = temporary.resolve("sessions-recovery");
        String markdown = "# Plan\n\nRecover explicitly.\n";
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", markdown)))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                    JsonObject.empty())));
            default -> ModelTurn.text("done");
        };
        SessionId id;
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            id = runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            runtime.releaseAcceptedPlan(acceptance);
        }
        try (HeadlessRuntimeSession resumed = new HeadlessRuntimeSession(
                ignored -> ModelTurn.text("done"), AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.resume(id)))) {
            resumed.open();
            var accepted = resumed.resumeApprovedPlanExecution().orElseThrow();
            resumed.runAcceptedPlan(accepted);
            assertThat(resumed.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);
        }
    }


    @Test
    void approvedResumeRejectsWorkspaceDriftBeforeClaimAndPersistsReapproval() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-resume-drift"));
        Path root = temporary.resolve("sessions-resume-drift");
        String markdown = "# Plan\n\nDo not run after drift.\n";
        AtomicInteger calls = new AtomicInteger();
        SessionId id;
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                planThenFinish(calls, markdown, false), AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            id = runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var accepted = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            runtime.releaseAcceptedPlan(accepted);
        }
        Files.writeString(workspace.resolve("external-change.txt"), "changed");
        List<io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope> events = new ArrayList<>();
        try (HeadlessRuntimeSession resumed = new HeadlessRuntimeSession(
                ignored -> { calls.incrementAndGet(); return ModelTurn.text("must not run"); }, events::add,
                options(workspace, root, SessionOpenRequest.resume(id)))) {
            resumed.open();
            assertThat(resumed.resumeApprovedPlanExecution()).isEmpty();
            assertThat(resumed.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
            assertThat(events).extracting(event -> event.event()).anyMatch(PlanExecutionBlockedEvent.class::isInstance);
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void acceptedPlanRejectsWorkspaceDriftBeforeExecutingWithZeroExecutionSideEffects() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-execute-drift"));
        Path root = temporary.resolve("sessions-execute-drift");
        String markdown = "# Plan\n\nDo not run after drift.\n";
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope> events = new ArrayList<>();
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(planThenFinish(calls, markdown, false),
                events::add, options(workspace, root, SessionOpenRequest.create()))) {
            runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var accepted = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            Files.writeString(workspace.resolve("external-change.txt"), "changed");
            assertThatThrownBy(() -> runtime.runAcceptedPlan(accepted))
                    .isInstanceOf(HeadlessRuntimeSession.PlanExecutionWorkspaceDriftException.class);
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
            assertThat(events).extracting(event -> event.event()).anyMatch(PlanExecutionBlockedEvent.class::isInstance);
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void typedSkipRejectsForgeryReplayAndCrossRequirementWithoutLedgerMutation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-skip"));
        Path root = temporary.resolve("sessions-skip");
        String markdown = "# Plan\n\nRun verification.\n";
        AtomicInteger calls = new AtomicInteger();
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(planThenFinish(calls, markdown, true),
                AgentEventSink.noop(), options(workspace, root, SessionOpenRequest.create()))) {
            runtime.installPlanVerificationSkipCoordinator(ignored -> true);
            runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            runtime.runAcceptedPlan(acceptance);
            PlanArtifact needs = runtime.planArtifact().orElseThrow();
            assertThat(needs.status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);

            PlanVerificationSkipDecision issued = runtime.requestPlanVerificationSkip("tests").orElseThrow();
            PlanVerificationSkipDecision forged = new PlanVerificationSkipDecision(issued.decisionId(),
                    issued.sessionId(), issued.planId(), issued.approvedPlanRevision(), issued.requirementId());
            assertThatThrownBy(() -> runtime.skipPlanVerification(forged)).isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.planArtifact().orElseThrow().evidenceLedger()).isEqualTo(needs.evidenceLedger());

            PlanVerificationSkipDecision crossRequirement = new PlanVerificationSkipDecision(issued.decisionId(),
                    issued.sessionId(), issued.planId(), issued.approvedPlanRevision(), "lint");
            assertThatThrownBy(() -> runtime.skipPlanVerification(crossRequirement))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.planArtifact().orElseThrow().evidenceLedger()).isEqualTo(needs.evidenceLedger());

            PlanArtifact skipped = runtime.skipPlanVerification(issued);
            assertThat(skipped.status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);
            assertThat(skipped.evidenceLedger().references()).filteredOn(reference ->
                    reference.requirementId().equals("tests")).singleElement().satisfies(reference ->
                    assertThat(reference.status().name()).isEqualTo("SKIPPED"));
            var afterSkip = skipped.evidenceLedger();
            assertThatThrownBy(() -> runtime.skipPlanVerification(issued)).isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.planArtifact().orElseThrow().evidenceLedger()).isEqualTo(afterSkip);
        }
    }

    private static ModelGateway planThenFinish(AtomicInteger calls, String markdown, boolean twoRequirements) {
        return request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", markdown)))));
            case 1 -> twoRequirements
                    ? ModelTurn.tools(List.of(new ToolCall("tests", "declare_plan_evidence",
                            new JsonObject(Map.of("requirementId", "tests", "kind", "VERIFICATION",
                                    "locator", "run_command", "label", "tests pass", "required", true)))))
                    : ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                            JsonObject.empty())));
            case 2 -> twoRequirements
                    ? ModelTurn.tools(List.of(new ToolCall("lint", "declare_plan_evidence",
                            new JsonObject(Map.of("requirementId", "lint", "kind", "VERIFICATION",
                                    "locator", "run_command", "label", "lint passes", "required", true)))))
                    : ModelTurn.text("planning complete");
            case 3 -> twoRequirements
                    ? ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                            JsonObject.empty())))
                    : ModelTurn.text("must not execute");
            case 4 -> ModelTurn.text("planning complete");
            default -> ModelTurn.text("done without verification");
        };
    }

    private static HeadlessRuntimeOptions options(Path workspace, Path root, SessionOpenRequest open) {
        return new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(5), PermissionMode.DEFAULT,
                List.of(), open, root, Optional.empty(),
                io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode.OFF, Optional.empty(),
                ExecutionBackendPreference.LOCAL, ExecutionShell.WINDOWS_PLATFORM);
    }
}
