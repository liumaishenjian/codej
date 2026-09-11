package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R1—R4 真实按键验收使用的确定性 Java Fake 宿主。
 *
 * <p>Fake 只替代 ModelGateway；命令 Tool、Permission/Session Grant、真实进程、生命周期、
 * stdio 与新 TUI 均走生产代码。第一轮在短暂模型延迟后连续提出两次完全相同的长命令：
 * 第一次供用户选择 Allow Session，第二次必须无审批执行。第二轮再次提出同一命令，供用户
 * 在已授权执行中按 Esc 验证取消终态。Fixture 不写业务文件，不访问网络。</p>
 */
public final class TuiCoreInteractionFixtureMain {
    private TuiCoreInteractionFixtureMain() {
    }

    /**
     * 启动生产 stdio Server。
     *
     * @param args 已存在 Workspace 与 Workspace 外 Session 目录
     * @throws Exception Runtime 或协议无法启动
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("需要 workspace sessions");
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        String padding = "x".repeat(320);
        String command = windows
                ? "$fixturePadding='" + padding + "'; "
                        + "[Console]::Out.WriteLine('fixture-command-start'); "
                        + "Start-Sleep -Seconds 12; "
                        + "[Console]::Out.WriteLine('fixture-command-full-tail')"
                : "fixture_padding='" + padding + "'; "
                        + "printf '%s\\n' 'fixture-command-start'; "
                        + "sleep 12; printf '%s\\n' 'fixture-command-full-tail'";
        AtomicInteger turns = new AtomicInteger();
        try (var handler = new RuntimeStdioCommandHandler(request -> {
            int turn = turns.getAndIncrement();
            if (turn == 0) {
                try {
                    Thread.sleep(4_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture 模型延迟被中断", interrupted);
                }
                return command("fixture-command-first", command);
            }
            if (turn == 1) {
                requireSuccessful(request, "fixture-command-first");
                return command("fixture-command-session", command);
            }
            if (turn == 2) {
                requireSuccessful(request, "fixture-command-session");
                return ModelTurn.text("两次命令均完成；第二次复用了本会话授权且没有再次审批。");
            }
            if (turn == 3) {
                return command("fixture-command-cancel", command);
            }
            throw new IllegalStateException("取消后的 Run 不应继续请求模型");
        }, new HeadlessRuntimeOptions(
                Path.of(args[0]),
                "tui-core-interaction-fake",
                Duration.ofSeconds(60),
                PermissionMode.DEFAULT,
                List.of(),
                SessionOpenRequest.create(),
                Path.of(args[1])))) {
            StdioProtocolServer.ExitReason reason =
                    new StdioProtocolServer(System.in, System.out, handler).run();
            if (reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR) {
                throw new IllegalStateException("协议进程失败");
            }
        }
    }

    private static ModelTurn command(String id, String command) {
        return ModelTurn.tools(List.of(new ToolCall(
                id,
                "run_command",
                new JsonObject(Map.of("command", command, "timeoutSeconds", 20)))));
    }

    private static void requireSuccessful(ModelRequest request, String callId) {
        List<ToolResult> matches = request.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .map(ToolResultMessage::result)
                .filter(result -> result.callId().equals(callId))
                .toList();
        if (matches.size() != 1 || matches.getFirst().status() != ToolResultStatus.SUCCESS) {
            throw new IllegalStateException("命令结果必须成功且关联唯一");
        }
    }
}
