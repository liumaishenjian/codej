package io.github.liumaishenjian.ccjava.model.springai.provider;

import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.model.springai.OpenAiCompatibleModelFactory;
import io.github.liumaishenjian.ccjava.model.springai.SpringAiModelGateway;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import java.util.Arrays;

/** 使用 OpenRouter 官方 origin 与独立 factory identity 创建 Gateway。 */
public final class OpenRouterProviderGatewayFactory implements ProviderGatewayFactory {
    /** 创建无状态 OpenRouter Gateway 工厂。 */
    public OpenRouterProviderGatewayFactory() {
    }

    @Override public ProviderGatewayKind kind() { return ProviderGatewayKind.OPENROUTER; }
    /** 创建 OpenRouter 的 OpenAI-compatible Gateway，不执行 fallback 或 profile rotation。 */
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
