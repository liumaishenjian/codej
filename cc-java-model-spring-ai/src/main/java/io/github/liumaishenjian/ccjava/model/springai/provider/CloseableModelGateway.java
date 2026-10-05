package io.github.liumaishenjian.ccjava.model.springai.provider;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为流式 Gateway 绑定底层 Provider Client 的显式关闭所有权。
 *
 * <p>该装饰器不改变模型回合、取消或错误分类，只确保 run route lease 释放时关闭
 * HTTP/SDK 资源。关闭幂等；资源关闭失败不会被转换成另一个模型终态。</p>
 */
final class CloseableModelGateway implements StreamingModelGateway, AutoCloseable,
        io.github.liumaishenjian.ccjava.core.ContextSummarizer {
    private final StreamingModelGateway delegate;
    private final AutoCloseable resource;
    private final AtomicBoolean closed = new AtomicBoolean();

    CloseableModelGateway(StreamingModelGateway delegate, AutoCloseable resource) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.resource = Objects.requireNonNull(resource, "resource 不能为空");
    }

    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelStreamObserver observer,
            CancellationToken cancellation) throws ModelGatewayException {
        return delegate.complete(request, observer, cancellation);
    }

    @Override
    public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
        return delegate.complete(request);
    }

    /** 保持摘要与模型回合使用同一个已绑定凭证的 Provider Client。 */
    @Override
    public java.util.Optional<io.github.liumaishenjian.ccjava.domain.SummaryCandidate> summarize(
            io.github.liumaishenjian.ccjava.domain.SummaryRequest request, CancellationToken cancellation) {
        if (closed.get()) throw new IllegalStateException("Provider route closed");
        if (!(delegate instanceof io.github.liumaishenjian.ccjava.core.ContextSummarizer summarizer)) {
            throw new IllegalStateException("Provider summary unavailable");
        }
        return summarizer.summarize(request, cancellation);
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            resource.close();
        }
    }
}
