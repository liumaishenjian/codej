# ADR-086：S15 Plan evidence correction continuation 与最终交付 Gate

- Status: Accepted
- Date: 2026-08-22
- Stage: S15 Independent Innovation（生产正确性修复）
- Feature IDs: `PLAN-01`、`CLI-01`、`CLI-07`、`OBS-04`（等级不变）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Capability Change: 无；S15 Exit 保持 OPEN

## 1. 问题与根因

accepted Plan 的旧线性化顺序是：`AgentRuntime` 接受无 Tool Call 的最终 Assistant、把模型 prose 写入
canonical transcript 并产生 `RunFinished(COMPLETED)`，随后 `HeadlessRuntimeSession` 才调用
`validatePlanEvidence`。因此交付物 locator 不一致时，Plan 虽最终进入 `NEEDS_VERIFICATION`，stdio/TUI
却可能已经展示模型“已完成”声明并回到 ready。

真实复现中 required `DELIVERABLE` locator 是 `河南各市7天天气.xlsx`，执行只创建
`河南各市7天天气预报.xlsx`。文件验证正确记录 `FILE_MISSING_OR_UNSAFE`，但该确定性失败发生在模型 prose
和 Run terminal 之后。根因不是 WorkspaceGuard 或 Ledger 误判，而是最终 Assistant、Run terminal、Plan evidence
terminal 与 Surface delivery 的 authority 顺序错误。

## 2. 受控参考研究

按 ADR-022 对授权快照中的 Plan 批准、执行期 Permission/Context 切换、远程执行轮询、Session 恢复与取消状态做
窄读，只提炼职责、状态、恢复和测试方法；未复制或翻译函数体、Prompt、文案、私有名称、布局、常量、Fixture
或源码字节。

| 分类 | 抽象结论 |
| --- | --- |
| Observed | Plan 批准是显式决定并与执行期策略绑定；UI 不直接执行 Tool，副作用仍经过正常 Runtime。 |
| Observed | 远程活动、完成、失败与取消是独立状态；瞬时轮询失败采用有界治理，迟到结果不能覆盖已停止任务。 |
| Observed | Resume/Fork 恢复关联，不表示自动重放已经成功的副作用。 |
| Inferred | evidence 失败应在同一受预算、取消与 Permission 治理的执行 Runtime 内纠正，不能另启一个自动重放旧 Tool 的执行。 |
| Inferred | Surface 完成声明必须服从确定性 authority，不能以模型 prose 替代 evidence terminal。 |
| Unknown | 参考内部是否存在逐 requirement ledger、final-write 前 continuation、重复失败指纹或等价次数上限。 |

## 3. 独立设计决策

1. `AgentRuntime` 在无 Tool Call 的最终 Assistant 写入 canonical transcript 前调用
   `FinalAssistantHandler.decide`。决定为 `ACCEPT`、`REJECT` 或 `CONTINUE`；旧 boolean handler 通过 default
   method 保持兼容。
2. `CONTINUE` 不写入当前 Assistant、不产生 Run terminal、不执行 Tool，也不创建第二个 Run。Runtime 只进入下一
   Model Turn，继续复用同一 Run ID、预算、deadline、取消、Permission、Approval、Hook 与 Tool Pipeline。
3. accepted Plan 使用私有 `PlanExecutionCorrectionController`。每次候选 final 到达时，它重新运行确定性 evidence validation，并读取绑定当前 `planId` cohort 的未完成 Task；同 Session 普通 Run 或旧 Plan Task 不参与本次判断。先把最新 Ledger 以 `EXECUTING` revision durable 保存；Evidence 全部满足且当前 cohort 未完成 Task 为空时才接受 final。
4. blocking correction 由批准 requirement 的封闭 `requirementId/kind/locator/reason` 与当前 planId cohort 未完成 `task-N` ID 构造；两类至少存在一种。不得包含文件正文、Task description/metadata、Tool 输出、异常文本、物理路径、Prompt、Markdown 或 Secret。该有界列表通过 transient System projection 反馈下一 Model Turn，不进入 canonical transcript。
5. correction 有独立固定上限 2；指纹同时覆盖 Evidence failure 与当前 cohort 未完成 Task ID。达到重复指纹或次数边界时，若只剩 Evidence failure，Run 可停止且 Plan 写 `NEEDS_VERIFICATION`；若当前 cohort 仍有未完成 Task，则拒绝候选 final 并由失败终态保持真实任务状态，不能把 prose、Task 或 Plan 伪装成完成。
6. correction 不自动撤销、重复或重放任何成功 Tool。旧 ToolResult 保留在 canonical history，模型只能自行提出新的
   Tool intent，并再次经过统一 Pipeline。取消、模型错误、deadline、limit 与 incomplete stream 沿现有 Run terminal
   进入 durable Plan failure status。
