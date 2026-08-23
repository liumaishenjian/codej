package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.core.skill.ImmutableSkillCatalog;
import io.github.liumaishenjian.ccjava.core.skill.SkillInvoker;
import io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator;
import io.github.liumaishenjian.ccjava.core.skill.SkillToolScopeNarrower;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillContentSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationPolicy;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationRequest;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolExecutionPipelineTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void keepsContentAtExactDefinitionLimit() {
        PipelineFixture fixture = fixture(toolWithLimit("abcd", 4));

        ToolResult result = fixture.execute();

        assertThat(result.content()).isEqualTo("abcd");
        assertThat(result.metadata().truncated()).isFalse();
        assertThat(result.metadata().returnedCharacters()).isEqualTo(4);
    }

    @Test
    void truncatesOneCharacterOverLimitAndCountsMarkerInsideLimit() {
        PipelineFixture fixture = fixture(toolWithLimit("abcdefghijklmnopqrstuvwxyz", 20));

        ToolResult result = fixture.execute();

        assertThat(result.content().codePointCount(0, result.content().length())).isEqualTo(20);
        assertThat(result.content()).contains("truncated");
        assertThat(result.metadata().truncated()).isTrue();
        assertThat(result.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.PIPELINE_CHARACTER_LIMIT);
        assertThat(result.metadata().knownOriginalCharacters()).hasValue(26);
    }

    @Test
    void neverSplitsUnicodeCodePoint() {
        PipelineFixture fixture = fixture(toolWithLimit("A😀BCDEFGHIJKLMNOPQRSTUVWXYZ", 20));

        ToolResult result = fixture.execute();

        assertThat(result.content()).doesNotContain("�");
        assertThat(result.content().codePointCount(0, result.content().length())).isEqualTo(20);
    }

    @Test
    void preservesSemanticTruncationWhenPipelineDoesNotTrimAgain() {
        String content = "line one";
        ToolResultMetadata metadata = new ToolResultMetadata(
                true,
                ToolResultTruncationReason.LINE_LIMIT,
                content.codePointCount(0, content.length()),
                java.util.OptionalLong.empty(),
                1,
                0,
                new JsonObject(Map.of("startLine", 2)));
        AgentTool tool = new RecordingAgentTool(
                "read_file",
                ignored -> ToolValidationResult.validResult(),
                ignored -> ToolExecutionOutcome.success(content, metadata));
        PipelineFixture fixture = fixture(tool);

        ToolResult result = fixture.execute();

        assertThat(result.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.LINE_LIMIT);
        assertThat(result.metadata().continuation().values()).containsEntry("startLine", 2);
    }

    @Test
    void propagatesTheExactRunCancellationTokenToToolInvocation() {
        AtomicReference<CancellationToken> observed = new AtomicReference<>();
        AgentTool tool = new RecordingAgentTool(
                "cancel-aware",
                ignored -> ToolValidationResult.validResult(),
                invocation -> {
                    observed.set(invocation.cancellationToken());
                    return ToolExecutionOutcome.success("ok");
                });
        PipelineFixture fixture = fixture(tool);
        CancellationSource cancellation = new CancellationSource();

        fixture.pipeline().execute(
                fixture.session(),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", fixture.toolName(), JsonObject.empty()),
                cancellation.token());

        assertThat(observed.get()).isSameAs(cancellation.token());
    }

    @Test
    void unrelatedSuccessDoesNotReleaseForbiddenFingerprintButSameToolStrategySuccessDoes() {
        AtomicInteger webExecutions = new AtomicInteger();
        AgentTool webSearch = new RecordingAgentTool(
                "web_search",
                ignored -> ToolValidationResult.validResult(),
                invocation -> {
                    webExecutions.incrementAndGet();
                    String query = (String) invocation.call().arguments().values().get("query");
                    return query.equals("blocked")
                            ? ToolExecutionOutcome.failure(ToolError.of(
                                    ToolErrorCode.WEB_SEARCH_FORBIDDEN, "typed 403"))
                            : ToolExecutionOutcome.success("policy success");
                });
        AgentTool gitStatus = RecordingAgentTool.succeeding("git_status", "clean");
        PipelineFixture fixture = fixture(List.of(webSearch, gitStatus));
        RunId runId = new RunId("run-fingerprint");

        ToolResult first = fixture.execute(runId, 1, "first", "web_search",
                new JsonObject(Map.of("query", "blocked")));
        ToolResult unrelated = fixture.execute(runId, 2, "status", "git_status", JsonObject.empty());
        ToolResult stillBlocked = fixture.execute(runId, 3, "retry", "web_search",
                new JsonObject(Map.of("query", "blocked")));
        ToolResult changed = fixture.execute(runId, 4, "changed", "web_search",
                new JsonObject(Map.of("query", "allowed")));
        ToolResult retriedAfterPolicySuccess = fixture.execute(runId, 5, "retry-after-success", "web_search",
                new JsonObject(Map.of("query", "blocked")));

        assertThat(first.error()).get().extracting(ToolError::code)
                .isEqualTo(ToolErrorCode.WEB_SEARCH_FORBIDDEN);
        assertThat(unrelated.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(stillBlocked.error()).get().extracting(ToolError::code)
                .isEqualTo(ToolErrorCode.REPEATED_FAILURE);
        assertThat(changed.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(retriedAfterPolicySuccess.error()).get().extracting(ToolError::code)
                .isEqualTo(ToolErrorCode.WEB_SEARCH_FORBIDDEN);
        assertThat(webExecutions).hasValue(3);
    }

    @Test
    void validationCorrectionShapeBlocksChangedBusinessArgumentsButAllowsDifferentViolationAndSuccess() {
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new RecordingAgentTool(
                "validated",
                arguments -> {
                    validations.incrementAndGet();
                    if (Boolean.TRUE.equals(arguments.values().get("bad"))) {
                        return ToolValidationResult.invalid("删除 bad",
                                new JsonObject(Map.of("removeFields", List.of("bad"))),
                                new JsonObject(Map.of("violation", "remove_bad")));
                    }
                    if (Boolean.TRUE.equals(arguments.values().get("otherBad"))) {
                        return ToolValidationResult.invalid("删除 otherBad",
                                new JsonObject(Map.of("removeFields", List.of("otherBad"))),
                                new JsonObject(Map.of("violation", "remove_other_bad")));
                    }
                    return ToolValidationResult.validResult();
                },
                ignored -> {
                    executions.incrementAndGet();
                    return ToolExecutionOutcome.success("ok");
                });
        PipelineFixture fixture = fixture(tool);
        RunId runId = new RunId("run-validation-fingerprint");

        ToolResult first = fixture.execute(runId, 1, "invalid-1", "validated",
                new JsonObject(Map.of("bad", true, "query", "first")));
        ToolResult repeated = fixture.execute(runId, 2, "invalid-2", "validated",
                new JsonObject(Map.of("bad", true, "query", "changed")));
        ToolResult different = fixture.execute(runId, 3, "invalid-b", "validated",
                new JsonObject(Map.of("otherBad", true, "query", "changed-again")));
        ToolResult corrected = fixture.execute(runId, 4, "corrected", "validated",
                new JsonObject(Map.of("query", "safe")));

        assertThat(first.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
            assertThat(error.details().values())
                    .containsEntry("argumentChangeRequired", true)
                    .containsEntry("retrySameArguments", false)
                    .containsEntry("removeFields", List.of("bad"))
                    .doesNotContainKeys("violation", "query", "path", "secret");
        });
        assertThat(repeated.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ToolErrorCode.REPEATED_FAILURE);
            assertThat(error.details().values()).containsEntry("requiredStrategyChange", true);
        });
        assertThat(different.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
            assertThat(error.details().values()).containsEntry("removeFields", List.of("otherBad"));
        });
        assertThat(corrected.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(validations).hasValue(4);
        assertThat(executions).hasValue(1);
    }

    @Test
    void genericValidationFailureRecordsExactArgumentsAndBlocksIdenticalRetry() {
        AtomicInteger validations = new AtomicInteger();
        AgentTool tool = new RecordingAgentTool(
                "generic_invalid",
                ignored -> {
                    validations.incrementAndGet();
                    return ToolValidationResult.invalid("change arguments");
                },
                ignored -> { throw new AssertionError("invalid Tool must not execute"); });
        PipelineFixture fixture = fixture(tool);
        RunId runId = new RunId("run-generic-validation-fingerprint");
        JsonObject arguments = new JsonObject(Map.of("query", "same", "path", "same.txt"));

        ToolResult first = fixture.execute(runId, 1, "generic-1", "generic_invalid", arguments);
        ToolResult repeated = fixture.execute(runId, 2, "generic-2", "generic_invalid", arguments);

        assertThat(first.error()).get().extracting(ToolError::code)
                .isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(repeated.error()).get().extracting(ToolError::code)
                .isEqualTo(ToolErrorCode.REPEATED_FAILURE);
        assertThat(validations).hasValue(1);
    }

    @Test
    void skillVisibilityGatePersistsExecuteZeroAndSkipsAdapterAndPermission() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean permissionChecked = new AtomicBoolean();
        AgentTool tool = new RecordingAgentTool(
                "hidden",
                ignored -> ToolValidationResult.validResult(),
                ignored -> {
                    executed.set(true);
                    return ToolExecutionOutcome.success("should-not-run");
                });
        SkillId id = new SkillId("locked");
        var descriptor = new SkillDescriptor(id, "locked", SkillInvocationPolicy.BOTH, SkillSource.USER,
                "user/locked", "a".repeat(64), SkillToolRestriction.declared(List.of()), List.of(), List.of());
        var catalog = new ImmutableSkillCatalog(new SkillCatalogSnapshot("b".repeat(64), List.of(descriptor), List.of()));
        var invoker = new SkillInvoker(catalog,
                (snapshot, entry, cancellation) -> new SkillContentSnapshot(id, snapshot.snapshotId(),
                        entry.contentDigest(), "body"),
                (snapshot, entry, cancellation) -> List.of(), new SkillToolScopeNarrower());
        var skills = new SkillRunCoordinator(catalog, invoker, List.of("hidden"));
        RunId runId = new RunId("run-skill-gate");
        assertThat(skills.invokeExplicit(new SkillInvocationRequest(runId, id, SkillInvocationKind.EXPLICIT, ""),
                CancellationToken.none()).succeeded()).isTrue();
        var journal = new RecordingSessionJournal();
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(new ToolRegistry(List.of(tool)),
                (invocation, definition) -> {
                    permissionChecked.set(true);
                    throw new AssertionError("permission must not run");
                }, (invocation, definition, outcome) -> { throw new AssertionError("approval must not run"); },
                new InMemorySessionPermissionState(), lifecycle, journal, CheckpointCoordinator.noop(),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(), skills);

        ToolResult result = pipeline.execute(session, runId, 1,
                new ToolCall("call-hidden", "hidden", JsonObject.empty()));

        assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(executed).isFalse();
        assertThat(permissionChecked).isFalse();
        assertThat(journal.resolutionReasons).containsExactly(ToolResolutionReason.SKILL_SCOPE_DENIED);
    }

    @Test
    void planGateDeniesWriteUntilApprovedStepAndThenUsesPipeline() {
        AtomicBoolean sideEffect = new AtomicBoolean();
        PlanModeCoordinator plan = new PlanModeCoordinator(new PlanDocument("plan-pipeline", "write",
                List.of(new PlanStep(1, "write", "approved", "digest-a")), PlanStatus.DRAFT, "digest-a"));
        PipelineFixture fixture = fixtureWithPlan(fakeWriteTool(sideEffect), plan);
        ToolResult before = fixture.execute();
        assertThat(before.status()).isEqualTo(ToolResultStatus.FAILURE);
        assertThat(before.error()).get().extracting(ToolError::code)
                .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolErrorCode.PLAN_GATE_BLOCKED);
        assertThat(sideEffect).isFalse();
        plan.approve("digest-a");
        plan.beginNext("digest-a");
        ToolResult after = fixture.execute();
        assertThat(after.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(sideEffect).isTrue();
    }

    @Test
    void planGateRejectsDigestConflictAndKeepsWriteBlocked() {
        AtomicBoolean sideEffect = new AtomicBoolean();
        PlanModeCoordinator plan = new PlanModeCoordinator(new PlanDocument("plan-conflict", "write",
                List.of(new PlanStep(1, "write", "approved", "digest-a")), PlanStatus.DRAFT, "digest-a"));
        PipelineFixture fixture = fixtureWithPlan(fakeWriteTool(sideEffect), plan);
        plan.approve("changed");
        ToolResult result = fixture.execute();
        assertThat(result.error()).get().extracting(ToolError::code)
                .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolErrorCode.PLAN_GATE_BLOCKED);
        assertThat(sideEffect).isFalse();
    }

    @Test
    void planGateAllowsOnlyOneActiveStep() {
        PlanModeCoordinator plan = new PlanModeCoordinator(new PlanDocument("plan-one", "write",
                List.of(new PlanStep(1, "write", "approved", "digest-a")), PlanStatus.DRAFT, "digest-a"));
        plan.approve("digest-a");
        assertThat(plan.beginNext("digest-a")).isPresent();
        assertThat(plan.beginNext("digest-a")).isEmpty();
    }

    @Test
    void fakeWriteExecutesOnlyAfterAllowOnceAndDenialSkipsSideEffect() {
        AtomicBoolean allowedEffect = new AtomicBoolean();
        AgentTool allowedTool = fakeWriteTool(allowedEffect);
        PipelineFixture allowed = fixture(
                allowedTool,
                new FixedPermissionGate(PermissionMode.DEFAULT),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce());

        ToolResult allowedResult = allowed.execute();

        assertThat(allowedEffect).isTrue();
        assertThat(allowedResult.status()).isEqualTo(ToolResultStatus.SUCCESS);

        AtomicBoolean deniedEffect = new AtomicBoolean();
        AgentTool deniedTool = fakeWriteTool(deniedEffect);
        PipelineFixture denied = fixture(
                deniedTool,
                new FixedPermissionGate(PermissionMode.DEFAULT),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny());

        ToolResult deniedResult = denied.execute();

        assertThat(deniedEffect).isFalse();
        assertThat(deniedResult.status()).isEqualTo(ToolResultStatus.DENIED);
    }

    @Test
    void autoReviewOnlyHandlesFinalAskAndDoesNotTouchSessionPermissionState() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean userApprovalCalled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger gatewayCalls = new java.util.concurrent.atomic.AtomicInteger();
        InMemorySessionPermissionState state = new InMemorySessionPermissionState();
        AgentTool tool = fakeWriteTool(executed);
        PermissionGate finalAsk = (invocation, definition) -> PermissionOutcome.of(
                PermissionDecision.ASK,
                PermissionReason.EFFECT_DEFAULT,
                new PermissionSelector(definition.name(), definition.source(), "fixture-target"));
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        AgentSession session = new InMemorySessionStore(ids, lifecycle).create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(List.of(tool)), finalAsk,
                (invocation, definition, outcome) -> {
                    userApprovalCalled.set(true);
                    return ApprovalResponse.allowSession(outcome.selector());
                },
                state, lifecycle, SessionJournal.noop(), CheckpointCoordinator.noop(),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(), ApprovalReviewer.AUTO_REVIEW,
                new AutoReviewCoordinator((request, token) -> {
                    gatewayCalls.incrementAndGet();
                    return ApprovalReviewResult.allowOnce();
                }));
        RunId runId = new RunId("run-auto");

        try (AutoReviewRunScope scope = AutoReviewRunScope.enabled(runId)) {
            ToolResult result = pipeline.execute(session, runId, 1,
                    new ToolCall("call-auto", tool.definition().name(), JsonObject.empty()),
                    CancellationToken.none(), scope);

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(executed).isTrue();
            assertThat(gatewayCalls).hasValue(1);
            assertThat(userApprovalCalled).isFalse();
            assertThat(state.rules(session.id())).isEmpty();
            assertThat(state.denialCount(session.id(), new PermissionSelector(
                    tool.definition().name(), tool.definition().source(), "fixture-target"))).isZero();
            assertThat(events.envelopes())
                    .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.PermissionDecided)
                    .singleElement()
                    .satisfies(envelope -> assertThat(
                            ((LifecycleEvent.PermissionDecided) envelope.event()).outcome().reason())
                            .isEqualTo(PermissionReason.AUTO_REVIEW_ALLOW_ONCE));
        }
    }

    @Test
    void autoReviewDoesNotReachGatewayAfterPolicyOrHookAlreadyDecided() {
        java.util.concurrent.atomic.AtomicInteger gatewayCalls = new java.util.concurrent.atomic.AtomicInteger();
        AtomicBoolean executed = new AtomicBoolean();
        AgentTool tool = fakeWriteTool(executed);
        PermissionGate denied = (invocation, definition) -> PermissionOutcome.of(
                PermissionDecision.DENY,
                PermissionReason.HARD_DENIAL,
                PermissionSelector.toolWide(definition.name(), definition.source()));
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        AgentSession session = new InMemorySessionStore(ids, lifecycle).create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(List.of(tool)), denied,
                (invocation, definition, outcome) -> { throw new AssertionError("approval must not run"); },
                new InMemorySessionPermissionState(), lifecycle, SessionJournal.noop(), CheckpointCoordinator.noop(),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(),
                SkillRunCoordinator.disabled(), ApprovalReviewer.AUTO_REVIEW,
                new AutoReviewCoordinator((request, token) -> {
                    gatewayCalls.incrementAndGet();
                    return ApprovalReviewResult.allowOnce();
                }));
        RunId runId = new RunId("run-policy-deny");

        try (AutoReviewRunScope scope = AutoReviewRunScope.enabled(runId)) {
            ToolResult result = pipeline.execute(session, runId, 1,
                    new ToolCall("call-policy-deny", tool.definition().name(), JsonObject.empty()),
                    CancellationToken.none(), scope);

            assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(executed).isFalse();
            assertThat(gatewayCalls).hasValue(0);
            assertThat(events.envelopes())
                    .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.PermissionDecided)
                    .hasSize(1);
        }
    }

    private static AgentTool toolWithLimit(String content, int limit) {
        return new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "bounded",
                    "测试 Pipeline 最终输出上限",
                    "{\"type\":\"object\"}",
                    io.github.liumaishenjian.ccjava.domain.ToolEffect.READ_WORKSPACE,
                    io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN,
                    false,
                    Duration.ofSeconds(1),
                    "text/plain",
                    limit);

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolExecutionOutcome execute(ToolInvocation invocation) {
                return ToolExecutionOutcome.success(content);
            }
        };
    }

    private static AgentTool fakeWriteTool(AtomicBoolean sideEffect) {
        return new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "fake_write",
                    "不访问文件系统的审批验证工具",
                    "{\"type\":\"object\"}",
                    io.github.liumaishenjian.ccjava.domain.ToolEffect.WRITE_WORKSPACE,
                    io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN,
                    true,
                    Duration.ofSeconds(1),
                    "text/plain",
                    1024);

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public java.util.Optional<io.github.liumaishenjian.ccjava.domain.CheckpointTarget>
                    checkpointTarget(ToolInvocation invocation) {
                return java.util.Optional.of(
                        new io.github.liumaishenjian.ccjava.domain.CheckpointTarget(
                                "fixture/fake-write.txt", false));
            }

            @Override
            public ToolExecutionOutcome execute(ToolInvocation invocation) {
                sideEffect.set(true);
                return ToolExecutionOutcome.success("fake write completed");
            }
        };
    }

    private static PipelineFixture fixture(AgentTool tool) {
        return fixture(
                tool,
                (ignoredInvocation, definition) ->
                        io.github.liumaishenjian.ccjava.domain.PermissionOutcome.of(
                                PermissionDecision.ALLOW,
                                io.github.liumaishenjian.ccjava.domain.PermissionReason.EFFECT_DEFAULT,
                                io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                                        definition.name(), definition.source())),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce());
    }

    private static PipelineFixture fixture(List<AgentTool> tools) {
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        InMemorySessionStore sessions = new InMemorySessionStore(new SequentialAgentIdGenerator(), lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(tools),
                (ignoredInvocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) -> ApprovalResponse.allowOnce(),
                lifecycle);
        return new PipelineFixture(pipeline, session, tools.get(0).definition().name());
    }

    private static PipelineFixture fixtureWithPlan(AgentTool tool, PlanModeCoordinator plan) {
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        InMemorySessionStore sessions = new InMemorySessionStore(new SequentialAgentIdGenerator(), lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(List.of(tool)),
                (ignoredInvocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT, PermissionSelector.toolWide(definition.name(), definition.source())),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) -> ApprovalResponse.allowOnce(),
                new InMemorySessionPermissionState(), lifecycle, SessionJournal.noop(), CheckpointCoordinator.noop(),
                io.github.liumaishenjian.ccjava.core.hook.HookCoordinator.disabled(), SkillRunCoordinator.disabled(), plan);
        return new PipelineFixture(pipeline, session, tool.definition().name());
    }

    private static PipelineFixture fixture(
            AgentTool tool,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler) {
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(List.of(tool)),
                permissionGate,
                approvalHandler,
                lifecycle);
        return new PipelineFixture(pipeline, session, tool.definition().name());
    }

    private static final class RecordingSessionJournal implements SessionJournal {
        private final List<ToolResolutionReason> resolutionReasons = new java.util.ArrayList<>();
        @Override public void runStarted(io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                RunId runId, io.github.liumaishenjian.ccjava.domain.UserMessage message) { }
        @Override public void assistantAppended(io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                RunId runId, io.github.liumaishenjian.ccjava.domain.AssistantMessage message) { }
        @Override public void toolResolved(io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                RunId runId, int ordinal, ToolResult result, ToolResolutionReason reason) {
            resolutionReasons.add(reason);
        }
        @Override public void toolStarted(io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                RunId runId, int ordinal, String callId, String toolName,
                io.github.liumaishenjian.ccjava.domain.ToolEffect effect) { }
        @Override public void toolCompleted(io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                RunId runId, int ordinal, ToolResult result) { }
        @Override public void runCompleted(io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                RunId runId, io.github.liumaishenjian.ccjava.domain.StopReason stopReason) { }
    }

    private record PipelineFixture(
            ToolExecutionPipeline pipeline,
            AgentSession session,
            String toolName) {

        ToolResult execute() {
            return execute(new RunId("run-1"), 1, "call-1", toolName, JsonObject.empty());
        }

        ToolResult execute(RunId runId, int ordinal, String callId, String name, JsonObject arguments) {
            return pipeline.execute(session, runId, ordinal, new ToolCall(callId, name, arguments));
        }
    }
}
