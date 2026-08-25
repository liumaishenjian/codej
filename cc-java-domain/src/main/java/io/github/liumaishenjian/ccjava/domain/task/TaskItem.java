package io.github.liumaishenjian.ccjava.domain.task;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Task Board 内单个任务的 canonical 不可变状态。
 *
 * <p>只保存单向 {@code blockedBy}；反向 blocks、blocked 与 recoveryRequired 由完整 Board/Run 状态投影。
 * claim 只允许出现在 IN_PROGRESS，且必须与 owner 一致。</p>
 *
 * @param id 稳定且不复用的任务身份
 * @param revision 从 1 开始的任务 CAS revision
 * @param status 三态之一
 * @param subject 有界任务标题
 * @param description 有界详细描述
 * @param activeForm 可选进行中短语
 * @param metadata 有界结构化 metadata
 * @param blockedBy canonical 依赖边
 * @param owner 可选分配 actor
 * @param claim 当前活动或待恢复 claim
 * @param lastClaimEpoch 已使用过的最大 claim epoch
 * @param createdAt 创建时间
 * @param updatedAt 最近 mutation 时间
 * @since 0.15.0
 */
public record TaskItem(TaskId id, long revision, TaskStatus status, String subject, String description,
        Optional<String> activeForm, TaskMetadata metadata, Set<TaskId> blockedBy,
        Optional<TaskActorId> owner, Optional<TaskClaim> claim, long lastClaimEpoch,
        Instant createdAt, Instant updatedAt) {

    /** 强制文本、依赖、claim、revision 与时间不变量。 */
    public TaskItem {
        id = Objects.requireNonNull(id, "id 不能为空");
        if (revision < 1) throw new IllegalArgumentException("Task revision 必须大于 0");
        status = Objects.requireNonNull(status, "status 不能为空");
        subject = requireText(subject, "subject", 200, true, -1, false);
        description = requireText(description, "description", Integer.MAX_VALUE, false, 4_096, true);
        activeForm = Objects.requireNonNull(activeForm, "activeForm 不能为空")
                .map(value -> requireText(value, "activeForm", 200, true, -1, false));
        metadata = Objects.requireNonNull(metadata, "metadata 不能为空");
        blockedBy = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(blockedBy, "blockedBy 不能为空")));
        if (blockedBy.size() > 32 || blockedBy.contains(id)) throw new IllegalArgumentException("blockedBy 无效或超过上限");
        owner = Objects.requireNonNull(owner, "owner 不能为空");
        claim = Objects.requireNonNull(claim, "claim 不能为空");
        if (lastClaimEpoch < 0) throw new IllegalArgumentException("lastClaimEpoch 不能为负数");
        if (claim.isPresent()) {
            TaskClaim current = claim.orElseThrow();
            if (status != TaskStatus.IN_PROGRESS || owner.isEmpty()
                    || !owner.orElseThrow().equals(current.actorId()) || current.epoch() != lastClaimEpoch) {
                throw new IllegalArgumentException("Task claim 与状态、owner 或 epoch 不一致");
            }
        } else if (status == TaskStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("IN_PROGRESS Task 必须携带 claim");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
    }

    private static String requireText(String value, String name, int maxCodePoints,
            boolean requireNonBlank, int maxUtf8Bytes, boolean allowDescriptionWhitespace) {
        Objects.requireNonNull(value, name + " 不能为空");
        boolean invalidControl = value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !(allowDescriptionWhitespace && (codePoint == '\n' || codePoint == '\t')));
        if ((requireNonBlank && value.isBlank()) || value.codePointCount(0, value.length()) > maxCodePoints
                || (maxUtf8Bytes >= 0 && value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes)
                || invalidControl || !validUnicode(value)) {
            throw new IllegalArgumentException(name + " 无效或超过上限");
        }
        return value;
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
