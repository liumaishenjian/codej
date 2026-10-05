# ADR-117：模型工具定义与实际命令 Shell 同源

Proposed；2026-09-13；S15；TOOL-10 L1、CLI-04/05 L2，不提升等级。

| 场景 / Feature | 参考链 / 分类 | 采用与偏差 | 验证 |
| --- | --- | --- | --- |
| 命令方言 TOOL-10 | AUTH-SRC-2026-07-29-A，BashTool与PowerShellTool独立注册、各自call进入对应执行路径，Observed | 本项目保留一个run_command，通过已装配executor.display同源Shell事实补充模型定义；不复制参考Prompt、不让模型选择Shell、不按宿主OS猜WSL/Docker方言 | Local/WSL/Docker定义及审批事实一致，读取定义不启动进程 |
| 审批与结果 CLI-04/05 | PowerShellTool→权限回调/UI→执行→原tool_use_id结果，Observed | 保持Java统一权限、准确命令预览、stdout/stderr、类型化失败与人工重审；不自动改写命令或绕过审批 | 参数拒绝shell覆盖，真实PowerShell样本、非零退出纠正和下一轮 |

ADR116真实入口发现模型首次使用Bash的test命令，实际PowerShell失败后才纠正。当前工具定义只说platform shell，没有告诉模型具体Shell。修复这一确定性信息缺失，不声称能消除所有模型语法错误。

由RunCommandTool实例生成稳定ToolDefinition，保留既有schema、Effect、超时、结果预算和ToolSource。描述附加同一执行器的Shell ID；PowerShell提示使用其原生语法、明确检查原生命令退出码，sh提示使用POSIX兼容语法。说明不包含可执行文件绝对路径、凭证或环境变量值。WSL/Docker按已装配后端显示事实使用sh说明，不因宿主Windows而写PowerShell。未知后端只报告身份，不臆测可用工具。

验证先运行Java命令/权限相关离线回归，再完整TUI Java Fake，最后真实新界面隔离目录提交未指定Shell的中文文件检查任务，检查生成命令、审批、实际输出和最终回答。真实样本只证明该次行为，Windows PowerShell 5.1、Linux实机与长期模型质量继续保留差距。
