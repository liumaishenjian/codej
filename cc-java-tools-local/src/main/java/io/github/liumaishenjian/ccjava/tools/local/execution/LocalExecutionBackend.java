package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.domain.execution.CapabilityStatus;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementDimension;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementReport;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;

/**
 * 保留既有平台 Shell 行为的明确未隔离后端。
 *
 * <p>它只提供最小环境、timeout/cancel/cleanup；所有维度均为 {@code DEGRADED}，绝不
 * 声称 Sandbox。Local 仍验证请求策略形状，防止调用方误以为额外 root 或网络 allowlist
 * 已被执行。</p>
 *
 * @since 0.13.0
 */
public final class LocalExecutionBackend extends AbstractProcessExecutionBackend {
    private final Path workspace;
    private final CommandShell shell;

    /**
     * 为固定 Workspace 创建平台 Local 后端。
     *
     * @param workspace 所有命令固定使用的工作目录
     */
    public LocalExecutionBackend(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.shell = CommandShell.current();
    }

    @Override
    public ExecutionBackendId id() {
        return ExecutionBackendId.LOCAL;
    }

    @Override
    protected Plan plan(ExecutionRequest request) throws IOException {
        var policy = ExecutionPolicyCompiler.compile(request.policy(), workspace);
        if (request.shell() == ExecutionShell.LINUX_SH) {
            throw new IOException("Local backend 不接受跨平台 LINUX_SH");
        }
        if (request.shell() == ExecutionShell.FIXED_ARGV) {
            var fixed = new ArrayList<String>();
            fixed.add(request.executable());
            fixed.addAll(request.arguments());
            return new Plan(fixed, policy.workspace(), policy.environment(), new byte[0]);
        }
        var invocation = shell.processInvocation(single(request), policy.environment());
        return new Plan(
                invocation.arguments(),
                policy.workspace(),
                invocation.environment(),
                new byte[0]);
    }

    private static String single(ExecutionRequest request) throws IOException {
        if (request.arguments().size() != 1) {
            throw new IOException("shell 请求必须有一个正文");
        }
        return request.arguments().getFirst();
    }

    @Override
    protected EnforcementReport report(boolean fallback) {
        var dimensions = new EnumMap<EnforcementDimension, CapabilityStatus>(
                EnforcementDimension.class);
        for (EnforcementDimension dimension : EnforcementDimension.values()) {
            dimensions.put(dimension, CapabilityStatus.DEGRADED);
        }
        return new EnforcementReport(
                id(),
                dimensions,
                fallback,
                "UNSANDBOXED_LOCAL");
    }
}
