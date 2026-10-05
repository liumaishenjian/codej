package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.model.pi.protocol.FrameCodec;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolException;
import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-100 §3.2 的同步私有凭证 RPC 宿主；只返回 payload，不拥有或输出公共 stdio。
 *
 * <p>调用方通过私有 Connection.send("credential.response", payload) 发送，并在断连时关闭。
 * 身份及用途由可信装配固定；帧 sequence 由 Connection 验证，本层验证业务 requestId。
 * 本层只做重编码预算，不能替代管道原始字节、整个 Worker 操作和物理连接的预算。
 * JSON 节点中的不可变字符串不保证擦除；本层拥有的材料和字节副本均及时关闭或擦除。</p>
 *
 * <p>handle 串行化，close 先以 volatile 置位再等待同一监视器，故阻塞锁等待可见取消，
 * 迟到取得的事务不能越过关闭检查。close 返回前等待本地提交及资源释放，不承诺中断
 * 任意 OS 文件 IO；提交已发布而响应未发送是未知结果，绝不以候选材料伪造 ACK。
 * 任何请求失败封闭本会话并释放事务，调用方不得继续该 Worker 操作。</p>
 */
public final class PiCredentialRpcSession implements AutoCloseable {
    /** 可信装配选择的用途，不接受 RPC 参数覆盖。 */
    public enum Purpose {
        /** 输入前捕获全索引代次，显式保存新登录。 */
        LOGIN,
        /** 固定登录代次，只允许正常刷新。 */
        MODEL
    }

    /** 私有响应及生命周期异常的封闭码，不带底层消息或 cause。 */
    public enum Code {
        /** 请求结构或身份关联非法。 */
        PROTOCOL_INVALID,
        /** 重编码字节或业务请求数超限。 */
        LIMIT,
        /** 已关闭或先前失败。 */
        CLOSED,
        /** 取消或 deadline 到期。 */
        CANCELLED,
        /** 身份代次、修订或事务冲突。 */
        CONFLICT,
        /** 秘密或显式 ENV 引用不可用。 */
        UNAVAILABLE,
        /** 本地存储读取、验证或写入失败。 */
        STORE_FAILED,
        /** 资源清理失败；重复 close 仍须报告。 */
        CLEANUP_FAILED
    }

    /** 仅用于无法返回响应的生命周期失败；不保留底层异常。 */
    public static final class RpcException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        /** 唯一可传播的诊断分类，不保存底层异常。 */
        private final Code code;
        private RpcException(Code code) { super(code.name(), null, false, false); this.code = code; }
        /**
         * 返回宿主清理与失败收敛所需的有限分类。
         * @return 封闭的生命周期失败分类
         */
        public Code code() { return code; }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String operationId;
    private final PiCredentialIdentity identity;
    private final Purpose purpose;
    private final PiCredentialStore store;
    private final long generationOrEpoch;
    private final boolean setDefault;
    private final Function<String, byte[]> environment;
    private final CancellationToken cancellation;
    private volatile boolean closeRequested;
    private boolean closed;
    private boolean cleanupFailed;
    private int nextRequest = 1;
    private long encodedBytes;
    private String transactionId;
    private PiCredentialStore.Transaction transaction;

