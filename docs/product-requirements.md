# codej 产品需求文档

> 文档状态：Draft v0.9
>
> 最后更新：2026-08-17
>
> 当前阶段：S01-S08 已 Accepted；S08 ADR-048 的既有能力已在 Commit
> `8fabd94b66881a4a8236cccabd4ae61dd39845d4` 上 Accepted，ADR-049 的显式文件引用又在实现
> Commit `5910a8f` 上完成 Commit-scoped G0-G6；`CLI-13`、`CTX-19` 达到 L2。S09 已完成
> Settings/Trust、Command/loopback HTTP、生命周期、Compact 与生产装配并 Accepted；S10 MCP Tool
> 主链已完成 STDIO/Streamable HTTP、多 Server、统一 Permission、Trust 与恢复并通过真实 E2E，Accepted。
> S11 Skills + Plugins 已在实现 Commit `71278431dd1e5c7c4e279b44f43e084755502a5d` 上完成 Commit-scoped G0-G6，量化、安全矩阵、Demo 与能力对账均通过，Stage Exit Accepted。S12 已在实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上完成 Commit-scoped G0-G6 与 Stage Exit Accepted；相关 Feature 达到冻结的 L2/L1 目标。S13 已在实现 Commit `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3` 上完成 Commit-scoped G0-G6 与 Stage Exit Accepted：ExecutionBackend/五维 policy、WSL2+bwrap Linux A、Docker pinned-image B、Windows process/env B（file/network U）、攻击回归、Command Hook/MCP stdio managed seam 与 root/child execution composition 均已验证。S14 已在实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 上完成 Commit-scoped G0-G6，Stage Exit Accepted with documented deviations。S15 已完成 `PERM-05` 的 Headless、stdio 与 React/Ink 三选 picker 受限生产接线及离线 Fake/E2E，达到 L1；真实 Provider 误放行率、延迟、成本与 A/B Eval 仍缺，S15 保持 IN_PROGRESS/OPEN。`CFG-07/HOOK-10` 保持 L1，`SEC-11` 保持 L0。
>
> 产品负责人：项目维护者

## 1. 定位演进

- v0.1 将项目过度绑定在自动 FixBug 场景上；
- v0.2 修正为通用 Java Coding Agent Runtime 与 CLI；
- v0.3 进一步明确：这是一个参考驱动的学习型 Java 重实现项目。
- v0.4 引入公开行为基线和统一 Stage 证据 Gate。
- v0.5 明确跨 Stage 目标等级、前置依赖、CLI 归属和 S07 渐进式 Context 路线。
- v0.6 隔离来源、Revision、许可证和授权范围不可核验的材料，撤销其活动设计结论；
  同时固定 S02 的 23 项启动范围。
- v0.7 根据维护者授权确认恢复受控源码学习，并将 S02 调整为 Java Headless Runtime、
  内部 stdio v0 与 React/Ink TUI 的 24 项范围。

最终定位：

> `codej` 是一个以 Java 独立实现 Agent Runtime、用成熟终端前端技术提供 CLI 的
> 通用 Coding Agent 学习项目。

项目先把成熟 Coding Agent 拆成完整能力地图，再按子系统独立重实现：Agent 语义、控制流、
工具和安全边界由 Java 承担；交互 Surface 可以使用更适合终端 UI 的技术。每个阶段都维护
公开行为基线、本项目设计、测试和差距，形成可重复证据后再进入独立创新。

它不是“只做一个 MVP 就自由生长”，也不是逐行翻译任何参考源码。它要在授权和发布边界内完成：

```text
建立参考基线
→ 理解架构问题
→ Java Runtime 与独立 Surface 重实现
→ 行为对照
→ 补齐差距
→ 基于证据创新
```

FixBug、代码审查、测试生成和日志调查只是建立在 Runtime 之上的用例，不进入核心领域模型。

## 2. 产品愿景

用户进入任意代码仓库后，可以直接启动 `cc-java`，用自然语言委托开发任务：

```text
cd my-project
cc-java

> 阅读这个项目，给订单创建接口增加幂等保护，并运行相关测试
```

CLI 应当能够：

1. 收集项目上下文；
2. 自主选择并调用工具；
3. 在有副作用的操作前执行权限判断；
4. 展示过程、请求批准并允许用户取消；
5. 修改代码并运行验证；
6. 输出结果、证据、风险和未完成项；
7. 在后续版本中恢复会话、加载扩展和委托子 Agent。

项目同时维护 [参考架构研究](./reference-architecture.md) 和 [功能对照矩阵](./feature-parity-matrix.md)。因此任何时候都可以回答：

- 当前 Java 版本处于哪个学习阶段；
- 与参考基线相比缺少哪些能力；
- 每项差距解决什么真实问题；
- 下一项实现如何被验证；
- 哪些设计已经从“复现”进入“创新”。

## 3. 为什么选择 Java

- 为 Java/Spring 开发者提供可读、可调试、可扩展的 Agent 工程参考；
- 用 Spring AI 接入模型，但不把 Runtime 绑定到某个模型 SDK；
- 验证 Java 在流式终端、工具执行、权限控制、会话和 MCP 领域的工程能力；
- 形成区别于 TypeScript/Python Agent 项目的开源作品。

项目不是为了证明“Java 可以翻译某份 TypeScript 源码”，而是用 Java 独立实现成熟 Coding Agent 中可复用的 Harness 设计。

## 4. 产品目标

### 4.1 核心目标

- G-001：提供可交互和非交互的通用 Agent CLI。
- G-002：实现由项目自身控制的 Agent Loop，而不是把完整循环交给 Spring AI。
- G-003：提供统一 Tool Runtime，使内置工具、MCP 工具和未来插件经过同一执行管线。
- G-004：把权限、审批、限制、取消和生命周期事件作为 Runtime 基础能力。
- G-005：支持从简单内存上下文演进到可恢复会话、压缩和持久记忆。
- G-006：保持模型、终端、工具和存储适配器可替换。
- G-007：维护版本化参考基线和逐项 Capability Parity。
- G-008：每个 Stage 都包含来源记录、机制研究、设计说明、代码、测试、Demo 和差距复盘。
- G-009：所有公开参考结论区分 `Documented / Observed / Inferred / Unknown`，所有能力
  声明区分参考行为、Java 设计和已验证实现。

### 4.2 学习与开源目标

- G-010：通过可运行代码掌握 Agent Loop、Tool Calling、Context Engineering 和 Harness Engineering。
- G-011：保留架构决策、评测和演进记录，让项目能作为 Java Agent 学习材料。
- G-012：提供一套能被其他 Java 项目嵌入的 Runtime 基础。
- G-013：只使用已登记的公开来源、授权研究输入和独立行为场景进行重实现，不复制或逐行
  翻译受保护的源码表达。
- G-014：关键模块必须由维护者能够独立解释，而不只是由 AI 生成。

## 5. 非目标

- 不追求首版与 Claude Code、Codex 或其他成熟产品功能对等；
- 不使用、改写或再发布泄露、未授权或超出授权范围的源码；
- 不兼容某个商业产品的私有配置格式、内部事件格式或隐藏 API；
- 不把 FixBug、测试或电商业务写进 Runtime Core；
- 不在第一轮重实现中同时完成生产级全功能 TUI、桌面端、IDE 插件或云端执行；S02 只完成
  支撑流式会话的最小 React/Ink TUI；
- 不跳过基础子系统直接堆叠多 Agent、插件市场或企业策略中心；
- 不承诺模型生成的修改一定正确；
- 不把 Spring AI 当作产品架构本身。

## 6. 目标用户

### 6.1 Java 后端开发者

希望在终端中委托代码解释、修改、测试、重构和排障任务，并能理解 Agent 的执行过程。

### 6.2 Agent 开发学习者

希望通过一个真实项目理解 Coding Agent 的 Runtime、Tool、Permission、Context、Session 和扩展系统。

### 6.3 Java Agent 平台开发者

希望复用 Runtime 或扩展接口，构建团队内部 Coding Agent、测试 Agent 或自动化场景。

## 7. 核心用户体验

### 7.1 交互模式

计划中的基础入口：

```text
cc-java [--workspace <path>]
```

行为：

- 默认以当前目录为 Workspace；
- 启动交互会话并显示当前模型、权限模式和 Workspace；
- 用户可以连续发送任务和补充信息；
- 模型文本和工具执行状态逐步显示；
- 有副作用的工具调用需要终端确认；
- `Ctrl+C` 取消当前模型或工具执行，但不立即退出会话；
- `/exit` 结束会话。

### 7.2 Print 模式

计划中的非交互入口：

```text
cc-java --print "解释订单创建流程"
cc-java --workspace . --model model-name --timeout 30s --print "解释订单创建流程"
```

用于脚本和 CI。首个 Print 实现不得弹出无法处理的交互审批；遇到未预授权写操作时应拒绝并返回明确退出码。

### 7.3 Agent 工作循环

用户看到的是一个连续过程，而不是固定业务流程：

```text
Gather context
→ Reason
→ Request tool
→ Permission / Approval
→ Execute
→ Observe result
→ Continue or finish
```

“调查、行动、验证”可以交替多次，Runtime 不预设任务一定是 FixBug。

## 8. 产品能力地图

| 能力域 | 职责 |
| --- | --- |
| CLI / Terminal | 参数、REPL、流式展示、审批、取消、退出码 |
| Agent Runtime | 消息循环、状态、终止、重试、预算 |
| Model | Provider 适配、流式文本、Tool Call、Usage |
| Tool Runtime | 注册、Schema、执行管线、结果裁剪、错误 |
| Permission | 模式、规则、审批、硬拒绝 |
| Context | 项目指令、消息、工具结果、Token 压力、压缩 |
| Session | Transcript、恢复、分叉、Checkpoint |
| Extensions | Hooks、Skills、MCP、Plugins |
| Advanced Agency | Sub-Agent、后台任务、Worktree、Sandbox |
| Observability | Agent Event、耗时、Token、费用、审计 |

## 9. 学习型重实现路线

| 阶段组 | Stage | 学习目标 |
| --- | --- | --- |
| 参考建模 | S00 | Harness 地图、公开行为基线、授权研究、术语、能力矩阵和来源规则 |
| 核心重实现 | S01-S04 | Agent Loop、Streaming CLI、Tools、Write/Command |
| 可靠性重实现 | S05-S08 | Permission、Session、Checkpoint、Context、Compaction、Instructions、Settings |
| 扩展重实现 | S09-S11 | Hooks、MCP、Skills、Plugins |
| 高级能力重实现 | S12-S14 | Sub-Agent、Worktree、Sandbox、Eval、SDK、发行 |
| 独立创新 | S15 | 在对照基线之上验证 Java/Spring 差异化 |

