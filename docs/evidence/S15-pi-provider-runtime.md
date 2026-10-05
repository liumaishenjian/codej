# Pi 三家整体接入：分阶段证据

- Status: Open
- Date: 2026-09-13
- Workspace: `G:/AI Cloud/cc-java-login-logout`；branch `feat/login-logout-integration`；base `422a527b0474f2b97bfd0ccf9db11dc5c917a3bd`。
- Stage: S15；MODEL-13 L1及关联Feature原等级保持；S15 Exit OPEN。
- 决策与修改前对照表：[ADR-100](../adr/ADR-100-s15-pi-provider-runtime.md)。
- 第一交付固定称“Pi三家整体接入的离线候选”，第二交付才验收真实账号/费用/模型。
- 不读取真实凭证、不更改安全软件/全局代理/TLS、不提交或合并。

## 冻结范围

三个品牌四条Pi路由：OpenAI API (`openai`)、ChatGPT/Codex (`openai-codex`)、DeepSeek (`deepseek`)、通义国内Token Plan (`qwen-token-plan-cn`)。Java仍是运行循环、凭证持久化、工具权限、Session与重试权威。现有Spring AI和旧身份显式兼容，不作为失败fallback。普通百炼、其他新增服务商、Pi Agent和真实在线授权不属于第一交付。

## 当前批次

| 批次 | 实现 / 验证状态 |
| --- | --- |
| A 目录、协议、失败Fake | 本批基础通过：Node55；Java协议52+宿主42+真实Node目录1，合计95且零skip；严格模块Javadoc通过。仅目录/传输，不是认证或模型闭环 |
| B Java凭证事务与Worker | 共享服务18项已复验（含三路真实Worker合成Key登录）；专用CLI/TUI认证Bridge仍未接入 |
| C OpenAI API工具闭环 | Worker/Gateway与Root/child显式装配已有定向证据；Fake模型驱动真实child read_file Pipeline通过。完整Java+Node+Pi编码闭环仍待验收 |
| D Codex OAuth、刷新、续接 | Domain/JSONL续接已实现并复验；Codex刷新仅Fake回调，实际OAuth与跨层模型续接未验收 |
| E DeepSeek、通义 | 两条生产路由的真实Node/SDK loopback已通过；完整编码场景仍待验收 |
| F TUI/CLI/Session/摘要/子Agent/发行 | Root/摘要/子模型来源部分已接入；统一认证CLI/TUI、发行与完整入口仍未完成 |
| G 完整离线验收 | 未执行；不能复用ADR099历史通过代替 |
| 第二交付真实账号 | 未授权执行/未验证 |

以下各增量保留发生时的切片结果；最新状态以“显式作用域集成增量”及当前批次表为准，历史“待接线”不代表最新代码状态。

## 本次增量复验（不替代整体验收）

- 主任务复验Java176/176，零失败/错误/跳过：旧租约8、新Pi租约3、Pi存储55、运行配置10、固定目录39、Domain续接13、Session续接48。
- 租约以完整backend/authMethod身份隔离，版本使用LegacyGeneration/PiAuthEpoch标签。实际删除事务返回水位，不使用之后可能已推进的snapshot；清理失败仍保留fence。
- 固定0.85.1目录来自公开describeCatalog投影：3品牌、4路由、68模型；这不是账户权益或全部模型已验收声明。
- Node模型模块29/29通过。主任务先复现合法JSON Schema的constructor/__proto__/prototype属性被错误拒绝，再改为自有数据属性复制；加强回归确认输入Schema和输出工具参数完整、对象原型未被污染。
- 另先复现工具参数仅键序变化却丢弃同源签名，改为JSON结构相等判断；文本、数组顺序及所有值仍严格匹配，跨路由或可见内容变化仍剥离隐藏数据。扩展既有续接测试后Node模块仍为29/29。
- 公开SDK loopback覆盖OpenAI Responses、DeepSeek、通义文本/交错多工具及单HTTP attempt；Codex刷新仍为Fake回调，不是实际OAuth交换。
- 严格`package javadoc:javadoc`通过，未压制warning。日志：`pi-shared-foundation.log`、`pi-shared-javadoc.log`（本机临时目录）。
- 再复现兼容route吞掉provider.close失败、以及Pi标签误入同名legacy路由两项缺陷。兼容工厂现已在读秘密前拒绝Pi标签，并将关闭失败交由租约保留未排空状态。对应route7、身份3、租约11，合计21项通过；这是增量组合，不与前述176机械累加。
- ProviderSelectionSnapshot增加backend/authMethod，旧三参数构造保持spring-ai/API_KEY兼容；持久默认选择和Pi模型路由尚待接线。
- Worker模型、Java Gateway及共享服务已继续补齐（见下节）；Run路由/摘要组合、两套TUI/CLI及完整离线入口验收仍未完成；G3/G4/Exit保持OPEN。

## 共享服务与模型网关增量

- 主任务组合Java152/152、零失败/错误/跳过：Gateway86、消息映射3、真实Node进程2、共享认证服务18及相关旧认证/租约/路由61中的其他43。Gateway进程成功用例又扩展到同实例两回合续接，单独91/91重跑通过。
- Node完整`npm test`238/238、零跳过，由主任务重跑，不仅引用子任务报告。
- 先复现共享服务释放登录槽后外部关闭仍能返回成功；最终回执现在检查宿主关闭状态。反射仅用于测试精确调度点，已发布材料不虚构回滚。
- 先复现资源关闭失败后仍允许同身份新租约；现在立即fence该身份，既有其他租约保持原生命周期，显式注销仍取消全部。
- 生产Connection没有null EOF：Gateway适配层使用既有awaitExit确认真实EOF、退出和IO清理后才返回EOF，未修改Connection契约、未把CLOSED异常当成功。
- Java→真实Node→公开Pi SDK loopback成功及429已通过，每回合一条HTTP请求；凭据Port为合成Fixture，尚不是CLI Store与ModelGateway联合模型证据。成功用例连续两回合携带续接，429保留typed retry与有界RetryAfter，但Gateway自身不重试。
- 先复现Usage小数经double舍入/下溢被误认成整数；改为整数节点与精确long范围校验，两条回归通过。
- 日志：`pi-shared-model-verified.log`、`pi-java-gateway-loopback.log`、`pi-worker-full-verified.log`、`pi-shared-model-javadoc.log`（本机临时目录）。这些仍不是完整clean verify、物理TUI或在线证据。

## Run路由增量（仍非整体入口验收）

