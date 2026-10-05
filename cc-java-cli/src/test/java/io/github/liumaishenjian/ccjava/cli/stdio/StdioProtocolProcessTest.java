package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StdioProtocolProcessTest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void realJavaProcessCancelsRunAndExitsWithoutProtocolNoise()
            throws Exception {
        Process process = startFixtureProcess();
        List<ProcessHandle> descendants = new ArrayList<>();
        try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                     process.getOutputStream(),
                     StandardCharsets.UTF_8));
             BufferedReader output = new BufferedReader(new InputStreamReader(
                     process.getInputStream(),
                     StandardCharsets.UTF_8))) {
            JsonMapper mapper = JsonMapper.builder().build();

            send(input,
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"req-1\",\"sequence\":1,\"payload\":{}}");
            JsonNode initialized = readEvent(process, output, mapper);
            assertThat(initialized.get("type").stringValue()).isEqualTo("initialized");
            String sessionId = initialized.get("sessionId").stringValue();

            send(input,
                    ("{\"version\":0,\"type\":\"run.start\","
                            + "\"requestId\":\"req-2\",\"sessionId\":\"%s\","
                            + "\"sequence\":2,\"payload\":{\"prompt\":\"cancel me\"}}")
                            .formatted(sessionId));
            JsonNode accepted = readEvent(process, output, mapper);
            assertThat(accepted.get("type").stringValue())
                    .isEqualTo("run.command.result");
            assertThat(accepted.get("requestId").stringValue()).isEqualTo("req-2");
            assertThat(accepted.at("/payload/disposition").stringValue())
                    .isEqualTo("accepted");
            JsonNode started = readEvent(process, output, mapper);
            assertThat(started.get("type").stringValue()).isEqualTo("run.started");
            assertThat(started.get("requestId").stringValue()).isEqualTo("req-2");
            String runId = started.get("runId").stringValue();

            send(input,
                    ("{\"version\":0,\"type\":\"run.cancel\","
                            + "\"requestId\":\"req-3\",\"sessionId\":\"%s\","
                            + "\"runId\":\"%s\",\"sequence\":3,\"payload\":{}}")
                            .formatted(sessionId, runId));

            JsonNode terminal = readUntilTerminal(process, output, mapper);
            assertThat(terminal.get("type").stringValue()).isEqualTo("run.cancelled");
            assertThat(terminal.get("runId").stringValue()).isEqualTo(runId);

            descendants.addAll(process.toHandle().descendants().toList());
            send(input,
                    ("{\"version\":0,\"type\":\"shutdown\","
                            + "\"requestId\":\"req-4\",\"sessionId\":\"%s\","
                            + "\"sequence\":4,\"payload\":{}}")
                            .formatted(sessionId));
        } finally {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }

        String stderr = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isZero();
        assertThat(stderr).isBlank();
        assertThat(process.isAlive()).isFalse();
        assertThat(descendants).noneMatch(ProcessHandle::isAlive);
    }

    @Test
    void realJavaProcessReturnsCompleteProviderControlResultsAndErrors() throws Exception {
        Process process = startFixtureProcess("provider-control");
        try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                     process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader output = new BufferedReader(new InputStreamReader(
                     process.getInputStream(), StandardCharsets.UTF_8))) {
            JsonMapper mapper = JsonMapper.builder().build();
            send(input, "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"provider-1\",\"sequence\":1,\"payload\":{}}");
            String sessionId = readEvent(process, output, mapper).get("sessionId").stringValue();
            send(input, providerControl("provider-2", sessionId, 2, "control-list", "auth.list", "{}"));
            JsonNode profiles = readEvent(process, output, mapper);
            assertThat(profiles.get("type").stringValue()).isEqualTo("provider.control.result");
            assertThat(profiles.at("/payload/result/profiles/0/providerId").stringValue()).isEqualTo("anthropic");
            assertThat(profiles.at("/payload/result/profiles/0/profileId").stringValue()).isEqualTo("fixture");
            assertThat(profiles.toString()).doesNotContain("fixture-provider-sentinel", "CC_JAVA_FIXTURE_KEY");

            send(input, providerControl("provider-3", sessionId, 3, "control-models", "models.list",
                    "{\"providerId\":\"anthropic\"}"));
            JsonNode models = readEvent(process, output, mapper);
            assertThat(models.at("/payload/result/models").isArray()).isTrue();
            assertThat(models.at("/payload/result/models").size()).isGreaterThan(0);

            send(input, providerControl("provider-4", sessionId, 4, "control-add", "models.add",
                    "{\"providerId\":\"anthropic\",\"modelId\":\"process-overlay\"}"));
            JsonNode added = readEvent(process, output, mapper);
            assertThat(added.at("/payload/status").stringValue()).isEqualTo("succeeded");
            assertThat(added.toString()).doesNotContain("secret", "apiKey");

            send(input, providerControl("provider-5", sessionId, 5, "control-use", "models.use",
                    "{\"providerId\":\"anthropic\",\"modelId\":\"missing-model\",\"profileId\":\"fixture\"}"));
            JsonNode rejected = readEvent(process, output, mapper);
            assertThat(rejected.at("/payload/status").stringValue()).isEqualTo("rejected");
            assertThat(rejected.at("/payload/code").stringValue()).isEqualTo("MODEL_UNKNOWN");
            assertThat(rejected.at("/payload/result").size()).isZero();

            send(input, ("{\"version\":0,\"type\":\"shutdown\",\"requestId\":\"provider-6\","
                    + "\"sessionId\":\"%s\",\"sequence\":6,\"payload\":{}}").formatted(sessionId));
        } finally {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isBlank();
    }

    @Test
    void realJavaAuthLifecycleNegotiatesAndBindsOneShotLogout() throws Exception {
        Process process = startFixtureProcess("provider-control");
        try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            JsonMapper mapper = JsonMapper.builder().build();
            send(input, "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"auth-1\",\"sequence\":1,\"payload\":{\"authLifecycleV1\":true}}");
            JsonNode initialized = readEvent(process, output, mapper);
            String session = initialized.get("sessionId").stringValue();
            assertThat(initialized.at("/payload/authLifecycleV1").booleanValue()).isTrue();
            send(input, providerControl("auth-2", session, 2, "activate", "auth.activate", "{\"providerId\":\"anthropic\",\"profileId\":\"fixture\"}"));
            assertThat(readEvent(process, output, mapper).at("/payload/status").stringValue()).isEqualTo("succeeded");
            send(input, providerControl("auth-3", session, 3, "prepare", "auth.logout.prepare", "{\"providerId\":\"anthropic\",\"profileId\":\"fixture\"}"));
            JsonNode prepared = readEvent(process, output, mapper);
            String ticket = prepared.at("/payload/result/confirmationId").stringValue();
            assertThat(ticket).isNotBlank();
            assertThat(prepared.toString()).doesNotContain("fixture-provider-sentinel", "CC_JAVA_FIXTURE_KEY", "secretRef");
            String args = "{\"confirmationId\":\"" + ticket + "\",\"confirmed\":true}";
            send(input, providerControl("auth-4", session, 4, "commit", "auth.logout.commit", args));
            JsonNode deleted = readEvent(process, output, mapper);
            assertThat(deleted.at("/payload/status").stringValue()).isEqualTo("succeeded");
            assertThat(deleted.at("/payload/result/remoteRevoked").booleanValue()).isFalse();
            send(input, providerControl("auth-5", session, 5, "replay", "auth.logout.commit", args));
            assertThat(readEvent(process, output, mapper).at("/payload/status").stringValue()).isEqualTo("rejected");
            send(input, providerControl("auth-6", session, 6, "list", "auth.list", "{}"));
            assertThat(readEvent(process, output, mapper).at("/payload/result/profiles").size()).isZero();
            send(input, ("{\"version\":0,\"type\":\"shutdown\",\"requestId\":\"auth-7\",\"sessionId\":\"%s\",\"sequence\":7,\"payload\":{}}").formatted(session));
        } finally {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); process.waitFor();
            }
        }
        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isBlank();
    }

    @Test
    void realJavaProcessCompletesTwoRunsInOneSession() throws Exception {
        Process process = startFixtureProcess();
        List<ProcessHandle> descendants = new ArrayList<>();
        String firstRunId;
        String secondRunId;
        try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                     process.getOutputStream(),
                     StandardCharsets.UTF_8));
             BufferedReader output = new BufferedReader(new InputStreamReader(
                     process.getInputStream(),
                     StandardCharsets.UTF_8))) {
            JsonMapper mapper = JsonMapper.builder().build();

            send(input,
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"multi-1\",\"sequence\":1,\"payload\":{}}");
            JsonNode initialized = readEvent(process, output, mapper);
            String sessionId = initialized.get("sessionId").stringValue();

            send(input,
                    ("{\"version\":0,\"type\":\"run.start\","
                            + "\"requestId\":\"multi-2\",\"sessionId\":\"%s\","
                            + "\"sequence\":2,\"payload\":{\"prompt\":\"first\"}}")
                            .formatted(sessionId));
            assertAcceptedResult(
                    readEvent(process, output, mapper),
                    "multi-2");
            JsonNode firstTerminal = readUntilTerminal(process, output, mapper);
            assertThat(firstTerminal.get("type").stringValue()).isEqualTo("run.completed");
            assertThat(firstTerminal.get("sessionId").stringValue()).isEqualTo(sessionId);
            firstRunId = firstTerminal.get("runId").stringValue();

            send(input,
                    ("{\"version\":0,\"type\":\"run.start\","
                            + "\"requestId\":\"multi-3\",\"sessionId\":\"%s\","
                            + "\"sequence\":3,\"payload\":{\"prompt\":\"second\"}}")
                            .formatted(sessionId));
            assertAcceptedResult(
                    readEvent(process, output, mapper),
                    "multi-3");
            JsonNode secondTerminal = readUntilTerminal(process, output, mapper);
            assertThat(secondTerminal.get("type").stringValue()).isEqualTo("run.completed");
            assertThat(secondTerminal.get("sessionId").stringValue()).isEqualTo(sessionId);
            secondRunId = secondTerminal.get("runId").stringValue();

            descendants.addAll(process.toHandle().descendants().toList());
            send(input,
                    ("{\"version\":0,\"type\":\"shutdown\","
                            + "\"requestId\":\"multi-4\",\"sessionId\":\"%s\","
                            + "\"sequence\":4,\"payload\":{}}")
                            .formatted(sessionId));
        } finally {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }

        assertThat(firstRunId).isNotEqualTo(secondRunId);
        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                .isBlank();
        assertThat(descendants).noneMatch(ProcessHandle::isAlive);
    }

    private static void assertAcceptedResult(
            JsonNode event,
            String requestId) {
        assertThat(event.get("type").stringValue())
                .isEqualTo("run.command.result");
        assertThat(event.get("requestId").stringValue())
                .isEqualTo(requestId);
        assertThat(event.at("/payload/disposition").stringValue())
                .isEqualTo("accepted");
    }

    private static String providerControl(String requestId, String sessionId, int sequence,
                                          String controlId, String intent, String arguments) {
        return ("{\"version\":0,\"type\":\"provider.control\",\"requestId\":\"%s\","
                + "\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"controlId\":\"%s\","
                + "\"intent\":\"%s\",\"arguments\":%s}}")
                .formatted(requestId, sessionId, sequence, controlId, intent, arguments);
    }

    private Process startFixtureProcess(String... arguments) throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javaExecutable = javaHome.resolve("bin").resolve(
                System.getProperty("os.name").startsWith("Windows")
                        ? "java.exe"
                        : "java");
        assertThat(Files.isRegularFile(javaExecutable)).isTrue();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(StdioProtocolFixtureMain.class.getName());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .directory(Path.of("").toAbsolutePath().toFile())
                .start();
    }

    private void send(BufferedWriter input, String message) throws IOException {
        input.write(message.strip());
        input.newLine();
        input.flush();
    }

    private JsonNode readUntilTerminal(
            Process process,
            BufferedReader output,
            JsonMapper mapper) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            JsonNode event = readEvent(process, output, mapper);
            String type = event.get("type").stringValue();
            if (type.equals("run.completed")
                    || type.equals("run.failed")
                    || type.equals("run.cancelled")) {
                return event;
            }
        }
        throw new AssertionError("未在事件上限内收到 Run 终态");
    }

    private JsonNode readEvent(
            Process process,
            BufferedReader output,
            JsonMapper mapper)
            throws Exception {
        long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
        while (!output.ready()) {
            if (!process.isAlive()) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("等待子进程协议事件超时");
            }
            Thread.sleep(10);
        }
        String line = output.readLine();
        assertThat(line).as("子进程必须输出协议事件").isNotNull();
        JsonNode event = mapper.readTree(line);
        assertThat(event.isObject()).isTrue();
        return event;
    }
}
