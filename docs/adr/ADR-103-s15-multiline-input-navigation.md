# ADR-103：新界面多行输入与历史边界

Proposed；2026-09-12；S15，CLI-04 L2保持不变。G:\AI Cloud\cc-java，feat/tui-redesign，codej --tui-next。维护者已认可现有交互和启动品牌，本批不改布局或新增命令。

| 场景 | 参考链路 | 采用及偏差 | 验收 |
| --- | --- | --- | --- |
| 上下移动 | AUTH-SRC-2026-07-29-A，PromptInput.tsx的TextInput输入到handleHistoryUp/Down，首/末行条件先于历史；源码Observed，参考实机Unknown | 复用本项目input-editor.ts reduceComposer的MoveUp/Down及视觉列定位；新Draft仅适配文字、字素光标和目标列。无新编辑算法 | 中文/emoji、硬换行、软折行、长短行保留目标列 |
| 历史边界 | 同上，输入内部移动优先，候选与面板独占焦点 | 仅普通空闲输入到边界才调用已有历史；运行中只编辑草稿；问卷和反馈不回调会话历史 | 到边界才取历史，返回保留原稿，多行自由回答与反馈可编辑 |

当前新界面只实现左右与Home/End，含换行时↑/↓无动作，长单行软折行时可能误取历史。保留现有候选、审批选择和最终复核；不接参考队列、页脚导航或新增快捷键。无Java、stdio、Permission、Agent Loop变更。验收包括Fake按键、既有尺寸回归及真实启动器PTY输入，不将字节记录当作参考视觉一致性。