package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 表示 Session、Run、Model Turn、Permission 和 Tool Pipeline 的内部生命周期点。
 *
 * <p>事件只能用于观察和测试，S01 不提供用户 Hook DSL，也不允许观察者改变
 * Runtime 决策。</p>
 *
 * @since 0.1.0
 */
public sealed interface LifecycleEvent extends AgentEvent
        permits LifecycleEvent.SessionStarted,
                LifecycleEvent.SessionEnded,
                LifecycleEvent.RunStarted,
                LifecycleEvent.ModelTurnStarted,
                LifecycleEvent.ModelAttemptStarted,
                LifecycleEvent.ModelRetryScheduled,
                LifecycleEvent.ModelTurnCompleted,
                LifecycleEvent.BeforeTool,
                LifecycleEvent.PermissionEvaluationStarted,
                LifecycleEvent.PermissionEvaluated,
                LifecycleEvent.ApprovalRequested,
                LifecycleEvent.PermissionDecided,
                LifecycleEvent.ToolOutput,
                LifecycleEvent.AfterTool,
                LifecycleEvent.BudgetGoverned,
                LifecycleEvent.PlanVerificationCorrectionRequested,
                LifecycleEvent.RunFinished {

    /**
     * Session 已在内存 Store 中创建。
     *
     * @param spec 创建 Session 时的稳定配置
     */
    record SessionStarted(SessionSpec spec) implements LifecycleEvent {

        /**
         * 创建 Session 启动事件。
         *
         * @param spec Session 的稳定配置
         * @throws NullPointerException 配置为空时
         */
        public SessionStarted {
            spec = Objects.requireNonNull(spec, "spec 不能为空");
        }
    }

    /**
     * Session 已显式关闭。
     */
    record SessionEnded() implements LifecycleEvent {
    }

    /**
     * Run 已接受用户消息并开始执行。
     *
     * @param request 本次 Run 的不可变请求
     */
    record RunStarted(AgentRunRequest request) implements LifecycleEvent {

        /**
         * 创建 Run 启动事件。
         *
         * @param request 本次 Run 的不可变请求
         * @throws NullPointerException 请求为空时
         */
        public RunStarted {
            request = Objects.requireNonNull(request, "request 不能为空");
        }
    }

    /**
     * Runtime 即将请求一个模型回合。
     *
     * @param turnNumber 从 1 开始的模型回合序号
     */
    record ModelTurnStarted(int turnNumber) implements LifecycleEvent {

        /**
         * 校验回合序号后创建模型回合启动事件。
         *
         * @param turnNumber 从 1 开始的模型回合序号
         * @throws IllegalArgumentException 回合序号小于 1 时
         */
        public ModelTurnStarted {
            if (turnNumber < 1) {
                throw new IllegalArgumentException("turnNumber 必须从 1 开始");
            }
        }
    }

    /**
     * 一个实际 Provider attempt 即将开始。
     *
     * @param turnNumber 当前模型回合序号
     * @param attempt 当前从 1 开始的实际请求序号
     * @param maxAttempts 本回合最大请求数
     */
    record ModelAttemptStarted(int turnNumber, int attempt, int maxAttempts)
            implements LifecycleEvent {
        /** 校验回合与 attempt 计数。 */
        public ModelAttemptStarted {
            if (turnNumber < 1 || attempt < 1 || maxAttempts < attempt || maxAttempts > 100) {
                throw new IllegalArgumentException("模型 attempt 计数非法");
            }
        }
    }

    /**
     * 瞬时模型失败已安排下一次 attempt。
     *
     * @param turnNumber 当前模型回合序号
     * @param failedAttempt 已失败的请求序号
     * @param nextAttempt 下一次请求序号
     * @param maxAttempts 本回合最大请求数
     * @param waitMillis 实际有界等待毫秒数
     * @param category 隐私安全失败类别
     */
    record ModelRetryScheduled(
            int turnNumber,
            int failedAttempt,
            int nextAttempt,
            int maxAttempts,
            long waitMillis,
            ModelFailureCategory category) implements LifecycleEvent {
        /** 校验只包含固定分类和有界计数。 */
        public ModelRetryScheduled {
            if (turnNumber < 1 || failedAttempt < 1 || nextAttempt != failedAttempt + 1
                    || maxAttempts < nextAttempt || maxAttempts > 100
                    || waitMillis < 0 || waitMillis > java.time.Duration.ofMinutes(5).toMillis()) {
                throw new IllegalArgumentException("模型重试摘要非法");
            }
            category = Objects.requireNonNull(category, "category 不能为空");
        }
    }

    /**
     * 一个完整模型回合已经聚合完成。
     *
     * @param turnNumber 模型回合序号
     * @param turn       聚合后的响应
     */
    record ModelTurnCompleted(int turnNumber, ModelTurn turn) implements LifecycleEvent {

        /**
         * 校验回合信息后创建模型回合完成事件。
         *
         * @param turnNumber 从 1 开始的模型回合序号
         * @param turn 聚合后的模型响应
         * @throws NullPointerException 模型响应为空时
         * @throws IllegalArgumentException 回合序号小于 1 时
         */
        public ModelTurnCompleted {
            if (turnNumber < 1) {
                throw new IllegalArgumentException("turnNumber 必须从 1 开始");
            }
            turn = Objects.requireNonNull(turn, "turn 不能为空");
        }
    }

    /**
     * 单个 Tool Call 即将进入权限与执行管线。
     *
     * @param ordinal 本次 Run 内从 1 开始的 Tool Call 序号
     * @param call    原始 Tool Call
     */
    record BeforeTool(int ordinal, ToolCall call) implements LifecycleEvent {

        /**
         * 校验调用序号后创建 Tool 执行前事件。
         *
         * @param ordinal 本次 Run 内从 1 开始的 Tool Call 序号
         * @param call 原始 Tool Call
         * @throws NullPointerException Tool Call 为空时
         * @throws IllegalArgumentException 调用序号小于 1 时
         */
        public BeforeTool {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            call = Objects.requireNonNull(call, "call 不能为空");
        }
    }

    /**
     * Permission 生命周期可公开观察的隐私安全调用摘要。
     *
     * <p>该摘要刻意不保存 {@link ToolCall}、参数或完整 {@link PermissionSelector}。
     * 它只关联稳定 Call ID、Tool 名称和可信 Effect，使内部事件、终端投影和未来
     * 观察者不能通过 record accessor 或 {@code toString()} 取得命令、文件正文或 Secret。</p>
     *
     * @param callId Provider 生成的稳定 Call ID
     * @param toolName 已注册 Tool 名称
     * @param effect Tool Definition 声明的可信副作用
     */
    record PermissionCallSummary(String callId, String toolName, ToolEffect effect) {

        /** 校验公开摘要只含稳定关联字段。 */
        public PermissionCallSummary {
            callId = requireText(callId, "callId");
            toolName = requireText(toolName, "toolName");
            effect = Objects.requireNonNull(effect, "effect 不能为空");
        }
    }

    /**
     * Permission 生命周期可公开观察的隐私安全决定摘要。
     *
     * <p>Policy 内部仍保留完整 selector 以执行精确规则和 Session Grant；本摘要只暴露
     * 决定、固定 reason、可选规则来源、是否需要交互，以及 selector 是否为 Tool-wide，
     * 不暴露 selector value。</p>
     *
     * @param decision 初始或最终权限行为
     * @param reason 稳定、无任意文本的权限原因
     * @param ruleSource 可选可信规则来源
     * @param interactive 该阶段是否需要用户审批
     * @param scoped 是否存在非 Tool-wide 的具体范围
     */
    record PermissionDecisionSummary(
            PermissionDecision decision,
            PermissionReason reason,
            Optional<PermissionRuleSource> ruleSource,
            boolean interactive,
            boolean scoped) {

        /** 校验决定摘要只使用类型化值。 */
        public PermissionDecisionSummary {
            decision = Objects.requireNonNull(decision, "decision 不能为空");
            reason = Objects.requireNonNull(reason, "reason 不能为空");
            ruleSource = Objects.requireNonNull(ruleSource, "ruleSource 不能为空");
        }
    }

    /**
     * Pipeline 即将运行 Permission Policy Kernel。
     *
     * @param call 隐私安全调用摘要
     */
    record PermissionEvaluationStarted(PermissionCallSummary call)
            implements LifecycleEvent {
        /** 校验调用摘要。 */
        public PermissionEvaluationStarted {
            call = Objects.requireNonNull(call, "call 不能为空");
        }
    }

    /**
     * Policy Kernel 已产生初始决定摘要。
     *
     * @param call 隐私安全调用摘要
     * @param outcome 初始 Allow/Ask/Deny 摘要
     */
    record PermissionEvaluated(
            PermissionCallSummary call,
            PermissionDecisionSummary outcome) implements LifecycleEvent {
        /** 校验调用和初始权限摘要。 */
        public PermissionEvaluated {
            call = Objects.requireNonNull(call, "call 不能为空");
            outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
        }
    }

    /**
     * ASK 即将交给用户 Surface 收敛。
     *
     * @param call 隐私安全调用摘要
     * @param outcome 不含 selector value 的交互摘要
     */
    record ApprovalRequested(
            PermissionCallSummary call,
            PermissionDecisionSummary outcome) implements LifecycleEvent {
        /** 校验待审批调用和交互摘要。 */
        public ApprovalRequested {
            call = Objects.requireNonNull(call, "call 不能为空");
            outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
            if (!outcome.interactive()) {
                throw new IllegalArgumentException("审批请求必须标记为需要交互");
            }
        }
    }

    /**
     * Tool Call 已得到唯一最终权限摘要。
     *
     * @param call 隐私安全调用摘要
     * @param outcome 最终 Allow 或 Deny 摘要
     */
    record PermissionDecided(
            PermissionCallSummary call,
            PermissionDecisionSummary outcome) implements LifecycleEvent {
        /** 校验唯一最终摘要，禁止残留 ASK 或交互状态。 */
        public PermissionDecided {
            call = Objects.requireNonNull(call, "call 不能为空");
            outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
            if (outcome.decision() == PermissionDecision.ASK || outcome.interactive()) {
                throw new IllegalArgumentException("最终权限事件不能保留 ASK 或交互状态");
            }
        }
    }

    /**
     * Tool 执行期间产生的一段有界输出。
     *
     * <p>该事件会进入有界的 Session 事件序列并供当前 Run 的交互 Surface 展示，但不
     * 追加为规范消息，也不进入遥测。最终进入模型 Context 的内容仍由 Tool Result 和
     * Pipeline 上限唯一决定。</p>
     *
     * @param ordinal 本次 Run 内的 Tool Call 序号
     * @param toolName Tool 名称
     * @param stream stdout 或 stderr
     * @param text 非空且有界的文本片段
     */
    record ToolOutput(
            int ordinal,
            String toolName,
            ToolOutputStream stream,
            String text) implements LifecycleEvent {

        /** 单个事件片段的最大字符数。 */
        public static final int MAX_CHUNK_CHARACTERS = 4_096;

        /**
         * 校验关联信息和片段边界。
         */
        public ToolOutput {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            toolName = requireText(toolName, "toolName");
            stream = Objects.requireNonNull(stream, "stream 不能为空");
            text = requireText(text, "text");
            if (text.codePointCount(0, text.length()) > MAX_CHUNK_CHARACTERS) {
                throw new IllegalArgumentException("Tool 输出片段超过字符上限");
            }
        }
    }

    /**
     * 单个 Tool Call 已被规范化为 Tool Result。
     *
     * @param ordinal 本次 Run 内的 Tool Call 序号
     * @param result  规范化结果
     */
    record AfterTool(int ordinal, ToolResult result) implements LifecycleEvent {

        /**
         * 校验调用序号后创建 Tool 执行后事件。
         *
         * @param ordinal 本次 Run 内从 1 开始的 Tool Call 序号
         * @param result 规范化后的 Tool Result
         * @throws NullPointerException Tool Result 为空时
         * @throws IllegalArgumentException 调用序号小于 1 时
         */
        public AfterTool {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            result = Objects.requireNonNull(result, "result 不能为空");
        }
    }

    /**
     * Runtime 对数量预算执行的一次隐私安全治理决定。
     *
     * @param reason 治理原因
     * @param modelTurns 已消耗模型回合
     * @param toolCalls 已消耗 Tool Call
     * @param totalModelTurns 调用方提供的总模型回合硬上限；空表示未提供
     * @param totalToolCalls 调用方提供的总 Tool Call 硬上限；空表示未提供
     */
    record BudgetGoverned(BudgetGovernanceReason reason, int modelTurns, int toolCalls,
                           OptionalInt totalModelTurns, OptionalInt totalToolCalls) implements LifecycleEvent {
        /** 校验治理原因和计数。 */
        public BudgetGoverned {
            reason = Objects.requireNonNull(reason, "reason 不能为空");
            totalModelTurns = Objects.requireNonNull(totalModelTurns, "totalModelTurns 不能为空");
            totalToolCalls = Objects.requireNonNull(totalToolCalls, "totalToolCalls 不能为空");
            if (modelTurns < 0 || toolCalls < 0
                    || totalModelTurns.stream().anyMatch(value -> value < 1)
                    || totalToolCalls.stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException("预算治理计数非法");
            }
        }
    }

    /**
     * Plan 最终 prose 被暂存后，确定性生命周期 Gate 请求同一 Run 进行一次纠正 continuation。
     *
     * <p>事件只用于观察：它不执行 Tool、不重新启动 Run，也不授予任何权限。Evidence 失败来自已批准
     * requirement；Task identity 来自同一 Session Board。两者至少存在一种，Surface 不得把事件解释为完成。</p>
     *
     * @param attempt 当前纠正次数，从 1 开始
     * @param maxAttempts 本次执行允许的纠正上限
     * @param failures 当前阻止 Plan 完成的稳定 Evidence 失败列表
     * @param incompleteTaskIds 当前尚未完成的稳定 Task identity
     */
    record PlanVerificationCorrectionRequested(
            int attempt,
            int maxAttempts,
            java.util.List<PlanEvidenceCorrectionFailure> failures,
            java.util.List<String> incompleteTaskIds) implements LifecycleEvent {
        /** 校验计数与有界失败、Task identity 集合。 */
        public PlanVerificationCorrectionRequested {
            failures = java.util.List.copyOf(Objects.requireNonNull(failures, "failures 不能为空"));
            incompleteTaskIds = java.util.List.copyOf(
                    Objects.requireNonNull(incompleteTaskIds, "incompleteTaskIds 不能为空"));
            if (attempt < 1 || maxAttempts < 1 || attempt > maxAttempts
                    || (failures.isEmpty() && incompleteTaskIds.isEmpty())
                    || failures.size() > PlanEvidenceLedger.MAX_REQUIREMENTS
                    || incompleteTaskIds.size() > 256
                    || incompleteTaskIds.stream().anyMatch(id -> id == null || !id.matches("task-[1-9][0-9]*"))) {
                throw new IllegalArgumentException("Plan lifecycle correction 事件无效");
            }
        }
    }

    /**
     * Run 已进入唯一终态。
     *
     * @param result Run 终态摘要
     */
    record RunFinished(AgentRunResult result) implements LifecycleEvent {

        /**
         * 创建 Run 完成事件。
         *
         * @param result Run 的终态摘要
         * @throws NullPointerException 终态摘要为空时
         */
        public RunFinished {
            result = Objects.requireNonNull(result, "result 不能为空");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
