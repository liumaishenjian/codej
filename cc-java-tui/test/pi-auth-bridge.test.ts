import {EventEmitter} from 'node:events';
import {PassThrough, Writable} from 'node:stream';
import type {ChildProcess, SpawnOptions} from 'node:child_process';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {PiAuthBridge, type PiAuthCallbacks, type PiAuthIdentity} from '../src/pi-auth-bridge.js';

const MAIN = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const AUTH_MAIN = 'io.github.liumaishenjian.ccjava.cli.auth.PiAuthBridgeMain';
const identity: PiAuthIdentity = {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'};
const codex: PiAuthIdentity = {...identity, providerId: 'openai-codex', authMethod: 'OAUTH'};
const spec = {executable: 'trusted-java', args: ['-Duser.home=trusted', '-cp', 'trusted-classpath', MAIN, '--stdio'],
  cwd: 'G:/trusted', env: {PATH: 'trusted-path'}};
const tick = () => new Promise<void>(resolve => setImmediate(resolve));

/** 保留 write 的真实缓冲引用，能证伪“发出前擦除”以及 callback 之前擦除。 */
class FakeChild extends EventEmitter {
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  readonly writes: Buffer[] = [];
  readonly snapshots: string[] = [];
  readonly callbacks: ((error?: Error | null) => void)[] = [];
  readonly stdin: Writable;
  holdWrites = false;
  kill = vi.fn(() => true);
  constructor() {
    super();
    this.stdin = new Writable({write: (chunk: Buffer, _encoding, callback) => {
      this.writes.push(chunk); this.snapshots.push(chunk.toString('utf8'));
      if (this.holdWrites) this.callbacks.push(callback); else callback();
    }});
  }
  completeWrite(error?: Error): void { this.callbacks.shift()?.(error); }
  async close(code = 0): Promise<void> {
    this.stdout.end(); this.stderr.end(); await tick();
    this.emit('exit', code, null); this.emit('close', code, null);
  }
}
function harness(id: PiAuthIdentity = identity, options: {holdWrites?: boolean; timeoutMs?: number; environmentName?: string} = {}) {
  const child = new FakeChild(); child.holdWrites = options.holdWrites ?? false;
  const onPrompt = vi.fn(); const onPromptCancelled = vi.fn(); const onAuthorizationUrl = vi.fn();
  const callbacks: PiAuthCallbacks = {onPrompt, onPromptCancelled, onAuthorizationUrl};
  let operationId = '';
  const spawn = vi.fn((_executable: string, args: readonly string[], _options: SpawnOptions) => {
    operationId = args[args.indexOf('--operation-id') + 1]!;
    return child as unknown as ChildProcess;
  });
  const bridge = new PiAuthBridge(spec, {spawnProcess: spawn, timeoutMs: options.timeoutMs ?? 1000, stopTimeoutMs: 20});
  const result = bridge.login(id, callbacks, options.environmentName === undefined ? {} : {environmentName: options.environmentName});
  let sequence = 0;
  const raw = (type: string, payload: object, overrides: object = {}) => JSON.stringify({version: 1,
    operationId, sequence: sequence++, type, payload, ...overrides}) + '\n';
  const send = (type: string, payload: object, overrides: object = {}) => child.stdout.write(raw(type, payload, overrides));
  const stored = (epoch = '9223372036854775807') => send('auth.stored', {...id, authEpoch: epoch});
  const stop = async () => { send('auth.input_stop', {}); await tick(); };
  return {child, bridge, result, raw, send, stored, stop, spawn, callbacks,
    onPrompt, onPromptCancelled, onAuthorizationUrl, operationId};
}

afterEach(() => { vi.useRealTimers(); });

describe('PiAuthBridge isolated identity and process contract', () => {
  it('derives only the fixed main and identity, snapshots trusted spec, never inherits process env', async () => {
    const h = harness();
    const args = h.spawn.mock.calls[0]![1];
    expect(args.slice(0, 4)).toEqual([...spec.args.slice(0, 3), AUTH_MAIN]);
    expect(args.slice(4)).toEqual(['--operation-id', h.operationId, '--provider', 'openai',
      '--profile', 'default', '--auth-method', 'API_KEY']);
    expect(h.spawn.mock.calls[0]![2]).toEqual({cwd: spec.cwd, env: spec.env,
      shell: false, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
    h.bridge.cancel(); await h.child.close(1); await h.result;
  });
  it.each([
    [MAIN, MAIN, '--stdio'], [MAIN, '--stdio', 'other'], ['--stdio', MAIN, '--stdio'], ['other', '--stdio'],
  ])('rejects untrusted main shape %j', (...args) => {
    expect(() => new PiAuthBridge({...spec, args})).toThrow('PI_AUTH_SPEC');
  });
  it.each([0, 300001, NaN, 1.5])('never expands operation budget %s', timeoutMs => {
    expect(() => new PiAuthBridge(spec, {timeoutMs})).toThrow('PI_AUTH_CONFIGURATION');
  });
  it.each([
    {...identity, providerId: 'openai-codex'}, {...codex, providerId: 'openai'},
    {...identity, backend: 'spring-ai'}, {...identity, profileId: '../default'}, {...identity, extra: true},
  ])('rejects invalid identity %# without spawning', async bad => {
    const spawn = vi.fn();
    const bridge = new PiAuthBridge(spec, {spawnProcess: spawn});
    expect(await bridge.login(bad as PiAuthIdentity, {} as PiAuthCallbacks)).toEqual({status: 'failed', code: 'IDENTITY'});
    expect(spawn).not.toHaveBeenCalled();
  });
  it('maps synchronous spawn exception without preserving diagnostics', async () => {
    const bridge = new PiAuthBridge(spec, {spawnProcess: () => { throw new Error('private-material'); }});
    expect(await bridge.login(identity, {} as PiAuthCallbacks)).toEqual({status: 'failed', code: 'SPAWN'});
    expect(bridge.active()).toBe(false);
  });
});

describe('PiAuthBridge prompts, EOF and exact receipt', () => {
  it.each([identity, {...identity, providerId: 'deepseek'} as PiAuthIdentity,
    {...identity, providerId: 'qwen-token-plan-cn'} as PiAuthIdentity, codex])('stores route $providerId after all exit gates', async id => {
    const h = harness(id);
    h.send('auth.prompt', {promptId: 1, kind: id.authMethod === 'OAUTH' ? 'manual_code' : 'secret'});
    expect(h.onPrompt).toHaveBeenCalledWith({promptId: 1, kind: id.authMethod === 'OAUTH' ? 'manual_code' : 'secret'});
    const source = Buffer.from('synthetic-fixture');
    expect(h.bridge.submit(1, source)).toBe(true); expect(source.every(b => b === 0)).toBe(true);
    await tick();
    expect(JSON.parse(h.child.snapshots[0]!)).toEqual({version: 1, operationId: h.operationId, sequence: 0,
      type: 'auth.response', payload: {promptId: 1, value: 'synthetic-fixture'}});
    expect(h.child.writes[0]!.every(b => b === 0)).toBe(true);
    await h.stop(); expect(h.child.stdin.writableFinished).toBe(true);
    let resolved = false; void h.result.then(() => { resolved = true; });
    h.stored(); await tick(); expect(resolved).toBe(false);
    h.child.emit('exit', 0, null); await tick(); expect(resolved).toBe(false);
    h.child.stdout.end(); h.child.stderr.end(); await tick(); expect(resolved).toBe(false);
    h.child.emit('close', 0, null);
    expect(await h.result).toEqual({status: 'stored', receipt: {...id, authEpoch: '9223372036854775807'}});
    expect(h.bridge.active()).toBe(false);
  });
  it('retains in-flight bytes until callback and ends stdin only after write completion', async () => {
    const h = harness(identity, {holdWrites: true});
    h.send('auth.prompt', {promptId: 1, kind: 'secret'});
    const owned = Buffer.from('synthetic-only');
    expect(h.bridge.submit(1, owned)).toBe(true);
    expect(owned.every(b => b === 0)).toBe(true);
    expect(h.child.writes[0]!.toString()).toContain('synthetic-only');
    h.send('auth.input_stop', {});
    expect(h.child.stdin.writableEnded).toBe(false);
    const late = Buffer.from('late'); expect(h.bridge.submit(1, late)).toBe(false);
    expect(late.every(b => b === 0)).toBe(true);
    h.child.completeWrite(); await tick();
    expect(h.child.writes[0]!.every(b => b === 0)).toBe(true);
    expect(h.child.stdin.writableFinished).toBe(true);
    h.stored(); await h.child.close(); expect((await h.result).status).toBe('stored');
  });
  it('serializes a second prompt response without mistaking slow callbacks for invalid server prompts', async () => {
    const h = harness(identity, {holdWrites: true});
    h.send('auth.prompt', {promptId: 1, kind: 'secret'});
    expect(h.bridge.submit(1, Buffer.from('first-fixture'))).toBe(true);
    h.send('auth.prompt', {promptId: 2, kind: 'secret'});
    const second = Buffer.from('second-fixture');
    expect(h.bridge.submit(2, second)).toBe(true); expect(second.every(b => b === 0)).toBe(true);
    expect(h.child.writes).toHaveLength(1);
    h.child.completeWrite(); expect(h.child.writes).toHaveLength(2);
    expect(JSON.parse(h.child.snapshots[1]!)).toMatchObject({sequence: 1, payload: {promptId: 2, value: 'second-fixture'}});
    h.child.completeWrite(); await h.stop(); h.stored(); await h.child.close();
    expect((await h.result).status).toBe('stored');
  });
  it.each(['stop', 'cancel', 'prompt_cancelled'] as const)('erases queued, unsent material on %s', async action => {
    const h = harness(identity, {holdWrites: true});
    h.send('auth.prompt', {promptId: 1, kind: 'secret'}); h.bridge.submit(1, Buffer.from('first-fixture'));
    h.send('auth.prompt', {promptId: 2, kind: 'secret'});
    const original = Buffer.from;
    let queued: Buffer | undefined;
    // 仅观测新桥持有的编码 Buffer；不修改 Writable 的消费或擦除时序。
    const spy = vi.spyOn(Buffer, 'from').mockImplementation(((...args: Parameters<typeof Buffer.from>) => {
      const buffer = original(...args);
      if (typeof args[0] === 'string' && args[0].includes('second-fixture')) queued = buffer;
      return buffer;
    }) as typeof Buffer.from);
    try { h.bridge.submit(2, new TextEncoder().encode('second-fixture')); } finally { spy.mockRestore(); }
    expect(queued?.toString()).toContain('second-fixture');
    if (action === 'stop') h.send('auth.input_stop', {});
    else if (action === 'cancel') h.bridge.cancel();
    else h.send('auth.prompt_cancelled', {promptId: 2});
    expect(queued?.every(b => b === 0)).toBe(true);
    expect(h.child.writes[0]!.toString()).toContain('first-fixture');
    h.child.completeWrite(); await tick(); expect(h.child.writes).toHaveLength(1);
    if (action === 'prompt_cancelled') {
      h.send('auth.prompt', {promptId: 3, kind: 'secret'});
      h.bridge.submit(3, Buffer.from('third-fixture'));
      expect(JSON.parse(h.child.snapshots[1]!)).toMatchObject({sequence: 1, payload: {promptId: 3}});
      h.child.completeWrite(); await h.stop();
    }
    if (action !== 'cancel') { h.stored(); await h.child.close(); expect((await h.result).status).toBe('stored'); }
    else { await h.child.close(1); expect((await h.result).status).toBe('cancelled'); }
  });
  it('rejects stored before EOF handshake even if the child later exits zero', async () => {
    const h = harness(); h.stored(); await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'PROTOCOL'});
  });
  it('cancels current prompt, rejects its late response, permits only the new prompt once', async () => {
    const h = harness(codex);
    h.send('auth.prompt', {promptId: 1, kind: 'manual_code'});
    h.send('auth.prompt_cancelled', {promptId: 1});
    expect(h.onPromptCancelled).toHaveBeenCalledWith(1);
    h.send('auth.prompt', {promptId: 2, kind: 'manual_code'});
    for (const promptId of [1, 3]) {
      const bytes = Buffer.from('late'); expect(h.bridge.submit(promptId, bytes)).toBe(false);
      expect(bytes.every(b => b === 0)).toBe(true);
    }
    expect(h.bridge.submit(2, Buffer.from('manual-fixture'))).toBe(true); await tick();
    expect(h.bridge.submit(2, Buffer.from('duplicate'))).toBe(false);
    await h.stop(); h.stored('9007199254740993'); await h.child.close();
    expect(await h.result).toEqual({status: 'stored', receipt: {...codex, authEpoch: '9007199254740993'}});
  });
  it('input_stop revokes an unsubmitted prompt and never waits on terminal stdin', async () => {
    const h = harness(); h.send('auth.prompt', {promptId: 1, kind: 'secret'}); await h.stop();
    expect(h.onPromptCancelled).toHaveBeenCalledExactlyOnceWith(1);
    expect(h.child.stdin.writableFinished).toBe(true);
    h.stored(); await h.child.close(); expect((await h.result).status).toBe('stored');
  });
  it.each(['0', '01', '-1', '9223372036854775808', '1e3', 9007199254740992])('rejects nonexact epoch %s', async epoch => {
    const h = harness(); await h.stop(); h.send('auth.stored', {...identity, authEpoch: epoch});
    await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'PROTOCOL'});
  });
  it('rejects mismatched receipt identity', async () => {
    const h = harness(); await h.stop(); h.send('auth.stored', {...identity, profileId: 'other', authEpoch: '1'});
    await h.child.close(); expect((await h.result).status).toBe('failed');
  });
});

