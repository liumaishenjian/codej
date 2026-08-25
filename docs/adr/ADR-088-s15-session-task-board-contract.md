# ADR-088：S15 Session-local Task Board 契约

- Status: Accepted
- Date: 2026-08-25
- Stage: S15 Independent Innovation（Task List Batch A-E）
- Feature IDs: `TASK-01`、`TASK-02`、`TASK-03`、`TASK-04`、`TASK-05`
- Current → Target: `L0 → L2`；Batch A-E 已完成 Domain/Core、Tool/Pipeline、Session persistence、生产装配、stable protocol 与 Ink TUI
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 授权快照机制为 `Observed / Inferred / Unknown`；本项目契约与 Batch A-E 独立实现为 `Documented / Tested`

## 1. 背景与批次边界

现有 S12 `ChildTaskReport` 描述一次父子执行委托，`SUB-11` 描述跨 Session/团队共享任务和消息；两者都不是用户与模型在单个 Session 中持续维护的工作任务板。S15 需要一个状态、持久化和权限独立于 `PlanArtifact` 的 Task Board：Plan 是需审批的 Markdown 规划工件，Task 是执行进度元数据。批准后的 Plan 可在执行边界把唯一显式步骤 section 冻结成权威 Task，但 Task 状态不能反向审批 Plan，也不能替代 Evidence Gate。

Batch A 实现框架无关的 Task 值对象、资源契约和 Core 确定性状态机；Batch B 增加四个内置 Tool Adapter 并验证统一 Pipeline、Permission、Hard Denial、Plan eligibility、AutoReview 与 PRE_TOOL/POST_TOOL Hook；Batch C-E 完成 canonical Session journal、Resume/Fork、root/child production capability、stable/stdin protocol 和 Ink TUI。Team board、跨进程订阅、peer message、lease 与自动领取仍明确延期 `SUB-11`，其等级保持 L0。

## 2. 受控参考研究（G0/G2）

2026-08-25 在仓库外只读研究 `AUTH-SRC-2026-07-29-A` 中任务创建、更新、列表、详情、依赖、认领、订阅和终端展示职责。只提炼状态、不变量、失败恢复和验证方法；未复制或翻译函数体、Prompt、注释、错误文案、私有名称、文件布局、常量、Fixture 或源码字节。

| 分类 | 最小机制结论 | 本项目采纳或偏离 |
| --- | --- | --- |
| Observed | 任务使用 pending、in-progress、completed 三种主状态；blocked 可由未完成依赖推导 | 采纳三态与派生 blocked，不新增持久化 BLOCKED 状态 |
| Observed | 摘要列表与单项详情分层，依赖和 owner 参与可运行任务选择 | Batch B 的 List 只投影有界摘要，Get 返回同一锁内捕获的 Board revision 与 canonical/derived detail |
| Observed | claim 必须在检查阻塞、完成和已有 owner 后原子收敛 | 采纳同步线性化、task/board revision 与 claim epoch |
| Observed | 任务共享模式需要额外所有权、通知、文件观察和漏事件恢复 | 本切片只做 root-owned Session-local board；Team/跨进程机制延期 `SUB-11` |
| Observed | 完成项、阻塞项与进行中项在 TUI 中采用不同优先级和有界展示 | 仅作为后续 Batch D/E 的 Surface 验收输入，不在 Batch A 实现 UI |
| Observed | 紧凑列表采用无全宽边框行；进行中项强调、完成项删除线/弱化、阻塞项弱化，全部完成短暂可见后退出常驻展示 | 采纳行为层级；本项目以 Ink、自有中文文案、5 秒时长和 display-width 测试独立实现 |
| Inferred | 恢复不能把中断中的任务解释成可安全重放副作用 | 公开状态仍为 IN_PROGRESS，`recoveryRequired` 由终止 run 与 claim 派生，必须显式续领、释放或重分配 |
| Unknown | 准确版本、稳定外部协议、全部 Team/多进程一致性和公开兼容承诺 | 不把参考 schema、常量或文件格式作为本项目测试 Oracle |

本项目有意偏离参考材料中可观察到的双向依赖维护压力：只持久化 `blockedBy` 单向 canonical edge，`blocks` 完全由快照投影；每次边变更在提交前执行全图环检查。

## 3. 产品与身份边界

