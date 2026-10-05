package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MODEL-13 独立 schema-1 格式兼容测试；只使用临时 home 和公开目录。 */
class PiDefaultSelectionTest {
    private static final CancellationToken NONE = CancellationToken.none();
    @TempDir Path home;

    @Test void oldJsonRetainsExactlyTwoSelectionFieldsAfterRewrite() throws Exception {
        var store = new ProviderDefinitionStore(home);
        store.selectDefault(Optional.empty(), 0, NONE);
        Files.writeString(file(), "{\"schemaVersion\":1,\"providers\":[],\"defaultSelection\":{\"providerId\":\"anthropic\",\"modelId\":\"claude-sonnet-4-6\"}}");
        var old = store.snapshot(NONE);
        assertThat(old.defaultSelection()).contains(new ProviderDefinitionStore.DefaultSelection("anthropic", "claude-sonnet-4-6"));
        store.selectDefault(old.defaultSelection(), old.generation(), NONE);
        var json = JsonMapper.builder().build().readTree(Files.readAllBytes(file()));
        assertThat(json.get("defaultSelection").size()).isEqualTo(2);
        assertThat(Files.readString(file())).doesNotContain("backend", "authMethod", "profileId");
    }

    @Test void piFiveFieldsRoundTripForApiKeyAndOauthExactProfiles() throws Exception {
        var store = new ProviderDefinitionStore(home);
        for (String provider : List.of("openai", "openai-codex")) {
            var value = pi(provider, provider.equals("openai") ? "API_KEY" : "OAUTH", "explicit-profile");
            store.selectDefault(Optional.of(value), store.snapshot(NONE).generation(), NONE);
            var reopened = new ProviderDefinitionStore(home).snapshot(NONE);
            assertThat(reopened.defaultSelection()).contains(value);
            var json = JsonMapper.builder().build().readTree(Files.readAllBytes(file()));
            assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
            assertThat(json.get("defaultSelection").size()).isEqualTo(5);
            assertThat(json.get("defaultSelection").get("profileId").asText()).isEqualTo("explicit-profile");
            assertThat(json.get("defaultSelection").get("authMethod").asText()).isEqualTo(value.authMethod());
        }
    }

    @Test void unknownMixedMissingAndWrongTagsFailClosed() throws Exception {
        var store = new ProviderDefinitionStore(home);
        store.selectDefault(Optional.of(pi("openai", "API_KEY", "exact")), 0, NONE);
        String valid = Files.readString(file());
        for (String field : List.of("backend", "authMethod", "profileId", "providerId", "modelId")) {
            var tree = JsonMapper.builder().build().readTree(valid);
            ((tools.jackson.databind.node.ObjectNode) tree.get("defaultSelection")).remove(field);
            Files.writeString(file(), tree.toString());
            assertThatThrownBy(() -> store.snapshot(NONE)).isInstanceOf(ProviderAuthException.class);
        }
        for (String invalid : List.of(
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"backend\":\"pi\"", "\"backend\":\"spring-ai\""),
                valid.replace("\"backend\":\"pi\"", "\"backend\":\"unknown\""),
                valid.replace("\"authMethod\":\"API_KEY\"", "\"authMethod\":\"api_key\""),
                valid.replace("\"authMethod\":\"API_KEY\"", "\"authMethod\":\"OAUTH\""),
                valid.replace("\"profileId\":\"exact\"", "\"unknown\":\"exact\""),
                valid.replace("\"profileId\":\"exact\"", "\"profileId\":null"),
                valid.replace("\"profileId\":\"exact\"", "\"profileId\":\"\""),
                valid.replace(model("openai"), "unknown-model"),
                valid.replace("\"defaultSelection\":{", "\"defaultSelection\":{\"extra\":true,"),
                "{\"schemaVersion\":1,\"providers\":[],\"defaultSelection\":{\"providerId\":\"openai\",\"modelId\":\"x\",\"profileId\":\"exact\"}}")) {
            Files.writeString(file(), invalid);
            assertThatThrownBy(() -> store.snapshot(NONE)).isInstanceOfSatisfying(ProviderAuthException.class,
                    error -> { assertThat(error.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_CORRUPT);
                        assertThat(error.getCause()).isNull(); });
        }
    }

    @Test void canonicalRejectsIllegalAuthModelAndLegacyProfile() {
        assertThatThrownBy(() -> pi("openai", "OAUTH", "exact")).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> new ProviderDefinitionStore.DefaultSelection("openai", "not-a-catalog-model",
                "pi", "API_KEY", Optional.of("exact"))).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> new ProviderDefinitionStore.DefaultSelection("openai", model("openai"),
                "pi", "API_KEY", Optional.empty())).isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(() -> new ProviderDefinitionStore.DefaultSelection("team", "model",
                "spring-ai", "API_KEY", Optional.of("exact"))).isInstanceOf(ProviderAuthException.class);
    }

    @Test void sameNamedPiDefaultDoesNotProtectLegacyDefinitionFromDeletion() {
        var store = new ProviderDefinitionStore(home);
        var custom = new ProviderDefinition("openai", ProviderDefinition.Kind.OPENAI_COMPATIBLE, "Synthetic",
                URI.create("https://synthetic.example/v1"), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                List.of("legacy-model"), "legacy-model", Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(30));
        store.add(custom, 0, NONE);
        var value = pi("openai", "API_KEY", "exact");
        var selected = store.selectDefault(Optional.of(value), 1, NONE);
        assertThat(store.remove("openai", selected.generation(), NONE).defaultSelection()).contains(value);
    }

    @Test void legacyOverlayMutationPreservesPiDefaultAndStillProtectsLegacyDefault() {
        var store = new ProviderDefinitionStore(home);
        var value = pi("openai", "API_KEY", "exact");
        store.selectDefault(Optional.of(value), 0, NONE);
        store.addModel("anthropic", "synthetic-overlay", 1, NONE);
        assertThat(store.removeModel("anthropic", "synthetic-overlay", 2, NONE).defaultSelection()).contains(value);
        store.addModel("anthropic", "synthetic-overlay", 3, NONE);
        store.selectDefault(Optional.of(new ProviderDefinitionStore.DefaultSelection("anthropic", "synthetic-overlay")), 4, NONE);
        assertThatThrownBy(() -> store.removeModel("anthropic", "synthetic-overlay", 5, NONE))
                .isInstanceOf(ProviderAuthException.class);
    }

    private Path file() { return home.resolve(".cc-java/providers.v1.json"); }
    private static String model(String provider) { return new PiProviderCatalog().require(provider).models().getFirst().id(); }
    private static ProviderDefinitionStore.DefaultSelection pi(String provider, String method, String profile) {
        return new ProviderDefinitionStore.DefaultSelection(provider, model(provider), "pi", method, Optional.of(profile));
    }
}
