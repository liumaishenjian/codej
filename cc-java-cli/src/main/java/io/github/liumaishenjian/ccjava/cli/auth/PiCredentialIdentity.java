package io.github.liumaishenjian.ccjava.cli.auth;

import java.util.Set;

/**
 * Pi 后端的不可变身份键；模型 ID 不参与认证身份，旧后端不能借此入口迁移。
 *
 * @param backend 固定为 pi
 * @param providerId 四个已批准的内部路由之一
 * @param authMethod API_KEY 或仅 Codex 使用的 OAUTH
 * @param profileId 受限的小写 profile 标识
 */
public record PiCredentialIdentity(String backend, String providerId, AuthMethod authMethod, String profileId) {
    private static final Set<String> PROVIDERS = Set.of("openai", "openai-codex", "deepseek", "qwen-token-plan-cn");

    /** 认证方法不同于材料来源：ENV_REF 仍属于 API_KEY。 */
    public enum AuthMethod {
        /** 静态 Key 或环境变量引用。 */
        API_KEY,
        /** 仅 OpenAI Codex 的可旋转令牌。 */
        OAUTH
    }

    /**
     * 校验身份所有组成部分，错误不包含调用方输入。
     * @param backend 固定后端
     * @param providerId 内部路由
     * @param authMethod 认证方法
     * @param profileId profile 标识
     */
    public PiCredentialIdentity {
        if (!"pi".equals(backend) || providerId == null || !PROVIDERS.contains(providerId)
                || authMethod == null || profileId == null || !profileId.matches("[a-z0-9][a-z0-9-]{0,62}")
                || ("openai-codex".equals(providerId) != (authMethod == AuthMethod.OAUTH))) {
            throw new IllegalArgumentException("PI_IDENTITY_INVALID");
        }
    }

    /**
     * 使用固定 Pi 后端构造身份。
     * @param providerId 内部路由
     * @param authMethod 认证方法
     * @param profileId profile 标识
     */
    public PiCredentialIdentity(String providerId, AuthMethod authMethod, String profileId) {
        this("pi", providerId, authMethod, profileId);
    }

    String lockKey() { return backend + "\0" + providerId + "\0" + authMethod.name() + "\0" + profileId; }
    @Override public String toString() { return "<redacted>"; }
}
