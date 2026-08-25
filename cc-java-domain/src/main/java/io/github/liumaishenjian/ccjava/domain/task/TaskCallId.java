package io.github.liumaishenjian.ccjava.domain.task;

/**
 * 同一 actor 域内用于 mutation 重试去重的调用身份。
 *
 * @param value 不包含正文或路径的有界身份
 * @since 0.15.0
 */
public record TaskCallId(String value) {
    /** 校验调用身份。 */
    public TaskCallId {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.codePoints().anyMatch(Character::isISOControl) || !validUnicode(value)) {
            throw new IllegalArgumentException("Task call ID 无效");
        }
    }

    private static boolean validUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }
}
