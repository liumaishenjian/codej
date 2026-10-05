package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S15：合成 opaque 材料的不可变、兼容、Unicode 预算与日志边界。 */
class ModelContinuationTest {
    @Test
    void preservesOpaqueDataWithoutParsingOrNormalization() {
        String payload = "synthetic hidden\n签名🙂\u0000 not-json { [  ";
        ModelContinuation value = continuation(payload);
        assertThat(value.payload()).isEqualTo(payload);
        assertThat(value).isEqualTo(continuation(payload));
        assertThat(value.hashCode()).isEqualTo(continuation(payload).hashCode());
        assertThat(value.backend()).isEqualTo("pi");
        assertThat(value.providerId()).isEqualTo("provider-test");
        assertThat(value.modelId()).isEqualTo("model-test");
    }

    @Test
    void recordOnlyOwnsFinalImmutableStrings() {
        assertThat(ModelContinuation.class.isRecord()).isTrue();
        assertThat(Modifier.isFinal(ModelContinuation.class.getModifiers())).isTrue();
        assertThat(ModelContinuation.class.getRecordComponents()).hasSize(4)
                .allSatisfy(component -> assertThat(component.getType()).isEqualTo(String.class));
        assertThat(ModelContinuation.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
    }

    @Test
    void keepsLegacyFactoriesAndVisibleEmptinessWithDefensiveCopy() {
        ModelContinuation value = continuation("hidden-only");
        ToolCall call = new ToolCall("call-1", "read_file", JsonObject.empty());
        List<ToolCall> calls = new ArrayList<>(List.of(call));
        AssistantMessage message = new AssistantMessage("visible", calls, Optional.of(value));
        calls.clear();
        assertThat(message.toolCalls()).containsExactly(call);
        assertThatThrownBy(() -> message.toolCalls().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(message.continuation()).contains(value);
        assertThat(new AssistantMessage("", List.of(), Optional.of(value)).isEmpty()).isTrue();
        assertThat(new AssistantMessage(" \n", List.of(), Optional.of(value)).isEmpty()).isTrue();
        assertThat(message.isEmpty()).isFalse();
        assertThat(new AssistantMessage("", List.of(call), Optional.of(value)).isEmpty()).isFalse();
        assertThat(new AssistantMessage("visible", List.of()).continuation()).isEmpty();
        assertThat(AssistantMessage.text("visible")).isEqualTo(new AssistantMessage("visible", List.of()));
        assertThat(AssistantMessage.tools(List.of(call))).isEqualTo(new AssistantMessage("", List.of(call)));
        assertThat(AssistantMessage.tools(List.of(call)).continuation()).isEmpty();
        assertThatThrownBy(() -> new AssistantMessage("", List.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantMessage(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantMessage("", null)).isInstanceOf(NullPointerException.class);
        List<ToolCall> nullElement = new ArrayList<>();
        nullElement.add(null);
        assertThatThrownBy(() -> new AssistantMessage("", nullElement)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "中", "🙂"})
    void enforcesUtf8BytesRatherThanJavaCharacterCount(String unit) {
        int bytes = unit.getBytes(StandardCharsets.UTF_8).length;
        int maximum = ModelContinuation.MAX_PAYLOAD_BYTES;
        String boundary = unit.repeat(maximum / bytes) + "x".repeat(maximum % bytes);
        assertThat(continuation(boundary).payload().getBytes(StandardCharsets.UTF_8)).hasSize(maximum);
        assertThatThrownBy(() -> continuation(boundary + "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDC00", "a\uD800b", "\uDC00\uD800", "\uD800\uD800"})
    void rejectsUnpairedSurrogatesWithoutLeakingInput(String malformed) {
        assertThatThrownBy(() -> continuation(malformed)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("续接 Unicode 无效");
        assertThatThrownBy(() -> new ModelContinuation("pi", malformed, "model", "hidden"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContinuation("pi", "provider", malformed, "hidden"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidIdentityAndNullOrEmptyPayload() {
        assertThatThrownBy(() -> continuation("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> continuation(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelContinuation(null, "provider", "model", "hidden"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelContinuation("legacy", "provider", "model", "hidden"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String invalid : List.of("", " ", "x".repeat(201), "bad\nidentity")) {
            assertThatThrownBy(() -> new ModelContinuation("pi", invalid, "model", "hidden"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ModelContinuation("pi", "provider", invalid, "hidden"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ModelContinuation("pi", null, "model", "hidden"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelContinuation("pi", "provider", null, "hidden"))
                .isInstanceOf(NullPointerException.class);
        assertThat(new ModelContinuation("pi", "p".repeat(200), "m".repeat(200), " ").payload())
                .isEqualTo(" ");
    }

    @Test
    void nestedToStringNeverDisclosesPrivateMaterial() {
        ModelContinuation value = new ModelContinuation("pi", "private-provider", "private-model", "HIDDEN_SIGNATURE_SENTINEL");
        assertThat(value.toString()).isEqualTo("ModelContinuation[redacted]");
        assertThat(new AssistantMessage("visible", List.of(), Optional.of(value)).toString())
                .contains("visible").doesNotContain("HIDDEN_SIGNATURE_SENTINEL", "private-provider", "private-model");
        assertThat(List.of(value).toString()).doesNotContain("HIDDEN_SIGNATURE_SENTINEL");
    }

    private static ModelContinuation continuation(String payload) {
        return new ModelContinuation("pi", "provider-test", "model-test", payload);
    }
}
