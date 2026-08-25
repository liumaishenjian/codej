package io.github.liumaishenjian.ccjava.mcp;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginNamespace;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把宿主创建的 MCP client Tool 映射为 {@link ToolSource#PLUGIN}，而不是允许 manifest 伪造来源。
 *
 * <p>完整 qualified name 隔离 Plugin/provider/remote tool；调用仍由 ToolRegistry 与统一 Pipeline 驱动。</p>
 *
 * @since 0.11.0
 */
final class PluginMcpAgentTool implements AgentTool {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final String remoteName;
    private final McpRemoteClient client;
    private final PluginToolCallGate callGate;
    private final ToolDefinition definition;

    PluginMcpAgentTool(String providerQualifiedName, McpToolDescriptor descriptor,
            McpRemoteClient client, PluginToolCallGate callGate, Duration timeout) {
        this.remoteName = Objects.requireNonNull(descriptor, "descriptor 不能为空").name();
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.callGate = Objects.requireNonNull(callGate, "callGate 不能为空");
        try {
            this.definition = new ToolDefinition(
                    PluginNamespace.qualifiedTool(providerQualifiedName, descriptor.name()),
                    descriptor.description(),
                    JSON.writeValueAsString(descriptor.inputSchema()),
                    ToolEffect.NETWORK_OR_REMOTE,
                    ToolSource.PLUGIN,
                    true,
                    Objects.requireNonNull(timeout, "timeout 不能为空"),
                    "text/plain",
                    32_768);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Plugin MCP Tool schema 无法序列化");
        }
    }

    @Override public ToolDefinition definition() { return definition; }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        if (invocation.cancellationToken().isCancellationRequested()) {
            return cancelled();
        }
        try (var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cc-java-plugin-mcp-tool-", 0).factory())) {
            var future = executor.submit(() -> callGate.call(() -> client.callTool(
                    remoteName, invocation.call().arguments().jsonValues(),
                    definition.defaultTimeout(), invocation.cancellationToken())));
            try (var registration = invocation.cancellationToken().onCancellation(() -> future.cancel(true))) {
                try {
                    McpCallOutcome outcome = future.get(
                            definition.defaultTimeout().toNanos(), TimeUnit.NANOSECONDS);
                    return outcome.error()
                            ? ToolExecutionOutcome.failure(ToolError.of(
                                    ToolErrorCode.EXECUTION_FAILED, "Plugin remote Tool 调用失败"))
                            : ToolExecutionOutcome.success(outcome.content());
                } catch (java.util.concurrent.TimeoutException failure) {
                    future.cancel(true);
                    return ToolExecutionOutcome.failure(ToolError.of(
                            ToolErrorCode.OPERATION_TIMED_OUT, "Plugin remote Tool 调用超时"));
                } catch (InterruptedException failure) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    return cancelled();
                } catch (java.util.concurrent.CancellationException failure) {
                    return cancelled();
                } catch (java.util.concurrent.ExecutionException failure) {
                    return ToolExecutionOutcome.failure(ToolError.of(
                            ToolErrorCode.EXECUTION_FAILED, "Plugin remote Tool 调用失败"));
                }
            }
        }
    }

    private static ToolExecutionOutcome cancelled() {
        return ToolExecutionOutcome.failure(ToolError.of(
                ToolErrorCode.OPERATION_CANCELLED, "Plugin remote Tool 调用已取消"));
    }
}
