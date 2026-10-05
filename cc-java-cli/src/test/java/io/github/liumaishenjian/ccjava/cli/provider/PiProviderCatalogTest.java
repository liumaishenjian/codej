package io.github.liumaishenjian.ccjava.cli.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PiProviderCatalogTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void staticResourceNeedsNoNodeEnvironmentOrCredentials() {
        PiProviderCatalog catalog = new PiProviderCatalog();
        assertThat(catalog.brands()).extracting(PiProviderCatalog.Brand::id).containsExactly("openai", "deepseek", "qwen");
        assertThat(catalog.list()).extracting(PiProviderCatalog.Provider::id)
                .containsExactly("openai", "openai-codex", "deepseek", "qwen-token-plan-cn");
        assertThat(catalog.list()).extracting(p -> p.models().size()).containsExactly(39, 8, 3, 18);
        for (var provider : catalog.list()) {
            assertThat(provider.authMethods()).extracting(PiProviderCatalog.AuthMethod::id)
                    .containsExactly(provider.id().equals("openai-codex") ? "oauth" : "api_key");
            assertThat(provider.models()).extracting(PiProviderCatalog.Model::id).isSorted().doesNotHaveDuplicates();
            for (var model : provider.models()) {
                assertThat(catalog.requireModel(provider.id(), model.id())).isSameAs(model);
                assertThat(model.name()).isNotBlank();
                assertThat(model.input()).containsAnyOf("text", "image");
            }
        }
    }

    @Test
    void typedViewsAndRecordInputsAreImmutable() {
        var catalog = new PiProviderCatalog();
        var provider = catalog.list().getFirst();
        var model = provider.models().getFirst();
        assertThatThrownBy(() -> catalog.list().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.brands().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.brands().getFirst().providerIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.models().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.authMethods().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> model.input().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<String> modalities = new ArrayList<>(List.of("text"));
        var copy = new PiProviderCatalog.Model("test", "Test", "test-api", 100, 10, false, modalities);
        modalities.add("image");
        assertThat(copy.input()).containsExactly("text");
        var models = new ArrayList<>(provider.models());
        var methods = new ArrayList<>(provider.authMethods());
        var copiedProvider = new PiProviderCatalog.Provider(provider.id(), provider.brandId(), provider.label(), methods, models);
        models.clear(); methods.clear();
        assertThat(copiedProvider.models()).isEqualTo(provider.models());
        assertThat(copiedProvider.authMethods()).isEqualTo(provider.authMethods());
    }

    @Test
    void queriesAreExactAndDoNotLeakUnknownValues() {
        var catalog = new PiProviderCatalog();
        assertThatThrownBy(() -> catalog.require("private-query")).hasMessage("PI_CATALOG_INVALID").hasNoCause();
        assertThatThrownBy(() -> catalog.require(null)).hasMessage("PI_CATALOG_INVALID");
        assertThatThrownBy(() -> catalog.requireModel("deepseek", "private-model")).hasMessage("PI_CATALOG_INVALID");
        assertThatThrownBy(() -> catalog.requireModel("openai", null)).hasMessage("PI_CATALOG_INVALID");
    }

    @ParameterizedTest
    @MethodSource("invalidSchemas")
    void rejectsSchemaDriftAndUnsafeMetadata(Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(resource());
        mutate.accept(root);
        rejects(MAPPER.writeValueAsBytes(root));
    }

    static Stream<Consumer<ObjectNode>> invalidSchemas() {
        return Stream.of(
                root -> root.put("piVersion", "0.85.2"),
                root -> root.put("endpoint", "https://private.invalid"),
                root -> root.remove("brands"),
                root -> ((ArrayNode) root.get("providers")).remove(0),
                root -> provider(root).put("id", "unknown"),
                root -> provider(root).put("brandId", "qwen"),
                root -> provider(root).put("apiKey", "private-secret"),
                root -> ((ObjectNode) provider(root).get("authMethods").get(0)).put("id", "oauth"),
                root -> ((ArrayNode) provider(root).get("authMethods")).addObject(),
                root -> ((ObjectNode) root.get("brands").get(0)).put("id", "unknown"),
                root -> ((ArrayNode) root.get("brands").get(0).get("providerIds")).remove(0),
                root -> model(root).put("cost", 1),
                root -> model(root).put("name", "schema uses label"),
                root -> model(root).put("id", ""),
                root -> model(root).put("label", " "),
                root -> model(root).put("label", "x".repeat(513)),
                root -> model(root).put("label", "\u00a0name"),
                root -> model(root).put("label", "bad\u0001"),
                root -> model(root).put("api", "INVALID/API"),
                root -> model(root).put("contextWindow", 0),
                root -> model(root).put("contextWindow", 1_000_000_001),
                root -> model(root).put("maxTokens", 1.5),
                root -> model(root).put("maxTokens", "100"),
                root -> model(root).put("maxTokens", true),
                root -> model(root).put("reasoning", "true"),
                root -> model(root).putNull("reasoning"),
                root -> model(root).put("input", "text"),
                root -> model(root).putArray("input"),
                root -> model(root).putArray("input").add("audio"),
                root -> model(root).putArray("input").add("text").add("text"),
                root -> ((ArrayNode) provider(root).get("models")).add(model(root).deepCopy()),
                root -> model(root).put("id", "zzzz-unsorted")
        );
    }

    @Test
    void rejectsInvalidUtf8DuplicateKeysEscapedDuplicatesDepthBomAndTrailingDocuments() throws Exception {
        String valid = new String(resource(), StandardCharsets.UTF_8);
        rejects(("{\"piVersion\":\"0.85.1\"," + valid.substring(1)).getBytes(StandardCharsets.UTF_8));
        rejects(("{\"pi\\u0056ersion\":\"0.85.1\"," + valid.substring(1)).getBytes(StandardCharsets.UTF_8));
        rejects((valid + " {}").getBytes(StandardCharsets.UTF_8));
        rejects(("\ufeff" + valid).getBytes(StandardCharsets.UTF_8));
        rejects(valid.getBytes(StandardCharsets.UTF_16LE));
        rejects(new byte[] {(byte) 0xc3, (byte) 0x28});
        rejects(("[".repeat(10) + "0" + "]".repeat(10)).getBytes(StandardCharsets.UTF_8));
        rejects(valid.replace("OpenAI API", "bad\\ud800").getBytes(StandardCharsets.UTF_8));
        rejects(new byte[0]);
    }

    @Test
    void boundedReadStopsAfterLimitAndDoesNotCloseCallerStream() {
        class LargeStream extends InputStream {
            int count;
            boolean closed;
            @Override public int read() { count++; return ' '; }
            @Override public void close() { closed = true; }
        }
        LargeStream stream = new LargeStream();
        assertThatThrownBy(() -> PiProviderCatalog.read(stream)).hasMessage("PI_CATALOG_INVALID").hasNoCause();
        assertThat(stream.count).isEqualTo(PiProviderCatalog.MAXIMUM_BYTES + 1);
        assertThat(stream.closed).isFalse();
        assertThatThrownBy(() -> PiProviderCatalog.read(null)).hasMessage("PI_CATALOG_INVALID");
        assertThatThrownBy(() -> PiProviderCatalog.read(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private-path"); }
        })).hasMessage("PI_CATALOG_INVALID").hasNoCause();
    }

    @Test
    void tooManyModelsAreRejectedEvenWithUniqueSortedIds() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(resource());
        ObjectNode prototype = model(root).deepCopy();
        ArrayNode models = provider(root).putArray("models");
        for (int i = 0; i < 10_001; i++) {
            ObjectNode next = prototype.deepCopy();
            next.put("id", Integer.toString(100_000 + i));
            models.add(next);
        }
        rejects(MAPPER.writeValueAsBytes(root));
    }

    @Test
    void emptyModelsRemainDeclaredProviders() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(resource());
        provider(root).putArray("models");
        var catalog = PiProviderCatalog.read(new ByteArrayInputStream(MAPPER.writeValueAsBytes(root)));
        assertThat(catalog.require("openai").models()).isEmpty();
        assertThat(catalog.list()).hasSize(4);
    }

    private static ObjectNode provider(ObjectNode root) { return (ObjectNode) root.get("providers").get(0); }
    private static ObjectNode model(ObjectNode root) { return (ObjectNode) provider(root).get("models").get(0); }

    private static byte[] resource() throws IOException {
        try (var source = PiProviderCatalogTest.class.getResourceAsStream("/pi/catalog-0.85.1.json")) {
            if (source == null) throw new IOException("Missing catalog fixture");
            return source.readAllBytes();
        }
    }

    private static void rejects(byte[] bytes) {
        assertThatThrownBy(() -> PiProviderCatalog.read(new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("PI_CATALOG_INVALID").hasNoCause();
    }
}
