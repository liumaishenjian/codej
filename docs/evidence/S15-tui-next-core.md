# S15：新界面的真实核心流程验收

日期：2026-09-10。工作树 feat/tui-redesign，main 基线 e51e6b5，未提交。
FR-CLI-001/003/005、FR-PERM-003、FR-EVENT-002、NFR-003；
CLI-01/03/04/05/09 保持 L2，TOOL-11/PLAN-01 保持 L1。S15/P7 仍 OPEN。

## 启动与操作

在目标项目目录使用 PowerShell 7：

    & 'G:\AI Cloud\cc-java\scripts\StartCodejDev.ps1' --tui-next

也可追加 --workspace 'E:\ai project\choco-backend'。开发构建已准备好，复用现有用户模型配置。
离线演示仍是 npm.cmd --prefix 'G:\AI Cloud\cc-java\cc-java-tui' run preview:tui。
默认安装入口未替换。

- 普通输入使用真实 Java Run；流式与最终正文去重，运行中草稿保留，Enter 不排队。
- Ctrl+J / Alt+Enter 换行；Ctrl+O 工具详情；PgUp/PgDn 回看，End 回到最新。
- 工具按 Run+ordinal 原位更新；同回合同类相邻搜索折叠，失败单独保留。
- 审批展示实际 Shell、完整命令和目录；长内容可翻页，本次允许/会话允许/拒绝经原管线。
- 问卷支持单选、多选、自由文字、多题及最终复核；Tab/Shift+Tab切题、空格多选，
  文字Enter保存，复核Enter整批提交。旧单题兼容。
- /plan进入规划，/plan任务直接开始；确认执行、反馈修改或取消。
  确认固定 APPROVE_USER + KEEP，执行中继续人工审批。
- Esc停止运行；正常尺寸文字编辑面板首次Esc返回选项，再次Esc停止。
  小于40×24直接Esc停止。Ctrl+C取消并退出。

## 源码对照

只读 AUTH-SRC-2026-07-29-A，G:\AI Cloud\claude-code-main，具体Revision Unknown。
Observed（源码）机制与独立实现：

| 参考入口 | 采用机制 | 本项目 |
| --- | --- | --- |
| src/screens/REPL.tsx、src/components/PromptInput/ | 分区、焦点接管与取消清理 | experience/runtime-app.tsx |
| src/utils/groupToolUses.ts、src/components/messages/GroupedToolUseContent.tsx | 分组、摘要和详情 | experience/runtime.ts、runtime-screen.tsx |
| src/tools/BashTool/BashToolResultMessage.tsx、src/components/permissions/BashPermissionRequest/ | 完整命令审批、输出折叠 | RuntimeStdioCommandHandler、runtime-screen.tsx |
| src/components/permissions/AskUserQuestionPermissionRequest/ | 选择/文字/复核分开，切题保留 | ADR-092、StdioQuestionCoordinator |
| src/commands/plan/plan.tsx、src/tools/ExitPlanModeTool/ | 单入口模式、审核后执行 | 现有原子review、experience/runtime.ts |

采用可独立表达机制，不引入参考字节、内部Prompt或私有类型表达。
复用 React/Ink、Marked、StdioClient 与 Java Pipeline；没有新 Agent Loop。

## 验证

- TUI 23文件335项通过，含新真实Java跨进程测试：target/tui-next-full-tests.log。
  最后显示计时/说明排版修正后19项定向复验通过（target/tui-next-final-tests.log），未用数量推算体验百分比。
- Java+Ink：完整问卷唯一工具结果；Plan反馈→确认→两次文件审批→完成→普通对话；
  搜索→Shell审批→stdout/stderr→退出7；运行中取消且不继续模型回合。
  test/experience-java-e2e.test.tsx 需 CC_JAVA_TEST_CLASSPATH 指向编译测试类和依赖。
- 相关Java全回归1299项、0失败、33跳过；最终问卷定向61/61，
  target/questionnaire-java-regression.log、questionnaire-final-targeted.log。
- 新公共契约严格Javadoc 0warning；全仓聚合因既有警告失败，
  target/questionnaire-javadoc-focused.log、questionnaire-javadoc.log。未宣称完整verify通过。
- 启动器66断言、入口解析4项、显示元数据Java4项通过，见 S15-tui-next-launcher.md。
- 真Windows PTY：编译index --tui-next +Java问卷宿主，逐键单选/多选/文字/复核/提交，
  Ctrl+C退出0并恢复光标。首次测试Session误放Workspace内被安全规则拒绝；
  改为工作区外临时Session后通过，未放宽规则。
- 真Provider：现有用户配置的Print短问答退出0，target/tui-next-provider-smoke.json；
  另通过正式 --tui-next 入口，在空临时目录实际输入无工具短问答，
  看到配置模型、流式“TUI接入成功”、结束回到输入，Ctrl+C退出0。
  未记录地址、凭证或业务数据。
- 视觉：40/80/120列×24/35行长问卷、Markdown折行自动检查；
  长说明可滚、选择与退出可见。target/tui-next/approval.png、question.png、plan.png
  经人工查看，是独立样例的彩色Ink渲染帧，**不是物理终端截图**。
  PTY颜色能力受环境限制，未宣称彩色物理窗口验收。

## Gate与剩余范围

G0范围、G1研究、G2契约、G3实现、G4相关回归有工作树证据；
G5有键盘/PTY/最小Provider观察，维护者视觉验收待完成；
G6文档/矩阵/看板按实际证据更新，不提升等级或关闭Stage。
本批为基础Markdown，不承诺复杂表格、鼠标滚轮、完整native scrollback、历史恢复或长历史性能。
参数为宿主白名单摘要，正文≤4096码点；启发式敏感文本隐藏不是全面脱敏保证。
不提供审批附加说明、计划恢复/重试/步骤命令、后台任务、配置/插件管理。
仍缺真实Provider复杂规划质量样本、跨平台视觉与物理窗口人工验收。

## 2026-09-10 青岛天气规划空白修复（ADR-093）

真实输入 /plan 看下青岛未来七天天气，修复前模型直接说明无法查询，未保存计划或请求审核；
宿主却返回COMPLETED，同时隐藏规划模型文本，造成空白结束。日志中的用户任务文本本身不能证明入口模式。

修复复用已有 FinalAssistantHandler/FinalAssistantDecision，同一Run最多一次无审核工件纠正；
仍未形成待审核计划则INVALID_MODEL_RESPONSE。前端也兼容旧宿主的空白COMPLETED，
明确提示未生成可审核计划、未开始执行，可直接补充要求继续规划。
规划中的任务/计划内部工具只显示中文生命周期，收起及展开均不展示内部JSON或身份；
真实失败继续可见，普通搜索、文件与命令工具详情保留。

实际验证：
- 后端121项、0失败、1跳过（target/adr093-plan-final.log）。
- 完整TUI 336项通过（target/tui-plan-guard-tests.log）；最后内部工具展示修正后18项定向通过，
  最终跨进程定向结果另记录于target/tui-plan-guard-final-tests.log。
- 使用新编译生产类、真实Provider和WindowsPTY再次提交相同请求，
  约32秒返回“查询青岛未来七天天气”计划及三个审核操作；
  终态后允许确认。仅用Esc拒绝测试计划，再Ctrl+C退出0，未批准实际查询或修改。
  Session工件顺序DRAFT→AWAITING_APPROVAL→REJECTED；没有执行阶段。
- 这证明该复现场景恢复计划审核，不代表已查得天气数据或真实Provider质量全面验收。

能力等级不变，S15/P7继续OPEN。

## 2026-09-10 计划最终结果未交付与任务记录刷屏（ADR-094）

旧真实运行已经产生天气查询ToolResult与完整Assistant正文，但空Evidence Ledger被执行控制器误接受，
持久化终态却是NEEDS_VERIFICATION；stdio因此不交付正文，新界面又未显示verification.required。
已统一执行与持久化完成Gate；缺少required验收依据不得进入审核，
文本查询允许以真实注册工具成功结果作为VERIFICATION，不强制创建文件。
旧空Ledger计划明确typed失败，不能借任务COMPLETED或恢复日志文本伪造验收通过。

参考复核：AUTH-SRC-2026-07-29-A的TaskList/Get/Create/Update工具调用渲染返回null，
AssistantToolUseMessage据此不生成调用行；ExitPlanModeTool/UI也不生成普通调用行，
计划内容与审核有独立交互。此前把内部JSON替换为“查看任务清单/更新任务状态”中文行属于本项目自行设计，
不符合此次学习目标，现已撤销。正常Task和内部计划编排不刷屏，失败仍可见，审核面板和真实命令详情保留。
Evidence Ledger为本项目契约，不声称参考源存在同一算法。

实际验证：
- TUI build成功；完整TUI 23文件、339项通过，含新真实Java跨进程路径（target/adr094-tui-tests.log）。
- 真实Provider + Windows PTY，在独立空临时目录执行同题“/plan 看下青岛未来七天天气”，
  明确仅查询并在对话给结果，不创建文件或安装软件。
- 实际确认计划后逐次审核三条只读查询命令，前两条Python命令PROCESS_EXIT失败；
  模型改用PowerShell Invoke-WebRequest后成功，随后最终七天天气正文完整出现在界面并恢复输入。
  正常Task调用行未显示；测试进程启动后进一步移除其余内部计划行的改动由339项回归验证。
- 测试会话最后Plan COMPLETED，required VERIFICATION locator=run_command引用真实成功ToolResult且PASSED；
  Ctrl+C退出0，临时工作区没有生成交付文件。此为PTY按键及屏幕文本观察，不是物理窗口截图。
- 用户原会话正文另恢复为target/青岛天气-本次运行结果.md，标明来自原运行，
  不重新查询、不篡改原会话状态，也不把未验证原计划标成成功。

本批不提升CLI-05/CLI-09 L2、PLAN-01 L1，不关闭S15。
仍缺复杂真实Provider计划质量评测；命令首次生成的Windows适配质量与Markdown表格排版仍有差距。

后端最终回归136项，0失败/错误、1跳过（target/adr094-verified-final.log）；包括缺证据修复后精确解除审核重复失败拦截，其他权限/网络失败仍保留。

## 2026-09-10 第一批：验证声明失败原因与宿主权威恢复（ADR-095）

本批只实施 `docs/plans/tui-core-handoff.md` 第一批，不表示整体 UI 重构或 R1—R6 全部完成。

- `declare_plan_evidence` 的模型可见说明现在完整列出当前 Runtime 冻结的可信验证 Tool；说明、validate 与 execute 复检复用同一集合，不自动替换 locator。
- 未注册验证 Tool 保持 `INVALID_ARGUMENTS/VALIDATION`，并由 Tool 生成封闭安全原因 `verification_tool_unavailable`。stdio 只输出该白名单码，不透传 locator、requirementId、violations、异常或完整 details。
- validation 发生在 `BeforeTool` 之前，因此 Tool 在安全 detail 中附带已通过领域校验的内部 requirement 身份；宿主只在当前 `ActiveRun` 使用。多个同 requirement 失败时，后续真实成功关联最近一次失败 ordinal；不同 requirement、普通成功或不同 Run 不关联。
- TUI 继续隐藏正常 Task/计划编排调用。未恢复显示“验证方式使用了当前不可用的工具”；收到合法 `recoveredFailureOrdinal` 后对应旧失败显示“已修正验证方式，继续规划”。旧 `tool.failed` status、ToolResult、输出与审计记录仍保持失败。
- 新 TUI 遇到旧宿主缺失可选字段时保留“工具参数无效”等通用提示，不猜具体原因或恢复；未知原因码、错误事件类型、错误 Tool 或非法 ordinal 由协议拒绝。
- 未给 validation 增加 correction signature；原 Run-owned 参数纠错指纹、重复失败上限、完成 Gate、Permission 与 no-replay 不变量保持不变。

