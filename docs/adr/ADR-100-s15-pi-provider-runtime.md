# ADR-100：Pi 三家认证与模型适配，Java 保持运行权威

- Status: Accepted（方案已由维护者批准；不表示实现或在线验收完成）
- Date: 2026-09-13
- Stage: S15
- Feature: MODEL-13 L1→L1（离线候选）；MODEL-01 L1、MODEL-04/05/06/08/10/12与CLI-05/08/09、CTX-07/09现有等级保持。在线门槛满足后另行对账，不自动提升。
- 需求：FR-MODEL-012；保持FR-AGENT-003/004/005、FR-MODEL-010重试约束、NFR-003/013/031。
- Workspace: `G:/AI Cloud/cc-java-login-logout`，`feat/login-logout-integration`，HEAD `422a527b0474f2b97bfd0ccf9db11dc5c917a3bd`；保留既有未提交修改，不提交/合并。
- Baseline: R2026.03；AUTH-SRC-2026-07-29-A沿用ADR-069与Session/Context已记录且未变化的职责研究；新增直接依赖为公开MIT Pi0.85.1，gitHead/integrity沿用ADR-099与lockfile。

## 1. 决策与来源边界

维护者明确选择认证和模型都接Pi，并锁定OpenAI、DeepSeek、通义国内Token Plan。界面为三个品牌，内部为`openai`、`openai-codex`、`deepseek`、`qwen-token-plan-cn`四路由。`deepseekm`按DeepSeek理解。第一交付是完整离线候选，第二交付才进行用户参与的真实账号与费用确认；不得再以只接OpenRouter登录宣称整体接入。

本ADR替代ADR-099第10条“Pi仅OpenRouter认证”的新增路由范围，不撤销旧协议、STORE/ENV、fence、提交CAS、秘密通道和Windows共享Console失败关闭约束。旧Spring AI和Anthropic/OpenRouter/custom身份继续作为显式兼容路径，绝不成为新Pi路由失败后的静默fallback。

Pi拥有Provider目录、认证wire/刷新与单次模型协议；Java拥有Credential持久化、身份、Session/Context、Agent Loop、权限、Tool Pipeline、重试、预算和取消。新增`cc-java-model-pi`边缘模块实现既有StreamingModelGateway；Node组件在现有`cc-java-provider-pi`内扩展，不引入Pi Agent、Pi Session文件、用户扩展或用户auth.json。

## 2. 修改前的逐场景对照

下列Pi路径均为锁定包内公开文档/导出及其实现入口，不是运行参考UI的证据；源码Observed不等于在线Observed。授权材料仅复用抽象结论，不复制源码表达。

| 场景 / Feature | 参考入口、基线与分类 | 可观察机制及不显示什么 | 本项目契约 / 可证伪验收 / 当前状态 |
| --- | --- | --- | --- |
| 三家目录 / MODEL-13,CLI-08 | Pi0.85.1 README Providers；providers/{openai,openai-codex,deepseek,qwen-token-plan-cn}.js，工厂→createProvider→getModels；Documented/源码Observed | Provider独立于凭证和模型可用状态；不因未登录丢失服务商 | 常驻三个品牌/四路由，只投影安全字段；目录零网络；缺组件明确不可用。待实现 |
| 网页与Key / MODEL-13,CLI-09 | providers/openai-codex.js→auth.oauth；auth/types.d.ts AuthInteraction.prompt/notify；README Programmatic OAuth；Documented/源码Observed | auth_url、prompt、取消、回调竞争；OAuth与API Key是不同产物 | OpenAI API/Codex分身份；DeepSeek与通义仅Key/ENV；链接不进Session，手工回调走专用输入。待实现/在线未验证 |
| 刷新和退出 / MODEL-13 | auth/types.d.ts CredentialStore.modify/delete；models.d.ts Models.login/getAuth；README Credential Store；ADR069§3.1授权职责Observed | 刷新在序列化modify中进行，已存身份刷新失败不回退ENV；logout不同于revoke | Java跨进程协调、single-flight与generation CAS；Node只执行回调，提交确认后继续。迟到刷新不能复活退出身份。待实现 |
| 流式/工具 / MODEL-01/04/05/06/10 | models.d.ts Models.stream；types.d.ts AssistantMessage/Event/StopReason；README Complete Event Reference、Compact Assistant Message Frames；Documented/源码Observed | partial是可变对象、块可交错、setup可能直接error；done/error各有语义 | Pi单回合，Java唯一Loop/Pipeline；禁止提前执行部分工具、重复Assistant、SDK隐藏retry。待实现 |
| 续接/压缩 / CTX-07/09,MODEL-08 | types.d.ts textSignature/thinkingSignature/content；README Context Serialization/Cross-Provider Handoffs；授权Session/Projection既有研究 | 同源续接保留内容/关联，跨源转换与持久化不同；不能拿text-only冒充完整恢复 | 框架无关、有界版本化续接块；同源恢复、跨源去除不可移植隐藏块；完整Tool配对、摘要同路由。待实现 |
| 终端/发布 / CLI-05/08/09 | 用户批准方案、ADR099真实ConPTY；Pi README Provider Factories/Bundling；Documented/本项目既有Observed | UI只呈现真实状态，不隐藏组件缺失或把网页打开当成功 | 两套TUI/CLI统一目录，六尺寸PTY；组件随发行目录，保留既有已打包Node。待实现 |

OpenAI官方账号资格/授权端点可达性及真实刷新仍Unknown。依赖公开MIT不等于承诺所有账号可授权；不绕过401/403、地区、套餐或服务条款限制。第二门槛缺账号时明确未验收，不用其他路由代替。

