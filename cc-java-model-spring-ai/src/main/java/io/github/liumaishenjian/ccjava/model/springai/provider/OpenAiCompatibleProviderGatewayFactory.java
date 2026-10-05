package io.github.liumaishenjian.ccjava.model.springai.provider;

import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.model.springai.OpenAiCompatibleModelFactory;
import io.github.liumaishenjian.ccjava.model.springai.SpringAiModelGateway;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import java.util.Arrays;

/** 使用独立 OpenAI-compatible 工厂契约创建单 route Gateway。 */
public final class OpenAiCompatibleProviderGatewayFactory implements ProviderGatewayFactory {
    /** 创建无状态 OpenAI-compatible Gateway 工厂。 */
    public OpenAiCompatibleProviderGatewayFactory() {
    }

    @Override public ProviderGatewayKind kind() { return ProviderGatewayKind.OPENAI_COMPATIBLE; }
    /** 创建禁用 SDK retry 的 OpenAI-compatible Gateway，并立即清零临时字符副本。 */
    @Override public ModelGateway create(ProviderGatewayConfiguration configuration) {
        char[] key = configuration.apiKey();
        try {
            OpenAiCompatibleSettings settings = new OpenAiCompatibleSettings(
                    configuration.baseUri().toString(), new String(key), configuration.modelId());
            var resource = new OpenAiCompatibleModelFactory().createResource(
                    settings, configuration.staticHeaders(), configuration.requestTimeout());
            try {
                return new CloseableModelGateway(
                        new SpringAiModelGateway(resource.chatModel(), configuration.modelId()), resource);
            } catch (RuntimeException | Error failure) {
                resource.close();
                throw failure;
            }
        } finally {
            Arrays.fill(key, '\0');
        }
    }
}
