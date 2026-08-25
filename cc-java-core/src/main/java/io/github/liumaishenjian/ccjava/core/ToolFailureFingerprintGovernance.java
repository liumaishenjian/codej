package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonNull;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在单个 Run 内阻止同一失败调用被模型原样重复。
 *
 * <p>所有类型化失败都记录 Tool 名、类型保真且键排序的参数 SHA-256 和失败类别；不保存原始参数，
 * 也不读取错误文案、stdout/stderr 或网页正文。参数校验失败还可增加独立 correction-shape 层：
 * 只哈希 Tool 显式声明安全的 violation/correction 摘要，不读取或保存原始 query、path、Secret。
 * 两层记录都只存在于当前 Run，不得跨 Run 复用或持久化。</p>
 *
 * @since 0.15.0
 */
public final class ToolFailureFingerprintGovernance {
    private final Set<FailureFingerprint> failed = new HashSet<>();
    private final Set<ValidationCorrectionFingerprint> validationCorrections = new HashSet<>();

    /** 返回该 Tool 与规范参数是否已有任一类型化失败记录；执行前不猜测下一次失败类别。 */
    public synchronized boolean repeated(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        String arguments = argumentsDigest(call);
        return failed.stream().anyMatch(value ->
                value.tool().equals(call.name()) && value.arguments().equals(arguments));
    }

    /** 记录由 Tool、规范参数与类型化失败类别共同组成的执行失败 fingerprint。 */
    public synchronized void record(ToolCall call, ToolError error) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(error, "error 不能为空");
        failed.add(new FailureFingerprint(call.name(), argumentsDigest(call), error.category()));
    }

    /**
     * 原子记录 validation exact-arguments failure 与可选 correction signature。
     *
     * <p>每个无效调用都记录 Tool + canonical arguments SHA-256 + typed category，使未声明
     * signature 的 generic invalid 也能阻止原样重试。非空 signature 额外按 Tool + 安全纠错
     * 摘要 SHA-256 治理；本对象不保存或投影 arguments、signature 正文、query、path 或 Secret。
     * 同步的双层 check-and-add 保证同批并发中只有一个调用获得首次 actionable 反馈。</p>
     *
     * @param call 当前无效调用
     * @param error 首次可反馈的类型化 validation 错误
     * @param correctionSignature Tool 自生成并声明安全的纠错形状；空对象表示只使用 exact 层
     * @return {@code true} 表示 exact 或 shape 任一层已记录，应返回 {@code REPEATED_FAILURE}
     */
    public synchronized boolean recordValidationFailureOrRepeated(
            ToolCall call,
            ToolError error,
            JsonObject correctionSignature) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(error, "error 不能为空");
        Objects.requireNonNull(correctionSignature, "correctionSignature 不能为空");
        String arguments = argumentsDigest(call);
        boolean exactRepeated = failed.stream().anyMatch(value ->
                value.tool().equals(call.name()) && value.arguments().equals(arguments));
        failed.add(new FailureFingerprint(call.name(), arguments, error.category()));

        boolean shapeRepeated = false;
        if (!correctionSignature.values().isEmpty()) {
            ValidationCorrectionFingerprint fingerprint = new ValidationCorrectionFingerprint(
                    call.name(), digest(canonical(correctionSignature.values())));
            shapeRepeated = !validationCorrections.add(fingerprint);
        }
        return exactRepeated || shapeRepeated;
    }

    /**
     * 记录真实成功，并按可证明的恢复范围清理失败窗口。
     *
     * <p>同一 Tool 变参成功证明其策略已经改变，因此清除该 Tool 的旧 fingerprint；成功写入
     * Workspace 或改变系统状态只会释放可能由本地内容导致的进程失败。纯读取、PlanArtifact
     * 写入、用户交互以及跨 Tool 的 HTTP/Permission 失败都没有得到恢复证明，仍须拦截。</p>
     *
     * @param call 已由 Adapter 真实执行成功的调用
     * @param effect Tool 声明的最高副作用等级
     */
    public synchronized void recordSuccess(ToolCall call, ToolEffect effect) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(effect, "effect 不能为空");
        failed.removeIf(value -> value.tool().equals(call.name())
                || recoversCrossToolFailure(effect, value.category()));
    }

    private static boolean recoversCrossToolFailure(ToolEffect effect, ToolFailureCategory category) {
        return category == ToolFailureCategory.PROCESS_EXIT
                && (effect == ToolEffect.WRITE_WORKSPACE
                        || effect == ToolEffect.SYSTEM_OR_DESTRUCTIVE);
    }

    /** 构造不泄漏参数的策略反馈。 */
    public static ToolError repeatedFailure() {
        return ToolError.classified(ToolErrorCode.REPEATED_FAILURE, ToolFailureCategory.INTERNAL, false,
                "相同 Tool 与参数已失败；禁止原样重试，请修改 arguments 或向用户解释阻塞原因",
                new JsonObject(Map.of("requiredStrategyChange", true,
                        "retrySameArguments", false,
                        "allowedChanges", List.of("arguments", "explanation"))));
    }

    private static String argumentsDigest(ToolCall call) {
        return digest(canonical(call.arguments().values()));
    }

    private static String digest(String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    arguments.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private record FailureFingerprint(
            String tool,
            String arguments,
            ToolFailureCategory category) {
    }

    private record ValidationCorrectionFingerprint(String tool, String correctionDigest) {
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> (String) entry.getKey()));
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : entries) {
                out.append(((String) entry.getKey()).length()).append(':').append(entry.getKey())
                        .append('=').append(canonical(entry.getValue())).append(';');
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) out.append(canonical(item)).append(';');
            return out.append(']').toString();
        }
        if (value == JsonNull.INSTANCE) return "null";
        if (value instanceof String text) return "s" + text.length() + ':' + text;
        if (value instanceof Boolean bool) return "b" + bool;
        if (value instanceof Number number) return "n" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        throw new IllegalArgumentException("不支持的参数类型");
    }
}
