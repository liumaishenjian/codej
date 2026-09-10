package io.github.liumaishenjian.ccjava.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Runtime 向 Surface 发出的旧单题或整批结构化问卷。
 *
 * <p>{@code callId} 绑定原始 Tool Call；答案必须以相同 ID 返回，保证暂停后继续同一
 * Session/Run 时不会接受迟到或跨 Run 输入。旧单选构造器保持兼容，整批请求独立校验每题答案。</p>
 *
 * @param callId Tool Call 关联 ID
 * @param question 用户可见问题
 * @param options 两到四个封闭选项
 * @param questions 整批题目；旧单题请求为空列表
 * @since 0.1.0
 */
public record UserQuestionRequest(String callId, String question, List<UserQuestionOption> options, List<UserQuestionItem> questions) {
    /** 保持旧单题调用的构造兼容性。
     * @param callId 工具调用号
     * @param question 问题文本
     * @param options 两到四个选项
     */
    public UserQuestionRequest(String callId, String question, List<UserQuestionOption> options) {
        this(callId, question, options, List.of());
    }
    /** 创建整批问卷，不将题目拼接为自由文本。
     * @param callId 工具调用号
     * @param questions 完整题目列表
     */
    public UserQuestionRequest(String callId, List<UserQuestionItem> questions) {
        this(callId, "问卷", List.of(), questions);
    }
    /** 校验答案完整性、选项归属及单选互斥规则。
     * @param answer 待验证答案
     * @return 答案是否完整匹配当前请求
     */
    public boolean accepts(UserQuestionAnswer answer) {
        if (!callId.equals(answer.callId())) return false;
        if (questions.isEmpty()) return answer.answers().isEmpty()
                && options.stream().anyMatch(o -> o.optionId().equals(answer.optionId()));
        if (answer.answers().size() != questions.size()) return false;
        var seen = new HashSet<String>();
        for (var selection : answer.answers()) {
            if (!seen.add(selection.questionId())) return false;
            var item = questions.stream().filter(q -> q.id().equals(selection.questionId())).findFirst();
            if (item.isEmpty()) return false;
            var q = item.orElseThrow();
            boolean text = !selection.freeText().isBlank();
            if ((!q.allowFreeText() && !selection.freeText().isEmpty())
                    || (selection.optionIds().isEmpty() && !text)
                    || (!q.multiSelect() && selection.optionIds().size() + (text ? 1 : 0) != 1)
                    || selection.optionIds().stream().anyMatch(id -> q.options().stream().noneMatch(o -> o.optionId().equals(id)))) return false;
        }
        return true;
    }
    /** 验证关联 ID、问题预算及选项唯一性。 */
    public UserQuestionRequest {
        callId = text(callId, "callId", 128);
        question = text(question, "question", 1_000);
        options = List.copyOf(Objects.requireNonNull(options, "options 不能为空"));
        questions = List.copyOf(Objects.requireNonNull(questions));
        long bytes = 0;
        for (var q : questions) {
            bytes += utf8(q.id()) + utf8(q.title()) + utf8(q.question());
            for (var o : q.options()) bytes += utf8(o.optionId()) + utf8(o.label()) + utf8(o.description());
        }
        // 为 JSON 字段、转义和事件信封保留余量，避免合法题目组合击穿单行协议上限。
        if (bytes > 24000) throw new IllegalArgumentException("问卷总文本超过传输预算");
        if (questions.size() > 4 || new HashSet<>(questions.stream().map(UserQuestionItem::id).toList()).size() != questions.size()) throw new IllegalArgumentException("问卷题号或数量无效");
        if (questions.isEmpty() && (options.size() < 2 || options.size() > 4)) {
            throw new IllegalArgumentException("options 必须包含 2 到 4 项");
        }
        HashSet<String> ids = new HashSet<>();
        if (options.stream().anyMatch(option -> !ids.add(option.optionId()))) {
            throw new IllegalArgumentException("optionId 不能重复");
        }
    }

    private static int utf8(String text) { return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }

    private static String text(String value, String name, int max) {
        value = Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
