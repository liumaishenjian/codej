package io.github.liumaishenjian.ccjava.domain.command;

/**
 * S08 已声明的封闭 Session Command 类别。
 *
 * @since 0.8.0
 */
public enum SessionCommandKind {
    /** 输出命令可用性。 */
    HELP,
    /** 清理 Surface 瞬态状态。 */
    CLEAR,
    /** 请求执行 Context 压缩。 */
    COMPACT,
    /** 读取已发布的 Context Usage。 */
    CONTEXT,
    /** 读取隐私安全诊断投影。 */
    DOCTOR,
    /** 请求更换模型。 */
    MODEL_CHANGE,
    /** 查询或变更 Permission。 */
    PERMISSIONS,
    /** 请求恢复指定会话。 */
    RESUME,
    /** 读取当前 Session-local Task Board 的有界展示投影。 */
    TASKS,
    /** 查询当前项目计划。 */
    PLAN_STATUS,
    /** 创建或替换待审批项目计划。 */
    PLAN,
    /** 批准当前项目计划。 */
    PLAN_APPROVE,
    /** 拒绝当前项目计划。 */
    PLAN_REJECT,
    /** 开始下一个已批准计划步骤。 */
    PLAN_STEP_BEGIN,
    /** 完成当前唯一活动计划步骤。 */
    PLAN_STEP_COMPLETE,
    /** 执行当前已批准计划的剩余步骤。 */
    PLAN_EXECUTE
}
