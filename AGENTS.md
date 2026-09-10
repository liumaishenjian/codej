# AGENTS.md

本文档定义仓库级的人类与 AI 贡献者协作规则，适用于仓库根目录下的所有文件。

## 1. 修改前必须阅读

按以下顺序阅读：

1. [README.md](./README.md)
2. [参考架构](./docs/reference-architecture.md)
3. [公开行为基线](./docs/reference-baselines/R2026.03-public-behavior.md)
4. [授权参考源码基线](./docs/reference-baselines/R2026.03-authorized-source.md)
5. [功能对照矩阵](./docs/feature-parity-matrix.md)
6. [产品需求文档](./docs/product-requirements.md)
7. [技术设计文档](./docs/technical-design.md)
8. [ADR-022](./docs/adr/ADR-022-reactivate-authorized-reference-study.md)、
   [ADR-023](./docs/adr/ADR-023-s02-java-headless-ink-tui.md)、
   [ADR-025](./docs/adr/ADR-025-s02-picocli-java-print.md)、
   [ADR-026](./docs/adr/ADR-026-s02-cli-overrides-run-deadline.md)、
   [ADR-027](./docs/adr/ADR-027-s02-model-stream-resilience.md)、
   [ADR-028](./docs/adr/ADR-028-s02-windows-terminal-lifecycle.md)、
   [ADR-029](./docs/adr/ADR-029-s02-continuous-session.md)、
   [ADR-030](./docs/adr/ADR-030-s02-privacy-safe-run-telemetry.md)、
   [ADR-031](./docs/adr/ADR-031-s02-provider-multi-tool-deviation.md)、
   [ADR-032](./docs/adr/ADR-032-s03-read-tools-security-contract.md)、
   [ADR-033](./docs/adr/ADR-033-s03-ripgrep-search-backend.md)、
   [ADR-035](./docs/adr/ADR-035-s04-approval-spine.md)、
   [ADR-036](./docs/adr/ADR-036-codej-development-launcher.md)、
   [ADR-037](./docs/adr/ADR-037-privacy-safe-model-failure-summary.md)、
   [ADR-038](./docs/adr/ADR-038-s05-authorized-permission-study.md)、
   [ADR-039](./docs/adr/ADR-039-s05-permission-pipeline.md)、
   [ADR-040](./docs/adr/ADR-040-s06-authorized-session-checkpoint-study.md)、
   [ADR-041](./docs/adr/ADR-041-s06-session-checkpoint.md)、
   [ADR-042](./docs/adr/ADR-042-s07-authorized-context-memory-study.md)、
   [ADR-043](./docs/adr/ADR-043-s07-context-projection-compaction.md)、
   [ADR-044](./docs/adr/ADR-044-s07-file-memory-prefetch.md)、
   [ADR-051](./docs/adr/ADR-051-s09-authorized-hook-study.md)、
   [ADR-052](./docs/adr/ADR-052-s09-hook-contract.md)、
   [ADR-053](./docs/adr/ADR-053-s09-command-hook-adapter.md)、
   [ADR-054](./docs/adr/ADR-054-s09-hook-settings-trust-gate.md)、
   [ADR-055](./docs/adr/ADR-055-s09-production-hooks.md)、
   [ADR-056](./docs/adr/ADR-056-s10-authorized-mcp-study.md)、
   [ADR-057](./docs/adr/ADR-057-s10-mcp-adapter.md)、
   [ADR-058](./docs/adr/ADR-058-s11-dual-source-skills-plugins-study.md)、
   [ADR-059](./docs/adr/ADR-059-s11-skill-runtime-contract.md)、
   [ADR-060](./docs/adr/ADR-060-s11-plugin-host-contract.md)、
   [ADR-084](./docs/adr/ADR-084-s15-model-request-retry-hardening.md)、
   [ADR-085](./docs/adr/ADR-085-s15-run-command-acceptance-handshake.md)、
   [ADR-086](./docs/adr/ADR-086-s15-plan-evidence-correction-continuation.md)、
   [ADR-021](./docs/adr/ADR-021-s02-model-streaming-cli-scope.md)、
   [ADR-018](./docs/adr/ADR-018-authorized-reference-study.md)、
   [ADR-019](./docs/adr/ADR-019-s07-progressive-context-reduction.md)与
   [Stage 证据包模板](./docs/templates/stage-evidence-package.md)
9. 本文档

参考架构定义长期学习目标；功能对照矩阵是当前差距和目标等级的权威记录；产品需求文档定义“做什么”；技术设计文档定义当前“如何实现”。不得通过实现细节悄悄改变产品行为，也不得让这些文档相互矛盾。

## 2. 当前项目阶段

