package io.github.liumaishenjian.ccjava.domain.command;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionSelection;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.List;
import java.util.Objects;

/**
 * Surface 已解码的 Session Command 意图。
 *
 * <p>所有携带用户文本的变体只可由 Application 层消费，不能被终态事件回显。</p>
 *
 * @since 0.8.0
 */
public sealed interface SessionCommandIntent permits SessionCommandIntent.Help, SessionCommandIntent.Clear,
        SessionCommandIntent.Compact, SessionCommandIntent.Context, SessionCommandIntent.Doctor,
        SessionCommandIntent.ModelChange, SessionCommandIntent.Permissions, SessionCommandIntent.Resume,
        SessionCommandIntent.Tasks,
        SessionCommandIntent.PlanStatus, SessionCommandIntent.Plan, SessionCommandIntent.PlanApprove,
        SessionCommandIntent.PlanReject, SessionCommandIntent.PlanStepBegin, SessionCommandIntent.PlanStepComplete,
        SessionCommandIntent.PlanExecute {
    /**
     * 返回封闭命令类别。
     *
     * @return 与意图变体一致的类别
     */
    SessionCommandKind kind();

    /** 显示当前 Surface 支持及延期能力。 */
    record Help() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.HELP; }
    }

    /** 清理当前 Surface 的短生命周期交互状态。 */
    record Clear() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.CLEAR; }
    }

    /**
     * 请求既有 S07 Gate 支持的显式压缩。
     *
     * @param anchors 仅供后续安全 adapter 消费的锚点，不会被终态事件回显
     */
    record Compact(List<String> anchors) implements SessionCommandIntent {
        /**
         * 冻结有界锚点列表。
         *
         * @param anchors 未解析的压缩锚点
         */
        public Compact {
            anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors 不能为空"));
            if (anchors.size() > 16 || anchors.stream().anyMatch(SessionCommandIntent::invalidCompactAnchor)) {
                throw new IllegalArgumentException("compact anchors 非法");
            }
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.COMPACT; }
        @Override public String toString() { return "Compact[anchors=<redacted>]"; }
    }

    /** 请求最新 Context Usage 的安全投影。 */
    record Context() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.CONTEXT; }
    }

    /** 请求已发布状态的只读诊断。 */
    record Doctor() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.DOCTOR; }
    }

    /**
     * 请求下一 Run 的模型变更。
     *
     * @param modelName 仅供后续安全 provider adapter 消费的模型名，不会被终态事件回显
     */
    record ModelChange(String modelName) implements SessionCommandIntent {
        /**
         * 验证有界且无控制字符的模型名。
         *
         * @param modelName 未解析模型名
         */
        public ModelChange {
            if (invalidText(modelName)) throw new IllegalArgumentException("modelName 非法");
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.MODEL_CHANGE; }
        @Override public String toString() { return "ModelChange[modelName=<redacted>]"; }
    }

    /**
     * 请求权限安全视图或变更封闭 Permission 设置；本切片不暴露或编辑 selector/规则。
     *
     * @param operation 封闭查询、模式或选择变更
     */
    record Permissions(PermissionsOperation operation) implements SessionCommandIntent {
        /**
         * 创建不含规则文本的封闭权限请求。
         *
         * @param operation 封闭权限动作
         */
        public Permissions { operation = Objects.requireNonNull(operation, "operation 不能为空"); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PERMISSIONS; }
    }

    /**
     * 请求恢复指定 Session。
     *
     * @param sessionId 仅供后续 S06 recovery-gated adapter 消费的会话标识
     */
    /** 查询当前项目计划安全投影。 */
    record PlanStatus() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN_STATUS; }
    }

    /**
     * 创建待审批项目计划；步骤只携带受限的意图描述。
     *
     * @param objective 规划目标
     * @param steps 有界步骤输入
     * @param workspaceDigest 创建时工作区摘要
     */
    record Plan(String objective, List<PlanStepInput> steps, String workspaceDigest) implements SessionCommandIntent {
        public Plan {
            if (invalidText(objective) || steps == null || steps.isEmpty() || steps.size() > 128
                    || invalidText(workspaceDigest)) throw new IllegalArgumentException("plan 参数非法");
            steps = List.copyOf(steps);
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN; }
    }

    /**
     * 批准当前项目计划并重新校验工作区摘要。
     *
     * @param planId Surface 实际展示的 Session-owned Plan 身份；空串仅供旧内部协议兼容
     * @param workspaceDigest {@code plan.proposed} 随同该计划发布的服务端工作区摘要
     */
    record PlanApprove(String planId, String workspaceDigest) implements SessionCommandIntent {
        public PlanApprove {
            if ((planId == null || (!planId.isEmpty() && invalidText(planId))) || invalidText(workspaceDigest)) {
                throw new IllegalArgumentException("plan approval binding 非法");
            }
        }
        /** 旧内部协议兼容入口；新 Surface 必须同时绑定 planId。 */
        public PlanApprove(String workspaceDigest) { this("", workspaceDigest); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN_APPROVE; }
    }

    /**
     * 拒绝当前项目计划。
     *
     * @param planId Surface 实际展示的 Plan 身份；空串仅供旧内部协议兼容
     */
    record PlanReject(String planId) implements SessionCommandIntent {
        public PlanReject {
            if (planId == null || (!planId.isEmpty() && invalidText(planId))) {
                throw new IllegalArgumentException("planId 非法");
            }
        }
        /** 旧内部协议兼容入口。 */
        public PlanReject() { this(""); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN_REJECT; }
    }

    /**
     * 开始下一个已批准计划步骤，并重新校验有界工作区摘要。
     *
     * @param workspaceDigest 开始步骤前的工作区摘要
     */
    record PlanStepBegin(String workspaceDigest) implements SessionCommandIntent {
        public PlanStepBegin { if (invalidText(workspaceDigest)) throw new IllegalArgumentException("workspaceDigest 非法"); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN_STEP_BEGIN; }
    }

    /**
     * 完成当前唯一活动计划步骤，并携带完成后重新观察到的工作区摘要。
     *
     * @param workspaceDigest 完成步骤后的工作区摘要
     */
    record PlanStepComplete(String workspaceDigest) implements SessionCommandIntent {
        public PlanStepComplete {
            if (invalidText(workspaceDigest)) throw new IllegalArgumentException("workspaceDigest 非法");
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN_STEP_COMPLETE; }
    }

    /**
     * 执行 Surface 已核对并恢复权限后的当前批准计划。
     *
     * @param planId Surface 实际批准的 Session-owned Plan 身份
     * @param workspaceDigest 该提案发布并获批的服务端摘要
     * @param maxSteps 防止无限执行的显式上限
     */
    record PlanExecute(String planId, String workspaceDigest, int maxSteps) implements SessionCommandIntent {
        public PlanExecute {
            if (invalidText(planId) || invalidText(workspaceDigest)
                    || maxSteps < 1 || maxSteps > 128) throw new IllegalArgumentException("plan execute binding 非法");
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PLAN_EXECUTE; }
    }

    /**
     * 计划步骤的安全输入投影。
     *
     * @param ordinal 步骤序号
     * @param title 标题
     * @param detail 有界详情
     * @param expectedDigest 预期工作区摘要
     */
    record PlanStepInput(int ordinal, String title, String detail, String expectedDigest) {
        public PlanStepInput {
            if (ordinal < 1 || invalidText(title) || invalidText(detail) || invalidText(expectedDigest))
                throw new IllegalArgumentException("plan step 参数非法");
        }
    }

    record Resume(SessionId sessionId) implements SessionCommandIntent {
        /**
         * 验证恢复目标标识。
         *
         * @param sessionId 目标会话标识
         */
        public Resume { sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空"); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.RESUME; }
        @Override public String toString() { return "Resume[sessionId=<redacted>]"; }
    }

    /** 查询当前 Session 的执行期 Task List；不读取或修改 Plan。 */
    record Tasks() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.TASKS; }
    }

    /** 不解析 selector、规则或 grant 的封闭权限动作。 */
    sealed interface PermissionsOperation permits PermissionsOperation.Query, PermissionsOperation.ModeChange,
            PermissionsOperation.SelectionChange {
        /** 仅查询当前已发布安全状态。 */
        record Query() implements PermissionsOperation { }

        /**
         * 仅替换下一 Run 的 PermissionMode 默认值。
         *
         * @param mode 已封闭的 S05 PermissionMode
         */
        record ModeChange(PermissionMode mode) implements PermissionsOperation {
            /**
             * 验证已封闭的 S05 PermissionMode。
             *
             * @param mode 已封闭的 S05 PermissionMode
             */
            public ModeChange { mode = Objects.requireNonNull(mode, "mode 不能为空"); }
        }

        /**
         * 原子请求下一 Run 的 PermissionMode 与 reviewer 选择。
         *
         * @param selection 面向 Surface 的封闭 Permission 选择
         */
        record SelectionChange(PermissionSelection selection) implements PermissionsOperation {
            /**
             * 验证封闭 Permission 选择。
             *
             * @param selection 映射到既有 mode 与 reviewer 的选择
             */
            public SelectionChange { selection = Objects.requireNonNull(selection, "selection 不能为空"); }
        }
    }

    private static boolean invalidText(String value) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > 256
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean invalidCompactAnchor(String value) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > 512
                || value.chars().anyMatch(Character::isISOControl);
    }
}
