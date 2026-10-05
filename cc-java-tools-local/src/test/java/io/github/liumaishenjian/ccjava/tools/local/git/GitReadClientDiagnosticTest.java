package io.github.liumaishenjian.ccjava.tools.local.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.WorkspaceSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitReadClientDiagnosticTest {
    private static final String PRIVATE = "synthetic-private-path-and-output";
    private final ArrayList<GitReadClient.ReadDiagnostic> observations = new ArrayList<>();

    private GitReadClient client(FakeProcess process) {
        return new GitReadClient(Path.of("."), builder -> {
            assertThat(builder.command()).contains("git", "--no-pager", "--porcelain=v1");
            assertThat(builder.environment()).containsEntry("GIT_OPTIONAL_LOCKS", "0");
            return process;
        }, observations::add, Duration.ofMillis(50));
    }

    @Test void successUsesOneInvocationAndNeverPublishesBody() {
        var process = new FakeProcess("## main\n?? " + PRIVATE, "", 0);
        assertThat(WorkspaceSnapshot.capture(client(process)).repository()).isTrue();
        assertThat(process.waits).isEqualTo(1);
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().failure()).isNull();
        assertThat(observations.toString()).doesNotContain(PRIVATE);
    }

    @Test void closesUnusedStdinBeforeWaitingForReadOnlyCommand() throws Exception {
        var process = new FakeProcess("## main", "", 0);
        client(process).status();
        assertThat(process.stdinClosedBeforeWait).isTrue();
    }

    @Test void stdinCloseFailureTerminatesWithoutPublishingException() {
        var process = new FakeProcess("", "", 0);
        process.stdinCloseFails = true;
        var failure = catchThrowableOfType(GitReadClient.GitReadException.class, client(process)::status);
        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.GIT_READ_FAILED);
        assertThat(process.destroyed).isTrue();
        assertThat(process.waits).isZero();
        assertThat(failure.toString() + observations).doesNotContain(PRIVATE);
    }

    @Test void originalFailureIsObservedWithoutASecondProbe() {
        var process = new FakeProcess("", "fatal: not a git repository " + PRIVATE, 128);
        assertThat(WorkspaceSnapshot.capture(client(process)).repositoryState())
                .isEqualTo(WorkspaceSnapshot.RepositoryState.NON_REPOSITORY);
        assertThat(process.waits).isEqualTo(1);
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().failure()).isEqualTo(ToolErrorCode.NOT_A_GIT_REPOSITORY);
        assertThat(observations.getFirst().exitCode()).isEqualTo(128);
        assertThat(observations.toString()).doesNotContain(PRIVATE);
    }

    @Test void unknownExitCannotBecomeNonRepository() {
        assertThat(WorkspaceSnapshot.capture(client(new FakeProcess("", PRIVATE, 7))).repositoryState())
                .isEqualTo(WorkspaceSnapshot.RepositoryState.UNKNOWN);
        assertThat(observations.getFirst().failure()).isEqualTo(ToolErrorCode.GIT_READ_FAILED);
    }

    @Test void unavailableDoesNotExposeIOException() {
        var git = new GitReadClient(Path.of("."), builder -> { throw new IOException(PRIVATE); },
                observations::add, Duration.ofMillis(50));
        var failure = catchThrowableOfType(GitReadClient.GitReadException.class, git::status);
        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.GIT_UNAVAILABLE);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString() + observations).doesNotContain(PRIVATE);
        assertThat(observations.getFirst().exitCode()).isNull();
    }

    @Test void startupOsCodeIsNumericOnlyAndNeverChangesUnavailableSemantics() {
        var git = new GitReadClient(Path.of("."), builder -> {
            throw new IOException(PRIVATE + ": CreateProcess error=5, " + PRIVATE);
        }, observations::add, Duration.ofMillis(50));
        assertThat(WorkspaceSnapshot.capture(git).repositoryState()).isEqualTo(WorkspaceSnapshot.RepositoryState.UNKNOWN);
        assertThat(observations.getFirst().startupOsCode()).isEqualTo(5);
        assertThat(observations.getFirst().failure()).isEqualTo(ToolErrorCode.GIT_UNAVAILABLE);
        assertThat(observations.toString()).doesNotContain(PRIVATE);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readFailureIsNotOverflowOrSuccess(boolean stderr) {
        var process = new FakeProcess("", "", 0);
        if (stderr) process.err = broken(); else process.out = broken();
        var failure = catchThrowableOfType(GitReadClient.GitReadException.class, client(process)::status);
        assertThat(failure).isNotNull();
        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.GIT_READ_FAILED);
        assertThat(observations.getFirst().stdoutReadFailed()).isEqualTo(!stderr);
        assertThat(observations.getFirst().stderrReadFailed()).isEqualTo(stderr);
        assertThat(observations.getFirst().stdoutTruncated()).isFalse();
        assertThat(observations.getFirst().stderrTruncated()).isFalse();
    }

    @Test void actualOverflowStaysBoundedAndDistinct() {
        var process = new FakeProcess("", "", 0);
        process.out = new ByteArrayInputStream(new byte[GitReadClient.MAX_STDOUT_BYTES + 1]);
        var failure = catchThrowableOfType(GitReadClient.GitReadException.class, client(process)::status);
        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.OUTPUT_LIMIT_EXCEEDED);
        assertThat(observations.getFirst().stdoutBytes()).isEqualTo(GitReadClient.MAX_STDOUT_BYTES);
        assertThat(observations.getFirst().stdoutTruncated()).isTrue();
        assertThat(observations.getFirst().stdoutReadFailed()).isFalse();
    }

    @Test void boundedStderrOverflowDoesNotReplaceSuccessfulStdout() throws Exception {
        var process = new FakeProcess("## main", "", 0);
        process.err = new ByteArrayInputStream(new byte[16 * 1024 + 1]);
        assertThat(client(process).status().stderrTruncated()).isTrue();
        assertThat(observations.getFirst().stderrBytes()).isEqualTo(16 * 1024);
    }

    @Test void timeoutTerminatesAndRetainsOriginalClassification() {
        var process = new FakeProcess("", "", 0); process.finished = false;
        var failure = catchThrowableOfType(GitReadClient.GitReadException.class, client(process)::status);
        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.OPERATION_TIMED_OUT);
        assertThat(process.destroyed).isTrue();
        assertThat(observations).hasSize(1);
    }

    @Test void interruptionIsPreservedAndProcessTerminated() {
        var process = new FakeProcess("", "", 0); process.interrupt = true;
        try {
            var failure = catchThrowableOfType(GitReadClient.GitReadException.class, client(process)::status);
            assertThat(failure.error().code()).isEqualTo(ToolErrorCode.OPERATION_TIMED_OUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(process.destroyed).isTrue();
            assertThat(observations.getFirst().interrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private static InputStream broken() {
        return new InputStream() { @Override public int read() throws IOException { throw new IOException(PRIVATE); } };
    }

    private static final class FakeProcess extends Process {
        private InputStream out, err;
        private final int exit;
        private boolean finished = true, destroyed, interrupt, stdinClosed, stdinClosedBeforeWait, stdinCloseFails;
        private int waits;
        FakeProcess(String stdout, String stderr, int exit) {
            out = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            err = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)); this.exit = exit;
        }
        @Override public InputStream getInputStream() { return out; }
        @Override public InputStream getErrorStream() { return err; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { throw new AssertionError("只读Git不接受stdin输入"); }
                @Override public void close() throws IOException {
                    stdinClosed = true;
                    if (stdinCloseFails) throw new IOException(PRIVATE);
                }
            };
        }
        @Override public int waitFor() { return exit; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            stdinClosedBeforeWait = stdinClosed;
            waits++; if (interrupt) throw new InterruptedException(); return finished;
        }
        @Override public int exitValue() {
            if (!finished && !destroyed) throw new IllegalThreadStateException(); return exit;
        }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
