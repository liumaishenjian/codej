# ADR-119：文件工具实际结果摘要

Proposed；2026-10-04；S15，CLI-04/05/09 L2、TOOL-10/11 L1，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 文件写入完成后确认产物 | `src/components/messages/GroupedToolUseContent.tsx`、`src/tools/FileEditTool/` 的调用与结果关联，AUTH-SRC-2026-07-29-A，Observed/Inferred | 默认行显示简短结果，详情可展开完整工具输出；审批意图不代替工具终态 | Java `ToolResultMetadata.continuation` 中已有受控 path/operation/行数/替换数，stdio 只投影白名单 `resultSummary`；TUI 默认显示真实路径和替换数，正文仍在 Ctrl+O 详情。未知 continuation 不显示 | Fake 终态摘要、Java stdio 编译/回归、TUI runtime 76/76 |
| 失败或敏感结果 | 工具结果与错误消息链路，Observed | 失败保持失败语义，不以预览或部分输出冒称成功；敏感结果不展开 | `tool.failed` 不读取摘要作为成功文案；`contentRedacted` 清除审批预览；路径安全失败时审批片段脱敏 | 敏感 `.env`、冲突、拒绝和脱敏回归 |
| 下一轮继续 | REPL 结果→输入焦点，Observed | 结果落地后可以继续下一轮，旧工具摘要不阻塞输入 | 只更新同一 `ToolRecord`，不创建额外“任务更新”流水；`finish` 与摘要生命周期不变 | 真实入口下一轮与 76/76 终态测试 |

这是独立 Java/TypeScript 契约，不复制参考源码表达。`resultSummary` 只服务用户可见事实，不能作为执行授权、磁盘锁、Git diff 或计划验收证据。实时磁盘 diff、超大文件扫描、语法高亮、参考实机视觉和跨平台仍是后续差距。

