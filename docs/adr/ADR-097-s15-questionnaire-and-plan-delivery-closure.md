# ADR-097：问卷与计划审核必须收敛到真实交付终态

- 状态：Proposed
- 日期：2026-09-11
- Stage：S15；Stage Exit 保持 OPEN
- Feature IDs：CLI-01/05/09 保持 L2，PLAN-01 保持 L1
- 公开行为基线：R2026.03（本批未取得可重放黑盒行为）
- 授权参考：AUTH-SRC-2026-07-29-A，Observed（源码职责）/ Inferred（本项目协议）/ Unknown（准确版本、稳定协议与视觉一致性）
- 关联决策：[ADR-094](./ADR-094-s15-plan-completion-evidence.md)、[ADR-096](./ADR-096-s15-tui-display-focus-and-command-metadata.md)

## 1. 问题、范围与不变量

第三批只复核 `docs/plans/tui-core-handoff.md` R5—R6：结构化问卷和单入口 `/plan` 的审核、执行与最终交付。保留用户已确认的问卷最终复核、计划固定三个审核操作、`KEEP` 上下文策略、执行阶段人工审批、Evidence/Task 完成 Gate、取消与迟到事件隔离；不增加菜单、状态栏、快捷键、额外计划命令或新的 Agent Loop。

问卷“已提交”、计划“已审核”、Tool“成功”和 Task“COMPLETED”都不是用户交付终态。验收必须继续观察真实模型正文或可访问文件、Runtime/协议/持久化终态和下一轮输入，并在失败或取消时保持非成功事实。

## 2. R5—R6 受控源码研究与可证伪对照

按 AGENTS.md 5.1/5.3，只读研究仓库外 `G:\AI Cloud\claude-code-main`。只提炼职责、状态转换、隐藏条件和验证方法，不复制或翻译函数体、Prompt、注释、错误文案、私有类型、常量、Fixture 或源码字节。

| 用户场景 / Feature ID | 参考路径与符号 / 基线 / 证据分类 | 参考可观察行为（含不显示什么） | 本项目采用机制 / 必要偏差 | 可证伪场景与实施前状态 |
| --- | --- | --- | --- | --- |
| R5 多题状态与切题 / CLI-01、CLI-09 | `AskUserQuestionPermissionRequest`、`QuestionView`、`useMultipleChoiceState`；AUTH-SRC-2026-07-29-A；Observed（职责） | 问题结构先校验；每题选择和自由输入由独立状态持有；前后切题不丢答案；单选、多选和自由输入按题型消费按键 | `question.requested` 提供封闭问题数组；`RuntimeUi.answers/free` 按 questionId 保存；Tab/方向键切题。项目不接入参考的图片、外部编辑器和附加访谈动作 | 单选、多选、自由回答、返回修改后答案仍在；缺环境能力不显示伪入口。现有自动测试已有主路径，需核对边界与实际按键 |
| R5 必答、最终复核与一次提交 / CLI-01、CLI-05 | `SubmitQuestionsView` 与 `AskUserQuestionPermissionRequest` 的最终提交职责；AUTH-SRC-2026-07-29-A；Observed（职责） | 最终视图汇总答案并标出未完成题；取消与提交是不同终态；一次完整答案集合返回原 Tool 调用 | 本项目保留已确认的“所有题型都进入最终复核”差异；TUI 在提交页检查每题非空，Java `UserQuestionRequest.accepts` 再次权威校验，`StdioQuestionCoordinator` 只接受当前 callId 的首次完整答案 | 必答缺失跳回对应题；修改后仅一次 `question.resolve`，原 `ask_user_questions` 只产生一个 ToolResult，模型最终正文可见并恢复输入 |
| R5 取消、断连和迟到提交 / CLI-01、CLI-09 | `AskUserQuestionPermissionRequest` 的拒绝/取消职责；AUTH-SRC-2026-07-29-A；Observed（职责）/ Inferred（本项目关联协议） | 取消关闭当前交互，不把部分答案当作成功结果；旧交互不得污染后续运行 | Esc 走当前 Run 取消；Runtime 以 request/session/run/sequence 过滤事件，Java coordinator 以 callId 和 pending identity 原子接受或拒绝。断连关闭 pending，不自动重放 | Esc 后为取消终态；旧 callId、重复提交、取消后的迟到 `question.requested`/resolve 均不能进入模型结果链或重开面板 |
| R6 `/plan` 进入、查看与审核 / PLAN-01、CLI-05 | `commands/plan/plan.tsx`、`ExitPlanModeV2Tool`、`ExitPlanModePermissionRequest`、`query.ts`；AUTH-SRC-2026-07-29-A；Observed（职责） | `/plan` 只切换规划模式或查看已有计划；退出计划在有效计划上进入用户审核，反馈保留规划语义并进入后续模型轮，批准恢复执行语义；查询 ToolResult 进入下一模型轮。受控快照中 VerifyPlanExecution 实现目录缺失、注册处仅为可选导入且 REPL 分支禁用，不能据此推断 NEEDS_VERIFICATION 恢复交互 | 本项目仅保留 `/plan` 与 `/plan 任务`；durable Plan 必须形成 `plan.review.requested`。面板固定“确认并执行、提出修改意见、取消”，不迁入附加命令或额外产品模式；Evidence Ledger、NEEDS_VERIFICATION 与恢复状态机均为项目独立设计 | 空白成功不能冒充审核；三个操作必须分别产生批准、继续规划或拒绝的宿主决定；计划正文可读且普通编排调用隐藏。无参 `/plan` 保持进入/查看语义，不自动重开失败计划审核 |
| R6 KEEP 与审核后执行 / PLAN-01、CLI-05 | `ExitPlanModePermissionRequest` 的 keep-context 允许路径、`ExitPlanModeV2Tool` 的模式恢复与结果职责；AUTH-SRC-2026-07-29-A；Observed（职责） | 用户批准后保留必要上下文并恢复执行权限模式；审核只允许继续实现，不代表实现已经完成；执行中的有副作用操作仍受权限控制 | `plan.review.resolve` 固定 `contextPolicy=KEEP`；Java 原子持久化 APPROVED 并接受 execution Run，`plan.execution.accepted` 早于该 Run；写文件和命令继续经过统一 Approval/Pipeline | 协议、Runtime、Plan manifest 与 UI 对批准/执行一致；执行 Tool 仍弹人工审批。只出现审核或 Task 完成而没有正文/产物必须判失败 |
| R6 查询与文件任务最终交付 / PLAN-01 | 参考源码未证明 codej 的 Evidence Ledger、持久化格式或同一 stdio 协议；Inferred / Unknown | 计划完成应回到可继续交互，并以用户可见结果而非内部状态结束 | 青岛天气查询使用真实读取/网络 Tool 结果，不强制造文件；临时文件任务必须在 Workspace 中读取实际内容。Evidence/Task、ToolResult、Run stopReason、Plan manifest 和 TUI finalText 联合验收 | Fake→Java/stdio/TUI→PTY→已配置 Provider。天气必须有真实正文和下一轮；文件必须实际存在、内容匹配、最终正文可见。失败/拒绝/取消不得显示成功 |

