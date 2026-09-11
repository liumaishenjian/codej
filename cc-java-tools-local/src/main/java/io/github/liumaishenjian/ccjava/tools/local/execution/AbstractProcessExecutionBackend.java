package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolOutputSink;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend;
import io.github.liumaishenjian.ccjava.domain.ToolOutputStream;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementReport;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionFailure;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionOutcome;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.tools.local.command.ProcessTreeTerminator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * JDK {@link ProcessBuilder} 后端的共同有界 I/O、取消和进程树清理实现。
 *
 * <p>子类只编译固定 argv 和实际 enforcement report。计划在 {@code start()} 前完整构造；
 * 一旦进程启动，任何失败都不会触发 Local 重放。timeout、取消和线程中断均收敛到同一棵
 * 进程树清理路径。</p>
 *
 * @since 0.13.0
 */
abstract class AbstractProcessExecutionBackend implements ExecutionBackend {
    private final ProcessTreeTerminator terminator = new ProcessTreeTerminator();

    /**
     * 将已验证请求编译为不可变平台启动计划。
     *
     * @param request 已通过 selector 与 policy Gate 的请求
     * @return 启动前完整构造的计划
     * @throws IOException 平台身份或参数无法安全转换时
     */
    protected abstract Plan plan(ExecutionRequest request) throws IOException;

    /**
     * 返回本后端实际能够证明的 enforcement 状态。
     *
     * @param fallback 是否以显式 Local fallback 执行
     * @return 与本次实际路径绑定的报告
     */
    protected abstract EnforcementReport report(boolean fallback);

    /**
     * timeout、取消或普通终态后的后端专属清理；默认无额外资源。
     *
     * @param plan 包含可选清理 identity 的启动计划
     */
    protected void cleanup(Plan plan) {
    }

    @Override
    public ExecutionOutcome execute(
            ExecutionRequest request,
            CancellationToken cancellation,
            ToolOutputSink sink) throws IOException {
        Plan plan = plan(request);
        ProcessBuilder builder = new ProcessBuilder(plan.argv());
        builder.directory(plan.hostCwd().toFile());
        builder.redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(plan.environment());

        Process process = builder.start();
        writeStdin(process, plan.stdin());
        Output output = new Output(request.outputCharacterLimit());
        Thread stdout = pump(process.getInputStream(), ToolOutputStream.STDOUT, output, sink);
        Thread stderr = pump(process.getErrorStream(), ToolOutputStream.STDERR, output, sink);
        long deadline = System.nanoTime() + request.timeout().toNanos();
        boolean timedOut = false;
        boolean cancelled = false;

        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancellation.isCancellationRequested()) {
                    cancelled = true;
                    terminator.terminate(process);
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    terminator.terminate(process);
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelled = true;
            terminator.terminate(process);
            cleanup(plan);
        }

        join(stdout);
        join(stderr);
        cleanup(plan);
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        Optional<ExecutionFailure> failure = failure(timedOut, cancelled);
        return output.outcome(
                exitCode,
                timedOut,
                cancelled,
                report(false),
                failure);
    }

    private static void writeStdin(Process process, byte[] stdin) throws IOException {
        try (var output = process.getOutputStream()) {
            if (stdin.length > 0) {
                output.write(stdin);
            }
        }
    }

    private static Optional<ExecutionFailure> failure(boolean timedOut, boolean cancelled) {
        if (timedOut) {
            return Optional.of(new ExecutionFailure(
                    ExecutionFailure.Kind.TIMED_OUT,
                    "PROCESS_TIMED_OUT",
                    true));
        }
        if (cancelled) {
            return Optional.of(new ExecutionFailure(
                    ExecutionFailure.Kind.CANCELLED,
                    "PROCESS_CANCELLED",
                    true));
        }
        return Optional.empty();
    }

    private static Thread pump(
            InputStream input,
            ToolOutputStream stream,
            Output output,
            ToolOutputSink sink) {
        return Thread.ofVirtual().start(() -> {
            try (input;
                 var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    String retained = output.append(stream, new String(buffer, 0, count));
                    if (!retained.isEmpty()) {
                        try {
                            sink.publish(stream, retained);
                        } catch (RuntimeException ignored) {
                            // 事件消费者失败不能跳过进程清理或改变命令终态。
                        }
                    }
                }
            } catch (IOException ignored) {
                // exitCode 与 stderr 是命令失败的权威输出，pump 只负责有界转发。
            }
        });
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000);
            if (thread.isAlive()) {
                thread.interrupt();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 平台边缘内部的不可变进程启动计划。 */
    protected record Plan(
            List<String> argv,
            Path hostCwd,
            Map<String, String> environment,
            byte[] stdin,
            Optional<String> cleanupIdentity) {
        protected Plan {
            argv = List.copyOf(argv);
            environment = Map.copyOf(environment);
            stdin = stdin.clone();
            cleanupIdentity = Optional.ofNullable(cleanupIdentity).orElseGet(Optional::empty);
        }

        protected Plan(
                List<String> argv,
                Path hostCwd,
                Map<String, String> environment,
                byte[] stdin) {
            this(argv, hostCwd, environment, stdin, Optional.empty());
        }

        /** 内部诊断不得展开 argv、环境值、stdin 或清理 identity。 */
        @Override
        public String toString() {
            return "Plan[argumentCount=" + argv.size()
                    + ", environmentEntryCount=" + environment.size()
                    + ", stdinBytes=" + stdin.length
                    + ", cleanupIdentityPresent=" + cleanupIdentity.isPresent() + "]";
        }
    }

    private static final class Output {
        private final int limit;
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();
        private long originalCharacters;
        private int retainedCharacters;

        private Output(int limit) {
            this.limit = limit;
        }

        private synchronized String append(ToolOutputStream stream, String value) {
            int characters = value.codePointCount(0, value.length());
            originalCharacters += characters;
            int accepted = Math.min(characters, Math.max(0, limit - retainedCharacters));
            if (accepted == 0) {
                return "";
            }
            String retained = accepted == characters
                    ? value
                    : value.substring(0, value.offsetByCodePoints(0, accepted));
            (stream == ToolOutputStream.STDOUT ? stdout : stderr).append(retained);
            retainedCharacters += accepted;
            return retained;
        }

        private synchronized ExecutionOutcome outcome(
                int exitCode,
                boolean timedOut,
                boolean cancelled,
                EnforcementReport report,
                Optional<ExecutionFailure> failure) {
            return new ExecutionOutcome(
                    exitCode,
                    timedOut,
                    cancelled,
                    stdout.toString(),
                    stderr.toString(),
                    originalCharacters > retainedCharacters,
                    originalCharacters,
                    report,
                    failure);
        }
    }
}
