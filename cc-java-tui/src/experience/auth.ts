import {PiAuthFlow} from './pi-auth.js';
import type {PiAuthIdentity, PiAuthCallbacks, PiAuthLoginOptions, PiAuthResult} from '../pi-auth-bridge.js';
import type {ProtocolEvent} from '../protocol.js';
import type {ProviderLoginRequest, ProviderLoginResult} from '../stdio-client.js';
import {parseSlashCommand} from '../slash-command.js';
import {beginModelSetup, editModelSetup, moveModelSetup, enterModelSetup, type ModelSetupState} from '../model-setup.js';

export type AuthIntent = 'providers.catalog' | 'providers.configure' | 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'auth.activate' | 'auth.logout.prepare' | 'auth.logout.commit' | 'models.list' | 'models.use' | 'models.add' | 'models.remove';
export interface AuthClient {
  piLogin?(identity: PiAuthIdentity, callbacks: PiAuthCallbacks, options?: PiAuthLoginOptions): Promise<PiAuthResult>;
  piSubmit?(promptId: number, bytes: Uint8Array): boolean;
  piCancel?(): void;
  providerControl?(controlId: string, intent: AuthIntent, args: Readonly<Record<string, unknown>>): string;
  providerLogin?(request: ProviderLoginRequest & {authMethod?: 'openrouter-browser'}): Promise<ProviderLoginResult>;
  cancelProviderLogin?(): void;
}
export interface AuthChoice {label: string; value: string}
export interface AuthPanel {
  operation: number; phase: 'wait' | 'providers' | 'profiles' | 'source' | 'secret' | 'env' | 'form' | 'login' | 'models' | 'confirm' | 'done' | 'error';
  title: string; message: string; choices: AuthChoice[]; focus: number;
  providerId: string; profileId: string; environmentName: string; form?: ModelSetupState;
  /** 仅非秘密长度；原文及字节不得进入面板或快照。 */
  secretByteCount?: number;
  backend?: 'pi' | 'spring-ai'; authMethod?: 'API_KEY' | 'OAUTH';
  promptId?: number | undefined; promptKind?: 'secret' | 'manual_code' | undefined;
}
const id = (v: unknown): v is string => typeof v === 'string' && /^[a-z0-9][a-z0-9-]{0,62}$/.test(v);
const model = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= 256 && !/[\u0000-\u001f\u007f]/.test(v);
const records = (v: unknown): Record<string, unknown>[] => Array.isArray(v) ? v.filter(x => x && typeof x === 'object').slice(0, 256) : [];
export const isAuthCommand = (v: string): boolean => /^\/(login|logout|connect|auth|models)(?:\s|$)/.test(v.trim());

