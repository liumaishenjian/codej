package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.*;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.execution.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Root→真实委托Pipeline→真实child读文件→有界结果→Root最终正文；Provider为Fake，不访问账号或Node。 */
class PiRootDelegationTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void twoRootRunsDelegateWithoutBorrowingRootGatewayOrReenteringAuthMutex(boolean explicitModel) throws Exception {
        verifyDelegation(explicitModel, false);
    }

    @Test void productionInspectSeparatesDeliveredRunFromFailedModelCleanup() throws Exception {
        verifyDelegation(false, true);
    }

    private void verifyDelegation(boolean explicitModel, boolean failChildClose) throws Exception {
        int runs = failChildClose ? 1 : 2;
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("fixture.txt"), "read-through-child-pipeline\n");
        var catalog = new PiProviderCatalog();
        String first = catalog.require("openai").models().getFirst().id();
        String second = catalog.require("openai").models().get(1).id();
        Path definitionsDirectory = Files.createDirectories(home.resolve(".cc-java/agents"));
        Files.writeString(definitionsDirectory.resolve("reader.agent"), """
                id=reader
                description=Independent fixture reader
                instructions=Read only the requested public fixture
                tools=read_file
                permission=PLAN
                %s
                max-model-turns=4
                max-tool-calls=2
                max-input-tokens=10000
                max-output-characters=4096
                timeout-seconds=10
                background=false
                """.formatted(explicitModel ? "model=" + second : ""));
        var identity = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "fixture");
        var store = new PiCredentialStore(home);
        try (var material = PiCredentialMaterial.envRef("SYNTHETIC_DELEGATION_KEY")) {
            store.saveLogin(identity, material, store.snapshot(CancellationToken.none()).generation(), false,
                    CancellationToken.none());
        }
        var leases = new CredentialLeaseRegistry();
        var providerDefinitions = new ProviderDefinitionStore(home);
        var legacy = new RestrictedFileCredentialStore(home);
        var auth = new ProviderAuthApplicationService(providerDefinitions, legacy,
                new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(workspace),
                        providerDefinitions, legacy), Map.of(), leases);
        var chosen = new AtomicReference<>(first);
        var created = new CopyOnWriteArrayList<ScriptedGateway>();
        var closed = new CountDownLatch(runs * 2);
        var factory = PiRouteFixture.create(store, leases, catalog, model -> {
            int ordinal = created.size();
            boolean child = ordinal % 2 == 1;
            String rootModel = ordinal / 2 == 0 ? first : second;
            assertThat(model).isEqualTo(child && explicitModel ? second : rootModel);
            var raw = new ScriptedGateway(child, ordinal / 2, model, auth, closed);
            raw.failClose = child && failChildClose;
            created.add(raw);
            return raw;
        });
        var routes = new SelectedProviderRouteFactory(providerDefinitions, new CredentialResolver(legacy, Map.of()),
                leases, ProviderGatewayFactoryRegistry.production(), factory);
        var gateway = ProviderAuthRuntimeResources.fencedGateway(routes.lazyGateway(() -> Optional.of(
                new ProviderSelectionSnapshot("openai", "fixture", chosen.get(), "pi", "API_KEY"))), auth);
        var options = new HeadlessRuntimeOptions(workspace, first, Duration.ofSeconds(20), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), temporary.resolve("sessions"), Optional.empty(),
                ModelDiagnosticMode.OFF, Optional.empty(), ExecutionBackendPreference.LOCAL,
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? ExecutionShell.WINDOWS_PLATFORM : ExecutionShell.POSIX_PLATFORM);
        try (leases; var runtime = new HeadlessRuntimeSession(gateway, AgentEventSink.noop(), options,
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce(), ContextPreparationService.noop(),
                null, HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.forHome(home))) {
            var rootSession = runtime.open();
            for (int index = 0; index < runs; index++) {
                chosen.set(index == 0 ? first : second);
                var result = runtime.run("Delegate reading fixture.txt to reader, then report completion");
                assertThat(created).allSatisfy(raw -> assertThat(raw.failure.get()).isNull());
                assertThat(result.stopReason()).as("root run %s", index).isEqualTo(StopReason.COMPLETED);
                assertThat(result.finalText()).contains("root-delivery-" + index);
                assertThat(result.modelTurns()).isEqualTo(2);
                assertThat(result.toolCalls()).isEqualTo(1);
                assertThat(runtime.hasActiveRun()).isFalse();
                assertThat(created.get(index * 2).requests).allSatisfy(request ->
                        assertThat(request.sessionId()).isEqualTo(rootSession));
                assertThat(created.get(index * 2 + 1).requests).allSatisfy(request ->
                        assertThat(request.sessionId()).isNotEqualTo(rootSession));
                var toolResult = created.get(index * 2).requests.getLast().messages().stream()
                        .filter(ToolResultMessage.class::isInstance).map(ToolResultMessage.class::cast)
                        .map(ToolResultMessage::result).toList().getLast();
                assertThat(toolResult.content()).startsWith("taskId=").contains("cleanup=");
                var taskId = new ChildTaskId(toolResult.content().split(";", 2)[0].substring("taskId=".length()));
                var snapshot = runtime.waitForChildTask(taskId, Duration.ZERO).orElseThrow();
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (snapshot.cleanupStatus() == ResourceCleanupStatus.CLEANING && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                    snapshot = runtime.inspectChildTask(taskId).orElseThrow();
                }
                assertThat(snapshot.status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
                assertThat(snapshot.verified()).isTrue();
                assertThat(snapshot.cleanupStatus()).isEqualTo(failChildClose
                        ? ResourceCleanupStatus.UNCONFIRMED : ResourceCleanupStatus.RELEASED);
                assertThat(snapshot.summary()).doesNotContain("child-private-delivery", "synthetic cleanup");
                try (var releasedGate = auth.beginRun()) { assertThat(releasedGate).isNotNull(); }
            }
            assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(leases.activeCount(identity)).isEqualTo(failChildClose ? 1 : 0);
            assertThat(leases.fenced(identity)).isEqualTo(failChildClose);
            assertThat(created).hasSize(runs * 2);
            assertThat(created).allSatisfy(raw -> assertThat(raw.requests).hasSize(2));
        }
    }

    private static final class ScriptedGateway implements ModelGateway, ContextSummarizer, AutoCloseable {
        private final boolean child;
        private final int rootIndex;
        private final String model;
        private final ProviderAuthApplicationService auth;
        private final CountDownLatch closed;
        private final AtomicInteger turns = new AtomicInteger();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        private boolean didClose;
        private boolean failClose;
        private final AtomicReference<AssertionError> failure = new AtomicReference<>();
        private ScriptedGateway(boolean child, int rootIndex, String model,
                ProviderAuthApplicationService auth, CountDownLatch closed) {
            this.child = child; this.rootIndex = rootIndex; this.model = model; this.auth = auth; this.closed = closed;
        }
        @Override public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
            try { return respond(request); }
            catch (AssertionError error) {
                // 在调用线程断言原错误；给Runtime类型化失败，避免Fixture断言污染Session清理路径。
                failure.set(error);
                throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT, "Fixture assertion failed");
            }
        }
        private ModelTurn respond(ModelRequest request) {
            requests.add(request);
            // child已打开自己的路由，但父认证互斥门仍由Root持有，不能借释放父门让测试成功。
            assertThatThrownBy(auth::beginRun).isInstanceOf(IllegalStateException.class).hasMessage("RUN_ACTIVE");
            if (turns.getAndIncrement() == 0) {
                if (child) return ModelTurn.tools(List.of(new ToolCall("read", "read_file",
                        new JsonObject(Map.of("path", "fixture.txt")))));
                return ModelTurn.tools(List.of(new ToolCall("delegate-" + rootIndex, "delegate_agent", new JsonObject(Map.of(
                        "definition", "reader", "prompt", "Read fixture.txt", "tools", List.of("read_file"),
                        "maxModelTurns", 4, "maxToolCalls", 2, "maxInputTokens", 10000,
                        "maxOutputCharacters", 4096, "timeoutSeconds", 10)))));
            }
            var results = request.messages().stream().filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast).map(ToolResultMessage::result).toList();
            assertThat(results).hasSize(child ? 1 : rootIndex + 1);
            assertThat(results.getLast().callId()).isEqualTo(child ? "read" : "delegate-" + rootIndex);
            if (child) {
                assertThat(results.getFirst().content()).contains("read-through-child-pipeline");
                return ModelTurn.text("child-private-delivery-" + model);
            }
            assertThat(results.getLast().content()).contains("status=succeeded", "failure=none", "verified=true",
                    "modelTurns=2", "toolCalls=1").doesNotContain("child-private-delivery");
            assertThat(request.messages().toString()).doesNotContain("child-private-delivery");
            return ModelTurn.text("root-delivery-" + rootIndex);
        }
        @Override public Optional<SummaryCandidate> summarize(SummaryRequest request, CancellationToken token) {
            throw new AssertionError("No summary expected for this short coding fixture");
        }
        @Override public synchronized void close() {
            if (!didClose) {
                didClose = true; closed.countDown();
                if (failClose) throw new IllegalStateException("synthetic cleanup private detail");
            }
        }
    }
}
