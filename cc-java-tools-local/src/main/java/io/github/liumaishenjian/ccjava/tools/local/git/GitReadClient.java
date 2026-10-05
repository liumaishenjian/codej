package io.github.liumaishenjian.ccjava.tools.local.git;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 只执行枚举 Git 读取操作的私有进程 Adapter。
 *
 * <p>调用始终使用 {@link ProcessBuilder#ProcessBuilder(List)}，不经过 Shell；固定禁用 pager、
 * color、external diff、textconv 和 optional locks。模型不能提供任意 Git 选项。</p>
 *
 * @since 0.3.0
 */
public final class GitReadClient {

    /** Git stdout 最大字节数。 */
    public static final int MAX_STDOUT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 16 * 1024;
    private static final int MAX_DIGEST_STDOUT_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Path workspace;
    private final ProcessStarter starter;
    private final java.util.function.Consumer<ReadDiagnostic> observer;
    private final Duration timeout;

    /** 包内进程接缝；生产仍固定ProcessBuilder，模型不能替换启动器。 */
    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    /**
     * 只含本次调用的安全事实；字节数为观测时已捕获量，不承载路径、正文或环境。
     * failure为空表示成功；exitCode/startupOsCode为空表示未取得相应事实。
     * 目录存在标记仅在启动失败时采集；未完成reader的诊断不代表最终输出完整。
     */
    record ReadDiagnostic(ToolErrorCode failure, Integer exitCode, int stdoutBytes, int stderrBytes,
                          boolean stdoutReadFailed, boolean stderrReadFailed,
                          boolean stdoutTruncated, boolean stderrTruncated, boolean interrupted,
                          Integer startupOsCode, boolean directoryPresentAfterStartupFailure) { }

    private static final class Probe {
        private Process process;
        private BoundedBytes stdout;
        private BoundedBytes stderr;
        private Integer startupOsCode;
        private boolean directoryPresentAfterStartupFailure;

        private ReadDiagnostic snapshot(ToolErrorCode failure) {
            Integer exit = null;
            if (process != null) {
                try { exit = process.exitValue(); } catch (IllegalThreadStateException running) { /* 未确认退出。 */ }
            }
            return new ReadDiagnostic(failure, exit, stdout == null ? 0 : stdout.captured,
                    stderr == null ? 0 : stderr.captured, stdout != null && stdout.readFailed,
                    stderr != null && stderr.readFailed, stdout != null && stdout.exceeded(),
                    stderr != null && stderr.exceeded(), Thread.currentThread().isInterrupted(),
                    startupOsCode, directoryPresentAfterStartupFailure);
        }
    }

    /**
     * 创建固定工作目录的只读 Git Adapter。
     *
     * @param workspace 已解析的真实 Workspace
     */
    public GitReadClient(Path workspace) {
        this(workspace, ProcessBuilder::start, ignored -> { }, TIMEOUT);
    }

    /** 测试只替换启动与观测，实际操作枚举、参数和错误分类仍走生产路径。 */
    GitReadClient(Path workspace, ProcessStarter starter,
                  java.util.function.Consumer<ReadDiagnostic> observer, Duration timeout) {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace 不能为空");
        this.starter = java.util.Objects.requireNonNull(starter, "starter 不能为空");
        this.observer = java.util.Objects.requireNonNull(observer, "observer 不能为空");
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout 必须为正");
    }

    /**
     * 执行 porcelain v1 状态读取。
     *
     * @return 有界 stdout 与 stderr 截断标记
     * @throws GitReadException Git 不可用、非仓库、超时或命令失败时
     */
    public GitReadResult status() throws GitReadException {
        return execute(List.of("status", "--porcelain=v1", "--branch", "--untracked-files=normal"));
    }

    /**
     * 执行 staged 或 unstaged Diff。
     *
     * @param staged 是否读取 index 相对 HEAD 的 Diff
     * @param protocolPath 可选、已经 Guard 校验的协议路径
     * @return 有界 Diff stdout 与 stderr 截断标记
     * @throws GitReadException Git 不可用、非仓库、超时或命令失败时
     */
    public GitReadResult diff(boolean staged, String protocolPath) throws GitReadException {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("diff");
        if (staged) {
            arguments.add("--cached");
        }
        arguments.add("--no-ext-diff");
        arguments.add("--no-textconv");
        arguments.add("--binary");
        if (protocolPath != null) {
            arguments.add("--");
            arguments.add(protocolPath);
        }
        return execute(arguments);
    }

    /**
     * 判断当前 Workspace 是否位于 Git work tree。
     *
     * @return 只读 rev-parse 成功且返回 true 时为 {@code true}
     */
    public boolean isRepository() {
        try {
            return requireRepository();
        } catch (GitReadException exception) {
            return false;
        }
    }

    /**
     * 严格判断当前 Workspace 是否位于 Git work tree，避免 Git 故障静默退化为全树扫描。
     *
     * @return 只读 rev-parse 明确返回 true 时为 {@code true}
     * @throws GitReadException Git 不可用、超时、非仓库或命令失败时
     */
    public boolean requireRepository() throws GitReadException {
        GitReadResult result = execute(List.of("rev-parse", "--is-inside-work-tree"));
        return result.stdout().strip().equals("true");
    }

    /**
     * 读取实时 Workspace 摘要所需的固定 Git 输入。
     *
     * <p>只枚举 index tracked 与遵循 standard excludes 的 untracked；index 与 porcelain
     * 状态单独纳入摘要，使 staged mode/blob/删除变化可被检测。</p>
     *
     * @return 有界的 NUL 分隔路径、index 状态与 porcelain v2 状态
     * @throws GitReadException Git 不可用、超时、失败或输出超限时
     */
    public WorkspaceDigestInputs workspaceDigestInputs() throws GitReadException {
        byte[] paths = executeBytes(List.of(
                "ls-files", "-z", "--cached", "--others", "--exclude-standard"),
                MAX_DIGEST_STDOUT_BYTES).stdout();
        byte[] index = executeBytes(List.of("ls-files", "-z", "--stage"),
                MAX_DIGEST_STDOUT_BYTES).stdout();
        byte[] status = executeBytes(List.of(
                "status", "--porcelain=v2", "-z", "--untracked-files=all", "--ignored=no"),
                MAX_DIGEST_STDOUT_BYTES).stdout();
        return new WorkspaceDigestInputs(paths, index, status);
    }

    private GitReadResult execute(List<String> operation) throws GitReadException {
        GitReadBytes result = executeBytes(operation, MAX_STDOUT_BYTES);
        return new GitReadResult(new String(result.stdout(), StandardCharsets.UTF_8), result.stderrTruncated());
    }

    private GitReadBytes executeBytes(List<String> operation, int maximumStdout) throws GitReadException {
        Probe probe = new Probe();
        ToolErrorCode failure = null;
        try {
            return executeBytes(operation, maximumStdout, probe);
        } catch (GitReadException exception) {
            failure = exception.error().code();
            throw exception;
        } finally {
            observer.accept(probe.snapshot(failure));
        }
    }

    private GitReadBytes executeBytes(List<String> operation, int maximumStdout, Probe probe) throws GitReadException {
        ArrayList<String> command = new ArrayList<>(List.of(
                "git", "--no-pager",
                "-c", "color.ui=false",
                "-c", "core.pager=cat",
                "-c", "pager.diff=false",
                "-c", "diff.external=",
                "-c", "diff.trustExitCode=false",
                "-c", "core.fsmonitor=false",
                "-c", "core.untrackedCache=false",
                "-c", "status.renames=false",
                "-C", workspace.toString()));
        command.addAll(operation);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.toFile());
            Map<String, String> environment = builder.environment();
            environment.put("GIT_PAGER", "cat");
            environment.put("GIT_EXTERNAL_DIFF", "");
            environment.put("GIT_OPTIONAL_LOCKS", "0");
            environment.put("LC_ALL", "C.UTF-8");
            environment.put("LANG", "C.UTF-8");
            process = starter.start(builder);
            probe.process = process;
        } catch (IOException exception) {
            // 仅提取JDK标准启动错误的数字，绝不附带IOException正文或cause（其中含路径）。
            String message = exception.getMessage();
            if (message != null) {
                var matcher = java.util.regex.Pattern.compile(": (?:CreateProcess )?error=([0-9]{1,9}),").matcher(message);
                while (matcher.find()) probe.startupOsCode = Integer.valueOf(matcher.group(1));
            }
            try { probe.directoryPresentAfterStartupFailure = java.nio.file.Files.isDirectory(workspace); }
            catch (SecurityException ignored) { /* 诊断不能改变原始GIT_UNAVAILABLE分类。 */ }
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.GIT_UNAVAILABLE, "Git 程序不可用"));
        }

        // 固定只读命令没有交互输入；立即交还stdin句柄，不依赖GC清理累计的父端管道。
        try {
            process.getOutputStream().close();
        } catch (IOException failure) {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.GIT_READ_FAILED, "Git 输入管道关闭失败"));
        }

        BoundedBytes stdout = new BoundedBytes(process.getInputStream(), maximumStdout);
        BoundedBytes stderr = new BoundedBytes(process.getErrorStream(), MAX_STDERR_BYTES);
        probe.stdout = stdout;
        probe.stderr = stderr;
        Thread stdoutThread = Thread.ofVirtual().start(stdout);
        Thread stderrThread = Thread.ofVirtual().start(stderr);
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.OPERATION_TIMED_OUT, "Git 读取被中断"));
        }
        if (!finished) {
            process.destroyForcibly();
            join(stdoutThread);
            join(stderrThread);
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.OPERATION_TIMED_OUT, "Git 读取超过时间上限"));
        }
        join(stdoutThread);
        join(stderrThread);
        // 管道读取失败与字节超限是不同事实；任何不完整读取都不能成为成功快照。
        if (stdout.readFailed || stderr.readFailed) {
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.GIT_READ_FAILED, "Git 输出读取失败"));
        }
        if (stdout.exceeded()) {
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.OUTPUT_LIMIT_EXCEEDED, "Git 输出超过字节上限"));
        }
        if (process.exitValue() != 0) {
            String errorText = stderr.text().toLowerCase(Locale.ROOT);
            ToolErrorCode code = errorText.contains("not a git repository")
                    || errorText.contains("not a git work tree")
                    ? ToolErrorCode.NOT_A_GIT_REPOSITORY
                    : ToolErrorCode.GIT_READ_FAILED;
            throw new GitReadException(ToolError.of(code, "Git 只读命令失败"));
        }
        return new GitReadBytes(stdout.bytes(), stderr.exceeded());
    }

    private static void join(Thread thread) throws GitReadException {
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitReadException(ToolError.of(
                    ToolErrorCode.OPERATION_TIMED_OUT, "Git 输出读取被中断"));
        }
    }

    /**
     * Git 摘要原始输入；所有数组均防御性复制。
     *
     * @param paths 有序路径字节
     * @param indexState Git index 状态字节
     * @param porcelainState 有界 porcelain 状态字节
     */
    public record WorkspaceDigestInputs(byte[] paths, byte[] indexState, byte[] porcelainState) {
        /** 冻结三组原始字节，避免调用方随后改变快照输入。 */
        public WorkspaceDigestInputs {
            paths = paths.clone();
            indexState = indexState.clone();
            porcelainState = porcelainState.clone();
        }
        /**
         * 返回路径序列的独立副本。
         * @return 保持原始排序与编码的路径字节
         */
        @Override public byte[] paths() { return paths.clone(); }
        /**
         * 返回 index 状态的独立副本。
         * @return 捕获时的 index 原始字节
         */
        @Override public byte[] indexState() { return indexState.clone(); }
        /**
         * 返回工作区状态的独立副本。
         * @return 有界 porcelain 原始字节
         */
        @Override public byte[] porcelainState() { return porcelainState.clone(); }
    }

    private record GitReadBytes(byte[] stdout, boolean stderrTruncated) {
        private GitReadBytes { stdout = stdout.clone(); }
        @Override public byte[] stdout() { return stdout.clone(); }
    }

    /**
     * Git 读取成功结果；stderr 正文永不暴露。
     *
     * @param stdout 严格有界的标准输出
     * @param stderrTruncated stderr 是否超过内部诊断预算
     */
    public record GitReadResult(String stdout, boolean stderrTruncated) {
    }

    /** 只携带安全 ToolError 的 Git Adapter 失败。 */
    public static final class GitReadException extends Exception {
        /** 可安全反馈给模型的结构化错误。 */
        private final ToolError error;

        GitReadException(ToolError error) {
            super(error.message());
            this.error = error;
        }

        /**
         * 返回不含原始 stderr 和绝对路径的结构化错误。
         *
         * @return 安全 ToolError
         */
        public ToolError error() {
            return error;
        }
    }

    private static final class BoundedBytes implements Runnable {
        private final InputStream input;
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean exceeded;
        private volatile boolean readFailed;
        private volatile int captured;

        private BoundedBytes(InputStream input, int maximum) {
            this.input = input;
            this.maximum = maximum;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8 * 1024];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int remaining = maximum - bytes.size();
                    if (remaining > 0) {
                        bytes.write(buffer, 0, Math.min(read, remaining));
                        captured = bytes.size();
                    }
                    if (read > remaining) {
                        exceeded = true;
                    }
                }
            } catch (IOException exception) {
                readFailed = true;
            }
        }

        boolean exceeded() {
            return exceeded;
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
