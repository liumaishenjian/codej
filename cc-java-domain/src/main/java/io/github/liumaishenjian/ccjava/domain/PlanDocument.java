package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 项目拥有的规划工件。其内容属于 Session 语义事件，不是第二份 Transcript。
 *
 * @param id 稳定工件标识
 * @param objective 规划目标
 * @param steps 有序步骤
 * @param status 当前状态
 * @param workspaceDigest 只读探索完成时的工作区摘要
 */
public record PlanDocument(String id, String objective, List<PlanStep> steps,
                           PlanStatus status, String workspaceDigest) {
    /** 校验有界文本和连续步骤序号，并冻结步骤集合；不批准或执行计划。 */
    public PlanDocument {
        id = text(id, "id", 128);
        objective = text(objective, "objective", 8_000);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps 不能为空"));
        if (steps.isEmpty() || steps.size() > 128) throw new IllegalArgumentException("steps 数量无效");
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).ordinal() != i + 1) throw new IllegalArgumentException("steps 必须连续有序");
        }
        status = Objects.requireNonNull(status, "status 不能为空");
        workspaceDigest = text(workspaceDigest, "workspaceDigest", 256);
    }
    /**
     * 生成保留目标、步骤与摘要的新状态文档；合法状态迁移由协调器负责。
     * @param next 非空的目标状态
     * @return 只替换状态的不可变文档
     */
    public PlanDocument withStatus(PlanStatus next) {
        return new PlanDocument(id, objective, steps, next, workspaceDigest);
    }
    private static String text(String value, String name, int max) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
