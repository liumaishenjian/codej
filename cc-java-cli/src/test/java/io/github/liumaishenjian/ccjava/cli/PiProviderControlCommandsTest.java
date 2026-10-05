package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialStore;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR-100 真实 Picocli 路由、临时 Store 与合成 ENV；禁止 Node、Probe 和秘密输入读取。 */
class PiProviderControlCommandsTest {
    @TempDir Path temporary;
    private static final String ENV = "SYNTHETIC_CLI_KEY";

    @Test void catalogIsAlwaysFourRoutesAndHasClosedMetadataWithoutNode() throws Exception {
        var f = fixture();
        var result = f.execute("providers", "list", "--backend", "pi", "--json");
        assertThat(result.exit).isZero();
        var rows = JsonMapper.builder().build().readTree(result.out).get("providers");
        assertThat(rows.size()).isEqualTo(4);
        for (var row : rows) {
            assertThat(row.propertyNames()).containsExactlyInAnyOrder("backend", "providerId", "brandId", "label",
                    "componentAvailable", "authMethods", "modelCount");
            assertThat(row.get("componentAvailable").booleanValue()).isFalse();
        }
        assertThat(result.all()).doesNotContain("https:", "accountId", "authEpoch", ENV);
        var models = f.execute("models", "list", "--backend", "pi", "--provider", "openai", "--json");
        assertThat(models.exit).isZero();
        assertThat(models.out).contains("\"backend\":\"pi\"").doesNotContain("https:", "apiKey");
        assertThat(f.execute("models", "list", "--backend", "pi", "--provider", "anthropic").exit).isEqualTo(2);
    }

