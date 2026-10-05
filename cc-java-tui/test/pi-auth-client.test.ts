import {beforeEach, expect, it, vi} from 'vitest';
import type {ProtocolEvent} from '../src/protocol.js';
import type {StdioClient} from '../src/stdio-client.js';
const fake = vi.hoisted(() => ({active: false, login: vi.fn(), submit: vi.fn(), cancel: vi.fn()}));
vi.mock('../src/pi-auth-bridge.js', () => ({PiAuthBridge: class {
  active() {return fake.active;} login(...args: unknown[]) {return fake.login(...args);}
  submit(...args: unknown[]) {return fake.submit(...args);} cancel() {fake.cancel();}
}}));
import {attachPiAuth} from '../src/pi-auth-client.js';
beforeEach(() => {fake.active = false; vi.clearAllMocks(); fake.login.mockResolvedValue({status: 'failed', code: 'LOGIN'});});
function mount() {
  const listeners: ((e: ProtocolEvent) => void)[] = []; let count = 0;
  const client = {
    fence: false,
    onEvent: (f: (e: ProtocolEvent) => void) => {listeners.push(f); return () => {};}, onFailure: () => () => {}, onExit: () => () => {}, onRunHandshake: () => () => {},
    startRun() {if (this.fence) throw new Error('original-fence'); return 'r' + ++count;},
    startPlan() {return this.startRun();}, startPlanExecution() {return this.startRun();}, invokeSkill() {return this.startRun();},
    resolvePlanReview() {return 'review';}, resumePlanVerification() {return 'resume';}, sessionCommand() {return 'control';},
    providerLogin: vi.fn(async () => ({status: 'succeeded', exitCode: 0})), terminate: vi.fn(), shutdown: vi.fn(async () => {}),
  };
  const originalLogin = client.providerLogin, originalShutdown = client.shutdown;
  const wrapped = attachPiAuth(client as unknown as StdioClient, {executable: 'fake', args: [], cwd: 'C:/fixture'});
  const emit = (type: string, payload: Record<string, unknown> = {}, requestId = 'init', runId?: string, session: string | null = 's') => listeners.forEach(f => f({version: 0, type, payload, requestId, ...(session ? {sessionId: session} : {}), sequence: 1, ...(runId ? {runId} : {})} as ProtocolEvent));
  emit('initialized', {piProviderV1: true, authLifecycleV1: true});
  return {client, wrapped, emit, originalLogin, originalShutdown};
}
const identity = {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'} as const;
const callbacks = {onPrompt: () => {}, onPromptCancelled: () => {}, onAuthorizationUrl: () => {}};
it('薄包装保留实例与原动态run fence，Pi活动期间拒绝所有生产run入口', () => {
  const h = mount(); expect(h.wrapped).toBe(h.client); h.client.fence = true;
  expect(() => h.wrapped.startPlan('fixture')).toThrow('original-fence'); h.client.fence = false; fake.active = true;
  expect(() => h.wrapped.startRun('fixture')).toThrow('PI_AUTH_BUSY');
  expect(() => h.wrapped.resolvePlanReview({planId: 'p', revision: 1, contentDigest: 'd', workspaceDigest: 'w', decision: 'APPROVE_USER', contextPolicy: 'KEEP', feedback: ''})).toThrow('PI_AUTH_BUSY');
});
it('pending/active run禁止helper，非相关错误不能清fence，精确拒绝/终态后才放行', async () => {
  const h = mount(); const request = h.wrapped.startRun('fixture'); h.emit('protocol.error', {}, 'unrelated');
  await h.wrapped.piLogin(identity, callbacks); expect(fake.login).not.toHaveBeenCalled();
  h.emit('run.command.result', {disposition: 'rejected'}, request); await h.wrapped.piLogin(identity, callbacks); expect(fake.login).toHaveBeenCalledTimes(1);
  const second = h.wrapped.startRun('fixture'); h.emit('run.started', {}, second, 'run');
  h.emit('run.completed', {}, second, 'wrong-run'); await h.wrapped.piLogin(identity, callbacks); expect(fake.login).toHaveBeenCalledTimes(1);
  h.emit('run.completed', {}, second, 'run'); await h.wrapped.piLogin(identity, callbacks); expect(fake.login).toHaveBeenCalledTimes(2);
});
it('精确无Session协议拒绝可清pending，错误Session不能清除', async () => {
  const h = mount(); const request = h.wrapped.startRun('fixture');
  h.emit('protocol.error', {}, request, undefined, 'other');
  await h.wrapped.piLogin(identity, callbacks); expect(fake.login).not.toHaveBeenCalled();
  h.emit('protocol.error', {}, request, undefined, null);
  await h.wrapped.piLogin(identity, callbacks); expect(fake.login).toHaveBeenCalledTimes(1);
});
it('shutdown等待私有登录取消结算，不能只取消后提前结束宿主', async () => {
  const h = mount(); let settle!: (value: unknown) => void;
  fake.login.mockImplementation(() => {fake.active = true; return new Promise(resolve => {settle = resolve;});});
  const login = h.wrapped.piLogin(identity, callbacks); let stopped = false;
  const shutdown = h.wrapped.shutdown().then(() => {stopped = true;});
  await Promise.resolve(); await Promise.resolve();
  expect(fake.cancel).toHaveBeenCalled(); expect(stopped).toBe(false);
  fake.active = false; settle({status: 'cancelled', code: 'CANCELLED'});
  await login; await shutdown; expect(h.originalShutdown).toHaveBeenCalledOnce();
});
it('helper清理未确认时宿主仍关闭，但shutdown不能报告成功', async () => {
  const h = mount(); fake.active = true;
  await expect(h.wrapped.shutdown()).rejects.toThrow('PI_AUTH_CLEANUP');
  expect(h.originalShutdown).toHaveBeenCalledOnce();
});
it('ENV options只交私有桥；提交拒绝也擦除；终止和shutdown取消helper', async () => {
  const h = mount(); await h.wrapped.piLogin(identity, callbacks, {environmentName: 'PUBLIC_ENV_NAME'});
  expect(fake.login).toHaveBeenCalledWith(identity, callbacks, {environmentName: 'PUBLIC_ENV_NAME'}); expect(h.originalLogin).not.toHaveBeenCalled();
  fake.submit.mockReturnValue(false); const bytes = new Uint8Array([65]); expect(h.wrapped.piSubmit(1, bytes)).toBe(false); expect(bytes[0]).toBe(0);
  h.wrapped.terminate(); await h.wrapped.shutdown(); expect(fake.cancel).toHaveBeenCalled();
});
