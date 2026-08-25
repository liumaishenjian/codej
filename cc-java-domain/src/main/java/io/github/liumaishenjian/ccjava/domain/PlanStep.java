package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 规划文档中的一个有序步骤。自然语言步骤使用内部 Agent Run 标记，批准后由正常模型—工具循环执行；
 * 显式结构化步骤仍可携带单个 Tool 意图。
 *
 * @param ordinal 从 1 开始的顺序号
 * @param title 面向用户的短标题
 * @param detail 只读探索得出的说明
 * @param expectedDigest 执行前用于冲突检测的工作区摘要
 * @param action 受限结构化执行意图或内部 Agent Run 标记
 */
public record PlanStep(int ordinal, String title, String detail, String expectedDigest, PlanStepAction action) {
    public PlanStep {
        if (ordinal < 1) throw new IllegalArgumentException("ordinal 必须从 1 开始");
        title = text(title, "title", 200);
        detail = text(detail, "detail", 8_000);
        expectedDigest = text(expectedDigest, "expectedDigest", 256);
        action = Objects.requireNonNull(action, "action 不能为空");
    }

    public PlanStep(int ordinal, String title, String detail, String expectedDigest) {
        this(ordinal, title, detail, expectedDigest,
                PlanStepAction.agentRunMarker());
    }

    /** 将步骤的执行前摘要推进到上一步真实完成后的工作区摘要。 */
    public PlanStep withExpectedDigest(String digest) {
        return new PlanStep(ordinal, title, detail, digest, action);
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
