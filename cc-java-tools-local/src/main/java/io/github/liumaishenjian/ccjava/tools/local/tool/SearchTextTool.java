package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.search.RipgrepJsonEvent;
import io.github.liumaishenjian.ccjava.tools.local.search.RipgrepParsedResult;
import io.github.liumaishenjian.ccjava.tools.local.search.RipgrepSearchClient;
import io.github.liumaishenjian.ccjava.tools.local.search.SearchCancellation;
import io.github.liumaishenjian.ccjava.tools.local.search.TextSearchBackend;
import io.github.liumaishenjian.ccjava.tools.local.search.TextSearchMode;
import io.github.liumaishenjian.ccjava.tools.local.search.TextSearchRequest;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 在受控 Workspace 文本文件中执行有界精确搜索。
 *
 * <p>生产路径通过类型化请求和 JSON Lines 协议调用 ripgrep，支持正则、过滤、上下文、
 * 三种结果模式和分页；rg 不可用时只有语义等价的字面 content 子集可以降级为 Java
 * 有界扫描。外部进程返回的每条路径仍须经过 WorkspaceGuard，搜索输出同时受事件、
 * 字节、条目和 Pipeline 字符预算约束。</p>
 *
 * @since 0.3.0
 */
public final class SearchTextTool implements AgentTool {

    private static final int MAX_SNIPPET_CODE_POINTS = 240;
    private static final Set<String> ARGUMENTS = Set.of(
            "query", "path", "glob", "type", "caseSensitive", "regex", "multiline",
            "mode", "lineNumbers", "context", "beforeContext", "afterContext",
            "offset", "limit", "maxResults");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "search_text",
            "Search workspace text with bounded ripgrep content, files, and count modes.",
            """
            {"type":"object","additionalProperties":false,"required":["query"],"properties":{"query":{"type":"string","minLength":1,"maxLength":1024},"path":{"type":"string","default":"."},"glob":{"type":"string"},"type":{"type":"string"},"caseSensitive":{"type":"boolean","default":true},"regex":{"type":"boolean","default":false},"multiline":{"type":"boolean","default":false},"mode":{"type":"string","enum":["content","files","count"],"default":"content"},"lineNumbers":{"type":"boolean","default":true},"context":{"type":"integer","minimum":0,"maximum":20},"beforeContext":{"type":"integer","minimum":0,"maximum":20,"default":0},"afterContext":{"type":"integer","minimum":0,"maximum":20,"default":0},"offset":{"type":"integer","minimum":0,"maximum":10000,"default":0},"limit":{"type":"integer","minimum":0,"maximum":500,"default":100}}}
            """,
            ToolEffect.READ_WORKSPACE,
            ToolSource.BUILT_IN,
            true,
            Duration.ofSeconds(10),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;
    private final TextSearchBackend searchBackend;

    /**
     * 创建绑定 Workspace 的精确搜索工具。
     *
     * @param guard 共享路径安全边界
     */
    public SearchTextTool(WorkspaceGuard guard) {
        this(guard, new RipgrepSearchClient(
                java.util.Objects.requireNonNull(guard, "guard 不能为空").workspace()));
    }