describe('PiAuthBridge hostile private protocol', () => {
  it.each([
    {operationId: 'other'}, {sequence: 1}, {sequence: -1}, {version: 2}, {extra: true}, {payload: []},
  ])('rejects envelope mutation %j', async override => {
    const h = harness(); h.send('auth.input_stop', {}, override); await h.child.close();
    expect(await h.result).toEqual({status: 'failed', code: 'PROTOCOL'});
  });
  it.each([
    ['auth.prompt', {promptId: 1, kind: 'secret', text: 'not-allowed'}],
    ['auth.prompt', {promptId: 2, kind: 'secret'}],
    ['auth.prompt', {promptId: 1, kind: 'manual_code'}],
    ['auth.prompt_cancelled', {promptId: 1}],
    ['auth.input_stop', {extra: true}], ['credential.request', {}], ['auth.failed', {code: 'private-diagnostic'}],
  ] as const)('rejects payload/type %s %j', async (type, payload) => {
    const h = harness(); h.send(type, payload); await h.child.close();
    expect(await h.result).toEqual({status: 'failed', code: 'PROTOCOL'});
  });
  it.each([
    (s: string) => s.replace('"version":1', '"version":1,"version":1'),
    (s: string) => s.replace('"version":1', '"version":1,"ver\\u0073ion":1'),
    (s: string) => s.replace('"payload":{}', '"payload":{"x":1,"x":2}'),
    (s: string) => s.replace('"payload":{}', '"payload":{"x":"\\ud800"}'),
    (s: string) => s.replace('"sequence":0', '"sequence":1e-999'),
    (s: string) => s.replace('"sequence":0', '"sequence":9007199254740993'),
    (s: string) => s.replace('"payload":{}', `"payload":${'['.repeat(65)}0${']'.repeat(65)}`),
    (s: string) => '\ufeff' + s,
    (s: string) => s.replace('\n', '\r\n'),
    (s: string) => s + ' ',
  ])('rejects duplicate keys, malformed scalar, depth, framing %#', async transform => {
    const h = harness(); h.child.stdout.write(transform(h.raw('auth.input_stop', {})));
    await h.child.close(); expect((await h.result).status).toBe('failed');
  });
  it('rejects fatal UTF8 and incomplete LF', async () => {
    for (const bytes of [Buffer.from([0xc3, 0x28, 0x0a]), Buffer.from('{"version":1}')]) {
      const h = harness(); h.child.stdout.write(bytes); await h.child.close();
      expect(await h.result).toEqual({status: 'failed', code: 'PROTOCOL'});
    }
  });
  it('accepts split UTF8 frames without replacing Unicode', async () => {
    const h = harness(codex);
    const bytes = Buffer.from(h.raw('auth.url', {url: 'https://auth.openai.com/authorize?label=中文'}));
    for (const byte of bytes) h.child.stdout.write(Buffer.from([byte]));
    expect(h.onAuthorizationUrl).toHaveBeenCalledWith('https://auth.openai.com/authorize?label=中文');
    await h.stop(); h.stored(); await h.child.close(); expect((await h.result).status).toBe('stored');
  });
  it.each(['https://auth.openai.com.evil/authorize', 'http://auth.openai.com/authorize',
    'https://user@auth.openai.com/authorize', 'https://auth.openai.com:444/authorize',
    'https://auth.openai.com\\@evil/authorize', 'https://auth.openai.com/authorize\n'])('rejects URL %s', async url => {
    const h = harness(codex); h.send('auth.url', {url}); await h.child.close();
    expect(h.onAuthorizationUrl).not.toHaveBeenCalled(); expect((await h.result).status).toBe('failed');
  });
  it('never permits a URL for an API_KEY route', async () => {
    const h = harness(); h.send('auth.url', {url: 'https://auth.openai.com/authorize'}); await h.child.close();
    expect(h.onAuthorizationUrl).not.toHaveBeenCalled(); expect((await h.result).status).toBe('failed');
  });
  it.each(['stdout', 'stderr'] as const)('charges raw %s whitespace and unfinished lines', async stream => {
    const h = harness(); h.child[stream].write(Buffer.alloc(128 * 1024 + 1, 32));
    await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'LIMIT'});
  });
  it('enforces frame limit including LF', async () => {
    const h = harness(); h.child.stdout.write(Buffer.alloc(32 * 1024, 32));
    await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'LIMIT'});
  });
  it('shares budget across sent input, stdout and stderr', async () => {
    const h = harness(); h.send('auth.prompt', {promptId: 1, kind: 'secret'});
    h.bridge.submit(1, Buffer.alloc(16_384, 65)); await tick();
    h.child.stderr.write(Buffer.alloc(112 * 1024, 32));
    await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'LIMIT'});
  });
  it('enforces combined 512 frame budget', async () => {
    const h = harness(codex);
    for (let i = 0; i < 513; i++) h.send('auth.url', {url: 'https://auth.openai.com/'});
    await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'LIMIT'});
    expect(h.onAuthorizationUrl).toHaveBeenCalledTimes(512);
  });
});

