package io.github.liumaishenjian.ccjava.tools.local.git;

import io.github.liumaishenjian.ccjava.tools.local.WorkspaceSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;

/** 仅测试可见的跨包桥；同一次真实调用生成快照及安全诊断，不追加探测。 */
public final class GitReadTestSupport {
    private GitReadTestSupport() { }

    /**
     * 同一次调用的快照与安全诊断，不对结果重试或重新解释。
     * @param snapshot 生产捕获逻辑返回的快照
     * @param diagnostic 仅枚举、数字与布尔值，不含输出或路径
     */
    public record Observation(WorkspaceSnapshot snapshot, String diagnostic) { }

    /**
     * 通过真实ProcessBuilder进行一次状态探测。
     * @param workspace 测试拥有的临时目录
     * @return 原始快照与原始调用诊断
     */
    public static Observation capture(Path workspace) {
        var diagnostics = new ArrayList<GitReadClient.ReadDiagnostic>();
        var git = new GitReadClient(workspace, ProcessBuilder::start, diagnostics::add, Duration.ofSeconds(10));
        var snapshot = WorkspaceSnapshot.capture(git);
        if (diagnostics.size() != 1) throw new AssertionError("预期仅一次Git调用");
        return new Observation(snapshot, diagnostics.getFirst().toString());
    }
}
