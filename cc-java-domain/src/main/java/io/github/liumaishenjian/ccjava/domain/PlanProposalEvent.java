package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * Headless Plan Run 通过严格解析后发布的有界规划提案事件。
 *
 * <p>该事件只包含规范化后的计划展示字段和 Runtime 生成的安全标识，不携带模型原始文本、
 * Tool 参数或第二份 transcript。Java Runtime 仍拥有计划状态，TUI/stdio 只能据此展示和发起
 * 既有显式批准命令。</p>
 *
 * @param planId Runtime 生成的计划标识
 * @param status 当前计划状态
 * @param objective 规范化目标
 * @param steps 连续有序的步骤展示
 * @param workspaceDigest 只读探索结束时的工作区摘要
 * @since 0.1.0
 */
public record PlanProposalEvent(String planId, PlanStatus status, String objective,
                                List<StepView> steps, String workspaceDigest) implements AgentEvent {
    /** 冻结事件并复用领域工件的边界校验。 */
    public PlanProposalEvent {
        String checkedDigest = workspaceDigest;
        PlanDocument checked = new PlanDocument(planId, objective,
                Objects.requireNonNull(steps, "steps 不能为空").stream()
                        .map(step -> new PlanStep(step.ordinal(), step.title(), step.detail(), checkedDigest))
                        .toList(), Objects.requireNonNull(status, "status 不能为空"), workspaceDigest);
        planId = checked.id();
        objective = checked.objective();
        workspaceDigest = checked.workspaceDigest();
        steps = List.copyOf(steps);
    }

    /**
     * 单个规范步骤的只读展示。
     *
     * @param ordinal 从 1 开始的连续序号
     * @param title 有界标题
     * @param detail 有界说明
     */
    public record StepView(int ordinal, String title, String detail) {
        /** 使用临时安全摘要复用 {@link PlanStep} 的文本与序号校验。 */
        public StepView {
            PlanStep checked = new PlanStep(ordinal, title, detail, "proposal");
            title = checked.title();
            detail = checked.detail();
        }
    }

    /**
     * 从权威 Plan 工件构造 Surface 事件。
     * @param document 非空的当前计划文档
     * @return 仅投影标题、说明、状态与摘要的事件，不暴露步骤执行参数
     */
    public static PlanProposalEvent from(PlanDocument document) {
        Objects.requireNonNull(document, "document 不能为空");
        return new PlanProposalEvent(document.id(), document.status(), document.objective(),
                document.steps().stream()
                        .map(step -> new StepView(step.ordinal(), step.title(), step.detail()))
                        .toList(), document.workspaceDigest());
    }
}
