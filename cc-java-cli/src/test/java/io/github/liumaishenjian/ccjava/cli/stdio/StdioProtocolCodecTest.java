package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StdioProtocolCodecTest {

    private final StdioProtocolCodec codec = new StdioProtocolCodec();

    @Test
    void decodesValidCommandAndIgnoresUnknownOptionalFields() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand("""
                {"version":0,"type":"initialize","requestId":"req-1",
                 "sequence":1,"payload":{},"futureField":{"enabled":true}}
                """);

        assertThat(command.type()).isEqualTo("initialize");
        assertThat(command.requestId()).isEqualTo("req-1");
        assertThat(command.sequence()).isEqualTo(1);
        assertThat(command.sessionId()).isEmpty();
        assertThat(command.payload()).isEmpty();
    }

    @Test
    void decodesTaskKeepAndRemoveAsStrictIdentityBoundCommands() throws Exception {
        for (String type : java.util.List.of("task.keep", "task.remove")) {
            StdioProtocol.Command command = codec.decodeCommand("""
                    {"version":0,"type":"%s","requestId":"worktree-1",
                     "sessionId":"session-1","sequence":2,"payload":{"taskId":"task-abc"}}
                    """.formatted(type));
            assertThat(command.type()).isEqualTo(type);
            assertThat(command.payload().get("taskId").stringValue()).isEqualTo("task-abc");
        }
        assertProtocolError("""
                {"version":0,"type":"task.remove","requestId":"worktree-2",
                 "sessionId":"session-1","sequence":3,
                 "payload":{"taskId":"task-abc","force":true}}
                """, "INVALID_ENVELOPE");
    }

    @Test
    void decodesApprovalResolveAsAFirstClassCommand() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand("""
                {"version":0,"type":"approval.resolve","requestId":"approve-1",
                 "sessionId":"session-1","runId":"run-1","sequence":3,
                 "payload":{"approvalId":"approval-1","decision":"allow_once"}}
                """);

        assertThat(command.type()).isEqualTo("approval.resolve");
        assertThat(command.sessionId()).contains("session-1");
        assertThat(command.runId()).contains("run-1");
        assertThat(command.payload().get("approvalId").stringValue())
                .isEqualTo("approval-1");
    }

    @Test
    void rejectsMalformedDuplicateAndUnknownProtocolInputs() {
        assertProtocolError("{", "MALFORMED_JSON");
        assertProtocolError(
                """
                {"version":0,"version":0,"type":"initialize",
                 "requestId":"req-1","sequence":1,"payload":{}}
                """,
                "MALFORMED_JSON");
        assertProtocolError(
                """
                {"version":1,"type":"initialize","requestId":"req-1",
                 "sequence":1,"payload":{}}
                """,
                "UNSUPPORTED_VERSION");
        assertProtocolError(
                """
                {"version":0,"type":"future.command","requestId":"req-1",
                 "sequence":1,"payload":{}}
                """,
                "UNKNOWN_COMMAND");
        assertProtocolError(
                """
                {"version":0,"type":"initialize","requestId":"req-1",
                 "sequence":1,"payload":[]}
                """,
                "INVALID_PAYLOAD");
    }

    @Test
    void decodesStrictBoundedSessionCommandAndRejectsUnknownFields() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"compact",
                 "arguments":{"anchors":["focus"]}}}
                """);
        assertThat(command.type()).isEqualTo("session.command");
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-tasks","sessionId":"session-1",
                 "sequence":3,"payload":{"protocolVersion":0,"commandId":"command-tasks","intent":"tasks",
                 "arguments":{}}}
                """).payload().get("intent").stringValue()).isEqualTo("tasks");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"compact",
                 "arguments":{"anchors":["%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s","%s"]}}}
                """.formatted("a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a"), "INVALID_ARGUMENT");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"compact",
                 "arguments":{"anchors":["bad\\nanchor"]}}}
                """, "INVALID_ARGUMENT");
        assertThatThrownBy(() -> codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"future":true,"payload":{"protocolVersion":0,"commandId":"command-1",
                 "intent":"help","arguments":{}}}
                """)).isInstanceOf(StdioProtocolException.class)
                .extracting(error -> ((StdioProtocolException) error).code()).isEqualTo("UNKNOWN_FIELD");
        assertThatThrownBy(() -> codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"unknown","arguments":{}}}
                """)).isInstanceOf(StdioProtocolException.class)
                .extracting(error -> ((StdioProtocolException) error).code()).isEqualTo("INVALID_ARGUMENT");
        assertThatThrownBy(() -> codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"model",
                 "arguments":{"name":"%s"}}}
                """.formatted("x".repeat(257)))).isInstanceOf(StdioProtocolException.class)
                .extracting(error -> ((StdioProtocolException) error).code()).isEqualTo("INVALID_ARGUMENT");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":4294967296,"commandId":"command-1","intent":"help","arguments":{}}}
                """, "UNSUPPORTED_VERSION");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"bad\\ncommand","intent":"help","arguments":{}}}
                """, "INVALID_PAYLOAD");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"%s","intent":"help","arguments":{}}}
                """.formatted("x".repeat(129)), "INVALID_PAYLOAD");
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{}}}
                """).type()).isEqualTo("session.command");
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"mode":"PLAN"}}}
                """).type()).isEqualTo("session.command");
        for (String selection : java.util.List.of("PLAN", "ASK", "AUTO")) {
            assertThat(codec.decodeCommand("""
                    {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                     "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"selection":"%s"}}}
                    """.formatted(selection)).type()).isEqualTo("session.command");
        }
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"operation":"query"}}}
                """, "UNKNOWN_FIELD");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"mode":"PLAN","selection":"ASK"}}}
                """, "INVALID_ARGUMENT");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"mode":"INVALID"}}}
                """, "INVALID_ARGUMENT");
        for (String value : java.util.List.of("", "UNKNOWN", "SECRET_SELECTION")) {
            assertThatThrownBy(() -> codec.decodeCommand("""
                    {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                     "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"selection":"%s"}}}
                    """.formatted(value)))
                    .isInstanceOfSatisfying(StdioProtocolException.class, error -> {
                        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
                        assertThat(error.getMessage()).doesNotContain("SECRET_SELECTION");
                    });
        }
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"selection":null}}}
                """, "INVALID_PAYLOAD");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"req-1","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"permissions","arguments":{"selection":false}}}
                """, "INVALID_PAYLOAD");
    }

    @Test
    void decodesBoundedPlanStepBeginAndRejectsMissingOrOversizedDigest() throws Exception {
        var command = codec.decodeCommand("""
                {"version":0,"type":"session.command","requestId":"plan-begin","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"plan-step-begin",
                 "arguments":{"workspaceDigest":"digest-a"}}}
                """);
        assertThat(command.payload().get("intent").stringValue()).isEqualTo("plan-step-begin");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"plan-begin","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"plan-step-begin",
                 "arguments":{}}}
                """, "INVALID_PAYLOAD");
        assertProtocolError("""
                {"version":0,"type":"session.command","requestId":"plan-begin","sessionId":"session-1",
                 "sequence":2,"payload":{"protocolVersion":0,"commandId":"command-1","intent":"plan-step-begin",
                 "arguments":{"workspaceDigest":"%s"}}}
                """.formatted("x".repeat(257)), "INVALID_ARGUMENT");
    }

    @Test
    void providerControlModelOverlaySchemaIsStrictAndSecretFree() throws Exception {
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"provider.control","requestId":"p-1","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"control-1","intent":"models.add",
                 "arguments":{"providerId":"anthropic","modelId":"overlay","setDefault":true}}}
                """).payload().get("intent").stringValue()).isEqualTo("models.add");
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"provider.control","requestId":"p-2","sessionId":"session-1",
                 "sequence":3,"payload":{"controlId":"control-2","intent":"models.remove",
                 "arguments":{"providerId":"anthropic","modelId":"overlay"}}}
                """).payload().get("intent").stringValue()).isEqualTo("models.remove");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"p-3","sessionId":"session-1",
                 "sequence":4,"payload":{"controlId":"control-3","intent":"models.add",
                 "arguments":{"providerId":"anthropic","modelId":"overlay","apiKey":"secret"}}}
                """, "UNKNOWN_FIELD");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"p-4","sessionId":"session-1",
                 "sequence":5,"payload":{"controlId":"control-4","intent":"models.use",
                 "arguments":{"providerId":"anthropic","modelId":"overlay","setDefault":"yes"}}}
                """, "INVALID_ARGUMENT");
    }

    @Test
    void providerControlAddProviderAcceptsOnlyExactBoundedFourFields() throws Exception {
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"provider.control","requestId":"p-add","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"add-1","intent":"providers.add",
                 "arguments":{"providerId":"team","displayName":"Team Gateway",
                 "baseUrl":"https://gateway.example/v1","modelId":"model-x"}}}
                """).payload().get("intent").stringValue()).isEqualTo("providers.add");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"p-add","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"add-1","intent":"providers.add",
                 "arguments":{"providerId":"team","displayName":"Team Gateway",
                 "baseUrl":"https://gateway.example/v1","modelId":"model-x","headers":{}}}}
                """, "UNKNOWN_FIELD");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"p-add","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"add-1","intent":"providers.add",
                 "arguments":{"providerId":"team","displayName":"bad\\nname",
                 "baseUrl":"https://gateway.example/v1","modelId":"model-x"}}}
                """, "INVALID_ARGUMENT");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"p-add","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"add-1","intent":"providers.add",
                 "arguments":{"providerId":"team","displayName":"Team Gateway",
                 "baseUrl":"https://gateway.example/v1","modelId":"%s"}}}
                """.formatted("x".repeat(257)), "INVALID_ARGUMENT");
    }

    @Test
    void providerQuickConfigureAcceptsOnlyBaseUrlAndModelAndNeverApiKey() throws Exception {
        assertThat(codec.decodeCommand("""
                {"version":0,"type":"provider.control","requestId":"quick","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"quick-1","intent":"providers.configure",
                 "arguments":{"baseUrl":"https://gateway.example/v1","modelId":"model-x"}}}
                """).payload().get("intent").stringValue()).isEqualTo("providers.configure");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"quick","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"quick-1","intent":"providers.configure",
                 "arguments":{"baseUrl":"https://gateway.example/v1","modelId":"model-x","apiKey":"secret"}}}
                """, "UNKNOWN_FIELD");
        assertProtocolError("""
                {"version":0,"type":"provider.control","requestId":"quick","sessionId":"session-1",
                 "sequence":2,"payload":{"controlId":"quick-1","intent":"providers.configure",
                 "arguments":{"baseUrl":"https://gateway.example/v1"}}}
                """, "INVALID_ARGUMENT");
    }

    @Test
    void encodesEventWithoutNullOptionalIds() throws Exception {
        ObjectNode payload = codec.objectNode();
        payload.put("protocolVersion", 0);
        StdioProtocol.Event event = new StdioProtocol.Event(
                0,
                "initialized",
                "req-1",
                Optional.of("session-1"),
                Optional.empty(),
                1,
                payload);

        String json = codec.encodeEvent(event);
        JsonNode root = JsonMapper.builder().build().readTree(json);

        assertThat(root.get("type").stringValue()).isEqualTo("initialized");
        assertThat(root.get("sessionId").stringValue()).isEqualTo("session-1");
        assertThat(root.has("runId")).isFalse();
        assertThat(root.get("sequence").longValue()).isEqualTo(1);
    }

    @Test
    void decodesFileSuggestWithExactQueryPayload() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand("""
                {"version":0,"type":"file.suggest","requestId":"suggest-1",
                 "sessionId":"session-1","sequence":4,"payload":{"query":"src/ma"}}
                """);

        assertThat(command.type()).isEqualTo("file.suggest");
        assertThat(command.sessionId()).contains("session-1");
        assertThat(command.runId()).isEmpty();
        assertThat(command.payload().get("query").stringValue()).isEqualTo("src/ma");
    }

    @Test
    void failsClosedOnFileSuggestSchemaViolations() {
        // 未知 payload 字段。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{"query":"a","limit":99}}
                """,
                "UNKNOWN_FIELD");
        // 未知信封字段。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{"query":"a"},"extra":1}
                """,
                "UNKNOWN_FIELD");
        // 缺少 Session。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sequence":1,
                 "payload":{"query":"a"}}
                """,
                "INVALID_ENVELOPE");
        // 不得携带 Run。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "runId":"run-1","sequence":1,"payload":{"query":"a"}}
                """,
                "INVALID_ENVELOPE");
        // 缺少 query。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{}}
                """,
                "INVALID_PAYLOAD");
        // query 类型错误。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{"query":7}}
                """,
                "INVALID_PAYLOAD");
        // query 超过 256 code point。
        assertProtocolError(
                ("""
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{"query":"%s"}}
                """).formatted("a".repeat(257)),
                "INVALID_ARGUMENT");
        // query 含控制字符。
        assertProtocolError(
                """
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{"query":"a\\u0000b"}}
                """,
                "INVALID_ARGUMENT");
    }

    @Test
    void acceptsExactlyMaximumFileSuggestQueryLength() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand(("""
                {"version":0,"type":"file.suggest","requestId":"s","sessionId":"session-1",
                 "sequence":1,"payload":{"query":"%s"}}
                """).formatted("a".repeat(256)));

        assertThat(command.payload().get("query").stringValue()).hasSize(256);
    }

    private void assertProtocolError(String input, String expectedCode) {
        assertThatThrownBy(() -> codec.decodeCommand(input))
                .isInstanceOf(StdioProtocolException.class)
                .extracting(exception -> ((StdioProtocolException) exception).code())
                .isEqualTo(expectedCode);
    }
}