仓库已经完成 **S01：Runtime Kernel** 与 **S02：Model + Streaming CLI**，
两者 G0-G6 与 Stage Exit 均为 Accepted；S01 被测实现 Commit 为
`5ef0bbbf54c75fcc3c8479c2c52bfbaa29beaabd`，S02 被测实现 Commit 为 `700251e`。
S03 Read Tools 已在实现 Commit `238fd631f7ae2246e6d57742508480ca05763850`
上完成 G0-G6 与 Stage Exit；WorkspaceGuard、五个只读 Tool、根 `AGENTS.md`、
结果上限、Junction/Symlink、敏感文件拒绝、ripgrep 完整参数与恢复策略均已有
Commit-scoped 证据。**S04 Write + Command** 已在实现 Commit
`16b47671a423fbb711eab298c9654de9a1fd6665` 上完成 G0-G6 与 Stage Exit：
ADR-035 已固定 Approval
启动 Gate；Approval L1 切片已实现 DEFAULT/PLAN Effect 决策表、Allow Once/Deny
stdio 协议和 React/Ink 审批面板。真实 Patch/Write L1 切片已实现精确上下文
`apply_patch`、只创建新文件的 `write_file`、父目录 realpath、提交前冲突重检、
同目录暂存/Move、有界审批摘要与文件取消。`run_command` L1 切片已实现固定平台
Shell/Workspace、准确命令审批、最小环境、
stdout/stderr 事件、输出上限、timeout/cancel 与 Windows 进程树清理；完整编码闭环
已通过公开 `Calculator.divide` Fixture 验证越权拒绝、测试失败、再次 Patch、成功
测试和 Git Diff。**S05 Permission Pipeline** 已在实现 Commit
`f7b7137081e2d85417fa5965835d4c014e514dac` 上完成 G0-G6 与 Stage Exit：ADR-038 固定参考
结论采纳边界，ADR-039 固定三模式、规则优先级、绑定可信 ToolSource 的 Session Grant、Hard
Denial、隐私安全 Permission Lifecycle、拒绝恢复与 Fake External Tool 测试契约；所列 S05
Feature 已达到 L2。**S06 Session + Checkpoint** 已在实现 Commit
`0a9df85b4a2d8532826c63aa96889540369cd1e9` 上完成 Commit-scoped G0-G6 与 Stage Exit：
ADR-040/041 固定授权研究边界、项目自有 append-only JSONL、
Create/Continue/Resume/Fork/Inspect、本机单 Writer、未完成 Tool Recovery Gate、ordinary-file
Checkpoint/Diff/显式 Undo、Behavior Replay 与 Java CLI/Print/stdio/TUI 生产接入；所列 Feature
除 `SESSION-08` 达到 L1 外均达到退出目标 L2，S06 Stage Exit 为 Accepted。

当前允许并要求：

- 保持 S01 的 Framework-free Domain、显式 Agent Runtime、Tool Pipeline、内存 Session
  与离线 Fake 测试回归继续通过；
- S02 实现范围必须保持 ADR-021 与 ADR-023 的 24 项 Feature、`Current → Target` 等级、真实
  Provider/流式 Tool Call Spike、CLI 契约、取消边界和可证伪实验；
- 已由对应 Spike 固定 Spring Boot BOM 4.1.0、Spring AI 2.0.0、Picocli 4.7.7、
  React 19.2.8 与 Ink 7.1.1；新增或升级仍必须重新提供兼容证据；
- S02 已完成 Java Fake stdio、最小 React/Ink、真实 Provider 与 Java Print Spike；
  不得整包合并候选分支
  `10c7873`，不得继续扩展其中的 JLine Renderer；
- S03 的只读安全边界和搜索回归必须继续通过；
- S04 的 Approval → Patch/Write → Command → Mini Coding Agent E2E 回归必须继续通过；
- S05 的授权研究、独立生产实现和 Commit-scoped G0-G6 证据必须继续通过；任何后续修改
  不得破坏固定优先级、ToolSource-bound Session scope、Hard Denial、隐私安全且唯一 final 的
  Permission Lifecycle、拒绝恢复或 Fake External Tool 统一入口；
- Patch/Write 的精确上下文、新文件父目录 realpath、原子替换、敏感路径和脏工作区保护
  回归必须保持通过；Command 的固定 Shell、精确预览、最小环境、输出上限、
  timeout/cancel 和 Windows 进程树清理回归也必须保持通过；
- 当前生产代码支持 Allow Once/Session/Deny、声明性 Startup/Session 规则与 Hard Denial；
  S09 的严格扩展 Settings/Trust、Command/loopback HTTP、Tool/Session/Run/Prompt/Permission/Compact
  生命周期和 transient Context Projection 已生产接入并 Accepted；远程 Hook、Prompt/Agent Hook、
  稳定外部活动协议和 OS Sandbox 不得描述为可用；
- S06 的 JSONL、恢复选择、Checkpoint phase、Recovery Gate 与 Undo 安全回归必须继续通过；
  任何后续修改不得自动重放有副作用操作、解析商业产品内部 JSONL、绕过 Writer/fence/active-run/
  显式确认 Gate，或把 Checkpoint 描述成 Git/OS Sandbox；
