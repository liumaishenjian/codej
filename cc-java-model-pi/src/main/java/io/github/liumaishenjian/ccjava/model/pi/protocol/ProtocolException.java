package io.github.liumaishenjian.ccjava.model.pi.protocol;

/**
 * 私有协议的封闭失败；不保存输入、路径、凭证、底层 cause 或调用栈。
 *
 * <p>禁止调用方把底层异常附加到本异常；业务层只能根据错误码结束操作，不能复用已失败的通道。</p>
 */
public final class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 允许跨边缘传播的有限错误分类。 */
    public enum Code {
        /** 帧内容、编码或调用顺序非法。 */
        PROTOCOL_INVALID,
        /** 达到不可重置的资源上限。 */
        PROTOCOL_LIMIT,
        /** 通道已经关闭、封存或失败。 */
        PROTOCOL_CLOSED
    }

    /** 唯一可序列化的协议诊断字段，不保存底层异常或输入。 */
    private final Code code;

    /**
     * 建立不可附加 cause、suppressed exception 或栈信息的失败。
     * @param code 封闭分类；null 归为非法协议
     */
    public ProtocolException(Code code) {
        super((code == null ? Code.PROTOCOL_INVALID : code).name(), null, false, false);
        this.code = code == null ? Code.PROTOCOL_INVALID : code;
    }

    /**
     * 提供业务失败收敛所需的分类，不暴露底层诊断。
     * @return 可安全记录的封闭分类
     */
    public Code code() {
        return code;
    }

    @Override
    public String toString() {
        return code.name();
    }
}
