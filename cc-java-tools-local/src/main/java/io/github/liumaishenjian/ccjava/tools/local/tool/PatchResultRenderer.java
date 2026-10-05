package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 为文件 Tool 生成有界、明确标记裁剪的上下文变更摘要。
 *
 * <p>该输出反馈给模型，不进入审批 stdio 事件。审批 Surface 只接收路径、操作和行数；
 * 完整 Workspace Diff 仍由模型显式调用 {@code git_diff} 取得。</p>
 *
 * @since 0.4.0
 */
final class PatchResultRenderer {

    private static final String TRUNCATION_MARKER =
            "\n[patch preview truncated: use git_diff for workspace evidence]\n";

    private PatchResultRenderer() {
    }

    static Rendered render(
            String path,
            String operation,
            String oldText,
            String newText,
            int replacements) {
        int removedLines = lineCount(oldText);
        int addedLines = lineCount(newText);
        String full = "path: " + path + '\n'
                + "operation: " + operation + '\n'
                + "replacements: " + replacements + '\n'
                + "removedLines: " + removedLines + '\n'
                + "addedLines: " + addedLines + '\n'
                + "patch:\n@@ exact-context @@\n"
                + prefixed(oldText, "- ")
                + prefixed(newText, "+ ");
        int originalCharacters = full.codePointCount(0, full.length());
        int limit = LocalToolLimits.MAX_PATCH_PREVIEW_CHARACTERS;
        boolean truncated = originalCharacters > limit;
        String content = truncated
                ? prefixByCodePoints(
                        full,
                        Math.max(0, limit - TRUNCATION_MARKER.codePointCount(
                                0, TRUNCATION_MARKER.length())))
                        + TRUNCATION_MARKER
                : full;
        int returnedCharacters = content.codePointCount(0, content.length());
        ToolResultMetadata metadata = new ToolResultMetadata(
                truncated,
                truncated ? ToolResultTruncationReason.BYTE_LIMIT
                        : ToolResultTruncationReason.NONE,
                returnedCharacters,
                OptionalLong.of(Math.max(originalCharacters, returnedCharacters)),
                replacements,
                0,
                new JsonObject(Map.of(
                        "path", path,
                        "operation", operation,
                        "verification", "verified",
                        "removedLines", removedLines,
                        "addedLines", addedLines)));
        return new Rendered(content, metadata);
    }

    static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }

    private static String prefixed(String text, String prefix) {
        if (text.isEmpty()) {
            return "";
        }
        return prefix + text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\n" + prefix)
                + '\n';
    }

    private static String prefixByCodePoints(String value, int count) {
        if (count <= 0) {
            return "";
        }
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    record Rendered(String content, ToolResultMetadata metadata) {
    }
}
