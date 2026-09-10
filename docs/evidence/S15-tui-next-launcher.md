# S15 新前端开发入口与真实展示元数据

日期：2026-09-09。工作树：G:/AI Cloud/cc-java-tui-redesign；分支 feat/tui-redesign。

## 实现边界

- --tui-next 由 CodejDevLauncher 消费，只交给 Node；不会成为 Java CLI 参数。
- 新入口渲染 ExperienceRuntimeApp(client, workspace)，复用 StdioClient 与退出清理；默认 AgentTui 和 --print 保持原分支。preview:tui 不变。
- 新入口将调用者工作区显式传至 Node 的 --workspace，作为 Java 子进程 cwd 与界面工作区；Java 本身仍收到原有 --workspace。旧入口 cwd 未改变。
- 纯启动参数解析抽取为 entry-options，便于验证前端参数不污染 Java argv。

## 授权参考与独立契约

AUTH-SRC-2026-07-29-A，只读研究 groupToolUses.ts 和 BashToolResultMessage.tsx：同模型响应内、支持分组的同类调用才合并；结果依调用 ID 关联，展开内容来自实际结果。这里采用项目自己的 run/ordinal 关联，并增加可选 callId/turn，未复制实现、常量或素材。

experienceV1 由初始化显式协商：

- run.started.requestModel：当前 RuntimeConfiguration 的模型名；前端只能称配置模型，未证明路由后的响应模型身份。
- tool.started.callId/turn/parametersPreview：调用 ID、模型回合、安全白名单活动摘要。不是原始完整参数；未知工具不暴露参数正文。超过 512 UTF-16 单元的调用 ID 省略，ordinal 保留。
- tool.completed/tool.failed.content：管线结果正文的瞬时预览，最多 4096 码点，去除 C0/C1 控制字符并保留换行、回车、制表；contentTruncated 与既有工具语义 truncated 分开。
- contentRedacted：复用已有公开 SecretCandidatePolicy。命中明显密钥赋值、Bearer、私钥、配置端点时隐藏整段正文。此策略是保守启发式，存在误报/漏报，不是全面脱敏保证；一般 URL 不会自动隐藏。
- 结果仅供本地 stdio 消费，不另写日志；不改模型收到的结果、审批或执行语义。未协商客户端不收到以上扩展。

命令审批已有完整 command/shell/workingDirectory；workingDirectory 是工作区相对路径。无需另造命令参数来源。

## 验证

- Node entry-options：4/4 通过，覆盖默认模式、新模式中文空格路径、print、无效参数。首次发现返回对象漏 tuiNext，修复后通过。
- PowerShell TestCodejDevLauncher：66 assertions 通过，包含默认关闭、选择器组合/重复/非法值；测试仅在临时目录安装/卸载假入口，未改用户已安装版本。
- ExperienceToolProjectionTest：新增 4 个 Java 测试，覆盖协商开关、真实 read_file 正文/关联/模型配置、敏感正文隐藏、Unicode 与 JSON 预算；交由问卷子任务统一 Maven 验证；收尾读取 Surefire 报告确认 4/4 通过、0 failures、0 errors、0 skips。
- 完整前端构建、整体视觉、真实 Provider 和默认入口切换不属于本证据已验收范围。共享矩阵与看板由主任务统一维护，不提升能力等级。

## 生产开发入口与既有 Provider 最小问答（2026-09-10）

- 通过现有 Invoke-CodejJavaBuild 执行 Maven package（skipTests）及 dependency:build-classpath；启动缓存 reusable=true、complete=true。没有替换用户已安装入口。
- 配置读取路径核实：新工作树没有 config/provider.local.properties，进程也没有三个 OpenAI 环境值；用户级 .cc-java/providers.v1.json 与 auth/profiles.v1.json 已存在。验证直接复用现有用户级配置，没有复制旧工作树凭证，也没有修改选择。
- 实际调用 StartCodejDev.ps1 --tui-next --workspace <空临时工作区> --timeout 60s --print <要求固定短回复且不使用工具的提示>，退出 0，耗时 7709ms，回复精确匹配预期标记。
- 只写入安全摘要 target/tui-next-provider-smoke.json，未记录 Provider 地址、密钥或原始 stdout/stderr。这个 print 流程没有提供工具计数观测，不能仅凭提示断言已证明工具调用数为零。
- --print 保持既有非交互分支，因此本次证明开发启动器、生产 Java/stdio、既有配置和真实 Provider 的最小连通性；不替代新交互界面的伪终端或物理终端验收。

开发验收启动命令（在用户自己的项目目录执行）：

```powershell
& 'G:\AI Cloud\cc-java-tui-redesign\scripts\StartCodejDev.ps1' --tui-next
```

离线视觉演示继续使用：

```powershell
npm.cmd --prefix 'G:\AI Cloud\cc-java-tui-redesign\cc-java-tui' run preview:tui
```
