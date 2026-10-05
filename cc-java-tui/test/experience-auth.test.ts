import {expect, it, vi} from 'vitest';
import {ExperienceRuntime, type RuntimeClient} from '../src/experience/runtime.js';
import type {ProtocolEvent} from '../src/protocol.js';
import type {ProviderLoginResult} from '../src/stdio-client.js';
import {ExperienceAuth, type AuthIntent} from '../src/experience/auth.js';

it('一次性字节在无效阶段、校验拒绝、同步抛错、异步拒绝、取消和完成均清零', async () => {
  for (const outcome of ['invalid', 'bad', 'throw', 'reject', 'cancel', 'success'] as const) {
    let finish = (_: ProviderLoginResult) => {};
    const client = {providerControl: vi.fn(() => 'request'), cancelProviderLogin: vi.fn(), providerLogin: vi.fn(() => {
      if (outcome === 'throw') throw new Error('private');
      if (outcome === 'reject') return Promise.reject(new Error('private'));
      return new Promise<ProviderLoginResult>(resolve => {finish = resolve;});
    })};
    const auth = new ExperienceAuth(client, () => {});
    if (outcome !== 'invalid') auth.panel = {operation: 0, phase: 'secret', title: '', message: '', choices: [], focus: 0, providerId: 'sample', profileId: 'default', environmentName: ''};
    const bytes = new TextEncoder().encode(outcome === 'bad' ? 'bad\nkey' : 'synthetic-key');
    auth.submitSecret(bytes);
    if (outcome === 'cancel') auth.cancel();
    if (outcome === 'success') finish({status: 'succeeded', exitCode: 0});
    for (let i = 0; i < 6; i++) await Promise.resolve();
    expect(bytes.every(byte => byte === 0), outcome).toBe(true);
    expect(JSON.stringify(auth.panel ?? null)).not.toContain('synthetic-key');
  }
});

function authHost(configured = true, supported = true) {
  let listener = (_: ProtocolEvent) => {}; let failure = (_: string) => {}; let seq = 0;
  let finishLogin = (_: ProviderLoginResult) => {};
  const controls: {control: string; intent: AuthIntent; args: Readonly<Record<string, unknown>>; request: string}[] = [];
  const client = {
    initialize: vi.fn(() => 'init'), onEvent: (fn: typeof listener) => {listener = fn; return () => {};}, onFailure: (fn: typeof failure) => {failure = fn; return () => {};},
    startRun: vi.fn(() => 'run-request'), startPlan: vi.fn(() => 'plan-request'), cancelRun: vi.fn(() => 'cancel'),
    resolveApproval: vi.fn(() => 'approve'), resolveQuestion: vi.fn(() => 'answer'), resolvePlanReview: vi.fn(() => 'review'), shutdown: vi.fn(async () => {}),
    providerControl: vi.fn((control: string, intent: AuthIntent, args: Readonly<Record<string, unknown>>) => {const request = 'control-' + controls.length; controls.push({control, intent, args, request}); return request;}),
    providerLogin: vi.fn(() => new Promise<ProviderLoginResult>(resolve => {finishLogin = resolve;})), cancelProviderLogin: vi.fn(),
  } satisfies RuntimeClient;
  const runtime = new ExperienceRuntime(client, 'C:/public-fixture');
  const emit = (type: string, payload: Record<string, unknown>, requestId: string, sessionId: string | null = 'session', runId?: string) => listener({version: 0, type, payload, requestId, ...(sessionId === null ? {} : {sessionId}), sequence: ++seq, ...(runId ? {runId} : {})} as ProtocolEvent);
  const initialize = () => emit('initialized', {authLifecycleV1: supported, modelConfigured: configured}, 'init');
  const result = (data: Record<string, unknown>, status = 'succeeded', index = controls.length - 1) => {const c = controls[index]!; emit('provider.control.result', {controlId: c.control, intent: c.intent, status, code: status === 'succeeded' ? 'OK' : 'UNAVAILABLE', result: data}, c.request);};
  const models = {models: [{providerId: 'sample', modelId: 'model-a', providerDefault: true}, {providerId: 'sample', modelId: 'model-b', providerDefault: false}]};
  const profiles = {profiles: [{providerId: 'sample', profileId: 'default', refKind: 'STORE', localStatus: 'AVAILABLE', authMethod: 'API_KEY', providerDefault: true}, {providerId: 'sample', profileId: 'work', refKind: 'ENV', providerDefault: false}]};
  const lists = () => {result(models); result(profiles);};
  const login = () => {runtime.submit('/login sample default'); lists(); runtime.auth.enter(); runtime.auth.submitSecret(new TextEncoder().encode('synthetic-key'));};
  return {runtime, client, controls, emit, initialize, result, models, profiles, lists, login, finishLogin: () => finishLogin({status: 'succeeded', exitCode: 0, credentialPreview: 'DO_NOT_PROJECT'}), fail: () => failure('secret-like error MUST NOT DISPLAY')};
}

