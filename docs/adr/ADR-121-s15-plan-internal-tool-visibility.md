# ADR-121：计划内部工具的用户可见边界

Proposed；2026-10-05；S15，PLAN-01 L1、CLI-04/05/09 L2，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 计划内部编排 | `src/commands/plan/plan.tsx`、`src/tools/ExitPlanModeTool/`、`src/components/messages/GroupedToolUseContent.tsx`，AUTH-SRC-2026-07-29-A，Observed/Inferred | 计划审核由专用计划面板承载；内部工具调用不自动变成普通对话消息 | TUI 保留 Java 内部 Tool Record 供状态关联，但隐藏 `task_*`、计划工件、审核请求和验收声明的普通行；只显示计划面板、通用计划通知和最终正文 | 73/73 Runtime 应用测试；隐藏成功、失败、展开、跨 Run 和重复终态 |
| 计划审核失败 | ExitPlanMode 审核链路，Observed | 用户看到可理解的审核未满足原因，不能看到内部协议名替代结果 | `plan_gate_blocked` 映射为“计划尚未满足审核条件”；内部参数失败映射为等待模型纠正，不显示 `记录验证要求` 或 `工具参数无效` 的内部流水 | 当前 Run 审核失败、跨 Run 旧事件忽略、参数纠正回归 |
| 审核恢复 | 计划面板请求和验收恢复链路，Observed/Inferred | 当前 Run 真实审核面板出现后，才显示审核条件恢复 | `plan.review.requested` 设置通用“审核条件已补齐，计划已提交审核”通知；不改写历史失败 Tool Record，不依赖 ordinal 猜测 | 同 Run/跨 Run recovery ordinal 与计划审核测试 |

Task CRUD 仍可由 Runtime 内部使用，但不作为用户可见的任务清单或任务状态流水。该边界不改变 Tool Pipeline、权限、Task Board 或计划验收协议；它只控制 TUI 投影。
