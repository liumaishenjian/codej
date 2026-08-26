# S15 Batch 4 可选总量限制与 Tool 失败治理证据

- Date: 2026-08-21；2026-08-26 corrective
- Stage: S15 Independent Innovation（仍 `IN_PROGRESS / OPEN`）
- Feature IDs: `LOOP-07`、`LOOP-08`、`TOOL-10`、`TOOL-13`、`TOOL-18`、`PERM-05`、`OBS-04`
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Capability Levels: 无变化

## 2026-08-26 证伪与替代

本文件初版记录的 adaptive soft checkpoint、`PROGRESS_EXTENDED` 和 absolute ceiling 已被真实
Provider/approved-plan 流程证伪，不再是生产契约。修正后删除 `AgentBudgetPolicy` 及所有
adaptive/absolute reason；`AgentLimits` 用 optional presence 表达三个调用方 hard limit。

| 行为 | 证据 | 结果 |
| --- | --- | --- |
| 普通交互无隐式总次数 | `AgentRuntimeTest.interactiveCompletesAfterMoreThanOneHundredTwentyEightTurnsWithoutImplicitCountLimit` | 129 Tool / 130 Model Turns 完成，0 个 BudgetGoverned event |
| 显式 cap 不放宽 | `AgentRuntimeTest.explicitToolCapStillTerminatesExactBatch`、`explicitToolCapRejectsWholeOversizedBatchWithoutPartialProtocol` | Tool cap=1 精确终止，整批不足时不部分执行 |
| 三个总量维度 absence | `AgentLimitsTest.modelsInteractiveTotalLimitsAsAbsentWithoutSentinels` | model/tool/deadline 均 empty，无 placeholder |
| Headless route 区分 | `HeadlessRuntimeSessionTest.ordinaryInteractiveOpensRunScopeWithoutTotalDeadline`、`printKeepsConfiguredHardDeadlineWhileInteractiveDoesNot` | Interactive `openRun()` 无 budget；Print 保留准确 Duration |
| approved-plan 长 Tool 不继承总 deadline | 真实 Java stdio→Ink XLSX E2E | options timeout=100ms，1.2s Tool 与整次 Plan 仍完成；Tool timeoutSeconds=20 独立 |
| repeated-only 防死循环 | `AgentRuntimeTest` validation/repeated failure cases | 连续两批完整配对后 `TOOL_ERROR`，不依赖总回合 ceiling |
| 相同 403 不盲重复；changed query 可执行 | `WebSearchPipelineTest.repeatedIdenticalForbiddenIsRedirectedBeforeExecutionButChangedQueryIsAllowed` | Adapter 执行 2 次而非 3 次；重复调用 `REPEATED_FAILURE` |
| 403/429/5xx 与 process failure | `WebSearchToolTest`、`RunCommandToolTest` | typed non-retryable/bounded retry 与 `PROCESS_EXIT` 继续有效 |

## 历史验证与当前边界

2026-08-21 初版 clean verify 的 1,117 tests / 35 skips 与 Tool failure taxonomy 证据仍有效；其中关于
adaptive budget 的测试和结论已删除，不能继续作为当前能力声明。2026-08-26 corrective 的最终 Maven、
TUI、真实 E2E、launcher 与 Dashboard 结果记录在 `S15-task-list-2026-08-25.md` 和进度看板。

真实站点 403 原因常不可观察；WebFetch 尚未作为独立生产 Tool 接入，尚无真实多站点 403/429/5xx、
真实 Provider 策略质量、跨 Session failure cache 或 L4 A/B 证据，因此不提升相关等级，S15 继续 OPEN。