/** ADR-099：只投影 Java 本地目录并发送控制意图。凭证及删除权威不在 UI；每个异步结果绑定操作和会话。 */
export class ExperienceAuth {
  panel: AuthPanel | undefined;
  enabled = false; browser = false; required = false; pi = false;
  readonly #pi: PiAuthFlow;
  get authorizationUrl(): string {return this.#pi.authorizationUrl;}
  #operation = 0; #serial = 0; #session = ''; #logout = false; #saved = false; #activated = false; #logoutCompleted = false;
  #loginBytes: Uint8Array | undefined;
  #models: {providerId: string; modelId: string}[] = [];
  #profiles: {providerId: string; profileId: string; source: string}[] = [];
  #pending: {request: string; control: string; intent: AuthIntent; operation: number; accept: (r: Record<string, unknown>) => void} | undefined;
  constructor(readonly client: AuthClient, readonly publish: (panel: AuthPanel | undefined, notice?: string, model?: string) => void, readonly compatibilityLogin?: () => void) {
    this.#pi = new PiAuthFlow(client, (panel, notice, model) => {this.panel = panel; if (model) this.required = false; publish(panel, notice, model);}, logout => {
      if (!logout && this.compatibilityLogin) {this.panel = undefined; this.publish(undefined); this.compatibilityLogin(); return;}
      const pi = this.pi; this.pi = false; this.open(logout ? '/logout' : '/login'); this.pi = pi;
    });
  }
  initialize(session: string, payload: Readonly<Record<string, unknown>>): void {
    this.invalidate(); this.publish(undefined); this.#session = session; this.enabled = payload.authLifecycleV1 === true && !!this.client.providerControl;
    this.pi = this.enabled && payload.piProviderV1 === true;
    this.browser = payload.openRouterBrowserAuthV1 === true; this.required = payload.modelConfigured === false;
    if (this.required) this.open('/login');
  }
  private update(patch: Partial<AuthPanel>): void {if (this.panel) {this.panel = {...this.panel, ...patch}; this.publish(this.panel);}}
  private choices(phase: AuthPanel['phase'], title: string, choices: AuthChoice[], message = ''): void {
    this.update({phase, title, choices, message, focus: 0});
  }
  private error(message: string): void {this.choices('error', '认证操作未完成', [], message);}
  private send(intent: AuthIntent, args: Record<string, unknown>, accept: (r: Record<string, unknown>) => void): void {
    const operation = this.#operation, control = `experience-auth:${operation}:${++this.#serial}`;
    this.update({phase: 'wait', choices: [], message: '正在处理本机配置…'});
    try {const request = this.client.providerControl!(control, intent, args); this.#pending = {request, control, intent, operation, accept};}
    catch {this.error(this.#logoutCompleted ? '本机退出已完成，但列表刷新未完成。'
      : this.#activated ? '凭证已保存并启用，但后续模型选择未完成。'
      : this.#saved ? '凭证已保存，但后续激活未完成。请重新核对。' : '请求未能发送，请检查宿主连接。');}
  }
  accept(event: ProtocolEvent): boolean {
    if (this.#pi.accept(event)) return true;
    const p = this.#pending;
    if (!p || event.requestId !== p.request || p.operation !== this.#operation || event.runId
      || (event.sessionId !== undefined && event.sessionId !== this.#session)
      || (event.type !== 'protocol.error' && event.sessionId !== this.#session)) return false;
    if (event.type === 'protocol.error') {this.#pending = undefined; this.error('宿主拒绝当前认证操作。'); return true;}
    if (event.type !== 'provider.control.result' || event.payload.controlId !== p.control || event.payload.intent !== p.intent) return false;
    this.#pending = undefined;
    if (event.payload.status !== 'succeeded') {
      this.error(p.intent === 'auth.activate' ? '凭证已保存，但未启用。请核对后重新登录。'
        : p.intent === 'models.use' ? '凭证已保存并启用，但模型选择失败。'
        : p.intent === 'auth.logout.commit' ? '退出未确认成功，结果待核对；不会自动重发。'
        : this.#logoutCompleted ? '本机退出已完成，但列表刷新失败。'
        : this.#activated ? '凭证已保存并启用，但模型列表读取失败。'
        : this.#saved ? '凭证已保存，但后续操作失败。'
        : '本机配置操作失败；没有自动重试。');
    } else p.accept(event.payload.result as Record<string, unknown>);
    return true;
  }
  open(command: string): boolean {
    if (!this.enabled) {
      const message = '当前宿主不支持安全认证生命周期，请升级后重启。';
      if (this.required) {this.invalidate(); this.panel = {operation: this.#operation, phase: 'error', title: '需要配置模型', message, choices: [], focus: 0, providerId: '', profileId: '', environmentName: ''};}
      this.publish(this.panel, this.panel ? '' : message); return false;
    }
    if (this.pi && ['/login', '/logout'].includes(command.trim())) {this.invalidate(); this.#pi.open(this.#session, command.trim() === '/logout'); return true;}
    const words = command.trim().split(/\s+/); let provider = '', profile = ''; let env = ''; let advanced: {intent: AuthIntent; args: Record<string, unknown>} | undefined;
    this.#logout = words[0] === '/logout';
    if (words[0] === '/login' || words[0] === '/logout') {
      if (words.length > (this.#logout ? 1 : 3) || words.slice(1).some(v => !id(v))) {this.publish(undefined, '用法：/login [provider [profile]] 或 /logout'); return false;}
      provider = words[1] ?? ''; profile = words[2] ?? '';
    } else {
      const parsed = parseSlashCommand(command);
      if (parsed.kind !== 'provider-control') {this.publish(undefined, '高级认证命令参数无效。'); return false;}
      const a = parsed.command.arguments;
      if (parsed.command.intent === 'connect') {provider = typeof a.providerId === 'string' ? a.providerId : ''; profile = typeof a.profileId === 'string' ? a.profileId : ''; env = typeof a.environmentName === 'string' ? a.environmentName : '';}
      else if (parsed.command.intent === 'auth' && a.action === 'logout') {this.#logout = true; provider = String(a.providerId); profile = String(a.profileId);}
      else {const {action, ...args} = a; advanced = {intent: `${parsed.command.intent}.${action}` as AuthIntent, args};}
    }
    this.invalidate(); this.#saved = false; this.#activated = false; this.#logoutCompleted = false;
    this.panel = {operation: this.#operation, phase: 'wait', title: this.#logout ? '退出本机账号' : '登录服务商', message: '', choices: [], focus: 0, providerId: provider, profileId: profile, environmentName: env};
    this.publish(this.panel, '');
    if (advanced) {const op = advanced; this.send(op.intent, op.args, r => {
      if (op.intent === 'models.list') {this.readModels(r); this.choices('done', '本机模型（只读）', this.#models.map(m => ({value: m.modelId, label: m.providerId + ' / ' + m.modelId})), 'Enter 返回；切换可使用 /models use。');}
      else if (op.intent === 'auth.list') {this.readProfiles(r); this.choices('done', '本机 Profile（只读）', this.#profiles.map(p => ({value: p.profileId, label: p.providerId + ' / ' + p.profileId + ' · ' + p.source})), 'Enter 返回；退出请使用 /logout。');}
      else {if (op.intent === 'models.use' && model(op.args.modelId)) {this.required = false; this.publish(this.panel, '', op.args.modelId);} this.choices('done', '操作已完成', [], '本机控制请求已完成。');}
    }); return true;}
    this.send('models.list', {}, r => {this.readModels(r); this.send('auth.list', {}, list => {
      this.readProfiles(list);
      if (this.#logout) this.showLogout();
      else if (provider) {if (![...this.#models, ...this.#profiles].some(x => x.providerId === provider)) this.error('Provider 不在本机目录，请使用自定义配置。'); else if (profile && env) this.login('env'); else if (profile) this.source(); else this.profiles();}
      else this.choices('providers', '选择服务商', [...new Set([...this.#models, ...this.#profiles].map(x => x.providerId))].map(value => ({value, label: value})).concat({value: '+', label: '添加自定义 HTTPS 服务商和模型'}));
    });});
    return true;
  }
  private readModels(r: Record<string, unknown>): void {this.#models = records(r.models).flatMap(x => (x.backend === undefined || x.backend === 'spring-ai') && id(x.providerId) && model(x.modelId) ? [{providerId: x.providerId, modelId: x.modelId}] : []);}
  private readProfiles(r: Record<string, unknown>): void {this.#profiles = records(r.profiles).flatMap(x => (x.backend === undefined || x.backend === 'spring-ai') && id(x.providerId) && id(x.profileId) ? [{providerId: x.providerId, profileId: x.profileId, source: x.refKind === 'ENV' ? 'ENV（仅本地引用）' : 'STORE'}] : []);}
  private profiles(): void {
    const p = this.panel!; const values = [...new Set(['default', ...this.#profiles.filter(x => x.providerId === p.providerId).map(x => x.profileId)])];
    this.choices('profiles', '选择 Profile', values.map(value => ({value, label: value === 'default' ? 'default（普通登录）' : value})), '高级 Profile 可通过 /login provider profile 显式指定。');
  }
  private source(): void {
    this.choices('source', '选择认证方式', [{value: 'stdin', label: 'API Key · 遮蔽输入'}, {value: 'env', label: 'ENV · 仅输入环境变量名称'},
      ...(this.browser && this.panel?.providerId === 'openrouter' ? [{value: 'browser', label: 'OpenRouter · 浏览器授权'}] : [])]);
  }
  private showLogout(): void {
    const p = this.panel!;
    const profiles = this.#profiles.filter(x => (!p.providerId || x.providerId === p.providerId) && (!p.profileId || x.profileId === p.profileId));
    this.choices('profiles', '选择明确的退出目标', profiles.map(x => ({value: x.providerId + '/' + x.profileId, label: 'spring-ai / API_KEY / ' + x.providerId + ' / ' + x.profileId + ' · ' + x.source})), profiles.length ? '仅移除选中凭证或 ENV 引用；不关闭 Session，不远端撤销。' : '没有匹配的本机 Profile。');
  }
  move(delta: number): void {if (this.#pi.panel) {this.#pi.move(delta); return;} const p = this.panel; if (!p) return; if (p.phase === 'form' && p.form) this.update({form: moveModelSetup(p.form, p.form.phase === 'form' && p.form.field === 'baseUrl' ? 'modelId' : 'baseUrl')}); else if (p.choices.length) this.update({focus: (p.focus + delta + p.choices.length) % p.choices.length});}
  input(value: string, backspace = false): void {
    if (this.#pi.panel) {this.#pi.input(value, backspace); return;}
    const p = this.panel; if (!p) return;
    if (p.phase === 'form' && p.form) this.update({form: editModelSetup(p.form, backspace ? {kind: 'backspace'} : {kind: 'append', text: value})});
    // 名称严格逐字校验，绝不接受值、等号或粘贴的 shell 赋值。
    if (p.phase === 'env') {const next = backspace ? p.environmentName.slice(0, -1) : p.environmentName + value; if (/^(?:[A-Z][A-Z0-9_]{0,127})?$/.test(next)) this.update({environmentName: next});}
  }
  enter(): void {
    if (this.#pi.panel) {this.#pi.enter(); return;}
    const p = this.panel; if (!p) return; const choice = p.choices[p.focus]?.value;
    if (p.phase === 'done' || p.phase === 'error') {this.cancel(); return;}
    if (p.phase === 'providers' && choice) {
      if (choice === '+') this.update({phase: 'form', title: '自定义 HTTPS 服务商', choices: [], form: beginModelSetup(this.#operation, this.required)});
      else {this.update({providerId: choice}); this.profiles();}
    } else if (p.phase === 'profiles' && choice) {
      if (this.#logout) {const [providerId, profileId] = choice.split('/'); this.update({providerId: providerId!, profileId: profileId!});
        this.send('auth.logout.prepare', {providerId, profileId}, r => {
          if (r.providerId !== providerId || r.profileId !== profileId || typeof r.confirmationId !== 'string') {this.error('退出确认目标不一致；未提交删除。'); return;}
          this.choices('confirm', '确认退出 ' + providerId + ' / ' + profileId, [{value: 'cancel', label: '取消，保留凭证'}, {value: r.confirmationId, label: '确认移除这个本机 Profile'}], '不会远端 revoke，不影响其他 Profile 或 Session。');
        });
      } else {this.update({profileId: choice}); this.source();}
    } else if (p.phase === 'source' && choice) {
      if (choice === 'env' && !p.environmentName) this.update({phase: 'env', choices: [], message: '只填写 ENV_NAME；不要输入 API Key 或值。'});
      else if (choice === 'stdin') this.update({phase: 'secret', choices: [], secretByteCount: 0, message: '输入 API Key（不显示原文）；仅接受单行 ASCII，最多 16 KiB。'});
      else this.login(choice === 'env' ? 'env' : 'store', choice === 'browser');
    } else if (p.phase === 'env' && /^[A-Z][A-Z0-9_]{0,127}$/.test(p.environmentName)) this.login('env');
    else if (p.phase === 'form' && p.form) {
      const action = enterModelSetup(p.form); this.update({form: action.state});
      if (action.kind === 'control') {
        // Tab 可移动焦点，但不能绕过 URL 校验；统一复用 model-setup 的两阶段校验。
        const checked = enterModelSetup({...action.state, phase: 'form', field: 'baseUrl'});
        if (checked.kind === 'state' && checked.state.validation) {this.update({form: checked.state}); return;}
        this.send(action.intent, {...action.arguments}, r => {
        if (!id(r.providerId) || r.modelId !== action.state.modelId) {this.error('配置响应无效，请核对已保存配置。'); return;}
        this.update({providerId: r.providerId, profileId: 'default'}); this.source();
        });
      }
    } else if (p.phase === 'models' && choice) {
      this.send('models.use', {providerId: p.providerId, profileId: p.profileId, modelId: choice, setDefault: true}, r => {
        if (r.providerId !== p.providerId || r.profileId !== p.profileId || r.modelId !== choice || r.setDefault !== true) {this.error('凭证已保存并启用，但默认模型结果不一致，请核对。'); return;}
        this.required = false; this.invalidate(); this.publish(undefined, '凭证已保存并启用，默认模型已选择。', choice);
      });
    } else if (p.phase === 'confirm' && choice) {
      if (choice === 'cancel') {this.cancel(); return;}
      this.send('auth.logout.commit', {confirmationId: choice, confirmed: true}, r => {
        if (r.providerId !== p.providerId || r.profileId !== p.profileId || r.remoteRevoked !== false) {this.error('退出结果待核对；不会自动重发。'); return;}
        this.#logoutCompleted = true;
        this.send('auth.list', {}, list => {this.readProfiles(list); this.choices('done', '本机退出已完成', [], '未远端撤销。剩余 Profile：' + this.#profiles.length + '。Session 保持打开。');});
      });
    }
  }
  /** 仅更新有界非秘密长度；UI 缓冲由 React ref 独占。 */
  secretCount(count: number): void {
    if (this.#pi.panel) {this.#pi.secretCount(count); return;}
    if (this.panel?.phase === 'secret' && Number.isInteger(count) && count >= 0 && count <= 16_384) this.update({secretByteCount: count});
  }
  /** 接管一次性字节所有权；无效阶段、异常、取消及完成均清零，不写入快照。 */
  submitSecret(bytes: Uint8Array): void {
    if (this.#pi.panel) {this.#pi.submitSecret(bytes); return;}
    if (this.panel?.phase !== 'secret') {bytes.fill(0); return;}
    if (!bytes.length || bytes.length > 16_384 || bytes.some(byte => byte < 32 || byte > 126)) {bytes.fill(0); return;}
    try {this.login('stdin', false, bytes);}
    catch {bytes.fill(0); this.#loginBytes = undefined; throw new Error('认证输入提交失败');}
  }
  private login(source: 'store' | 'stdin' | 'env', browser = false, bytes?: Uint8Array): void {
    if (!this.client.providerLogin || !this.client.cancelProviderLogin) {bytes?.fill(0); this.error('当前客户端没有可取消的独立安全登录桥。'); return;}
    this.#loginBytes = bytes;
    const p = this.panel!, operation = this.#operation, session = this.#session;
    this.update({phase: 'login', choices: [], secretByteCount: 0, message: '正在通过独立认证桥提交；请等待结果。'});
    const valid = () => this.#operation === operation && this.#session === session && this.panel?.phase === 'login';
    try {void this.client.providerLogin({providerId: p.providerId, profileId: p.profileId, secretSource: source, setDefault: true,
      ...(bytes === undefined ? {} : {secretBytes: bytes}), ...(source === 'env' ? {environmentName: p.environmentName} : {}), ...(browser ? {authMethod: 'openrouter-browser' as const} : {})}).then(result => {
      if (!valid()) return;
      if (result.status !== 'succeeded') {this.error('登录未确认成功，保存结果待核对；不会自动重试。'); return;}
      this.#saved = true;
      this.send('auth.activate', {providerId: p.providerId, profileId: p.profileId}, activated => {
        if (activated.providerId !== p.providerId || activated.profileId !== p.profileId) {this.error('凭证已保存，但激活响应目标不一致，请核对。'); return;}
        this.#activated = true;
        this.send('models.list', {providerId: p.providerId}, r => {
          this.readModels(r); const choices = this.#models.filter(m => m.providerId === p.providerId).map(m => ({value: m.modelId, label: m.modelId}));
          this.choices('models', '凭证已保存并启用 · 选择默认模型', choices, choices.length ? '保存不等于连接测试；未自动 probe。' : '没有可选本机模型；请关闭面板，通过 /models add 添加后再选择。');
        });
      });
    }).catch(() => {if (valid()) this.error('登录结果待核对；不会自动重试。');}).finally(() => {
      bytes?.fill(0); if (this.#loginBytes === bytes) this.#loginBytes = undefined;
    });} catch {bytes?.fill(0); this.#loginBytes = undefined; if (valid()) this.error('登录桥未确认成功，请核对或使用 ENV 名称。');}
  }
  cancel(disconnected = false): void {
    if (this.#pi.panel) {this.#pi.cancel(); return;}
    const phase = this.panel?.phase, intent = this.#pending?.intent;
    const uncertain = phase === 'login' || (phase === 'wait' && !!intent && !['models.list', 'auth.list', 'auth.logout.prepare'].includes(intent));
    const outcome = this.#logoutCompleted ? '本机退出已完成；列表刷新状态请核对。'
      : uncertain ? (this.#activated ? '凭证已保存并启用，模型选择结果待核对；不会自动重发。' : this.#saved ? '凭证已保存，激活结果待核对；不会自动重发。' : '保存或退出结果待核对；不会自动重发。')
      : this.#activated ? '凭证已保存并启用；尚未选择默认模型。'
      : this.#saved ? '凭证已保存；后续激活状态请核对。' : '认证面板已关闭。';
    this.invalidate(); this.publish(undefined, (disconnected ? '连接已断开。' : '') + outcome);
  }
  invalidate(): void {this.#pi.invalidate(); this.#loginBytes?.fill(0); this.#loginBytes = undefined; const login = this.panel?.phase === 'login' && this.panel.backend !== 'pi'; ++this.#operation; this.#pending = undefined; this.panel = undefined; if (login) {try {this.client.cancelProviderLogin?.();} catch { /* 结果仍按未知处理。 */ }}}
}
