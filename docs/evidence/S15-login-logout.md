# S15 登录 / 退出接入：工作树验证与交接

- 日期：2026-09-12；状态：Open（本地交付门槛完成；两次连续标准完整构建及专项复验通过，非Commit-scoped，在线账号与维护者体验验收仍单列）。
- 分支：`feat/login-logout-integration`；工作树：`G:/AI Cloud/cc-java-login-logout`。
- 分叉基线：`feat/tui-redesign` / `422a527b0474f2b97bfd0ccf9db11dc5c917a3bd`。
- Stage / Feature：S15；MODEL-13 L1保持，CLI-05/08/09 L2保持；S15 Exit OPEN。
- 决策：[ADR-099](../adr/ADR-099-s15-login-logout-integration.md)；需求 FR-MODEL-006/007/008/011。
- 没有提交、推送、合并或读取真实凭证；不覆盖另一 TUI 工作树。
- **最新结论**：维护者明确要求不再处理360、继续任务后，按其选择的当前环境连续运行两次标准`./mvnw -q clean verify`，均通过。最新报告汇总212 suites / 1387 tests / 13 skips / 零失败错误；登录脚本、TUI398/398、Pi13/13、六尺寸ConPTY、launcher66、严格Javadoc及看板复验通过。本地G4通过，S15 Exit仍OPEN；不声称防护已恢复、360根因已证实或在线授权已验收。

## 实现边界

1. 新界面 `/login [provider [profile]]`：选择已有服务商/账号，自定义 HTTPS URL/模型，遮蔽输入经独立 Java stdin，或 ENV 名称；普通路径使用 default。`/connect`、`/auth`、`/models` 保留。
2. `/logout`：选择明确账号 → Java prepare → 默认取消的确认 → 原会话一次性 commit。ENV仅删引用，不改环境；不删其他账号、Provider定义或Session，不等于远端revoke。
3. 认证等待前读取generation；提交在持久锁内CAS，迟到登录不能越过删除事务。退出后必须由当前宿主显式验证新代次并激活，不通过list/status或legacy自动恢复。
4. 取消、断连和超时不承诺回滚已提交凭证。helper终止未确认时恢复终端但保留登录槽，不能同时启动第二个helper。
5. 同一长期宿主的下一Run按新selection建route；模型与ContextSummarizer同源，旧Run保持原lease。Java仍是唯一Agent Loop与凭证持久化权威。
6. 可选OpenRouter浏览器组件锁定Pi AI 0.85.1，仅获取API_KEY；不是订阅登录、OAuth refresh、Pi Agent或Pi模型transport。源码启动器固定定位组件；当前发行打包脚本不附带该组件。

## 复跑

前置：JDK21、PowerShell7、Node >=22.19；安装已锁定依赖，不读取用户auth.json：

```powershell
npm.cmd --prefix cc-java-tui ci --ignore-scripts
npm.cmd --prefix cc-java-provider-pi ci --ignore-scripts
pwsh -NoProfile -File scripts/TestLoginLogout.ps1
.\mvnw.cmd clean verify
pwsh -NoProfile -File scripts/TestCodejDevLauncher.ps1
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
```

`TestLoginLogout.ps1`执行定向Java、TypeScript build、完整普通TUI套件（显式classpath使Java跨进程Fixture运行）及Pi桥离线测试，恢复调用者原classpath环境变量；不是完整clean verify、真实在线授权或物理TTY测试。

## 已观察的验证