7. stdio 不在 lifecycle `RunFinished` listener 中直接发布 Surface terminal，而是等待 `application.run()/runPlan()` 返回；此时 Headless `finally` 已释放 active Run，客户端收到 `run.completed` 后立即批准不会命中 `STALE_PLAN_REVIEW`。approved Plan 仍抑制全部模型 text delta，并先发布 `plan.verification.correction|required|completed`；只有 Plan `COMPLETED` 的 terminal 才包含最终 `finalText`。`NEEDS_VERIFICATION` terminal 不含第一份或边界耗尽后的模型完成声明。
8. TUI 严格校验 `plan.verification.correction` exact schema，包括 `incompleteTaskCount/incompleteTaskIds/failures` 数量一致性；纠正时保持 running，并明确显示“任务或证据尚未收敛”、同一 Run、当前次数和“不会自动重放既有副作用”。evidence required 或失败终态后才回 ready，并显示 actionable 非完成状态。
9. deliverable 验证继续使用 WorkspaceGuard realpath、16 MiB 上限、读后重检与 SHA-256；verification 继续只接受
   当前 execution message slice 中同名 canonical `SUCCESS` ToolResult。模型 prose、文件名近似和失败 ToolResult
   永不构成证据。

## 4. 状态与顺序

```text
candidate final
  -> deterministic evidence validation + authoritative current planId Task cohort
     -> evidence satisfied and current cohort all COMPLETED
        -> accept final -> Plan COMPLETED -> verification.completed -> run.completed(finalText)
     -> blocking evidence/task failures, fresh fingerprint, below bound
        -> persist EXECUTING ledger -> correction event -> same Run next model turn
     -> repeated fingerprint or bound exhausted, evidence-only failure
        -> accept Run stop only -> Plan NEEDS_VERIFICATION
        -> verification.required -> run.completed(no finalText)
     -> repeated fingerprint or bound exhausted, incomplete Tasks remain
        -> reject candidate final -> failed terminal(no finalText), preserve real Task states

cancel/model error/deadline/limit/incomplete stream
  -> Plan failure terminal -> plan.execution.failed -> run terminal(no finalText)
```

## 5. 可证伪验证

- Fake/Headless Runtime：中文文件名不一致后，同一 Run 创建精确 locator、重新计算 digest 并完成；第一份 final 不进入
  下一请求或 canonical transcript，错误文件仍存在以证明没有自动回滚或副作用重放。
- 有界失败：持续缺失产生一次 correction，第二次相同指纹后停止，Plan 为 `NEEDS_VERIFICATION`，模型调用不无限增长。
- Verification Tool：失败/拒绝的 `write_file` 不构成证据；后续 canonical 成功 ToolResult 才使 requirement PASSED。
- 失败终态：correction 中模型错误进入 `FAILED`；取消进入 `CANCELLED`，已成功写入的文件保持一次且精确 deliverable
  不会被自动创建。
- stdio：correction payload exact，包含数量匹配的未完成 Task ID 与可为空的 Evidence failures；verification 先于 terminal、未验证 terminal 无 `finalText`、approved `RunFinished` 不重复投影。
- TUI：protocol 拒绝额外字段；reducer/Ink 保持 running、显示 no-replay notice、未展示 withheld prose，并在
  `NEEDS_VERIFICATION` 后显示非完成状态。
- 真实 Java Plan E2E：真实 stdio child 经两次 `write_file` Approval，先生成错误中文文件名，再由同一 Run correction
  生成精确文件名；两个 Tool 各执行一次，correction、digest evidence、最终文本和 Ink ready 顺序均通过。

## 6. 剩余差距

- 固定上限 2 与重复指纹策略是本项目独立安全默认值，尚无真实 Provider 质量/成本评测；后续 L4 Eval 可比较不同上限，
  但不得取消绝对 ceiling 或 no-replay invariant。
- stdio v0 仍是内部协议；stable v1/daemon、跨机器远程执行和多人 Plan 冲突继续使用后续正式契约。
- 本修复没有提供事务回滚、OS Sandbox 或副作用幂等保证。它只保证 Runtime 不自动重放；模型提出的新 Tool intent 仍需
  Permission/Approval 和 Tool 自身安全边界。
- 真实 Provider、Linux/macOS 安装版与 S15 L4 A/B Eval 未完成，`PLAN-01`、`OBS-04` 和 Stage 状态均不提升。
