# ADR-105：核心输入、问卷复核与详情阅读整批收口

Proposed；2026-09-12；S15，CLI-04/05/09 L2、TOOL-10 L1不变。G:\AI Cloud\cc-java，feat/tui-redesign，codej --tui-next。维护者认可当前交互、要求扩大交付批次；本批保持布局、品牌、两项命令及安全管线。

| 场景 | 参考链路及证据分类 | 本项目采用/必要偏差 | 可证伪验收 |
| --- | --- | --- | --- |
| 编辑与历史 | AUTH-SRC-2026-07-29-A PromptInput→TextInput→handleHistoryUp/Down；已有input-editor.ts moveCursor与detachForEdit区分导航/改文档；Observed | 普通光标移动不退出历史，改正文才退出。修正最近新界面仅上下移动保留历史的遗漏；复用已有按词移动，支持已在旧版存在的Ctrl/Alt+左右，不加入新菜单 | 历史取回→移动→返回原稿；中英文/emoji按词移动；面板编辑一致 |
| 问卷返回修改 | AskUserQuestionPermissionRequest/use-multiple-choice-state按题保存选择/文本，QuestionView传回defaultValue，CustomSelect/use-select-navigation保存焦点与可视范围；Observed | 按题保存焦点，返回修改能定位原选项；未保存自由文本继续留在草稿，最终只提交明确保存的答案。多选已有其他选项时允许清空自由回答，必答仍拦截空答案 | 单选/多选/自由回答→复核→返回改答；清除自由文本；缺必答阻止提交；新请求清理焦点 |
| 长工具/审批 | ADR099/102已研究BashPermissionRequest/输出详情及独立滚动；ScrollKeybindingHandler按实际滚动范围处理；Observed，参考物理UI Unknown | 按键计算与实际渲染共用活动区高度；修正原生历史预留两行后按键仍按全高计算的错误；详情内容、选择和草稿职责不变 | 40/80/120×24/35，长命令尾部、目录与允许/拒绝始终可达；多次翻页/展开/返回；取消迟到事件 |

分批内先写真实失败复现，再修复；最终统一运行TUI+Java stdio回归，正式PTY按键验证输入和长详情，并记录哪些问卷/审批分支为Fake生产链证据。没有新增Java协议、权限决策、Agent Loop、队列或后台任务。稳定历史与超长未稳定输出仍遵循ADR102，不能把本批描述为全文滚动或参考视觉完全一致。