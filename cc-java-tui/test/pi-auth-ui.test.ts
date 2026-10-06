import {expect, it, vi} from 'vitest';
import {ExperienceAuth, type AuthClient, type AuthIntent} from '../src/experience/auth.js';
import type {PiAuthCallbacks, PiAuthResult} from '../src/pi-auth-bridge.js';
import type {ProtocolEvent} from '../src/protocol.js';

function harness(openAuthorizationUrl: (url: string) => boolean = () => false) {
  const calls: {control: string; intent: AuthIntent; args: Readonly<Record<string, unknown>>; request: string}[] = [];
  let callbacks: PiAuthCallbacks = undefined!; let finish: (r: PiAuthResult) => void = undefined!;
  const states: string[] = [];
  const client = {
    providerControl: vi.fn((control, intent, args) => {const request = 'r' + calls.length; calls.push({control, intent, args, request}); return request;}),
    providerLogin: vi.fn(async () => ({status: 'succeeded' as const, exitCode: 0})), cancelProviderLogin: vi.fn(),
    piLogin: vi.fn((_: import('../src/pi-auth-bridge.js').PiAuthIdentity, c: PiAuthCallbacks, _options?: import('../src/pi-auth-bridge.js').PiAuthLoginOptions) => {callbacks = c; return new Promise<PiAuthResult>(r => {finish = r;});}),
    piSubmit: vi.fn((_, bytes) => {bytes.fill(0); return true;}), piCancel: vi.fn(),
  } satisfies AuthClient;
  const auth = new ExperienceAuth(client, p => states.push(JSON.stringify(p)), undefined, openAuthorizationUrl);
  auth.initialize('s', {authLifecycleV1: true, piProviderV1: true, modelConfigured: true});
  const result = (r: Record<string, unknown>, status = 'succeeded') => {
    const p = calls.at(-1)!;
    auth.accept({version: 0, type: 'provider.control.result', requestId: p.request, sessionId: 's', sequence: calls.length, payload: {controlId: p.control, intent: p.intent, status, result: r}} as ProtocolEvent);
  };
  const choose = (value: string) => {const p = auth.panel!; const index = p.choices.findIndex(c => c.value === value); expect(index).toBeGreaterThanOrEqual(0); auth.move(index - p.focus); auth.enter();};
  const start = (provider = 'openai', profiles: unknown[] = []) => {auth.open('/login'); result({providers: []}); choose(provider); result({profiles}); choose('default');};
  return {auth, client, calls, states, result, choose, start, callbacks: () => callbacks, finish: async (r: PiAuthResult) => {finish(r); await Promise.resolve();}};
}
const identity = {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'} as const;
it('缺组件仍展示三个品牌四路由和显式兼容入口；同名显式登录仍是legacy', () => {
  const h = harness(); h.auth.open('/login'); h.result({providers: [], componentAvailable: false});
  expect(h.auth.panel!.choices.map(c => c.value)).toEqual(['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn', 'legacy']);
  expect(h.auth.panel!.message).toContain('认证服务暂不可用');
  h.auth.open('/login openai default'); expect(h.calls.at(-1)?.args).toEqual({}); expect(h.calls.at(-1)?.intent).toBe('models.list');
});
it('输入前启动helper；prompt之后才允许secret，stored停在确认且精确字符串epoch不进快照', async () => {
  const h = harness(); h.start(); h.choose('login'); expect(h.client.piLogin).toHaveBeenCalledTimes(1); expect(h.auth.panel!.phase).toBe('login');
  const early = new Uint8Array([65]); h.auth.submitSecret(early); expect(early[0]).toBe(0); expect(h.client.piSubmit).not.toHaveBeenCalled();
  h.callbacks().onPrompt({promptId: 1, kind: 'secret'}); expect(h.auth.panel!.phase).toBe('secret');
  const bytes = new TextEncoder().encode('synthetic'); h.auth.submitSecret(bytes); expect(bytes.every(b => b === 0)).toBe(true);
  await h.finish({status: 'stored', receipt: {...identity, authEpoch: '9007199254740993'}});
  expect(h.calls.some(c => c.intent === 'auth.activate')).toBe(false); expect(h.auth.panel!.phase).toBe('confirm');
  expect(h.states.join()).not.toContain('9007199254740993'); expect(h.states.join()).not.toContain('synthetic');
  h.choose('activate'); expect(h.calls.at(-1)?.args).toEqual({...identity, authEpoch: '9007199254740993'});
  h.result(identity); expect(h.calls.at(-1)?.intent).toBe('models.list');
  h.result({models: [{backend: 'pi', providerId: 'openai', modelId: 'fake-model'}]});
  expect(h.calls.some(c => c.intent === 'models.use')).toBe(false); h.choose('fake-model'); expect(h.calls.at(-1)?.args).toEqual({...identity, modelId: 'fake-model', setDefault: true});
});
it('manual_code、URL和promptcancel局限当前operation，旧会话迟到结果无效', async () => {
  const h = harness(); h.start('openai-codex'); expect(h.auth.panel?.phase).toBe('login'); const callbacks = h.callbacks();
  callbacks.onAuthorizationUrl('https://auth.openai.com/oauth/authorize?synthetic'); callbacks.onPrompt({promptId: 1, kind: 'manual_code'});
  expect(h.auth.authorizationUrl).toContain('synthetic'); expect(h.states.join()).not.toContain('https://');
  callbacks.onPromptCancelled(1); expect(h.auth.panel!.phase).toBe('login'); expect(h.auth.panel!.promptId).toBeUndefined();
  callbacks.onPrompt({promptId: 2, kind: 'manual_code'}); expect(h.auth.panel!.promptId).toBe(2);
  h.auth.initialize('other', {authLifecycleV1: true, piProviderV1: true}); expect(h.auth.authorizationUrl).toBe('');
  callbacks.onPrompt({promptId: 3, kind: 'secret'}); expect(h.auth.panel).toBeUndefined();
  await h.finish({status: 'stored', receipt: {...identity, authEpoch: '8'}}); expect(h.calls.some(c => c.intent === 'auth.activate')).toBe(false);
});
it('Codex auth_url 交给宿主浏览器，manual_code 只作为自动回调失败时的回退', () => {
  const opened: string[] = [];
  const h = harness(url => {opened.push(url); return true;}); h.start('openai-codex'); expect(h.auth.panel?.phase).toBe('login');
  h.callbacks().onAuthorizationUrl('https://auth.openai.com/oauth/authorize?synthetic');
  expect(opened).toEqual(['https://auth.openai.com/oauth/authorize?synthetic']);
  expect(h.auth.panel?.authorizationOpened).toBe(true);
  expect(h.auth.panel?.message).toContain('浏览器已打开');
  h.callbacks().onPrompt({promptId: 1, kind: 'manual_code'});
  expect(h.auth.panel?.message).toContain('若未自动返回');
});
it('网页成功页之后仍须等待本机回执，并将后续失败解释为未确认落盘', async () => {
  const h = harness(); h.start('openai-codex');
  h.callbacks().onAuthorizationUrl('https://auth.openai.com/oauth/authorize?synthetic');
  await h.finish({status: 'failed', code: 'LOGIN'});
  expect(h.auth.panel?.phase).toBe('error');
  expect(h.auth.panel?.message).toContain('授权码交换或本机凭证保存未确认');
  expect(h.auth.panel?.message).not.toContain('登录成功');
});
it('ENV只传名称、配置profile免重登但REVOKED不可使用', () => {
  const h = harness(); h.start('openai', [{...identity, refKind: 'ENV_REF', localStatus: 'REVOKED_IN_PROCESS'}]);
  expect(h.auth.panel!.choices.some(c => c.value === 'existing')).toBe(false);
  h.choose('env'); h.auth.input('KEY=value'); expect(h.auth.panel!.environmentName).toBe(''); h.auth.input('PUBLIC_NAME'); h.auth.enter();
  expect(h.client.piLogin.mock.calls[0]?.[2]).toEqual({environmentName: 'PUBLIC_NAME'}); expect(h.client.providerLogin).not.toHaveBeenCalled();
  h.auth.cancel(); h.start('openai', [{...identity, refKind: 'API_KEY', localStatus: 'CONFIGURED_UNVERIFIED'}]); h.choose('existing');
  expect(h.calls.at(-1)?.args).toEqual({backend: 'pi', providerId: 'openai'}); expect(h.client.piLogin).toHaveBeenCalledTimes(1);
});
it('退出绑定身份并只提交一次票据；失败/stale不重发或关闭session', () => {
  const h = harness(); h.auth.open('/logout'); h.result({profiles: [{...identity, refKind: 'API_KEY', localStatus: 'CONFIGURED_UNVERIFIED'}]}); h.result({profiles: [{providerId: 'openai', profileId: 'default'}]});
  expect(h.auth.panel!.choices.map(c => c.label).join()).toContain('spring-ai / API_KEY'); h.choose('pi:0');
  expect(h.calls.at(-1)?.args).toEqual(identity); h.result({...identity, confirmationId: 'ticket-private'});
  expect(h.states.join()).not.toContain('ticket-private'); h.choose('logout'); h.auth.enter();
  expect(h.calls.at(-1)?.args).toEqual({confirmationId: 'ticket-private', confirmed: true}); h.result({}, 'failed');
  expect(h.calls.filter(c => c.intent === 'auth.logout.commit')).toHaveLength(1); expect(h.auth.panel!.phase).toBe('error');
});
it('取消新操作拒绝旧回调，即使新旧操作都处于secret阶段', async () => {
  const h = harness(); h.start(); h.choose('login'); const old = h.callbacks(); old.onPrompt({promptId: 1, kind: 'secret'});
  h.auth.cancel(); h.start(); h.choose('login'); h.callbacks().onPrompt({promptId: 1, kind: 'secret'});
  old.onPromptCancelled(1); old.onAuthorizationUrl('https://private-old'); expect(h.auth.panel!.phase).toBe('secret'); expect(h.auth.authorizationUrl).toBe('');
});
