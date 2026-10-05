import {EventEmitter} from 'node:events';
import type {ChildProcess, SpawnOptions} from 'node:child_process';
import {PassThrough} from 'node:stream';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {
  ProviderLoginBridge as RuntimeProviderLoginBridge,
  type ProviderLoginBridgeOptions,
  type ChildProcessSpec,
  type ProviderLoginRequest,
} from '../src/stdio-client.js';

// Console兼容契约在明确的非Windows平台运行；Windows真实交接已证伪，必须另测失败关闭。
class ProviderLoginBridge extends RuntimeProviderLoginBridge {
  constructor(spec: ChildProcessSpec, options: ProviderLoginBridgeOptions = {}) {
    super(spec, {platform: 'linux', ...options});
  }
}
const MAIN = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const SPEC: ChildProcessSpec = {
  executable: 'java',
  args: ['-cp', 'fixed-classpath', MAIN, '--stdio'],
  cwd: 'fixed-workspace',
  env: {PATH: 'fixed-path'},
};
const STORE: ProviderLoginRequest = {
  providerId: 'anthropic',
  profileId: 'default',
  secretSource: 'store',
};
const DEFAULT_STORE: ProviderLoginRequest = {...STORE, setDefault: true};

class FakeChild extends EventEmitter {
  readonly kill = vi.fn(() => true);
  readonly stdin = new PassThrough();
  readonly stderr = new PassThrough();
}

function terminal(isTTY = true) {
  return {
    isTTY,
    isRaw: true,
    pause: vi.fn(),
    resume: vi.fn(),
    setRawMode: vi.fn(),
  };
}

function spawnFixture(child: FakeChild, failSynchronously = false) {
  return vi.fn((executable: string, args: readonly string[], options: SpawnOptions) => {
    if (failSynchronously) throw new Error('spawn failed');
    return child as unknown as ChildProcess;
  });
}

afterEach(() => vi.useRealTimers());

