package io.github.liumaishenjian.ccjava.core.task;

import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonNull;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 四个 Task Tool 共用的封闭参数解析、可信身份校验、错误映射与 deterministic JSON 编码。
 *
 * <p>该类只理解 ADR-088 独立 wire name，不依赖 Jackson 或 Provider 类型；任何 Map/List
 * 都必须在这里收敛为 Task 领域允许的标量与 ID。错误输出只包含安全 identity/revision，
 * 不回显 subject、description、metadata 或原始 Tool 参数。</p>
 */
final class TaskToolSupport {
    static final int MAX_LIST_UTF8_BYTES = 16 * 1024;
    static final int MAX_GET_UTF8_BYTES = 16 * 1024;
    static final Set<String> CREATE_FIELDS = Set.of(
            "subject", "description", "active_form", "blocked_by", "metadata");
    static final Set<String> LIST_FIELDS = Set.of("status", "filter", "cursor", "limit");
    static final Set<String> GET_FIELDS = Set.of("task_id");
    static final Set<String> UPDATE_FIELDS = Set.of(
            "task_id", "operation", "expected_task_revision", "expected_board_revision",
            "expected_claim_epoch", "subject", "description", "active_form", "metadata_patch",
            "target_status", "target_actor", "add_blocked_by", "remove_blocked_by");

    private TaskToolSupport() { }

    /** 确认 Pipeline invocation 与构造时宿主 capability 的 Session/Run 完全一致。 */
    static Optional<ToolExecutionOutcome> identityFailure(
            ToolInvocation invocation, TaskBoardCapability capability, long boardRevision) {
        if (!invocation.sessionId().equals(capability.actorSessionId())
                || !invocation.runId().equals(capability.actorRunId())) {
            return Optional.of(taskFailure(new TaskDiagnostic(
                    TaskDiagnosticCode.TASK_CAPABILITY_DENIED, Optional.empty(), boardRevision,
                    Optional.empty(), Set.of())));
        }
        return Optional.empty();
    }

    /** 从 create payload 构造 mutation；callId 永远取 Pipeline ToolInvocation。 */
    static TaskMutation.Create createMutation(JsonObject arguments, String callId) {
        requireExactOrSubset(arguments, CREATE_FIELDS, Set.of("subject"));
        String subject = taskText(string(arguments, "subject", true), "subject", 200, true, -1, false);
        String description = taskText(string(arguments, "description", false, ""),
                "description", Integer.MAX_VALUE, false, 4_096, true);
        Optional<String> activeForm = optionalString(arguments, "active_form")
                .map(value -> taskText(value, "active_form", 200, true, -1, false));
        List<TaskId> blockedBy = taskIdList(arguments, "blocked_by", false);
        TaskMetadata metadata = metadata(arguments.values().get("metadata"));
        return new TaskMutation.Create(new TaskCallId(callId), subject, description,
                activeForm, metadata, blockedBy);
    }

