package io.github.liumaishenjian.ccjava.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tool 参数使用的递归不可变 JSON Object 值。
 *
 * <p>该类型避免直接把可变 {@code Map<String, Object>} 暴露给模型端口和工具。
 * 当前支持字符串、布尔值、常见数字、JSON null、List 和 String-key Map；可选字段通常直接
 * 省略，只有 Tool schema 明确允许时才可解释 {@link JsonNull}。输入在构造时被递归复制并冻结。</p>
 *
 * @since 0.1.0
 */
public final class JsonObject {

    private static final JsonObject EMPTY = new JsonObject(Map.of());

    private final Map<String, Object> values;

    /**
     * 创建不可变 Tool 参数快照。
     *
     * @param values JSON Object 的键值
     * @throws IllegalArgumentException 值不属于受支持的 JSON 类型时抛出
     */
    public JsonObject(Map<String, ?> values) {
        Objects.requireNonNull(values, "values 不能为空");
        this.values = freezeMap(values);
    }

    /**
     * 返回空参数对象。
     *
     * @return 共享的不可变空对象
     */
    public static JsonObject empty() {
        return EMPTY;
    }

    /**
     * 返回递归不可变的参数视图。
     *
     * @return 保持输入顺序的不可变 Map
     */
    public Map<String, Object> values() {
        return values;
    }

    /**
     * 返回可直接交给 JSON serializer 或远端 Tool client 的递归不可变视图。
     *
     * <p>该视图把内部 {@link JsonNull} sentinel 还原为真正的 Java {@code null}；内部参数解析应继续
     * 使用 {@link #values()}，以区分字段缺失和显式 JSON null。</p>
     *
     * @return 保持键与数组顺序、以 Java null 表示 JSON null 的 Map
     */
    public Map<String, Object> jsonValues() {
        return thawMap(values);
    }

    /**
     * 读取字符串参数。
     *
     * @param name 参数名
     * @return 参数不存在时为空
     * @throws IllegalArgumentException 参数存在但不是字符串时抛出
     */
    public Optional<String> string(String name) {
        Objects.requireNonNull(name, "name 不能为空");
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("参数 '%s' 不是字符串".formatted(name));
        }
        return Optional.of(stringValue);
    }

    private static Map<String, Object> freezeMap(Map<String, ?> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("JSON Object 的键不能为空");
            }
            copy.put(key, value == null ? JsonNull.INSTANCE : freezeValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freezeValue(Object value) {
        if (value == JsonNull.INSTANCE || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (isSupportedNumber(value)) {
            ensureFiniteNumber(value);
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> stringKeyMap = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("嵌套 JSON Object 的键必须是字符串");
                }
                stringKeyMap.put(stringKey, nestedValue);
            });
            return freezeMap(stringKeyMap);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(element == null ? JsonNull.INSTANCE : freezeValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(
                "不支持的 JSON 值类型: " + value.getClass().getName());
    }

    private static Map<String, Object> thawMap(Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, thawValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object thawValue(Object value) {
        if (value == JsonNull.INSTANCE) return null;
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put((String) key, thawValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            list.forEach(element -> copy.add(thawValue(element)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static boolean isSupportedNumber(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal;
    }

    private static void ensureFiniteNumber(Object value) {
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)) {
            throw new IllegalArgumentException("JSON Number 不能是 NaN 或 Infinity");
        }
        if (value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw new IllegalArgumentException("JSON Number 不能是 NaN 或 Infinity");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof JsonObject that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