describe('ProviderLoginBridge', () => {
  it.each((['linux', 'win32'] as const).flatMap(platform =>
    (['stdin', 'env', 'browser'] as const).map(mode => ({platform, mode}))))('$platform/$mode 不交接 Ink 终端，只有秘密使用私有 stdin pipe', async ({platform, mode}) => {
    const child = new FakeChild(); const spawnProcess = spawnFixture(child); const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {platform, spawnProcess, terminal: tty});
    const request: ProviderLoginRequest = mode === 'stdin'
      ? {...STORE, secretSource: 'stdin', secretBytes: Buffer.from('synthetic-key')}
      : mode === 'env' ? {...STORE, secretSource: 'env', environmentName: 'TEST_KEY'}
        : {...STORE, providerId: 'openrouter', authMethod: 'openrouter-browser'};
    const result = bridge.login(request);
    expect(bridge.active()).toBe(true);
    expect(spawnProcess.mock.calls[0]?.[2].stdio).toEqual([mode === 'stdin' ? 'pipe' : 'ignore', 'inherit', 'pipe']);
    expect(tty.pause).not.toHaveBeenCalled(); expect(tty.setRawMode).not.toHaveBeenCalled();
    child.emit('exit', 0, null);
    await expect(result).resolves.toEqual({status: 'succeeded', exitCode: 0});
    expect(tty.pause).not.toHaveBeenCalled(); expect(tty.resume).not.toHaveBeenCalled();
    expect(tty.setRawMode).not.toHaveBeenCalled();
  });

  it('验证失败也清零独立stdin缓冲，不启动helper', async () => {
    const spawnProcess = spawnFixture(new FakeChild());
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess, terminal: terminal()});
    const bytes = Uint8Array.from([65, 66, 67]);
    await expect(bridge.login({...STORE, providerId: 'invalid provider', secretSource: 'stdin', secretBytes: bytes})).rejects.toThrow();
    expect([...bytes]).toEqual([0, 0, 0]); expect(spawnProcess).not.toHaveBeenCalled();
  });
  it('Windows共享Console在暂停终端或spawn之前失败关闭', async () => {
    const spawnProcess = spawnFixture(new FakeChild()); const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {platform: 'win32', spawnProcess, terminal: tty});
    await expect(bridge.login(STORE)).rejects.toThrow('Windows共享终端Console交接不安全');
    expect(spawnProcess).not.toHaveBeenCalled(); expect(tty.pause).not.toHaveBeenCalled();
  });
  it('OpenRouter browser 仅追加公开意图并保留受信JVM桥位置，不携带凭证', async () => {
    const child = new FakeChild();
    const spawnProcess = spawnFixture(child);
    const bridge = new ProviderLoginBridge({...SPEC, args: ['-Dcodej.piBridge=fixed/login.mjs', ...SPEC.args]}, {platform: 'win32', spawnProcess, terminal: terminal()});
    const result = bridge.login({...STORE, providerId: 'openrouter', authMethod: 'openrouter-browser'});
    child.emit('exit', 0, null);
    await expect(result).resolves.toEqual({status: 'succeeded', exitCode: 0});
    expect(spawnProcess.mock.calls[0]?.[1]).toEqual(['-Dcodej.piBridge=fixed/login.mjs', '-cp', 'fixed-classpath', MAIN,
      'auth', 'login', '--provider', 'openrouter', '--profile', 'default', '--browser', '--tui-preview']);
  });

  it('browser 拒绝其他Provider及ENV混用，不能静默改为其他认证方法', async () => {
    const spawnProcess = spawnFixture(new FakeChild());
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess, terminal: terminal()});
    await expect(bridge.login({...STORE, authMethod: 'openrouter-browser'})).rejects.toThrow();
    await expect(bridge.login({...STORE, providerId: 'openrouter', authMethod: 'openrouter-browser', secretSource: 'env', environmentName: 'TEST_KEY'})).rejects.toThrow();
    expect(spawnProcess).not.toHaveBeenCalled();
  });
  it('只从固定 Java 主类派生参数且使用继承终端与 shell=false', async () => {
    const child = new FakeChild();
    const spawnProcess = spawnFixture(child);
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess, terminal: tty});

    const result = bridge.login(STORE);
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({status: 'succeeded', exitCode: 0});
    expect(spawnProcess).toHaveBeenCalledWith('java', [
      '-cp', 'fixed-classpath', MAIN, 'auth', 'login',
      '--provider', 'anthropic', '--profile', 'default', '--tui-preview',
    ], expect.objectContaining({
      cwd: 'fixed-workspace', env: SPEC.env, shell: false,
      stdio: ['inherit', 'inherit', 'pipe'], windowsHide: false,
    }));
    expect(tty.pause).toHaveBeenCalledTimes(1);
    expect(tty.resume).toHaveBeenCalledTimes(1);
    expect(tty.setRawMode.mock.calls).toEqual([[false], [true]]);
    expect(bridge.active()).toBe(false);
  });

  it('仅在显式 setDefault=true 时追加持久默认参数，旧请求保持兼容', async () => {
    const defaultChild = new FakeChild();
    const defaultSpawn = spawnFixture(defaultChild);
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess: defaultSpawn, terminal: terminal()});
    const result = bridge.login(DEFAULT_STORE);
    defaultChild.emit('exit', 0, null);
    await result;
    expect(defaultSpawn.mock.calls[0]?.[1]).toEqual([
      '-cp', 'fixed-classpath', MAIN, 'auth', 'login',
      '--provider', 'anthropic', '--profile', 'default', '--tui-preview', '--set-default',
    ]);

    const legacyChild = new FakeChild();
    const legacySpawn = spawnFixture(legacyChild);
    const legacy = new ProviderLoginBridge(SPEC, {spawnProcess: legacySpawn, terminal: terminal()});
    const legacyResult = legacy.login(STORE);
    legacyChild.emit('exit', 0, null);
    await legacyResult;
    expect(legacySpawn.mock.calls[0]?.[1]).not.toContain('--set-default');
  });

  it('只接受 Java 生成的固定格式脱敏摘要', async () => {
    const child = new FakeChild();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const result = bridge.login(STORE);
    child.stderr.write('CODEJ_CREDENTIAL_PREVIEW=sk-...a9K2\n');
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({
      status: 'succeeded', exitCode: 0, credentialPreview: 'sk-…a9K2',
    });
  });

  it('Windows一次性stdin仍可用，写入后立即清零调用方缓冲', async () => {
    const child = new FakeChild();
    const chunks: Buffer[] = [];
    child.stdin.on('data', chunk => chunks.push(Buffer.from(chunk)));
    const secretBytes = Buffer.from('sk-protected-a9K2');
    const bridge = new ProviderLoginBridge(SPEC, {
      platform: 'win32', spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const result = bridge.login({...STORE, secretSource: 'stdin', secretBytes});
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({status: 'succeeded', exitCode: 0});
    expect(Buffer.concat(chunks).toString('utf8')).toBe('sk-protected-a9K2\n');
    expect(secretBytes.every(byte => byte === 0)).toBe(true);
  });

  it('spawn 同步失败也只恢复一次终端并返回 typed failed', async () => {
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(new FakeChild(), true), terminal: tty,
    });

    await expect(bridge.login(STORE)).resolves.toEqual({status: 'failed', exitCode: null});
    expect(tty.pause).toHaveBeenCalledTimes(1);
    expect(tty.resume).toHaveBeenCalledTimes(1);
    expect(tty.setRawMode.mock.calls).toEqual([[false], [true]]);
  });

  it('spawn error 与随后 exit 竞争时只完成并恢复一次', async () => {
    const child = new FakeChild();
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess: spawnFixture(child), terminal: tty});

    const result = bridge.login(STORE);
    child.emit('error', new Error('late spawn error'));
    child.emit('exit', 1, null);

    await expect(result).resolves.toEqual({status: 'failed', exitCode: null});
    expect(tty.resume).toHaveBeenCalledTimes(1);
  });

  it('cancel 杀死活动进程并稳定胜出 exit', async () => {
    const child = new FakeChild();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const result = bridge.login(STORE);
    bridge.cancel();
    child.emit('exit', null, 'SIGTERM');

    await expect(result).resolves.toEqual({status: 'cancelled', exitCode: null});
    expect(child.kill).toHaveBeenCalledTimes(1);
  });

  it('timeout 杀死活动进程且迟到 exit 不改写结果', async () => {
    vi.useFakeTimers();
    const child = new FakeChild();
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {
      timeoutMs: 1_000, spawnProcess: spawnFixture(child), terminal: tty,
    });

    const result = bridge.login(STORE);
    await vi.advanceTimersByTimeAsync(1_000);
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({status: 'timed_out', exitCode: null});
    expect(child.kill).toHaveBeenCalledTimes(1);
    expect(tty.resume).toHaveBeenCalledTimes(1);
  });

  it('终止未确认也有界恢复终端，但实际exit前不得启动第二个登录', async () => {
    vi.useFakeTimers();
    const child = new FakeChild();
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {timeoutMs: 1_000, spawnProcess: spawnFixture(child), terminal: tty});
    const result = bridge.login(STORE);
    await vi.advanceTimersByTimeAsync(2_000);
    await expect(result).resolves.toEqual({status: 'timed_out', exitCode: null});
    expect(tty.resume).toHaveBeenCalledTimes(1);
    expect(bridge.active()).toBe(true);
    await expect(bridge.login(STORE)).rejects.toThrow('已有 Provider login');
    child.emit('exit', 0, null);
    expect(bridge.active()).toBe(false);
    expect(tty.resume).toHaveBeenCalledTimes(1);
  });

  it('并发登录在 spawn 前即被拒绝', async () => {
    const child = new FakeChild();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const first = bridge.login(STORE);
    await expect(bridge.login(STORE)).rejects.toThrow('已有 Provider login 正在执行');
    child.emit('exit', 0, null);
    await expect(first).resolves.toEqual({status: 'succeeded', exitCode: 0});
  });

  it('STORE 在非 TTY fail closed，ENV 仅接受合法名称', async () => {
    const spawnProcess = spawnFixture(new FakeChild());
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess, terminal: terminal(false)});

    await expect(bridge.login(STORE)).rejects.toThrow(
      'STORE 登录需要可交互 TTY；可改用 /connect <provider> <profile> env <ENV_NAME>',
    );
    expect(spawnProcess).not.toHaveBeenCalled();
    await expect(bridge.login({...STORE, secretSource: 'env', environmentName: 'bad-name'}))
      .rejects.toThrow('ENV name 无效');
  });

  it('要求唯一主类并以唯一 --stdio 结尾，派生登录时不继承 workspace/model 文本', async () => {
    expect(() => new ProviderLoginBridge({...SPEC, args: [...SPEC.args, '--model', 'untrusted']}))
      .toThrow('以 --stdio 结尾');
    expect(() => new ProviderLoginBridge({...SPEC, args: ['-cp', 'x', MAIN, '--print']}))
      .toThrow('以 --stdio 结尾');
    const child = new FakeChild();
    const spawnProcess = spawnFixture(child);
    const bridge = new ProviderLoginBridge({...SPEC, args: [
      '-cp', 'fixed-classpath', MAIN, '--workspace', 'untrusted-workspace', '--model', 'untrusted-model', '--stdio',
    ]}, {spawnProcess, terminal: terminal()});
    const result = bridge.login(STORE);
    child.emit('exit', 0, null);
    await result;
    expect(spawnProcess.mock.calls[0]?.[1]).not.toContain('untrusted-workspace');
    expect(spawnProcess.mock.calls[0]?.[1]).not.toContain('untrusted-model');
  });
});
