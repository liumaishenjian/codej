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
- 活动 Run 在 Model 状态之后、Tool 历史之前固定显示最多两行当前任务和 `active_form`；完整自动面板使用无全宽边框的紧凑行，不展示 Task ID/revision/owner/实现语言；↑/↓ 移动选择，Enter 显示详情，Esc 关闭；
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

- 普通复杂任务无需输入 `/tasks`，在真实 `task_create → IN_PROGRESS → COMPLETED` 后自动显示 Task List；
- 每个权威 snapshot 紧随对应 `tool.completed`，Board revision 严格单调；
- Plan planning 模型通过 `task_create` 建立中文任务；批准 execution 首先 list/get，并以同一 task-1..N 原位更新，不从 Markdown 预置第二套 Task；
- Task snapshot 依次显示中文 PENDING/IN_PROGRESS/COMPLETED；Evidence 独立验证真实产物，Run 与 durable Plan 均完成而不是 `time_limit_reached`；
- 自动面板不抢输入焦点；活动 Run 的当前任务不会被长 Tool 历史挤出视口，完成行显示 `✓`，完成态装饰策略为 `strikethrough/dim`。

2026-08-26 corrective E2E 覆盖 6 个场景。历史失败先后暴露 execution scope 缺 Task Tool、简单状态参数被旧 parser 拒绝、隐式 5m/30m 总 deadline，以及批准后 seeder 造成两套 Task identity/capability。当前实现三个总量维度为 optional，Interactive/Plan/approved-plan 均 absent；Plan 两阶段共享同一 Board 和四个 Tool，通用 `task_update` 由宿主管 CAS。XLSX fixture 的 options timeout 固定 100ms，仍通过独立 Tool timeout 完成 1.2s 检查；隔离 Workspace 从文件不存在开始生成，独立进程重新打开校验 18×7=126 行，最终 `/tasks` Ink frame 显示五个 `✓ 中文标题`。失败历史均保留为可证伪证据。

## Demo 5：窄状态协议、样式节点与真实交付

```powershell
.\mvnw.cmd -pl cc-java-cli -am "-Dtest=TaskToolBatchBTest,TaskToolProductionCompositionTest,RuntimeStdioCommandHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
npm --prefix cc-java-tui run check
npm --prefix cc-java-tui run test:real-java
```

预期观察：

- 模型可见的通用 `task_update` 只有 task ID 与业务字段；`COMPLETED + active_form` 一次成功，宿主读取当前任务并处理 CAS 和 claim；
- 最大长度 Unicode Provider call ID 的 active/status 阶段 ID 固定有界，同一更新完整或 partial replay 不推进 revision；
- React 节点属性直接显示 IN_PROGRESS 主标题 bold、活动行 dim、COMPLETED strikethrough/dim 且活动行消失；真实 E2E 最终 `/tasks` frame 逐条显示五个 `✓ 中文标题`；
- 30m 只作为 Print/显式调用方 hard deadline 默认值；Interactive/Plan/approved-plan 不装配总 Run deadline；
- 真实 Java E2E 在 100ms options timeout 下完成 1.2s Tool，从不存在开始生成 create-only XLSX，并由独立进程重开验证 126 行。

## 负例与事实边界

- 自依赖、全图环、blocked claim/complete、旧 revision/claim epoch、跨 Board capability、未知 target actor 均拒绝且不推进 revision。
- stable protocol 只读；不能绕过模型 Tool/Pipeline 修改 Board。
- 该 Demo 不证明 Team Board、跨进程推送、peer messaging、lease、自动领取或远程 worker；这些仍属于 `SUB-11`。
