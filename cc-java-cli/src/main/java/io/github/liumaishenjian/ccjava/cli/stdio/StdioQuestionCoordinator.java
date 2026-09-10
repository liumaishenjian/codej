package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.UserQuestionHandler;
import io.github.liumaishenjian.ccjava.domain.UserQuestionAnswer;
import io.github.liumaishenjian.ccjava.domain.UserQuestionRequest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 把同步 Plan ask-user Tool 桥接为 stdio callId-correlated 选择器。
 *
 * <p>同一连接至多等待一个问题；取消、关闭、迟到 callId 或未知 optionId 都失败关闭。
 * 协调器校验整批答案，不把原始 Tool JSON 交给事件层。</p>
 *
 * @since 0.1.0
 */
final class StdioQuestionCoordinator implements UserQuestionHandler, AutoCloseable {
    private final Object lock = new Object();
    private final Consumer<UserQuestionRequest> requestSink;
    private Pending pending;
    private boolean closed;

    /** 使用结构化问题事件出口创建协调器。 */
    StdioQuestionCoordinator(Consumer<UserQuestionRequest> requestSink) {
        this.requestSink = Objects.requireNonNull(requestSink, "requestSink 不能为空");
    }

    @Override
    public UserQuestionAnswer ask(UserQuestionRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) throw new IllegalStateException("问题已取消");
        Pending current = new Pending(request, new CompletableFuture<>());
        synchronized (lock) {
            if (closed) throw new IllegalStateException("问题 Surface 已关闭");
            if (pending != null) throw new IllegalStateException("同一连接只能等待一个问题");
            pending = current;
        }
        try (CancellationToken.Registration ignored = cancellationToken.onCancellation(
                () -> fail(request.callId()))) {
            try {
                requestSink.accept(request);
            } catch (RuntimeException transportFailure) {
                fail(request.callId());
            }
            UserQuestionAnswer answer = current.answer().join();
            if (answer == null) throw new IllegalStateException("问题未获得有效答案");
            return answer;
        } finally {
            synchronized (lock) {
                if (pending == current) pending = null;
            }
        }
    }

    /** 仅完成匹配 callId 且属于已声明选项的首次答案。 */
    boolean resolve(String callId, String optionId) {
        return resolve(new UserQuestionAnswer(callId, optionId));
    }

    /** 原子接受一次完整且属于待决请求的答案。 */
    boolean resolve(UserQuestionAnswer answer) {
        synchronized (lock) {
            if (pending != null && pending.request().questions().isEmpty() && answer.answers().size() == 1) {
                var selection = answer.answers().getFirst();
                if (!selection.questionId().equals("question") || selection.optionIds().size() != 1 || !selection.freeText().isEmpty()) return false;
                answer = new UserQuestionAnswer(answer.callId(), selection.optionIds().getFirst());
            }
            if (pending == null || !pending.request().accepts(answer)) return false;
            return pending.answer().complete(answer);
        }
    }
    private void fail(String callId) {
        synchronized (lock) {
            if (pending != null && pending.request().callId().equals(callId)) {
                pending.answer().complete(null);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (pending != null) pending.answer().complete(null);
        }
    }

    private record Pending(UserQuestionRequest request, CompletableFuture<UserQuestionAnswer> answer) {
    }
}