- S13 已提供经验证的 Windows-hosted WSL2+bwrap Linux 与可选 Docker 进程执行隔离；不得把
  应用层 Permission、FileLock、Checkpoint、Worktree、进程清理或 UNSANDBOXED Local 描述成
  OS Sandbox，也不得把 Linux A 扩张为 native Windows/macOS 同等级隔离；
- S07 已在实现基线 `f12fe259b6fb623f1a9add19a55c45d254f329ec` 上完成 Commit-scoped G0-G6，
  Stage Exit 为 Accepted：Canonical Transcript/Projection、条件式 C1-C4、typed overflow 至多一次恢复、
  内部 Usage View、文件记忆 M1-M5 与 ready-only 零等待预取均已验证；LOOP-11、CTX-06/07/08/09/10/11/17/18
  达到 L2，CTX-12/13 与 OBS-04 为 L1；
- S08 Instructions + Settings 与 S09 Hooks 已 Accepted；S10 MCP Tool 主链也已完成 STDIO/
  Streamable HTTP、多 Server、filter/prefix、统一 Permission/Approval/Pipeline、Trust 与单次恢复并
  通过真实 E2E。MCP Lazy Tool、Resource/Prompt 完整投影、OAuth、JVM 内 HTTP 强制网络边界与
  S14 稳定 Export/Retention/Migration 继续保持未实现状态。

