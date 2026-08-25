package io.github.liumaishenjian.ccjava.domain.task;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 有界、不可变且可确定编码的 Task metadata。
 *
 * @param values 最多 16 个受限 key 对应的封闭标量
 * @since 0.15.0
 */
public record TaskMetadata(Map<String, TaskMetadataValue> values) {
    /** 空 metadata。 */
    public static final TaskMetadata EMPTY = new TaskMetadata(Map.of());
    private static final String KEY_PATTERN = "[a-z][a-z0-9_.-]{0,63}";

    /** 复制并验证 key、value 与 4096 UTF-8 bytes 总预算。 */
    public TaskMetadata {
        Objects.requireNonNull(values, "values 不能为空");
        if (values.size() > 16) throw new IllegalArgumentException("Task metadata key 超过上限");
        TreeMap<String, TaskMetadataValue> copy = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || !key.matches(KEY_PATTERN)) throw new IllegalArgumentException("Task metadata key 无效");
            copy.put(key, Objects.requireNonNull(value, "Task metadata value 不能为空"));
        });
        values = Collections.unmodifiableMap(copy);
        if (canonicalJsonBytes(values) > 4_096) throw new IllegalArgumentException("Task metadata 超过 UTF-8 上限");
    }

    /** 应用独立 upsert/removal patch，并重新验证完整结果预算。 */
    public TaskMetadata apply(TaskMetadataPatch patch) {
        Objects.requireNonNull(patch, "patch 不能为空");
        TreeMap<String, TaskMetadataValue> next = new TreeMap<>(values);
        patch.removals().forEach(next::remove);
        next.putAll(patch.upserts());
        return new TaskMetadata(next);
    }

    /** 返回 canonical JSON UTF-8 字节数，仅用于资源治理而不暴露正文。 */
    public int canonicalJsonBytes() { return canonicalJsonBytes(values); }

    private static int canonicalJsonBytes(Map<String, TaskMetadataValue> source) {
        int bytes = 2;
        int index = 0;
        for (var entry : new TreeMap<>(source).entrySet()) {
            if (index++ > 0) bytes++;
            bytes += 2 + entry.getKey().getBytes(StandardCharsets.US_ASCII).length + 1;
            bytes += entry.getValue().canonicalJsonBytes();
        }
        return bytes;
    }
}
