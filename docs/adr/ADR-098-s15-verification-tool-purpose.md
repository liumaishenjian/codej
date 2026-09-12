# ADR-098：验证工具必须提供交付事实，审核恢复使用真实事件

- 状态：Proposed；日期：2026-09-12；Stage：S15。
- Feature：PLAN-01 L1、CLI-05/09 L2保持不变；不关闭Stage。
- 参考：AUTH-SRC-2026-07-29-A，Observed（源码职责），准确Revision Unknown。

## 问题与参考对照

用户青岛天气运行中，run_command成功，但required验证绑定ask_user_questions，最终因缺少该工具成功结果停止。注册可信不等于可以证明交付；提问和Task进度不应成为最终交付依据。更早一次审核条件不足已恢复，UI却残留plan_gate_blocked。

| 场景 | 参考入口与职责 | 本项目决策及可证伪验证 |
| --- | --- | --- |
| 计划审核 | src/tools/ExitPlanModeTool/UI.tsx的renderToolUseMessage不产生普通调用行；审核有独立面板 | 复用已绑定当前Run的plan.review.requested事件确认同Run审核阻塞已解除；原失败事实不改写。取消、旧Run或无关工具成功不得标恢复 |
| 错误结果 | src/components/messages/UserToolResultMessage/UserToolErrorMessage.tsx分别处理错误、拒绝和取消 | 保留简短中文原因与真实恢复状态，不展示裸错误码、不伪造成功 |
| 交付验收 | 参考不证明codej Evidence Ledger机制；此项为独立设计 | 限定验证候选来自可信内置且效应为工作区读/写、进程执行、网络操作。排除交互、内部状态、计划编排和系统级委派。实际成功ToolResult与文件Gate保持不变 |

## 实现边界

宿主只生成一份当前可用验证工具集合，供模型定义、声明校验、审核Gate及恢复检查使用。规划中不开放命令执行，说明批准后才可调用对应工具。不能通过提问一次、更新Task或更换验收条件绕过真实结果。旧计划不静默改写；已有显式反馈/纠正路径继续使用同一计划身份。

审核错误使用中文说明；只有同一Run真实审核事件到达才标记已恢复，运行终态与持久化状态不受UI标记影响。无需新增协议字段或AgentLoop。

## 验收

Fake复现以交互工具声明required被拒绝→同身份改用run_command→审核→命令成功→最终正文与COMPLETED。确保合法read/write/command回归继续通过；UI测试审核失败、无关成功、真实恢复及跨Run隔离。真实Provider在新唯一目录通过正式--tui-next入口重跑青岛七天天气至正文可见及下一轮输入；实际结果记录在核心证据，不提前标通过。

实测补充的显示问题：WebSearchTool/UI.tsx的renderToolResultMessage从工具结构产生搜索摘要；BashToolResultMessage区分stdout/stderr与执行元数据。本项目不能取包装正文首行作为用户摘要，导致shell/provenance暴露。现有协议缺少可信搜索次数和耗时，不编造数值；成功命令/网页搜索显示明确完成状态，原输出仍可展开。新增收起/展开测试覆盖元数据头不冒充结果。
