package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 供真实 Ink/Java 端到端验收的确定性问卷宿主，仅进入测试 classpath。 */
public final class QuestionnairesFixtureMain {
    private QuestionnairesFixtureMain() { }
    /**
     * 使用调用方创建的临时目录启动真实协议与运行管线。
     * @param args 已存在 Git 工作区和会话存储目录
     * @throws Exception 协议、目录或运行管线失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要 workspace 和 sessions");
        var calls = new AtomicInteger();
        var choices = List.of(Map.of("optionId", "a", "label", "方案 A", "description", "保留当前边界"),
                Map.of("optionId", "b", "label", "方案 B", "description", "分步完善交互"));
        var questions = List.of(
                Map.of("id", "single", "title", "单选", "question", "请选择一个方案", "multiSelect", false, "allowFreeText", true, "options", choices),
                Map.of("id", "multi", "title", "多选", "question", "请选择需要的范围", "multiSelect", true, "allowFreeText", true, "options", choices),
                Map.of("id", "text", "title", "补充", "question", "请输入补充说明", "multiSelect", false, "allowFreeText", true, "options", List.of()));
        try (var handler = new RuntimeStdioCommandHandler(request -> {
            String latestUser = request.messages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .map(UserMessage::content)
                    .reduce((previous, current) -> current)
                    .orElse("");
            if (latestUser.contains("下一轮")) {
                return ModelTurn.text("问卷结束后下一轮仍可继续。");
            }
            if (calls.getAndIncrement() == 0) return ModelTurn.tools(List.of(new ToolCall("questionnaire-e2e", "ask_user_questions",
                    new JsonObject(Map.of("questions", questions)))));
            var results = request.messages().stream().filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast).map(ToolResultMessage::result)
                    .filter(r -> r.callId().equals("questionnaire-e2e")).toList();
            if (results.size() != 1 || results.getFirst().status() != ToolResultStatus.SUCCESS
                    || !results.getFirst().content().contains("single:") || !results.getFirst().content().contains("multi:")
                    || !results.getFirst().content().contains("text:")) throw new IllegalStateException("问卷工具结果未完整返回");
            return ModelTurn.text("问卷答案已通过真实工具结果返回模型。\n" + results.getFirst().content());
        }, new HeadlessRuntimeOptions(Path.of(args[0]), "questionnaire-fake", Duration.ofSeconds(90),
                PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(), Path.of(args[1])))) {
            if (new StdioProtocolServer(System.in, System.out, handler).run() == StdioProtocolServer.ExitReason.INTERNAL_ERROR)
                throw new IllegalStateException("协议进程失败");
        }
    }
}