### 2.1 扩大接入前的公开Pi源码复核

只读追踪Pi0.85.1 `auth/resolve.js`的resolveProviderAuth→resolveStoredOAuth→CredentialStore.modify：
先读取snapshot，接近过期后在modify内再次读取/判断，刷新结果经store返回后才toAuth；模型错误会拼接底层cause文字。
因此本项目必须实现真实跨进程modify、保持无ENV失败回退，并只投影封闭错误分类，不转发Pi errorMessage。
此为公开MIT依赖的源码Observed，不是参考商业源码表达复制。

只读追踪`auth/oauth/openai-codex.js`导出的OAuth实现→login→浏览器/设备选择→回调与手工输入竞争→token交换→返回凭证：
上游也有设备流程，但本批用户选择的是网页授权，适配器只在精确匹配的上游模式选择中选择browser，不额外开放未验收设备流程；
未知选项变化应明确不兼容。上游本地回调页面先于token交换完成，不得据网页成功提示生成Java登录成功。
实际凭证还包含accountId，需要受限持久化；回调监听保持loopback，绑定失败可使用本次流程的手工回调输入，不改成任意端口/监听地址。
网页、OAuth刷新与模型请求的代理能力分别验证，不能因模型SDK支持代理就推定OAuth的原生fetch也受同一设置控制。

## 3. 边界与数据流

1. 四个Provider仅从公开子路径导入；不用providers/all或compat全局API。目录只读取工厂与getModels，不调用getAuth/getAvailable或联网refresh。API/能力取Pi声明与本项目已实现映射的交集。
2. 后端/Provider/认证方法/Profile是身份键，模型选择是独立记录；普通用户使用default。旧ID不抢占，旧profile不静默迁移。每Run固定路由，摘要/子Agent继承；切换影响下一Run。
3. OAuth凭证为版本化受限记录，保留access/refresh/expires和Pi所需白名单账户字段；ENV只持久名称。Pi CredentialStore以私有RPC代理Java事务，禁止Node自行落盘。
4. read/list仅限当前可信目标；modify由Java发放有界事务身份并取得当前snapshot，Node计算后提出写入，Java在锁内校验generation/fence并原子提交，再确认。刷新按身份single-flight，logout可取消未收敛事务。取消Run不等于logout；已完成刷新必要的有界提交不准触发新模型请求。
5. Worker一操作一进程，固定入口、最小环境、取消/EOF/timeout清理；不建立全局daemon。生产首批使用SSE；maxRetries=0，Java沿用现有无Provider frame后才可重试的策略。不支持deferred/batch或Pi工具执行。
6. 使用有版本、operationId、sequence和可信target绑定的私有协议。认证32KiB/帧、128KiB/操作；模型1MiB/帧、32MiB输入、16MiB输出，分块且服从更严格现有预算。任意超限/协议错误失败关闭，不把部分正文结算成功。
7. 模型流通过Pi公开帧编码器提取有序更新，终态单独确认；Java发布增量但只持久合法聚合回合。Usage未知不伪造，cost不得冒充真实账单。
8. Assistant新增版本化、提供商命名空间绑定的受限续接信息；不把Pi类型、HTTP body/header或credential放入Core。保留必要的有序内容、签名和Tool关联；同源Resume/Fork可重建，跨源不发送隐藏续接块，旧记录缺字段可读。无法安全表示时明确拒绝。
9. 浏览器URL仅按锁定Provider契约校验；提供打开/复制动作，不强行抢焦点。manual_code/secret走专用输入，不进入普通Agent控制帧、Session、React state或日志。取消/保存未知必须如实呈现。
10. 可信代理配置可经受控边缘传递，不修改全局代理/TLS/防护；proxy credential同样是秘密。仅当显式 HTTP(S)_PROXY 存在时，由 Java Worker 配置固定注入 Node 的 `NODE_USE_ENV_PROXY=1`，使原生 fetch 使用同一白名单代理；不继承 NODE_OPTIONS、Pi扩展或无关环境凭证。

### 3.1 凭证事务接线约束（进入Batch B前固定）

现有CredentialProfile.authMethod与RestrictedFileCredentialStore.parseProfile/parseSecret/writeSecret固定API_KEY，
不能把OAuth JSON伪装成旧Key。新typed记录明确区分API_KEY、ENV_REF、OAUTH，旧记录继续按原语义读取。
身份由backend/provider/authMethod/profile组成，ENV_REF属于API_KEY认证方法的来源，不允许OAuth回退到环境变量。

登录身份代次与刷新材料修订必须分开：显式登录/退出改变身份代次，正常token旋转只更新材料修订，不能把同一Run的正常刷新当成切换账号。
Pi modify在本身份跨进程锁内重读当前材料并单次刷新；持久提交仍需短时索引事务，核对身份代次与材料修订且合并最新索引，
不得用锁外旧snapshot覆盖别的身份。logout先建立fence，迟到刷新发布必须因代次变化被拒绝；持久化ACK前不得发起依赖该token的模型请求。
长时间网络刷新不能靠snapshot→无条件save模拟事务，也不能以复制一份Pi auth.json替代Java权威。

存储兼容采用同一Java受限根下的独立Pi命名空间`auth/pi/`：typed索引与秘密文件分离，旧`auth/profiles.v1.json`及旧secrets不自动重写。
这样旧API_KEY解析器不会误读OAuth，旧孤儿清理也不会删除Pi材料；统一应用服务后续合并元数据视图并按backend精确路由，迁移必须显式且保留旧记录。
本批不是新增OS vault。仍复用RestrictedFileSecurity的路径/owner/DACL/reparse/hard-link/原子写保护。
身份网络事务锁与短时索引发布锁分离；身份锁使用固定有界锁条带映射，避免反复登录任意profile积累无限锁文件。
同一身份必落同条带，不同身份允许保守串行；不通过删除仍可能被其他进程持有的锁文件来回收。
索引只含身份/代次/引用等元数据，list不得读取OAuth秘密。

