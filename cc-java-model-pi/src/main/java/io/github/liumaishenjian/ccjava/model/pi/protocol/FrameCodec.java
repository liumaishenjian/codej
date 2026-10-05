package io.github.liumaishenjian.ccjava.model.pi.protocol;

import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-100 私有 UTF-8 NDJSON 编解码；JSON 语法、重复键及嵌套约束交给 Jackson。
 *
 * <p>仅校验五字段封套和 JSON 可互操作性，不授权 payload、不识别业务终态、不运行模型。
 * 数值按 Node 的 IEEE-754 语义检查，整数必须安全；不保证任意精度小数无损。</p>
 */
public final class FrameCodec {
    /** 单帧字节硬上限，包含 LF（CRLF 的 CR 也计入）。 */
    public static final int MAXIMUM_LINE_BYTES = 1024 * 1024;
    /** 单操作输入字节硬上限。 */
    public static final long MAXIMUM_TOTAL_BYTES = 32L * 1024 * 1024;
    /** 单方向帧数硬上限。 */
    public static final int MAXIMUM_FRAMES = 65536;
    /** JavaScript 可精确表示的最大整数。 */
    public static final long MAXIMUM_SAFE_INTEGER = 9007199254740991L;
    private static final Pattern OPERATION = Pattern.compile("[a-zA-Z0-9_-]{1,96}");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9]*(?:[._][a-z][a-z0-9]*)*");
    private static final Set<String> FIELDS = Set.of("version", "operationId", "sequence", "type", "payload");
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64)
                    .maxStringLength(MAXIMUM_LINE_BYTES)
                    .maxNameLength(MAXIMUM_LINE_BYTES)
                    .maxNumberLength(MAXIMUM_LINE_BYTES)
                    .maxDocumentLength(MAXIMUM_LINE_BYTES)
                    .build())
            .build()).build();

    private FrameCodec() { }

    /**
     * 编码一帧，返回缓冲包含 LF；不修改调用方 payload。
     * @param frame 已校验帧
     * @return 发送方拥有的缓冲，发送结束后应擦除
     * @throws ProtocolException 非法数据或超过 1 MiB
     */
    public static byte[] encode(ProtocolFrame frame) {
        return encode(frame, MAXIMUM_LINE_BYTES);
    }

    /**
     * 使用业务选择的更严格单帧预算编码；失败时擦除本方法拥有的临时字节。
     * @param frame 已校验帧
     * @param maximumLineBytes 含 LF 的预算，1 至 1 MiB
     * @return 调用方负责擦除的 UTF-8 字节
     * @throws ProtocolException 非法数据或超限，不携带 Jackson 异常
     */
    public static byte[] encode(ProtocolFrame frame, int maximumLineBytes) {
        checkLimit(maximumLineBytes, MAXIMUM_LINE_BYTES);
        byte[] bytes = null;
        BoundedWriter writer = new BoundedWriter(maximumLineBytes - 1);
        try {
            if (frame == null) throw invalid();
            MAPPER.writeValue(writer, frame.wireValue());
            bytes = writer.text.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length >= maximumLineBytes) throw limit();
            byte[] result = Arrays.copyOf(bytes, bytes.length + 1);
            result[result.length - 1] = '\n';
            return result;
        } catch (RuntimeException failure) {
            if (writer.exceeded) throw limit();
            throw sanitized(failure);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            writer.text.setLength(0);
        }
    }

    /**
     * 解码完整且必须带 LF 的单帧；调用方输入不被修改。
     * @param line 一帧 UTF-8 字节，可使用 CRLF
     * @return 防御复制的合法帧；序号连续性需要另交 FrameSequence 校验
     * @throws ProtocolException 缺 LF、非法 JSON/UTF-8、未知字段或超限
     */
    public static ProtocolFrame decodeLine(byte[] line) {
        if (line == null || line.length == 0) throw invalid();
        if (line.length > MAXIMUM_LINE_BYTES) throw limit();
        if (line[line.length - 1] != '\n') throw invalid();
        // 单帧 API 不能把多个物理行当作 JSON 空白。
        for (int i = 0; i < line.length - 1; i++) if (line[i] == '\n') throw invalid();
        return decodeContent(line, line.length - 1);
    }

    static ProtocolFrame decodeContent(byte[] bytes, int length) {
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            // 从字符入口解析，避免 Jackson 的字节编码探测接受 BOM 或 UTF-16。
            if (source.indexOf('\ufeff') == 0) throw invalid();
            try (var parser = MAPPER.createParser(source)) {
                JsonNode root = MAPPER.readTree(parser);
                if (root == null || parser.nextToken() != null) throw invalid();
                checkTree(root, 0);
                if (!root.isObject() || root.size() != FIELDS.size()) throw invalid();
                for (String key : root.propertyNames()) if (!FIELDS.contains(key)) throw invalid();
                JsonNode version = root.get("version");
                JsonNode sequence = root.get("sequence");
                JsonNode operation = root.get("operationId");
                JsonNode type = root.get("type");
                JsonNode payload = root.get("payload");
                if (!version.isNumber() || version.asDouble() != 1 || !sequence.isNumber()
                        || sequence.asDouble() < 0 || sequence.asDouble() != Math.rint(sequence.asDouble())
                        || !operation.isString() || !type.isString() || !payload.isObject()) throw invalid();
                return new ProtocolFrame(operation.asString(), (long) sequence.asDouble(),
                        type.asString(), (ObjectNode) payload);
            }
        } catch (Exception failure) {
            throw sanitized(failure);
        }
    }

    static ObjectNode object() { return MAPPER.createObjectNode(); }

    static void checkHeader(String operationId, long sequence, String type) {
        if (operationId == null || !OPERATION.matcher(operationId).matches()
                || sequence < 0 || sequence > MAXIMUM_SAFE_INTEGER
                || type == null || type.length() > 64 || !TYPE.matcher(type).matches()) throw invalid();
    }

    /** 容器深度从封套 0 开始，与 Node 的 depth >= 64 拒绝规则对应。 */
    static void checkTree(JsonNode node, int depth) {
        if (node == null) throw invalid();
        if (node.isObject() || node.isArray()) {
            if (depth >= 64) throw limit();
            if (node.isObject()) {
                for (var property : node.properties()) {
                    checkUnicode(property.getKey());
                    checkTree(property.getValue(), depth + 1);
                }
            } else {
                for (JsonNode child : node) checkTree(child, depth + 1);
            }
        } else if (node.isString()) {
            checkUnicode(node.asString());
        } else if (node.isNumber()) {
            double number = node.asDouble();
            if (!Double.isFinite(number)
                    || (number == Math.rint(number) && Math.abs(number) > MAXIMUM_SAFE_INTEGER)) throw invalid();
        } else if (!node.isBoolean() && !node.isNull()) {
            // 禁止 POJO、binary、missing 等会被 Jackson 自动转型的非 JSON 值。
            throw invalid();
        }
    }

    private static void checkUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(current)) throw invalid();
        }
    }

    static void checkLimit(long value, long maximum) {
        if (value < 1 || value > maximum) throw limit();
    }

    static ProtocolException invalid() { return new ProtocolException(ProtocolException.Code.PROTOCOL_INVALID); }
    static ProtocolException limit() { return new ProtocolException(ProtocolException.Code.PROTOCOL_LIMIT); }
    static ProtocolException closed() { return new ProtocolException(ProtocolException.Code.PROTOCOL_CLOSED); }

    static ProtocolException sanitized(Throwable failure) {
        // 不保留原对象：即使异常源于不可信回调，也只重新建立封闭码。
        if (failure instanceof ProtocolException protocol) return new ProtocolException(protocol.code());
        if (failure instanceof StreamConstraintsException) return limit();
        return invalid();
    }

    /** 写入前先约束字符数量，避免编码任意大 payload 时生成无界中间字符串。 */
    private static final class BoundedWriter extends Writer {
        private final int maximum;
        private final StringBuilder text = new StringBuilder();
        private boolean exceeded;

        BoundedWriter(int maximum) { this.maximum = maximum; }

        @Override
        public void write(char[] chars, int offset, int length) {
            if (length > maximum - text.length()) {
                exceeded = true;
                throw limit();
            }
            text.append(chars, offset, length);
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }
}
