package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为新 TUI 的验证声明失败/恢复场景提供真实 Java stdio 进程。
 *
 * <p>Fixture 只使用 Fake Model；失败、恢复 ordinal 和 Plan review 均经过生产
 * {@link RuntimeStdioCommandHandler} 与真实 Core Pipeline。</p>
 */
public final class PlanEvidenceRecoveryFixtureMain {
    private PlanEvidenceRecoveryFixtureMain() {
    }

    /**
     * 启动一次确定性 Plan planning 进程。
     *
     * @param args 唯一参数是测试拥有的临时父目录
     * @throws Exception 临时目录或协议启动失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要临时父目录");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Path workspace = Files.createDirectory(parent.resolve("workspace"));
        Path sessions = Files.createDirectory(parent.resolve("sessions"));
        AtomicInteger calls = new AtomicInteger();
        String markdown = "# Weather plan\n\nQuery weather and verify with an available tool.\n";
        var model = (io.github.liumaishenjian.ccjava.core.ModelGateway) request ->
                switch (calls.getAndIncrement()) {
                    case 0 -> tools(new ToolCall("update", "revise_plan_artifact",
                            new JsonObject(Map.of("markdown", markdown))));
                    case 1 -> tools(declaration("failed-1", "weather", "missing_weather_tool"));
                    case 2 -> tools(declaration("failed-2", "weather", "other_missing_weather_tool"));
                    case 3 -> tools(declaration("other-success", "other-check", "list_files"));
                    case 4 -> tools(declaration("weather-success", "weather", "list_files"));
                    case 5 -> tools(new ToolCall("review", "request_plan_review", JsonObject.empty()));
                    default -> ModelTurn.text("planning complete");
                };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), sessions);
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(model, options)) {
            StdioProtocolServer.ExitReason reason = new StdioProtocolServer(
                    System.in, System.out, handler).run();
            if (reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR) System.exit(2);
        }
    }

    private static ModelTurn tools(ToolCall call) {
        return ModelTurn.tools(List.of(call));
    }

    private static ToolCall declaration(String callId, String requirementId, String locator) {
        return new ToolCall(callId, "declare_plan_evidence", new JsonObject(Map.of(
                "requirementId", requirementId,
                "kind", "VERIFICATION",
                "locator", locator,
                "label", "weather verification",
                "required", true)));
    }
}
