# S15 Session-local Task List Demo

## 前置条件

- Java 21、Node.js 22；仓库根目录为工作目录。
- 不需要网络、API Key 或真实模型；生产 composition 由确定性 Fake Model/Tool 测试驱动。

## Demo 1：生产 Tool 与 child capability

```powershell
.\mvnw.cmd --% -q -pl cc-java-cli -am -Dtest=TaskToolProductionCompositionTest,HeadlessChildTaskBoardCompositionTest,PermissionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期观察：

1. root 通过生产 `task_create` 创建 Task；
2. `delegate_agent(taskIds)` 经审批入口，宿主验证 Task ID 并创建独立 child Session；
3. child 只能访问授权 scope，完成后 root `task_list` 可见同一 Board 的状态；
4. PLAN、显式 Deny、Plugin spoof 和越权 Task ID 被拒绝。

2026-08-25 实际结果：PASS。

## Demo 2：Session journal、Resume 与 Fork

```powershell
.\mvnw.cmd --% -q -pl cc-java-cli -am -Dtest=FileSessionTaskBoardTest,JsonlSessionCodecJsonNullTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期观察：

- Resume 保留 Board identity、revision、依赖和 completed 状态；
- terminated claim 投影为 recoveryRequired，不自动重放或完成；
- Fork 创建新 Board，IN_PROGRESS 重置为 PENDING 并清 claim；
- 256×4 KiB description 的增量 journal 小于 4 MiB，可完整恢复；损坏尾部只保留最后完整前缀。

2026-08-25 实际结果：PASS。

## Demo 3：stable protocol 与 Ink Surface

```powershell
.\mvnw.cmd --% -q -pl cc-java-cli -am -Dtest=StableProtocolHandlerTest,SessionCommandDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix cc-java-tui run check
```

预期观察：

- stable v1 协商 `task-list-v1` 后，`task.snapshot` 返回 revision、最多 50 项和 continuation cursor；
- `/tasks` 打开 Task List，按恢复、进行中、待处理、阻塞、最近完成排序；
- 自动面板使用无全宽边框的紧凑行，不展示 Task ID/revision/owner/Java；↑/↓ 移动选择，Enter 显示详情，Esc 关闭；
- 进行中项加粗，完成项显示 `✓` 并使用删除线/dim；全部完成约 5 秒后面板隐藏，`/tasks` 可重开；
- 20/24/240 列宽度测试覆盖 CJK、emoji、combining sequence、依赖与恢复后缀，相关行不越界；
- Run 的 canonical final 保持原文，只额外显示未完成/需恢复 advisory。

2026-08-25 corrective 实际结果：Java PASS；TUI build PASS，17 files / 293 tests PASS。

## Demo 4：真实 Java stdio → Ink 实时闭环

按 `cc-java-tui/README.md` 设置编译 classpath 后执行：

```powershell
npm --prefix cc-java-tui run test:real-java
```

预期观察：

- 普通复杂任务无需输入 `/tasks`，在真实 `task_create → CLAIM → COMPLETED` 后自动显示 Task List；
- 每个权威 snapshot 紧随对应 `tool.completed`，Board revision 为 1、2、3；
- 批准 Plan 的中文显式步骤由应用在首个模型 Tool 前预置，城市等其他编号列表不会成为 Task；执行模型没有 `task_create`，只更新原步骤；
- Task snapshot 依次显示中文 PENDING/IN_PROGRESS/COMPLETED；全部 Task 与 Evidence 满足后进入零 Tool final-only，Run 与 durable Plan 均完成而不是 `time_limit_reached`；
- 自动面板不抢输入焦点，完成行显示 `✓`，完成态装饰策略为 `strikethrough/dim`。

2026-08-26 第四次 corrective 实际结果：6/6 PASS。历史首次 corrective E2E 曾因 Plan execution scope 未保留 Task Tool 而失败并报告三个 `unknown_tool`；后续真实运行又暴露通用 task_update 难以驱动逐项交互和 5m Run deadline 取消重试。本轮批准 execution 改用 `task_id/status/active_form`，并真实生成、重新打开校验 18×7=126 行 OpenXML XLSX；五项任务均观察到 PENDING/IN_PROGRESS/COMPLETED，另一个命令 timeout 场景保持 recovery，失败历史均保留为可证伪证据。

## Demo 5：窄状态协议、样式节点与真实交付

```powershell
.\mvnw.cmd -pl cc-java-cli -am "-Dtest=ApprovedPlanTaskUpdateToolTest,TaskToolProductionCompositionTest,CcJavaCommandTest,StdioProtocolCodecTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
npm --prefix cc-java-tui run check
npm --prefix cc-java-tui run test:real-java
```

预期观察：

- 模型可见的批准 Plan `task_update` 只有 task/status/active form，Java 注入 Plan、Run、CAS 和 claim；
- 最大长度 Unicode Provider call ID 的内部阶段 ID 固定有界，同一更新重放不推进 revision；
- React 节点属性直接显示 IN_PROGRESS 主标题 bold、活动行 dim、COMPLETED strikethrough/dim 且活动行消失；
- 默认 Run timeout 为有界 30m，显式 `--timeout` 仍覆盖；
- 真实 Java E2E 生成并校验 XLSX，而不是只写入同名文本文件。

## 负例与事实边界

- 自依赖、全图环、blocked claim/complete、旧 revision/claim epoch、跨 Board capability、未知 target actor 均拒绝且不推进 revision。
- stable protocol 只读；不能绕过模型 Tool/Pipeline 修改 Board。
- 该 Demo 不证明 Team Board、跨进程推送、peer messaging、lease、自动领取或远程 worker；这些仍属于 `SUB-11`。
