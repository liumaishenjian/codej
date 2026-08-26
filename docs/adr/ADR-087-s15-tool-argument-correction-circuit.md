# ADR-087：S15 Tool 参数纠错与重复失败熔断

- Status: Accepted
- Date: 2026-08-23
- Stage: S15 Independent Innovation（生产正确性修复）
- Feature IDs: `TOOL-01`、`TOOL-03`、`TOOL-05`、`TOOL-13`、`LOOP-05`、`LOOP-07`、`CLI-04`、`OBS-04`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 授权快照机制为 `Observed / Inferred / Unknown`；本项目契约为 `Documented`；Capability Level 不变

## 背景

真实交互 Session 暴露了一个跨层契约缺陷：`search_text` 的公开 Schema 同时声明规范参数
`limit` 与旧兼容参数 `maxResults`，而 Tool validator 拒绝两者共存。模型依据公开契约同时发送
两个字段后，只收到泛化的参数校验失败；由于校验失败在既有 failure fingerprint Gate 之前返回，
同一调用可以跨模型回合持续重复；当时只能依赖后来已废弃的 adaptive budget `NO_PROGRESS` 终止。
TUI 又把连续失败折叠为一个缺少纠错说明的次数摘要，掩盖了 Runtime 已经停滞的事实。

本修复不改变 Tool Pipeline、多 Tool Call、Permission、Approval 或 Capability Level。目标是让公开
契约、兼容解析、模型纠错、Run 内失败治理和终端投影形成一个可证伪闭环。

## 受控研究结论

本轮在仓库外只读复核了授权快照的 Grep 输入 Schema、Tool 输入解析/值校验、Tool Result 配对和
连续 Read/Search 聚合。只提炼职责、状态、不变量和验证方法，未复制函数体、Prompt、注释、错误
文案、私有名称、文件布局、常量、Fixture 或源码字节。

| 结论 | 分类 | 本项目采纳或偏差 |
| --- | --- | --- |
| 搜索 Tool 对模型公开一组单义的分页参数；输入 Schema 使用严格对象约束，类型不符在 Permission 和执行前失败 | Observed | `search_text` 只公开规范 `limit`；旧 `maxResults` 仅留在兼容 validator/executor，不继续进入模型 Schema |
| 类型校验失败会把具体字段问题返回到对应 `tool_use_id`，值校验也在 Permission 前返回可操作原因 | Observed | 保留每个 Call ID 精确一个失败 Result，并增加结构化、隐私安全的修正动作；不只返回泛化 message |
| 某些 Schema 缺失场景会追加确定性的恢复提示，而不是要求模型从原始异常自行猜测下一步 | Observed | Tool validation 结果可以携带固定字段的 correction details；Provider 映射继续投影现有结构化 ToolError |
| 连续 Read/Search 会折叠为活动摘要，但失败状态仍可独立观察 | Observed | stdio 只白名单投影 `argumentChangeRequired` / `strategyChangeRequired`，TUI 使用项目自有中文固定文案 |
| 未观察到可直接采纳的通用“同一 invalid args 多回合熔断”状态机或阈值 | Unknown | Run 内 fingerprint 与连续 repeated-only batch 熔断是本项目独立治理，使用 Fake 测试证伪 |
| 参考实现内部是否兼容历史搜索字段、如何迁移旧 transcript，材料不足以确认 | Unknown | 本项目明确偏差：接受单独旧 `maxResults`，但 advertised Schema 不再宣传它 |

## 决策

### 1. 分离 advertised Schema 与 legacy parser

`search_text` 的 Tool Definition 只声明规范分页字段 `limit`。validator 的允许字段集合和 executor
继续接受单独的 `maxResults`，用于恢复旧 Session、历史调用和兼容客户端；两者同时出现仍失败，
并明确要求删除 `maxResults`、保留 `limit`。这项兼容不意味着旧字段仍是推荐或公开契约。

Schema 测试必须证明公开 properties 不含 `maxResults`，同时执行测试证明单独旧字段仍生效。

### 2. 参数校验结果携带安全纠错元数据

`ToolValidationResult` 在 violations 之外增加不可变 `details`，并为需要 Run 内治理的确定性错误增加
独立 `correctionSignature`。details 只能包含 Tool 自己生成的封闭、可公开元数据；signature 只描述稳定的
violation/correction 形状，不投影给模型或 Session。两者都不得复制完整参数、query、path、文件正文、
Secret 或底层异常。Pipeline 对所有 invalid result 固定补充：

- `argumentChangeRequired=true`；
- `retrySameArguments=false`；
- 有界 `violations`；
- Tool 提供的安全字段，例如 `conflictingFields`、`preferredField`、`removeFields`。

模型第一次失败即可获得“改什么”的信息；Provider Adapter 继续使用统一 ToolError 映射，不建立第二套
自由文本错误协议。

### 3. validation failure 使用 exact + correction shape 两层治理

Run governance 在找到 Tool 后、首次 validation 前取得。第一次参数校验失败仍正常返回
`INVALID_ARGUMENTS / VALIDATION / retryable=false`，并原子记录两层状态：

