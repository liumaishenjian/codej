package io.github.liumaishenjian.ccjava.domain;

/**
 * Provider 明确返回的单次模型回合 Token Usage。
 *
 * @param inputTokens 输入消息和 Tool Schema 消耗的 Token
 * @param outputTokens 模型输出消耗的 Token
 * @param totalTokens Provider 报告或Adapter按已报告分项、相同计数口径精确合成的总Token；不表示价格
 * @since 0.1.0
 */
public record ModelUsage(int inputTokens, int outputTokens, int totalTokens) {

    /**
     * 校验 Provider Usage 非负且总数不小于两个分项。
     */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Model Usage 不能为负数");
        }
        if (totalTokens < inputTokens || totalTokens < outputTokens) {
            throw new IllegalArgumentException("totalTokens 不能小于任一分项");
        }
    }
}