| 项目 | 当前结果 / 限定 |
| --- | --- |
| 定向Java认证/凭证/协议/路由 | 入口及摘要修正后复跑23 suites，232 tests / 2 skips / 0 failures / 0 errors（230通过）；包括RuntimeStdioCommandHandler、DefaultCliModeRunner、HeadlessRuntimeSession |
| TypeScript build | 通过 |
| TUI完整普通套件，含Java跨进程 | 26 files / 398 tests全部通过；排除需另行启用的real-java-plan-e2e、installed-plan-e2e |
| 真正Java宿主 + 独立Java登录 + 新React/Ink面板 | 临时home/workspace、合成ENV Key；登录→默认模型→退出→同进程重新登录通过；未调用模型/网络，事件与帧不含canary |
| 既有Java→stdio→新界面 | 7/7通过，涵盖Plan、审批、问卷、命令与取消；与认证用例合计8/8 |
| Pi桥 | 13/13离线通过；公开exports、URL/loopback校验、超时/取消/晚到结果、严格帧/输出界限、生产Node入口EOF/取消 |
| Pi production依赖审计 | npm audit --omit=dev：0漏洞（当日结果，不代表长期无漏洞或发布签名验证） |
| Launcher | 66 assertions通过 |
| 认证/Provider应用服务定向Javadoc | JDK javadoc -Xdoclint:all -Werror：0 warning通过 |
| 完整Maven clean verify | 第四次通过：211 suites / 1370 tests / 33 skips / 零失败；第五次再次在WorkspaceSnapshot失败（工具模块194项/17skip/1失败），后续模块未继续。前两次同一探测失败；第三次三个过期Plan Fixture报错，修正后第四次通过。第六次工具模块204项/17skip/1失败，首次诊断为GIT_UNAVAILABLE；随后模块组合捕获CreateProcess error=5。补stdin释放后的全量通过工具模块，但CLI607项/15skip/1error。后续在用户选择的当前环境，两次连续标准clean verify均通过；最新汇总212 suites/1387项/13skip，历史失败不删除 |
| 仓库strict aggregate Javadoc | `-Dmaven.javadoc.failOnWarnings=true javadoc:aggregate`最终两次通过、零warning；此前达到100 warnings上限，67个Java文件补齐契约，最后两类显式保留原public无参构造器后通过，无警告抑制或POM降级 |
| Windows真实ConPTY | 80/100/120列×24/35行，共6/6；遮蔽登录、输入取消不保存、退出取消保留、确认删除、同宿主重登录、宿主exit0和临时目录清理通过；无canary回显、未提交模型Prompt、非物理截图 |
| Dashboard | generate / --check / --self-test全部通过；已把Pi桥源码/lockfile纳入摘要并排除node_modules |

### 回归中修复的问题

- 全量失败调查：工作区探测组合90/90与独立Git探测200次通过，未找到偶发UNKNOWN根因。仅在原断言失败时独立输出安全错误码，不改变分类/断言或以重试判成功。第三、第四次全量该用例通过；第五次再次失败：`Initial snapshot=UNKNOWN; independent diagnostic probe=NOT_A_GIT_REPOSITORY; threadInterrupted=false`。原始失败分类仍未知，第二次诊断不替代第一次结果。Git为2.20.1.windows.1，仅记录版本，不据此推定原因。
- 第三次全量暴露`TaskToolProductionCompositionTest`三个Fixture仍声明`task_get/task_update`为交付证据，与基线ADR-098冲突；隔离同样3错误。保留Task身份/完成/未完成拦截断言，添加真实`read_file`和公开临时文件，未改生产证据Gate；隔离10/10及第四次标准全量通过。

- 独立安全审查指出同名ENV重新登录后的旧probe元数据污染：使用可注入Clock精确插入lease释放→退出→重新登录，先复现无异常的错误写入；新增锁内generation+SecretRef CAS后拒绝旧结果，完整定向脚本重新通过。浏览器入口问题也被独立审查确认，已修复；审查本身不代替这些动态测试。

- Anthropic正常摘要`end_turn`原先被拒绝：新增用例先复现失败，修复后6项摘要测试和完整定向脚本通过；`max_tokens/length/tool_use/tool_calls/refusal/unknown`仍拒绝。没有将Fake结果描述成真实Anthropic在线验证。

- 浏览器启动器误接库模块`bridge.mjs`，修正为实际进程入口`login.mjs`；配置校验回归禁止库模块被宣传为可用入口。新测试初次误用JUnit常量编译失败，修正后完整定向脚本通过；仍不声称真实授权已验证。

- 新capability改变initialize期望，补齐旧Fake精确断言。
- 未装配Provider/Auth服务的嵌入式/Fake宿主不应声称支持authLifecycle，也不应以false谎报模型未配置；现在缺失字段表示Unknown，真实生产空配置仍明确false。
- Java protocol.error可能没有Session字段；认证等待只在当前操作的精确request匹配时接受该错误，拒绝错Session/错request，错误自由文本不展示。
- 真实跨进程回归初次因此超时，修复后8/8和完整380/380重新通过；未把初次失败隐藏或计作通过。

## Windows真实TTY实验（反例已修正，六尺寸复验通过）

