package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue;
import io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind;
import io.github.liumaishenjian.ccjava.core.SessionRecoverySnapshot;
import io.github.liumaishenjian.ccjava.core.PlanRecoveryProjection;
import io.github.liumaishenjian.ccjava.domain.PlanApprovalGate;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanLifecyclePolicy;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.UserFileAttachment;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 编解码项目自有 S06 major 1 Session JSONL 记录。
 *
 * <p>该格式是内部持久化协议，不承诺 S14 跨版本迁移。Codec 手工限制字段和类型；Jackson 类型
 * 只停留在 CLI Adapter，恢复输出为框架无关 Core 快照。</p>
 *
 * @since 0.6.0
 */
final class JsonlSessionCodec {

    static final int SCHEMA_MAJOR = 1;
    /** 1 MiB UTF-8 用户文本按 JSON 最坏 6 字节转义后再预留信封。 */
    static final int MAX_LINE_BYTES = 7 * 1_048_576;
    static final long MAX_FILE_BYTES = 64L * 1_048_576L;
    static final int MAX_RECORDS = 20_000;
    private static final int MAX_TEXT_CHARS = 1_048_576;
    private static final int MAX_IDENTIFIER_CHARS = 200;
    private static final int MAX_COLLECTION_ITEMS = 256;
    private static final int MAX_JSON_DEPTH = 16;
    private static final int MAX_JSON_NODES = 4_096;
    private static final java.util.regex.Pattern SHA_256 =
            java.util.regex.Pattern.compile("[0-9a-f]{64}");

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final TaskJournalJson taskJson = new TaskJournalJson(mapper);

