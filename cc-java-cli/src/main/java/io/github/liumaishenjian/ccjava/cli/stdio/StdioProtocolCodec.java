package io.github.liumaishenjian.ccjava.cli.stdio;

import java.util.Optional;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 在 Jackson Tree Model 与项目内部 stdio v0 信封之间转换。
 *
 * <p>Codec 在 CLI Adapter 内手工校验必需字段，允许未知可选字段，从而同时满足
 * fail-closed 的主版本/类型约束和后续字段演进。Jackson 类型不会进入 Domain/Core。</p>
 *
 * @since 0.1.0
 */
public final class StdioProtocolCodec {

    /** 文本标识字段的最大 UTF-16 字符数。 */
    public static final int MAX_IDENTIFIER_CHARS = 128;

    private static final Set<String> COMMAND_TYPES = Set.of(
            "initialize",
            "run.start",
            "plan.start",
            "plan.review.resolve",
            "plan.resume",
            "plan.execute",
            "plan.feedback",
            "input.begin",
            "input.chunk",
            "input.commit",
            "run.cancel",
            "approval.resolve",
            "question.resolve",
            "checkpoint.list",
            "checkpoint.diff",
            "checkpoint.undo",
            "session.command",
            "provider.control",
            "skill.invoke",
            "task.inspect",
            "task.wait",
            "task.cancel",
            "task.keep",
            "task.remove",
            "file.suggest",
            "shutdown");

    /** {@code file.suggest} 查询的最大 code point 数。 */
    public static final int MAX_SUGGEST_QUERY_CODE_POINTS = 256;

    private final ObjectMapper mapper;

    /**
     * 创建启用重复字段检测的 JSON Codec。
     */
    public StdioProtocolCodec() {
        mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * 解析并校验一条 Client 命令。
     *
     * @param line 不包含换行符的 UTF-8 解码文本
     * @return 独立命令对象
     * @throws StdioProtocolException JSON 或信封不合法时
     */
    public StdioProtocol.Command decodeCommand(String line)
            throws StdioProtocolException {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception exception) {
            throw error("MALFORMED_JSON", "消息不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw error("INVALID_ENVELOPE", "消息根节点必须是 JSON Object");
        }

        int version = requiredInt(root, "version");
        if (version != StdioProtocol.VERSION) {
            throw error("UNSUPPORTED_VERSION", "不支持该协议主版本");
        }
        String type = requiredText(root, "type");
        String requestId = requiredText(root, "requestId");
        if (!COMMAND_TYPES.contains(type)) {
            throw new StdioProtocolException(
                    "UNKNOWN_COMMAND",
                    requestId,
                    "不支持该命令类型");
        }
        long sequence = requiredLong(root, "sequence");
        if (sequence < 1) {
            throw new StdioProtocolException(
                    "INVALID_SEQUENCE",
                    requestId,
                    "sequence 必须从 1 开始");
        }
        Optional<String> sessionId = optionalText(root, "sessionId", requestId);
        Optional<String> runId = optionalText(root, "runId", requestId);
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw new StdioProtocolException(
                    "INVALID_PAYLOAD",
                    requestId,
                    "payload 必须是 JSON Object");
        }
        if ("session.command".equals(type)) {
            validateSessionCommand(root, (ObjectNode) payloadNode, requestId);
        }
        if ("provider.control".equals(type)) {
            validateProviderControl(root, (ObjectNode) payloadNode, requestId);
        }
        if ("skill.invoke".equals(type)) {
            validateSkillInvoke(root, (ObjectNode) payloadNode, requestId);
        }
        if (type.startsWith("task.")) {
            validateTaskCommand(root, (ObjectNode) payloadNode, requestId, type);
        }
        if ("file.suggest".equals(type)) {
            validateFileSuggest(root, (ObjectNode) payloadNode, requestId);
        }

        return new StdioProtocol.Command(
                version,
                type,
                requestId,
                sessionId,
                runId,
                sequence,
                (ObjectNode) payloadNode);
    }