每个 Stage 的功能清单和完成定义见 [功能对照矩阵](./feature-parity-matrix.md)。

FixBug 可以在 S11 后实现为 Skill 或独立应用，也可以作为 S04 的测试任务，但不改变 Runtime 主线。

## 10. 第一轮 Java 重实现：S01-S04

第一轮不是项目终点，只是把 Agent Harness 的最小骨架变成一个可以观察和实验的运行系统。它分为四个学习增量。

### S01：Runtime Kernel

- 不接真实模型，先使用 Scripted Fake Model；
- 实现单模型回合和多轮 Tool Calling；
- 实现 Tool Execution Pipeline；
- 实现最小 Permission Gate 和 Approval Port；
- 实现 Agent Event、模型回合/Tool Call 数量限制和 Stop Reason；
- 只保留 Cancellation 扩展缝隙，不宣称模型流或子进程取消已经可用；
- 实现内存 Session 与追加式 Context；
- 完成离线协议测试。

### S02：Model 与 Streaming CLI

- 接入维护者提供的 OpenAI 兼容模型端点，Spring AI Adapter 仍保持 Provider-neutral
  Core 边界；
- Java Headless Composition Root 提供 `--print` 和实验性 `--stdio`；
- React/Ink TUI 拉起 Java 子进程并提供 Interactive Session；
- 内部 UTF-8 NDJSON v0 只承诺 S02 本地进程通信，不是稳定公共 API；
- 支持流式文本、Tool Call Chunk 聚合和执行状态展示；
- 支持模型流取消、不完整流、输出长度 finish reason、有界停止/续接、限流和 Usage 转换；
- S02 初始切片的重试只发生在第一个可见 Delta 前，最多三次并受 Run Deadline/取消约束；
  S15 ADR-084 已将 production 确定 route 加固为最多 10 retries/11 total attempts、指数退避+jitter、
  typed Retry-After 与 Provider-frame fence。已输出后的断流仍 Fail Closed，`length` 以明确停止结束；
- Windows 验证 `Ctrl+C`、TTY/非 TTY、中文宽字符、粘贴、Resize 和无孤儿进程；
- 用显式启用的真实 Provider E2E 验证 Adapter，但普通 CI 仍只使用 Fake。

### S03：Read Tools

- 提供 `list_files`、`read_file`、`search_text`、`git_status`、`git_diff`；
- `search_text` 生产路径采用受控 ripgrep，支持字面/正则、Glob/type、大小写、多行、
  上下文、content/files/count、offset/limit，并传播 Run 取消；rg 不可用时只允许
  语义等价的字面 content 子集降级，不把 RAG 冒充精确代码检索；
- 加载项目根 `AGENTS.md`；
- 建立 Workspace Realpath、Symlink/Junction、敏感文件和大小边界；
- 为 Tool Result 建立类型化上限、明确截断和可供后续 Context 使用的元数据；
- 对真实公开仓库完成代码理解任务。

### S04：Controlled Coding

- 增加 `apply_patch`；
- 增加 `run_command`；
- 写文件和执行命令默认请求用户批准；
- 展示将执行的命令或补丁摘要；
- 设置进程超时、输出上限和取消传播；
- 修改后展示 Git Diff；
- Agent 可以运行构建或测试并形成最终总结。
- 提供固定的安全 `PLAN` 行为：允许调查，拒绝写文件和有副作用命令；S05 已补充
  可配置模式、可信 Startup Rules 和 Session Allow。

S04 完成后，项目得到第一个可运行的 Mini Coding Agent CLI；随后继续按矩阵学习可靠性和扩展能力。

## 11. 第一轮闭环功能需求

### 11.1 CLI

- FR-CLI-001：不带任务启动时进入交互 REPL。
- FR-CLI-002：支持通过参数执行一次性 Print 任务。
- FR-CLI-003：默认 Workspace 是当前目录，也可显式指定。
- FR-CLI-004：启动时显示 Workspace、模型和权限模式。
- FR-CLI-005：模型文本、工具状态、审批和最终结果有可区分的终端表现。
- FR-CLI-006：`Ctrl+C` 可以取消当前运行，`/exit` 可以结束会话。
- FR-CLI-007：不同结束原因映射到稳定退出码。
- FR-CLI-008：交互 Composer 支持 Workspace-relative `@path` 与带引号空格路径的异步补全；
  TUI 不读取文件，候选只作为提示，提交必须由 Java 重新验证。迟到、重复、乱序或超限建议
  不得覆盖当前 token；接受建议只替换光标处活动 token，并保持 grapheme、多行、Paste、History
  与 Steering 语义。
- FR-CLI-009：主屏交互必须保留完整 transcript 供终端原生 scrollback/鼠标滚轮访问；不得用固定高度
  `overflow` 裁剪历史。若未来使用 alternate screen，必须提供真实虚拟 viewport 与鼠标滚动协议。
- FR-CLI-010：运行中展示确定性的模型回合阶段、受控 Tool 活动摘要和来源明确的 Token 数值。Provider
  Usage 与 Context 估算必须分开标注；不得展示、伪造或从模型 prose 猜测隐藏思维链。
- FR-CLI-011：Tool stdout/stderr 默认折叠但不得丢失通道、失败或退出事实；运行中可选择并展开详情，
  Run 完成后必须仍能从独立 live viewer 查看最近历史 Run 的所选 Tool 快照，且不得重绘已进入 Ink
  `Static` 的 native scrollback。重复诊断只按相邻、同通道、完整且文本等价压缩；大量异构活动必须
  有界折叠，并在汇总行保留失败、拒绝和截断计数。参数校验与重复失败只允许通过 Java 白名单 boolean
  投影确定性动作，TUI 必须显示“需要修改参数”或“已阻止相同失败重试”，不得只用 `×N` 隐藏停滞。

### 11.2 Agent Runtime

- FR-AGENT-001：Runtime 接收用户消息并发起模型回合。
- FR-AGENT-002：模型可以返回文本、一个或多个 Tool Call，或二者组合。
- FR-AGENT-003：Runtime 负责完整 Tool Loop，Spring AI Adapter 不得自动执行工具。
- FR-AGENT-004：同一模型回合的 Assistant Message 只能追加一次。
- FR-AGENT-005：每个 Tool Result 必须与 Tool Call ID 一一对应。
- FR-AGENT-006：总模型回合、总 Tool 次数与总 Run deadline 必须分别建模为可选调用方 hard limit；
  present 时必须精确执行。普通 Interactive/Default/Auto/Plan/approved-plan 三个维度均 absent，禁止
  软检查点、续租、absolute ceiling、placeholder 或默认 30 分钟总 deadline。用户取消、Provider/Tool
  单次 timeout、Context/Token/输出上限和连续 repeated-failure 熔断必须继续独立生效。
- FR-AGENT-007：S02 将取消传播到模型流；S04 将取消传播到正在运行的工具和子进程树。
- FR-AGENT-008：运行以明确 Stop Reason 结束，不能无限循环。连续两批全部为 `REPEATED_FAILURE` 时，
  必须先完整配对并追加当前批次的全部 Tool Result，再以 `TOOL_ERROR` 有界终止；任一不同结果重置熔断计数。

### 11.3 Model

- FR-MODEL-001：S02 只要求一个可运行 Provider，第二个 Provider 用于后续验证抽象。
- FR-MODEL-002：核心不出现 Spring AI 或 Provider SDK 类型。
- FR-MODEL-003：Adapter 支持文本增量事件，并在回合结束时返回聚合后的 Tool Call。
- FR-MODEL-004：模型异常、限流和无效响应转换成 Runtime 错误。
- FR-MODEL-005：Token Usage 不可用时允许缺省，但不得伪造。
- FR-MODEL-010（S15，`LOOP-08/09`、`MODEL-10`）：生产同 Provider route 默认最多 10 retries，
  即首次请求加最多十次重试、总计 11 attempts；使用 capped exponential backoff、0～25% 正 jitter、
  typed delta-seconds `Retry-After` 和共享 Run deadline/cancel。只重试 transport、408/409/429/5xx/529
  等明确瞬时失败；普通 401/403、404、validation/其他 4xx 永久失败。任何 visible Delta、Provider frame
  或 Tool intent 后禁止自动重放；Context Overflow、cancel、incomplete stream、跨 Provider fallback、
  credential refresh 与 durable Plan recovery 必须由各自状态机处理。retry lifecycle 只允许枚举、attempt 和
  等待时长进入 stdio/TUI，不得投影 endpoint、Header、body、Prompt、Secret 或异常正文。