    ObjectNode record(long sequence, String type) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaMajor", SCHEMA_MAJOR);
        root.put("sequence", sequence);
        root.put("recordType", type);
        return root;
    }

    String encode(ObjectNode record) {
        return mapper.writeValueAsString(record);
    }

    ObjectNode decode(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            if (node == null || !node.isObject()) {
                throw invalid("INVALID_RECORD", "Session record 必须是 JSON Object");
            }
            validateJsonShape(node);
            return (ObjectNode) node;
        } catch (SessionOpenException known) {
            throw known;
        } catch (Exception exception) {
            throw invalid("MALFORMED_RECORD", "Session record 不是合法 JSON");
        }
    }

    ObjectNode encodeSessionCreated(
            long sequence,
            SessionId sessionId,
            SessionSpec spec,
            String workspaceIdentity,
            Optional<SessionId> parentSessionId) {
        ObjectNode root = record(sequence, "session.created");
        root.put("sessionId", checkedIdentifier(sessionId.value(), "sessionId"));
        root.put("workspaceIdentity", checkedIdentifier(workspaceIdentity, "workspaceIdentity"));
        parentSessionId.ifPresent(parent -> root.put("parentSessionId", parent.value()));
        root.put("systemInstructions", checkedText(spec.systemInstructions(), "systemInstructions"));
        ObjectNode metadata = mapper.createObjectNode();
        if (spec.runtimeMetadata().size() > MAX_COLLECTION_ITEMS) {
            throw invalid("LIMIT_EXCEEDED", "Session metadata 条目过多");
        }
        spec.runtimeMetadata().forEach((key, value) ->
                metadata.put(checkedIdentifier(key, "metadata key"), checkedText(value, "metadata value")));
        root.set("metadata", metadata);
        return root;
    }

    ObjectNode encodeRunStarted(long sequence, RunId runId, UserMessage message) {
        ObjectNode root = record(sequence, "run.started");
        root.put("runId", checkedIdentifier(runId.value(), "runId"));
        root.put("userText", checkedText(message.content(), "userText"));
        ArrayNode attachments = mapper.createArrayNode();
        for (UserFileAttachment attachment : message.attachments()) {
            ObjectNode item = mapper.createObjectNode();
            item.put("protocolPath", checkedText(attachment.protocolPath(), "attachment.protocolPath", 1_024));
            item.put("textSnapshot", checkedText(attachment.textSnapshot(), "attachment.textSnapshot", 65_536));
            item.put("sha256Digest", checkedDigest(attachment.sha256Digest(), "attachment.sha256Digest"));
            item.put("startLine", attachment.startLine());
            item.put("endLine", attachment.endLine());
            item.put("truncated", attachment.truncated());
            attachments.add(item);
        }
        root.set("attachments", attachments);
        return root;
    }

    ObjectNode encodeAssistant(long sequence, RunId runId, AssistantMessage message) {
        ObjectNode root = record(sequence, "assistant.appended");
        root.put("runId", checkedIdentifier(runId.value(), "runId"));
        root.put("text", checkedText(message.text(), "assistantText"));
        if (message.toolCalls().size() > MAX_COLLECTION_ITEMS) {
            throw invalid("LIMIT_EXCEEDED", "Tool Call 批次过大");
        }
        ArrayNode calls = mapper.createArrayNode();
        for (ToolCall call : message.toolCalls()) {
            ObjectNode callNode = mapper.createObjectNode();
            callNode.put("id", checkedIdentifier(call.id(), "callId"));
            callNode.put("name", checkedIdentifier(call.name(), "toolName"));
            JsonNode arguments = mapper.valueToTree(call.arguments().jsonValues());
            validateJsonShape(arguments);
            callNode.set("arguments", arguments);
            calls.add(callNode);
        }
        root.set("toolCalls", calls);
        return root;
    }

    ObjectNode encodeToolResolved(
            long sequence,
            RunId runId,
            int ordinal,
            ToolResult result,
            String reason) {
        ObjectNode root = record(sequence, "tool.resolved");
        root.put("runId", runId.value());
        root.put("ordinal", ordinal);
        root.put("resolutionReason", checkedIdentifier(reason, "resolutionReason"));
        root.set("result", encodeToolResult(result));
        return root;
    }

    ObjectNode encodeToolStarted(
            long sequence,
            RunId runId,
            int ordinal,
            String callId,
            String toolName,
            ToolEffect effect) {
        ObjectNode root = record(sequence, "tool.started");
        root.put("runId", runId.value());
        root.put("ordinal", ordinal);
        root.put("callId", checkedIdentifier(callId, "callId"));
        root.put("toolName", checkedIdentifier(toolName, "toolName"));
        root.put("effect", effect.name());
        return root;
    }

    ObjectNode encodeToolCompleted(
            long sequence,
            RunId runId,
            int ordinal,
            ToolResult result) {
        ObjectNode root = record(sequence, "tool.completed");
        root.put("runId", runId.value());
        root.put("ordinal", ordinal);
        root.set("result", encodeToolResult(result));
        return root;
    }

    ObjectNode encodeSkillInvoked(long sequence, RunId runId, SkillInvocationKind kind,
            SkillRecoveryRecord recovery) {
        ObjectNode root = record(sequence, "skill.invoked");
        root.put("runId", checkedIdentifier(runId.value(), "runId"));
        root.put("skillId", checkedIdentifier(recovery.skillId().value(), "skillId"));
        root.put("invocationKind", kind.name());
        root.put("snapshotId", checkedDigest(recovery.snapshotId(), "snapshotId"));
        root.put("manifestDigest", checkedDigest(recovery.manifestDigest(), "manifestDigest"));
        root.put("bodyDigest", checkedDigest(recovery.bodyDigest(), "bodyDigest"));
        root.put("contentDigest", checkedDigest(recovery.contentDigest(), "contentDigest"));
        root.put("resourcesDigest", checkedDigest(recovery.resourcesDigest(), "resourcesDigest"));
        root.put("effectiveToolDigest", checkedDigest(recovery.effectiveToolDigest(), "effectiveToolDigest"));
        root.put("hookSetDigest", checkedDigest(recovery.hookSetDigest(), "hookSetDigest"));
        root.put("pluginTreeDigest", checkedDigest(recovery.pluginTreeDigest(), "pluginTreeDigest"));
        root.put("pluginManifestDigest", checkedDigest(recovery.pluginManifestDigest(), "pluginManifestDigest"));
        root.put("mcpConfigDigest", checkedDigest(recovery.mcpConfigDigest(), "mcpConfigDigest"));
        return root;
    }

    ObjectNode encodeSkillCompleted(long sequence, RunId runId, SkillId skillId,
            SkillInvocationKind kind, SkillErrorCode errorCode) {
        ObjectNode root = record(sequence, "skill.completed");
        root.put("runId", checkedIdentifier(runId.value(), "runId"));
        root.put("skillId", checkedIdentifier(skillId.value(), "skillId"));
        root.put("invocationKind", kind.name());
        root.put("status", errorCode == null ? "SUCCEEDED" : "FAILED");
        if (errorCode != null) root.put("errorCode", errorCode.name());
        return root;
    }

    ObjectNode encodeRunCompleted(long sequence, RunId runId, String stopReason) {
        ObjectNode root = record(sequence, "run.completed");
        root.put("runId", runId.value());
        root.put("stopReason", checkedIdentifier(stopReason, "stopReason"));
        return root;
    }

    /** 编码一次 durable-before-visible Task mutation。 */
    ObjectNode encodeTaskMutation(long sequence,
            io.github.liumaishenjian.ccjava.core.task.TaskMutationEvent event) {
        return taskJson.encodeMutation(sequence, event);
    }

    /** 编码 Fork 目标 Board 的 lineage seed。 */
    ObjectNode encodeTaskBoardSeed(long sequence,
            io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed seed) {
        return taskJson.encodeSeed(sequence, seed);
    }

    ObjectNode encodePlanArtifact(long sequence, PlanArtifact artifact) {
        ObjectNode root = record(sequence, "plan.artifact.saved");
        root.put("planId", checkedIdentifier(artifact.planId(), "planId"));
        root.put("sessionId", checkedIdentifier(artifact.sessionId().value(), "sessionId"));
        root.put("revision", artifact.revision());
        root.put("markdownContent", checkedText(artifact.markdownContent(), "plan.markdownContent"));
        root.put("contentDigest", checkedDigest(artifact.contentDigest(), "plan.contentDigest"));
        root.put("status", artifact.status().name());
        root.put("createdAt", artifact.createdAt().toString());
        root.put("updatedAt", artifact.updatedAt().toString());
        artifact.executionBrief().ifPresent(brief -> root.set("executionBrief",
                ExecutionBriefJson.encode(mapper.createObjectNode(), brief)));
        root.set("evidenceLedger", PlanEvidenceLedgerJson.encode(mapper.createObjectNode(), artifact.evidenceLedger()));
        return root;
    }

    ObjectNode encodePlanCommit(
            long sequence, PlanArtifact artifact, PlanDocument document, PlanExecutionState state) {
        PlanRecoveryProjection projection;
        try {
            projection = new PlanRecoveryProjection(document, state);
        } catch (IllegalArgumentException invalidProjection) {
            throw invalid("INVALID_RECORD", "Plan commit projection 不一致");
        }
        if (!artifact.planId().equals(projection.document().id())
                || artifact.status() != projection.document().status()) {
            throw invalid("INVALID_RECORD", "Plan commit 身份或状态不一致");
        }
        ObjectNode root = encodePlanArtifact(sequence, artifact);
        addPlanProjection(root, document, state);
        return root;
    }

    ObjectNode encodePlanSnapshot(long sequence, PlanDocument document, PlanExecutionState state) {
        try {
            new PlanRecoveryProjection(document, state);
        } catch (IllegalArgumentException invalidProjection) {
            throw invalid("INVALID_RECORD", "Plan snapshot projection 不一致");
        }
        ObjectNode root = record(sequence, "plan.snapshot");
        addPlanProjection(root, document, state);
        return root;
    }

    private void addPlanProjection(ObjectNode root, PlanDocument document, PlanExecutionState state) {
        root.put("planId", checkedIdentifier(document.id(), "planId"));
        root.put("objective", checkedText(document.objective(), "plan.objective"));
        root.put("status", document.status().name());
        root.put("workspaceDigest", checkedText(document.workspaceDigest(), "plan.workspaceDigest"));
        root.put("approvalGate", state.approvalGate().name());
        if (state.nextStep() == null) root.putNull("nextStep"); else root.put("nextStep", state.nextStep());
        if (state.activeStep() == null) root.putNull("activeStep"); else root.put("activeStep", state.activeStep());
        ArrayNode steps = mapper.createArrayNode();
        document.steps().forEach(step -> {
            ObjectNode item = mapper.createObjectNode();
            item.put("ordinal", step.ordinal());
            item.put("title", checkedText(step.title(), "plan.step.title"));
            item.put("detail", checkedText(step.detail(), "plan.step.detail"));
            item.put("expectedDigest", checkedText(step.expectedDigest(), "plan.step.expectedDigest"));
            steps.add(item);
        });
        root.set("steps", steps);
    }

    ObjectNode encodeCheckpointCreated(
            long sequence,
            RunId runId,
            int ordinal,
            io.github.liumaishenjian.ccjava.domain.CheckpointSummary summary,
            String preDigest) {
        ObjectNode root = record(sequence, "checkpoint.created");
        root.put("runId", checkedIdentifier(runId.value(), "runId"));
        root.put("ordinal", ordinal);
        root.put("checkpointId", checkedIdentifier(summary.id().value(), "checkpointId"));
        root.put("callId", checkedIdentifier(summary.callId(), "callId"));
        root.put("toolName", checkedIdentifier(summary.toolName(), "toolName"));
        root.put("target", checkedText(summary.target(), "checkpoint.target"));
        root.put("existedBefore", summary.existedBefore());
        root.put("preDigest", checkedPreDigest(preDigest));
        return root;
    }

    ObjectNode encodeCheckpointCompleted(
            long sequence,
            RunId runId,
            int ordinal,
            io.github.liumaishenjian.ccjava.domain.CheckpointId checkpointId,
            Optional<String> postDigest,
            boolean postAbsent) {
        if (postDigest.isPresent() == postAbsent) {
            throw invalid("INVALID_RECORD", "Checkpoint post-state 必须且只能是 digest 或 ABSENT");
        }
        ObjectNode root = record(sequence, "checkpoint.completed");
        root.put("runId", checkedIdentifier(runId.value(), "runId"));
        root.put("ordinal", ordinal);
        root.put("checkpointId", checkedIdentifier(checkpointId.value(), "checkpointId"));
        root.put(
                "postState",
                postAbsent
                        ? "ABSENT"
                        : checkedDigest(postDigest.orElseThrow(), "postDigest"));
        return root;
    }

    ObjectNode encodeCheckpointUndoCompleted(
            long sequence,
            io.github.liumaishenjian.ccjava.domain.CheckpointId checkpointId) {
        ObjectNode root = record(sequence, "checkpoint.undo.completed");
        root.put("checkpointId", checkedIdentifier(checkpointId.value(), "checkpointId"));
        return root;
    }

    List<ObjectNode> forkRecords(
            List<String> sourceLines,
            SessionId targetSessionId,
            SessionSpec targetSpec,
            String workspaceIdentity,
            SessionId parentSessionId) {
        if (sourceLines.isEmpty()) {
            throw invalid("MISSING_HEADER", "Session journal 缺少首记录");
        }
        List<ObjectNode> records = new ArrayList<>();
        records.add(encodeSessionCreated(
                1,
                targetSessionId,
                targetSpec,
                workspaceIdentity,
                Optional.of(parentSessionId)));
        long sequence = 2;
        for (int index = 1; index < sourceLines.size(); index++) {
            ObjectNode source = decode(sourceLines.get(index));
            if (requiredInt(source, "schemaMajor") != SCHEMA_MAJOR) {
                throw invalid("UNSUPPORTED_VERSION", "不支持该 Session Schema 主版本");
            }
            ObjectNode copied = source.deepCopy();
            copied.put("sequence", sequence++);
            records.add(copied);
        }
        return List.copyOf(records);
    }

    SessionRecoverySnapshot replay(
            List<String> lines,
            boolean damagedTail,
            String expectedWorkspaceIdentity) {
        if (lines.isEmpty()) {
            throw invalid("MISSING_HEADER", "Session journal 缺少首记录");
        }
        if (lines.size() > MAX_RECORDS) {
            throw invalid("LIMIT_EXCEEDED", "Session record 数量超过上限");
        }
        List<AgentMessage> messages = new ArrayList<>();
        List<RunId> runIds = new ArrayList<>();
        List<SessionRecoveryIssue> issues = new ArrayList<>();
        List<SkillRecoveryRecord> skillRecords = new ArrayList<>();
        Map<String, SkillInvocationState> skillInvocations = new LinkedHashMap<>();
        Map<String, ToolCallState> calls = new LinkedHashMap<>();
        Map<String, CheckpointRecordState> checkpoints = new LinkedHashMap<>();
        Optional<PlanRecoveryProjection> plan = Optional.empty();
        Optional<PlanArtifact> planArtifact = Optional.empty();
        Optional<io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot> taskSnapshot = Optional.empty();
        Set<String> activeRuns = new HashSet<>();
        SessionId sessionId = null;
        SessionSpec spec = null;
        Optional<SessionId> parent = Optional.empty();
        long expectedSequence = 1;
        for (int index = 0; index < lines.size(); index++) {
            ObjectNode record = decode(lines.get(index));
            int major = requiredInt(record, "schemaMajor");
            if (major != SCHEMA_MAJOR) {
                throw invalid("UNSUPPORTED_VERSION", "不支持该 Session Schema 主版本");
            }
            long sequence = requiredLong(record, "sequence");
            if (sequence != expectedSequence++) {
                throw invalid("INVALID_SEQUENCE", "Session record sequence 不连续");
            }
            String type = requiredText(record, "recordType", MAX_IDENTIFIER_CHARS);
            if (index == 0 && !"session.created".equals(type)) {
                throw invalid("MISSING_HEADER", "首记录必须是 session.created");
            }
            switch (type) {
                case "session.created" -> {
                    if (index != 0) {
                        throw invalid("INVALID_RECORD", "session.created 只能出现一次");
                    }
                    sessionId = new SessionId(requiredText(record, "sessionId", MAX_IDENTIFIER_CHARS));
                    String workspace = requiredText(
                            record, "workspaceIdentity", MAX_IDENTIFIER_CHARS);
                    if (!workspace.equals(expectedWorkspaceIdentity)) {
                        throw invalid("WORKSPACE_MISMATCH", "Session 不属于当前 Workspace");
                    }
                    parent = optionalText(record, "parentSessionId", MAX_IDENTIFIER_CHARS)
                            .map(SessionId::new);
                    String instructions = requiredText(
                            record, "systemInstructions", MAX_TEXT_CHARS);
                    ObjectNode metadataNode = requiredObject(record, "metadata");
                    if (metadataNode.size() > MAX_COLLECTION_ITEMS) {
                        throw invalid("LIMIT_EXCEEDED", "Session metadata 条目过多");
                    }
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadataNode.properties().forEach(entry -> {
                        if (!entry.getValue().isString()) {
                            throw invalid("INVALID_RECORD", "Session metadata 必须是字符串");
                        }
                        metadata.put(
                                checkedIdentifier(entry.getKey(), "metadata key"),
                                checkedText(entry.getValue().stringValue(), "metadata value"));
                    });
                    spec = new SessionSpec(instructions, metadata);
                }
                case "run.started" -> {
                    String run = requiredText(record, "runId", MAX_IDENTIFIER_CHARS);
                    if (!activeRuns.add(run)) {
                        throw invalid("INVALID_RECORD", "Run 重复启动");
                    }
                    runIds.add(new RunId(run));
                    messages.add(new UserMessage(
                            requiredText(record, "userText", MAX_TEXT_CHARS),
                            decodeAttachments(record)));
                }
                case "assistant.appended" -> {
                    requireActiveRun(record, activeRuns);
                    String text = requiredTextAllowEmpty(record, "text", MAX_TEXT_CHARS);
                    ArrayNode callNodes = requiredArray(record, "toolCalls");
                    if (callNodes.size() > MAX_COLLECTION_ITEMS) {
                        throw invalid("LIMIT_EXCEEDED", "Tool Call 批次过大");
                    }
                    List<ToolCall> toolCalls = new ArrayList<>();
                    for (JsonNode node : callNodes) {
                        if (!node.isObject()) {
                            throw invalid("INVALID_RECORD", "Tool Call 必须是 JSON Object");
                        }
                        ObjectNode callNode = (ObjectNode) node;
                        String callId = requiredText(callNode, "id", MAX_IDENTIFIER_CHARS);
                        String toolName = requiredText(callNode, "name", MAX_IDENTIFIER_CHARS);
                        ObjectNode arguments = requiredObject(callNode, "arguments");
                        validateJsonShape(arguments);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> values = mapper.convertValue(arguments, Map.class);
                        ToolCall call;
                        try {
                            call = new ToolCall(callId, toolName, new JsonObject(values));
                        } catch (IllegalArgumentException invalidArguments) {
                            throw invalid("INVALID_RECORD", "Tool Call arguments 包含不支持的 JSON 值");
                        }
                        if (calls.putIfAbsent(callId, new ToolCallState(call)) != null) {
                            throw invalid("INVALID_RECORD", "Tool Call ID 重复");
                        }
                        toolCalls.add(call);
                    }
                    messages.add(new AssistantMessage(text, toolCalls));
                }
                case "tool.resolved" -> {
                    requireActiveRun(record, activeRuns);
                    int ordinal = requiredPositiveOrdinal(record);
                    requiredEnumValue(
                            io.github.liumaishenjian.ccjava.core.ToolResolutionReason.class,
                            record,
                            "resolutionReason");
                    ToolResult result = decodeToolResult(requiredObject(record, "result"));
                    ToolCallState state = requireCall(calls, result.callId(), result.toolName());
                    state.resolve(result, ordinal);
                    messages.add(new ToolResultMessage(result));
                }
                case "tool.started" -> {
                    requireActiveRun(record, activeRuns);
                    int ordinal = requiredPositiveOrdinal(record);
                    String callId = requiredText(record, "callId", MAX_IDENTIFIER_CHARS);
                    String toolName = requiredText(record, "toolName", MAX_IDENTIFIER_CHARS);
                    ToolEffect effect = enumValue(
                            ToolEffect.class,
                            requiredText(record, "effect", MAX_IDENTIFIER_CHARS),
                            "effect");
                    requireCall(calls, callId, toolName).start(effect, ordinal);
                }
                case "tool.completed" -> {
                    requireActiveRun(record, activeRuns);
                    int ordinal = requiredPositiveOrdinal(record);
                    ToolResult result = decodeToolResult(requiredObject(record, "result"));
                    ToolCallState state = requireCall(calls, result.callId(), result.toolName());
                    state.complete(result, ordinal);
                    messages.add(new ToolResultMessage(result));
                }
                case "skill.invoked" -> {
                    String run = requireActiveRun(record, activeRuns);
                    SkillId skillId = new SkillId(requiredText(record, "skillId", SkillId.MAX_GLOBAL_LENGTH));
                    SkillInvocationKind kind = requiredEnumValue(
                            SkillInvocationKind.class, record, "invocationKind");
                    SkillRecoveryRecord recovery = new SkillRecoveryRecord(
                            skillId,
                            requiredDigest(record, "snapshotId"),
                            requiredDigest(record, "manifestDigest"),
                            requiredDigest(record, "bodyDigest"),
                            requiredDigest(record, "contentDigest"),
                            requiredDigest(record, "resourcesDigest"),
                            requiredDigest(record, "effectiveToolDigest"),
                            requiredDigest(record, "hookSetDigest"),
                            requiredDigest(record, "pluginTreeDigest"),
                            requiredDigest(record, "pluginManifestDigest"),
                            requiredDigest(record, "mcpConfigDigest"));
                    String key = skillKey(run, skillId, kind);
                    if (skillInvocations.putIfAbsent(key, new SkillInvocationState(true)) != null) {
                        throw invalid("INVALID_RECORD", "Skill invocation 重复");
                    }
                    skillRecords.add(recovery);
                }
                case "skill.completed" -> {
                    String run = requireActiveRun(record, activeRuns);
                    SkillId skillId = new SkillId(requiredText(record, "skillId", SkillId.MAX_GLOBAL_LENGTH));
                    SkillInvocationKind kind = requiredEnumValue(
                            SkillInvocationKind.class, record, "invocationKind");
                    String status = requiredText(record, "status", MAX_IDENTIFIER_CHARS);
                    String key = skillKey(run, skillId, kind);
                    SkillInvocationState state = skillInvocations.get(key);
                    if ("SUCCEEDED".equals(status)) {
                        if (record.has("errorCode") || state == null || state.completed) {
                            throw invalid("INVALID_RECORD", "Skill completed 配对无效");
                        }
                    } else if ("FAILED".equals(status)) {
                        requiredEnumValue(SkillErrorCode.class, record, "errorCode");
                        if (state == null) {
                            state = new SkillInvocationState(false);
                            skillInvocations.put(key, state);
                        } else if (state.invoked || state.completed) {
                            throw invalid("INVALID_RECORD", "Skill failed 配对无效");
                        }
                    } else {
                        throw invalid("INVALID_RECORD", "Skill status 无效");
                    }
                    state.completed = true;
                }
                case "plan.artifact.saved" -> {
                    SessionId owner = new SessionId(requiredText(record, "sessionId", MAX_IDENTIFIER_CHARS));
                    if (sessionId == null || !sessionId.equals(owner)) {
                        throw invalid("INVALID_RECORD", "PlanArtifact Session ID 不匹配");
                    }
                    PlanArtifact candidate;
                    try {
                        candidate = new PlanArtifact(
                                requiredText(record, "planId", MAX_IDENTIFIER_CHARS), owner,
                                requiredPositiveLong(record, "revision"),
                                requiredText(record, "markdownContent", MAX_TEXT_CHARS),
                                requiredDigest(record, "contentDigest"),
                                requiredEnumValue(PlanStatus.class, record, "status"),
                                java.time.Instant.parse(requiredText(record, "createdAt", MAX_IDENTIFIER_CHARS)),
                                java.time.Instant.parse(requiredText(record, "updatedAt", MAX_IDENTIFIER_CHARS)),
                                record.has("executionBrief")
                                        ? Optional.of(ExecutionBriefJson.decode(record.get("executionBrief"), requiredText(record, "markdownContent", MAX_TEXT_CHARS)))
                                        : Optional.empty(),
                                record.has("evidenceLedger")
                                        ? PlanEvidenceLedgerJson.decode(record.get("evidenceLedger"))
                                        : io.github.liumaishenjian.ccjava.domain.PlanEvidenceLedger.planning(owner,
                                                requiredText(record, "planId", MAX_IDENTIFIER_CHARS),
                                                java.time.Instant.parse(requiredText(record, "createdAt", MAX_IDENTIFIER_CHARS))));
                    } catch (SessionOpenException invalidRecord) {
                        throw invalidRecord;
                    } catch (RuntimeException invalidArtifact) {
                        throw invalid("INVALID_RECORD", "PlanArtifact revision、时间或正文无效");
                    }
                    if (planArtifact.isPresent()) {
                        PlanArtifact previous = planArtifact.orElseThrow();
                        if (!previous.planId().equals(candidate.planId())
                                || !previous.createdAt().equals(candidate.createdAt())
                                || candidate.revision() != previous.revision() + 1
                                || candidate.updatedAt().isBefore(previous.updatedAt())
                                || !PlanLifecyclePolicy.validTransition(previous.status(), candidate.status())) {
                            throw invalid("INVALID_RECORD", "PlanArtifact revision、时间或状态链无效");
                        }
                    } else if (candidate.revision() != 1
                            || !PlanLifecyclePolicy.validInitial(candidate.status())) {
                        throw invalid("INVALID_RECORD", "PlanArtifact 首个 revision 或状态无效");
                    }
                    planArtifact = Optional.of(candidate);
                    if (record.has("steps")) {
                        plan = Optional.of(decodePlanProjection(record, planArtifact, plan));
                    }
                }
                case "plan.snapshot" ->
                        plan = Optional.of(decodePlanProjection(record, planArtifact, plan));
                case "checkpoint.created" -> {
                    requireActiveRun(record, activeRuns);
                    int ordinal = requiredPositiveOrdinal(record);
                    String checkpointId = requiredText(record, "checkpointId", MAX_IDENTIFIER_CHARS);
                    String callId = requiredText(record, "callId", MAX_IDENTIFIER_CHARS);
                    String toolName = requiredText(record, "toolName", MAX_IDENTIFIER_CHARS);
                    ToolCallState call = requireCall(calls, callId, toolName);
                    if (call.ordinal().isPresent() && call.ordinal().getAsLong() != ordinal) {
                        throw invalid("INVALID_RECORD", "Checkpoint ordinal 与 Tool 不匹配");
                    }
                    requiredText(record, "target", MAX_TEXT_CHARS);
                    boolean existedBefore = requiredBoolean(record, "existedBefore");
                    String preDigest = requiredText(record, "preDigest", MAX_IDENTIFIER_CHARS);
                    if (existedBefore) {
                        requireDigest(preDigest, "preDigest");
                    } else if (!"ABSENT".equals(preDigest)) {
                        throw invalid("INVALID_RECORD", "新文件 Checkpoint 的 preDigest 必须是 ABSENT");
                    }
                    if (checkpoints.putIfAbsent(
                            checkpointId,
                            new CheckpointRecordState(callId, ordinal)) != null) {
                        throw invalid("INVALID_RECORD", "Checkpoint ID 重复");
                    }
                }
                case "checkpoint.completed" -> {
                    requireActiveRun(record, activeRuns);
                    int ordinal = requiredPositiveOrdinal(record);
                    String checkpointId = requiredText(record, "checkpointId", MAX_IDENTIFIER_CHARS);
                    String postState = requiredText(record, "postState", MAX_IDENTIFIER_CHARS);
                    if (!"ABSENT".equals(postState)) {
                        requireDigest(postState, "postState");
                    }
                    CheckpointRecordState checkpoint = checkpoints.get(checkpointId);
                    if (checkpoint == null || checkpoint.ordinal != ordinal || checkpoint.completed) {
                        throw invalid("INVALID_RECORD", "Checkpoint completed 配对无效");
                    }
                    checkpoint.completed = true;
                }
                case "checkpoint.undo.completed" -> {
                    String checkpointId = requiredText(record, "checkpointId", MAX_IDENTIFIER_CHARS);
                    CheckpointRecordState checkpoint = checkpoints.get(checkpointId);
                    if (checkpoint == null || !checkpoint.completed || checkpoint.undone) {
                        throw invalid("INVALID_RECORD", "Checkpoint Undo 配对无效");
                    }
                    checkpoint.undone = true;
                }
                case "task.mutation.succeeded" -> {
                    try {
                        var event = taskJson.decodeEvent(record, taskSnapshot);
                        taskSnapshot = Optional.of(event.snapshot());
                    }
                    catch (RuntimeException invalidTask) {
                        throw invalid("INVALID_RECORD", "Task mutation record 无效");
                    }
                }
                case "task.board.forked" -> {
                    try {
                        if (taskSnapshot.isPresent()) throw new IllegalArgumentException("duplicate task seed");
                        taskSnapshot = Optional.of(taskJson.decodeSeed(record).snapshot());
                    }
                    catch (RuntimeException invalidTask) {
                        throw invalid("INVALID_RECORD", "Task fork record 无效");
                    }
                }
                case "run.completed" -> {
                    String run = requiredText(record, "runId", MAX_IDENTIFIER_CHARS);
                    if (!activeRuns.remove(run)) {
                        throw invalid("INVALID_RECORD", "run.completed 没有活动 Run");
                    }
                    enumValue(
                            io.github.liumaishenjian.ccjava.domain.StopReason.class,
                            requiredText(record, "stopReason", MAX_IDENTIFIER_CHARS),
                            "stopReason");
                }
                default -> throw invalid("UNKNOWN_RECORD", "不支持的 Session recordType");
            }
        }
        if (plan.isPresent() && planArtifact.isPresent()) {
            PlanRecoveryProjection projection = plan.orElseThrow();
            PlanArtifact artifact = planArtifact.orElseThrow();
            if (!projection.document().id().equals(artifact.planId())
                    || projection.document().status() != artifact.status()) {
                throw invalid("INVALID_RECORD", "Plan 最终 snapshot 与 artifact 不一致");
            }
        }
        if (damagedTail) {
            issues.add(SessionRecoveryIssue.session(SessionRecoveryIssueKind.DAMAGED_TAIL));
        }
        if (!activeRuns.isEmpty()) {
            issues.add(SessionRecoveryIssue.session(SessionRecoveryIssueKind.UNFINISHED_RUN));
        }
        for (ToolCallState state : calls.values()) {
            state.issue().ifPresent(issues::add);
        }
        if (skillInvocations.values().stream().anyMatch(state -> !state.completed)) {
            issues.add(SessionRecoveryIssue.session(SessionRecoveryIssueKind.SKILL_INVOCATION_UNFINISHED));
        }
        return new SessionRecoverySnapshot(
                java.util.Objects.requireNonNull(sessionId),
                java.util.Objects.requireNonNull(spec),
                messages,
                runIds,
                parent,
                issues,
                skillRecords,
                plan,
                planArtifact);
    }

    /** 从已通过主 replay 的 canonical lines 重建 Task Board 事件投影。 */
    TaskJournalProjection replayTaskBoard(List<String> lines) {
        ArrayList<io.github.liumaishenjian.ccjava.core.task.TaskMutationEvent> events = new ArrayList<>();
        Optional<io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed> seed = Optional.empty();
        Optional<io.github.liumaishenjian.ccjava.domain.task.TaskBoardSnapshot> snapshot = Optional.empty();
        for (String line : lines) {
            ObjectNode record = decode(line);
            String type = requiredText(record, "recordType", MAX_IDENTIFIER_CHARS);
            if ("task.board.forked".equals(type)) {
                if (seed.isPresent() || !events.isEmpty()) throw invalid("INVALID_RECORD", "Task fork seed 顺序无效");
                try {
                    seed = Optional.of(taskJson.decodeSeed(record));
                    snapshot = Optional.of(seed.orElseThrow().snapshot());
                }
                catch (RuntimeException failure) { throw invalid("INVALID_RECORD", "Task fork seed 无效"); }
            } else if ("task.mutation.succeeded".equals(type)) {
                try {
                    var event = taskJson.decodeEvent(record, snapshot);
                    events.add(event);
                    snapshot = Optional.of(event.snapshot());
                }
                catch (RuntimeException failure) { throw invalid("INVALID_RECORD", "Task mutation 无效"); }
            }
        }
        return new TaskJournalProjection(seed, events);
    }

    record TaskJournalProjection(Optional<io.github.liumaishenjian.ccjava.core.task.TaskBoardSeed> seed,
            List<io.github.liumaishenjian.ccjava.core.task.TaskMutationEvent> events) {
        TaskJournalProjection { seed = Objects.requireNonNull(seed); events = List.copyOf(events); }
    }

    private PlanRecoveryProjection decodePlanProjection(
            ObjectNode record,
            Optional<PlanArtifact> planArtifact,
            Optional<PlanRecoveryProjection> previousPlan) {
        String id = requiredText(record, "planId", MAX_IDENTIFIER_CHARS);
        String objective = requiredText(record, "objective", MAX_TEXT_CHARS);
        PlanStatus status = enumValue(PlanStatus.class,
                requiredText(record, "status", MAX_IDENTIFIER_CHARS), "status");
        String digest = requiredText(record, "workspaceDigest", MAX_TEXT_CHARS);
        PlanApprovalGate gate = enumValue(PlanApprovalGate.class,
                requiredText(record, "approvalGate", MAX_IDENTIFIER_CHARS), "approvalGate");
        Integer next = nullablePositive(record, "nextStep");
        Integer active = nullablePositive(record, "activeStep");
        ArrayNode nodes = requiredArray(record, "steps");
        if (nodes.isEmpty() || nodes.size() > 128) {
            throw invalid("LIMIT_EXCEEDED", "Plan steps 数量无效");
        }
        List<PlanStep> steps = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) throw invalid("INVALID_RECORD", "Plan step 必须是 Object");
            ObjectNode item = (ObjectNode) node;
            steps.add(new PlanStep(requiredInt(item, "ordinal"), requiredText(item, "title", 200),
                    requiredText(item, "detail", MAX_TEXT_CHARS),
                    requiredText(item, "expectedDigest", MAX_TEXT_CHARS)));
        }
        try {
            PlanDocument document = new PlanDocument(id, objective, steps, status, digest);
            PlanExecutionState state = new PlanExecutionState(id, gate, next, active, status, digest);
            if (planArtifact.isPresent()
                    && (!planArtifact.orElseThrow().planId().equals(id)
                            || planArtifact.orElseThrow().status() != status)) {
                throw invalid("INVALID_RECORD", "Plan projection 与 artifact 不一致");
            }
            if (previousPlan.isPresent()) {
                PlanRecoveryProjection previous = previousPlan.orElseThrow();
                if (!previous.document().id().equals(id)
                        || !PlanLifecyclePolicy.validTransition(previous.document().status(), status)) {
                    throw invalid("INVALID_RECORD", "Plan snapshot 状态链无效");
                }
            }
            return new PlanRecoveryProjection(document, state);
        } catch (SessionOpenException invalid) {
            throw invalid;
        } catch (IllegalArgumentException invalid) {
            throw invalid("INVALID_RECORD", "Plan projection 无效");
        }
    }


    private List<UserFileAttachment> decodeAttachments(ObjectNode record) {
        JsonNode value = record.get("attachments");
        if (value == null) {
            return List.of();
        }
        if (!value.isArray() || value.size() > 8) {
            throw invalid("INVALID_RECORD", "run.started attachments 无效");
        }
        List<UserFileAttachment> attachments = new ArrayList<>();
        int totalBytes = 0;
        for (JsonNode node : value) {
            if (!node.isObject()) {
                throw invalid("INVALID_RECORD", "attachment 必须是 JSON Object");
            }
            ObjectNode item = (ObjectNode) node;
            Set<String> fields = Set.of(
                    "protocolPath", "textSnapshot", "sha256Digest", "startLine", "endLine", "truncated");
            if (item.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))
                    || item.size() != fields.size()) {
                throw invalid("INVALID_RECORD", "attachment 字段集合无效");
            }
            String text = requiredTextAllowEmpty(item, "textSnapshot", 65_536);
            totalBytes = Math.addExact(totalBytes,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (totalBytes > 196_608) {
                throw invalid("LIMIT_EXCEEDED", "attachment 总 UTF-8 字节超过限制");
            }
            try {
                attachments.add(new UserFileAttachment(
                        requiredText(item, "protocolPath", 1_024),
                        text,
                        requiredText(item, "sha256Digest", 64),
                        requiredInt(item, "startLine"),
                        requiredInt(item, "endLine"),
                        requiredBoolean(item, "truncated")));
            } catch (IllegalArgumentException failure) {
                throw invalid("INVALID_RECORD", "attachment 字段无效");
            }
        }
        return List.copyOf(attachments);
    }

    private ObjectNode encodeToolResult(ToolResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("callId", checkedIdentifier(result.callId(), "callId"));
        node.put("toolName", checkedIdentifier(result.toolName(), "toolName"));
        node.put("status", result.status().name());
        node.put("content", checkedText(result.content(), "toolResult.content"));
        result.error().ifPresent(error -> {
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("code", error.code().name());
            errorNode.put("category", error.category().name());
            errorNode.put("retryable", error.retryable());
            errorNode.put("message", checkedText(error.message(), "toolError.message"));
            JsonNode details = mapper.valueToTree(error.details().jsonValues());
            validateJsonShape(details);
            errorNode.set("details", details);
            node.set("error", errorNode);
        });
        ToolResultMetadata metadata = result.metadata();
        ObjectNode metadataNode = mapper.createObjectNode();
        metadataNode.put("truncated", metadata.truncated());
        metadataNode.put("truncationReason", metadata.truncationReason().name());
        metadataNode.put("returnedCharacters", metadata.returnedCharacters());
        metadata.knownOriginalCharacters().ifPresent(value ->
                metadataNode.put("knownOriginalCharacters", value));
        metadataNode.put("returnedItems", metadata.returnedItems());
        metadataNode.put("filteredItems", metadata.filteredItems());
        JsonNode continuation = mapper.valueToTree(metadata.continuation().jsonValues());
        validateJsonShape(continuation);
        metadataNode.set("continuation", continuation);
        node.set("metadata", metadataNode);
        return node;
    }

    private ToolResult decodeToolResult(ObjectNode node) {
        String callId = requiredText(node, "callId", MAX_IDENTIFIER_CHARS);
        String toolName = requiredText(node, "toolName", MAX_IDENTIFIER_CHARS);
        ToolResultStatus status = enumValue(
                ToolResultStatus.class,
                requiredText(node, "status", MAX_IDENTIFIER_CHARS),
                "status");
        String content = requiredTextAllowEmpty(node, "content", MAX_TEXT_CHARS);
        Optional<ToolError> error = Optional.empty();
        JsonNode errorNode = node.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            if (!errorNode.isObject()) {
                throw invalid("INVALID_RECORD", "Tool error 必须是 JSON Object");
            }
            ObjectNode object = (ObjectNode) errorNode;
            ToolErrorCode code = enumValue(
                    ToolErrorCode.class,
                    requiredText(object, "code", MAX_IDENTIFIER_CHARS),
                    "error.code");
            io.github.liumaishenjian.ccjava.domain.ToolFailureCategory category = object.has("category")
                    ? enumValue(io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.class,
                            requiredText(object, "category", MAX_IDENTIFIER_CHARS), "error.category")
                    : new ToolError(code, "compatibility", JsonObject.empty()).category();
            boolean retryable = object.has("retryable")
                    ? requiredBoolean(object, "retryable")
                    : new ToolError(code, "compatibility", JsonObject.empty()).retryable();
            String message = requiredText(object, "message", MAX_TEXT_CHARS);
            @SuppressWarnings("unchecked")
            Map<String, Object> details = mapper.convertValue(
                    requiredObject(object, "details"), Map.class);
            error = Optional.of(new ToolError(code, category, retryable, message, new JsonObject(details)));
        }
        ObjectNode metadataNode = requiredObject(node, "metadata");
        OptionalLong knownOriginal = optionalLong(metadataNode, "knownOriginalCharacters");
        @SuppressWarnings("unchecked")
        Map<String, Object> continuation = mapper.convertValue(
                requiredObject(metadataNode, "continuation"), Map.class);
        ToolResultMetadata metadata = new ToolResultMetadata(
                requiredBoolean(metadataNode, "truncated"),
                enumValue(
                        ToolResultTruncationReason.class,
                        requiredText(metadataNode, "truncationReason", MAX_IDENTIFIER_CHARS),
                        "truncationReason"),
                requiredInt(metadataNode, "returnedCharacters"),
                knownOriginal,
                requiredLong(metadataNode, "returnedItems"),
                requiredLong(metadataNode, "filteredItems"),
                new JsonObject(continuation));
        return new ToolResult(callId, toolName, status, content, error, metadata);
    }

    private void validateJsonShape(JsonNode root) {
        int visited = validateJsonShape(root, 1, 0);
        if (visited > MAX_JSON_NODES) {
            throw invalid("LIMIT_EXCEEDED", "Session record JSON 节点过多");
        }
    }

    private int validateJsonShape(JsonNode node, int depth, int visited) {
        if (depth > MAX_JSON_DEPTH || visited >= MAX_JSON_NODES) {
            throw invalid("LIMIT_EXCEEDED", "Session record JSON 结构超过限制");
        }
        int count = visited + 1;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.size() > MAX_COLLECTION_ITEMS) {
                throw invalid("LIMIT_EXCEEDED", "Session record JSON Object 条目过多");
            }
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                checkedIdentifier(entry.getKey(), "JSON field");
                count = validateJsonShape(entry.getValue(), depth + 1, count);
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            if (array.size() > MAX_COLLECTION_ITEMS) {
                throw invalid("LIMIT_EXCEEDED", "Session record JSON Array 条目过多");
            }
            for (JsonNode child : array) {
                count = validateJsonShape(child, depth + 1, count);
            }
        } else if (node.isString()) {
            checkedText(node.stringValue(), "JSON string");
        }
        return count;
    }

    private Integer nullablePositive(ObjectNode record, String field) {
        JsonNode node = record.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
            throw invalid("INVALID_RECORD", field + " 必须为正整数或 null");
        }
        return node.intValue();
    }

    private long requiredPositiveLong(ObjectNode record, String field) {
        long value = requiredLong(record, field);
        if (value < 1) throw invalid("INVALID_RECORD", field + " 必须为正整数");
        return value;
    }

    private int requiredPositiveOrdinal(ObjectNode record) {
        int ordinal = requiredInt(record, "ordinal");
        if (ordinal < 1) {
            throw invalid("INVALID_RECORD", "ordinal 必须为正整数");
        }
        return ordinal;
    }

    private <E extends Enum<E>> E requiredEnumValue(
            Class<E> type,
            JsonNode node,
            String field) {
        return enumValue(
                type,
                requiredText(node, field, MAX_IDENTIFIER_CHARS),
                field);
    }

    private String requireActiveRun(ObjectNode record, Set<String> activeRuns) {
        String runId = requiredText(record, "runId", MAX_IDENTIFIER_CHARS);
        if (!activeRuns.contains(runId)) {
            throw invalid("INVALID_RECORD", "record 没有活动 Run");
        }
        return runId;
    }

    private static String skillKey(String runId, SkillId skillId, SkillInvocationKind kind) {
        return runId + '\0' + skillId.value() + '\0' + kind.name();
    }

    private ToolCallState requireCall(
            Map<String, ToolCallState> calls,
            String callId,
            String toolName) {
        ToolCallState state = calls.get(callId);
        if (state == null || !state.call.name().equals(toolName)) {
            throw invalid("INVALID_RECORD", "Tool record 没有匹配 Call");
        }
        return state;
    }

    private ObjectNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw invalid("INVALID_RECORD", field + " 必须是 JSON Object");
        }
        return (ObjectNode) value;
    }

    private ArrayNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw invalid("INVALID_RECORD", field + " 必须是 JSON Array");
        }
        return (ArrayNode) value;
    }

    private String requiredText(JsonNode node, String field, int limit) {
        String value = requiredTextAllowEmpty(node, field, limit);
        if (value.isBlank()) {
            throw invalid("INVALID_RECORD", field + " 不能为空白");
        }
        return value;
    }

    private String requiredTextAllowEmpty(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw invalid("INVALID_RECORD", field + " 必须是字符串");
        }
        return checkedText(value.stringValue(), field, limit);
    }

    private Optional<String> optionalText(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw invalid("INVALID_RECORD", field + " 必须是字符串");
        }
        return Optional.of(checkedText(value.stringValue(), field, limit));
    }

    private String checkedPreDigest(String value) {
        if ("ABSENT".equals(value)) {
            return value;
        }
        return checkedDigest(value, "preDigest");
    }

    private String requiredDigest(JsonNode node, String field) {
        String value = requiredText(node, field, MAX_IDENTIFIER_CHARS);
        requireDigest(value, field);
        return value;
    }

    private String checkedDigest(String value, String field) {
        String checked = checkedIdentifier(value, field);
        requireDigest(checked, field);
        return checked;
    }

    private void requireDigest(String value, String field) {
        if (!SHA_256.matcher(value).matches()) {
            throw invalid("INVALID_RECORD", field + " 必须是小写 SHA-256 hex");
        }
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("INVALID_RECORD", field + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("INVALID_RECORD", field + " 必须是整数");
        }
        return value.intValue();
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid("INVALID_RECORD", field + " 必须是整数");
        }
        return value.longValue();
    }

    private OptionalLong optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return OptionalLong.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid("INVALID_RECORD", field + " 必须是整数");
        }
        return OptionalLong.of(value.longValue());
    }

    private String checkedIdentifier(String value, String field) {
        return checkedText(value, field, MAX_IDENTIFIER_CHARS);
    }

    private String checkedText(String value, String field) {
        return checkedText(value, field, MAX_TEXT_CHARS);
    }

    private String checkedText(String value, String field, int limit) {
        if (value == null || value.length() > limit || value.chars().anyMatch(ch -> ch == 0)) {
            throw invalid("LIMIT_EXCEEDED", field + " 超过限制或包含 NUL");
        }
        return value;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid("INVALID_RECORD", field + " 枚举值无效");
        }
    }

    private SessionOpenException invalid(String code, String message) {
        return new SessionOpenException(code, message);
    }

    private static final class SkillInvocationState {
        private final boolean invoked;
        private boolean completed;

        private SkillInvocationState(boolean invoked) {
            this.invoked = invoked;
        }
    }

    private final class ToolCallState {
        private final ToolCall call;
        private boolean resolved;
        private boolean started;
        private boolean completed;
        private ToolEffect effect;
        private int ordinal;

        private ToolCallState(ToolCall call) {
            this.call = call;
        }

        private void resolve(ToolResult result, int checkedOrdinal) {
            if (resolved || started || completed) {
                throw invalid("INVALID_RECORD", "Tool 状态重复或冲突");
            }
            resolved = true;
            ordinal = checkedOrdinal;
        }

        private void start(ToolEffect checkedEffect, int checkedOrdinal) {
            if (resolved || started || completed) {
                throw invalid("INVALID_RECORD", "Tool 状态重复或冲突");
            }
            started = true;
            effect = checkedEffect;
            ordinal = checkedOrdinal;
        }

        private void complete(ToolResult result, int checkedOrdinal) {
            if (!started || resolved || completed || ordinal != checkedOrdinal) {
                throw invalid("INVALID_RECORD", "tool.completed 缺少相同 ordinal 的唯一 started");
            }
            completed = true;
        }

        private OptionalLong ordinal() {
            return ordinal == 0 ? OptionalLong.empty() : OptionalLong.of(ordinal);
        }

        private Optional<SessionRecoveryIssue> issue() {
            if (resolved || completed) {
                return Optional.empty();
            }
            if (!started) {
                return Optional.of(new SessionRecoveryIssue(
                        SessionRecoveryIssueKind.TOOL_NOT_STARTED,
                        Optional.of(call.id()),
                        Optional.of(call.name()),
                        Optional.empty()));
            }
            boolean potential = effect != ToolEffect.READ_WORKSPACE
                    && effect != ToolEffect.PLAN_ARTIFACT_WRITE
                    && effect != ToolEffect.USER_INTERACTION;
            return Optional.of(new SessionRecoveryIssue(
                    potential
                            ? SessionRecoveryIssueKind.POTENTIAL_SIDE_EFFECT
                            : SessionRecoveryIssueKind.TOOL_COMPLETION_UNKNOWN,
                    Optional.of(call.id()),
                    Optional.of(call.name()),
                    Optional.of(effect)));
        }
    }

    private static final class CheckpointRecordState {
        private final String callId;
        private final int ordinal;
        private boolean completed;
        private boolean undone;

        private CheckpointRecordState(String callId, int ordinal) {
            this.callId = callId;
            this.ordinal = ordinal;
        }
    }
}
