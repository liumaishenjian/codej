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

    /**
     * 返回规范任务身份。
     * @return Board 内永不复用的 Task ID
     */ public TaskId id() { return item.id(); }
    /**
     * 返回规范任务修订号。
     * @return 用于单项 CAS 的当前 revision
     */ public long revision() { return item.revision(); }
    /**
     * 返回规范任务状态，不从派生阻塞项推断成功。
     * @return 当前持久化状态
     */ public TaskStatus status() { return item.status(); }
    /**
     * 返回任务短标题。
     * @return 规范任务的有界 subject
     */ public String subject() { return item.subject(); }
    /**
     * 返回任务说明，不将其视为可执行命令。
     * @return 规范任务的有界 description
     */ public String description() { return item.description(); }
    /**
     * 返回当前动作的显示短语。
     * @return 可选 active form，不等于真实执行证据
     */ public Optional<String> activeForm() { return item.activeForm(); }
    /**
     * 返回规范任务的结构化元数据。
     * @return 已受类型和大小边界约束的元数据
     */ public TaskMetadata metadata() { return item.metadata(); }
    /**
     * 返回完整声明依赖，而非仅当前未完成的 blocker。
     * @return 不可变依赖身份集合
     */ public Set<TaskId> blockedBy() { return item.blockedBy(); }
    /**
     * 返回任务当前归属 actor。
     * @return 可选 owner，不自动赋予修改权限
     */ public Optional<TaskActorId> owner() { return item.owner(); }
    /**
     * 返回任务当前执行领取事实。
     * @return 可选 claim；是否需恢复由派生 recoveryRequired 表示
     */ public Optional<TaskClaim> claim() { return item.claim(); }
    /**
     * 返回规范任务创建时间。
     * @return 原始创建时间，不随投影刷新改变
     */ public Instant createdAt() { return item.createdAt(); }
    /**
     * 返回规范任务最近更新时间。
     * @return 任务本身的更新时间，不是本视图生成时间
     */ public Instant updatedAt() { return item.updatedAt(); }
}
