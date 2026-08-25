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
- ↑/↓ 移动选择，Enter 显示详情，Esc 关闭；完成项显示 `✓` 并使用删除线/dim；
- Run 的 canonical final 保持原文，只额外显示未完成/需恢复 advisory。

2026-08-25 实际结果：Java PASS；TUI build PASS，17 files / 289 tests PASS。

## 负例与事实边界

- 自依赖、全图环、blocked claim/complete、旧 revision/claim epoch、跨 Board capability、未知 target actor 均拒绝且不推进 revision。
- stable protocol 只读；不能绕过模型 Tool/Pipeline 修改 Board。
- 该 Demo 不证明 Team Board、跨进程推送、peer messaging、lease、自动领取或远程 worker；这些仍属于 `SUB-11`。