it('登录保存 → 激活 → 默认模型 → 下一 run；不 probe、不记录认证命令或摘要', async () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.login();
  expect(h.client.initialize).toHaveBeenCalledWith(expect.objectContaining({authLifecycleV1: true}));
  expect(h.client.providerLogin).toHaveBeenCalledWith({providerId: 'sample', profileId: 'default', secretSource: 'stdin', secretBytes: expect.any(Uint8Array), setDefault: true});
  h.finishLogin(); await Promise.resolve(); expect(h.controls.at(-1)?.intent).toBe('auth.activate');
  h.result({providerId: 'sample', profileId: 'default'}); h.result(h.models);
  expect(h.runtime.state.auth?.phase).toBe('models'); h.runtime.auth.move(1); h.runtime.auth.enter();
  expect(h.controls.at(-1)).toMatchObject({intent: 'models.use', args: {providerId: 'sample', profileId: 'default', modelId: 'model-b', setDefault: true}});
  h.result({providerId: 'sample', profileId: 'default', modelId: 'model-b', setDefault: true});
  expect(h.runtime.state.auth).toBeUndefined(); expect(h.runtime.state.model).toBe('model-b');
  expect(h.runtime.submit('inspect public fixture')).toBe(true); expect(h.client.startRun).toHaveBeenCalledWith('inspect public fixture');
  expect(h.controls.some(c => c.intent === 'auth.probe')).toBe(false);
  expect(JSON.stringify(h.runtime.state)).not.toMatch(/DO_NOT_PROJECT|\/login/);
});
it('没有Session的protocol.error仍按当前request终结等待，错误文本不投影', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('/login');
  const request = h.controls.at(-1)!.request;
  h.emit('protocol.error', {code: 'INVALID_STATE', message: 'raw-canary'}, 'other-request', null);
  h.emit('protocol.error', {code: 'INVALID_STATE', message: 'raw-canary'}, request, 'wrong-session');
  expect(h.runtime.state.auth?.phase).toBe('wait');
  h.emit('protocol.error', {code: 'INVALID_STATE', message: 'raw-canary'}, request, null);
  expect(h.runtime.state.auth?.phase).toBe('error');
  expect(JSON.stringify(h.runtime.state)).not.toContain('raw-canary');
});
it('嵌入式宿主未提供modelConfigured表示Unknown而非未配置', () => {
  const h = authHost(); h.runtime.connect(); h.emit('initialized', {}, 'init');
  expect(h.runtime.state.auth).toBeUndefined();
  expect(h.runtime.submit('fake model task')).toBe(true);
});
it('首次未配置自动打开面板，Esc 不绕过普通 prompt gate；旧端失败关闭', () => {
  const h = authHost(false); h.runtime.connect(); h.initialize(); expect(h.runtime.state.auth).toBeDefined();
  h.runtime.cancel(); expect(h.runtime.submit('must not run')).toBe(false); expect(h.client.startRun).not.toHaveBeenCalled();
  const old = authHost(false, false); old.runtime.connect(); old.initialize(); old.runtime.submit('must not run');
  expect(old.client.providerControl).not.toHaveBeenCalled(); expect(old.client.providerLogin).not.toHaveBeenCalled(); expect(old.client.startRun).not.toHaveBeenCalled();
});
it('busy、计划模态拒绝认证且不入队、不转发模型', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('task');
  expect(h.runtime.submit('/login')).toBe(false); expect(h.runtime.submit('/logout')).toBe(false); expect(h.controls).toHaveLength(0);
  expect(h.client.startRun).toHaveBeenCalledTimes(1); expect(JSON.stringify(h.runtime.state.blocks)).not.toContain('/login');
  h.runtime.patch({status: 'idle', showPlan: true}); expect(h.runtime.submit('/login')).toBe(false);
});
it('logout prepare 绑定明确profile；取消不delete；确认仅提交一次且刷新本地列表', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('/logout'); h.lists();
  h.runtime.auth.move(1); h.runtime.auth.enter();
  expect(h.controls.at(-1)).toMatchObject({intent: 'auth.logout.prepare', args: {providerId: 'sample', profileId: 'work'}});
  h.result({providerId: 'sample', profileId: 'work', confirmationId: 'ticket-1'});
  h.runtime.cancel(); expect(h.controls.some(c => c.intent === 'auth.logout.commit')).toBe(false);
  h.runtime.submit('/logout'); h.lists(); h.runtime.auth.enter(); h.result({providerId: 'sample', profileId: 'default', confirmationId: 'ticket-2'});
  h.runtime.auth.move(1); h.runtime.auth.enter(); h.runtime.auth.enter();
  expect(h.controls.filter(c => c.intent === 'auth.logout.commit')).toHaveLength(1);
  expect(h.controls.at(-1)?.args).toEqual({confirmationId: 'ticket-2', confirmed: true});
  h.result({providerId: 'sample', profileId: 'default', remoteRevoked: false}); expect(h.controls.at(-1)?.intent).toBe('auth.list');
  h.result({profiles: [h.profiles.profiles[1]]}); expect(h.runtime.state.auth?.message).toContain('剩余 Profile：1');
  expect(h.client.shutdown).not.toHaveBeenCalled(); expect(h.runtime.state.session).toBe('session');
});
it('cancel/login late promise and wrong session/request/control results never revive panel', async () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.login(); h.runtime.cancel();
  expect(h.client.cancelProviderLogin).toHaveBeenCalledTimes(1); expect(h.runtime.state.notice).toContain('结果待核对');
  h.finishLogin(); await Promise.resolve(); expect(h.runtime.state.auth).toBeUndefined(); expect(h.controls.some(c => c.intent === 'auth.activate')).toBe(false);
  h.runtime.submit('/logout'); const c = h.controls.at(-1)!;
  for (const [request, session, control] of [[c.request, 'other', c.control], ['wrong', 'session', c.control], [c.request, 'session', 'wrong']]) {
    h.emit('provider.control.result', {controlId: control, intent: c.intent, status: 'succeeded', result: h.models}, request!, session!);
    expect(h.runtime.state.auth?.phase).toBe('wait');
  }
  h.fail(); h.result(h.models); expect(h.runtime.state.auth).toBeUndefined(); expect(h.runtime.state.connection).toBe('closed');
});
it('断连登录 promise 不激活；提交logout断连不重发且诚实未知', async () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.login(); h.fail(); h.finishLogin(); await Promise.resolve();
  expect(h.controls.some(c => c.intent === 'auth.activate')).toBe(false); expect(h.runtime.state.notice).toContain('待核对');
  const l = authHost(); l.runtime.connect(); l.initialize(); l.runtime.submit('/logout'); l.lists(); l.runtime.auth.enter(); l.result({providerId: 'sample', profileId: 'default', confirmationId: 't'});
  l.runtime.auth.move(1); l.runtime.auth.enter(); l.fail();
  expect(l.controls.filter(c => c.intent === 'auth.logout.commit')).toHaveLength(1); expect(l.runtime.state.notice).toContain('待核对');
});
it('ENV字段只收名称；不会接收赋值或在普通控制发送密钥；browser capability 缺失隐藏', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('/login sample default'); h.lists();
  expect(h.runtime.state.auth?.choices.some(c => c.value === 'browser')).toBe(false);
  h.runtime.auth.move(1); h.runtime.auth.enter(); h.runtime.auth.input('API_KEY=secret');
  expect(h.runtime.state.auth?.environmentName).toBe(''); h.runtime.auth.input('TEST_API_KEY'); h.runtime.auth.enter();
  expect(h.client.providerLogin).toHaveBeenCalledWith(expect.objectContaining({secretSource: 'env', environmentName: 'TEST_API_KEY'}));
  expect(JSON.stringify(h.controls)).not.toContain('secret'); expect(JSON.stringify(h.runtime.state)).not.toContain('API_KEY=');
});
it('保存后activate失败与models.use失败区分显示', async () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.login(); h.finishLogin(); await Promise.resolve();
  h.result({}, 'rejected'); expect(h.runtime.state.auth?.message).toContain('已保存，但未启用');
  h.runtime.cancel(); h.login(); h.finishLogin(); await Promise.resolve(); h.result({providerId: 'sample', profileId: 'default'}); h.result(h.models); h.runtime.auth.enter(); h.result({}, 'rejected');
  expect(h.runtime.state.auth?.message).toContain('模型选择失败');
});
it('自定义地址复用 HTTPS 校验，非法地址不发送配置', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('/login'); h.lists(); h.runtime.auth.move(1); h.runtime.auth.enter();
  h.runtime.auth.input('http://example.invalid'); h.runtime.auth.enter();
  expect(h.runtime.state.auth?.form?.validation).toContain('HTTPS'); expect(h.controls.some(c => c.intent === 'providers.configure')).toBe(false);
  h.runtime.auth.move(1); h.runtime.auth.input('a-model'); h.runtime.auth.enter();
  expect(h.controls.some(c => c.intent === 'providers.configure')).toBe(false);
});
it('取消prepare后迟到确认不重开；不一致目标永不commit', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('/logout'); h.lists(); h.runtime.auth.enter();
  h.runtime.cancel(); h.result({providerId: 'sample', profileId: 'default', confirmationId: 'late'}); expect(h.runtime.state.auth).toBeUndefined();
  h.runtime.submit('/logout'); h.lists(); h.runtime.auth.enter(); h.result({providerId: 'other', profileId: 'default', confirmationId: 'wrong'});
  expect(h.runtime.state.auth?.phase).toBe('error'); h.runtime.auth.enter(); expect(h.controls.some(c => c.intent === 'auth.logout.commit')).toBe(false);
});
it('来源仅投影固定STORE/ENV标签，忽略私有来源详情和服务端自由错误', () => {
  const h = authHost(); h.runtime.connect(); h.initialize(); h.runtime.submit('/logout'); h.result(h.models);
  h.result({profiles: [{providerId: 'sample', profileId: 'default', refKind: 'ENV', environmentName: 'PRIVATE_ENV', secret: 'PRIVATE_KEY', localStatus: 'PRIVATE_STATUS', path: '/private/path'}]});
  expect(JSON.stringify(h.runtime.state)).not.toMatch(/PRIVATE_|private\/path/); expect(h.runtime.state.auth?.choices[0]?.label).toContain('ENV');
});
it('仅initialized显式支持时展示OpenRouter浏览器方式', () => {
  const h = authHost(); h.runtime.connect(); h.emit('initialized', {authLifecycleV1: true, openRouterBrowserAuthV1: true, modelConfigured: true}, 'init');
  h.runtime.submit('/login openrouter default'); h.result({models: [{providerId: 'openrouter', modelId: 'example-model', providerDefault: true}]}); h.result({profiles: []});
  expect(h.runtime.state.auth?.choices.map(c => c.value)).toContain('browser'); h.runtime.auth.move(-1); h.runtime.auth.enter();
  expect(h.client.providerLogin).toHaveBeenCalledWith(expect.objectContaining({providerId: 'openrouter', authMethod: 'openrouter-browser'}));
});