- `PiSelectedProviderRouteFactoryTest`10项及旧路由/登录生命周期共29项通过。显式Pi分派、固定epoch、逐Run网关和逐回合RPC已接入；不隐式访问legacy。
- `PiRouteProcessTest`新增2项通过：DeepSeek与中国区Qwen Token Plan，生产Selected路由/PiStore/RPC/Java Gateway/真实Node/公开SDK，两回合保留工具调用/结果和续接；ENV临时导出逐回合擦除，结束后租约为0，旧存储未创建。HTTP只发往受信Fixture的127.0.0.1。
- 工具结果是独立Fixture提供，并未执行AgentRuntime/Tool Pipeline；不能称为编码闭环、真实账号或官方端点验证。首跑Fixture因未创建legacy测试home被安全规则正确拒绝，补建临时home后两项通过，未放宽安全规则。
- 源码复核发现SpringAi摘要器直接依赖ChatModel/Reactor，不能复用为Pi摘要。ADR-100已固定同一Pi实例的独立摘要适配契约，后续验证见下节；持久选择及Headless root/child组合仍在继续实现/复核。
- 日志：`pi-route-composition-tests.log`、`pi-route-process-tests.log`；数字与旧组合存在重叠，不累计声称全套通过。

## 同源摘要适配增量

- 主任务复核`PiModelGateway`与`PiSummaryMapper`，模型和摘要共用操作槽、固定身份、凭据工厂及清理状态机；将子任务用观察者身份判定摘要的实现改为显式私有模式参数，观察者不承担控制职责。
- 组合7套138/138通过、零失败/错误/跳过：摘要28、原Gateway86、映射3、Gateway真实进程2、Pi路由10、旧路由7、生产路由进程2。数字与此前证据重叠，不累计。
- 两项生产路由loopback又扩展到“工具调用→Fixture结果→最终文本→同源C3摘要”：第三次HTTP仍走原ENV引用及凭据，预算64实际进入公开SDK请求，零工具；来源revision/IDs保持。结束后三次临时导出均擦除、租约为0。没有运行Core Adoption Gate、真实Tool Pipeline或官方端点。
- model-pi及其上游模块的严格Javadoc通过；尚未运行最新全工程Javadoc/clean verify。
- 日志：`pi-summary-route-verified.log`、`pi-summary-javadoc.log`。这证明适配器和路由组合，不证明Headless root/child、CLI/TUI已经使用它。

## 持久选择增量

- 主任务复核DefaultSelection两字段legacy/五字段Pi的严格读写、backend隔离、Service先分派后读凭据与Definition generation CAS。
- 7套66/66通过、零跳过：新格式6、新服务8、旧DefinitionStore7、旧认证服务10、Pi认证18及两种路由17；与此前数字重叠，不累计。
- CLI及其上游模块严格Javadoc通过。日志`pi-selection-review.log`、`pi-selection-javadoc.log`。
- 该服务入口尚未由CLI/TUI公开流程调用；源码已接线不代表界面可用。当时作用域回归尚未修复；后续状态见下节。

## 作用域原失败与显式集成

`SelectedRunScopeOwnershipTest.reusedWorkerCanOpenItsOwnRunAfterCreatorRunEnds`：
在创建者Run内预热单worker，不让worker调用模型；创建者Run结束后让同worker打开独立Run。
预期独立Run可执行，实际报已有Run route，1 test/1 error，日志`pi-scope-inheritance-repro.log`。
根因是`InheritableThreadLocal<PendingRun>`把旧作用域留在复用线程中。原测试保持不改，现已通过。
旧跨线程双Run测试仅将调用入口改为`scope.binding().gateway()`，精确路由/隔离断言未削弱；新增负例拒绝worker调用同步facade。

源码核对曾发现child共享启动gateway、模型名仅metadata、Context noop及compact缺scope；本批通过显式端口和入队来源完成相应装配，以下按实际证据限定结论。

### 显式作用域集成增量

- `RunModelBinding/CapturedRunSource.forModel`在提交前固定选择与认证版本；`RunModelSourceRegistry`按真实Session/Run登记，仅保留冻结来源和空child模板，不持父活动gateway。来源声明校验不启动Node或取得子租约。
- `PreparedChildRuntime`在Supervisor入队前捕获；实际ToolInvocation传可信父身份，worker不重查“当前模型”。Root结束后可打开独立child，重登改变epoch则拒绝，不借用新账号或Root认证互斥门。
- Root普通、计划、批准执行完整重绑runtime/pipeline/reviewer，ActiveRun引用实际实例；USER_PROMPT前联结启动取消，真实initializer登记来源，finally解除。声明`providesRunBindings=true`却缺binding时拒绝，不回退；旧静态Fake保持明确兼容。
- Root共享已安装Projection及pending容器，child Context/观察者独立。compact使用canonical消息、操作局部Guard和`min(options.timeout,300秒)`墙钟取消，保留stale/revision Gate，不伪造RunId。默认选择文件锁等待现在也接收同一启动token。
- 先通过显式端口43项；随后主任务统一重跑**20 suites / 215 tests / 1 skip，零失败/错误**。包括原复现1、显式端口17、Root真实Runtime/Pipeline Fake15、child组合4、Core prepared/startup18、Context绑定/局部摘要17及既有回归；数字与之前组合重叠，不相加。
- child组合的两种模型选择均运行真实FileSessionStore、AgentRuntime、read_file与Tool Pipeline到最终正文，父scope先结束；另验证旧epoch拒绝和同源摘要通过Core Projection采纳且不消费父pending/观察者。模型本身是Fake，**不是Java+Node完整编码或真实账号证据**。生产路由两项仍为loopback+Fixture工具结果。
- 新增测试的包内Fake接缝、Lifecycle构造与Session ID前缀错误先导致编译/Fixture失败，均修正Fixture，未放宽生产安全规则。严格CLI及上游Javadoc首轮报告新Registry缺构造说明，补中文契约后原严格命令通过。
- 日志：`pi-explicit-runtime-verified.log`、`pi-explicit-runtime-javadoc.log`。该组不是标准clean verify；HeadlessRuntimeSessionTest的外部Symlink场景因当前环境创建链接时报AccessDeniedException而skip，不当成通过。
- 仍需完整Root→delegate→child回流、四路Java+Node编码/恢复、清理失败可观察性及预算边界复核、统一CLI/TUI/发行和两次新标准构建；不得把本组通过换算为离线候选完成。

复验命令：

