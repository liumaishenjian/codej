# ADR-122：`/plan` 确认到真实交付的跨进程闭环

Proposed；2026-10-05；S15，PLAN-01 L1、CLI-04/05/09 L2，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 计划确认并执行 | `src/commands/plan/plan.tsx`、`src/tools/ExitPlanModeTool/`、`src/tools/ExitPlanModeTool/UI.tsx`，AUTH-SRC-2026-07-29-A，Observed/Inferred | 计划先作为审核内容呈现；用户决定后才进入执行；执行结果回到对话终态 | `plan.review.resolve` 是唯一确认入口，`APPROVE_USER` 先产生 `plan.execution.accepted`，随后才启动执行 Run；TUI 的计划面板不直接调用工具，也不把审批成功当作交付 | `real-java-plan-e2e.test.ts` 自然语言计划、修改意见、确认、两次人工审批、真实文件修正共 6/6 通过 |
| 验收与最终正文 | `src/tools/ExitPlanModeTool/`、消息终态渲染链路，Observed/Inferred | 只有计划完成并满足交付条件后才结束；失败或缺少依据时保留解释 | Java 在 durable PlanArtifact 进入 `COMPLETED` 后发布 `plan.verification.completed`；TUI 只在 `run.completed` 的 `finalText` 存在时追加最终正文，`verification.required` 明确提示“未完成交付”，不以 Task 状态或 Tool 成功替代正文 | 同一跨进程测试验证 `verification.completed` 早于 `run.completed`、最终正文和产物可读、下一轮普通输入可继续 |
| 计划校正的必要诊断 | 计划审核失败/纠正链路，Observed/Inferred | 内部工具行不直接刷屏，但用户仍能知道验证方式被拒绝以及已纠正 | `verification_tool_unavailable` 与对应恢复只暂存为本轮通用摘要，在终态显示“验证方式使用了当前不可用的工具”“已修正验证方式，继续规划”；不显示工具名、ordinal、参数或内部协议码 | 真实 Java→stdio→Ink `experience-java-e2e.test.tsx` 7/7；Runtime 应用 73/73 |
| 参数错误与拒绝恢复 | ExitPlanMode 审核/纠正链路，Observed/Inferred | 计划修改回到当前计划上下文；拒绝不启动执行 | 内部工具行由 ADR-121 隐藏；参数错误只显示通用通知，`CONTINUE_PLANNING` 保留同一 planId 和上下文，`REJECT` 结束当前计划且不生成副作用 | real-java-plan-e2e 计划反馈、拒绝、新计划 identity、verification resume 场景通过 |

普通沙箱运行跨进程 Fixture 时，Windows `%TEMP%` 的 `toRealPath()` 被环境拒绝，导致 Fixture 以 exit=2 静默退出；提升到仓库测试允许的本机进程权限后 6/6 全部通过。这是测试执行环境限制，不是产品启动协议证据；后续报告必须分开记录。

本 ADR 只固定用户可观察的状态边界，不复制参考源码表达，也不新增 `/plan-*`、恢复或步骤管理命令。
