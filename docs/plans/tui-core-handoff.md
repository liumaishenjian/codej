# 新 TUI 核心功能收口与源码对照交接文档

日期：2026-09-11。状态：分批实施中；第一批已完成实现、确定性验收及真实 Provider 正常天气闭环，另以 Fake Model + 生产 stdio/TUI 的 Windows PTY 验证失败恢复；第二批 R1—R4 已完成源码对照、自动六尺寸、跨进程与 Windows PTY 验收。第三批 R5—R6 已形成收窄后的方向验证候选版：同一真实 Session 从 revision 24 的 `NEEDS_VERIFICATION` 修正至 revision 37 `COMPLETED`，并完成 PowerShell 7 Unicode/长正文和定向分片安全边界；修复后全 TUI、显式 Java E2E 与最终真实入口均已复验通过，Windows PowerShell 5.1 未实机。本文件不表示整体重构已经完成。

## 1. 目标、基线与交接方式

**目标：沿用用户已认可的界面，修复内部错误呈现，完成对话、工具审批、问卷、`/plan` 四条核心流程的源码对照与最终交付验收。**

开发目录与历史归档见[工作树说明](../development-workspaces.md)。

### 工作基线

| 项目 | 固定值 |
|---|---|
| 工作目录 | `G:\AI Cloud\cc-java-tui-redesign` |
| 分支 | `feat/tui-redesign` |
| main 起始基线 | `e51e6b5062fbab46881137342a2bff84bfc05645` |
| 已推送实现提交 | `0c01799`，包含 `116af64`、`dea3349`；远程分支 `origin/feat/tui-redesign` |
| 实现状态 | 第一、二批已有验收记录；第三批为方向验证候选版。以本文末尾实际证据和剩余差距为准，整体重构尚未完成 |
| 授权参考目录 | `G:\AI Cloud\claude-code-main`，只读 |
| 参考身份 | `AUTH-SRC-2026-07-29-A`，具体 Revision 未知 |
| 项目阶段 | S15，Stage Exit 保持 OPEN |
| 能力范围 | CLI-01/03/04/05/09 保持 L2，TOOL-11、PLAN-01 保持 L1；不因本轮验收自动升级 |

其他工具接手时，先获取远程 `feat/tui-redesign` 最新提交，并确认包含候选提交 `0c01799`；仅检出 main 起始基线不会获得新 TUI。若交接后出现新改动，还需单独交接未提交及未跟踪文件。禁止直接重置工作树或整包迁入旧重构实现。交接材料不得包含本地凭证、用户会话或构建缓存。实施前遵循仓库 AGENTS.md 的必读顺序及第 5.3 节。

真实入口：

```powershell
& 'G:\AI Cloud\cc-java-tui-redesign\scripts\StartCodejDev.ps1' --tui-next
```

`preview:tui` 仅用于离线视觉演示，不能证明真实 Runtime 已接通。继续保留已安装版本默认入口，不提交、合并或发布；额度剩余不高于 1% 时停止并记录进度。

### 已有证据与当前缺口

- [ADR-091](../adr/ADR-091-s15-live-core-experience.md)、[ADR-092](../adr/ADR-092-s15-questionnaire-contract.md)：真实核心适配与问卷协议。
- [ADR-093](../adr/ADR-093-s15-plan-review-required.md)：规划未形成审核工件却空白结束的修复。
- [ADR-094](../adr/ADR-094-s15-plan-completion-evidence.md)：执行完成与持久化验收条件统一。
- 近期相关后端回归 136 项，0 失败、1 跳过；TUI 339 项通过；最终跨进程定向 23 项通过。上述是既有证据，不是本计划实施后的验收。
- 真实天气场景已观察到：审核、命令执行、最终正文可见、Plan COMPLETED、恢复输入。
- 第一批已让未注册验证 Tool 的安全原因与宿主权威恢复关联可见；真实 Provider 正常天气样本未触发该分支，Fake Model + 生产 stdio/TUI 的 Windows PTY 已覆盖失败与恢复显示。
- 第二批已关闭无审批 `run_command` 缺完整 command/shell/cwd 的实际偏差，并完成草稿/焦点、搜索分组、流式去重、取消迟到隔离及六尺寸回归。
- 第三批已复现并修复拒绝终态永久阻塞新 `/plan` identity、非 Git Workspace 声明不可满足 required Git verification、显式 correction 的 durable 恢复、Windows PowerShell 双引号/Unicode/argv 边界及旧 Host 定向分片协商。无参 `/plan` 固定只进入/查看；仅显式 `/plan <修正请求>` 纠正同一 planId/Task cohort/requirementId。Java 155/155、同一真实 Session revision 24→37、修复后 build、全 TUI 349 pass/7 skip、显式 Java E2E 7/7，以及真实入口混合中文/错误 exit 7/正确正文/下一轮均已通过。
- Markdown 表格排版、首次生成命令的 Windows 适配质量仍有差距；本轮不宣称解决全部视觉与模型质量问题。
- 全仓聚合 Javadoc 的既有警告限制仍需如实记录。

