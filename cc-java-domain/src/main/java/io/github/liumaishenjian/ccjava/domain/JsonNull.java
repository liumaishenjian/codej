package io.github.liumaishenjian.ccjava.domain;

/**
 * Tool 参数中的 JSON {@code null} 字面量。
 *
 * <p>该哨兵只表达模型确实提交了 {@code null}，与字段缺失不同。具体 Tool 必须在自身
 * schema 与确定性校验中决定是否接受；Task metadata patch 仅把它解释为删除 key，
 * 其他任意嵌套结构不会因此自动获得业务语义。</p>
 *
 * @since 0.15.0
 */
public enum JsonNull {
    /** 唯一 JSON null 值。 */
    INSTANCE
}
