package io.github.liumaishenjian.ccjava.model.pi.protocol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 已校验的 v1 五字段帧；Jackson 类型仅留在 Pi 边缘模块，不进入 Domain/Core。
 *
 * <p>payload 是普通 JSON 数据，原型同名属性没有特殊含义；所有权通过防御复制隔离。
 * 不提供包含正文的默认字符串表示，不承诺擦除 JVM 中的 JSON 字符串。</p>
 */
public final class ProtocolFrame {
    private final String operationId;
    private final long sequence;
    private final String type;
    private final ObjectNode payload;

    /**
     * 建立发送帧；检查结构、Unicode、数值和整个帧的深度，字节上限在编码时检查。
     * @param operationId 宿主生成的 ASCII 操作标识，1 至 96 字符
     * @param sequence 非负 JavaScript safe integer；连续性由 FrameSequence 约束
     * @param type 最多 64 字符的小写点分/下划线分段名称
     * @param payload 非 null JSON 对象；不接受 POJO 或非 JSON 节点
     * @throws ProtocolException 参数违反私有协议
     */
    public ProtocolFrame(String operationId, long sequence, String type, ObjectNode payload) {
        FrameCodec.checkHeader(operationId, sequence, type);
        if (payload == null) throw FrameCodec.invalid();
        ObjectNode snapshot;
        try {
            // 复制前检查深度也阻止循环图触发 deepCopy 的无限递归。
            FrameCodec.checkTree(payload, 1);
            snapshot = payload.deepCopy();
            FrameCodec.checkTree(snapshot, 1);
        } catch (RuntimeException failure) {
            throw FrameCodec.sanitized(failure);
        }
        this.operationId = operationId;
        this.sequence = sequence;
        this.type = type;
        this.payload = snapshot;
    }

    /**
     * 返回本批唯一支持的协议版本。
     * @return 固定版本 1
     */
    public int version() { return 1; }

    /**
     * 返回可信宿主生成、需要与通道绑定比较的操作身份。
     * @return 仅供关联校验的操作标识，不应记录为诊断正文
     */
    public String operationId() { return operationId; }

    /**
     * 返回待交给单方向护栏检查连续性的序号。
     * @return 当前方向的帧序号
     */
    public long sequence() { return sequence; }

    /**
     * 返回语法合法的事件名；业务层仍须按操作白名单验证。
     * @return 业务事件类型，不表示该事件已经获业务授权
     */
    public String type() { return type; }

    /**
     * 隔离 payload 所有权，防止下游修改已验证帧。
     * @return 调用方拥有的可变副本，调用方负责安全消费且不得自动记录
     */
    public ObjectNode payload() { return payload.deepCopy(); }

    JsonNode wireValue() {
        ObjectNode root = FrameCodec.object();
        root.put("version", 1);
        root.put("operationId", operationId);
        root.put("sequence", sequence);
        root.put("type", type);
        root.set("payload", payload);
        return root;
    }

    @Override
    public String toString() { return "PROTOCOL_FRAME"; }
}