### 3.2 凭证RPC（Batch B私有契约）

仅在Worker私有连接中使用`credential.request`，payload严格为`{requestId,action,arguments}`；requestId从1连续递增且最多512。
`action`为list/read/begin/finish/abort。前三者arguments为空；finish为`{transactionId,change}`，abort为`{transactionId}`。
change为`{kind:keep}`、`{kind:put,credential}`或SDK契约中的delete。宿主回复`credential.response`，payload为
`{requestId,ok:true,result}`或`{requestId,ok:false,code}`，不传底层异常。
list返回`{entries:[{providerId,type}]}`且至多本身份一项；read/finish返回`{credential}`；begin返回`{transactionId,credential}`；abort返回空对象。

宿主在构造会话时绑定operationId、PiCredentialIdentity和用途，不从RPC参数接受换身份。
MODEL用途绑定authEpoch，仅允许当前材料读取和同账户OAuth KEEP/PUT；LOGIN用途绑定输入前捕获的全索引generation，
PUT走saveLogin，不能在这个阶段假装已激活或验证账号。Worker中的delete契约不授予注销权限：本批两个用途均拒绝delete，
实际注销仍走Java显式fence/drain/delete。ENV只由Java解析本身份已保存的引用，临时导出为API_KEY，不回写环境值。
每连接至多一个活动事务，断连必须关闭它；重复/未知transactionId拒绝。任何失败响应封闭双方RPC会话并触发宿主事务清理；不能向已失败会话重发abort或新事务。
LOGIN在成功持久ACK后结束，新MODEL会话才采用新epoch；同一LOGIN继续read仍受最初generation约束。
`startAuthentication`已在连接层固定单帧32KiB及stdin/stdout/stderr共同128KiB（发送前保守预留，接收按原始字节）；
生产Worker认证操作已接线，但Java应用服务/lease/UI仍待接入；不能把RPC重编码检查当成原始字节限额，也不能据此宣称完整认证体验可用。

#### 生产登录操作接线（Batch B）

固定`operation.start` payload为`{operation:auth.login,providerId,authType}`，仅三条API_KEY路由的api_key与Codex的oauth。
Worker调用公开Pi Models.login，CredentialStore仍通过Java RPC，AuthContext禁用环境和文件fallback。
私有事件为`auth.prompt:{promptId,kind}`（secret/manual_code）、`auth.prompt_cancelled:{promptId}`和`auth.url:{url}`；
输入为`auth.response:{promptId,value}`。promptId从1递增、最多16，同一时刻只允许一个；SDK回调抢先完成时取消对应提示，
允许识别并丢弃该已取消提示的一次迟到输入，不能作用于后续提示或别的操作。
不转发SDK提示正文、progress/info或device_code。Codex只选择公开交互选项browser，不增加设备登录入口；
浏览器链接只允许官方HTTPS授权路径与localhost:1455/auth/callback回调，最终成功不依据浏览器回调页面。
`auth.result:{providerId,authType,status:stored}`只能在SDK登录与宿主持久ACK成功后产生，再由外层唯一终态/EOF/exit确认。
stored不表示账号、订阅或模型访问已验证，也不直接激活Java选中路由。

| 场景 / Feature | 参考路径/符号 / 证据 | 可观察机制及不显示项 | 独立契约/验收边界 |
| --- | --- | --- | --- |
| API Key输入 / MODEL-13 | Pi0.85.1公开AuthInteraction.prompt与Models.login，源码Observed；真实SDK合成输入已测 | secret输入后经CredentialStore.modify保存；不把Key作为对话正文 | auth.prompt只携带kind/id；生产Worker三家合成Key及取消/CAS冲突5项真实进程测试通过，尚非CLI/TUI交付 |
| Codex浏览器登录 / MODEL-13 | Pi0.85.1 dist/auth/oauth/openai-codex.js openaiCodexOAuth.login→loginOpenAICodex，源码Observed | browser选择、auth_url、manual_code与回调竞争；页面成功不等于token交换完成 | 固定browser；只允许manual_code、不增加密码提示；隐藏SDK原文/设备码，按prompt signal取消，结果flush阶段只消费一次对应迟到输入；Fake已测，无真实账号/视觉验收 |

#### Java登录协调器与共享服务接缝

新增一次性的`PiLoginOperation`应用边缘协调器，拥有认证连接、LOGIN RPC会话、提示handle与回复发送任务。
`run`持续receive；提示端口必须非阻塞返回包含异步SecretMaterial结果的PromptHandle，不能在接收线程等待Console或手工code。
提示取消按operationId/promptId关闭原handle；迟到材料无条件擦除，不转给后续提示。关闭handle必须确认不再接受新输入；
不能以Future.cancel或线程中断假装阻塞Console已停止。材料到达后在受控发送任务中处理，关闭后不得新发帧。
只有一个成功PUT ACK、匹配的stored、唯一completed、真实EOF/exit和清理确认齐备时才返回存储回执；ACK后断连不声称回滚。
协调器固定不设默认、不激活旧route，仍以输入前Pi索引generation和身份epoch校验结果。
清理失败保持失败状态，不能被将来的lease关闭流程吞掉；不承诺强制中断任意OS文件IO。

