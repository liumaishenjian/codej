package io.github.liumaishenjian.ccjava.mcp;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把一个已发现 MCP Tool 映射为统一 {@link AgentTool}。
 *
 * <p>Definition 使用 Server 前缀避免冲突，执行时仍调用远端原名。所有 MCP Tool
 * 保守声明为 {@link ToolEffect#NETWORK_OR_REMOTE}，因此不能绕过本项目 Permission。</p>
 *
 * @since 0.10.0
 */
public final class McpAgentTool implements AgentTool {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final String remoteName;
    private final McpRemoteClient client;
    private final ToolDefinition definition;

    McpAgentTool(String serverName, McpToolDescriptor descriptor, McpRemoteClient client, Duration timeout) {
        this.remoteName = descriptor.name();
        this.client = Objects.requireNonNull(client, "client 不能为空");
        String schema;
        try {
            schema = JSON.writeValueAsString(descriptor.inputSchema());
        } catch (Exception failure) {
            throw new IllegalArgumentException("MCP Tool schema 无法序列化");
        }
        this.definition = new ToolDefinition(
                qualifiedName(serverName, descriptor.name()),
                descriptor.description(),
                schema,
                ToolEffect.NETWORK_OR_REMOTE,
                ToolSource.MCP,
                true,
                timeout,
                "text/plain",
                32_768);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        if (invocation.cancellationToken().isCancellationRequested()) {
            return ToolExecutionOutcome.failure(ToolError.of(
                    ToolErrorCode.OPERATION_CANCELLED, "MCP Tool 调用已取消"));
        }
        var executor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cc-java-mcp-tool-", 0).factory());
        var future = executor.submit(() -> client.callTool(
                remoteName,
                invocation.call().arguments().jsonValues(),
                definition.defaultTimeout(),
                invocation.cancellationToken()));
        try (io.github.liumaishenjian.ccjava.core.CancellationToken.Registration registration =
                     invocation.cancellationToken().onCancellation(() -> future.cancel(true))) {
            McpCallOutcome result;
            try {
                result = future.get(definition.defaultTimeout().toNanos(),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException failure) {
                future.cancel(true);
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.OPERATION_TIMED_OUT, "MCP Tool 调用超时"));
            } catch (InterruptedException failure) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.OPERATION_CANCELLED, "MCP Tool 调用已取消"));
            } catch (java.util.concurrent.CancellationException failure) {
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.OPERATION_CANCELLED, "MCP Tool 调用已取消"));
            } catch (java.util.concurrent.ExecutionException failure) {
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.EXECUTION_FAILED, "MCP Tool 调用失败"));
            }
            if (result.error()) {
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.EXECUTION_FAILED, "MCP Server 返回 Tool 错误"));
            }
            return ToolExecutionOutcome.success(result.content());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 生成边界明确、在 Registry 中唯一的 MCP Tool 名。
     *
     * @param serverName 稳定 Server 名称
     * @param remoteName 远端 Tool 原始名称
     * @return 带 {@code mcp__server__} 前缀的 Registry 名称
     */
    public static String qualifiedName(String serverName, String remoteName) {
        return "mcp__" + McpServerConfig.requireName(serverName, "serverName")
                + "__" + McpServerConfig.requireName(remoteName, "remoteName");
    }
}
