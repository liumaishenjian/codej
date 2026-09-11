# ADR-095：验证声明失败原因与恢复呈现由宿主权威关联

- 状态：Proposed
- 日期：2026-09-10
- Stage：S15；Stage Exit 保持 OPEN
- Feature IDs：CLI-01/03/04/05/09 保持 L2，TOOL-11/PLAN-01 保持 L1
- 公开行为基线：R2026.03（本批未取得可重放黑盒行为）
- 授权参考：AUTH-SRC-2026-07-29-A，Observed（源码职责）/ Inferred（独立契约）/ Unknown（准确版本与稳定公开协议）
- 关联决策：[ADR-094](./ADR-094-s15-plan-completion-evidence.md)

## 1. 问题与边界

真实天气规划中，模型把 `VERIFICATION` locator 声明为当前 Runtime 未注册的工具，确定性校验正确拒绝；随后模型保持同一 requirement 身份并改用真实可用工具后成功。旧 stdio/TUI 只显示通用参数错误，既不能解释失败，也不能证明哪一次后续成功修正了该失败。

本批只改进验证工具范围声明和失败/恢复事实投影，不新增 Tool、命令、面板、菜单、Agent Loop 或自动重放路径，不放宽完成 Gate、Permission、重复失败治理或 Evidence 校验。历史 `tool.failed`、ToolResult 和审计记录保持失败；Surface 的“已修正”只表示后续声明成功，不把旧结果改写为成功。

## 2. R3 受控研究与可证伪对照

按 AGENTS.md 5.1/5.3 对授权目录 `G:\AI Cloud\claude-code-main` 只读窄读以下入口：

- `src/components/messages/AssistantToolUseMessage.tsx` 的调用渲染选择；
- `src/tools/TaskListTool/TaskListTool.ts` 与 `src/tools/TaskUpdateTool/TaskUpdateTool.ts` 的普通调用渲染；
- `src/components/messages/UserToolResultMessage/UserToolErrorMessage.tsx` 的错误结果分派。

只提炼职责、状态与隐藏条件，不复制函数体、Prompt、错误文案、私有名称、布局、常量、Fixture 或源码字节。

| 用户场景 / Feature ID | 参考路径与符号 / 基线 / 证据分类 | 参考可观察行为（含不显示什么） | 本项目采用机制 / 必要偏差 | 可证伪场景与实际证据 / 未通过项 |
| --- | --- | --- | --- | --- |
| 正常内部调用 / CLI-03、CLI-04 | `AssistantToolUseMessage` 调用渲染；Task List/Update Tool 调用渲染；AUTH-SRC-2026-07-29-A；Observed（源码职责） | Tool 的普通调用渲染可返回空，调用行因此不显示；这不等于错误结果也应隐藏 | 保留现有内部计划/Task 正常调用隐藏；不增加任务流水或新面板 | 正常 `declare_plan_evidence`、Task 与计划编排成功不出现在主对话；失败仍独立可见。待本批 TUI 回归与跨进程证据 |
| 内部 Tool 失败 / CLI-03、TOOL-11 | `UserToolErrorMessage` 错误分派；AUTH-SRC-2026-07-29-A；Observed（源码职责） | 错误结果使用独立路径；未知旧 Tool 仍有通用错误降级 | `tool.failed` 可选投影封闭原因码；仅 `verification_tool_unavailable` 映射为简短安全说明，不传异常、details、参数 JSON 或 requirementId | 缺字段显示旧通用错误；未知码协议拒绝；安全码显示“验证方式使用了当前不可用的工具”。待协议与 TUI 测试 |
| 同一要求后续修正 / CLI-05、PLAN-01 | 参考未观察到 Evidence Ledger 或 ordinal 恢复协议；Inferred / Unknown | 无可声称的等价稳定协议 | 本项目独立设计：宿主在单个 ActiveRun 内以合法 `VERIFICATION requirementId` 关联最近一次原因匹配的失败 ordinal；后续同身份成功才在 `tool.completed` 增加 `recoveredFailureOrdinal`。TUI 不按工具名、相邻顺序或文本猜测 | 同 requirement 多次失败只关联最近一次；不同 requirement、不同 Run、普通成功不得恢复；旧失败 status/ToolResult 不变。待 Fake、Java/stdio/TUI 跨进程证据 |
| 未恢复终态 / CLI-01、CLI-09 | 错误路径与交互终态职责；AUTH-SRC-2026-07-29-A；Inferred | 失败不能因普通调用隐藏而消失；准确恢复协议 Unknown | 取消、断连、纠正上限或后续失败均不产生恢复字段；保留原失败和真实 Run/Plan 终态 | 无 `recoveredFailureOrdinal` 时不得显示“已修正”；完成 Gate 与重复失败治理回归不变。待定向测试和真实入口复验 |

