package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

class StdioProtocolFixtureCleanupTest {
    @TempDir Path temporary;

    @Test
    void isolatedPlanFixtureCorrectsDeliverableThroughRealToolsAndCleansTemporaryWorkspace() throws Exception {
        Files.writeString(temporary.resolve("parent-sentinel.txt"), "outside plan workspace");
        java.util.Set<String> beforePlan = fixtureDirectories(temporary, "plan-runtime-");
        StdioProtocol.CommandHandler plan = StdioProtocolFixtureMain.planRuntimeHandlerForTest(temporary);
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, requestId, sessionId, runId, payload.deepCopy()));
        try {
            java.util.Set<String> activeRoots = fixtureDirectories(temporary, "plan-runtime-");
            assertThat(activeRoots).hasSize(beforePlan.size() + 1);
            String created = activeRoots.stream().filter(value -> !beforePlan.contains(value)).findFirst().orElseThrow();
            Path fixtureRoot = temporary.resolve(created);
            assertThat(fixtureRoot.resolve("workspace/.git")).isDirectory();
            assertThat(fixtureRoot.resolve("provider")).isDirectory();

            StdioProtocolCodec codec = new StdioProtocolCodec();
            plan.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\","
                            + "\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = awaitEvent(events, "initialized", "init").sessionId().orElseThrow();
            plan.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.start\","
                    + "\"requestId\":\"plan\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"plan\"}}").formatted(sessionId)), emitter);
            CapturedEvent review = awaitEvent(events, "plan.review.requested", "plan");
            awaitEvent(events, "run.completed", "plan");

            ObjectNode reviewPayload = review.payload();
            plan.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"plan.review.resolve\","
                    + "\"requestId\":\"execute\",\"sessionId\":\"%s\",\"sequence\":3,"
                    + "\"payload\":{\"planId\":\"%s\",\"revision\":%d,"
                    + "\"contentDigest\":\"%s\",\"workspaceDigest\":\"%s\","
                    + "\"decision\":\"APPROVE_USER\",\"contextPolicy\":\"KEEP\",\"feedback\":\"\"}}")
                    .formatted(sessionId, reviewPayload.get("planId").stringValue(),
                            reviewPayload.get("revision").longValue(),
                            reviewPayload.get("contentDigest").stringValue(),
                            reviewPayload.get("workspaceDigest").stringValue())), emitter);

            awaitEvent(events, "plan.execution.accepted", "execute");
            CapturedEvent executionStarted = awaitEvent(events, "run.started", "execute");
            CapturedEvent firstApproval = awaitEvent(events, "approval.requested", "execute");
            plan.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approval-1\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":4,\"payload\":{\"approvalId\":\"%s\",\"decision\":\"allow_once\"}}")
                    .formatted(sessionId, executionStarted.runId().orElseThrow(),
                            firstApproval.payload().get("approvalId").stringValue())), emitter);
            awaitEventCount(events, "tool.completed", "execute", 2);
            awaitEvent(events, "plan.verification.correction", "execute");
            CapturedEvent secondApproval = awaitEventCount(events, "approval.requested", "execute", 2);
            plan.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approval-2\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":5,\"payload\":{\"approvalId\":\"%s\",\"decision\":\"allow_once\"}}")
                    .formatted(sessionId, executionStarted.runId().orElseThrow(),
                            secondApproval.payload().get("approvalId").stringValue())), emitter);
            awaitEventCount(events, "tool.completed", "execute", 4);
            awaitEvent(events, "plan.verification.completed", "execute");
            CapturedEvent terminal = awaitEvent(events, "run.completed", "execute");

            assertThat(events.stream().filter(event -> event.type().equals("tool.started")
                    && event.requestId().equals("execute"))
                    .map(event -> event.payload().get("toolName").stringValue()))
                    .containsExactly("task_update", "write_file", "write_file", "task_update");
            assertThat(events.stream().filter(event -> event.type().equals("tool.completed")
                    && event.requestId().equals("execute"))).hasSize(4);
            assertThat(events.stream().filter(event -> event.type().equals("task.board.snapshot")
                    && event.requestId().equals("execute"))
                    .map(event -> event.payload().get("boardRevision").longValue()))
                    .containsExactly(1L, 2L, 3L);
            assertThat(events.stream().filter(event -> event.requestId().equals("execute"))
                    .map(CapturedEvent::type)).containsSubsequence(
                            "plan.execution.accepted", "run.started", "tool.started", "tool.completed",
                            "plan.verification.correction", "tool.started", "tool.completed",
                            "plan.verification.completed", "run.completed");
            assertThat(terminal.payload().get("finalText").stringValue())
                    .isEqualTo("approved plan corrected and verified");
            assertThat(events.toString()).doesNotContain("FIRST_UNVERIFIED_FINAL");
            assertThat(fixtureRoot.resolve("workspace/河南各市7天天气预报.xlsx")).hasContent("wrong-name");
            assertThat(fixtureRoot.resolve("workspace/河南各市7天天气.xlsx")).hasContent("correct-name");
            assertThat(temporary.resolve("parent-sentinel.txt")).hasContent("outside plan workspace");
        } finally {
            plan.close();
        }
        assertThat(fixtureDirectories(temporary, "plan-runtime-"))
                .containsExactlyInAnyOrderElementsOf(beforePlan);
    }

    @Test
    void closesProviderHandlerWithoutTemporaryRootResidue() throws Exception {
        Path systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        java.util.Set<String> beforeProvider = fixtureDirectories(
                systemTemporary, "cc-java-provider-control-fixture-");
        StdioProtocol.CommandHandler provider = StdioProtocolFixtureMain.providerControlHandlerForTest();
        provider.close();
        assertThat(fixtureDirectories(systemTemporary, "cc-java-provider-control-fixture-"))
                .containsExactlyInAnyOrderElementsOf(beforeProvider);
    }

    @Test
    void deletesOnlyNamedStrictDescendantAndRejectsSibling() throws Exception {
        Path parent = temporary.toAbsolutePath().normalize();
        Path realParent = parent.toRealPath();
        Path fixture = Files.createTempDirectory(parent, "permission-runtime-");
        Files.writeString(fixture.resolve("value.txt"), "fixture");
        StdioProtocolFixtureMain.deleteFixtureTree(
                parent, realParent, fixture, "permission-runtime-");
        assertThat(fixture).doesNotExist();

        Path sibling = Files.createTempDirectory(parent.getParent(), "permission-runtime-outside-");
        try {
            assertThatThrownBy(() -> StdioProtocolFixtureMain.deleteFixtureTree(
                    parent, realParent, sibling, "permission-runtime-"))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(sibling).exists();
        } finally {
            Files.delete(sibling);
        }
    }

    private static CapturedEvent awaitEvent(
            CopyOnWriteArrayList<CapturedEvent> events, String type, String requestId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CapturedEvent> matched = events.stream()
                    .filter(event -> event.type().equals(type) && event.requestId().equals(requestId))
                    .findFirst();
            if (matched.isPresent()) return matched.orElseThrow();
            Thread.sleep(10);
        }
        throw new AssertionError("未收到事件 " + type + " for " + requestId + ": " + events);
    }

    private static CapturedEvent awaitEventCount(
            CopyOnWriteArrayList<CapturedEvent> events, String type, String requestId, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            java.util.List<CapturedEvent> matched = events.stream()
                    .filter(event -> event.type().equals(type) && event.requestId().equals(requestId))
                    .toList();
            if (matched.size() >= count) return matched.get(count - 1);
            Thread.sleep(10);
        }
        throw new AssertionError("未收到第 " + count + " 个事件 " + type + " for " + requestId + ": " + events);
    }

    private static java.util.Set<String> fixtureDirectories(Path parent, String prefix) throws Exception {
        try (var children = Files.list(parent)) {
            return children.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private record CapturedEvent(
            String type,
            String requestId,
            Optional<String> sessionId,
            Optional<String> runId,
            ObjectNode payload) {
    }
}
