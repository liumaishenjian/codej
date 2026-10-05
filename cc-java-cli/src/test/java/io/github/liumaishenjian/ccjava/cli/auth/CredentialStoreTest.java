package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 不支持 CAS 的第三方 store 不得退化为快照检查后无条件写。 */
class CredentialStoreTest {
    @Test void unsupportedCasFailsClosedAndConsumesSecretWithoutCallingLegacyMethods() {
        CredentialStore store = new CredentialStore() {
            @Override public Snapshot snapshot(CancellationToken token) { throw new AssertionError("不能先检查快照"); }
            @Override public CredentialProfile saveStore(String provider, String profile, SecretMaterial secret,
                    boolean setDefault, CancellationToken token) { throw new AssertionError("不能退化写入"); }
            @Override public CredentialProfile saveEnv(String provider, String profile, String env,
                    boolean setDefault, CancellationToken token) { throw new AssertionError("不能退化写入"); }
            @Override public boolean secretExists(SecretRef.Store ref, CancellationToken token) { throw new AssertionError(); }
            @Override public SecretMaterial readSecret(SecretRef.Store ref, CancellationToken token) { throw new AssertionError(); }
            @Override public void delete(String provider, String profile, long expected, CancellationToken token) {
                throw new AssertionError();
            }
        };
        SecretMaterial secret = new SecretMaterial("synthetic-unsupported-key".toCharArray());
        assertThatThrownBy(() -> store.saveStore("anthropic", "personal", secret, true, 0, CancellationToken.none()))
                .isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(secret::copyChars).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.saveEnv("anthropic", "personal", "CC_TEST_KEY", true, 0, CancellationToken.none()))
                .isInstanceOf(ProviderAuthException.class);
    }
}