- FR-MODEL-006（S15 已实现 L1，`MODEL-13`）：产品采用本地直连 BYOK，不提供官方模型中转 Gateway；非秘密 `ProviderDefinition` 已与用户级 `CredentialProfile`/SecretRef 分离，并已实现 OpenAI-compatible custom URL/model、Anthropic 与 OpenRouter 三类 Provider Factory。CLI、TUI 与 stdio 的 `auth/providers/models`（含 TUI `/connect`、`/auth list`、`/auth logout`、`/models`）共用 Java Application Service，真实请求仍仅走现有 `ModelGateway`/`ProviderRouter`。
- FR-MODEL-007（S15 已实现 L1，`MODEL-13`）：API key 只支持权限受限用户文件 STORE 或显式 ENV SecretRef；restricted store 已实现，secret 不得进入 Domain、Canonical/Session、log、telemetry、Agent event、普通 error、argv、evidence 或 Provider Definition。Console `/connect` 已使用 masked input，普通文件不得称 OS vault；OAuth 仅保留 Provider 官方固定 issuer/client/redirect 的合法扩展，当前不实现。
- FR-MODEL-008（S15 已实现 L1，`MODEL-13`）：profile 解析固定为显式 profile→Provider default→env ephemeral→legacy properties ephemeral；显式或 default profile 失效必须 fail closed，不 silent rotation/failover。list/status 不联网，显式单 profile 的有界 probe 已实现；logout 已实现先 fence 新 lease、取消并 drain 同进程 active runs、清应用 secret/Gateway cache，再原子删除本地 secret，同时明确本地删除不等于 Provider revoke。
- FR-MODEL-009（S15 已实现 L1，`MODEL-13`）：`config/provider.local.properties` 保持可读且最低优先级；迁移只能由用户显式触发，发布并重读新 store 后仍不得修改、重命名或删除旧文件。restricted store 已覆盖严格 schema/ceiling、原子 move、单 writer lock、crash recovery、ACL/mode、Symlink/Junction/reparse 与竞态 fail closed。完整契约和验收矩阵见 ADR-069/070；当前 `MODEL-13` 达到 L1。
- FR-MODEL-010（S15 已实现 L1，`MODEL-13`）：built-in 模型目录已支持通过 strict 本地 `modelOverrides` 和 `models add/remove` 显式维护；Anthropic baseline 为 `claude-sonnet-4-6`，OpenRouter baseline 为 `anthropic/claude-sonnet-4.6`；本地 `list` 零网络，CLI `models use` 默认持久化，而 TUI 默认只影响 next run。无参数 `/connect` 必须打开消费级 Ink 向导，普通连接路径隐藏 profile 并固定 `default`，登录成功刷新 credential 后直接进入模型选择；带参数 `/connect`、`/auth`、`/models` 继续兼容高级/脚本接口。remote model sync 尚未实现；TUI 已通过严格 stdio `providers.add` 复用 Java 应用服务完成自定义 OpenAI-compatible 服务的分步创建、认证和模型选择；普通向导登录与 `models.use` 必须显式持久设为默认并经 store 重开验证。已保存 custom Provider 从安全 models/profiles 投影中有界稳定排序进入 picker，选择后直接进入 management/auth；保存 in-flight 时 Enter/Esc 均为有提示的 no-op，避免重复副作用。`provider.control` 的 `models.add/remove/use` 成功结果采用严格 exact schema，其中 add/use 的 `setDefault` 必须为 boolean；真实 StdioClient/fake stdio child 必须证明三个 intent 均可通过协议验证。尚无至少两个 distinct Provider 的真实 BYOK 在线 E2E，因此 `MODEL-13` 不得提升到 L2。

### 11.4 Tool Runtime

- FR-TOOL-001：所有工具具有唯一名称、描述、输入 Schema 和副作用等级。
- FR-TOOL-002：所有来源的工具都必须经过同一 Tool Execution Pipeline。
- FR-TOOL-003：Pipeline 至少执行参数校验、权限判断、审批、执行、结果裁剪、事件发布和错误转换。
- FR-TOOL-004：内置读工具只能访问 Workspace 允许范围。
- FR-TOOL-005：`apply_patch` 只能修改 Workspace 内允许文件。
- FR-TOOL-006：`run_command` 必须显示准确命令、Shell 类型和工作目录后再审批。
- FR-TOOL-007：命令执行支持超时、取消、退出码和 stdout/stderr 上限；非零退出必须经 Pipeline
  返回 `FAILURE / PROCESS_EXIT` 并保留有界证据。Shell HTTP 应显式使用 fail-with-body 语义，Runtime
  不抓取 HTML 或 stderr prose 猜测 HTTP 失败。
- FR-TOOL-008：模型不能通过工具参数修改 Permission Policy。
- FR-TOOL-009：`web_search` 必须作为 `NETWORK_OR_REMOTE / BUILT_IN` Tool 进入唯一 Pipeline；模型只可提供 query 和有界结果数，不能提供 Provider、endpoint、Header、credential、remote Tool name 或 fetch URL。可信本地 Provider gate 固定 Exa/Parallel hosted MCP 目标；Exa 可选 key 只能由 Adapter 形成精确编码的 `exaApiKey` query，Parallel key 只能形成 Bearer。每次出站均须经过绑定固定 scheme/host/effective port 的 `NetworkAccessPort`，使用 JSON-RPC 2.0 `tools/call`，只接受有界 `application/json`/`text/event-stream`（兼容参数，未知或缺失 media type 拒绝），redirect 不跟随，结果页不抓取，外部 textual content 以有界 untrusted provenance 返回。生产默认关闭，显式启用即表示 query 会发送给所选第三方。
- FR-TOOL-010（S15 Batch 4 / ADR-087 纠正）：Tool 失败必须携带正交的稳定 category 与可证明 retryable metadata；至少覆盖 Authorization、Permission、HTTP 403/4xx/429/5xx、Transport、Process Exit、Validation、Execution、Cancel、Timeout、Output 与 Protocol。执行失败每 Run 对“同 Tool + canonical args + 同 typed category”记录 fingerprint；第一次 `INVALID_ARGUMENTS` 必须返回有界 violations、`argumentChangeRequired=true`、`retrySameArguments=false` 及 Tool 生成的安全纠错字段。确定性 validator 可另行声明不含 query/path/Secret 的 correction signature；Pipeline 只能哈希 Tool 名与该安全摘要，并以原子 record-or-repeated 判定未改变的纠错形状。第二次同 Tool、同形状即使无关业务参数变化也返回 `REPEATED_FAILURE`；另一种 invalid shape 必须得到首次反馈，真正通过 validation 的参数必须允许。连续 repeated-only batch 受 FR-AGENT-008 熔断且多 Call ID 完整。公开 Tool Schema 只能宣传规范字段；旧字段可在 validator/executor 兼容，但不得继续诱导模型同时发送互斥参数。
- FR-TOOL-011（S15 Batch 4）：Web 403 是非重试 `HTTP_FORBIDDEN`；只在受信状态/响应头可观察时细分 Authorization required、UA/ACL 或 ordinary forbidden，不记录 secret/header value/query/body。429 与 5xx 只在 Web Adapter 层共享 deadline/cancel 做固定次数、封顶退避；普通 4xx、403、redirect、协议/类型/大小失败不重试。

### 11.5 Permission

- FR-PERM-001：S05 提供可由 CLI/Composition Root 选择的 `DEFAULT`、安全 `PLAN` 和
  `ACCEPT_EDITS`；模式在一次 Headless Session 装配时固定。
- FR-PERM-002：`DEFAULT` 自动允许普通读取，修改和 Shell 默认询问。
- FR-PERM-003：`PLAN` 规划期必须由 capability/effect hard boundary 禁止 Workspace 修改、进程执行和未声明安全能力的外部 Tool；不是静态工具名白名单。用户以 `/plan [自然语言任务]` 在当前 Session 启动真实多轮规划；模型可执行受控本地读取、经 Permission/AutoReview 的只读网络、唯一 PlanArtifact CAS 写入、callId 结构化提问，并增量维护用户可读 Markdown。TUI/stdin 不得展示模型 Tool payload、最终 JSON 或内部 objective/title/detail/expectedDigest；review 必须读取 durable artifact revision。用户反馈执行 `AWAITING_APPROVAL -> DRAFT`，保持同一 sessionId/planId/revision chain 并可 Resume。MCP/Plugin 默认不可用，只有可信 Definition 显式声明匹配安全 capability 时才可进入双 Gate。该 hard boundary 是项目独立强化，不描述为参考产品的普遍 registry filter。Batch 2 停在 durable review event；Batch 3 已实现绑定 revision/digest 的原子批准执行交接。
- FR-PERM-003A（S15 durable Plan 基础）：计划的长期用户可读形态是 Session-owned Markdown `PlanArtifact`，至少具有 planId/sessionId/revision/contentDigest/status/timestamps。修改必须执行 revision+digest CAS，并由不可变 Markdown generation + 单个原子替换的 authoritative manifest 发布；两个 rename 不得称为原子事务。Resume 复用 identity，Fork 复制内容但生成新 plan/session identity、revision 1 并重新等待批准，不能共享可写 artifact。项目自有 canonical Session journal 是跨文件恢复事实：合法 projection 缺失/落后/领先可确定重建、fast-forward 或丢弃指针；manifest/generation 损坏、身份或摘要冲突 Fail Closed。写前与 replay 必须复用同一 Domain 状态策略；非法初态/跳转在 journal append 前结构化拒绝，终态重复决定由调用方幂等跳过。恢复 Core 必须交叉验证 document/state/artifact 身份和状态，Fork 新 target 的失败清理只能精确处理本次新建目录，不能递归触碰 source。旧 `PlanDocument`/内部命令保持兼容，artifact 不授予执行权限、不自动重放副作用，也不解析参考产品格式。ADR-077 Batch 2 已在该基础上接入持续规划、结构化问题与 durable review；ADR-078 Batch 3 又完成批准执行交接与恢复，真实 Provider/Eval 仍缺，`PLAN-01` 保持 L1。
- FR-PERM-004：审批支持允许一次、按可信 Tool/ToolSource/selector 当前会话允许和拒绝；
  持久规则与分层 Settings 仍属于 S08。
- FR-PERM-005：硬拒绝优先于模式、规则和用户会话允许。
- FR-PERM-006：Print 模式遇到需要交互的操作时，若无可信 Startup Allow 则拒绝；
  Startup Allow 仍不能覆盖 Hard Denial 或 Tool Adapter 安全校验。
- FR-PERM-007：规则优先级固定为 Hard Denial → Deny → PLAN → Ask → Allow → Mode/
  Effect Default，不受规则列表顺序影响。
- FR-PERM-008：相同 Session 与 selector 连续两次拒绝后，第三次及以后固定拒绝且不再弹窗；
  新 selector 仍可正常评估。
- FR-PERM-009（S15，`PERM-05` L1）：`AUTO` 选择仅将 `DEFAULT` 的最终 `ASK` 交给受限模型复核；
  Hard Denial、显式 Deny、PLAN、permission hook Deny 与既有 Allow 均不得被覆盖。复核只接收有界、
  脱敏摘要，以无 Tool 模型回合严格返回当前 Call 的 `ALLOW_ONCE` 或 `DENY`；解析、Provider、超时和
  内部失败全部拒绝，不能建立 Session Grant。真实 Provider 误放行率、延迟、成本和 A/B Eval 未完成，
  因此不声明 L2 或更高自动化能力。

### 11.6 Context

- FR-CTX-001：S03 加载 Workspace 根目录中的 `AGENTS.md` 作为项目指令；S08 前不引入用户/目录
  分层 Instructions。
- FR-CTX-002：项目指令、记忆、摘要和 Tool/模型输出都只作为不可信 Context，不能扩大工具权限、
  Workspace 或解除 Hard Denial/Recovery Gate。
- FR-CTX-003：工具输出具有类型化大小上限、明确的截断或外置标记。
- FR-CTX-004：S07 保持 S06 Canonical Transcript 不变，每次模型请求构造短生命周期 Context
  Projection；压缩失败、取消或损坏输入不得回写规范 JSONL。