    /**
     * 注入可替换的 ripgrep 适配器，供进程边界测试使用。
     *
     * @param guard Workspace 安全边界
     * @param searchBackend 结构化搜索后端
     */
    public SearchTextTool(WorkspaceGuard guard, TextSearchBackend searchBackend) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard 不能为空");
        this.searchBackend = java.util.Objects.requireNonNull(
                searchBackend, "searchBackend 不能为空");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            ToolArguments.rejectUnknown(arguments, ARGUMENTS);
            String query = ToolArguments.string(arguments, "query", null);
            ToolArguments.requireNonBlank("query", query);
            if (query.codePointCount(0, query.length()) > 1024) {
                throw new IllegalArgumentException("query 超过 1024 字符");
            }
            ToolArguments.requireNonBlank("path", ToolArguments.string(arguments, "path", "."));
            String glob = ToolArguments.string(arguments, "glob", null);
            if (glob != null) {
                ProtocolGlob.compile(glob);
            }
            String type = ToolArguments.string(arguments, "type", null);
            if (type != null && !type.matches("[\\p{L}\\p{N}]+")) {
                throw new IllegalArgumentException("type 只能包含 Unicode 字母或数字");
            }
            ToolArguments.bool(arguments, "caseSensitive", true);
            ToolArguments.bool(arguments, "regex", false);
            ToolArguments.bool(arguments, "multiline", false);
            TextSearchMode mode = mode(arguments);
            ToolArguments.bool(arguments, "lineNumbers", true);
            range(arguments, "beforeContext", 0, 20, 0);
            range(arguments, "afterContext", 0, 20, 0);
            range(arguments, "context", 0, 20, 0);
            range(arguments, "offset", 0, 10_000, 0);
            if (arguments.values().containsKey("limit")
                    && arguments.values().containsKey("maxResults")) {
                return ToolValidationResult.invalid(
                        "limit 与旧参数 maxResults 不能同时提供；请删除 maxResults，仅使用 limit",
                        new JsonObject(Map.of(
                                "conflictingFields", List.of("limit", "maxResults"),
                                "preferredField", "limit",
                                "removeFields", List.of("maxResults"))),
                        new JsonObject(Map.of(
                                "violation", "mutually_exclusive_fields",
                                "fields", List.of("limit", "maxResults"),
                                "correction", "remove_legacy_max_results")));
            }
            range(arguments, "limit", 0, LocalToolLimits.MAX_SEARCH_RESULTS, 100);
            if (arguments.values().containsKey("maxResults")) {
                range(arguments, "maxResults", 1, LocalToolLimits.MAX_SEARCH_RESULTS, 100);
            }
            if (mode != TextSearchMode.CONTENT
                    && (arguments.values().containsKey("context")
                    || arguments.values().containsKey("beforeContext")
                    || arguments.values().containsKey("afterContext")
                    || arguments.values().containsKey("lineNumbers"))) {
                throw new IllegalArgumentException(
                        "files/count 模式不接受上下文或 lineNumbers");
            }
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String query = ToolArguments.string(arguments, "query", null);
        String rootInput = ToolArguments.string(arguments, "path", ".");
        String glob = ToolArguments.string(arguments, "glob", null);
        String fileType = ToolArguments.string(arguments, "type", null);
        boolean caseSensitive = ToolArguments.bool(arguments, "caseSensitive", true);
        boolean regex = ToolArguments.bool(arguments, "regex", false);
        boolean multiline = ToolArguments.bool(arguments, "multiline", false);
        TextSearchMode mode = mode(arguments);
        boolean lineNumbers = ToolArguments.bool(arguments, "lineNumbers", true);
        int context = ToolArguments.integer(arguments, "context", -1);
        int beforeContext = context >= 0
                ? context : ToolArguments.integer(arguments, "beforeContext", 0);
        int afterContext = context >= 0
                ? context : ToolArguments.integer(arguments, "afterContext", 0);
        int offset = ToolArguments.integer(arguments, "offset", 0);
        int limit = arguments.values().containsKey("limit")
                ? ToolArguments.integer(arguments, "limit", 100)
                : ToolArguments.integer(arguments, "maxResults", 100);
        try {
            ValidatedWorkspacePath root = guard.requireExisting(rootInput);
            if (!Files.isDirectory(root.realPath(), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(root.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                return ToolExecutionOutcome.failure(ToolError.of(
                        ToolErrorCode.PATH_TYPE_MISMATCH,
                        "搜索目标必须是普通文件或目录"));
            }
            TextSearchRequest request = new TextSearchRequest(
                    query,
                    root.protocolPath(),
                    glob,
                    fileType,
                    mode,
                    caseSensitive,
                    regex,
                    multiline,
                    lineNumbers,
                    beforeContext,
                    afterContext,
                    offset,
                    limit,
                    SearchCancellation.from(
                            invocation.cancellationToken()::isCancellationRequested));
            try {
                return structuredOutcome(searchBackend.searchStructured(request), request, root);
            } catch (TextSearchBackend.SearchException exception) {
                if (exception.error().code()
                        != ToolErrorCode.SEARCH_UNAVAILABLE) {
                    return ToolExecutionOutcome.failure(exception.error());
                }
                if (!canUseJavaFallback(request)
                        || !Files.isDirectory(root.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return ToolExecutionOutcome.failure(exception.error());
                }
            }
            Search traversal = new Search(
                    root,
                    query,
                    glob == null ? null : ProtocolGlob.compile(glob),
                    caseSensitive,
                    limit);
            traversal.visit(root.realPath());
            return traversal.outcome();
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        } catch (IOException exception) {
            return ToolExecutionOutcome.failure(ToolError.of(
                    ToolErrorCode.EXECUTION_FAILED, "文本搜索失败"));
        }
    }

    private ToolExecutionOutcome structuredOutcome(
            RipgrepParsedResult result,
            TextSearchRequest request,
            ValidatedWorkspacePath root) {
        return switch (request.mode()) {
            case CONTENT -> contentOutcome(result, request, root);
            case FILES -> filesOutcome(result, request, root);
            case COUNT -> countOutcome(result, request, root);
        };
    }

    private ToolExecutionOutcome contentOutcome(
            RipgrepParsedResult result,
            TextSearchRequest request,
            ValidatedWorkspacePath root) {
        ArrayList<ValidatedSearchLine> safeLines = new ArrayList<>();
        long filtered = result.ignoredEvents();
        for (RipgrepJsonEvent.SearchLine line : result.content()) {
            ValidatedWorkspacePath path = validateResultPath(line.path(), root);
            if (path == null) {
                filtered++;
                continue;
            }
            safeLines.add(new ValidatedSearchLine(path.protocolPath(), line));
        }
        Page<ValidatedSearchLine> page = page(safeLines, request.offset(), request.limit());
        StringBuilder output = new StringBuilder();
        for (ValidatedSearchLine item : page.items()) {
            RipgrepJsonEvent.SearchLine line = item.line();
            output.append(item.path());
            if (request.lineNumbers() && line.lineNumber() > 0) {
                output.append(':').append(line.lineNumber());
            }
            output.append(line.kind() == RipgrepJsonEvent.LineKind.MATCH ? ": " : "- ")
                    .append(snippet(normalizeSearchText(line.text())))
                    .append('\n');
        }
        appendSummary(output, page.items().size(), safeLines.size(), result.summary());
        return successfulPage(
                output.toString(), page, filtered, request.mode(), request.offset());
    }

    private ToolExecutionOutcome filesOutcome(
            RipgrepParsedResult result,
            TextSearchRequest request,
            ValidatedWorkspacePath root) {
        ArrayList<ValidatedFile> safeFiles = new ArrayList<>();
        long filtered = result.ignoredEvents();
        for (String candidate : result.files()) {
            ValidatedWorkspacePath path = validateResultPath(candidate, root);
            if (path == null) {
                filtered++;
                continue;
            }
            try {
                safeFiles.add(new ValidatedFile(
                        path.protocolPath(),
                        Files.getLastModifiedTime(path.realPath()).toMillis()));
            } catch (IOException exception) {
                filtered++;
            }
        }
        safeFiles.sort(Comparator.comparingLong(ValidatedFile::modifiedMillis)
                .reversed()
                .thenComparing(ValidatedFile::path));
        Page<ValidatedFile> page = page(safeFiles, request.offset(), request.limit());
        StringBuilder output = new StringBuilder();
        page.items().forEach(file -> output.append(file.path()).append('\n'));
        output.append("summary: files=").append(safeFiles.size()).append('\n');
        return successfulPage(
                output.toString(), page, filtered, request.mode(), request.offset());
    }

    private ToolExecutionOutcome countOutcome(
            RipgrepParsedResult result,
            TextSearchRequest request,
            ValidatedWorkspacePath root) {
        ArrayList<CountEntry> safeCounts = new ArrayList<>();
        long filtered = result.ignoredEvents();
        for (Map.Entry<String, Long> entry : result.counts().entrySet()) {
            ValidatedWorkspacePath path = validateResultPath(entry.getKey(), root);
            if (path == null) {
                filtered++;
                continue;
            }
            safeCounts.add(new CountEntry(path.protocolPath(), entry.getValue()));
        }
        Page<CountEntry> page = page(safeCounts, request.offset(), request.limit());
        StringBuilder output = new StringBuilder();
        long returnedMatches = 0;
        for (CountEntry item : page.items()) {
            output.append(item.path()).append(": ").append(item.count()).append('\n');
            returnedMatches += item.count();
        }
        output.append("summary: files=").append(safeCounts.size())
                .append(" returnedMatches=").append(returnedMatches).append('\n');
        return successfulPage(
                output.toString(), page, filtered, request.mode(), request.offset());
    }

    private ToolExecutionOutcome successfulPage(
            String content,
            Page<?> page,
            long filtered,
            TextSearchMode mode,
            int offset) {
        boolean truncated = page.hasMore();
        JsonObject continuation = truncated
                ? new JsonObject(Map.of(
                        "mode", mode.name().toLowerCase(Locale.ROOT),
                        "offset", offset + page.items().size()))
                : JsonObject.empty();
        return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                truncated,
                truncated
                        ? ToolResultTruncationReason.ITEM_LIMIT
                        : ToolResultTruncationReason.NONE,
                content.codePointCount(0, content.length()),
                OptionalLong.empty(),
                page.items().size(),
                filtered,
                continuation));
    }

