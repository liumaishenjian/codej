package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/**
 * 一份问卷中的必答题；稳定题号独立于用户可见文案。
 * @param id 问卷内唯一题号
 * @param title 简短题签
 * @param question 完整问题
 * @param multiSelect 是否允许多个选项
 * @param options 已声明选项，纯文字题可为空
 * @param allowFreeText 是否允许用户自行输入
 * @since 0.1.0
 */
public record UserQuestionItem(String id, String title, String question, boolean multiSelect,
        List<UserQuestionOption> options, boolean allowFreeText) {
    /** 构造有界且不可变的题目，拒绝重复选项。 */
    public UserQuestionItem {
        id = text(id, 64); title = text(title, 120); question = text(question, 1000);
        options = List.copyOf(Objects.requireNonNull(options));
        if (options.size() > 8 || (options.isEmpty() && !allowFreeText)) throw new IllegalArgumentException("选项数量无效");
        if (options.stream().anyMatch(o -> o.label().length() > 120 || o.description().length() > 500))
            throw new IllegalArgumentException("选项文字超过问卷预算");
        var ids = new HashSet<String>();
        if (options.stream().anyMatch(o -> !ids.add(o.optionId()))) throw new IllegalArgumentException("选项重复");
    }
    private static String text(String value, int max) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("题目文本无效");
        return value;
    }
}
