package io.github.liumaishenjian.ccjava.tools.local;

import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import java.util.Objects;

/**
 * Session 打开时记录的非 Secret Workspace Git 摘要。
 *
 * @param repositoryState Git work tree 探测结果；故障不得伪装成明确的非仓库
 * @param branch 当前分支或 {@code unknown}
 * @param staged staged 条目数
 * @param unstaged unstaged 条目数
 * @param untracked untracked 条目数
 * @since 0.3.0
 */
public record WorkspaceSnapshot(
        RepositoryState repositoryState,
        String branch,
        int staged,
        int unstaged,
        int untracked) {

    /**
     * 区分明确的仓库、明确的非仓库与探测故障。
     *
     * <p>{@link #UNKNOWN} 必须按不可依赖 Git 能力处理，但不能对外冒充已经确认的非 Git
     * Workspace，以便后续诊断和测试能够发现 Git 不可用、超时或读取失败。</p>
     */
    public enum RepositoryState {
        /** 已确认位于 Git work tree。 */
        REPOSITORY,
        /** Git 明确报告当前目录不是 work tree。 */
        NON_REPOSITORY,
        /** Git 程序不可用、超时、输出超限或发生其他读取故障。 */
        UNKNOWN
    }

    /** 校验安全摘要。 */
    public WorkspaceSnapshot {
        repositoryState = Objects.requireNonNull(repositoryState, "repositoryState 不能为空");
        branch = Objects.requireNonNull(branch, "branch 不能为空");
        if (branch.isBlank() || staged < 0 || unstaged < 0 || untracked < 0) {
            throw new IllegalArgumentException("WorkspaceSnapshot 字段无效");
        }
    }

    /**
     * 非 Git Workspace 的稳定摘要。
     *
     * @return repository=false 且计数为零的摘要
     */
    public static WorkspaceSnapshot nonRepository() {
        return new WorkspaceSnapshot(RepositoryState.NON_REPOSITORY, "none", 0, 0, 0);
    }

    /** Git 探测故障时的 fail-closed 摘要。 */
    public static WorkspaceSnapshot unknownRepositoryState() {
        return new WorkspaceSnapshot(RepositoryState.UNKNOWN, "unknown", 0, 0, 0);
    }

    /**
     * 保留既有调用方的布尔视图；只有明确成功的 Git 探测才返回 {@code true}。
     *
     * @return 当前快照是否已确认位于 Git work tree
     */
    public boolean repository() {
        return repositoryState == RepositoryState.REPOSITORY;
    }

    /**
     * 从固定 porcelain 读取构造摘要，不保留路径正文。
     *
     * @param git 固定 Workspace 的只读 Git Adapter
     * @return 明确非 Git 时返回 non-repository；其他读取故障返回 unknown
     */
    public static WorkspaceSnapshot capture(GitReadClient git) {
        try {
            String branch = "unknown";
            int staged = 0;
            int unstaged = 0;
            int untracked = 0;
            for (String line : git.status().stdout().lines().toList()) {
                if (line.startsWith("## ")) {
                    branch = line.substring(3).strip();
                } else if (line.length() >= 2) {
                    if (line.startsWith("??")) {
                        untracked++;
                    } else {
                        if (line.charAt(0) != ' ') {
                            staged++;
                        }
                        if (line.charAt(1) != ' ') {
                            unstaged++;
                        }
                    }
                }
            }
            return new WorkspaceSnapshot(RepositoryState.REPOSITORY, branch, staged, unstaged, untracked);
        } catch (GitReadClient.GitReadException exception) {
            return exception.error().code()
                    == io.github.liumaishenjian.ccjava.domain.ToolErrorCode.NOT_A_GIT_REPOSITORY
                    ? nonRepository() : unknownRepositoryState();
        }
    }
}
