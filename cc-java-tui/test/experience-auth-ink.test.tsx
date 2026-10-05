import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import stringWidth from 'string-width';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import {ExperienceRuntime, type RuntimeClient} from '../src/experience/runtime.js';
import type {AuthIntent} from '../src/experience/auth.js';
import type {ProtocolEvent} from '../src/protocol.js';
import type {ProviderLoginResult} from '../src/stdio-client.js';
const size = vi.hoisted(() => ({columns: 80, rows: 24}));
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => size}));
afterEach(cleanup);
const wait = () => new Promise(resolve => setTimeout(resolve, 40));
async function mount(configured = true, supported = true) {
  let event = (_: ProtocolEvent) => {}; let failure = (_: string) => {}; let sequence = 0;
  let finish = (_: ProviderLoginResult) => {};
  const calls: {id: string; intent: AuthIntent; args: Readonly<Record<string, unknown>>; request: string}[] = [];
  const client = {
    initialize: vi.fn(() => 'init'), onEvent: (f: typeof event) => {event = f; return () => {};}, onFailure: (f: typeof failure) => {failure = f; return () => {};},
    startRun: vi.fn(() => 'run-request'), startPlan: vi.fn(() => 'plan-request'), cancelRun: vi.fn(() => 'cancel'),
    resolveApproval: vi.fn(() => 'a'), resolveQuestion: vi.fn(() => 'q'), resolvePlanReview: vi.fn(() => 'p'), shutdown: vi.fn(async () => {}),
    providerControl: vi.fn((id: string, intent: AuthIntent, args: Readonly<Record<string, unknown>>) => {const request = 'c' + calls.length; calls.push({id, intent, args, request}); return request;}),
    providerLogin: vi.fn(() => new Promise<ProviderLoginResult>(resolve => {finish = resolve;})), cancelProviderLogin: vi.fn(),
  } satisfies RuntimeClient;
  const app = render(<ExperienceRuntimeApp client={client} workspace="C:/public-fixture"/>);
  const emit = (type: string, payload: Record<string, unknown>, requestId: string, runId?: string) => event({version: 0, type, payload, requestId, sessionId: 'session', sequence: ++sequence, ...(runId ? {runId} : {})} as ProtocolEvent);
  await wait(); emit('initialized', {authLifecycleV1: supported, modelConfigured: configured}, 'init'); await wait();
  const key = async (input: string) => {app.stdin.write(input); await wait();};
  const result = async (result: Record<string, unknown>) => {const c = calls.at(-1)!; emit('provider.control.result', {controlId: c.id, intent: c.intent, status: 'succeeded', code: 'OK', result}, c.request); await wait();};
  const models = {models: [{providerId: 'sample', modelId: 'model-a', providerDefault: true}]};
  const profiles = {profiles: [{providerId: 'sample', profileId: 'default', refKind: 'STORE', localStatus: 'AVAILABLE', providerDefault: true, authMethod: 'API_KEY'}]};
  return {client, app, calls, key, result, models, profiles, emit, fail: async () => {failure('untrusted private detail'); await wait();}, finish: async () => {finish({status: 'succeeded', exitCode: 0}); await wait();}};
}
async function secretPanel() {
  const h = await mount(); await h.key('/login sample default'); await h.key('\r');
  await h.result(h.models); await h.result(h.profiles); await h.key('\r');
  expect(h.app.lastFrame()).toContain('[已遮蔽]'); return h;
}
it('遮蔽输入拒绝控制字、多行和超限，重复Enter只提交一次且不进入模型/控制/快照/历史', async () => {
  const snapshots: string[] = [];
  const original = ExperienceRuntime.prototype.patch;
  const patch = vi.spyOn(ExperienceRuntime.prototype, 'patch').mockImplementation(function (this: ExperienceRuntime, value) {
    original.call(this, value); snapshots.push(JSON.stringify(this.state));
  });
  try {
    const h = await secretPanel();
    await h.key('bad\nmultiline'); await h.key('x'.repeat(16_385)); await h.key('\x01');
    expect(h.app.lastFrame()).toContain('0 字节'); expect(h.client.providerLogin).not.toHaveBeenCalled();
    const canary = 'SYNTHETIC_PRIVATE_INPUT_CANARY'; await h.key(canary);
    expect(h.app.lastFrame()).toContain(canary.length + ' 字节');
    await h.key('\x7f'); await h.key('Y');
    await h.key('\r'); await h.key('\r');
    expect(h.client.providerLogin).toHaveBeenCalledTimes(1);
    const request = h.client.providerLogin.mock.calls[0] as unknown as [import('../src/stdio-client.js').ProviderLoginRequest];
    expect(request[0].secretSource).toBe('stdin');
    expect(new TextDecoder().decode(request[0].secretBytes)).toBe(canary);
    await h.fail(); expect(request[0].secretBytes?.every(byte => byte === 0)).toBe(true);
    expect(h.app.frames.join('\n') + JSON.stringify(h.calls) + snapshots.join('')).not.toContain(canary);
    expect(h.client.startRun).not.toHaveBeenCalled();
  } finally {patch.mockRestore();}
});
it.each(['escape', 'ctrl-c', 'disconnect', 'unmount', 'operation', 'session'])('secret ref在%s时清零，认证字符不进入composer/history', async reason => {
  const buffers: Uint8Array[] = [];
  const fill = Uint8Array.prototype.fill;
  const spy = vi.spyOn(Uint8Array.prototype, 'fill').mockImplementation(function (this: Uint8Array, value, start, end) {
    if (this.length === 16_384) buffers.push(this);
    return fill.call(this, value, start, end);
  });
  let runtime: ExperienceRuntime | undefined;
  const original = ExperienceRuntime.prototype.patch;
  const patch = vi.spyOn(ExperienceRuntime.prototype, 'patch').mockImplementation(function (this: ExperienceRuntime, value) {runtime = this; original.call(this, value);});
  try {
    const h = await secretPanel(); await h.key('SYNTHETIC_ERASE_CANARY');
    if (reason === 'escape') await h.key('\x1b');
    else if (reason === 'ctrl-c') {await h.key('\x03'); expect(h.client.shutdown).toHaveBeenCalledTimes(1);}
    else if (reason === 'disconnect') await h.fail();
    else if (reason === 'unmount') h.app.unmount();
    else if (reason === 'operation') {runtime!.auth.open('/login'); await wait();}
    else {runtime!.patch({session: 'new-session'}); await wait();}
    expect(buffers.length).toBeGreaterThan(0);
    expect(buffers.every(bytes => bytes.every(byte => byte === 0))).toBe(true);
    expect(h.client.providerLogin).not.toHaveBeenCalled();
    if (reason === 'escape') {await h.key('\x1b[A'); await h.key('safe prompt'); await h.key('\r'); expect(h.client.startRun).toHaveBeenCalledWith('safe prompt');}
    expect(h.app.frames.join('\n')).not.toContain('SYNTHETIC_ERASE_CANARY');
  } finally {spy.mockRestore(); patch.mockRestore();}
});
it.each(['escape', 'ctrl-c'] as const)('stdin helper 已启动未返回时 %s 仍由 Ink 处理，清零并拒绝迟到成功', async reason => {
  const h = await secretPanel();
  const canary = 'SYNTHETIC_PENDING_HELPER_CANARY';
  await h.key(canary); await h.key('\r');
  // 必须先提交并进入 login，不能把 secret phase 的取消误当成 helper 等待期间的取消。
  expect(h.client.providerLogin).toHaveBeenCalledTimes(1);
  const [request] = h.client.providerLogin.mock.calls[0] as unknown as [import('../src/stdio-client.js').ProviderLoginRequest];
  expect(request.secretSource).toBe('stdin');
  expect(new TextDecoder().decode(request.secretBytes)).toBe(canary);
  expect(h.app.lastFrame()).toContain('正在通过独立认证桥提交');
  await h.key('discard-during-login'); await h.key('\x1b[A'); await h.key('\r');
  expect(h.client.providerLogin).toHaveBeenCalledTimes(1);
  expect(h.client.startRun).not.toHaveBeenCalled();
  await h.key(reason === 'escape' ? '\x1b' : '\x03');
  expect(h.client.cancelProviderLogin).toHaveBeenCalledTimes(1);
  expect(request.secretBytes?.every(byte => byte === 0)).toBe(true);
  expect(h.client.shutdown).toHaveBeenCalledTimes(reason === 'ctrl-c' ? 1 : 0);
  const controls = h.calls.length;
  await h.finish();
  expect(h.calls).toHaveLength(controls);
  expect(h.calls.some(c => c.intent === 'auth.activate')).toBe(false);
  expect(h.app.lastFrame()).not.toContain('正在通过独立认证桥提交');
  if (reason === 'escape') {
    expect(h.app.lastFrame()).toContain('结果待核对');
    await h.key('\x1b[A'); await h.key('safe after cancel'); await h.key('\r');
    expect(h.client.startRun).toHaveBeenCalledWith('safe after cancel');
  }
  expect(h.app.frames.join('\n')).not.toContain(canary);
  expect(h.app.frames.join('\n')).not.toContain('discard-during-login');
});