## 3. 独立契约

1. `PlanEvidenceDeclarationTool` 构造时对当前 Runtime 已注册的可信 BUILT_IN Tool 集合排序并冻结。模型可见说明、参数校验和执行前复检使用同一集合；列表为空时明确为 `none`。不得另建第二份允许名单，也不得自动替换模型 locator。
2. 未注册验证工具仍返回 `INVALID_ARGUMENTS/VALIDATION`。仅该确定性分支在安全 `details` 中携带 `failureReasonCode=verification_tool_unavailable` 和已通过领域校验的 `recoveryRequirementId`；不添加新的 `correctionSignature`，避免改变既有 Run-owned 参数纠错指纹和重复失败治理。后一个字段只供宿主处理 validation 在 `BeforeTool` 生命周期之前失败的情况。
3. stdio 只在规范化 ToolResult 同时满足 `toolName=declare_plan_evidence`、`INVALID_ARGUMENTS` 与 `VALIDATION` 时读取该保留 detail；其他或外部 Tool 即使伪造同名字段也不能触发原因码或恢复登记。通过后才复制封闭原因码，不透传完整 `details`、violations、参数、异常、locator 或 requirementId。
4. 成功声明由宿主从 `BeforeTool` 的原始结构化参数提取合法 `VERIFICATION requirementId`；validation 失败不会触发 `BeforeTool`，因此从内置声明 Tool 自己生成且经上述来源约束的安全 detail 取得同一规范身份。二者只保存在当前 `ActiveRun`，不持久化恢复映射。原因码匹配的失败登记 ordinal；同一 requirement 的后续成功声明移除并输出最近失败 ordinal。Run 结束即丢弃关联状态。
5. `recoveredFailureOrdinal` 只允许出现在 `declare_plan_evidence` 的 `tool.completed`，必须是正整数且小于当前 ordinal。TUI 收到后只更新对应失败记录的展示事实，不能改写其 `failed` status、输出或历史 ToolResult。
6. 旧宿主不发送可选字段时，新 TUI 保留通用错误且不猜恢复。未知原因码、非法 ordinal、错误 Tool 或错误事件类型按协议 fail closed。

## 4. 验证与交付

必须覆盖：

- Tool definition 完整列出冻结的可信验证工具，空集合、validate 与 direct execute 复检一致；
- 未注册 locator 返回安全原因 detail，但 correction signature 为空；
- 同 requirement 一次失败后成功、多个相同 requirement 失败后成功、不同 requirement、不同 Run、普通成功、取消/未恢复；
- stdio 只输出白名单原因码与权威关联 ordinal，不泄漏 requirementId、locator、异常或原始 details；其他 Tool 伪造保留 detail 必须经活动 Runtime 的生产 `publish` 事件链证明不产生原因码，后续真实声明也不被误恢复；
- TypeScript 协议拒绝未知码和非法关联，缺字段兼容；Reducer 只允许名称、失败状态与已知原因都匹配的旧声明记录成为恢复目标；
- 正常内部调用隐藏，未恢复显示具体原因，恢复后显示“已修正验证方式，继续规划”；ordinal 只供协议关联，展开仅表达“原声明失败／后续声明修正成功”，不向用户暴露内部序号；
- 原天气场景按 Fake、Java/stdio/TUI 跨进程、PTY/真实 Provider 顺序复验。真实入口由 `scripts/StartCodejDev.ps1 --tui-next` 启动；无法完成的实测必须标为未验证。

## 5. 剩余差距

本 ADR 不证明参考产品存在相同 Evidence Ledger、原因码、requirement 身份或 ordinal 协议，也不完成后续 R1—R6 全面 UI 对照。Markdown 表格排版、首次命令的 Windows 适配质量、复杂真实 Provider 计划质量、跨平台视觉与物理终端验收继续保留为后续批次差距；能力等级不变，S15 仍 OPEN。