- 测试组件：本机已有`@lydell/node-pty@1.2.0-beta.3`，仅显式路径借用作测试工具，不加入产品依赖，不读取其宿主应用配置。Node24.14.0、JDK21、六尺寸ConPTY、临时home/workspace、合成凭证；系统环境白名单不继承宿主凭证/代理/Node或Java注入参数。
- 真实新TUI→Java Console：提示后合成密码回显且卡住；停用Ink readable监听并等待300ms仍复现。仅pause并不是输入隔离。
- 对照：独立Java Console、未曾读取TTY的Node父进程，均正常保存、exit0且无回显；开始读取TTY后再pause的最小Node父进程再次复现。
- Windows共享Console因此在spawn前失败关闭，新TUI已改用已有独立stdin通道和短期字节缓冲。最新bridge定向测试覆盖拒绝发生在终端暂停/spawn前及非法参数时缓冲清零。
- `scripts/TestLoginLogoutPty.mjs`最终六尺寸全部exit0。仅用合成凭证、保存计数及是否回显作断言，不提交模型Prompt。早期新路径曾因测试脚本错误期待退出完成后的普通关闭文案超时；修正为真实完成提示。借用的beta模块在子进程退出后仍持有原生管道，所以仅在宿主exit0及临时目录清理均确认后显式退出测试进程，不以强制退出掩盖测试失败。
- 新字段只接受单行可打印ASCII/16KiB；Escape/Ctrl+C/断连/切操作/切会话/卸载清零；拒绝控制字、多行、超限，重复Enter不重复启动helper。清零字节缓冲不保证V8不可变字符串或OS内存完全擦除。
- 可复跑：在定向脚本之后执行`node scripts/TestLoginLogoutPty.mjs --pty-module <已安装node-pty模块的绝对路径> --size 80x24`；支持80/100/120×24/35。模块路径须显式提供，不自动安装测试工具或读取宿主配置。
- 桌面截图不可用：`orca computer capabilities --json`先报runtime_unavailable，按工具指引open后重试仍被断开；停止重试，不冒称物理截图通过。测试失败后的专属Java/Node进程检查为0。

## 按审核计划收尾的新证据

### 首次Git失败与独立分类缺陷

- `GitReadClient`包内启动/观测接缝与测试专用跨包支撑，现在记录同一次调用，不再追加第二次探测。公开API、Session与stdio协议不变。
- 首次诊断：`GIT_UNAVAILABLE / exitCode=null / stdoutBytes=0 / stderrBytes=0 / interrupted=false`；进一步提取JDK标准启动异常中的数字为`CreateProcess error=5`，目录存在。未保留异常正文、cause、路径或环境。
- Windows进程创建阶段拒绝访问的具体来源尚未知；只读检索未发现tools-local测试修改全局PATH/系统属性或创建git.exe影子程序的证据。不能据此断言是杀软、Git版本或ACL。未修改系统配置、关闭防护、升级Git或添加启动重试。
- 两个独立Fake反例先失败：stdout IOException被当作OUTPUT_LIMIT_EXCEEDED、stderr IOException可成功返回。现在两者均GIT_READ_FAILED；真正超限仍独立分类。11个Git诊断Fake用例覆盖首次失败、无泄漏、数字OS码、启动/读流错误、超限、超时和中断，已通过。这不是CreateProcess error=5的修复证明。

### 定向安全审查与修正

- 实际Ink+未返回helper的Fake证伪login等待阶段Esc不可达。新TUI保持raw输入，只接受Esc取消/Ctrl+C退出，其他输入不进composer；Bridge只有legacy非Windows Console才交接终端，ENV/browser stdin为ignore，stdin仍私有pipe。新增测试先红后绿；迟到成功不再触发activate。
- Java浏览器桥原有ByteArrayOutputStream.reset和帧消费未擦除可变副本。改为固定行缓冲和有界可关闭通道；每帧try/finally擦除，关闭时同锁清除部分行、待发布与排队帧，阻止取消后重新入队。新增4个清零/满队列取消测试及原6个浏览器测试通过；不声称能擦除不可变字符串或OS内存。
- 六尺寸ConPTY复验为正常登录/输入取消/退出确认/重登录；helper等待中Esc/Ctrl+C为实际Ink+Fake helper证据，未冒称浏览器在线或物理等待取消已验收。

### Pi网络与依赖查询

