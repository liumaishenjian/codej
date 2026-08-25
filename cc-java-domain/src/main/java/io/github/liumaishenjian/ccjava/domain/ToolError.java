package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 可安全反馈给模型的结构化 Tool 错误。
 *
 * <p>{@code category} 是跨 Adapter 的治理语义，{@code code} 保留具体纠正原因；
 * {@code retryable} 只能由类型化事实证明，不能从 {@code message} 或外部正文猜测。</p>
 *
 * @param code 稳定细粒度错误码
 * @param category 跨 Adapter 的失败类别
 * @param retryable 在不改变调用策略时 Adapter 是否可安全重试
 * @param message 不包含敏感实现细节的可读说明
 * @param details 便于模型纠正调用的结构化细节
 * @since 0.1.0
 */
public record ToolError(ToolErrorCode code, ToolFailureCategory category, boolean retryable,
                        String message, JsonObject details) {

    /**
     * 使用错误码的保守默认分类创建兼容错误。
     *
     * @param code 稳定错误码
     * @param message 安全说明
     * @param details 结构化纠正细节
     */
    public ToolError(ToolErrorCode code, String message, JsonObject details) {
        this(code, defaultCategory(code), defaultRetryable(code), message, details);
    }

    /** 校验错误内容后创建结构化 Tool 错误。 */
    public ToolError {
        code = Objects.requireNonNull(code, "code 不能为空");
        category = Objects.requireNonNull(category, "category 不能为空");
        message = Objects.requireNonNull(message, "message 不能为空");
        details = Objects.requireNonNull(details, "details 不能为空");
        if (message.isBlank()) throw new IllegalArgumentException("message 不能为空白");
    }

    /** 创建不包含额外细节的保守错误。 */
    public static ToolError of(ToolErrorCode code, String message) {
        return new ToolError(code, message, JsonObject.empty());
    }

    /** 创建带明确治理分类和 retryable 元数据的错误。 */
    public static ToolError classified(ToolErrorCode code, ToolFailureCategory category,
            boolean retryable, String message, JsonObject details) {
        return new ToolError(code, category, retryable, message, details);
    }

    private static ToolFailureCategory defaultCategory(ToolErrorCode code) {
        return switch (Objects.requireNonNull(code, "code 不能为空")) {
            case PERMISSION_DENIED, HOOK_BLOCKED, PLAN_GATE_BLOCKED, NETWORK_ACCESS_DENIED ->
                    ToolFailureCategory.PERMISSION;
            case INVALID_ARGUMENTS, INVALID_PATH, WORKSPACE_BOUNDARY_VIOLATION, PATH_NOT_FOUND,
                    PATH_TYPE_MISMATCH, LINK_ESCAPE, SENSITIVE_PATH, FILE_TOO_LARGE, FILE_CONFLICT,
                    UNSUPPORTED_ENCODING, NOT_A_GIT_REPOSITORY, WEB_SEARCH_DISABLED,
                    WEB_SEARCH_INVALID_TARGET, PLAN_ARTIFACT_CONFLICT,
                    TASK_NOT_FOUND, TASK_DELETED, TASK_REVISION_CONFLICT, TASK_BOARD_CONFLICT,
                    TASK_INVALID_TRANSITION, TASK_BLOCKED, TASK_DEPENDENCY_INVALID,
                    TASK_DEPENDENCY_CYCLE, TASK_CLAIM_CONFLICT, TASK_RECOVERY_REQUIRED,
                    TASK_CAPABILITY_DENIED, TASK_LIMIT_EXCEEDED -> ToolFailureCategory.VALIDATION;
            case WEB_SEARCH_FORBIDDEN -> ToolFailureCategory.HTTP_FORBIDDEN;
            case WEB_SEARCH_REMOTE_CLIENT_ERROR -> ToolFailureCategory.HTTP_CLIENT;
            case WEB_SEARCH_RATE_LIMITED -> ToolFailureCategory.HTTP_RATE_LIMIT;
            case WEB_SEARCH_REMOTE_SERVER_ERROR -> ToolFailureCategory.HTTP_SERVER;
            case NETWORK_CONTROL_UNAVAILABLE -> ToolFailureCategory.TRANSPORT;
            case PROCESS_EXIT -> ToolFailureCategory.PROCESS_EXIT;
            case OPERATION_CANCELLED -> ToolFailureCategory.CANCELLATION;
            case OPERATION_TIMED_OUT -> ToolFailureCategory.TIMEOUT;
            case OUTPUT_LIMIT_EXCEEDED -> ToolFailureCategory.OUTPUT_LIMIT;
            case RESULT_PROTOCOL_VIOLATION, SEARCH_PROTOCOL_VIOLATION, WEB_SEARCH_REDIRECT_REFUSED,
                    WEB_SEARCH_REMOTE_PROTOCOL_ERROR, WEB_SEARCH_UNSUPPORTED_MEDIA_TYPE,
                    WEB_SEARCH_MALFORMED_RESPONSE -> ToolFailureCategory.PROTOCOL;
            case INTERNAL_ERROR, REPEATED_FAILURE -> ToolFailureCategory.INTERNAL;
            default -> ToolFailureCategory.EXECUTION;
        };
    }

    private static boolean defaultRetryable(ToolErrorCode code) {
        return switch (code) {
            case WEB_SEARCH_RATE_LIMITED, WEB_SEARCH_REMOTE_SERVER_ERROR -> true;
            default -> false;
        };
    }
}
