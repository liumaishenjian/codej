package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.UserQuestionAnswer;
import io.github.liumaishenjian.ccjava.domain.UserQuestionOption;
import io.github.liumaishenjian.ccjava.domain.UserQuestionRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在持续规划 Run 内提出结构化单选问题，并以 callId 恢复同一模型/工具循环。
 *
 * <p>Tool 输入经过确定性 schema 约束后才投影为用户可见问题。Surface 只看到 question 与
 * options，不看到原始 JSON；返回结果只含所选 optionId，不接受自由文本或 y/n 解析。</p>
 *
 * @since 0.1.0
 */
public final class PlanAskUserTool implements AgentTool {
    /** 供模型调用的独立稳定名称。 */
    public static final String NAME = "ask_plan_question";
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Ask one necessary planning question with two to four mutually exclusive options. Use this only when the answer materially changes the plan.",
            """
            {"type":"object","additionalProperties":false,"required":["question","options"],"properties":{"question":{"type":"string","minLength":1,"maxLength":1000},"options":{"type":"array","minItems":2,"maxItems":4,"items":{"type":"object","additionalProperties":false,"required":["optionId","label","description"],"properties":{"optionId":{"type":"string","minLength":1,"maxLength":64},"label":{"type":"string","minLength":1,"maxLength":120},"description":{"type":"string","minLength":1,"maxLength":500}}}}}}
            """,
            ToolEffect.USER_INTERACTION, ToolSource.BUILT_IN, true,
            Duration.ofMinutes(30), "text/plain", 256,
            Set.of(PlanToolCapability.USER_QUESTION));

    private final UserQuestionHandler questions;

    /**
     * 绑定当前 Surface 的结构化交互端口。
     * @param questions 非空的用户回答端口，执行时传播取消令牌
     */
    public PlanAskUserTool(UserQuestionHandler questions) {
        this.questions = Objects.requireNonNull(questions, "questions 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            if (!arguments.values().keySet().equals(Set.of("question", "options"))) {
                return ToolValidationResult.invalid("字段集合无效");
            }
            decode("validation-call", arguments);
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("结构化问题参数无效");
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        UserQuestionRequest request = decode(invocation.call().id(), invocation.call().arguments());
        UserQuestionAnswer answer = Objects.requireNonNull(
                questions.ask(request, invocation.cancellationToken()), "UserQuestionHandler 返回 null");
        if (!answer.callId().equals(request.callId())
                || request.options().stream().noneMatch(option -> option.optionId().equals(answer.optionId()))) {
            throw new IllegalStateException("结构化问题答案与待决请求不匹配");
        }
        return ToolExecutionOutcome.success("User selected option: " + answer.optionId());
    }

    private static UserQuestionRequest decode(String callId, JsonObject arguments) {
        String question = arguments.string("question").orElseThrow();
        Object rawOptions = arguments.values().get("options");
        if (!(rawOptions instanceof List<?> values)) throw new IllegalArgumentException("options 不是数组");
        ArrayList<UserQuestionOption> options = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw) || raw.size() != 3
                    || !(raw.get("optionId") instanceof String id)
                    || !(raw.get("label") instanceof String label)
                    || !(raw.get("description") instanceof String description)) {
                throw new IllegalArgumentException("option 无效");
            }
            options.add(new UserQuestionOption(id, label, description));
        }
        return new UserQuestionRequest(callId, question, options);
    }
}