## 2. 源码参考与强制对照点

以下参考路径均相对于授权参考目录。**文件存在和源码阅读不等于实际视觉验收。**每批记录具体符号、调用链、观察结果和必要偏差，不复制源码表达、Prompt 或测试期望文本。本项目 TUI 路径均相对于 `cc-java-tui/src/`。

| 编号 / 场景 | 核心参考入口 | 必须核对的行为 | 本项目实现入口与验收重点 |
|---|---|---|---|
| R1 主界面与输入 | `src/screens/REPL.tsx`；`src/components/PromptInput/PromptInput.tsx` | 消息、运行状态、输入区的组织；面板焦点接管；多行输入与取消 | `experience/runtime-app.tsx`、`experience/editor.ts`、`experience/runtime.ts`；流式回答不重复，运行中保留草稿，面板退出不丢输入 |
| R2 普通工具与分组 | `src/utils/groupToolUses.ts`；`src/components/messages/GroupedToolUseContent.tsx` | 哪些调用可以分组；摘要与详览；失败是否独立显示 | `experience/runtime.ts`、`experience/runtime-screen.tsx`；调用与结果原位关联，不能跨 Run 分组或吞掉失败 |
| R3 内部调用与错误 | `src/components/messages/AssistantToolUseMessage.tsx`；`src/tools/TaskListTool/TaskListTool.ts`；`src/tools/TaskUpdateTool/TaskUpdateTool.ts`；`src/components/messages/UserToolResultMessage/UserToolErrorMessage.tsx` | 普通调用行与错误结果是不同渲染路径；调用返回 null 不代表错误也必须隐藏 | 当前正常内部调用已隐藏；错误仍只有笼统提示。按下一节修复，禁止重新添加任务流水 |
| R4 Shell 与审批 | `src/tools/BashTool/BashToolResultMessage.tsx`；`src/components/permissions/BashPermissionRequest/BashPermissionRequest.tsx` | 完整命令预览；输出更新；允许、拒绝、取消；详情可读性 | `RuntimeStdioCommandHandler`、`experience/runtime-screen.tsx`；显示真实 Shell、目录和命令；Windows 不统一标成 Bash |
| R5 问卷 | `src/components/permissions/AskUserQuestionPermissionRequest/AskUserQuestionPermissionRequest.tsx`；同目录 `use-multiple-choice-state.ts`、`QuestionView.tsx`、`SubmitQuestionsView.tsx` | 选择、自由回答、切题保存、提交与取消；单题与多题路径区别 | `StdioQuestionCoordinator`、问卷协议、`experience/runtime-app.tsx`；答案真实返回原工具调用，不转成额外普通消息 |
| R6 `/plan` | `src/commands/plan/plan.tsx`；`src/tools/ExitPlanModeTool/ExitPlanModeV2Tool.ts`、`UI.tsx`；`src/components/permissions/ExitPlanModePermissionRequest/ExitPlanModePermissionRequest.tsx` | 进入模式、当前计划展示、审核与执行的边界；审核不代表任务完成 | `HeadlessRuntimeSession`、`RuntimeStdioCommandHandler`、`experience/runtime.ts`；三个审核操作及最终交付闭环 |

已明确的偏差：

- Evidence Ledger 是本项目独立验收契约，不能声称参考源码存在同一机制。
- 用户已确认问卷支持最终复核；参考中部分单题路径可能直接提交，本项目保留已确认的复核流程。
- 本轮只提供 `/plan` 主入口，不迁入参考的附加计划命令。
- 已确认保留“简短错误原因与恢复状态”，这是本次明确选择的产品行为，不能包装成源码原样实现。

## 3. 分批实施内容

### 第一批：修复验证声明错误与恢复呈现

