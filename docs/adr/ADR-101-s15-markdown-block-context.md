# ADR-101：Markdown列表项与块上下文

- Proposed；2026-09-12；S15；CLI-03 L2保持不变。
- 工作入口：G:\AI Cloud\cc-java，feat/tui-redesign，StartCodejDev.ps1 --tui-next。保留ADR-099/100未提交改动。
- 参考：AUTH-SRC-2026-07-29-A，utils/markdown.ts formatToken的list/list_item/text/blockquote/code分支，components/Markdown.tsx MarkdownBody；源码机制Observed，实机视觉Unknown。

| 场景 | 参考机制 | 本项目采用或偏差 | 可证伪验收 |
| --- | --- | --- | --- |
| 嵌套列表 | 列表深度、编号及父token上下文分别传递，标记不作为所有子块的文本前缀 | 列表项正文使用悬挂缩进，仅首个内容行附加本项标记；子列表保留自身标记，不重复父项目符号。Marked负责语法判定 | 两层列表、有序起始编号、多段落、任务项，项目符号次数与实际项数一致 |
| 列表内代码/引用 | code保持原文本；blockquote渲染内部块后施加引用上下文 | 保留ADR100代码侧边线；列表标记只出现一次。引用标记传递到折行、空行和表格键值回退，不丢引用层级 | 引用+代码+空行、中文长引用、列表里的表格与代码 |
| 流式与终态 | MarkdownBody消费解析后的块，StreamingMarkdown容忍未闭合代码 | 保留本项目有界全文重布局；独立行模型，不复制参考ANSI格式器或编号常量 | 每个分片可渲染、终态正文与下一轮实际入口检查 |

本批不增加快捷键、命令、语法高亮依赖或Java协议。代码侧边线是上批独立显示选择，不描述为参考代码块已有相同装饰。极深嵌套的窄屏逐字锚点不作为本批已完成声明。结果写入S15-tui-next-core，未验证不提前记通过。
