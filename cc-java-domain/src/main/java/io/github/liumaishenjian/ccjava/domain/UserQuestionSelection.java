package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/**
 * 单题答案；具体选项归属及单多选语义由待决请求校验。
 * @param questionId 原题号
 * @param optionIds 所选选项号，不可重复
 * @param freeText 自由文字；没有文字时为空串
 * @since 0.1.0
 */
public record UserQuestionSelection(String questionId, List<String> optionIds, String freeText) {
    /** 保持不可变并在跨线程传递前限制用户文字预算。 */
    public UserQuestionSelection {
        Objects.requireNonNull(questionId); Objects.requireNonNull(freeText);
        optionIds = List.copyOf(Objects.requireNonNull(optionIds));
        if (questionId.isBlank() || questionId.length() > 64 || optionIds.size() > 8
                || optionIds.stream().anyMatch(s -> s.isBlank() || s.length() > 64)
                || new HashSet<>(optionIds).size() != optionIds.size() || freeText.length() > 2000
                || freeText.codePoints().anyMatch(c -> Character.isISOControl(c) && c != 9 && c != 10 && c != 13))
            throw new IllegalArgumentException("答案字段无效");
        for (int i = 0; i < freeText.length(); i++) {
            char c = freeText.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= freeText.length() || !Character.isLowSurrogate(freeText.charAt(i)))
                    throw new IllegalArgumentException("答案 Unicode 无效");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("答案 Unicode 无效");
        }
    }
}