S09/S10 Accepted 不表示远程 Hook、MCP OAuth/Lazy Tool/Resource 自动投影、JVM 内 HTTP 强制
网络边界或 S14 稳定持久化能力已经可用。S11 Skills + Plugins 已在实现 Commit
`71278431dd1e5c7c4e279b44f43e084755502a5d` 上完成 Commit-scoped G0-G6：量化、安全矩阵、
67/67 Demo、813 tests/21 skips、TUI 129/129 与 launcher 59/59 均通过，Stage Exit Accepted；
`SKILL-01..07`、`CTX-14`、`PLUGIN-01..03` 为 L2，`PLUGIN-04` 为 L1。S12 已在实现 Commit
`cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上完成 Commit-scoped G0-G6：标准 clean verify
838 tests/21 skips、TUI 133/133、launcher 59 assertions 与 Dashboard 均通过，Stage Exit Accepted；
`SUB-01..05/07..10`、`CTX-15`、`HOOK-08`、`TOOL-15` 为 L2，`SUB-06/HOOK-11` 为 L1。
Worktree reparse、Git fault/timeout、Windows remove/branch-lock cancellation recovery 仍是明确 gap；
S13 已在实现 Commit `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3` 上完成 Commit-scoped G0-G6 与 Stage Exit，状态为 Accepted：ExecutionBackend/五维 policy、run_command Local refactor、WSL2 Ubuntu+bwrap Linux A、Docker pinned-image B、native Windows process/env B（file/network U）、攻击回归、Command Hook/MCP stdio managed seam 与 root/child execution composition 均已有固定证据。标准 clean verify 为 851 tests/29 skips，真实 selector 5/5 + attack 8/8 共 13/13；首次因 Docker daemon 未运行导致 5 个 Docker 用例失败，启动 daemon 26.1.4 后完整通过，测试后 label residue 为 0。JVM 内 HTTP 仍不受进程 backend 强制，PERM-05 保持 L0、HOOK-10 保持 L1、SEC-11 保持 L0。S14 已在实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 上完成 Commit-scoped G0-G6，Stage Exit 为 Accepted with documented deviations：第二次完整 clean verify 为 911 tests/10 skips，严格 aggregate Javadoc 0 warning，Dashboard check/self-test 通过；第一次 clean verify 中历史 AgentRuntime cancellation 2 秒窗口偶发 timeout，同一用例立即隔离重跑 1/1 通过，随后第二次完整 verify 通过。无真实 Anthropic 在线证据、已发布 N-1 artifact、WSL JDK21、macOS/Native Image/公开更新服务证据；`CFG-07`、`SESSION-14` 及矩阵内其他 L0/L1 项保持原等级，不因 Stage Exit 自动提升。

## 3. 项目定位

`codej` 是一个参考成熟 Coding Agent、采用 Java 独立设计和重实现的通用 Agent Runtime 与 CLI。
仓库中的 Maven 模块、Java 包和既有协议仍保留 `cc-java`/`ccjava` 技术标识，以维持兼容性。

项目遵循以下学习闭环：

```text
观察公开行为
→ 解释负责该行为的子系统
→ 定义独立的 Java 契约
→ 实现并测试
→ 与参考基线对照
→ 记录剩余差距
→ 真正理解后再创新
```

第一个可以运行的 Coding Loop 只是学习检查点，不是项目最终范围。FixBug、代码评审和测试生成属于未来的 Skill 或示例场景，不是核心领域模型。

## 4. 可追踪性与学习证据

每一个实现任务都必须明确：

1. 所属 Stage（`S01` 至 `S15`）；
2. `docs/feature-parity-matrix.md` 中对应的一个或多个 Feature ID；
3. 当前等级与目标等级（`L0` 至 `L4`）；
4. 公开行为基线、授权参考快照 ID（未使用时写 `N/A - Not Used`），以及
   `Documented / Observed / Inferred / Unknown`；
5. 正在重现的可独立表达行为或项目需求；
6. 能够证明等级提升的测试、演示或度量；
7. 完成后维护者应当能够解释的设计决策。

所有 Stage 使用 [Stage 证据包模板](./docs/templates/stage-evidence-package.md)中的
G0-G6 Gate。当前授权研究和独立重实现边界由
[ADR-022](./docs/adr/ADR-022-reactivate-authorized-reference-study.md)定义。

能力等级提升时，必须在同一个变更中更新功能对照矩阵。一个 Stage 只有同时具备以下材料才算完成：

- 设计说明或 ADR；
- 在可行情况下提供确定性测试；
- 可复现的演示；
- 与参考行为的对照；
- 简短的差距报告，明确仍然缺失的内容。

不得仅因为某个功能“看起来有趣”就加入实现。应优先关闭当前 Stage 的差距；如果确实进行独立创新，必须记录假设和评测方案。

### 4.1 项目进度看板强制更新

[功能对照矩阵](./docs/feature-parity-matrix.md)始终是 Capability、等级和 Stage 路线的
权威来源；[`docs/progress-state.properties`](./docs/progress-state.properties)只记录
当前 Stage、G0-G6、阻塞项和最近验证；[`docs/progress.html`](./docs/progress.html)是由
前两者生成并附带源码/构建摘要的只读展示，不是第三份事实来源。

以下任一情况发生时，任务完成前必须更新并校验进度看板：

- 修改任意生产或测试 Java/TypeScript 代码；
- 修改父 POM、模块 POM、Maven Wrapper、构建配置或仓库脚本；
- 修改 Capability Level、Stage 目标、Gate 状态、阻塞项、测试证据或能力声明；
- 新增、删除、合并或重命名 Feature ID。

强制流程：

1. 如果能力、范围或证据发生变化，先更新功能矩阵和对应 ADR/PRD/技术设计/Demo/Gap；
2. 无论 Capability Level 是否变化，都更新 `progress-state.properties` 的
   `last.updated` 和 `last.change`；没有等级变化时必须明确写出；
3. 代码输入或功能矩阵发生变化时，根据生成器报出的当前值更新
   `inputs.code.digest` 或 `inputs.matrix.digest`；该确认表示维护者已经重新审视本次变更
   对 Stage、Gate、证据和能力等级的影响，不能只机械复制摘要而跳过判断；
4. Gate、阻塞项和 `evidence.*` 只能根据实际证据更新，不能因为代码已编写就提前标记通过；
5. 在仓库根目录执行：

   ```text
   java scripts/ProgressDashboard.java
   java scripts/ProgressDashboard.java --check
   java scripts/ProgressDashboard.java --self-test
   ```

6. `docs/progress.html` 必须与相关代码和文档处于同一个变更中；
7. `--check` 未通过时，不得宣称任务完成、不得提升 Capability Level、不得退出 Stage。

禁止手工编辑 `docs/progress.html`。如果看板展示需要变化，应修改矩阵、状态文件或生成器。
生成器会把 Java 源码、POM、Wrapper 和仓库脚本的摘要写入 HTML，因此这些文件发生变化
却没有重新生成看板时，`--check` 会失败。
首次加入 `cc-java-tui` 代码的变更必须同时扩展生成器，使 TypeScript、`package.json`
和 lockfile 进入代码摘要；在此之前不得声称看板能检测 TUI 代码漂移。
仅修改与项目进度完全无关的纯排版/拼写且不接触代码时，可以不更新
`progress-state.properties`，但只要输入文件发生变化仍必须重新生成并运行 `--check`。

## 5. 来源控制与独立重实现规则

除第 5.1 节中由维护者明确确认的已授权学习材料外，以下规则不可妥协：

- 不复制或翻译泄露、反编译或其他受限制的源码；
- 不复制内部 Prompt、注释、错误文案、私有类型名、文件布局或实现常量；
- 不把受限制源码仓库用作依赖、子模块、测试 Fixture 或 Golden Output 来源；
- 不在查看受限制源码后凭记忆还原其具体表达；
- 默认只从公开文档、公开接口和独立设计的黑盒场景中提取行为需求；
- 使用能由本项目需求解释的独立命名、Java Runtime 设计和独立 UI 契约；
- 记录重要的第三方启发来源和适用的许可证义务；
- 不以产品名或商标暗示本项目与原产品存在官方关系。

“仅用于学习”“不商用”以及在自己的 GitHub 账号中保存副本，都不会自动取得再发布权。如果来源不清楚，应停止使用该材料，只保留能够独立表达和验证的行为需求。

仓库许可证仍是开放决策。在维护者确认前，不得添加 `LICENSE` 文件，也不接受外部代码贡献。

### 5.1 已授权参考源码的受控学习例外

`AUTH-SRC-2026-07-29-A` 已由维护者明确确认为合法学习材料，可在仓库外隔离目录只读
研究。准确 Revision、许可证、权利人和再发布权仍为 `Unknown`，不得把学习授权扩大
解释为复制或分发授权。

使用该快照时必须：

- 只提炼子系统职责、状态转换、算法策略、边界条件、失败恢复和验证方法；
- 不复制或逐行翻译函数体、Prompt、注释、错误文案、私有类型名、文件布局或常量；
- 使用本项目可独立解释的 Java 契约、UI 协议、命名、模块边界和测试；
- 不把参考字节放入仓库、依赖、子模块、Fixture、Golden Output 或发布物；
- 在研究结论进入 PRD、技术设计或代码前，通过单独 ADR 说明采纳范围和可证伪验证；
- 对外区分参考机制、本项目设计和已经测试的实现；
- 授权范围或材料身份出现不确定性时立即停止使用。

完整快照、未知项和停止条件见
[授权参考源码基线](./docs/reference-baselines/R2026.03-authorized-source.md)与 ADR-022。

### 5.2 成熟核心机制的默认研究要求

对矩阵中的 Agent Loop、Tool、Permission、Context、Session、Hook、MCP、Skill、
Subagent、Worktree、后台执行和 Sandbox 等成熟核心能力，进入设计或实现前默认必须先在
`AUTH-SRC-2026-07-29-A` 中完成受控机制研究，而不是直接从头试做。只有授权快照确实没有
对应机制、材料不可用或该能力明确属于本项目独立创新时，才可写
`N/A - Not Used`，并必须在 ADR 中记录原因和替代验证方法。

每项研究和重实现至少应留下：

1. 授权源中可抽象表达的职责、状态转换、边界条件、失败恢复和验证方法；
2. 本项目采用或有意偏离的机制，以及独立 Java/TypeScript 契约；
3. 能够自动证伪理解错误的 Fake、集成测试、Demo 或度量；
4. 仍未达到参考机制的差距和后续 Feature/Stage；
5. 对照结论、测试、文档和看板的自动化复验结果。

维护者负责验收学习目标和体验，不负责替代实现者逐项发现参考机制或手工证明每个基础
功能。实现者不得因为某项功能“容易自己写”就跳过授权源研究，也不得把授权研究误解为
复制源码表达。

### 5.3 源码对照与用户可见交互验收（强制）

本节将第 5.2 节的研究要求落实到每一批实现和缺陷修复，尤其适用于 TUI。
“看过源码”“测试通过”“工具执行成功”均不能单独证明交互对齐或任务完成。
本节不扩大第 5.1 节的学习授权，不允许复制参考源码表达。

#### 开始前：明确入口与对照记录

- 开始或恢复任务时，先确认当前仓库绝对路径、分支、启动入口和用户最近认可的效果；
  不把旧工作树、离线演示、已安装版本与当前生产适配器混为一谈。
- 默认参考目录为仓库外只读的 `G:\AI Cloud\claude-code-main`，
  基线身份为 `AUTH-SRC-2026-07-29-A`；身份或授权不确定时按第 5.1 节停止使用。
- 修改前，在对应 ADR 或证据文档中建立下表。研究要追踪入口、状态生产者、事件传递、
  渲染与隐藏条件、终态和错误恢复，不能只读一个组件或凭文件名推测。
- 恢复任务或上下文压缩后，先读取本批对照表及未通过项；已经记录且未变化的研究不必重做。
  子任务必须携带同一 Feature ID、参考入口、验收场景与边界，主任务负责集成后复验。

| 用户场景 / Feature ID | 参考路径与符号 / 基线 / 证据分类 | 参考可观察行为（含不显示什么） | 本项目采用机制 / 必要偏差 | 可证伪场景与实际证据 / 未通过项 |
| --- | --- | --- | --- | --- |
| 每一项可见交互分别填写 | 精确到符号或调用链；区分 Observed、Inferred、Unknown | 出现时机、文案语义、焦点、按键、摘要/展开、完成与失败 | 独立契约及偏差原因 | 记录运行版本、步骤、预期、实测与结果 |

#### 实现中：可见行为必须有依据

- 每个新增的可见提示、工具行、面板、快捷键、自动跳转或工作流必须能追溯到
  对照表中的参考行为或用户已确认的需求；不得为了表现“有进展”自行增加交互。
- 内部协议存在某个工具或事件，不表示它应出现在对话中。必须核对参考的默认显示、
  隐藏、分组及展开规则；不得把内部 JSON 换成中文行就声称对齐了参考交互。
- 例如本次已核对的 Task CRUD 普通调用行在参考渲染链路中被隐藏；
  不能凭工具存在就添加“查看任务清单／更新任务状态”流水。
  任务进度、计划审核、实际工具操作和最终回答是不同职责，分别按对应参考机制处理。
- 已认可的视觉方向作为本批基线，先完成一个纵向可运行场景，再扩展下一项。
  未获认可的扩展不提前加入。技术限制或安全边界造成偏差时先记录原因与用户影响；
  用户已授权的常规实现继续推进，改变已确认产品行为的选择才需用户决策。
- 参考不存在的本项目机制必须标为独立设计并说明必要性；
  不得把 Evidence Ledger 等独立契约描述成参考已有机制。

#### 完成前：必须看到最终交付

- 先验证 Java Fake 与协议，再验证实际入口的按键流程；涉及模型生成或真实工具选择的修复，
  在已配置且授权的 Provider 上重跑对应场景。无法运行时明确标为未验证，不能用 Fake 替代实测结论。
- 使用同一独立场景对照参考与本项目：初始输入、运行中、工具摘要/详情、审批/提问、
  取消或失败、最终正文/产物、下一轮可继续。只验收本批涉及的状态，但不能跳过其终态。
- `/plan` 必须走到“任务描述 → 可审核计划 → 确认 → 实际执行 →
  最终正文或真实产物可访问 → 返回可继续输入”。停在计划展示、审批成功、
  工具成功或 Task COMPLETED，不算计划执行闭环通过。
- 联合核对模型/工具结果、Runtime 终止状态、协议终态、持久化状态与界面实际可见内容；
  有正文却没显示、失败却显示完成、只更新任务没有交付，均为验收失败。
  不得通过放宽验收条件、伪造成功或暴露未验证正文为“完成”来消除表象。
- 新增或改变 TUI 布局时，按已认可的尺寸基线检查折行、留白、焦点和操作可达性；
  对照记录必须区分源码观察、离线渲染帧、PTY 按键/屏幕文本、物理终端截图与用户验收。
  未实际运行参考 UI 时，只能声称“源码机制已对照”，不能声称视觉一致。
- 自动测试应能发现偏差，不能只固化自己当前的设计。对用户报告的失败先复现或核对真实事件，
  明确预期与根因，再补回归；修复后必须重跑原场景到最终交付，不能只跑邻近成功路径。
- 交付说明只报告实际新增能力、运行入口、对照结论、验证证据和剩余差距。
  测试数量不得换算为体验完成百分比；未通过项不得转交用户作为首次基础测试。
  用户负责最终体验验收，不替代实现者验证完整执行链路。

## 6. 核心架构不变量

- 模型只能提出操作意图，是否执行由确定性的应用代码决定。
- Agent Runtime 掌握模型/工具循环、预算、取消和终止状态。
- `ModelGateway` 表示一个模型回合并返回原始 Tool Call；Spring AI 不得在 Core 背后自动运行整个循环。
- 内置 Tool、MCP Tool 和 Plugin Tool 必须经过同一个 `ToolExecutionPipeline`。
- Pipeline 负责参数校验、生命周期事件、权限、审批、执行、截断、脱敏和结果转换。
- Tool Call ID 与对应的 Tool Result ID 必须准确匹配并保持协议顺序。
- CLI、未来桌面端和 SDK 只消费事件，不承载 Agent 决策。
- Core 和 Domain 类型不得依赖 Spring AI、Reactor、Picocli、Ink、Node、文件系统或持久化框架类型。
- Permission 规则不能被描述成 OS Sandbox。
- README 中的能力声明必须与真实代码和矩阵等级一致。

初始模块依赖方向：

```text
cc-java-domain
        ↑