确定性证据：

- Java：`PlanEvidenceDeclarationToolTest` 2/2；`RuntimeStdioCommandHandlerTest` 54/54，合计 56/56。覆盖完整可信列表、空列表、validate/direct execute、原因码脱敏、同 requirement 最近失败关联和原失败不变；另经活动 Runtime 的生产 `publish` 事件链证明其他 Tool 伪造保留 detail 时 `tool.failed` 不含安全原因码，后续真实声明成功也不含恢复关联。
- TUI 定向：`protocol.test.ts` + `experience-runtime-app.test.tsx` 44/44；未知码/非法 ordinal 拒绝、旧字段兼容、不同 Run/无关联成功不猜测。Reducer 还要求目标旧记录同时是 `declare_plan_evidence`、失败且带已知原因，不能把其他 Tool 或通用声明失败标成恢复；ordinal 仅在协议与 reducer 内关联，展开只显示“原声明失败／后续声明修正成功”，不向用户显示内部序号。
- Java→stdio→Ink 跨进程：`experience-java-e2e.test.tsx` 4/4；新增 Fixture 经过真实 Core Pipeline 与生产 `RuntimeStdioCommandHandler`，两次失败、无关成功及同 requirement 成功的事件与界面一致。
- TUI 全量：TypeScript build 成功；22 文件通过、1 文件按环境跳过，338 项通过、4 项跳过。显式 Java classpath 跨进程已单独运行，不能把普通缺环境 skip 计作通过。
- 看板：`java scripts/ProgressDashboard.java` 已生成 `docs/progress.html`，`--check` 通过，`--self-test` 通过；确认 matrix digest `054933a830df`、code digest `49cf36b46c72`。Capability 等级不变，S15 保持 OPEN。

真实入口复验：

    pwsh -NoProfile -File .\docs\evidence\S15-tui-next-batch1-recheck.ps1

脚本只启动 `scripts/StartCodejDev.ps1 --tui-next` 并打印独立天气场景与安全记录边界，不采集 Provider 配置、凭证、完整 Prompt 或敏感日志。协调者于 2026-09-10 23:48—23:54 使用该真实入口和独立临时 workspace 完成天气计划：生成审核、确认、两次只读命令 Allow Once、展开看到真实输出与 `exitCode=0`、最终七天天气正文可见，并完成无工具下一轮回答；Plan manifest 为 `COMPLETED`，workspace 保持为空。该 Provider 运行没有先选未注册验证 Tool，因此只证明正常天气闭环，失败恢复线上分支仍明确标记“未覆盖”，未把正常成功伪装成恢复证据。审批阶段有物理屏幕观察；最终画面被其他窗口遮挡，不计作最终截图证据。

协调者于 2026-09-11 00:08—00:09 另在独立临时目录使用 `PlanEvidenceRecoveryFixtureMain` 与真实 `index --tui-next` 完成 Windows Orca PTY 复验：界面同时保留一次未恢复失败和最近一次已恢复记录，`Ctrl+O` 展开只显示“原声明失败／后续声明修正成功”且无 ordinal，普通成功内部调用隐藏；随后在审核面板以 `Esc` 取消，未进入执行。该证据是生产 stdio/TUI 路径上的 PTY 屏幕文本观察，Fixture 使用 Fake Model，不冒充真实 Provider 或参考 UI 视觉对照。

剩余差距：第三批 R5—R6 问卷与 `/plan` 全闭环仍待后续派发；Markdown 表格、首次 Windows 命令质量、复杂 Provider 计划质量、参考 UI 与跨平台视觉仍未完成。本批不提升 CLI-01/03/04/05/09、TOOL-11/PLAN-01，不关闭 S15。

## 2026-09-11 第二批：R1—R4 显示、焦点与命令执行元数据（ADR-096）

本批沿用已认可布局，只修复实际复现的偏差：Startup Allow 或 Session Grant 使 `run_command` 无需审批时，`tool.started` 原先缺少完整 command、shell 和 cwd，界面只能降级显示通用命令摘要。现由已实例化的 `LocalCommandExecutor` 根据真实 `ExecutionBackend.id()` 产生唯一非 Secret 显示描述，经 `RunCommandTool`、`LocalWorkspaceBootstrap` 与 `HeadlessRuntimeSession` 转交 stdio；审批与无审批路径复用同一 command preview。`tool.started` 表示已校验调用和已装配执行配置，不表示进程已启动或成功。

实现与协议边界：

- TUI 优先读取受控完整 `command`，而不是截断的 `parametersPreview`；shell/cwd 不由 UI 或 OS 猜测。
- command/shell/workingDirectory 只允许作为 `run_command/tool.started` 的完整封闭元组，cwd 固定为 workspace-relative `.`；审批与 started 共用稳定 shell ID 集。旧宿主缺字段时继续降级，不填造事实。
- 中文/emoji 草稿、Ctrl+O、审批焦点、相邻搜索分组、失败独立、内部成功隐藏、流式 final 替换 delta 及取消后迟到事件的既有状态机未被无依据重写，仅补强可证伪测试。
- 交互 Fixture 的三次执行使用完全相同的约 12 秒命令，使 Allow Session 的 exact selector 可验证，并为第三次执行后的 Esc 提供稳定窗口。取消测试等待当前第三个 `tool.started` 的 runId+ordinal 对应输出，再等待同 Run 取消终态，不能误命中历史输出。

确定性证据：

- Java：`RuntimeStdioCommandHandlerTest` 56、`StdioApprovalCoordinatorTest` 4、`HeadlessRuntimeSessionTest` 56、`LocalCommandExecutorTest` 5、`RunCommandToolTest` 3，共 124/124 通过。覆盖 Startup Allow 无审批元数据、审批/started 同源元组与真实执行器描述。
- TUI 全量：TypeScript build 成功；22 文件通过、1 文件按环境跳过，344 项通过、5 项跳过。定向覆盖协议稳定 shell ID 与命令控制字符、中文草稿跨 Ctrl+O/审批、搜索只按同 Run/turn/name 相邻成功分组、失败与 expanded 独立、流式正文去重、取消后迟到 approval/question/tool/delta 隔离。
- 自动六尺寸：40/80/120 列 × 24/35 行均检查行宽/行高、搜索分组、Shell、中文草稿、完整命令尾部、目录和审批第三选项/Esc 提示可达。该证据是离线 Ink 渲染约束，不是物理终端截图。
- Java→生产 stdio→Ink：最新 12 秒 Fixture 重新 test-compile 后 `experience-java-e2e.test.tsx` 5/5 通过，40.99 秒；旧 1400ms 结果不计入本次取消验收。
- Windows PTY：协调者在实际终端观察到运行中中文/emoji 草稿保留、Ctrl+O 展开完整长命令尾部与 `.`、数字 2 仅改变选择且 Enter 后 Allow Session、第二条同命令无再次审批、第三条命令真实输出后 Esc 显示取消/本轮停止并恢复输入。等待超过命令窗口后面板未重开，继续输入中文/emoji 草稿及 Ctrl+O 后仍保留。持久化日志中的前两次 Tool 成功、第三次 Tool 取消和 Run 用户取消终态与屏幕一致；Workspace 除 `.git` 外无业务文件。

可交互入口：

```powershell
pwsh -NoProfile -File .\docs\evidence\S15-tui-next-batch2-fixture.ps1
```

脚本默认打印并保留临时目录供复核；仅显式 `-Cleanup` 且绝对目标是系统 temp 直属 `codej-batch2-<32hex>`、不是 temp 根且不是重解析点时才删除。`-Check -Cleanup` 已通过。Fixture 只替代 ModelGateway，Tool、Permission/Session Grant、进程、stdio 与 TUI 均走生产代码；它不是线上 Provider 或参考 UI 视觉证据。

剩余差距：未实际运行参考 UI，不能声称视觉一致；线上 Provider 未参与本批确定性 Fixture；Markdown 表格、首次 Windows 命令生成质量、跨平台物理终端及第三批 R5—R6 仍为 OPEN。Capability 等级不变，S15 Stage Exit 保持 OPEN。

## 2026-09-11 第三批：R5—R6 问卷与 Plan 真实交付闭环（ADR-097）

本批没有重写已通过的问卷和 Plan 主状态机。新增可证伪跨进程场景后，问卷必答、单选/多选/自由回答、切题与返回修改、最终复核、原 `ask_user_questions` 唯一 ToolResult、取消后无成功 ToolResult、最终正文和下一轮均保持通过。

实际复现与修复检查点：

- 拒绝 Plan 后 TUI 按既有契约保持 plan 模式，但 Java 因 durable `REJECTED` 工件拒绝下一次显式 `/plan 新任务`，发出 `run.launch.failed`。现在旧终态只可经 planId/revision/digest/status 完整 CAS 轮换为全新 planId、revision 1 DRAFT；新工件不继承旧正文、Evidence、批准绑定、ExecutionBrief、验证恢复标记或 Task cohort。统一生命周期判断同时用于写入、Jsonl 重放和 manifest 恢复；带旧 projection 的 close→resume 及旧 manifest 故障恢复均回到 journal 中的新 identity。`APPROVED/EXECUTING/PAUSED/NEEDS_VERIFICATION/DIGEST_CONFLICT` 继续禁止轮换。
- Windows Java `ProcessBuilder` 直接向 PowerShell `-Command` 传递含双引号正文时，真实复现引号被原生命令行编码层消费。固定 Windows Shell 现使用短小固定 launcher：仅 launcher 进入 UTF-16LE `-EncodedCommand`，已批准正文放入本次 Process environment，执行前清除且不被孙进程继承；`-OutputFormat Text`、Console UTF-8 与 `$OutputEncoding` 同时固定。审批仍展示并匹配原命令。PowerShell 7 已覆盖中文、supplementary Unicode、Host/raw 混合流、纯文本 stderr、`Write-Error`、native failure、显式 exit 和语言边界；argv 与 8192 emoji 正文长度解耦。`ProcessInvocation` 与下游 `Plan` 的 `toString()` 只显示计数，不展开正文、环境、stdin 或 cleanup identity。Windows PowerShell 5.1 因本机策略限制未实机验证。
- WebSearch 全量回归的单项失败已隔离归因。审批拒绝后 loopback 命中数保持零且 Tool 失败；Fake 后续只返回文本，从未保存 PlanArtifact 或请求审核。宿主按既有 missing-review Gate 纠正一次后以 `INVALID_MODEL_RESPONSE`/`run.failed` 诚实结束。旧测试等待 `run.completed` 与既有 Gate 冲突，现改为精确断言 `run.failed`、stop reason、模型/Tool 次数、零网络命中和无 `plan.review.requested`；目标工作树隔离复跑通过。

确定性证据：