## 3. 独立协议和安全契约

1. `question.requested` 的 questions、optionId、multiSelect 与 allowFreeText 是封闭结构；UI 只提交 questionId、已声明 optionId 和有界 freeText，不回传原始 Tool JSON。
2. 必答由 UI 提前提示与 Java `UserQuestionRequest.accepts` 双层验证；Java 是权威边界。重复 questionId、未知 optionId、非法自由回答、重复或迟到 callId 均失败关闭。
3. TUI 只有最终复核确认时调用一次 `resolveQuestionnaire`。选项切换、切题和返回修改只更新本地暂态，不提前产生 ToolResult。
4. Plan 审核决定只允许 `APPROVE_USER`、`CONTINUE_PLANNING`、`REJECT`；继续规划必须带非空反馈，批准固定 `KEEP`，旧 plan identity 或 workspace digest 变化按 stale 拒绝。
5. 批准只代表新的 execution Run 被可靠接受。文件/命令仍经过统一 Permission、Approval、Hook 与 Pipeline；最终完成仍由 ADR-094 的 Evidence/Task Gate 和持久化终态决定。
6. 取消后只接受当前 Run 的终态，拒绝迟到问题、审批、Tool 和正文；连接失败清空 pending 但保留用户草稿，不自动重放副作用。
7. 证据不记录 Provider 密钥、地址、用户 Session、完整 Prompt、完整 Tool 参数、完整异常或敏感日志；真实 Provider 无法覆盖的分支如实标为未验证。
8. `REJECTED` 等明确结束且没有恢复责任的 Plan 不得永久占用 Session 的规划入口。下一次显式 `/plan 任务` 必须创建全新 planId、revision 1、空 Evidence Ledger 与独立 Task cohort；旧终态身份、正文、Evidence 和执行状态只保留为 journal 历史，不复制进新计划。该轮换使用旧 planId/revision/digest/status 的宿主 CAS，并保持 canonical journal 先于 manifest 投影提交；`APPROVED/EXECUTING/PAUSED/NEEDS_VERIFICATION/DIGEST_CONFLICT` 等仍有执行、恢复或冲突责任的状态禁止轮换。恢复重放必须以 journal 中最后一次 PlanArtifact 事实重建新 identity，不能因本地旧 manifest 回退。
9. Windows 固定 PowerShell 的审批正文与执行正文必须语义相同。执行边缘使用固定、短小的 UTF-16LE `-EncodedCommand` launcher，已批准正文只进入本次子进程的 Process environment；launcher 执行前清除 payload，并固定 Console/管道 UTF-8。TUI/审批仍显示原始受控命令，不显示 launcher 或环境载荷，也不改变 Permission selector、Shell 身份、工作目录、timeout/cancel 和纯文本 stderr 语义。
10. Plan verification Tool 的模型可见集合必须同时满足“已注册可信 Tool”和“当前 Workspace 前提可用”。非 Git Workspace 不得声明 required `git_status`/`git_diff`；历史 requirement 在重开后不可用时，审核 Gate 必须要求以同一 requirementId 原位替换，不能只修改 Markdown 或新增另一条要求。
11. durable `NEEDS_VERIFICATION` 只由显式 `/plan <修正请求>` 表达纠正意图；无参 `/plan` 永远保持进入/查看语义。纠正复用同一 planId、Task cohort 与 requirementId，先回到 DRAFT，再形成新审核并由用户批准；contextPolicy 固定 `KEEP`。transport/enqueue 失败后 durable DRAFT 必须可由同一 Session 重开继续，不能自动重放副作用。
12. 长 Plan 的定向分片只在 stdio 初始化协商 `directedChunkInputV1` 后发送。新客户端面对旧 Host 时长 Plan 失败关闭，不能降级成普通 Run；未协商的普通长 Run 继续使用旧分片格式。request/session/inputId/digest/chunk 顺序和 replay 绑定保持不变。

