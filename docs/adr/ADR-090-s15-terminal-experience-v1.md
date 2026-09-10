# ADR-090：独立终端体验第一版
- 状态：Proposed
- 日期：2026-09-09
- Stage：S15
- Feature：CLI-01/03/04/05/09 L2 → L2；TOOL-11、PLAN-01 L1 → L1；不提升等级。
- 基线：main e51e6b5，分支 feat/tui-redesign。
- 授权参考：AUTH-SRC-2026-07-29-A，只读研究；不复制源码表达。

## 决定
根据维护者指令放弃旧前端呈现结构，在干净 main 上独立实现终端体验原型。
本轮交付是可操作前端及固定数据演示，不接 Java、不执行 Shell、不调用模型。
先验收完整视觉交互，再迁入必要后端适配，避免再次用局部机制测试替代体验。
旧工作区保留，不复制旧 rebuild 目录或大批协议改动。

## 本轮核对的核心参考（相对 G:\AI Cloud\claude-code-main）
| 源码入口 | 可独立表达机制 | 本项目采用与边界 |
| --- | --- | --- |
| src/screens/REPL.tsx | 消息区、活动状态、输入及对话框分别调度 | 视图分层，面板独占焦点；截图体现的布局为目标，尚未声称所有模式一致 |
| src/components/messages/UserPromptMessage.tsx | 用户消息背景、普通和精简模式不同 | 全宽低对比背景，保留完整记录 |
| src/utils/groupToolUses.ts | 同回合同类支持分组的工具合并；verbose恢复顺序 | 示例搜索合并为一条摘要；展开显示详细记录 |
| src/components/messages/GroupedToolUseContent.tsx | 按调用关联结果和进行状态 | 同一工具块原位更新，避免stdout刷满记录 |
| src/components/PromptInput/PromptInput.tsx | 水平边界、输入、页脚分离 | 上下细线、上下文提示、多行草稿；普通终端尺寸自适应 |
| src/components/Spinner.tsx | 集中运行状态与时间 | 独立状态行；不捏造模型token计量 |
| src/tools/BashTool/BashToolResultMessage.tsx、src/components/shell/OutputLine.tsx | 输出、错误和详览密度分离 | 紧凑结果与完整模拟输出，失败给下一步 |
| src/components/permissions/BashPermissionRequest/BashPermissionRequest.tsx | 完整命令、描述、决策、附加说明、取消 | 模拟审批不落地权限；本次/会话选项明确属于演示 |
| src/components/permissions/AskUserQuestionPermissionRequest/QuestionView.tsx | 单选多选不同、自由输入和选项焦点分离 | 编号、箭头、空格多选、自由回答 |
| 同目录 QuestionNavigationBar.tsx、SubmitQuestionsView.tsx | 题目导航、保留答案、提交前复核 | 前后切题保留草稿，漏题不可最终提交 |
| src/components/CustomSelect/select.tsx | 选项说明、选项内编辑、确认与取消 | 独立选项/编辑焦点，不复用正文快捷键处理 |

上述是源码观察；用户提供截图是实际外观观察。准确版本/私有渲染器可复现构建仍Unknown。
本项目使用锁定的 React/Ink 公共依赖，自行定义名称、布局与示例任务，不使用参考图标品牌、prompt、常量或参考字节fixture。

## 第一版验收
入口 preview:tui。覆盖 /demo、/bash、/questions、/diff、/error；Ctrl+O详情，
PgUp/PgDn记录，Esc停止/取消，Ctrl+C退出；编辑器含中文、组合字符、光标、删除、Ctrl+J换行。
问卷单选确认后前进，多选空格切换，Other独立编辑，Tab/左右导航，最终复核才提交。
40/80/120列、24/35行检查裁切和焦点；正常终端内容不足一屏时输入跟随正文，超过一屏时保留面板与输入。
实际图像核对和基础按键测试必须分开报告；自动通过不代表维护者接受视觉方向。
不承诺生产权限、真实工具、后台进程、登录、跨平台、长历史性能或完整编辑器兼容。
