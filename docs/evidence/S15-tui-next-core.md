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
