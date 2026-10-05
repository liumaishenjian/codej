package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR-100 / CTX-07/09：通过实际 prepare、compact 和 overflow 证伪模型绑定及状态所有权。 */
class ContextModelBindingTest {
    private static final SessionId SESSION = new SessionId("context-binding-session");
    private static final RunId RUN = new RunId("context-binding-run");
    private static final List<AgentMessage> HISTORY = List.of(
            new SystemMessage("s"), new UserMessage("a".repeat(80)),
            AssistantMessage.text("b".repeat(80)), new UserMessage("tail"));

    @Test
    void reboundPreparationCallsOnlyBoundSummaryPort() {
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger boundCalls = new AtomicInteger();
        ContextPreparationService root = service(130, summary(oldCalls, "old"), ContextUsageObserver.noop());
        ContextPreparationService rebound = root.withSummarizer(summary(boundCalls, "bound"));

        ModelRequest prepared = rebound.prepare(request(HISTORY), CancellationToken.none());

        assertThat(summaryText(prepared)).containsExactly("bound");
        assertThat(boundCalls).hasValue(1);
        assertThat(oldCalls).hasValue(0);
        assertThat(prepared.messages().getLast()).isEqualTo(HISTORY.getLast());
        assertThat(request(HISTORY).messages()).containsExactlyElementsOf(HISTORY);
    }

    @Test
    void reboundConsumesPreviouslyInstalledProjectionExactlyOnceAcrossRootViews() {
        ContextPreparationService root = service(1_000, summary(new AtomicInteger(), "saved"), ContextUsageObserver.noop());
        ContextProjection compact = root.compact(HISTORY, List.of(), CancellationToken.none()).projection().orElseThrow();
        root.installForNextRun(HISTORY, compact);
        ContextPreparationService rebound = root.withSummarizer((r, c) -> {
            throw new AssertionError("installed projection must not request a new summary");
        });

        assertThat(rebound.prepare(request(HISTORY), CancellationToken.none()).messages())
                .containsExactlyElementsOf(compact.messages());
        assertThat(root.prepare(request(HISTORY), CancellationToken.none()).messages())
                .containsExactlyElementsOf(HISTORY);
        assertThat(rebound.prepare(request(HISTORY), CancellationToken.none()).messages())
                .containsExactlyElementsOf(HISTORY);
    }

