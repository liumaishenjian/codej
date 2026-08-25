package io.github.liumaishenjian.ccjava.domain.task;

/**
 * Task mutation 的宿主可信 actor identity。
 *
 * <p>该值由 composition root 注入，模型不能自行声明或替换。</p>
 *
 * @param value 不含控制字符的有界身份
 * @since 0.15.0
 */
public record TaskActorId(String value) {
    /** 校验 actor identity。 */
    public TaskActorId {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.codePoints().anyMatch(TaskActorId::control) || !validUnicode(value)) {
            throw new IllegalArgumentException("Task actor ID 无效");
        }
    }

    private static boolean control(int codePoint) {
        return Character.isISOControl(codePoint);
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