1. Board 由 root Session 独占拥有；不同 Session 即使 Workspace 相同也不能共享 Board。
2. Resume 继续原 Board；Fork 创建新 Board 与 lineage，保留 COMPLETED、把 IN_PROGRESS 重置为 PENDING，并清除 owner/claim/recovery/idempotency。
3. Child 继续拥有独立 Session、Context、Permission、Run 和取消所有权；父模型只能在 `delegate_agent.taskIds` 提出候选 ID，宿主重新验证节点存在、固定最多 32 项并注入不可伪造 capability。嵌套委托不继承 parent Board，不能扩大范围。
4. Root actor identity 对 Board 生命周期稳定，Run identity 单独变化；Child actor 使用独立 invocation identity，不能伪装为 Root。
5. Board capability 绑定 board、owner Session、actor、actor Session、actor Run、root/child 角色与可访问 Task scope；模型不能提交或改写这些可信字段。CLAIM/RESUME_CLAIM 不接受命令侧 RunId，只使用 capability 的 actorRunId。

## 4. Domain/Core 独立契约

### 4.1 状态与投影

公开 `TaskStatus` 只有：

```text
PENDING → CLAIM → IN_PROGRESS → COMPLETE → COMPLETED
COMPLETED → REOPEN → PENDING
IN_PROGRESS → RELEASE → PENDING
```

- `blocked`：`blockedBy` 中至少一个任务尚未 COMPLETED；
- `blocks`：从其他任务的 canonical `blockedBy` 反向投影；
- `recoveryRequired`：任务为 IN_PROGRESS，存在 claim，且 claim 绑定的 Run 已终止；
- blocked/recovery 都不是第四种持久状态；取消、崩溃和恢复不得自动完成、释放或重放任务副作用。

### 4.2 Revision、ID 与 tombstone

- Board revision 从 0 开始，每次成功 mutation 严格 `+1`；
- Task revision 从 1 开始，每次影响该 Task 的成功 mutation 严格 `+1`；
- 更新要求 `expectedTaskRevision`，涉及全图或删除的 mutation 同时要求 `expectedBoardRevision`；
- Task ID 由 Board high-water mark 单调生成；删除只产生 tombstone，ID 永不复用；
- 单 Board 内 `(actorId, actorSessionId, actorRunId, callId)` 是重试幂等键（Board 由服务实例隐含）：相同参数返回原成功结果，不同参数返回结构冲突；callId 不替代 Writer lock 或 CAS。拒绝结果不进入缓存，避免攻击者用失败调用扩张内存。

### 4.3 Claim 与恢复

Claim 保存 `actorId/runId/claimEpoch/claimedAt`，其中 runId 只能取宿主 capability 的 actorRunId。CLAIM 是普通 PENDING 任务进入 IN_PROGRESS 的唯一入口；RESUME_CLAIM 只允许 Root 对 recoveryRequired 任务生成新 epoch/run；RELEASE 只清除活动 claim、保留 owner 并回到 PENDING；COMPLETED reopen 也保留 owner。IN_PROGRESS 的 EDIT/complete/release/reassign 必须携带当前 epoch，旧 epoch 必须拒绝；recoveryRequired Task 不能直接 EDIT 或 complete，只能显式 resume、release 或 reassign。无时间 lease 和自动过期，因为本切片没有 Team/多进程 worker。

### 4.4 Mutation 集

Core `TaskListService` 提供确定性 mutation：

- CREATE：固定创建 PENDING、无 claim，调用方不能指定 ID、Board、owner 或 status；
- EDIT：修改 subject、description、activeForm 与 metadata patch；IN_PROGRESS 时必须携带当前 claim epoch；
- TRANSITION：只接受目标 PENDING（completed reopen）或 COMPLETED；
- CLAIM / RESUME_CLAIM / RELEASE：按 claim 与 recovery 规则收敛；
- ASSIGN / REASSIGN：仅 Root 可分配 actor；Child 只能 self-claim/release 或修改 capability 授权且归属自己的任务；
- DEPENDENCY：单次原子添加/删除 canonical blockedBy，拒绝空 ID、自依赖、缺失/tombstoned 节点、重复边与全图环；
- DELETE：仅 Root，存在入边时拒绝，不级联，成功后保留 tombstone。

