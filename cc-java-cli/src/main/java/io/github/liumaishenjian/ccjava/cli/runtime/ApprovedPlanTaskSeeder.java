package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.task.TaskBoardCapabilityFactory;
import io.github.liumaishenjian.ccjava.core.task.TaskListService;
import io.github.liumaishenjian.ccjava.core.task.TaskMutation;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.TaskCallId;
import io.github.liumaishenjian.ccjava.domain.task.TaskItemView;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadata;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadataValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把已批准 Markdown 中的顶层有序步骤幂等物化为执行 Task。
 *
 * <p>该类型只识别 Markdown 的顶层有序列表标记，不解释命令、路径或嵌套结构，也不让模型
 * 重新命名、重排或补写步骤。完整条目保存在 description，用户可见 subject 保留首行原语言；
 * 超过 Task subject 上限时只缩短展示标题，不丢失 description 中的原始语义。</p>
 *
 * <p>物化使用稳定 Plan metadata 和 call ID。若进程在多个 Task 之间中断，恢复入口会验证已写前缀
 * 与批准 revision 完全一致后继续；任何冲突都 Fail Closed，避免把另一份 Plan 或模型创建的 Task
 * 冒充为批准步骤。该过程不执行 Plan 内容中的任何指令或副作用。</p>
 *
 * @since 0.15.0
 */
final class ApprovedPlanTaskSeeder {
    private static final Pattern ORDERED_STEP = Pattern.compile("^(\\d{1,4})[.)]\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,}).*$");
    private static final java.util.Set<String> STEP_SECTION_TITLES = java.util.Set.of(
            "拟定步骤", "实施步骤", "执行步骤",
            "proposed steps", "implementation steps", "execution steps");
    private static final String SOURCE = "approved-plan";

    private ApprovedPlanTaskSeeder() { }

    /**
     * 在 Plan 已 durable 批准后确保权威 Task 前缀完整存在。
     *
     * @param board 当前 root Session 的 durable Board
     * @param sessionId Board owner Session
     * @param actorRunId 用于持久 mutation 归属的宿主 Run identity
     * @param artifact 精确批准的 Plan revision
     * @return 物化后的有序步骤数量
     */
    static int seed(TaskListService board, SessionId sessionId, RunId actorRunId, PlanArtifact artifact) {
        List<PlanStep> steps = parse(artifact.markdownContent());
        if (steps.isEmpty()) return 0;
        var capability = TaskBoardCapabilityFactory.root(
                board.snapshot().boardId(), sessionId, actorRunId);
        List<TaskItemView> existing = planTasks(board, artifact);
        if (existing.size() > steps.size()) {
            throw new IllegalStateException("批准 Plan 的执行 Task 数量冲突");
        }
        for (int index = 0; index < existing.size(); index++) {
            TaskItemView task = existing.get(index);
            PlanStep step = steps.get(index);
            if (!task.subject().equals(step.subject()) || !task.description().equals(step.description())) {
                throw new IllegalStateException("批准 Plan 的执行 Task 内容冲突");
            }
        }
        for (int index = existing.size(); index < steps.size(); index++) {
            PlanStep step = steps.get(index);
            TaskMetadata metadata = metadata(artifact, index + 1);
            var mutation = new TaskMutation.Create(
                    new TaskCallId(callId(artifact, index + 1)),
                    step.subject(), step.description(), Optional.empty(), metadata, List.of());
            var result = board.execute(capability, mutation);
            if (!result.succeeded()) {
                throw new IllegalStateException("批准 Plan 的执行 Task 物化失败: "
                        + result.diagnostic().orElseThrow().code());
            }
        }
        return steps.size();
    }

