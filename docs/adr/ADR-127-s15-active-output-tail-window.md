# ADR-127：流式工具输出的有界尾窗

Proposed；2026-10-05；S15，CLI-04/05/09 L2、TOOL-10/11 L1，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 运行中的长工具输出 | `src/components/messages/GroupedToolUseContent.tsx`、工具进度与结果关联链，AUTH-SRC-2026-07-29-A，Observed/Inferred | 进行中的调用保持可见并跟随最新输出；完成后结果可继续展开阅读，输入焦点不被输出量夺走 | TUI 对正在运行的 ToolRecord 只对最近 16,384 字符折行；完成或取消后的记录仍使用 Java 有界正文。折叠预览继续显示最近三行并说明运行中尾窗，Ctrl+O 在完成后显示可用全文；不改变 Java 输出上限、脱敏、取消或 Tool 终态 | `experience-render-cache` 覆盖 5,000 行运行中输出的尾窗与完成后全文；TUI build 与完整标准回归 449 passed / 7 skipped |
| 当前回合的流式长回答 | `src/screens/REPL.tsx` 的活动回答区域，AUTH-SRC-2026-07-29-A，Observed/Inferred | 活动内容跟随最新尾部，终态可回看完整回答；状态变化不能被旧排版缓存遮蔽 | 当前回合的 assistant Message 同样使用 16,384 字符排版尾窗；body projection 缓存额外绑定运行状态，完成后同一 blocks 快照不会继续复用运行中尾窗 | `experience-render-cache` 覆盖 5,000 行回答的运行中/终态切换；不改变最终正文文本；标准回归 449 passed / 7 skipped |

该尾窗是排版优化，不是对宿主输出的二次截断，也不影响后续事件关联。语法高亮、超大文件扫描和参考产品物理终端视觉仍未因本 ADR 完成。
