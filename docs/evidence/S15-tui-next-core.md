# S15：新界面的真实核心流程验收

日期：2026-09-10。工作树 feat/tui-redesign，main 基线 e51e6b5，未提交。
FR-CLI-001/003/005、FR-PERM-003、FR-EVENT-002、NFR-003；
CLI-01/03/04/05/09 保持 L2，TOOL-11/PLAN-01 保持 L1。S15/P7 仍 OPEN。

## 启动与操作

在目标项目目录使用 PowerShell 7：

    & 'G:\AI Cloud\cc-java-tui-redesign\scripts\StartCodejDev.ps1' --tui-next

也可追加 --workspace 'E:\ai project\choco-backend'。开发构建已准备好，复用现有用户模型配置。
离线演示仍是 npm.cmd --prefix 'G:\AI Cloud\cc-java-tui-redesign\cc-java-tui' run preview:tui。
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
