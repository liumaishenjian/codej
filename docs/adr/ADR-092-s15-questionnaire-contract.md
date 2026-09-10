# ADR-092：核心问卷的协商式整批交互

- 状态：Proposed
- Stage：S15；Feature：CLI-05/09 L2保持、TOOL-11/PLAN-01 L1保持
- 基线：AUTH-SRC-2026-07-29-A，2026-09-09，Observed（源码）

只读研究 AskUserQuestionPermissionRequest 的 QuestionView、SubmitQuestionsView 和 use-multiple-choice-state。参考机制为每题保留选择及文字草稿、单多选分别处理、最后复核再统一提交。学习授权不扩大为复制授权，不引入参考字节。

独立契约：沿用 question.requested/question.resolve 与同步取消端口，questionnaireV1 协商后启用结构化 questions/answers。旧单题协议和 ask_plan_question 保持可用。新 ask_user_questions 仅在支持问卷的 Surface 注册并通过统一工具管线；普通和 Plan 共享。callId/Session/Run 校验、答案题号与选项归属、唯一答案、整批完整性由后端确定性检查。单选文字与选项互斥，多选可同时有文字。所有题必答；纯文字题允许零选项；至多四题、每题八项、答案每题 2000 UTF-16 单元；题目总文本 24000 UTF-8 字节，保留 JSON 转义和信封余量。新问卷的题签、问题、选项文本均按 UTF-16 单元限制；旧单选保持原预算。均为本项目传输预算。

验证：Domain 约束、协调器取消/重复/迟到答案、工具参数与结果、旧协议回归、协商协议和 TypeScript 客户端。UI 对照由主任务独立验收；此 ADR 不宣称视觉对齐。

验证记录：定向 Java（含新问卷双工具调用、普通与 Plan、旧单题）通过，见 target/questionnaire-integration.log；TypeScript 问卷和既有协议/client 81/81 通过，见 target/questionnaire-ts-tests.log。严格聚合 Javadoc 因既有核心类等文档警告失败，不能据此宣称全局通过；新增公共契约文档已补齐。真实终端问卷视觉与跨进程验收由主任务继续。

最终后端验证：相关 Java 模块全回归 1299 tests，0 failures，0 errors，33 skips（target/questionnaire-java-regression.log）。最终选项 UTF-16 预算改动后再次定向验证通过（target/questionnaire-final-targeted.log）。新增问卷 Domain/Core 五个公共契约文件单独使用 javadoc -Xdoclint:all -Werror 验证，0 warning（target/questionnaire-javadoc-focused.log）；这不替代全仓聚合 Javadoc。

跨进程与新界面验证已完成，详见 [核心流程验收证据](../evidence/S15-tui-next-core.md)；维护者视觉验收待完成。
