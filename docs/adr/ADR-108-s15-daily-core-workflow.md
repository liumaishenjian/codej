# ADR-108：核心流程日常使用整批收口

Proposed；2026-09-12；S15；CLI-03/04/05/09 L2、TOOL-10/PLAN-01 L1 不变。当前分支 `feat/tui-redesign`，唯一工作区 `G:\AI Cloud\cc-java`，验收入口 `codej --tui-next`。维护者已授权整批持续执行，完成前不拆成按键级交付；不自动提交、推送或替换默认入口。

## 对照与采用范围

授权快照 AUTH-SRC-2026-07-29-A，只读研究，以下均为源码 Observed，非参考实机视觉证据。复用 ADR099–107 的 R1–R6 调用链，新增研究如下。

| 整块内容 | 源码对照 | 本项目机制及边界 | 验收 |
| --- | --- | --- | --- |
| 长回答、工具流与输入 | REPL.tsx 的 deferredMessages、utils/messages.ts text_delta 累积、AssistantTextMessage/Markdown 按内容渲染 | 保持 Runtime 同步接收完整事件，只在视图订阅层合并短时间内的文本/工具输出更新。公共 Ink 与参考 fork 调度不同，采用有界定时发布是独立偏差；状态切换、审批、取消、脱敏和最终替换立即发布并清掉延迟，不能封存可修正文本 | 密集增量显著减少视图发布，逐字无丢失；终态/取消/断连立即呈现且无迟到恢复；输入不重解析旧历史 |
| 短窗口完整操作 | AskUserQuestionPermissionRequest/QuestionView → CustomSelect/SelectMulti → visibleOptions/visibleFromIndex | 根据可用高度限制选项窗口，始终包含焦点；完整题目/长标签/说明仍在可分页详情。错误提示计入布局预算，不能挤掉焦点、输入或确认 | 40/80/120列×24/35行，8选项加自由回答、长描述、验证错误、切题复核和返回编辑 |
| 工具执行到结果 | R2/R4，groupToolUses、GroupedToolUseContent、BashToolResultMessage、BashPermissionRequest；沿用ADR099/105 | 复用调用身份、原位更新、真实Shell/目录、唯一审批管线；集中复验搜索、文件修改、失败/取消和下一轮 | Java Fake完整事件与最终正文；真实Provider审批执行/失败修正和实际文件内容 |
| 问卷与计划交付 | R5/R6，AskUserQuestion、ExitPlanMode及计划入口；沿用现有原子审核协议 | 单选/多选/自由回答→复核；计划修改→审核→执行→文件及验证→最终回答，失败保持可解释停止 | 批量答案对应真实工具结果；重复确认不重放；拒绝不修改文件；执行完成不以任务状态代替产物/最终正文 |
| 连续使用与交接 | REPL输入、状态、终态与现有正常buffer Static机制 | 保留品牌与核心命令，集中整理可复验操作清单；不新增后台任务、插件菜单、plan附属命令 | 连续多轮、窗口变化、回看、草稿、断连与退出；完整TUI/Java Fake、launcher及看板 |

## 实施与停止条件

先写失败复现；实现输出发布调度与有界面板；运行定向异常回归；再运行整套四条核心链和真实Provider连续使用；最后同步矩阵/设计/证据/看板。只有实际证据支持才记录通过。额度剩余1%停止；不因单项实现完毕要求维护者再次授权继续。

超长单条 Markdown 仍使用完整 Marked 解析：本批先减少重复调用，不把有界发布描述为增量AST或无上限输出。普通终端稳定历史不可事后改写，因此当前回合的可修正内容不能提前冻结。OSC8、详情鼠标、参考实机视觉完全一致、跨平台同等体验仍保持差距。

补充源码对照：QuestionNavigationBar 按终端宽度压缩标签，完整问题仍由 QuestionView 展示。本项目保留短导航与可分页完整标题，避免四个最大标题淹没问题。Markdown.tsx 将非表格内容聚合后交给 Ansi，未逐字符创建React元素；本项目在最终绘制边界合并相邻同样式 Span，排版阶段的字素、宽度和光标契约不变。用长正文的节点数量及样式/字素展开等价性证伪错误合并，不复制参考 ANSI renderer。