被阻塞任务不能 claim 或 complete。Root 可完成未 claim 且未阻塞任务；Child 完成任务时必须持有当前 claim 与 epoch。

## 5. 资源与元数据契约

| 资源 | 上限 |
| --- | --- |
| live tasks | 256 |
| 单 Board 成功 mutation | 4096；达到上限后仅已缓存的完全相同重试可返回原结果，新调用 Fail Closed 且 revision 不变 |
| 幂等缓存 | 仅保存成功 mutation，最多 4096 项；Batch C replay 必须由 canonical 成功事件重建计数和索引 |
| 每任务 blockedBy | 32 |
| 单次边变更 | 32 |
| subject / activeForm | 各 200 Unicode code points；拒绝全部 ISO control |
| description | 4096 UTF-8 bytes；只允许 LF/TAB 控制字符，拒绝 CR/ESC/其他 ISO control |
| metadata | 16 keys、canonical UTF-8 总计 4096 bytes |
| metadata key | ASCII 小写字母起始；后续仅小写字母、数字、`_`、`-`、`.`；最长 64 |
| metadata string | 无控制字符、合法 Unicode、最多 512 code points |
| metadata number | JSON 安全整数 |

metadata 持久值只允许 boolean、安全整数和字符串；patch 中删除由独立 removal 集表达，后续 Tool Adapter 再把 JSON `null` 转为该领域语义。所有可能进入 JSON/journal/protocol 的 Map/Set 均按 key/TaskId/enum natural order 冻结，不能依赖 Hash/插入顺序。达到上限必须在 mutation 前 Fail Closed；语义无变化的 EDIT/DEPENDENCY 确定性拒绝，不推进 Board/Task revision，也不产生成功事件。

## 6. 错误与隐私

冻结错误码：

- `TASK_NOT_FOUND`
- `TASK_DELETED`
- `TASK_REVISION_CONFLICT`
- `TASK_BOARD_CONFLICT`
- `TASK_INVALID_TRANSITION`
- `TASK_BLOCKED`
- `TASK_DEPENDENCY_INVALID`
- `TASK_DEPENDENCY_CYCLE`
- `TASK_CLAIM_CONFLICT`
- `TASK_RECOVERY_REQUIRED`
- `TASK_CAPABILITY_DENIED`
- `TASK_LIMIT_EXCEEDED`

诊断只允许携带安全 Task/Board identity、当前 revision 和有界 blocker IDs；不得回显 description、metadata、Prompt、Tool 参数/输出、路径或底层异常正文。

## 7. Tool/Pipeline 与生产边界

生产实现只注册四个 `ToolSource.BUILT_IN` Task Tool：

| Tool | Effect | 模型可提交字段 | 投影/约束 |
| --- | --- | --- | --- |
| `task_create` | `WRITE_SESSION_STATE` | subject、description、active_form、blocked_by、metadata | Board/actor/Session/Run/ID/owner/status 由宿主注入 |
| `task_update` | `WRITE_SESSION_STATE` | task_id、operation、对应 CAS/epoch/编辑字段 | flat schema；运行时按 operation 拒绝缺失字段、无关字段与字段混用；无 run_id；ASSIGN/REASSIGN 的 target_actor 必须通过宿主 `TaskActorDirectory` |
| `task_list` | `READ_SESSION_STATE` | status、filter、cursor、limit | 默认 25、最大 50、TaskId 稳定顺序；不返回 description/metadata/claim/timestamp；16KiB UTF-8 语义分页并返回 continuation cursor，不让 Pipeline 切断 JSON |
| `task_get` | `READ_SESSION_STATE` | task_id | 在同一 Board 临界区捕获 revision/detail；完整合法 JSON 的 UTF-8 上限为 16KiB，超限整体失败而不截断 |

Adapter 放在 `cc-java-core.task`，因为它直接适配 Core-owned `TaskListService`，不访问文件系统、网络、OS 或 Provider/Jackson 类型；Domain 仍只保存框架无关协议，FileSessionStore、stdio/stable 和 TUI 保持在架构边缘。

`metadata` 只接受 boolean、JSON-safe integer、string；`metadata_patch` 额外把显式 JSON `null` 转为领域 removal。`JsonObject` 用 `JsonNull.INSTANCE` 区分字段缺失与显式 null，Context estimator 将其按 null 计量；Provider、MCP 与 Session JSON 边界统一通过 `jsonValues()` 还原原生 null，Tool failure fingerprint 也以类型保真 canonical 形式处理该值。

