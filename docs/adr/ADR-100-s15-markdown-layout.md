# ADR-100：新界面正文表格与缩进

- 状态：Proposed；日期：2026-09-12；Stage S15；CLI-03 L2保持不变。
- 入口：G:\AI Cloud\cc-java，feat/tui-redesign，StartCodejDev.ps1 --tui-next；延续ADR-099工作树，不覆盖上批改动。
- AUTH-SRC-2026-07-29-A：源码机制Observed，参考产品运行视觉Unknown，准确Revision Unknown。

| 场景 | 参考调用链及行为 | 独立实现与偏差 | 验证 |
| --- | --- | --- | --- |
| 表格 | components/Markdown.tsx MarkdownBody从Marked分出table→MarkdownTable；计算列宽、按单元格折行并对齐，过窄切换键值 | 保留已有Marked，生成现有Row/Span；按终端单元格宽度分配，保留原文和对齐。不能容纳列或单元格过高时逐条表头/值，算法与阈值独立选择，不复制函数体 | 40/80/120列、中文/emoji、左中右对齐、空格与转义管道符、窄屏不丢数据 |
| 流式正文 | Markdown.tsx StreamingMarkdown使用词法块边界，未闭合代码交由Marked解析 | 继续现有有界全文缓存，每次内容/宽度变化重新布局；本批不复制稳定前缀缓存，性能提升未声明 | 分片表头/代码围栏→完整正文，无异常且终态只呈现一次 |
| 代码及列表 | utils/markdown.ts formatToken对code/list按块处理，MarkdownBody呈现；不把源码标记直接铺到正文 | 代码块增加连续侧边线，语言只作标签，原始缩进和空行保留；普通列表折行使用悬挂缩进。无语法高亮新依赖，嵌套复杂列表继续记录差距 | 未闭合代码、反引号、中文长行、空行、列表续行、40列 |

不改Java/stdio/权限、计划Gate，不新增用户命令。先渲染及实际新界面协议回归，再正式入口真实Provider输出独立示例、观察最终正文和下一轮；物理截图与参考视觉未实测不宣称一致。结果记入S15-tui-next-core。
