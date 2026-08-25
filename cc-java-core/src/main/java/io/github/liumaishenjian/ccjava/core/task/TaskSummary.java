package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskActorId;
import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Task List 返回的紧凑投影，不包含 description、metadata、claim 或时间正文。
 *
 * @param id Task identity
 * @param taskRevision Task CAS revision
 * @param status 三态
 * @param subject 有界标题
 * @param blocked 是否存在未完成 blocker
 * @param blockerIds 当前未完成 blocker IDs
 * @param owner 可选 owner
 * @param activeForm 可选进行中短语
 * @param recoveryRequired 是否必须显式恢复 claim
 * @since 0.15.0
 */
public record TaskSummary(TaskId id, long taskRevision, TaskStatus status, String subject,
        boolean blocked, Set<TaskId> blockerIds, Optional<TaskActorId> owner,
        Optional<String> activeForm, boolean recoveryRequired) {
    /** 冻结紧凑投影并保持 blocker 顺序。 */
    public TaskSummary {
        id = Objects.requireNonNull(id, "id 不能为空");
        if (taskRevision < 1) throw new IllegalArgumentException("taskRevision 必须大于 0");
        status = Objects.requireNonNull(status, "status 不能为空");
        subject = Objects.requireNonNull(subject, "subject 不能为空");
        blockerIds = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(blockerIds, "blockerIds 不能为空")));
        owner = Objects.requireNonNull(owner, "owner 不能为空");
        activeForm = Objects.requireNonNull(activeForm, "activeForm 不能为空");
        if (blocked != !blockerIds.isEmpty()) throw new IllegalArgumentException("blocked 与 blockerIds 不一致");
    }
}