    /**
     * 建立绑定用途与身份的会话，不触碰存储、环境或公共 IO。
     * @param operationId 私有连接操作标识
     * @param identity 可信目标完整身份
     * @param purpose LOGIN 或 MODEL
     * @param store 可信 Java 权威存储
     * @param generationOrEpoch LOGIN 为输入前全索引代次；MODEL 为正 authEpoch
     * @param setDefault 仅 LOGIN 可设置默认身份
     * @param environment 只解析传入的已保存 ENV 名；返回字节所有权转移给本会话
     * @param token Runtime 取消及剩余时限
     */
    public PiCredentialRpcSession(String operationId, PiCredentialIdentity identity, Purpose purpose,
            PiCredentialStore store, long generationOrEpoch, boolean setDefault,
            Function<String, byte[]> environment, CancellationToken token) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9_-]{1,96}") || identity == null
                || purpose == null || store == null || environment == null || token == null
                || generationOrEpoch < (purpose == Purpose.MODEL ? 1 : 0)
                || purpose == Purpose.MODEL && setDefault) throw failure(Code.PROTOCOL_INVALID);
        this.operationId = operationId; this.identity = identity; this.purpose = purpose;
        this.store = store; this.generationOrEpoch = generationOrEpoch; this.setDefault = setDefault;
        this.environment = environment;
        // Store 锁轮询只使用查询接口；内部关闭源与外部 token 取并集，不注册阻塞取消回调。
        this.cancellation = new CancellationToken() {
            @Override public boolean isCancellationRequested() {
                return closeRequested || token.isCancellationRequested();
            }
            @Override public Optional<Duration> remainingTime() { return token.remainingTime(); }
            @Override public Registration onCancellation(Runnable action) {
                throw failure(Code.PROTOCOL_INVALID);
            }
        };
    }

    /**
     * 消费一个私有请求；失败只返回三字段错误 payload，并使会话失败关闭。
     * @param request Connection 已验证封套及方向序号的 credential.request
     * @return 用于 credential.response 的 payload；发送者不可记录秘密
     */
    public synchronized ObjectNode handle(ProtocolFrame request) {
        int requestId = nextRequest <= 512 ? nextRequest : 512;
        try {
            if (closed || closeRequested) throw failure(Code.CLOSED);
            active();
            if (request == null || !operationId.equals(request.operationId())
                    || !"credential.request".equals(request.type())) throw failure(Code.PROTOCOL_INVALID);
            ObjectNode payload = request.payload();
            JsonNode id = payload.get("requestId");
            if (id != null && id.isIntegralNumber() && id.canConvertToInt()
                    && id.intValue() >= 1 && id.intValue() <= 512) requestId = id.intValue();
            budget(request);
            fields(payload, Set.of("requestId", "action", "arguments"));
            if (nextRequest > 512) throw failure(Code.LIMIT);
            if (id == null || !id.isIntegralNumber() || !id.canConvertToInt()
                    || id.intValue() != nextRequest) throw failure(Code.PROTOCOL_INVALID);
            nextRequest++;
            String action = text(payload, "action");
            JsonNode arguments = payload.get("arguments");
            ObjectNode result = switch (action) {
                case "list" -> { fields(arguments, Set.of()); yield list(); }
                case "read" -> { fields(arguments, Set.of()); yield credential(readCurrent()); }
                case "begin" -> { fields(arguments, Set.of()); yield begin(); }
                case "finish" -> finish(arguments);
                case "abort" -> abort(arguments);
                default -> throw failure(Code.PROTOCOL_INVALID);
            };
            active();
            ObjectNode response = JSON.createObjectNode().put("requestId", requestId).put("ok", true);
            response.set("result", result);
            // send 的实际序号归连接所有；最大合法序号保守预留封套长度。
            budget(new ProtocolFrame(operationId, FrameCodec.MAXIMUM_SAFE_INTEGER, "credential.response", response));
            return response;
        } catch (Throwable failed) {
            Code code = classify(failed);
            closeRequested = true;
            try { release(); } catch (Throwable cleanup) { code = Code.CLEANUP_FAILED; }
            closed = true;
            return JSON.createObjectNode().put("requestId", requestId).put("ok", false).put("code", code.name());
        }
    }

    private ObjectNode list() {
        var entry = store.snapshot(cancellation).find(identity);
        ObjectNode result = JSON.createObjectNode();
        var entries = result.putArray("entries");
        entry.ifPresent(value -> entries.addObject().put("providerId", identity.providerId())
                .put("type", value.kind() == PiCredentialMaterial.Kind.OAUTH ? "oauth" : "api_key"));
        return result;
    }

    private PiCredentialMaterial readCurrent() {
        if (purpose == Purpose.MODEL) return store.read(identity, generationOrEpoch, cancellation);
        var snapshot = loginSnapshot();
        var entry = snapshot.find(identity);
        return entry.isEmpty() ? null : store.read(identity, entry.orElseThrow().authEpoch(), cancellation);
    }

    private PiCredentialStore.Snapshot loginSnapshot() {
        var snapshot = store.snapshot(cancellation);
        if (snapshot.generation() != generationOrEpoch) throw failure(Code.CONFLICT);
        return snapshot;
    }

    private ObjectNode begin() {
        if (transactionId != null) throw failure(Code.CONFLICT);
        if (purpose == Purpose.MODEL) {
            transaction = store.beginModify(identity, generationOrEpoch, cancellation);
            active(); // close 已置位时，handle 的失败路径释放刚取得的锁。
        }
        ObjectNode result = credential(transaction == null ? readCurrent() : transaction.snapshot());
        active();
        transactionId = UUID.randomUUID().toString();
        result.put("transactionId", transactionId);
        return result;
    }

    private ObjectNode finish(JsonNode arguments) {
        fields(arguments, Set.of("transactionId", "change"));
        requireTransaction(text(arguments, "transactionId"));
        // 校验 id 后立即消费；包括坏 change、delete 和候选解析失败都不得重用。
        transactionId = null;
        try {
            JsonNode change = arguments.get("change");
            String kind = text(change, "kind");
            if ("keep".equals(kind)) {
                fields(change, Set.of("kind"));
                if (purpose != Purpose.MODEL) throw failure(Code.PROTOCOL_INVALID);
                return credential(transaction.finish(PiCredentialStore.Change.KEEP, null, cancellation));
            }
            if (!"put".equals(kind)) throw failure(Code.PROTOCOL_INVALID);
            fields(change, Set.of("kind", "credential"));
            byte[] bytes = JSON.writeValueAsBytes(change.get("credential"));
            try (PiCredentialMaterial candidate = PiCredentialMaterial.fromJson(bytes)) {
                candidate.requireIdentity(identity);
                // ENV_REF 是 Java 装配契约，不授予 Node 将环境值或引用回写的权限。
                if (candidate.kind() == PiCredentialMaterial.Kind.ENV_REF) throw failure(Code.PROTOCOL_INVALID);
                if (purpose == Purpose.MODEL) {
                    return credential(transaction.finish(PiCredentialStore.Change.PUT, candidate, cancellation));
                }
                var published = store.saveLogin(identity, candidate, generationOrEpoch, setDefault, cancellation);
                return credential(store.read(identity, published.authEpoch(), cancellation));
            } finally { Arrays.fill(bytes, (byte) 0); }
        } finally { release(); }
    }

    private ObjectNode abort(JsonNode arguments) {
        fields(arguments, Set.of("transactionId"));
        requireTransaction(text(arguments, "transactionId"));
        release();
        return JSON.createObjectNode();
    }

    private void requireTransaction(String id) {
        if (transactionId == null || !transactionId.equals(id)) throw failure(Code.CONFLICT);
    }

    /** 消耗调用方转交的材料；ENV 临时导出字节从不持久化。 */
    private ObjectNode credential(PiCredentialMaterial material) {
        ObjectNode result = JSON.createObjectNode();
        if (material == null) return result.putNull("credential");
        try (material) {
            if (material.kind() == PiCredentialMaterial.Kind.ENV_REF) {
                byte[] key;
                try { key = environment.apply(material.variableName()); }
                catch (Throwable failed) { throw failure(Code.UNAVAILABLE); }
                try {
                    active();
                    if (key == null) throw failure(Code.UNAVAILABLE);
                    try (var temporary = PiCredentialMaterial.apiKey(key)) {
                        result.set("credential", export(temporary));
                    }
                } finally { if (key != null) Arrays.fill(key, (byte) 0); }
            } else result.set("credential", export(material));
            return result;
        }
    }

    private JsonNode export(PiCredentialMaterial material) {
        byte[] bytes = material.copyJson();
        try { return JSON.readTree(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private void budget(ProtocolFrame frame) {
        byte[] bytes = FrameCodec.encode(frame, purpose == Purpose.LOGIN ? 32 * 1024 : FrameCodec.MAXIMUM_LINE_BYTES);
        try {
            long maximum = purpose == Purpose.LOGIN ? 128 * 1024 : 16L * 1024 * 1024;
            if (bytes.length > maximum - encodedBytes) throw failure(Code.LIMIT);
            encodedBytes += bytes.length;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private void active() {
        if (closeRequested) throw failure(Code.CLOSED);
        if (Thread.currentThread().isInterrupted() || cancellation.isCancellationRequested()
                || cancellation.remainingTime().filter(value -> value.isZero() || value.isNegative()).isPresent()) {
            throw failure(Code.CANCELLED);
        }
    }

    private void release() {
        transactionId = null;
        if (transaction != null) {
            try { transaction.close(); transaction = null; }
            catch (Throwable failed) { cleanupFailed = true; throw failure(Code.CLEANUP_FAILED); }
        }
        if (cleanupFailed) throw failure(Code.CLEANUP_FAILED);
    }

    /**
     * 断连时释放未完成事务；先取消锁排队再等待 handle，防止迟到锁泄漏。
     * @throws RpcException 清理失败；再次关闭仍报告固定 CLEANUP_FAILED
     */
    @Override public void close() {
        closeRequested = true;
        synchronized (this) {
            closed = true;
            release();
        }
    }

    private static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()
                || !node.propertyNames().containsAll(names)) throw failure(Code.PROTOCOL_INVALID);
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || !value.isString()) throw failure(Code.PROTOCOL_INVALID);
        return value.asString();
    }
    private static Code classify(Throwable failure) {
        if (failure instanceof RpcException rpc) return rpc.code();
        if (failure instanceof ProtocolException protocol) return protocol.code() == ProtocolException.Code.PROTOCOL_LIMIT
                ? Code.LIMIT : Code.PROTOCOL_INVALID;
        if (failure instanceof ProviderAuthException auth) return switch (auth.code()) {
            case AUTH_CANCELLED -> Code.CANCELLED;
            case AUTH_TRANSACTION_CONFLICT, AUTH_PROFILE_CONFLICT -> Code.CONFLICT;
            case AUTH_SECRET_UNAVAILABLE -> Code.UNAVAILABLE;
            default -> Code.STORE_FAILED;
        };
        if (failure instanceof IllegalArgumentException) return Code.PROTOCOL_INVALID;
        return Code.STORE_FAILED;
    }
    private static RpcException failure(Code code) { return new RpcException(code); }
    @Override public String toString() { return "PI_CREDENTIAL_RPC_SESSION"; }
}