默认 Permission 在 DEFAULT/PLAN/ACCEPT_EDITS 中允许这两个 Session state Effect，但 `DefaultHardDenialPolicy` 只放行上述名称、Effect 与 BUILT_IN source 的精确组合；Plugin/MCP 仿冒、名称/Effect 错配和任意其他 Session-state Tool 均 Hard Deny。显式 Deny 仍优先。四个 Tool 全部经过唯一 `ToolExecutionPipeline`、Lifecycle、Journal seam 与 PRE_TOOL/POST_TOOL Hook，没有平行 Task mutation 通道。为使 child 生产链可达，只有精确 BUILT_IN `delegate_agent` + `SYSTEM_OR_DESTRUCTIVE` 从不可覆盖 Hard Denial 收窄为 DEFAULT/ACCEPT_EDITS 下的 `ASK`；PLAN、显式 Deny、其他名称和其他来源继续拒绝。

Session JSONL 对每次成功 mutation 只保存命令、可信 actor identity、Board revision/high-water/tombstone 和 changed Task delta；replay 在线性扫描中重建完整快照与幂等索引。Fork seed 只写一次完整快照。该设计避免 256 个大 Task 的 4096 次 mutation 产生二次方日志膨胀；256 个 4 KiB description 的回归文件小于 4 MiB，并可 Resume 为 256 项。

stable v1 通过 wire 名 `task-list-v1` 协商，只读 `task.snapshot` 支持 TaskId cursor、默认 25/最大 50 和 canonical revision；mutation 仍只通过模型 Tool/Pipeline。内部 stdio `/tasks` 返回活动任务与最近五个完成项的有界投影。每次成功 `task_create/task_update` 必须先发布 `tool.completed`，再由同一 stdio writer 发布 Java 权威 `task.board.snapshot`；失败 mutation 不发布快照。事件绑定当前 Session/Run 且携带单调 Board revision，TUI 拒绝错归属和迟到/重复 revision。

自动快照只打开非聚焦 Ink live region，不拦截 Composer、Steering、Approval、Question 或 Plan Review；显式 `/tasks` 才聚焦并启用 ↑/↓、Enter、Esc。面板按“需恢复、进行中、可执行 pending、blocked pending、最近完成”排序；采用紧凑无边框行，隐藏 Task ID/revision/owner 和 Java/Ink 等实现词。IN_PROGRESS 使用实心符号、加粗标题和下一行 dim activity；COMPLETED 通过独立装饰策略映射到真实 `strikethrough + dimColor` 并移除 activity。全部完成保留约 5 秒后只隐藏面板，Board 仍可 `/tasks` 重开。所有行按 terminal display width 预算缩进、符号、活动省略号、CJK、emoji、combining sequence 和依赖/恢复后缀。

普通复杂 Run 在 durable Task Tool 已注册时获得四个 Tool 和 Task 指导。批准 Plan 则由 `ApprovedPlanTaskSeeder` 只读取唯一明确命名的 `拟定步骤/实施步骤/执行步骤` 或受控英文等价 heading 下的顶层有序列表；忽略其他编号列表、代码 fence 和嵌套 heading，零候选保持 legacy，多个候选 Fail Closed。应用在 Runtime 生成真实 RunId、canonical Run 已开始且取得 active ownership后、首个模型回合前幂等创建 Task；metadata 绑定 planId、content digest、稳定的 `ExecutionBrief.approvedRevision` 和 step ordinal。执行模型只获得 `task_list/task_get/task_update`，`task_create` 从 definitions 移除；其中同名 `task_update` 被窄 Adapter 替换，只接受 `task_id/status/active_form`，revision、claim epoch、Plan identity、Session/Run 与 actor capability 均由宿主注入，因此不能另建英文标题、改序、合并、拆分或编辑批准步骤。内部多阶段 mutation call ID 由 Provider call ID 与 phase 做 SHA-256 稳定派生，兼顾长 Unicode ID 上限与 partial retry 幂等。初始 PENDING snapshot 在 `run.started` 后、首个模型 Tool 前由同一 stdio writer 发布。

