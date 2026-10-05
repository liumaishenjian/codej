# ADR-124：长工具输出的有界排版与阅读缓存

Proposed；2026-10-05；S15，CLI-04/05/09 L2、TOOL-10/11 L1，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 长命令输出 | `src/components/messages/GroupedToolUseContent.tsx`、`src/utils/groupToolUses.ts`，AUTH-SRC-2026-07-29-A，Observed/Inferred | 默认摘要不展开全部输出；详情在当前视图内阅读，状态变化不会破坏输入 | TUI 继续由 Java 输出上限提供输入边界；命令折叠预览和 Ctrl+O 详情都按宽度生成有限行，并以不可变 ToolRecord 做 WeakMap 缓存；流式事件创建新记录，旧排版可回收 | 40/80/120 列、24/35 行工具输出与输入草稿回归；`experience-render-cache`、Runtime 应用 76/76 |
| 文件变更预览 | `src/tools/FileEditTool/utils.ts`、diff 相关 UI，Observed/Inferred | 差异采用有限上下文；过大内容不阻塞主界面 | 审批前 `FileChange` 已限制前后各 6000 字符和 jsdiff 预算；超限转摘要，详情不猜测完整行号 | 文件审阅六尺寸、敏感/超限/冲突场景保持通过 |

该缓存只优化已接收记录的排版，不改变 Java Tool 输出上限、脱敏、取消、终态或审批判断；超大文件扫描、语法高亮和参考产品物理视觉仍是后续差距。
