# S15 Session-local Task List Gap Report

## 已达到

`TASK-01..05` 达到 L2：Session-local 三态任务板、依赖 DAG、CAS/claim/recovery、四个普通 Run 生产 Tool、统一 Permission/Hook/Pipeline、增量 JSONL、Resume/Fork、root/child capability、stable snapshot 与 stdio `/tasks` 均已实现。批准 Plan 的唯一显式步骤 section 会按原语言/顺序应用预置 Task，模型无 `task_create`，只使用由 Java 注入 revision/claim/Plan identity 的窄状态更新；Task+Evidence 完成后一次 final-only 收敛。普通复杂任务和批准 Plan 均通过真实 Java stdio→Ink E2E；五步中文场景实际生成并校验 OpenXML XLSX，timeout 场景证明 Run terminal 与 `IN_PROGRESS + recoveryRequired` 一致。紧凑面板不抢焦点，进行中显示 bold 主行与 dim activity，完成态删除线/dim 并在约 5 秒后只隐藏。

## 仍未达到参考机制的部分

- `SUB-11` Team Task Board：不同 root Session、进程或主机之间不共享任务。
- 无 peer message、跨进程 watch/poll/push subscription、离线 owner reclaim、时间 lease 或自动领取。
- stable v1 只提供协商后的分页 snapshot，不提供跨连接增量事件订阅。
- child capability 只在一次本机生产委托中生效，不是远程 worker 凭证或授权协议。
- 不支持从任意 Plan Markdown 自由推断任务：只接受唯一明确步骤 section 的顶层有序项；零 section 保持 legacy，多 section Fail Closed。完成 Task 仍不是 Plan 审批或 Evidence 本身。

## 风险与后续验证

- journal 当前受单 Writer Session 约束；若未来进入 Team/多进程，必须先重新设计跨进程一致性、通知漏失恢复和 owner fencing，不能直接扩张现有锁。
- TUI 完成项删除线依赖终端对 ANSI SGR 的支持；不支持时仍有 `✓`、分组和 dim 作为可读降级。
- stdio codec 与 dispatcher 的 intent 集目前仍由两处显式 switch 维护；本轮已用真实 `/tasks` 跨进程回归锁定，未来新增 intent 必须同步 schema 测试，避免再次出现 Client 可发送而 codec 拒绝。
- 256 live / 4096 mutations / 32 dependencies / 16 KiB projection 是本项目资源契约，不应被描述为参考产品常量。
- S15 的 L4 创新收益、真实 Provider 质量、PERM-05 A/B Eval 和双 Provider BYOK 仍是独立 blocker；本切片不提供这些证据。

## Accepted Deviation

- canonical 只保存 `blockedBy`，反向 `blocks` 每次由快照投影，避免双向边更新不一致。
- recoveryRequired 是 Run 终态派生状态，不是第四种持久 TaskStatus；恢复必须显式操作，避免自动重放副作用。
- stable protocol mutation 明确不开放，保持所有模型写入经过唯一 Tool Pipeline。