```text
./mvnw -q -pl cc-java-cli -am -Dtest=PiChildModelBindingTest,PiRootModelBindingTest,ExplicitRunModelBindingTest,SelectedRunScopeOwnershipTest,PreparedChildRuntimeTest,RuntimeStartupCancellationTest,AgentSupervisorS12Test,ContextModelBindingTest,SummaryOperationLocalTest,HeadlessRuntimeSessionTest,ProviderLoginRouteTest,ProviderAuthLifecycleTest,PiProviderAuthServiceTest,PiModelSelectionServiceTest,ProviderAuthApplicationServiceTest,SelectedProviderRouteFactoryTest,PiSelectedProviderRouteFactoryTest,AgentDefinitionModelSelectionTest,FileAgentDefinitionCatalogTest,PiRouteProcessTest -Dsurefire.failIfNoSpecifiedTests=false -Dcodej.test.nodeExecutable=D:/node/node.exe test
./mvnw -q -pl cc-java-cli -am -DskipTests -Dmaven.javadoc.failOnWarnings=true package javadoc:javadoc
```

### Root委托回流与启动预算增量

- `PiRootDelegationTest`两项通过：真实Headless Root→delegate_agent Pipeline→Supervisor入队捕获→独立child read_file Pipeline→有界任务结果→Root最终正文；同一Session再运行一轮并切模，分别覆盖继承与明确覆盖。child运行时Root认证互斥门仍有效，不能靠释放父门或重入Root门实现；四个gateway最终关闭，租约归零，父消息不含child完整正文。Provider为Fake，不是Node/账号/公开CLI验收。
- Fixture先因跨Run重复Tool Call ID被正确拒绝，随后其断言错误地忽略了保留的上一轮canonical结果；改为唯一ID并核对累积历史及当前Call ID，未弱化生产协议。
- 新增预算回归先复现两处缺口：启动剩余2秒在USER_PROMPT中变成约5分钟；剩余0但未通知取消时仍COMPLETED。Runtime现以正剩余预算收窄实际AgentLimits，保留次数/用户输入/explicitSkill；已耗尽按启动取消收口；Hook之后的deadline线程仅等待剩余时长。另验证不能扩大较短显式预算，以及没有外部timer仍由Runtime超时并拒绝迟到正文。
- 主任务`cc-java-domain`与`cc-java-core`普通test：77 suites/493 tests，零失败/错误/跳过。最新路由/Root/child/预算组合：21 suites/221 tests，零失败/错误/跳过；本轮原Symlink测试未跳过，不据此推定系统防护或权限变化。两组与旧证据重叠，不相加。
- 严格CLI及上游Javadoc通过。日志：`pi-startup-budget-repro.log`（原两项失败）、`pi-core-budget-regression.log`、`pi-delegation-budget-verified.log`、`pi-delegation-budget-javadoc.log`。后续Fixture断言诊断调整与新四路进程测试还需统一复验。
- 清理只读审查确认：Lease失败保持fence，但scope返回/ChildRuntimeScope吞异常未提供直接任务清理状态；不得用SUCCEEDED推断RELEASED。独立清理状态通路正在设计/准备证伪；不把此缺口混同Pi操作内EOF/exit失败（后者在正文交付前拒绝）。四路Java+Node真实工具循环测试也在补充，尚未计入上述结果。

### 独立清理状态整合中（尚非整批验收）

- Core增加ResourceCleanupStatus、ChildTaskReport快照、scope显式探针及Supervisor终态先行/关闭后单次冻结；关闭/探针失败、旧无证据、pending均不伪报释放。Task不新增对scope/Runtime/probe的长期持有，也不二次finish。普通Tool摘要带cleanup固定分类。
- 主任务完整domain/core test：79 suites/510 tests，零失败/错误/跳过；严格Core/Domain Javadoc通过。日志`pi-core-cleanup-regression.log`、`pi-core-cleanup-javadoc.log`。此前493 tests与agent独立17 tests都与本组重叠，不另累计。
- 边缘正在整合：Lease独立失败位/terminal回执→Selected/Fenced scope→ChildModelRun→ChildFactory状态盒。额外核对发现FileSessionStore也有best-effort IO吞异常，因此保持原关闭接口并添加sticky资源回执，不能仅用map清空确认成功。
- 新增Lease关闭/取消阻塞、child正文完成但关闭失败/fence保持、Session channel故障、Journal只保存terminal时清理快照测试；CLI整合尚未运行，不能计通过。Core注释亦已澄清撤销清理可与Run执行重叠。
- 协议/TUI投影及四路Java+Node真实read_file循环仍在准备，未构成公开入口或在线验收。当前进度状态已标明整合中，看板代码摘要待各输入稳定、主任务核对与统一复验后刷新。

### 四路真实SDK工具循环与清理接线复验

- `PiCodingLoopProcessTest`四路已实际经过Selected/Pi Store/RPC→真实Node/public SDK→Java AgentRuntime/read_file Pipeline→最终正文；核对两次HTTP、调用/结果ID、正文marker、canonical continuation及JSONL Resume恢复、租约归零，API_KEY ENV临时导出擦除。Codex为未来expiry合成OAuth，无真实登录/刷新或OAuth内部字节擦除新增证据。
- Fixture首次失败来自Optional正文断言，以及Node24 Codex发送zstd而JDK fixture只解析JSON。保持实际provider API，在仅本测试fetch seam中解压SDK生成的zstd后交给loopback server；不据此声明压缩HTTP原始字节验收。全局/注入fetch均限定本次origin并拒绝重定向。修正后六suite/25 tests通过。
- 扩至14 suites/138 tests时，Qwen一次MODEL_ERROR，未记录足够分类，根因未知。仅增加安全modelFailure/HTTP计数诊断后，同组合138 tests零失败/错误/跳过；此为重跑通过，不是缺陷已修复。列为待查，不能隐藏首次失败或推定防护原因。
- 最新组合包含Lease阻塞/失败、child完成但关闭失败仍UNCONFIRMED且fenced、Session Store安静IO故障、Journal快照、原Session/Root/显式scope及Core清理回归。严格CLI及上游Javadoc通过。日志`pi-coding-cleanup-first.log`、`pi-coding-cleanup-verified.log`、`pi-coding-cleanup-integration.log`、`pi-coding-cleanup-integration-rerun.log`、`pi-coding-cleanup-javadoc.log`；与Core510及旧组合重叠，不累计。
- 不覆盖恢复后的新一轮编码、Fork编码、四路完整摘要采纳、公开CLI/TUI、物理终端或在线账号；这些仍按原计划推进，S15/能力等级不提升。

