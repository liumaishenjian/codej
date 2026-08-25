package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonNull;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证显式 JSON null 在 Provider 序列化往返中保持 null，而不是泄漏内部 sentinel 名称。 */
class SpringAiJsonNullTest {
    @Test
    void writesAndReadsExplicitNullAcrossProviderBoundary() throws Exception {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("remove", null);
        ArrayList<Object> nested = new ArrayList<>(List.of("keep"));
        nested.add(null);
        values.put("nested", Map.of("items", nested));
        JsonObject arguments = new JsonObject(values);

        String encoded = SpringAiJson.write(arguments.jsonValues());
        JsonObject decoded = SpringAiJson.readArguments(encoded);

        assertThat(encoded).contains("\"remove\":null", "[\"keep\",null]")
                .doesNotContain("INSTANCE");
        assertThat(decoded.values()).containsEntry("remove", JsonNull.INSTANCE);
        Map<?, ?> decodedNested = (Map<?, ?>) decoded.values().get("nested");
        List<?> decodedItems = (List<?>) decodedNested.get("items");
        assertThat(decodedItems).hasSize(2);
        assertThat(decodedItems.get(0)).isEqualTo("keep");
        assertThat(decodedItems.get(1)).isSameAs(JsonNull.INSTANCE);
    }
}
