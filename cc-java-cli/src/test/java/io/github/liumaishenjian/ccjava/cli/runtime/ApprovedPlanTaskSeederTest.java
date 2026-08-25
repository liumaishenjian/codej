package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.task.TaskBoardCapabilityFactory;
import io.github.liumaishenjian.ccjava.core.task.TaskListService;
import io.github.liumaishenjian.ccjava.core.task.TaskMutation;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.task.TaskBoardId;
import io.github.liumaishenjian.ccjava.domain.task.TaskCallId;
import io.github.liumaishenjian.ccjava.domain.task.TaskId;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadata;
import io.github.liumaishenjian.ccjava.domain.task.TaskMetadataValue;
import io.github.liumaishenjian.ccjava.domain.task.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 验证批准 Plan 的中文步骤冻结、顺序保真与幂等恢复。 */
class ApprovedPlanTaskSeederTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void freezesTopLevelChineseStepsInOriginalOrderWithoutParsingNestedCriteria() {
        SessionId sessionId = new SessionId("session-plan-seed");
        TaskListService board = new TaskListService(new TaskBoardId("board-plan-seed"), sessionId,
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> false);
        String markdown = """
                # 河南天气工作簿方案

                ## 城市范围
                1. 郑州
                2. 开封
                3. 洛阳
                4. 平顶山
                5. 安阳
                6. 鹤壁
                7. 新乡
                8. 焦作
                9. 濮阳
                10. 许昌
                11. 漯河
                12. 三门峡
                13. 南阳
                14. 商丘
                15. 信阳
                16. 周口
                17. 驻马店
                18. 济源

                ```text
                1. 代码示例中的编号不能成为 Task
                ```

                ## 拟定步骤
                1. 读取河南各市天气数据。
                   保留来源日期与城市顺序。
                   1. 这是验收细则，不是独立执行步骤。
                2. 生成七天预报工作簿。
                3. 验证工作簿内容与文件名。
                4. 汇总验证结果并交付。

                ### 验收细则
                1. 该子 section 的编号也不能成为 Task。

                ## 风险
                1. 上游数据暂时不可用。
                """;
        PlanArtifact artifact = PlanArtifact.create("plan-authoritative-seed", sessionId, markdown,
                PlanStatus.AWAITING_APPROVAL, NOW);

        assertThat(ApprovedPlanTaskSeeder.seed(board, sessionId, new RunId("run-plan-seed"), artifact))
                .isEqualTo(4);
        assertThat(board.snapshot().tasks().values())
                .extracting(task -> task.subject())
                .containsExactly("读取河南各市天气数据。", "生成七天预报工作簿。", "验证工作簿内容与文件名。",
                        "汇总验证结果并交付。");
        assertThat(board.snapshot().tasks().values()).first().satisfies(task -> {
            assertThat(task.description()).contains("保留来源日期与城市顺序。")
                    .contains("1. 这是验收细则，不是独立执行步骤。");
            assertThat(task.metadata().values().get("plan.source"))
                    .isEqualTo(new TaskMetadataValue.StringValue("approved-plan"));
            assertThat(task.metadata().values().get("plan.step"))
                    .isEqualTo(new TaskMetadataValue.IntegerValue(1));
        });

