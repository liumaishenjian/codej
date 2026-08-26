package io.github.liumaishenjian.ccjava.domain;

/**
 * Runtime 因调用方显式总量限制而终止的稳定原因。
 *
 * @since 0.15.0
 */
public enum BudgetGovernanceReason {
    /** 达到调用方明确提供的总模型回合或总 Tool Call 硬上限。 */ EXPLICIT_LIMIT
}
