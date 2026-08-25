package io.github.liumaishenjian.ccjava.cli.hooks;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.hook.HookHandler;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.tools.local.process.ManagedProcessLauncher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 通过固定 argv 启动本地进程的 S09 Command Hook Adapter。
 *
 * <p>该 Adapter 位于 CLI 边缘，只把已经脱敏的 {@link HookInvocation} 作为一条 JSON
 * stdin 写入外部进程，并且只接受有界的 JSON stdout 意见。它不经过 Shell、不继承父进程
 * 环境，也不把 stderr、命令行或原始异常文本带回 Core。进程超时、取消、输出超限和协议
 * 错误均返回结构化非完成状态，再由 {@code HookCoordinator} 按绑定的 Fail-Open/Closed
 * 策略收敛。</p>
 *
 * @since 0.1.0
 */
public final class CommandHookHandler implements HookHandler {

    /** Command Hook 默认最多读取的 stdout/stderr 字节数。 */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1_024;
    /** Command Hook 默认墙钟上限。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_COMMAND_ARGUMENTS = 64;
    private static final int MAX_ARGUMENT_CHARACTERS = 4_096;
    private static final int MAX_TOTAL_COMMAND_CHARACTERS = 32 * 1_024;
    private static final int MAX_INPUT_BYTES = 256 * 1_024;
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "disposition", "reason", "additionalContext");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String handlerId;
    private final List<String> command;
    private final Path workspace;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final ProcessLauncher launcher;

    /**
     * 创建使用 JDK {@link ProcessBuilder} 的固定命令 Handler。
     *
     * @param handlerId 稳定 Handler ID
     * @param command 不经 Shell 解释的完整 argv；第一个元素必须是绝对可执行路径
     * @param workspace 外部进程的固定真实工作目录
     * @param timeout 单次调用墙钟上限
     * @param maxOutputBytes stdout/stderr 各自的硬上限
     */
    public CommandHookHandler(
            String handlerId,
            List<String> command,
            Path workspace,
            Duration timeout,
            int maxOutputBytes) {
        this(handlerId, command, workspace, timeout, maxOutputBytes, JdkProcessLauncher.INSTANCE);
    }

