package io.github.liumaishenjian.ccjava.cli.auth;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 独立拥有可擦除 UTF-8 字节的 typed Pi 材料，不把 OAuth JSON 伪装成旧 Key。
 *
 * <p>工厂复制输入；所有返回材料及 copyJson 返回的副本归调用方清理。close 幂等且擦除
 * 本对象字节。Jackson/JVM 临时不可变字符串不保证擦除；toString 与异常不包含秘密或 cause。
 * ENV_REF 只保存变量名称，永不解析环境。同步方法允许跨线程移交所有权。</p>
 */
public final class PiCredentialMaterial implements AutoCloseable {
    /** 单字段 ASCII 字符上限，与锁定 Node 契约一致。 */
    public static final int MAX_ITEM_BYTES = 16384;
    /** 紧凑 JSON 材料整体字节上限。 */
    public static final int MAX_BYTES = 24576;
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** 持久化材料种类；与认证方法分开校验。 */
    public enum Kind {
        /** 明确保存的 API Key。 */
        API_KEY,
        /** 只保存环境变量名称。 */
        ENV_REF,
        /** 访问令牌、刷新令牌、过期时间和账户。 */
        OAUTH
    }

    private final Kind kind;
    private final byte[] bytes;
    private boolean closed;

    private PiCredentialMaterial(Kind kind, byte[] bytes) { this.kind = kind; this.bytes = bytes; }