cc-java-core
    ↑           ↑
cc-java-model-spring-ai   cc-java-tools-local
             \             /
           cc-java-cli (Java headless)
                    ↑
          cc-java-tui (React/Ink)
```

模块规则：

- `cc-java-domain` 保存框架无关的不可变协议和值对象；
- `cc-java-core` 负责 Runtime、Agent Loop、端口、Context、限制、生命周期和权限管线；
- `cc-java-model-spring-ai` 只负责项目协议与 Spring AI 之间的转换；
- `cc-java-tools-local` 实现项目 Tool 契约和本地执行安全；
- `cc-java-cli` 是 Java Headless Composition Root，提供 `--print` 和实验性 `--stdio`；
- `cc-java-tui` 是非 Maven 的 React/Ink 终端适配器，只通过命令/事件协议使用 Runtime；
- 只有当前 Stage 确实需要时才创建新模块。

## 7. Stage 纪律

Stage 是可验证的学习切片，不表示后续能力不在项目范围内。

- S01-S04：Runtime Kernel、模型流式输出、仓库读取、受控 Patch 与命令循环；
- S05-S08：深度权限、Session、Checkpoint、Context、Instructions 与 Settings；
- S09-S11：Hooks、MCP、Skills 与 Plugins；
- S12-S13：Subagent、Worktree、后台执行与 Sandbox；
- S14-S15：生产级 Harness 和经过评测的独立创新。

不得在早期 Stage 中提前完整实现后期能力。应保留文档规定的扩展缝隙、记录延期差距并继续沿矩阵推进，不能把第一个可运行版本当成项目完成。

## 8. 安全规则

- 用户输入、仓库文件、模型输出、Tool 参数、命令输出和外部集成都属于不可信输入。
- 绝不能依赖 Prompt 实施访问控制。
- 每次文件操作前都要解析并验证真实路径。
- 拒绝路径穿越、绝对路径滥用、符号链接逃逸和 Windows Junction 逃逸。
- 保护敏感文件，并限制文件大小、结果数量、输出字节、回合数、调用数和运行时间。
- 不得把模型或用户文本直接插值到 Shell 字符串中。
- 应尽量使用结构化参数执行已批准命令，并固定工作目录、超时、输出上限和取消机制。
- 默认不得记录 API Key、完整 Prompt、完整源码文件或未经处理的敏感 Tool 输出。
- S02 Provider 密钥可以来自环境变量、外部 Secret Store，或固定路径
  `config/provider.local.properties`；该本地文件必须保持 Git 忽略，不得被提交、
  复制到证据包或写入日志；
- 不得把密钥写入任何已提交配置；`config/provider.local.properties.example` 只能包含空值；
- 不得加入真实公司端点、凭证、Schema、日志、工单、源码或未脱敏业务数据。
- Commit、Push、Merge、Release、Deployment 和外部系统写入需要单独、明确的用户授权。

如果便利性与安全边界冲突，应保留安全边界并记录取舍。

## 9. 变更流程

实现前：

1. 确认 Stage、Feature ID 和目标等级；
2. 阅读对应参考资料和验收条件；
3. 列出受影响的模块边界、协议和安全不变量；
4. 在架构选择被隐藏进代码之前记录 ADR；
5. 设计能够证伪当前理解的最小实验。

实现过程中：

- 保留用户已有且与当前任务无关的改动；
- 把适配器放在架构边缘；
- 避免推测性抽象和面向未来的空模块；
- 使用结构化错误和明确终止状态；
- 在每条新执行路径中传播预算、取消和生命周期事件；
- 在接入真实 Provider 前优先使用确定性的 Fake Model 和 Fake Tool 测试；
- JDK 或已有依赖足够时，不增加新依赖。

完成前：

1. 运行最小相关测试，并在可行时运行更广泛的模块测试；
2. 运行对应 Stage 的演示或行为对照；
3. 确认 Diff 中没有密钥、私有数据或受限制源码表达；
4. 更新矩阵等级和证据链接；
5. 更新能力声明并记录剩余差距。

## 10. 测试要求

Agent Loop 必须能够通过脚本化 Fake `ModelGateway` 在无网络、无 API Key 的条件下完成测试。

至少覆盖：

- 直接最终响应与流式结果聚合；
- 单轮和多轮 Tool 调用；
- 一个模型回合包含多个 Tool Call；
- Tool Call/Tool Result ID 精确匹配；
- 非法参数、未知 Tool、权限拒绝和 Tool 失败；
- 模型失败、空响应和不完整流；
- 回合、Tool、Token、输出和时间限制；
- 用户 Steering、取消和子进程清理；
- 进入对应 Stage 后的 Session 恢复、未完成副作用检测和 Context 压缩。

Tool 测试必须覆盖路径穿越、绝对路径、符号链接/Junction 逃逸、敏感文件、大小上限、输出截断和脏工作区保护。

真实模型测试必须显式启用，不作为普通 CI 的前提，不断言固定自然语言，也不得暴露凭证或私有仓库。

行为对照只能使用独立编写的任务和可观察结果，不得把受限制源码文本作为测试期望值。

## 11. 多 Tool Call 协议

当一个模型回合包含多个 Tool Call 时：

1. 只追加一次包含全部 Tool Call 的 Assistant Message；
2. 按当前 Stage 的顺序策略执行调用；
3. 为每个 Call ID 准确追加一个 Tool Result；
4. 整批调用到达明确终止状态后，才能请求下一个模型回合。

不得为每个 Tool 重复追加同一条 Assistant Message。

## 12. 中文注释与 Javadoc 规范

这是学习项目，代码不仅要能运行，还要能够帮助维护者理解成熟 Agent Harness 的设计。因此，重要模块、核心类和关键实体必须提供有信息量的中文说明。

### 12.1 必须使用中文 Javadoc 的对象

以下对象在创建或修改时必须使用标准 `/** ... */` Javadoc：

- 核心模块的主要包；使用 `package-info.java` 说明模块职责、边界和依赖方向；
- Agent Runtime、Agent Loop、Tool Pipeline、Permission、Context、Session、Lifecycle 等核心类和核心接口；
- Domain 中承担协议语义的实体、值对象、枚举和 `record`；
- Port、SPI、Adapter 的公共契约；
- 安全边界类、状态机、策略类、预算/取消控制和异常体系；
- 对外公开或会被其他模块调用的重要方法；
- 行为不直观、存在重要不变量或容易被误用的实现。

### 12.2 Javadoc 应说明什么

Javadoc 优先解释“为什么”和“契约”，而不是逐字复述代码。根据对象性质说明：

- 在整体 Agent Harness 中承担的职责；
- 明确不负责什么，以及与相邻模块的边界；
- 关键状态、不变量和终止条件；
- 输入输出语义、空值约定和所有权；
- 副作用、权限要求、线程模型和取消行为；
- 失败语义，以及调用者应如何处理；
- `record` 各组件的业务或协议含义；
- 必要的 `@param`、`@return`、`@throws` 和 `@since`。

示例：

```java
/**
 * 驱动单次 Agent Run 中的模型回合与工具回合。
 *
 * <p>该类型只负责状态迁移和终止判断，不直接访问文件系统、
 * 调用终端 UI 或执行具体工具。所有工具请求都必须交给
 * {@code ToolExecutionPipeline}。</p>
 *
 * @since 0.1.0
 */
