package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 保存成功模型回合的不可解释续接材料，不承担模型协议解析或来源转换。
 *
 * <p>仅支持 {@code pi} 来源；身份绑定及内容白名单由边缘适配器验证。本类型只验证
 * 非空、严格 Unicode 与字节预算，不解析 JSON，也不把隐藏内容投影到正文、系统提示
 * 或运行元数据。不可变字符串可安全共享，日志表示始终遮蔽全部材料。</p>
 *
 * @param backend 后端标识，目前只允许 pi
 * @param providerId 非空且至多 200 个 UTF-16 单元的提供商身份
 * @param modelId 非空且至多 200 个 UTF-16 单元的模型身份
 * @param payload 非空严格 Unicode 私有材料，UTF-8 至多 1 MiB；原样保存，不作规范化
 * @since 0.15.0
 */
public record ModelContinuation(String backend, String providerId, String modelId, String payload) {
    /** 续接材料的 UTF-8 字节上限，不包含外部 JSON 信封。 */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    /** 身份字段的 UTF-16 单元上限。 */
    public static final int MAX_IDENTITY_CHARS = 200;

    /**
     * 验证独立领域边界；异常只包含固定分类，不包含输入材料。
     *
     * @param backend 后端标识
     * @param providerId 提供商身份
     * @param modelId 模型身份
     * @param payload 原样持有的私有序列化材料
     * @throws NullPointerException 任一字段为 null
     * @throws IllegalArgumentException 来源、身份、Unicode 或字节预算无效
     */
    public ModelContinuation {
        checkIdentity(backend);
        checkIdentity(providerId);
        checkIdentity(modelId);
        if (!"pi".equals(backend)) throw new IllegalArgumentException("续接来源不受支持");
        Objects.requireNonNull(payload, "续接材料不能为空");
        if (payload.isEmpty() || payload.length() > MAX_PAYLOAD_BYTES
                || utf8Length(payload) > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("续接材料为空或超过限制");
        }
    }

    private static void checkIdentity(String value) {
        Objects.requireNonNull(value, "续接身份不能为空");
        if (value.isBlank() || value.length() > MAX_IDENTITY_CHARS
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("续接身份无效");
        }
        utf8Length(value);
    }

    /** 按 Unicode 标量计数，避免 UTF-8 默认编码器悄悄替换不成对代理。 */
    private static int utf8Length(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("续接 Unicode 无效");
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("续接 Unicode 无效");
            } else {
                bytes += ch <= 0x7f ? 1 : ch <= 0x7ff ? 2 : 3;
            }
        }
        return bytes;
    }

    /**
     * 返回固定遮蔽表示，避免消息、集合或恢复快照的递归日志泄漏隐藏材料。
     * @return 不含身份和 payload 的固定摘要
     */
    @Override
    public String toString() {
        return "ModelContinuation[redacted]";
    }
}
