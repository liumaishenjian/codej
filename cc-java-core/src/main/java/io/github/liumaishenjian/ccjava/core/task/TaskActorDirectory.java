package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.task.TaskActorId;

/**
 * 验证 ASSIGN/REASSIGN 目标是否属于宿主认可的 Task actor 集合。
 *
 * <p>Batch B 只定义内存查询 Port，不读取模型文本、Session 文件或外部目录；Batch C 的 composition
 * 必须从可信 root/child capability 注册表提供实现，不能把任意格式合法字符串当成存在的 actor。</p>
 *
 * @since 0.15.0
 */
@FunctionalInterface
public interface TaskActorDirectory {
    /**
     * 判断目标 actor 是否可被当前 root Task Tool 分配。
     *
     * @param actor 已完成格式校验的目标身份
     * @return 仅当宿主确认该 actor 存在且可分配时为 true
     */
    boolean assignable(TaskActorId actor);
}