public final class AgentRuntime {
}
```

### 12.3 注释质量要求

- 中文是项目代码注释和 Javadoc 的默认语言；类名、协议名和行业术语可以保留英文。
- 注释必须与实现同步；过期注释按缺陷处理。
- 不为简单 Getter、Setter、显而易见的构造器或一眼可读的语句添加机械注释。
- 不写“初始化变量”“循环列表”之类只复述语法的低价值注释。
- 复杂算法可以使用行内中文注释解释决策原因、边界条件和安全考虑。
- TODO 必须说明未完成原因、对应 Feature ID 或 Issue，以及可以删除 TODO 的条件。
- 不能用长篇注释掩盖命名混乱或职责过大的设计；应先改善代码结构。

代码评审时，核心实现缺少必要的中文 Javadoc，或注释不能帮助理解架构契约，均视为未完成。

## 13. 文档规范

- 所有文档使用 UTF-8 Markdown。
- 描述行为时使用 `FR-*`、`NFR-*` 和 Feature ID。
- 决策状态使用 `Proposed`、`Accepted`、`Open` 或 `Superseded`。
- 对版本敏感的参考结论添加日期或 Baseline ID。
- 框架和产品行为优先引用公开的一手官方文档。
- 明确区分观察到的行为、推断和本项目独立设计。
- 产品范围或能力改变时，同步更新 PRD、技术设计和功能对照矩阵。
- 只有在 Mermaid 能明显改善流程、状态或依赖关系表达时才使用。

## 14. 依赖与版本策略

已确认的技术基线包括 Java 21、Maven 3.9.16、JUnit 5.14.3、AssertJ 3.27.7、
Node.js 22、Spring Boot BOM 4.1.0、Spring AI 2.0.0、Picocli 4.7.7、
React 19.2.8 与 Ink 7.1.1。首个 Provider 为维护者配置的 OpenAI-compatible 端点；
具体服务实现不进入仓库或能力声明。

引入依赖时：

- 使用 Spring Boot Parent 或 BOM 管理 Boot 依赖；
- 单独导入 Spring AI BOM；
- 使用 Maven Wrapper；
- 优先选择 Maven Central 中的稳定版本；
- TUI 依赖优先使用 npm Registry 中的官方稳定包，并提交 lockfile；
- 从一个真实模型 Provider 和一个 Fake Gateway 开始；
- 在变更说明中解释每一个非测试依赖的用途。

## 15. Git 规范

- 使用聚焦的提交，Commit Subject 采用 `docs:`、`feat:`、`fix:`、`test:`、`refactor:` 等 Conventional Commit 风格。
- 除非维护者明确要求，否则不重写共享历史。
- 不提交构建产物、IDE 状态、密钥或本地模型配置。
- 除非用户明确授权，否则不执行 Push、Merge、Release 或其他外部系统变更。

## 16. 完成定义

一项变更只有同时满足以下条件才算完成：

- 达到声明的 Feature ID 目标等级；
- 能从公开需求和独立设计解释其行为；
- 模块依赖和执行管线保持文档规定的边界；
- 相关离线测试和 Stage 证据通过；
- 重要模块、核心类、关键实体和公共契约具有必要且准确的中文 Javadoc；
- 功能对照矩阵和能力声明准确；
- 项目进度状态已更新，`java scripts/ProgressDashboard.java --check` 通过；
- 第 5.3 节的源码对照记录、实际入口终态及最终交付证据齐备；未验证项不宣称完成；
- 剩余差距与风险被明确说明，而不是隐藏。
