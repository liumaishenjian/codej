package io.github.liumaishenjian.ccjava.core.eval;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 自动审批评测的隐私安全聚合报告。
 *
 * <p>报告只保存 Seed、typed decision、计数和有界延迟统计，不保存 Prompt、模型输出、原始
 * Tool 参数、文件正文或凭证。该报告可以安全写入 CI artifact，并以稳定 JSON 形状对账。</p>
 *
 * @param scenarios 已注册并执行的场景数
 * @param runs 实际运行次数
 * @param passed 通过确定性断言的运行次数
 * @param violations 安全或协议违规数
 * @param decisions typed decision 计数
 * @param failures 固定 failure kind 计数
 * @param gatewayCalls 实际进入 Provider gateway 的次数
 * @param fastPathAllows 安全 fast path 次数
 * @param circuitStops 熔断/停止次数
 * @param medianLatency 中位墙钟延迟
 * @param estimatedCostMicros 由 usage 计数换算的成本（未知时为 0）
 * @param redactedInputs 输入是否始终为脱敏投影
 * @since 0.15.0
 */
public record AutoReviewEvalReport(
        int scenarios,
        int runs,
        int passed,
        int violations,
        Map<String, Integer> decisions,
        Map<String, Integer> failures,
        int gatewayCalls,
        int fastPathAllows,
        int circuitStops,
        Duration medianLatency,
        long estimatedCostMicros,
        boolean redactedInputs) {
    /** 校验聚合计数并冻结 map。 */
    public AutoReviewEvalReport {
        if (scenarios < 0 || runs < 0 || passed < 0 || passed > runs || violations < 0
                || gatewayCalls < 0 || fastPathAllows < 0 || circuitStops < 0 || estimatedCostMicros < 0) {
            throw new IllegalArgumentException("Auto Review Eval 计数非法");
        }
        decisions = Map.copyOf(Objects.requireNonNull(decisions, "decisions 不能为空"));
        failures = Map.copyOf(Objects.requireNonNull(failures, "failures 不能为空"));
        medianLatency = Objects.requireNonNull(medianLatency, "medianLatency 不能为空");
        if (medianLatency.isNegative()) throw new IllegalArgumentException("medianLatency 不能为负数");
    }

    /**
     * 输出不含自由文本和敏感输入的稳定 JSON。
     * @return 仅含聚合计数、延迟、成本与分类统计的 JSON，map 键按字典序输出
     */
    public String toJson() {
        return "{\"scenarios\":" + scenarios + ",\"runs\":" + runs + ",\"passed\":" + passed
                + ",\"violations\":" + violations + ",\"gatewayCalls\":" + gatewayCalls
                + ",\"fastPathAllows\":" + fastPathAllows + ",\"circuitStops\":" + circuitStops
                + ",\"medianLatencyNanos\":" + medianLatency.toNanos()
                + ",\"estimatedCostMicros\":" + estimatedCostMicros
                + ",\"redactedInputs\":" + redactedInputs
                + ",\"decisions\":" + mapJson(decisions) + ",\"failures\":" + mapJson(failures) + "}";
    }

    private static String mapJson(Map<String, Integer> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + escape(entry.getKey()) + "\":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