describe('PiAuthBridge ENV_REF', () => {
  it('passes only the name and waits for the same stop/EOF/stored gates', async () => {
    const h = harness(identity, {environmentName: 'SYNTHETIC_API_KEY'});
    expect(h.spawn.mock.calls[0]![1].slice(-2)).toEqual(['--environment-name', 'SYNTHETIC_API_KEY']);
    expect(h.onPrompt).not.toHaveBeenCalled();
    await h.stop(); h.stored(); await h.child.close();
    expect((await h.result).status).toBe('stored'); expect(h.child.snapshots).toEqual([]);
  });
  it.each(['', '1BAD', 'BAD-NAME', 'A'.repeat(129), 'bad\nname'])('rejects invalid name %j', async environmentName => {
    const h = harness(identity, {environmentName});
    expect(await h.result).toEqual({status: 'failed', code: 'IDENTITY'}); expect(h.spawn).not.toHaveBeenCalled();
  });
  it('rejects OAuth ENV before spawning', async () => {
    const h = harness(codex, {environmentName: 'KEY'});
    expect(await h.result).toEqual({status: 'failed', code: 'IDENTITY'}); expect(h.spawn).not.toHaveBeenCalled();
  });
  it.each(['auth.prompt', 'auth.url'])('rejects ENV %s', async type => {
    const h = harness(identity, {environmentName: 'KEY'});
    h.send(type, type === 'auth.prompt' ? {promptId: 1, kind: 'secret'} : {url: 'https://auth.openai.com/'});
    await h.child.close(); expect(await h.result).toEqual({status: 'failed', code: 'PROTOCOL'});
    expect(h.onPrompt).not.toHaveBeenCalled(); expect(h.onAuthorizationUrl).not.toHaveBeenCalled();
  });
});

