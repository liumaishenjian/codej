package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 显式 JSON null 的 Session JSONL serializer 兼容回归。 */
class JsonlSessionCodecJsonNullTest {
    @Test
    void encodesToolArgumentsAsJsonNullInsteadOfSentinelName() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("remove", null);
        JsonlSessionCodec codec = new JsonlSessionCodec();

        String encoded = codec.encode(codec.encodeAssistant(2, new RunId("run-null"),
                new AssistantMessage("", List.of(new ToolCall("call-null", "task_update",
                        new JsonObject(values))))));

        assertThat(encoded).contains("\"remove\":null").doesNotContain("INSTANCE");
    }
}