- Plan identity、问卷和持久化相关 Java 定向测试先前通过；覆盖时间倒退、完整空 Ledger、identity/CAS 漂移拒绝，以及合法轮换后的 journal 重放、旧 projection 清除、close→resume 和旧 manifest 修复。
- `RuntimeClient.initialize` 缺少 `directedChunkInputV1` 类型导致 TS2353；本批只补充该可选类型、不改变运行行为。本工作树 `tsc -p tsconfig.json --noEmit` 通过；协调者随后独立 `npm run build` exit 0、全 TUI 349 passed / 7 skipped，并以显式 `CC_JAVA_TEST_CLASSPATH` 运行 Java→stdio→Ink 7/7。两组均为类型修复后的当前快照证据。
- Java→生产 stdio→Ink：`experience-java-e2e.test.tsx` 7/7，44.64 秒。Plan Fixture 的 `.xlsx` 文件只承载精确 `correct-name` 测试文本，不冒充真实 Excel 工作簿；两项 Plan Task 最终 COMPLETED、`plan.verification.completed` 早于 `run.completed`、最终正文显示并接受普通下一轮。拒绝路径无写文件和审批，随后显式新任务形成不同 planId 的新审核且无 `run.launch.failed`。问卷成功和取消路径都恢复下一轮，成功路径只有一个对应 ToolResult。
- 可交互入口：`docs/evidence/S15-tui-next-batch3-fixture.ps1 -Scenario Questionnaire|Plan`。默认保留独立系统临时目录；`-Check -Cleanup` 对两个场景均通过同级目录、固定叶名和重解析点安全校验。Fixture 只替代 ModelGateway。
- 最新协调者独立 Java 定向回归 155/155 通过：Workspace applicability 1、CommandShell 11、Local command 6、RunCommand 3、Process Plan privacy 1、Headless 60、durable 13、stdio 60；包含显式 correction 在 transport failure 后留下 durable DRAFT、同 Session 重启继续并保持 planId 的恢复路径。未重复运行 Maven，也未把此前编译失败或修改前 green 计入该结果。

真实 Provider 与 PTY 结果：

- 问卷主路径、必答返回、修改保留、唯一 ToolResult、取消非成功和两种下一轮均通过实际入口验证。
- 从原 `REJECTED` Session 恢复后，显式新 `/plan` 形成不同 identity 并完成真实青岛七天天气查询；最终正文可见，下一轮可继续，临时 Workspace 未造文件。过程中 Tool 失败保持可见，不冒充全程无错。
- 同 Session 再开始文件计划并经过修改意见、KEEP、人工审批。`acceptance.txt` 实读为 37 字节 UTF-8 无 BOM，正文与末尾换行准确；`write_file`、`read_file` 和双引号字节比较均成功。revision 24 因非 Git Workspace 中遗留 required `git_status` 停于 `NEEDS_VERIFICATION`，证明其他成功 Tool 不能替代该 requirement，且界面没有虚假最终正文。
- 修复后 declaration 与 review Gate 共用当前 Workspace 的可用 verification Tool 集合，每轮 planning request 投影完整 durable Ledger；遗留 locator 当前不可用时，模型必须使用相同 requirementId 原位修正。用户固定产品语义为：无参 `/plan` 只进入/查看，只有显式 `/plan <修正请求>` 表达纠正意图。纠正保持同一 planId、同一 Task cohort，重新审核并由用户批准，contextPolicy 固定 `KEEP`。
- 同一真实 Session 从 revision 24 继续到 revision 37 后，五条 required requirement 全部 `PASSED`，Plan 为 `COMPLETED`，最终正文在下一模型轮可见；目标文件 hash 与失败检查点一致。过程中未执行 `git init`、联网、安装或改写真实验收文件。模型 Markdown 以单反引号包住含 PowerShell 反引号的文本时仍可能把换行转义显示为可见 `n`，该呈现差距保留。
- stdio 定向大输入增加协商能力 `directedChunkInputV1`：新客户端面对未确认能力的旧 Host 时长 Plan 失败关闭，不能降级为普通 Run；未协商普通长 Run 仍走旧分片。request/session/inputId/digest/chunk 顺序/replay 与 run acceptance gate 保持不变。

当前交付状态：**收窄后的方向验证候选版**。Capability 等级不变，CLI-01/05/09 保持 L2，TOOL-10/PLAN-01 保持 L1，S15 Stage Exit 仍 OPEN；未实际运行参考 UI，不声称视觉一致。

协调者已使用本轮新构建从生产 `StartCodejDev.ps1 --tui-next` 入口完成 C 终态：人工 Allow Once 后，同一 `run_command` 的 stdout 同时显示中文/emoji Host 输出与原始 UTF-8 中文/emoji，stderr 为纯文本中文错误，exit code 7 与 Tool failure 一致；`Ctrl+O` 详情和最终正文正确说明“命令非零失败、未重试、未修复”，没有把失败冒充成功，随后无工具下一轮可见并返回 idle。运行没有文件读写、联网或安装。stderr 中固定 launcher 的 `. $script` 行号是已知 Adapter 差异，本批不扩展修复。

剩余差距为 Windows PowerShell 5.1 实机、参考 UI 视觉一致性、复杂 Provider/跨平台质量及 Markdown 反引号呈现。

## 2026-09-12：交付证据职责与审核恢复（ADR-098）

原失败运行已从本机 journal 核对：真实 run_command 成功，required requirement 却绑定 ask_user_questions，因此最终停在 NEEDS_VERIFICATION。审核失败后模型虽已修正另一项条件，UI仍显示裸 plan_gate_blocked。不是目录切换造成此业务错误。

参考入口、职责、独立设计偏差及验证方法见 [ADR-098](../adr/ADR-098-s15-verification-tool-purpose.md)。宿主共用验证工具集合现在排除交互、内部状态及计划/委派编排；实际交付的成功 ToolResult 和文件验收不放宽。当前 Run 的真实审核事件才可将先前审核阻塞标为恢复，保留原始失败状态。命令和网页搜索成功摘要不再直接显示 shell/provenance 包装头，Ctrl+O 保留原输出；缺少结构化计数时不编造统计。

本次证据（工作树，未提交）：

- Java 定向 Maven：DurablePlanExecutionHandoffTest、HeadlessRuntimeSessionTest、RuntimeStdioCommandHandlerTest、PlanEvidenceDeclarationToolTest、ToolFailureFingerprintGovernanceTest，共141项，0失败、0错误、1跳过。新增原失败方式被拒绝、同 requirementId 修正为 run_command、真实命令成功、最终正文、COMPLETED 和零造文件断言。
- 最终 TypeScript build 通过；显式 CC_JAVA_TEST_CLASSPATH 的 TUI 回归23文件、358/358通过，47.67秒。命令排除 real-java-plan-e2e 与 installed-plan-e2e 两套需单独启用的在线/安装测试；Java→stdio→Ink 套件包含在358项内。新增折叠/展开、失败不可显示成功、真实审核恢复与跨Run隔离断言。新增测试曾因元组类型检查失败，修正后上述构建与回归重新通过。
- 正式 StartCodejDev.ps1 --tui-next、当前配置真实 Provider、独立临时 Workspace、实际 PTY按键：输入原请求 `/plan 看下青岛未来七天天气`，读取计划后确认执行，三次公共天气网页搜索均人工本次允许。Plan revision7 为 COMPLETED，唯一 required `qingdao-weather-7d` 使用 `web_search`，reference 为 PASSED/TOOL_RESULT。最终七天正文与提示可见，下一轮“请只回复：本轮对话可以继续。”得到对应回答、恢复idle，Ctrl+C退出0。未以进度更新代替结果。
- PTY记录验证的是实际程序交互，不是物理终端截图或参考产品视觉一致性。在线运行启动早于最终摘要显示调整；摘要调整由其后的最终构建、渲染和跨进程回归验证，不把旧PTY画面说成新摘要证据。

本次没有独立验证模型汇总的天气事实准确性。一次成功查询不能证明规模化Provider质量，也不能证明所有计划的语义验收充分。Markdown表格仍按文本折行，复杂排版及参考视觉一致性仍是差距。PLAN-01保持L1，CLI-05/09保持L2，S15 Exit保持OPEN。

## 2026-09-12：工具执行呈现切片（ADR-099）

源码职责、事件关联、默认/展开显示及必要偏差见[ADR-099对照表](../adr/ADR-099-s15-tool-output-interaction.md)。本批只改React/Ink适配器，Java生产代码、stdio协议和权限管线不变。取消同时识别既有兼容值及真实宿主operation_cancelled。审批前使用当前Run/ordinal绑定显示等待审批，完整命令仍可读，不把命令在工具行和全局状态重复多次。

确定性验收覆盖：同Run同回合相邻成功搜索、真实returnedItems及缺失统计、取消与失败隔离、宿主截断在折叠态可见；命令多行输出默认三终端行预览、运行跟随末尾、完整输出展开、非零退出不能显示成功、终态脱敏覆盖旧增量；Ctrl+O来回保留草稿/审批选择、回看中追加正文后返回原段落。40/80/120列×24/35行检查未越宽高且输入仍可见。第一次定向检查有一个旧“2项”文案断言失败，修改为明确“2次调用”后通过，不把调用数当命中数。

最终构建通过；显式CC_JAVA_TEST_CLASSPATH的离线回归23文件、362/362通过，48.09秒，含真实Java→stdio→Ink。命令排除需单独启用的real-java-plan-e2e及installed-plan-e2e；真实Provider证据单列如下。最后operation_cancelled映射修正后已重新构建并完整重跑。看板generate/check/self-test与git diff --check通过。未修改Java，本批不将既往Java模块测试数字列为重新执行。

生产入口实测（Windows PowerShell 7、真实Provider、系统临时Workspace、PTY实际按键）：

- 第一轮要求五行中文/stdout、stderr及exit7。模型生成多余的嵌套powershell包装，本轮人工拒绝，最终正文正确说明未获授权、不重试。该模型首次命令生成差距保留，不包装成全程无误。
- 下一轮明确直接PowerShell脚本；完整审批核对后Allow Once。运行时折叠视图出现逐行中文，完成后Ctrl+O与PgUp可读五行stdout、stderr“验收错误”和exitCode7；最终正文明确失败与退出码，未重试，运行中草稿仍在。正常退出0。此进程启动早于最后的“等待审批/短全局状态”调整，因此不作为这两项最终显示证据。
- 用最终显示版本重新启动取消场景：实际等待审批→Allow Once→“正在执行命令”及“取消前输出”→Esc→“已取消/本轮已停止”。journal结果为FAILURE/OPERATION_CANCELLED，无“不应到达尾部”输出，Run为USER_CANCELLED。草稿“取消后请只回复下一轮正常”直接提交后得到“下一轮正常”，Run为COMPLETED、回到idle，退出0。对应本机Session为session-505a1cef-9469-43de-8afb-4eb252eca75c，不提交原journal或系统提示。

物理终端截图和参考产品实机视觉一致性未验证；本次PTY只证明实际入口按键与结果。搜索分组使用本项目更保守的相邻成功策略，未复刻参考的跨位置分组；展开位置按行数保留，改宽后无法保证逐字锚点。Markdown表格与复杂Provider命令质量仍是后续差距。CLI-05/09保持L2、TOOL-10保持L1，S15 Exit仍OPEN。

## 2026-09-12：正文表格与缩进（ADR-100）

参考对照见[ADR-100](../adr/ADR-100-s15-markdown-layout.md)。新界面此前没有table分支，GFM表格按原文显示；本批复用Marked，单独生成表头/列边界、单元格折行和对齐。窄屏无法容纳列或单元格过高时使用表头—值形式，保留单元格正文。代码块保持侧边线、原始缩进、空行和反引号；普通列表续行悬挂缩进。不新增依赖、协议或Java改动。