    /**
     * 复制并校验 Node 兼容的 typed JSON；拒绝未知、重复字段和非法 UTF-8。
     * ENV_REF 的独立编码为 type=env_ref、variableName，不作为 Node OAuth 输入。
     * @param source 调用方拥有的 JSON 字节
     * @return 调用方必须 close 的独立材料
     */
    public static PiCredentialMaterial fromJson(byte[] source) {
        byte[] owned = null;
        try {
            if (source == null || source.length == 0 || source.length > MAX_BYTES) throw invalid();
            owned = source.clone();
            String sourceText = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(owned)).toString();
            // 必须解析刚验证的字符，不能让Jackson再次按原始字节探测UTF-16或BOM。
            if (sourceText.startsWith("\ufeff")) throw invalid();
            JsonNode root = JSON.readTree(sourceText);
            Kind kind = validate(root);
            byte[] canonical = JSON.writeValueAsBytes(root);
            if (canonical.length > MAX_BYTES) { Arrays.fill(canonical, (byte) 0); throw invalid(); }
            return new PiCredentialMaterial(kind, canonical);
        } catch (CharacterCodingException | RuntimeException failure) {
            throw invalid();
        } finally { if (owned != null) Arrays.fill(owned, (byte) 0); }
    }

    /**
     * 复制可打印 ASCII Key。输入不被消耗，调用方自行擦除。
     * @param key Key 字节
     * @return 独立材料
     */
    public static PiCredentialMaterial apiKey(byte[] key) {
        return encode(Map.of("type", "api_key", "key", ascii(key, MAX_ITEM_BYTES)));
    }

    /**
     * 保存变量名称，不读取值。
     * @param variableName 合法环境变量名
     * @return 独立 ENV 引用
     */
    public static PiCredentialMaterial envRef(String variableName) {
        if (variableName == null || !variableName.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw invalid();
        return encode(Map.of("type", "env_ref", "variableName", variableName));
    }

    /**
     * 复制 OAuth 材料；账户不能在正常刷新中变化，store 负责跨修订校验。
     * @param access 访问令牌
     * @param refresh 刷新令牌
     * @param expires 正的 JavaScript 安全整数毫秒时间
     * @param accountId 1 至 256 个可打印 ASCII 字节的账户标识
     * @return 独立 OAuth 材料
     */
    public static PiCredentialMaterial oauth(byte[] access, byte[] refresh, long expires, byte[] accountId) {
        return encode(Map.of("type", "oauth", "access", ascii(access, MAX_ITEM_BYTES),
                "refresh", ascii(refresh, MAX_ITEM_BYTES), "expires", expires, "accountId", ascii(accountId, 256)));
    }

    /**
     * 查询非秘密类型，不需要访问内部字节。
     * @return 非秘密材料种类；关闭后仍可查询
     */
    public Kind kind() { return kind; }

    /**
     * 在协议边界导出受限 JSON，不返回内部数组。
     * @return 独立 JSON 副本，调用方必须擦除；关闭后拒绝读取
     */
    public synchronized byte[] copyJson() { requireOpen(); return bytes.clone(); }

    /**
     * 防御复制以隔离事务与调用方的所有权。
     * @return 调用方必须 close 的独立材料副本
     */
    public synchronized PiCredentialMaterial copy() { requireOpen(); return new PiCredentialMaterial(kind, bytes.clone()); }

    /**
     * 只读取引用名称，不解析环境值。
     * @return ENV_REF 的变量名，其他种类拒绝；此方法永不查询环境
     */
    public synchronized String variableName() {
        requireOpen();
        if (kind != Kind.ENV_REF) throw invalid();
        return JSON.readTree(bytes).get("variableName").asText();
    }

    boolean sameAccount(PiCredentialMaterial other) {
        byte[] left = copyJson();
        byte[] right = other.copyJson();
        try {
            return kind == Kind.OAUTH && other.kind == Kind.OAUTH
                    && JSON.readTree(left).get("accountId").equals(JSON.readTree(right).get("accountId"));
        } finally { Arrays.fill(left, (byte) 0); Arrays.fill(right, (byte) 0); }
    }

    void requireIdentity(PiCredentialIdentity identity) {
        if (identity == null || (identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH) != (kind == Kind.OAUTH)) {
            throw invalid();
        }
        synchronized (this) { requireOpen(); }
    }

    /** 擦除本对象持有的字节；不声称擦除调用方副本或 JVM 不可变字符串。 */
    @Override public synchronized void close() { Arrays.fill(bytes, (byte) 0); closed = true; }
    @Override public String toString() { return "<redacted>"; }

    private void requireOpen() { if (closed) throw new IllegalStateException("PI_MATERIAL_CLOSED"); }
    private static PiCredentialMaterial encode(Object value) {
        byte[] encoded = null;
        try { encoded = JSON.writeValueAsBytes(value); return fromJson(encoded); }
        catch (RuntimeException failure) { throw invalid(); }
        finally { if (encoded != null) Arrays.fill(encoded, (byte) 0); }
    }
    private static Kind validate(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        String type = text(node, "type");
        Kind kind;
        Set<String> fields;
        switch (type) {
            case "api_key" -> {
                kind = Kind.API_KEY; fields = Set.of("type", "key"); printable(text(node, "key"), MAX_ITEM_BYTES);
            }
            case "oauth" -> {
                kind = Kind.OAUTH; fields = Set.of("type", "access", "refresh", "expires", "accountId");
                printable(text(node, "access"), MAX_ITEM_BYTES); printable(text(node, "refresh"), MAX_ITEM_BYTES);
                printable(text(node, "accountId"), 256);
                JsonNode expiry = node.get("expires");
                if (expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong()
                        || expiry.longValue() <= 0 || expiry.longValue() > MAX_SAFE_INTEGER) throw invalid();
            }
            case "env_ref" -> {
                kind = Kind.ENV_REF; fields = Set.of("type", "variableName");
                if (!text(node, "variableName").matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw invalid();
            }
            default -> throw invalid();
        }
        if (!node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()).equals(fields)) throw invalid();
        return kind;
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }
    private static String ascii(byte[] value, int maximum) {
        if (value == null || value.length == 0 || value.length > maximum) throw invalid();
        for (byte b : value) if (b < 32 || b > 126) throw invalid();
        return new String(value, StandardCharsets.US_ASCII);
    }
    private static void printable(String value, int maximum) {
        if (value.isEmpty() || value.length() > maximum) throw invalid();
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PI_MATERIAL_INVALID"); }
}
