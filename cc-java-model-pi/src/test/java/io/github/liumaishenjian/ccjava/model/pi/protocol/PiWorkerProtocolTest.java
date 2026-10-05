package io.github.liumaishenjian.ccjava.model.pi.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PiWorkerProtocolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "{\"version\":1,\"operationId\":\"operation-1\",\"sequence\":0,\"type\":\"model.delta\",\"payload\":";
    // 独立业务样例；对应 Node encodeFrame 的合法输出，不引用 Provider 凭证或第三方私有帧。
    private static final String NODE_SAMPLE = PREFIX + "{\"text\":\"中文😀\",\"__proto__\":{\"safe\":true},\"constructor\":\"data\",\"values\":[null,false,-1,1.25,9007199254740991]}}\n";

    @Test
    void nodeEncodedIndependentSampleHasSameSemanticsAndSafeRepresentation() {
        ProtocolFrame frame = FrameCodec.decodeLine(bytes(NODE_SAMPLE));
        assertThat(frame.version()).isEqualTo(1);
        assertThat(frame.operationId()).isEqualTo("operation-1");
        assertThat(frame.sequence()).isZero();
        assertThat(frame.type()).isEqualTo("model.delta");
        assertThat(frame.payload().get("text").asString()).isEqualTo("中文😀");
        assertThat(frame.payload().get("__proto__").get("safe").asBoolean()).isTrue();
        assertThat(frame.payload().get("constructor").asString()).isEqualTo("data");
        assertThat(JSON.readTree(FrameCodec.encode(frame))).isEqualTo(JSON.readTree(NODE_SAMPLE));
        assertThat(frame.toString()).isEqualTo("PROTOCOL_FRAME");
        frame.payload().put("text", "changed");
        assertThat(frame.payload().get("text").asString()).isEqualTo("中文😀");
    }

    @Test
    void exportsIndependentSamplesForExplicitOfflineNodeInteropCheck() throws Exception {
        Path directory = Path.of("target", "protocol-interop");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("node-sample.ndjson"), NODE_SAMPLE, StandardCharsets.UTF_8);
        Files.write(directory.resolve("java-sample.ndjson"), FrameCodec.encode(FrameCodec.decodeLine(bytes(NODE_SAMPLE))));
    }

    @Test
    void utf8CodePointsCanBeSplitAtEveryByteAndCrLfIsAccepted() {
        List<ProtocolFrame> result = new ArrayList<>();
        JsonLineDecoder decoder = new JsonLineDecoder(result::add);
        byte[] input = bytes(NODE_SAMPLE.replace("\n", "\r\n"));
        for (byte value : input) decoder.push(new byte[]{value});
        decoder.end();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().payload().get("text").asString()).isEqualTo("中文😀");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"n\":{\"x\":1,\"x\":2}}",
            "{\"x\":01}", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":-Infinity}",
            "{\"x\":1e999}", "{\"x\":9007199254740992}", "{\"x\":-9007199254740992}",
            "{\"x\":9007199254740992.0}", "{\"x\":9.007199254740992e15}",
            "{\"x\":999999999999999999999999999999999999999}",
            "{\"x\":undefined}", "{\"x\":[1,]}", "/*comment*/{}", "{'x':1}",
            "{\"x\":\"\\ud800\"}", "{\"x\":\"\\udc00\"}", "{\"\\ud800\":0}",
            "[]", "null", "1", "true", "\"value\""
    })
    void rejectsBadJsonAndNonObjectPayload(String payload) {
        invalid(() -> FrameCodec.decodeLine(bytes(PREFIX + payload + "}\n")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}{}\n", "\n", "{}\n", "{}", "\ufeff{}\n", " \ufeff{}\n",
            "{\"version\":1,\"version\":1}\n", "{\"version\":1,\"\\u0076ersion\":1}\n"
    })
    void rejectsInvalidPhysicalFrames(String text) {
        invalid(() -> FrameCodec.decodeLine(bytes(text)));
    }

    @Test
    void rejectsBomMalformedUtf8AndUnencodedSurrogateBytes() {
        for (byte[] bad : List.of(new byte[]{(byte) 0xc0, (byte) 0xaf},
                new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                new byte[]{(byte) 0x80}, new byte[]{(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                new byte[]{(byte) 0xe4, (byte) 0xb8})) {
            byte[] left = bytes(PREFIX + "{\"x\":\"");
            byte[] right = bytes("\"}}\n");
            byte[] line = new byte[left.length + bad.length + right.length];
            System.arraycopy(left, 0, line, 0, left.length);
            System.arraycopy(bad, 0, line, left.length, bad.length);
            System.arraycopy(right, 0, line, left.length + bad.length, right.length);
            invalid(() -> FrameCodec.decodeLine(line));
        }
        invalid(() -> FrameCodec.decodeLine(bytes("\ufeff" + NODE_SAMPLE)));
        // 字符串内部 BOM 是合法业务数据，不应过度过滤。
        assertThat(FrameCodec.decodeLine(bytes(PREFIX + "{\"x\":\"\ufeff\"}}\n")).payload().get("x").asString()).isEqualTo("\ufeff");
    }

    @Test
    void fiveFieldsVersionOperationTypeAndSequenceAreStrict() {
        for (String field : List.of("version", "operationId", "sequence", "type", "payload")) {
            ObjectNode root = root(); root.remove(field);
            invalid(() -> FrameCodec.decodeLine(bytes(root + "\n")));
        }
        ObjectNode extra = root(); extra.put("extra", 0);
        invalid(() -> FrameCodec.decodeLine(bytes(extra + "\n")));
        for (String field : List.of("version", "operationId", "sequence", "type", "payload")) {
            ObjectNode root = root(); root.putNull(field);
            invalid(() -> FrameCodec.decodeLine(bytes(root + "\n")));
        }
        for (String value : List.of("\"1\"", "2", "true", "0", "1.1")) {
            invalid(() -> FrameCodec.decodeLine(bytes(NODE_SAMPLE.replace("\"version\":1", "\"version\":" + value))));
        }
        for (String value : List.of("-1", "0.5", "9007199254740992", "\"0\"")) {
            invalid(() -> FrameCodec.decodeLine(bytes(NODE_SAMPLE.replace("\"sequence\":0", "\"sequence\":" + value))));
        }
        for (String id : List.of("", "a".repeat(97), "bad space", "中文", "line\n", "slash/")) {
            invalid(() -> new FrameSequence(id));
            invalid(() -> new ProtocolFrame(id, 0, "model.delta", JSON.createObjectNode()));
        }
        for (String type : List.of("", "A", "model..delta", "a._b", "a.1", "a_1", "a-", "a\n", "a".repeat(65))) {
            invalid(() -> new ProtocolFrame("operation-1", 0, type, JSON.createObjectNode()));
        }
        ProtocolFrame boundary = new ProtocolFrame("a".repeat(96), FrameCodec.MAXIMUM_SAFE_INTEGER, "a".repeat(64), JSON.createObjectNode());
        assertThat(FrameCodec.decodeLine(FrameCodec.encode(boundary)).sequence()).isEqualTo(FrameCodec.MAXIMUM_SAFE_INTEGER);
        assertThat(FrameCodec.decodeLine(bytes(NODE_SAMPLE.replace("\"version\":1", "\"version\":1.0")
                .replace("\"sequence\":0", "\"sequence\":-0.0"))).sequence()).isZero();
    }

    @Test
    void depthCountsEnvelopeAndPayloadAndRejectsCycles() {
        // root + payload + 62 arrays = 64 containers, exactly the Node limit.
        String accepted = PREFIX + "{\"x\":" + "[".repeat(62) + "0" + "]".repeat(62) + "}}\n";
        assertThat(FrameCodec.decodeLine(bytes(accepted)).payload()).isNotNull();
        FrameCodec.encode(FrameCodec.decodeLine(bytes(accepted)));
        failure(ProtocolException.Code.PROTOCOL_LIMIT,
                () -> FrameCodec.decodeLine(bytes(accepted.replace("0]", "[0]]"))));
        ObjectNode cyclic = JSON.createObjectNode(); cyclic.set("cycle", cyclic);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> new ProtocolFrame("operation-1", 0, "model.delta", cyclic));
    }

    @Test
    void lineLimitIncludesLfAndCrAndEncoderRejectsOversize() {
        ProtocolFrame frame = frame(0);
        byte[] line = FrameCodec.encode(frame);
        assertThat(FrameCodec.encode(frame, line.length)).isEqualTo(line);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> FrameCodec.encode(frame, line.length - 1));
        JsonLineDecoder reader = new JsonLineDecoder(value -> {}, line.length, line.length, 1);
        reader.push(line); reader.end();
        JsonLineDecoder crlf = new JsonLineDecoder(value -> {}, line.length, line.length + 1, 1);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> crlf.push(bytes(new String(line, StandardCharsets.UTF_8).replace("\n", "\r\n"))));
        int overhead = FrameCodec.encode(new ProtocolFrame("operation-1", 0, "model.delta", JSON.createObjectNode().put("x", ""))).length;
        ProtocolFrame maximum = new ProtocolFrame("operation-1", 0, "model.delta", JSON.createObjectNode().put("x", "a".repeat(FrameCodec.MAXIMUM_LINE_BYTES - overhead)));
        byte[] maximumBytes = FrameCodec.encode(maximum);
        assertThat(maximumBytes).hasSize(FrameCodec.MAXIMUM_LINE_BYTES);
        FrameCodec.decodeLine(maximumBytes);
        ProtocolFrame excessive = new ProtocolFrame("operation-1", 0, "model.delta", maximum.payload().put("extra", true));
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> FrameCodec.encode(excessive));
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> FrameCodec.decodeLine(new byte[FrameCodec.MAXIMUM_LINE_BYTES + 1]));
    }

    @Test
    void utf8EncodedByteLimitIsNotCharacterLimit() {
        ProtocolFrame frame = FrameCodec.decodeLine(bytes(NODE_SAMPLE));
        int actual = FrameCodec.encode(frame).length;
        assertThat(FrameCodec.encode(frame, actual)).hasSize(actual);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> FrameCodec.encode(frame, actual - 1));
    }

    @Test
    void cumulativeBudgetsAndConfiguredHardMaximumsCannotBeReset() {
        byte[] line = FrameCodec.encode(frame(0));
        JsonLineDecoder total = new JsonLineDecoder(value -> {}, line.length, 2L * line.length - 1, 2);
        total.push(line);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> total.push(line));
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> total.push(new byte[0]));
        AtomicInteger calls = new AtomicInteger();
        JsonLineDecoder frames = new JsonLineDecoder(value -> calls.incrementAndGet(), line.length, 2L * line.length, 1);
        frames.push(line);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> frames.push(line));
        assertThat(calls).hasValue(1);
        for (int size : new int[]{0, -1, FrameCodec.MAXIMUM_LINE_BYTES + 1}) {
            failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> new JsonLineDecoder(value -> {}, size, 1, 1));
            failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> FrameCodec.encode(frame(0), size));
        }
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> new JsonLineDecoder(value -> {}, 1, FrameCodec.MAXIMUM_TOTAL_BYTES + 1, 1));
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> new JsonLineDecoder(value -> {}, 1, 1, 65537));
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> new JsonLineDecoder(value -> {}, 1, 0, 1));
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> new JsonLineDecoder(value -> {}, 1, 1, 0));
    }

    @Test
    void defaultFrameCountAndTotalByteHardBoundariesAreEnforced() {
        byte[] line = FrameCodec.encode(frame(0));
        AtomicInteger calls = new AtomicInteger();
        JsonLineDecoder decoder = new JsonLineDecoder(value -> calls.incrementAndGet());
        for (int i = 0; i < 65536; i++) decoder.push(line);
        assertThat(calls).hasValue(65536);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> decoder.push(line));
        int overhead = line.length;
        byte[] large = new byte[FrameCodec.MAXIMUM_LINE_BYTES];
        Arrays.fill(large, (byte) ' ');
        System.arraycopy(line, 0, large, 0, overhead - 1);
        large[large.length - 1] = '\n';
        JsonLineDecoder total = new JsonLineDecoder(value -> {});
        for (int i = 0; i < 32; i++) total.push(large);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> total.push(new byte[]{' '}));
    }

    @Test
    void eofNeverCompletesLastLineAndFailurePermanentlyPoisons() {
        JsonLineDecoder partial = new JsonLineDecoder(value -> { throw new AssertionError("must not deliver"); });
        byte[] line = FrameCodec.encode(frame(0));
        partial.push(Arrays.copyOf(line, line.length - 1));
        invalid(partial::end);
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> partial.push(new byte[]{'\n'}));
        JsonLineDecoder empty = new JsonLineDecoder(value -> {}); empty.end();
        failure(ProtocolException.Code.PROTOCOL_CLOSED, empty::end);
        JsonLineDecoder malformed = new JsonLineDecoder(value -> {});
        invalid(() -> malformed.push(bytes("{bad}\n")));
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> malformed.push(line));
        JsonLineDecoder nullInput = new JsonLineDecoder(value -> {});
        invalid(() -> nullInput.push(null));
        failure(ProtocolException.Code.PROTOCOL_CLOSED, nullInput::end);
    }

    @Test
    void callbackFailureIsSanitizedAndStopsLaterFrames() {
        AtomicInteger calls = new AtomicInteger();
        JsonLineDecoder decoder = new JsonLineDecoder(value -> {
            calls.incrementAndGet(); throw new IllegalStateException("SYNTHETIC_PRIVATE_PATH_CREDENTIAL");
        });
        ProtocolException failure = invalid(() -> decoder.push(bytes(NODE_SAMPLE + NODE_SAMPLE)));
        assertThat(calls).hasValue(1);
        assertThat(failure.getStackTrace()).isEmpty();
        assertThat(failure.getSuppressed()).isEmpty();
        failure.addSuppressed(new IllegalStateException("SYNTHETIC_PRIVATE_DATA"));
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.toString()).isEqualTo("PROTOCOL_INVALID");
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> decoder.push(bytes(NODE_SAMPLE)));
    }

    @Test
    void callbackErrorIsAlsoSanitizedAndErasesOwnedBytes() throws Exception {
        JsonLineDecoder decoder = new JsonLineDecoder(value -> { throw new AssertionError("SYNTHETIC_PRIVATE_DATA"); });
        invalid(() -> decoder.push(bytes(NODE_SAMPLE)));
        assertThat(buffer(decoder)).containsOnly((byte) 0);
        failure(ProtocolException.Code.PROTOCOL_CLOSED, decoder::end);
    }

    @Test
    void callbacksCannotReenterOrCloseAndContinueTheDecoder() {
        JsonLineDecoder[] holder = new JsonLineDecoder[1];
        holder[0] = new JsonLineDecoder(value -> holder[0].push(new byte[0]));
        invalid(() -> holder[0].push(bytes(NODE_SAMPLE)));
        holder[0] = new JsonLineDecoder(value -> holder[0].close());
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> holder[0].push(bytes(NODE_SAMPLE + NODE_SAMPLE)));
    }

    @Test
    void ownedBytesAreErasedAfterConsumeCloseAndFailureNotCallerBytes() throws Exception {
        JsonLineDecoder decoder = new JsonLineDecoder(value -> {});
        byte[] line = bytes(NODE_SAMPLE);
        byte[] copy = line.clone();
        decoder.push(line);
        assertThat(buffer(decoder)).containsOnly((byte) 0);
        assertThat(line).isEqualTo(copy);
        decoder.push(bytes("{\"partial\":"));
        assertThat(buffer(decoder)[0]).isEqualTo((byte) '{');
        decoder.close(); decoder.close();
        assertThat(buffer(decoder)).containsOnly((byte) 0);
        JsonLineDecoder bad = new JsonLineDecoder(value -> {});
        invalid(() -> bad.push(bytes("{\"private\":broken}\n")));
        assertThat(buffer(bad)).containsOnly((byte) 0);
        JsonLineDecoder oversized = new JsonLineDecoder(value -> {}, 8, 100, 1);
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> oversized.push(bytes("12345678")));
        assertThat(buffer(oversized)).containsOnly((byte) 0);
    }

    @Test
    void encoderRejectsInvalidUnicodeAndNonJsonNodeValues() {
        for (ObjectNode payload : List.of(JSON.createObjectNode().put("x", "\ud800"),
                JSON.createObjectNode().put("\udc00", "x"), JSON.createObjectNode().put("x", Double.NaN),
                JSON.createObjectNode().put("x", Double.POSITIVE_INFINITY), JSON.createObjectNode().put("x", 9007199254740992L),
                JSON.createObjectNode().putPOJO("x", new Object()), JSON.createObjectNode().put("x", new byte[]{1}))) {
            invalid(() -> new ProtocolFrame("operation-1", 0, "model.delta", payload));
        }
        invalid(() -> FrameCodec.encode(null));
        invalid(() -> new ProtocolFrame("operation-1", 0, "model.delta", null));
        invalid(() -> new ProtocolFrame("operation-1", -1, "model.delta", JSON.createObjectNode()));
        invalid(() -> new ProtocolFrame("operation-1", Long.MAX_VALUE, "model.delta", JSON.createObjectNode()));
    }

    @Test
    void sequenceStartsAtZeroRejectsDuplicatesGapsWrongOperationAndIsPoisoned() {
        for (ProtocolFrame bad : List.of(frame(1), frame(2),
                new ProtocolFrame("different", 0, "model.delta", JSON.createObjectNode()))) {
            FrameSequence guard = new FrameSequence("operation-1");
            invalid(() -> guard.accept(bad));
            failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> guard.accept(frame(0)));
        }
        FrameSequence duplicate = new FrameSequence("operation-1"); duplicate.accept(frame(0));
        invalid(() -> duplicate.accept(frame(0)));
        failure(ProtocolException.Code.PROTOCOL_CLOSED, duplicate::seal);
        FrameSequence nil = new FrameSequence("operation-1"); invalid(() -> nil.accept(null));
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> nil.accept(frame(0)));
    }

    @Test
    void explicitSealMakesTerminalUniqueAndDoesNotInferType() {
        FrameSequence guard = new FrameSequence("operation-1");
        guard.accept(new ProtocolFrame("operation-1", 0, "model.done", JSON.createObjectNode()));
        guard.accept(frame(1)); // type 本身没有业务授权，只有调用方 seal 才确认终态。
        guard.seal();
        failure(ProtocolException.Code.PROTOCOL_CLOSED, guard::seal);
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> guard.accept(frame(2)));
        guard.close(); guard.close();
        FrameSequence empty = new FrameSequence("operation-1"); invalid(empty::seal);
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> empty.accept(frame(0)));
        FrameSequence terminal = new FrameSequence("operation-1");
        JsonLineDecoder decoder = new JsonLineDecoder(value -> { terminal.accept(value); terminal.seal(); });
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> decoder.push(bytes(NODE_SAMPLE + NODE_SAMPLE)));
    }

    @Test
    void sequenceFrameLimitIsIndependentOfDecoder() {
        FrameSequence guard = new FrameSequence("operation-1");
        for (int i = 0; i < 65536; i++) guard.accept(frame(i));
        failure(ProtocolException.Code.PROTOCOL_LIMIT, () -> guard.accept(frame(65536)));
        failure(ProtocolException.Code.PROTOCOL_CLOSED, () -> guard.accept(frame(65537)));
    }

    private static byte[] buffer(JsonLineDecoder decoder) throws Exception {
        var field = JsonLineDecoder.class.getDeclaredField("buffer"); field.setAccessible(true);
        return (byte[]) field.get(decoder);
    }

    private static ProtocolFrame frame(long sequence) {
        return new ProtocolFrame("operation-1", sequence, "model.delta", JSON.createObjectNode());
    }

    private static ObjectNode root() { return (ObjectNode) JSON.readTree(NODE_SAMPLE); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static ProtocolException invalid(Runnable action) {
        return failure(ProtocolException.Code.PROTOCOL_INVALID, action);
    }
    private static ProtocolException failure(ProtocolException.Code code, Runnable action) {
        ProtocolException failure = assertThrows(ProtocolException.class, action::run);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.getMessage()).isEqualTo(code.name());
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).isEqualTo(code.name());
        return failure;
    }
}