describe('PiAuthBridge fail-closed lifecycle', () => {
  it.each(['frame', 'whitespace', 'stderr', 'nonzero', 'missing-end'] as const)('does not accept stored with %s', async fault => {
    const h = harness(); await h.stop(); h.stored();
    if (fault === 'frame') h.send('auth.input_stop', {});
    if (fault === 'whitespace') h.child.stdout.write(' ');
    if (fault === 'stderr') h.child.stderr.write('private-output');
    if (fault === 'missing-end') { h.child.emit('exit', 0, null); h.child.emit('close', 0, null); }
    else await h.child.close(fault === 'nonzero' ? 1 : 0);
    const result = await h.result; expect(result.status).toBe('failed'); expect(result).not.toHaveProperty('receipt');
    expect(JSON.stringify(result)).not.toContain('private-output');
  });
  it.each(['spawn', 'stdout', 'stderr', 'stdin', 'write-callback', 'write-throw'] as const)('redacts %s failures', async fault => {
    const h = harness(identity, {holdWrites: true});
    const error = new Error('private-secret-url');
    if (fault === 'spawn') h.child.emit('error', error);
    else if (fault === 'write-callback' || fault === 'write-throw') {
      h.send('auth.prompt', {promptId: 1, kind: 'secret'});
      if (fault === 'write-throw') vi.spyOn(h.child.stdin, 'write').mockImplementation(() => { throw error; });
      const source = Buffer.from('synthetic'); h.bridge.submit(1, source); expect(source.every(b => b === 0)).toBe(true);
      if (fault === 'write-callback') h.child.completeWrite(error);
      await tick();
    } else h.child[fault].emit('error', error);
    await h.child.close(1);
    const result = await h.result; expect(result.status).toBe('failed');
    expect(JSON.stringify(result)).not.toContain('private-secret-url');
    for (const bytes of h.child.writes) expect(bytes.every(b => b === 0)).toBe(true);
  });
  it('cancels with private auth.cancel and destroys neither terminal nor in-flight bytes early', async () => {
    const h = harness(identity, {holdWrites: true});
    h.send('auth.prompt', {promptId: 1, kind: 'secret'}); h.bridge.cancel();
    expect(JSON.parse(h.child.snapshots[0]!)).toMatchObject({type: 'auth.cancel', sequence: 0, payload: {}});
    expect(h.child.writes[0]!.some(b => b !== 0)).toBe(true);
    expect(h.onPromptCancelled).toHaveBeenCalledExactlyOnceWith(1);
    h.child.completeWrite(); await h.child.close(1);
    expect(await h.result).toEqual({status: 'cancelled', code: 'CANCELLED'});
    expect(h.child.writes[0]!.every(b => b === 0)).toBe(true);
  });
  it('bounded timeout keeps busy until actual close and ignores late stored', async () => {
    vi.useFakeTimers();
    const h = harness(identity, {timeoutMs: 100});
    await vi.advanceTimersByTimeAsync(141);
    expect(await h.result).toEqual({status: 'timed_out', code: 'TIMEOUT'});
    expect(h.child.kill).toHaveBeenCalledWith('SIGKILL'); expect(h.bridge.active()).toBe(true);
    expect(await h.bridge.login(identity, h.callbacks)).toEqual({status: 'failed', code: 'BUSY'});
    h.send('auth.prompt', {promptId: 1, kind: 'secret'}); h.stored();
    expect(h.onPrompt).not.toHaveBeenCalled();
    h.child.emit('exit', 0, null); h.child.emit('close', 0, null); expect(h.bridge.active()).toBe(false);
  });
  it('stored without close/EOF fails on short deadline rather than waiting 300 seconds', async () => {
    const h = harness(); await h.stop(); h.stored();
    expect(await h.result).toEqual({status: 'failed', code: 'CLEANUP'});
    expect(h.bridge.active()).toBe(true); h.child.emit('exit', 0, null); h.child.emit('close', 0, null);
  });
  it('keeps blocked writes alive until actual exit, including cancellation deadline', async () => {
    vi.useFakeTimers(); const h = harness(identity, {holdWrites: true});
    h.send('auth.prompt', {promptId: 1, kind: 'secret'}); h.bridge.submit(1, Buffer.from('synthetic'));
    h.bridge.cancel(); await vi.advanceTimersByTimeAsync(41);
    expect((await h.result).status).toBe('cancelled'); expect(h.bridge.active()).toBe(true);
    expect(h.child.writes[0]!.toString()).toContain('synthetic');
    h.child.emit('exit', 1, null); expect(h.child.writes[0]!.every(b => b === 0)).toBe(true);
    expect(h.bridge.active()).toBe(true); h.child.emit('close', 1, null); expect(h.bridge.active()).toBe(false);
  });
  it('always erases rejected caller buffers including idle, invalid UTF8, oversized and cancelled inputs', async () => {
    const idle = new PiAuthBridge(spec);
    const bytes = Buffer.from('idle'); expect(idle.submit(1, bytes)).toBe(false); expect(bytes.every(b => b === 0)).toBe(true);
    for (const material of [Buffer.alloc(16385, 65), Buffer.from([0xc3, 0x28]), Buffer.from('bad\nvalue')]) {
      const h = harness(); h.send('auth.prompt', {promptId: 1, kind: 'secret'});
      expect(h.bridge.submit(1, material)).toBe(false); expect(material.every(b => b === 0)).toBe(true);
      h.bridge.cancel(); const late = Buffer.from('late'); expect(h.bridge.submit(1, late)).toBe(false);
      expect(late.every(b => b === 0)).toBe(true); await h.child.close(1); await h.result;
    }
  });
  it('old child events cannot notify or release the next operation', async () => {
    const children: FakeChild[] = [];
    const ids: string[] = [];
    const spawnProcess = (_executable: string, args: readonly string[]) => {
      const child = new FakeChild(); children.push(child); ids.push(args[args.indexOf('--operation-id') + 1]!);
      return child as unknown as ChildProcess;
    };
    const bridge = new PiAuthBridge(spec, {spawnProcess, timeoutMs: 1000, stopTimeoutMs: 20});
    const callbacks = {onPrompt: vi.fn(), onPromptCancelled: vi.fn(), onAuthorizationUrl: vi.fn()};
    const first = bridge.login(identity, callbacks); bridge.cancel(); await children[0]!.close(1); await first;
    const second = bridge.login(identity, callbacks);
    children[0]!.emit('close', 0, null);
    children[0]!.stdout.emit('data', Buffer.from(JSON.stringify({version: 1, operationId: ids[0], sequence: 0,
      type: 'auth.prompt', payload: {promptId: 1, kind: 'secret'}}) + '\n'));
    expect(bridge.active()).toBe(true); expect(callbacks.onPrompt).not.toHaveBeenCalled();
    bridge.cancel(); await children[1]!.close(1); expect((await second).status).toBe('cancelled');
  });
  it('isolates callback exceptions and never leaks thrown material', async () => {
    const h = harness(); h.onPrompt.mockImplementation(() => { throw new Error('private-callback'); });
    h.send('auth.prompt', {promptId: 1, kind: 'secret'}); await h.child.close(1);
    expect(await h.result).toEqual({status: 'failed', code: 'CALLBACK'});
  });
});
