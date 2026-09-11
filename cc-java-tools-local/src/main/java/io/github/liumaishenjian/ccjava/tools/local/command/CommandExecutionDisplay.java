package io.github.liumaishenjian.ccjava.tools.local.command;

import java.util.Objects;

/**
 * 由已装配命令执行器公开的非 Secret 显示事实。
 *
 * <p>该值来自真实 {@link io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend}
 * 身份与固定 Workspace，而不是 UI、操作系统探测或 Permission 是否弹窗。它只描述将使用的
 * 执行配置；不表示进程已经启动或成功。</p>
 *
 * @param shell 实际后端使用的稳定 Shell ID
 * @param workingDirectory 命令固定 cwd 的 Workspace-relative 表达
 * @since 0.15.0
 */
public record CommandExecutionDisplay(String shell, String workingDirectory) {

    /** 规范化可进入本地适配器协议的命令显示事实。 */
    public CommandExecutionDisplay {
        shell = Objects.requireNonNull(shell, "shell 不能为空");
        workingDirectory = Objects.requireNonNull(
                workingDirectory, "workingDirectory 不能为空");
        if (shell.isBlank() || shell.length() > 64 || !".".equals(workingDirectory)) {
            throw new IllegalArgumentException("命令显示事实无效");
        }
    }
}
