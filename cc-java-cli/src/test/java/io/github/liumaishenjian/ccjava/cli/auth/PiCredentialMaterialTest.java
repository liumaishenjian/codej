package io.github.liumaishenjian.ccjava.cli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class PiCredentialMaterialTest {
    @Test void identityIsBoundToBackendRouteAndMethodAndRedacts() {
        var identity = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "default");
        assertThat(identity.toString()).isEqualTo("<redacted>");
        assertThatThrownBy(() -> new PiCredentialIdentity("legacy", "openai", PiCredentialIdentity.AuthMethod.API_KEY, "default"))
                .hasMessage("PI_IDENTITY_INVALID");
        for (String provider : new String[]{"openai", "deepseek", "qwen-token-plan-cn"}) {
            assertThatThrownBy(() -> new PiCredentialIdentity(provider, PiCredentialIdentity.AuthMethod.OAUTH, "default"))
                    .hasMessage("PI_IDENTITY_INVALID");
        }
        assertThatThrownBy(() -> new PiCredentialIdentity("openai-codex", PiCredentialIdentity.AuthMethod.API_KEY, "default"))
                .hasMessage("PI_IDENTITY_INVALID");
        assertThatThrownBy(() -> new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "../secret"))
                .hasMessage("PI_IDENTITY_INVALID");
    }

    @Test void mutableOwnershipIsIndependentAndCloseReallyWipesBackingBytes() throws Exception {
        byte[] source = bytes("synthetic-key");
        var material = PiCredentialMaterial.apiKey(source);
        Arrays.fill(source, (byte) 0);
        var field = PiCredentialMaterial.class.getDeclaredField("bytes"); field.setAccessible(true);
        byte[] backing = (byte[]) field.get(material);
        try (var copy = material.copy()) {
            material.close(); material.close();
            assertThat(backing).containsOnly((byte) 0);
            assertThatThrownBy(material::copyJson).hasMessage("PI_MATERIAL_CLOSED");
            assertThat(new String(copy.copyJson(), StandardCharsets.UTF_8)).contains("synthetic-key");
            assertThat(copy.toString()).isEqualTo("<redacted>");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "null", "[]", "{\"type\":\"api_key\",\"key\":\"x\",\"extra\":1}",
        "{\"type\":\"api_key\",\"key\":\"x\",\"key\":\"y\"}",
        "{\"type\":\"api_key\",\"key\":\"x\",\"k\\u0065y\":\"y\"}",
        "{\"type\":\"api_key\",\"key\":\"\"}", "{\"type\":\"api_key\",\"key\":\"中文\"}",
        "{\"type\":\"api_key\",\"key\":\"\\ud800\"}",
        "{\"type\":\"api_key\",\"key\":\"x\"} {}",
        "{\"type\":\"oauth\",\"access\":\"x\",\"refresh\":\"y\",\"expires\":0,\"accountId\":\"z\"}",
        "{\"type\":\"oauth\",\"access\":\"x\",\"refresh\":\"y\",\"expires\":1.5,\"accountId\":\"z\"}",
        "{\"type\":\"oauth\",\"access\":\"x\",\"refresh\":\"y\",\"expires\":9007199254740992,\"accountId\":\"z\"}",
        "{\"type\":\"env_ref\",\"variableName\":\"PATH=x\"}"
    })
    void rejectsInvalidAndDuplicateTypedFieldsWithoutCause(String json) {
        assertThatThrownBy(() -> PiCredentialMaterial.fromJson(bytes(json)))
                .hasMessage("PI_MATERIAL_INVALID").hasNoCause();
    }

    @Test void rejectsByteEncodingDetectionInsteadOfStrictUtf8() {
        String json = "{\"type\":\"api_key\",\"key\":\"synthetic\"}";
        for (byte[] input : new byte[][] {json.getBytes(StandardCharsets.UTF_16LE),
                json.getBytes(StandardCharsets.UTF_16BE), bytes("\ufeff" + json)}) {
            assertThatThrownBy(() -> PiCredentialMaterial.fromJson(input))
                    .hasMessage("PI_MATERIAL_INVALID").hasNoCause();
        }
    }

    @Test void validatesExactNodeLimitsAndEnvironmentNeverResolves() {
        try (var maximum = PiCredentialMaterial.apiKey(bytes("a".repeat(16384)))) {
            assertThat(maximum.kind()).isEqualTo(PiCredentialMaterial.Kind.API_KEY);
        }
        assertThatThrownBy(() -> PiCredentialMaterial.apiKey(bytes("a".repeat(16385)))).hasMessage("PI_MATERIAL_INVALID");
        assertThatThrownBy(() -> PiCredentialMaterial.oauth(bytes("a".repeat(16000)), bytes("r".repeat(16000)), 1, bytes("account")))
                .hasMessage("PI_MATERIAL_INVALID");
        assertThatThrownBy(() -> PiCredentialMaterial.oauth(bytes("a"), bytes("r"), 1, bytes("a".repeat(257))))
                .hasMessage("PI_MATERIAL_INVALID");
        assertThatThrownBy(() -> PiCredentialMaterial.fromJson(new byte[]{(byte) 0xff})).hasMessage("PI_MATERIAL_INVALID");
        try (var oauth = PiCredentialMaterial.oauth(bytes("a"), bytes("r"), 9007199254740991L, bytes("a".repeat(256)));
             var env = PiCredentialMaterial.envRef("PI_SYNTHETIC_UNSET_ENV")) {
            assertThat(oauth.kind()).isEqualTo(PiCredentialMaterial.Kind.OAUTH);
            assertThat(env.variableName()).isEqualTo("PI_SYNTHETIC_UNSET_ENV");
            assertThatThrownBy(() -> env.requireIdentity(new PiCredentialIdentity("openai-codex", PiCredentialIdentity.AuthMethod.OAUTH, "default")))
                    .hasMessage("PI_MATERIAL_INVALID");
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