后续由`ProviderAuthRuntimeResources`装配、共享`ProviderAuthApplicationService`按明确backend委派；
Pi与legacy的profile/selection/lease必须携带backend与authMethod，版本区分PiAuthEpoch和LegacyGeneration，
不自动迁移旧文件，也不把Pi失败转成empty触发旧Provider fallback。两套TUI只扩展既有可擦除输入区与专用认证Bridge；
普通Agent stdio不得承载秘密。当前尚未完成这些共享服务/协议/UI改动。

共享服务切片新增显式Pi入口，而不把Pi identity传进legacy存储。异步登录最多一个，输入前捕获索引，
不设默认且不切换模型；列表仅展示CONFIGURED_UNVERIFIED元数据。独立helper后激活必须绑定回执epoch并
验证本地材料；注销共用16张/120秒/会话绑定票据及fence→确认清理→精确epoch删除→实际水位契约。
RuntimeResources负责活动登录的关闭，清理未确认保持槽位/fence。共享服务18项已由主任务复验，
包括三路真实Worker合成Key登录与槽位释放后外部close的最终回执竞态；CLI/TUI Bridge仍未接线。

租约键采用`spring-ai/API_KEY/provider/profile`与`pi/authMethod/provider/profile`两个明确命名空间；
旧入口把long封装为LegacyGeneration，Pi新入口只接受PiAuthEpoch，不接受materialRevision。
删除后的fence水位由`PiCredentialStore.deleteWithReceipt`直接返回实际事务generation，禁止删除后重读
snapshot冒充删除回执（其他身份可能已推进索引）。新增身份隔离、清理失败及删除水位测试尚待执行，不能算验收证据。
目录将使用公开describeCatalog投影的固定0.85.1资源，以便缺Node/凭据时仍能展示完整声明；运行组件可用性另行判断。

#### 单回合模型与续接契约（进入C前固定）

模型操作为`operation.start:{operation:model,providerId,modelId,request}`。request严格包含systemPrompt、messages、tools、options；
messages为user(text)、assistant(text/toolCalls及可选continuation)、toolResult(toolCallId/toolName/text/isError)，保留顺序；
tools为name/description/parameters(JSON Schema)，options仅maxTokens/temperature可选，不接受URL/header/key/代理或任意SDK选项。
工具JSON的原型同名键仍是普通数据，须用自有属性复制避免setter；判断续接一致性时对象键序不具语义，
但文本、数组/调用顺序与每个参数值必须匹配。Java网关保留既有附件/摘要/记忆/Skill不可信User信封，
不把这些内容提升为System；System消息按原序合并。一个网关仅一个活动操作，不同Run由Root分别构造。
四项Usage按Pi已报告分项口径合成输入/输出/总数并精确检查int溢出；全零保守视为未知，不宣称价格。
Java只接受无Provider frame的typed retry，RetryAfter按现有Domain上限裁到5分钟；Worker崩溃/协议错不盲重试。
Gateway通过Connection既有awaitExit确认实际EOF/退出/IO清理，再向内部Channel返回EOF；
不改变Connection.receive契约，也不把CLOSED异常伪装成成功。Usage必须是整数节点，禁止先转double后误接收舍入/下溢小数。
当前Java单回合/映射/真实进程91项通过，Worker Node全套238项通过；Run路由/摘要/工具循环整合仍未完成。

#### 同源摘要适配决策（独立Java设计，待验证）

现有SpringAiContextSummarizer直接要求ChatModel/Reactor，不能拿Pi网关假装ChatModel。
PiModelGateway实现ContextSummarizer，使用同一个实例的操作槽、身份、authEpoch绑定凭据工厂、
预算、取消和清理状态机；模型与摘要互斥，不创建Pi Agent、不另开legacy路由。
复用本项目已有cc-java-summary-request-v1 User信封语义：快照Base64、来源revision/IDs、
受保护锚点及字节/Token上限；固定摘要System说明不得提升快照权限，tools为空，
options.maxTokens显式采用摘要预算。摘要不伪造Session/Run ID，也不追加模型正文或隐藏续接到Transcript。
公开Pi 0.85.1 `dist/providers/openai-codex.js#buildRequestBody`不序列化该输出Token参数；
四路九请求loopback已明确验证这一缺口。Codex只具备请求预算意图、Java时间/字节限制及Core采纳Gate，
不能声明服务端原生Token上限；另三路验证实际HTTP字段。禁止补造不受支持字段、改API或fallback掩盖缺口。
有效正文按UTF-8字节及code point保守估算再次限额，只产生原tier/revision/IDs的SummaryCandidate，
仍交Core Adoption Gate。取消返回empty；有工具或超限的已完成候选拒绝；执行/协议/清理失败抛固定且无cause的摘要失败。
摘要与普通模型共用唯一传输执行路径，不自动重试；超出物理帧上限不放宽限制、不截断来源冒充成功。
可证伪测试须覆盖同实例互斥/复用、同凭据工厂、maxTokens/零工具、取消、错误脱敏、超限及close竞态。

#### 持久模型选择（独立格式扩展，待验证）