## 4. 实现与验证顺序

1. 先运行并扩展现有问卷与 Plan Fake/协议/界面测试，复现实际偏差；未复现路径不重写。
2. 若发现偏差，最小修改 TUI/stdio/Runtime 边缘，不改变 Permission、Plan 持久化或完成 Gate 的权威所有者。
3. 建立确定性 Java Fake→生产 stdio→Ink Fixture：问卷覆盖单选、多选、自由回答、返回修改、必答、一次 ToolResult、取消迟到；Plan 覆盖三审核操作、KEEP、执行审批、查询正文和文件产物。
4. 协调者使用实际 Windows PTY 验证按键、屏幕、真实文件和持久化终态；再在已配置 Provider 上运行青岛天气与临时文件独立场景。Fake 不能替代 Provider 质量结论。
5. 运行相关 Java/TUI/跨进程回归、`git diff --check` 和 Dashboard generate/check/self-test；更新 S15 证据、handoff、矩阵摘要与 progress-state，不提升 Capability、不关闭 S15。

## 5. 实际复现、修复与边界

第三批先复验已有状态机，没有重写已通过的问卷必答、按题保存、最终复核、整批一次提交、取消迟到隔离、Plan 三操作、`KEEP` 和执行审批。新增跨进程断言后复现两项生产偏差：

1. 用户拒绝 durable Plan 后，TUI 按已确认语义继续处于 plan 模式；下一次显式 `/plan 新任务` 到达 Java `preparePlanRun` 时，旧 `REJECTED` 工件却被当作不可继续状态，产生 `run.launch.failed`。修复没有把拒绝强行切回 chat，而是在旧终态完整 CAS 下轮换全新 identity。写入、Jsonl 重放和 manifest 恢复统一使用 `PlanLifecyclePolicy.validIdentityRotation`；新工件必须是不同 planId、同 Session、revision 1 DRAFT、时间单调且首版时间一致、无 ExecutionBrief/恢复标记，并具有完整未批准空 Ledger。旧 projection 在 journal 重放遇到合法轮换时清空，旧 manifest 可由 canonical journal 收敛到新 identity；仍有执行、恢复或冲突责任的状态继续失败关闭。
2. 真实 Provider 文件验收中，审批面板显示的 PowerShell 双引号命令在 Java `ProcessBuilder` → `-Command` 原生命令行编码后丢失引号，脚本把字符串首词误当命令。独立无副作用测试复现后，Windows 固定 Shell 改为固定 launcher argv：launcher 由 UTF-16LE `-EncodedCommand` 传递，原始正文仅进入本次 Process environment，执行前清除且不被孙进程继承；`-OutputFormat Text` 保持纯文本错误流，Console 与 `$OutputEncoding` 固定 UTF-8。PowerShell 7 已验证中文、supplementary Unicode、Host/raw 混合输出、`Write-Error`、native failure、显式 exit、timeout/cancel 和语言边界；诊断 `toString()` 只显示计数，不展开 argv/environment/stdin。Windows PowerShell 5.1 因本机策略限制未实机验证，仍是明确差距。