Plan controller 在每个模型请求前检查当前 Plan identity 的 Task 数量、顺序、正文、COMPLETED、blocked/recovery 及确定性 Evidence。全部满足后单向进入一次 final-only turn，移除所有 Tool definition；若模型仍发 Tool Call，Runtime 在执行前以 `INVALID_MODEL_RESPONSE` 终止。最终 `PlanStatus.COMPLETED` 同时要求权威 Task 和 Evidence；未满足时沿既有有界纠正路径处理，不合成成功。这样关闭“任务已全完成但模型继续 list/update，最终 `time_limit_reached`”缺口，同时保持 Task 不构成审批或 Evidence 本身。普通 Run 的 Task terminal advisory 语义不变。

## 8. 可证伪验证

Batch A-E 确定性测试覆盖：

1. 自依赖、缺失依赖、重复边与深层环在提交前拒绝；
2. blocked claim/complete、删除存在入边任务、非法 reopen/transition 均不改变 revision；
3. completed blocker reopen 后下游重新 blocked，blocks 投影与 canonical blockedBy 一致；
4. tombstone 后 ID 不复用，live/边/文本/metadata 所有边界均有正负例；
5. board/task CAS 冲突、并发 claim 仅一次成功；
6. 旧 claim epoch、非 recovery resume、Child 越权与跨 Board capability 全部拒绝；
7. 同 actor/session/run/callId 同参数重试返回原成功结果，不同参数拒绝；4096 次成功 mutation 后已存重试仍返回，新调用不推进 revision；
8. terminated Run 只派生 recoveryRequired，不自动 replay/release/complete；CLAIM/RESUME_CLAIM 的 RunId 只能来自 capability；
9. 无序插入的所有外部 Map/Set 均产生确定顺序；文本控制字符、语义 no-op 与 COMPLETED→PENDING 的无关 expectedClaimEpoch 均在提交前拒绝；
10. 四个 schema 的名称、Effect、Source、required/additionalProperties、operation-specific 字段、Unicode/大小与 closed metadata 验证；
11. List 默认 25/最大 50、cursor/filter/稳定顺序、摘要/详情分层、List 16KiB 语义分页、Get 完整 JSON 16KiB Gate 与原子 Board revision；
12. DEFAULT/PLAN/ACCEPT_EDITS、显式 Deny、Plugin/MCP spoof、Registry collision、child scope、所有 `TASK_*` 错误映射和隐私字段排除；
13. 同一 Pipeline 的 callId/幂等、PRE_TOOL/POST_TOOL、拒绝/失败与完成 mutation 后 Hook 可见性。
14. 增量 journal 的 256×4 KiB 容量、Resume/Fork、terminated claim recovery 与损坏尾部完整前缀；
15. stable `task-list-v1` negotiate/snapshot/cursor/idempotency，stdio `/tasks` 与 terminal advisory；
16. 真实 production `task_create → delegate_agent(taskIds) → 独立 child Session → task_list`，以及 PLAN/显式 Deny/Plugin spoof 负例；
17. TUI `/tasks` parser、分组排序、选择/详情/关闭、completed 删除线代码路径及 final 文本不被 advisory 覆盖。
18. 成功 mutation 的 `tool.completed → task.board.snapshot` 顺序、失败 mutation 不推送、错 Session/Run 与旧 revision 丢弃、自动面板不抢 Steering；
19. 真实 Java stdio→Ink E2E 覆盖普通复杂任务与批准 Plan 执行的 `PENDING → IN_PROGRESS → COMPLETED`，并直接断言 COMPLETED 装饰策略为 `strikethrough=true/dimColor=true`。
20. 中文批准步骤与 18 项城市编号列表并存时只 seed 步骤；英文受控 heading、零/多 section、代码 fence、嵌套 heading、部分 seed、旧 revision、unrelated Task 和 recovery 均有正负例；
21. Runtime initializer 使用真实执行 RunId，失败产生配对 `INTERNAL_ERROR` 终态且释放 ownership；权威 Task+Evidence 满足后下一请求为零 Tool final-only，违规 Tool Call 不执行；
22. 真实 Java→stdio→Ink E2E 断言中文标题、无 `task_create`、初始 snapshot 先于首个 Tool、两个步骤完整状态迁移、Run `completed` 与 durable Plan `COMPLETED`；20/24/240 列渲染覆盖 CJK/emoji/combining/依赖/恢复且相关整行不越界。
23. 五步中文批准 Plan 在真实临时 Workspace 中通过三个独立 `run_command` 生成并校验最小 OpenXML XLSX、执行超过一秒的质量检查，逐项观察 PENDING/IN_PROGRESS/COMPLETED；另一个真实命令 timeout 场景验证 `operation_timed_out`、Run terminal pending/recovery 计数、终止后 `/tasks` 权威投影与 Ink 恢复提示一致。stdio strict codec 必须与 dispatcher 同步接受无参数 `tasks` intent。
24. 批准 execution 的公开 `task_update` schema 不包含 operation/revision/claim/metadata；128 个非 BMP code point 的 Provider call ID 可完成 active edit、claim、幂等重放与 complete 而不漂移 revision。React 节点测试直接断言 IN_PROGRESS bold/dim activity、COMPLETED dim/strikethrough、活动行消失及中文窄宽度。

