package io.github.liumaishenjian.ccjava.model.pi.process;

/**
 * Worker 边缘的封闭失败分类；不保存路径、argv、payload、底层异常或调用栈。
 * 不允许追加 cause/suppressed，调用方不得把本异常解释成业务终态。
 */
public final class PiWorkerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 允许向调用者公开的诊断码。 */
    public enum Code {
        /** 可信配置不符合固定入口或环境白名单。 */
        CONFIGURATION_INVALID,
        /** 无法启动受信可执行文件。 */
        START_FAILED,
        /** 帧序列、封套、EOF 或终态不合法。 */
        PROTOCOL_INVALID,
        /** 帧、操作字节或 stderr 超限。 */
        LIMIT_EXCEEDED,
        /** 私有管道故障。 */
        IO_FAILED,
        /** 调用者已经取消或等待线程被中断。 */
        CANCELLED,
        /** 不可重置的总预算耗尽。 */
        DEADLINE_EXCEEDED,
        /** 连接已关闭，或终态已被消费。 */
        CLOSED,
        /** 在独立清理窗口内未确认进程及全部 IO 线程结束。 */
        CLEANUP_FAILED
    }

    /** 唯一诊断状态；不保留底层异常或任何私有通道正文。 */
    private final Code code;

    /**
     * 创建只有封闭码的失败。
     * @param code 失败分类，null 视为 IO_FAILED
     */
    public PiWorkerException(Code code) {
        super((code == null ? Code.IO_FAILED : code).name(), null, false, false);
        this.code = code == null ? Code.IO_FAILED : code;
    }

    /**
     * 返回供宿主收敛失败使用的有限分类。
     * @return 可安全用于诊断的封闭分类
     */
    public Code code() { return code; }

    @Override
    public String toString() { return code.name(); }
}