最终build通过；24文件、368/368离线回归通过，49.34秒，显式Java classpath包含Java→stdio→Ink，排除需单独启用的real-java-plan-e2e与installed-plan-e2e。新增6项覆盖38/78/118可用列宽（外层消息前缀占2列）、12/20窄列回退、中文/emoji、左右居中、空单元格与转义管道、粗体、长内容不省略、未闭合代码围栏、空行和逐字流式表头。既有六种视窗、问卷、审批、计划、取消及正文唯一性回归保持通过。

正式入口StartCodejDev.ps1 --tui-next、真实Provider、独立临时Workspace、实际PTY按键：要求不调用工具、不联网，生成明确标注虚构的周一至周日三列表格与两行PowerShell代码。终端实际显示完整列边界、七行单元格和带侧边线代码；最终正文回到idle。下一轮请求固定短句得到“排版验收后可以继续对话。”，再次idle，Ctrl+C退出0。示例不是真实天气查询，代码只展示、未执行。

本批证据为渲染检查和实际PTY，不是物理终端截图或参考产品实机一致性。未实现语法高亮、稳定前缀增量解析及全部复杂嵌套Markdown；流式表格在分隔行尚未完整时可能暂按段落显示，最终由Marked重解析收敛。CLI-03保持L2，S15 Exit保持OPEN。上一批未提交改动保留。
## 2026-09-12：列表项与块上下文（ADR-101）

[ADR-101](../adr/ADR-101-s15-markdown-block-context.md)记录参考formatToken列表深度、父token与引用上下文的独立采纳边界。修改前四项复现均失败：四个实际列表项出现七个项目符号，有序代码块重复五次编号，引用续行与任务表格也重复标记。修复将列表正文按悬挂缩进排版，只向首内容行附加本项标记；子列表、代码、表格和后续段落各自保留块上下文。引用标记在折行和空行延续，定义token不制造空段。

最终build通过，24文件373/373离线回归通过，48.05秒；显式Java classpath包含真实Java→stdio→Ink，排除需单独启用的real-java-plan-e2e及installed-plan-e2e。新增五项回归覆盖列表/引用组合及20/38/78/118列。第一遍完整回归有一项新测试错误地将多列表格跨行拼成单个单元格，随后改为独立单元格字符完整性断言；中间还修正了误改宽度用例及测试数据字符与段落重叠问题，最终重新构建并完整回归通过，不计失败版本为完成证据。

正式入口StartCodejDev --tui-next、真实Provider、独立临时Workspace、PTY实际按键：请求不调用工具，仅生成从9起始的两项有序列表，第9项含后续段落、两个子项及两行PowerShell代码，最后为引用内列表和续段。实际可见9/10编号各一次、子项自身项目符号、代码与段落缩进、引用空行与续段；最终idle。下一轮得到“下一轮正常。”，再次idle，Ctrl+C退出0；展示代码未执行。未把渲染检查或PTY当作物理终端截图、参考产品视觉一致性。

无Java/协议改动，无Capability升级，CLI-03保持L2。极深嵌套窄屏、语法高亮、增量解析性能及参考实机视觉仍未验证；前两批未提交修改继续保留。看板generate/check/self-test及diff检查作为同批完成检查。
## 2026-09-12：普通终端原生历史（ADR-102）

[ADR-102](../adr/ADR-102-s15-native-terminal-history.md)对照授权源utils/fullscreen.ts、REPL.tsx及AlternateScreen.tsx：普通模式使用终端历史，可选全屏模式另行接管鼠标。此前新界面裁掉完整历史并自行提示PgUp；本批使用仓库已采用的公共Ink Static输出稳定前缀，移除主界面分页提示与键盘拦截。工具分组、当前回合可修正回答和审核恢复尚未稳定时不永久封存。Ctrl+O详情继续键盘阅读，普通模式不发送鼠标捕获指令。

最终build通过，24文件376/376回归通过（47.45秒），包含显式Java classpath的Java→stdio→Ink；排除需单独启用的real-java-plan-e2e及installed-plan-e2e。新增完整60行终态替换、工具/审核稳定边界、非debug实际渲染字节回归：稳定正文完整写出，下一次草稿更新不重印旧正文，无清空scrollback或进入AlternateScreen序列。原有40/80/120列及24/35行、问卷、审批、计划和取消覆盖通过。中途发现Static原地修改数组造成正文遗漏，已改为不可变数组；debug测试会累计历史，不能作为实际重复输出证据。受限运行Java子进程测试未结束，已终止该次并在正常本地权限下完整重跑通过。

正式StartCodejDev --tui-next、真实Provider、隔离临时Workspace、真实PTY按键：不调用工具，生成HIST-01至HIST-50中文测试行；终态完整50行连续输出，随后请求NEXT-OK成功回到idle，Ctrl+C退出0。第二轮捕获未重印HIST-01。第一次流式捕获有工具输出截断，因此不把该段作为全程逐字无清屏证明；无清屏与单次追加的完整字节断言由非debug离线回归提供。参考物理终端截图及鼠标滚轮未操作，不宣称实机体验一致。

剩余偏差：超长未稳定当前回答只显示有界尾部，稳定后完整进入终端历史；Ctrl+O可读取当前完整记录，但仍是键盘详情，不是可选全屏鼠标模式。无Java或协议变更，CLI-01/03/04/09仍L2，S15 Exit OPEN。ADR099—101未提交修改全部保留，本批不commit/push。
## 2026-09-12：启动品牌与技术日志收敛

S15，FR-CLI-004/009，CLI-01/03/09保持L2，无等级变化。仓库G:\AI Cloud\cc-java、feat/tui-redesign，正式入口codej --tui-next。按维护者明确要求复用项目原有CODEJ彩色字形，替换实验图标及“新界面”标签，不新增产品流程。

| 场景 | 来源和参考入口 | 本项目采用/偏差 | 验收 |
| --- | --- | --- | --- |
| 启动品牌 | 本项目app.tsx已有CODEJ_BANNER与青/蓝/紫色；AUTH-SRC-2026-07-29-A的LogoV2.tsx按columns选择compact/其他布局，源码Observed，实机Unknown | 提取共享brand.ts供旧版与新界面复用；新界面52列以上使用原五行字形，窄屏使用青色codej；不复制参考品牌 | 40/80/120列既有布局回归、正式入口PTY |
| 启动信息 | StartCodejDev→Invoke-CodejJavaBuild/Initialize-CcJavaRipgrep→Node→ExperienceRuntime；用户要求隐藏成功启动技术日志 | 成功状态改为Write-Verbose，错误继续可见；连接状态改为“正在启动”，预览也用共享品牌 | 原三条日志不出现、输入可用、Ctrl+C正常退出 |

首遍build、TUI 369通过/7未启用Java跨进程测试，以及启动器66项断言通过。正式入口PTY可见原五行CODEJ字形，三条成功日志已消失，连接成功后可输入，Ctrl+C退出0。字形与命名配色直接来自原代码；PTY默认未输出颜色字节，不将其描述为物理终端配色验收。改动不涉及Java事件协议、权限或Agent Loop。最终构建和回归结果另附。
最终build与TUI369通过/7skip（16.73秒）；7项Java跨进程未在本轮启用，此前ADR102的376项完整通过仍属于此前证据。第二次正式入口设置FORCE_COLOR=3，可见实际ANSI 96/94/95三色，原三条日志、新界面及正在连接Java标签均未出现；ready可输入、Ctrl+C退出0。未强制修改用户配色环境。看板generate/check/self-test与diff检查作为完成检查，未commit/push。
## 2026-09-12：多行输入移动（ADR-103）

参考、状态传递和偏差见[ADR-103](../adr/ADR-103-s15-multiline-input-navigation.md)。本批复用input-editor.ts已有reduceComposer，不改布局、品牌、问卷复核或审核操作。修复新界面缺失的上下移动：视觉行优先，普通空闲输入边界才切历史；运行中只改草稿；自由回答和计划反馈不触发会话历史或选项切换。纯光标上下移动保留历史游标，实际插入仍退出历史浏览。

最终build与24文件381/381回归通过（49.88秒），显式Java classpath包含Java→stdio→Ink，排除单独启用的real-java-plan-e2e和installed-plan-e2e。新增五项检查：中文/emoji与长短行目标列、软折行、历史边界及恢复、问卷真实答案提交、计划反馈真实内容提交。首次build遇到exactOptionalPropertyTypes不接受undefined目标列，已修正类型并重新build及完整回归。

正式StartCodejDev --tui-next、隔离临时Workspace、真实Provider、PTY按键：粘贴说明及甲乙/丙丁三行，↑回到上一行、插入“改”，屏幕显示甲乙改/丙丁；Enter后最终回答原样返回修改后的两行。下一轮得到OK，重新idle，Ctrl+C退出0。此为本项目PTY和源码对照，非参考物理终端截图；计划反馈和问卷分支为确定性按键证据，未冒称本轮真实Provider生成了问卷或计划。

CLI-04保持L2，S15 Exit OPEN。尚未稳定超长回答、详情鼠标模式、参考实机视觉及跨平台差距保留。维护者认可此前交互，本批不把自动回归换算为体验完成百分比；未commit/push。
## 2026-09-12：核心命令Tab补全（ADR-104）

[ADR-104](../adr/ADR-104-s15-core-command-completion.md)对照PromptInput→useTypeahead→handleTab→applyCommandSuggestion的只补全不提交职责。修复此前Tab只切候选索引的偏差，使用现有草稿编辑入口接受选中命令并保留参数空格；不增加命令、菜单或执行路径。帮助文字同步将旧主界面PgUp回看改为阅读详情。

最终build与24文件383/383回归通过（47.84秒），显式Java classpath含Java→stdio→Ink，排除单独启用的real-java-plan-e2e与installed-plan-e2e。新增两项验证单候选、选择/help、补参数后一次提交及运行中不排队。初次测试错误断言快照保留行尾空格、遗漏现有startPlan的verificationCorrection参数，导致两项失败；修正测试契约后重新完整通过，未改底层计划行为。

正式StartCodejDev --tui-next、真实Java启动、隔离临时Workspace、PTY按键：/pla→Tab后仍在人工审批输入态；再输入检查目录得到/plan 检查目录，未提交。Ctrl+U清草稿，/he→Tab→Enter显示真实帮助并回到输入；Ctrl+C退出0。没有调用Provider或生成计划，本批真实PTY验证的是补全和本地帮助，不冒称重跑在线计划执行闭环。计划提交与既有审批路径由Fake/Java回归验证。

CLI-04保持L2，S15 Exit OPEN；此前长流式正文、详情鼠标和跨平台差距保留。未commit/push。
## 2026-09-12：核心输入、问卷复核与详情整批收口（ADR-105）

本批按维护者要求扩大交付粒度，沿用已认可布局和品牌，统一覆盖R1、R2/R4和R5。参考链路、机制和范围见[ADR-105](../adr/ADR-105-s15-core-interaction-batch.md)。不新增Java协议、Agent Loop、命令或菜单。

修改前四项独立复现全部失败：取回历史后左右移动导致原稿无法恢复；返回单选题焦点误回第一项；多选无法清除已保存自由回答；24行终端长审批末页只能到command-line-43，看不到命令末行与目录。修复后四项均通过，并补充按词导航、未保存回答/必答拦截、新请求状态隔离和六尺寸首尾可达性。纯光标导航保留历史身份；文本编辑仍退出历史；词边界复用旧版reduceComposer。问卷焦点按请求内questionId保存，切题统一处理，旧请求不能污染新问卷。按键与渲染共用runtimeViewportHeight，保留原生终端历史所需的两行空间。

