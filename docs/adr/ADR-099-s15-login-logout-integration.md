# ADR-099：新 TUI 的登录、退出与受控浏览器认证

- Status: Proposed
- Date: 2026-09-12
- Stage / Feature：S15；MODEL-13 L1→L1（整体L2仍需双Provider真实BYOK证据），CLI-05/08/09 L2保持。
- 实施基线：`feat/login-logout-integration`，从`feat/tui-redesign`的`422a527b0474f2b97bfd0ccf9db11dc5c917a3bd`建立独立worktree。用户明确授权独立分支开发；不合并、不推送、不覆盖主目录。

## 场景、来源与边界

| 用户场景 | 来源 / 分类 | 机制与不显示什么 | CodeJ独立契约 / 验证 |
| --- | --- | --- | --- |
| /login选择服务商 | Pi0.85.1 providers文档、AuthInteraction公开类型；Documented/源码Observed；用户明确要求新入口 | 服务商与认证方式分开，不把Token当对话 | 使用experience最终宿主；内置Provider/已有连接/自定义配置，秘密仅走独立Java认证桥；新TUI遮蔽输入只短期借用可清零字节缓冲，不进入React状态 |
| 本机/logout与重新登录 | AUTH-SRC-2026-07-29-A；ADR069§3.1已有受控login/logout/cache研究；Observed职责，不是视觉验收 | 删除凭证、失效缓存与远端revoke不同 | 当前Java宿主prepare/commit确认，fence/drain/delete；重新登录验证新持久代次后启用，不自动fallback |
| 浏览器授权 | OpenRouter官方 https://openrouter.ai/docs/guides/overview/auth/oauth；2026-09-12 Documented；Pi dist/auth/oauth/openrouter.js机制Observed | 第三方应用PKCE S256、localhost回调/手工输入，返回长期Key；不要求client ID | 可选Pi适配，产物保存为既有API_KEY，不声称具有OAuth刷新能力 |
| 模态焦点/终态 | AUTH-SRC-2026-07-29-A既有ADR090–098对照；用户授权本批登录需求 | 模态独占输入，秘密/内部协议不进入历史 | 保留--tui-next布局、草稿和已有Plan/审批/问卷；取消/断连不复活面板 |

参考准确Revision/商业源码复制权仍Unknown；不复制参考表达。此次只复用已记录且未变化的授权研究，不声称运行参考UI或视觉一致。Pi npm0.85.1对应gitHead `d981de1229ef899957bbe968bc8dcda02a21f477`，上游MIT Copyright (c) 2025 Mario Zechner；tarball integrity已研究核对。引入包时补齐第三方声明与锁文件。OpenAI官方认证页面本次返回403，复用Pi Codex client ID的授权条件Unknown；该路径保持不启用，不能因MIT或客户端ID公开就认定允许。

## 决策

