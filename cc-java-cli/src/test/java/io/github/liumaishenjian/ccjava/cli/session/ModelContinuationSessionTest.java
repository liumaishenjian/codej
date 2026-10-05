package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.DefaultContextAssembler;
import io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.core.MemoryContextService;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks;
import io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.SessionJournal;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelContinuation;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 合成材料走真实 Journal→JSONL→restore/Fork；不访问真实 Session 或网络。 */
class ModelContinuationSessionTest {
    private static final SessionSpec SPEC = new SessionSpec("safe instructions", Map.of("model", "fake"));
    private static final RunId RUN = new RunId("run-synthetic");
    private static final String HIDDEN = "SYNTHETIC_HIDDEN_SIGNATURE";
    @TempDir Path temporaryRoot;

    static Stream<String> payloads() {
        return Stream.of("opaque-not-json\n" + HIDDEN + "\u0000中文🙂\\\"\r\n  ",
                "中".repeat(349_525) + "a", "🙂".repeat(262_144), "\u0000".repeat(1_048_576));
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void reopensAndForksExactUtf8PayloadAndOrderedToolHistory(String payload) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path root = temporaryRoot.resolve("sessions");
        ModelContinuation value = continuation(payload);
        ToolCall call = new ToolCall("call-continuation", "read_file", JsonObject.empty());
        AssistantMessage toolMessage = new AssistantMessage("visible tool", List.of(call), Optional.of(value));
        AssistantMessage finalMessage = new AssistantMessage("visible final", List.of(), Optional.of(value));
        SessionId source;
        try (FileSessionStore store = store(root, workspace, 1)) {
            source = store.create(SPEC).id();
            SessionJournal journal = store;
            journal.runStarted(source, RUN, new UserMessage("independent synthetic scenario"));
            journal.assistantAppended(source, RUN, toolMessage);
            journal.toolStarted(source, RUN, 1, call.id(), call.name(), ToolEffect.READ_WORKSPACE);
            journal.toolCompleted(source, RUN, 1, ToolResult.success(call.id(), call.name(), "safe result"));
            journal.assistantAppended(source, RUN, finalMessage);
            journal.runCompleted(source, RUN, StopReason.COMPLETED);
        }
        Path sourceJournal = root.resolve(source.value()).resolve("session.jsonl");
        byte[] original = Files.readAllBytes(sourceJournal);
        SessionId fork;
        try (FileSessionStore reopened = store(root, workspace, 20)) {
            SessionOpenResult resume = reopened.open(request(SessionOpenMode.RESUME, source), SPEC);
            assertHistory(resume.session(), payload, toolMessage, finalMessage);
            assertThat(resume.issues()).isEmpty();
            assertThat(resume.session().hasActiveRun()).isFalse();
            reopened.close(source);
            SessionOpenResult target = reopened.open(request(SessionOpenMode.FORK, source), SPEC);
            fork = target.session().id();
            assertThat(target.parentSessionId()).contains(source);
            assertHistory(target.session(), payload, toolMessage, finalMessage);
            assertThat(target.session().spec().runtimeMetadata()).isEqualTo(SPEC.runtimeMetadata());
            assertThat(target.session().spec().systemInstructions()).isEqualTo(SPEC.systemInstructions());
        }
        assertThat(Files.readAllBytes(sourceJournal)).isEqualTo(original);
        try (FileSessionStore reopenedFork = store(root, workspace, 40)) {
            SessionOpenResult resumed = reopenedFork.open(request(SessionOpenMode.RESUME, fork), SPEC);
            assertHistory(resumed.session(), payload, toolMessage, finalMessage);
            assertThat(resumed.issues()).isEmpty();
        }
        ObjectNode record = new JsonlSessionCodec().decode(Files.readAllLines(sourceJournal).get(2));
        assertThat(record.path("schemaMajor").intValue()).isEqualTo(1);
        assertThat(record.path("continuation").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("backend", "providerId", "modelId", "payload");
        assertThat(record.path("text").stringValue()).isEqualTo("visible tool");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void runtimeJournalsOnlyGateAcceptedCandidateWithContinuation(boolean accepted) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path root = temporaryRoot.resolve("sessions");
        AssistantMessage candidate = new AssistantMessage("candidate visible", List.of(), Optional.of(continuation(HIDDEN)));
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            AgentSession session = store.create(SPEC);
            id = session.id();
            var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
            ToolRegistry tools = new ToolRegistry(List.of());
            ToolExecutionPipeline pipeline = new ToolExecutionPipeline(tools,
                    (invocation, definition) -> { throw new AssertionError("no tool expected"); },
                    (invocation, definition, outcome) -> { throw new AssertionError("no approval expected"); },
                    new InMemorySessionPermissionState(), lifecycle, store);
            AgentIdGenerator ids = new AgentIdGenerator() {
                @Override public SessionId newSessionId() { throw new AssertionError("already created"); }
                @Override public RunId newRunId() { return RUN; }
            };
            AgentRuntime runtime = new AgentRuntime(store, ids, request -> new ModelTurn(candidate),
                    new DefaultContextAssembler(), tools, pipeline, lifecycle, store,
                    ContextPreparationService.noop(), MemoryContextService.noop(), InstructionContextService.noop(),
                    HookCoordinator.disabled(), SkillRunCoordinator.disabled(), PluginRunCoordinator.disabled(),
                    PluginRunHooks.none(), (sessionId, runId, assistant) -> accepted);
            var result = runtime.run(id, new AgentRunRequest(new UserMessage("safe input"), AgentLimits.DEFAULT));
            assertThat(result.stopReason()).isEqualTo(accepted ? StopReason.COMPLETED : StopReason.INVALID_MODEL_RESPONSE);
            assertThat(session.messages().stream().filter(AssistantMessage.class::isInstance).toList())
                    .containsExactlyElementsOf(accepted ? List.of(candidate) : List.of());
            assertThat(result.toString()).doesNotContain(HIDDEN);
        }
        String jsonl = Files.readString(root.resolve(id.value()).resolve("session.jsonl"));
        if (accepted) assertThat(jsonl).contains("assistant.appended", HIDDEN);
        else assertThat(jsonl).doesNotContain("assistant.appended", HIDDEN, "candidate visible");
        try (FileSessionStore reopened = store(root, workspace, 20)) {
            var restored = reopened.open(request(SessionOpenMode.RESUME, id), SPEC);
            assertThat(restored.session().messages().stream().filter(AssistantMessage.class::isInstance).toList())
                    .containsExactlyElementsOf(accepted ? List.of(candidate) : List.of());
        }
    }

