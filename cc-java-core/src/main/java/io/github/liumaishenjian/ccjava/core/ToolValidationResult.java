package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import java.util.List;
import java.util.Objects;

/**
 * Tool 对模型参数进行确定性校验后的结果。
 *
 * <p>{@code details} 只允许承载 Tool 自己生成、可安全投影给模型的纠错元数据，
 * 不得包含完整参数、文件正文、Secret 或底层异常。{@code correctionSignature} 是不投影的
 * 确定性纠错形状；只有 Tool 能确认摘要不含 query、path、Secret 或其他业务输入时才可提供。
 * Pipeline 只哈希该摘要，用于当前 Run 的重复纠错治理。</p>
 *
 * @param valid               参数是否可执行
 * @param violations          无效时可反馈给模型的全部问题
 * @param details             无效时可供模型确定性纠错的安全结构化元数据
 * @param correctionSignature 不投影、可安全哈希的确定性 violation/correction 摘要；空对象表示不参与形状治理
 * @since 0.1.0
 */
public record ToolValidationResult(
        boolean valid,
        List<String> violations,
        JsonObject details,
        JsonObject correctionSignature) {

    /**
     * 校验并创建参数校验结果。
     *
     * @param valid      参数是否有效
     * @param violations 无效时可反馈给模型的问题
     * @throws NullPointerException     {@code violations} 为空时抛出
     * @throws IllegalArgumentException 状态与问题列表不一致，或问题为空白时抛出
     */
    public ToolValidationResult {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations 不能为空"));
        details = Objects.requireNonNull(details, "details 不能为空");
        correctionSignature = Objects.requireNonNull(
                correctionSignature, "correctionSignature 不能为空");
        if (valid && (!violations.isEmpty()
                || !details.values().isEmpty()
                || !correctionSignature.values().isEmpty())) {
            throw new IllegalArgumentException("有效结果不能包含 violations、details 或 correctionSignature");
        }
        if (!valid && violations.isEmpty()) {
            throw new IllegalArgumentException("无效结果必须说明至少一个问题");
        }
        if (violations.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("violation 不能为空");
        }
    }

    /**
     * 创建校验通过结果。
     *
     * @return 无问题的有效结果
     */
    public static ToolValidationResult validResult() {
        return new ToolValidationResult(true, List.of(), JsonObject.empty(), JsonObject.empty());
    }

    /**
     * 创建包含一个问题、但没有额外结构化动作的校验失败结果。
     *
     * @param violation 可供模型纠正的说明
     * @return 无效结果
     */
    public static ToolValidationResult invalid(String violation) {
        return invalid(violation, JsonObject.empty());
    }

    /**
     * 创建同时包含问题说明和安全纠错元数据的校验失败结果。
     *
     * @param violation 可供模型纠正的说明
     * @param details 不含原始敏感输入的结构化纠错动作
     * @return 无效结果
     */
    public static ToolValidationResult invalid(String violation, JsonObject details) {
        return new ToolValidationResult(false, List.of(violation), details, JsonObject.empty());
    }

    /**
     * 创建带有显式安全纠错形状的校验失败结果。
     *
     * <p>{@code correctionSignature} 不会反馈给模型或写入 Session；Tool 必须保证其只描述
     * violation/correction 类型，不包含 query、path、Secret 或其他业务参数值。</p>
     *
     * @param violation 可供模型纠正的说明
     * @param details 可安全投影的结构化纠错动作
     * @param correctionSignature 可安全哈希的稳定纠错形状
     * @return 无效结果
     */
    public static ToolValidationResult invalid(
            String violation,
            JsonObject details,
            JsonObject correctionSignature) {
        if (correctionSignature.values().isEmpty()) {
            throw new IllegalArgumentException("显式 correctionSignature 不能为空");
        }
        return new ToolValidationResult(false, List.of(violation), details, correctionSignature);
    }
}
