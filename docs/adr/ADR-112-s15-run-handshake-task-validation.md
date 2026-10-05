# ADR-112：排队握手与任务参数可纠正性

Proposed；2026-09-12；S15，CLI-04/09 L2、PLAN-01 L1不变。

现场：用户青岛请求对应本机会话显示task_create首次metadata含departDate，校验失败；随后省略metadata重试成功，计划产物/审核请求及run.completed均已持久化。截图的启动拒绝不等于后端运行失败；无现场stdio握手转录，不能确定该次提示对应queued还是rejected。

对照沿用ADR-085已完成的AUTH-SRC-2026-07-29-A输入队列、correlation、tombstone机制研究：已接受/排队不等于拒绝；后续事件继续绑定原request，真实终态才解除关联。新界面原先把所有非accepted都finish，queued确定性复现会导致后续结果丢失。修复为queued保留关联、等待run.started；steering.discarded才结束，取消等待保留意图直到run.started后发送取消；真正rejected仍终结，不能自动重发。

TaskMetadata只允许小写受限键，但TaskCreateTool schema未描述该约束。使用Domain同一公开pattern生成schema propertyNames，校验错误返回不含参数原文的键规则，保持拒绝不合格键，不静默改名或绕过元数据边界。

验收：queued→started→工具错误→修正→最终正文；queued取消/丢弃、真正拒绝；metadata camelCase拒绝、snake_case通过、schema一致、错误不回显用户内容。现场会话只读分析，不把旅行正文/网页结果复制成fixture，不自动重新执行或批准原计划。
