package io.github.liumaishenjian.ccjava.domain.task;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * canonical Task 与完整 Board/Run 状态组合后的只读投影。
 *
 * @param item canonical 任务
 * @param blocks 由其他任务 blockedBy 反向推导的任务集合
 * @param blocked 是否仍存在未完成 blocker
 * @param activeBlockers 当前未完成 blocker
 * @param recoveryRequired claim 对应 Run 已终止且需要显式处理
 * @since 0.15.0
 */
public record TaskItemView(TaskItem item, Set<TaskId> blocks, boolean blocked,
        Set<TaskId> activeBlockers, boolean recoveryRequired) {
    /** 复制派生集合并校验 blocked 标志。 */
    public TaskItemView {
        item = Objects.requireNonNull(item, "item 不能为空");
        blocks = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(blocks, "blocks 不能为空")));
        activeBlockers = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(activeBlockers, "activeBlockers 不能为空")));
        if (blocked != !activeBlockers.isEmpty()) throw new IllegalArgumentException("blocked 与 activeBlockers 不一致");
        if (recoveryRequired && item.claim().isEmpty()) throw new IllegalArgumentException("recoveryRequired 必须存在 claim");
    }

    /** Task identity。 */ public TaskId id() { return item.id(); }
    /** Task revision。 */ public long revision() { return item.revision(); }
    /** Task status。 */ public TaskStatus status() { return item.status(); }
    /** Task subject。 */ public String subject() { return item.subject(); }
    /** Task description。 */ public String description() { return item.description(); }
    /** Task active form。 */ public Optional<String> activeForm() { return item.activeForm(); }
    /** Task metadata。 */ public TaskMetadata metadata() { return item.metadata(); }
    /** canonical blockedBy。 */ public Set<TaskId> blockedBy() { return item.blockedBy(); }
    /** Task owner。 */ public Optional<TaskActorId> owner() { return item.owner(); }
    /** Task claim。 */ public Optional<TaskClaim> claim() { return item.claim(); }
    /** 创建时间。 */ public Instant createdAt() { return item.createdAt(); }
    /** 更新时间。 */ public Instant updatedAt() { return item.updatedAt(); }
}
