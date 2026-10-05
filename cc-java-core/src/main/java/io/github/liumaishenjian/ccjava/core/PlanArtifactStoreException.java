package io.github.liumaishenjian.ccjava.core;

import java.util.Objects;

/**
 * Plan artifact 持久边缘的隐私安全失败。
 *
 * <p>异常只暴露固定分类，不携带物理路径、Markdown 正文、JSON 原文或底层异常消息。</p>
 *
 * @since 0.1.0
 */
public final class PlanArtifactStoreException extends RuntimeException {
    /** 持久化失败的封闭分类。 */
    public enum Code {
        /** 预期已有工件但目标缺失。 */
        NOT_FOUND,
        /** create-only 目标已经存在。 */
        ALREADY_EXISTS,
        /** 预期 revision 或下一 revision 不匹配。 */
        STALE_REVISION,
        /** 预期正文摘要与 durable 当前值不匹配。 */
        DIGEST_CONFLICT,
        /** 待写 revision 的初态或相邻状态迁移不合法。 */
        INVALID_STATE,
        /** manifest/generation、schema、UTF-8、摘要或状态链损坏。 */
        CORRUPT,
        /** Plan 或 Session 身份不属于当前 store。 */
        IDENTITY_MISMATCH,
        /** 路径、链接、重解析点或文件类型被拒绝。 */
        PATH_REJECTED,
        /** 正文、metadata 或其他预算超过上限。 */
        LIMIT_EXCEEDED,
        /** 平台不能提供所需原子提交语义。 */
        ATOMIC_MOVE_UNAVAILABLE,
        /** 其他无法安全收敛的持久 I/O 失败。 */
        IO_FAILURE
    }

    /** 可持久化或投影的封闭分类，不携带底层异常自由文本。 */
    private final Code code;

    /**
     * 创建不包含底层自由文本的失败。
     *
     * @param code 固定失败分类
     */
    public PlanArtifactStoreException(Code code) {
        super(Objects.requireNonNull(code, "code 不能为空").name());
        this.code = code;
    }

    /**
     * 返回不包含底层自由文本的固定失败分类。
     *
     * @return 固定失败分类
     */
    public Code code() {
        return code;
    }
}