    @Test
    void sharedContainersRetainLateWritesAndSurviveAnotherViewsCloseRun() {
        ContextPreparationService root = service(1_000, summary(new AtomicInteger(), "saved"), ContextUsageObserver.noop());
        ContextPreparationService rebound = root.withSummarizer(summary(new AtomicInteger(), "bound"));
        ContextProjection compact = root.compact(HISTORY, List.of(), CancellationToken.none()).projection().orElseThrow();
        // 两个写入都发生在创建视图之后，复制 map 或复制 AtomicReference 值会丢失它们。
        root.installForNextRun(HISTORY, compact);
        root.recordExternalContext(SESSION, "late-root-context");
        root.closeRun(RUN);

        ModelRequest prepared = rebound.prepare(request(HISTORY), CancellationToken.none());
        assertThat(summaryText(prepared)).containsExactly("saved");
        assertThat(prepared.messages().getLast()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.content()).contains("late-root-context", "trust=\"untrusted\""));
        assertThat(root.prepare(request(HISTORY), CancellationToken.none()).messages()).isEqualTo(HISTORY);
        rebound.recordExternalContext(SESSION, "late-view-context");
        assertThat(root.prepare(request(HISTORY), CancellationToken.none()).messages().getLast())
                .isInstanceOfSatisfying(SystemMessage.class,
                        message -> assertThat(message.content()).contains("late-view-context"));
    }

    @Test
    void sameRunIdentityDoesNotShareCooldownAcrossViewsAndCloseIsLocal() {
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicInteger boundCalls = new AtomicInteger();
        ContextPreparationService root = service(130, rejecting(rootCalls), ContextUsageObserver.noop());
        ContextPreparationService rebound = root.withSummarizer(rejecting(boundCalls));
        root.prepare(request(HISTORY), CancellationToken.none());
        rebound.prepare(request(HISTORY), CancellationToken.none());
        assertThat(rootCalls).hasValue(2);
        assertThat(boundCalls).hasValue(2);

        root.closeRun(RUN);
        rebound.prepare(request(HISTORY), CancellationToken.none());
        assertThat(boundCalls).hasValue(2);
        root.prepare(request(HISTORY), CancellationToken.none());
        assertThat(rootCalls).hasValue(4);
    }

    @Test
    void closingRootDoesNotDiscardPreparedOverflowRecoveryInBoundView() throws Exception {
        AtomicInteger boundCalls = new AtomicInteger();
        ContextPreparationService root = service(1_000, rejecting(new AtomicInteger()), ContextUsageObserver.noop());
        ContextPreparationService rebound = root.withSummarizer(summary(boundCalls, "bound"));
        root.prepare(request(HISTORY), CancellationToken.none());
        ModelRequest prepared = rebound.prepare(request(HISTORY), CancellationToken.none());
        root.closeRun(RUN);
        AtomicInteger attempts = new AtomicInteger();

        String result = rebound.executePrepared(prepared, CancellationToken.none(), modelRequest -> {
            if (attempts.incrementAndGet() == 1) throw overflow();
            assertThat(summaryText(modelRequest)).containsExactly("bound");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(2);
        assertThat(boundCalls).hasValue(1);
    }

    @Test
    void childNeitherConsumesParentStateNorPublishesItsOwnStateToParent() {
        ContextPreparationService root = service(1_000, summary(new AtomicInteger(), "parent"), ContextUsageObserver.noop());
        ContextProjection parentCompact = root.compact(HISTORY, List.of(), CancellationToken.none()).projection().orElseThrow();
        root.installForNextRun(HISTORY, parentCompact);
        root.recordExternalContext(SESSION, "parent-only");
        ContextPreparationService child = root.forkForChild(summary(new AtomicInteger(), "child"));

        assertThat(child.prepare(request(HISTORY), CancellationToken.none()).messages()).isEqualTo(HISTORY);
        ContextProjection childCompact = child.compact(HISTORY, List.of(), CancellationToken.none()).projection().orElseThrow();
        child.installForNextRun(HISTORY, childCompact);
        child.recordExternalContext(SESSION, "child-only");
        child.closeRun(RUN);
        ModelRequest parentPrepared = root.prepare(request(HISTORY), CancellationToken.none());
        assertThat(summaryText(parentPrepared)).containsExactly("parent");
        assertThat(parentPrepared.messages().getLast()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.content()).contains("parent-only").doesNotContain("child-only"));
        root.closeRun(RUN);
        ModelRequest childPrepared = child.prepare(request(HISTORY), CancellationToken.none());
        assertThat(summaryText(childPrepared)).containsExactly("child");
        assertThat(childPrepared.messages().getLast()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.content()).contains("child-only").doesNotContain("parent-only"));
        assertThat(root.prepare(request(HISTORY), CancellationToken.none()).messages()).isEqualTo(HISTORY);
    }

    @Test
    void rootObserverIsRetainedAndChildObserverIsExplicitlyIsolated() {
        List<ContextUsageView> parentEvents = new ArrayList<>();
        List<ContextUsageView> childEvents = new ArrayList<>();
        ContextSummarizer summarizer = summary(new AtomicInteger(), "brief");
        ContextPreparationService root = service(1_000, summarizer, parentEvents::add);
        root.withSummarizer(summarizer).prepare(request(HISTORY), CancellationToken.none());
        assertThat(parentEvents).hasSize(1);
        root.forkForChild(summarizer).prepare(request(HISTORY), CancellationToken.none());
        assertThat(parentEvents).hasSize(1);
        root.forkForChild(summarizer, Optional.empty(), OptionalLong.empty(), childEvents::add)
                .prepare(request(List.of(new UserMessage("child"))), CancellationToken.none());
        assertThat(parentEvents).hasSize(1);
        assertThat(childEvents).singleElement().satisfies(view -> assertThat(view.usage().totalTokens()).isEqualTo(5));
    }

    @Test
    void declaredWindowsOnlyNarrowAndNeverReduceOutputOrSafetyReservations() {
        List<ContextUsageView> events = new ArrayList<>();
        ContextSummarizer summarizer = summary(new AtomicInteger(), "brief");
        ContextPreparationService root = service(1_000, summarizer, events::add);
        ContextPreparationService narrow = root.withSummarizer(summarizer, Optional.of("smaller"), OptionalLong.of(130));
        assertThat(summaryText(narrow.prepare(request(HISTORY), CancellationToken.none()))).containsExactly("brief");
        assertCapacity(events.getLast(), 130);
        narrow.withSummarizer(summarizer, Optional.empty(), OptionalLong.of(5_000))
                .prepare(request(HISTORY), CancellationToken.none());
        assertCapacity(events.getLast(), 130);
        root.withSummarizer(summarizer, Optional.of("larger"), OptionalLong.of(5_000))
                .prepare(request(HISTORY), CancellationToken.none());
        assertCapacity(events.getLast(), 1_000);
        root.forkForChild(summarizer, Optional.of("child"), OptionalLong.of(130), events::add)
                .prepare(request(HISTORY), CancellationToken.none());
        assertCapacity(events.getLast(), 130);
        for (long invalid : new long[] {-1, 0, 29, 30}) {
            assertThatThrownBy(() -> root.withSummarizer(summarizer, Optional.empty(), OptionalLong.of(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> root.forkForChild(summarizer, Optional.empty(), OptionalLong.of(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> root.withSummarizer(summarizer, Optional.of(" "), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        // 保留空间恰好之外的一枚输入 token 合法；不私自减去输出/安全余量。
        root.withSummarizer(summarizer, Optional.empty(), OptionalLong.of(31))
                .prepare(request(List.of(new UserMessage("x"))), CancellationToken.none());
        assertCapacity(events.getLast(), 31);
    }

    @Test
    void explicitlyDisabledParentRemainsDisabledForRootAndChildBindings() {
        AtomicInteger calls = new AtomicInteger();
        List<ContextUsageView> events = new ArrayList<>();
        ContextSummarizer summarizer = summary(calls, "brief");
        ContextPreparationService disabled = ContextPreparationService.noop();
        List<ContextPreparationService> views = List.of(disabled.withSummarizer(summarizer),
                disabled.forkForChild(summarizer, Optional.of("child"), OptionalLong.of(130), events::add));
        ModelRequest canonical = request(HISTORY);
        for (ContextPreparationService view : views) {
            assertThat(view.prepare(canonical, CancellationToken.none())).isSameAs(canonical);
            assertThat(view.compact(HISTORY, List.of(), CancellationToken.none()).status())
                    .isEqualTo(ContextPreparationService.ExplicitCompactStatus.UNAVAILABLE);
        }
        assertThat(calls).hasValue(0);
        assertThat(events).isEmpty();
    }

    @Test
    void disabledRootStillSharesLateExternalContextButDisabledChildDoesNot() {
        ContextPreparationService root = ContextPreparationService.noop();
        ContextSummarizer never = (r, c) -> {
            throw new AssertionError("explicitly disabled context cannot summarize");
        };
        ContextPreparationService rebound = root.withSummarizer(never);
        ContextPreparationService child = root.forkForChild(never);
        root.recordExternalContext(SESSION, "late-disabled-context");
        assertThat(child.prepare(request(HISTORY), CancellationToken.none()).messages()).isEqualTo(HISTORY);
        assertThat(rebound.prepare(request(HISTORY), CancellationToken.none()).messages().getLast())
                .isInstanceOfSatisfying(SystemMessage.class,
                        message -> assertThat(message.content()).contains("late-disabled-context"));
        assertThat(root.prepare(request(HISTORY), CancellationToken.none()).messages()).isEqualTo(HISTORY);
    }

    @Test
    void repeatedRunlessCompactsDoNotConsumeOrdinaryRunsSingleOverflowRecovery() throws Exception {
        AtomicInteger summaries = new AtomicInteger();
        ContextPreparationService service = service(1_000, summary(summaries, "brief"), ContextUsageObserver.noop());
        for (int i = 0; i < 2; i++) {
            assertThat(service.compact(HISTORY, List.of(), CancellationToken.none()).status())
                    .isEqualTo(ContextPreparationService.ExplicitCompactStatus.ADOPTED);
        }
        ModelRequest prepared = service.prepare(request(HISTORY), CancellationToken.none());
        AtomicInteger attempts = new AtomicInteger();
        assertThat(service.<String>executePrepared(prepared, CancellationToken.none(), r -> {
            if (attempts.incrementAndGet() == 1) throw overflow();
            return "done";
        })).isEqualTo("done");
        assertThat(attempts).hasValue(2);
        assertThat(summaries).hasValue(3);
        assertThat(service.compact(HISTORY, List.of(), CancellationToken.none()).status())
                .isEqualTo(ContextPreparationService.ExplicitCompactStatus.ADOPTED);
        ModelRequest next = service.prepare(request(HISTORY), CancellationToken.none());
        AtomicInteger nextAttempts = new AtomicInteger();
        assertThatThrownBy(() -> service.executePrepared(next, CancellationToken.none(), r -> {
            nextAttempts.incrementAndGet();
            throw overflow();
        })).isInstanceOf(ModelGatewayException.class);
        assertThat(nextAttempts).hasValue(1);
        assertThat(summaries).hasValue(4);
    }

    @Test
    void cancelledAndFailedRunlessCompactsPreserveCanonicalAndDoNotInstallCandidates() {
        List<AgentMessage> canonical = new ArrayList<>(HISTORY);
        CancellationSource cancelled = new CancellationSource();
        ContextPreparationService service = service(1_000, (request, token) -> {
            assertThat(token).isSameAs(cancelled.token());
            cancelled.cancel();
            return Optional.of(candidate(request, "brief"));
        }, ContextUsageObserver.noop());
        assertThat(service.compact(canonical, List.of(), cancelled.token()).status())
                .isEqualTo(ContextPreparationService.ExplicitCompactStatus.CANCELLED);
        ContextPreparationService failed = service.withSummarizer((request, token) -> {
            throw new IllegalStateException("untrusted failure detail");
        });
        assertThat(failed.compact(canonical, List.of(), CancellationToken.none()).status())
                .isEqualTo(ContextPreparationService.ExplicitCompactStatus.SUMMARIZER_REJECTED);
        assertThat(canonical).containsExactlyElementsOf(HISTORY);
        assertThat(service.prepare(request(canonical), CancellationToken.none()).messages()).isEqualTo(HISTORY);
        assertThat(failed.prepare(request(canonical), CancellationToken.none()).messages()).isEqualTo(HISTORY);
    }

    @Test
    void oldCompactEntryDelegatesToIdenticalRunlessBehaviorAndKeepsSummaryLimits() {
        List<SummaryRequest> calls = new ArrayList<>();
        ContextSummarizer summarizer = (r, token) -> {
            calls.add(r);
            return Optional.of(candidate(r, "KEEP"));
        };
        ContextPreparationService root = service(1_000, summarizer, ContextUsageObserver.noop());
        ContextPreparationService bound = root.withSummarizer(summarizer, Optional.of("new"), OptionalLong.of(800));
        // 锚点必须来自待归纳快照；否则生产Gate会在调用摘要端口前正确拒绝。
        List<AgentMessage> anchored = List.of(HISTORY.getFirst(), new UserMessage("KEEP " + "a".repeat(80)),
                HISTORY.get(2), HISTORY.getLast());
        var oldResult = bound.compact(request(anchored), List.of("KEEP"), CancellationToken.none());
        var newResult = bound.compact(anchored, List.of("KEEP"), CancellationToken.none());
        assertThat(oldResult).isEqualTo(newResult);
        assertThat(calls).hasSize(2).allSatisfy(r -> {
            assertThat(r.maxOutputUtf8Bytes()).isEqualTo(64);
            assertThat(r.maxOutputTokens()).isEqualTo(32);
            assertThat(r.requiredProtectedAnchors()).containsExactly("KEEP");
        });
        assertThat(newResult.projection().orElseThrow().messages().getLast()).isEqualTo(HISTORY.getLast());
    }

    @Test
    void installedBudgetRejectsSmallWindowWithoutConsumingParentAndAllowsExplicitReselection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ContextUsageView> events = new ArrayList<>();
        ContextSummarizer summarizer = summary(calls, "brief");
        ContextPreparationService root = new ContextPreparationService(new ContextPreparationConfig(
                new ContextCapacity("launcher", 256_000, 8192, 4096), 40, 1, 64, 32), summarizer, events::add);
        root.recordExternalContext(SESSION, "parent-pending");
        AtomicInteger modelCalls = new AtomicInteger();
        assertThatThrownBy(() -> {
            var bound = root.withSummarizer(summarizer, Optional.of("gpt-4"), OptionalLong.of(8192));
            bound.executePrepared(bound.prepare(request(HISTORY), CancellationToken.none()),
                    CancellationToken.none(), r -> modelCalls.incrementAndGet());
        }).isExactlyInstanceOf(ModelContextBudgetException.class)
                .hasMessage("Model context window cannot accommodate reserved context budget").hasNoCause();
        assertThatThrownBy(() -> root.forkForChild(summarizer, Optional.of("sensitive-model"),
                OptionalLong.of(12288), events::add)).isExactlyInstanceOf(ModelContextBudgetException.class)
                .hasMessage("Model context window cannot accommodate reserved context budget").hasNoCause();
        assertThat(modelCalls).hasValue(0);
        assertThat(calls).hasValue(0);
        assertThat(events).isEmpty();
        assertThat(root.prepare(request(HISTORY), CancellationToken.none()).messages().getLast())
                .isInstanceOfSatisfying(SystemMessage.class, m -> assertThat(m.content()).contains("parent-pending"));
        assertThat(events.getLast().maximumInputTokens()).isEqualTo(256_000);
        root.recordExternalContext(SESSION, "parent-pending");
        var rebound = root.withSummarizer(summarizer, Optional.of("gpt-4-turbo"), OptionalLong.of(128_000));
        var prepared = rebound.prepare(request(HISTORY), CancellationToken.none());
        assertThat(prepared.messages().getLast()).isInstanceOfSatisfying(SystemMessage.class,
                m -> assertThat(m.content()).contains("parent-pending"));
        assertThat(events.getLast().maximumInputTokens()).isEqualTo(128_000);
        assertThat(events.getLast().reservedOutputTokens()).isEqualTo(8192);
        assertThat(events.getLast().safetyMarginTokens()).isEqualTo(4096);
        rebound.executePrepared(prepared, CancellationToken.none(), r -> modelCalls.incrementAndGet());
        assertThat(modelCalls).hasValue(1);
        for (long invalid : new long[] {0, -1}) {
            assertThatThrownBy(() -> root.withSummarizer(summarizer, Optional.empty(), OptionalLong.of(invalid)))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertCapacity(ContextUsageView view, long maximum) {
        assertThat(view.maximumInputTokens()).isEqualTo(maximum);
        assertThat(view.reservedOutputTokens()).isEqualTo(20);
        assertThat(view.safetyMarginTokens()).isEqualTo(10);
        assertThat(view.availableInputTokens()).isEqualTo(maximum - 30);
    }

    private static ContextPreparationService service(long maximum, ContextSummarizer summarizer, ContextUsageObserver observer) {
        return new ContextPreparationService(new ContextPreparationConfig(
                new ContextCapacity("original", maximum, 20, 10), 40, 1, 64, 32), summarizer, observer);
    }

    private static ModelRequest request(List<AgentMessage> messages) {
        return new ModelRequest(SESSION, RUN, 1, messages, List.of());
    }

    private static List<String> summaryText(ModelRequest request) {
        return request.messages().stream().filter(ContextSummaryMessage.class::isInstance)
                .map(ContextSummaryMessage.class::cast).map(ContextSummaryMessage::content).toList();
    }

    private static ContextSummarizer summary(AtomicInteger calls, String text) {
        return (request, token) -> {
            calls.incrementAndGet();
            return Optional.of(candidate(request, text));
        };
    }

    private static ContextSummarizer rejecting(AtomicInteger calls) {
        return (request, token) -> {
            calls.incrementAndGet();
            return Optional.empty();
        };
    }

    private static SummaryCandidate candidate(SummaryRequest request, String text) {
        return new SummaryCandidate(request.tier(), text, request.sourceRevision(),
                request.sourceMessageIds(), text.length(), text.length());
    }

    private static ModelGatewayException overflow() {
        return new ModelGatewayException(ModelGatewayException.FailureKind.CONTEXT_OVERFLOW, "overflow");
    }
}
