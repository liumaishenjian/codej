# ADR-128：S15 真实入口验收边界

- 状态：Accepted（证据边界，不改变生产能力）
- 日期：2026-10-06
- Stage：S15
- Feature：CLI-01/03/04/05/09、TOOL-10/11、PLAN-01、MODEL-13
- 参考基线：`AUTH-SRC-2026-07-29-A`
- 参考入口：`src/screens/REPL.tsx`、`src/components/PromptInput/`、`src/utils/groupToolUses.ts`、`src/components/messages/GroupedToolUseContent.tsx`、`src/components/permissions/BashPermissionRequest/`、`src/components/permissions/AskUserQuestionPermissionRequest/`、`src/commands/plan/plan.tsx`、`src/tools/ExitPlanModeTool/`

## 背景

S15 的实现已经有 Java Fake、stdio 和 TUI 自动回归，但任务 7 要求把同一条闭环放到真实入口，并区分初始化、运行失败、真实 Provider 成功和物理视觉证据。一次启动失败不能被解释为业务失败；一次模型错误也不能被解释为成功交付。

## 观察与分类

| 场景 | 证据分类 | 观察 | 结论 |
| --- | --- | --- | --- |
| TUI 构建与标准回归 | Observed | 30 个测试文件，449 passed / 7 skipped | 当前代码回归通过 |
| Java→stdio→Ink 运行时 classpath | Observed | 根目录 JUnit-only classpath 缺少 Jackson；改用 `cc-java-cli/target/test-dependency-classpath.txt` 后，1/6 初始化通过，5/6 在临时 auth store ACL 处返回 `EPERM` | 启动前环境错误，不计为产品失败 |
| 隔离 workspace/home | Observed | 初始化、`run.started`、`model.turn.started`、安全 `run.failed=model_retry_exhausted` 可见；无工具调用或文件副作用 | 跨进程失败投影安全，Provider 成功仍未知 |
| 确定性 Java→stdio→Ink Fixture | Observed | 修正 Fixture 父目录与 `user.name` 后，计划、验证恢复、Task Board、审批会话 4/6 通过；XLSX 命令和超时场景进入真实 `run_command` 后，Java `ProcessBuilder` 启动固定 Windows PowerShell 返回 `CreateProcess error=5` | 真实工具链已到达 Shell 边界；当前沙箱不提供这两项通过证据 |
| Session Store 位置 | Observed | workspace 直接作为 Session Store 外层时拒绝启动，并要求 Store 位于 workspace 外 | 保留安全契约，不通过改路径规则消除错误 |
| 在线 Provider 成功 | Unknown | 当前沙箱没有可授权的成功请求 | 不得声称真实 Provider 通过 |
| 参考产品物理终端视觉 | Unknown | 只完成源码机制对照，未在参考产品上运行同一场景截图 | 不得声称视觉一致 |

### OAuth 回调页与本机回执边界

源码对照确认 OpenAI Codex 的本地回调处理器会先返回 `Authentication successful` HTML，
再继续授权码交换、JWT 账户标识解析、凭证事务和进程收尾。该页面只证明某个监听器
接受了合法 `code/state`，不证明当前 codej helper 已取得 `auth.stored` 回执；固定端口
被其他进程占用时，页面甚至可能来自另一个监听器。TUI 因此继续以 Java/Node 回执、EOF、
退出码和关闭完成作为唯一成功条件，并将失败分类翻译成不含凭证、路径或远端原文的摘要。

## 决策

1. 真实入口验收必须单独记录启动、运行、工具、终态、产物和下一轮；协议错误、ACL 错误和 Provider 失败分别归类。
2. 继续使用隔离 workspace 与隔离 user home 进行可重复的本地失败探针；不读取或向未知外部端点发送凭证以制造“成功”证据。
3. 历史上在具备临时目录权限的环境完成的真实计划 6/6、Java→Ink 7/7 继续作为历史证据，但不覆盖本次环境的未知项。
4. 在在线 Provider、Windows PTY 全场景和参考物理视觉证据补齐前，S15 Exit 保持 OPEN，Capability 等级不提升。

确定性 Fixture 的测试启动器可以把 ACL 与当前 OS 账户对齐，但不能也不应绕过 Java 子进程对固定 Shell 的访问控制；`CreateProcess error=5` 是当前环境边界，生产 Shell 选择与最小环境保持不变。

当前运行时自带的 PowerShell 7 可由独立 Java 探针启动，但生产解析器不从任意 `PATH` 或用户可变环境选择可执行文件；因此不能把该探针当作生产 `run_command` 已通过，也不为测试临时放宽 Shell 信任边界。

## 独立实现与偏差

本项目只采用参考源码可观察的职责边界：稳定消息进入终端历史、工具调用与结果按身份关联、审批/问卷负责决定、计划审核与执行分离。错误摘要、Session Store 外置约束、classpath 检查和本 ADR 的证据分类是本项目独立契约，不复制参考源码的文案、私有类型或实现布局。

## 验收与后续

- 已验证：TUI build、标准回归、看板 generate/check/self-test、`git diff --check`。
- 待验证：成功真实 Provider 的普通问答、搜索/读取、审批、问卷、`/plan` 执行失败和下一轮；Windows 物理 PTY；参考 UI 同场景视觉对照；跨平台安装。
- 对应证据：`docs/evidence/S15-tui-next-core.md` 的“2026-10-06：真实入口复验边界”与 `docs/plans/tui-next-refactor-roadmap.md` 的“2026-10-06 复验边界”。