it('真实Ink按键登录、模型选择、返回输入、下一run；认证不入输入历史', async () => {
  const h = await mount(); await h.key('/login'); await h.key('\r'); await h.result(h.models); await h.result(h.profiles);
  expect(h.app.lastFrame()).toContain('选择服务商'); await h.key('\r'); await h.key('\r');
  expect(h.app.lastFrame()).toContain('遮蔽输入'); await h.key('\r'); await h.key('synthetic-key'); await h.key('\r'); await h.finish();
  expect(h.calls.at(-1)?.intent).toBe('auth.activate'); await h.result({providerId: 'sample', profileId: 'default'}); await h.result(h.models);
  await h.key('\r'); await h.result({providerId: 'sample', profileId: 'default', modelId: 'model-a', setDefault: true});
  expect(h.app.lastFrame()).toContain('配置模型：model-a'); await h.key('\x1b[A'); expect(h.app.lastFrame()).not.toContain('❯ /login');
  await h.key('检查公开fixture'); await h.key('\r'); expect(h.client.startRun).toHaveBeenCalledWith('检查公开fixture');
  expect(h.client.startRun).toHaveBeenCalledTimes(1);
  expect(h.app.frames.join('\n')).not.toContain('synthetic-key');
});
it('真实Ink logout默认取消、显式确认一次、断连不重发', async () => {
  const h = await mount(); await h.key('/logout'); await h.key('\r'); await h.result(h.models); await h.result(h.profiles); await h.key('\r');
  expect(h.calls.at(-1)?.intent).toBe('auth.logout.prepare'); await h.result({providerId: 'sample', profileId: 'default', confirmationId: 'one'});
  expect(h.app.lastFrame()).toContain('取消，保留凭证'); await h.key('\x1b'); expect(h.calls.some(c => c.intent === 'auth.logout.commit')).toBe(false);
  await h.key('/logout'); await h.key('\r'); await h.result(h.models); await h.result(h.profiles); await h.key('\r');
  await h.result({providerId: 'sample', profileId: 'default', confirmationId: 'two'}); await h.key('\x1b[B'); await h.key('\r'); await h.key('\r');
  expect(h.calls.filter(c => c.intent === 'auth.logout.commit')).toHaveLength(1); await h.fail();
  expect(h.app.lastFrame()).toContain('结果待核对'); expect(h.app.lastFrame()).not.toContain('untrusted');
});
it('首次必填面板独占焦点、长列表箭头分页且80×24可达', async () => {
  const h = await mount(false); await h.result({models: Array.from({length: 20}, (_, i) => ({providerId: 'provider-' + i, modelId: 'model', providerDefault: true}))}); await h.result({profiles: []});
  for (let i = 0; i < 19; i++) await h.key('\x1b[B');
  expect(h.app.lastFrame()).toContain('provider-19'); expect(h.app.lastFrame()).toContain('20/21');
  await h.key('this is not a prompt'); await h.key('\r'); expect(h.client.startRun).not.toHaveBeenCalled();
  const frame = h.app.lastFrame()!; expect(frame.split('\n').length).toBeLessThanOrEqual(24); expect(Math.max(...frame.split('\n').map(line => stringWidth(line)))).toBeLessThanOrEqual(80);
  await h.key('\x1b'); await h.key('ordinary prompt'); await h.key('\r'); expect(h.client.startRun).not.toHaveBeenCalled();
});
it('旧端、busy按键不发送认证或模型；取消后迟到登录不重开', async () => {
  const old = await mount(false, false); await old.key('prompt'); await old.key('\r'); expect(old.client.startRun).not.toHaveBeenCalled(); expect(old.calls).toHaveLength(0);
  old.app.unmount();
  const h = await mount(); await h.key('task'); await h.key('\r'); await h.key('/login'); await h.key('\r');
  expect(h.calls).toHaveLength(0); expect(h.client.startRun).toHaveBeenCalledTimes(1);
  h.emit('run.launch.failed', {}, 'run-request'); await wait(); await h.key('\x15'); await h.key('/login sample default'); await h.key('\r'); await h.result(h.models); await h.result(h.profiles); await h.key('\r');
  await h.key('\x1b'); await h.finish(); expect(h.calls.some(c => c.intent === 'auth.activate')).toBe(false); expect(h.app.lastFrame()).toContain('认证面板已关闭');
});