**复现事实：**模型将验证工具填成未注册的 `web_search`，校验拒绝；随后使用相同验证要求身份改成 `run_command` 并成功。当前界面只留下“记录验证要求／工具参数无效”。

实施要求：

1. **提前提供准确的工具范围。**
   `PlanEvidenceDeclarationTool` 的模型可见定义补充当前可信验证工具名单，复用现有 `trustedVerificationTools` 集合，避免另维护名单。说明文字、参数校验、执行时复检必须使用同一来源。保留服务端拒绝未注册工具的校验；不自动把模型填写的工具替换成另一个工具。

2. **增加安全、结构化的失败原因。**
   在现有 `tool.failed` 上增加可选的原因码，用于区分“验证工具当前不可用”与一般参数错误。由宿主映射成可理解的中文。不得把完整异常、`details`、参数 JSON 或内部计划身份直接透传到界面。

3. **由宿主确定恢复关联。**
   只在同一 Run、同一验证要求身份、后续声明真实成功后，将成功调用关联到前一次失败调用。通过可选的 `recoveredFailureOrdinal` 表达关联，TUI 不能仅凭工具名相同或下一次调用成功猜测恢复。

4. **保留一条简短错误记录。**
   失败时说明“验证方式使用了当前不可用的工具”，不提前声称正在恢复。确认恢复后，该记录显示“已修正验证方式，继续规划”；详情保留原失败及恢复事实。历史工具结果仍是失败，不改写为成功。

5. **未恢复必须诚实结束。**
   达到已有纠正上限、取消、断连或执行失败时，保留准确终态，不显示“已恢复”。既有完成 Gate、权限与重复失败治理不得放宽。

接口兼容：

- stdio 仅增加可选字段，旧消费者可以忽略。
- 新 TUI 遇到旧宿主缺失字段时保留通用错误提示，不猜测具体原因和恢复状态。
- 不新增公共命令、Agent Loop 或自动重放执行路径。
- 新决策写入 ADR，并链接 ADR-094；标明它是独立实现与已确认产品选择。

**本批出口：**原天气场景中不再出现无法理解且永久悬挂的内部参数错误；发生错误时能区分“未修复”和“已修复”。

### 第二批：核心显示与焦点对照

按 R1—R4 顺序，对已有实现逐项检查，只修实际发现的偏差：

- 正常内部任务、计划编排调用不逐条刷屏。
- 普通搜索、文件、命令工具保留真实摘要与详情；失败不被分组掩盖。
- `Ctrl+O` 展开和返回后草稿不变，长命令和审批选项可读、可达。
- Shell 名称、执行目录和命令来自后端真实数据。
- 流式回答和最终回答去重；取消后的迟到事件不重新激活运行或面板。
- 沿用已认可布局，不增加新的任务面板、菜单、状态栏或快捷键。

**本批出口：**每个可见元素能对应 R1—R4 的参考行为或已确认需求，并有尺寸与按键证据。

### 第三批：问卷与 `/plan` 闭环

按 R5—R6 完成接入复验：

- 单选、多选、自由回答、切题、返回修改、最终复核一次提交。
- 必答缺失不能提交；取消、旧请求和重复提交不能进入模型结果链。
- `/plan` 进入模式，`/plan 任务` 直接规划，已有计划可再次查看。
- 审核固定“确认并执行、提出修改意见、取消”；确认保留上下文，执行中继续人工审批。
- 无文件查询使用真实工具结果验收，不能为满足机制强制创建文件。
- 有文件任务核对文件确实存在、内容满足任务、结果可访问。
- 工具结果、Runtime、协议、持久化状态和界面终态一致；不能停在 Task COMPLETED。

**本批出口：**四条核心流程都走到用户能看到或访问结果，并能继续下一轮。

## 4. 验收场景与判定

按“确定性测试 → Java/stdio/TUI 跨进程 → PTY → 已配置真实 Provider”的顺序执行。参考与本项目使用独立编写的同一场景；测试不得复制参考源码或固定自然语言回答。

