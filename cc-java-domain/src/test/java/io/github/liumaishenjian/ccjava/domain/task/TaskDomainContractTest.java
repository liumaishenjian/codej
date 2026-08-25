package io.github.liumaishenjian.ccjava.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ADR-088 Task Domain 的 Unicode、UTF-8、metadata、claim 与 capability 边界回归。 */
class TaskDomainContractTest {

    @Test
    void textLimitsCountCodePointsAndUtf8Bytes() {
        assertThat(item("😀".repeat(200), "汉".repeat(1_365), Optional.of("进".repeat(200))))
                .isNotNull();
        assertThat(item("ok", "x".repeat(4_096), Optional.empty())).isNotNull();
        assertThatThrownBy(() -> item("😀".repeat(201), "", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item("ok", "汉".repeat(1_366), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item("ok", "x".repeat(4_097), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item("ok", "", Optional.of("😀".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item("\uD800", "", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataAcceptsClosedScalarsAndCanonicalBudget() {
        Map<String, TaskMetadataValue> sixteen = new LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            sixteen.put("key." + index, new TaskMetadataValue.StringValue("值".repeat(20)));
        }
        TaskMetadata metadata = new TaskMetadata(sixteen);
        assertThat(metadata.values()).hasSize(16);
        assertThat(new TaskMetadataValue.IntegerValue(9_007_199_254_740_991L).canonicalJsonBytes())
                .isPositive();
        assertThatThrownBy(() -> new TaskMetadataValue.IntegerValue(9_007_199_254_740_992L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskMetadataValue.StringValue("x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskMetadataValue.StringValue("bad\nvalue"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataRejectsKeyCountKeyShapeAndTotalBytes() {
        Map<String, TaskMetadataValue> seventeen = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) seventeen.put("k" + index, new TaskMetadataValue.BooleanValue(true));
        assertThatThrownBy(() -> new TaskMetadata(seventeen)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskMetadata(Map.of("Upper", new TaskMetadataValue.BooleanValue(true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TaskMetadata(Map.of("a".repeat(64), new TaskMetadataValue.BooleanValue(true))).values())
                .hasSize(1);
        assertThatThrownBy(() -> new TaskMetadata(Map.of("a".repeat(65), new TaskMetadataValue.BooleanValue(true))))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, TaskMetadataValue> tooLarge = new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) {
            tooLarge.put("large" + index, new TaskMetadataValue.StringValue("汉".repeat(512)));
        }
        assertThatThrownBy(() -> new TaskMetadata(tooLarge)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataPatchUsesSeparateRemovalWithoutNull() {
        TaskMetadata original = new TaskMetadata(Map.of(
                "keep", new TaskMetadataValue.BooleanValue(true),
                "remove", new TaskMetadataValue.StringValue("old")));
        TaskMetadataPatch patch = new TaskMetadataPatch(
                Map.of("keep", new TaskMetadataValue.IntegerValue(2)), Set.of("remove"));
        assertThat(original.apply(patch).values()).containsOnlyKeys("keep");
        assertThatThrownBy(() -> new TaskMetadataPatch(
                Map.of("same", new TaskMetadataValue.BooleanValue(true)), Set.of("same")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataCanonicalBudgetAcceptsExactLimitAndRejectsNextByte() {
        TaskMetadata exact = new TaskMetadata(Map.of(
                "a", new TaskMetadataValue.StringValue("😀".repeat(512)),
                "b", new TaskMetadataValue.StringValue("😀".repeat(508) + "x")));
        assertThat(exact.canonicalJsonBytes()).isEqualTo(4_096);
        assertThatThrownBy(() -> new TaskMetadata(Map.of(
                "a", new TaskMetadataValue.StringValue("😀".repeat(512)),
                "b", new TaskMetadataValue.StringValue("😀".repeat(508) + "xx"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itemEnforcesClaimStatusOwnerEpochAndDependencyLimit() {
        TaskActorId actor = new TaskActorId("root:session-1");
        TaskClaim claim = new TaskClaim(actor, new RunId("run-1"), 1, Instant.EPOCH);
        assertThatThrownBy(() -> new TaskItem(new TaskId(1), 1, TaskStatus.PENDING, "s", "",
                Optional.empty(), TaskMetadata.EMPTY, Set.of(), Optional.of(actor), Optional.of(claim), 1,
                Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskItem(new TaskId(1), 1, TaskStatus.IN_PROGRESS, "s", "",
                Optional.empty(), TaskMetadata.EMPTY, Set.of(), Optional.of(actor), Optional.empty(), 1,
                Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskItem(new TaskId(1), 1, TaskStatus.IN_PROGRESS, "s", "",
                Optional.empty(), TaskMetadata.EMPTY, Set.of(), Optional.of(actor), Optional.of(claim), 2,
                Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        Set<TaskId> tooMany = java.util.stream.LongStream.rangeClosed(2, 34)
                .mapToObj(TaskId::new).collect(java.util.stream.Collectors.toSet());
        assertThatThrownBy(() -> new TaskItem(new TaskId(1), 1, TaskStatus.PENDING, "s", "",
                Optional.empty(), TaskMetadata.EMPTY, tooMany, Optional.empty(), Optional.empty(), 0,
                Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorCallAndCapabilityRejectMalformedOrEscalatingIdentity() {
        assertThatThrownBy(() -> new TaskActorId("bad\uD800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskCallId("bad\uDC00")).isInstanceOf(IllegalArgumentException.class);
        TaskBoardId boardId = new TaskBoardId("board-contract");
        SessionId owner = new SessionId("session-owner");
        assertThatThrownBy(() -> new TaskBoardCapability(boardId, owner, new TaskActorId("child"), owner, new RunId("run-child"),
                false, Set.of(ToolEffect.WRITE_SESSION_STATE), Set.of(new TaskId(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskBoardCapability(boardId, owner, new TaskActorId("root"), owner, new RunId("run-root"),
                true, Set.of(ToolEffect.WRITE_WORKSPACE), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taskTextRejectsTerminalControlsButDescriptionAllowsLfAndTab() {
        assertThat(item("subject", "line-1\n\tline-2", Optional.of("working"))).isNotNull();
        for (int codePoint : new int[]{0x0D, 0x1B, 0x00, 0x85}) {
            String control = Character.toString(codePoint);
            assertThatThrownBy(() -> item("bad" + control, "", Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item("ok", "bad" + control, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item("ok", "", Optional.of("bad" + control)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> item("bad\nsubject", "", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item("ok", "", Optional.of("bad\tactive")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externallySerializedCollectionsUseDeterministicOrder() {
        LinkedHashMap<String, TaskMetadataValue> unsortedMetadata = new LinkedHashMap<>();
        unsortedMetadata.put("z", new TaskMetadataValue.BooleanValue(true));
        unsortedMetadata.put("a", new TaskMetadataValue.IntegerValue(1));
        TaskMetadata metadata = new TaskMetadata(unsortedMetadata);
        assertThat(metadata.values().keySet()).containsExactly("a", "z");

        LinkedHashSet<TaskId> unsortedIds = new LinkedHashSet<>();
        unsortedIds.add(new TaskId(3));
        unsortedIds.add(new TaskId(1));
        unsortedIds.add(new TaskId(2));
        TaskItem orderedItem = new TaskItem(new TaskId(9), 1, TaskStatus.PENDING, "ordered", "",
                Optional.empty(), metadata, unsortedIds, Optional.empty(), Optional.empty(), 0,
                Instant.EPOCH, Instant.EPOCH);
        TaskItemView view = new TaskItemView(orderedItem, unsortedIds, true, unsortedIds, false);
        TaskItem secondItem = new TaskItem(new TaskId(8), 1, TaskStatus.PENDING, "second", "",
                Optional.empty(), metadata, Set.of(), Optional.empty(), Optional.empty(), 0,
                Instant.EPOCH, Instant.EPOCH);
        TaskItemView secondView = new TaskItemView(secondItem, Set.of(), false, Set.of(), false);
        TaskDiagnostic diagnostic = new TaskDiagnostic(TaskDiagnosticCode.TASK_BLOCKED,
                Optional.of(orderedItem.id()), 1, Optional.of(1L), unsortedIds);
        LinkedHashMap<TaskId, TaskItemView> unsortedTasks = new LinkedHashMap<>();
        unsortedTasks.put(new TaskId(9), view);
        unsortedTasks.put(new TaskId(8), secondView);
        TaskBoardSnapshot snapshot = new TaskBoardSnapshot(new TaskBoardId("board-ordered"),
                new SessionId("ordered-session"), 1, 9, unsortedTasks, unsortedIds);
        TaskMetadataPatch patch = new TaskMetadataPatch(unsortedMetadata,
                new LinkedHashSet<>(java.util.List.of("z-remove", "a-remove")));
        TaskBoardCapability capability = new TaskBoardCapability(new TaskBoardId("board-capability-order"),
                new SessionId("owner-order"), new TaskActorId("child-order"),
                new SessionId("child-order-session"), new RunId("child-order-run"), false,
                new LinkedHashSet<>(java.util.List.of(ToolEffect.WRITE_SESSION_STATE, ToolEffect.READ_SESSION_STATE)),
                unsortedIds);

        assertThat(orderedItem.blockedBy()).containsExactly(new TaskId(1), new TaskId(2), new TaskId(3));
        assertThat(view.blocks()).containsExactly(new TaskId(1), new TaskId(2), new TaskId(3));
        assertThat(view.activeBlockers()).containsExactly(new TaskId(1), new TaskId(2), new TaskId(3));
        assertThat(diagnostic.relatedTaskIds()).containsExactly(new TaskId(1), new TaskId(2), new TaskId(3));
        assertThat(snapshot.tombstones()).containsExactly(new TaskId(1), new TaskId(2), new TaskId(3));
        assertThat(snapshot.tasks().keySet()).containsExactly(new TaskId(8), new TaskId(9));
        assertThat(patch.upserts().keySet()).containsExactly("a", "z");
        assertThat(patch.removals()).containsExactly("a-remove", "z-remove");
        assertThat(capability.effects()).containsExactly(ToolEffect.READ_SESSION_STATE, ToolEffect.WRITE_SESSION_STATE);
        assertThat(capability.taskScope()).containsExactly(new TaskId(1), new TaskId(2), new TaskId(3));
    }

    private static TaskItem item(String subject, String description, Optional<String> activeForm) {
        return new TaskItem(new TaskId(1), 1, TaskStatus.PENDING, subject, description, activeForm,
                TaskMetadata.EMPTY, Set.of(), Optional.empty(), Optional.empty(), 0,
                Instant.EPOCH, Instant.EPOCH);
    }
}
