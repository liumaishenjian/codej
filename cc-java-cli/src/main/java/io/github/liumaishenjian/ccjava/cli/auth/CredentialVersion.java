package io.github.liumaishenjian.ccjava.cli.auth;

/** 凭据租约版本的来源标签；Pi材料修订号不属于可获取租约的版本。 */
public sealed interface CredentialVersion permits CredentialVersion.LegacyGeneration, CredentialVersion.PiAuthEpoch {
    /** 返回来源内版本。
     * @return 来源内单调版本；不同标签不得混比 */
    long value();

    /** 标记旧存储版本。
     * @param value 旧存储的非负索引代次 */
    record LegacyGeneration(long value) implements CredentialVersion {
        /** 拒绝非法版本。 */
        public LegacyGeneration { if (value < 0) throw new IllegalArgumentException("CREDENTIAL_VERSION_INVALID"); }
    }

    /** 标记Pi登录版本。
     * @param value Pi存储的正登录代次，不是材料修订号 */
    record PiAuthEpoch(long value) implements CredentialVersion {
        /** 拒绝非法版本。 */
        public PiAuthEpoch { if (value < 1) throw new IllegalArgumentException("CREDENTIAL_VERSION_INVALID"); }
    }
}