providers.v1.json 的旧默认仍只写providerId/modelId，保留spring-ai/API_KEY与旧Provider默认profile解释。
显式Pi默认使用严格五字段providerId/modelId/backend/authMethod/profileId，backend只能pi，profile显式绑定；
不能从同名legacy profile或环境推断。外层schemaVersion保持1；旧格式不迁移，旧程序不识别新Pi记录时拒绝读取，
不宣称N-1双向兼容。未知/混合字段、非法认证、目录外模型均拒绝。
共享服务新增显式selectPiModel(identity,modelId,setDefault,token)：仅校验目录及Pi元数据和fence，
不读秘密/ENV值、不激活租约、不变更PiStore默认、不启动Node。持久化采用DefinitionStore generation CAS，
成功后才更新下一Run的五字段选择，当前Run不可变。Pi默认缺账号、撤销或组件不可用均不能落回legacy。
legacy模型删除/overlay保护及默认标记按backend隔离；Pi默认的本地就绪仅表示元数据已配置未验证，不表示账号权益。

#### 显式模型作用域决策（已接线并定向复验，整体门槛仍OPEN）

沿用ADR-061/062已记录的独立子Context、创建边界模型覆盖及结构化取消机制；以下端口为本项目独立设计，
不宣称参考有同名类型。新增RunModelBinding（模型、摘要、捕获来源、可选完整选择、可选声明Context窗口）和
CapturedRunSource。RunScope通过可选binding保持旧Fake源码兼容，生产selected/fenced scope必须提供绑定，
失败也返回会明确抛错的绑定，不能以empty触发Pi→legacy fallback。模型/摘要使用绑定端口，不依赖线程继承。
旧同步调用facade仅保留普通ThreadLocal兼容，不作为生产Runtime或child的绑定方式。

来源只保存非秘密选择和已捕获认证版本；childSource直接打开selected工厂，不经过Root auth.beginRun互斥门。
入队时冻结来源，启动时检查原epoch并以原epoch登记新lease/RPC；logout/relogin后的旧排队任务必须拒绝，
不能改用新账号。父Run关闭只释放自身lease，不使已捕获来源借用关闭的父网关；Session关闭仍由Supervisor管控。
模型覆盖只允许同backend/provider目录内的显式模型。静态启动legacy属于明确兼容路径，不借它补救Pi失败。

Root的Context重绑共享已安装Projection及pending external context容器，但新建RunState；child重建全部Context状态，
不复制父缓存/待消费上下文，也不把child Usage事件错误送进父观察者。声明窗口只收窄现有配置：
maximumInput=min(父配置,目标声明)，输出保留/安全余量不削减；不足时拒绝，不扩大预算。
关闭或结束单个绑定不能清空其他绑定的状态。显式compact采用独立局部Guard及canonical消息入口，不伪造RunId；
宿主compact墙钟预算采用min(options.timeout,300秒)，必须向准备/摘要传递同一剩余预算和取消，不只设置HTTP timeout。

Runtime装配同时重绑模型、摘要和审批reviewer，ActiveRun指向实际绑定runtime。
子定义缺省模型使用显式Optional覆盖语义，旧字符串构造表示明确覆盖；这是独立格式扩展，不能把旧显式模型改成继承。
`providesRunBindings`仅描述内部端口契约，生产selected为true、fenced转发；声明true却缺binding时失败关闭。
它用于启动期选择captured目录/child工厂，不是账号就绪或Capability声明。旧静态Fake为false，保持旧构造兼容；
不能在Pi错误或父登记缺失时动态切换到该兼容路径。

`RunModelSourceRegistry`按真实Session/Run登记冻结来源和全新的child Context模板，`forModel`在入队前验证同provider覆盖，
`PreparedChildRuntime`使复用worker只消费已捕获来源。Root/child的真实initializer登记后代来源与精确取消；
Runtime四参入口在USER_PROMPT之前联结宿主启动取消，所有退出解除订阅。
child从打开模型scope起持有自己的墙钟取消源，覆盖后续workspace装配；排队及既有Start Hook仍遵循Supervisor契约。
默认选择的Definition/Pi/legacy元数据读取也接收启动token，取消不得变成empty、CORRUPT_STORE或legacy fallback。
启动token的正剩余预算必须收窄Runtime实际AgentLimits，并保留用户消息、次数限制与explicitSkill；
不能只转发取消通知而重置成默认五分钟。已耗尽的输入按启动取消收口，零模型调用；
Hook/初始化后的deadline任务只等待当前剩余时长，不重新获得完整期限。此为独立预算契约，原取消/超时首胜规则不变。

主任务定向复验为20 suites/215 tests/1 skip，零失败/错误；CLI及上游严格Javadoc通过。
原线程复用失败测试保持不改并通过。Fake驱动真实child read_file Pipeline及Core摘要采纳，不代表四路Node编码、完整公开入口或在线验收。
精确参考调用链已完成只读补核（下表）；完整Root→delegate→child回流、清理可观察性与预算边界等仍需完成，不能以这些切片通过宣布离线候选完成。

##### 显式作用域参考补核（2026-09-13）

基线仍为AUTH-SRC-2026-07-29-A，Revision/再发布权Unknown；下列路径相对授权目录`src/`。
分类均为Source Observed（静态源码），未运行参考UI，不构成视觉或在线一致性证据。

