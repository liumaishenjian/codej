# ADR-079：S15 可选总量限制与类型化 Tool 失败治理

- Status: Accepted（2026-08-26 修订）
- Date: 2026-08-21
- Stage: S15 Independent Innovation（Batch 4）
- Feature IDs: `LOOP-07`、`LOOP-08`、`TOOL-10`、`TOOL-13`、`TOOL-18`、`PERM-05`、`OBS-04`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 授权快照机制为 `Observed / Inferred / Unknown`；本项目契约为 `Documented`；能力等级不变

## 背景

历史默认预算把普通交互 Run 固定在 16 个模型回合、32 个 Tool Call 和一个总墙钟 deadline。
随后引入的 `INTERACTIVE_ADAPTIVE` 又把这些数值改成软检查点并设置 128/256 absolute ceiling。
真实 Provider 上的批准 Plan 执行证伪了这两种设计：任务仍在合法推进时，隐式总次数或总 deadline
都可能先于用户、Provider 单请求 timeout 或 Tool 单次 timeout 终止 Run。

与此同时，失败仍需要稳定治理：Runtime 必须识别“同一 Tool、同一规范参数、同一失败类别”的
重复请求，Web 403、命令非零退出和传输故障也需要统一的跨 Adapter 失败语义。

## 受控研究结论

本轮在仓库外只读复核了授权快照中的交互主循环、可选最大回合、模型与网络重试、Web Fetch/Search
HTTP 失败、Shell 退出状态及 Task Update。只提炼职责、状态、不变量和验证方法，未复制函数体、
Prompt、注释、错误文案、私有名称、布局、常量、Fixture 或字节。

| 结论 | 分类 | 本项目采纳 |
| --- | --- | --- |
| 交互主循环只在调用方提供 `maxTurns` 时检查总回合；Print 可显式提供 hard limit，普通交互不应伪造一个默认值 | Observed | `AgentLimits` 分别以 optional 表达总模型回合、总 Tool 次数与 Run deadline；普通 Interactive/Plan/approved-plan 三者均 absent |
| 模型重试、Tool 重试和模型再次提出同一 Tool 是三种不同机制 | Observed | Adapter 只重试瞬态传输/HTTP；Runtime 对跨模型回合重复 Tool 失败做 fingerprint 治理 |
| Web Fetch 能观察 HTTP 状态与部分受信响应头，但认证、UA/ACL 和普通 forbidden 并非总能可靠区分 | Observed / Unknown | 403 统一为非重试 `HTTP_FORBIDDEN`，仅在受信 Adapter 信号存在时附加安全 reason code |
| 429 与服务端错误可以在网络 Adapter 层有界退避；授权、权限和普通 4xx 不应盲重试 | Observed / Inferred | Web Adapter 使用可注入等待器和固定尝试上限；Runtime 不重复实现 HTTP backoff |
| Shell 非零退出是进程级失败证据，不能靠 stdout/stderr 文本猜测 | Observed / Inferred | `run_command` 非零退出映射 `PROCESS_EXIT`；Runtime 不抓取或解析自由文本来猜状态 |
| 参考快照主要通过模型反馈要求改变策略；未观察到可直接采纳的通用失败 fingerprint 状态机 | Unknown | fingerprint coordinator 是本项目独立、可证伪治理，不声明为参考内部机制 |

## 决策

### 1. 三个总量维度显式建模 absence

`AgentLimits` 只保存三项调用方 hard limit：

- `OptionalInt totalModelTurns`；
- `OptionalInt totalToolCalls`；
- `Optional<Duration> runDeadline`。

兼容构造器把既有整数与 Duration 映射为 present，供 Print、API、SDK、Daemon 和测试继续精确执行。
`AgentLimits.interactive()` 返回三个维度均 absent 的值；不存在 `AgentBudgetPolicy`、adaptive soft
checkpoint、续租、absolute ceiling 或 placeholder/sentinel。

Core 只能依据 presence 决定是否检查次数、创建 deadline cancellation source 或启动 Run deadline
线程。`LifecycleEvent.BudgetGoverned` 同样用 optional limit 投影，且原因只保留可达的
`EXPLICIT_LIMIT`。普通 Interactive、Plan 与 approved-plan 不产生数量治理事件，也不会因为
Headless options 中用于 Print/Provider 的默认 `30m` 而获得总 Run deadline。

