package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanVerificationSkipDecision;

/**
 * 把 verification skip 的显式用户决定与 Runtime 的确定性消费逻辑隔离。
 *
 * <p>只有可信 Composition Root 可以安装实现；模型、Tool 参数和普通 Java 字符串都不能
 * 直接签发能力。端口只回答单次提议是否确由用户批准，不写 Evidence Ledger。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface PlanVerificationSkipCoordinator {
    /**
     * 确认用户是否批准精确绑定的 skip 提议。
     *
     * @param proposed Runtime 生成且尚未登记的一次性提议
     * @return 仅当用户显式批准该完整绑定时返回 {@code true}
     */
    boolean approve(PlanVerificationSkipDecision proposed);

    /**
     * 返回拒绝所有提议的安全默认实现。
     * @return 不签发授权且始终返回 false 的协调器
     */
    static PlanVerificationSkipCoordinator unavailable() {
        return ignored -> false;
    }
}
