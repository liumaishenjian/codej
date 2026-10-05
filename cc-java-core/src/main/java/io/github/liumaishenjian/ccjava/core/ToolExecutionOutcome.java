package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import java.util.Objects;
import java.util.Optional;

/**
 * Tool 实现返回给 Pipeline 的、尚未绑定 Call ID 的业务结果。
 *
 * <p>Tool 只能描述执行是否成功以及安全输出，最终
 * {@link io.github.liumaishenjian.ccjava.domain.ToolResult} 的 Call ID、
 * Tool 名称和状态由 Pipeline 根据原始调用绑定。</p>
 *
 * @param successful 执行是否成功
 * @param content    成功时的输出正文
 * @param error      失败时的结构化错误
 * @param metadata   Tool 语义裁剪和 continuation 元数据
 * @since 0.1.0
 */
public record ToolExecutionOutcome(
        boolean successful,
        String content,
        Optional<ToolError> error,
        ToolResultMetadata metadata) {

    /**
     * 校验并创建 Tool 业务结果。
     *
     * @param successful 执行是否成功
     * @param content    成功时的输出正文
     * @param error      失败时的结构化错误
     * @param metadata   Tool 语义裁剪和 continuation 元数据
     * @throws NullPointerException     {@code content}、{@code error} 或 {@code metadata} 为空时抛出
     * @throws IllegalArgumentException 成功状态与错误是否存在不一致时抛出
     */
    public ToolExecutionOutcome {
        content = Objects.requireNonNull(content, "content 不能为空");
        error = Objects.requireNonNull(error, "error 不能为空");
        metadata = Objects.requireNonNull(metadata, "metadata 不能为空");
        if (metadata.returnedCharacters() != content.codePointCount(0, content.length())) {
            throw new IllegalArgumentException("metadata 返回字符数必须与 content 一致");
        }
        if (successful && error.isPresent()) {
            throw new IllegalArgumentException("成功 Outcome 不能携带 error");
        }
        if (!successful && error.isEmpty()) {
            throw new IllegalArgumentException("失败 Outcome 必须携带 error");
        }
    }

    /**
     * 创建成功业务结果。
     *
     * @param content Tool 输出正文
     * @return 成功 Outcome
     */
    public static ToolExecutionOutcome success(String content) {
        return new ToolExecutionOutcome(
                true,
                Objects.requireNonNull(content, "content 不能为空"),
                Optional.empty(),
                ToolResultMetadata.complete(content));
    }

    /**
     * 创建携带语义裁剪 metadata 的成功业务结果。
     *
     * @param content Tool 输出正文
     * @param metadata Tool 产生的有界结果信息
     * @return 成功 Outcome
     */
    public static ToolExecutionOutcome success(String content, ToolResultMetadata metadata) {
        return new ToolExecutionOutcome(
                true,
                Objects.requireNonNull(content, "content 不能为空"),
                Optional.empty(),
                Objects.requireNonNull(metadata, "metadata 不能为空"));
    }

    /**
     * 创建失败业务结果。
     *
     * @param error 结构化错误
     * @return 失败 Outcome
     */
    public static ToolExecutionOutcome failure(ToolError error) {
        return failure("", error, ToolResultMetadata.complete(""));
    }

    /**
     * 创建保留有界失败证据的业务结果，仍由 Pipeline 统一转换与截断。
     * @param content 非空的失败证据正文，调用方应避免敏感数据
     * @param error 非空的结构化失败原因
     * @param metadata 与正文一致的媒体类型、长度和截断事实
     * @return 明确失败且保留证据与元数据的 Outcome
     */
    public static ToolExecutionOutcome failure(String content, ToolError error, ToolResultMetadata metadata) {
        return new ToolExecutionOutcome(false, Objects.requireNonNull(content, "content 不能为空"),
                Optional.of(Objects.requireNonNull(error, "error 不能为空")),
                Objects.requireNonNull(metadata, "metadata 不能为空"));
    }
}
