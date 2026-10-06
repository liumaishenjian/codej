import type {ProtocolEvent} from '../protocol.js';
import type {PiAuthFailureCode, PiAuthIdentity, PiAuthReceipt} from '../pi-auth-bridge.js';
import type {AuthClient, AuthIntent, AuthPanel, AuthChoice} from './auth.js';

const routes = [
  ['openai', 'OpenAI · API Key', 'API_KEY'], ['openai-codex', 'OpenAI · Codex 网页授权', 'OAUTH'],
  ['deepseek', 'DeepSeek · API Key', 'API_KEY'], ['qwen-token-plan-cn', '通义 · 国内 Token Plan · API Key', 'API_KEY'],
] as const;
const rows = (v: unknown): Record<string, unknown>[] => Array.isArray(v) ? v.filter(x => x && typeof x === 'object').slice(0, 256) : [];
const safeId = (v: unknown): v is string => typeof v === 'string' && /^[a-z0-9][a-z0-9-]{0,62}$/.test(v);
type Profile = {identity: PiAuthIdentity; revoked: boolean; source: string};

/** ADR-100 私有认证交互：回执、URL与一次性票据不进入通用面板快照；不拥有凭据或模型运行。 */
export class PiAuthFlow {
  panel: AuthPanel | undefined;
  #url = ''; #receipt: PiAuthReceipt | undefined; #identity: PiAuthIdentity | undefined;
  #profiles: Profile[] = []; #legacyProfiles: {providerId: string; profileId: string}[] = [];
  #legacyTarget: {providerId: string; profileId: string} | undefined;
  #logout = false; #ticket = ''; #serial = 0; #operation = 0; #session = '';
  #pending: {request: string; control: string; intent: AuthIntent; accept: (r: Record<string, unknown>) => void} | undefined;
  #login = false; #stored = false; #authorizationStarted = false;
  readonly #requiresActivation = new Set<string>();
  private identityKey(identity: PiAuthIdentity): string {return [identity.backend, identity.providerId, identity.authMethod, identity.profileId].join('/');}
  constructor(readonly client: AuthClient, readonly publish: (p: AuthPanel | undefined, notice?: string, model?: string) => void,
    readonly compatibility: (logout: boolean) => void,
    readonly openAuthorizationUrl: (url: string) => boolean = () => false) {}
  get authorizationUrl(): string {return this.#url;}
  open(session: string, logout = false): void {
    this.invalidate(); this.#session = session; this.#logout = logout;
    this.panel = {operation: this.#operation, phase: 'wait', title: logout ? '退出本机账号' : '登录服务商', message: '', choices: [], focus: 0, providerId: '', profileId: '', environmentName: '', backend: 'pi'};
    this.publish(this.panel, '');
    if (logout) this.loadProfiles(() => this.send('auth.list', {}, r => {
      this.#legacyProfiles = rows(r.profiles).flatMap(p => safeId(p.providerId) && safeId(p.profileId) && (p.backend === undefined || p.backend === 'spring-ai') ? [{providerId: p.providerId, profileId: p.profileId}] : []);
      this.choices('profiles', '选择明确的退出目标', [
        ...this.#profiles.map((p, i) => ({value: 'pi:' + i, label: this.label(p)})),
        ...this.#legacyProfiles.map((p, i) => ({value: 'legacy:' + i, label: 'spring-ai / API_KEY / ' + p.providerId + ' / ' + p.profileId})),
      ], '仅删除本机身份，不关闭 Session，不远端撤销。');
    }));
    else this.send('providers.catalog', {backend: 'pi'}, r => this.choices('providers', '选择服务商', routes.map<AuthChoice>(([value, label]) => ({value, label})).concat({value: 'legacy', label: '兼容 Provider'}), (r.componentAvailable === true || rows(r.providers).length === 4 && rows(r.providers).every(p => p.componentAvailable === true)) ? '' : '认证服务暂不可用，请稍后重试。'));
  }
  private update(patch: Partial<AuthPanel>): void {if (this.panel) {this.panel = {...this.panel, ...patch}; this.publish(this.panel);}}
  private choices(phase: AuthPanel['phase'], title: string, choices: AuthChoice[], message = ''): void {this.update({phase, title, choices, focus: 0, message});}
  /**
   * OAuth 成功页只证明回调服务器收到 code/state；后续 token 交换、凭证落盘和
   * helper 收尾仍可能失败。这里仅把桥的封闭分类翻译成用户可执行的安全摘要，
   * 不显示 provider 返回文案、路径、URL 查询参数或凭证材料。
   */
  private error(code?: PiAuthFailureCode): void {
    const oauthFlow = this.#identity?.authMethod === 'OAUTH' && this.#authorizationStarted;
    this.#url = ''; this.#receipt = undefined;
    const message = this.#stored
      ? '凭证已保存，后续操作未确认成功；不会自动重发。'
        : code === 'LOGIN'
        ? oauthFlow ? '网页授权流程已开始，但授权码交换或本机凭证保存未确认；不会自动重发。'
          : '认证服务或本机凭证保存未完成；不会自动重发。'
        : code === 'CLEANUP' || code === 'PROTOCOL'
          ? oauthFlow ? '网页授权流程已开始，但认证通道收尾未确认；不会自动重发。'
            : '认证通道收尾未确认；不会自动重发。'
        : code === 'EXIT'
            ? oauthFlow ? '认证进程未正常结束；网页授权结果未确认写入。'
              : '认证进程未正常结束；凭证未确认写入。'
            : code === 'TIMEOUT'
              ? oauthFlow ? '认证等待超时；网页授权结果未确认写入。'
                : '认证等待超时；凭证未确认写入。'
              : '操作未确认成功；不会自动重发或切换兼容 Provider。';
    this.choices('error', '认证操作未完成', [], message);
  }
  private send(intent: AuthIntent, args: Record<string, unknown>, accept: (r: Record<string, unknown>) => void): void {
    this.update({phase: 'wait', choices: [], message: '正在处理本机配置…'});
    const control = `pi-ui:${this.#operation}:${++this.#serial}`;
    try {const request = this.client.providerControl!(control, intent, args); this.#pending = {request, control, intent, accept};} catch {this.error();}
  }
  accept(e: ProtocolEvent): boolean {
    const p = this.#pending;
    if (!p || e.requestId !== p.request || e.runId || (e.sessionId !== undefined && e.sessionId !== this.#session)) return false;
    if (e.type === 'protocol.error') {this.#pending = undefined; this.error(); return true;}
    if (e.type !== 'provider.control.result' || e.sessionId !== this.#session || e.payload.controlId !== p.control || e.payload.intent !== p.intent) return false;
    this.#pending = undefined;
    if (e.payload.status !== 'succeeded') this.error(); else p.accept(e.payload.result as Record<string, unknown>);
    return true;
  }
  private loadProfiles(then: () => void): void {
    this.send('auth.list', {backend: 'pi'}, r => {
      this.#profiles = rows(r.profiles).flatMap(p => {
        const route = routes.find(([id, , method]) => id === p.providerId && method === p.authMethod);
        return p.backend === 'pi' && route && safeId(p.profileId) ? [{identity: {backend: 'pi', providerId: route[0], authMethod: route[2], profileId: p.profileId} as PiAuthIdentity, revoked: p.localStatus !== 'CONFIGURED_UNVERIFIED', source: p.refKind === 'ENV_REF' ? 'ENV 引用' : p.refKind === 'OAUTH' ? 'OAuth' : 'API Key'}] : [];
      }); then();
    });
  }
  private label(p: Profile): string {return `pi / ${p.identity.providerId} / ${p.identity.authMethod} / ${p.identity.profileId} · ${p.source}${p.revoked ? ' · REVOKED' : ''}`;}
  private source(): void {
    const identity = this.#identity!;
    const configured = this.#profiles.find(p => this.same(p.identity, identity));
    // Pi 的 OAuth 首次登录没有额外的“启动网页授权”确认页：选择 provider/profile
    // 后直接进入浏览器流程；只有已有 profile 时才保留“使用现有登录”与“重新登录”的分叉。
    if (!configured && identity.authMethod === 'OAUTH') {this.login(); return;}
    this.choices('source', '登录方式', [
      ...(configured && !configured.revoked && !this.#requiresActivation.has(this.identityKey(identity)) ? [{value: 'existing', label: '使用已配置 profile → 选模型'}] : []),
      {value: 'login', label: identity.authMethod === 'OAUTH' ? '重新进行网页授权' : '输入 API Key'},
      ...(identity.authMethod === 'API_KEY' ? [{value: 'env', label: 'ENV · 仅保存环境变量名称'}] : []),
    ], configured?.revoked || this.#requiresActivation.has(this.identityKey(identity)) ? '本进程已撤销或保存未启用此身份；必须重新登录并显式启用。' : '保存不等于在线验证；登录后需显式启用。');
  }
  private same(a: PiAuthIdentity, b: PiAuthIdentity): boolean {return a.backend === b.backend && a.providerId === b.providerId && a.profileId === b.profileId && a.authMethod === b.authMethod;}
  move(delta: number): void {const p = this.panel; if (p?.choices.length) this.update({focus: (p.focus + delta + p.choices.length) % p.choices.length});}
  input(text: string, backspace = false): void {const p = this.panel; if (p?.phase !== 'env') return; const next = backspace ? p.environmentName.slice(0, -1) : p.environmentName + text; if (/^(?:[A-Za-z_][A-Za-z0-9_]{0,127})?$/.test(next)) this.update({environmentName: next});}
  enter(): void {
    const p = this.panel; if (!p) return; const c = p.choices[p.focus]?.value;
    if (p.phase === 'error' || p.phase === 'done') {this.cancel(); return;}
    if (c === 'legacy' && (p.phase === 'providers' || p.phase === 'profiles')) {const logout = this.#logout; this.invalidate(); this.compatibility(logout); return;}
    if (p.phase === 'providers' && c) {
      const route = routes.find(([id]) => id === c); if (!route) return;
      this.#identity = {backend: 'pi', providerId: route[0], authMethod: route[2], profileId: 'default'} as PiAuthIdentity;
      this.update({...this.#identity}); this.loadProfiles(() => {
        const profiles = [...new Set(['default', ...this.#profiles.filter(x => x.identity.providerId === c).map(x => x.identity.profileId)])];
        this.choices('profiles', '选择 Profile', profiles.map(value => ({value, label: value})));
      });
    } else if (p.phase === 'profiles' && c) {
      if (this.#logout) {
        const legacy = c.startsWith('legacy:');
        const target = legacy ? this.#legacyProfiles[Number(c.slice(7))] : this.#profiles[Number(c.slice(3))]?.identity;
        if (!target) return;
        this.#legacyTarget = legacy ? target : undefined; this.#identity = legacy ? undefined : target as PiAuthIdentity;
        this.update({...target, backend: legacy ? 'spring-ai' : 'pi', authMethod: legacy ? 'API_KEY' : (target as PiAuthIdentity).authMethod});
        this.send('auth.logout.prepare', {...target}, r => {
          const matches = legacy ? r.providerId === target.providerId && r.profileId === target.profileId && (r.backend === undefined || r.backend === 'spring-ai') : this.matches(r);
          if (!matches || typeof r.confirmationId !== 'string' || !r.confirmationId) {this.error(); return;}
          this.#ticket = r.confirmationId;
          this.choices('confirm', '确认退出本机 Profile', [{value: 'cancel', label: '取消，保留凭证'}, {value: 'logout', label: '确认移除这个本机 Profile'}], '仅移除选中身份，不关闭 Session。');
        });
      } else {
        this.#identity = {...this.#identity!, profileId: c}; this.update({profileId: c}); this.source();
      }
    } else if (p.phase === 'source') {
      if (c === 'existing') this.models();
      else if (c === 'env') this.update({phase: 'env', choices: [], message: '只填写名称，不读取或发送环境值。'});
      else if (c === 'login') this.login();
    } else if (p.phase === 'env' && /^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(p.environmentName)) this.login(p.environmentName);
    else if (p.phase === 'confirm') {
      if (c === 'cancel') {this.cancel(); return;}
      if (c === 'activate' && this.#receipt) {
        const receipt = this.#receipt; this.#receipt = undefined;
        this.send('auth.activate', {...receipt}, r => {if (!this.matches(r)) this.error(); else {this.#requiresActivation.delete(this.identityKey(receipt)); this.models();}});
      } else if (c === 'logout' && this.#ticket) {
        const confirmationId = this.#ticket; this.#ticket = '';
        this.send('auth.logout.commit', {confirmationId, confirmed: true}, r => {
          // commit 的旧响应可省略 backend/authMethod；目标由 prepare 的会话绑定票据冻结。
          const target = this.#legacyTarget ?? this.#identity!;
          if (r.providerId !== target.providerId || r.profileId !== target.profileId || r.remoteRevoked !== false
            || r.backend !== undefined && r.backend !== (this.#legacyTarget ? 'spring-ai' : 'pi')
            || r.authMethod !== undefined && r.authMethod !== (this.#legacyTarget ? 'API_KEY' : this.#identity!.authMethod)) this.error();
          else this.choices('done', '本机退出已完成', [], '未远端撤销；Session 保持打开。');
        });
      }
    } else if (p.phase === 'models' && c) {
      this.send('models.use', {...this.#identity!, modelId: c, setDefault: true}, r => {
        if (!this.matches(r) || r.modelId !== c || r.setDefault !== true) {this.error(); return;}
        this.invalidate(); this.publish(undefined, '已选择默认模型；本机配置不表示在线验证。', c);
      });
    }
  }
  private matches(r: Record<string, unknown>): boolean {return !!this.#identity && r.backend === 'pi' && r.providerId === this.#identity.providerId && r.profileId === this.#identity.profileId && r.authMethod === this.#identity.authMethod;}
  private models(): void {
    this.send('models.list', {backend: 'pi', providerId: this.#identity!.providerId}, r => {
      const choices = rows(r.models).filter(x => x.backend === 'pi' && x.providerId === this.#identity!.providerId && typeof x.modelId === 'string' && x.modelId.length <= 256 && !/[\x00-\x1f\x7f]/.test(x.modelId)).map(x => ({value: x.modelId as string, label: '设为默认模型 · ' + x.modelId}));
      this.choices('models', '独立选择默认模型', choices, choices.length ? '仅在选择后设置默认；不会自动 probe。' : '没有可选静态模型。');
    });
  }
  private login(environmentName?: string): void {
    if (!this.client.piLogin || !this.client.piSubmit || !this.client.piCancel) {this.error(); return;}
    const operation = this.#operation, session = this.#session, identity = this.#identity!;
    const valid = () => this.#operation === operation && this.#session === session && this.#login && !!this.panel;
    this.#login = true; this.#url = ''; this.#authorizationStarted = false;
    const title = identity.providerId === 'openai-codex' ? '网页授权' : '输入 API Key';
    this.update({phase: 'login', title, choices: [], message: identity.providerId === 'openai-codex' ? '正在打开浏览器授权…' : '正在启动安全输入…', secretByteCount: 0, authorizationOpened: false});
    try {void this.client.piLogin(identity, {
      onPrompt: prompt => {if (valid()) this.update({phase: 'secret', title: prompt.kind === 'manual_code' ? '等待网页授权' : '输入 API Key', promptId: prompt.promptId, promptKind: prompt.kind, secretByteCount: 0, message: prompt.kind === 'manual_code' ? '若未自动返回，请粘贴授权码或回调 URL。' : '输入 API Key；原文不会显示。'});},
      onPromptCancelled: promptId => {if (valid() && this.panel?.promptId === promptId) this.update({phase: 'login', title, promptId: undefined, promptKind: undefined, secretByteCount: 0, message: '安全提示已取消；等待认证终态。'});},
      onAuthorizationUrl: url => {if (valid()) {
        this.#authorizationStarted = true; this.#url = url;
        const opened = this.openAuthorizationUrl(url);
        this.update({title: '网页授权', authorizationOpened: opened, message: opened
          ? '浏览器已打开，等待授权回调。'
          : '浏览器未自动打开，请打开下方授权页。'});
      }},
    }, environmentName === undefined ? {} : {environmentName}).then(result => {
      if (!valid()) return;
      this.#login = false; this.#url = '';
      this.update({promptId: undefined, promptKind: undefined, secretByteCount: 0});
      if (result.status !== 'stored') {this.error(result.code); return;}
      if (!this.same(result.receipt, identity) || !/^[1-9][0-9]*$/.test(result.receipt.authEpoch)) {this.error('PROTOCOL'); return;}
      this.#stored = true; this.#receipt = {...result.receipt}; this.#requiresActivation.add(this.identityKey(identity));
      this.choices('confirm', '凭证已保存 · 尚未启用', [{value: 'cancel', label: '暂不启用'}, {value: 'activate', label: '启用这次登录'}], '保存不等于在线验证；启用后再独立选择模型。');
    }).catch(() => {if (valid()) {this.#login = false; this.error();}});} catch {this.#login = false; this.error();}
  }
  secretCount(n: number): void {if (this.panel?.phase === 'secret' && Number.isInteger(n) && n >= 0 && n <= 16_384) this.update({secretByteCount: n});}
  submitSecret(bytes: Uint8Array): void {
    const p = this.panel;
    try {
      if (p?.phase !== 'secret' || p.promptId === undefined || !bytes.length || bytes.length > 16_384 || bytes.some(b => b < 32 || b > 126)) return;
      this.update({phase: 'login', promptId: undefined, promptKind: undefined, secretByteCount: 0, message: '等待认证结果…'});
      if (!this.client.piSubmit?.(p.promptId, bytes)) {this.client.piCancel?.(); this.#login = false; this.error();}
    } catch {this.client.piCancel?.(); this.#login = false; this.error();} finally {bytes.fill(0);}
  }
  cancel(): void {const stored = this.#stored; const pending = this.#login || !!this.#pending; this.invalidate(); this.publish(undefined, stored ? '凭证已保存；启用或模型选择状态请核对。' : pending ? '认证结果待核对；不会自动重发。' : '认证面板已关闭。');}
  invalidate(): void {
    const login = this.#login; ++this.#operation; this.#login = false; this.#pending = undefined; this.#receipt = undefined; this.#ticket = ''; this.#url = ''; this.#stored = false; this.#authorizationStarted = false; this.#legacyTarget = undefined; this.panel = undefined;
    if (login) {try {this.client.piCancel?.();} catch { /* 清理结果不能假定成功。 */ }}
  }
}
