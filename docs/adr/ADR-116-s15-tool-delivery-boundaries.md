# ADR-116：工具执行到最终交付的呈现边界

Proposed；2026-09-13；S15；CLI-03/04/05/09 L2、PLAN-01 L1、TOOL-10 L1，等级不变。

AUTH-SRC-2026-07-29-A，Observed：`utils/groupToolUses.ts` 以同响应与工具身份关联结果，verbose恢复逐项记录；`tools/BashTool/BashToolResultMessage.tsx` 分离输出、错误与执行状态；`query.ts` 的工具结果与下一模型响应属于循环链路。沿用参考职责，不复制表达。本项目Java仍拥有工具执行、工具结果配对和计划验收；TUI只投影事件。

本批独立契约：

| 场景 / Feature | 参考调用链 / 分类 | 采用与偏差 | 验收 |
| --- | --- | --- | --- |
| 工具关联 CLI-05 | groupToolUses.applyGrouping → GroupedToolUseContent resultsByToolUseId，Observed | Java BeforeTool/AfterTool → stdio → Runtime.tool → runtimeBody；额外以终态防迟到覆盖，保留现有同回合相邻成功搜索分组 | 错callId、重复终态、迟到输出、遮蔽、展开逐项 |
| 命令 TOOL-10 | BashToolResultMessage → OutputLine，Observed | 独立固定Shell审批、真实输出及退出码；不解析自然语言判断成功 | 真实创建文件→命令失败→模型纠正→再次审批→结果 |
| 内部编排 CLI-09/PLAN-01 | TaskListTool/TaskUpdateTool.renderToolUseMessage返回空，Observed | 普通界面继续隐藏；详情显示本项目工具参数/结果是排障需要的独立偏差，不声称参考也显示Task JSON | 默认无流水、详情可核对、脱敏与截断继续有效 |
| 最终交付 CLI-03/04 | query工具结果汇入下一模型请求；Java既有RunResult→stdio终态，Observed | 当前回合且工具之后正文或明确finalText；无正文沿用异常提示，不伪造交付总结 | 查询、文件与计划三个真实入口场景；缺正文由Fake证伪 |

- 成功终态缺少finalText时，只有最后工具之后的当前模型回合正文可作为交付证据；工具前说明或任务清单更新不算最终回答。不由前端编造产物或自动重跑。
- 单条工具以当前Run、ordinal及可用callId关联；终态不可被后续start/output/重复终态重写，错callId不能覆盖原调用。这样已遮蔽结果也不能被迟到输出重新暴露。缺callId旧协议继续使用既有ordinal，不提升其关联保证。
- 普通对话继续隐藏成功的内部计划编排；Ctrl+O逐项展示参数、实际结果、失败与截断，便于核对任务更新和真实交付的区别。既有修正摘要保留，原始结果仍可读。

验证范围：工具前说明→工具→无最终回答、后续模型正文、失败说明与最终回答并存；重复/迟到/错调用/遮蔽；计划成功和失败工具的详情、六尺寸。完整Java Fake回归继续覆盖查询/命令失败、文件审批与计划确认执行、最终回答、取消与下一轮。另用生产入口隔离目录做真实Provider验证。物理鼠标、跨平台、完整文件diff和持久恢复不在本批完成声明内。