最终build通过；24文件391/391回归通过（48.80秒），显式Java classpath包含Java→stdio→Ink，排除单独启用的real-java-plan-e2e和installed-plan-e2e。自动检查覆盖40/80/120列×24/35行、工具与审批完整首尾、选择与草稿、问卷复核/返回修改/缺必答/旧请求、计划反馈、拒绝和取消迟到事件。没有因代码编写提升Capability或Stage Gate。

正式StartCodejDev --tui-next、隔离临时Workspace、真实Provider、Windows PTY验证：

- 请求单个只输出测试文字的PowerShell命令。模型提供30条展开Write-Output语句；PgDn读到ADR105-30和目录，允许本次后命令成功并最终说明输出30行。Ctrl+O实际读到输出第30行，连续PgUp回到完整详情顶部，关闭后仍保留下一轮草稿；下一轮得到OK并idle。未读写文件或联网。
- 真实ask_user_questions两题：第一题选择后端，第二题多选输入并保存自由说明；Shift+Tab返回第一题时焦点仍为后端；返回第二题清空旧自由说明并保存。复核仅显示后端/输入，最终模型总结也仅包含后端/输入，删除文本未作为答案发送。该路径为真实Provider及生产Java工具链，不是演示定时器。
- 问卷完成后输入保留草稿😀，↑取回历史，左右移动，↓恢复原草稿；屏幕正确显示原稿，Ctrl+C退出0。

以上是源码机制、自动渲染/协议和本项目PTY证据，非参考产品物理截图。复杂终端组合、参考视觉完全一致及超长未稳定回答差距仍保留；取消和拒绝分支本批由确定性/Java跨进程回归覆盖，真实Provider长命令走允许路径。CLI-04/05/09保持L2，TOOL-10保持L1，S15 Exit OPEN。未commit/push。
## 2026-09-12：长会话呈现与缓存一致性（ADR-106）

[ADR-106](../adr/ADR-106-s15-long-session-rendering.md)对照参考AssistantTextMessage的内容依赖缓存及REPL消息/活动输入分工。普通输入测量不再扫描完整历史；正文布局按不可变记录数组、宽度、展开、审批和头部元数据复用；Markdown按消息对象与宽度复用。WeakMap不另外持有整个会话，单正文最多4个投影、单消息最多3个宽度。测量空数组归当前界面实例，实例退出可释放；稳定历史扫描从已封存前缀继续，输入变化不重复创建活动切片。

修改前确定性复现：24条历史、12次输入/动画更新导致288次Markdown解析；仅替换末条回答也重新解析24条。修复后同场景为0次旧消息重解析，末条替换仅1次；宽度变化仍重新解析24条。挂载16条消息后，普通输入、展开后编辑与翻页均不重解析稳定回答。以上是调用次数证据，不推算端到端耗时或体验百分比。失效验证覆盖最终正文替换不残留旧文、审批进退、脱敏结果替换、模型头部更新及resize。

最终build及25文件395/395回归通过（47.12秒），含显式Java classpath的Java→stdio→Ink，排除独立启用的real-java-plan-e2e和installed-plan-e2e。新增4项缓存/挂载测试；首次build发现测试审批事件缺version字段，修正测试后重新build与全回归通过。既有六尺寸、取消迟到事件、问卷、计划、工具和非debug原生历史单次追加回归保持通过。

正式StartCodejDev --tui-next、隔离临时Workspace、真实Provider、PTY：请求16个编号小节与虚构测试表格，不调用工具；最终可见第16项和CACHE-END。输入下一轮草稿，Ctrl+O阅读表格尾部及PgUp前页，返回仍保留草稿；提交后得到OK、回到idle、Ctrl+C退出0。长流式工具捕获有截断，因此不把捕获全文当作完整字节一致性证明；Markdown完整性、最终替换和单次历史写出依赖自动回归。此为本项目PTY与源码机制证据，非参考产品物理截图；真实样本是一条长回答，超过缓存容量的多消息场景由确定性24条与挂载16条测试覆盖。

无Java协议或布局变化，CLI-03/04/09保持L2，S15 Exit OPEN。超长未稳定单条Markdown仍整体重解析、未实现增量语法树；缓存不是流式首屏性能全部问题的解决。详情鼠标、参考实机视觉和跨平台差距保留。未commit/push。
## 2026-09-12：核心结果交付（ADR-107）

对照 [ADR-107](../adr/ADR-107-s15-result-delivery.md)：参考 Markdown→formatToken 的链接目标、图片地址与强调机制。公共 Ink 与参考 fork 的能力不同，本项目采用名称加完整地址的文本回退；不宣称已实现 OSC8 点击。保留已有 GFM 删除线是明确偏差。

修改前四项复现失败：链接/产物目标丢失、强调样式丢失、深层引用正文消失、表格链接目标缺失。进一步确认 Marked 18 checkbox token 与手工列表标记重复；统一由列表标记呈现，有序任务同时保留序号与勾选。深度限制保留，超限显示原文而非吞掉内容。链接和图片不触发访问，控制字符经过既有过滤；代码保持字面文本。

针对性 Markdown 与挂载交互53/53通过；最终build及25文件400/400通过（47.90秒），显式设置Java classpath，包含对话/命令允许与取消、问卷整批答案与取消、计划修订/确认/文件检查/最终正文/下一轮、计划拒绝无副作用。独立启用的real-java-plan-e2e和installed-plan-e2e未在本批运行。未修改Java安全管线，不据测试数量提升能力。

正式StartCodejDev --tui-next、隔离TEMP workspace、已配置真实Provider、Windows PTY：输出完整测试来源URL与示例产物地址、两个勾选状态和DELIVERY-END；输入下一轮草稿，Ctrl+O展开再返回，提交后得到OK，回到idle，Ctrl+C退出0。示例文件地址只是排版样本，未创建文件，不能当作真实产物创建证据；实际计划产物检查来自Java Fake集成回归。本批没有重新运行真实Provider命令与问卷，之前ADR105证据单独保留。PTY文本记录不是参考产品物理截图或鼠标点击验证。

窄屏38/78/118列及12列表格回退、深层嵌套、流式链接闭合、草稿返回均有回归；已有六尺寸与历史/缓存回归继续通过。CLI-03/04/09 L2、PLAN-01 L1均不变；超长单条流式增量解析、OSC8、详情鼠标、参考实机视觉与跨平台差距仍开放。未commit/push。

## 2026-09-12：日常核心流程整批（ADR-108）

源码对照与交接：[ADR-108](../adr/ADR-108-s15-daily-core-workflow.md)、[整批验收](../plans/tui-daily-core-acceptance.md)。采用参考REPL的正文与输入调度分离、Markdown聚合渲染、QuestionNavigationBar限宽及SelectMulti焦点窗口机制。公共Ink适配采用50ms定时发布是明确独立偏差，不复制参考fork实现。

生产变更：Runtime同步处理全部事件，仅向订阅者标记正文/工具输出可合并；RuntimePresentation保留最新快照并在短帧内合并，审批/取消/断连/脱敏/终态立即发布并清理待发帧，卸载不再唤醒。RowView合并同样式相邻文字，字素排版、反色光标、颜色和强调不变。问卷限宽导航并保留完整标题，选项窗口包含焦点；新说明回到首屏，选择后移除过期必答提示；验证提示占用明确高度预算。新面板首帧不再短暂显示旧计划反馈；断连不继续提示等待连接。

确定性证据：200条密集增量在Runtime同步产生200次更新，视图在50ms后发布一次且全文完全一致；慢流持续发布。终态替换、取消迟到事件、审批、脱敏、断连、卸载均有断言。400次中文/emoji样本的4800个字素绘制节点合并为76个，文字相同；不把节点数量换算为端到端耗时。四个120字符标题、8选项加自由输入、长说明、三行编辑与错误提示在六尺寸复验；修改前40×24看不到当前问题，修改后可见，挂载测试完成多选/输入/缩放/返回复核与唯一提交。

真实Provider第一段：正式StartCodejDev --tui-next，在TEMP/codej-adr108-daily-core中用input.txt（alpha=3、beta=4）执行规划提问语言→选择中文→计划展示→修改意见要求写原值与sum=7→修订计划→确认→写文件审批→PowerShell验证。首次审核前和修订审核前report.md均不存在；审批后文件实际包含原值与sum=7。第一次模型错误套用双引号嵌套pwsh，执行exit1，界面显示失败；模型自行修正为当前Shell直接执行并重新审批，输出VERIFICATION_OK。最终回答给出./report.md、sum=7、实际验证结果；Ctrl+O返回保留草稿，下一轮实际回答sum=7，退出0。文件SHA256为003EBF1427CE0AC8E26EDEE9EB7FFA3E5AFE2C3C7F07A2FA1E7D795AC5F31C16。本段期间后续加入的绘制合并/新面板首帧优化，另由最终回归和重启后的PTY验证，不能把早启动进程当成自动热更新。

真实Provider第二段：重启载入本批绘制/问卷修改，真实ask_user_questions单选中文、多选输入值/验证、自由回答保留原始数值；复核返回再提交，模型准确总结实际答案。随后拒绝命令审批，界面显示操作已被拒绝，模型说明停止且不重试。下一轮直接PowerShell命令输出CANCEL-READY后等待30秒；执行中输入后续草稿并Esc，显示已取消/本轮已停止，草稿保留，提交后得到OK。未观察到命令末尾CANCEL-NOT-EXPECTED被作为输出，文件hash仍相同。未单独取进程PID作本批物理清理证据，进程树安全继续依赖既有Java回归。

回归中旧非交互watchdog用例曾在并发运行时失败：把真实Node child启动也算进200ms，导致预期成功实际timeout；同一用例立即隔离重跑通过。该用例改为可控事件+虚拟时钟，直接断言正常terminal到达后，即使transport尚未exit也不再触发watchdog；真实child正常退出断言保留在独立跨进程用例，不放宽生产deadline。修改后print-session 15/15通过，完整回归另记最终结果。

最终代码的长输出PTY：真实Provider生成60行中文表格及6行PowerShell代码，终态输出含第1至60项和LONG-END；Ctrl+O读尾部，再PgUp读到第42项及后续内容，返回保留最后一轮草稿。流式抓取部分被工具截断，不据此宣称完整逐字字节审计；完整内容与稳定历史一次写出仍由自动回归证明。参考产品实机截图、终端鼠标点击、跨平台、超长单条增量AST继续保持未验证或未实现。

最终验证：TypeScript build通过；显式Java classpath完整TUI回归27文件408/408通过（47.56秒），含Java→stdio→Ink的命令、问卷、计划、取消、拒绝及最终交付；独立启用的real-java-plan-e2e/installed-plan-e2e本批未运行，真实Provider改用上述生产入口PTY。launcher 66 assertions通过。最后真实长回答后的下一轮得到OK，Ctrl+C退出0。六尺寸/大问卷/异常由自动测试，实际PTY为80列Windows样本。无Java生产代码或协议修改，Capability不变；S15仍OPEN，待维护者体验验收。未commit/push。

## 2026-09-12：长内容阅读与窗口重排（ADR-109）

