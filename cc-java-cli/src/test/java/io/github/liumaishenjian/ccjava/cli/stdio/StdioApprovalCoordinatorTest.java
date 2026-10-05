package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StdioApprovalCoordinatorTest {

    @Test
    void contextualPreviewUsesCanonicalFileCoordinatesAndNeverWrites(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        var file = workspace.resolve("sample.txt");
        java.nio.file.Files.writeString(file, "第一行\r\nold\r\n末行\r\n");
        var change = previewPatch(workspace, "sample.txt", "old", "new", false);
        assertThat(change.scope()).isEqualTo("file");
        assertThat(change.before()).isEqualTo("第一行\nold\n末行\n");
        assertThat(change.after()).isEqualTo("第一行\nnew\n末行\n");
        assertThat(java.nio.file.Files.readString(file)).isEqualTo("第一行\r\nold\r\n末行\r\n");
        java.nio.file.Files.writeString(file, "old\n间隔\nold\n");
        assertThat(previewPatch(workspace, "sample.txt", "old", "new", false).scope()).isEqualTo("fragment");
        assertThat(previewPatch(workspace, "sample.txt", "old", "new", true).after()).isEqualTo("new\n间隔\nnew\n");
        assertThat(previewPatch(workspace, "sample.txt", "missing", "new", false).scope()).isEqualTo("fragment");
    }

    @Test
    void contextualPreviewRejectsSecretsAndBoundsReplacementExpansion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        var file = workspace.resolve("sample.txt");
        java.nio.file.Files.writeString(file, "password=EXAMPLE\nold\n");
        var secret = previewPatch(workspace, "sample.txt", "old", "new", false);
        assertThat(secret.status()).isEqualTo("redacted");
        assertThat(secret.before()).isEmpty(); assertThat(secret.after()).isEmpty();
        java.nio.file.Files.writeString(file, "a".repeat(6000));
        assertThat(previewPatch(workspace, "sample.txt", "a", "b".repeat(6000), true).scope()).isEqualTo("fragment");
        java.nio.file.Files.writeString(file, "a".repeat(25000));
        assertThat(previewPatch(workspace, "sample.txt", "a", "b", true).scope()).isEqualTo("fragment");
        assertThat(previewPatch(workspace, "absent.txt", "a", "b", false).scope()).isEqualTo("fragment");
        assertThat(previewPatch(workspace, "../escape.txt", "a", "b", false)).isNull();
        java.nio.file.Files.writeString(workspace.resolve(".env"), "PREVIEW_MUST_NOT_READ\na\n");
        var denied = previewPatch(workspace, ".env", "a", "b", false);
        assertThat(denied.status()).isEqualTo("redacted");
        assertThat(denied.before()).isEmpty();
        assertThat(denied.after()).isEmpty();
    }

    private static StdioApprovalCoordinator.FileChange previewPatch(java.nio.file.Path workspace, String path,
            String before, String after, boolean all) throws Exception {
        AtomicReference<StdioApprovalCoordinator> holder = new AtomicReference<>();
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        try (var coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request); holder.get().resolve(request.approvalId(), ApprovalResponse.deny());
        })) {
            holder.set(coordinator); coordinator.enableFilePreviews(true);
            coordinator.bindPreviewGuard(new io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard(workspace));
            var call = new ToolCall("patch", "apply_patch", new JsonObject(java.util.Map.of(
                    "path", path, "oldText", before, "newText", after, "replaceAll", all)));
            coordinator.requestApproval(new ToolInvocation(new SessionId("session"), new RunId("run"), 1,
                            call, new CancellationSource().token()),
                    new ToolDefinition("apply_patch", "Patch", "{}", ToolEffect.WRITE_WORKSPACE, ToolSource.BUILT_IN,
                            false, Duration.ofSeconds(1), "text/plain", 1024),
                    ask(PermissionSelector.toolWide("apply_patch", ToolSource.BUILT_IN)));
            return captured.get().preview().change();
        }
    }

    @Test
    void negotiatedFilePreviewIsBoundedAndNeverEchoesRejectedContent() {
        assertThat(StdioApprovalCoordinator.FileChange.from("old", "new"))
                .isEqualTo(new StdioApprovalCoordinator.FileChange("old", "new", "available"));
        for (String rejected : java.util.List.of("password=EXAMPLE", "x\u001by", "x\u009cy")) {
            assertThat(StdioApprovalCoordinator.FileChange.from("safe", rejected))
                    .isEqualTo(new StdioApprovalCoordinator.FileChange("", "", "redacted"));
        }
        assertThat(StdioApprovalCoordinator.FileChange.from("a".repeat(6001), "new"))
                .isEqualTo(new StdioApprovalCoordinator.FileChange("", "", "too_large"));
        assertThat(StdioApprovalCoordinator.FileChange.from("", "中文\n\tvalue").after())
                .isEqualTo("中文\n\tvalue");
    }

    @Test
    void enabledPreviewIsProjectedFromSameBuiltinCallAndDenyStillWins() {
        AtomicReference<StdioApprovalCoordinator> holder = new AtomicReference<>();
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            holder.get().resolve(request.approvalId(), ApprovalResponse.deny());
        });
        holder.set(coordinator);
        coordinator.enableFilePreviews(true);
        var call = new ToolCall("file", "write_file", new JsonObject(java.util.Map.of(
                "path", "report.md", "content", "中文正文\n")));
        var invocation = new ToolInvocation(new SessionId("session"), new RunId("run"), 1,
                call, new CancellationSource().token());
        var definition = new ToolDefinition("write_file", "Write", "{}", ToolEffect.WRITE_WORKSPACE,
                ToolSource.BUILT_IN, false, Duration.ofSeconds(1), "text/plain", 1024);
        var result = coordinator.requestApproval(invocation, definition,
                ask(PermissionSelector.toolWide("write_file", ToolSource.BUILT_IN)));
        assertThat(result).isEqualTo(ApprovalResponse.deny());
        assertThat(captured.get().preview().change().after()).isEqualTo("中文正文\n");
        assertThat(captured.get().preview().target()).isEqualTo("report.md");
        assertThat(captured.get().preview().addedLines()).isEqualTo(1);
        var external = new ToolDefinition("write_file", "External", "{}", ToolEffect.WRITE_WORKSPACE,
                ToolSource.PLUGIN, false, Duration.ofSeconds(1), "text/plain", 1024);
        coordinator.requestApproval(invocation, external,
                ask(PermissionSelector.toolWide("write_file", ToolSource.PLUGIN)));
        assertThat(captured.get().preview().change()).isNull();
        coordinator.close();
    }

    @Test
    void matchingAllowOnceReleasesOnlyTheDisplayedRequest() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            requested.countDown();
        }, () -> "approval-1");
        PermissionSelector scope = PermissionSelector.toolWide("fake_write", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN);

        CompletableFuture<ApprovalResponse> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(
                        invocation(new CancellationSource()), definition(), ask(scope)));
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isEqualTo(new StdioApprovalCoordinator.Request(
                "approval-1",
                new RunId("run-1"),
                1,
                "fake_write",
                ToolEffect.WRITE_WORKSPACE,
                scope,
                StdioApprovalCoordinator.Preview.unavailable()));
        assertThat(coordinator.resolve("other", ApprovalResponse.allowOnce())).isFalse();
        assertThat(decision).isNotDone();

        assertThat(coordinator.resolve("approval-1", ApprovalResponse.allowOnce())).isTrue();
        assertThat(decision.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.allowOnce());
        assertThat(coordinator.resolve("approval-1", ApprovalResponse.deny())).isFalse();
    }

    @Test
    void cancellationAndCloseFailClosedAndReleaseWaiters() throws Exception {
        CountDownLatch firstRequested = new CountDownLatch(1);
        CancellationSource cancellation = new CancellationSource();
        StdioApprovalCoordinator cancelledCoordinator =
                new StdioApprovalCoordinator(ignored -> firstRequested.countDown(), () -> "cancel");
        CompletableFuture<ApprovalResponse> cancelled = CompletableFuture.supplyAsync(
                () -> cancelledCoordinator.requestApproval(
                        invocation(cancellation), definition(),
                        ask(PermissionSelector.toolWide("fake_write", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN))));
        assertThat(firstRequested.await(2, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();
        assertThat(cancelled.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.deny());

        CountDownLatch secondRequested = new CountDownLatch(1);
        StdioApprovalCoordinator closedCoordinator =
                new StdioApprovalCoordinator(ignored -> secondRequested.countDown(), () -> "close");
        CompletableFuture<ApprovalResponse> closed = CompletableFuture.supplyAsync(
                () -> closedCoordinator.requestApproval(
                        invocation(new CancellationSource()), definition(),
                        ask(PermissionSelector.toolWide("fake_write", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN))));
        assertThat(secondRequested.await(2, TimeUnit.SECONDS)).isTrue();
        closedCoordinator.close();
        assertThat(closed.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.deny());
    }

    @Test
    void patchPreviewContainsOnlyRelativeTargetOperationAndLineCounts() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            requested.countDown();
        }, () -> "patch-approval");
        ToolInvocation invocation = new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", "apply_patch", new JsonObject(java.util.Map.of(
                        "path", "src/main/App.java",
                        "oldText", "old\nblock",
                        "newText", "new\nblock\nextra"))),
                new CancellationSource().token());
        ToolDefinition definition = new ToolDefinition(
                "apply_patch",
                "Patch",
                "{\"type\":\"object\"}",
                ToolEffect.WRITE_WORKSPACE,
                ToolSource.BUILT_IN,
                false,
                Duration.ofSeconds(1),
                "text/plain",
                1024);
        PermissionSelector scope = new PermissionSelector("apply_patch", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN, "src/main/App.java");

        CompletableFuture<ApprovalResponse> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(invocation, definition, ask(scope)));
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(captured.get().preview()).isEqualTo(
                new StdioApprovalCoordinator.Preview(
                        "src/main/App.java", "modify", 2, 3, "", "", "", "", ""));
        assertThat(coordinator.resolve("patch-approval", ApprovalResponse.deny())).isTrue();
        assertThat(decision.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.deny());
    }

    @Test
    void webSearchPreviewContainsOnlyBoundedQueryAndFixedDestination() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            requested.countDown();
        }, () -> "network-approval");
        ToolInvocation invocation = new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-web", "web_search", new JsonObject(java.util.Map.of(
                        "query", "明天杭州天气",
                        "result_limit", 5))),
                new CancellationSource().token());
        ToolDefinition definition = new ToolDefinition(
                "web_search",
                "Controlled web search",
                "{\"type\":\"object\"}",
                ToolEffect.NETWORK_OR_REMOTE,
                ToolSource.BUILT_IN,
                true,
                Duration.ofSeconds(10),
                "text/plain",
                64_000);
        PermissionSelector scope = PermissionSelector.toolWide("web_search", ToolSource.BUILT_IN);

        CompletableFuture<ApprovalResponse> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(invocation, definition, ask(scope)));
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get().preview()).isEqualTo(
                StdioApprovalCoordinator.Preview.webSearch("明天杭州天气"));
        assertThat(captured.get().preview().toString())
                .doesNotContain("https://", "Authorization", "api-key");
        assertThat(coordinator.resolve("network-approval", ApprovalResponse.allowOnce())).isTrue();
        assertThat(decision.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.allowOnce());
    }

    private static PermissionOutcome ask(PermissionSelector selector) {
        return PermissionOutcome.of(
                PermissionDecision.ASK,
                PermissionReason.EFFECT_DEFAULT,
                selector);
    }

    private ToolInvocation invocation(CancellationSource cancellation) {
        return new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", "fake_write", JsonObject.empty()),
                cancellation.token());
    }

    private ToolDefinition definition() {
        return new ToolDefinition(
                "fake_write",
                "Fake write without filesystem access",
                "{\"type\":\"object\"}",
                ToolEffect.WRITE_WORKSPACE,
                ToolSource.BUILT_IN,
                true,
                Duration.ofSeconds(1),
                "text/plain",
                1024);
    }
}
