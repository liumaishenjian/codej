# ADR-104：核心命令Tab补全

Proposed；2026-09-12；S15，CLI-04 L2不变。feat/tui-redesign，codej --tui-next。保持用户已认可的布局及品牌。

| 场景 | 参考链路 | 本项目采用与边界 | 验收 |
| --- | --- | --- | --- |
| Tab补全 | AUTH-SRC-2026-07-29-A；PromptInput→useTypeahead→handleTab→applyCommandSuggestion，补全只改输入并清候选，不调用提交；源码Observed | 现有两项命令匹配，Tab把选中命令写入草稿并留空格继续参数；↑↓选择、Shift+Tab原有反向选择保留。只提供/plan与/help，不扩展参考模式/文件建议 | /pla补全不启动Run，补任务后Enter才进入计划；多候选选中正确 |
| 运行中 | 已确认产品需求：保留后续草稿，不自动排队 | Tab只编辑，运行中Enter不启动新Run；面板Tab继续原有切题职责 | 当前Run结束前不提交，问卷回归 |

原实现把Tab与↑↓都视为切换候选索引，导致/pla无法用Tab补为/plan。新行为仅替换这个分支，不增加命令、协议或Agent Loop。验证Fake按键、既有回归、正式PTY补全后仍可编辑；涉及规划执行的验收仍复用原完整计划链，本批不改变其审核行为。