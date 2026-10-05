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
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceTextSnapshot;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceTextSnapshotReader;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceWriteVerification;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 以精确旧内容前置条件修改一个 Workspace UTF-8 普通文件。
 *
 * <p>匹配发生在<b>规范化文本</b>上：文件的 {@code \r\n} 与裸 {@code \r} 都折叠为
 * {@code \n}，模型传入的 {@code oldText}/{@code newText} 也先做同样规范化。因此在 Windows
 * CRLF 文件上使用 LF 多行 {@code oldText} 能够正确命中，而写回时仍按原文件外观恢复
 * CRLF 并保留 BOM。空白与缩进仍然严格精确，不做任何模糊匹配。</p>
 *
 * <p>写回按原始字节切片：匹配区间之外的字节逐字保留，因此不会顺手改写无关行的分隔符。
 * 当文件分隔符风格不一致或含裸 {@code \r}，且本次替换确实需要合成新分隔符时，Tool 以
 * {@link ToolErrorCode#UNSUPPORTED_ENCODING} 失败关闭，而不是猜测风格。</p>
 *
 * <p>默认要求旧内容只出现一次；多处替换必须显式设置 {@code replaceAll}。修改前要求同
 * Session 已存在覆盖待修改区域（或整份文件）的可信 Read 证据，且该证据对应的文件身份
 * 至今未变；否则返回可纠正的 {@link ToolErrorCode#FILE_CONFLICT}，提示模型先读取。
 * 审批通过后仍会重新读取文件，并在原子移动前比较原始字节和真实路径，因此不会用过期
 * Patch 覆盖用户或其他进程的并发修改。该 Tool 不删除文件、不创建目录、不格式化仓库。</p>
 *
 * @since 0.4.0
 */
public final class ApplyPatchTool implements AgentTool {

    private static final Set<String> ARGUMENTS =
            Set.of("path", "oldText", "newText", "replaceAll");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "apply_patch",
            "Replace exact UTF-8 text in one existing workspace-relative file.",
            """
            {"type":"object","additionalProperties":false,"required":["path","oldText","newText"],"properties":{"path":{"type":"string","minLength":1},"oldText":{"type":"string","minLength":1,"maxLength":524288},"newText":{"type":"string","maxLength":524288},"replaceAll":{"type":"boolean","default":false}}}
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
     * 创建带独立 Read 登记表的 Patch Tool。
     *
     * <p>该构造只适用于把读取与修改视为同一封闭单元的测试与工具；生产装配必须与
     * {@link ReadFileTool} 共享同一登记表，否则“修改前必须已读过”无法成立。</p>
     *
     * @param guard 共享 WorkspaceGuard
     */
    public ApplyPatchTool(WorkspaceGuard guard) {
        this(guard, new WorkspaceReadRegistry());
    }

    /**
     * 创建与读取工具共享 Read 证据登记表的 Patch Tool。
     *
     * @param guard 共享 WorkspaceGuard
     * @param readRegistry 与 {@link ReadFileTool} 共享的有界 Read 证据登记表
     */
    public ApplyPatchTool(WorkspaceGuard guard, WorkspaceReadRegistry readRegistry) {
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
            String oldText = ToolArguments.string(arguments, "oldText", null);
            String newText = ToolArguments.string(arguments, "newText", null);
            ToolArguments.requireNonBlank("path", path);
            if (oldText == null || oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText 不能为空");
            }
            if (newText == null) {
                throw new IllegalArgumentException("newText 不能为空");
            }
            if (canonicalize(oldText).equals(canonicalize(newText))) {
                throw new IllegalArgumentException("oldText 与 newText 必须不同");
            }
            ToolArguments.requireMaximumCharacters(
                    "oldText", oldText, LocalToolLimits.MAX_PATCH_FRAGMENT_CHARACTERS);
            ToolArguments.requireMaximumCharacters(
                    "newText", newText, LocalToolLimits.MAX_PATCH_FRAGMENT_CHARACTERS);
            ToolArguments.rejectBinaryNull("oldText", oldText);
            ToolArguments.rejectBinaryNull("newText", newText);
            ToolArguments.bool(arguments, "replaceAll", false);
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public Optional<CheckpointTarget> checkpointTarget(ToolInvocation invocation)
            throws WorkspaceAccessException {
        String path = ToolArguments.string(invocation.call().arguments(), "path", null);
        ValidatedWorkspacePath validated = guard.requireRegularFile(path);
        return Optional.of(new CheckpointTarget(validated.protocolPath(), true));
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String path = ToolArguments.string(arguments, "path", null);
        String oldText = canonicalize(ToolArguments.string(arguments, "oldText", null));
        String newText = canonicalize(ToolArguments.string(arguments, "newText", null));
        boolean replaceAll = ToolArguments.bool(arguments, "replaceAll", false);
        try {
            if (invocation.cancellationToken().isCancellationRequested()) {
                return cancelled();
            }
            ValidatedWorkspacePath validated = guard.requireRegularFile(path);
            WorkspaceTextSnapshot original = WorkspaceTextSnapshotReader.read(
                    validated.realPath(), LocalToolLimits.MAX_TEXT_FILE_BYTES);
            int matches = original.countOccurrences(oldText);
            if (matches == 0) {
                return conflict("oldText 在当前文件中不存在");
            }
            if (matches > 1 && !replaceAll) {
                return conflict("oldText 匹配多处；需要更多上下文或显式 replaceAll");
            }
            if (!original.canReplace(oldText, newText)) {
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.UNSUPPORTED_ENCODING,
                        "文件的行分隔符不一致或包含裸 CR，无法在不改写无关行的前提下修改"));
            }
            Optional<ToolExecutionOutcome> gate = requirePriorRead(
                    invocation, validated, original, oldText, replaceAll, matches);
            if (gate.isPresent()) {
                return gate.get();
            }
            byte[] updatedBytes = original.replaceBytes(oldText, newText, replaceAll);
            if (updatedBytes.length > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                return ToolExecutionOutcome.failure(
                        ToolError.of(ToolErrorCode.FILE_TOO_LARGE, "修改后文件超过大小上限"));
            }
            AtomicUtf8FileWriter.replace(
                    validated.realPath(),
                    original.rawBytes(),
                    updatedBytes,
                    invocation.cancellationToken(),
                    () -> {
                        ValidatedWorkspacePath current = guard.requireRegularFile(path);
                        if (!current.realPath().equals(validated.realPath())) {
                            throw new WorkspaceAccessException(ToolError.of(
                                    ToolErrorCode.FILE_CONFLICT,
                                    "文件真实路径在写入前已改变"));
                        }
                    });
            WorkspaceWriteVerification.requireMatches(
                    guard, path, validated.realPath(), updatedBytes);
            refreshEvidence(
                    invocation,
                    validated,
                    original.replaceCanonicalText(oldText, newText, replaceAll));
            PatchResultRenderer.Rendered rendered = PatchResultRenderer.render(
                    validated.protocolPath(),
                    "modified",
                    oldText,
                    newText,
                    replaceAll ? matches : 1);
            return ToolExecutionOutcome.success(rendered.content(), rendered.metadata());
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }

    /**
     * 把模型传入的片段规范化为 {@code \n}，使 CRLF 文件也能用 LF 片段精确匹配。
     *
     * <p>规范化只作用于行分隔符；空白、缩进和其他字符保持逐字精确。</p>
     */
    private static String canonicalize(String value) {
        if (value == null || value.indexOf('\r') < 0) {
            return value;
        }
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 校验同 Session 是否已存在覆盖待修改区域的可信 Read 证据。
     *
     * <p>可信意味着三件事同时成立：证据覆盖了本次匹配所在的行区间（或整份文件）、
     * 文件大小与最后修改时间自读取以来未变、并且该区间的规范化内容摘要仍然一致。
     * 任一条不成立都返回可纠正错误，让模型先重新读取，而不是在过期理解上写文件。
     * 缓存本身不是安全边界：进程重启或 Session Resume 后登记表为空，模型只需重读一次。</p>
     */
    private Optional<ToolExecutionOutcome> requirePriorRead(
            ToolInvocation invocation,
            ValidatedWorkspacePath validated,
            WorkspaceTextSnapshot snapshot,
            String oldText,
            boolean replaceAll,
            int matches) {
        Optional<ReadEvidence> found =
                readRegistry.find(invocation.sessionId(), validated.protocolPath());
        if (found.isEmpty()) {
            return Optional.of(conflict(
                    "修改前必须先用 read_file 读取该文件的相关区域"));
        }
        ReadEvidence evidence = found.get();
        int regionFirstLine;
        int regionLastLine;
        if (replaceAll && matches > 1) {
            // 全量替换可能落在任意位置，只有整份文件的 Read 证据才足够。
            regionFirstLine = 1;
            regionLastLine = Math.max(1, snapshot.lineCount());
        } else {
            int matchIndex = snapshot.indexOf(oldText);
            regionFirstLine = snapshot.lineNumberAt(matchIndex);
            regionLastLine = snapshot.lineNumberAt(matchIndex + oldText.length());
        }
        if (!evidence.covers(regionFirstLine, regionLastLine)) {
            return Optional.of(conflict(
                    "已有 Read 证据未覆盖待修改区域；请先读取该区域"));
        }
        Optional<Identity> identity = Identity.of(validated);
        if (identity.isEmpty()
                || identity.get().sizeBytes != evidence.sizeBytes()
                || identity.get().lastModifiedMillis != evidence.lastModifiedMillis()) {
            return Optional.of(conflict("文件在上次读取后已改变；请重新读取"));
        }
        String coveredText = evidence.completeFile()
                ? snapshot.canonicalLines(1, Math.max(1, snapshot.lineCount()))
                : snapshot.canonicalLines(evidence.firstLine(), evidence.lastLine());
        if (ReadEvidence.digestOf(coveredText) != evidence.contentDigest()) {
            return Optional.of(conflict("文件内容在上次读取后已改变；请重新读取"));
        }
        return Optional.empty();
    }

    /**
     * 写入成功后用已知的新内容替换旧证据，使同 Session 连续修改不必重复整文件读取。
     */
    private void refreshEvidence(
            ToolInvocation invocation,
            ValidatedWorkspacePath validated,
            String updatedCanonicalText) {
        readRegistry.invalidate(validated.protocolPath());
        Optional<Identity> identity = Identity.of(validated);
        if (identity.isEmpty()) {
            return;
        }
        int lineCount = lineCount(updatedCanonicalText);
        readRegistry.record(invocation.sessionId(), new ReadEvidence(
                validated.protocolPath(),
                1,
                Math.max(1, lineCount),
                true,
                identity.get().sizeBytes,
                identity.get().lastModifiedMillis,
                ReadEvidence.digestOf(canonicalLines(updatedCanonicalText))));
    }

    private static int lineCount(String canonicalText) {
        if (canonicalText.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < canonicalText.length(); index++) {
            if (canonicalText.charAt(index) == '\n') {
                lines++;
            }
        }
        return canonicalText.charAt(canonicalText.length() - 1) == '\n' ? lines - 1 : lines;
    }

    private static String canonicalLines(String canonicalText) {
        if (canonicalText.endsWith("\n")) {
            return canonicalText.substring(0, canonicalText.length() - 1);
        }
        return canonicalText;
    }

    private static ToolExecutionOutcome conflict(String message) {
        return ToolExecutionOutcome.failure(ToolError.of(ToolErrorCode.FILE_CONFLICT, message));
    }

    private static ToolExecutionOutcome cancelled() {
        return ToolExecutionOutcome.failure(
                ToolError.of(ToolErrorCode.OPERATION_CANCELLED, "文件操作已取消"));
    }

    /** 判定 Read 证据是否仍然新鲜所需的文件身份。 */
    private record Identity(long sizeBytes, long lastModifiedMillis) {

        private static Optional<Identity> of(ValidatedWorkspacePath validated) {
            try {
                return Optional.of(new Identity(
                        Files.size(validated.realPath()),
                        Files.getLastModifiedTime(validated.realPath()).toMillis()));
            } catch (IOException exception) {
                return Optional.empty();
            }
        }
    }

}
