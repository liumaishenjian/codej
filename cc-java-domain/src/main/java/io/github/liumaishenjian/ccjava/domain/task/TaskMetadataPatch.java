package io.github.liumaishenjian.ccjava.domain.task;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * EDIT mutation 使用的 metadata 增量。
 *
 * <p>后续 Tool Adapter 将 JSON null 转换为 removals；Domain 本身不接收 null。</p>
 *
 * @param upserts 新增或替换的标量
 * @param removals 要删除的 key
 * @since 0.15.0
 */
public record TaskMetadataPatch(Map<String, TaskMetadataValue> upserts, Set<String> removals) {
    /** 复制 patch 并拒绝同一 key 同时更新和删除。 */
    public TaskMetadataPatch {
        upserts = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(upserts, "upserts 不能为空")));
        removals = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(removals, "removals 不能为空")));
        if (upserts.size() + removals.size() > 16) throw new IllegalArgumentException("metadataPatch 操作超过上限");
        new TaskMetadata(upserts);
        for (String key : removals) new TaskMetadata(Map.of(key, new TaskMetadataValue.BooleanValue(false)));
        if (upserts.keySet().stream().anyMatch(removals::contains)) {
            throw new IllegalArgumentException("metadataPatch 不能同时更新和删除同一 key");
        }
    }

    /**
     * 创建不声明任何 metadata 操作的增量。
     * @return upsert 与 removal 集合均为空的不可变 patch
     */
    public static TaskMetadataPatch empty() { return new TaskMetadataPatch(Map.of(), Set.of()); }

    /**
     * 判断 patch 是否未声明操作，不比较应用前后的元数据值。
     * @return upsert 与 removal 均为空时为 true
     */
    public boolean isEmpty() { return upserts.isEmpty() && removals.isEmpty(); }
}