第三批 Fixture 和新界面跨进程测试同时补齐：问卷完成/取消后的下一轮、唯一原 ToolResult；Plan 反馈、批准、两次人工文件审批、实际目标文件内容与可访问性、Task/Evidence/Run/UI 终态、聊天下一轮；拒绝后无写入/审批，并能以不同 planId 形成新的审核。Fake Model 只替代模型选择，不替代生产 Tool、Permission、stdio、持久化或 TUI。

最终 Provider 复验又发现一个独立失败：非 Git 临时 Workspace 中，`git_status`/`git_diff` 虽已注册，却不具备可执行前提；旧实现仅按 BUILT_IN 注册身份生成可信集合，因此模型能持久声明无法满足的 required verification。真实文件内容和双引号字节校验均成功，但 Gate 正确收敛为 `NEEDS_VERIFICATION`，不显示最终正文。修复后 declaration 与 review Gate 共用当前 Workspace 的可用工具事实，并在每轮 planning request 投影完整 durable Ledger；遗留 required locator 不可用时必须以同一 requirementId 原位替换。纠正只由显式 `/plan <修正请求>` 触发，无参 `/plan` 继续进入/查看，不自动重开审核、不自动跳过、不初始化 Git，也不新增旧 UI 的 `/plan-resume` 命令。

## 6. 当前 Unknown 与停止条件

未实际运行参考 UI，因此只能声称源码职责已对照，不能声称视觉一致。参考的图片、外部编辑器、附加访谈、自动/清空上下文及团队审批分支不属于本批；若真实 Provider、持久化或工作区事实与界面不一致，立即按失败记录并先修根因，不得放宽验收或用 Fake 文本替代交付。

第三批当前交付为**收窄后的方向验证候选版**，不是整体重构完成。已固定：无参 `/plan` 仅进入/查看；只有显式 `/plan <修正请求>` 才纠正 durable `NEEDS_VERIFICATION`；非 Git Workspace 不再声明不可满足的 Git required verification；PowerShell 7 Unicode 输出与长正文启动边界已有生产回归。能力等级不变，S15 Stage Exit 保持 OPEN。Windows PowerShell 5.1、最终 C 真实入口、参考 UI 视觉一致性和复杂 Provider 质量仍未验证。

## 7. 最终证据与方向验证边界

- B 初次真实场景在同一 durable Session 的 revision 24 停于 `NEEDS_VERIFICATION`，证明旧 required Git locator 未因 Markdown 修改而消失。修复后仍使用同一 Session、同一 planId、同一 Task cohort，并以原 requirementId 逐条替换不可用 locator；revision 37 达到 `COMPLETED`，五条 required requirement 全部 `PASSED`，最终正文在下一模型轮可见，目标文件 hash 未变化。该结果不依赖 `git init`、重写文件、联网或安装。
- 最新协调者独立 Java 定向回归为 155/155，通过 Workspace applicability、CommandShell 11、Local command 6、RunCommand 3、Plan privacy 1、Headless 60、durable 13、stdio 60，并包含 correction transport failure 后同 Session 重启恢复。最小 TS capability port 类型修复后，本工作树 `tsc --noEmit`、协调者独立 build、全 TUI 349 passed / 7 skipped、显式 Java→stdio→Ink 7/7 均通过。
- PowerShell 7 已覆盖固定 launcher、8192 supplementary code points、UTF-8 Host/raw 混合输出、payload 清除/子进程不继承、纯文本 stderr 和退出语义。协调者使用本轮新构建从生产 `StartCodejDev.ps1 --tui-next` 入口验证：Allow Once 后混合中文/emoji stdout、纯文本中文 stderr、预期 exit 7、`Ctrl+O` 详情、正确的非成功最终说明及无工具下一轮均可见并返回 idle；未发生文件读写、联网或安装。Windows PowerShell 5.1 未实机验证。
- 模型 Markdown 使用单反引号包裹包含 PowerShell 反引号的正文时，终端可能把换行转义呈现为可见 `n`；这是保留的显示差距，不影响已验证的执行 argv/environment 事实，但仍需后续独立处理。
- 本批不补新的 enqueue 故障矩阵、不扩展命令或交互、不提升 CLI-01/05/09、TOOL-10 或 PLAN-01 等级，也不关闭 S15。
