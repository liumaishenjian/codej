package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * TaskListService 接受的封闭 mutation 命令。
 *
 * <p>actor/Board/Session/Run identity 不在命令中，由宿主 capability 提供；callId 只治理同
 * actor + actorSession + actorRun 域内的重试，模型不能为 CLAIM/RESUME_CLAIM 指定 Run。</p>
 *
 * @since 0.15.0
 */
public sealed interface TaskMutation permits TaskMutation.Create, TaskMutation.Edit, TaskMutation.Transition,
        TaskMutation.Claim, TaskMutation.ResumeClaim, TaskMutation.Release, TaskMutation.Assign,
        TaskMutation.Reassign, TaskMutation.Dependency, TaskMutation.Delete {

    /** 同 actor 域内的幂等调用身份。 */
    TaskCallId callId();

    /**
     * 返回 mutation 的目标 Task；CREATE 尚未分配 ID，因此为空。
     *
     * <p>封闭 switch 使新增 mutation 必须同时决定诊断 identity，避免资源拒绝静默丢失 task_id。</p>
     */
    default Optional<TaskId> targetId() {
        return switch (this) {
            case Create ignored -> Optional.empty();
            case Edit value -> Optional.of(value.taskId());
            case Transition value -> Optional.of(value.taskId());
            case Claim value -> Optional.of(value.taskId());
            case ResumeClaim value -> Optional.of(value.taskId());
            case Release value -> Optional.of(value.taskId());
            case Assign value -> Optional.of(value.taskId());
            case Reassign value -> Optional.of(value.taskId());
            case Dependency value -> Optional.of(value.taskId());
            case Delete value -> Optional.of(value.taskId());
        };
    }

    /**
     * 创建固定 PENDING、无 owner/claim 的任务。
     *
     * @param callId 幂等调用身份
     * @param subject 用户可见摘要
     * @param description 完整任务描述
     * @param activeForm 可选的当前动作短语
     * @param metadata 有界结构化元数据
     * @param blockedBy 初始依赖 Task ID
     */
    record Create(TaskCallId callId, String subject, String description, Optional<String> activeForm,
            TaskMetadata metadata, List<TaskId> blockedBy) implements TaskMutation {
        /** 复制可选值与依赖列表；资源校验由 TaskItem 构造器统一执行。 */
        public Create {
            callId = Objects.requireNonNull(callId); activeForm = Objects.requireNonNull(activeForm);
            metadata = Objects.requireNonNull(metadata); blockedBy = List.copyOf(Objects.requireNonNull(blockedBy));
        }
    }

    /**
     * 修改文本或 metadata；activeFormSpecified 区分“不改”与“清空”。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param expectedClaimEpoch IN_PROGRESS Task 的预期 claim epoch
     * @param subject 可选的新摘要
     * @param description 可选的新描述
     * @param activeFormSpecified 是否显式修改 activeForm
     * @param activeForm 新 activeForm；空值在已指定时表示清除
     * @param metadataPatch metadata 增删补丁
     */
    record Edit(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            OptionalLong expectedClaimEpoch, Optional<String> subject, Optional<String> description,
            boolean activeFormSpecified, Optional<String> activeForm, TaskMetadataPatch metadataPatch)
            implements TaskMutation {
        /** 校验 patch 结构；IN_PROGRESS 的 epoch 语义由当前 Task 状态决定。 */
        public Edit {
            callId = Objects.requireNonNull(callId); taskId = Objects.requireNonNull(taskId);
            expectedClaimEpoch = Objects.requireNonNull(expectedClaimEpoch);
            subject = Objects.requireNonNull(subject); description = Objects.requireNonNull(description);
            activeForm = Objects.requireNonNull(activeForm); metadataPatch = Objects.requireNonNull(metadataPatch);
            if (!activeFormSpecified && activeForm.isPresent()) throw new IllegalArgumentException("未指定 activeForm patch 时不能携带值");
        }
    }

    /**
     * 显式完成或把 COMPLETED reopen 为 PENDING。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param target 目标公开状态
     * @param expectedClaimEpoch 完成 IN_PROGRESS Task 时的预期 claim epoch
     */
    record Transition(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            TaskStatus target, OptionalLong expectedClaimEpoch) implements TaskMutation {
        /** 限制目标状态，避免与 CLAIM/RELEASE 重叠。 */
        public Transition {
            callId = Objects.requireNonNull(callId); taskId = Objects.requireNonNull(taskId);
            target = Objects.requireNonNull(target); expectedClaimEpoch = Objects.requireNonNull(expectedClaimEpoch);
            if (target == TaskStatus.IN_PROGRESS) throw new IllegalArgumentException("IN_PROGRESS 必须使用 CLAIM");
        }
    }

    /**
     * 领取未阻塞 PENDING Task；Run identity 只能来自宿主 capability。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     */
    record Claim(TaskCallId callId, TaskId taskId, long expectedTaskRevision) implements TaskMutation {
        /** 校验 claim identity。 */ public Claim { Objects.requireNonNull(callId); Objects.requireNonNull(taskId); }
    }

    /**
     * Root 对 recoveryRequired Task 建立新 claim epoch；Run identity 只能来自宿主 capability。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param expectedClaimEpoch 已终止 claim 的预期 epoch
     */
    record ResumeClaim(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            long expectedClaimEpoch) implements TaskMutation {
        /** 校验恢复 identity。 */ public ResumeClaim { Objects.requireNonNull(callId); Objects.requireNonNull(taskId); }
    }

    /**
     * 释放当前 claim 并回到 PENDING。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param expectedClaimEpoch 当前 claim epoch
     */
    record Release(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            long expectedClaimEpoch) implements TaskMutation {
        /** 校验 release identity。 */ public Release { Objects.requireNonNull(callId); Objects.requireNonNull(taskId); }
    }

    /**
     * Root 首次分配一个 PENDING Task。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param targetActor 受宿主目录认可的目标 actor
     */
    record Assign(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            TaskActorId targetActor) implements TaskMutation {
        /** 校验 assignment identity。 */ public Assign { Objects.requireNonNull(callId); Objects.requireNonNull(taskId); Objects.requireNonNull(targetActor); }
    }

    /**
     * Root 改变 PENDING owner，或收敛 recoveryRequired claim 后重分配。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param targetActor 受宿主目录认可的新 actor
     * @param expectedClaimEpoch recoveryRequired Task 的预期 claim epoch
     */
    record Reassign(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            TaskActorId targetActor, OptionalLong expectedClaimEpoch) implements TaskMutation {
        /** 校验 reassignment identity。 */ public Reassign { Objects.requireNonNull(callId); Objects.requireNonNull(taskId); Objects.requireNonNull(targetActor); Objects.requireNonNull(expectedClaimEpoch); }
    }

    /**
     * 原子增加/删除 canonical blockedBy。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param expectedBoardRevision 预期 Board revision
     * @param addBlockedBy 要增加的依赖
     * @param removeBlockedBy 要删除的依赖
     */
    record Dependency(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            long expectedBoardRevision, List<TaskId> addBlockedBy, List<TaskId> removeBlockedBy)
            implements TaskMutation {
        /** 复制边变更列表。 */ public Dependency {
            Objects.requireNonNull(callId); Objects.requireNonNull(taskId);
            addBlockedBy = List.copyOf(Objects.requireNonNull(addBlockedBy));
            removeBlockedBy = List.copyOf(Objects.requireNonNull(removeBlockedBy));
        }
    }

    /**
     * Root 删除无入边 Task 并保留 tombstone。
     *
     * @param callId 幂等调用身份
     * @param taskId 目标 Task
     * @param expectedTaskRevision 预期 Task revision
     * @param expectedBoardRevision 预期 Board revision
     */
    record Delete(TaskCallId callId, TaskId taskId, long expectedTaskRevision,
            long expectedBoardRevision) implements TaskMutation {
        /** 校验删除 identity。 */ public Delete { Objects.requireNonNull(callId); Objects.requireNonNull(taskId); }
    }
}
