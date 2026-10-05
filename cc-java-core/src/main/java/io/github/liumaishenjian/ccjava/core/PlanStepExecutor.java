package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.RunId;
import java.util.Objects;

/**
 * Plan 单步执行端口。
 *
 * <p>实现必须把真实 Tool 调用交给 {@link ToolExecutionPipeline}；该端口不是绕过
 * Permission/Approval 的文件或命令执行入口。只读测试可使用确定性 Fake 实现。</p>
 */
@FunctionalInterface
public interface PlanStepExecutor {
    /**
     * 执行一个已经通过 Plan Gate 的步骤。
     * @param step 当前独占领取的计划步骤
     * @param cancellationToken 必须传播到真实执行边界的取消与时间预算
     * @return 非空的类型化执行结果
     */
    PlanStepExecutionResult execute(PlanStep step, CancellationToken cancellationToken);

    /**
     * 创建将显式工具意图交给统一 Pipeline 的执行器。
     * <p>该适配器沿用步骤的预期摘要，不重新扫描工作区；需要真实执行后摘要的宿主应提供
     * 自己的执行适配器。内部 Agent Run 标记不应提交至此入口。</p>
     * @param pipeline 非空的统一工具管线
     * @param session 工具执行所属的非空 Session
     * @param runId 工具事实绑定的非空 Run 身份
     * @return 绑定 Session 和 Run 的步骤执行器
     */
    static PlanStepExecutor pipeline(
            ToolExecutionPipeline pipeline, AgentSession session, RunId runId) {
        Objects.requireNonNull(pipeline, "pipeline 不能为空");
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        return (step, token) -> {
            io.github.liumaishenjian.ccjava.domain.ToolCall call =
                    new io.github.liumaishenjian.ccjava.domain.ToolCall(
                            "plan-step-" + step.ordinal(), step.action().toolName(), step.action().arguments());
            io.github.liumaishenjian.ccjava.domain.ToolResult result = pipeline.execute(session, runId,
                    step.ordinal(), call, token);
            if (result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
                return PlanStepExecutionResult.success(step.expectedDigest(), result.content());
            }
            return new PlanStepExecutionResult(
                    result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.DENIED
                            ? PlanStepExecutionResult.Status.DENIED : PlanStepExecutionResult.Status.FAILURE,
                    step.expectedDigest(), "pipeline tool execution did not succeed");
        };
    }

    /**
     * 拒绝执行器返回空结果；本方法不验证摘要真实性。
     * @param result 执行器返回的结果
     * @return 原结果对象；null 时抛出异常
     */
    static PlanStepExecutionResult requireResult(PlanStepExecutionResult result) {
        return Objects.requireNonNull(result, "Plan step result 不能为空");
    }
}
