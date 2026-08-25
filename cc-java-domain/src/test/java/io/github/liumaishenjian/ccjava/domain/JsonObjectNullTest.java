package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 显式 JSON null 与字段缺失的边界回归。 */
class JsonObjectNullTest {
    @Test
    void freezesExplicitNullRecursivelyWithoutConfusingItWithMissing() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("top", null);
        ArrayList<Object> items = new ArrayList<>(List.of("value"));
        items.add(null);
        source.put("nested", Map.of("items", items));

        JsonObject object = new JsonObject(source);

        assertThat(object.values()).containsEntry("top", JsonNull.INSTANCE);
        Map<?, ?> nested = (Map<?, ?>) object.values().get("nested");
        List<?> frozenItems = (List<?>) nested.get("items");
        assertThat(frozenItems).hasSize(2);
        assertThat(frozenItems.get(0)).isEqualTo("value");
        assertThat(frozenItems.get(1)).isSameAs(JsonNull.INSTANCE);
        assertThat(object.values()).doesNotContainKey("missing");
        assertThat(object.jsonValues()).containsEntry("top", null);
        Map<?, ?> serializedNested = (Map<?, ?>) object.jsonValues().get("nested");
        List<?> serializedItems = (List<?>) serializedNested.get("items");
        assertThat(serializedItems).hasSize(2);
        assertThat(serializedItems.get(0)).isEqualTo("value");
        assertThat(serializedItems.get(1)).isNull();
        assertThatThrownBy(() -> object.string("top")).isInstanceOf(IllegalArgumentException.class);
        assertThat(object.string("missing")).isEmpty();
    }
}
