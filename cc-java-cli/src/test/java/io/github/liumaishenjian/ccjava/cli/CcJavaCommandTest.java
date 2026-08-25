package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenMode;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CcJavaCommandTest {

    @Test
    void dispatchesPrintPromptAndReturnsRunnerExitCode() {
        FakeCliModeRunner runner = new FakeCliModeRunner(17, 18);
        Invocation invocation = execute(runner, "--print", "介绍一下你自己");

        assertThat(invocation.exitCode()).isEqualTo(17);
        assertThat(runner.printPrompt).isEqualTo("介绍一下你自己");
        assertThat(runner.overrides.model()).isEmpty();
        assertThat(runner.overrides.timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void dispatchesStdioAndReturnsRunnerExitCode() {
        FakeCliModeRunner runner = new FakeCliModeRunner(17, 18);
        Invocation invocation = execute(runner, "--stdio");

        assertThat(invocation.exitCode()).isEqualTo(18);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isOne();
    }

    @Test
    void dispatchesExtensionStatusAndExplicitTrustAsProviderFreeModes() {
        FakeCliModeRunner status = new FakeCliModeRunner(17, 18);
        FakeCliModeRunner trust = new FakeCliModeRunner(17, 18);

        assertThat(execute(status, "--extension-status").exitCode()).isEqualTo(18);
        assertThat(execute(trust, "--trust-project-extensions").exitCode()).isEqualTo(18);

        assertThat(status.extensionCalls).isOne();
        assertThat(status.extensionApprove).isFalse();
        assertThat(trust.extensionCalls).isOne();
        assertThat(trust.extensionApprove).isTrue();
        assertThat(status.stdioCalls).isZero();
    }

    @Test
    void parsesWorkspaceModelAndHumanDurationAsTypedOverrides() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation invocation = execute(
                runner,
                "--workspace",
                ".",
                "--model",
                "override-model",
                "--timeout",
                "250ms",
                "--print",
                "hello");

        assertThat(invocation.exitCode()).isZero();
        assertThat(runner.overrides.workspace())
                .isEqualTo(Path.of("").toAbsolutePath().normalize());
        assertThat(runner.overrides.model()).contains("override-model");
        assertThat(runner.overrides.timeout()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void defaultsDiagnosticsOffAndParsesTrustedLocalSelection() {
        FakeCliModeRunner defaultRunner = new FakeCliModeRunner(0, 0);
        FakeCliModeRunner enabledRunner = new FakeCliModeRunner(0, 0);
        Path directory = Path.of("target", "diagnostics-test").toAbsolutePath().normalize();

        assertThat(execute(defaultRunner, "--print", "hello").exitCode()).isZero();
        assertThat(execute(enabledRunner,
                "--model-diagnostics", "safe",
                "--model-diagnostics-dir", directory.toString(),
                "--print", "hello").exitCode()).isZero();

        assertThat(defaultRunner.overrides.diagnosticMode()).isEqualTo(ModelDiagnosticMode.OFF);
        assertThat(defaultRunner.overrides.diagnosticDirectory()).isEmpty();
        assertThat(enabledRunner.overrides.diagnosticMode()).isEqualTo(ModelDiagnosticMode.SAFE);
        assertThat(enabledRunner.overrides.diagnosticDirectory()).contains(directory);
    }

    @Test
    void rejectsDiagnosticDirectoryWhileModeIsOff() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation invocation = execute(runner,
                "--model-diagnostics-dir", Path.of("target", "diagnostics").toString(),
                "--print", "hello");

        assertThat(invocation.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(runner.overrides).isNull();
    }

    @Test
    void parsesOnlyCompleteExplicitContextCapacityTuple() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation invocation = execute(
                runner,
                "--model",
                "configured-model",
                "--context-maximum-input-tokens",
                "128000",
                "--context-reserved-output-tokens",
                "4096",
                "--context-safety-margin-tokens",
                "1024",
                "--print",
                "hello");

        assertThat(invocation.exitCode()).isZero();
        assertThat(runner.overrides.contextPreparation()).hasValueSatisfying(config -> {
            assertThat(config.capacity()).isEqualTo(
                    new ContextCapacity("configured-model", 128000, 4096, 1024));
            assertThat(config.largePayloadTokenThreshold()).isPositive();
            assertThat(config.maxSummaryTokens()).isPositive();
        });
    }

    @Test
    void rejectsPartialOrInvalidContextCapacityBeforeCallingRunner() {
        FakeCliModeRunner partialRunner = new FakeCliModeRunner(0, 0);
        FakeCliModeRunner invalidRunner = new FakeCliModeRunner(0, 0);
        FakeCliModeRunner exhaustedRunner = new FakeCliModeRunner(0, 0);
        FakeCliModeRunner boundedRunner = new FakeCliModeRunner(0, 0);

        Invocation partial = execute(
                partialRunner,
                "--context-maximum-input-tokens",
                "1000",
                "--print",
                "hello");
        Invocation invalid = execute(
                invalidRunner,
                "--context-maximum-input-tokens",
                "1000",
                "--context-reserved-output-tokens",
                "0",
                "--context-safety-margin-tokens",
                "100",
                "--print",
                "hello");
        Invocation exhausted = execute(
                exhaustedRunner,
                "--context-maximum-input-tokens",
                "1000",
                "--context-reserved-output-tokens",
                "900",
                "--context-safety-margin-tokens",
                "100",
                "--print",
                "hello");
        Invocation beyondBound = execute(
                boundedRunner,
                "--context-maximum-input-tokens",
                "2147483648",
                "--context-reserved-output-tokens",
                "100",
                "--context-safety-margin-tokens",
                "100",
                "--print",
                "hello");

        assertThat(partial.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(invalid.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(exhausted.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(beyondBound.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(partialRunner.overrides).isNull();
        assertThat(invalidRunner.overrides).isNull();
        assertThat(exhaustedRunner.overrides).isNull();
        assertThat(boundedRunner.overrides).isNull();
    }

    @Test
    void parsesMutuallyExclusiveSessionSelection() {
        FakeCliModeRunner continueRunner = new FakeCliModeRunner(0, 0);
        FakeCliModeRunner resumeRunner = new FakeCliModeRunner(0, 0);
        FakeCliModeRunner forkRunner = new FakeCliModeRunner(0, 0);

        assertThat(execute(continueRunner, "--continue", "--stdio").exitCode()).isZero();
        assertThat(execute(
                resumeRunner,
                "--resume",
                "session-resume-1",
                "--print",
                "hello").exitCode()).isZero();
        assertThat(execute(
                forkRunner,
                "--fork",
                "session-fork-1",
                "--stdio").exitCode()).isZero();

        assertThat(continueRunner.overrides.sessionOpenRequest().mode())
                .isEqualTo(SessionOpenMode.CONTINUE);
        assertThat(resumeRunner.overrides.sessionOpenRequest().mode())
                .isEqualTo(SessionOpenMode.RESUME);
        assertThat(resumeRunner.overrides.sessionOpenRequest().sessionId())
                .contains(new SessionId("session-resume-1"));
        assertThat(forkRunner.overrides.sessionOpenRequest().mode())
                .isEqualTo(SessionOpenMode.FORK);
        assertThat(forkRunner.overrides.sessionOpenRequest().sessionId())
                .contains(new SessionId("session-fork-1"));
    }

    @Test
    void rejectsConflictingOrInvalidSessionSelection() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation conflicting = execute(
                runner,
                "--continue",
                "--resume",
                "session-valid-1",
                "--stdio");
        Invocation invalidId = execute(
                runner,
                "--resume",
                "../outside",
                "--stdio");

        assertThat(conflicting.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(invalidId.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void rejectsInvalidTimeoutAndModelBeforeCallingRunner() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation malformedTimeout = execute(
                runner,
                "--timeout",
                "soon",
                "--print",
                "hello");
        Invocation outOfRangeTimeout = execute(
                runner,
                "--timeout",
                "1ms",
                "--print",
                "hello");
        Invocation blankModel = execute(
                runner,
                "--model",
                " ",
                "--print",
                "hello");

        assertThat(malformedTimeout.exitCode())
                .isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(outOfRangeTimeout.exitCode())
                .isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(blankModel.exitCode())
                .isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void rejectsMissingOrConflictingModeWithoutCallingRunner() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation missing = execute(runner);
        Invocation conflicting = execute(
                runner,
                "--print",
                "hello",
                "--stdio");

        assertThat(missing.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(conflicting.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void standardHelpDoesNotRequireAHeadlessMode() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation invocation = execute(runner, "--help");

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stdout())
                .contains("Usage: cc-java")
                .contains("--print")
                .contains("--stdio")
                .contains("--timeout=<duration>")
                .contains("30m");
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void routesExplicitStableStdioWithoutChangingV0() {
        FakeCliModeRunner stable = new FakeCliModeRunner(0, 0);
        assertThat(execute(stable, "--stdio-v1").exitCode()).isZero();
        assertThat(stable.stableStdioCalls).isOne();
        assertThat(stable.stdioCalls).isZero();

        FakeCliModeRunner v0 = new FakeCliModeRunner(0, 0);
        assertThat(execute(v0, "--stdio").exitCode()).isZero();
        assertThat(v0.stdioCalls).isOne();
        assertThat(v0.stableStdioCalls).isZero();
    }

    @Test
    void routesExplicitDaemonToProductionModeRunner() {
        FakeCliModeRunner daemon = new FakeCliModeRunner(0, 0);

        assertThat(execute(daemon, "--daemon").exitCode()).isZero();

        assertThat(daemon.daemonCalls).isOne();
        assertThat(daemon.stdioCalls).isZero();
        assertThat(daemon.stableStdioCalls).isZero();
    }

    private Invocation execute(FakeCliModeRunner runner, String... args) {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exitCode = CcJavaCliMain.execute(
                args,
                runner,
                new PrintWriter(stdout, true),
                new PrintWriter(stderr, true));
        return new Invocation(exitCode, stdout.toString(), stderr.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }

    private static final class FakeCliModeRunner implements CliModeRunner {

        private final int printExitCode;
        private final int stdioExitCode;
        private String printPrompt;
        private CliOverrides overrides;
        private int stdioCalls;
        private int stableStdioCalls;
        private int daemonCalls;
        private int extensionCalls;
        private boolean extensionApprove;

        private FakeCliModeRunner(int printExitCode, int stdioExitCode) {
            this.printExitCode = printExitCode;
            this.stdioExitCode = stdioExitCode;
        }

        @Override
        public int runPrint(String prompt, CliOverrides overrides) {
            printPrompt = prompt;
            this.overrides = overrides;
            return printExitCode;
        }

        @Override
        public int runStdio(CliOverrides overrides) {
            this.overrides = overrides;
            stdioCalls++;
            return stdioExitCode;
        }

        @Override
        public int runStableStdio(CliOverrides overrides) {
            this.overrides = overrides;
            stableStdioCalls++;
            return stdioExitCode;
        }

        @Override
        public int runDaemon(CliOverrides overrides) {
            this.overrides = overrides;
            daemonCalls++;
            return stdioExitCode;
        }

        @Override
        public int runExtensions(boolean approve, CliOverrides overrides) {
            this.overrides = overrides;
            extensionCalls++;
            extensionApprove = approve;
            return stdioExitCode;
        }
    }
}