    /**
     * 创建注入进程边界的 Handler，供确定性测试验证协议与清理。
     */
    CommandHookHandler(
            String handlerId,
            List<String> command,
            Path workspace,
            Duration timeout,
            int maxOutputBytes,
            ProcessLauncher launcher) {
        this.handlerId = requireText(handlerId, "handlerId");
        this.command = validateCommand(command);
        this.workspace = realDirectory(workspace);
        this.timeout = validateTimeout(timeout);
        if (maxOutputBytes < 256 || maxOutputBytes > 1_048_576) {
            throw new IllegalArgumentException("maxOutputBytes 必须在 256 到 1048576 之间");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.launcher = Objects.requireNonNull(launcher, "launcher 不能为空");
    }

    @Override
    public String id() {
        return handlerId;
    }

    @Override
    public HookExecutionResult execute(
            HookInvocation invocation,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) {
            return failure(HookExecutionStatus.CANCELLED, "Hook 调用已取消");
        }

        byte[] input;
        try {
            input = encodeInvocation(invocation);
        } catch (RuntimeException | IOException failure) {
            return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook 输入编码失败");
        }

        CommandProcess process;
        try {
            process = launcher.start(command, workspace);
        } catch (IOException | RuntimeException failure) {
            return failure(HookExecutionStatus.FAILED, "Hook 进程启动失败");
        }

        BoundedBytes stdout = new BoundedBytes(maxOutputBytes);
        BoundedBytes stderr = new BoundedBytes(maxOutputBytes);
        Thread stdoutThread = Thread.ofVirtual().name("cc-java-hook-stdout-" + handlerId).start(
                () -> stdout.read(process.stdout()));
        Thread stderrThread = Thread.ofVirtual().name("cc-java-hook-stderr-" + handlerId).start(
                () -> stderr.read(process.stderr()));
        AtomicReference<IOException> inputFailure = new AtomicReference<>();
        Thread inputThread = Thread.ofVirtual().name("cc-java-hook-stdin-" + handlerId).start(() -> {
            try {
                writeInput(process.stdin(), input);
            } catch (IOException failure) {
                inputFailure.set(failure);
            }
        });
        AtomicReference<CommandHookOutcome> outcome = new AtomicReference<>();
        CancellationToken.Registration cancellation = cancellationToken.onCancellation(() -> {
            process.destroyTree();
            outcome.compareAndSet(null,
                    new CommandHookOutcome(HookExecutionStatus.CANCELLED, "Hook 调用已取消"));
        });
        try {
            if (!await(process, cancellationToken)) {
                CommandHookOutcome cancelled = outcome.get();
                if (cancelled != null) {
                    return failure(cancelled.status(), cancelled.reason());
                }
                process.destroyTree();
                return failure(HookExecutionStatus.TIMED_OUT, "Hook 超过时间上限");
            }
            inputThread.join();
            join(stdoutThread, stderrThread);
            if (outcome.get() != null) {
                CommandHookOutcome cancelled = outcome.get();
                return failure(cancelled.status(), cancelled.reason());
            }
            if (stdout.exceeded() || stderr.exceeded()) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook 输出超过上限");
            }
            if (inputFailure.get() != null) {
                return failure(HookExecutionStatus.FAILED, "Hook stdin 写入失败");
            }
            if (process.exitCode() != 0) {
                return failure(HookExecutionStatus.FAILED, "Hook 进程返回非零状态");
            }
            return decodeResult(stdout.bytes());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyTree();
            return failure(HookExecutionStatus.CANCELLED, "Hook 调用被中断");
        } catch (RuntimeException failure) {
            process.destroyTree();
            return failure(HookExecutionStatus.FAILED, "Hook 进程通信失败");
        } finally {
            cancellation.close();
            close(process.stdin());
            if (process.isAlive()) {
                process.destroyTree();
            }
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            joinQuietly(inputThread);
        }
    }

    private byte[] encodeInvocation(HookInvocation invocation) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("event", invocation.event().name());
        root.put("sessionId", invocation.sessionId().value());
        invocation.runId().ifPresent(run -> root.put("runId", run.value()));
        root.put("subject", invocation.subject());
        root.set("data", MAPPER.valueToTree(invocation.data().jsonValues()));
        byte[] bytes = MAPPER.writeValueAsBytes(root);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Hook 输入超过上限");
        }
        return bytes;
    }

    private HookExecutionResult decodeResult(byte[] bytes) {
        try {
            JsonNode node = MAPPER.readTree(bytes);
            if (node == null || !node.isObject()) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook stdout 必须是 JSON Object");
            }
            ObjectNode object = (ObjectNode) node;
            var fields = object.properties().stream().map(entry -> entry.getKey()).toList();
            if (!OUTPUT_FIELDS.containsAll(fields) || !fields.contains("disposition")) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook stdout 字段不符合协议");
            }
            JsonNode dispositionNode = object.get("disposition");
            if (dispositionNode == null || !dispositionNode.isTextual()) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook disposition 无效");
            }
            HookDisposition disposition;
            try {
                disposition = HookDisposition.valueOf(dispositionNode.asText());
            } catch (IllegalArgumentException invalid) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook disposition 无效");
            }
            var reason = optionalText(object, "reason");
            var context = optionalText(object, "additionalContext");
            if (reason.invalid() || context.invalid()) {
                return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook 摘要字段无效");
            }
            return new HookExecutionResult(
                    handlerId,
                    disposition,
                    HookExecutionStatus.COMPLETED,
                    reason.value(),
                    context.value());
        } catch (Exception invalid) {
            return failure(HookExecutionStatus.INVALID_OUTPUT, "Hook stdout 不是合法协议");
        }
    }

    private boolean await(CommandProcess process, CancellationToken token)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (token.isCancellationRequested()) {
                process.destroyTree();
                return false;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                    TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }

    private void writeInput(OutputStream stream, byte[] input) throws IOException {
        try (OutputStream output = stream) {
            output.write(input);
            output.write('\n');
            output.flush();
        }
    }

    private HookExecutionResult failure(HookExecutionStatus status, String reason) {
        return new HookExecutionResult(
                handlerId,
                HookDisposition.CONTINUE,
                status,
                java.util.Optional.of(reason),
                java.util.Optional.empty());
    }

    private static TextValue optionalText(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null) {
            return TextValue.absent();
        }
        if (!node.isTextual()
                || node.asText().codePointCount(0, node.asText().length())
                        > HookExecutionResult.MAX_TEXT_CHARACTERS) {
            return TextValue.invalidValue();
        }
        return TextValue.valueOf(node.asText());
    }

    private static List<String> validateCommand(List<String> source) {
        Objects.requireNonNull(source, "command 不能为空");
        if (source.isEmpty() || source.size() > MAX_COMMAND_ARGUMENTS) {
            throw new IllegalArgumentException("command 参数数量无效");
        }
        int total = 0;
        List<String> copy = new ArrayList<>(source.size());
        for (String argument : source) {
            Objects.requireNonNull(argument, "command 参数不能为空");
            if (argument.isEmpty()
                    || argument.codePointCount(0, argument.length()) > MAX_ARGUMENT_CHARACTERS) {
                throw new IllegalArgumentException("command 参数长度无效");
            }
            total += argument.length();
            copy.add(argument);
        }
        if (total > MAX_TOTAL_COMMAND_CHARACTERS) {
            throw new IllegalArgumentException("command 总长度超过上限");
        }
        if (!Path.of(copy.getFirst()).isAbsolute()) {
            throw new IllegalArgumentException("command 第一个参数必须是绝对可执行路径");
        }
        return List.copyOf(copy);
    }

    private static Path realDirectory(Path source) {
        Objects.requireNonNull(source, "workspace 不能为空");
        try {
            Path real = source.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("workspace 必须是目录");
            }
            return real;
        } catch (IOException failure) {
            throw new IllegalArgumentException("workspace 不能解析");
        }
    }

    private static Duration validateTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout 不能为空");
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 必须在 1ms 到 30s 之间");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 256) {
            throw new IllegalArgumentException(field + " 长度无效");
        }
        return value;
    }

    private static void join(Thread thread, Thread other) throws InterruptedException {
        thread.join();
        other.join();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        CommandProcess start(List<String> command, Path workspace) throws IOException;
    }

    interface CommandProcess {
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;

        int exitCode();

        boolean isAlive();

        void destroyTree();
    }

    private enum JdkProcessLauncher implements ProcessLauncher {
        INSTANCE;

        private final ManagedProcessLauncher launcher = new ManagedProcessLauncher();

        @Override
        public CommandProcess start(List<String> command, Path workspace) throws IOException {
            ManagedProcessLauncher.ManagedProcess process = launcher.start(
                    new ManagedProcessLauncher.LaunchRequest(
                            Path.of(command.getFirst()),
                            command.subList(1, command.size()),
                            workspace,
                            java.util.Map.of("CC_JAVA_HOOK_PROTOCOL", "1")));
            return new JdkCommandProcess(process);
        }
    }

    private record JdkCommandProcess(
            ManagedProcessLauncher.ManagedProcess process) implements CommandProcess {
        @Override
        public OutputStream stdin() {
            return process.stdin();
        }

        @Override
        public InputStream stdout() {
            return process.stdout();
        }

        @Override
        public InputStream stderr() {
            return process.stderr();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        @Override
        public int exitCode() {
            return process.exitCode();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void destroyTree() {
            process.destroyTree();
        }
    }

    private static final class BoundedBytes {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean exceeded;

        private BoundedBytes(int limit) {
            this.limit = limit;
        }

        private void read(InputStream input) {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4_096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (bytes.size() < limit) {
                        int accepted = Math.min(read, limit - bytes.size());
                        bytes.write(buffer, 0, accepted);
                        if (accepted < read) {
                            exceeded = true;
                        }
                    } else {
                        exceeded = true;
                    }
                }
            } catch (IOException ignored) {
                // 进程终止时关闭管道是正常清理路径；解析层会按无效输出处理空结果。
            }
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }

        private boolean exceeded() {
            return exceeded;
        }
    }

    private record CommandHookOutcome(HookExecutionStatus status, String reason) {
    }

    private record TextValue(java.util.Optional<String> value, boolean invalid) {
        private static TextValue absent() {
            return new TextValue(java.util.Optional.empty(), false);
        }

        private static TextValue valueOf(String value) {
            return new TextValue(java.util.Optional.of(value), false);
        }

        private static TextValue invalidValue() {
            return new TextValue(java.util.Optional.empty(), true);
        }
    }
}