        assertThat(ApprovedPlanTaskSeeder.seed(board, sessionId, new RunId("run-plan-seed-resume"), artifact))
                .isEqualTo(4);
        assertThat(board.snapshot().revision()).isEqualTo(4);
        assertThat(board.snapshot().tasks()).hasSize(4);
    }

    @Test
    void acceptsControlledEnglishSectionAndIgnoresNumberedCodeFence() {
        assertThat(ApprovedPlanTaskSeeder.parse("""
                # Plan
                1. unrelated list

                ## Execution Steps:
                ```text
                1. ignored example
                ```
                1. Preserve the approved language.
                2. Verify the result.
                """))
                .extracting(ApprovedPlanTaskSeeder.PlanStep::subject)
                .containsExactly("Preserve the approved language.", "Verify the result.");
    }

    @Test
    void multipleExplicitStepSectionsFailClosed() {
        assertThatThrownBy(() -> ApprovedPlanTaskSeeder.parse("""
                ## 拟定步骤
                1. 第一步。

                ## Execution Steps
                1. Another step.
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只能包含一个");
    }

    @Test
    void legacyFreeFormPlanDoesNotInventTasks() {
        SessionId sessionId = new SessionId("session-plan-free-form");
        TaskListService board = new TaskListService(new TaskBoardId("board-plan-free-form"), sessionId,
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> false);
        PlanArtifact artifact = PlanArtifact.create("plan-free-form", sessionId,
                "# Plan\n\nInspect the repository without changing files.\n",
                PlanStatus.AWAITING_APPROVAL, NOW);

        assertThat(ApprovedPlanTaskSeeder.seed(board, sessionId, new RunId("run-plan-free-form"), artifact))
                .isZero();
        assertThat(board.snapshot().tasks()).isEmpty();
    }

    @Test
    void unrelatedCompletedTaskCannotSatisfyApprovedPlanCompletion() {
        SessionId sessionId = new SessionId("session-unrelated-completion");
        RunId runId = new RunId("run-unrelated-completion");
        TaskListService board = new TaskListService(new TaskBoardId("board-unrelated-completion"), sessionId,
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> true);
        var capability = TaskBoardCapabilityFactory.root(board.snapshot().boardId(), sessionId, runId);
        board.execute(capability, new TaskMutation.Create(new TaskCallId("unrelated-create"), "无关任务", "",
                Optional.empty(), new TaskMetadata(Map.of()), List.of()));
        board.execute(capability, new TaskMutation.Claim(new TaskCallId("unrelated-claim"), new TaskId(1), 1));
        board.execute(capability, new TaskMutation.Transition(new TaskCallId("unrelated-complete"), new TaskId(1),
                2, TaskStatus.COMPLETED, OptionalLong.of(1)));
        PlanArtifact artifact = PlanArtifact.create("plan-unrelated", sessionId,
                "# Plan\n\n## 拟定步骤\n1. 完成批准任务。\n", PlanStatus.AWAITING_APPROVAL, NOW);

        assertThat(ApprovedPlanTaskSeeder.completionReady(board, artifact)).isFalse();
    }

    @Test
    void partialAuthoritativeSeedCannotSatisfyCompletion() {
        SessionId sessionId = new SessionId("session-partial-completion");
        RunId runId = new RunId("run-partial-completion");
        TaskListService board = new TaskListService(new TaskBoardId("board-partial-completion"), sessionId,
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> true);
        PlanArtifact artifact = PlanArtifact.create("plan-partial", sessionId,
                "# Plan\n\n## 拟定步骤\n1. 第一步。\n2. 第二步。\n", PlanStatus.AWAITING_APPROVAL, NOW);
        ApprovedPlanTaskSeeder.seed(board, sessionId, runId, artifact);
        var capability = TaskBoardCapabilityFactory.root(board.snapshot().boardId(), sessionId, runId);
        board.execute(capability, new TaskMutation.Delete(new TaskCallId("remove-second"), new TaskId(2), 1,
                board.snapshot().revision()));

        assertThat(board.snapshot().tasks()).hasSize(1);
        assertThat(ApprovedPlanTaskSeeder.completionReady(board, artifact)).isFalse();
    }

    @Test
    void recoveryRequiredAuthoritativeTaskCannotSatisfyCompletion() {
        SessionId sessionId = new SessionId("session-recovery-completion");
        RunId runId = new RunId("run-recovery-completion");
        TaskListService board = new TaskListService(new TaskBoardId("board-recovery-completion"), sessionId,
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> true);
        PlanArtifact artifact = PlanArtifact.create("plan-recovery", sessionId,
                "# Plan\n\n## 拟定步骤\n1. 需要恢复的任务。\n", PlanStatus.AWAITING_APPROVAL, NOW);
        ApprovedPlanTaskSeeder.seed(board, sessionId, runId, artifact);
        var capability = TaskBoardCapabilityFactory.root(board.snapshot().boardId(), sessionId, runId);
        board.execute(capability, new TaskMutation.Claim(new TaskCallId("claim-recovery"), new TaskId(1), 1));

        assertThat(board.snapshot().tasks().get(new TaskId(1)).recoveryRequired()).isTrue();
        assertThat(ApprovedPlanTaskSeeder.completionReady(board, artifact)).isFalse();
    }
}