对照REPL进入/退出transcript时捕获消息数量并截取最新消息数组，以及Markdown宽度无关cachedLexer。Ctrl+O以Runtime最新已接收的块数量固定阅读范围，不能使用50ms呈现延迟快照；readingState只截取最新对象，审批/notice/状态不截取，脱敏与终态修正继续及时生效。End更新范围，Ctrl+O返回，成功提交新任务取消范围限制以显示下一轮。详情底部给出实际操作提示，正常终端滚轮行为不变。

Marked完整语法解析与宽度相关排版分离；32项且原文总长度不超过1048576 UTF-16单元的LRU，宽度改变仍重排但不再次调用lexer。既有24条历史resize用例现在断言零次重新解析；后置引用定义、围栏闭合重新解释，35条缓存样本验证旧项淘汰只导致重解析、不丢正文。未实现增量AST或逐行resize锚定。

初次定向验证发现进入详情可能遗漏最新待发布记录，修复为读取Runtime同步状态后通过。首轮完整回归另有旧Java验收默认新工具会自动加入详情的断言失败：测试在工具开始前展开，已按本批明确行为加入End刷新，仍检查完整命令、session许可、后续草稿和取消终态，没有取消断言。最终build及28文件412/412通过（46.91秒，显式Java classpath），包括核心Java→stdio→Ink；独立在线/installed测试仍需显式启用，未作为本批运行。

真实Provider生产入口、Windows80列PTY、既有TEMP隔离工作区：命令审批时Ctrl+O展开，允许本次后READING-READY及READING-DONE原位更新，exitCode0；保留下一轮草稿，运行结束后详情仍保持原范围。此为本项目PTY与源码观察，非参考实机截图或多尺寸物理验证。六尺寸、迟到事件、脱敏、审批、最终替换由自动回归覆盖。无Capability等级变化，S15 Exit OPEN，未commit/push。

PTY收尾：End后实际出现READING-FINAL，Ctrl+O返回仍保留下一轮草稿；提交得到OK、回到idle、Ctrl+C退出0。ProgressDashboard generate/check/self-test与git diff --check通过。

## 2026-09-12：网页来源链接（ADR-110）

源码对照入口、采纳和偏差见ADR-110：参考Markdown→hyperlink→supports-hyperlinks，独立采用Marked的结构化href与公共terminal-link 5.0.0（MIT，Node >=20，项目Node22/React19.2.8/Ink7.1.1兼容）。lockfile只新增terminal-link及能力检测的3个传递包，复用已有ansi-escapes，未升级既有框架。安装审计另报告既有Vitest/mocker/Vite/postcss/nanoid开发依赖告警，本批未进行无关依赖升级，也不把build通过称为安全审计通过。

标签及完整地址始终可见；只有绝对HTTP/HTTPS无控制字符目标携带href。样式合并比较href，字素折行保留目标，公共包只在RowView最后一步生成OSC8；本地路径、相对路径、可执行协议保持惰性文字。既有Runtime、审批和stdio不变。能力检测遵循公共包；验收强制开关只用于子进程，不修改用户配置。

公共Ink非debug、独立Node进程验证40/80/120列：真实标签和完整地址处于OSC8开始/闭合范围内、后续NEXT_INPUT位于范围外；强制不支持与自然非TTY无OSC8、可见内容相同；中文/emoji/粗体/表格/图片、代码不激活、危险目标、地址规范化及缓存复用通过。初次3/3通过后加严检查实际链接范围与自然非TTY退化，最终build与29文件415/415通过（47.71秒，显式Java Fake classpath）。未运行独立real-java-plan-e2e/installed-plan-e2e，本批在线用生产入口PTY。

真实Provider Windows80列PTY：在TEMP隔离工作区通过StartCodejDev --tui-next启动；仅本次子进程设置FORCE_HYPERLINK=1验证协议通路。模型返回中文来源、完整example.com地址和LINK-END，Ctrl+O展开/返回保留草稿，下一轮得到OK、idle、Ctrl+C退出0。ConPTY抓取把OSC8开闭移动到可见文字前，不能以该转录证明物理链接范围或点击成功；范围证据来自上面的公共Ink原始输出测试。没有调用浏览器、打开目标或修改验收工作区文件。物理点击、文件链接、参考实机视觉、跨平台仍为差距。

CLI-03/04/09仍L2，S15 Exit OPEN；不替换默认入口，未commit/push。
## 2026-09-12：文件变更审批（ADR-111）

参考FileWriteToolDiff/FileEditToolDiff到StructuredDiff的职责链；独立实现意图片段而非整文件diff。experienceV1初始化才启用协调器正文，旧默认测试保持原摘要；只允许BUILT_IN，单侧6000 UTF-16上限，SecretCandidatePolicy或异常控制字符触发整段拒显。专用fileChange仅用于stdio交互事件，不添加Session/日志输出，不改变参数或文件提交前复检。Java文档明确检测并非完整秘密识别。

新界面消费已有移除/新增行数及可选片段；中文折行、末尾无换行、红绿区别、分页和固定决定。创建/修改成功摘要只在tool.completed显示；失败/取消先于成功分支。未提供或拒显明确说明，不造假正文。一次真实创建预览发现末尾换行被计成多一行，已修复Java计数及TUI尾部空行显示。

Java定向回归：StdioApprovalCoordinator/RuntimeStdioCommandHandler 66/66，WriteFile/ApplyPatch/AtomicUtf8FileWriter/WorkspaceGuard/WorkspaceWriteHardDenial 29/29，总95通过。TUI最终build与29文件416/416（46.74秒，显式Java Fake classpath）通过，含计划确认后两次真实write_file审批字段及可见正文。六尺寸同时验证片段全文可分页到达、决定始终可见；旧Host、拒显、取消、非法字段与危险地址由相应回归覆盖。初次新UI测试引用不存在的frame字段导致build失败，改为对真实分页rows逐页验证后通过。未运行全仓clean verify和独立在线/installed测试。

真实Provider Windows80列PTY：在TEMP隔离目录请求write_file创建preview-111.txt，再apply_patch将version=1变为version=2，两次审批均显示实际片段，允许本次后工具成功。独立读盘为“中文预览”和“version=2”，SHA256为2C08CDDC4BF172442D1F866492C86D523152B474BA3774F2D27079CB23B80F48。该进程启动早于尾部换行/成功摘要修正，最终版本由上述完整回归验证；参考实机视觉、整文件上下文diff和语法高亮仍有差距。真实终态另记。
真实终态补充：Provider最终给出创建、修改及当前两行内容的完整回答，界面恢复idle；模型使用search_text核验，另尝试git_diff但明确报告TEMP工作区非Git仓库，没有把该失败当作修改失败。总运行约250秒（含人工审批等待和Provider延迟），不宣称低延迟。实际文件已独立核验。本批看板generate/check/self-test与git diff --check通过，无Capability升级，未commit/push。
下一轮真实回答OK并恢复idle，Ctrl+C正常退出0；文件内容保持version=2。


## 2026-09-12：用户启动报错（ADR-112）

只读核对对应本机会话：metadata中camelCase键违反Domain规则，移除后task_create成功，计划产物、审核请求、最终正文及run.completed均存在。没有现场stdio握手转录，因此不能断言截图中的提示必定来自queued。独立复现确认新界面将queued误当启动失败并清掉request，之后所有结果都被过滤；修复保留关联，明确排队/拒绝/丢弃，取消不自动重放。增加测试覆盖queued后工具失败/修正、计划审核、最终正文及下一步终态。

schema从Domain同一个键pattern生成propertyNames；camelCase继续拒绝且给出snake_case纠正指引，错误不回显键值。Java TaskToolBatchBTest17、RuntimeStdioCommandHandlerTest60，共77通过；TypeScript build通过。用户旅行正文及搜索结果未复制到仓库fixture；未重启或修改用户会话，也未自动批准其计划。完整TUI结果另记。

最终TUI完整回归29文件418/418（45.56秒，显式Java Fake）通过；看板generate/check/self-test及diff检查通过。未commit/push，未运行本批真实Provider重演；现场无完整握手日志的限制保留。

## 2026-09-12：可靠交付整批（ADR-113）

对照REPL→handlePromptSubmit→messageQueueManager中队列、活动请求与记录的职责分离，复用ADR085/112握手契约。独立实现不含正文的内存诊断：封闭事件/结果码/阶段/路由，64项，连续重复聚合计数上限999999；仅异常且无待决面板的Ctrl+O末尾显示12项。没有原始request/run/session ID、参数、路径、自由异常、文件日志、网络上传或自动重放。

修复三类终态交付：验收未通过不再吞掉finalText；普通成功但本Run无正文时明确提示；计划有解释但无审核面板时仍明确未形成可审核计划。取消后正文继续丢弃，宿主主动run.cancelled使活动工具标为取消；断连则展示transport_lost/执行结果未知，不冒称副作用已撤销。后续诊断在正文缓存外投影，正常聊天无内部流水。

初次新增诊断测试漏写ProtocolEvent.version并使用未声明事件类型，build报错；补齐合法fixture及明确的异常类型测试后build通过。完整回归30文件423/423（46.44秒，显式Java classpath）通过，含对话/文件审批/问卷/计划Java Fake、排队纠正、取消迟到及连续运行。随后仅校正流式事件白名单为真实model.text.delta并增加六尺寸断言，build和定向2文件50/50（17.85秒）通过。未修改Java生产代码，本批未重跑全仓Maven clean verify。

真实Provider生产StartCodejDev --tui-next、Windows80列PTY、TEMP隔离目录：命令Write-Output样本并exit5，完整审批允许本次，显示实际输出和退出码5，模型最终一句话报告预期失败，不重试或修改文件；Ctrl+O可读完整命令结果，返回保留草稿，下一轮OK、idle、Ctrl+C退出0。该进程用于普通工具失败交付，计划验收失败带正文、空结果、断连、宿主主动取消及六尺寸诊断由自动测试验证，不宣称这些分支已有同批物理终端证据。

看板generate/check/self-test及git diff --check通过。CLI-03/04/09 L2、PLAN-01 L1不变，S15 Exit OPEN；未commit/push，未替换默认入口。没有原始协议抓包/持久诊断、自动恢复或参考实机/跨平台视觉证据。
## 2026-09-12：提交拒绝恢复（ADR-114）

对照FilePermissionDialog/usePermissionHandler的面板与决定职责分离，继续采用ADR085跨stdio pending/拒绝不重放契约；具体恢复协议为本项目设计。修复计划确认rejected先finish清掉decisions而无法恢复的问题，先还原同一plan身份/反馈编辑状态，再忽略相邻旧protocol.error。已接受计划后的错误保持等待终态，不能恢复成可重复执行面板。旧审批错误不覆盖新面板。

启动原输入仅保留到accepted/queued/started前；明确拒绝时以本地一次性ID交给Composer。空输入框填回，已有后续草稿不覆盖，原输入仍可经既有历史找回。取消、断连未知结果和已接受任务不触发恢复或自动发送。只有protocol.error且仍未接受的兼容拒绝使用同一路径。问卷协议拒绝恢复相同复核身份，因此原选择仍在；再次提交必须再次确认。

初次build发现React19 useRef需要初值、分支收窄后旧plan比较不可达，均已修正。完整回归30文件428/428（45.19秒，显式Java Fake classpath）通过，含真实Java stdio对话/文件/问卷/计划及取消；之后统一拒绝分支并补兼容protocol.error样本，最终Runtime定向54/54（19.55秒）通过，build通过。新拒绝序列由可控客户端+公共Ink按键测试验证，不宣称本批已制造真实Provider或物理终端拒绝竞态。Java生产代码不变，本批未重跑Maven或原用户会话。