    private ValidatedWorkspacePath validateResultPath(
            String candidate,
            ValidatedWorkspacePath requestedRoot) {
        try {
            ValidatedWorkspacePath path = guard.requireRegularFile(candidate);
            boolean inside = Files.isDirectory(
                    requestedRoot.realPath(), LinkOption.NOFOLLOW_LINKS)
                    ? path.realPath().startsWith(requestedRoot.realPath())
                    : path.realPath().equals(requestedRoot.realPath());
            return inside ? path : null;
        } catch (WorkspaceAccessException exception) {
            return null;
        }
    }

    private static void appendSummary(
            StringBuilder output,
            int returned,
            int available,
            RipgrepJsonEvent.Summary summary) {
        output.append("summary: returned=").append(returned)
                .append(" available=").append(available);
        if (summary != null && summary.matches() >= 0) {
            output.append(" matches=").append(summary.matches());
        }
        output.append('\n');
    }

    private static String normalizeSearchText(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .stripTrailing()
                .replace("\n", "\\n");
    }

    private static boolean canUseJavaFallback(TextSearchRequest request) {
        return request.mode() == TextSearchMode.CONTENT
                && !request.regex()
                && !request.multiline()
                && request.fileType() == null
                && request.beforeContext() == 0
                && request.afterContext() == 0
                && request.offset() == 0
                && request.lineNumbers()
                && request.limit() > 0;
    }

