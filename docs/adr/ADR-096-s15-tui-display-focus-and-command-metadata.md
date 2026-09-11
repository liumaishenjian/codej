# ADR-096：新界面显示、焦点与命令元数据以运行事实为准

- 状态：Proposed
- 日期：2026-09-11
- Stage：S15；Stage Exit 保持 OPEN
- Feature IDs：CLI-01/03/04/05/09 保持 L2，TOOL-11/PLAN-01 保持 L1
- 公开行为基线：R2026.03（本批未取得可重放黑盒行为）
- 授权参考：AUTH-SRC-2026-07-29-A，Observed（源码职责）/ Inferred（独立协议）/ Unknown（准确版本、稳定协议与视觉一致性）
- 关联决策：[ADR-095](./ADR-095-s15-plan-evidence-failure-recovery-presentation.md)

## 1. 问题、范围与不变量

第二批只复核 `docs/plans/tui-core-handoff.md` R1—R4：输入草稿与焦点、工具分组、内部调用隐藏、Shell 命令摘要/详情，以及流式终态和取消后的迟到事件。保留已认可布局，不增加面板、菜单、状态栏、快捷键或新的 Agent Loop；Permission、Approval、Session Grant、Startup Rule、完成 Gate、no-replay 和第一批验证恢复契约均不改变。

源码与现有测试显示，中文/emoji 草稿、Ctrl+O、相邻搜索分组、失败独立、内部成功隐藏、流式 final 替换 delta、取消后拒绝非终态事件已有独立状态边界。实施前可证实的生产偏差只有：`run_command` 的完整 command、shell 和工作目录只在 `approval.requested` 投影；Session Grant 或 Startup Allow 直接放行时没有审批事件，`tool.started` 因而缺少 shell/目录，界面退化为“命令”。本批仅修复该偏差并补齐可证伪回归，没有为未复现路径重写状态机。

## 2. R1—R4 受控研究与可证伪对照

按 AGENTS.md 5.1/5.3，只读研究仓库外 `G:\AI Cloud\claude-code-main`。仅提炼职责、状态转换、隐藏条件和验证方法，不复制或翻译函数体、Prompt、注释、错误文案、私有类型、文件布局、常量、Fixture 或源码字节。