    /** 从 update payload 构造 operation-specific mutation，并拒绝任意字段混用。 */
    static TaskMutation updateMutation(JsonObject arguments, String callId) {
        requireExactOrSubset(arguments, UPDATE_FIELDS,
                Set.of("task_id", "operation", "expected_task_revision"));
        TaskId taskId = taskId(string(arguments, "task_id", true));
        String operation = string(arguments, "operation", true);
        long expectedTaskRevision = positiveLong(arguments, "expected_task_revision", true);
        TaskCallId trustedCallId = new TaskCallId(callId);
        return switch (operation) {
            case "EDIT" -> {
                requireOperationFields(arguments, Set.of("task_id", "operation", "expected_task_revision"),
                        Set.of("expected_claim_epoch", "subject", "description", "active_form", "metadata_patch"), true);
                OptionalLong epoch = optionalPositiveLong(arguments, "expected_claim_epoch");
                Optional<String> subject = optionalString(arguments, "subject")
                        .map(value -> taskText(value, "subject", 200, true, -1, false));
                Optional<String> description = optionalString(arguments, "description")
                        .map(value -> taskText(value, "description", Integer.MAX_VALUE,
                                false, 4_096, true));
                boolean activeSpecified = arguments.values().containsKey("active_form");
                Optional<String> activeForm = optionalNullableString(arguments, "active_form")
                        .map(value -> taskText(value, "active_form", 200, true, -1, false));
                TaskMetadataPatch patch = metadataPatch(arguments.values().get("metadata_patch"));
                yield new TaskMutation.Edit(trustedCallId, taskId, expectedTaskRevision, epoch,
                        subject, description, activeSpecified, activeForm, patch);
            }
            case "TRANSITION" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "target_status"),
                        Set.of("expected_claim_epoch"), false);
                TaskStatus target = taskStatus(string(arguments, "target_status", true));
                yield new TaskMutation.Transition(trustedCallId, taskId, expectedTaskRevision,
                        target, optionalPositiveLong(arguments, "expected_claim_epoch"));
            }
            case "CLAIM" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision"), Set.of(), false);
                yield new TaskMutation.Claim(trustedCallId, taskId, expectedTaskRevision);
            }
            case "RESUME_CLAIM" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "expected_claim_epoch"),
                        Set.of(), false);
                yield new TaskMutation.ResumeClaim(trustedCallId, taskId, expectedTaskRevision,
                        positiveLong(arguments, "expected_claim_epoch", true));
            }
            case "RELEASE" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "expected_claim_epoch"),
                        Set.of(), false);
                yield new TaskMutation.Release(trustedCallId, taskId, expectedTaskRevision,
                        positiveLong(arguments, "expected_claim_epoch", true));
            }
            case "ASSIGN" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "target_actor"),
                        Set.of(), false);
                yield new TaskMutation.Assign(trustedCallId, taskId, expectedTaskRevision,
                        new TaskActorId(string(arguments, "target_actor", true)));
            }
            case "REASSIGN" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "target_actor"),
                        Set.of("expected_claim_epoch"), false);
                yield new TaskMutation.Reassign(trustedCallId, taskId, expectedTaskRevision,
                        new TaskActorId(string(arguments, "target_actor", true)),
                        optionalPositiveLong(arguments, "expected_claim_epoch"));
            }
            case "DEPENDENCY" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "expected_board_revision"),
                        Set.of("add_blocked_by", "remove_blocked_by"), true);
                yield new TaskMutation.Dependency(trustedCallId, taskId, expectedTaskRevision,
                        nonNegativeLong(arguments, "expected_board_revision", true),
                        taskIdList(arguments, "add_blocked_by", false),
                        taskIdList(arguments, "remove_blocked_by", false));
            }
            case "DELETE" -> {
                requireOperationFields(arguments,
                        Set.of("task_id", "operation", "expected_task_revision", "expected_board_revision"),
                        Set.of(), false);
                yield new TaskMutation.Delete(trustedCallId, taskId, expectedTaskRevision,
                        nonNegativeLong(arguments, "expected_board_revision", true));
            }
            default -> throw new IllegalArgumentException("operation 无效");
        };
    }

    /** 解析 Task List 的稳定过滤与游标。 */
    static TaskListQuery listQuery(JsonObject arguments) {
        requireExactOrSubset(arguments, LIST_FIELDS, Set.of());
        Optional<TaskStatus> status = optionalString(arguments, "status").map(TaskToolSupport::taskStatus);
        Optional<String> filter = optionalString(arguments, "filter");
        Optional<TaskId> cursor = optionalString(arguments, "cursor").map(TaskToolSupport::taskId);
        int limit = arguments.values().containsKey("limit")
                ? Math.toIntExact(positiveLong(arguments, "limit", true)) : 25;
        return new TaskListQuery(status, filter, cursor, limit);
    }

    /** 解析 Task Get 唯一模型参数。 */
    static TaskId getTaskId(JsonObject arguments) {
        requireExactOrSubset(arguments, GET_FIELDS, Set.of("task_id"));
        return taskId(string(arguments, "task_id", true));
    }

    /** 把 mutation 结果转为安全、紧凑 JSON 或 machine-readable TASK_* 错误。 */
    static ToolExecutionOutcome mutationOutcome(TaskMutationResult result) {
        if (!result.succeeded()) return taskFailure(result.diagnostic().orElseThrow());
        String content = "{\"board_revision\":" + result.snapshot().revision()
                + ",\"task\":" + result.task().map(TaskToolSupport::summaryJson).orElse("null") + "}";
        return ToolExecutionOutcome.success(content);
    }

    /** 把 List 页面编码为完整且不超过 16KiB 的稳定 JSON，并用既有 continuation metadata 表达续页。 */
    static ToolExecutionOutcome listOutcome(TaskReadResult<TaskListPage> result) {
        if (!result.succeeded()) return taskFailure(result.diagnostic().orElseThrow());
        TaskListPage page = result.value().orElseThrow();
        ArrayList<String> encoded = new ArrayList<>();
        Optional<TaskId> cursor = page.nextCursor();
        boolean byteLimited = false;
        for (int index = 0; index < page.tasks().size(); index++) {
            TaskSummary task = page.tasks().get(index);
            encoded.add(summaryJson(task));
            boolean remainingInPage = index + 1 < page.tasks().size();
            Optional<TaskId> candidateCursor = remainingInPage ? Optional.of(task.id()) : page.nextCursor();
            String candidate = listJson(page.boardRevision(), encoded, candidateCursor);
            if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_LIST_UTF8_BYTES) {
                encoded.remove(encoded.size() - 1);
                byteLimited = true;
                cursor = encoded.isEmpty()
                        ? Optional.empty()
                        : Optional.of(page.tasks().get(encoded.size() - 1).id());
                break;
            }
            cursor = candidateCursor;
        }
        if (byteLimited && encoded.isEmpty()) {
            return ToolExecutionOutcome.failure(ToolError.of(ToolErrorCode.OUTPUT_LIMIT_EXCEEDED,
                    "Task summary exceeds the safe 16KiB result budget"));
        }
        String content = listJson(page.boardRevision(), encoded, cursor);
        boolean truncated = cursor.isPresent();
        if (!truncated) return ToolExecutionOutcome.success(content);
        JsonObject continuation = new JsonObject(Map.of("cursor", cursor.orElseThrow().value()));
        return ToolExecutionOutcome.success(content, new ToolResultMetadata(true,
                byteLimited ? ToolResultTruncationReason.BYTE_LIMIT : ToolResultTruncationReason.ITEM_LIMIT,
                content.codePointCount(0, content.length()), OptionalLong.empty(), encoded.size(), 0,
                continuation));
    }

    private static String listJson(long boardRevision, List<String> tasks, Optional<TaskId> cursor) {
        return "{\"board_revision\":" + boardRevision + ",\"tasks\":[" + String.join(",", tasks)
                + "],\"next_cursor\":" + cursor.map(value -> quote(value.value())).orElse("null") + "}";
    }

    /** 把 Get detail 编码为完整 JSON；超过 16KiB 时返回结构化失败而不切断 JSON。 */
    static ToolExecutionOutcome getOutcome(TaskReadResult<TaskGetProjection> result) {
        if (!result.succeeded()) return taskFailure(result.diagnostic().orElseThrow());
        TaskGetProjection projection = result.value().orElseThrow();
        String content = "{\"board_revision\":" + projection.boardRevision() + ",\"task\":"
                + detailJson(projection.task()) + "}";
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_GET_UTF8_BYTES) {
            return ToolExecutionOutcome.failure(ToolError.of(ToolErrorCode.OUTPUT_LIMIT_EXCEEDED,
                    "Task detail exceeds the safe 16KiB result budget"));
        }
        return ToolExecutionOutcome.success(content);
    }

    /** 把 Task diagnostic 映射为 ToolError，不投影任务正文。 */
    static ToolExecutionOutcome taskFailure(TaskDiagnostic diagnostic) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        diagnostic.taskId().ifPresent(value -> details.put("task_id", value.value()));
        details.put("board_revision", diagnostic.boardRevision());
        diagnostic.taskRevision().ifPresent(value -> details.put("task_revision", value));
        if (!diagnostic.relatedTaskIds().isEmpty()) {
            details.put("related_task_ids", diagnostic.relatedTaskIds().stream().map(TaskId::value).toList());
        }
        return ToolExecutionOutcome.failure(new ToolError(
                ToolErrorCode.valueOf(diagnostic.code().name()),
                "Task operation rejected", new JsonObject(details)));
    }

    static String summaryJson(TaskItemView view) {
        return summaryJson(new TaskSummary(view.id(), view.revision(), view.status(), view.subject(),
                view.blocked(), view.activeBlockers(), view.owner(), view.activeForm(), view.recoveryRequired()));
    }

    static String summaryJson(TaskSummary task) {
        return "{\"id\":" + quote(task.id().value())
                + ",\"task_revision\":" + task.taskRevision()
                + ",\"status\":" + quote(statusWire(task.status()))
                + ",\"subject\":" + quote(task.subject())
                + ",\"blocked\":" + task.blocked()
                + ",\"blocker_ids\":" + idsJson(task.blockerIds())
                + ",\"owner\":" + task.owner().map(value -> quote(value.value())).orElse("null")
                + ",\"active_form\":" + task.activeForm().map(TaskToolSupport::quote).orElse("null")
                + ",\"recovery_required\":" + task.recoveryRequired() + "}";
    }

    private static String detailJson(TaskItemView view) {
        TaskItem item = view.item();
        String claim = item.claim().map(value -> "{\"actor_id\":" + quote(value.actorId().value())
                + ",\"run_id\":" + quote(value.runId().value())
                + ",\"claim_epoch\":" + value.epoch()
                + ",\"claimed_at\":" + quote(value.claimedAt().toString()) + "}").orElse("null");
        return "{\"id\":" + quote(item.id().value())
                + ",\"task_revision\":" + item.revision()
                + ",\"status\":" + quote(statusWire(item.status()))
                + ",\"subject\":" + quote(item.subject())
                + ",\"description\":" + quote(item.description())
                + ",\"active_form\":" + item.activeForm().map(TaskToolSupport::quote).orElse("null")
                + ",\"metadata\":" + metadataJson(item.metadata())
                + ",\"blocked_by\":" + idsJson(item.blockedBy())
                + ",\"blocks\":" + idsJson(view.blocks())
                + ",\"blocked\":" + view.blocked()
                + ",\"blocker_ids\":" + idsJson(view.activeBlockers())
                + ",\"owner\":" + item.owner().map(value -> quote(value.value())).orElse("null")
                + ",\"claim\":" + claim
                + ",\"last_claim_epoch\":" + item.lastClaimEpoch()
                + ",\"created_at\":" + quote(item.createdAt().toString())
                + ",\"updated_at\":" + quote(item.updatedAt().toString())
                + ",\"recovery_required\":" + view.recoveryRequired() + "}";
    }

    private static String metadataJson(TaskMetadata metadata) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        metadata.values().forEach((key, value) -> joiner.add(quote(key) + ":" + metadataValueJson(value)));
        return joiner.toString();
    }

    private static String metadataValueJson(TaskMetadataValue value) {
        if (value instanceof TaskMetadataValue.BooleanValue item) return Boolean.toString(item.value());
        if (value instanceof TaskMetadataValue.IntegerValue item) return Long.toString(item.value());
        return quote(((TaskMetadataValue.StringValue) value).value());
    }

    private static String idsJson(Collection<TaskId> ids) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        ids.forEach(value -> joiner.add(quote(value.value())));
        return joiner.toString();
    }

    private static String quote(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            switch (codePoint) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\n' -> encoded.append("\\n");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (codePoint <= 0x1F) encoded.append("\\u%04x".formatted(codePoint));
                    else encoded.appendCodePoint(codePoint);
                }
            }
            index += Character.charCount(codePoint);
        }
        return encoded.append('"').toString();
    }

    private static String statusWire(TaskStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static TaskStatus taskStatus(String value) {
        return TaskStatus.valueOf(value);
    }

    private static TaskId taskId(String value) {
        if (!value.matches("^task-[1-9][0-9]*$")) throw new IllegalArgumentException("task_id 格式无效");
        try {
            TaskId parsed = new TaskId(Long.parseLong(value.substring(5)));
            if (!parsed.value().equals(value)) throw new IllegalArgumentException("task_id 格式无效");
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("task_id 格式无效", invalid);
        }
    }

    private static TaskMetadata metadata(Object raw) {
        if (raw == null) return TaskMetadata.EMPTY;
        if (!(raw instanceof Map<?, ?> source)) throw new IllegalArgumentException("metadata 必须为 object");
        TreeMap<String, TaskMetadataValue> values = new TreeMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("metadata key 无效");
            if (entry.getValue() == JsonNull.INSTANCE) throw new IllegalArgumentException("metadata 不接受 null");
            values.put(key, metadataValue(entry.getValue()));
        }
        return new TaskMetadata(values);
    }

    private static TaskMetadataPatch metadataPatch(Object raw) {
        if (raw == null) return TaskMetadataPatch.empty();
        if (!(raw instanceof Map<?, ?> source)) throw new IllegalArgumentException("metadata_patch 必须为 object");
        TreeMap<String, TaskMetadataValue> upserts = new TreeMap<>();
        TreeSet<String> removals = new TreeSet<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("metadata_patch key 无效");
            if (entry.getValue() == JsonNull.INSTANCE) removals.add(key);
            else upserts.put(key, metadataValue(entry.getValue()));
        }
        return new TaskMetadataPatch(upserts, removals);
    }

    private static TaskMetadataValue metadataValue(Object value) {
        if (value instanceof Boolean booleanValue) return new TaskMetadataValue.BooleanValue(booleanValue);
        if (value instanceof String stringValue) return new TaskMetadataValue.StringValue(stringValue);
        if (value instanceof Number number) return new TaskMetadataValue.IntegerValue(exactLong(number));
        throw new IllegalArgumentException("metadata 只允许 boolean/integer/string");
    }

    private static List<TaskId> taskIdList(JsonObject arguments, String name, boolean required) {
        Object raw = arguments.values().get(name);
        if (raw == null) {
            if (required) throw new IllegalArgumentException(name + " 必填");
            return List.of();
        }
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException(name + " 必须为 array");
        if (values.size() > 32) throw new IllegalArgumentException(name + " 超过 32 项");
        ArrayList<TaskId> ids = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text)) throw new IllegalArgumentException(name + " 元素必须为 task_id");
            ids.add(taskId(text));
        }
        return List.copyOf(ids);
    }

    private static Optional<String> optionalString(JsonObject arguments, String name) {
        Object value = arguments.values().get(name);
        if (value == null) return Optional.empty();
        if (!(value instanceof String text)) throw new IllegalArgumentException(name + " 必须为 string");
        return Optional.of(text);
    }

    private static Optional<String> optionalNullableString(JsonObject arguments, String name) {
        Object value = arguments.values().get(name);
        if (value == null || value == JsonNull.INSTANCE) return Optional.empty();
        if (!(value instanceof String text)) throw new IllegalArgumentException(name + " 必须为 string 或 null");
        return Optional.of(text);
    }

    private static String string(JsonObject arguments, String name, boolean required) {
        return string(arguments, name, required, null);
    }

    private static String string(JsonObject arguments, String name, boolean required, String defaultValue) {
        Object value = arguments.values().get(name);
        if (value == null) {
            if (required) throw new IllegalArgumentException(name + " 必填");
            return defaultValue;
        }
        if (!(value instanceof String text)) throw new IllegalArgumentException(name + " 必须为 string");
        return text;
    }

    private static String taskText(String value, String name, int maxCodePoints,
            boolean requireNonBlank, int maxUtf8Bytes, boolean allowDescriptionWhitespace) {
        boolean invalidControl = value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !(allowDescriptionWhitespace && (codePoint == '\n' || codePoint == '\t')));
        if ((requireNonBlank && value.isBlank())
                || value.codePointCount(0, value.length()) > maxCodePoints
                || (maxUtf8Bytes >= 0 && value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes)
                || invalidControl || !validUnicode(value)) {
            throw new IllegalArgumentException(name + " 无效或超过上限");
        }
        return value;
    }

    private static boolean validUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++index))) return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static OptionalLong optionalPositiveLong(JsonObject arguments, String name) {
        if (!arguments.values().containsKey(name)) return OptionalLong.empty();
        return OptionalLong.of(positiveLong(arguments, name, true));
    }

    private static long positiveLong(JsonObject arguments, String name, boolean required) {
        long value = nonNegativeLong(arguments, name, required);
        if (value < 1) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }

    private static long nonNegativeLong(JsonObject arguments, String name, boolean required) {
        Object raw = arguments.values().get(name);
        if (raw == null) {
            if (required) throw new IllegalArgumentException(name + " 必填");
            return 0;
        }
        if (!(raw instanceof Number number)) throw new IllegalArgumentException(name + " 必须为 integer");
        long value = exactLong(number);
        if (value < 0) throw new IllegalArgumentException(name + " 不能为负数");
        return value;
    }

    private static long exactLong(Number number) {
        try {
            if (number instanceof BigDecimal decimal) return decimal.longValueExact();
            if (number instanceof BigInteger integer) return integer.longValueExact();
            if (number instanceof Double value) {
                if (!Double.isFinite(value) || value != Math.rint(value)) throw new ArithmeticException();
                return BigDecimal.valueOf(value).longValueExact();
            }
            if (number instanceof Float value) {
                if (!Float.isFinite(value) || value != Math.rint(value)) throw new ArithmeticException();
                return BigDecimal.valueOf(value.doubleValue()).longValueExact();
            }
            return number.longValue();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("integer 超出范围或包含小数", invalid);
        }
    }

    private static void requireExactOrSubset(JsonObject arguments, Set<String> allowed, Set<String> required) {
        if (!allowed.containsAll(arguments.values().keySet())
                || !arguments.values().keySet().containsAll(required)) {
            throw new IllegalArgumentException("字段集合无效");
        }
    }

    private static void requireOperationFields(JsonObject arguments, Set<String> required,
            Set<String> optional, boolean requireOptionalMutationField) {
        HashSet<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        Set<String> actual = arguments.values().keySet();
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) {
            throw new IllegalArgumentException("operation 字段集合无效");
        }
        if (requireOptionalMutationField && optional.stream().noneMatch(actual::contains)) {
            throw new IllegalArgumentException("operation 缺少 mutation 字段");
        }
    }
}
