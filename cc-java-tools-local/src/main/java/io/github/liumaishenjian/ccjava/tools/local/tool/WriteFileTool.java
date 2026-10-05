package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.CheckpointTarget;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.tools.local.text.ReadEvidence;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceReadRegistry;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceWriteVerification;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 在安全、已存在的 Workspace 父目录中创建一个新的 UTF-8 文本文件。
 *
 * <p>首版明确不覆盖已有文件，也不隐式创建父目录；覆盖已有内容必须改用携带旧内容
 * 前置条件的 {@link ApplyPatchTool}。审批通过后仍会再次验证新文件路径，再以同目录
 * 临时文件 Move 完成创建。</p>
 *
 * @since 0.4.0
 */
public final class WriteFileTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of("path", "content");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "write_file",
            "Create one new UTF-8 file under an existing workspace directory.",
            """
            {"type":"object","additionalProperties":false,"required":["path","content"],"properties":{"path":{"type":"string","minLength":1},"content":{"type":"string","maxLength":2097152}}}
            """,
            ToolEffect.WRITE_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(5),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;
    private final WorkspaceReadRegistry readRegistry;

    /**
     * 创建带独立 Read 登记表的新文件 Tool。
     *
     * @param guard 共享 WorkspaceGuard
     */
    public WriteFileTool(WorkspaceGuard guard) {
        this(guard, new WorkspaceReadRegistry());
    }

    /**
     * 创建与读取和 Patch 工具共享 Read 证据登记表的新文件 Tool。
     *
     * <p>新建文件的内容完全由本次调用给出，因此创建成功后可以直接登记为整份文件的权威
     * 证据；这样“先创建、再精确修改”不需要额外读取一次刚写下的内容。</p>
     *
     * @param guard 共享 WorkspaceGuard
     * @param readRegistry 与 {@link ReadFileTool}、{@link ApplyPatchTool} 共享的登记表
     */
    public WriteFileTool(WorkspaceGuard guard, WorkspaceReadRegistry readRegistry) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
        this.readRegistry = Objects.requireNonNull(readRegistry, "readRegistry 不能为空");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            ToolArguments.rejectUnknown(arguments, ARGUMENTS);
            String path = ToolArguments.string(arguments, "path", null);
            String content = ToolArguments.string(arguments, "content", null);
            ToolArguments.requireNonBlank("path", path);
            if (content == null) {
                throw new IllegalArgumentException("content 不能为空");
            }
            ToolArguments.rejectBinaryNull("content", content);
            if (Utf8TextEncoder.encode(content, false).length
                    > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("content 超过 UTF-8 字节上限");
            }
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public java.util.Optional<CheckpointTarget> checkpointTarget(ToolInvocation invocation)
            throws WorkspaceAccessException {
        String path = ToolArguments.string(invocation.call().arguments(), "path", null);
        ValidatedWorkspacePath validated = guard.requireNewFile(path);
        return java.util.Optional.of(new CheckpointTarget(validated.protocolPath(), false));
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String path = ToolArguments.string(arguments, "path", null);
        String content = ToolArguments.string(arguments, "content", null);
        try {
            if (invocation.cancellationToken().isCancellationRequested()) {
                return ToolExecutionOutcome.failure(
                        ToolError.of(ToolErrorCode.OPERATION_CANCELLED, "文件操作已取消"));
            }
            ValidatedWorkspacePath validated = guard.requireNewFile(path);
            byte[] bytes = Utf8TextEncoder.encode(content, false);
            AtomicUtf8FileWriter.create(
                    validated.realPath(),
                    bytes,
                    invocation.cancellationToken(),
                    () -> {
                        ValidatedWorkspacePath current = guard.requireNewFile(path);
                        if (!current.realPath().equals(validated.realPath())) {
                            throw new WorkspaceAccessException(ToolError.of(
                                    ToolErrorCode.FILE_CONFLICT,
                                    "新文件真实父目录在写入前已改变"));
                        }
                    });
            WorkspaceWriteVerification.requireMatches(
                    guard, path, validated.realPath(), bytes);
            recordCreatedEvidence(invocation, validated, content);
            PatchResultRenderer.Rendered rendered = PatchResultRenderer.render(
                    validated.protocolPath(),
                    "created",
                    "",
                    content,
                    1);
            return ToolExecutionOutcome.success(rendered.content(), rendered.metadata());
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }

    /**
     * 把刚创建的完整内容登记为整份文件的权威 Read 证据。
     *
     * <p>先作废该路径的旧证据，再登记新证据；文件身份不可得时安静跳过，让后续修改
     * 退化为“先读取”，而不是在不确定身份上继续写。</p>
     */
    private void recordCreatedEvidence(
            ToolInvocation invocation,
            ValidatedWorkspacePath validated,
            String content) {
        readRegistry.invalidate(validated.protocolPath());
        long size;
        long lastModified;
        try {
            size = java.nio.file.Files.size(validated.realPath());
            lastModified = java.nio.file.Files
                    .getLastModifiedTime(validated.realPath()).toMillis();
        } catch (java.io.IOException exception) {
            return;
        }
        String canonical = content.replace("\r\n", "\n").replace('\r', '\n');
        String withoutTrailingNewline = canonical.endsWith("\n")
                ? canonical.substring(0, canonical.length() - 1)
                : canonical;
        int lines = withoutTrailingNewline.isEmpty()
                ? 0
                : (int) withoutTrailingNewline.chars().filter(value -> value == '\n').count() + 1;
        readRegistry.record(invocation.sessionId(), new ReadEvidence(
                validated.protocolPath(),
                1,
                Math.max(1, lines),
                true,
                size,
                lastModified,
                ReadEvidence.digestOf(withoutTrailingNewline)));
    }
}