- FR-CTX-005：上下文接近模型限制时按压力条件选择 C1 大载荷缩减、C2 旧 Tool 输出清理、C3
  滚动记忆或 C4 全量摘要；C1-C4 不是固定串行四步，预算满足后立即停止。
- FR-CTX-006：任意 Projection 必须保持完整 Tool Call/Result 配对和批次顺序，协议孤儿数为零；
  活动或未完成 Tool 不进入可删除边界。
- FR-CTX-007：Provider 明确 Overflow 时同一模型回合最多恢复一次且最多新增一次模型请求；重复压力
  由绑定 Run/source revision/tier 的 Thrashing Guard 限制，每层每个来源最多一次摘要尝试，无法安全
  满足预算时以 `CONTEXT_LIMIT_REACHED` 停止。
- FR-CTX-008：摘要为空、失败、取消、返回 Tool Call/Result 协议片段、来源 revision 变化、source
  message ID 未有序精确覆盖、严格 UTF-8 或 byte/token 上限不满足、输出估算未严格降低，或关键
  protected anchor 缺失时，不提交压缩边界并保持上一 Projection 深度相等。摘要 Port 只返回数据，
  不拥有 Tool Registry/Pipeline，也不能发起 Tool Call。
- FR-CTX-009：S07 内部 Context Usage View 按 System、Instructions、Transcript、Tool、Memory、
  Reserved/Free 分类展示有界估算且不泄漏正文；完整 `/context` Slash Command UX 归 S08。
- FR-CTX-010：项目级文件记忆默认位于 `~/.cc-java/projects/<repository-id>/memory`，入口为
  `MEMORY.md`，分 M1 Storage、M2 Index、M3 Catalog、M4 Recall、M5 Projection；M2 最多 200 行
  或 25KB，M3 最多 200 topic 文件。
- FR-CTX-011：记忆类型只使用 `USER_PROFILE`、`WORKING_GUIDANCE`、`PROJECT_STATE`、
  `REFERENCE_POINTER`；记忆是可修正、可删除、可重建的 Projection 输入，不是 Session 事实。
- FR-CTX-012：相关记忆可以并行预取，但消费必须零等待：只使用消费时已完成且通过校验的结果；
  `consumeReady` 不得调用阻塞式 `get/join/wait/sleep` 或等待 monitor，使用无锁单次消费；未完成、
  失败、取消按空结果继续，重复消费者得到独立原因码，当前请求忽略的迟到结果不得再次注入。
  M4 选择计划最多携带 20 个候选，不能由调用者绕过查询上限。
- FR-CTX-013：文件记忆拒绝绝对路径、Traversal、Symlink/Junction、非法 UTF-8、超限和 Secret
  候选；repository-id 不得泄漏 Workspace 绝对路径。M1 单 topic 最多 64KB/2,000 行，frontmatter
  前 16 行内闭合；kebab-case slug 最多 64 字符，单行 description 最多 512 Code Point。上述常量为
  cc-java 独立保守上限，不来自参考实现。
- FR-CTX-014：M1 创建仅允许目标不存在，更新和删除必须匹配读取时 SHA-256；同目录随机暂存只用
  `ATOMIC_MOVE` 提交且不回退非原子写入。M1 成功后 M2 重建失败不得回滚 topic，而应返回不回显
  内容的结构化诊断。
 - FR-CTX-015：S08 G3-D 的显式 `/compact` 在 idle 边界构造候选；无参数时 anchors 为空且使用
   当前 Session 的 Canonical Transcript。普通 `codej` 必须显式装配受控 Context 容量三元组，不能因
   启动器遗漏而把已声明可用的命令固定降级为 `UNAVAILABLE`。即使 C1/C2 已满足预算，仍可在
  原有 S07 C3/C4 Gate 下尝试摘要；只有候选来源仍是下一 Run Canonical 前缀时，才一次性用于该
  下一 Run 的首个模型请求。它不得覆盖整个 Run、不得改变自动 reduction、Canonical Transcript、
  Tool Call/Result 配对、取消或 JSONL/Checkpoint。
- FR-CTX-016：S08 G3-D 的 `/context` 只返回 latest `ContextUsageView` 的数值/枚举白名单投影，
  不可用时返回固定 `UNAVAILABLE`；不得输出 Prompt、正文、路径、Tool 参数/结果、Secret 或 Provider 原文。
- FR-CTX-017：S08 ADR-049 的显式文件引用只能访问 Workspace 内经 `WorkspaceGuard` 验证的普通
  UTF-8 文本文件；支持可选行范围，拒绝绝对路径、Traversal、敏感文件、Symlink/Junction 逃逸、
  目录、二进制、非法 UTF-8、读中替换和数量/行/字节超限。任一显式引用失败必须在 Run、模型和
  Session 副作用之前 Fail Closed。
- FR-CTX-018：成功引用形成包含相对路径、行范围、SHA-256 与正文的不可变用户附件快照；快照随
  Canonical 与 Session JSONL 保存，Resume/Fork 不重新读取磁盘。模型 Adapter 以固定
  `untrusted` envelope 投影并计入 Context 预算；附件不扩大 Permission，也不是 Tool Result。

### 11.7 Session 与事件

- FR-SESSION-001：每次启动生成 Session ID。
- FR-SESSION-002：S01-S04 在内存中保存当前会话消息和事件。
- FR-SESSION-003：S06 使用项目自有、版本化、append-only semantic JSONL 保存聚合规范历史，不逐 token 持久化，也不解析商业产品内部 JSONL。
- FR-SESSION-004：Java CLI、Print、stdio 与 TUI 支持 Workspace-bound Create、Continue、Resume、Fork 与 Inspect；Fork 使用新 ID 和 parent lineage，Resume 复用指定 ID。
- FR-SESSION-005：同一 Session 同时只有一个本机 Writer；并发 Writer 明确拒绝，Inspect 只读。S06 不承诺 heartbeat、stale reclaim、网络文件系统或多主机一致性。
- FR-SESSION-006：Assistant Tool Calls、Tool resolved/started/completed 与 Run 唯一终态按 durable 顺序提交；恢复发现未完成 Tool 或潜在副作用时阻止可写 Run，绝不自动重放有副作用操作。
- FR-SESSION-007：写 Tool 执行前创建独立于 Git 的普通文件 Checkpoint；Tool 完成后记录类型化 digest 或 known `ABSENT` post-state。
- FR-SESSION-008：用户可以显式 list/diff/undo 单个 Checkpoint；Undo 必须持有 Writer、Session 非 fenced、没有活动 Run、收到针对具体 Checkpoint 的独立确认，并在最终 Move/Delete 前重检 NOFOLLOW、realpath 与 post digest。
- FR-SESSION-009：Checkpoint 仅恢复受支持的普通文件，不恢复 Symlink/Junction、Shell、进程、网络、远端或权限副作用；Permission、lease 与 Checkpoint 都不是 OS Sandbox。
- FR-EVENT-001：Runtime 发布 Session、Turn、Model、Tool、Permission 和 Stop 事件。
- FR-EVENT-002：终端只消费事件，不直接读取 Runtime 内部状态。
- FR-EVENT-003：默认事件不包含 API Key 或未经裁剪的敏感内容。
- FR-EVENT-004：所有会产生 Run 的内部 stdio 命令必须在首次写入前建立 request correlation，并由 Java 以
  `accepted/queued/rejected` 或 transport terminal 确定终结 acceptance handshake；`run.started` 前 Surface 不得
  冒充模型已运行，watchdog/reject/断连只能恢复草稿而不得自动重发。

## 12. 第一轮对照验收任务

在一个包含自动测试的公开样例仓库中执行：

```text
给 Calculator 增加 divide 方法：
1. 除数为 0 时抛出明确异常；
2. 增加测试；
3. 运行相关测试。
```

S04 完成必须满足：

1. CLI 启动交互会话；
2. Agent 自主搜索和读取相关代码；
3. 修改前展示审批；
4. 拒绝审批时仓库不变化；
5. 批准后通过受控 Patch 修改代码；
6. 执行测试前展示准确命令并再次审批；
7. 测试输出、退出码和 Diff 可见；
8. 最终回答说明修改、验证结果和风险；
9. `PLAN` 模式下同一任务不会产生修改；
10. 中途取消不会留下仍在运行的子进程；
11. Fake Model 离线测试覆盖主要 Agent Loop 路径；
12. 普通 CI 不需要 API Key。

## 13. S05-S08：可靠性能力重实现

这一组阶段不是泛化地“优化首版”，而是逐项学习成熟 Harness 如何在真实环境中保持可控和可恢复。

### S05：Permission Pipeline

- `DEFAULT / PLAN / ACCEPT_EDITS` 三种模式；Accept Edits 只自动批准已经通过安全校验的
  Workspace Write，不把不透明 Shell 当作编辑；
- `ALLOW / ASK / DENY` 声明性规则、Tool/规范化参数 selector、进程内 Startup/Session
  来源和非交互策略；User/Project/Managed 持久来源留到 S08/S13；
- Hard Denial、显式 Deny 和 PLAN 限制优先于 Ask/Allow、Session Grant 与人工批准；
- `ALLOW_ONCE / ALLOW_SESSION / DENY` 使用有界 scope，命令 Session Allow 不能含糊地
  变成允许所有 Shell；
- 权限评估、审批、执行、裁剪、脱敏和内部生命周期事件全部经过统一 Pipeline；
- 拒绝结果回传模型，相同 scope 的重复请求有确定性去循环策略；
- 用 Fake MCP/Plugin/Sub-Agent Tool 证明任何来源都不能绕过权限；S10-S12 再验证真实
  Adapter。完整契约见 ADR-039。

### S06：Session 与 Checkpoint

- 项目自有 major 1 append-only semantic JSONL，保存聚合规范消息、Tool durable 状态、Run 终态、Workspace-aware metadata 与 lineage；
- Java CLI/Print/stdio/TUI 共用 Create、`--continue`、`--resume`、`--fork` 与只读 Inspect 组合根；
- 本机 OS `FileLock` 单 Writer、并发打开检测和只读恢复；
- 崩溃后识别未完成 Tool、损坏尾部与潜在副作用，阻止可写恢复并绝不自动重放；
- `apply_patch`/`write_file` 执行前 ordinary-file Checkpoint、类型化 post-state、有界 Diff 和 compare-before-restore Undo；
- React/Ink list/diff/逐项 Undo 确认只经受控 stdio，不直接读取 Session 或 Workspace 文件；
- Scripted Fake Model 验证 Resume/Fork canonical history、Call ID 配对与停止语义的 Behavior Replay。