| 用户场景 / Feature ID | 参考路径与符号 / 基线 / 证据分类 | 参考可观察行为（含不显示什么） | 本项目采用机制 / 必要偏差 | 可证伪场景与实际证据 / 未通过项 |
| --- | --- | --- | --- | --- |
| R1 运行中中文草稿、Ctrl+O 与面板焦点 / CLI-01、CLI-09 | `src/components/PromptInput/PromptInput.tsx` 输入值与 focus 条件；`src/screens/REPL.tsx` 输入、权限面板与运行取消职责；AUTH-SRC-2026-07-29-A；Observed（源码职责） | 输入由上层状态持有；面板出现时接管按键而非把主输入值当作已提交内容；loading 取消与 idle 提交职责分开 | `runtime-app.tsx` 以 `ui.draft` 持有 grapheme-aware 草稿；Ctrl+O 只切换详情；审批/问卷/计划面板优先消费输入；只有 `submit` 接受后清空草稿 | 自动按键与实际 Windows PTY 均通过：运行中中文/emoji 草稿跨 Ctrl+O、审批选择与 Enter 后保留；取消后继续编辑并等待超过命令窗口，迟到问卷/审批/输出未抢焦点或重开面板 |
| R2 同回合工具分组 / CLI-03、CLI-04 | `src/utils/groupToolUses.ts` 分组资格；`src/components/messages/GroupedToolUseContent.tsx` 每项进行中/错误/结果职责；AUTH-SRC-2026-07-29-A；Observed（源码职责） | 仅具备分组资格、同响应身份和同 Tool 的多项合并；详细模式不分组；组内失败事实不被摘要吞掉 | 本项目协议没有参考响应 ID，采用相邻且 `run+turn+toolName` 相同的搜索 Tool 分组；expanded 逐条；失败永远独立。该键是项目独立协议差异，不声称同实现 | 相邻同 run/turn/name 成功折叠、失败独立、不跨 turn、expanded 逐条的自动回归通过；40/80/120 列 × 24/35 行六尺寸均验证折行与详情可达 |
| R3 内部调用隐藏与真实失败 / CLI-03、TOOL-11 | 已由 ADR-094/095 研究的 Tool 调用渲染与错误结果分派；AUTH-SRC-2026-07-29-A；Observed（源码职责） | 普通内部调用可无调用行；错误结果走独立可见路径 | 正常 Task、计划编排和声明 Tool 隐藏；失败仍显示安全摘要；第一批的受控原因与恢复语义保持不变 | 收起/展开均不泄漏内部 JSON、参数身份或 ordinal；一般失败与详情继续可见。ADR-095 定向和跨进程证据已通过，本批只做回归 |
| R4 有审批命令 / CLI-04、TOOL-11 | `src/components/permissions/BashPermissionRequest/BashPermissionRequest.tsx` 审批职责；`src/tools/BashTool/BashToolResultMessage.tsx` stdout/stderr/无输出/返回状态职责；AUTH-SRC-2026-07-29-A；Observed（源码职责） | 审批显示完整命令；允许、拒绝、取消独立；执行结果按结构化事实呈现，不从显示文本猜状态 | 生产 `approval.requested` 使用受控 preview；TUI 将完整 command、shell、workspace-relative cwd 写入对应 ToolRecord；输出与 exitCode 仍来自现有结构化事件 | 命令审批显示完整 command、shell、目录；Enter 才提交选择；stdout/stderr 与失败保持可见。现有路径已覆盖，不改变权限规则 |
| R4 无审批命令 / CLI-04、TOOL-11 | 参考源码未证明 codej stdio 或 Session Grant 元数据协议；Inferred / Unknown | 可见执行摘要应与实际执行配置一致，不能因未弹审批而丢失或猜平台默认值 | 本项目独立修复：已实例化的 `LocalCommandExecutor` 从真实 `ExecutionBackend.id()` 产生唯一 shell/cwd 描述，经 Composition Root 转交 stdio；完整 command 来自已通过 Tool 参数校验的原始调用。`tool.started` 无论是否审批都携带这些受控字段；审批 preview 复用同一描述，不从 TUI 文本或 OS 猜测 | Startup Allow 自动测试及 Allow Session Java→stdio→Ink/Windows PTY 均通过：第二条相同命令无再次审批，仍显示完整 command、实际 shell ID 与 `.`；审批和 started 元组一致 |
| 流式 delta 与 finalText / CLI-01、CLI-05 | `src/screens/REPL.tsx` streaming 与最终消息职责；AUTH-SRC-2026-07-29-A；Observed（状态职责） | 流式暂态与最终消息有独立合并/隐藏路径，不应把同一正文重复交付 | 同 turn 的 delta 追加到 assistant block；finalText 替换该 block，而非再追加正文 | 多个 delta 后相同 finalText 只出现一次的界面回归通过；终态恢复输入，下一轮可继续 |
| 取消、断连与迟到事件 / CLI-01、CLI-09 | `src/screens/REPL.tsx` abort 与状态清理职责；AUTH-SRC-2026-07-29-A；Observed（职责）/ Inferred（本项目协议） | 取消结束当前运行；旧异步结果不应重新激活已关闭交互 | stdio/TUI 按 request/session/run/sequence 绑定；`cancelWanted` 后只接受当前终态，拒绝迟到正文、Tool 和面板；断连保留草稿但不重开运行 | 自动测试覆盖迟到 approval/question/tool/delta；跨进程取消绑定第三个命令的 runId+ordinal 及同 Run 终态。实际 PTY 在当前命令输出后 Esc，持久化终态为用户取消，等待超过命令窗口后面板未重开且草稿仍可编辑 |

## 3. 命令显示元数据契约

