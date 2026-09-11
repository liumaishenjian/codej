package io.github.liumaishenjian.ccjava.tools.local.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommandShellTest {

    @TempDir
    Path temporary;

    @Test
    void prefersMachineInstallThenFallsBackToUserPowerShellSeven() throws Exception {
        Path machineRoot = Files.createDirectories(temporary.resolve("machine"));
        Path userRoot = Files.createDirectories(temporary.resolve("user"));
        Path userPwsh = createExecutable(
                userRoot.resolve("Programs/PowerShell/7/pwsh.exe"));

        assertThat(CommandShell.findPowerShell7(
                machineRoot.toString(), userRoot.toString()))
                .isEqualTo(userPwsh);

        Path machinePwsh = createExecutable(
                machineRoot.resolve("PowerShell/7/pwsh.exe"));
        assertThat(CommandShell.findPowerShell7(
                machineRoot.toString(), userRoot.toString()))
                .isEqualTo(machinePwsh);
    }

    @Test
    void returnsNullWhenStandardInstallRootsAreMissing() {
        assertThat(CommandShell.findPowerShell7(null, " ")).isNull();
        assertThat(CommandShell.findPowerShell7(
                temporary.resolve("missing-machine").toString(),
                temporary.resolve("missing-user").toString()))
                .isNull();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void preservesQuotedUnicodeTextAndPlainErrorStreamsWhenLaunchingPowerShell() throws Exception {
        ProcessResult success = runPowerShell(
                "$value = \"TUI acceptance\"; Write-Output $value; Write-Output \"中文输出\"");
        assertThat(success.exitCode()).isZero();
        assertThat(success.stdout()).contains("TUI acceptance", "中文输出");
        assertThat(success.stderr()).isEmpty();

        ProcessResult failure = runPowerShell("Write-Error \"中文错误\"; exit 7");
        assertThat(failure.exitCode()).isEqualTo(7);
        assertThat(failure.stdout()).isEmpty();
        assertThat(failure.stderr())
                .contains("Write-Error", "中文错误")
                .doesNotContain("#< CLIXML", "_x001B_");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keepsLauncherArgvFixedAndClearsLargestSupplementaryUnicodePayload() throws Exception {
        String emoji = "😀".repeat(8_192);
        var invocation = CommandShell.WINDOWS_POWERSHELL.processInvocation(
                "Write-Output '" + emoji + "'",
                Map.of());

        assertThat(invocation.arguments().getLast()).hasSizeLessThan(2_000);
        assertThat(invocation.arguments()).noneMatch(argument -> argument.contains(emoji));
        var privateInvocation = CommandShell.WINDOWS_POWERSHELL.processInvocation(
                "Write-Output 'PRIVATE_COMMAND_SENTINEL'",
                Map.of());
        assertThat(privateInvocation.toString())
                .doesNotContain("PRIVATE_COMMAND_SENTINEL", "CODEJ_PS_BODY");
        ProcessResult maximum = runPowerShell("Write-Output '" + emoji + "'");
        assertThat(maximum.exitCode()).isZero();
        assertThat(maximum.stdout().stripTrailing()).isEqualTo(emoji);
        assertThat(maximum.stderr()).isEmpty();

        ProcessResult cleared = runPowerShell(
                "if (Test-Path Env:CODEJ_PS_BODY) { exit 9 }; Write-Output 'cleared'");
        assertThat(cleared.exitCode()).isZero();
        assertThat(cleared.stdout()).contains("cleared");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void preservesPowerShellSuccessAndFailureExitSemantics() throws Exception {
        assertThat(runPowerShell("Write-Error '错误中文😀'").exitCode()).isEqualTo(1);
        assertThat(runPowerShell("& cmd.exe /d /c exit 7").exitCode()).isEqualTo(1);
        assertThat(runPowerShell(
                "& cmd.exe /d /c exit 7; Write-Output 'recovered'").exitCode()).isZero();
        assertThat(runPowerShell("exit 7").exitCode()).isEqualTo(7);
    }

    @ParameterizedTest
    @MethodSource("powerShellLanguageCases")
    @EnabledOnOs(OS.WINDOWS)
    void preservesPowerShellLanguageBoundaries(
            String command,
            int exitCode,
            String expectedText,
            boolean stderr) throws Exception {
        ProcessResult result = runPowerShell(command);
        assertThat(result.exitCode()).isEqualTo(exitCode);
        assertThat(stderr ? result.stderr() : result.stdout()).contains(expectedText);
        assertThat(result.stderr()).doesNotContain("#< CLIXML", "_x001B_");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keepsMixedHostAndRawBytesOnOneUtf8StreamAndHidesPayloadFromChild() throws Exception {
        ProcessResult mixed = runPowerShell(
                "Write-Host '主机中文'; $b=[Text.Encoding]::UTF8.GetBytes('原始😀'); "
                        + "[Console]::OpenStandardOutput().Write($b,0,$b.Length)");
        assertThat(mixed.exitCode()).isZero();
        assertThat(mixed.stdout()).isEqualTo("主机中文\r\n原始😀");

        ProcessResult child = runPowerShell(
                "$exe=(Get-Process -Id $PID).Path; & $exe -NoLogo -NoProfile -NonInteractive "
                        + "-Command \"if (Test-Path Env:CODEJ_PS_BODY) { exit 9 }\"; "
                        + "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; "
                        + "Write-Output 'child-cleared'");
        assertThat(child.exitCode()).isZero();
        assertThat(child.stdout()).contains("child-cleared");
    }

    private static Stream<Arguments> powerShellLanguageCases() {
        return Stream.of(
                Arguments.of(
                        "param([string]$Value='参数中文😀'); Write-Output $Value",
                        0,
                        "参数中文😀",
                        false),
                Arguments.of(
                        "#requires -Version 5.1\nWrite-Output 'requires中文😀'",
                        0,
                        "requires中文😀",
                        false),
                Arguments.of(
                        "$x=@'\n中文😀\n'@\nWrite-Output $x",
                        0,
                        "中文😀",
                        false),
                Arguments.of(
                        "Write-Output '尾注释中文😀' # no newline",
                        0,
                        "尾注释中文😀",
                        false),
                Arguments.of(
                        "throw '异常中文😀'",
                        1,
                        "异常中文😀",
                        true));
    }

    private static ProcessResult runPowerShell(String command) throws Exception {
        var invocation = CommandShell.WINDOWS_POWERSHELL.processInvocation(
                command,
                CommandEnvironment.minimal());
        ProcessBuilder builder = new ProcessBuilder(invocation.arguments());
        builder.environment().clear();
        builder.environment().putAll(invocation.environment());
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            var stderr = executor.submit(() -> process.getErrorStream().readAllBytes());
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            assertThat(exited).as("固定 PowerShell 命令必须在有界时间内退出").isTrue();
            return new ProcessResult(
                    process.exitValue(),
                    new String(stdout.get(5, TimeUnit.SECONDS),
                            CommandShell.WINDOWS_POWERSHELL.outputCharset()),
                    new String(stderr.get(5, TimeUnit.SECONDS),
                            CommandShell.WINDOWS_POWERSHELL.outputCharset()));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private static Path createExecutable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "test");
    }
}
