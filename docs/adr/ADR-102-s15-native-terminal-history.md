# ADR-102：新界面恢复终端原生历史

- Proposed；2026-09-12；S15，CLI-01/03/04/09 L2保持不变。
- G:\AI Cloud\cc-java，feat/tui-redesign，StartCodejDev --tui-next。保留ADR099—101工作树。
- AUTH-SRC-2026-07-29-A；源码职责Observed，参考物理交互Unknown。

| 场景 | 参考链路 | 本项目采用及偏差 | 验收 |
| --- | --- | --- | --- |
| 普通历史 | utils/fullscreen.ts isFullscreenEnvEnabled默认普通模式；REPL.tsx普通输出与AlternateScreen分支区分原生scrollback和全屏虚拟滚动 | 默认新界面使用公共Ink Static写稳定前缀，不再把全部历史裁成固定屏幕。仓库原AgentTui已有Static机制，沿用公共能力；不复制参考自有Ink | 多轮长正文进入原生缓冲，历史不反复打印，不发清空scrollback序列 |
| 活动区 | REPL的消息和输入分区；稳定输出与流式/权限交互职责分离 | 只保留可变尾部和面板重绘，高度严格小于屏幕，避免Ink7 Windows全屏清屏回退。工具同回合相邻分组在边界明确后封存；可恢复内部失败在Run终态前不封存 | 流式最终修正、审批、取消、草稿、工具分组 |
| 展开历史 | REPL transcript路径区分pager与dump，普通历史不要求用户学习PgUp | 主界面不再显示“更早内容/PgUp回看”；滚轮由终端处理，不捕获鼠标。Ctrl+O保留可交互详情视图和现有键盘阅读；不将其冒称原生滚动 | 输入不受滚轮事件干扰；详情返回保持草稿 |

未完成的当前Markdown块必须可更新，不能提前永久写入后又悄悄失去finalText修正。超长尚未稳定尾部继续有界显示，完整内容在其稳定后进入原生历史；Ctrl+O可查看当前完整记录。这一限制必须明确，不宣称全屏虚拟滚轮已实现。鼠标物理滚动由具体终端提供，PTY只能检查字节输出及无清空历史，不能代替实机鼠标验收。
