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

## 2026-08-26 修订：Print 默认值与交互 absence

用户真实批准 Plan 执行先证明 5m 总 deadline 会错误取消合法长任务，随后又证伪“提升到 30m 即可”——
这只延后同一语义缺陷。最终修正把总 Run deadline 建模为 optional：`--print` 与显式 API/SDK 请求继续
把 `--timeout` 作为 hard deadline，默认值统一为 30m；普通 Interactive、Plan、approved-plan 不装配
总 Run deadline。只有 deadline present 时 Core 才创建 Deadline 线程并产生 `TIME_LIMIT_REACHED`。

Provider 单请求 timeout、Tool 单次 timeout、用户取消、Context/Token/输出上限和重复失败熔断继续独立
生效。launcher、开发 launcher、Spike 和 Java CLI 的 Print 默认统一为 30m，用户显式值原样保留。