    /**
     * 从唯一、明确命名的步骤 section 提取顶层有序条目。
     *
     * <p>全文中的城市编号、验收编号和代码示例均不是 Task 来源。零个候选 section 返回空列表，
     * 两个及以上候选 section Fail Closed；候选 section 只读取到下一个同级或更高 heading，且忽略
     * 代码 fence 与更深子 heading 下的编号，避免自然语言 Markdown 的其他列表扩张执行范围。</p>
     */
    static List<PlanStep> parse(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Section> candidates = candidateSections(lines);
        if (candidates.isEmpty()) return List.of();
        if (candidates.size() != 1) {
            throw new IllegalStateException("批准 Plan 必须且只能包含一个明确的执行步骤 section");
        }
        Section section = candidates.getFirst();
        ArrayList<PlanStep> steps = new ArrayList<>();
        StringBuilder current = null;
        boolean fenced = false;
        boolean insideNestedHeading = false;
        for (int index = section.startLine(); index < section.endLine(); index++) {
            String line = lines[index];
            if (FENCE.matcher(line).matches()) {
                fenced = !fenced;
                continue;
            }
            if (fenced) continue;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                insideNestedHeading = heading.group(1).length() > section.level();
                continue;
            }
            if (insideNestedHeading) continue;
            Matcher matcher = ORDERED_STEP.matcher(line);
            if (matcher.matches()) {
                if (current != null) steps.add(step(current.toString()));
                current = new StringBuilder(matcher.group(2).stripTrailing());
                continue;
            }
            if (current != null && (line.isBlank() || startsIndentedContinuation(line))) {
                if (line.isBlank()) current.append('\n');
                else current.append('\n').append(line.strip());
            } else if (current != null) {
                steps.add(step(current.toString()));
                current = null;
            }
        }
        if (current != null) steps.add(step(current.toString()));
        return List.copyOf(steps);
    }

    private static List<Section> candidateSections(String[] lines) {
        ArrayList<Section> sections = new ArrayList<>();
        boolean fenced = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (FENCE.matcher(line).matches()) {
                fenced = !fenced;
                continue;
            }
            if (fenced) continue;
            Matcher heading = HEADING.matcher(line);
            if (!heading.matches() || !isStepSectionTitle(heading.group(2))) continue;
            int level = heading.group(1).length();
            int end = lines.length;
            boolean sectionFence = false;
            for (int cursor = index + 1; cursor < lines.length; cursor++) {
                if (FENCE.matcher(lines[cursor]).matches()) {
                    sectionFence = !sectionFence;
                    continue;
                }
                if (sectionFence) continue;
                Matcher nextHeading = HEADING.matcher(lines[cursor]);
                if (nextHeading.matches() && nextHeading.group(1).length() <= level) {
                    end = cursor;
                    break;
                }
            }
            sections.add(new Section(level, index + 1, end));
        }
        return List.copyOf(sections);
    }

    private static boolean isStepSectionTitle(String title) {
        String normalized = title.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[：:]$", "").strip();
        return STEP_SECTION_TITLES.contains(normalized);
    }

    private static boolean startsIndentedContinuation(String line) {
        return line.startsWith(" ") || line.startsWith("\t");
    }

    private static PlanStep step(String description) {
        String normalized = trimTrailingBlankLines(description);
        String firstLine = normalized.lines().findFirst().orElseThrow().strip();
        if (firstLine.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > 4_096) {
            throw new IllegalStateException("批准 Plan 的步骤为空或超过 Task 描述上限");
        }
        return new PlanStep(truncateCodePoints(firstLine, 200), normalized);
    }

    private static String trimTrailingBlankLines(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\n') end--;
        return value.substring(0, end);
    }

    private static String truncateCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximum) return value;
        int end = value.offsetByCodePoints(0, maximum - 1);
        return value.substring(0, end) + "…";
    }

    private static TaskMetadata metadata(PlanArtifact artifact, int index) {
        return new TaskMetadata(Map.of(
                "plan.digest", new TaskMetadataValue.StringValue(artifact.contentDigest()),
                "plan.id", new TaskMetadataValue.StringValue(artifact.planId()),
                "plan.revision", new TaskMetadataValue.IntegerValue(authoritativeRevision(artifact)),
                "plan.source", new TaskMetadataValue.StringValue(SOURCE),
                "plan.step", new TaskMetadataValue.IntegerValue(index)));
    }

    /**
     * 判断当前批准 revision 的全部权威步骤是否都已真实完成且不存在恢复/阻塞状态。
     *
     * <p>普通模型创建的 Task、旧 Plan revision 和只有部分 seed 的前缀均不能触发 final-only。</p>
     */
    static boolean completionReady(TaskListService board, PlanArtifact artifact) {
        List<PlanStep> expected = parse(artifact.markdownContent());
        if (expected.isEmpty()) return false;
        List<TaskItemView> tasks = planTasks(board, artifact);
        if (tasks.size() != expected.size()) return false;
        for (int index = 0; index < tasks.size(); index++) {
            TaskItemView task = tasks.get(index);
            PlanStep step = expected.get(index);
            if (stepIndex(task) != index + 1L || !task.subject().equals(step.subject())
                    || !task.description().equals(step.description())
                    || task.status() != io.github.liumaishenjian.ccjava.domain.task.TaskStatus.COMPLETED
                    || task.blocked() || task.recoveryRequired()) return false;
        }
        return true;
    }

    private static List<TaskItemView> planTasks(TaskListService board, PlanArtifact artifact) {
        return board.snapshot().tasks().values().stream()
                .filter(task -> metadataMatches(task, artifact))
                .sorted(Comparator.comparingLong(ApprovedPlanTaskSeeder::stepIndex))
                .toList();
    }

    private static boolean metadataMatches(TaskItemView task, PlanArtifact artifact) {
        Map<String, TaskMetadataValue> values = task.metadata().values();
        return stringValue(values.get("plan.source")).filter(SOURCE::equals).isPresent()
                && stringValue(values.get("plan.id")).filter(artifact.planId()::equals).isPresent()
                && stringValue(values.get("plan.digest")).filter(artifact.contentDigest()::equals).isPresent()
                && integerValue(values.get("plan.revision"))
                        .filter(value -> value == authoritativeRevision(artifact)).isPresent();
    }

    private static long stepIndex(TaskItemView task) {
        return integerValue(task.metadata().values().get("plan.step")).orElseThrow();
    }

    private static Optional<String> stringValue(TaskMetadataValue value) {
        return value instanceof TaskMetadataValue.StringValue string ? Optional.of(string.value()) : Optional.empty();
    }

    private static Optional<Long> integerValue(TaskMetadataValue value) {
        return value instanceof TaskMetadataValue.IntegerValue integer ? Optional.of(integer.value()) : Optional.empty();
    }

    /** 执行状态 revision 会推进；Task 身份固定绑定用户实际批准的正文 revision。 */
    private static long authoritativeRevision(PlanArtifact artifact) {
        return artifact.executionBrief()
                .map(io.github.liumaishenjian.ccjava.domain.ExecutionBrief::approvedRevision)
                .orElse(artifact.revision());
    }

    private static String callId(PlanArtifact artifact, int index) {
        return "approved-plan:" + artifact.contentDigest().substring(0, 16) + ':'
                + authoritativeRevision(artifact) + ':' + index;
    }

    /** 一个保留原始语义正文和用户可见标题的批准步骤。 */
    record PlanStep(String subject, String description) { }

    /** 唯一明确步骤 section 在 Markdown 行数组中的有界范围。 */
    private record Section(int level, int startLine, int endLine) { }
}