### 清理状态协议投影复验

- Java taskPayload直接投影实际cleanupStatus；TUI严格校验五种小写值，旧payload缺字段归unknown，null/未知值拒绝。Reducer保留当时快照，运行terminal或Worktree更新不推断released。
- 主任务复验：15 suites/144 Java tests零失败/错误/跳过，含四路实际进程循环；TUI typecheck及3 files/92纯离线tests通过。日志`pi-cleanup-protocol-integration.log`、`pi-cleanup-tui-verified.log`。与138、510及agent结果均重叠，不累计为新增数量。
- 本次没有新增UI布局、面板、弹窗、焦点或通知框架；现有子任务没有详情展示入口，故只声明协议/state传递，不声称cleanup已在TUI可见或实际按键验收通过。公开任务inspect入口和更完整界面/编码验收仍待推进。
- 前述Qwen一次MODEL_ERROR仍为未查明项，不因本次通过删除记录。能力等级和Stage/Gate不提升。

### 参考机制补核（只读源码，不新增测试计数）

授权基线AUTH-SRC-2026-07-29-A的精确路径/符号已补入ADR-100“显式作用域参考补核”表。
确认模型可在创建边界再次解析，子Context独立但同步取消可共享，后台终态与清理分离；
参考也使用异步局部存储，压缩存在缓存分叉及当前主模型回退，不能将其概括成本项目显式端口实现。
本项目入队冻结选择/authEpoch、独立lease/空模板及不采用该压缩回退均明确列为独立设计或必要偏差。
参考账号epoch与跨登录来源存活仍Unknown，未运行参考UI或账号；此次仅补源码对照，不新增通过项或提升等级。

## 必须证伪的场景

1. 缺凭证和组件不可用时目录仍完整；认证方法与模型路由不能错配。
2. API Key/ENV隔离，OAuth链接/回调/手工输入/取消/端口冲突/超时/迟到成功。
3. 刷新旋转、跨进程竞争、持久化失败、logout fence、generation CAS及重新登录。
4. start前错误、交错块、partial可变对象、半截JSON、多工具ID完整配对、非法参数及拒绝恢复。
5. 模型取消、网络/协议错误、EOF/进程死亡、不完整输出；不得假成功、隐藏重试或重复副作用。
6. 下一轮、跨路由切换、摘要、压缩、Resume/Fork与推理续接信息。
7. 实际Java+Node+Pi Faux/loopback和生产入口终态；80/100/120×24/35 PTY。
8. 两次标准clean verify、TUI/Worker/launcher、严格Javadoc、发行目录、看板及隐私/差异复核。

## Batch A 当前事实（2026-09-13）

新增`worker-protocol.mjs`处理严格JSON/UTF-8、长度/总量/深度/帧数、序号与唯一终态、缓冲擦除和封闭错误；
Batch A时`worker-runtime.mjs`只开放catalog，取消/EOF/超时关闸，晚到结果不能发布；本节保留该切片历史证据。
`worker.mjs`拒绝参数及直接TTY、收紧子进程环境、核对本地Pi依赖版本，不转发底层stack。
宿主spawn前环境清理仍必须独立实现，入口自清理不能撤销已发生的NODE_OPTIONS预加载。

实测：`node --test worker-protocol.test.mjs worker-runtime.test.mjs worker-entrypoint.test.mjs`：27/27，零skip。
其中24项为内存协议/状态机，3项为真实Node子进程负例；没有模型网络请求。
测试先证伪了两处本批实现错误：协作取消的异常覆盖CANCELLED终态、超限未发送帧消耗序号；修正后原用例通过。
不能把catalog操作就绪误作认证或模型就绪；Batch A入口对其他操作返回OPERATION_UNSUPPORTED而不转用旧Provider。
随后主任务通读并复核`provider-registry.mjs`及其测试，补充真实Worker进程的目录成功场景。
`npm test`全组件55/55、零skip通过：旧OpenRouter桥13、新目录14、严格协议14、状态机10、入口4。
真实进程成功返回三个品牌、四条路由及非空静态模型目录，顺序帧/唯一终态/零stderr通过；测试进程只注入合成Key，未进行认证或模型网络请求。
目录字段只表示Pi声明，仍须由Java与本项目实际支持取交集，不证明账号模型权益或图片能力。
主任务随后复核新`cc-java-model-pi`的协议生产类和测试、确认根POM仅新增一行module注册，并重跑：

- `./mvnw -q -pl cc-java-model-pi -am -Dtest=PiWorkerProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test`：52 tests，零failure/error/skip/flake。
- `node cc-java-model-pi/src/test/resources/pi-protocol-interop.mjs`：Java编码→Node逐字节解码通过；Node编码与Java已消费的独立样例字节相同。

这仍是独立样例的双向互操作，不冒充Java宿主启动真实Worker的业务闭环。该协议切片当时尚未实现StreamingModelGateway、认证事务或进程生命周期，后续集成证据单列。
真实SDK Faux模型流和TUI/CLI生产接线还需继续验证。

### Java宿主进程基础集成

主任务复核`process/PiWorkerConfiguration/PiWorkerConnection/PiWorkerException`及测试后，发现并以确定性测试复现：
编码期间close已清理完成，发送线程仍可把含秘密帧入队，导致无人消费的字节残留。
修复由发送方在finally尝试从队列取回所有权并擦除；已被writer领取的帧仍仅由writer擦除，避免写入竞态。
新增原场景回归后，主任务执行：

```text
./mvnw -q -pl cc-java-model-pi -am -Dtest=PiWorkerProtocolTest,PiWorkerConnectionTest,PiWorkerNodeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dcodej.test.nodeExecutable=D:/node/node.exe test
node cc-java-model-pi/src/test/resources/pi-protocol-interop.mjs
./mvnw -q -pl cc-java-model-pi -am -DskipTests -Dmaven.javadoc.failOnWarnings=true javadoc:javadoc
```

- Java 52+42+1=95，零failure/error/skip/flake；真实目录用Node24.14.0，**没有模型或OAuth网络请求**。
- 真实Java发送operation.start，经生产worker.mjs与Pi静态工厂返回三品牌/四路由/非空目录，确认唯一终态、真正EOF与exit0。
- 未设显式Node属性时集成用例会skip，不能算实测；本次已启用并通过。
- 严格Javadoc首次报告2项说明缺失，补正文及字段说明后原命令通过，未降低警告门槛。
- 关闭仅保证本次直接子进程与自身IO资源的有界确认；无任意孙进程清理、跨进程撤销或OS sandbox保证。
- 尚未接入CLI生产选择、身份事务、模型/摘要、OAuth刷新或Session续接；认证业务还须收窄至32KiB/帧及128KiB/操作。


