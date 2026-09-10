# ADR-091：已认可界面的真实核心流程接入
- 状态：Proposed
- 日期：2026-09-09
- Stage：S15；CLI-01/03/04/05/09 L2保持，TOOL-11/PLAN-01 L1保持。
- 参考：AUTH-SRC-2026-07-29-A，仓库外只读；前端方向由维护者验收认可。
- 工作树：feat/tui-redesign，main e51e6b5。

## 受控研究与决定
核对 REPL/PromptInput 的分区与面板焦点；groupToolUses 的同回合同类分组与详览；
BashToolResultMessage/权限面板的命令与输出职责；AskUserQuestionPermissionRequest 的多题、
选择/文字编辑/复核分离；commands/plan/plan.tsx 的进入模式与当前计划展示。
采用以上可独立表达行为，不复制参考类型、常量、prompt或函数表达。准确来源版本Unknown。
使用已有 StdioClient/Java Runtime/统一Tool Pipeline；Marked负责基础Markdown解析，
Ink负责终端刷新。不另造Agent Loop，也不整体迁入旧重构。

## 本批确定契约
演示入口保留，真实入口 --tui-next。真实消息按请求、Session、Run和Tool ordinal关联；
活动状态只来自事件。连接/取消等待期间不接受新Run，不自动发送运行中草稿。
终端变小保留内容与焦点，Esc始终可取消，Ctrl+C始终可退出。
失败/断连/结束清除当前审批与问卷；迟到事件不可重新打开。计划review可先于run终态到达，
只在后端就绪后提交。/plan是唯一计划命令，bare进入模式或查看当前计划，带任务启动规划。
计划确认固定 APPROVE_USER + KEEP，后续操作正常审批；修改意见使用同一原子review入口；
取消不启动执行。原始review身份保留给协议，不展示内部id/digest。

基本工具审批仅复用once/session/deny。Esc取消Run，拒绝选项使用已有deny语义，不伪称停止。
真实审批附加说明本批不开放。工具详情只展示后端安全投影，不以字符计数伪装真实结果。
新问卷与显示元数据按初始化能力协商；旧单选不被静默改变。问卷扩展见ADR-092。

## 可证伪验证
实际Ink键盘+Fake client：输入/流式/多Tool交错、拒绝/取消/迟到事件/重复提交/换Session/
断连、完整问卷、Plan修改和确认执行。Java Fake进程端到端核对真实命令与结果绑定；
真实Provider在已配置条件下显式测试。视觉帧与真实PTY证据单独登记。
必要预算：终端派生文本有界、详情明确截断；不承诺长历史性能、完整历史恢复、
后台任务、复杂配置、新plan扩展命令或默认安装入口切换。