看板generate/check/self-test与git diff --check通过；CLI-04/05/09 L2、PLAN-01 L1不变，S15 Exit OPEN。未commit/push，未替换默认入口。
## 2026-09-13：问卷工具结果确认（ADR-115）

Observed参考：AskUserQuestionPermissionRequest提交答案到工具决定，AskUserQuestionTool由结果答案渲染；独立适配使用Java experienceV1已存在的AfterTool callId，不新增Agent Loop或协议。此前发送即写notice，拒绝无法撤销已封存原生历史；现在仅匹配当前run/callId及问卷工具的成功终态后追加一次。失败、结果遮蔽不回显，拒绝删未确认摘要但保留复核；取消后事件忽略，运行结束/断连清理。旧宿主无callId不猜测。

build通过；Runtime定向63/63（19.41秒），完整30文件437/437（45.77秒，显式Java Fake classpath）通过，未跳过Java核心交互。新增普通/计划/旧单题与拒绝修改、重复终态、错调用/run/工具、失败/脱敏/缺ID/取消/断连/结束边界。真实Java问卷测试额外断言请求与工具结果callId和runId一致，既有模型工具结果返回与下一轮仍通过。未修改Java生产代码，未重跑全仓Maven；本批无新在线Provider/物理终端证据，竞态由可控客户端覆盖。

CLI-04/05/09 L2、PLAN-01 L1不变，S15 Exit OPEN。未commit/push，默认入口未替换。

## 2026-09-13：工具到最终交付（ADR-116）

本批对照表见ADR116。沿用既有工具分组和命令审批，把缺交付判定限定为明确finalText或最后工具之后的当前回合正文；工具前说明、任务更新不再掩盖无回答。工具保留可用callId并拒绝错身份和终态后增量/重启/重复结果。默认仍隐藏内部计划成功调用，详情逐项显示本项目参数与结果，明确属于独立排障偏差；失败的既有纠正摘要继续保留。

初次定向发现7条失败：包括旧测试同一ordinal先成功再失败、问卷错调用样本未先建立原调用，以及详情隐藏预期已改变。按真实协议修正fixture并保持拒绝/遮蔽检查；同时修复失败分支收窄后的TypeScript不可达比较。最终build通过；Runtime71/71（19.60秒），完整30文件445/445（47.24秒，显式Java Fake classpath）通过。实测发现空stderr包装缺末尾换行会显示空标题，扩大固定包装尾部识别，最终build与Runtime71/71（19.34秒）通过。Java生产未变，本批未重跑全仓Maven。

生产入口实测：Windows 80列PTY，StartCodejDev.ps1 --tui-next，TEMP/codej-adr116-delivery，既有真实Provider。读取sample.txt得到3+4=7及来源；创建result.txt，经完整文件预览批准后落盘。首次模型生成Bash语法在PowerShell退出1，界面显示真实错误；模型改为PowerShell精确比较，再次人工批准后验证成功，最终正文报告文件与结果。创建后SHA256为22BAC2FE813637998C6BCAA6A647486E10B94EAF6DE450C7A3FCBFBEF14BA843。

随后/plan生成最小修改计划，PgDn读完风险与验收，确认前文件仍sum=7；确认后apply_patch预览sum=7→sum=8，批准后命令精确验证成功，最终正文说明修改文件和验证通过。独立读盘sum=8，SHA256为F696673AA1A89A580FDE6C86664DED53C0C1213B676F93AC823AF7B0058512E3。模型附加git_diff因临时目录非Git仓库失败，详情保留not_a_git_repository；不把它当作已验证Git差异。Ctrl+O可核对task_update真实结果和最终正文，返回保持“下一轮只回复OK”草稿，提交后OK、idle。

实机进程启动早于最后两项纯呈现调整（空stderr尾部、内部详情摘要去重复）；后二者最终build和Runtime71/71（19.30秒）验证。三类正常交付有实际入口证据；故意制造空终态/错callId/迟到增量由可控客户端覆盖。仍无参考产品实机视觉对照、物理鼠标、跨平台和完整文件diff证据；模型Shell选择仍存在首次出错可能，本批验证其失败纠正链，不声称已消除Provider错误。

## 2026-09-13：命令Shell契约（ADR-117）

对照BashTool/PowerShellTool的方言与执行职责分离，独立地将本项目执行器display Shell事实注入RunCommandTool模型定义。只改变description，保留所有schema、安全与预算元数据；不复制参考Prompt、不暴露本地可执行路径或环境、不自动转换命令。普通TUI视觉不变。

Java定向87/87，0失败/跳过（26.345秒）：CommandShell11、LocalCommandExecutor6、RunCommandTool4、RuntimeStdio60、StdioApproval6。覆盖Unicode/命令参数、非零退出与取消/输出、模型定义与审批Shell一致、读取定义不执行、禁止shell参数覆盖及旧stdio边界。使用新Java构建的完整TUI30文件445/445（45.11秒）通过，含问卷/计划/文件/命令与下一轮；非全仓Maven clean verify。

真实Provider：StartCodejDev.ps1 --tui-next，Windows80列PTY，TEMP/codej-adr117-shell，中文样本.txt为sum=8加LF。用户任务不指定Shell，首次命令即为PowerShell字节精确比较，完整预览后仅允许本次，输出SHELL_CHECK_OK并正常返回最终正文，无首次语法失败、无修改文件。此样本验证定义信息被使用，不代表长期模型正确率；WSL/Docker仅定义与契约离线证据，没有本批Linux或Windows PowerShell5.1实机证据。

真实下一轮OK、idle、Ctrl+C退出0；看板generate/check/self-test与git diff --check通过。TOOL-10 L1、CLI-04/05 L2保持，S15 OPEN；未commit/push，默认入口不变。

## 2026-09-13：文件上下文审阅（ADR-118）

参考链与偏差见ADR118。采用diff9.0.0公开BSD-3-Clause实现structuredPatch，未复制参考表达；Node/TS构建与Ink组合回归通过。生产依赖npm audit --omit=dev为0项；全依赖安装仍报告已有开发依赖3项，未进行无关升级。审批快照限24KB读取、前后各6000字符，复用WorkspaceGuard、严格UTF8快照与真实写工具同源精确替换算法；多处替换先检查扩张预算。默认旧协议/非内置工具无新增正文；scope=file才有文件坐标，无法定位/超限回到片段。秘密/控制字符拒显，预览不登记模型Read证据。

TUI显示3行上下文与旧/新行号、中文长行折行、多块、新建/删除片段和末尾换行。差异计算50ms/12000编辑预算，WeakMap保存结果及预算失败，退化不反复阻塞渲染。审批决定始终固定；按Run/ordinal把审批时意图保留在工具详情，明确不是当前磁盘diff；结果脱敏清除预览，工具终态撤销同一待决审批。冲突不显示修改成功。

最终Java92/92（17.154秒，0跳过）：ApplyPatch17、AtomicWriter2、Write3、WriteHardDenial2、Stdio60、审批8；另WorkspaceGuard5/WindowsJunction1共6/6（1.701秒）通过。新增覆盖CRLF坐标、多处/缺失匹配、敏感上下文、替换扩张、超限、缺文件/敏感路径/路径逃逸；既有文件写入与冲突边界继续通过。非全仓Maven clean verify。TUI定向75/75，完整31文件449/449，显式新Java Fake，最后终态面板修正后的完整结果另列。

真实入口：Windows80列PTY，StartCodejDev.ps1 --tui-next，TEMP/codej-adr118-review，独立Git目录。第一个进程显示第5行及前后三行，PgDn看完整红绿行；审批期间外部写入value=external，确认后file_conflict，最终解释停止且无自动重试；独立读盘保留external。该进程早于后续复用同源替换方法和冲突中文提示，不冒称验证了之后所有分支。

最终Java构建重新启动：读取sample.txt，审批显示第5行external→accepted和上下文，批准成功；第二文件note.txt显示新建第1行及无末尾换行，批准成功。模型读取核对后报告两个产物与Git未跟踪事实。独立读盘确认sample其余行保持，SHA256为0BF0A19AA4BA276296D6723C325DE577EC06B1A68AA3975160C98B8A933EE3A6；note为“审阅完成。”，SHA256为8C0A2640687A0BDB3C4B7820864601B7E3B53710878C84BB38465925DCCFBBED。Ctrl+O/PgUp可读对应文件审批时意图和真实工具结果，返回保留下一轮草稿。最后终态主动清理面板的异常序列由自动测试验证。

剩余：超大文件上下文扫描、语法高亮、落盘后重新读取实时diff、无审批会话授权下的预览生成、参考实机视觉和跨平台未完成。本批只关闭有界文件审阅差距，不提升Capability或宣称全部重构完成。未commit/push，默认入口不变。

最终终态面板修正后build及31文件449/449（45.57秒，显式Java Fake）通过；真实下一轮OK、idle、Ctrl+C退出0。真实正常流程中另出现两次搜索参数无效后纠正，文件内容独立验证通过；模型参数质量不因本批改善自动宣称解决。
## 2026-10-04：文件工具实际结果摘要（ADR-119）

参考机制：工具调用与结果关联，默认保留简短状态，详情展开正文；未复制参考表达。Java已有 `ToolResultMetadata.continuation` 的文件事实现在通过stdio白名单投影为 `resultSummary`，只包含相对路径、operation、替换数和行数；TUI在默认工具行显示“已修改/已创建 + 路径 + 替换数”，Ctrl+O仍显示完整正文和审批时意图。摘要不会改变权限、执行或计划验收。

同时修复审批预览路径安全失败的泄露边界：已存在敏感/越界目标不再保留模型片段，普通不存在目标仍可显示候选片段；文件写入预览先经过 `requireNewFile`。Java 68/68、TUI定向76/76、TypeScript build通过；随后使用显式 Java Fake classpath 的完整回归为 30 个文件通过、1 个文件跳过，443 passed / 7 skipped。能力等级不变，S15 Exit保持OPEN；未commit/push，默认入口不变。
最终终态面板修正后build及31文件449/449（45.57秒，显式Java Fake）通过；真实下一轮OK、idle、Ctrl+C退出0。真实正常流程中另出现两次搜索参数无效后纠正，文件内容独立验证通过；模型参数质量不因本批改善自动宣称解决。

## 2026-10-05：落盘后实际结果核验（ADR-120）

参考链见 ADR-120。文件工具在原子写入后使用同一 WorkspaceGuard 重新解析真实路径，并对有界字节内容做大小和字节级核对；核验成功才在 continuation 中投影 `verification=verified`。TUI 将其显示为“已核验”，审批前预览仍标记为修改意图。路径漂移、外部内容变化、删除、超限和重读失败返回 `FILE_CONFLICT`，不会产生成功核验摘要。

Java 文件工具定向 26/26（ApplyPatch17、AtomicWriter2、WriteFile3、WorkspaceWriteHardDenial2、WorkspaceWriteVerification2）通过；TUI 结果摘要定向 73/73，TypeScript build通过。完整 TUI 和真实入口验收待本批收口后重跑；能力等级不变，S15 Exit保持OPEN，未commit/push。

## 2026-10-05：计划内部工具可见边界（ADR-121）

