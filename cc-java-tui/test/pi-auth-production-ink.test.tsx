import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import {AgentTui, type AgentClient} from '../src/app.js';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import type {RuntimeClient} from '../src/experience/runtime.js';
import type {AuthIntent} from '../src/experience/auth.js';
import type {PiAuthCallbacks, PiAuthIdentity, PiAuthResult} from '../src/pi-auth-bridge.js';
import type {ProtocolEvent} from '../src/protocol.js';
const size = vi.hoisted(() => ({columns: 80, rows: 24}));
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => size}));
afterEach(() => {cleanup(); size.columns = 80; size.rows = 24;});
const wait = () => new Promise(r => setTimeout(r, 40));
async function mount(next: boolean, configured = false) {
  let listener: (e: ProtocolEvent) => void = () => {}; let sequence = 0;
  let callbacks: PiAuthCallbacks = undefined!, finish: (r: PiAuthResult) => void = undefined!;
  const calls: {controlId: string; intent: AuthIntent; args: Readonly<Record<string, unknown>>; request: string}[] = [];
  const client = {
    initialize: vi.fn(() => 'init'), onEvent: (f: typeof listener) => {listener = f; return () => {};}, onFailure: () => () => {}, onExit: () => () => {},
    startRun: vi.fn(() => 'run'), startPlan: vi.fn(() => 'plan'), cancelRun: vi.fn(() => 'cancel'), shutdown: vi.fn(async () => {}), terminate: vi.fn(),
    resolveApproval: vi.fn(() => 'a'), resolveQuestion: vi.fn(() => 'q'), resolvePlanReview: vi.fn(() => 'p'),
    providerControl: vi.fn((controlId: string, intent: AuthIntent, args: Readonly<Record<string, unknown>>) => {const request = 'c' + calls.length; calls.push({controlId, intent, args, request}); return request;}),
    piLogin: vi.fn((_identity: PiAuthIdentity, cb: PiAuthCallbacks) => {callbacks = cb; return new Promise<PiAuthResult>(r => {finish = r;});}),
    piSubmit: vi.fn((_id: number, bytes: Uint8Array) => {bytes.fill(0); return true;}), piCancel: vi.fn(),
  } satisfies AgentClient & RuntimeClient;
  const app = render(next ? <ExperienceRuntimeApp client={client} workspace="C:/public-fixture"/> : <AgentTui client={client}/>);
  const emit = (type: string, payload: Record<string, unknown>, requestId = 'init') => listener({version: 0, type, payload, requestId, sessionId: 's', sequence: ++sequence} as ProtocolEvent);
  await wait(); emit('initialized', {authLifecycleV1: true, piProviderV1: true, piWorkerAvailable: false, modelConfigured: configured}); await wait();
  const result = async (r: Record<string, unknown>) => {const c = calls.at(-1)!; emit('provider.control.result', {controlId: c.controlId, intent: c.intent, status: 'succeeded', code: 'OK', result: r}, c.request); await wait();};
  const key = async (s: string) => {app.stdin.write(s); await wait();};
  return {app, client, calls, key, result, cb: () => callbacks, finish: async (r: PiAuthResult) => {finish(r); await wait();}};
}
it.each([false, true])('生产%s入口：四路目录、prompt后遮蔽、显式activate、模型及返回下一轮', async next => {
  const h = await mount(next); expect(h.calls.at(-1)?.intent).toBe('providers.catalog');
  expect(h.client.initialize).toHaveBeenCalledWith(expect.objectContaining({piProviderV1: true, authLifecycleV1: true}));
  await h.result({providers: [], componentAvailable: false});
  for (const label of ['OpenAI', 'Codex', 'DeepSeek', '通义', '兼容 Provider']) expect(h.app.lastFrame()).toContain(label);
  await h.key('\r'); await h.result({profiles: []}); await h.key('\r'); await h.key('\r');
  expect(h.client.piLogin).toHaveBeenCalledTimes(1); expect(h.app.lastFrame()).not.toContain('[已遮蔽]');
  await h.key('discard-before-prompt'); expect(h.client.piSubmit).not.toHaveBeenCalled();
  h.cb().onPrompt({promptId: 1, kind: 'secret'}); await wait(); await h.key('SYNTHETIC_KEY');
  expect(h.app.lastFrame()).toContain('13 字节'); expect(h.app.frames.join()).not.toContain('SYNTHETIC_KEY');
  await h.key('\r'); expect(h.client.piSubmit).toHaveBeenCalledTimes(1);
  const identity = {backend: 'pi', providerId: 'openai', authMethod: 'API_KEY', profileId: 'default'} as const;
  await h.finish({status: 'stored', receipt: {...identity, authEpoch: '9007199254740993'}});
  expect(h.calls.some(c => c.intent === 'auth.activate')).toBe(false); expect(h.app.lastFrame()).toContain('尚未启用');
  await h.key('\x1b[B'); await h.key('\r'); expect(h.calls.at(-1)?.args).toEqual({...identity, authEpoch: '9007199254740993'});
  await h.result(identity); await h.result({models: [{backend: 'pi', providerId: 'openai', modelId: 'fixture-model'}]});
  expect(h.app.lastFrame()).toContain('设为默认模型'); await h.key('\r'); await h.result({...identity, modelId: 'fixture-model', setDefault: true});
  await h.key('下一轮公开任务'); await h.key('\r'); expect(h.client.startRun).toHaveBeenCalledWith('下一轮公开任务');
  expect(h.app.frames.join()).not.toContain('9007199254740993'); expect(h.app.frames.join()).not.toContain('discard-before-prompt');
});
it.each([false, true])('生产%s入口：manual_code URL仅本面板，prompt切换及取消擦除输入', async next => {
  const h = await mount(next); await h.result({providers: []}); await h.key('\x1b[B'); await h.key('\r'); await h.result({profiles: []}); await h.key('\r'); await h.key('\r');
  h.cb().onAuthorizationUrl('https://auth.openai.com/oauth/authorize?fixture=1'); h.cb().onPrompt({promptId: 1, kind: 'manual_code'}); await wait();
  expect(h.app.lastFrame()).toContain('https://auth.openai.com'); await h.key('SYNTHETIC_CODE');
  h.cb().onPrompt({promptId: 2, kind: 'manual_code'}); await wait(); expect(h.app.lastFrame()).toContain('0 字节');
  await h.key('\r'); expect(h.client.piSubmit).not.toHaveBeenCalled(); await h.key('SECOND_CODE');
  h.cb().onPromptCancelled(2); await wait(); expect(h.app.lastFrame()).not.toContain('[已遮蔽]');
  h.cb().onPrompt({promptId: 3, kind: 'manual_code'}); await wait(); expect(h.app.lastFrame()).toContain('0 字节');
  await h.key('\x1b'); expect(h.client.piCancel).toHaveBeenCalledTimes(1); expect(h.app.lastFrame()).not.toContain('https://auth.openai.com');
  expect(h.app.frames.join()).not.toContain('SYNTHETIC_CODE'); expect(h.app.frames.join()).not.toContain('SECOND_CODE');
});
it.each([false, true])('已配置生产%s入口的bare /login和/logout仍走明确Pi路由，认证不提交Agent', async next => {
  const h = await mount(next, true); await h.key('/login'); await h.key('\r');
  expect(h.calls.at(-1)?.intent).toBe('providers.catalog'); await h.result({providers: []}); await h.key('\x1b');
  await h.key('/logout'); await h.key('\r'); expect(h.calls.at(-1)?.args).toEqual({backend: 'pi'});
  await h.result({profiles: [{backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY', refKind: 'API_KEY'}]});
  await h.result({profiles: [{providerId: 'openai', profileId: 'default'}]});
  expect(h.app.lastFrame()).toContain('pi / openai / API_KEY'); expect(h.app.lastFrame()).toContain('spring-ai / API_KEY');
  await h.key('\r'); await h.result({backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY', confirmationId: 'one-ticket'});
  await h.key('\x1b[B'); await h.key('\r'); await h.key('\r');
  expect(h.calls.filter(c => c.intent === 'auth.logout.commit')).toHaveLength(1);
  await h.result({providerId: 'openai', profileId: 'default', remoteRevoked: false}); expect(h.app.lastFrame()).toContain('本机退出已完成');
  expect(h.client.startRun).not.toHaveBeenCalled();
});
it.each([[80, 24], [100, 24], [120, 24], [80, 35], [100, 35], [120, 35]])('两生产视图%s×%s目录选择离线可达（非PTY/物理验收）', async (columns, rows) => {
  size.columns = columns!; size.rows = rows!;
  for (const next of [false, true]) {
    const h = await mount(next); await h.result({providers: []});
    expect(h.app.lastFrame()).toContain('兼容 Provider');
    await h.key('\x1b[B'); await h.key('\x1b[B'); await h.key('\x1b[B'); await h.key('\r');
    expect(h.calls.at(-1)?.args).toEqual({backend: 'pi'}); await h.result({profiles: []}); await h.key('\r');
    expect(h.app.lastFrame()).toContain('qwen-token-plan-cn'); expect(h.app.lastFrame()).toContain('ENV'); h.app.unmount();
  }
});
