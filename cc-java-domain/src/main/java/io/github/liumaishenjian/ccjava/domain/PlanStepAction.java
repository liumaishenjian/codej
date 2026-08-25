package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Plan 步骤的受限结构化 Tool 意图；不允许从 detail 文本推断命令。
 *
 * @param toolName 受白名单约束的 Tool 名或内部 Agent Run 标记
 * @param arguments 结构化且不可为空的 Tool 参数
 * @param safePreview 不含秘密的用户可见摘要
 */
public record PlanStepAction(String toolName, JsonObject arguments, String safePreview) {
    public static final String AGENT_RUN = "agent_run";
    private static final Set<String> EXECUTABLE = Set.of(
            "list_files", "read_file", "search_text", "git_status", "git_diff",
            "apply_patch", "write_file", "run_command");

    public PlanStepAction {
        toolName = requireText(toolName, "toolName", 64);
        if (!EXECUTABLE.contains(toolName) && !AGENT_RUN.equals(toolName)) {
            throw new IllegalArgumentException("Plan Tool 不允许");
        }
        arguments = Objects.requireNonNull(arguments, "arguments 不能为空");
        safePreview = requireText(safePreview, "safePreview", 1_000);
    }

    public boolean readOnly() {
        return toolName.equals("list_files") || toolName.equals("read_file")
                || toolName.equals("search_text") || toolName.equals("git_status") || toolName.equals("git_diff");
    }

    /** 自然语言步骤在批准后由正常 Agent Runtime 统一执行，而不是伪装成一次 Tool 调用。 */
    public boolean agentRun() {
        return AGENT_RUN.equals(toolName);
    }

    /** 为自然语言 Plan 创建内部执行标记；该标记永远不能提交给 Tool Pipeline。 */
    public static PlanStepAction agentRunMarker() {
        return new PlanStepAction(AGENT_RUN, JsonObject.empty(), "approved plan agent run");
    }

    /** 模型可声明的显式 Tool 集合；内部 Agent Run 标记不得由模型构造。 */
    public static Set<String> allowedToolNames() { return EXECUTABLE; }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