    @Test
    void inspectSummaryAndBothExportModesExcludeHiddenMaterial() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path root = temporaryRoot.resolve("sessions");
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.runStarted(id, RUN, new UserMessage("safe input"));
            store.assistantAppended(id, RUN, new AssistantMessage("visible final", List.of(),
                    Optional.of(continuation(HIDDEN))));
            store.runCompleted(id, RUN, StopReason.COMPLETED);
        }
        try (FileSessionStore store = store(root, workspace, 20)) {
            SessionOpenResult inspect = store.open(request(SessionOpenMode.INSPECT, id), SPEC);
            assertThat(inspect.readOnly()).isTrue();
            assertThat(inspect.toString()).doesNotContain(HIDDEN);
            assertThat(inspect.session().messages().toString()).doesNotContain(HIDDEN);
            assertThat(inspect.session().events().toString()).doesNotContain(HIDDEN);
        }
        SessionLifecycleService lifecycle = new SessionLifecycleService(root);
        String metadata = new String(lifecycle.export(id.value(), false, false, false), StandardCharsets.UTF_8);
        String content = new String(lifecycle.export(id.value(), true, true, true), StandardCharsets.UTF_8);
        assertThat(metadata).doesNotContain(HIDDEN, "continuation", "visible final");
        assertThat(content).contains("visible final").doesNotContain(HIDDEN, "continuation", "provider-test", "model-test");
    }

    @Test
    void oldJsonlWithoutFieldRemainsReadableAndDoesNotWriteEmptyField() throws Exception {
        JsonlSessionCodec codec = new JsonlSessionCodec();
        ObjectNode old = codec.encodeAssistant(3, RUN, AssistantMessage.text("old visible"));
        assertThat(old.has("continuation")).isFalse();
        assertThat(old.path("schemaMajor").intValue()).isEqualTo(1);
        var snapshot = codec.replay(records(codec, old), false, "workspace-test");
        assertThat(((AssistantMessage) snapshot.messages().get(1)).continuation()).isEmpty();
        AgentSession restored = AgentSession.restore(snapshot);
        assertThat(restored.messages()).containsExactly(new UserMessage("safe input"), AssistantMessage.text("old visible"));
        assertThat(codec.encodeAssistant(3, RUN, (AssistantMessage) restored.messages().get(1)).has("continuation"))
                .isFalse();
    }

    @Test
    void oldOnDiskJournalReopensUnchangedAndInvalidAddedMetadataFailsClosed() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path root = temporaryRoot.resolve("sessions");
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.runStarted(id, RUN, new UserMessage("safe input"));
            store.assistantAppended(id, RUN, AssistantMessage.text("old visible"));
            store.runCompleted(id, RUN, StopReason.COMPLETED);
        }
        Path path = root.resolve(id.value()).resolve("session.jsonl");
        byte[] original = Files.readAllBytes(path);
        assertThat(new String(original, StandardCharsets.UTF_8)).doesNotContain("continuation");
        try (FileSessionStore store = store(root, workspace, 10)) {
            var resumed = store.open(request(SessionOpenMode.RESUME, id), SPEC);
            assertThat(((AssistantMessage) resumed.session().messages().get(1)).continuation()).isEmpty();
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        JsonlSessionCodec codec = new JsonlSessionCodec();
        ObjectNode assistant = codec.decode(lines.get(2));
        ObjectNode invalid = validNode(); invalid.put("unexpected", HIDDEN);
        assistant.set("continuation", invalid);
        lines.set(2, codec.encode(assistant));
        Files.write(path, lines, StandardCharsets.UTF_8);
        byte[] corrupt = Files.readAllBytes(path);
        try (FileSessionStore store = store(root, workspace, 20)) {
            for (SessionOpenMode mode : List.of(SessionOpenMode.RESUME, SessionOpenMode.FORK, SessionOpenMode.INSPECT)) {
                assertThatThrownBy(() -> store.open(request(mode, id), SPEC))
                        .isInstanceOf(SessionOpenException.class).hasMessageNotContaining(HIDDEN);
            }
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(corrupt);
    }

    static Stream<ObjectNode> invalidContinuations() {
        List<ObjectNode> invalid = new ArrayList<>();
        JsonMapper mapper = JsonMapper.builder().build();
        for (String field : List.of("backend", "providerId", "modelId", "payload")) {
            ObjectNode missing = validNode(); missing.remove(field); invalid.add(missing);
            ObjectNode nil = validNode(); nil.putNull(field); invalid.add(nil);
            ObjectNode numeric = validNode(); numeric.put(field, 1); invalid.add(numeric);
            ObjectNode array = validNode(); array.set(field, mapper.createArrayNode()); invalid.add(array);
            ObjectNode object = validNode(); object.set(field, mapper.createObjectNode()); invalid.add(object);
            ObjectNode bool = validNode(); bool.put(field, false); invalid.add(bool);
            ObjectNode empty = validNode(); empty.put(field, ""); invalid.add(empty);
        }
        ObjectNode extra = validNode(); extra.put("hiddenExtra", HIDDEN); invalid.add(extra);
        ObjectNode source = validNode(); source.put("backend", "legacy"); invalid.add(source);
        for (String field : List.of("providerId", "modelId")) {
            ObjectNode huge = validNode(); huge.put(field, "x".repeat(201)); invalid.add(huge);
        }
        for (String payload : List.of("x".repeat(1_048_577), "中".repeat(349_526), "🙂".repeat(262_145), "\uD800", "\uDC00")) {
            ObjectNode bad = validNode(); bad.put("payload", payload); invalid.add(bad);
        }
        return invalid.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidContinuations")
    void rejectsBadContinuationMetadataWithSanitizedFailure(ObjectNode invalid) {
        JsonlSessionCodec codec = new JsonlSessionCodec();
        ObjectNode record = codec.encodeAssistant(3, RUN, AssistantMessage.text("safe visible"));
        record.set("continuation", invalid);
        assertThatThrownBy(() -> codec.replay(records(codec, record), false, "workspace-test"))
                .isInstanceOf(SessionOpenException.class)
                .hasMessageNotContaining(HIDDEN);
    }

    @Test
    void opaqueExceptionDoesNotRelaxOrdinaryToolArgumentCharacterValidation() {
        JsonObject arguments = new JsonObject(Map.of("recordType", "assistant.appended",
                "continuation", Map.of("payload", "\u0000")));
        ToolCall spoof = new ToolCall("call-spoof", "read_file", arguments);
        assertThatThrownBy(() -> new JsonlSessionCodec().encodeAssistant(3, RUN,
                AssistantMessage.tools(List.of(spoof)))).isInstanceOf(SessionOpenException.class);
    }

    @Test
    void rejectsExplicitNullScalarArrayAndDuplicateEnvelopeFields() {
        JsonlSessionCodec codec = new JsonlSessionCodec();
        ObjectNode record = codec.encodeAssistant(3, RUN, AssistantMessage.text("safe visible"));
        for (String raw : List.of("null", "true", "1", "[]", "\"opaque\"")) {
            record.set("continuation", JsonMapper.builder().build().readTree(raw));
            assertThatThrownBy(() -> codec.replay(records(codec, record), false, "workspace-test"))
                    .isInstanceOf(SessionOpenException.class);
        }
        record.set("continuation", validNode());
        String duplicate = codec.encode(record).replace("\"backend\":\"pi\"", "\"backend\":\"pi\",\"backend\":\"pi\"");
        assertThatThrownBy(() -> codec.decode(duplicate)).isInstanceOf(SessionOpenException.class)
                .hasMessageNotContaining(HIDDEN);
    }

    private static void assertHistory(AgentSession session, String payload,
            AssistantMessage toolMessage, AssistantMessage finalMessage) {
        assertThat(session.messages()).hasSize(4);
        assertThat(session.messages().get(1)).isEqualTo(toolMessage);
        assertThat(session.messages().get(2)).isInstanceOf(ToolResultMessage.class);
        assertThat(((ToolResultMessage) session.messages().get(2)).result().callId()).isEqualTo("call-continuation");
        assertThat(session.messages().get(3)).isEqualTo(finalMessage);
        for (int index : List.of(1, 3)) {
            ModelContinuation restored = ((AssistantMessage) session.messages().get(index)).continuation().orElseThrow();
            assertThat(restored.payload().getBytes(StandardCharsets.UTF_8)).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<String> records(JsonlSessionCodec codec, ObjectNode assistant) {
        return List.of(codec.encode(codec.encodeSessionCreated(1, new SessionId("session-test"), SPEC,
                        "workspace-test", Optional.empty())),
                codec.encode(codec.encodeRunStarted(2, RUN, new UserMessage("safe input"))),
                codec.encode(assistant), codec.encode(codec.encodeRunCompleted(4, RUN, "COMPLETED")));
    }

    private static ObjectNode validNode() {
        ObjectNode node = JsonMapper.builder().build().createObjectNode();
        node.put("backend", "pi"); node.put("providerId", "provider-test");
        node.put("modelId", "model-test"); node.put("payload", HIDDEN);
        return node;
    }

    private static ModelContinuation continuation(String payload) {
        return new ModelContinuation("pi", "provider-test", "model-test", payload);
    }

    private static SessionOpenRequest request(SessionOpenMode mode, SessionId id) {
        return new SessionOpenRequest(mode, Optional.of(id));
    }

    private static FileSessionStore store(Path root, Path workspace, int firstId) {
        AtomicInteger ids = new AtomicInteger(firstId);
        AgentIdGenerator generator = new AgentIdGenerator() {
            @Override public SessionId newSessionId() { return new SessionId("session-synthetic-" + ids.getAndIncrement()); }
            @Override public RunId newRunId() { return new RunId("run-synthetic-" + ids.getAndIncrement()); }
        };
        return new FileSessionStore(root, workspace, generator,
                new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop()), Clock.systemUTC());
    }
}
