package io.github.liumaishenjian.ccjava.tools.web;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 不携带 endpoint、query、credential、响应正文或底层异常 message 的搜索失败。
 *
 * @since 0.1.0
 */
public final class WebSearchException extends Exception {
    /** 不含不可信文本的封闭失败分类。 */
    private final WebSearchFailure failure;
    /** 仅限 0 到 300 秒的安全重试提示。 */
    private final OptionalLong retryAfterSeconds;
    /** 403 仅由受信状态/头信号形成的安全原因。 */
    private final Optional<WebForbiddenReason> forbiddenReason;

    /**
     * 创建无 Retry-After 的固定失败。
     *
     * @param failure 封闭失败分类
     */
    public WebSearchException(WebSearchFailure failure) {
        this(failure, OptionalLong.empty(), Optional.empty());
    }

    /**
     * 创建可带安全 Retry-After 秒数的固定失败。
     *
     * @param failure 封闭失败分类
     * @param retryAfterSeconds 已限制的重试提示
     */
    public WebSearchException(WebSearchFailure failure, OptionalLong retryAfterSeconds) {
        this(failure, retryAfterSeconds, Optional.empty());
    }

    /**
     * 创建带 403 安全原因且不含远端正文的失败。
     * @param failure 必须为 FORBIDDEN 的封闭分类
     * @param forbiddenReason 仅从受信状态或头信号形成的非空原因
     */
    public WebSearchException(WebSearchFailure failure, WebForbiddenReason forbiddenReason) {
        this(failure, OptionalLong.empty(), Optional.of(forbiddenReason));
    }

    private WebSearchException(WebSearchFailure failure, OptionalLong retryAfterSeconds,
            Optional<WebForbiddenReason> forbiddenReason) {
        super(Objects.requireNonNull(failure, "failure 不能为空").name());
        this.failure = failure;
        this.retryAfterSeconds = Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds 不能为空");
        this.forbiddenReason = Objects.requireNonNull(forbiddenReason, "forbiddenReason 不能为空");
        if (failure != WebSearchFailure.FORBIDDEN && forbiddenReason.isPresent()) {
            throw new IllegalArgumentException("只有 403 可以携带 forbiddenReason");
        }
    }

    /**
     * 返回固定失败分类。
     *
     * @return 固定失败分类
     */
    public WebSearchFailure failure() { return failure; }

    /**
     * 返回已限制的 Retry-After 秒数。
     *
     * @return 已限制的 Retry-After 秒数
     */
    public OptionalLong retryAfterSeconds() { return retryAfterSeconds; }

    /**
     * 返回 403 可观察的安全原因。
     * @return 已记录的封闭原因；未提供或非 403 时为空
     */
    public Optional<WebForbiddenReason> forbiddenReason() { return forbiddenReason; }
}