计划编排内部调用不再显示为普通工具流水：`task_list/task_get/task_create/task_update`、计划工件、审核请求和验收声明统一由专用计划面板及通用通知呈现。参数错误、`plan_gate_blocked` 和恢复状态不暴露内部工具名；当前 Run 的审核面板出现后才显示“审核条件已补齐，计划已提交审核”。

TUI Runtime 应用定向 73/73 通过，覆盖成功/失败/展开/重复终态/跨 Run 和恢复 ordinal；尚未完成任务 2 的完整计划真实入口验收。能力等级不变，S15 Exit保持OPEN。

## 2026-10-05：计划确认到真实交付跨进程闭环（ADR-122）

真实 Java stdio → TUI 的 `real-java-plan-e2e.test.ts` 在允许 Java 子进程访问 Windows 临时目录的本机测试环境完成 6/6：自然语言计划生成、修改意见保留同一 planId、用户确认、两次真实审批、文件纠正、`plan.verification.completed` 早于 `run.completed`、最终正文与真实产物可读、显式恢复、Task 运行/超时恢复和五步中文 XLSX 交付均通过。普通沙箱中的 exit=2 是 `%TEMP%` `toRealPath()` 权限限制，单独记录为环境限制，未计入业务失败。

计划闭环仍遵守“任务描述 → 可审核计划 → 确认 → 实际执行 → 真实核验 → 最终正文/产物 → 下一轮”。`plan_gate_blocked` 仍表示规划证据或任务就绪条件未满足；TUI 只显示通用原因，不把内部 Task CRUD 或审核工具流水暴露给用户。能力等级不变，S15 Exit保持OPEN。

计划纠正场景补充验证：内部 `verification_tool_unavailable` 失败与宿主关联的恢复不再显示 Tool 行，但终态计划面板保留两条必要的通用摘要——“验证方式使用了当前不可用的工具”“已修正验证方式，继续规划”。真实 Java→stdio→Ink `experience-java-e2e.test.tsx` 7/7 通过，Runtime 应用定向 73/73 通过；未暴露工具名、ordinal、参数或协议错误码。

## 2026-10-05：演示层历史分页提示清理（ADR-123）

离线演示 `screen.tsx` 不再插入“↑ 更早内容 · PgUp 回看”或“正在回看”状态行；普通滚屏继续交给终端，详情页的 Ctrl+O 阅读能力保持不变。该批只删除无源码依据的可见提示；真实入口物理终端视觉仍需独立截图验收。能力等级不变，S15 Exit保持OPEN。

## 2026-10-05：长工具输出有界排版缓存（ADR-124）

命令折叠预览与 Ctrl+O 详情按不可变 ToolRecord、终端宽度和视图模式缓存折行结果；流式事件产生新记录，旧排版不会污染新内容。Java 输出上限、文件审阅 6000 字符/预算、脱敏和终态边界保持不变。`experience-render-cache`、Runtime 应用定向共 76/76，TUI build 通过；40/80/120 列×24/35 行回归通过。能力等级不变，S15 Exit保持OPEN。

## 2026-10-05：真实模型失败安全摘要（ADR-125）

Runtime 新增对 Java `modelFailure` 白名单摘要的安全投影：`provider_error`、状态类别和尝试次数映射为用户可行动提示；非法摘要只保留 `stopReason`，不显示异常原文、请求正文或凭证。定向 Runtime 90/90、TUI build 通过。正式入口真实 Provider 最小请求曾返回 `model_error`，没有工具调用或文件副作用；修复后应显示安全失败类别，成功 Provider 场景仍待配置/服务可用时重跑。能力等级不变，S15 Exit保持OPEN。

同批在 `SpringAiModelGateway` 边界补齐未分类 Provider/SDK 异常：建流、订阅和响应解码阶段的 `RuntimeException` 统一转换为固定 `PROVIDER_ERROR` 摘要，底层 sentinel/异常原文只保留 JVM cause，不进入 stdio、TUI 或诊断正文。`SpringAiModelDiagnosticTest` 6/6，模型模块 89 tests、0 failures/errors、2 skipped。TUI 全量回归更新为 30 个文件通过、445 passed / 7 skipped；真实 Provider 成功场景仍未具备可复验条件，S15 Exit保持OPEN。

CLI 计划组合回归中的 3 个旧 Fixture 原先把内部 `task_get/task_update` 声明为 `VERIFICATION`，因此被现有 Evidence Gate 正确拒绝并停留在 DRAFT；已改为保留 Task discovery，同时在批准执行阶段调用无副作用 `run_command` 作为真实验证证据。`TaskToolProductionCompositionTest` 10/10 通过，全仓 Maven 非 clean 回归汇总 1,347 tests、0 failures/errors、13 skipped。生产 Gate 未放宽，内部 Task 状态仍不替代交付证据。

## 2026-10-05：可恢复工具参数失败投影（ADR-126）

对照工具结果按调用关联、错误可见且后续回合可继续的机制，补齐普通 Tool 的安全校验元数据投影。`argumentChangeRequired`、`retryable` 和 `failureCategory` 只作为 Java 已白名单化的布尔/分类字段保存在原 ToolRecord；活动回合显示“参数无效，已返回模型修正”或“参数校验失败，等待模型重试”。没有宿主提供的恢复关联时，终态回到中性的“工具参数无效”，不把后续成功调用猜成旧调用已修正，也不显示原始参数、校验正文或异常。

`protocol.test.ts`、`experience-runtime.test.ts` 与 `experience-runtime-app.test.tsx` 定向合计 130/130；覆盖字段封闭校验、参数失败、同 Run 后续成功和 `run.completed` 后的中性终态。TUI build 通过，完整标准回归为 30 个文件 449 passed / 7 skipped。该批不改变 Tool Pipeline、审批、计划 Gate 或自动重试，能力等级不变，S15 Exit 保持 OPEN。

## 2026-10-05：流式工具输出尾窗（ADR-127）

对照进行中工具和活动回答保持可见、跟随最新输出、完成后可回看的机制，修复 TUI 在每个 `tool.output`/回答增量上对累计全文重复折行的问题。运行中的 ToolRecord 和当前回合 assistant Message 只排版最近 16,384 个字符，折叠视图保留最近三行并提示尾窗；工具或回答完成后恢复宿主已经限制的正文，Ctrl+O 仍可阅读可用工具全文。body projection 缓存绑定运行状态，终态不会复用运行中尾窗。该优化只影响排版，不改变 Java 输出上限、脱敏、取消、审批或 Tool 终态。

`experience-render-cache` 新增 5,000 行运行中工具输出及 5,000 行回答的运行中/终态切换回归；build 通过。超大文件扫描、语法高亮、参考实机视觉和跨平台也未因本批关闭；能力等级不变，S15 Exit 保持 OPEN。

本批尝试重跑真实 `real-java-plan-e2e` 时，首次 Maven 依赖补齐被受限网络拒绝；改用现有编译产物后，Java Fixture 在临时目录的受限 `.cc-java` ACL 上退出，5 个场景未产生协议事件，1 个纯初始化场景通过。该次环境失败不计为产品回归，也不覆盖此前在可访问临时目录完成的 6/6 证据；遗留临时目录已清理，后续需在具备 Java 临时目录 realpath 权限的环境重跑。

随后按 `cc-java-tui/README.md` 改用 `cc-java-cli/target/test-dependency-classpath.txt` 复验，确认此前一次失败命令还误用了根目录的 JUnit-only classpath（直接原因是缺少 `tools/jackson/databind/ObjectMapper`，不计为产品结果）。正确 classpath 下 6 个场景为 1 通过、5 个在 Java 启动前因 `provider/home/.cc-java` 的 Windows ACL 触发 `EPERM`；没有协议事件或 TUI 终态可供验收。该复验仍不覆盖此前具备临时目录 realpath 权限环境中的 6/6 闭环证据；本批未修改 Java/TUI 生产代码，临时目录已清理。

## 2026-10-06：真实入口复验边界

本次复验只使用仓库已有构建产物、仓库规定的运行时 classpath 和隔离临时目录，没有读取或向未知外部端点发送用户凭证。TUI 构建通过，标准回归为 30 个测试文件、449 passed / 7 skipped。Maven clean test 在当前沙箱被依赖下载网络策略拒绝，未产生 Java 测试失败；此前具备缓存依赖的非 clean 回归结果继续作为历史证据，不被本次环境结果替换。

真实 Java→stdio→Ink 的复验先暴露了一个启动前工具错误：使用仓库根目录的 JUnit-only classpath 会缺少 `tools/jackson/databind/ObjectMapper`。改用 `cc-java-cli/target/test-dependency-classpath.txt` 后，1/6 初始化场景通过，另外 5/6 在 Java 启动前因临时 `provider/home/.cc-java` 的 Windows ACL 返回 `EPERM`，因此没有协议事件或 TUI 终态可用于验收。将 workspace 与 home 都放入独立临时目录后，初始化、`run.started`、`model.turn.started` 和安全 `run.failed`（`model_retry_exhausted`）均可见；没有工具调用、文件副作用或伪成功。仓库 workspace 直接作为 Session Store 外层时，Runtime 按既有契约拒绝并报告“Session Store root 必须位于 Workspace 外”，未修改该安全边界。

本次没有可授权的在线 Provider 成功请求，也没有参考产品物理终端截图。历史上在具备临时目录权限的环境完成的真实计划 6/6 和 Java→Ink 7/7 仍有效，但不等同于本次沙箱中的成功 Provider 证据。任务 7 的成功 Provider、Windows PTY 全场景、参考视觉和跨平台验收继续标记为 `Unknown / 未完成`；S15 Exit 保持 OPEN。

## 2026-10-06：跨进程 Fixture ACL 与 Shell 边界复验

为避免把系统 `%TEMP%` 的目录所有者误判成产品失败，`real-java-plan-e2e.test.ts` 将确定性 Fixture 父目录放到仓库 `target/.tui-real-java-fixtures`，并按当前 OS 账户向 Java Fixture 传入单一的 `-Duser.name=<account>` 参数；该改动只影响测试启动器，不改变生产 Runtime 或安全策略。测试诊断同时保留安全的工具失败分类。

修正后使用 README 规定的完整 Java classpath 重跑 6 个跨进程场景：计划生成/确认/执行链、显式验证恢复、普通 Task Board 和审批会话 4/4 通过；完整文件仍为 6 个场景，其中 XLSX 真实 `run_command` 和命令超时 2 个场景已进入真实工具执行，但固定 Windows PowerShell 的 Java `ProcessBuilder` 在当前沙箱返回 `CreateProcess error=5（拒绝访问）`，分别投影为 `execution_failed`，因此不能把它们算作业务通过。独立安全探针确认同一 Java 进程可完成 stdio 初始化，失败只发生在受限 Shell 启动边界。

补充探针显示当前运行时自带的 PowerShell 7 可由 Java 子进程启动，但生产 `CommandShell` 有意只信任标准安装目录，不从任意 `PATH` 或用户可变环境选择可执行文件；本次不为通过测试而放宽这一安全边界。

该结果比前一次 1/6（其余为临时 auth store ACL）更接近真实入口，但仍不是完整 6/6 证据；在线 Provider 成功、Windows 物理 PTY、参考 UI 实机视觉和跨平台安装继续为 `Unknown / 未完成`。失败 Fixture 的临时目录已在授予当前沙箱账户删除权限后清理。能力等级不变，S15 Exit 保持 OPEN。