1. 新真实入口为`StartCodejDev.ps1 --tui-next`，默认AgentTui保留。新增/login、/logout并保留/connect、/auth和/models兼容；不切换默认入口，不使用离线preview模拟认证成功。
2. 普通登录使用default profile，支持已有Provider及自定义HTTPS compatible地址/模型；高级指定Provider/profile与ENV保持。只展示已接入认证方式。list/status零网络；probe显式请求；保存不自动测试模型。
3. 新TUI API Key改用遮蔽输入和独立Java认证stdin：秘密只在短期字节缓冲中借用，不进入React state、普通Agent stdio、对话、输入历史、argv、错误或遥测；提交/取消/切会话/卸载清零。不声称秘密完全不经过Node。独立CLI仍支持Java Console，ENV只收名称，浏览器仍使用私有桥。允许独立stdin不等于允许普通auth.secret frame，ADR070中的冲突条款以本条限定修正。
4. 新authLifecycleV1显式协商。新增安全的auth.activate、auth.logout.prepare/commit操作；旧端不支持则失败关闭，不降级为未绑定的面板删除。确认票据有界、一次性、时限且绑定宿主会话和展示时的store generation。确认后目标变化应重新确认，不能重新解析默认账号删除。
5. 登录在等待秘密输入之前读取持久generation，saveStore/saveEnv在锁内按该generation CAS。采用现有单调index generation作为保守事务序列：其他账号的并发修改也可能使登录失败，宁可显式重试，不偷换成无条件写。成功delete保留递增index，即使最后profile已删除，旧登录提交重启后也被拒绝。该规则覆盖本版本应用服务；不宣称旧二进制并发无条件写入也服从新契约，不新增无必要schema迁移。
6. fence不能简单清除：成功持久删除后记录删除generation，只有宿主显式重读目标确实存在且generation更新、旧lease已终止，才能建立新的允许代次下界；旧lease或迟到回调不得复活。删除失败或drain失败保持fence。auth.list不负责重新启用。
7. /logout通过当前Java宿主操作，不启动另一CLI进程只删文件。prepare绑定快照，commit在删除事务继续CAS。旧auth.logout确认语法保留即时目标语义，不称为先前面板快照绑定。ENV仅移除本地引用，legacy不改；不删其他账号、Provider定义或Session，不远端revoke。
8. cancel/timeout按提交线性化：提交前取消禁止凭证mutation；提交后不自动撤销，进程丢失结果时显示保存结果待核对，不宣称零副作用。显式激活失败应保留“已保存但未启用”事实。
9. busy/审批/问卷/计划面板期间不切账号、不排队执行认证命令；输入不转发模型。Java仍处理检查之后发生的Run/退出竞态。后端probe及lease晚绑定取消、阻塞close以回归限定。probe结果提交必须同时比较开始时generation与SecretRef，不能只比较ENV名称；释放lease后若退出并用同名ENV重新登录，旧结果必须失败关闭。
10. 可选Pi只负责OpenRouter授权交互，不启动Pi Agent/Session、扩展、用户auth.json、自动模型发现或Telemetry。Java是持久化写入方，SpringAI仍负责该API Key的模型调用；Codex/Copilot/Anthropic订阅和通用OAuth刷新不因本批自动可用。

## Windows终端实验导致的安全修正

2026-09-12，真实ConPTY运行新TUI时，Node曾开始读取TTY后，`stdin.pause()`不能可靠转移Windows控制台输入所有权：Java Console提示后合成密码回显且卡住。去掉Ink readable监听并等待300ms仍复现。对照实验中，独立Java Console、从未开始读取TTY的Node父进程均不回显并正常保存。仅离线Ink/Fake bridge测试无法发现这个问题。

因此Windows共享终端的`store` Console路径在spawn/终端暂停前失败关闭，不采用延时或重试掩盖；新TUI统一复用已有受控`stdin`字节通道和遮蔽输入。此为安全性必要偏差，不增加Node持久credential store。浏览器流程不从终端读取密码，不受此禁用影响；CLI独立终端Console保留。已用真实ConPTY在80/100/120列×24/35行六种尺寸验证遮蔽、输入取消不保存、退出取消保留、确认删除和同宿主重新登录；物理终端截图与在线授权仍另行记录。

新TUI字段只接受单行可打印ASCII，最多16KiB；非ASCII可使用ENV或独立CLI。短期字节缓冲清零不等于能强制擦除Ink/V8产生的所有不可变字符串或OS内存。旧TUI带Profile的共享Console参数形式在Windows也会被拒绝，须用不带参数的`/login`遮蔽面板、ENV或独立CLI；不将不安全行为作为兼容承诺。

严格aggregate Javadoc的文档债同时清理：67个生产Java文件补充契约；其中两类显式声明原有public无参构造器，仅为附着Javadoc，不改变构造语义或提升Plan/Task能力。严格生成零warning；完整构建第五次仍被历史WorkspaceSnapshot偶发失败阻塞，不能以文档检查替代完整回归。