## Batch B 凭证适配当前事实

主任务复核`credential-store.mjs`与测试后，发现list借道read读取秘密，补独立宿主metadata端口并以原场景回归验证。
私有事务抽象现在是list/read/begin/finish/abort；list只接受本身份的providerId/type，不能夹带access/refresh或其他身份。
`npm test`全Pi组件74/74、零skip（其中凭证适配19），覆盖串行modify、取消、有限清理、迟到ACK、错误脱敏及快照隔离。
这是Fake事务端口证据，不是Java持久化/跨进程锁/真实refresh；该适配器首轮验证时尚未接入Worker。
Java独立Pi命名空间typed存储已进入集成复核。主任务通读生产实现，补测试复现并修复两项：
先检查UTF-8却再让Jackson按原字节自动探测编码，导致UTF-16被接受；remainingTime已为0但取消标志未置位时仍发布登录。
改为只解析已严格解码的字符并拒绝BOM，在存储活动检查中纳入剩余预算。

主任务重跑`./mvnw -q -pl cc-java-cli -am -Dtest=PiCredentialMaterialTest,PiCredentialStoreTest,PiCredentialSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test`通过（原59加2条新回归）。
随后主任务复核秘密封装绑定、布局Semaphore及重复close失败保持，重跑同一Maven命令得到80项（18+55+7），零failure/error/skip。
同kind秘密交换、metadata/ref/epoch/revision错配、布局排队取消/deadline、durable phase恢复均覆盖；close故障是合成句柄注入，不是实际OS故障。

Node新增`credential-rpc.mjs`，按ADR100关联requestId，等待send完成与宿主ACK后才返回，拒绝重复/未知回复，取消后永久关闭并要求宿主释放事务。
随后统一失败收敛：Java任何失败响应封闭会话，Node收到拒绝也关闭client并拒绝其余pending，不再向已关闭宿主发送abort。
`npm test`全组件82/82、零skip，其中凭证19、RPC8；这部分仍为Node端Fake宿主证据。

Java `PiCredentialRpcSession`绑定身份/LOGIN或MODEL/generation或epoch，严格请求、持久ACK、ENV擦除与关闭竞态50/50通过。
新增 `startAuthentication`：单帧32KiB，发送前预留字节与实际stdout/stderr共用128KiB；空白/未完成行/stderr均不绕过物理计数。
四项独立JVM Fake进程回归验证发送单帧、原始空白、双向合计及stderr合计；旧模型连接预算不变。该限额切片完成时Worker尚只有catalog，不能把工厂限额本身称作完整认证业务已上线。

`PiCredentialRpcProcessTest`主任务复核、扩大到三家API_KEY，并显式用`D:/node/node.exe`重跑10/10、零skip：真实Java/Node、生产双端RPC和持久Store、生产Provider注册表及公开Pi SDK。
OpenAI API、DeepSeek、通义国内Token Plan走真实SDK `models.login`与合成秘密交互；Codex走真实 `models.getAuth`调度，但refresh网络回调为独立Fake。
另覆盖finish前delete拒绝迟到旋转、Node异常退出后的事务释放、ENV不落盘、刷新失败与最终文件回读；fixture禁止网络调用，绝不证明真实账号/订阅权益或OAuth网络刷新。
LOGIN的成功ACK后应结束操作，由新Store/新MODEL会话读取；同一LOGIN继续read仍受原generation约束而CONFLICT，负例确认不能谎称此前已发布材料被回滚。

本批相关Java分轮共239项均通过：协议52、进程46、真实目录1、材料18、持久55、安全7、RPC50、凭证真实进程10；均零failure/error/skip。
`./mvnw -q -pl cc-java-cli -am -DskipTests -Dmaven.javadoc.failOnWarnings=true package javadoc:javadoc`通过。
### 生产Worker登录接线（仍非应用层登录交付）

新增`auth-operation.mjs`并接入`worker-runtime.mjs/worker.mjs`的auth.login；三家API Key走公开Models.login，Codex只browser/manual_code，禁用环境/文件fallback。
宿主RPC失败、通知输出失败、取消、重复输入均永久关闭；结果仅stored元数据，秘密不成为最终正文。Worker外层独占终态，先确认操作清理再发送成功。
主任务补测试先复现再修复：Codex误接受未批准的secret提示、全空白输入被接受、取消提示的一次迟到回复在结果flush期间被误判失败。
修正后只在对应已取消提示的一次迟到输入上消耗记录；未知/重复ID、显式取消及其他错误不被放宽。

`npm test`168/168、零skip（认证操作78、认证状态机8，其他既有82）。Codex依然仅Fake登录交互/网络回调；三家API Key使用真实SDK。
`PiProductionLoginProcessTest`5/5，真实开发目录worker.mjs、真实Java RPC/Store与EOF/exit：OpenAI API、DeepSeek、通义国内Token Plan合成Key录入回读，取消无保存，输入期间generation变化拒绝保存。
这一轮组合命令运行相关Java244/244、零failure/error/skip，包含上述5项与此前239项；package及严格模块Javadoc再次通过。

Java应用服务与lease/fence装配、两套UI/CLI、模型请求仍未接入；Codex真实OAuth/账号与物理界面没有验收。S15 G3/G4继续OPEN。

### Java异步登录协调器

新增`PiLoginOperation`，持续接收私有RPC，异步PromptHandle交付材料；最多16个提示、单线程有界发送，关闭需确认输入入口与已拥有资源收敛。
主任务通读实现后补测试复现：内部清理与外部close共用状态，导致外部已关闭后最终检查仍返回成功回执。
现区分内部cleanup与外部关闭，最终回执检查拒绝外部关闭；测试同时确认已持久材料不被谎称回滚。
协调器61/61、零skip，含三家真实Node合成Key；完整相关Java组合305/305通过，Node维持此前168/168。
严格Javadoc首轮因缺少主说明失败，补充公共契约说明后重新package+javadoc通过，未关闭警告Gate。
本协调器仍未装配到共享service、lease或UI，不表示普通CLI/TUI登录已可用。

## 初始Java接缝复核（历史源码观察，后续修复/证据见上文）

