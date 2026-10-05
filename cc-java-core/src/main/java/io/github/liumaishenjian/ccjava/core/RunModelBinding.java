package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 单个 Run 的显式模型决策；模型和摘要固定到同一来源，不依赖调用线程。
 *
 * <p>端口由所属 RunScope 管理，关闭后必须拒绝调用；来源可用于独立 child，
 * 但捕获来源不是授权绕过，每次打开仍必须重新验证原身份版本及撤销状态。
 * 窗口仅为模型目录声明，不是账号权益，也不允许扩大调用方预算。</p>
 *
 * @param gateway 本 Run 的模型端口
 * @param summarizer 本 Run 的同源摘要端口
 * @param childSource 不持有父 Run 活动网关或秘密的来源；启动兼容端口仍由原调用方持有
 * @param selection 可选完整选择；旧启动兼容入口可无身份元数据
 * @param contextWindowTokens 可选正上下文窗口声明；未知不推测
 */
public record RunModelBinding(ModelGateway gateway, ContextSummarizer summarizer,
        CapturedRunSource childSource, Optional<ProviderSelectionSnapshot> selection,
        OptionalLong contextWindowTokens) {
    /** 校验全部端口及元数据容器，不将缺失元数据解释为 fallback。 */
    public RunModelBinding {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(summarizer, "summarizer");
        Objects.requireNonNull(childSource, "childSource");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(contextWindowTokens, "contextWindowTokens");
        if (contextWindowTokens.isPresent() && contextWindowTokens.getAsLong() <= 0) {
            throw new IllegalArgumentException("contextWindowTokens 必须大于 0");
        }
    }
}
