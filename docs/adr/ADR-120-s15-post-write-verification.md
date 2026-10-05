# ADR-120：文件落盘后的实际结果核验

Proposed；2026-10-05；S15，CLI-04/05/09 L2、TOOL-10/11 L1，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 文件写入后的结果交付 | `src/tools/FileWriteTool/FileWriteTool.ts`、`src/tools/FileEditTool/utils.ts`、对应 UI 组件，AUTH-SRC-2026-07-29-A，Observed/Inferred | 写入前有内容/身份检查，写入后更新文件状态；成功 UI 显示简短结果，详情可展开结构化 diff | 原子写入完成后由 `WorkspaceWriteVerification` 在同一 `WorkspaceGuard` 边界重新读取字节并核对真实路径、大小和内容；成功 continuation 白名单携带 `verification=verified`，TUI 显示“已核验”。这是项目独立契约，不复制参考文案 | ApplyPatch 17、WriteFile 3、核验辅助类 2、stdio 真实 tool.completed 摘要、TUI 结果摘要测试 |
| 写入后文件漂移 | 参考拒绝预览/用户已手改的错误链路，Observed | 当前文件与预期不一致时显示错误，不把旧预览当作成功结果 | 路径漂移、大小变化、内容差异、删除或重读失败统一返回 `FILE_CONFLICT`；不生成 `verification` 成功摘要 | 内容外部变化、路径安全边界和文件冲突回归 |
| 继续下一轮 | REPL 结果→输入焦点，Observed | 结果终态后仍可继续输入 | 核验失败进入真实失败终态；核验成功保持同一 ToolRecord，不创建任务状态流水 | TUI 终态、下一轮和完整回归 |

核验是一次性结果确认，不是锁、权限、持续监视或 OS Sandbox。核验通过后到后续流程之间仍可能发生外部变化，因此不得描述成持续一致性保证。读取受文件大小上限约束；超限或无法安全读取时失败关闭。该批不实现通用实时磁盘 diff、超大文件扫描或语法高亮。
