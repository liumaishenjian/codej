package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserQuestionnaireTest {
    private static final UserQuestionOption A = new UserQuestionOption("a", "选择 A", "说明");
    private static final UserQuestionOption B = new UserQuestionOption("b", "选择 B", "说明");
    @Test void requiresCompleteUniqueAnswersAndDeclaredChoices() {
        var request = new UserQuestionRequest("call", List.of(
                new UserQuestionItem("one", "单选", "选一个", false, List.of(A, B), true),
                new UserQuestionItem("many", "多选", "选多个", true, List.of(A, B), true),
                new UserQuestionItem("text", "文字", "填写说明", false, List.of(), true)));
        var single = new UserQuestionSelection("one", List.of("a"), "");
        var multi = new UserQuestionSelection("many", List.of("a", "b"), "补充中文😀");
        var text = new UserQuestionSelection("text", List.of(), "文字\n第二行");
        assertThat(request.accepts(new UserQuestionAnswer("call", List.of(single, multi, text)))).isTrue();
        assertThat(request.accepts(new UserQuestionAnswer("old", List.of(single, multi, text)))).isFalse();
        assertThat(request.accepts(new UserQuestionAnswer("call", List.of(single, multi)))).isFalse();
        assertThat(request.accepts(new UserQuestionAnswer("call", List.of(single, single, text)))).isFalse();
        assertThat(request.accepts(new UserQuestionAnswer("call", List.of(
                new UserQuestionSelection("one", List.of("a"), "同选文字"), multi, text)))).isFalse();
        assertThat(request.accepts(new UserQuestionAnswer("call", List.of(single,
                new UserQuestionSelection("many", List.of("unknown"), ""), text)))).isFalse();
        assertThat(request.accepts(new UserQuestionAnswer("call", List.of(single, multi,
                new UserQuestionSelection("text", List.of(), "  "))))).isFalse();
    }
    @Test void rejectsCombinedQuestionTextOverWireBudget() {
        var choices = java.util.stream.IntStream.range(0, 8).mapToObj(i ->
                new UserQuestionOption("o" + i, "中".repeat(120), "中".repeat(500))).toList();
        var questions = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
                new UserQuestionItem("q" + i, "题签", "中".repeat(1000), true, choices, true)).toList();
        assertThatThrownBy(() -> new UserQuestionRequest("call", questions)).isInstanceOf(IllegalArgumentException.class);
    }    @Test void rejectsOverBudgetDuplicatesAndBrokenUnicode() {
        assertThatThrownBy(() -> new UserQuestionSelection("q", List.of("a", "a"), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserQuestionSelection("q", List.of(), "x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserQuestionSelection("q", List.of(), "\ud800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserQuestionItem("q", "题签", "问题", false, List.of(), false)).isInstanceOf(IllegalArgumentException.class);
        var q = new UserQuestionItem("q", "题签", "问题", false, List.of(A), false);
        assertThatThrownBy(() -> new UserQuestionRequest("call", List.of(q,q))).isInstanceOf(IllegalArgumentException.class);
    }
}
