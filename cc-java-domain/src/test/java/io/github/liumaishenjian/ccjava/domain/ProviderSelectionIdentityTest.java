package io.github.liumaishenjian.ccjava.domain;

import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 选择快照携带后端与认证标签，不以同名provider混用身份。 */
class ProviderSelectionIdentityTest {
    @Test void oldConstructorKeepsExplicitCompatibilityIdentity() {
        assertThat(new ProviderSelectionSnapshot("openai", "default", "model"))
                .isEqualTo(new ProviderSelectionSnapshot("openai", "default", "model", "spring-ai", "API_KEY"));
    }
    @Test void sameNamesAcrossBackendsAreDifferentSelections() {
        assertThat(new ProviderSelectionSnapshot("openai", "default", "model"))
                .isNotEqualTo(new ProviderSelectionSnapshot("openai", "default", "model", "pi", "API_KEY"));
    }
    @Test void unsupportedTagsCannotBecomeImplicitCompatibilityRoutes() {
        for (String backend : new String[]{null, "", "PI", "unknown"}) {
            assertThatThrownBy(() -> new ProviderSelectionSnapshot("openai", "default", "model", backend, "API_KEY"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ProviderSelectionSnapshot("openai", "default", "model", "spring-ai", "OAUTH"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderSelectionSnapshot("openai", "default", "model", "pi", "ENV_REF"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
