# ADR-109：长内容阅读与窗口重排

Proposed；2026-09-12；S15，CLI-03/04/09 L2不变；feat/tui-redesign，codej --tui-next。

| 对照 | 授权参考 AUTH-SRC-2026-07-29-A（Observed） | 本项目契约 | 验收 |
| --- | --- | --- | --- |
| 阅读范围 | REPL handleEnterTranscript捕获消息长度，transcriptMessages按长度截取；退出清除 | Ctrl+O进入时记录块数量，不复制旧快照；新记录暂不进入阅读区，已有记录始终取最新对象，避免冻结失败/取消/脱敏。End显式查看最新并更新范围，Ctrl+O返回正常终端 | 新回合追加不改变阅读正文；终态和脱敏仍替换；新审批面板立即出现；返回保留草稿 |
| 解析与排版 | components/Markdown.tsx cachedLexer缓存与宽度无关的Marked tokens | 使用现有Marked，不写增量语法。正文token缓存按原文LRU，最多32项且原文总长度不超过1048576 UTF-16单元，超过预算单条不缓存；宽度相关行缓存仍独立 | 24条消息跨宽度不重新解析；最终修正/后置链接定义/未闭合代码须重新解析；缓存不改变正文 |
| 操作提示 | REPL transcript footer与退出控制 | 仅详情页提示Ctrl+O返回、End最新和PgUp/PgDn阅读，不改正常终端滚轮或增加命令 | 六尺寸、切换/新事件/输入、断连、原生历史回归 |

不同于参考产品fork的虚拟滚动，本项目继续使用公共Ink与正常buffer Static。只固定阅读范围，不冻结可变正文，不宣称逐行resize锚定或增量AST。正常模式不捕获鼠标；OSC8、详情鼠标与参考实机视觉仍保留差距。
