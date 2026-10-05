package io.github.liumaishenjian.ccjava.model.pi.protocol;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 单操作、单线程的增量 NDJSON 解码器；物理 chunk 可以切在 UTF-8 码点中间。
 *
 * <p>同步回调前擦除本行自有字节，关闭/失败擦除全部缓冲。不擦除调用方输入、
 * Jackson/JVM 中的字符串或回调保存的 payload。不可重入，不负责识别业务终态；
 * 调用方须在回调中使用 FrameSequence，并在确定终态后显式 seal。</p>
 */
public final class JsonLineDecoder implements AutoCloseable {
    private final byte[] buffer;
    private final long maximumTotalBytes;
    private final int maximumFrames;
    private Consumer<ProtocolFrame> onFrame;
    private int used;
    private long total;
    private int frames;
    private boolean closed;
    private boolean pushing;

    /**
     * 使用 1 MiB/帧、32 MiB/操作及 65,536 帧硬上限。
     * @param onFrame 同步消费或入队回调；不得重入本解码器
     * @throws ProtocolException 回调为 null
     */
    public JsonLineDecoder(Consumer<ProtocolFrame> onFrame) {
        this(onFrame, FrameCodec.MAXIMUM_LINE_BYTES, FrameCodec.MAXIMUM_TOTAL_BYTES, FrameCodec.MAXIMUM_FRAMES);
    }

    /**
     * 业务可选择更严格预算；这些预算在关闭或失败后均不能重置。
     * @param onFrame 同步帧回调；异常被脱敏且停止后续帧
     * @param maximumLineBytes 包含 LF 的帧预算，1 至 1 MiB
     * @param maximumTotalBytes 整个输入预算，1 至 32 MiB
     * @param maximumFrames 最多帧数，1 至 65,536
     * @throws ProtocolException 回调缺失或预算越界
     */
    public JsonLineDecoder(Consumer<ProtocolFrame> onFrame, int maximumLineBytes,
            long maximumTotalBytes, int maximumFrames) {
        if (onFrame == null) throw FrameCodec.invalid();
        FrameCodec.checkLimit(maximumLineBytes, FrameCodec.MAXIMUM_LINE_BYTES);
        FrameCodec.checkLimit(maximumTotalBytes, FrameCodec.MAXIMUM_TOTAL_BYTES);
        FrameCodec.checkLimit(maximumFrames, FrameCodec.MAXIMUM_FRAMES);
        this.buffer = new byte[maximumLineBytes];
        this.maximumTotalBytes = maximumTotalBytes;
        this.maximumFrames = maximumFrames;
        this.onFrame = onFrame;
    }

    /**
     * 消费输入但不保存调用方数组；每个完整合法帧仅回调一次。
     * @param bytes 本次物理字节片段；可以为空，不可为 null
     * @throws ProtocolException 非法输入、预算超限、回调失败或实例已关闭
     */
    public void push(byte[] bytes) {
        if (closed) throw FrameCodec.closed();
        try {
            if (pushing || bytes == null) throw FrameCodec.invalid();
            pushing = true;
            if (bytes.length > maximumTotalBytes - total) throw FrameCodec.limit();
            total += bytes.length;
            for (byte value : bytes) {
                if (value == '\n') {
                    if (++frames > maximumFrames) throw FrameCodec.limit();
                    ProtocolFrame frame;
                    try {
                        frame = FrameCodec.decodeContent(buffer, used);
                    } finally {
                        Arrays.fill(buffer, 0, used, (byte) 0);
                        used = 0;
                    }
                    onFrame.accept(frame);
                    if (closed) throw FrameCodec.closed();
                } else {
                    if (used >= buffer.length - 1) throw FrameCodec.limit();
                    buffer[used++] = value;
                }
            }
        } catch (Throwable failure) {
            // 回调属于边缘扩展；即使抛出 Error 也不能携带其正文越过私有协议边界。
            close();
            throw FrameCodec.sanitized(failure);
        } finally {
            pushing = false;
        }
    }

    /**
     * 声明 EOF；不把末行补成成功帧。空流是否缺少业务终态由调用方判断。
     * @throws ProtocolException 存在不完整末行、重入或已经关闭
     */
    public void end() {
        if (closed) throw FrameCodec.closed();
        boolean invalid = used != 0 || pushing;
        close();
        if (invalid) throw FrameCodec.invalid();
    }

    /** 幂等清理；擦除完整自有缓冲并释放回调引用，实例永久不可复用。 */
    @Override
    public void close() {
        closed = true;
        Arrays.fill(buffer, (byte) 0);
        used = 0;
        onFrame = null;
    }

    @Override
    public String toString() { return "PROTOCOL_DECODER"; }
}
