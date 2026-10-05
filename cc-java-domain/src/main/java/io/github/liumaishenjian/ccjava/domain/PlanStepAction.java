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
    /** 宿主内部的正常 Agent Run 标记，不是可调用工具名。 */
    public static final String AGENT_RUN = "agent_run";
    private static final Set<String> EXECUTABLE = Set.of(
            "list_files", "read_file", "search_text", "git_status", "git_diff",
            "apply_patch", "write_file", "run_command");

    /** 校验工具白名单或内部标记、非空参数及有界安全预览。 */
    public PlanStepAction {
        toolName = requireText(toolName, "toolName", 64);
        if (!EXECUTABLE.contains(toolName) && !AGENT_RUN.equals(toolName)) {
            throw new IllegalArgumentException("Plan Tool 不允许");
        }
        arguments = Objects.requireNonNull(arguments, "arguments 不能为空");
        safePreview = requireText(safePreview, "safePreview", 1_000);
    }

    /**
     * 按固定内置工具集合识别只读步骤，不以模型声明决定权限。
     * @return 属于五个已知只读工具之一时为 true；Agent Run 标记不视为只读
     */
    public boolean readOnly() {
        return toolName.equals("list_files") || toolName.equals("read_file")
                || toolName.equals("search_text") || toolName.equals("git_status") || toolName.equals("git_diff");
    }

    /**
     * 自然语言步骤在批准后由正常 Agent Runtime 统一执行，而不是伪装成一次 Tool 调用。
     * @return 携带内部 Agent Run 标记时为 true
     */
    public boolean agentRun() {
        return AGENT_RUN.equals(toolName);
    }

    /**
     * 为自然语言 Plan 创建内部执行标记；该标记永远不能提交给 Tool Pipeline。
     * @return 参数为空的内部标记动作，不授予权限
     */
    public static PlanStepAction agentRunMarker() {
        return new PlanStepAction(AGENT_RUN, JsonObject.empty(), "approved plan agent run");
    }

    /**
     * 模型可声明的显式 Tool 集合；内部 Agent Run 标记不得由模型构造。
     * @return 不可变工具名白名单，不包含内部标记
     */
    public static Set<String> allowedToolNames() { return EXECUTABLE; }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