- `SelectedProviderRouteFactory.open/LazyRunGateway.summarize`将模型与摘要冻结到同一Run身份；新Pi适配不仅需要StreamingModelGateway，还需要同源摘要。当前资源回调吞掉provider.close异常，接入Worker时必须避免把清理失败算成lease排空。
- `RestrictedFileCredentialStore.locked/saveStoreInternal/recover`的generation是全索引代次；旧API_KEY记录和snapshot/readSecret分离读取不能直接充当OAuth锁内modify事务。保留先发布index/generation再清理旧秘密的顺序。
- `AgentSupervisor`使用复用的ThreadPoolExecutor；selected gateway使用InheritableThreadLocal。跨任务继承旧Run是待证伪风险，不以现有注释证明隔离；加入同线程两Run与父Run结束后后台child场景。
- 续接必须落在`AssistantMessage→SessionJournal.assistantAppended→FileSessionStore→JsonlSessionCodec→AgentSession.restore`链路；只扩展ModelTurnMetadata不会持久化。runtimeMetadata会投影成模型可见文字，不能存隐藏签名。

## 认证回执、恢复/Fork编码与超时诊断补验（当前未提交工作树）

- `PiSessionCodingProcessTest`四路各九个真实Java→Node→公开SDK loopback请求：
  初始read/正文、关闭Writer后RESUME新Run、FORK新Run read/write/read/正文、实际摘要与Core compact/install/prepare采纳。
  确认唯一调用/结果配对、真实`artifact.txt`、原会话不被Fork修改、opaque continuation继承、Writer重取及租约排空。
  使用既有no-op Checkpoint接缝，不冒充完整生产Checkpoint或CLI/TUI验收。
- 首次99项组合有四个测试把Optional正文错误地与String比较，另一个ENV测试只造fence却未提供真实删除回执。
  修正测试为正文精确contains、先实际logout取得删除水位；另加反向场景确认单独fence绝不能被ENV保存解除，未放宽产品安全Gate。
  第二次组合 **100 tests、0 failure/error/skip**：Receipt 13、共享服务18、协调器61、基本四路编码4、恢复/Fork/摘要4。
  这些组与先前61/18/4重叠，不作为额外累计通过数。
- `PiModelGatewayTest`复现Worker只发TIMEOUT终态时误报INVALID_RESPONSE，现保留REQUEST_TIMEOUT；有内容仍INCOMPLETE_STREAM，仍不增加重试。
  Gateway/Process/Connection/Summary组合 **164 tests、0 failure/error/skip**。HTTP fixture新增固定阶段与单调耗时安全诊断，
  不记录body/header。历史Qwen一次MODEL_ERROR原因仍Unknown；以上通过不是该问题的根因证明。
- Codex公开SDK `dist/providers/openai-codex.js#buildRequestBody`没有序列化maxTokens。
  本轮实际HTTP明确核对缺失，而不是补字段造证据；另三路验证原生HTTP输出上限128。
  Codex仍有Java时间/字节限制、摘要信封预算及Core采纳Gate，但**不具有已验证服务端Token硬上限**。
- 私有桥初次后台实现因传输中断仅留下实现与进程fixture，未交付测试，不计通过；已恢复专门测试工作。
  TS私有桥与发行脚本的子任务结果仍须主任务集成复验。新增`codej.piAuthCli`显式路径，开发Pi交互登录先编译TTY薄壳，ENV/旧控制不增加该构建依赖。
  公开CLI、两套实际TUI、安装版、双次新clean及物理终端均未据此宣告完成。

## 公开入口与私有桥集成复验（2026-09-14，仍未提交）

- 主任务复验`Pi*Test`：**31 suites/629 tests/1 skip，零失败错误**；skip为配置符号链接拒绝场景（未满足该测试前置条件）。
  含私有Java桥48项、原Store/RPC/模型/摘要/Root/child组；与旧证据重叠，不累计为额外通过数。
- 真正公开CLI链第一次失败：Windows直接JSON argv应13项却被拆成24项，JSON引号丢失；旧Fake Runner没有覆盖此问题。
  现改为严格规范Base64url封装的非秘密JVM参数数组，不是加密或秘密传输。
  新`PiPublicCliProcessTest`三路API Key使用当前Java CLI→Node CLI→Java私有桥→生产Worker/公开SDK，
  登录、status、logout全部exit0；临时home/workspace、合成Key、真实EOF/退出与owned Worker已消失，fetch guard零触发。
  此组含CLI配置单元共 **14 tests、零失败错误跳过**；临时Python smoke与新三项测试重叠，不计第二份覆盖。
- 普通Pi控制恢复接通Handler/codec/TS能力与请求后端关联。新Java fixture首次把Session Store放进Workspace，15项在安全Gate被拒绝；
  修正为兄弟目录后，新20项与旧协议/Codec组合 **35 tests、零失败错误跳过**，不修改Session安全边界。
- 主任务再复现并修复：外层TUI shutdown没有等待私有登录取消结算；无Session的精确protocol.error不能释放pending；
  UI假fixture把实际REVOKED_IN_PROCESS写成REVOKED；私有CLI/默认spec漏转发显式代理；长OAuth URL被追加到整个固定屏幕之外。
  修复分别保留实际关闭/未知失败、精确请求关联、仅CONFIGURED_UNVERIFIED可复用、最小代理白名单和面板内受控OSC8短标签。
  公开Pi 0.85.1 login-dialog#showAuth源码机制已对照，未运行参考UI，不自动打开浏览器；无OSC8终端与物理打开仍未验证。
- 新旧指定TUI组 **13 files/277 tests**通过，包含私有桥/CLI、两生产组件离线Ink、六尺寸长链接预算、协议及旧认证回归；
  均为Fake/Ink/协议证据，不是当前index→真实Java→终态的TUI验收。主任务当前package与严格CLI/上游Javadoc通过。
- 脚本层`TestPiLauncher.mjs`、`TestPiRelease.ps1`、`TestCodejDevLauncher.ps1`主任务复验通过；
  当时真实BuildRelease/安装版及两次新standard clean尚未执行；后续结果见下面中间检查点，不能用静态脚本通过替代。

## 整体回归中间检查点（2026-09-14，后续预算恢复修复前）

- 第一次standard clean在WorkspaceSnapshotTest出现Git启动OS错误5，仍为未归因的环境/启动失败；原断言不变，隔离1/1随后通过。
  第二次被调用执行器300秒上限中断，不算产品超时或通过。第三、第四次完整`clean verify`均exit0，
  各 **259 suites/2193 tests/13 skips、零失败错误**。后续代码变更必须重新复验，不冒充最终候选记录。