    @Test void environmentLoginSelectExactIdentityAndConfirmedLogoutNeverReadValuesOrNode() throws Exception {
        var f = fixture();
        assertThat(f.identity("login", "openai", "work", "--from-env", ENV).exit).isZero();
        assertThat(f.service.effectiveSelection()).isEmpty();
        assertThat(f.store.snapshot(CancellationToken.none()).providerDefaults()).isEmpty();
        assertThat(f.identity("login", "deepseek", "work", "--from-env", "ABSENT_SYNTHETIC_ENV").exit).isZero();
        var list = f.execute("auth", "list", "--backend", "pi", "--provider", "openai", "--auth-method", "API_KEY", "--json");
        var rows = JsonMapper.builder().build().readTree(list.out).get("profiles");
        assertThat(list.exit).isZero(); assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).propertyNames()).containsExactlyInAnyOrder("backend", "providerId", "profileId",
                "authMethod", "refKind", "localStatus");
        assertThat(f.identity("status", "openai", "work", "--json").out).contains("CONFIGURED_UNVERIFIED");
        assertThat(f.identity("status", "openai", "other", "--json").exit).isEqualTo(3);
        String model = new PiProviderCatalog().require("openai").models().getFirst().id();
        assertThat(f.execute("models", "use", "--backend", "pi", "--provider", "openai", "--profile", "work",
                "--auth-method", "API_KEY", "--model", model, "--set-default").exit).isZero();
        var selected = f.service.effectiveSelection().orElseThrow();
        assertThat(selected.backend()).isEqualTo("pi"); assertThat(selected.profileId()).isEqualTo("work");
        assertThat(f.identity("logout", "openai", "work").exit).isEqualTo(2);
        var logout = f.identity("logout", "openai", "work", "--yes");
        assertThat(logout.exit).isZero(); assertThat(logout.out).contains("was not revoked");
        assertThat(f.identity("status", "openai", "work", "--json").exit).isEqualTo(3);
        assertThat(list.all()+logout.all()).doesNotContain(ENV, "synthetic-value", "authEpoch", "accountId", "secretRef");
        assertThat(f.identity("status", "deepseek", "work", "--json").exit).isZero();
    }

    @Test void largeEpochIsNeverProjectedInJson() throws Exception {
        var f = fixture();
        assertThat(f.identity("login", "openai", "work", "--from-env", ENV).exit).isZero();
        // 只修改临时 ENV_REF 元数据；没有秘密封装需要伪造，不读真实配置。
        Path index;
        try (var paths = Files.walk(temporary)) {
            index = paths.filter(path -> path.getFileName().toString().equals("index.v1.json")).findFirst().orElseThrow();
        }
        var json = JsonMapper.builder().build();
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(index));
        root.put("generation", 9007199254740993L);
        var profiles = root.get("profiles");
        ((tools.jackson.databind.node.ObjectNode) profiles.get(0)).put("authEpoch", 9007199254740993L);
        Files.writeString(index, json.writeValueAsString(root));
        var status = f.identity("status", "openai", "work", "--json");
        var list = f.execute("auth", "list", "--backend", "pi", "--json");
        assertThat(status.exit).isZero(); assertThat(list.exit).isZero();
        assertThat(status.all()+list.all()).doesNotContain("9007199254740993", "authEpoch", ENV);
    }

    @Test void legacyIsExplicitlyCompatibleAndDoesNotBorrowPiProfile() throws Exception {
        var f = fixture();
        assertThat(f.execute("auth", "login", "--provider", "anthropic", "--profile", "work", "--from-env", ENV).exit).isZero();
        assertThat(f.execute("auth", "status", "--backend", "spring-ai", "--provider", "anthropic", "--profile", "work").exit).isZero();
        assertThat(f.execute("auth", "list", "--json").out).contains("anthropic").doesNotContain("\"backend\":\"pi\"");
        assertThat(f.identity("status", "openai", "work").exit).isEqualTo(3);
        assertThat(f.execute("auth", "login", "--provider", "deepseek", "--profile", "work", "--from-env", ENV).exit).isNotZero();
        assertThat(f.service.listPiProfiles(Optional.empty(), CancellationToken.none())).isEmpty();
    }

    @Test void unsupportedBackendMethodsAndPiFlagsFailClosedBeforeAnyInput() throws Exception {
        var f = fixture();
        String[][] rejected = {
                {"providers", "list", "--backend", "unknown"},
                {"providers", "remove", "--backend", "pi", "--id", "openai", "--yes"},
                {"providers", "add", "--backend", "pi", "--id", "custom", "--kind", "openai-compatible", "--base-url", "https://example.invalid", "--model", "x"},
                {"auth", "probe", "--backend", "pi", "--provider", "openai", "--profile", "work", "--auth-method", "API_KEY"},
                {"auth", "migrate-legacy", "--backend", "pi", "--provider", "openai", "--profile", "work"},
                {"auth", "status", "--backend", "pi", "--provider", "openai", "--profile", "work"},
                {"auth", "status", "--backend", "pi", "--provider", "openai", "--profile", "work", "--auth-method", "OAUTH"},
                {"auth", "list", "--backend", "pi", "--auth-method", "api_key"},
                {"auth", "list", "--backend", "spring-ai", "--auth-method", "API_KEY"},
                {"models", "add", "--backend", "pi", "--provider", "openai", "--model", "x"},
                {"models", "remove", "--backend", "pi", "--provider", "openai", "--model", "x"},
                {"models", "use", "--backend", "pi", "--provider", "openai", "--model", "x", "--auth-method", "API_KEY"},
                {"auth", "login", "--backend", "pi", "--provider", "openai-codex", "--profile", "work", "--auth-method", "OAUTH", "--api-key-stdin", "--browser"},
                {"auth", "login", "--backend", "pi", "--provider", "openai-codex", "--profile", "work", "--auth-method", "OAUTH", "--from-env", ENV}
        };
        for (var args : rejected) assertThat(f.execute(args).exit).as(String.join(" ", args)).isEqualTo(2);
        for (String flag : List.of("--set-default", "--tui-preview", "--browser", "--api-key-stdin"))
            assertThat(f.identity("login", "openai", "work", "--from-env", ENV, flag).exit).isEqualTo(2);
        assertThat(f.service.listPiProfiles(Optional.empty(), CancellationToken.none())).isEmpty();
    }

    private Fixture fixture() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path repo = Files.createDirectory(temporary.resolve("repo"));
        var definitions = new ProviderDefinitionStore(home);
        var legacy = new RestrictedFileCredentialStore(home);
        var migration = new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(repo), definitions, legacy);
        var store = new PiCredentialStore(home);
        var service = new ProviderAuthApplicationService(definitions, legacy, migration, Map.of(ENV, "synthetic-value"),
                new CredentialLeaseRegistry(), (d, m, s, timeout, token) -> { throw new AssertionError("NO_PROBE"); },
                Clock.systemUTC(), store, () -> { throw new IllegalStateException("NO_NODE"); });
        return new Fixture(service, store);
    }
    private record Fixture(ProviderAuthApplicationService service, PiCredentialStore store) {
        Invocation identity(String action, String provider, String profile, String...flags) {
            var args = new ArrayList<>(List.of("auth", action, "--backend", "pi", "--provider", provider,
                    "--profile", profile, "--auth-method", "API_KEY"));
            args.addAll(List.of(flags)); return execute(args.toArray(String[]::new));
        }
        Invocation execute(String...args) {
            var out = new StringWriter(); var err = new StringWriter();
            InputStream forbidden = new InputStream() {
                @Override public int read() { throw new AssertionError("JAVA_SECRET_READ_FORBIDDEN"); }
            };
            int code = CcJavaCliMain.executeProviderControl(args, service, forbidden, new PrintWriter(out, true), new PrintWriter(err, true));
            return new Invocation(code, out.toString(), err.toString());
        }
    }
    private record Invocation(int exit, String out, String err) { String all() { return out+err; } }
}