| 场景 | 必须观察的结果 |
|---|---|
| 普通中文问答 | 中文多行可编辑，流式与最终正文不重复，下一轮正常 |
| 运行中保留草稿 | Enter 不自动排队；展开详情、审批结束后草稿仍在 |
| 未注册验证工具 | 声明被拒绝、不写入有效证据；界面显示具体安全原因 |
| 同一要求修正成功 | 仅对应失败显示已恢复；原失败结果与审计记录不被改写 |
| 无关成功、不同要求或不同 Run | 不得误标前一次错误已恢复 |
| 验证工具范围变化或为空 | 模型可见范围与实际校验一致；不能伪造可用工具 |
| 搜索分组与失败 | 合适调用显示摘要，失败独立可见，展开是真实参数和结果 |
| Shell 审批与非零退出 | 精确命令、目录、Shell 可读；拒绝不执行；退出码与输出一致 |
| 多题问卷 | 单选、多选、自由回答、返回修改后整批提交，仅生成一个对应工具结果 |
| 问卷取消或迟到提交 | 旧答案不能送入新 Run，界面可恢复输入 |
| `/plan` 查询青岛七天天气 | 审核后才执行；最终正文可见；证据、Plan 终态与结果一致 |
| `/plan` 创建临时测试文件 | 用户确认与真实写入审批后执行；文件可访问且内容正确；最终回答说明结果 |
| 计划修改与拒绝 | 修改复用当前规划链；拒绝后无执行副作用 |
| 缺证据、工具失败、断连、取消 | 明确非成功终态，无空白结束、无虚假完成、无自动重放 |
| 窄屏与长内容 | 40/80/120 列 × 24/35 行检查折行、焦点、详情和操作可达性 |

每条证据记录：

- 工作树与构建身份、启动入口、独立输入、具体按键。
- 参考预期、本项目实测、差异和通过状态。
- 明确标注源码观察、离线渲染、PTY 文本、物理终端截图或用户验收。
- 无法运行参考 UI 时保留“视觉对照未完成”，不能用源码阅读或本项目截图替代。

回归包含相关 Java 模块、TUI、真实 Java 跨进程路径；修改协议时增加缺字段兼容测试。测试日志不得包含凭证、完整 Prompt 或未经处理的用户数据。

## 5. 交付材料与停止条件

每一批完成后更新本文执行表，不一次宣称整体完成：

| 批次 | 初始状态 | 必须附带的交付 |
|---|---|---|
| 第一批：验证错误与恢复 | 已完成本批验收 | ADR-095、stdio 可选字段兼容、Fake/Java/stdio/TUI 失败与恢复对照已补；真实 Provider 正常天气闭环和 Fake Model + 生产链 Windows PTY 失败/恢复结果见 S15 核心证据。真实 Provider 样本未触发失败恢复分支，未伪装为已覆盖 |
| 第二批：显示与焦点 | 已完成本批验收 | ADR-096；唯一复现偏差为无审批命令缺可信显示元组，已由实际执行器同源修复。自动六尺寸、12 秒 Java→stdio→Ink 5/5、Windows PTY 按键与持久化终态均通过；不声称参考视觉一致 |
| 第三批：问卷与计划 | 方向验证候选版 | ADR-097；问卷成功/取消与下一轮、拒绝后全新 identity、Jsonl/manifest 恢复、非 Git verification applicability、显式 correction、定向长 Plan capability 与 PowerShell 7 Unicode/固定 launcher 已收口。同一真实 Session revision 24→37 后五条 required 全 PASS、Plan COMPLETED、正文可见且文件 hash 不变；Java 155/155、修复后 TypeScript build、全 TUI 349 pass/7 skip、显式 Java→stdio→Ink 7/7 均通过。真实 `StartCodejDev.ps1 --tui-next` 已验证混合中文/emoji stdout、纯文本中文 stderr、预期 exit 7、正确非成功说明及下一轮回 idle。Windows PowerShell 5.1、参考 UI 视觉一致性、复杂 Provider/跨平台和 Markdown 反引号呈现仍是差距；Capability 等级不变，S15 OPEN |

统一要求：

- 在 [核心验收证据](../evidence/S15-tui-next-core.md) 中链接本批证据。
- 按 AGENTS.md 更新矩阵、进度状态、相关设计说明，保持能力等级与证据一致。
- 运行看板生成、`--check`、`--self-test`；未通过不得宣称完成。
- 每批交付说明只写新增能力、准确启动方式、参考对照结论、实际验证与剩余差距。
- 不扩展 `/plan-*`、完整历史恢复、后台任务、插件管理、配置菜单或新的任务管理产品界面。
- 不用测试数量估算体验百分比，不把基础闭环验证转交用户首次发现。
- 接手者不得以隐藏错误、取消验收 Gate 或绕开工具结果链路完成本计划。

**完成标准：核心流程交付可见、错误和恢复可解释、源码对照可追踪、实际入口验收可复现。**