| 场景 / Feature | 参考路径与符号 | 抽象机制 / 不应推定的行为 | 本项目采用 / 必要偏差 / 证伪状态 |
| --- | --- | --- | --- |
| 子模型继承与覆盖 / MODEL-13、SUB-03 | `tools/AgentTool/AgentTool.tsx#call` → `utils/model/agent.ts#getAgentModel`；`tools/AgentTool/runAgent.ts#runAgent` | 创建前筛选许可类型，模型解析包含环境、调用与定义覆盖及父模型模式；创建时再次解析。不能据此声称参考在入队时冻结认证版本 | 本项目只接受可信捕获来源及同provider精确模型，不引入环境模型覆盖；入队冻结是有意偏差。继承/覆盖、父结束后启动及重登epoch拒绝已有Fake组合，完整排队切模回流仍待测 |
| 独立Context、取消与后台终态 / CTX-15、SUB-04/05/07 | `runAgent.ts#runAgent` → `utils/forkedAgent.ts#createSubagentContext` → `query.ts#query`；`tools/AgentTool/agentToolUtils.ts#runAsyncAgentLifecycle` | 子Context另建；同步路径可共享父取消controller，异步路径使用独立controller；先形成终态再清理与通知。参考也使用异步局部存储传播归属，不能概括为完全没有隐式上下文 | 本项目显式gateway/source，child独占Context及局部取消源并保留父取消链；不照搬所有资源共享规则。Core入队/取消与child组合已测；清理失败可观察性仍待收口 |
| 压缩模型与投影 / CTX-07/09 | `commands/compact/compact.ts#call` → `services/compact/compact.ts#compactConversation` / `streamCompactSummary`；缓存路径经`utils/forkedAgent.ts#runForkedAgent` → `query` | 先处理压缩边界后消息；缓存路径使用Context内模型，回退路径显式采用当前主模型并调整附件，两路传递压缩取消；错误消息不能作为有效摘要 | 本项目canonical入口、局部Guard与单一已绑定摘要端口，不采用缓存分叉/参考回退算法，更不允许Pi→legacy回退。Fake同源采纳/取消/stale已测；完整四路Node编码与恢复仍待测 |

参考是否冻结账号epoch、跨登录后来源能否继续存活仍Unknown。authEpoch、lease、空模板登记及不重查当前账号是本项目安全契约，不是参考已有的同名协议。

保留ADR-061规定的运行终态与CLEANING/RELEASED分离；不采纳把所有终态延迟到慢清理后的整体状态机改写。
清理失败仍必须可观察且不能伪报RELEASED或排空，模型本身的EOF/exit/cleanup失败仍在模型结果交付前拒绝。

独立清理通路采用本项目`ResourceCleanupStatus`：UNKNOWN、NOT_STARTED、CLEANING、RELEASED、UNCONFIRMED。
它描述声明的owned资源，不表示OS Sandbox、任意后代已结束或worktree被删除。Lease新增独立失败位，
不能把尚在关闭与关闭异常都视为resourceFinished=false而丢失区别；只有terminal确认才为RELEASED，原fence条件不变。
RunScope及child装配显式提供清理证据；旧构造无证据默认UNKNOWN，close正常返回本身不足以确认模型资源释放。
FileSessionStore保留原best-effort关闭行为，但独立累积关闭IO失败的sticky回执；清空map或吞异常返回不能当成功。
child装配同时核对模型与Session Store回执，并汇总其他owned资源操作异常，不改变Session恢复/Writer安全策略。
Supervisor保留运行终态先行：运行结束时标CLEANING并发布终态，然后关闭scope并冻结独立清理快照；
装配失败或关闭后仍pending/未知时保守UNCONFIRMED，不再持有完整scope/Runtime跟踪迟到成功，不二次改写运行终态。
真正尚未取得runtime资源的排队取消可确认无scope资源；不会由SUCCEEDED推断释放。
inspect/await、既有任务协议和详情携带该状态，terminal通知只是当时快照，后续inspect取得更新；不新增通知框架或弹窗。
持久journal只记录原终态时的清理快照，不在terminal后追加清理事件，旧记录/恢复未证实的状态为UNKNOWN。
这些字段是独立诊断设计，不能描述成参考已有协议；具体回归及跨层验收仍待完成。

公开认证适配采用以下独立接线契约（不重新设计Pi Store/RPC）：
- 应用服务`loginPiWithReceipt`返回安全summary与PiLoginOperation原始精确receipt；旧loginPi继续返回summary。
  ENV薄入口`loginPiEnvironment`只保存ENV_REF名称，不读值、不启动Node，OAuth不得使用；共用登录槽、CAS、关闭与最终回执Gate。
- 私有双向桥与Agent普通stdio分离，固定五字段version/operationId/sequence/type/payload；每方向sequence从0递增，
  UTF-8/LF、重复键拒绝，32KiB/frame、512帧及128KiB累计输入输出预算，拥有的输入缓冲擦除。
  外层operationId来自可信启动器，内部Worker operationId不透传；auth.prompt含promptId/kind，auth.prompt_cancelled含promptId，
  auth.url含已校验url，输入auth.response含promptId/value，auth.cancel仅为空payload；不透传credential.*。
  登录协调器返回后发auth.input_stop空payload，客户端停止输入、擦除待发材料并关闭stdin；helper必须有界确认输入EOF及自身资源关闭，
  才发auth.stored。此私有停止握手解决无控制台阻塞读取的归属，不依赖Thread.interrupt假装stdin已关闭；不属于用户可见新工作流。
- 终态auth.stored仅在保存与清理确认后发送，含backend/providerId/profileId/authMethod/authEpoch；authEpoch用正十进制字符串传输，
  不经JS Number，避免长期Store代次精度损失。失败仅封闭分类，不输出原异常/Worker诊断或材料；无回执不自动重试或虚构回滚。
  外层TUI shutdown也须等待私有登录取消结算，不能仅调用cancel就提前结束；清理仍未知时关闭主宿主但报告失败。
  普通请求的无Session protocol.error可按唯一requestId解除本请求pending；显式错误Session或无关requestId不能解除。
- 普通provider控制显式backend=pi/authMethod=API_KEY或OAUTH；省略backend保留旧兼容语义，不按provider名猜backend。
  auth.activate携带精确authEpoch字符串，profiles/list不含epoch/ENV名/accountId/url；logout仍按会话绑定一次性票据提交。
  私有helper保存不能自动激活主进程，Pi TUI需显式确认激活，下一Run模型选择仍是独立步骤；不改旧路线已批准行为。