- 原`fetch failed`来自Pi自动重试耗尽；只检查Provider/模型标识及代理配置是否存在，未读取会话正文或认证文件。当前本机代理TCP可达；Pi显式使用EnvHttpProxyAgent，不能因NODE_USE_ENV_PROXY未设就认定没有代理。原模型请求底层cause未取得、未重现，不声称根因已修复；未修改全局网络配置或发起独立模型测试。
- npm audit命令曾在registry TLS建连失败，不能把失败当作零漏洞。随后隔离进程使用Pi HTTP dispatcher、无认证头向公共registry bulk advisory API查询锁文件171个公共包，取得3条匹配：`vitest@4.1.10`与`@vitest/mocker@4.1.10`（moderate，[GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9)），`nanoid@3.3.16`（high，[GHSA-2v37-7h3g-55p8](https://github.com/advisories/GHSA-2v37-7h3g-55p8)）。这是直接公共advisory查询，不冒称npm audit命令已成功。
- 三者在lockfile均dev=true，TUI生产src无这些导入。nanoid路径为vite→postcss，已见postcss调用nanoid(6)，不是公告的size=0自定义生成器。未发现这些已知问题进入本次登录生产路径；不等于完整依赖安全证明。
- 本轮不改依赖。后续开发工具升级需验证vitest/mocker >=4.1.11、nanoid >=3.3.18与现有工具链的兼容；不运行audit fix --force、不向外暴露开发服务器。此前Pi production审计与本次TUI查询不混用。

## 继续诊断：进程资源与原生边界

- 同一临时程序使用4线程、每种100个独立目录，对比git/git.exe/绝对路径，均得到预期NON_REPOSITORY；另外分别加入符号链接创建、故意无效cwd启动、显式GC的控制，均未重现。完整模块临时改绝对Git路径仍复现首次error=5，该临时替换已撤除。
- 按测试集合缩减：数据、进程、相邻Workspace测试等分组各自通过；排除所有Command/RunCommand/Ripgrep进程测试后，GitTools和Snapshot仍可失败。没有证据将其归因于taskkill或PATH查找。只读查询近期AppLocker/CodeIntegrity/Defender阻止事件未匹配，不等于排除所有防护来源；当前诊断进程不在Job中，也不代表所有历史测试进程状态。
- 确认一个独立资源缺口：Git固定只读命令从不使用stdin，却未主动关闭父端管道。新增Fake先红，现于等待前关闭；关闭异常终止子进程、清理输出流并保持GIT_READ_FAILED/UNKNOWN，不暴露异常正文。Git诊断测试由11增至13。
- 对照：加关闭后工具模块通过；暂时撤回关闭，原Snapshot error=5再次出现；恢复关闭后标准clean verify通过工具模块，但CLI `RuntimeStdioCommandHandlerTest.repeatedPlanResumeReprojectsSameDurableReviewWithoutStartingRun`仍因GIT_UNAVAILABLE失败（CLI607项/15skip/1error）。因此stdin缺口已修正，但不能声称它充分解释或解决全部启动失败；CLI底层OS码尚未采集。
- 公开OpenJDK jdk-21.0.11-ga研究确认，CreateProcess前缀来自CreateProcessW返回失败，不是把Git exit128解释为启动错误；未找到可直接套用的已确认JDK快速退出缺陷。参见[ProcessImpl_md.c](https://github.com/openjdk/jdk21u/blob/jdk-21.0.11-ga/src/java.base/windows/native/libjava/ProcessImpl_md.c)、[JDK-8159775](https://bugs.openjdk.org/browse/JDK-8159775)。
- 原生诊断尝试仅针对测试自有JVM，通过Windows自带DbgEng API，未采集环境、命令正文、内存转储或认证数据。握手/互操作实验发生过超时和调试器导致的测试进程退出，均不是产品回归结论。最终系统DbgEng报告拒绝加载其dbghelp（SECURE / error5），未取得可用原生创建轨迹；不能将此直接等同于Git故障根因。没有关闭该安全检查。
- 临时调试握手和可执行路径覆盖均已从仓库测试移除；stdin修正恢复。官方Microsoft.Debugging.Platform.DbgEng 20260319.1511.0包仅下载到临时目录并核对Microsoft有效签名，未安装、未加载其DLL、未作为项目依赖。该包要求Windows SDK许可接受；此为确认前状态，后续授权及结果见下节。

## 经授权的原生诊断结果

维护者已明确同意Windows SDK许可及临时调试。官方签名组件只在临时PowerShell进程内加载，未安装、注册DLL或加入项目依赖。DbgEng仍未形成有效附加会话；后续独立Win32调试接缝通过硬件断点取得以下同调用证据：

| 同调用观测 | 实际值与解释 |
| --- | --- |
| 正常对照 | NtCreateUserProcess=0；CreateProcessW=TRUE；原测试通过 |
| 缺失cwd对照 | CreateProcessW=FALSE / Win32=267，与缺失目录吻合 |
| 完整模块原失败 | NtCreateUserProcess=0；随后CreateProcessW=FALSE / Win32=5；Java首次诊断仍为GIT_UNAVAILABLE |
| 错误转换 | RtlNtStatusToDosError输入0xC000004B（STATUS_THREAD_IS_TERMINATING），随后设置Win32错误5 |
| 返回前状态 | 子进程及初始线程已退出，exitCode=0；未观察到失败调用进入通常的NtResumeThread路径 |
| 父调用清理 | 父线程执行NtTerminateProcess前，子进程已经exit0；清理请求状态为0xC000004B。因此不能把这一清理调用当成最初终止者 |
| Job检查 | 被观察的测试JVM和失败子进程均不在Job中；不推广到其他进程或运行 |

这将故障缩小为创建期间提前终止，而非Git exit128被Java误读，亦不是缺失目录的267错误。但最初终止来源仍未确定，不能宣布根因已修复。

调试仅读取系统调用状态、句柄/进程标识、必要的控制流字段；没有读取凭证、环境、Session正文或制作内存转储。断点和附加会话在实验后撤除，成功轨迹的detach返回TRUE。调试器改变时序，部分配置不再复现，均未算作正式验收。所有临时仓库握手再次移除，stdin修正恢复；定向回归与看板按恢复后的源码复验。

本机存在360tray/ZhuDongFangYu，但存在本身不是归因证据。仅对相关日志位置作元数据查询、在当日弹窗日志中检查git/java名称是否出现，未导出其他日志；未发现匹配。Orca桌面接口恢复后打开官方安全操作中心，界面树为空、截图不可读，未核实防护记录。没有停用防护或添加白名单。下一步需要维护者选择受控环境对照，不能自行突破既定安全设置边界。

## 用户授权的防护暂停对照（2026-09-12 23:26）

- 用户明确报告已暂停360；暂停状态依据用户陈述，助手未读取或改写防护配置。
- 没有更改源码、断言、测试选择、重试策略或权限判定，顺序运行：
  1. `./mvnw -o -q -pl cc-java-tools-local -am test`：通过。
  2. `./mvnw -o -q clean verify`：通过，212 suites / 1387 tests / 13 skips / 0 failures / 0 errors，含此前失败的CLI路径。
- 13个skip分布：RestrictedFileCredentialStoreTest 1、RestrictedFileSecurityContainerBoundaryTest 2、OpenAiProviderSpikeTest 2、S13RealBackendAttackTest 8。真实Provider评测明确opt-in-disabled，未发起在线授权/模型实验。
- 这是一轮使用本地缓存依赖的完整离线对照，不是两次连续的正式验收，也不证明360就是终止者。完成后立即提醒用户恢复防护；恢复状态下的同命令复验待确认。

## 最终本地门槛与交接

用户随后明确要求“不用管360了，你继续”，本批不再等待恢复确认或延伸防护归因。防护恢复状态未知，助手没有修改防护配置；以下结论仅对本次实际环境有效，不承诺恢复其他安全配置后必然不再出现原生启动终止。

- 两次标准命令`./mvnw -q clean verify`背靠背执行，退出码均0；没有失败后自动重试、跳过测试或额外弱化参数。与此前`-o`环境对照分开记录。
- 随后`TestLoginLogout.ps1`通过：TypeScript build、26文件/TUI398 tests、Pi13 tests；Java目标回归通过。最新全部Surefire头部汇总212 suites/1387 tests/13 skips/零失败错误。
- 六尺寸`TestLoginLogoutPty.mjs`再次全部通过；遮蔽输入、取消不保存、取消退出保留、确认退出删除、同宿主重登录及清理均通过。未发模型Prompt，非物理截图证据。
- `TestCodejDevLauncher.ps1`为66 assertions；严格aggregate Javadoc零warning；看板generate/check/self-test和diff check通过。
- 最终差异复核包括新增文件清单、配置未变检查和有限模式检查：未检出私钥正文、凭证URL或临时调试开关。这不是完整形式化安全证明；已确认的安全问题及修复保留在上文。
- 临时下载的SDK包与解压组件已删除，项目不依赖这些调试组件；文本诊断记录保留在临时目录，未混入发布物。

### 交付入口

在`G:/AI Cloud/cc-java-login-logout`运行：

```powershell
pwsh -NoProfile -File scripts/StartCodejDev.ps1 --tui-next
```

使用`/login`打开专用面板、`/logout`进入默认取消的退出确认；不要将API Key粘贴到普通聊天输入。
旧AgentTui仍是默认入口。可选浏览器桥仅在可信配置有效时出现，入口为`cc-java-provider-pi/login.mjs`；不附带到当前发行包。

### 合并评审热点（不自动合并）

- TUI：`experience/auth*`、`runtime-app.tsx`、`runtime.ts`、`runtime-screen.tsx`、`stdio-client.ts`、`protocol.ts`、`app.tsx`，需与另一TUI分支逐项核对。
- Java：凭证generation CAS、lease/fence、logout票据与激活、下一Run及摘要路由、stdio能力协商和浏览器私有帧。
- 脚本/文档：开发启动器、Pi源码包/notice、复现脚本、看板生成器与状态/矩阵。
- 跨模块中文Javadoc补齐属于已记录的文档清理；两处公开空构造器保持原隐式构造器的语义，不是Plan功能扩展。

本地门槛完成不代表S15退出或MODEL-13升级。真实OpenRouter账号授权、双Provider在线BYOK、Node22实机、跨平台信号及发行打包仍按既定第二门槛/差距处理。环境启动终止来源未闭合；不通过重试或把UNKNOWN改为NON_REPOSITORY掩盖。全部变更保持未提交，维护者自行决定评审、提交和合并。

## 人工体验步骤（未执行的在线操作）

在本分支工作树运行 `pwsh scripts/StartCodejDev.ps1 --tui-next --workspace <公开测试目录>`。

1. `/login` → 服务商 → default → 专用遮蔽字段输入（不在聊天框粘贴密钥），或ENV只填变量名称 → 选择默认模型。
2. 已明确准备好自己的测试账号后才发送无敏感内容的模型请求；保存本身不等于在线验证。
3. `/logout` → 选择账号 → 检查provider/profile → 确认；Session保持打开。再次`/login`应可在同宿主启用新凭证。
4. OpenRouter方式仅在可选组件已安装并协商时出现；按独立终端给出的官方URL完成浏览器操作，不粘贴回调Token。没做过这一步不能称真实PKCE成功。

## 已知差距与合并注意

- 没有真实OpenRouter授权/双Provider BYOK在线证据；MODEL-13不能提升L2。Codex client使用授权Unknown，保持禁用；不声称订阅兼容。
- 六尺寸已补ConPTY认证验收，仍不是物理终端截图、浏览器焦点或真实在线授权；独立Java Console有合成凭证对照，未使用真实凭证。
- 浏览器组件只由源码开发启动器自动接线，安装包不携带它；缺组件时API Key/ENV仍可用。
- 全局索引CAS对无关账号并发写也会保守冲突；旧二进制无条件写入不受新API契约约束；fence只作用于本进程，不是跨进程或远端撤销。
- 默认旧AgentTui保留；`/login`兼容其连接入口，新的`/logout`确认面板仅在`--tui-next`，旧入口仍用显式`/auth logout … confirm`。旧TUI带Profile的Console参数形式在Windows失败关闭，可改用无参数`/login`遮蔽面板、ENV或独立CLI。
- TUI的3条当前匹配advisory均在开发依赖，详见上节可达性与升级路径；本批没有升级依赖，不宣称完整安全审计通过。Pi历史production审计为0不能替代本次TUI结论。
- 合并热点：experience的三个runtime文件、stdio-client/protocol、Java stdio handler、HeadlessRuntimeSession装配、三个Plan Fixture兼容修正、WorkspaceSnapshot失败诊断、README/PRD/技术设计/矩阵/看板。认证模块与Pi目录独立，不整包覆盖另一分支。
- 当前改动未提交，不能仅执行merge分支名取得未提交内容；需维护者明确授权提交或自行审阅提交。没有伪造实现Commit或Stage Accepted。
