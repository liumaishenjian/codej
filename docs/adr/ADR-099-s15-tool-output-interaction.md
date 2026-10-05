# ADR-099：工具执行摘要、输出预览与展开位置

- 状态：Proposed；日期：2026-09-12；Stage：S15。
- Feature：CLI-05/09 L2、TOOL-10 L1，目标等级不变。
- 当前入口：G:\AI Cloud\cc-java，feat/tui-redesign，StartCodejDev.ps1 --tui-next。
- 授权基线：AUTH-SRC-2026-07-29-A；源码机制为 Observed，参考产品实际运行视觉为 Unknown。不复制参考实现表达。

| 用户场景 / Feature | 参考路径与调用链 | 参考行为 | 采用机制 / 必要偏差 | 可证伪验收 |
| --- | --- | --- | --- | --- |
| 搜索分组 / CLI-05 | utils/groupToolUses.ts applyGrouping → messages/GroupedToolUseContent.tsx → 工具 renderGroupedToolUse；按同模型响应及工具类型关联结果 | 聚合同类型调用、关联每项结果；详览恢复逐项记录，运行与错误单独有状态 | 当前协议用 Run+turn+ordinal，对相邻且成功的同类本地搜索分组；失败/取消/运行中调用独立。数量使用返回项字段，缺失不猜测；调用数不冒称命中数 | 同回合相邻成功、跨回合、错误、取消、缺失统计和展开原始输出 |
| 命令输出 / TOOL-10、CLI-05 | BashToolResultMessage → shell/OutputLine → renderTruncatedContent；stdout/stderr输出与执行状态分开 | 默认可看到有界输出；展开读全文；空输出、失败、进行中分别表示 | 复用 tool.output 文本增量和终态结果，在单条调用下面显示有界预览；完成命令仅识别本项目 RunCommandTool 的固定包装头，不把输出文本推断为执行成功。原始正文在详览保留；使用既有截断事实 | 多行中文输出、无输出、stderr、非零退出、取消、迟到输出、宿主脱敏 |
| 展开与草稿 / CLI-09 | REPL.tsx app:toggleTranscript 及 transcript 缓存；OutputLine 的 verbose/expand分支 | 展开是呈现选择；与运行状态、输入分别管理 | 保留两种视图各自滚动位置，返回后恢复草稿/焦点；仍使用当前视口，不复制参考冻结分页器。按已渲染行位置恢复，宽度变化后按合法范围收敛 | PgUp回看→Ctrl+O→输出继续→Ctrl+O返回；草稿和审批选择不变 |
| 命令审批 / TOOL-10 | BashPermissionRequest → BashPermissionRequestInner → 原命令展示与onReject/onDone | 审批查看实际命令后作决定，不能把描述当作实际命令 | 沿用当前完整Shell、命令、目录和可滚动详情及一次/会话/拒绝；不扩展说明输入或权限范围 | 40/80/120列，长命令尾部可读，拒绝无执行，Esc取消 |

## 边界与证据

只修改终端适配器，不新增Agent Loop、协议、后台任务或命令。结果计数只使用宿主returnedItems；仅已成功完成的调用才可计为成功，UI预览截断与宿主截断分别表述。已脱敏正文不得从缓存中重新露出。

先做确定性协议/渲染回归，再走生产入口实际按键并看到最终正文及下一轮。测试和实测完成后在S15-tui-next-core.md记录；未通过前不宣称一致或完整完成。Markdown表格留到下一批。