## 收尾：首次Git失败观测

S15收尾保持TOOL-07 L2与MODEL-13 L1，不新增Git能力；沿用S03固定只读Git契约。本次诊断为项目自有可证伪实验，不新增授权源机制或复制参考表达。Git适配器增加包内进程启动/诊断接缝，公共API和Session/stdio协议不变。测试捕获同一次调用的类型化失败、退出码、字节数、读取异常/截断和中断状态，不记录stderr、路径、环境值。原有NON_REPOSITORY/UNKNOWN断言保持；第二次探测不能解释或替代首次失败。先以Fake进程/流证伪分类与取消，再用真实调用定位原因，不以重试或跳过用例收口。

### 首次观测与安全审查结果（2026-09-12）

首次真实失败已定位到ProcessBuilder.start：GIT_UNAVAILABLE，随后同一调用诊断捕获CreateProcess error=5、工作目录存在、无进程退出码/输出、线程未中断。此为Windows进程创建阶段拒绝访问；尚不能归因于杀软、Git版本、ACL或全局配置。未改系统防护、未升级Git、未加重试或降低NON_REPOSITORY断言。完整构建第六次及工具模块组合仍失败，连续两次完整验收未满足。

另外，Fake证伪stdout读取异常被当作超限、stderr读取异常可返回成功；两种读取异常现统一GIT_READ_FAILED并保持UNKNOWN，实际超限仍独立分类。这不是上述CreateProcess失败的根因修复。

认证审查又证伪等待helper时Ink停用输入导致Esc取消不可达。stdin/ENV/browser现在保持TUI的raw输入所有权，仅legacy非Windows Console交接；等待期间只响应Esc/Ctrl+C，其余输入丢弃。Java浏览器桥改用固定可擦除缓冲及可关闭的有界帧通道：消费finally擦除帧，退出前关闭发布入口并擦除部分行、待发布与排队副本，取消后不能重新入队；不承诺清除不可变JSON字符串或OS内存。公共协议与凭证存储权威不变。

### 继续诊断中的资源修正

Git固定只读命令不接受stdin数据，应在启动后立即关闭父端管道，不能将释放交给GC。
关闭异常终止子进程并清理输出流，保持GIT_READ_FAILED/UNKNOWN；新增两个Fake覆盖正常释放和关闭失败。
反向撤回实验再次出现原error=5，但恢复后全量在CLI计划恢复测试仍出现GIT_UNAVAILABLE，
故不把该资源修正描述为所有启动失败的充分根因修复。没有增加重试、修改断言或更改系统防护。
经维护者授权后取得原生轨迹：NtCreateUserProcess成功，但返回Win32调用前进程已退出，
STATUS_THREAD_IS_TERMINATING（0xC000004B）映射为error5。最初终止来源仍未确定；
没有因本机存在360就认定其为根因；临时握手/路径覆盖已移除。
维护者随后明确报告暂停360，未改源码的完整离线构建通过1387项/13skip，已立即提醒恢复。
暂停由维护者操作，助手未修改防护配置；当时不以单次通过关闭根因或Gate。
随后维护者明确要求不再处理360、继续任务；当前环境连续两次标准clean verify及专项复验通过，
本地交付门槛完成。没有声称防护已恢复、360根因已证实或其他环境必然通过；环境风险和历史失败继续保留。
详情与失败记录见登录证据。

## 实施和验收

先做失败Fake：提交CAS/跨store实例迟到登录、确认后替换、同进程logout→login→模型调用、late cancellation、drain deadline、结果未知。再做Java codec/client严格协议、experience面板实际输入与默认入口兼容。OpenRouter授权单独Fake/受控回调测试，不从现有账号读取秘密。

80/100/120列×24/35行按本分支视觉基线检查；输出区分渲染帧、PTY和物理终端。真实Provider只在授权合成场景下验证，未验证条目保留。不以测试数量提升MODEL-13；最终证据、Gap和看板随实现补齐。
