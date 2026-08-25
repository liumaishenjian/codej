package io.github.liumaishenjian.ccjava.domain.subagent;

import java.util.Objects;
import java.util.Set;
import io.github.liumaishenjian.ccjava.domain.task.TaskId;

/**
 * 父任务提交给 Supervisor 的不可变委托请求。
 *
 * @param delegationId 父调用关联键
 * @param definitionId 已冻结定义
 * @param prompt 子任务正文，仅进入独立子 Session
 * @param requestedTools 委托的进一步 Tool 收窄集
 * @param requestedBudget 委托预算
 * @param background 是否后台返回
 * @param depth Host 持有的嵌套 provenance；根委托固定为 1，child 委托只能由 Host 递增
 * @param worktree 是否请求 Git Worktree 隔离
 * @param taskScope 父模型提出、由宿主重新验证并冻结的 parent Board Task 范围
 * @since 0.12.0
 */
public record ChildTaskRequest(DelegationId delegationId, AgentDefinitionId definitionId, String prompt,
        Set<String> requestedTools, ChildBudget requestedBudget, boolean background, int depth, boolean worktree,
        Set<TaskId> taskScope) {
    /**
     * 保留不请求 parent Task capability 的既有构造契约。
     *
     * @param delegationId 父调用关联键
     * @param definitionId 已冻结定义
     * @param prompt 子任务正文
     * @param requestedTools 委托 Tool 收窄集
     * @param requestedBudget 委托预算
     * @param background 是否后台返回
     * @param depth 宿主嵌套深度
     * @param worktree 是否请求 Worktree
     */
    public ChildTaskRequest(DelegationId delegationId, AgentDefinitionId definitionId, String prompt,
            Set<String> requestedTools, ChildBudget requestedBudget, boolean background, int depth,
            boolean worktree) {
        this(delegationId, definitionId, prompt, requestedTools, requestedBudget, background, depth, worktree,
                Set.of());
    }

    /**
     * 校验正文、Tool 收窄集、预算与宿主深度 provenance。
     *
     * @param delegationId 父调用关联键
     * @param definitionId 已冻结定义
     * @param prompt 子任务正文
     * @param requestedTools 委托 Tool 收窄集
     * @param requestedBudget 委托预算
     * @param background 是否后台返回
     * @param depth 宿主持有的嵌套深度
     * @param worktree 是否请求 Worktree
     * @param taskScope 经宿主验证的 parent Board Task 范围
     */
    public ChildTaskRequest {
        delegationId = Objects.requireNonNull(delegationId, "delegationId 不能为空");
        definitionId = Objects.requireNonNull(definitionId, "definitionId 不能为空");
        if (prompt == null || prompt.isBlank() || prompt.codePointCount(0, prompt.length()) > 1_048_576) throw new IllegalArgumentException("prompt 无效");
        requestedTools = Set.copyOf(Objects.requireNonNull(requestedTools, "requestedTools 不能为空"));
        requestedBudget = Objects.requireNonNull(requestedBudget, "requestedBudget 不能为空");
        taskScope = Set.copyOf(Objects.requireNonNull(taskScope, "taskScope 不能为空"));
        if (depth < 1) throw new IllegalArgumentException("depth 必须大于 0");
        if (taskScope.size() > 32) throw new IllegalArgumentException("Task 委托范围超过上限");
    }
}