1. **exact arguments 层**：所有 invalid 都记录 Tool 名 + canonical arguments SHA-256 + typed category；
   不保存原始值。即使 Tool 只返回 generic `ToolValidationResult.invalid()`、没有安全 signature，相同 Tool 与
   相同 arguments 的后续调用也会在再次 validation 前返回 `REPEATED_FAILURE`；
2. **correction shape 层**：确定性 validator 可额外声明安全 `correctionSignature`。Pipeline 只哈希 Tool 名与
   规范 signature；query、path 或其他无关业务参数变化，但 violation/correction 形状未变时仍要求真正改变
   纠错策略。signature 正文、原始 arguments 和业务参数值都不保存、不投影，Pipeline 也不从 violation prose
   猜测 shape。

`recordValidationFailureOrRepeated` 在一个同步临界区内检查并写入 exact 与可选 shape，保证首次调用返回
完整 actionable `INVALID_ARGUMENTS`；并行 read batch 中同 shape 只有一个调用获得首次反馈，其余稳定结算为
repeated，同时保留全部 Call ID。同一 Tool 从 conflict A 改成 invalid B 会得到 B 的首次反馈；真正删除冲突
字段并通过 validation 则正常进入 Hook、Permission、Approval 和 Adapter。执行阶段的既有 exact-arguments
fingerprint 继续治理 403、process exit 等 typed failure，并与 validation shape 层并存。

### 4. repeated-only batch 使用有界 Run 熔断

仅靠每次返回 `REPEATED_FAILURE` 仍可能让模型重复消耗回合。`AgentRunState` 因此追踪连续“整批结果均为
`REPEATED_FAILURE`”的批次：

- 第一个 repeated-only batch 仍完整追加所有 Tool Result，让模型获得最后一次策略提示；
- 第二个连续 repeated-only batch 在结果全部按 Call ID 配对并进入规范历史后，以既有
  `StopReason.TOOL_ERROR` 终止 Run；
- 任一成功、不同失败、变参后的首次 validation failure 或混合 batch 都重置该计数。

该阈值让单调用停滞最多经历“首次 typed failure + 两个 repeated-only batch”。文档初版对比的
16/24 回合软预算属于现已废弃的历史设计；当前普通交互没有隐式总回合数，因此此熔断是防止确定性
失败死循环的独立安全边界。同时它不截断一个多 Tool Call batch，也不制造孤立 Assistant Tool Call。

复核 `AgentRunState` 后，本 ADR 不增加“失败占优”规则：混合 batch 中至少一个真实 success 会重置
repeated-only 计数。该熔断只约束 repeated-only 死循环；普通交互没有 128/256 隐式 ceiling，混合批次
仍由用户取消、Provider 单请求 timeout、Tool 单次 timeout、Context/Token/输出上限、Permission 与审批等
正交边界治理。若未来要限制“1 success + 大量 repeated failure”的成本，应作为独立显式预算与 Eval
决策，而不是在本次 validation correction 缺陷中悄悄改变成功语义。

### 5. stdio/TUI 只投影白名单动作

Java stdio `tool.failed` 不发送完整 ToolError details，只从 boolean 白名单字段投影：

- `argumentChangeRequired`；
- `strategyChangeRequired`。

TUI reducer 忽略未知字段并保存这两个 boolean。连续聚合仍保留调用次数和失败数，但在对应条件下显示
“需要修改参数”或“已阻止相同失败重试”，不再让 `×N` 成为唯一解释。TUI 不解析 violation prose，
不显示原始参数或 Tool Result 正文。

## 可证伪验证

1. `search_text` Schema 只含 `limit`，单独 `maxResults` 仍能限制结果；二者共存返回结构化修正动作；
2. Scripted Model 第一次发送冲突字段，看到 actionable Tool Result 后下一回合仅用 `limit` 并完成；
3. 模型连续三回合改变 query、但保留同一 `limit/maxResults` conflict 时，第三个模型批次的全部
   Tool Result（含多 Call ID）配对后以 `TOOL_ERROR` 终止，不依赖任何总回合 ceiling；
4. 同一 Tool 从 conflict A 改成 invalid B 时收到 B 的首次反馈；真正修正冲突后允许执行；并行同 signature
   只有一个首次记录且全部 Call ID 保持一一配对；
5. Spring AI 映射包含修正字段但不包含原始 query/path/Secret；
6. stdio/TUI 测试证明失败摘要显示参数修正/策略变化，混合失败后成功的既有聚合语义不回归；
7. Maven 聚焦测试、TUI test/build、Dashboard `--check` 与 `--self-test` 共同通过。

## 明确差距

本修复不建设全局 Schema DSL、跨 Session failure cache、自动改写模型参数、Provider 特定 Prompt、远程
Tool 重试协调或 OS Sandbox。旧 `maxResults` 的最终移除需要单独版本化迁移和公开兼容决策；本次只停止
继续宣传它。Capability Level 保持不变。
