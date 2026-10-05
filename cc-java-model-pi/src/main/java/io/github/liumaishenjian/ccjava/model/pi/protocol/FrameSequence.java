package io.github.liumaishenjian.ccjava.model.pi.protocol;

/**
 * 私有通道单方向的操作身份与连续序号护栏；两个方向必须使用不同实例。
 *
 * <p>从零计数，任何失败都永久 poison；业务调用方识别终态后必须显式 seal。
 * 本类不推测 type 的业务含义，不代表 Worker 已结束或模型回合已通过校验。
 * 仅供单线程同步使用，不允许跨操作复用。</p>
 */
public final class FrameSequence implements AutoCloseable {
    private final String operationId;
    private long next;
    private boolean closed;

    /**
     * 绑定宿主生成的操作身份。
     * @param operationId 1 至 96 位 ASCII 字母、数字、下划线或横线
     * @throws ProtocolException 操作标识非法
     */
    public FrameSequence(String operationId) {
        FrameCodec.checkHeader(operationId, 0, "frame");
        this.operationId = operationId;
    }

    /**
     * 接受恰好一个连续帧；非法后即使提供正确帧也无法恢复。
     * @param frame 已经过封套校验的帧
     * @throws ProtocolException 错操作、跳号、重复、超限或已封存
     */
    public void accept(ProtocolFrame frame) {
        try {
            if (closed) throw FrameCodec.closed();
            if (frame == null || !operationId.equals(frame.operationId()) || frame.sequence() != next) {
                throw FrameCodec.invalid();
            }
            if (next >= FrameCodec.MAXIMUM_FRAMES) throw FrameCodec.limit();
            next++;
        } catch (ProtocolException failure) {
            closed = true;
            throw failure;
        }
    }

    /**
     * 业务已验证刚接受的帧是唯一终态时显式封存。
     * @throws ProtocolException 尚未接受任何帧、重复封存或先前已经失败
     */
    public void seal() {
        if (closed) throw FrameCodec.closed();
        closed = true;
        if (next == 0) throw FrameCodec.invalid();
    }

    /** 取消/失败清理，不表示成功终态；幂等且不重置序号。 */
    @Override
    public void close() { closed = true; }

    @Override
    public String toString() { return "PROTOCOL_SEQUENCE"; }
}
