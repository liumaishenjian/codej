package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/** ADR-100：真实 Root 绑定拒绝经 stdio 唯一终态恢复；不调用网络或修改保留预算。 */
class ModelContextBudgetProtocolTest {
    @TempDir Path temporary;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rejectedInstalledBudgetHasSafeTerminalAndExplicitLargerModelDelivers(boolean genericFailure) throws Exception {
        var gateway = new BoundGateway();
        gateway.genericFailure = genericFailure;
        var options = new HeadlessRuntimeOptions(Files.createDirectories(temporary.resolve("workspace")),
                "launcher", Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(),
                temporary.resolve("sessions"), Optional.of(new ContextPreparationConfig(
                        new ContextCapacity("launcher", 256_000, 8192, 4096), 200, 1, 1024, 256)),
                ModelDiagnosticMode.OFF, Optional.empty(),
                io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference.LOCAL,
                io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell.WINDOWS_PLATFORM);
        var codec = new StdioProtocolCodec();
        var events = new CopyOnWriteArrayList<Event>();
        var failed = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, request, session, run, payload) -> {
            events.add(new Event(type, request, session, run, payload.deepCopy()));
            if (type.equals("run.launch.failed")) failed.countDown();
            if (type.equals("run.completed")) completed.countDown();
        };
        try (var handler = new RuntimeStdioCommandHandler((sink, approvals) ->
                new HeadlessRuntimeSession(gateway, sink, options, approvals,
                        (request, token) -> { throw new AssertionError("facade summary forbidden"); }))) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String session = events.getFirst().session().orElseThrow();
            handler.handle(codec.decodeCommand(start(session, "small", 2)), emitter);
            assertThat(failed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.calls).hasValue(0);
            assertThat(events.stream().filter(e -> e.type().equals("run.launch.failed")).toList())
                    .singleElement().satisfies(e -> {
                        assertThat(e.request()).isEqualTo("small");
                        assertThat(e.session()).contains(session);
                        assertThat(e.run()).isEmpty();
                        assertThat(e.payload().size()).isEqualTo(2);
                        assertThat(e.payload().get("code").stringValue()).isEqualTo(genericFailure
                                ? "RUNTIME_LAUNCH_FAILED" : "MODEL_CONTEXT_BUDGET_INCOMPATIBLE");
                        assertThat(e.payload().get("stopReason").stringValue()).isEqualTo("internal_error");
                    });
            assertThat(events).noneMatch(e -> e.type().equals("run.started") || e.type().equals("run.failed"));
            gateway.genericFailure = false;
            gateway.window = 128_000;
            handler.handle(codec.decodeCommand(start(session, "large", 3)), emitter);
            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.calls).hasValue(1);
        }
        assertThat(events.stream().filter(e -> e.request().equals("small") &&
                List.of("run.launch.failed", "run.failed", "run.completed", "run.cancelled").contains(e.type())).count()).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.type().equals("run.completed")).toList()).singleElement()
                .satisfies(e -> assertThat(e.payload().get("finalText").stringValue()).isEqualTo("delivered"));
    }

    private static String start(String session, String request, int sequence) {
        return "{\"version\":0,\"type\":\"run.start\",\"requestId\":\"%s\",\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"prompt\":\"inspect\"}}"
                .formatted(request, session, sequence);
    }

    private record Event(String type, String request, Optional<String> session, Optional<String> run, ObjectNode payload) {}

    private static final class BoundGateway implements RunScopedModelGateway {
        private volatile long window = 8192;
        private volatile boolean genericFailure;
        private final AtomicInteger calls = new AtomicInteger();
        @Override public boolean providesRunBindings() { return true; }
        @Override public ModelTurn complete(ModelRequest request) { throw new AssertionError("facade forbidden"); }
        @Override public RunScope openRun() {
            if (genericFailure) throw new IllegalStateException("sensitive-detail-must-not-project");
            var binding = new RunModelBinding(request -> { calls.incrementAndGet(); return ModelTurn.text("delivered"); },
                    (request, token) -> { throw new AssertionError("summary not needed"); },
                    (override, budget) -> { throw new AssertionError("child not requested"); },
                    Optional.of(new ProviderSelectionSnapshot("openai", "default", window == 8192 ? "gpt-4" : "gpt-4-turbo", "pi", "API_KEY")),
                    OptionalLong.of(window));
            return new RunScope() {
                @Override public Optional<RunModelBinding> binding() { return Optional.of(binding); }
                @Override public void bindCancellation(Runnable cancellation) { }
                @Override public void close() { }
            };
        }
    }
}
