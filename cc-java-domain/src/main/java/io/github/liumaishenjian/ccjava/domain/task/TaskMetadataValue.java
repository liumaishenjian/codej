package io.github.liumaishenjian.ccjava.domain.task;

import java.nio.charset.StandardCharsets;

/**
 * Task metadata 允许持久化的封闭标量。
 *
 * <p>不接受浮点数、集合、嵌套对象或任意 JSON，以限制 Context、协议和持久化成本。</p>
 *
 * @since 0.15.0
 */
public sealed interface TaskMetadataValue permits TaskMetadataValue.BooleanValue,
        TaskMetadataValue.IntegerValue, TaskMetadataValue.StringValue {

    /** 返回独立 canonical JSON 编码所需的 UTF-8 字节数。 */
    int canonicalJsonBytes();

    /**
     * boolean metadata 值。
     *
     * @param value 结构化布尔值
     */
    record BooleanValue(boolean value) implements TaskMetadataValue {
        @Override public int canonicalJsonBytes() { return value ? 4 : 5; }
    }

    /**
     * JSON 安全整数 metadata 值。
     *
     * @param value 绝对值不超过 2^53-1 的整数
     */
    record IntegerValue(long value) implements TaskMetadataValue {
        private static final long MAX_SAFE = 9_007_199_254_740_991L;
        /** 校验 JSON 安全整数范围。 */
        public IntegerValue {
            if (value < -MAX_SAFE || value > MAX_SAFE) {
                throw new IllegalArgumentException("Task metadata 整数超出 JSON 安全范围");
            }
        }
        @Override public int canonicalJsonBytes() {
            return Long.toString(value).getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * 无控制字符且 Unicode 结构完整的字符串 metadata 值。
     *
     * @param value 最多 512 code points 的字符串
     */
    record StringValue(String value) implements TaskMetadataValue {
        /** 校验长度、控制字符和 surrogate 配对。 */
        public StringValue {
            if (value == null || value.codePointCount(0, value.length()) > 512
                    || value.codePoints().anyMatch(Character::isISOControl)
                    || !validUnicode(value)) {
                throw new IllegalArgumentException("Task metadata 字符串无效");
            }
        }
        @Override public int canonicalJsonBytes() {
            int bytes = 2;
            for (int index = 0; index < value.length();) {
                int codePoint = value.codePointAt(index);
                if (codePoint == '"' || codePoint == '\\') bytes += 2;
                else bytes += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
                index += Character.charCount(codePoint);
            }
            return bytes;
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
}
