package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 新终端的真实搜索、命令审批、输出失败与取消验收宿主，只存在于测试 classpath。
 * <p>Fake 仅替代模型；工具、权限、进程及 stdio 仍走生产管线。命令只输出固定文字并等待后退出。</p>
 */
public final class CoreToolsFixtureMain {
    private CoreToolsFixtureMain() { }
    /**
     * 启动不写业务文件的确定性工具会话。
     * @param args 已存在 Git 工作区、会话目录、可选 cancel 模式
     * @throws Exception 运行管线或协议进程失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("cancel")))
            throw new IllegalArgumentException("需要 workspace sessions [cancel]");
        boolean cancel = args.length == 3;
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String command = windows
                ? "[Console]::Out.WriteLine('fixture-command-start'); [Console]::Error.WriteLine('fixture-command-stderr'); Start-Sleep -Milliseconds " + (cancel ? "30000" : "800") + "; exit 7"
                : "printf '%s\\n' 'fixture-command-start'; printf '%s\\n' 'fixture-command-stderr' >&2; sleep " + (cancel ? "30" : "1") + "; exit 7";
        AtomicInteger calls = new AtomicInteger();
        try (var handler = new RuntimeStdioCommandHandler(request -> {
            int turn = calls.getAndIncrement();
            if (turn == 0) return ModelTurn.tools(List.of(new ToolCall("fixture-search", "search_text",
                    new JsonObject(Map.of("query", "fixture-search-marker", "path", ".", "regex", false, "mode", "content", "limit", 10)))));
            if (turn == 1) {
                var search = result(request, "fixture-search");
                if (search.status() != ToolResultStatus.SUCCESS || !search.content().contains("fixture-search-marker"))
                    throw new IllegalStateException("真实搜索未找到fixture marker");
                return ModelTurn.tools(List.of(new ToolCall("fixture-command", "run_command",
                        new JsonObject(Map.of("command", command, "timeoutSeconds", 40)))));
            }
            if (turn != 2 || cancel) throw new IllegalStateException("取消后不应再请求模型");
            var execution = result(request, "fixture-command");
            if (execution.status() == ToolResultStatus.DENIED) return ModelTurn.text("命令已被用户拒绝，未执行。");
            if (execution.status() != ToolResultStatus.FAILURE || execution.error().isEmpty()
                    || execution.error().orElseThrow().code() != ToolErrorCode.PROCESS_EXIT
                    || !execution.content().contains("fixture-command-start") || !execution.content().contains("fixture-command-stderr"))
                throw new IllegalStateException("真实命令失败结果未正确返回模型");
            return ModelTurn.text("搜索已完成；命令按预期以退出码 7 失败，标准输出和错误输出已返回模型。");
        }, new HeadlessRuntimeOptions(Path.of(args[0]), "core-tools-fake", Duration.ofSeconds(90),
                PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(), Path.of(args[1])))) {
            if (new StdioProtocolServer(System.in, System.out, handler).run() == StdioProtocolServer.ExitReason.INTERNAL_ERROR)
                throw new IllegalStateException("协议进程失败");
        }
    }
    private static ToolResult result(ModelRequest request, String id) {
        var results = request.messages().stream().filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast).map(ToolResultMessage::result).filter(r -> r.callId().equals(id)).toList();
        if (results.size() != 1) throw new IllegalStateException("工具结果关联必须唯一");
        return results.getFirst();
    }
}
