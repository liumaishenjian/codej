package io.github.liumaishenjian.ccjava.tools.local.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ToolOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class LocalCommandExecutorTest {

    Path workspace;

    @BeforeEach
    void createWorkspace() throws IOException {
        workspace = Path.of("target", "command-test-workspaces", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(workspace);
    }

    @AfterEach
    void deleteWorkspace() throws IOException {
        if (!Files.exists(workspace)) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void capturesExitCodeAndStreamsBothChannels() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        String command = CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                ? "Write-Output 'hello-out'; [Console]::Error.WriteLine('hello-err'); exit 7"
                : "printf 'hello-out\\n'; printf 'hello-err\\n' >&2; exit 7";

        CommandExecutionResult result = new LocalCommandExecutor(workspace).execute(
                command,
                Duration.ofSeconds(20),
                CancellationToken.none(),
                (stream, text) -> events.add(stream + ":" + text));

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.cancelled()).isFalse();
        assertThat(result.stdout()).contains("hello-out");
        assertThat(result.stderr()).contains("hello-err");
        assertThat(events).anyMatch(value -> value.startsWith(ToolOutputStream.STDOUT + ":"));
        assertThat(events).anyMatch(value -> value.startsWith(ToolOutputStream.STDERR + ":"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsPowerShellKeepsStructuredUnicodeStreamsWithoutCliXml() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

        CommandExecutionResult result = new LocalCommandExecutor(workspace).execute(
                "Write-Output \"中文输出\"; Write-Error \"中文错误\"; exit 7",
                Duration.ofSeconds(20),
                CancellationToken.none(),
                (stream, text) -> events.add(stream + ":" + text));

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.cancelled()).isFalse();
        assertThat(result.stdout()).contains("中文输出");
        assertThat(result.stderr())
                .contains("Write-Error", "中文错误")
                .doesNotContain("#< CLIXML", "_x001B_");
        assertThat(events).anySatisfy(value -> assertThat(value)
                .startsWith(ToolOutputStream.STDOUT + ":").contains("中文输出"));
        assertThat(events).anySatisfy(value -> assertThat(value)
                .startsWith(ToolOutputStream.STDERR + ":").contains("中文错误"));
    }

    @Test
    void cancellationTerminatesForegroundProcess() throws Exception {
        CancellationSource cancellation = new CancellationSource();
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancellation.cancel();
        });

        CommandExecutionResult result = new LocalCommandExecutor(workspace).execute(
                sleepCommand(10),
                Duration.ofSeconds(20),
                cancellation.token(),
                (ignoredStream, ignoredText) -> {
                });

        assertThat(result.cancelled()).isTrue();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void timeoutKillsDescendantBeforeItCanWriteMarker() throws Exception {
        Path marker = workspace.resolve("orphan-marker.txt");
        String command = javaChildCommand(marker, 2_000);

        CommandExecutionResult result = new LocalCommandExecutor(workspace).execute(
                command,
                Duration.ofMillis(300),
                CancellationToken.none(),
                (ignoredStream, ignoredText) -> {
                });
        Thread.sleep(2_300);

        assertThat(result.timedOut()).isTrue();
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void truncatesLargeOutputWhileContinuingToDrainProcess() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

        CommandExecutionResult result = new LocalCommandExecutor(workspace).execute(
                javaCommand(OutputFloodProcess.class.getName(), "60000"),
                Duration.ofSeconds(20),
                CancellationToken.none(),
                (ignoredStream, text) -> events.add(text));

        assertThat(result.exitCode()).isZero();
        assertThat(result.truncated()).isTrue();
        assertThat(result.originalCharacters()).isEqualTo(60_000);
        assertThat(result.stdout()).hasSize(48 * 1024);
        assertThat(events.stream().mapToInt(String::length).sum()).isEqualTo(48 * 1024);
    }

    @Test
    void minimalEnvironmentDoesNotInheritProviderCredentials() {
        assertThat(CommandEnvironment.minimal())
                .doesNotContainKeys(
                        "CC_JAVA_OPENAI_API_KEY",
                        "OPENAI_API_KEY",
                        "ANTHROPIC_API_KEY");
    }

    private static String sleepCommand(int seconds) {
        return CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                ? "Start-Sleep -Seconds " + seconds
                : "sleep " + seconds;
    }

    private static String javaChildCommand(Path marker, long delayMillis) {
        return javaCommand(
                DelayedMarkerProcess.class.getName(),
                marker.toString(),
                Long.toString(delayMillis));
    }

    private static String javaCommand(String className, String... arguments) {
        String java = Path.of(
                System.getProperty("java.home"),
                "bin",
                CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                        ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        if (CommandShell.current() == CommandShell.WINDOWS_POWERSHELL) {
            StringBuilder command = new StringBuilder("& ")
                    .append(quotePowerShell(java))
                    .append(" -cp ")
                    .append(quotePowerShell(classpath))
                    .append(' ')
                    .append(quotePowerShell(className));
            for (String argument : arguments) {
                command.append(' ').append(quotePowerShell(argument));
            }
            return command.toString();
        }
        StringBuilder command = new StringBuilder(quoteSh(java))
                .append(" -cp ")
                .append(quoteSh(classpath))
                .append(' ')
                .append(quoteSh(className));
        for (String argument : arguments) {
            command.append(' ').append(quoteSh(argument));
        }
        return command.toString();
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quoteSh(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * 进程树测试使用的最小子进程；若未被清理，会在延迟后留下可观察 Marker。
     */
    public static final class DelayedMarkerProcess {

        private DelayedMarkerProcess() {
        }

        public static void main(String[] arguments) throws Exception {
            Thread.sleep(Long.parseLong(arguments[1]));
            Files.writeString(Path.of(arguments[0]), "orphan");
        }
    }

    /** 输出上限测试使用的确定性字符生产者。 */
    public static final class OutputFloodProcess {

        private OutputFloodProcess() {
        }

        public static void main(String[] arguments) {
            System.out.print("x".repeat(Integer.parseInt(arguments[0])));
        }
    }
}
