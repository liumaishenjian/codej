package io.github.liumaishenjian.ccjava.domain.task;

import io.github.liumaishenjian.ccjava.domain.RunId;
import java.time.Instant;
import java.util.Objects;

/**
 * IN_PROGRESS Task 当前活动或待恢复的认领事实。
 *
 * @param actorId 认领 actor
 * @param runId 认领发生的独立 Run
 * @param epoch 每次 claim/resume 严格递增的防迟到序号
 * @param claimedAt 认领时间
 * @since 0.15.0
 */
public record TaskClaim(TaskActorId actorId, RunId runId, long epoch, Instant claimedAt) {
    /** 校验 claim identity、epoch 和时间。 */
    public TaskClaim {
        actorId = Objects.requireNonNull(actorId, "actorId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        if (epoch < 1) throw new IllegalArgumentException("claim epoch 必须大于 0");
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt 不能为空");
    }
}
