package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.*;
import java.time.Duration;
import java.util.*;

/**
 * 普通对话和规划共享的整批用户提问工具。
 * <p>只通过当前 Surface 等待用户答案，不执行文件操作；结果继续使用原始 Tool Call ID。</p>
 * @since 0.1.0
 */
public final class AskUserQuestionsTool implements AgentTool {
    /** 协商式问卷工具的稳定名称。 */
    public static final String NAME = "ask_user_questions";
    private final UserQuestionHandler handler;
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Ask necessary questions together. Each question may offer single or multiple choices and optional free text. Wait for the user's reviewed answers.",
            """
            {"type":"object","additionalProperties":false,"required":["questions"],"properties":{"questions":{"type":"array","minItems":1,"maxItems":4,"items":{"type":"object","additionalProperties":false,"required":["id","title","question","multiSelect","allowFreeText","options"],"properties":{"id":{"type":"string","minLength":1,"maxLength":64},"title":{"type":"string","minLength":1,"maxLength":120},"question":{"type":"string","minLength":1,"maxLength":1000},"multiSelect":{"type":"boolean"},"allowFreeText":{"type":"boolean"},"options":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,"required":["optionId","label","description"],"properties":{"optionId":{"type":"string","minLength":1,"maxLength":64},"label":{"type":"string","minLength":1,"maxLength":120},"description":{"type":"string","minLength":1,"maxLength":500}}}}}}}}}
            """, ToolEffect.USER_INTERACTION, ToolSource.BUILT_IN, true, Duration.ofMinutes(30),
            "text/plain", 32768, Set.of(PlanToolCapability.USER_QUESTION));
    /** 绑定当前连接的用户交互端口。
     * @param handler 当前运行的问卷交互端口
     */
    public AskUserQuestionsTool(UserQuestionHandler handler) { this.handler = Objects.requireNonNull(handler); }
    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public ToolValidationResult validate(JsonObject arguments) {
        try { decode("validation", arguments); return ToolValidationResult.validResult(); }
        catch (RuntimeException invalid) { return ToolValidationResult.invalid("问卷参数无效"); }
    }
    @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
        var request = decode(invocation.call().id(), invocation.call().arguments());
        var answer = handler.ask(request, invocation.cancellationToken());
        if (answer == null || !request.accepts(answer)) throw new IllegalStateException("问卷答案与请求不匹配");
        var result = new StringBuilder("用户确认的问卷答案：\n");
        for (var q : request.questions()) {
            var a = answer.answers().stream().filter(v -> v.questionId().equals(q.id())).findFirst().orElseThrow();
            result.append(q.id()).append(": ").append(q.question()).append('\n');
            for (var id : a.optionIds()) result.append("- ").append(id).append(": ")
                    .append(q.options().stream().filter(o -> o.optionId().equals(id)).findFirst().orElseThrow().label()).append('\n');
            if (!a.freeText().isEmpty()) result.append("用户文字：").append(a.freeText()).append('\n');
        }
        return ToolExecutionOutcome.success(result.toString());
    }
    private static UserQuestionRequest decode(String callId, JsonObject input) {
        if (!input.values().keySet().equals(Set.of("questions")) || !(input.values().get("questions") instanceof List<?> raw)
                || raw.isEmpty() || raw.size() > 4) throw new IllegalArgumentException("题目数组无效");
        var questions = new ArrayList<UserQuestionItem>();
        for (var value : raw) {
            if (!(value instanceof Map<?, ?> q) || !q.keySet().equals(Set.of("id", "title", "question", "multiSelect", "options", "allowFreeText"))
                    || !(q.get("options") instanceof List<?> choices) || !(q.get("multiSelect") instanceof Boolean multi)
                    || !(q.get("allowFreeText") instanceof Boolean text)) throw new IllegalArgumentException("题目字段无效");
            var options = new ArrayList<UserQuestionOption>();
            for (var choice : choices) {
                if (!(choice instanceof Map<?, ?> o) || !o.keySet().equals(Set.of("optionId", "label", "description"))) throw new IllegalArgumentException("选项字段无效");
                options.add(new UserQuestionOption((String)o.get("optionId"), (String)o.get("label"), (String)o.get("description")));
            }
            questions.add(new UserQuestionItem((String)q.get("id"), (String)q.get("title"), (String)q.get("question"), multi, options, text));
        }
        return new UserQuestionRequest(callId, questions);
    }
}