- Pi Node全部 **238/238**；TUI串行全部（不含安装专组）**39 files/670 tests**，包括主任务复验的两组件×三路真实Java/Helper/SDK **6/6**。
  新旧数字重叠，不累计。真实Java Ink测试另拒绝其他工作树classpath，检查Key及特征前缀不出现在界面/协议/Session。
- 源码index ConPTY在80×24三条API Key路由×两TUI、CLI保存/取消共12例，另OpenAI两TUI五个尺寸10例通过。
  但该轮自定义Java命令没有Context三参数，不能冒充启动器默认开启Context的证据；后续必须按真实默认预算重跑。
  这些是PTY按键/屏幕文本，不是物理终端截图或在线账号验收。
- 首次真实BuildRelease产生Pi独立生产闭包与SBOM（92个Pi npm包）；原安装Plan专组1/1通过。
  **该次stdio smoke的临时Java适配器被codej.cmd重设CODEJ_JAVA绕过，隔离未生效，可能触及本机配置/Session，不计隔离验收。**
  无模型请求；没有读取这些本机文件来反推或清理未知状态。当时尝试改走JAVA_HOME；后续EPERM与最终隔离方案见下一节。
- 安装ConPTY进一步复现：C#测试适配器的重定向句柄未转发、随后stdin缓冲未逐块flush，分别造成无输出和连接等待；
  这些是测试适配器问题，已补直接user.home属性证明及字节转发/flush，不能归因为产品或防护软件。
- 实际安装默认预算触发GPT-4小窗口拒绝（8192 < 8192+4096），UI原先只显示通用启动失败，尚未交付；
  ADR-100已固定不缩减保留空间、类型化提示与显式重选恢复。该原失败场景未通过前不宣称安装Pi闭环完成。

## 当前实现复验（2026-09-14，预算恢复修复后）

| 场景 / Feature | 运行入口与证据类型 | 实测 / 未通过项 |
| --- | --- | --- |
| 小模型保留预算拒绝 / MODEL-13、CTX | ContextModelBindingTest、ModelContextBudgetProtocolTest；两生产视图状态回归 | 保留min(window,current)及8192/4096；仅启动前分类MODEL_CONTEXT_BUDGET_INCOMPATIBLE；不请求模型、不自动重放，显式更大窗口后交付。初版新增Java fixture未实现ContextSummarizer导致编译失败，主任务改为禁止误用的独立摘要lambda后通过，没有改生产Gate。 |
| 实际安装原失败恢复 / MODEL-13 | 安装default经codej-launcher；next经安装index及显式Java子命令；ConPTY文本 | gpt-4原失败→安全指导→用户重选gpt-4-turbo→实际read_file结果→正文→下一轮；两视图六尺寸通过。不是参考UI视觉一致或物理截图。 |
| 三路认证与取消 / MODEL-13 | scripts/TestPiAuthPty.mjs，安装包SDK/operations与严格loopback | 80×24三路×两视图，加公开CLI保存/取消共12例；OpenAI两视图另五尺寸10例，共22例。合成Key/特征前缀不回显；取消输入未保存、取消退出保留配置，最终确认后登出，拥有的父进程/Worker退出。Codex真实登录不在该组。 |
| 当前源码入口 / MODEL-13 | 相同脚本，源码index/public CLI | 80×24全部12例通过；这次两视图均显式带启动器Context参数，替代早期no-op Context声明；不与旧22例累计。 |
| 实际批处理安装隔离 / MODEL-13、CLI | TestBuildRelease的codej.cmd→包内launcher→真实Java | JAVA_HOME/bin/java.exe临时适配器遭spawnSync EPERM，归因Unknown，不更改防护。最终仅该测试进程的Node预加载在真实spawn边界补临时user.home；保留原唯一initialized、exit0、零stderr、15秒断言，并增加调用回执+临时home唯一Session文件证明。该预加载不进入产品；不是用Fake Java替代。 |
| 安装闭包和完整性 / MODEL-13 | TestInstalledPlanTuiE2E.ps1实际BuildRelease、TestBuildRelease | 新发行含Pi production闭包、SDK 0.85.1 SBOM记录、无项目Pi测试文件；Pi入口/隐藏文件篡改均由实际启动器失败关闭并恢复，安装Plan 1/1通过。C# ConPTY adapter仅测试使用，逐例先验证实际JVM home。 |
| 当前回归与文档 / MODEL-13 | CLI模块、全TUI、Node、launcher/Javadoc | CLI **119 suites/1123 tests/3 skips**，全TUI **40 files/680 tests**，Pi Node **238/238**；TestPiLauncher、TestPiRelease、TestCodejDevLauncher及package后严格上游/CLI Javadoc通过。各组重叠不累计。 |
| 最后完整Gate / G3/G4 | 两次原样`clean verify -Dcodej.test.nodeExecutable=D:/node/node.exe` | **两次均失败**于WorkspaceSnapshotTest初始Git启动OS5：UNKNOWN/GIT_UNAVAILABLE，目录存在、零输出、未被interrupt。保留原断言，不跳过、不自动重试；不声称旧2193两次通过验证了最新候选，也不把CLI单独通过冒充整个reactor通过。 |

源码第一次恢复PTY未见新分类、零模型请求；当时采用旧Surefire classpath，疑似上游JAR尚未重包（Inferred，未采集完整错误帧，不作唯一归因）。
完成package后源码及安装原场景通过；这次失败仍保留，不因后续成功反推确切原因。
最终上述安装和PTY用例使用临时workspace/home与合成材料；最初隔离失效记录作为例外保留，不因最终修复而删除。已确认的旧用户Java/Node进程未停止。

随后用原断言隔离运行WorkspaceSnapshotTest通过；未过滤用例的整个tools模块也通过（30 suites/207 tests/8 skips）。
这不能消除两次clean中的失败（当时tools模块207 tests/17 skips），只表明结果具有条件性。
只读代码复核未找到前序测试取消/共享状态误伤本次Git的证据。ProcessTreeTerminator的taskkill helper等待被中断后缺少退出确认
是另一个待验证生命周期缺口，不是已证明的OS5原因；本批不以推断修改进程或防护策略。

### 复验入口与日志

在本工作树用JDK21、Node24.14.0执行（Node22未实际验证）。以下不是在线账号测试：

