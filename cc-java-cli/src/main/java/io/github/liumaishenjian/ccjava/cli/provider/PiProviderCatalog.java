package io.github.liumaishenjian.ccjava.cli.provider;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-100 四路由静态公开目录，不依赖 Node、环境、凭据或网络。
 *
 * <p>版本化资源由生产 Node provider-registry.describeCatalog 的公开投影生成；只含
 * Pi 声明的元数据，不是账号权益、运行可用性或价格。JSON 的模型 label 映射为 record 的
 * name。输入声明 image 不表示 Java 已实现图片生产请求。解析拒绝未知字段、重复键、
 * 非 UTF-8、错类型及超限；异常不携带输入或底层解析原因。</p>
 */
public final class PiProviderCatalog {
    /** 锁定的公开 Pi 目录来源版本。 */
    public static final String PI_VERSION = "0.85.1";
    static final int MAXIMUM_BYTES = 8 * 1024 * 1024;
    private static final String RESOURCE = "/pi/catalog-0.85.1.json";
    private static final List<String> ROUTES = List.of("openai", "openai-codex", "deepseek", "qwen-token-plan-cn");
    private static final List<String> BRANDS = List.of("openai", "deepseek", "qwen");
    private static final List<String> ROUTE_BRANDS = List.of("openai", "openai", "deepseek", "qwen");
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(512).maxNameLength(32).maxNumberLength(16)
                    .maxDocumentLength(MAXIMUM_BYTES).build()).build()).build();
    private final List<Brand> brands;
    private final List<Provider> providers;

    /**
     * 只读取包内有界版本化资源，不检查 Node、环境或登录状态。
     * @throws IllegalArgumentException 资源缺失或目录不合法，错误内容为封闭码
     */
    public PiProviderCatalog() {
        try (InputStream source = PiProviderCatalog.class.getResourceAsStream(RESOURCE)) {
            PiProviderCatalog parsed = read(source);
            brands = parsed.brands;
            providers = parsed.providers;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private PiProviderCatalog(List<Brand> brands, List<Provider> providers) {
        this.brands = List.copyOf(brands);
        this.providers = List.copyOf(providers);
    }

    /** 返回公开品牌声明。
     * @return 三品牌不可变列表，保持生产投影顺序 */
    public List<Brand> brands() { return brands; }

    /** 返回与运行组件可用性无关的路由声明。
     * @return 四路由不可变列表，未登录或缺 Node 时同样完整 */
    public List<Provider> list() { return providers; }

    /**
     * 精确查找路由，不推断品牌、别名或兼容 Provider。
     * @param providerId 公开路由 ID
     * @return 声明的路由元数据
     * @throws IllegalArgumentException 路由不存在，不回显查询内容
     */
    public Provider require(String providerId) {
        return providers.stream().filter(provider -> provider.id().equals(providerId))
                .findFirst().orElseThrow(PiProviderCatalog::invalid);
    }

    /**
     * 在指定路由中精确查找模型，不意味着当前账号可以使用它。
     * @param providerId 四路由之一
     * @param modelId Pi 的模型 ID
     * @return 不可变模型声明
     * @throws IllegalArgumentException 路由或模型不存在，不跨路由回退
     */
    public Model requireModel(String providerId, String modelId) {
        return require(providerId).models().stream().filter(model -> model.id().equals(modelId))
                .findFirst().orElseThrow(PiProviderCatalog::invalid);
    }

    // 包级离线测试 seam；调用方拥有并关闭流。先限制字节，再严格解码 UTF-8。
    static PiProviderCatalog read(InputStream source) {
        try {
            if (source == null) throw invalid();
            byte[] bytes = source.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) throw invalid();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (json.charAt(0) == '\ufeff') throw invalid();
            JsonNode root;
            try (var parser = MAPPER.createParser(json)) {
                root = MAPPER.readTree(parser);
                if (parser.nextToken() != null) throw invalid();
            }
            fields(root, "piVersion", "brands", "providers");
            if (!PI_VERSION.equals(text(root.get("piVersion"), 16))) throw invalid();
            JsonNode providerNodes = array(root.get("providers"), 4, 4);
            List<Provider> providers = new ArrayList<>();
            for (int i = 0; i < ROUTES.size(); i++) {
                JsonNode node = providerNodes.get(i);
                fields(node, "id", "brandId", "label", "authMethods", "models");
                String id = text(node.get("id"), 64);
                String brandId = text(node.get("brandId"), 64);
                if (!ROUTES.get(i).equals(id) || !ROUTE_BRANDS.get(i).equals(brandId)) throw invalid();
                JsonNode auth = array(node.get("authMethods"), 1, 1).get(0);
                fields(auth, "id", "label");
                String authId = text(auth.get("id"), 16);
                if (!(i == 1 ? "oauth" : "api_key").equals(authId)) throw invalid();
                List<Model> models = new ArrayList<>();
                String previous = null;
                for (JsonNode model : array(node.get("models"), 0, 10_000)) {
                    fields(model, "id", "label", "api", "contextWindow", "maxTokens", "reasoning", "input");
                    String modelId = text(model.get("id"), 256);
                    if (previous != null && previous.compareTo(modelId) >= 0) throw invalid();
                    previous = modelId;
                    String api = text(model.get("api"), 128);
                    if (!api.matches("[a-z][a-z0-9-]*")) throw invalid();
                    JsonNode reasoning = model.get("reasoning");
                    if (!reasoning.isBoolean()) throw invalid();
                    List<String> input = new ArrayList<>();
                    for (JsonNode item : array(model.get("input"), 1, 2)) {
                        String modality = text(item, 5);
                        if (!(modality.equals("text") || modality.equals("image")) || input.contains(modality)) throw invalid();
                        input.add(modality);
                    }
                    models.add(new Model(modelId, text(model.get("label"), 512), api,
                            tokens(model.get("contextWindow")), tokens(model.get("maxTokens")), reasoning.asBoolean(), input));
                }
                providers.add(new Provider(id, brandId, text(node.get("label"), 512),
                        List.of(new AuthMethod(authId, text(auth.get("label"), 512))), models));
            }
            JsonNode brandNodes = array(root.get("brands"), 3, 3);
            List<Brand> brands = new ArrayList<>();
            for (int i = 0; i < BRANDS.size(); i++) {
                JsonNode node = brandNodes.get(i);
                fields(node, "id", "label", "providerIds");
                String id = text(node.get("id"), 64);
                if (!BRANDS.get(i).equals(id)) throw invalid();
                List<String> ids = new ArrayList<>();
                for (JsonNode item : array(node.get("providerIds"), 1, 2)) ids.add(text(item, 64));
                if (!ids.equals(providers.stream().filter(p -> p.brandId().equals(id)).map(Provider::id).toList())) throw invalid();
                brands.add(new Brand(id, text(node.get("label"), 512), ids));
            }
            return new PiProviderCatalog(brands, providers);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static void fields(JsonNode node, String... names) {
        Set<String> allowed = Set.of(names);
        if (node == null || !node.isObject() || node.size() != allowed.size()) throw invalid();
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid();
    }

    private static JsonNode array(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isArray() || node.size() < minimum || node.size() > maximum) throw invalid();
        return node;
    }

    private static String text(JsonNode node, int maximum) {
        if (node == null || !node.isString()) throw invalid();
        String value = node.asString();
        if (value.isEmpty() || value.length() > maximum
                || edgeWhitespace(value.codePointAt(0)) || edgeWhitespace(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw invalid();
        return value;
    }

    private static boolean edgeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xfeff;
    }

    private static int tokens(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 1 || node.intValue() > 1_000_000_000) throw invalid();
        return node.intValue();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PI_CATALOG_INVALID"); }

    /**
     * 品牌展示声明，不是登录身份。
     * @param id 品牌 ID
     * @param label 公开名称
     * @param providerIds 按目录顺序排列的路由 ID，防御复制
     */
    public record Brand(String id, String label, List<String> providerIds) {
        /**
         * 防御复制路由列表，防止调用方修改目录。
         * @param id 品牌 ID
         * @param label 公开名称
         * @param providerIds 不可为 null 的路由 ID 列表
         */
        public Brand { providerIds = List.copyOf(providerIds); }
    }

    /**
     * 认证方式声明，不表示已经认证。
     * @param id api_key 或 oauth
     * @param label 公开展示名称
     */
    public record AuthMethod(String id, String label) { }

    /**
     * 路由声明；模型和认证列表均深度不可变。
     * @param id Pi 路由 ID
     * @param brandId 所属品牌 ID
     * @param label 路由公开名称
     * @param authMethods 路由声明认证方式
     * @param models 按 UTF-16 ID 顺序排列的模型声明
     */
    public record Provider(String id, String brandId, String label, List<AuthMethod> authMethods, List<Model> models) {
        /**
         * 防御复制两份列表，不暴露 JSON 树或可变 SDK 对象。
         * @param id 路由 ID
         * @param brandId 品牌 ID
         * @param label 路由名称
         * @param authMethods 不可为 null 的认证声明列表
         * @param models 不可为 null 的模型声明列表
         */
        public Provider { authMethods = List.copyOf(authMethods); models = List.copyOf(models); }
    }

    /**
     * Pi 模型公开声明；不包含 URI、价格、密钥或账号权益。
     * @param id 模型 ID
     * @param name 模型名称，由公开 JSON label 投影
     * @param api Pi 声明的单回合协议标识
     * @param contextWindow 声明的上下文 token 上限
     * @param maxTokens 声明的最大输出 token 数
     * @param reasoning Pi 声明的推理能力
     * @param input Pi 声明的 text/image 模态，image 不代表 Java 图片生产实现
     */
    public record Model(String id, String name, String api, int contextWindow, int maxTokens,
            boolean reasoning, List<String> input) {
        /**
         * 防御复制输入模态声明，不把声明转成生产能力。
         * @param id 模型 ID
         * @param name 模型名称
         * @param api 声明协议
         * @param contextWindow 声明上下文上限
         * @param maxTokens 声明输出上限
         * @param reasoning 声明推理能力
         * @param input 不可为 null 的输入模态列表
         */
        public Model { input = List.copyOf(input); }
    }
}
