package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutoReviewCoordinatorTest {

    private static final SessionId SESSION = new SessionId("session-1");
    private static final RunId RUN = new RunId("run-1");
    private static final PermissionSelector SCOPE = new PermissionSelector(
            "run_command", ToolSource.BUILT_IN, "scope-digest");

    @Test
    void onlyFinalAskCanReachGatewayAndStrictAllowIsOnceOnly() {
        AtomicInteger calls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            calls.incrementAndGet();
            return ApprovalReviewResult.allowOnce();
        });
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.DENY), request(),
                    CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.NOT_FINAL_ASK);
            assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ALLOW), request(),
                    CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.NOT_FINAL_ASK);
            assertThat(calls).hasValue(0);

            InMemorySessionPermissionState sessionState = new InMemorySessionPermissionState();
            AutoReviewDecision decision = coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                    CancellationToken.none(), circuit);
            assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
            assertThat(calls).hasValue(1);
            assertThat(sessionState.rules(SESSION)).isEmpty();
        }
    }

    @Test
    void autoFastPathAllowsRepeatedSafeLocalAndWebCallsWithoutClassifier() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        }, true);
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            PermissionOutcome read = PermissionOutcome.of(
                    PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                    new PermissionSelector("read_file", ToolSource.BUILT_IN, "src/Main.java"));
            ApprovalReviewRequest readRequest = new ApprovalReviewRequest(
                    SESSION, RUN, "read-1", "read_file", ToolEffect.READ_WORKSPACE,
                    ToolSource.BUILT_IN, true, "读取受控 Workspace 文件");
            assertThat(coordinator.reviewAuto(read, readRequest, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
            PermissionOutcome web = PermissionOutcome.of(
                    PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                    PermissionSelector.toolWide("web_search", ToolSource.BUILT_IN));
            ApprovalReviewRequest webRequest = new ApprovalReviewRequest(
                    SESSION, RUN, "web-1", "web_search", ToolEffect.NETWORK_OR_REMOTE,
                    ToolSource.BUILT_IN, false, "访问已配置的受信 Web Search");
            assertThat(coordinator.reviewAuto(web, webRequest, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
            assertThat(coordinator.reviewAuto(web, webRequest, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
            assertThat(gatewayCalls).hasValue(0);
        }
    }

    @Test
    void taskFastPathRequiresExactBuiltinNameAndEffectPair() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        });
        PermissionOutcome ask = PermissionOutcome.of(
                PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                PermissionSelector.toolWide("task_list", ToolSource.BUILT_IN));
        ApprovalReviewRequest exact = new ApprovalReviewRequest(
                SESSION, RUN, "task-exact", "task_list", ToolEffect.READ_SESSION_STATE,
                ToolSource.BUILT_IN, false, "读取 Session Task 摘要");
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewAuto(ask, exact, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
        }

        List<ApprovalReviewRequest> spoofed = List.of(
                new ApprovalReviewRequest(SESSION, RUN, "task-wrong-effect", "task_list",
                        ToolEffect.WRITE_SESSION_STATE, ToolSource.BUILT_IN, false, "错误 Effect"),
                new ApprovalReviewRequest(SESSION, RUN, "task-unknown", "unknown_task",
                        ToolEffect.READ_SESSION_STATE, ToolSource.BUILT_IN, false, "未知名称"),
                new ApprovalReviewRequest(SESSION, RUN, "task-mcp", "task_list",
                        ToolEffect.READ_SESSION_STATE, ToolSource.MCP, false, "外部来源"));
        for (ApprovalReviewRequest request : spoofed) {
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                assertThat(coordinator.reviewAuto(ask, request, CancellationToken.none(), circuit).status())
                        .isEqualTo(AutoReviewDecision.Status.DENY);
            }
        }
        assertThat(gatewayCalls).hasValue(3);
    }

    @Test
    void unconfiguredWebSearchNeverUsesFastPath() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        });
        PermissionOutcome web = PermissionOutcome.of(
                PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                PermissionSelector.toolWide("web_search", ToolSource.BUILT_IN));
        ApprovalReviewRequest request = new ApprovalReviewRequest(
                SESSION, RUN, "web-1", "web_search", ToolEffect.NETWORK_OR_REMOTE,
                ToolSource.BUILT_IN, false, "未配置的 Web Search");
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewAuto(web, request, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.DENY);
            assertThat(gatewayCalls).hasValue(1);
        }
    }

    @Test
    void untrustedOrUnknownNetworkToolNeverUsesFastPath() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        }, true);
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            for (ToolSource source : new ToolSource[]{ToolSource.MCP, ToolSource.PLUGIN}) {
                PermissionOutcome outcome = PermissionOutcome.of(
                        PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide("web_search", source));
                ApprovalReviewRequest request = new ApprovalReviewRequest(
                        SESSION, RUN, "web-" + source, "web_search", ToolEffect.NETWORK_OR_REMOTE,
                        source, false, "外部来源 Web Search");
                assertThat(coordinator.reviewAuto(outcome, request, CancellationToken.none(), circuit).status())
                        .isEqualTo(AutoReviewDecision.Status.DENY);
            }
            PermissionOutcome unknown = PermissionOutcome.of(
                    PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                    PermissionSelector.toolWide("unknown_network", ToolSource.BUILT_IN));
            ApprovalReviewRequest unknownRequest = new ApprovalReviewRequest(
                    SESSION, RUN, "unknown-1", "unknown_network", ToolEffect.NETWORK_OR_REMOTE,
                    ToolSource.BUILT_IN, false, "未知网络 Tool");
            assertThat(coordinator.reviewAuto(unknown, unknownRequest, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.DENY);
        }
        assertThat(gatewayCalls).hasValue(3);
    }

    @Test
    void scopedConfiguredWebSearchStillUsesClassifier() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        }, true);
        PermissionOutcome web = PermissionOutcome.of(
                PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                new PermissionSelector("web_search", ToolSource.BUILT_IN, "query-scope"));
        ApprovalReviewRequest request = new ApprovalReviewRequest(
                SESSION, RUN, "web-scoped", "web_search", ToolEffect.NETWORK_OR_REMOTE,
                ToolSource.BUILT_IN, true, "带具体 scope 的 Web Search");
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewAuto(web, request, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.DENY);
        }
        assertThat(gatewayCalls).hasValue(1);
    }

    @Test
    void nonAllowlistedReadReachesClassifier() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        });
        PermissionOutcome read = PermissionOutcome.of(
                PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT,
                new PermissionSelector("custom_read", ToolSource.BUILT_IN, "scope"));
        ApprovalReviewRequest request = new ApprovalReviewRequest(
                SESSION, RUN, "call-1", "custom_read", ToolEffect.READ_WORKSPACE,
                ToolSource.BUILT_IN, true, "读取受控内容");
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewAuto(read, request, CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.DENY);
            assertThat(gatewayCalls).hasValue(1);
        }
    }

    @Test
    void fastPathChecksCancellationAndCircuitBeforeAllowing() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            gatewayCalls.incrementAndGet();
            return ApprovalReviewResult.deny();
        });
        PermissionOutcome read = outcome(PermissionDecision.ASK);
        ApprovalReviewRequest request = new ApprovalReviewRequest(
                SESSION, RUN, "call-1", "read_file", ToolEffect.READ_WORKSPACE,
                ToolSource.BUILT_IN, true, "读取受控文件");
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> coordinator.reviewAuto(read, request, cancelled.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(gatewayCalls).hasValue(0);
        }
        AutoReviewCircuit closed = new AutoReviewCircuit(RUN);
        closed.close();
        assertThat(coordinator.reviewAuto(read, request, CancellationToken.none(), closed).status())
                .isEqualTo(AutoReviewDecision.Status.RUN_CLOSED);
        assertThat(gatewayCalls).hasValue(0);
    }

    @Test
    void fastPathAllowResetsCircuitState() {
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> ApprovalReviewResult.deny());
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                    CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.DENY);
            assertThat(circuit.consecutiveFailures()).isOne();
            ApprovalReviewRequest safe = new ApprovalReviewRequest(
                    SESSION, RUN, "safe-1", "read_file", ToolEffect.READ_WORKSPACE,
                    ToolSource.BUILT_IN, true, "读取文件");
            assertThat(coordinator.reviewAuto(outcome(PermissionDecision.ASK), safe,
                    CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
            assertThat(circuit.consecutiveFailures()).isZero();
        }
    }

    @Test
    void providerTimeoutParseInternalNullAndExceptionFailClosed() {
        for (ApprovalReviewResult.FailureKind kind : new ApprovalReviewResult.FailureKind[]{
                ApprovalReviewResult.FailureKind.PROVIDER,
                ApprovalReviewResult.FailureKind.TIMEOUT,
                ApprovalReviewResult.FailureKind.PARSE,
                ApprovalReviewResult.FailureKind.INTERNAL
        }) {
            AutoReviewCoordinator coordinator = new AutoReviewCoordinator(
                    (request, token) -> ApprovalReviewResult.failure(kind));
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                AutoReviewDecision decision = coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                        CancellationToken.none(), circuit);
                assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.FAILED_CLOSED);
                assertThat(decision.failure()).contains(kind);
            }
        }
        for (ApprovalReviewGateway gateway : new ApprovalReviewGateway[]{
                (request, token) -> null,
                (request, token) -> { throw new IllegalStateException("sentinel"); }
        }) {
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                AutoReviewDecision decision = new AutoReviewCoordinator(gateway).reviewFinalAsk(
                        outcome(PermissionDecision.ASK), request(), CancellationToken.none(), circuit);
                assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.FAILED_CLOSED);
                assertThat(decision.failure()).contains(ApprovalReviewResult.FailureKind.INTERNAL);
            }
        }
    }

    @Test
    void cancellationPropagatesAndDoesNotCountCircuit() {
        CancellationSource before = new CancellationSource();
        before.cancel();
        AtomicInteger calls = new AtomicInteger();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> new AutoReviewCoordinator((request, token) -> {
                calls.incrementAndGet();
                return ApprovalReviewResult.allowOnce();
            }).reviewFinalAsk(outcome(PermissionDecision.ASK), request(), before.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(calls).hasValue(0);
            assertThat(circuit.consecutiveFailures()).isZero();
        }

        CancellationSource during = new CancellationSource();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> new AutoReviewCoordinator((request, token) -> {
                during.cancel();
                return ApprovalReviewResult.allowOnce();
            }).reviewFinalAsk(outcome(PermissionDecision.ASK), request(), during.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(circuit.consecutiveFailures()).isZero();
        }
        CancellationSource reported = new CancellationSource();
        reported.cancel();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> new AutoReviewCoordinator((request, token) ->
                    ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.CANCELLED))
                    .reviewFinalAsk(outcome(PermissionDecision.ASK), request(), reported.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(circuit.consecutiveFailures()).isZero();
        }
    }

    @Test
    void fabricatedGatewayCancellationFailsClosedAndCountsAsInternal() {
        for (ApprovalReviewGateway gateway : new ApprovalReviewGateway[]{
                (request, token) -> { throw new java.util.concurrent.CancellationException("fabricated"); },
                (request, token) -> ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.CANCELLED)
        }) {
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                AutoReviewDecision decision = new AutoReviewCoordinator(gateway).reviewFinalAsk(
                        outcome(PermissionDecision.ASK), request(), CancellationToken.none(), circuit);

                assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.FAILED_CLOSED);
                assertThat(decision.failure()).contains(ApprovalReviewResult.FailureKind.INTERNAL);
                assertThat(circuit.consecutiveFailures()).isOne();
            }
        }
    }

    @Test
    void denyAndFailureAccumulateAllowResetsAndThirdCurrentDecisionRequestsStop() {
        AtomicInteger calls = new AtomicInteger();
        ApprovalReviewResult[] results = {
                ApprovalReviewResult.deny(),
                ApprovalReviewResult.allowOnce(),
                ApprovalReviewResult.deny(),
                ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.PROVIDER),
                ApprovalReviewResult.deny()
        };
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) ->
                results[calls.getAndIncrement()]);
        AutoReviewCircuit circuit = new AutoReviewCircuit(RUN);
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).stopAfterCurrentDeny()).isFalse();
        assertThat(circuit.consecutiveFailures()).isOne();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).status()).isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).stopAfterCurrentDeny()).isFalse();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).stopAfterCurrentDeny()).isFalse();
        AutoReviewDecision third = coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit);
        assertThat(third.status()).isEqualTo(AutoReviewDecision.Status.DENY);
        assertThat(third.stopAfterCurrentDeny()).isTrue();
        assertThat(circuit.consecutiveFailures()).isEqualTo(3);
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).status())
                .isEqualTo(AutoReviewDecision.Status.CIRCUIT_OPEN);
        assertThat(calls).hasValue(5);
        circuit.close();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).status())
                .isEqualTo(AutoReviewDecision.Status.RUN_CLOSED);
    }

    private static PermissionOutcome outcome(PermissionDecision decision) {
        return PermissionOutcome.of(decision, PermissionReason.EFFECT_DEFAULT, SCOPE);
    }

    private static ApprovalReviewRequest request() {
        return new ApprovalReviewRequest(
                SESSION, RUN, "call-1", "run_command", ToolEffect.EXECUTE_PROCESS,
                ToolSource.BUILT_IN, true, "执行受控测试命令");
    }
}