S06 不兼容商业产品内部 JSONL，也不承诺稳定外部 Export、Retention、SQLite、跨版本迁移、
heartbeat/stale reclaim 或 OS Sandbox；这些兼容性和隔离能力属于 S13/S14。完整契约见 ADR-040/041。

### S07：Context Engineering

ADR-042/043/044 已完成研究、独立设计和 Commit-scoped G0-G6 验收；S07 Stage Exit 为 Accepted。已验证范围：

- Canonical Transcript/Context Projection 分离、Model-aware 容量预算和可解释 Usage View；
- C1 大载荷缩减、C2 旧 Tool 输出清理、C3 滚动记忆、C4 全量摘要按条件选择，保持完整 Tool
  协议且满足预算后停止；
- 摘要提交 Gate、同一次 Overflow 一次恢复、失败不污染规范历史和 Thrashing Guard；
- `MEMORY.md` 入口及 M1 Storage、M2 Index、M3 Catalog、M4 Recall、M5 Projection；
- `CTX-17` Auto Memory Index 与 `CTX-18` Relevant Memory Prefetch，后者采用 ready-only 零等待消费；
- 长会话回放比较事实/约束保持、任务完成度和 Token 降幅，并证明慢预取不增加模型请求关键路径。

S07 文件记忆只保存普通本地 Markdown 投影，不保存 Secret、完整 Prompt/源码或未经裁剪 Tool 输出。
S08 负责分层 Instructions、Settings 和完整 Slash Command UX；S12 负责 Sub-Agent/后台任务；S14
负责稳定 Export/Retention/Migration、SQLite 与 Provider-native Context/Cache 对照；S13 负责 OS
Sandbox。历史 ADR-019 继续 Superseded。

### S08：Instructions、Settings 与 CLI 交互

- 用户级、项目级和目录级项目指令；
- 配置分层、优先级和规则持久化；
- 模型切换和 Provider 配置；
- Slash Command：`/help`、`/clear`、`/compact`、`/context`、`/model`、
  `/permissions`、`/resume`；
- 更完整的 React/Ink 历史、多行输入、补全和运行中 steering。

S08 的 G0 受控机制研究已由 ADR-045 完成；G1 已由 ADR-046 冻结项目自有 Instructions 文件位置、
Settings schema v1、逐字段 merge/delete/provenance、最小 Slash/doctor 语义和可证伪切片；G2 已由 ADR-047 冻结
Domain/Core/Application/Adapter 契约、独立 user-root guard、严格 duplicate-key parser、last-known-good 刷新、
Command Intent/Event 与 G3/G4 测试矩阵。G3-C/D/F 的有界子切片现已接入内存 Session patch、stdio v0 与封闭 Slash/TUI：输入 `/` 显示固定命令面板，方向键选择且由 Tab/Enter 补全；`/help`、`/context`、`/doctor`、`/permissions` 的严格结果使用本地固定标签渲染，不显示服务端自由文本。`/model` 只接受当前启动模型名；`/permissions query` 查询实际 mode/reviewer/selection，裸 `/permissions` 打开固定 `Plan / Ask for approval / Approve for me` picker，并分别映射为 `PLAN+USER / DEFAULT+USER / DEFAULT+AUTO_REVIEW`。旧 `mode DEFAULT|PLAN|ACCEPT_EDITS` 继续兼容，但 `ACCEPT_EDITS` 不进入 picker。变更只作用于下一 Run，保留 CLI precedence，且不读取 Settings 文件、不写 JSONL/Checkpoint。`/resume <session-id>` 仅在 idle 边界通过既有 S06 Workspace、Writer、fence、incomplete-side-effect 与 Checkpoint recovery Gate 后原子切换当前 Session；拒绝、取消或竞争保留原 Session，绝不自动重放 Tool 或副作用。permissions query 只显示无 selector 的 Settings-derived STARTUP rules provenance；无 LKG 时如实显示 Runtime baseline。

历史 G3-E 实现提供了受限多行、进程内历史、封闭补全与 steering，但其字符串缓冲与证据没有证明 grapheme 光标、视觉多行导航、完整编辑键、History/Completion 优先级、viewport 和无损大 Paste；8,192 只能约束可见 grapheme/token 结构，不能约束展开后的用户内容，且旧契约未与 Java UTF-16 和 stdio 64 KiB 单行预算形成无歧义边界。因此 [ADR-048](./adr/ADR-048-s08-corrective-composer-model-diagnostics.md) 重开 S08，并完成 `ComposerState` 纯 Reducer、约 1 MiB UTF-8 的有界无损 payload/展开预算、stdio v0 原子 begin/chunk/commit 及独立 privacy-safe ModelDiagnostic。完整 Reactor 各模块 45/172/43/101/227（Spring/Tools/CLI 分别 2/8/11 skipped，0 failures/errors）、TUI 111/111、launcher 59/59、真实 TTY G5 与独立最终 review 均通过；成功组装后的输入仍由 256K Token Context pipeline 权威治理，不得截断、提前写 Session/Canonical 或创建部分 Run。corrective implementation Commit `8fabd94b66881a4a8236cccabd4ae61dd39845d4` 已固定并完成 G6，S08 Accepted，`CLI-08`/`CLI-09` 恢复 L2；内部分块不恢复 `DIST-04`，规则编辑、Provider discovery/多模型注册、稳定机器协议与 OTel/诊断导出仍属后续 Stage。

## 14. S09-S11：扩展系统重实现

### S09：Hooks

- Pre/Post Tool、Session、Turn、Prompt、Permission、Compact 等生命周期；
- Hooks 的结构化输入、超时、阻断语义和失败隔离；
- 固定 argv 的本地 Command Hook 与有界 JSON stdin/stdout；
- Hook 不能绕过 Permission Pipeline，也不能直接污染核心状态。
- 项目 Hook 必须经过精确内容指纹的显式 Trust；配置变化必须重新批准；
- Compact Hook 可在摘要器前阻断，Post Context 只能投影到后续请求，不能改写 Canonical Transcript。

### S10：MCP

- Spring AI MCP Client 或独立 MCP Adapter；
- MCP Tool 到统一 Tool Registry 的映射；
- MCP Tool 仍经过本项目的 Permission Gate、输出裁剪和审计；
- 覆盖连接失败、Schema 变化和不可信返回值。
- 支持真实 STDIO 与 Streamable HTTP、多 Server 隔离、filter/prefix、单次重连和环境 Bearer；
- Project MCP 配置复用扩展 Trust Gate，MCP Tool 默认 ASK 且仍走统一 Approval/Pipeline；
- Resource/Prompt 完整消费、OAuth 与 Lazy Tool Loading 不属于本次 Tool 主链退出，必须如实保留差距。

### S11：Skills 与 Plugins

S11 已按 ADR-058～060 在实现 Commit `71278431dd1e5c7c4e279b44f43e084755502a5d` 上完成 Commit-scoped G0-G6，量化、安全矩阵与可复现离线 Demo 均通过，Stage Exit Accepted。验收范围为：

- `SKILL-01..07` 与 `CTX-14` L2：有界 metadata-first catalog、`/skill-name` 显式调用、模型 Skill Tool、正文与资源仅在调用时进入 transient Projection；`allowed-tools` 仅计算 runtime visibility 与 Skill allowlist 的交集，每个真实调用仍逐次执行 S05 Permission/Approval/Pipeline；禁止 nested/reentrant，单 Run 可稳定激活多个不同 Skill但每项至多一次，Scope/Hook 从正文成功投影持续到 Run 唯一终态，无活动 Run 的 Resume 不自动恢复；
- `PLUGIN-01..03` 已验收达到 L2：严格 manifest/namespace、Session immutable snapshot、host-side factory 返回持有 Tool/lease/close 的 Contribution，以及只引用已验证 named MCP Server 的首个 MCP-backed Adapter；G3 受控允许可信 `ToolSource.PLUGIN + NETWORK_OR_REMOTE` 进入 ASK，manifest 不得构造 ToolDefinition/ToolSource，Plugin Tool 仍逐次经过统一 Permission/Approval/Hook/Pipeline；
- `PLUGIN-04` 在 S11 只达到 L1：仅接受本地目录，archive 一律拒绝；staging 文件/目录 flush、同文件系统原子发布、registry staged flush/原子替换任一步不支持即 Fail Closed；卸载采用 quiescing/lease 归零，S14 再以崩溃恢复、迁移和跨平台管理达到 L2；
- `PLUGIN-05/06` 与 `SEC-11` 保持 L0；fingerprint 只做精确内容 Trust，不冒充签名、Marketplace、供应链隔离或 OS Sandbox；
- S11 明确拒绝任意 JAR、Class、ServiceLoader、native library、安装脚本或插件自带 Java Tool 代码；Provider factory 只能由宿主生产代码预注册；
- `MCP-08` 和通用 `TOOL-16` 不随 Skill catalog/MCP-backed Plugin 自动升级，仍需独立规模和质量证据；
- FixBug、Review、Test Generation 可作为独立示例 Skill，但不得成为 Runtime 分支。

实现 Commit `7127843` 的 G3-G6 证据确认 `SKILL-01..07`、`CTX-14`、`PLUGIN-01..03` 达到 L2，`PLUGIN-04` 达到 L1；Maven 813 tests/21 skips、Demo 67/67、TUI 129/129、launcher 59/59 与 Dashboard 均通过。数值上限、metadata 99.51% 降幅、恶意资源、安装故障点、快照漂移、权限旁路、恢复错配及五类零泄漏结果见 S11 Gate Evidence/Demo/Gap。S12 已在实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上完成 G0-G6 与 Stage Exit；相关 Feature 达到冻结的 L2/L1 目标。

## 15. S12-S13：高级 Agent 与安全重实现

### S12：Subagent 与任务系统

ADR-061/062 完成双源研究与契约冻结，下列产品行为已在实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上通过 commit-scoped G0-G6 验收：

