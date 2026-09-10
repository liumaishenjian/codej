# ADR-093：规划必须形成可审核工件才能成功结束

- 状态：Proposed
- Stage：S15；CLI-05/09保持L2、PLAN-01保持L1
- 基线：AUTH-SRC-2026-07-29-A；2026-09-10；Observed（源码）

只读核对参考 commands/plan/plan.tsx 的模式入口和现有计划展示，以及 tools/ExitPlanModeTool/ExitPlanModeV2Tool.ts 的显式退出规划、计划提交和权限审核职责。采用“形成计划后进入用户审核”的机制，不复制参考源码或提示文本。一次纠正属于本项目既有 FinalAssistantHandler/FinalAssistantDecision 的独立有界恢复，不能宣称参考实现也使用同样次数。

问题：当前 Plan 最终回合即使没有 reviewArtifact 也被接受；stdio 有意隐藏规划模型原文，导致用户看到空白成功。无审核工件不代表计划完成，更不能代表已经执行。

决策：继续使用现有同一 Core Run 循环。第一个没有审核工件的最终回合只触发一次继续请求，并通过现有 instructions projection 提醒生成计划工件及请求审核；不能授予工具能力、提前联网或执行计划。再次无审核工件时拒绝，以 INVALID_MODEL_RESPONSE 停止。预算、取消与现有工具管线不变；失败模型原文不直接投影为UI，以免暴露内部JSON。已有审核工件正常发布一次review事件并成功结束。

前端兼容补救：规划模式无待审核计划时，INVALID_MODEL_RESPONSE 或旧宿主无正文的 COMPLETED 显示“未生成可审核计划，未开始执行，可补充要求继续规划”。不增加协议字段，不影响已有有效审核路径。

验证：Fake模型连续拒绝最多两轮失败；首轮拒绝后合法生成工件并请求审核可成功；只保存草稿而未审核不能成功；纠正后取消和只读权限保持。保留既有计划审核/执行成功回归。暂无本次真实Provider通过声明。

后端验证：HeadlessRuntimeSessionTest（含无工件一次纠正、成功恢复、草稿、取消和只读边界）、RuntimeStdioCommandHandlerTest、DurablePlanExecutionHandoffTest、StdioQuestionCoordinatorTest 定向回归通过，见 target/adr093-plan-final.log。原问卷规划测试已补为真实保存计划并请求审核，保留其成功场景含义。首次只读约束测试的空工件成功预期改为失败并增加回合上限断言；没有放宽权限或删掉副作用验证。未修改 RuntimeStdioCommandHandler 生产代码。

真实Provider同题已返回审核面板；内部计划工具仅显示实际生命周期，避免身份JSON混入正文。完整证据见 [核心验收记录](../evidence/S15-tui-next-core.md)。