参考实现不是 Golden Output；测试只断言本 ADR 的独立状态机、Tool/Pipeline 与资源契约。

## 9. 结果与剩余边界

Batch A-E 已完成并通过 G0-G6 capability 对账，`TASK-01..05` 提升到 L2。Task List 已成为 Session-local、可持久恢复、可由模型和 TUI 使用的生产能力；Plan 仍是独立审批工件，唯一显式步骤 section 的单向 seed 不允许 Task 反向审批 Plan，也不替代 Evidence。

2026-08-25 corrective review 发现初版只具备 `/tasks` 查询面板与分层组件测试，未把生产 mutation 实时推送到 Ink；首次真实 Plan E2E 又证伪 execution scope 丢失 Task Tool，出现 `unknown_tool`。Commit `0a7a4a1149371ab0e52c68838cf453cf37d2a8f1` 增加权威快照事件、非聚焦 live region、复杂任务指导、Plan execution tool 保留和跨层 E2E；该修正关闭交互证据缺口，不改变既有 L2 等级或 S15 Exit。

同日第二次深度交互审查由用户真实运行证伪三个剩余假设：模型可重新创建英文 Task、全宽面板与技术词泄漏不符合紧凑交互、Task/Evidence 已完成后仍可能循环至 `time_limit_reached`。实现 Commit `dd1e8885555f0b538c535f63148068e9694f121d` 引入批准步骤的应用权威 seed、真实 Run initializer、Task+Evidence final-only Gate、紧凑宽度安全 Surface 与 5 秒完成态。该 corrective 细化 `TASK-05` 的 L2 行为，不提升 Capability Level，S15 Exit 仍 OPEN。

第三次验收审查发现原跨层 `.xlsx` 证据只是扩展名文本，并且 TypeScript Client/Java dispatcher 已声明 `/tasks`，strict codec 却遗漏 `tasks` intent，导致 Run terminal 后恢复查询返回 `protocol.error`。本变更以测试 classpath 中独立 JDK 进程生成/校验真实 OpenXML ZIP package，增加长耗时成功路径与真实 command timeout；同时补齐 codec allowlist 与负参数边界。该修正关闭真实性和协议收敛缺口，`TASK-01..05` 仍维持 L2，S15 Exit 仍 OPEN。

第四次真实验收继续证伪两个跨层假设：通用 `task_update` 要求模型维护 operation/revision/claim，导致模型只 list 不逐项更新；交互式复杂 Run 的 5m 默认绝对 deadline 又会在首次命令失败和修补后取消第二次命令。修正后批准 execution 使用简单三态 Adapter，Java 注入可信并发/身份字段，Ink 自动投影弱化 activity 与完成划线；CLI 默认 Run deadline 提升到既有有界上限 30m，显式 override 仍为硬限制。真实 XLSX 与 timeout E2E、长 Unicode 幂等测试和 React 样式节点断言共同关闭该缺口；Capability Level 不变。

以下不因本 ADR 提升：`SUB-11` Team Task Board、跨 root Session 共享、peer messaging、文件 watch/poll、跨进程订阅、离线 owner reclaim、时间 lease、自动领取和远程 worker；stable v1 目前提供协商与分页 snapshot，未提供跨连接 push subscription。S15 Stage Exit 仍由 PERM-05/PLAN-01/双 Provider/L4 A/B Eval 等既有 blocker 决定，保持 OPEN。