1. `HeadlessRuntimeOptions` 是非 Secret、由可信 Composition Root 固定且同时用于装配本地 Tool/ExecutionBackend 的生产配置。stdio 不从环境、显示文本、模型参数或审批是否发生推断 shell/cwd。
2. command 只从 `run_command` 的结构化 `ToolCall.arguments.command` 读取，并复用 Tool 的长度、空值与控制字符边界；协议可以发送完整已校验 command，不发送整个参数对象。
3. shell ID 由已实例化的 `LocalCommandExecutor` 根据真实 `ExecutionBackend.id()` 产生；`LocalWorkspaceBootstrap` 和 `HeadlessRuntimeSession` 只转交这一份描述。stdio 不复制 backend→字符串映射，TUI 也不根据操作系统猜测。Sandbox/Container 初始化失败仍按原路径 fail closed，不用期望配置伪装实际后端。
4. 当前 `run_command` 固定在 Workspace 根执行，执行器描述只给出 workspace-relative `.`，不暴露绝对用户路径。该值与执行请求的 cwd 同源，不从 output 中的 `workingDirectory:` 文本反向解析。
5. `tool.started` 是不依赖 Permission 结果的“已校验调用与已装配执行配置”预览出口，不表示进程已启动或成功。Approval 发生时仍发送原 `approval.requested`，但二者必须由同一执行器描述生成，避免一个路径显示不同 shell/cwd。
6. 旧宿主缺字段时新 TUI 保持现有降级；不得填造 shell 或目录。普通 Tool 不接收命令专用字段。

## 4. 实现与验证顺序

1. 先添加 Java 生产事件测试：Startup Allow 或 Session Grant 下 `run_command` 无 `approval.requested`，但 `tool.started` 有受控完整 command、shell 和 `.`；审批路径与无审批路径值一致。
2. 最小修改 Composition Root/stdio 描述器与 `BeforeTool` 投影，不修改 Permission 决策或执行请求。
3. 补 TUI 按键与 reducer/渲染测试：审批前已有 command 元数据；审批选择后中文草稿恢复；Ctrl+O 不丢草稿；搜索边界；stream final 去重；取消后迟到审批/正文不重开。
4. 建立确定性 Java→stdio→Ink Fixture，供协调者按真实键盘复验；检查 40/80/120 列 × 24/35 行，不用测试数量替代体验结论。
5. 运行相关 Java/TUI/跨进程回归及 Dashboard generate/check/self-test；更新 S15 证据、handoff、矩阵摘要和 progress-state，但不提升 Capability 等级、不关闭 S15。

## 5. 实施与实际证据

- Java 生产元数据与会话相关回归：`RuntimeStdioCommandHandlerTest` 56、`StdioApprovalCoordinatorTest` 4、`HeadlessRuntimeSessionTest` 56、`LocalCommandExecutorTest` 5、`RunCommandToolTest` 3，共 124 项通过。Startup Allow 无审批路径与审批路径共享完整 command/shell/cwd 的断言均通过。
- TUI build 与全量离线回归：TypeScript 编译成功；22 个测试文件通过、1 个按环境跳过，344 项通过、5 项跳过。协议覆盖稳定 shell ID、封闭命令元组与 C0/C1 控制字符；界面覆盖中文/emoji 草稿、搜索分组边界、流式去重、迟到事件以及六尺寸。
- Java→生产 stdio→Ink：重新编译 12 秒 Fixture 后 `experience-java-e2e.test.tsx` 5/5 通过，40.99 秒；取消等待严格绑定当前命令的 runId+ordinal 及同 Run `run.cancelled`，不命中历史输出。
- 实际 Windows PTY：协调者使用同一 Fixture 观察到 Ctrl+O 后完整命令尾部与 `.`、Allow Session 后草稿恢复、第二条相同命令无审批、第三条命令真实输出后 Esc 收敛为停止；等待超过命令窗口后面板未重开，中文/emoji 草稿仍可编辑。持久化日志中的两次成功、第三次取消 Tool 结果与 Run 用户取消终态一致，Workspace 除 `.git` 外无业务文件。
- `docs/evidence/S15-tui-next-batch2-fixture.ps1` 默认保留并打印临时目录；只有显式 `-Cleanup` 且目标是系统 temp 直属、名称匹配本次随机目录并且不是重解析点时才递归删除。`-Check -Cleanup` 已通过。

以上分别是自动六尺寸、跨进程测试与 PTY 屏幕文本/持久化核对，不是参考 UI 截图、真实 Provider 或跨平台视觉证据。

## 6. 剩余差距

本 ADR 只证明授权源码机制职责已对照和本项目协议可被独立证伪，不声称参考 UI 视觉一致、参考存在同一 stdio 字段或 codej 分组键。真实 Provider 复杂规划质量、首次 Windows 命令生成质量、Markdown 表格、参考 UI/物理终端跨平台视觉、第三批 R5—R6 仍保持 OPEN。