    private static TextSearchMode mode(JsonObject arguments) {
        String value = ToolArguments.string(arguments, "mode", "content");
        try {
            return TextSearchMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mode 必须是 content、files 或 count");
        }
    }

    private static int range(
            JsonObject arguments,
            String name,
            int minimum,
            int maximum,
            int defaultValue) {
        int value = ToolArguments.integer(arguments, name, defaultValue);
        ToolArguments.requireRange(name, value, minimum, maximum);
        return value;
    }

    private static <T> Page<T> page(List<T> source, int offset, int limit) {
        int start = Math.min(offset, source.size());
        int end = limit == 0
                ? source.size()
                : Math.min(source.size(), start + limit);
        return new Page<>(source.subList(start, end), end < source.size());
    }

    private final class Search {
        private final ValidatedWorkspacePath root;
        private final String expected;
        private final ProtocolGlob matcher;
        private final boolean caseSensitive;
        private final int maxResults;
        private final ArrayList<Match> matches = new ArrayList<>();
        private long scannedBytes;
        private long filtered;
        private int scannedFiles;
        private ToolResultTruncationReason limitReason = ToolResultTruncationReason.NONE;

        private Search(
                ValidatedWorkspacePath root,
                String query,
                ProtocolGlob matcher,
                boolean caseSensitive,
                int maxResults) {
            this.root = root;
            this.expected = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
            this.matcher = matcher;
            this.caseSensitive = caseSensitive;
            this.maxResults = maxResults;
        }