- FR-SUB-001：Agent definition 使用严格、带来源和内容身份的 Session snapshot；未知字段、冲突、未知 Tool/Model 和权限放宽 Fail Closed。
- FR-SUB-002：Subagent 复用同一 Agent Runtime，不建立第二套 Loop；每个 child 使用独立 Session、Canonical/Projection、Tool Registry、Permission state、模型/预算和 Cancellation。
- FR-SUB-003：父子委托具有稳定 identity、显式状态和唯一终态；父只接收有界 privacy-safe report，不注入完整子 Transcript/Tool output。
- FR-SUB-004：Tool/Permission/Agent Hook 只允许收窄，父 Session Grant 不泄漏；每个真实 child Tool Call 仍逐次经过统一 Permission/Approval/Hook/Pipeline。
- FR-SUB-005：父预算在创建前原子 reservation；同 Session 根/嵌套 child 共享 active≤4、queue≤32、depth≤2 的公平容量，不得超卖。
- FR-SUB-006：前台与后台共用任务状态、inspect/wait/cancel 和通知；后台仍由父 Session 拥有，parent cancel、显式取消、timeout、shutdown 均必须传播且无 orphan。
- FR-SUB-007：Sub-Agent start Hook 可阻断或附加有界不可信 Context，stop Hook 只观察；S12 的 Agent Hook 仅为宿主预注册的 definition/delegation 纯收窄 seam，模型决策延期 S15。
- FR-SUB-008：同一模型批次只并发宿主白名单 `READ_WORKSPACE` Tool；结果仍按原顺序和 Call ID 恰好一次归并，写/命令/远程保持顺序。
- FR-SUB-009：Git Worktree 采用显式 lease/create/keep/remove；进入后重建 WorkspaceGuard、Settings/Instructions、Session/Tool composition；dirty、untracked、new commit、active lease 或不确定状态一律 preserve。
- FR-SUB-010：S12 不自动 commit、merge、cherry-pick 或 push；Worktree/Permission/Checkpoint/进程清理不是 OS Sandbox。

实施冻结为 Batch A `Scope + single delegate` → Batch B `bounded concurrency + background + TOOL-15` → Batch C `Git Worktree + integrated Eval`。`SUB-11` Team Board/peer messaging、remote/跨重启 worker、稳定 task protocol、模型 Prompt/Agent Hook 与 OS Sandbox 明确延期。

### S13：Sandbox

ADR-063/064 冻结的产品范围已在实现 Commit `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3` 上完成 Commit-scoped G0-G6 与 Stage Exit Accepted：

- `SEC-02/03/04/05` L1→L2，`SEC-06/07/12` 与 `EVAL-04` L0→L2；
- `SEC-08` L0→L1；`PERM-05`、`CFG-07` 因未生产接入保持 L0，`HOOK-10` 保持 L1；
- `PERM-08`、`PERM-09`、`PERM-12`、`SEC-09` 各自保持 L2 做组合回归，`SEC-11` 保持 L0；
- 区分 Permission/Approval 与 OS 级隔离，提供 `ExecutionBackend`、Local、Windows-hosted WSL2 Linux bwrap 与可选 Docker Container；
- capability probe 必须实际证明 WSL2/bwrap、Docker daemon/image 及每个 file/process/network/environment/secret 强制维度；
- 默认 fail-closed，Local fallback 只能在执行前对当前 Call ID 独立显式批准一次；
- Command、Sub-Agent、Plugin/MCP stdio、Command Hook 共用进程后端且仍经过唯一 Tool Pipeline；JVM 内 HTTP 不受该后端约束，远程 Hook 保持 L1；
- Windows PowerShell/cmd 不得隐式改写为 Linux shell；只有 fixed-drive path identity 双向核验且审批明确显示 `LINUX_SH` 的调用可进入 WSL/container；
- 最低证据为 WSL2+bwrap Linux A、Docker Container B、native Windows B（file/network C/U）、macOS C/U；Linux A 不冒充 native Windows A。

三个完整实现 Batch 已完成：Contracts/Local/truthful probe → WSL2+bwrap Linux A/path identity/LINUX_SH → Docker B/Attack Eval/native Windows 与 macOS 分级/G4-G6。标准 clean verify 为 851 tests/29 skips，真实 selector 5/5 + attack 8/8 共 13/13，TUI 133/133、launcher 59 assertions；首次真实测试因 Docker daemon 未运行导致 5 个 Docker 用例失败，启动 Docker Desktop、确认 daemon 26.1.4 后完整通过，测试后 label residue 为 0。所有新增或修改的核心公共契约具有准确中文 Javadoc。Permission、Checkpoint、Worktree、Job cleanup、最小环境与 Local backend 均不等于 Sandbox。

## 16. S14-S15：生产化与独立创新

### S14：Production Harness

状态：`ACCEPTED`（with documented deviations），Stage Exit `ACCEPTED`。ADR-065/066 已冻结范围；实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 已完成 Commit-scoped G0-G6：

- Provider/Eval/Observability：configured/observed/effective capability、无可见/无 durable intent 前的保守 Fallback、可信 Usage/价格 Cost、Anthropic Spring AI Factory、NetworkAccessPort、typed Eval 与 direct OTel Adapter；
- Protocol/SDK/Daemon/Session：项目自有 stable v1 codec/state、v0 保持原义、Java SDK、`--stdio-v1` 与 token/ownership 保护的 `--daemon` 共用 Application Service；Export/Retention/Migration/Index/Governance 已接入 SDK/v1 control API；
- Governance/Plugin/Distribution：本机管理员 provenance/LKG/fail-closed、stable/experimental gates、Plugin transaction recovery/signature port、app-dir/Windows/Linux launcher/manifest/checksum/SBOM/rollback。
- 2026-08-16 ADR-071 corrective：产品 launcher 默认进入编译 Ink TUI，`--print` 与 Provider control 复用同一 Java Runtime；固定平台 archive、Java/Node 自包含 CI 包、checksum、versions/current/LKG、install/update/uninstall 与 tag workflow 已实现。项目已选择 Apache-2.0；在 tag workflow 与 Linux runner 产生真实结果前仍不把本地 Windows 候选写成已公开 Release。

能力仍只按矩阵逐项记录已有 L1/L2，不以 Stage Exit 名义整体提升。Commit-scoped 证据证明 SESSION-12/13 的服务端 canonical/fence、60 个真实 production-harness Eval（含 Tool loop/ID/拒绝与失败恢复/cancel/limit/context/session/SDK/stable event+terminal+idempotency）、真实 OTel lifecycle 与 Plugin recovery/install/uninstall/registry migration；CFG-10/PLUGIN-04/OBS-06/EVAL-03 达 L2，SESSION-14 普通文件 Index 按冻结例外保持 L1，CFG-07 保持 L1。真实 Anthropic、已发布 N-1、WSL JDK21、macOS/Native Image/公开更新服务缺失均作为 documented deviation，不伪造 L3。第一次 clean verify 的历史 cancellation 2 秒窗口偶发 timeout 已记录；同一用例立即 1/1 通过，第二次完整 clean verify 911 tests/10 skips 通过。

### S15：Independent Innovation

只有 S01-S14 完成、矩阵内能力达到规定等级、关键能力达到可对照的 `L3`，并且已有回放评测后，才将创新列入主线。候选方向包括：

- Java/Spring 项目的语义工具与构建诊断；
- 企业内网部署、审计和可解释审批；
- 可嵌入 Spring 应用的 Agent Runtime；
- 面向测试、FixBug 和代码评审的高质量 Skill；
- 基于评测数据而不是直觉的模型与工具路由。

S15 第一优先级补齐 `TOOL-18` 的可控网络检索基线：新增 BUILT_IN `web_search`，模型只可提交 query 与 result limit；显式本地 Provider gate 固定 Exa/Parallel hosted MCP endpoint、远端 Tool 和可选 credential。每次出站都经过 `NETWORK_OR_REMOTE` Permission/Approval、统一 Hook/Pipeline 与 `NetworkAccessPort`，使用 JSON-RPC `tools/call` 并有界解析 JSON/SSE；结果标记 external/untrusted provenance，不打开或抓取引用 URL。2026-08-12 真实 Exa 与安装版 `codej` 天气 E2E 已通过；该 L2 基线仍不是 L4 创新证据，S15 保持 `IN_PROGRESS/OPEN`。

S15 的准确 Feature `MODEL-13` 不复用 Managed Policy 的 `CFG-07`。ADR-069 将 OpenCode/OpenClaw 官方 Documented 输入、Claude 授权快照与 Codex 0.147.0 的 Observed 机制、项目 Inferred/Unknown 分开；ADR-070 冻结 ProviderDefinition/CredentialProfile、用户级 restricted store、CLI/TUI/stdio 共用服务、优先级、legacy 迁移、probe、logout active-run fence/drain、隐私错误/事件及测试/E2E。当前工作树已实现 restricted store、OpenAI-compatible/Anthropic/OpenRouter 三类 Provider Factory、CLI/TUI/stdio 管理入口、masked Console `/connect`、显式有界 probe、logout fence/drain，以及 strict 本地 `modelOverrides`，`MODEL-13` 已达到 L1。由于尚未完成至少两个 distinct Provider 的真实 BYOK 在线 E2E，且 remote model sync 尚未实现，因此不得提升到 L2；它仍是参考能力补齐而非 L4 创新，S15 Exit 继续 OPEN。

### 16.1 S15 Session-local Task Board（ADR-088，Batch A-E）

`TASK-01..05` 已完成 Batch A-E 并达到 L2；以下需求同时约束 Core、生产 Tool、持久化、协议与 TUI，Plan 审批工件继续保持独立：