### 2. Surface 与 timeout 边界

- `--print` 继续使用显式 16/32 与调用方 `--timeout`；默认 `30m`，launcher、Java CLI 和帮助一致；
- 完整 API/SDK `AgentRunRequest` 精确消费每个 present hard limit；
- Interactive、Plan、approved-plan 调用 `RunScopedModelGateway.openRun()`，不传总 Run budget；
- Provider 单次请求 timeout、Tool definition/调用的单次 timeout、用户取消和进程清理保持独立；
- 重复失败熔断保持：连续两批仅含 `REPEATED_FAILURE` 时以 Tool error 收敛，避免无界交互死循环。

### 3. Tool 失败分类

`ToolError` 的 `ToolFailureCategory` 与 `retryable` 至少覆盖 `AUTHORIZATION`、`PERMISSION`、
`HTTP_FORBIDDEN`、`HTTP_CLIENT`、`HTTP_RATE_LIMIT`、`HTTP_SERVER`、`TRANSPORT`、
`PROCESS_EXIT`、`VALIDATION`、`EXECUTION`、`CANCELLATION`、`TIMEOUT`、`OUTPUT_LIMIT`、
`PROTOCOL`、`INTERNAL`。retryable 只由 Domain 能独立证明的分类和 Adapter 明确信号设置；
403、授权、权限、校验和进程退出默认不可重试。

### 4. 重复失败 fingerprint

每个 Run 拥有独立 coordinator。fingerprint 由规范 Tool 名、递归排序且类型保真的 JSON 参数摘要、
以及 typed failure category 组成，不读取错误 prose、stdout/stderr、网页正文或 Secret。第一次失败
正常反馈；同 fingerprint 再次出现时，Runtime 不执行 Adapter，返回 `REPEATED_FAILURE` 与结构化
strategy feedback。Gate 位于 Pipeline 参数校验之后、Pre Hook/Permission/执行之前，因此不会再次
出站、启动进程或请求审批，同时仍生成唯一 Tool Result、Call ID 和生命周期终态。

### 5. Web、Process 与 AutoReview

Web Adapter 对 403 映射 `HTTP_FORBIDDEN` 且不重试；429 和 5xx 只在 Adapter 内以共享单请求
期限/cancel、固定最大尝试、封顶退避和可注入 sleeper 重试。`run_command` 非零退出返回
`FAILURE / PROCESS_EXIT`，details 仅含数值 exit code。AutoReview 不能覆盖 Hard Denial、显式 deny、
source/trust 不匹配、Hook deny 或 repeated-failure Gate。

## 可证伪验证

- 普通 Interactive 完成 130 个模型回合、129 次 Tool Call，不出现默认计数终止或 budget event；
- 显式总模型回合/Tool 次数仍在精确边界停止，超大 Tool batch 不部分执行；
- Headless 普通 Run 的 `RunScope` 观察到 absent budget，Print/API 显式请求观察到准确 Duration；
- 真实 approved-plan fixture 把 Headless options timeout 收窄到 `100ms`，但含 1.2 秒 Tool 的整次执行
  仍完成；`run_command timeoutSeconds=20` 继续证明 Tool 单次 timeout 独立；
- 相同 Tool+规范参数+失败类别第一次执行、后续阻断，改变参数后允许；
- 429/5xx 的尝试数、退避和取消由 Fake clock/sleeper 确定性验证；403 尝试数恒为 1；
- `run_command` 非零退出是 `FAILURE/PROCESS_EXIT`，输出裁剪、取消和 Windows 进程树清理回归不变。

## 明确差距

Web Fetch 尚未作为独立生产 Tool 接入；真实站点对 403 的原因经常不可观察，不能凭正文或品牌文案
猜测。本轮不建设全局跨 Session failure cache、Provider 自动切换或 OS 网络 Sandbox。普通交互无
隐式总量不等于无限资源：Context/Token、单请求/单 Tool timeout、取消、输出上限、权限、审批与重复
失败熔断仍有效；能力等级保持不变。
