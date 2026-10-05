package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.core.subagent.ChildTaskJournal;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 使用项目自有 append-only JSONL 保存子任务聚合生命周期。
 *
 * <p>记录不含 Prompt、Tool 参数/输出或绝对路径。恢复只把缺少 terminal 的 identity 投影为
 * {@link ChildTaskStatus#INTERRUPTED_UNKNOWN}，不调用模型、Tool 或 Git Adapter。</p>
 *
 * @since 0.12.0
 */
public final class FileChildTaskJournal implements ChildTaskJournal, AutoCloseable {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private final Path path;
    private final Path failureDirectory;
    private final FileChannel channel;

    /**
     * 在固定目录打开或创建子任务日志及终态失败标记目录。
     *
     * @param directory 日志根目录
     */
    public FileChildTaskJournal(Path directory) {
        try {
            Path root = Objects.requireNonNull(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            path = root.resolve("child-tasks.jsonl");
            failureDirectory = root.resolve("terminal-failures");
            Files.createDirectories(failureDirectory);
            if (Files.isSymbolicLink(failureDirectory)
                    || !Files.isDirectory(failureDirectory, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("task journal failure marker root 不安全");
            if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IllegalArgumentException("task journal 不安全");
            FileChannel opened = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            if (opened.size() > MAX_BYTES) {
                opened.close();
                throw new IllegalArgumentException("task journal 超过上限");
            }
            opened.position(opened.size());
            channel = opened;
        } catch (IOException failure) {
            throw new IllegalArgumentException("task journal 初始化失败", failure);
        }
    }

    @Override public synchronized void requested(ChildTaskId id) { append(id, "requested", null); }
    @Override public synchronized void started(ChildTaskId id) { append(id, "started", null); }
    @Override public synchronized void terminal(ChildTaskReport report) { append(report.taskId(), "terminal", report); }

    /**
     * 返回恢复时的固定 terminal 投影；不会修改 journal 或重放任务。
     *
     * @return 缺少可靠 terminal 记录的中断任务投影
     */
    public synchronized List<ChildTaskReport> interruptedUnknown() {
        try {
            channel.force(true);
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("task journal 超过上限");
            Map<String, State> states = new LinkedHashMap<>();
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", -1);
            int completeLines = content.endsWith("\n") || content.endsWith("\r") ? lines.length : lines.length - 1;
            for (int index = 0; index < completeLines; index++) {
                String line = lines[index];
                if (line.isBlank()) continue;
                String id = field(line, "taskId"); String event = field(line, "event");
                State state = states.computeIfAbsent(id, ignored -> new State());
                state.accept(event);
            }
            try (var markers = Files.list(failureDirectory)) {
                markers.filter(path -> path.getFileName().toString().endsWith(".failed"))
                        .forEach(marker -> {
                            String name = marker.getFileName().toString();
                            String id = name.substring(0, name.length() - ".failed".length());
                            states.computeIfAbsent(id, ignored -> new State()).terminal = true;
                        });
            }
            List<ChildTaskReport> reports = new ArrayList<>();
            states.forEach((id, state) -> { if (state.seen && !state.terminal) reports.add(new ChildTaskReport(
                    new ChildTaskId(id), new AgentDefinitionId("recovered"), ChildTaskStatus.INTERRUPTED_UNKNOWN,
                    ChildTaskFailureCode.INTERRUPTED_UNKNOWN, 0, 0, 0, Duration.ZERO,
                    "interrupted_unknown", false, Optional.empty())); });
            return List.copyOf(reports);
        } catch (IOException failure) { throw new IllegalStateException("task journal 恢复失败", failure); }
    }

    /**
     * 主 JSONL terminal 写失败时，以 task identity 独占原子 marker 封闭恢复语义。
     */
    @Override public synchronized void terminalFailure(ChildTaskReport report) {
        Objects.requireNonNull(report, "report 不能为空");
        Path target = failureDirectory.resolve(report.taskId().value() + ".failed").normalize();
        if (!target.getParent().equals(failureDirectory)) throw new IllegalArgumentException("task identity 无效");
        Path temporary = failureDirectory.resolve(report.taskId().value() + ".tmp").normalize();
        byte[] bytes = ("v=1\ntaskId=" + report.taskId().value() + "\nstatus=JOURNAL_FAILED\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileChannel marker = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) marker.write(buffer);
            marker.force(true);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (FileAlreadyExistsException alreadyMarked) {
            // marker 幂等；既存 identity 已经 fail closed。
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("task terminal failure marker 写入失败", failure);
        }
    }

    private void append(ChildTaskId id, String event, ChildTaskReport report) {
        StringBuilder json = new StringBuilder("{\"v\":1,\"at\":\"").append(Instant.now())
                .append("\",\"taskId\":\"").append(id.value()).append("\",\"event\":\"").append(event).append('"');
        if (report != null) json.append(",\"definitionId\":\"").append(report.definitionId().value())
                .append("\",\"status\":\"").append(report.status()).append("\",\"failure\":\"")
                .append(report.failureCode()).append("\",\"modelTurns\":").append(report.modelTurns())
                .append(",\"toolCalls\":").append(report.toolCalls())
                .append(",\"cleanupStatus\":\"").append(report.cleanupStatus()).append('"');
        json.append("}\n");
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (channel.size() + bytes.length > MAX_BYTES) throw new IllegalStateException("task journal 超过上限");
            channel.position(channel.size());
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (IOException failure) { throw new IllegalStateException("task journal 写入失败", failure); }
    }

    private static String field(String line, String name) {
        String marker = "\"" + name + "\":\""; int start = line.indexOf(marker);
        if (start < 0) throw new IllegalStateException("task journal 格式无效");
        start += marker.length(); int end = line.indexOf('"', start);
        if (end < 0) throw new IllegalStateException("task journal 格式无效");
        return line.substring(start, end);
    }

    @Override public synchronized void close() { try { channel.close(); } catch (IOException ignored) { } }
    private static final class State {
        private int sequence;
        private boolean seen;
        private boolean terminal;

        private void accept(String event) {
            if (terminal) throw new IllegalStateException("task journal terminal 后仍有事件");
            switch (event) {
                case "requested" -> {
                    if (sequence != 0) throw new IllegalStateException("task journal requested 重复或乱序");
                    sequence = 1; seen = true;
                }
                case "started" -> {
                    if (sequence != 1) throw new IllegalStateException("task journal started 重复或乱序");
                    sequence = 2; seen = true;
                }
                case "terminal" -> {
                    if (sequence < 1) throw new IllegalStateException("task journal terminal 缺少 requested");
                    sequence = 3; terminal = true;
                }
                default -> throw new IllegalStateException("task journal event 无效");
            }
        }
    }
}