- FR-TASK-001：每个 root Session 独占一个 Task Board；不同 Session 不因 Workspace 相同而共享。Task 的持久化、状态与权限边界仍独立于 Plan 审批工件；Plan planning 与批准 execution 必须复用同一 Board 和同一 Task identity。应用不得在批准边界解析 Markdown 步骤、按标题匹配或重建第二套任务，也不得反向用 Task 状态批准 Plan。
- FR-TASK-002：Task 公开状态仅为 `PENDING/IN_PROGRESS/COMPLETED`；`blocked`、反向 `blocks` 与 `recoveryRequired` 均为确定性投影。canonical 只保存单向 `blockedBy`，提交前拒绝自依赖、缺失节点、重复边和全图环。
- FR-TASK-003：Task/Board revision 提供 CAS，ID 由 high-water mark 单调生成，删除形成 tombstone 且 ID 永不复用；同 actor 的 callId 只用于重试幂等，不能代替 Writer lock 或 CAS。
- FR-TASK-004：Claim 绑定 actor、Run、epoch 与时间；blocked 任务不能 claim/complete。Run 终止后状态仍为 IN_PROGRESS，但投影 `recoveryRequired`，必须由 Root 显式续领、释放、重分配或完成，绝不自动重放副作用。
- FR-TASK-005：Child 保持独立 Session/Context/Permission，只能通过宿主绑定 Board/owner Session/actor/actor Session/effect/task scope 的 capability 访问 parent-owned Board；不能自行提交 Board identity 或扩权。
- FR-TASK-006：Core mutation 集为 CREATE、EDIT、TRANSITION、CLAIM、RESUME_CLAIM、RELEASE、ASSIGN/REASSIGN、DEPENDENCY、DELETE；`task_create/task_update/task_list/task_get` 四个 BUILT_IN Tool 必须经过统一 Pipeline/Hook/Permission，不能旁路。
- FR-TASK-007：资源上限为 live 256、成功 mutation/幂等索引 4096、每任务依赖和单次边变更 32、subject/activeForm 200 code points、description 4096 UTF-8 bytes、metadata 16 keys/4096 bytes；达到上限在 mutation 前 Fail Closed，已存完全相同重试仍可返回。
- FR-TASK-008：Resume 同 Board、Fork 新 Board；Team shared、peer messaging、跨进程 owner/lease/watch/poll 和自动领取继续延期 `SUB-11`。
- FR-TASK-009：模型不能提交 Board/actor Session/actor Run/revision/claim epoch 等可信字段；CLAIM/RESUME_CLAIM 的 Run 只能来自宿主 capability。`task_update` 对模型只暴露 `task_id` 与可选 subject、description、active_form、status、owner、add/remove dependency；宿主读取当前 Task，并在同一服务临界区以强 CAS、claim 和稳定 phase call ID 执行领域 mutation。owner 必须由宿主 actor directory 确认存在且可分配。
- FR-TASK-010：List 默认 25、最大 50，按 TaskId 稳定分页，只返回摘要；超过 16KiB UTF-8 时按完整条目语义分页并提供 cursor。Get 返回同一临界区内捕获的 Board revision 与 canonical/derived detail，完整 JSON 超过 16KiB 时整体失败，禁止切断 JSON。
- FR-TASK-011：metadata 只允许 boolean、JSON-safe integer、string；metadata patch 的显式 JSON null 表示删除，字段缺失表示不修改；Context 与 Provider/MCP/Session JSON 边界必须保持 null 语义。
- FR-TASK-012：Session state Effect 只对四个精确 BUILT_IN Task name/effect 组合开放；显式 Deny 优先，Plugin/MCP spoof、Registry collision、错配 Effect 与 Child 越权必须拒绝。
- FR-TASK-013：每次成功 mutation 以 canonical 增量事件 append+force 到 Session JSONL；Resume 线性重建完整 Board 与幂等索引，Fork 只写一次重置 IN_PROGRESS 的完整 seed。日志不得按 mutation 重复保存整个 Board。
- FR-TASK-014：root 委托只能通过 `delegate_agent.taskIds` 提出最多 32 个已存在 Task ID；宿主重新验证并注入 child capability，嵌套委托不继承 parent Board。精确 BUILT_IN `delegate_agent` 在 DEFAULT/ACCEPT_EDITS 中必须审批，PLAN 和伪造来源继续拒绝。
- FR-TASK-015：stable v1 以 `task-list-v1` 协商只读 `task.snapshot`，支持 revision、TaskId cursor 与最大 50 条；模型 mutation 仍只能走四个 Tool。内部 stdio `/tasks` 返回活动项与最近五个完成项的有界投影。
- FR-TASK-016：Ink Task 面板按 recovery、in-progress、可执行 pending、blocked pending、recent completed 排序，支持 ↑/↓、Enter、Esc；自动展示采用无全宽边框的紧凑列表，不暴露 Task ID、revision、owner 或实现语言。活动 Run 必须只保留一条加载行：显式重试状态优先，否则使用当前 Task 的 `activeForm`（缺失时回退 subject），并在 Tool 运行期持续显示黄色动画。Task List 不得再显示同一 `activeForm` 子行或第二份当前任务摘要；进行中项使用黄色实心符号和加粗主标题，完成项必须使用绿色勾选、真实删除线和 dim。全部完成保留约 5 秒后只隐藏面板，durable Board 不丢失且 `/tasks` 可重开。CJK、emoji、combining sequence、缩进、依赖和恢复后缀必须共同计入显示宽度。
- FR-TASK-017：生产模型只在 durable Task Tool 已真实注册时接收复杂多步骤任务的 Task List 指导。普通 Run、Plan planning 和批准 execution 都可使用 `task_create/task_update/task_list/task_get`；后两者必须共享 Session Board。批准 execution 应先 list/get 规划期任务并复用原 ID，不能由应用从 Markdown seed、按标题匹配或替换清单。Session-local root 只有当前 runtime actor 可分配，因此模型提交的任意非空 owner 标签必须由宿主规范化为 capability actor，不能要求模型猜测隐藏 actor ID；child/未来协作目录仍必须精确校验，不得借文本扩大 scope。成功模型 mutation 必须在对应 `tool.completed` 之后通过同一 stdio writer 发布有 Session/Run 归属和单调 Board revision 的权威快照。TUI 自动显示但不抢 Composer、Steering、Approval、Question 或 Plan Review 焦点，手动 `/tasks` 才进入方向键交互。
- FR-TASK-018：Task List 与批准 Plan 的 Evidence Gate 独立。Task 状态不能替代批准或确定性产物验证，Evidence Gate 也不能重写 Task identity 或伪造完成；批准 execution 不继承隐式总次数或总 Run deadline，仍保留单次 Provider/Tool timeout、用户取消和重复失败熔断。若模型未维护任务，Surface 必须诚实保留未完成项，而不是用应用层 final-only 回合改变工具集合或合成状态。
- FR-TASK-019：stdio codec 与 Runtime dispatcher 必须接受同一组 `session.command` intent；`tasks` 只接受空 arguments，并在 Run 终止后仍可读取权威 Board。真实跨进程验收必须在隔离临时 Workspace 中先证明目标 XLSX 不存在，生成器以 create-only 拒绝陈旧覆盖，再由独立进程重新打开并验证 18×7=126 条数据；同时覆盖规划期创建五个中文 Task、批准执行复用同一 ID/标题/依赖、逐项 PENDING→IN_PROGRESS activity→COMPLETED，以及命令单次 timeout 后 `tool.failed`、Run terminal 计数、Task `IN_PROGRESS + recoveryRequired` 与 Ink 恢复提示一致。产物成功只能由 Evidence 验证，Task 交互只能由真实 mutation/snapshot 验证，不能以陈旧文件、扩展名文本或纯 reducer snapshot 代替。

Team shared、peer messaging、跨进程 owner/lease/watch/poll、自动领取和 stable push subscription 继续延期 `SUB-11`；S15 其他 Stage Exit blocker 不因本切片完成而关闭。

## 17. 非功能需求

### 17.1 来源控制、独立重实现与可维护性

- NFR-001：实现不得复制、逐行翻译或再发布泄露、未授权或超出授权范围的源码表达。
- NFR-002：需求来自本项目文档、公开来源、受控授权机制研究和独立场景；测试来自独立验收任务，不以
  参考源码文本作为断言。
- NFR-006：参考研究必须记录来源、版本/Revision、权利边界和
  `Documented / Observed / Inferred / Unknown`；无法核验的材料必须隔离。
- NFR-003：核心 Runtime 不依赖 Spring AI、React、Ink、Node、终端、文件系统或数据库类型。
- NFR-004：内置、MCP 和插件工具不得拥有绕过 Pipeline 的执行入口。
- NFR-005：不为尚未进入里程碑的能力创建复杂 DSL 或空模块。

### 17.2 安全

- NFR-010：仓库内容、工具输出和模型文本全部视为不可信输入。
- NFR-011：权限由代码执行，不依赖 Prompt。
- NFR-012：路径工具阻止绝对路径、穿越、符号链接和 Windows Junction 越界。
- NFR-013：API Key、密码、Token、端点和其他 Secret 不进入仓库、Transcript、文件记忆、摘要或普通日志；疑似 Secret 记忆候选 Fail Closed。
- NFR-014：Permission、Memory、Context Reduction、FileLock 与 Checkpoint 都不是 OS Sandbox，文档和 UI 必须明确这一点。
- NFR-015：未经用户明确要求和批准，不执行远端推送、发布、部署或数据写入。
- NFR-016：记忆与索引文件每次访问都必须在独立 memory root 内做真实路径、普通文件、大小、数量、Symlink/Junction 与竞态校验。
- NFR-017：相关记忆预取失败、取消或迟到不能阻断主模型请求，也不能在请求发送后异步改变该次 Context。

### 17.3 质量

- NFR-020：Runtime 主要路径可以完全离线测试。
- NFR-021：真实模型测试显式启用，不作为普通 CI 前提。
- NFR-022：支持 Windows 11 和主流 Linux。
- NFR-023：模型流、工具进程和取消均有超时。
- NFR-024：错误必须保留结构化分类和用户可读信息。

### 17.4 可观测性与隐私

- NFR-030：统计模型轮次、工具次数、耗时、Token 和 Stop Reason。
- NFR-031：Prompt、Completion、文件内容和命令输出默认不导出到遥测系统。
- NFR-032：Agent Event 可以重建控制流，但不要求保存完整敏感内容。
- NFR-033：可选模型诊断必须与用户可见失败摘要、Agent Event 和 Session durable 事实分离；默认 OFF，SAFE/VERBOSE 也只能使用封闭枚举、Run/turn/attempt 关联及有界本地轮转 JSONL，任何 sink 失败不得改变 Run。
- NFR-034：Prompt/Completion、Provider 原始响应、Header、Request ID、Endpoint、异常文本/栈、Tool/命令/文件正文、selector、Secret 和绝对路径不得进入模型诊断。

## 18. 成功指标

### 18.1 第一轮工程指标

- 验收任务端到端完成；
- 越界文件访问成功次数为 0；
- 未审批修改和命令执行次数为 0；
- 无限循环和遗留子进程次数为 0；
- Agent Loop 离线协议测试通过率 100%；
- Windows 与 Linux 基础测试通过；
- 普通 CI 不需要模型密钥；
- S01-S04 对应矩阵项至少达到目标等级；
- 发布时生成一份能力差距报告，明确下一阶段而不是凭感觉加功能。

### 18.2 项目价值指标