        private void visit(Path directory) throws IOException {
            if (limited()) {
                return;
            }
            List<Path> children;
            try (var stream = Files.list(directory)) {
                children = stream.sorted(Comparator.comparing(path ->
                                path.getFileName().toString()))
                        .toList();
            }
            for (Path child : children) {
                if (limited()) {
                    return;
                }
                String protocol = protocol(root, child);
                ValidatedWorkspacePath validated;
                try {
                    validated = guard.requireExisting(protocol);
                } catch (WorkspaceAccessException exception) {
                    filtered++;
                    continue;
                }
                if (Files.isDirectory(validated.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                    visit(validated.realPath());
                } else if (Files.isRegularFile(validated.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                    searchFile(validated);
                }
            }
        }

        private void searchFile(ValidatedWorkspacePath validated) {
            String relative = root.realPath().relativize(validated.realPath())
                    .toString().replace('\\', '/');
            if (matcher != null && !matcher.matches(relative)) {
                return;
            }
            if (scannedFiles >= LocalToolLimits.MAX_SEARCH_FILES) {
                limitReason = ToolResultTruncationReason.FILE_LIMIT;
                return;
            }
            long size;
            try {
                size = Files.size(validated.realPath());
            } catch (IOException exception) {
                filtered++;
                return;
            }
            if (size > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                filtered++;
                return;
            }
            if (scannedBytes + size > LocalToolLimits.MAX_SEARCH_BYTES) {
                limitReason = ToolResultTruncationReason.SCAN_BYTE_LIMIT;
                return;
            }
            String text;
            try {
                text = Utf8TextReader.read(
                        validated.realPath(), LocalToolLimits.MAX_TEXT_FILE_BYTES);
            } catch (WorkspaceAccessException exception) {
                filtered++;
                return;
            }
            scannedFiles++;
            scannedBytes += size;
            List<String> lines = text.isEmpty() ? List.of() : text.lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                String compared = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                if (compared.contains(expected)) {
                    matches.add(new Match(validated.protocolPath(), index + 1, snippet(line)));
                    if (matches.size() >= maxResults) {
                        limitReason = ToolResultTruncationReason.ITEM_LIMIT;
                        return;
                    }
                }
            }
        }

        private boolean limited() {
            return limitReason != ToolResultTruncationReason.NONE;
        }

        private ToolExecutionOutcome outcome() {
            StringBuilder output = new StringBuilder();
            for (Match match : matches) {
                output.append(match.path()).append(':').append(match.line()).append(": ")
                        .append(match.snippet()).append('\n');
            }
            output.append("summary: matches=").append(matches.size())
                    .append(" files=").append(scannedFiles)
                    .append(" bytes=").append(scannedBytes).append('\n');
            String content = output.toString();
            boolean truncated = limited();
            return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                    truncated,
                    limitReason,
                    content.codePointCount(0, content.length()),
                    OptionalLong.empty(),
                    matches.size(),
                    filtered,
                    truncated
                            ? new JsonObject(Map.of("path", root.protocolPath()))
                            : JsonObject.empty()));
        }
    }

    private static String snippet(String line) {
        String normalized = line.strip();
        int points = normalized.codePointCount(0, normalized.length());
        if (points <= MAX_SNIPPET_CODE_POINTS) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(
                0, MAX_SNIPPET_CODE_POINTS - 1)) + "…";
    }

    private static String protocol(ValidatedWorkspacePath root, Path candidate) {
        String child = root.realPath().relativize(candidate).toString().replace('\\', '/');
        return root.protocolPath().equals(".") ? child : root.protocolPath() + "/" + child;
    }

    private record Match(String path, int line, String snippet) {
    }

    private record ValidatedSearchLine(String path, RipgrepJsonEvent.SearchLine line) {
    }

    private record CountEntry(String path, long count) {
    }

    private record ValidatedFile(String path, long modifiedMillis) {
    }

    private record Page<T>(List<T> items, boolean hasMore) {
        private Page {
            items = List.copyOf(items);
        }
    }
}
