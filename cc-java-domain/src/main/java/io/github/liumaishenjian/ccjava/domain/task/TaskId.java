package io.github.liumaishenjian.ccjava.domain.task;

/**
 * 单个 Board 内由 high-water mark 单调分配的任务身份。
 *
 * @param sequence 从 1 开始且删除后永不复用的序号
 * @since 0.15.0
 */
public record TaskId(long sequence) implements Comparable<TaskId> {
    /** 校验任务序号。 */
    public TaskId {
        if (sequence < 1) throw new IllegalArgumentException("Task sequence 必须大于 0");
    }

    /** 返回稳定协议使用的安全文本身份。 */
    public String value() { return "task-" + sequence; }

    @Override public int compareTo(TaskId other) { return Long.compare(sequence, other.sequence); }

    @Override public String toString() { return value(); }
}
