package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Task List 只读投影的有界查询。
 *
 * @param status 可选三态过滤
 * @param filter 可选 subject 子串过滤，使用 Locale.ROOT 大小写折叠
 * @param cursor exclusive TaskId 游标
 * @param limit 返回条目上限，1..50
 * @since 0.15.0
 */
public record TaskListQuery(Optional<TaskStatus> status, Optional<String> filter,
        Optional<TaskId> cursor, int limit) {
    /** 校验查询资源边界。 */
    public TaskListQuery {
        status = Objects.requireNonNull(status, "status 不能为空");
        filter = Objects.requireNonNull(filter, "filter 不能为空");
        cursor = Objects.requireNonNull(cursor, "cursor 不能为空");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Task list limit 必须为 1..50");
        filter.ifPresent(value -> {
            if (value.isBlank() || value.codePointCount(0, value.length()) > 200
                    || value.codePoints().anyMatch(Character::isISOControl) || !validUnicode(value)) {
                throw new IllegalArgumentException("Task list filter 无效");
            }
        });
    }

    /** 默认返回前 25 项。 */
    public static TaskListQuery defaults() {
        return new TaskListQuery(Optional.empty(), Optional.empty(), Optional.empty(), 25);
    }

    private static boolean validUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }
}
