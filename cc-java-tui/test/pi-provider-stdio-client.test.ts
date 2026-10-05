import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {spawn} from 'node:child_process';
import {StdioClient} from '../src/stdio-client.js';

vi.mock('node:child_process', async original => ({...await original<typeof import('node:child_process')>(), spawn: vi.fn()}));
class Child extends EventEmitter {
  stdin = new PassThrough(); stdout = new PassThrough(); stderr = new PassThrough();
  pid = undefined; exitCode = null; signalCode = null; kill = vi.fn();
}
let child: Child;
let client: StdioClient;
let frames: Record<string, any>[];
let failures: string[];
let sequence: number;
function event(type: string, payload: unknown, requestId = 'tui-1') {
  child.stdout.write(JSON.stringify({version: 0, type, requestId, sessionId: 'session', sequence: sequence++, payload}) + '\n');
}
function init(payload: Record<string, unknown>) { event('initialized', {protocolVersion: 0, ...payload}); }
const target = {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'};
const piCaps = {authLifecycleV1: true, piProviderV1: true};

beforeEach(() => {
  child = new Child(); sequence = 1; frames = []; failures = [];
  vi.mocked(spawn).mockReturnValue(child as never);
  child.stdin.on('data', data => frames.push(JSON.parse(data.toString())));
  client = new StdioClient({executable: 'synthetic-java', args: [], cwd: '.'});
  client.onFailure(message => failures.push(message));
});

describe('ADR100 StdioClient Pi capability and binding', () => {
  it('请求能力并接受组件缺失，出站epoch精确且commit无后端', () => {
    client.initialize(piCaps); expect(frames[0]!.payload).toEqual(piCaps);
    init({...piCaps, piWorkerAvailable: false}); expect(client.piProviderEnabled).toBe(true);
    client.providerControl('a', 'auth.activate', {...target, authEpoch: '9007199254740993'});
    expect(frames[1]!.payload.arguments.authEpoch).toBe('9007199254740993');
    client.providerControl('b', 'auth.logout.commit', {confirmationId: 'ticket', confirmed: true});
    expect(frames[2]!.payload.arguments).toEqual({confirmationId: 'ticket', confirmed: true});
    expect(failures).toEqual([]);
  });
  it.each([
    [{}, {...piCaps, piWorkerAvailable: false}],
    [{piProviderV1: true}, {piProviderV1: true, piWorkerAvailable: false}],
    [piCaps, {piProviderV1: true, piWorkerAvailable: false}],
    [piCaps, {...piCaps}],
    [piCaps, {...piCaps, piWorkerAvailable: 1}],
    [piCaps, {authLifecycleV1: true, piWorkerAvailable: false}],
    [piCaps, {...piCaps, piProviderV1: 'true', piWorkerAvailable: false}],
    [{authLifecycleV1: true}, {...piCaps, piWorkerAvailable: false}],
  ])('拒绝未请求/缺依赖/非法能力 %#', (request, response) => {
    client.initialize(request); init(response);
    expect(failures).toHaveLength(1); expect(client.isClosed()).toBe(true);
  });
  it('旧宿主和未协商Pi不按同名provider猜后端', () => {
    client.initialize(); init({});
    client.providerControl('legacy', 'models.list', {providerId: 'openai'});
    expect(() => client.providerControl('pi', 'auth.list', {backend: 'pi'})).toThrow();
    expect(frames).toHaveLength(2); expect(client.piProviderEnabled).toBe(false);
  });
  it('非法出站不写入普通stdio', () => {
    client.initialize(piCaps); init({...piCaps, piWorkerAvailable: true});
    for (const authEpoch of [1, '01', '9223372036854775808'])
      expect(() => client.providerControl('a', 'auth.activate', {...target, authEpoch})).toThrow();
    expect(() => client.providerControl('b', 'auth.logout.commit', {...target, confirmationId: 'ticket', confirmed: true})).toThrow();
    expect(() => client.providerControl('c', 'auth.list', {backend: 'unknown'})).toThrow();
    expect(frames).toHaveLength(1);
  });
  it.each([false, true])('拒绝与请求不同后端的同名profile pi=%s', pi => {
    client.initialize(piCaps); init({...piCaps, piWorkerAvailable: false});
    const requestId = client.providerControl('a', 'auth.list', pi ? {backend: 'pi'} : {});
    const item = {providerId: 'openai', profileId: 'default', authMethod: 'API_KEY', localStatus: 'CONFIGURED_UNVERIFIED', providerDefault: false,
      ...(pi ? {refKind: 'STORE'} : {backend: 'pi', refKind: 'API_KEY'})};
    event('provider.control.result', {controlId: 'a', intent: 'auth.list', status: 'succeeded', code: 'OK', result: {profiles: [item]}}, requestId);
    expect(failures).toHaveLength(1);
  });
  it('空列表按请求归属，legacy格式commit回执仍可用', () => {
    client.initialize(piCaps); init({...piCaps, piWorkerAvailable: false});
    const requestId = client.providerControl('a', 'auth.list', {backend: 'pi'});
    event('provider.control.result', {controlId: 'a', intent: 'auth.list', status: 'succeeded', code: 'OK', result: {profiles: []}}, requestId);
    const commitId = client.providerControl('b', 'auth.logout.commit', {confirmationId: 'ticket', confirmed: true});
    event('provider.control.result', {controlId: 'b', intent: 'auth.logout.commit', status: 'succeeded', code: 'OK',
      result: {providerId: 'openai', profileId: 'default', remoteRevoked: false}}, commitId);
    expect(failures).toEqual([]);
  });
});