```text
./mvnw -q clean verify -Dcodej.test.nodeExecutable=D:/node/node.exe
./mvnw -q -pl cc-java-cli test -Dcodej.test.nodeExecutable=D:/node/node.exe
./mvnw -q -pl cc-java-cli -am -DskipTests package javadoc:javadoc
pwsh -NoProfile -File scripts/TestInstalledPlanTuiE2E.ps1
node scripts/TestPiAuthPty.mjs --pty-module <已安装的@lydell/node-pty绝对路径> --release <本工作树/target/release>
```

PTY脚本默认80×24全部三路；`--provider openai --surface tui --size 100x24`等覆盖其他尺寸。
源码模式省略`--release`，需要当前工作树PiPublicCliProcessTest的Surefire classpath。
TUI全部验证设置`CC_JAVA_TEST_CLASSPATH`为该classpath、`CC_JAVA_PLAN_FAKE_CLASSPATH`为本工作树CLI test-classes，
执行`vitest run --no-file-parallelism --exclude test/installed-plan-e2e.test.ts`；安装专组由上方PowerShell入口另跑。
完整构建执行器限时900秒，不提高产品deadline或测试断言窗口。

本机Temp保留`pi-final-clean-verify-first/second.log`、`pi-final-cli-module.log`、`pi-final-tui-full.log`、
`pi-final-worker-full.log`、`pi-final-git-isolated.log`、`pi-final-tools-module.log`、`pi-installed-build-final.log`、`pi-final-installed-pty-*.log`、`pi-final-source-pty-80x24.log`、
`pi-final-javadoc.log`与launcher脚本日志。早期失败及修复记录同目录保留；它们不是提交或可永久访问的外部证据服务。

## 后续阻塞对照及端到端复验（2026-09-16/17）

- Bash标准完整构建、Windows原生mvnw.cmd、实际ConPTY内完整构建、仅保留OS/Java/PATH等显式环境的完整构建，
  均在原WorkspaceSnapshotTest首次Git启动OS5失败；未过滤测试、增加产品重试、延长测试断言或调整防护。
  ConPTY前两次cmd参数组合为测试驱动自身语法错误，Maven未运行，不计为构建结果；改由PowerShell在ConPTY中执行后得到真正的同一失败。
- tools全组带Node测试属性有失败也有通过；原单用例带同属性通过。该属性在tools源码没有引用，不能声称它是原因。
  第二次只读复核未找到Pi测试跨fork遗留外部清理程序或错误父子PID归属的证据。
- 测试专用条件对照发现：原调用失败后，后续不同条件的调用可以启动；但把realpath、stdin空设备、合并stderr、短argv、
  去除Git/locale覆盖、独立线程或GC分别放到首次调用前，仍得到原OS5。没有可靠修复依据。
  **保留原失败，不用后续探测成功替代原断言**；临时诊断方法/分支已全部撤回，生产启动策略、权限边界与测试断言未改变。
- 重新实际BuildRelease、批处理隔离/完整性负例与安装Plan专组通过。重新运行安装ConPTY **22/22**：三路API Key两视图、
  CLI保存/取消，OpenAI两视图六尺寸；预算原失败→显式重选→真实读文件→正文→下一轮、注销与自有进程退出均通过。
- 四路新Run/Resume/Fork读写、Root/child装配与公开CLI组合再次 **29/29、零失败错误跳过**。与既有1123/680等重叠，不累计。
  本次未使用真实账号/付费端点，仍不是Codex在线OAuth或物理终端截图验收。

日志：`pi-acceptance-clean-verify-a.log`、`pi-native-wrapper-clean-a.log`、`pi-conpty-clean-c.log`、
`pi-clean-environment-build.log`、`pi-tools-node-property-comparison.log`、`pi-tools-order-diagnostic.log`、
`pi-git-*-first-control.log`、`pi-git-start-conditions.log`、`pi-sep17-installed-build.log`、
`pi-sep17-installed-pty-*.log`和`pi-sep17-coding-e2e.log`，均位于本机Temp。
当前没有源码层面的可证实修复；不擅自重启已停止的防护归因调查，不靠重复同一构建刷绿。

## 2026-10-05 离线验收复跑

本轮未读取真实凭证、未访问在线 Provider、未产生付费请求。以下命令均在当前工作树执行并成功：

- `./mvnw -q -pl cc-java-cli -am test -Dcodej.test.nodeExecutable=D:/node/node.exe`：Java CLI 及依赖模块测试通过；仅保留既有环境相关 clean 阻塞，不以跳过测试替代。
- `node --test cc-java-provider-pi/*.test.mjs`：**238/238**。
- `npm.cmd --prefix cc-java-tui run check`：**674/674**（39 files，无跳过）。
- `pwsh -NoProfile -File scripts/TestLoginLogout.ps1`：登录/注销离线回归通过，包含 TUI、Java 与 Pi Node 组合。
- `pwsh -NoProfile -File scripts/TestPiRelease.ps1`：Pi 生产依赖布局、来源身份、隐藏文件摘要、SBOM/license 离线检查通过。
- `pwsh -NoProfile -File scripts/TestBuildRelease.ps1`：发布候选构建、自检与完整性校验通过（SBOM 170 components）。
- `java scripts/ProgressDashboard.java --check --self-test`：看板检查与自测通过。

本轮新增证据仍不等同于最终 `clean verify`、真实 OAuth、在线 Provider 或物理终端验收；完整候选的既有 OS error 5 阻塞保持原样记录。

同日追加发布入口验收：`node scripts/TestPiLauncher.mjs` **8/8**；`pwsh -NoProfile -File scripts/TestInstalledPlanTuiE2E.ps1` 安装版 Plan TUI→Java E2E **1/1**；发布构建和 S14 release self-test **170 SBOM components** 通过。测试均使用合成材料，不读取用户配置、不访问在线服务。

## 尚未完成

功能接线和受控离线入口交付已有证据，但**完整离线候选验收仍被最终standard clean阻断**，G3/G4与S15 Exit保持OPEN，MODEL-13保持L1。
历史Qwen一次MODEL_ERROR、原生启动/终止来源仍Unknown；不改防护设置，不伪称已查明。
Codex真实OAuth/账号权益、在线Provider/付费行为、服务端Token硬上限、Node22、物理截图/OSC8点击及跨平台等仍未验证。
用户既有配置、SDK/Node运行时与防护状态不由本批擅自修改。证据区分源码、Fake、loopback、真实进程、PTY、物理界面和真实服务商，不以测试数换算体验完成率。