- helper的唯一可选ENV参数为`--environment-name`，只允许API_KEY的合法引用名称，绝不携带值；
  ENV路径不启动Node Worker，仍需相同input_stop/EOF/关闭回执，且不应收到prompt/url。
- 公开CLI显式`--backend pi`，身份操作携带auth-method，不支持的Pi修改/probe拒绝而不落入legacy。
  ENV直接调用共享服务；TTY或API Key stdin登录通过受信`codej.piAuthCli`编译脚本复用同一私有桥。
  父Java只等待，Node壳独占并还原raw输入；stdin方式等helper提示（CAS已捕获）后才读取材料。
  脚本参数只含Java路径/受信JVM参数JSON的Base64url封装/cwd/身份及stdin模式，不含秘密；OAuth非TTY拒绝。
  私有字段为`--java-args-base64`，解码必须规范Base64url、严格UTF-8及有界字符串数组；Base64不是加密。
  真实Windows入口已复现直接JSON argv的引号消失与参数拆分（应13项实际24项），因此不以Fake Runner证明进程参数可用。
  CLI保存不自动选择默认模型，TUI启用仍是显式步骤。该薄壳不是新的认证权威，也不声称任意后代清理。
  两入口的helper环境保留宿主选定的HTTP_PROXY/HTTPS_PROXY/NO_PROXY及最小OS白名单，不能丢弃代理变成直连；
  显式spec.env存在时不合并ambient环境，且不读取或转交Key/Node注入变量。CLI桥工作期限290秒，早于父Java298秒，
  为正常停止/TTY还原预留余量；OS强杀或调度失控仍不能保证TTY恢复，不能声称任意外部终止都被覆盖。
- 两个生产入口是src/index.tsx的默认AgentTui与--tui-next ExperienceRuntimeApp；experience/main仅演示，不能代替验收。
  发行复用既有Node并安装受信Pi Worker模块闭包和锁定生产依赖，显式传绝对配置；启动不安装、不搜索环境凭证或fallback。
授权页布局补充对照：公开Pi 0.85.1 `dist/modes/interactive/components/login-dialog.js#showAuth`
显示OSC8授权链接并调用浏览器打开；`showManualInput/showPrompt`保留输入区。分类为公开源码Observed，未运行参考UI。
本项目采用经固定origin/path/控制字符校验的短标签OSC8链接，置于认证面板高度预算内，不把完整长URL追加到整屏之外；
真实交互 TTY 还会通过无 shell 的系统 opener（Windows `rundll32.exe url.dll,FileProtocolHandler`、macOS `open`、Linux `xdg-open`）自动打开同一 URL。
非 TTY/离线渲染不产生外部窗口；自动打开失败时保留受控 OSC8 与 Pi `manual_code` 回退输入。这是宿主适配，
不是把浏览器行为放进 Java/Pi Worker，也不改变回调竞争、取消或秘密边界。
不支持OSC8的终端或实际在线浏览器授权仍未验证，不声称视觉一致；秘密输入/取消/终态不因此移动或被挤出。

上述桥及公开入口当前尚未完成验证，后续证据须区分Fake、真实Node、协议与实际TUI终态。

Worker只发operation.failed/TIMEOUT而无model.error时，Java必须保留REQUEST_TIMEOUT诊断，不能误归INVALID_RESPONSE。
已有内容fence仍优先INCOMPLETE_STREAM，仍禁止据此新增重试；EOF/退出/清理确认条件不变。
此诊断缺陷已由独立Fake证伪，与历史Qwen一次MODEL_ERROR的因果关系仍为Unknown。
单回合调用公开Models.streamSimple，固定SSE与maxRetries=0，由Java决定重试与工具执行。
私有输出为`model.frame:{}`（实际Provider内容/工具意图开始的保守fence）、`model.delta:{text}`、
`model.result:{text,toolCalls,usage,continuation}`或`model.error:{code,retryable,providerFrame,retryAfterMs?}`。
toolCalls为id/name/arguments，usage只含input/output/cacheRead/cacheWrite非负计数，不把Pi估价当本项目已验证价格。
error只用AUTH/CANCELLED/TIMEOUT/RATE_LIMIT/TRANSIENT/CONTEXT_OVERFLOW/INCOMPLETE/PROTOCOL/PERMANENT/UNSUPPORTED/LIMIT，
不转发SDK错误正文；Retry-After仅有限数值，已有Provider frame后不重试。不把HTTP状态头等同文本增量。
上下文溢出只认有界解析后的已知结构化错误码，不能用含用户文本的任意错误文案判断。
结果之后仍须唯一终态及EOF/exit；length、error、aborted、deferred、空输出或没有done的流均不充当正常完成。

Domain新增可选ModelContinuation(backend/providerId/modelId/payload)，payload为至多1MiB的不可解释私有序列化数据，
不投影为system/runtimeMetadata、正文或toString。AssistantMessage保留两参数兼容构造，新增可选continuation；
SessionJournal→JsonlSessionCodec→restore/fork必须保持，旧记录缺失按empty读取，不改变旧资料。
Pi payload仅保存已完成Assistant的api/content/stopReason/timestamp，以及续接所需responseId/providerThinkingLevel（可选）；
content保留text/textSignature、thinking/thinkingSignature/redacted、toolCall/id/name/arguments/thoughtSignature/namespace的顺序与白名单字段。
SDK重建所需usage从可用计数补充，不保存diagnostics/errorMessage/headers/凭证。相同route/model且可见text/toolCalls完全一致才复用；
跨route/model或投影已改变可见内容时丢弃私有部分并从可见内容重建，不能携带不可移植签名或重放旧工具。
不因存储续接元数据改变AgentMessage.isEmpty、权限、Plan交付Gate或已有Capability等级。

