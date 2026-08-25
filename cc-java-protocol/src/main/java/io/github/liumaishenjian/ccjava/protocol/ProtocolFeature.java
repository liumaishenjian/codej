package io.github.liumaishenjian.ccjava.protocol;

/** Initialize 可协商的稳定能力。 */
public enum ProtocolFeature {
    /** 提交 Agent Run。 */
    RUN,
    /** 取消活动 Run。 */
    CANCEL,
    /** 恢复既有 Session。 */
    SESSION_RESUME,
    /** 导出 Session metadata 或经确认脱敏的正文。 */
    SESSION_EXPORT,
    /** 归档或二次确认永久删除 Session。 */
    SESSION_RETENTION,
    /** 迁移 canonical Session schema。 */
    SESSION_MIGRATION,
    /** 分页列出和搜索 Session metadata index。 */
    SESSION_INDEX,
    /** 查询 Managed Policy 与 Feature Gate 投影。 */
    GOVERNANCE,
    /** 使用 Session Checkpoint 能力。 */
    CHECKPOINT,
    /** 通过独立 stable daemon 进程承载协议。 */
    DAEMON,
    /** 读取与 Run Tool 事件关联的 Session-local Task List v1 投影。 */
    TASK_LIST_V1("task-list-v1"),
    /** 协商实验 Feature Gate 元数据，不改变 stable schema。 */
    EXPERIMENTAL_FEATURE_GATES;

    private final String wireName;

    ProtocolFeature() { this.wireName = name(); }
    ProtocolFeature(String wireName) { this.wireName = wireName; }

    /** 返回 initialize wire 上的稳定 capability 名称。 */
    public String wireName() { return wireName; }

    /** 按稳定 wire 名称解析，不把 Java enum 名泄漏为新协议。 */
    public static ProtocolFeature fromWireName(String value) {
        for (ProtocolFeature feature : values()) {
            if (feature.wireName.equals(value)) return feature;
        }
        throw new IllegalArgumentException("未知 Protocol feature");
    }
}
