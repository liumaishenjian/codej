package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.task.*;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** ADR-088 Task Board canonical JSONL record 的严格编解码器。 */
final class TaskJournalJson {
    private final ObjectMapper mapper;

    TaskJournalJson(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    ObjectNode encodeMutation(long sequence, TaskMutationEvent event) {
        ObjectNode root = record(sequence, "task.mutation.succeeded");
        root.put("actorId", event.actorId().value());
        root.put("actorSessionId", event.actorSessionId().value());
        root.put("actorRunId", event.actorRunId().value());
        root.set("mutation", mutation(event.mutation()));
        TaskBoardSnapshot snapshot = event.snapshot();
        root.put("boardId", snapshot.boardId().value());
        root.put("ownerSessionId", snapshot.ownerSessionId().value());
        root.put("boardRevision", snapshot.revision());
        root.put("highWaterMark", snapshot.highWaterMark());
        root.set("tombstones", ids(snapshot.tombstones()));
        event.result().task().ifPresent(value -> root.set("changedTask", task(value.item())));
        event.result().task().ifPresent(task -> root.put("resultTaskId", task.id().value()));
        return root;
    }

    ObjectNode encodeSeed(long sequence, TaskBoardSeed seed) {
        ObjectNode root = record(sequence, "task.board.forked");
        root.put("parentBoardId", seed.parentBoardId().value());
        root.set("snapshot", snapshot(seed.snapshot()));
        return root;
    }

    TaskMutationEvent decodeEvent(ObjectNode root) {
        return decodeEvent(root, Optional.empty());
    }

    TaskMutationEvent decodeEvent(ObjectNode root, Optional<TaskBoardSnapshot> previous) {
        TaskMutation command = decodeCommand(requiredObject(root, "mutation"));
        TaskBoardSnapshot snapshot = root.has("snapshot")
                ? decodeSnapshot(requiredObject(root, "snapshot"))
                : decodeDelta(root, command, previous);
        Optional<TaskItemView> task = optionalText(root, "resultTaskId")
                .map(this::parseTaskId).map(id -> snapshot.task(id).orElseThrow(() -> invalid("result task missing")));
        return new TaskMutationEvent(new TaskActorId(text(root, "actorId")),
                new SessionId(text(root, "actorSessionId")), new RunId(text(root, "actorRunId")), command,
                new TaskMutationResult(snapshot, task, Optional.empty()));
    }

    private TaskBoardSnapshot decodeDelta(ObjectNode root, TaskMutation command,
            Optional<TaskBoardSnapshot> previous) {
        TaskBoardId board = new TaskBoardId(text(root, "boardId"));
        SessionId owner = new SessionId(text(root, "ownerSessionId"));
        long revision = positive(root, "boardRevision");
        long highWater = nonNegative(root, "highWaterMark");
        TreeMap<TaskId, TaskItem> items = new TreeMap<>();
        if (previous.isPresent()) {
            TaskBoardSnapshot before = previous.orElseThrow();
            if (!before.boardId().equals(board) || !before.ownerSessionId().equals(owner)
                    || revision != before.revision() + 1) throw invalid("task delta chain invalid");
            before.tasks().forEach((id, view) -> items.put(id, view.item()));
        } else if (revision != 1) {
            throw invalid("first task delta revision invalid");
        }
        JsonNode changed = root.get("changedTask");
        if (changed != null) {
            if (!changed.isObject()) throw invalid("changed task must be object");
            TaskItem item = decodeTask((ObjectNode) changed);
            items.put(item.id(), item);
        } else if (command instanceof TaskMutation.Delete deletion) {
            if (items.remove(deletion.taskId()) == null) throw invalid("deleted task missing");
        } else {
            throw invalid("successful mutation missing changed task");
        }
        return projectedSnapshot(board, owner, revision, highWater, items,
                new TreeSet<>(decodeIds(requiredArray(root, "tombstones"))));
    }

    TaskBoardSeed decodeSeed(ObjectNode root) {
        return new TaskBoardSeed(new TaskBoardId(text(root, "parentBoardId")),
                decodeSnapshot(requiredObject(root, "snapshot")));
    }

    private ObjectNode mutation(TaskMutation value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("callId", value.callId().value());
        switch (value) {
            case TaskMutation.Create v -> {
                node.put("kind", "CREATE"); node.put("subject", v.subject()); node.put("description", v.description());
                optional(node, "activeForm", v.activeForm()); node.set("metadata", metadata(v.metadata()));
                node.set("blockedBy", ids(v.blockedBy()));
            }
            case TaskMutation.Edit v -> {
                node.put("kind", "EDIT"); target(node, v.taskId(), v.expectedTaskRevision());
                optionalLong(node, "expectedClaimEpoch", v.expectedClaimEpoch());
                optional(node, "subject", v.subject()); optional(node, "description", v.description());
                node.put("activeFormSpecified", v.activeFormSpecified()); optional(node, "activeForm", v.activeForm());
                node.set("metadataUpserts", metadata(new TaskMetadata(v.metadataPatch().upserts())));
                node.set("metadataRemovals", strings(v.metadataPatch().removals()));
            }
            case TaskMutation.Transition v -> {
                node.put("kind", "TRANSITION"); target(node, v.taskId(), v.expectedTaskRevision());
                node.put("targetStatus", v.target().name()); optionalLong(node, "expectedClaimEpoch", v.expectedClaimEpoch());
            }
            case TaskMutation.Claim v -> { node.put("kind", "CLAIM"); target(node, v.taskId(), v.expectedTaskRevision()); }
            case TaskMutation.ResumeClaim v -> { node.put("kind", "RESUME_CLAIM"); target(node, v.taskId(), v.expectedTaskRevision()); node.put("expectedClaimEpoch", v.expectedClaimEpoch()); }
            case TaskMutation.Release v -> { node.put("kind", "RELEASE"); target(node, v.taskId(), v.expectedTaskRevision()); node.put("expectedClaimEpoch", v.expectedClaimEpoch()); }
            case TaskMutation.Assign v -> { node.put("kind", "ASSIGN"); target(node, v.taskId(), v.expectedTaskRevision()); node.put("targetActor", v.targetActor().value()); }
            case TaskMutation.Reassign v -> { node.put("kind", "REASSIGN"); target(node, v.taskId(), v.expectedTaskRevision()); node.put("targetActor", v.targetActor().value()); optionalLong(node, "expectedClaimEpoch", v.expectedClaimEpoch()); }
            case TaskMutation.Dependency v -> {
                node.put("kind", "DEPENDENCY"); target(node, v.taskId(), v.expectedTaskRevision());
                node.put("expectedBoardRevision", v.expectedBoardRevision());
                node.set("addBlockedBy", ids(v.addBlockedBy())); node.set("removeBlockedBy", ids(v.removeBlockedBy()));
            }
            case TaskMutation.Delete v -> { node.put("kind", "DELETE"); target(node, v.taskId(), v.expectedTaskRevision()); node.put("expectedBoardRevision", v.expectedBoardRevision()); }
        }
        return node;
    }

    private TaskMutation decodeCommand(ObjectNode node) {
        TaskCallId call = new TaskCallId(text(node, "callId"));
        String kind = text(node, "kind");
        return switch (kind) {
            case "CREATE" -> new TaskMutation.Create(call, text(node, "subject"), textAllowEmpty(node, "description"),
                    optionalText(node, "activeForm"), decodeMetadata(requiredObject(node, "metadata")),
                    decodeIds(requiredArray(node, "blockedBy")));
            case "EDIT" -> new TaskMutation.Edit(call, task(node), positive(node, "expectedTaskRevision"),
                    optionalLong(node, "expectedClaimEpoch"), optionalText(node, "subject"),
                    optionalText(node, "description"), bool(node, "activeFormSpecified"),
                    optionalText(node, "activeForm"), new TaskMetadataPatch(
                            decodeMetadata(requiredObject(node, "metadataUpserts")).values(),
                            new TreeSet<>(decodeStrings(requiredArray(node, "metadataRemovals")))));
            case "TRANSITION" -> new TaskMutation.Transition(call, task(node), positive(node, "expectedTaskRevision"),
                    TaskStatus.valueOf(text(node, "targetStatus")), optionalLong(node, "expectedClaimEpoch"));
            case "CLAIM" -> new TaskMutation.Claim(call, task(node), positive(node, "expectedTaskRevision"));
            case "RESUME_CLAIM" -> new TaskMutation.ResumeClaim(call, task(node), positive(node, "expectedTaskRevision"), positive(node, "expectedClaimEpoch"));
            case "RELEASE" -> new TaskMutation.Release(call, task(node), positive(node, "expectedTaskRevision"), positive(node, "expectedClaimEpoch"));
            case "ASSIGN" -> new TaskMutation.Assign(call, task(node), positive(node, "expectedTaskRevision"), new TaskActorId(text(node, "targetActor")));
            case "REASSIGN" -> new TaskMutation.Reassign(call, task(node), positive(node, "expectedTaskRevision"), new TaskActorId(text(node, "targetActor")), optionalLong(node, "expectedClaimEpoch"));
            case "DEPENDENCY" -> new TaskMutation.Dependency(call, task(node), positive(node, "expectedTaskRevision"), nonNegative(node, "expectedBoardRevision"), decodeIds(requiredArray(node, "addBlockedBy")), decodeIds(requiredArray(node, "removeBlockedBy")));
            case "DELETE" -> new TaskMutation.Delete(call, task(node), positive(node, "expectedTaskRevision"), nonNegative(node, "expectedBoardRevision"));
            default -> throw invalid("unknown task mutation");
        };
    }

    private ObjectNode snapshot(TaskBoardSnapshot value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("boardId", value.boardId().value()); node.put("ownerSessionId", value.ownerSessionId().value());
        node.put("revision", value.revision()); node.put("highWaterMark", value.highWaterMark());
        ArrayNode tasks = mapper.createArrayNode();
        value.tasks().values().forEach(view -> tasks.add(task(view.item())));
        node.set("tasks", tasks); node.set("tombstones", ids(value.tombstones()));
        return node;
    }

    private TaskBoardSnapshot decodeSnapshot(ObjectNode node) {
        TaskBoardId board = new TaskBoardId(text(node, "boardId"));
        SessionId owner = new SessionId(text(node, "ownerSessionId"));
        long revision = nonNegative(node, "revision"); long highWater = nonNegative(node, "highWaterMark");
        TreeMap<TaskId, TaskItem> items = new TreeMap<>();
        for (JsonNode raw : requiredArray(node, "tasks")) {
            if (!raw.isObject()) throw invalid("task must be object");
            TaskItem item = decodeTask((ObjectNode) raw);
            if (items.putIfAbsent(item.id(), item) != null) throw invalid("duplicate task");
        }
        return projectedSnapshot(board, owner, revision, highWater, items,
                new TreeSet<>(decodeIds(requiredArray(node, "tombstones"))));
    }

    private TaskBoardSnapshot projectedSnapshot(TaskBoardId board, SessionId owner, long revision,
            long highWater, TreeMap<TaskId, TaskItem> items, TreeSet<TaskId> tombstones) {
        TreeMap<TaskId, Set<TaskId>> blocks = new TreeMap<>();
        items.keySet().forEach(id -> blocks.put(id, new TreeSet<>()));
        items.values().forEach(item -> item.blockedBy().forEach(id -> {
            Set<TaskId> targets = blocks.get(id); if (targets == null) throw invalid("missing blocker"); targets.add(item.id());
        }));
        LinkedHashMap<TaskId, TaskItemView> views = new LinkedHashMap<>();
        items.forEach((id, item) -> {
            TreeSet<TaskId> active = new TreeSet<>();
            item.blockedBy().forEach(blocker -> { if (items.get(blocker).status() != TaskStatus.COMPLETED) active.add(blocker); });
            views.put(id, new TaskItemView(item, blocks.get(id), !active.isEmpty(), active, false));
        });
        return new TaskBoardSnapshot(board, owner, revision, highWater, views, tombstones);
    }

    private ObjectNode task(TaskItem value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", value.id().value()); node.put("revision", value.revision()); node.put("status", value.status().name());
        node.put("subject", value.subject()); node.put("description", value.description()); optional(node, "activeForm", value.activeForm());
        node.set("metadata", metadata(value.metadata())); node.set("blockedBy", ids(value.blockedBy()));
        value.owner().ifPresent(owner -> node.put("owner", owner.value()));
        value.claim().ifPresent(claim -> { ObjectNode c = mapper.createObjectNode(); c.put("actorId", claim.actorId().value()); c.put("runId", claim.runId().value()); c.put("epoch", claim.epoch()); c.put("claimedAt", claim.claimedAt().toString()); node.set("claim", c); });
        node.put("lastClaimEpoch", value.lastClaimEpoch()); node.put("createdAt", value.createdAt().toString()); node.put("updatedAt", value.updatedAt().toString());
        return node;
    }

    private TaskItem decodeTask(ObjectNode node) {
        Optional<TaskClaim> claim = Optional.empty();
        if (node.has("claim")) {
            ObjectNode c = requiredObject(node, "claim");
            claim = Optional.of(new TaskClaim(new TaskActorId(text(c, "actorId")), new RunId(text(c, "runId")),
                    positive(c, "epoch"), Instant.parse(text(c, "claimedAt"))));
        }
        return new TaskItem(parseTaskId(text(node, "id")), positive(node, "revision"),
                TaskStatus.valueOf(text(node, "status")), text(node, "subject"), textAllowEmpty(node, "description"),
                optionalText(node, "activeForm"), decodeMetadata(requiredObject(node, "metadata")),
                new TreeSet<>(decodeIds(requiredArray(node, "blockedBy"))), optionalText(node, "owner").map(TaskActorId::new),
                claim, nonNegative(node, "lastClaimEpoch"), Instant.parse(text(node, "createdAt")), Instant.parse(text(node, "updatedAt")));
    }

    private ObjectNode metadata(TaskMetadata value) {
        ObjectNode node = mapper.createObjectNode();
        value.values().forEach((key, raw) -> { switch (raw) {
            case TaskMetadataValue.BooleanValue v -> node.put(key, v.value());
            case TaskMetadataValue.IntegerValue v -> node.put(key, v.value());
            case TaskMetadataValue.StringValue v -> node.put(key, v.value());
        }});
        return node;
    }

    private TaskMetadata decodeMetadata(ObjectNode node) {
        TreeMap<String, TaskMetadataValue> values = new TreeMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            TaskMetadataValue decoded;
            if (value.isBoolean()) decoded = new TaskMetadataValue.BooleanValue(value.booleanValue());
            else if (value.isIntegralNumber() && value.canConvertToLong()) decoded = new TaskMetadataValue.IntegerValue(value.longValue());
            else if (value.isString()) decoded = new TaskMetadataValue.StringValue(value.stringValue());
            else throw invalid("invalid task metadata");
            values.put(entry.getKey(), decoded);
        });
        return new TaskMetadata(values);
    }

    private ObjectNode record(long sequence, String type) { ObjectNode n = mapper.createObjectNode(); n.put("schemaMajor", 1); n.put("sequence", sequence); n.put("recordType", type); return n; }
    private void target(ObjectNode n, TaskId id, long revision) { n.put("taskId", id.value()); n.put("expectedTaskRevision", revision); }
    private TaskId task(ObjectNode n) { return parseTaskId(text(n, "taskId")); }
    private TaskId parseTaskId(String value) {
        if (value == null || !value.matches("task-[1-9][0-9]*")) throw invalid("task id invalid");
        try { return new TaskId(Long.parseLong(value.substring("task-".length()))); }
        catch (RuntimeException failure) { throw invalid("task id invalid"); }
    }
    private void optional(ObjectNode n, String key, Optional<String> value) { value.ifPresent(v -> n.put(key, v)); }
    private void optionalLong(ObjectNode n, String key, java.util.OptionalLong value) { if (value.isPresent()) n.put(key, value.getAsLong()); }
    private ArrayNode ids(Collection<TaskId> values) { ArrayNode a = mapper.createArrayNode(); values.stream().sorted().forEach(v -> a.add(v.value())); return a; }
    private ArrayNode strings(Collection<String> values) { ArrayNode a = mapper.createArrayNode(); values.stream().sorted().forEach(a::add); return a; }
    private List<TaskId> decodeIds(ArrayNode a) { return decodeStrings(a).stream().map(this::parseTaskId).toList(); }
    private List<String> decodeStrings(ArrayNode a) { ArrayList<String> out = new ArrayList<>(); for (JsonNode n : a) { if (!n.isString()) throw invalid("string array required"); out.add(n.stringValue()); } return List.copyOf(out); }
    private String text(JsonNode n, String key) { String v = textAllowEmpty(n, key); if (v.isBlank()) throw invalid(key + " blank"); return v; }
    private String textAllowEmpty(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isString()) throw invalid(key + " string required"); return v.stringValue(); }
    private Optional<String> optionalText(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null) return Optional.empty(); if (!v.isString()) throw invalid(key + " string required"); return Optional.of(v.stringValue()); }
    private long positive(JsonNode n, String key) { long v = nonNegative(n, key); if (v < 1) throw invalid(key + " positive required"); return v; }
    private long nonNegative(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0) throw invalid(key + " nonnegative required"); return v.longValue(); }
    private java.util.OptionalLong optionalLong(JsonNode n, String key) { JsonNode v = n.get(key); return v == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(positive(n, key)); }
    private boolean bool(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isBoolean()) throw invalid(key + " boolean required"); return v.booleanValue(); }
    private ObjectNode requiredObject(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isObject()) throw invalid(key + " object required"); return (ObjectNode) v; }
    private ArrayNode requiredArray(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isArray()) throw invalid(key + " array required"); return (ArrayNode) v; }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Task journal 无效: " + message); }
}
