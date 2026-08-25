package io.github.liumaishenjian.ccjava.domain.command;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionSelection;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.List;
import java.util.Objects;

/**
 * 一次命令请求的唯一终态安全事件。
 *
 * @param kind 已分派命令类别
 * @param commandId 请求关联标识
 * @param sessionId 处理时的当前会话标识
 * @param status 终态分类
 * @param code 固定终态代码
 * @param payload 白名单投影
 * @since 0.8.0
 */
public record SessionCommandEvent(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                  SessionCommandStatus status, SessionCommandResultCode code,
                                  SessionCommandPayload payload) {
    /** 冻结终态事件的安全组件。 */
    public SessionCommandEvent {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        commandId = Objects.requireNonNull(commandId, "commandId 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        code = Objects.requireNonNull(code, "code 不能为空");
        payload = Objects.requireNonNull(payload, "payload 不能为空");
        requireStatusCode(status, code);
    }

    /** 命令 Event 的封闭白名单 payload。 */
    public sealed interface SessionCommandPayload permits EmptyPayload, HelpPayload, ContextPayload, DoctorPayload,
            PermissionsPayload, ResumePayload, TaskListPayload, PlanPayload { }

    /**
     * TUI 使用的有界 Task Board 展示投影。
     *
     * <p>该 payload 不包含 description、metadata、claim Run 或时间戳；最多 50 项，避免把
     * canonical Board 全量复制进 stdio 单行。{@code truncated} 明确表示仍有未展示任务。</p>
     *
     * @param boardRevision 当前 Board revision
     * @param totalTasks 当前可见任务总数
     * @param truncated 是否因展示上限省略任务
     * @param tasks 稳定 TaskId 顺序的安全摘要
     */
    public record TaskListPayload(long boardRevision, int totalTasks, boolean truncated,
                                  List<TaskView> tasks) implements SessionCommandPayload {
        public TaskListPayload {
            if (boardRevision < 0 || totalTasks < 0) throw new IllegalArgumentException("Task 计数无效");
            tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks 不能为空"));
            if (tasks.size() > 50 || totalTasks < tasks.size()) throw new IllegalArgumentException("Task 投影上限无效");
        }
    }

    /**
     * 单个 Task 的 TUI 安全摘要。
     *
     * @param taskId Task 标识
     * @param revision Task revision
     * @param status 公开状态
     * @param subject 用户可见摘要
     * @param blocked 是否存在未完成依赖
     * @param blockerIds 未完成依赖 ID
     * @param owner 可选 owner
     * @param activeForm 可选当前动作短语
     * @param recoveryRequired 是否必须显式恢复 claim
     */
    public record TaskView(String taskId, long revision, String status, String subject,
                           boolean blocked, List<String> blockerIds, String owner,
                           String activeForm, boolean recoveryRequired) {
        public TaskView {
            taskId = boundedSafeId(taskId);
            if (revision < 1) throw new IllegalArgumentException("Task revision 无效");
            status = boundedEnum(status, "status");
            subject = boundedText(subject, "subject", 200);
            blockerIds = List.copyOf(Objects.requireNonNull(blockerIds, "blockerIds 不能为空"));
            if (blockerIds.size() > 32) throw new IllegalArgumentException("Task blocker 过多");
            blockerIds = blockerIds.stream().map(SessionCommandEvent::boundedSafeId).toList();
            owner = owner == null ? null : boundedSafeId(owner);
            activeForm = activeForm == null ? null : boundedText(activeForm, "activeForm", 200);
        }
    }

    /**
     * 项目计划的有界状态投影；不包含工具参数或 Transcript。
     *
     * @param planId Plan 标识
     * @param status Plan 状态
     * @param approvalGate 审批 Gate
     * @param nextStep 下一步骤序号
     * @param activeStep 活动步骤序号
     * @param objective 规划目标
     * @param steps 有界步骤投影
     * @param workspaceDigest 工作区摘要
     */
    public record PlanPayload(String planId, String status, String approvalGate, Integer nextStep,
                              Integer activeStep, String objective, List<PlanStepView> steps,
                              String workspaceDigest) implements SessionCommandPayload {
        public PlanPayload {
            planId = boundedSafeId(planId);
            status = boundedEnum(status, "status");
            approvalGate = boundedEnum(approvalGate, "approvalGate");
            objective = boundedText(objective, "objective", 8_000);
            workspaceDigest = boundedSafeId(workspaceDigest);
            steps = List.copyOf(Objects.requireNonNull(steps, "steps 不能为空"));
            if (steps.size() > 128) throw new IllegalArgumentException("steps 过多");
        }
    }

    /**
     * 单个计划步骤的安全展示。
     *
     * @param ordinal 步骤序号
     * @param title 标题
     * @param detail 有界详情
     * @param expectedDigest 预期工作区摘要
     */
    public record PlanStepView(int ordinal, String title, String detail, String expectedDigest) {
        public PlanStepView {
            if (ordinal < 1) throw new IllegalArgumentException("ordinal 非法");
            title = boundedText(title, "title", 200);
            detail = boundedText(detail, "detail", 8_000);
            expectedDigest = boundedSafeId(expectedDigest);
        }
    }

    /** 无额外数据的安全确认。 */
    public record EmptyPayload() implements SessionCommandPayload { }

    /**
     * Resume 成功后的最小会话切换投影。
     *
     * @param previousSessionId 被替换的旧 Session ID
     * @param resumedSessionId 已通过 S06 Gate 且成为当前 Session 的 ID
     */
    public record ResumePayload(String previousSessionId, String resumedSessionId) implements SessionCommandPayload {
        /** 验证不包含路径、历史正文或恢复诊断的稳定 Session 标识。 */
        public ResumePayload {
            previousSessionId = boundedSessionId(previousSessionId, "previousSessionId");
            resumedSessionId = boundedSessionId(resumedSessionId, "resumedSessionId");
            if (previousSessionId.equals(resumedSessionId)) {
                throw new IllegalArgumentException("Resume 前后 Session 不能相同");
            }
        }
    }

    /**
     * 当前命令的可用与延期状态。
     *
     * @param commands 每个封闭命令类别的静态支持状态
     */
    public record HelpPayload(List<CommandAvailability> commands) implements SessionCommandPayload {
        /**
         * 冻结命令可达性列表。
         *
         * @param commands 命令支持状态
         */
        public HelpPayload { commands = List.copyOf(Objects.requireNonNull(commands, "commands 不能为空")); }
    }

    /**
     * 一个命令的静态支持状态。
     *
     * @param kind 命令类别
     * @param support 当前切片中的支持等级
     */
    public record CommandAvailability(SessionCommandKind kind, CommandSupport support) {
        /**
         * 验证命令支持状态。
         *
         * @param kind 命令类别
         * @param support 支持等级
         */
        public CommandAvailability {
            kind = Objects.requireNonNull(kind, "kind 不能为空");
            support = Objects.requireNonNull(support, "support 不能为空");
        }
    }

    /** 命令可达性分类。 */
    public enum CommandSupport {
        /** 命令可在当前 Java Application 切片中执行。 */
        AVAILABLE,
        /** 命令需要后续安全 adapter 或 Surface。 */
        DEFERRED,
        /** 当前不存在可安全调用的实现。 */
        NOT_AVAILABLE
    }

    /**
     * 已发布权限设置的无 selector 安全投影。
     *
     * @param effectiveMode 当前 Runtime 实际使用的 S05 PermissionMode 枚举名
     * @param effectiveReviewer 最终 ASK 的审查主体枚举名
     * @param effectiveSelection 面向 Surface 的安全选择枚举名；ACCEPT_EDITS 固定投影为 ADVANCED
     * @param modeSourceKind 有效 mode 的安全来源类别，未由 Settings 提供时为 BASELINE
     * @param modeSafeSourceId 有效 mode 的安全来源标识，基线时为 runtime-baseline
     * @param modeValidationStatus 来源校验状态，基线时为 BASELINE
     * @param startupRuleCount 仅 Settings 派生的最终 STARTUP 规则总数，不表示全部 Runtime 规则
     * @param rules 每项仅包含规则稳定 ID 和 Settings 来源安全投影
     */
    public record PermissionsPayload(String effectiveMode, String effectiveReviewer, String effectiveSelection,
                                     String modeSourceKind, String modeSafeSourceId, String modeValidationStatus,
                                     int startupRuleCount, List<PermissionRuleProvenance> rules) implements SessionCommandPayload {
        /**
         * 兼容旧调用方按 mode 与 {@link ApprovalReviewer#USER} 推导 reviewer 和安全选择。
         *
         * @param effectiveMode 当前 Runtime 实际使用的 S05 PermissionMode 枚举名
         * @param modeSourceKind 有效 mode 的安全来源类别
         * @param modeSafeSourceId 有效 mode 的安全来源标识
         * @param modeValidationStatus 来源校验状态
         * @param startupRuleCount Settings 派生的最终 STARTUP 规则总数
         * @param rules 不含 selector 的规则来源投影
         */
        public PermissionsPayload(String effectiveMode, String modeSourceKind, String modeSafeSourceId,
                                  String modeValidationStatus, int startupRuleCount,
                                  List<PermissionRuleProvenance> rules) {
            this(effectiveMode, ApprovalReviewer.USER.name(), legacySelection(effectiveMode), modeSourceKind,
                    modeSafeSourceId, modeValidationStatus, startupRuleCount, rules);
        }

        /** 验证并冻结不含规则正文的权限投影。 */
        public PermissionsPayload {
            effectiveMode = boundedEnum(effectiveMode, "effectiveMode");
            effectiveReviewer = boundedEnum(effectiveReviewer, "effectiveReviewer");
            effectiveSelection = boundedEnum(effectiveSelection, "effectiveSelection");
            requireEffectivePermissionState(effectiveMode, effectiveReviewer, effectiveSelection);
            modeSourceKind = boundedEnum(modeSourceKind, "modeSourceKind");
            modeSafeSourceId = boundedSafeId(modeSafeSourceId);
            modeValidationStatus = boundedEnum(modeValidationStatus, "modeValidationStatus");
            if (startupRuleCount < 0 || startupRuleCount > 128) throw new IllegalArgumentException("startupRuleCount 非法");
            rules = List.copyOf(Objects.requireNonNull(rules, "rules 不能为空"));
            if (rules.size() != startupRuleCount) throw new IllegalArgumentException("rules 数量不一致");
        }
    }

    /**
     * 单条最终规则的来源安全投影，不携带 selector 或 Tool 参数。
     *
     * @param ruleId 稳定规则标识
     * @param sourceKind 来源类别枚举名
     * @param safeSourceId 不含路径的来源标识
     * @param operation provenance 操作枚举名
     * @param validationStatus provenance 校验状态枚举名
     */
    public record PermissionRuleProvenance(String ruleId, String sourceKind, String safeSourceId,
                                           String operation, String validationStatus) {
        /** 验证有界 provenance 投影。 */
        public PermissionRuleProvenance {
            ruleId = boundedRuleId(ruleId);
            sourceKind = boundedEnum(sourceKind, "sourceKind");
            safeSourceId = boundedSafeId(safeSourceId);
            operation = boundedEnum(operation, "operation");
            validationStatus = boundedEnum(validationStatus, "validationStatus");
        }
    }

    /**
     * Context Usage 的数值和枚举投影。
     *
     * @param systemTokens system token 估计值
     * @param transcriptTokens transcript token 估计值
     * @param toolTokens tool token 估计值
     * @param memoryTokens memory token 估计值
     * @param totalTokens 总 token 估计值
     * @param availableInputTokens 输入预算
     * @param freeTokens 剩余预算
     * @param overflowTokens 超出预算的 token 数
     * @param sourceRevision 已发布 source revision
     * @param estimateKind 估计方式枚举名
     * @param status preparation 状态枚举名
     * @param reductionStrategies 已应用压缩策略枚举名
     * @param reasonCodes 固定原因代码枚举名
     * @param modelRequestAttempts 模型请求次数
     */
    public record ContextPayload(long systemTokens, long transcriptTokens, long toolTokens, long memoryTokens,
                                 long totalTokens, long availableInputTokens, long freeTokens, long overflowTokens,
                                 long sourceRevision, String estimateKind, String status,
                                 List<String> reductionStrategies, List<String> reasonCodes, int modelRequestAttempts)
            implements SessionCommandPayload {
        /** 验证白名单 Context 投影的数值和枚举。 */
        public ContextPayload {
            if (systemTokens < 0 || transcriptTokens < 0 || toolTokens < 0 || memoryTokens < 0 || totalTokens < 0
                    || availableInputTokens <= 0 || overflowTokens < 0 || sourceRevision < 0 || modelRequestAttempts < 0) {
                throw new IllegalArgumentException("context payload 数值非法");
            }
            estimateKind = boundedEnum(estimateKind, "estimateKind");
            status = boundedEnum(status, "status");
            reductionStrategies = boundedEnums(reductionStrategies, "reductionStrategies");
            reasonCodes = boundedEnums(reasonCodes, "reasonCodes");
        }
    }

    /**
     * Doctor 的来源与状态白名单投影。
     *
     * @param settingsAvailable 是否已有设置 LKG
     * @param settingsRevision 已发布设置 revision
     * @param instructionCount 已发布指令来源数
     * @param contextAvailable 是否已有 Context Usage
     * @param activeRun 是否存在活动 Run
     * @param entries 固定来源和状态条目
     */
    public record DoctorPayload(boolean settingsAvailable, long settingsRevision, int instructionCount,
                                boolean contextAvailable, boolean activeRun, List<DoctorEntry> entries)
            implements SessionCommandPayload {
        /** 验证并冻结 doctor 安全投影。 */
        public DoctorPayload {
            if (settingsRevision < 0 || instructionCount < 0) throw new IllegalArgumentException("doctor 数值非法");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries 不能为空"));
            if (entries.size() > 128) throw new IllegalArgumentException("doctor entries 过多");
        }
    }

    /**
     * 不含正文或物理路径的 doctor 条目。
     *
     * @param component 固定组件类别
     * @param sourceKind 固定来源类别
     * @param safeId 非绝对路径的安全来源标识
     * @param code 固定状态代码
     * @param severity 固定严重程度
     */
    public record DoctorEntry(String component, String sourceKind, String safeId, String code, String severity) {
        /** 验证 doctor 条目的白名单组件。 */
        public DoctorEntry {
            component = boundedEnum(component, "component");
            sourceKind = boundedEnum(sourceKind, "sourceKind");
            safeId = boundedSafeId(safeId);
            code = boundedEnum(code, "code");
            severity = boundedEnum(severity, "severity");
        }
    }

    private static String legacySelection(String effectiveMode) {
        PermissionMode mode = PermissionMode.valueOf(boundedEnum(effectiveMode, "effectiveMode"));
        return switch (mode) {
            case PLAN -> PermissionSelection.PLAN.name();
            case DEFAULT -> PermissionSelection.ASK.name();
            case ACCEPT_EDITS -> "ADVANCED";
        };
    }

    private static void requireEffectivePermissionState(String effectiveMode, String effectiveReviewer,
                                                        String effectiveSelection) {
        PermissionMode mode = PermissionMode.valueOf(effectiveMode);
        ApprovalReviewer reviewer = ApprovalReviewer.valueOf(effectiveReviewer);
        if ("ADVANCED".equals(effectiveSelection)) {
            if (mode != PermissionMode.ACCEPT_EDITS || reviewer != ApprovalReviewer.USER) {
                throw new IllegalArgumentException("effective permission state 非法");
            }
            return;
        }
        PermissionSelection selection;
        try {
            selection = PermissionSelection.valueOf(effectiveSelection);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("effectiveSelection 非法", exception);
        }
        if (mode != selection.mode() || reviewer != selection.reviewer()) {
            throw new IllegalArgumentException("effective permission state 非法");
        }
    }

    private static void requireStatusCode(SessionCommandStatus status, SessionCommandResultCode code) {
        boolean valid = switch (status) {
            case SUCCEEDED -> code == SessionCommandResultCode.OK;
            case CANCELLED -> code == SessionCommandResultCode.CANCELLED;
            case FAILED -> code == SessionCommandResultCode.INTERNAL_FAILURE;
            case REJECTED -> code != SessionCommandResultCode.OK
                    && code != SessionCommandResultCode.CANCELLED
                    && code != SessionCommandResultCode.INTERNAL_FAILURE;
        };
        if (!valid) throw new IllegalArgumentException("终态 status 与 code 不匹配");
    }

    private static String boundedEnum(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 64 || !value.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(name + " 非法");
        }
        return value;
    }

    private static String boundedRuleId(String value) {
        if (value == null || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || value.length() > 64) {
            throw new IllegalArgumentException("ruleId 非法");
        }
        return value;
    }

    private static String boundedSessionId(String value, String name) {
        if (value == null || value.length() > 128 || !value.matches("session-[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException(name + " 非法");
        }
        return value;
    }

    private static String boundedText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(name + " 非法");
        return value;
    }

    private static String boundedSafeId(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || value.indexOf('\\') >= 0
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("safeId 非法");
        return value;
    }

    private static List<String> boundedEnums(List<String> values, String name) {
        values = List.copyOf(Objects.requireNonNull(values, name + " 不能为空"));
        if (values.size() > 32) throw new IllegalArgumentException(name + " 过多");
        for (String value : values) boundedEnum(value, name);
        return values;
    }
}
