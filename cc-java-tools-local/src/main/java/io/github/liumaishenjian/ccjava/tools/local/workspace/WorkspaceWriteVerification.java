package io.github.liumaishenjian.ccjava.tools.local.workspace;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 在文件 Tool 原子写入完成后重新读取并核对实际磁盘内容。
 *
 * <p>该检查不是权限或锁，也不替代写入前的冲突重检。它只确认写入返回成功时，
 * 当前 Workspace 内相同真实文件的字节内容仍与本次 Tool 计算的结果一致；任何
 * 路径漂移、大小变化、读取失败或内容差异都以可纠正的文件冲突返回。</p>
 *
 * @since 0.4.0
 */
public final class WorkspaceWriteVerification {

    private WorkspaceWriteVerification() {
    }

    /**
     * 重新读取目标并要求字节级等同于预期结果。
     *
     * @param guard 共享 Workspace 安全边界
     * @param input Tool 使用的 Workspace-relative 路径
     * @param expectedRealPath 原子写入前固定的真实路径
     * @param expectedBytes 本次写入的完整 UTF-8 字节
     * @throws WorkspaceAccessException 目标漂移、读取失败或内容不一致时
     */
    public static void requireMatches(
            WorkspaceGuard guard,
            String input,
            Path expectedRealPath,
            byte[] expectedBytes) throws WorkspaceAccessException {
        ValidatedWorkspacePath current;
        try {
            current = guard.requireRegularFile(input);
        } catch (WorkspaceAccessException exception) {
            if (exception.error().code() == ToolErrorCode.PATH_NOT_FOUND
                    || exception.error().code() == ToolErrorCode.PATH_TYPE_MISMATCH
                    || exception.error().code() == ToolErrorCode.FILE_CONFLICT) {
                throw conflict("写入后目标文件已改变，无法确认产物");
            }
            throw exception;
        }
        if (!current.realPath().equals(expectedRealPath)) {
            throw conflict("写入后文件真实路径已改变，无法确认产物");
        }
        try {
            long size = Files.size(current.realPath());
            if (size != expectedBytes.length) {
                throw conflict("写入后文件大小与预期不一致，无法确认产物");
            }
            if (size > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                throw conflict("写入后文件超过读取上限，无法确认产物");
            }
            int readLimit = (int) Math.min(
                    LocalToolLimits.MAX_TEXT_FILE_BYTES + 1,
                    Math.max(1, expectedBytes.length + 1));
            byte[] actual;
            try (var inputStream = Files.newInputStream(current.realPath())) {
                actual = inputStream.readNBytes(readLimit);
            }
            if (!Arrays.equals(actual, expectedBytes)) {
                throw conflict("写入后文件内容与预期不一致，无法确认产物");
            }
        } catch (IOException exception) {
            throw new WorkspaceAccessException(ToolError.of(
                    ToolErrorCode.FILE_CONFLICT,
                    "写入后无法重新读取文件，无法确认产物"));
        }
    }

    private static WorkspaceAccessException conflict(String message) {
        return new WorkspaceAccessException(ToolError.of(ToolErrorCode.FILE_CONFLICT, message));
    }
}
