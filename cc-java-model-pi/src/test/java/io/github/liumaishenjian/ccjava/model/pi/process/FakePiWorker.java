package io.github.liumaishenjian.ccjava.model.pi.process;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 独立 JVM 子进程 fixture；手工构造 wire，不依赖生产 FrameCodec，不读取凭证。 */
public final class FakePiWorker {
    private FakePiWorker() { }

    public static void main(String[] args) throws Exception {
        String mode = Files.readString(Path.of(args[0]));
        switch (mode) {
            case "idle", "no-read" -> Thread.sleep(60000);
            case "crash" -> System.exit(7);
            case "half" -> System.out.print("{\"version\":1");
            case "missing" -> System.out.print(frame("test_operation", 0, "event", "{}"));
            case "cross" -> System.out.print(frame("other", 0, "operation.completed", "{}"));
            case "skip" -> System.out.print(frame("test_operation", 1, "operation.completed", "{}"));
            case "double" -> System.out.print(frame("test_operation", 0, "operation.completed", "{}")
                    + frame("test_operation", 1, "operation.failed", "{}"));
            case "after" -> System.out.print(frame("test_operation", 0, "operation.completed", "{}") + "x");
            case "flood" -> {
                for (int i = 0; i < 400; i++) System.out.print(frame("test_operation", i, "event", "{}"));
                System.out.flush();
                Thread.sleep(60000);
            }
            case "auth-space" -> {
                System.out.print(" ".repeat(32768) + frame("test_operation", 0, "event", "{}"));
                System.out.flush();
                Thread.sleep(60000);
            }
            case "auth-stderr" -> {
                new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                System.err.write(new byte[112 * 1024]);
                System.err.flush();
                Thread.sleep(60000);
            }
            case "stderr" -> {
                byte[] bytes = new byte[8192];
                java.util.Arrays.fill(bytes, (byte) 's');
                for (int i = 0; i < 300; i++) System.err.write(bytes);
                System.err.flush();
                Thread.sleep(60000);
            }
            case "huge" -> {
                System.out.print("x".repeat(1024 * 1024 + 1));
                System.out.flush();
            }
            case "total" -> {
                String payload = "{\"value\":\"" + "x".repeat(900000) + "\"}";
                for (int i = 0; i < 22; i++) System.out.print(frame("test_operation", i, "event", payload));
            }
            case "frames" -> {
                for (int i = 0; i < 65537; i++) System.out.print(frame("test_operation", i, "event", "{}"));
            }
            case "terminal-hang" -> {
                System.out.print(frame("test_operation", 0, "operation.completed", "{}"));
                System.out.flush();
                Thread.sleep(60000);
            }
            case "failed" -> {
                System.out.print(frame("test_operation", 0, "operation.failed", "{\"code\":\"SYNTHETIC\"}"));
                System.out.flush();
                System.exit(4);
            }
            case "sink" -> {
                byte[] bytes = new byte[8192];
                while (System.in.read(bytes) != -1) java.util.Arrays.fill(bytes, (byte) 0);
            }
            case "echo" -> {
                var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
                for (int i = 0; i < 3; i++) System.out.println(input.readLine());
            }
            case "environment" -> {
                boolean clean = System.getenv().keySet().stream().allMatch(name -> java.util.Set.of(
                        "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")
                        .contains(name.toUpperCase(java.util.Locale.ROOT)));
                boolean proxy = "http://synthetic:credential@127.0.0.1:9".equals(System.getenv("HTTP_PROXY"));
                boolean cwd = Path.of("").toRealPath().equals(Path.of(args[0]).getParent().toRealPath());
                System.out.print(frame("test_operation", 0, "environment.result", "{\"clean\":" + clean
                        + ",\"proxy\":" + proxy + ",\"cwd\":" + cwd + "}"));
                System.out.print(frame("test_operation", 1, "operation.completed", "{}"));
            }
            default -> {
                System.out.print(frame("test_operation", 0, "event", "{\"value\":\"synthetic-secret\"}"));
                System.out.print(frame("test_operation", 1, "operation.completed", "{}"));
            }
        }
        System.out.flush();
    }

    private static String frame(String operation, int sequence, String type, String payload) {
        return "{\"version\":1,\"operationId\":\"" + operation + "\",\"sequence\":" + sequence
                + ",\"type\":\"" + type + "\",\"payload\":" + payload + "}\n";
    }
}
