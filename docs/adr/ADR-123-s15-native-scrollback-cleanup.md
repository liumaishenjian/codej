# ADR-123：移除演示层自造的历史分页提示

Proposed；2026-10-05；S15，CLI-01/04/05/09 L2，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 普通终端历史阅读 | `src/screens/REPL.tsx`、Transcript/PromptInput 组织，AUTH-SRC-2026-07-29-A，Observed/Inferred | 普通历史由终端滚屏承载，应用不插入“更早内容”或自定义分页器状态 | 离线演示 `screen.tsx` 不再向正文前插入“↑ 更早内容 · PgUp 回看”或“正在回看”行；正常运行的 NativeHistory 仍使用终端滚屏，Ctrl+O 详情页保留必要的阅读操作提示 | 既有 Runtime/NativeHistory 视图回归以及演示帧断言；真实入口视觉仍需物理终端截图验收 |

该清理只删除无依据的可见提示，不改变终端滚轮、Ctrl+O 详情、End 回到底部或输入焦点行为。
