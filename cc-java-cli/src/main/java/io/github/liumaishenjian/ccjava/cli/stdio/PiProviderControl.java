package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialVersion;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-100 普通 stdio 的显式 Pi 控制边界，不接收秘密、不启动登录，也不回退旧后端。
 * 列表只投影安全元数据；激活使用客户端原始字符串回执，退出提交仍由共享服务绑定票据。
 */
final class PiProviderControl {
    private PiProviderControl() { }

    /** 校验封闭参数集；不接触文件、凭据或运行组件。 */
    static void validate(String intent, JsonNode arguments) {
        Set<String> allowed = switch (intent) {
            case "providers.catalog", "auth.list" -> Set.of("backend");
            case "models.list" -> Set.of("backend", "providerId");
            case "models.use" -> Set.of("backend", "providerId", "profileId", "authMethod", "modelId", "setDefault");
            case "auth.activate" -> Set.of("backend", "providerId", "profileId", "authMethod", "authEpoch");
            case "auth.logout.prepare" -> Set.of("backend", "providerId", "profileId", "authMethod");
            default -> throw invalid();
        };
        if (!arguments.isObject() || arguments.properties().stream().anyMatch(e -> !allowed.contains(e.getKey()))
                || !"pi".equals(text(arguments, "backend"))) throw invalid();
        if (intent.equals("models.list") && arguments.has("providerId"))
            new PiProviderCatalog().require(text(arguments, "providerId"));
        if (intent.equals("models.use") || intent.equals("auth.activate") || intent.equals("auth.logout.prepare"))
            identity(arguments);
        if (intent.equals("models.use")) {
            new PiProviderCatalog().requireModel(text(arguments, "providerId"), text(arguments, "modelId"));
            if (arguments.has("setDefault") && !arguments.get("setDefault").isBoolean()) throw invalid();
        }
        if (intent.equals("auth.activate")) epoch(arguments);
    }

    /** 只有协商后的宿主可调用；异常交由现有 handler 转为封闭分类。 */
    static ObjectNode execute(String intent, JsonNode arguments, ProviderAuthApplicationService service,
                              String sessionId, StdioProtocolCodec codec) {
        validate(intent, arguments);
        ObjectNode result = codec.objectNode();
        var cancellation = CancellationToken.none();
        switch (intent) {
            case "providers.catalog" -> {
                var providers = codec.arrayNode();
                boolean available = service.piComponentAvailable();
                for (var provider : new PiProviderCatalog().list()) {
                    var item = codec.objectNode(); item.put("backend", "pi");
                    item.put("providerId", provider.id()); item.put("brandId", provider.brandId());
                    item.put("label", provider.label()); item.put("componentAvailable", available);
                    var methods = codec.arrayNode();
                    provider.authMethods().forEach(method -> methods.add(method.id().equals("oauth") ? "OAUTH" : "API_KEY"));
                    item.set("authMethods", methods); providers.add(item);
                }
                result.set("providers", providers);
            }
            case "auth.list" -> {
                var profiles = codec.arrayNode();
                for (var profile : service.listPiProfiles(Optional.empty(), cancellation)) {
                    var item = codec.objectNode(); item.put("backend", "pi");
                    item.put("providerId", profile.providerId()); item.put("profileId", profile.profileId());
                    item.put("authMethod", profile.authMethod()); item.put("refKind", profile.refKind());
                    item.put("localStatus", profile.status()); item.put("providerDefault", false); profiles.add(item);
                }
                result.set("profiles", profiles);
            }
            case "models.list" -> {
                var models = codec.arrayNode(); var catalog = new PiProviderCatalog();
                var providers = arguments.has("providerId") ? java.util.List.of(catalog.require(text(arguments, "providerId"))) : catalog.list();
                for (var provider : providers) for (var model : provider.models()) {
                    var item = codec.objectNode(); item.put("backend", "pi"); item.put("providerId", provider.id());
                    item.put("modelId", model.id()); item.put("providerDefault", false); models.add(item);
                }
                result.set("models", models);
            }
            case "models.use" -> {
                var identity = identity(arguments);
                boolean setDefault = arguments.has("setDefault") && arguments.get("setDefault").booleanValue();
                service.selectPiModel(identity, text(arguments, "modelId"), setDefault, cancellation);
                projectIdentity(result, identity); result.put("modelId", text(arguments, "modelId")); result.put("setDefault", setDefault);
            }
            case "auth.activate" -> {
                var identity = identity(arguments);
                var profile = service.activatePiLogin(identity, new CredentialVersion.PiAuthEpoch(epoch(arguments)), cancellation);
                projectIdentity(result, identity); result.put("localStatus", profile.status());
            }
            case "auth.logout.prepare" -> {
                var identity = identity(arguments);
                var preview = service.preparePiLogout(identity, sessionId, cancellation);
                projectIdentity(result, identity); result.put("confirmationId", preview.confirmationId());
            }
            default -> throw invalid();
        }
        return result;
    }

    private static void projectIdentity(ObjectNode result, PiCredentialIdentity identity) {
        result.put("backend", "pi"); result.put("providerId", identity.providerId());
        result.put("profileId", identity.profileId()); result.put("authMethod", identity.authMethod().name());
    }

    private static PiCredentialIdentity identity(JsonNode arguments) {
        return new PiCredentialIdentity(text(arguments, "providerId"),
                PiCredentialIdentity.AuthMethod.valueOf(text(arguments, "authMethod")), text(arguments, "profileId"));
    }

    private static long epoch(JsonNode arguments) {
        String value = text(arguments, "authEpoch");
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isString()) throw invalid();
        return value.stringValue();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_ARGUMENT"); }
}
