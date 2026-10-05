package io.github.liumaishenjian.ccjava.core;

/**
 * 声明模型窗口收窄后，固定输出保留与安全余量已耗尽输入预算。
 *
 * <p>调用方应提示用户选择更大窗口模型，或由用户显式调整 Context 参数；
 * 不得削减保留空间、绕过 Context 或重试模型请求。异常只携带固定分类文案，
 * 不接收模型标识、用户输入或底层异常，适用于模型请求前的绑定拒绝。</p>
 */
public final class ModelContextBudgetException extends IllegalArgumentException {
    /** 创建不包含动态数据或原始 cause 的预算不相容分类。 */
    public ModelContextBudgetException() {
        super("Model context window cannot accommodate reserved context budget");
    }
}
