# ADR-026：S02 类型化 CLI Override 与 Runtime 墙钟限制

- Status: Accepted
- Date: 2026-07-29；Amended: 2026-08-26
- Stage: S02 Model + Streaming CLI
- Feature IDs: `CFG-01`、`CTX-01`、`LOOP-08`、`CLI-06`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Classification: CLI 参数行为为 `Documented`；本项目 Runtime 设计为 `Inferred`；
  Fake/真实 Provider 结果为 `Observed`

## 背景

Java Print 已能调用真实模型，但 Workspace、模型和运行时限仍只能来自隐含默认值。
此前 `TIME_LIMIT_REACHED` 只存在于领域枚举，Runtime 从未真正产生它；如果只在 CLI
外层等待并杀进程，会丢失 Session 唯一终态，也无法证明取消已经传播到模型订阅。

## 决策

1. Java Headless 增加 `--workspace <path>`、`--model <name>` 和
   `--timeout <duration>`，同时适用于 `--print` 与 `--stdio`。
2. Duration 接受整数 `ms`、`s`、`m` 或 ISO-8601，范围固定为 10ms～30m，
   默认 30m；显式 `--timeout` 始终是硬覆盖，参数解析失败返回用法错误 2。
3. Workspace 必须解析为可访问的真实目录。错误诊断不回显路径；S02 没有文件 Tool，
   本决定不替代 S03 的 WorkspaceGuard、Symlink/Junction 和敏感路径规则。
4. 模型名覆盖经过与配置文件相同的长度和控制字符校验。API Key、Base URL 不提供
   CLI 参数，继续来自 Git 忽略配置或环境变量。
5. Workspace、最终模型名和 Timeout 写入不可变 Session Metadata，使 Fake Model、
   Lifecycle Event 和后续诊断能够追踪实际配置。
6. `AgentLimits` 增加正数 `maxDuration`。`AgentRuntime` 为每个 Run 启动一个虚拟
   Deadline 线程，到期后通过现有 `CancellationToken` 传播，并产生
   `TIME_LIMIT_REACHED`；用户取消与超时竞态时，首次成功设置原因的一方获胜。
7. Runtime 在取消后拒绝发布迟到的 Model Text Delta。Adapter 仍负责释放实际
   Provider 订阅，Runtime 负责唯一终态。
8. Print 超时写固定 stderr `cc-java: run timed out`，退出码为 1；用户取消仍为 130。

## 可证伪验证

- Picocli Fake 验证三项 Override、Duration 语法/范围和无效模型；
- Fake Streaming Gateway 等待取消，验证 50ms 后得到 `TIME_LIMIT_REACHED`；
- Fake 在观察取消后故意发布迟到 Delta，Runtime 必须抑制；
- SessionStarted Event 必须包含规范 Workspace、模型和 ISO Duration；
- 不存在的 Workspace 必须在读取 Provider 配置前返回 2；
- 真实 OpenAI-compatible Provider 使用 `--timeout 10ms`，必须快速退出、无文本续写，
  stderr 只有固定超时分类，退出码为 1。

## 结果与差距

上述测试与真实负例已通过，`CFG-01` 和 `CTX-01` 达到 L2。`LOOP-08`、`CLI-06`
继续保持 S02 L1：模型流 Deadline 已建立，但 TTY 中第一次 Ctrl+C 保持 Session、
S04 Tool/子进程树传播和跨平台长期稳定性尚未完成。

本 ADR 不证明限流重试、不完整流、输出长度恢复或 Windows TTY 全部负例，也不支持
S02 Stage Exit。

## 2026-08-26 修订：交互式复杂 Run 的默认值

用户真实批准 Plan 执行证明，原默认 5m 会把首次命令失败后的读取、修补、再次执行与验证
共同挤入同一个绝对 deadline；第二次 `run_command` 即使自身 timeout 尚未到期，也可能先被
整个 Run 取消并最终得到 `TIME_LIMIT_REACHED`。这不是命令或交付物本身的失败语义。

因此默认值提升到既有上限 30m，不改变 10ms～30m 的安全边界、Deadline 线程、取消传播、
唯一终态或显式覆盖语义，也不引入自动重放、动态 lease 或无限 Run。CLI Fake 固定默认/显式值，
真实 Task List E2E 另外验证成功的长耗时命令与命令自身 timeout 后的 recovery 一致性。