### 3.3 私有帧基础（Batch A）

新Worker帧固定为`{version:1, operationId, sequence, type, payload}`五字段；两个方向各自从sequence=0递增，
operationId为宿主生成的1–96位ASCII字母/数字/下划线/横线标识。type为最多64位的小写点分/下划线分段标识，
payload必须为对象。未知字段、重复键（包括Unicode转义后的同名键）、无效UTF-8/未配对surrogate、深度>64、
非有限或不安全整数、错版本/错操作/跳号及终态后的数据均拒绝。最多65,536帧/操作，认证上层继续取更严上限。
错误只返回PROTOCOL_INVALID/LIMIT/CLOSED等封闭码，不记录输入或底层异常。帧结束必须有LF，EOF不补全末行。
这是独立协议，不修改已有OpenRouter login.mjs私有v1的兼容语义；业务操作和秘密事务另加白名单，不以通用payload开放任意调用。

## 4. 实施与验收序列

A. 固定目录/协议与失败Fake → B. Java凭证事务/Worker生命周期 → C. OpenAI API单回合与Java工具闭环 → D. Codex OAuth/刷新/续接 → E. DeepSeek与通义 → F. 两套TUI/CLI/摘要/子Agent/Session/发行接入 → G. 两次标准clean verify、专项、六尺寸PTY、严格Javadoc、发行目录检查和看板。

每批状态和未通过项记录在`docs/evidence/S15-pi-provider-runtime.md`。第一门槛必须覆盖实际Java+Node+Pi Faux/loopback，而不只是静态目录；第二门槛分别覆盖四路由真实调用、Codex网页到最终正文/退出重登录及三家Coding Loop。没有真实刷新证据就仍标为未验证。

开发使用现有外部Node≥22.19；仓库已有自包含发行包携带Node，继续复用其运行时，不为本批移除已发布能力或自动下载新运行时。生产依赖通过lockfile在显式准备/构建时安装，正常启动只诊断，不偷偷联网安装。未提交构建产物，不执行发布。

### 安装入口的 Context 预算不相容恢复

安装默认256000/8192/4096开启Context，而直接Java未提供三参数时是no-op。静态OpenAI首项gpt-4仅8192，
小于输出保留与余量之和12288；真实安装ConPTY已复现保存/启用/选模型后、模型请求前的run.launch.failed。
不能缩减保留空间、跳过Context或悄悄换模型来让验收变绿。独立设计是在原拒绝点抛出无动态数据的
`ModelContextBudgetException`，仅在Run尚未开始时投影封闭code `MODEL_CONTEXT_BUDGET_INCOMPATIBLE`；
两视图说明选择更大窗口模型或显式调整Context参数。其他启动失败继续原契约，错误不回显源码/输入/异常文案。
验收必须保留原gpt-4失败→明确提示→显式重选gpt-4-turbo→同一任务读工具/交付/下一轮，而非只测邻近成功模型。
此提示是本项目预算契约的必要可恢复性，不声称参考已有相同协议；现有参考机制研究和不削减保留空间边界不变。

### 安装验收的凭据隔离

`TestBuildRelease` 的真实stdio smoke不得使用维护者的认证/Session目录。仅改HOME/USERPROFILE不足以改变本机JDK的user.home，
已以无配置读取的`-version`属性探针核对。初次CODEJ_JAVA适配器被codej.cmd覆盖，隔离失效，该次不计隔离证据；
后续临时JAVA_HOME/bin/java.exe适配器又遇到Node spawnSync EPERM，原因Unknown，不更改防护或命名规避来消除该现象。

最终TestBuildRelease保留实际codej.cmd→包内launcher→真实Java；仅在该测试子进程环境设置临时Node预加载，
在启动器spawnSync边界给CcJavaCliMain补入`-Duser.home`，不替换执行文件/JAR/协议，不注入Java agent。
必须同时有真实调用回执与临时home下唯一Session文件，原exit0、唯一initialized、零stderr及15秒断言保持。
测试预加载与NODE_OPTIONS不进入生产配置或发行物，不读取维护者配置；实际Pi Worker继续使用其最小环境。

ConPTY另用`IsolatedJavaTestLauncher.cs`的独立命名适配器，经Node启动器既有CODEJ_JAVA缝隙追加临时home，
并仅替换受控loopback Worker路径；SDK和operations来自安装包。每例先证明实际JVM home，再启动UI。
该C#适配器不作为codej.cmd smoke隔离证据，也不进入发行物或增加生产依赖。

## 5. 当前证据

已完成目录、认证/模型适配、Root/child/摘要、统一CLI/两视图及安装Pi闭包的受控离线复验。
当前CLI模块1123 tests/3 skips、TUI680、Node238，安装ConPTY22例与源码当前入口12例通过；窗口原失败后的显式重选交付已覆盖。
预算恢复修复前两次standard clean各2193 tests/13 skips通过；修复后最后两次仍在WorkspaceSnapshotTest的Git启动OS错误5失败，
因此完整新实现G3/G4尚未通过，不能引用旧全量结果消除此阻塞。ADR099的1387 tests等历史证据不覆盖本ADR新功能。
MODEL-13保持L1，S15 Exit OPEN；Codex真实OAuth/在线权益、物理UI和Node22等差距继续保留。
