import {EventEmitter} from 'node:events';
import {PassThrough, Writable} from 'node:stream';
import {resolve} from 'node:path';
import {describe, expect, it, vi} from 'vitest';
import {parsePiAuthCliArguments, runPiAuthCli, PI_AUTH_CLI_LOGIN_TIMEOUT_MS} from '../src/pi-auth-cli.js';
import type {PiAuthCallbacks, PiAuthResult} from '../src/pi-auth-bridge.js';

const MAIN = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const encodedArguments = (args: unknown) => Buffer.from(JSON.stringify(args)).toString('base64url');
const args = ['--java', resolve('trusted-java'), '--java-args-base64', encodedArguments(['-Duser.home=trusted space', '-cp', 'trusted cp', MAIN, '--stdio']),
  '--cwd', resolve('.'), '--provider', 'openai', '--profile', 'default', '--auth-method', 'API_KEY'];
const tick = () => new Promise<void>(r => setImmediate(r));
class FakeTTY extends PassThrough {
  isTTY = true; isRaw = false;
  setRawMode = vi.fn((raw: boolean) => { this.isRaw = raw; return this; });
}
function harness(pipe = false, oauth = false) {
  const input = new FakeTTY(); input.isTTY = !pipe;
  let text = '';
  const output = Object.assign(new Writable({write: (chunk, _encoding, done) => { text += String(chunk); done(); }}), {isTTY: !pipe});
  const signals = new EventEmitter();
  let callbacks!: PiAuthCallbacks;
  let finish!: (r: PiAuthResult) => void;
  const result = new Promise<PiAuthResult>(r => { finish = r; });
  const bridge = {login: vi.fn((_id, cb: PiAuthCallbacks) => { callbacks = cb; return result; }),
    submit: vi.fn((_id: number, bytes: Uint8Array) => { submitted.push(Buffer.from(bytes)); refs.push(bytes); return true; }),
    cancel: vi.fn(() => finish({status: 'cancelled', code: 'CANCELLED'}))};
  const submitted: Buffer[] = [], refs: Uint8Array[] = [];
  const argv = [...args]; if (pipe) argv.push('--api-key-stdin');
  if (oauth) { argv[7] = 'openai-codex'; argv[11] = 'OAUTH'; }
  const createBridge = vi.fn(() => bridge);
  const run = (environment?: Record<string, string>) => runPiAuthCli(argv, {input, output, signals, createBridge,
    ...(environment === undefined ? {} : {environment})});
  const prompt = (id = 1) => callbacks.onPrompt({promptId: id, kind: oauth ? 'manual_code' : 'secret'});
  const stored = () => finish({status: 'stored', receipt: {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY', authEpoch: '9007199254740993'}});
  return {input, output, signals, bridge, createBridge, submitted, refs, run, prompt, stored, finish,
    callbacks: () => callbacks, text: () => text};
}

describe('Pi auth CLI frozen arguments', () => {
  it('passes only trusted spec and fixed identity, no activation command', () => {
    const parsed = parsePiAuthCliArguments(args);
    expect(parsed.spec.env).toEqual({});
    expect(parsed.spec.args).toEqual(JSON.parse(Buffer.from(args[3]!, 'base64url').toString('utf8')));
    expect(parsed.identity).toEqual({backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'});
    expect(JSON.stringify(parsed)).not.toMatch(/secret|activate|select/);
  });
  it.each([
    [...args, '--secret', 'synthetic'], [...args, '--api-key-stdin', '--api-key-stdin'],
    args.map((s, i) => i === 1 ? 'relative-java' : s),
    args.map((s, i) => i === 3 ? encodedArguments([1]) : s),
    args.map((s, i) => i === 3 ? encodedArguments(['wrong.Main', '--stdio']) : s),
    args.map((s, i) => i === 9 ? '../profile' : s),
    args.map((s, i) => i === 11 ? 'OAUTH' : s),
    args.map((s, i) => i === 4 ? '--java' : s),
    args.map((s, i) => i === 3 ? args[3] + '=' : s),
    args.map((s, i) => i === 3 ? Buffer.from([0xff]).toString('base64url') : s),
    args.map((s, i) => i === 2 ? '--java-args-json' : s),
  ].map(argv => [argv]))('rejects malformed argv %#', argv => expect(() => parsePiAuthCliArguments(argv)).toThrow());
});

describe('Pi auth CLI input ownership', () => {
  it('私有helper保留显式代理但不携带密钥，并在父Java期限前预留TTY清理时间', async () => {
    const h = harness(); const running = h.run({HTTPS_PROXY: 'http://127.0.0.1:32123',
      OPENAI_API_KEY: 'synthetic-not-forwarded', NODE_OPTIONS: '--synthetic-untrusted-option'});
    expect(h.createBridge).toHaveBeenCalledWith(expect.objectContaining({env: {HTTPS_PROXY: 'http://127.0.0.1:32123'}}),
      {timeoutMs: PI_AUTH_CLI_LOGIN_TIMEOUT_MS});
    expect(PI_AUTH_CLI_LOGIN_TIMEOUT_MS + 4500 + 250).toBeLessThan(298000);
    h.signals.emit('SIGTERM'); expect(await running).toBe(1); expect(h.input.isRaw).toBe(false);
  });
  it.each(['input', 'output'] as const)('refuses missing %s TTY before bridge start', async which => {
    const h = harness(); h[which].isTTY = false;
    expect(await h.run()).toBe(2); expect(h.createBridge).not.toHaveBeenCalled();
  });
  it('refuses pipe OAuth without displaying authorization URLs', async () => {
    const h = harness(true, true); expect(await h.run()).toBe(2); expect(h.createBridge).not.toHaveBeenCalled();
  });
  it('does not attach data listener or consume stdin until auth.prompt', async () => {
    const h = harness(true); const result = h.run();
    h.input.write(Buffer.from('synthetic\n')); await tick();
    expect(h.input.listenerCount('data')).toBe(0); expect(h.input.readableLength).toBe(10);
    expect(h.bridge.submit).not.toHaveBeenCalled(); expect(h.bridge.login).toHaveBeenCalledOnce();
    h.prompt(); await tick(); expect(h.bridge.submit).not.toHaveBeenCalled();
    h.input.end(); await tick(); expect(h.submitted[0]?.toString()).toBe('synthetic');
    h.stored(); expect(await result).toBe(0); expect(h.text()).not.toContain('synthetic');
  });
  it.each([false, true])('masks input and erases backspace/submit buffers (oauth=%s)', async oauth => {
    const h = harness(false, oauth); const result = h.run();
    h.prompt(); h.input.write(Buffer.from('abc\x7fd\r')); await tick();
    expect(h.submitted[0]?.toString()).toBe('abd'); expect(h.refs[0]?.every(b => b === 0)).toBe(true);
    expect(h.text()).not.toContain('abd'); expect(h.text()).not.toContain('abc');
    h.input.write(Buffer.from('\r')); expect(h.bridge.submit).toHaveBeenCalledTimes(1);
    h.stored(); expect(await result).toBe(0);
    expect(h.input.setRawMode.mock.calls).toEqual([[true], [false]]);
    expect(h.input.listenerCount('data')).toBe(0); expect(h.signals.eventNames()).toEqual([]);
    expect(h.text()).toContain('尚未验证'); expect(h.text()).not.toContain('9007199254740993');
  });
  it.each([3, 27])('cancels with byte %s and restores previous raw state', async byte => {
    const h = harness(); h.input.isRaw = true; const result = h.run(); h.prompt();
    h.input.write(Buffer.from([65, byte])); expect(await result).toBe(1);
    expect(h.bridge.cancel).toHaveBeenCalledOnce(); expect(h.bridge.submit).not.toHaveBeenCalled();
    expect(h.input.isRaw).toBe(true); expect(h.input.listenerCount('data')).toBe(0);
  });
  it.each(['SIGINT', 'SIGTERM', 'SIGHUP', 'close'])('cancels %s', async event => {
    const h = harness(); const result = h.run(); h.prompt();
    if (event === 'close') h.input.emit(event); else h.signals.emit(event);
    expect(await result).toBe(1); expect(h.input.isRaw).toBe(false);
  });
  it('revokes old prompt before allowing a new prompt', async () => {
    const h = harness(); const result = h.run(); h.prompt(); h.input.write(Buffer.from('old'));
    h.callbacks().onPromptCancelled(1); h.input.write(Buffer.from('\r'));
    h.prompt(2); h.input.write(Buffer.from('new\r')); expect(h.submitted[0]?.toString()).toBe('new');
    expect(h.bridge.submit.mock.calls[0]?.[0]).toBe(2); h.stored(); expect(await result).toBe(0);
  });
  it.each([Buffer.alloc(16385, 65), Buffer.from('one\ntwo\n'), Buffer.from('bad\t'), Buffer.from([0xff])])('rejects hostile pipe input %#', async bytes => {
    const h = harness(true); const result = h.run(); h.prompt(); h.input.end(bytes);
    expect(await result).toBe(1); expect(h.bridge.submit).not.toHaveBeenCalled();
  });
  it.each([Buffer.alloc(16385, 65), Buffer.from('one\rtwo\r')])('rejects hostile TTY input %#', async bytes => {
    const h = harness(); const result = h.run(); h.prompt(); h.input.write(bytes);
    expect(await result).toBe(1); expect(h.bridge.submit).not.toHaveBeenCalled();
  });
  it.each(['', '\n', 'abc\r'])('requires nonempty ASCII and complete single line/EOF %j', async text => {
    const h = harness(true); const result = h.run(); h.prompt(); h.input.end(text);
    expect(await result).toBe(1); expect(h.bridge.submit).not.toHaveBeenCalled();
  });
  it.each(['abc', 'abc\n', 'abc\r\n', 'a'.repeat(16384)])('accepts bounded EOF input %#', async text => {
    const h = harness(true); const result = h.run(); h.prompt(); h.input.end(text); await tick();
    expect(h.bridge.submit).toHaveBeenCalledOnce(); h.stored(); expect(await result).toBe(0);
  });
  it('shows auth URL only on TTY and never receipt epoch', async () => {
    const h = harness(false, true); const result = h.run();
    h.callbacks().onAuthorizationUrl('https://auth.openai.com/authorize?fixture');
    expect(h.text()).toContain('https://auth.openai.com/'); h.stored(); expect(await result).toBe(0);
  });
  it('bounds unknown cleanup and restores TTY without claiming cleanup success', async () => {
    vi.useFakeTimers();
    try {
      const h = harness(); h.bridge.cancel.mockImplementation(() => {});
      const result = h.run(); h.signals.emit('SIGINT');
      await vi.advanceTimersByTimeAsync(4501);
      expect(await result).toBe(1); expect(h.input.isRaw).toBe(false);
      expect(h.text()).toContain('清理结果可能需要核对');
    } finally { vi.useRealTimers(); }
  });
  it('preserves unrelated signal listeners and rejects existing input readers', async () => {
    const h = harness(); const other = vi.fn(); h.signals.on('SIGINT', other);
    const result = h.run(); h.stored(); expect(await result).toBe(0);
    expect(h.signals.listeners('SIGINT')).toEqual([other]);
    const busy = harness(); busy.input.on('data', other);
    expect(await busy.run()).toBe(2); expect(busy.createBridge).not.toHaveBeenCalled();
    busy.input.removeListener('data', other);
  });
  it('does not promote a late stored after local cancellation', async () => {
    const h = harness(); h.bridge.cancel.mockImplementation(() => {});
    const result = h.run(); h.signals.emit('SIGINT'); h.stored(); expect(await result).toBe(1);
  });
});