    /**
     * 把事件编码成不含换行符的 JSON。
     *
     * @param event 已分配输出序号的事件
     * @return 单行 JSON
     */
    public String encodeEvent(StdioProtocol.Event event) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", event.version());
        root.put("type", event.type());
        root.put("requestId", event.requestId());
        event.sessionId().ifPresent(value -> root.put("sessionId", value));
        event.runId().ifPresent(value -> root.put("runId", value));
        root.put("sequence", event.sequence());
        root.set("payload", event.payload());
        return mapper.writeValueAsString(root);
    }

    /**
     * 创建供 Adapter 组装事件数据的空 Object。
     *
     * @return 新 ObjectNode
     */
    public ObjectNode objectNode() {
        return mapper.createObjectNode();
    }

    /**
     * 创建供 Adapter 组装事件数据的空 Array。
     *
     * @return 新 ArrayNode
     */
    public ArrayNode arrayNode() {
        return mapper.createArrayNode();
    }

    private void validateSessionCommand(JsonNode root, ObjectNode payload, String requestId)
            throws StdioProtocolException {
        Set<String> envelope = Set.of("version", "type", "requestId", "sessionId", "runId", "sequence", "payload");
        if (root.properties().stream().anyMatch(entry -> !envelope.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "session.command 包含未知信封字段");
        }
        if (root.get("sessionId") == null || root.get("runId") != null) {
            throw new StdioProtocolException("INVALID_ENVELOPE", requestId, "session.command 必须携带 Session 且不能携带 Run");
        }
        Set<String> fields = Set.of("protocolVersion", "commandId", "intent", "arguments");
        if (payload.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "session.command payload 包含未知字段");
        }
        JsonNode protocolVersion = payload.get("protocolVersion");
        if (protocolVersion == null || !protocolVersion.isIntegralNumber()
                || !protocolVersion.canConvertToInt()
                || protocolVersion.intValue() != StdioProtocol.VERSION) {
            throw new StdioProtocolException("UNSUPPORTED_VERSION", requestId, "session.command protocolVersion 不受支持");
        }
        String commandId = requiredPayloadText(payload, "commandId", requestId);
        if (invalidIdentifier(commandId)) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "commandId 非法");
        }
        String intent = requiredPayloadText(payload, "intent", requestId);
        JsonNode arguments = payload.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "arguments 必须是 JSON Object");
        }
        validateSessionCommandArguments(intent, (ObjectNode) arguments, requestId);
    }

    /** 校验显式 Skill 命令的精确信封与有界参数。 */
    private void validateTaskCommand(JsonNode root, ObjectNode payload, String requestId, String type)
            throws StdioProtocolException {
        Set<String> envelope = Set.of("version", "type", "requestId", "sessionId", "runId", "sequence", "payload");
        Set<String> allowed = "task.wait".equals(type) ? Set.of("taskId", "timeoutMillis") : Set.of("taskId");
        if (root.properties().stream().anyMatch(entry -> !envelope.contains(entry.getKey()))
                || root.get("sessionId") == null || root.get("runId") != null
                || payload.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new StdioProtocolException("INVALID_ENVELOPE", requestId, type + " 信封无效");
        }
        String taskId = requiredPayloadText(payload, "taskId", requestId);
        if (!taskId.matches("task-[A-Za-z0-9_-]{1,96}"))
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "taskId 无效");
        if ("task.wait".equals(type)) {
            JsonNode timeout = payload.get("timeoutMillis");
            if (timeout == null || !timeout.canConvertToInt() || timeout.intValue() < 1 || timeout.intValue() > 30_000)
                throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "timeoutMillis 无效");
        }
    }

    private void validateSkillInvoke(JsonNode root, ObjectNode payload, String requestId)
            throws StdioProtocolException {
        Set<String> envelope = Set.of("version", "type", "requestId", "sessionId", "runId", "sequence", "payload");
        if (root.properties().stream().anyMatch(entry -> !envelope.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "skill.invoke 包含未知信封字段");
        }
        if (root.get("sessionId") == null || root.get("runId") != null
                || payload.properties().stream().anyMatch(entry -> !Set.of("name", "arguments").contains(entry.getKey()))) {
            throw new StdioProtocolException("INVALID_ENVELOPE", requestId, "skill.invoke 信封无效");
        }
        String name = requiredPayloadText(payload, "name", requestId);
        JsonNode arguments = payload.get("arguments");
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 64
                || arguments == null || !arguments.isString()
                || arguments.stringValue().codePointCount(0, arguments.stringValue().length()) > 8_192) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "skill.invoke 参数无效");
        }
    }

    /**
     * 校验 {@code file.suggest} 的精确信封与唯一 {@code query} 字段。
     *
     * <p>该命令只用于补全展示，因此必须携带已初始化 Session、不得携带 Run，也不接受任何
     * 其他字段；未知字段、控制字符和超限查询一律 fail closed。</p>
     */
    private void validateFileSuggest(JsonNode root, ObjectNode payload, String requestId)
            throws StdioProtocolException {
        Set<String> envelope = Set.of(
                "version", "type", "requestId", "sessionId", "runId", "sequence", "payload");
        if (root.properties().stream().anyMatch(entry -> !envelope.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "file.suggest 包含未知信封字段");
        }
        if (root.get("sessionId") == null || root.get("runId") != null) {
            throw new StdioProtocolException(
                    "INVALID_ENVELOPE", requestId, "file.suggest 必须携带 Session 且不能携带 Run");
        }
        if (payload.properties().stream().anyMatch(entry -> !entry.getKey().equals("query"))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "file.suggest payload 包含未知字段");
        }
        String query = requiredPayloadText(payload, "query", requestId);
        if (query.codePointCount(0, query.length()) > MAX_SUGGEST_QUERY_CODE_POINTS
                || query.chars().anyMatch(Character::isISOControl)) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "file.suggest query 非法");
        }
    }

    /** 校验不含 secret 的 Provider 控制命令；登录必须继续使用独立 stdin 子进程。 */
    private void validateProviderControl(JsonNode root, ObjectNode payload, String requestId)
            throws StdioProtocolException {
        Set<String> envelope = Set.of("version", "type", "requestId", "sessionId", "runId", "sequence", "payload");
        if (root.properties().stream().anyMatch(entry -> !envelope.contains(entry.getKey()))
                || root.get("sessionId") == null || root.get("runId") != null) {
            throw new StdioProtocolException("INVALID_ENVELOPE", requestId,
                    "provider.control 必须携带 Session 且不能携带 Run");
        }
        Set<String> fields = Set.of("controlId", "intent", "arguments");
        if (payload.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "provider.control payload 包含未知字段");
        }
        if (invalidIdentifier(requiredPayloadText(payload, "controlId", requestId))) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "controlId 非法");
        }
        String intent = requiredPayloadText(payload, "intent", requestId);
        JsonNode rawArguments = payload.get("arguments");
        if (rawArguments == null || !rawArguments.isObject()) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "arguments 必须是 JSON Object");
        }
        ObjectNode arguments = (ObjectNode) rawArguments;
        Set<String> allowed = switch (intent) {
            case "providers.configure" -> Set.of("baseUrl", "modelId");
            case "providers.add" -> Set.of("providerId", "displayName", "baseUrl", "modelId");
            case "auth.list" -> Set.of();
            case "auth.probe" -> Set.of("providerId", "profileId", "modelId");
            case "auth.logout" -> Set.of("providerId", "profileId", "confirmed");
            case "models.list" -> Set.of("providerId");
            case "models.add" -> Set.of("providerId", "modelId", "setDefault");
            case "models.remove" -> Set.of("providerId", "modelId");
            case "models.use" -> Set.of("providerId", "modelId", "profileId", "setDefault");
            default -> throw new StdioProtocolException("INVALID_ARGUMENT", requestId,
                    "未知 provider.control intent");
        };
        if (arguments.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "provider.control arguments 包含未知字段");
        }
        if (intent.equals("auth.list") && !arguments.isEmpty()) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "auth.list 不接受参数");
        }
        boolean providerAdd = intent.equals("providers.add");
        boolean providerConfigure = intent.equals("providers.configure");
        boolean modelMutation = intent.equals("models.add") || intent.equals("models.remove");
        validateOptionalControlText(arguments, "providerId", requestId,
                providerAdd || intent.equals("auth.probe") || intent.equals("auth.logout")
                        || intent.equals("models.use") || modelMutation);
        if (providerAdd || providerConfigure) {
            if (providerAdd) validateProviderAddText(arguments, "displayName", requestId, 80, 256);
            validateProviderAddText(arguments, "baseUrl", requestId, 2_048, 4_096);
            validateProviderAddText(arguments, "modelId", requestId, 256, 1_024);
        }
        validateOptionalControlText(arguments, "profileId", requestId,
                intent.equals("auth.probe") || intent.equals("auth.logout"));
        validateOptionalControlText(arguments, "modelId", requestId,
                intent.equals("models.use") || modelMutation);
        if (intent.equals("auth.probe") && arguments.get("modelId") != null) {
            validateOptionalControlText(arguments, "modelId", requestId, true);
        }
        if (intent.equals("models.add") || intent.equals("models.use")) {
            validateOptionalControlBoolean(arguments, "setDefault", requestId);
        }
        if (intent.equals("auth.logout")) {
            JsonNode confirmed = arguments.get("confirmed");
            if (confirmed == null || !confirmed.isBoolean() || !confirmed.booleanValue()) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "logout 需要显式确认");
            }
        }
    }

    /** 校验 providers.add 自由文本的 code point、UTF-8 byte 与控制字符上限。 */
    private void validateProviderAddText(ObjectNode arguments, String field, String requestId,
                                         int maximumCodePoints, int maximumBytes)
            throws StdioProtocolException {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isString()) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, field + " 缺失");
        }
        String text = value.stringValue();
        if (text.isBlank() || !text.equals(text.strip())
                || text.codePointCount(0, text.length()) > maximumCodePoints
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximumBytes
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, field + " 非法");
        }
    }

    private void validateOptionalControlBoolean(ObjectNode arguments, String field, String requestId)
            throws StdioProtocolException {
        JsonNode value = arguments.get(field);
        if (value != null && !value.isBoolean()) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, field + " 非法");
        }
    }

    private void validateOptionalControlText(ObjectNode arguments, String field, String requestId, boolean required)
            throws StdioProtocolException {
        JsonNode value = arguments.get(field);
        if (value == null) {
            if (required) throw new StdioProtocolException("INVALID_ARGUMENT", requestId, field + " 缺失");
            return;
        }
        if (!value.isString() || invalidCommandText(value.stringValue())) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, field + " 非法");
        }
    }
    private String requiredPayloadText(ObjectNode payload, String field, String requestId) throws StdioProtocolException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString()) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, field + " 必须是字符串");
        }
        return value.stringValue();
    }

    private void validateSessionCommandArguments(String intent, ObjectNode arguments, String requestId)
            throws StdioProtocolException {
        Set<String> allowed = switch (intent) {
            case "help", "clear", "context", "doctor", "tasks" -> Set.of();
            case "compact" -> Set.of("anchors");
            case "model" -> Set.of("name");
            case "permissions" -> Set.of("mode", "selection");
            case "resume" -> Set.of("sessionId");
            case "plan-status" -> Set.of();
            case "plan-reject" -> Set.of("planId");
            case "plan-step-complete", "plan-step-begin" -> Set.of("workspaceDigest");
            case "plan-approve" -> Set.of("planId", "workspaceDigest");
            case "plan-execute" -> Set.of("planId", "workspaceDigest", "maxSteps");
            case "plan" -> Set.of("objective", "workspaceDigest", "steps");
            default -> throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "未知 session.command intent");
        };
        if (arguments.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "arguments 包含未知字段");
        }
        if ((intent.equals("help") || intent.equals("clear") || intent.equals("context") || intent.equals("doctor")
                || intent.equals("tasks")) && !arguments.isEmpty()) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "该 intent 不接受 arguments");
        }
        if (intent.equals("compact")) {
            JsonNode anchors = arguments.get("anchors");
            if (anchors == null || !anchors.isArray() || anchors.size() > 16
                    || java.util.stream.StreamSupport.stream(anchors.spliterator(), false)
                    .anyMatch(value -> !value.isString() || invalidCompactAnchor(value.stringValue()))) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "compact anchors 非法");
            }
        }
        if (intent.equals("model") && invalidCommandText(requiredPayloadText(arguments, "name", requestId))) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "model name 非法");
        }
        if (intent.equals("permissions") && !arguments.isEmpty()) {
            if (arguments.has("mode") && arguments.has("selection")) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "permissions 参数冲突");
            }
            if (arguments.has("mode")) {
                String mode = requiredPayloadText(arguments, "mode", requestId);
                if (!mode.equals("DEFAULT") && !mode.equals("PLAN") && !mode.equals("ACCEPT_EDITS")) {
                    throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "permissions mode 非法");
                }
            }
            if (arguments.has("selection")) {
                String selection = requiredPayloadText(arguments, "selection", requestId);
                if (!selection.equals("PLAN") && !selection.equals("ASK") && !selection.equals("AUTO")) {
                    throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "permissions selection 非法");
                }
            }
        }
        if (intent.equals("resume") && invalidCommandText(requiredPayloadText(arguments, "sessionId", requestId))) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "resume sessionId 非法");
        }
        if ((intent.equals("plan-approve") || intent.equals("plan-step-begin")
                || intent.equals("plan-step-complete") || intent.equals("plan-execute"))
                && invalidCommandText(requiredPayloadText(arguments, "workspaceDigest", requestId))) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "plan workspaceDigest 非法");
        }
        if ((intent.equals("plan-approve") || intent.equals("plan-reject") || intent.equals("plan-execute"))
                && invalidCommandText(requiredPayloadText(arguments, "planId", requestId))) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "planId 非法");
        }
        if (intent.equals("plan-execute")) {
            JsonNode maxSteps = arguments.get("maxSteps");
            if (maxSteps == null || !maxSteps.isIntegralNumber() || maxSteps.intValue() < 1 || maxSteps.intValue() > 128) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "plan maxSteps 非法");
            }
        }
        if (intent.equals("plan")) {
            if (invalidCommandText(requiredPayloadText(arguments, "objective", requestId))
                    || invalidCommandText(requiredPayloadText(arguments, "workspaceDigest", requestId))) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "plan 文本非法");
            }
            JsonNode steps = arguments.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 128) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "plan steps 非法");
            }
            java.util.Set<Integer> ordinals = new java.util.HashSet<>();
            for (JsonNode step : steps) {
                if (!step.isObject() || step.properties().stream().anyMatch(e -> !java.util.Set.of("ordinal", "title", "detail", "expectedDigest").contains(e.getKey())))
                    throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "plan step 非法");
                if (!step.get("ordinal").canConvertToInt() || !ordinals.add(step.get("ordinal").intValue())
                        || invalidCommandText(step.get("title").stringValue()) || invalidCommandText(step.get("detail").stringValue())
                        || invalidCommandText(step.get("expectedDigest").stringValue()))
                    throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "plan step 非法");
            }
        }
    }

    private static boolean invalidIdentifier(String value) {
        return value.isBlank() || value.codePointCount(0, value.length()) > MAX_IDENTIFIER_CHARS
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean invalidCommandText(String value) {
        return value.isBlank() || value.codePointCount(0, value.length()) > 256
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean invalidCompactAnchor(String value) {
        return value.isBlank() || value.codePointCount(0, value.length()) > 512
                || value.chars().anyMatch(Character::isISOControl);
    }

    private int requiredInt(JsonNode root, String field)
            throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw error("INVALID_ENVELOPE", field + " 必须是整数");
        }
        return value.intValue();
    }

    private long requiredLong(JsonNode root, String field)
            throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw error("INVALID_ENVELOPE", field + " 必须是整数");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode root, String field)
            throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            throw error("INVALID_ENVELOPE", field + " 必须是字符串");
        }
        return checkedText(value.stringValue(), field, StdioProtocol.UNAVAILABLE_REQUEST_ID);
    }

    private Optional<String> optionalText(
            JsonNode root,
            String field,
            String requestId) throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new StdioProtocolException(
                    "INVALID_ENVELOPE",
                    requestId,
                    field + " 必须是字符串");
        }
        return Optional.of(checkedText(value.stringValue(), field, requestId));
    }

    private String checkedText(String value, String field, String requestId)
            throws StdioProtocolException {
        if (value.isBlank() || value.length() > MAX_IDENTIFIER_CHARS) {
            throw new StdioProtocolException(
                    "INVALID_ENVELOPE",
                    requestId,
                    field + " 为空或超过长度限制");
        }
        return value;
    }

    private StdioProtocolException error(String code, String message) {
        return new StdioProtocolException(
                code,
                StdioProtocol.UNAVAILABLE_REQUEST_ID,
                message);
    }
}
