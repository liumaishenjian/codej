package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * Surface 对同一工具调用的单题或整批答案。
 *
 * @param callId 必须匹配等待中的 Tool Call
 * @param optionId 用户选择的已声明选项
 * @param answers 整批答案；旧单题答案为空列表
 * @since 0.1.0
 */
public record UserQuestionAnswer(String callId, String optionId, java.util.List<UserQuestionSelection> answers) {
    /** 保持旧单题答案兼容。
     * @param callId 工具调用号
     * @param optionId 所选选项号
     */
    public UserQuestionAnswer(String callId, String optionId) { this(callId, optionId, java.util.List.of()); }
    /** 创建整批答案。
     * @param callId 工具调用号
     * @param answers 完整答案列表
     */
    public UserQuestionAnswer(String callId, java.util.List<UserQuestionSelection> answers) { this(callId, "", answers); }
    /** 验证答案关联字段。 */
    public UserQuestionAnswer {
        callId = text(callId, "callId", 128);
        answers = java.util.List.copyOf(Objects.requireNonNull(answers));
        if (answers.size() > 4) throw new IllegalArgumentException("答案数量无效");
        if (answers.isEmpty()) optionId = text(optionId, "optionId", 64);
        else if (!optionId.isEmpty()) throw new IllegalArgumentException("答案格式不能混用");
    }

    private static String text(String value, String name, int max) {
        value = Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
