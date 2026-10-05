# CodeJ Pi Provider 组件

S15 / MODEL-13 L1保持：`login.mjs`是ADR-099的OpenRouter兼容桥；`worker.mjs`是ADR-100四条Pi路由的开发工作树入口。
本组件为独立`.mjs`包，Java适配另在`cc-java-model-pi`与CLI中。共享认证、四路 Run 路由、摘要映射及 CLI/TUI 接入已有受控离线证据，当前仍保持 MODEL-13 L1；真实 OAuth、Node 22、物理终端和完整工具循环尚未验收。

```text
npm ci --ignore-scripts
npm test
```

要求原生 Node >=22.19.0；本次实际 npm install --ignore-scripts 与 node:test 在 Node
24.14.0 下验证；旧桥历史13/13，本批全组件238/238离线通过。历史 npm audit 93 packages、0 vulnerabilities不代表本批重新审计。
完整工程的 Java/TUI 回归见[交付证据](../docs/evidence/S15-login-logout.md)；
真实浏览器登录与 Node22 实机仍未验证。Pi 精确版本、tarball integrity
由 package-lock.json 固定，完整 MIT 见 `../docs/third-party-notices/pi-ai-0.85.1.md`。

## 新Worker（ADR-100）

固定入口`worker.mjs`只用于Java拥有的私有管道，拒绝TTY与附加argv，启动核对实际Pi包0.85.1。
当前操作为`catalog`、`auth.login`和单回合`model`，不创建Pi Agent或执行工具，不隐式切回Spring AI。
- `model`固定公开Models.streamSimple、SSE及SDK零重试。Java Gateway/映射/真实进程91项通过，含合成凭据loopback连续两回合续接及429；这不是官方端点、账号或完整CLI工具循环验收。
- 结果只有在终态、实际EOF/退出和资源清理确认后才成立；Java继续决定是否重试。模型单帧1MiB、输入32MiB、输出16MiB，认证仍使用更小的独立限制。
- 三家API Key用公开Models.login录入，通过Java Credential RPC持久ACK后只返回`stored`，不声明账号有效或自动激活路由。
- Codex只选browser，限制官方授权URL，支持绑定提示的manual_code和回调取消；隐藏SDK原文、info/progress与设备登录。
- Frame/RPC及提示协议见[ADR-100](../docs/adr/ADR-100-s15-pi-provider-runtime.md)。认证单帧32KiB；Java的startAuthentication将stdin/stdout/stderr合计限制为128KiB。
- 生产Worker三家合成Key、取消与CAS冲突已通过真实Java/Node五项测试；Codex仍是Fake交互/刷新证据，不是在线OAuth或物理界面验收。
- 新批次明细见[分阶段证据](../docs/evidence/S15-pi-provider-runtime.md)。秘密不进入公开stdio、Session、argv或日志，不保证不可变字符串物理擦除。

## OpenRouter兼容入口与私有协议

Java `OpenRouterBrowserLogin(Path nodeExecutable, Path bridgeEntrypoint)` 的第二参数指向
本目录 `login.mjs`。父目录固定 cwd；路径必须来自可信启动配置，不接受模型参数。
Windows 使用原生 node.exe，不能把 npm.cmd/node.cmd 当可执行文件。此桥不是 sandbox。

Java 私有 stdin 只发送三个命令（每行一个对象，不接受附加字段）：

1. `{"type":"start"}` → `{"type":"ready"}`。
2. `{"type":"login"}` → 零或多个 `{"type":"auth_url","url":"..."}`，然后唯一终态。
3. `{"type":"cancel"}` → `{"type":"cancelled"}`，无 late success。

成功终态 `{"type":"success","key":"..."}` **仅允许进入 Java 私有 pipe**，不能接到普通
stdio、日志、事件或 TUI。失败仅固定 `error`/`code` 白名单，不传递上游错误。
双方单行含 LF <=32KiB，总输出/输入 <=128KiB；Java stderr 直接 DISCARD、不继承终端。
URL 仅 HTTPS openrouter.ai/auth，固定 PKCE S256 参数且 callback 为 127.0.0.1 随机路径/端口。

Java 方法 `SecretMaterial login(CancellationToken, Consumer<String>)` 返回长期 API key；
消费者只收到校验 URL，必须快速非阻塞。调用方负责 SecretMaterial.close，并由应用服务
使用登录前 generation CAS 保存。此类不自行持久化，不自动执行浏览器 Shell。
测试可用 `(Path, Path, Duration, LongSupplier)` 注入最长五分钟期限和单调时钟；同 package
另有 ProcessLauncher 缝隙，Java test 使用内存 Fake Process，不运行 Node 或真实 API。

## OpenRouter兼容路径生命周期和来源边界

- 只从公开 `@earendil-works/pi-ai/providers/openrouter` 创建 Provider，调用其公开
  `auth.oauth.login/toAuth`。没有内部 dist import、OAuth 实现复制、Codex 或其他 Provider 路由。
- 不创建 Pi Agent/Models 用户配置、auth.json、extensions 或模型发现；不注入 telemetry
  context/exporter。上游 telemetry 是显式 context 合约，本路径不启动 exporter；同时设置
  DO_NOT_TRACK/OTEL_SDK_DISABLED 防御性环境标记，不把它们当上游保证。
- Java 与入口双重清环境，仅系统运行时路径/临时目录与固定 callback、禁遥测标记保留；
  Node_OPTIONS、其他 Provider keys、代理配置、Pi 配置入口均不继承。
- Node cancel、stdin EOF、timeout 会 abort Provider；终态后安排100ms强制退出计时器以关闭残留socket，
  不承诺事件循环拥塞时的硬实时上界。Java finally/shutdown hook 销毁helper/已观测子孙并关闭管道；
  Linux SIGTERM实际场景尚未实测，不能保证SIGKILL、崩溃或恶意并发派生逃逸。Windows宿主可额外taskkill整树。
- 没有手工粘贴 code/回调、不自动打开浏览器；URL 可由用户手工打开。无真实 OAuth、账号
  权益、模型调用或浏览器认证的完整 CLI/TUI E2E 验证。上游回调 HTML 由 Pi 原样管理，本项目仅保证自身
  JSONL/Java异常/日志不回显上游异常，不声称修改了上游浏览器错误页。
- Java/SpringAI 仍是秘密保存与模型请求方；上游内部 OAuth credential 仅临时传给 toAuth，
  存储语义是长期 API_KEY，不宣称支持 token refresh。
