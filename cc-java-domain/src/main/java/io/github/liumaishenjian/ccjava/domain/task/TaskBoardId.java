package io.github.liumaishenjian.ccjava.domain.task;

/**
 * 由 root Session 拥有的 Task Board 身份。
 *
 * @param value 不包含路径或用户正文的稳定标识
 * @since 0.15.0
 */
public record TaskBoardId(String value) {
    /** 校验 Board identity 可安全进入诊断和后续协议。 */
    public TaskBoardId {
        if (value == null || !value.matches("board-[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("Task Board ID 格式无效");
        }
    }
}
