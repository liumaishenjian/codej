package io.github.liumaishenjian.ccjava.domain.model;

import java.util.Objects;

/**
 * 单个 Run 启动时固定的非秘密 Provider 选择。
 *
 * <p>快照只携带已校验 identity；不持久化 secret、profile generation 或 store 信息。
 * active Run 不因后续默认值变化而替换该快照。</p>
 *
 * @param providerId Provider identity
 * @param profileId credential profile identity
 * @param modelId Provider catalog 中的精确模型 identity
 * @param backend 明确选择的pi或spring-ai后端，不推断失败fallback
 * @param authMethod API_KEY或OAUTH认证身份，不表示凭据来源
 * @since 0.1.0
 */
public record ProviderSelectionSnapshot(String providerId, String profileId, String modelId,
                                        String backend, String authMethod) {
    /**
     * 旧调用显式归属Spring AI/API Key身份，保持既有三参数契约。
     * @param providerId Provider身份
     * @param profileId 本地配置身份
     * @param modelId 精确模型身份
     */
    public ProviderSelectionSnapshot(String providerId, String profileId, String modelId) {
        this(providerId, profileId, modelId, "spring-ai", "API_KEY");
    }

    /** 校验身份与后端标签，具体Provider能力仍由边缘目录验证。 */
    public ProviderSelectionSnapshot {
        providerId = id(providerId, "providerId");
        profileId = id(profileId, "profileId");
        modelId = model(modelId);
        if (!("pi".equals(backend) || "spring-ai".equals(backend))
                || !("API_KEY".equals(authMethod) || "OAUTH".equals(authMethod))
                || ("spring-ai".equals(backend) && !"API_KEY".equals(authMethod))) {
            throw new IllegalArgumentException("Provider backend/authMethod 无效");
        }
    }

    private static String id(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " 格式无效");
        }
        return value;
    }

    private static String model(String value) {
        Objects.requireNonNull(value, "modelId 不能为空");
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 256 || bytes > 1024
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("modelId 格式无效");
        }
        return value;
    }
}
