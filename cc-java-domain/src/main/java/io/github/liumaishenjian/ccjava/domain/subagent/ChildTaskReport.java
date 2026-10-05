package io.github.liumaishenjian.ccjava.domain.subagent;

import io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 可注入父 Context 的唯一有界子任务投影。
 *
 * <p>禁止包含绝对路径、Prompt、Tool 参数/输出、Provider 原文或 Secret。</p>
 *
 * @param taskId 子任务身份
 * @param definitionId 定义身份
 * @param status 唯一终态或当前状态
 * @param failureCode 固定失败分类
 * @param modelTurns 模型回合计数
 * @param toolCalls Tool 调用计数
 * @param estimatedTokens 估算 Token
 * @param elapsed 墙钟耗时
 * @param summary 最多 4096 code points 的脱敏摘要
 * @param verified 是否取得明确 Runtime 终态
 * @param worktreeDisposition 可选 worktree opaque disposition
 * @param cleanupStatus 本次声明 owned 资源的独立清理快照，不代表运行结果或 Worktree 删除
 * @since 0.12.0
 */
public record ChildTaskReport(ChildTaskId taskId, AgentDefinitionId definitionId, ChildTaskStatus status,
        ChildTaskFailureCode failureCode, int modelTurns, int toolCalls, long estimatedTokens,
        Duration elapsed, String summary, boolean verified, Optional<String> worktreeDisposition,
        ResourceCleanupStatus cleanupStatus) {
    public static final int MAX_SUMMARY_CHARACTERS = 4096;

    /**
     * 保留旧报告构造；缺少清理证据不能推断资源已释放。
     * @param taskId 子任务身份
     * @param definitionId 定义身份
     * @param status 运行状态
     * @param failureCode 固定失败分类
     * @param modelTurns 模型回合计数
     * @param toolCalls 工具调用计数
     * @param estimatedTokens 估算 Token
     * @param elapsed 墙钟耗时
     * @param summary 有界脱敏摘要
     * @param verified 是否取得 Runtime 终态
     * @param worktreeDisposition 可选 Worktree 诊断
     */
    public ChildTaskReport(ChildTaskId taskId, AgentDefinitionId definitionId, ChildTaskStatus status,
            ChildTaskFailureCode failureCode, int modelTurns, int toolCalls, long estimatedTokens,
            Duration elapsed, String summary, boolean verified, Optional<String> worktreeDisposition) {
        this(taskId, definitionId, status, failureCode, modelTurns, toolCalls, estimatedTokens,
                elapsed, summary, verified, worktreeDisposition, ResourceCleanupStatus.UNKNOWN);
    }

    /**
     * 创建不含 Worktree 诊断和清理证据的兼容报告。
     * @param taskId 子任务身份
     * @param definitionId 定义身份
     * @param status 运行状态
     * @param failureCode 固定失败分类
     * @param modelTurns 模型回合计数
     * @param toolCalls 工具调用计数
     * @param estimatedTokens 估算 Token
     * @param elapsed 墙钟耗时
     * @param summary 有界脱敏摘要
     * @param verified 是否取得 Runtime 终态
     */
    public ChildTaskReport(ChildTaskId taskId, AgentDefinitionId definitionId, ChildTaskStatus status,
            ChildTaskFailureCode failureCode, int modelTurns, int toolCalls, long estimatedTokens,
            Duration elapsed, String summary, boolean verified) {
        this(taskId, definitionId, status, failureCode, modelTurns, toolCalls, estimatedTokens,
                elapsed, summary, verified, Optional.empty());
    }

    /**
     * 叠加独立清理快照，不改写运行终态、计数、验证标记或隐私摘要。
     * @param value 非空清理证据
     * @return 保留原报告其余字段的新投影
     */
    public ChildTaskReport withCleanupStatus(ResourceCleanupStatus value) {
        return new ChildTaskReport(taskId, definitionId, status, failureCode, modelTurns, toolCalls,
                estimatedTokens, elapsed, summary, verified, worktreeDisposition, value);
    }

    /** 校验计数、摘要与 Worktree disposition 后冻结投影。 */
    public ChildTaskReport {
        taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        definitionId = Objects.requireNonNull(definitionId, "definitionId 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        failureCode = Objects.requireNonNull(failureCode, "failureCode 不能为空");
        elapsed = Objects.requireNonNull(elapsed, "elapsed 不能为空");
        worktreeDisposition = Objects.requireNonNull(worktreeDisposition, "worktreeDisposition 不能为空");
        cleanupStatus = Objects.requireNonNull(cleanupStatus, "cleanupStatus 不能为空");
        if (modelTurns < 0 || toolCalls < 0 || estimatedTokens < 0 || elapsed.isNegative()) throw new IllegalArgumentException("计数无效");
        summary = Objects.requireNonNullElse(summary, "");
        if (summary.codePointCount(0, summary.length()) > MAX_SUMMARY_CHARACTERS || summary.chars().anyMatch(c -> c == 0)) throw new IllegalArgumentException("summary 超过边界");
        if (worktreeDisposition.isPresent() && !worktreeDisposition.orElseThrow().matches("[A-Z_]{2,32}")) throw new IllegalArgumentException("worktree disposition 无效");
    }
}
