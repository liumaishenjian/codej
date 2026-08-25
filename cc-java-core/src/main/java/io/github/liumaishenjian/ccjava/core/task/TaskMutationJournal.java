package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * Task mutation 在 Session canonical journal 中的持久化端口。
 *
 * <p>{@link TaskListService} 在 mutation 对并发读取可见前同步调用本端口。实现必须在返回前完成
 * durable append；失败必须抛出异常并 fence 所属 Session，Core 随后回滚尚未发布的内存候选。</p>
 *
 * @since 0.15.0
 */
@FunctionalInterface
public interface TaskMutationJournal {
    /**
     * 可靠追加一条成功 mutation 事件。
     *
     * @param ownerSessionId Board 所属 root Session
     * @param event 已通过状态机校验的成功 mutation 事件
     */
    void append(SessionId ownerSessionId, TaskMutationEvent event);

    /**
     * 仅用于不验证 durable recovery 的易失测试禁用实现。
     *
     * @return 丢弃事件的测试端口
     */
    static TaskMutationJournal volatileOnly() { return (ignoredSession, ignoredEvent) -> { }; }
}