- 新用户可以根据 README 在 10 分钟内理解并启动 CLI；
- 核心架构可以只根据本项目 PRD、技术设计和 ADR 被解释和调试；
- 至少提供一个可复现公开 Demo；
- 每个 Stage 有设计说明或 ADR、测试、演示和差距记录；
- FixBug 等上层用例无需修改 Runtime Core 即可实现。

## 19. 主要风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 再次被某个业务场景绑架 | Runtime 失去通用性 | Core 只出现 Agent、Tool、Context、Session、Permission 概念 |
| 只追求“能跑的 MVP” | 学不到成熟系统为何复杂，也不知道下一步做什么 | 用完整能力矩阵持续推进，不把 S04 当终点 |
| 试图一次完成全部参考能力 | 长期没有可验证的学习反馈 | 每个 Stage 都保留可执行 Demo、测试和差距报告 |
| Spring AI 自动执行工具 | 绕过本项目权限与事件 | Adapter 只返回原始 Tool Call |
| 通用 Shell 带来副作用 | 数据或环境受损 | 精确展示、默认询问、超时、取消；S13 再做 OS Sandbox |
| 上下文持续膨胀 | 成本和稳定性下降 | S03-S04 先限制并停止，S07 系统学习压缩 |
| 参考材料授权范围或身份出现疑问 | 可能越过学习与发布边界 | 立即停止对应研究；保留指纹和 Unknown，等待维护者重新确认 |
| 双运行时和跨进程协议失控 | 调试、取消和发行成本上升 | S02 只做内部 v0、Fake 跨进程测试和原生 Windows 进程清理；稳定性留到 S14 |
| “开源不商用”含义不清 | License 与目标冲突 | S00 明确是维护者不商业化，还是许可证禁止商业使用 |

## 20. 已确认与待确认决策

S01 已确认：

1. 项目名和仓库名使用 `cc-java`；
2. Java 21 作为基线，Maven Wrapper 固定 Maven 3.9.16；Windows 普通 `.m2` 目录启动
   缺陷已修复，并在稳定 Commit 上通过 G4/G6 标准复验；
3. Maven GroupId 使用 `io.github.liumaishenjian`，Java 根包使用
   `io.github.liumaishenjian.ccjava`；
4. S01 不接真实 Provider，Fake Model 只存在于测试源。
5. 采用 `R2026.03` 公开行为基线；维护者确认 `AUTH-SRC-2026-07-29-A` 只读学习授权，
   精确 Revision、License 和再发布权继续保持 `Unknown`。
6. S02 的 UI 路线采用 Java Headless + 内部 stdio v0 + React/Ink；`CLI-11` 在 S02
   只达到 L1，稳定公共 JSON/JSONL 仍属于 S14。
7. S02 首个真实 Provider 采用维护者提供的 OpenAI 兼容 Base URL、API Key 和模型，
   不使用 Ollama；每台电脑默认填写 Git 忽略的 `config/provider.local.properties`，
   环境变量可以覆盖，具体兼容能力由真实 Spike 验证。

后续 Stage 仍需确认：

1. OpenAI 兼容中转端点的 Tool Call Streaming、Usage、Finish Reason 与 Cancellation
   实际兼容程度；
2. 最小协议 Schema/大小上限；Spring AI 2.0.0、Spring Boot BOM 4.1.0 与
   Picocli 4.7.7 已由真实 Spike 确认；
3. `run_command` 在 Windows 和 Linux 的默认 Shell；
4. S04 是否允许“当前会话始终允许”Shell，或只允许单次批准；
5. “开源不商用”的准确含义：
   - 维护者自己不计划商业化，但采用 Apache-2.0/MIT；或
   - 许可证禁止商业使用，此时属于 source-available，而非 OSI Open Source。

## 21. 术语

- **Agent Harness**：围绕模型提供上下文、工具、权限、执行环境、会话和反馈循环的系统。
- **Agent Runtime**：驱动模型回合和工具回合的核心运行时。
- **Tool Execution Pipeline**：工具从请求到校验、权限、审批、执行、裁剪和事件的统一路径。
- **Interactive 模式**：用户在同一终端会话中持续对话和审批。
- **Print 模式**：一次性、适合脚本的非交互运行。
- **可审计参考研究**：使用来源、版本和权利边界可核验的材料研究行为或机制；未核验材料不进入活动设计。
- **独立重实现**：Java 契约、命名、实现和测试均能够由本项目需求与 ADR 独立解释。
- **FixBug**：Runtime 的一个可能用例，不是核心架构。

### FR-PLAN-04：durable review 与原子执行交接（S15 Batch 3）

- 模型只以 Markdown 调用 `revise_plan_artifact`，并以空 intent 调用 `request_plan_review`；revision、
  contentDigest、Session/Plan identity 与 store CAS 必须由 trusted application control plane 重新加载和持有，
  不能要求模型手工维护；真正并发漂移仍必须 Fail Closed；
- active Run 资格检查、review feedback 转回 DRAFT 和 Plan Scope 占用必须原子收敛；被并发 Run 拒绝的
  `runPlan` 不得提前修改 durable revision；
- `plan.review.requested` 必须绑定同一 durable 工件的 `planId + revision + contentDigest + Markdown snapshot`
  和独立 workspace snapshot，严禁混用两个 digest；
- 单一 picker 默认“批准并自动执行”，另含普通逐 Tool 审批、带反馈继续规划、拒绝退出；内部 approve/execute
  命令不得展示；
- 一次 Enter 必须由一个 Java 命令完成精确 revision 校验、`AWAITING_APPROVAL → APPROVED`、执行权限选择、
  `ExecutionBrief` 构造和执行入队；入队接受前不得回送成功；
- 批准 Markdown 直接作为不可信自然语言上下文进入普通 Agent Runtime，不解析成命令/步骤三元组；
- AUTO 只替换最终 ASK reviewer，Hard Denial、显式 Deny、PLAN capability boundary 和 Tool 安全校验保持最终；
- keep/clear 都保留批准工件；`APPROVED` 只能显式恢复，`EXECUTING` 崩溃必须进入 recovery gate，绝不自动重放；
- APPROVE_AUTO/APPROVE_USER/CONTINUE_PLANNING 在首次 stdio write 前必须登记 request correlation；本地发送只进入
  `submitting`，Java 必须以统一 `run.command.result` 确定回答 `accepted`、`queued`、`rejected` 或由 transport terminal
  终结；`plan.execution.accepted` 保留 durable handoff 语义，`run.started` 才允许进入 `running` 并显示模型等待/retry；
  REJECT 不创建 Run，重复 Enter、同步提交异常、watchdog 和协议拒绝必须恢复尚未 accepted 的草稿且不残留幽灵 Run；
  watchdog 后必须关闭 outcome-unknown transport，accepted/queued 后断连不得把可能已执行的输入恢复为可重提草稿；
- durable Plan 审批/拒绝必须在内部先恢复进入 Plan 前的权限选择，再提交原子 review 决定；已经 accepted、但
  Runtime 尚未产生 Run ID 的同步启动失败必须以独立安全终态恢复 ready，不能永久显示等待模型或自动重放；
- unknown/late/mismatched acceptance 或 Run event 必须安全隔离且不能完成其他 Run；handshake watchdog、Reducer
  projection notice、普通 protocol rejection 与真实 transport failure/child exit 必须分离，任何恢复都不得自动重发用户
  输入或重放副作用；真实 Java 与安装版 E2E 必须通过 Ink reducer/render 断言 disposition、Tool、最终文本、verification、
  新 `run.started` 和终态，不能只监听 raw event；
- 只有 trusted BUILT_IN Plan artifact Tool 的 concurrency/state conflict 才能映射为隐私安全、模型可行动的
  typed error；普通/MCP/Plugin Tool 不得伪造该恢复语义或绕过 repeated-failure governance；typed Plan failure
  不得压成 generic execution failure，也不得泄漏物理路径、Markdown、JSON 或底层异常文本。

### FR-PLAN-05：durable Evidence Gate（S15 Batch 5）

- 规划期只能通过受控 Tool 声明有界交付物相对路径和验证 Tool 名；VERIFICATION locator 必须是当前
  Runtime 实际注册的可信 BUILT_IN Tool，而不只是通过名称 regex；拒绝反馈只给有界 alternatives；不得从
  Markdown 解析命令、executable triple、checkbox 或证据，声明不能写 Workspace；
- DRAFT 中相同 requirementId 必须允许幂等声明或确定性原位 correction/replacement；完全相同的幂等重试
  不得重复 store save 或推进 revision；批准后冻结、稳定顺序、identity 和最大数量保持，错误 locator 不得永久污染 Plan。
- `PlanEvidenceLedger` 绑定 sessionId、planId、批准 revision、ExecutionBrief digest 与批准时 workspace
  revision，并为每项 required evidence 保存有界隐私安全生命周期和引用。
- 普通 Agent Run `COMPLETED` 只表示循环正常停止。只有确定性文件验证和 canonical 成功 ToolResult
  满足全部 required evidence 时，Plan 才能进入 `COMPLETED`；否则进入 `NEEDS_VERIFICATION`。
- 候选最终 Assistant 必须在写入 canonical transcript 和形成 Surface terminal 前经过 Evidence Gate。验证失败时，
  Runtime 应在同一 Run 内提供有界、可观察的 correction continuation：只反馈批准 requirement 的身份、kind、locator
  与封闭 reason，不自动执行或重放任何既有 Tool；相同失败指纹或次数上限必须收敛为清晰用户决定。
- correction 期间沿用原 Run 的预算、deadline、取消、Permission、Approval、Hook 和 Tool Pipeline；第一份未验证
  final 不得进入后续 canonical request。Surface 必须抑制或明确标记未验证 prose，只有 evidence 全部满足后的
  terminal 才可把最终文本作为完成交付展示；`NEEDS_VERIFICATION` terminal 不得携带模型完成声明。
- accepted Plan 的模型失败、重试耗尽、取消、deadline、limit 与 incomplete stream 必须进入 durable
  failure status 并投影 `plan.execution.failed`；只有正常完成后才允许投影 `plan.verification.required/completed`。
  Surface 必须明确“不自动重放”，恢复仍需显式领取并经过既有 recovery gate。
- 用户可在策略允许时对具体 requirement 显式批准 typed skip；skip 必须使用独立 decision identity、
  durable/auditable，不能从模型文本暗示或批量推断。
- Surface 必须显示 actionable 非完成状态；Evidence Gate 不改变 Permission、AutoReview、Hard Denial、
  Hook、Checkpoint、MCP/Plugin/Skill Pipeline 或 EXECUTING restart no-replay。
