package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandExecutionDisplay;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 把同步 Tool Pipeline 的 ASK 决策桥接为 stdio 单次审批。
 *
 * <p>协调器在任意时刻至多保存一个待决请求。只有匹配的审批 ID 可以完成等待；
 * Run 取消、连接关闭或显式关闭都会按 Deny 释放等待线程。该类型不缓存 Session
 * 授权，也不把 Tool 原始参数交给终端。</p>
 *
 * @since 0.1.0
 */
final class StdioApprovalCoordinator implements ApprovalHandler, AutoCloseable {

    private static final int MAX_PREVIEW_PATH_CHARACTERS = 512;
    private static final int MAX_NETWORK_QUERY_CHARACTERS = 512;
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private final Object lock = new Object();
    private final Consumer<Request> requestSink;
    private final Supplier<String> idSupplier;
    private volatile CommandDisplay commandDisplay;
    private Pending pending;
    private boolean closed;
    private volatile boolean filePreviews;
    private volatile io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard previewGuard;

    /** 绑定当前会话的安全路径边界；预览读取不生成模型 Read 证据。 */
    void bindPreviewGuard(io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard guard) {
        previewGuard = Objects.requireNonNull(guard);
    }

    /** 仅由stdio初始化协商启用交互正文；默认保持历史摘要契约。 */
    void enableFilePreviews(boolean enabled) {
        filePreviews = enabled;
    }

    /**
     * 使用随机关联 ID 创建协调器。
     *
     * @param requestSink 安全审批摘要的事件出口
     */
    StdioApprovalCoordinator(Consumer<Request> requestSink) {
        this(requestSink, () -> UUID.randomUUID().toString(), CommandDisplay.platform());
    }

    /**
     * 使用 Runtime 已冻结的命令执行配置创建协调器。
     *
     * @param requestSink 安全审批摘要的事件出口
     * @param commandDisplay 与执行 Tool 同源的非 Secret Shell/cwd 描述
     */
    StdioApprovalCoordinator(
            Consumer<Request> requestSink,
            CommandDisplay commandDisplay) {
        this(requestSink, () -> UUID.randomUUID().toString(), commandDisplay);
    }

    /**
     * 使用可注入 ID 创建可确定性验证的协调器。
     *
     * @param requestSink 安全审批摘要的事件出口
     * @param idSupplier 唯一审批 ID 来源
     */
    StdioApprovalCoordinator(
            Consumer<Request> requestSink,
            Supplier<String> idSupplier) {
        this(requestSink, idSupplier, CommandDisplay.platform());
    }

    private StdioApprovalCoordinator(
            Consumer<Request> requestSink,
            Supplier<String> idSupplier,
            CommandDisplay commandDisplay) {
        this.requestSink = Objects.requireNonNull(requestSink, "requestSink 不能为空");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier 不能为空");
        this.commandDisplay = Objects.requireNonNull(commandDisplay, "commandDisplay 不能为空");
    }

    /**
     * 发布审批请求并等待匹配决定。
     *
     * @param invocation 当前 Tool 调用
     * @param definition Tool Definition
     * @return Allow 或 Deny；取消和关闭均返回 Deny
     */
    @Override
    public ApprovalResponse requestApproval(
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionOutcome outcome) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(outcome, "outcome 不能为空");
        if (invocation.cancellationToken().isCancellationRequested()) {
            return ApprovalResponse.deny();
        }

        String approvalId = requireId(idSupplier.get());
        Pending current = new Pending(
                new Request(
                        approvalId,
                        invocation.runId(),
                        invocation.ordinal(),
                        definition.name(),
                        definition.effect(),
                        outcome.selector(),
                        preview(invocation, definition)),
                new CompletableFuture<>());
        synchronized (lock) {
            if (closed) {
                return ApprovalResponse.deny();
            }
            if (pending != null) {
                throw new IllegalStateException("同一连接只能等待一个审批");
            }
            pending = current;
        }

        try (CancellationToken.Registration ignored =
                     invocation.cancellationToken().onCancellation(
                             () -> resolveInternally(approvalId, ApprovalResponse.deny()))) {
            if (!current.decision().isDone()) {
                try {
                    requestSink.accept(current.request());
                } catch (RuntimeException failure) {
                    resolveInternally(approvalId, ApprovalResponse.deny());
                }
            }
            return current.decision().join();
        } finally {
            synchronized (lock) {
                if (pending == current) {
                    pending = null;
                }
            }
        }
    }

    /**
     * 使用终端返回的单次决定完成待决请求。
     *
     * @param approvalId 终端看到的审批 ID
     * @param decision 最终 Allow 或 Deny
     * @return ID 匹配且首次完成时为 {@code true}
     */
    boolean resolve(String approvalId, ApprovalResponse decision) {
        Objects.requireNonNull(approvalId, "approvalId 不能为空");
        Objects.requireNonNull(decision, "decision 不能为空");
        return resolveInternally(approvalId, decision);
    }

    Request pendingRequest() {
        synchronized (lock) {
            return pending == null ? null : pending.request();
        }
    }

    /**
     * 在 Runtime Session 完成真实 Tool 装配后绑定唯一命令显示来源。
     *
     * @param display 与实际 LocalCommandExecutor 同源的事实
     */
    void bindCommandDisplay(CommandExecutionDisplay display) {
        Objects.requireNonNull(display, "display 不能为空");
        synchronized (lock) {
            if (pending != null) {
                throw new IllegalStateException("存在待决审批时不能替换命令显示配置");
            }
            commandDisplay = new CommandDisplay(display.shell(), display.workingDirectory());
        }
    }

    private boolean resolveInternally(
            String approvalId,
            ApprovalResponse decision) {
        synchronized (lock) {
            if (pending == null
                    || !pending.request().approvalId().equals(approvalId)) {
                return false;
            }
            return pending.decision().complete(decision);
        }
    }

    /**
     * 关闭连接并拒绝仍在等待的请求。
     */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (pending != null) {
                pending.decision().complete(ApprovalResponse.deny());
            }
        }
    }

    private static String requireId(String value) {
        Objects.requireNonNull(value, "approvalId 不能为空");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("approvalId 为空或过长");
        }
        return value;
    }

    private Preview preview(
            ToolInvocation invocation,
            ToolDefinition definition) {
        String name = definition.name();
        if (definition.effect() == ToolEffect.NETWORK_OR_REMOTE
                && "web_search".equals(name)) {
            String query;
            try {
                query = invocation.call().arguments().string("query").orElse("");
            } catch (IllegalArgumentException exception) {
                return Preview.unavailable();
            }
            if (query.isBlank()
                    || query.codePointCount(0, query.length()) > MAX_NETWORK_QUERY_CHARACTERS
                    || query.codePoints().anyMatch(Character::isISOControl)) {
                return Preview.unavailable();
            }
            return Preview.webSearch(query);
        }
        if ("run_command".equals(name)) {
            return commandPreview(invocation.call());
        }
        if (!"apply_patch".equals(name) && !"write_file".equals(name)) {
            return Preview.unavailable();
        }
        JsonPreviewArguments arguments = JsonPreviewArguments.from(invocation);
        String target = safeRelativePath(arguments.path());
        if (target.isEmpty()) {
            return Preview.unavailable();
        }
        String operation = "apply_patch".equals(name) ? "modify" : "create";
        Preview summary = new Preview(
                target,
                operation,
                lineCount(arguments.oldText()),
                lineCount(arguments.newText()),
                "",
                "",
                "",
                "",
                "");
        if (!filePreviews || definition.source() != io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN) return summary;
        return summary.withChange(fileChange(invocation, arguments));
    }

    /** 小文件的完整候选仅用于审阅；读取或精确定位失败时保留无行号片段。 */
    private FileChange fileChange(ToolInvocation invocation, JsonPreviewArguments arguments) {
        FileChange fragment = FileChange.from(arguments.oldText(), arguments.newText());
        if (!"available".equals(fragment.status())) return fragment;
        String safe = safeRelativePath(arguments.path());
        if (safe.isEmpty()) return new FileChange("", "", "redacted");
        var guard = previewGuard;
        if (guard == null || invocation.cancellationToken().isCancellationRequested()) return fragment;
        if ("write_file".equals(invocation.call().name())) {
            try {
                guard.requireNewFile(arguments.path());
                return fragment.withScope("file");
            } catch (io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException ignored) {
                return fragment;
            }
        }
        try {
            var path = guard.requireRegularFile(arguments.path());
            var snapshot = io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceTextSnapshotReader.read(path.realPath(), 24576);
            if (!guard.requireRegularFile(arguments.path()).realPath().equals(path.realPath())) return fragment;
            String before = snapshot.canonicalText();
            String oldText = arguments.oldText().replace("\r\n", "\n").replace('\r', '\n');
            String newText = arguments.newText().replace("\r\n", "\n").replace('\r', '\n');
            if (oldText.isEmpty()) return fragment;
            boolean all = Boolean.TRUE.equals(invocation.call().arguments().values().get("replaceAll"));
            int matches = snapshot.countOccurrences(oldText);
            if (matches == 0 || (!all && matches > 1) || !snapshot.canReplace(oldText, newText)) return fragment;
            if (before.length() > 6000) return fragment;
            long occurrences = all ? matches : 1;
            if (before.length() + occurrences * (newText.length() - oldText.length()) > 6000) return fragment;
            String after = snapshot.replaceCanonicalText(oldText, newText, all);
            FileChange full = FileChange.from(before, after);
            return "too_large".equals(full.status()) ? fragment : full.withScope("file");
        } catch (io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException | IllegalArgumentException ignored) {
            Path candidate = guard.workspace().resolve(safe).normalize();
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return new FileChange("", "", "redacted");
            }
            return fragment;
        }
    }

    /**
     * 为审批与无审批的 {@code tool.started} 生成同一份命令显示事实。
     *
     * <p>完整命令来自结构化 ToolCall；Shell 与工作目录来自可信 Runtime 配置。
     * 无法满足 Tool 的显示安全边界时返回 unavailable，调用方不得猜测。</p>
     */
    Preview commandPreview(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        if (!"run_command".equals(call.name())) {
            return Preview.unavailable();
        }
        String command;
        try {
            command = call.arguments().string("command").orElse("");
        } catch (IllegalArgumentException exception) {
            return Preview.unavailable();
        }
        if (command.isBlank()
                || command.codePointCount(0, command.length())
                > LocalToolLimits.MAX_COMMAND_CHARACTERS
                || command.codePoints().anyMatch(character ->
                        Character.isISOControl(character)
                                && character != '\r'
                                && character != '\n'
                                && character != '\t')) {
            return Preview.unavailable();
        }
        return new Preview(
                "",
                "execute",
                0,
                0,
                command,
                commandDisplay.shell(),
                commandDisplay.workingDirectory(),
                "",
                "");
    }

    private static String safeRelativePath(String raw) {
        if (raw == null
                || raw.isBlank()
                || raw.length() > MAX_PREVIEW_PATH_CHARACTERS
                || raw.startsWith("/")
                || raw.startsWith("\\")
                || WINDOWS_DRIVE.matcher(raw).matches()) {
            return "";
        }
        String normalized = raw.replace('\\', '/');
        String[] segments = normalized.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || containsControl(segment)) {
                return "";
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] lines = text.split("\\R", -1);
        return lines.length - (lines[lines.length - 1].isEmpty() ? 1 : 0);
    }

    /**
     * 可以进入 stdio 协议的脱敏审批摘要。
     *
     * @param approvalId 单次审批关联 ID
     * @param runId 所属 Run
     * @param ordinal Tool 序号
     * @param toolName 固定 Tool 名称
     * @param effect Tool 最高副作用
     * @param scope 可用于 Session Allow 的具体规范化范围
     * @param preview 只含相对路径、命令或受控网络查询的专用预览
     */
    record Request(
            String approvalId,
            RunId runId,
            int ordinal,
            String toolName,
            ToolEffect effect,
            io.github.liumaishenjian.ccjava.domain.PermissionSelector scope,
            Preview preview) {

        Request {
            requireId(approvalId);
            runId = Objects.requireNonNull(runId, "runId 不能为空");
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            Objects.requireNonNull(toolName, "toolName 不能为空");
            effect = Objects.requireNonNull(effect, "effect 不能为空");
            scope = Objects.requireNonNull(scope, "scope 不能为空");
            preview = Objects.requireNonNull(preview, "preview 不能为空");
        }
    }

    /**
     * 与真实命令执行配置同源的非 Secret 显示描述。
     *
     * <p>工作目录固定为 Workspace-relative {@code .}，避免 stdio 暴露用户绝对路径。
     * 后端与 Shell 组合由 {@link io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions}
     * 在可信 Composition Root 冻结。</p>
     *
     * @param shell 实际执行后端的稳定 Shell ID
     * @param workingDirectory 固定命令 cwd 的 Workspace-relative 表达
     */
    record CommandDisplay(String shell, String workingDirectory) {
        CommandDisplay {
            shell = Objects.requireNonNull(shell, "shell 不能为空");
            workingDirectory = Objects.requireNonNull(
                    workingDirectory, "workingDirectory 不能为空");
            if (shell.isBlank() || shell.length() > 64 || !".".equals(workingDirectory)) {
                throw new IllegalArgumentException("命令显示配置无效");
            }
        }

        static CommandDisplay platform() {
            return new CommandDisplay(CommandShell.current().id(), ".");
        }
    }

    /**
     * stdio专用审批摘要；只有显式启用的新界面附带经过拒显检测的有界文件片段。
     *
     * @param target Workspace-relative 目标；不可安全展示时为空
     * @param operation {@code modify}、{@code create}、{@code execute}、{@code search} 或 {@code unavailable}
     * @param removedLines 预计删除行数
     * @param addedLines 预计新增行数
     * @param command 已批准的完整命令正文；文件操作时为空
     * @param shell 固定 Shell ID；文件操作时为空
     * @param workingDirectory Workspace-relative 工作目录
     * @param networkDestination 固定网络目的类型；非网络操作时为空
     * @param networkQuery 将发送给受控搜索 Provider 的有界查询；非网络操作时为空
     * @param change 交互专用变更意图；旧客户端或非内置文件工具为null
     */
    record Preview(
            String target,
            String operation,
            int removedLines,
            int addedLines,
            String command,
            String shell,
            String workingDirectory,
            String networkDestination,
            String networkQuery,
            FileChange change) {

        Preview(String target, String operation, int removedLines, int addedLines,
                String command, String shell, String workingDirectory,
                String networkDestination, String networkQuery) {
            this(target, operation, removedLines, addedLines, command, shell, workingDirectory,
                    networkDestination, networkQuery, null);
        }

        Preview withChange(FileChange value) {
            return new Preview(target, operation, removedLines, addedLines, command, shell,
                    workingDirectory, networkDestination, networkQuery, value);
        }

        Preview {
            target = Objects.requireNonNull(target, "target 不能为空");
            operation = Objects.requireNonNull(operation, "operation 不能为空");
            command = Objects.requireNonNull(command, "command 不能为空");
            shell = Objects.requireNonNull(shell, "shell 不能为空");
            workingDirectory = Objects.requireNonNull(
                    workingDirectory, "workingDirectory 不能为空");
            networkDestination = Objects.requireNonNull(
                    networkDestination, "networkDestination 不能为空");
            networkQuery = Objects.requireNonNull(networkQuery, "networkQuery 不能为空");
            if (target.length() > MAX_PREVIEW_PATH_CHARACTERS
                    || (!"modify".equals(operation)
                    && !"create".equals(operation)
                    && !"execute".equals(operation)
                    && !"search".equals(operation)
                    && !"unavailable".equals(operation))
                    || removedLines < 0
                    || addedLines < 0
                    || command.codePointCount(0, command.length())
                    > LocalToolLimits.MAX_COMMAND_CHARACTERS
                    || shell.length() > 64
                    || workingDirectory.length() > MAX_PREVIEW_PATH_CHARACTERS
                    || networkDestination.length() > 64
                    || networkQuery.codePointCount(0, networkQuery.length()) > MAX_NETWORK_QUERY_CHARACTERS
                    || networkQuery.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("审批预览字段无效");
            }
        }

        static Preview unavailable() {
            return new Preview("", "unavailable", 0, 0, "", "", "", "", "");
        }

        static Preview webSearch(String query) {
            return new Preview("", "search", 0, 0, "", "", "",
                    "configured_web_search_provider", query);
        }
    }

    /**
     * 交互专用的有界修改意图，可包含审批时快照，但不是执行成功证据。
     * @param before 待替换片段；拒显时为空
     * @param after 待写入片段；拒显时为空
     * @param status available、redacted或too_large；后两者不得含正文
     * @param scope file表示完整文件坐标，fragment表示无法提供文件行号的意图片段
     */
    record FileChange(String before, String after, String status, String scope) {
        FileChange(String before, String after, String status) { this(before, after, status, "fragment"); }
        FileChange withScope(String value) { return new FileChange(before, after, status, value); }
        static FileChange from(String before, String after) {
            if (before.length() > 6000 || after.length() > 6000) {
                return new FileChange("", "", "too_large");
            }
            var policy = new io.github.liumaishenjian.ccjava.tools.local.memory.SecretCandidatePolicy();
            if (policy.isSecretCandidate(before) || policy.isSecretCandidate(after)
                    || (before + after).codePoints().anyMatch(c -> Character.isISOControl(c)
                    && c != '\n' && c != '\r' && c != '\t')) {
                return new FileChange("", "", "redacted");
            }
            return new FileChange(before, after, "available");
        }
    }

    private record JsonPreviewArguments(
            String path,
            String oldText,
            String newText) {

        private static JsonPreviewArguments from(ToolInvocation invocation) {
            var arguments = invocation.call().arguments();
            try {
                String name = invocation.call().name();
                return new JsonPreviewArguments(
                        arguments.string("path").orElse(""),
                        "apply_patch".equals(name)
                                ? arguments.string("oldText").orElse("") : "",
                        "apply_patch".equals(name)
                                ? arguments.string("newText").orElse("")
                                : arguments.string("content").orElse(""));
            } catch (IllegalArgumentException exception) {
                return new JsonPreviewArguments("", "", "");
            }
        }
    }

    private record Pending(
            Request request,
            CompletableFuture<ApprovalResponse> decision) {
    }
}
