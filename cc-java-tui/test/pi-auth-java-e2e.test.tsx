import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import type {ChildProcess} from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {AgentTui} from '../src/app.js';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import {StdioClient, type ChildProcessSpec} from '../src/stdio-client.js';
import {attachPiAuth} from '../src/pi-auth-client.js';
import type {ProtocolEvent} from '../src/protocol.js';
import {startPiAuthSse} from './fixtures/pi-auth-sse.js';

// Transparent process observation: still real spawn, pipes, bytes and exit events, never a FakeClient/Bridge.
const observed = vi.hoisted(() => ({children: [] as {kind: string; pid: number | undefined; closed: boolean; stdout: string; stderr: string}[], commands: [] as string[]}));
vi.mock('node:child_process', async original => {
  const actual = await original<typeof import('node:child_process')>();
  return {...actual, spawn: (...args: unknown[]) => {
    const child = Reflect.apply(actual.spawn, undefined, args) as ChildProcess;
    const argv = Array.isArray(args[1]) ? args[1] as string[] : [];
    const entry = {kind: argv.includes('io.github.liumaishenjian.ccjava.cli.auth.PiAuthBridgeMain') ? 'helper' : 'host',
      pid: child.pid, closed: false, stdout: '', stderr: ''};
    observed.children.push(entry);
    child.stdout?.on('data', (bytes: Buffer) => {entry.stdout += bytes.toString('utf8');});
    child.stderr?.on('data', (bytes: Buffer) => {entry.stderr += bytes.toString('utf8');});
    child.once('close', () => {entry.closed = true;});
    return child;
  }};
});
vi.mock('../src/protocol.js', async original => {
  const actual = await original<typeof import('../src/protocol.js')>();
  return {...actual, encodeCommand: (...args: Parameters<typeof actual.encodeCommand>) => {
    const encoded = actual.encodeCommand(...args); observed.commands.push(encoded); return encoded;
  }};
});
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => ({columns: 80, rows: 24})}));
afterEach(cleanup);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const component = path.join(root, 'cc-java-provider-pi');
// 普通离线CI没有当前Java classpath时明确跳过；不得搜索用户配置或自行运行Maven。
const classpath = process.env.CC_JAVA_TEST_CLASSPATH ?? await fs.readFile(
  process.env.PI_AUTH_TEST_CLASSPATH_FILE ?? path.join(os.tmpdir(), 'pi-current-test-classpath.txt'), 'utf8')
  .then(value => value.trim(), () => undefined);
const wait = (ms = 40) => new Promise(resolve => setTimeout(resolve, ms));
const alive = (pid: number) => {try {process.kill(pid, 0); return true;} catch {return false;}};

/**
 * S15 / ADR-100 / MODEL-13 L1→L1、CLI-05/08/09现有等级不变。
 * 复用ADR-100对照表：真实公开SDK、生产两视图、StdioClient/attachPiAuth、CcJavaCliMain、
 * ProviderAuthRuntimeResources/真实Store、PiAuthBridgeMain与生产Worker操作。
 * 仅HTTP响应是独立脚本；只有真实read_file结果带file-only marker才交付最终正文。
 * 属于80×24 Ink离线+真实Java进程证据，不是index/PTY/物理终端或真实账户验证。
 * Codex浏览器、取消/失败矩阵、六尺寸、任意脱离后代不在本文件证据范围。
 */
it.skipIf(!classpath).each([
  ['default', 'openai', 0], ['next', 'openai', 0],
  ['default', 'deepseek', 2], ['next', 'deepseek', 2],
  ['default', 'qwen-token-plan-cn', 3], ['next', 'qwen-token-plan-cn', 3],
] as const)('%s / %s: real Java private login → explicit activate → SDK read/final → next turn → logout', async (mode, provider, routeIndex) => {
  expect(classpath!.replaceAll('\\', '/').toLowerCase(), 'Reject a stale or different worktree classpath')
    .toContain(path.join(root, 'cc-java-cli/target/classes').replaceAll('\\', '/').toLowerCase());
  observed.children.length = 0; observed.commands.length = 0;
  const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-pi-auth-ink-'));
  const home = path.join(temporary, 'home'), workspace = path.join(temporary, 'workspace');
  const trusted = path.join(temporary, 'trusted component'), audit = path.join(temporary, 'worker-audit.jsonl');
  await fs.mkdir(home); await fs.mkdir(workspace); await fs.mkdir(trusted);
  const marker = 'file-only-' + randomUUID();
  const canary = 'synthetic-ui-key-' + randomUUID();
  const leaksInput = (value: string) => value.includes(canary) || value.includes(canary.slice(0, 12));
  // Input fixture only; no prewritten assistant delivery or tool result.
  await fs.writeFile(path.join(workspace, 'fixture.txt'), marker + '\n', 'utf8');
  const server = await startPiAuthSse(provider, marker, canary);
  const metadata = path.join(trusted, 'node_modules/@earendil-works/pi-ai/package.json');
  await fs.mkdir(path.dirname(metadata), {recursive: true});
  await fs.copyFile(path.join(component, 'node_modules/@earendil-works/pi-ai/package.json'), metadata);
  const worker = path.join(trusted, 'worker.mjs');
  await fs.writeFile(worker, `import {runPiAuthFixture} from ${JSON.stringify(pathToFileURL(path.join(root, 'cc-java-tui/test/fixtures/pi-auth-worker.mjs')).href)};\n`
    + `await runPiAuthFixture(${JSON.stringify({component, home, origin: server.origin, audit})});\n`);
  const env: NodeJS.ProcessEnv = {CC_JAVA_REPOSITORY_ROOT: workspace};
  for (const name of ['SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'PATH']) if (process.env[name]) env[name] = process.env[name];
  const spec: ChildProcessSpec = {executable: process.env.PI_AUTH_TEST_JAVA ?? 'java', args: [
    '-Dfile.encoding=UTF-8', `-Duser.home=${home}`, `-Dcodej.nodeExecutable=${process.execPath}`, `-Dcodej.piWorker=${worker}`,
    '-cp', classpath!, 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain', '--workspace', workspace, '--stdio'], cwd: workspace, env};
  const events: ProtocolEvent[] = [], failures: string[] = [];
  const client = attachPiAuth(new StdioClient(spec), spec);
  client.onEvent(e => events.push(e)); client.onFailure(e => failures.push(e));
  const app = render(mode === 'next' ? <ExperienceRuntimeApp client={client} workspace={workspace}/> : <AgentTui client={client}/>);
  let phase = 'initialize';
  const until = async (condition: () => boolean, label: string) => {
    phase = label;
    const deadline = Date.now() + 20_000;
    while (!condition()) {
      if (Date.now() > deadline) {
        // No frame, request, secret, URL, stderr or arbitrary model failure message in diagnostics.
        throw Error(JSON.stringify({phase, events: events.slice(-8).map(e => ({type: e.type,
          ...(e.type === 'provider.control.result' ? {intent: e.payload.intent, status: e.payload.status, code: e.payload.code} : {})})),
          failureCount: failures.length, httpErrors: server.errors, httpTurns: server.requests.length,
          processes: observed.children.map(p => ({kind: p.kind, closed: p.closed}))}));
      }
      await wait();
    }
    await wait();
  };
  const visible = (text: string) => until(() => !!app.lastFrame()?.includes(text), text);
  const key = async (text: string) => {app.stdin.write(text); await wait();};
  const controls = (intent: string) => events.filter(e => e.type === 'provider.control.result' && e.payload.intent === intent);
  const auditEntries = async () => (await fs.readFile(audit, 'utf8')).trim().split('\n').filter(Boolean)
    .map(line => JSON.parse(line) as {kind: string; pid?: number; parent?: number});
  try {
    await until(() => events.some(e => e.type === 'initialized'), 'real initialized handshake');
    const initial = events.find(e => e.type === 'initialized')!;
    expect(initial.payload.piProviderV1, 'Java must actually negotiate Pi').toBe(true);
    expect(initial.payload.authLifecycleV1).toBe(true);
    expect(initial.sessionId).toBeTruthy();
    await visible('选择服务商');
    for (const label of ['OpenAI', 'Codex', 'DeepSeek', '通义', '兼容 Provider']) expect(app.lastFrame()).toContain(label);
    // Exercise bare /login through keys, not automatic startup alone.
    await key('\x1b'); await key('/login'); await key('\r'); await visible('选择服务商');
    for (let index = 0; index < routeIndex; ++index) await key('\x1b[B');
    await key('\r'); await visible('选择 Profile'); await key('\r');
    await visible('登录方式'); await key('\r');
    await visible('输入 API Key'); await key(canary);
    expect(leaksInput(app.frames.join('\n')), 'No secret or identifying prefix in Ink').toBe(false);
    await key('\r'); await visible('尚未启用');
    expect(controls('auth.activate')).toHaveLength(0);
    expect(server.requests).toHaveLength(0);
    const loginWorkers = (await auditEntries()).filter(e => e.kind === 'worker');
    expect(loginWorkers).toHaveLength(1);
    expect(alive(loginWorkers[0]!.pid!)).toBe(false);
    expect(alive(loginWorkers[0]!.parent!)).toBe(false);
    const helpers = observed.children.filter(p => p.kind === 'helper');
    expect(helpers).toHaveLength(1);
    expect(helpers[0]!.closed).toBe(true);
    const storedFrames = helpers[0]!.stdout.trim().split('\n').map(line => JSON.parse(line)).filter(frame => frame.type === 'auth.stored');
    expect(storedFrames).toHaveLength(1);
    const storedEpoch: unknown = storedFrames[0]!.payload.authEpoch;
    expect(typeof storedEpoch).toBe('string');
    expect(typeof storedEpoch === 'string' && /^[1-9][0-9]*$/.test(storedEpoch)).toBe(true);
    expect((await auditEntries()).filter(e => e.kind === 'allowed' || e.kind === 'forbidden')).toHaveLength(0);
    await key('\x1b[B'); await key('\r'); await visible('设为默认模型');
    expect(controls('auth.activate')).toHaveLength(1);
    expect(controls('auth.activate')[0]!.payload.status).toBe('succeeded');
    const command = observed.commands.map(line => JSON.parse(line)).find(c => c.payload?.intent === 'auth.activate');
    expect(typeof command?.payload?.arguments?.authEpoch, 'Epoch stays a decimal string on ordinary wire').toBe('string');
    expect(command.payload.arguments.authEpoch === storedEpoch, 'Exact helper receipt without Number conversion').toBe(true);
    await key('\r'); await visible('本机配置不表示在线验证');
    await key('Read fixture.txt using read_file and report its contents.'); await key('\r');
    await visible(server.finalText);
    await until(() => events.filter(e => e.type === 'run.completed').length === 1, 'first runtime terminal');
    expect(server.errors).toEqual([]); expect(server.requests).toHaveLength(2);
    expect(server.requests[1]!.toolResultObserved).toBe(true);
    await key('Confirm this second turn is usable.'); await key('\r'); await visible(server.continuationText);
    await until(() => events.filter(e => e.type === 'run.completed').length === 2, 'second runtime terminal');
    await key('/logout'); await key('\r'); await visible('选择明确的退出目标');
    await key('\r'); await visible('确认退出本机 Profile'); await key('\x1b[B'); await key('\r');
    await visible('本机退出已完成');
    expect(controls('auth.logout.commit')).toHaveLength(1);
    expect(controls('auth.logout.commit')[0]!.payload.status).toBe('succeeded');
    expect(controls('auth.logout.commit')[0]!.sessionId).toBe(initial.sessionId);
    expect(app.lastFrame()).toContain('Session');
    await key('\r'); await key('/login'); await key('\r'); await visible('选择服务商');
    expect(events.filter(e => e.type === 'initialized')).toHaveLength(1);
    expect(server.requests).toHaveLength(3); expect(server.errors).toEqual([]);
    expect(events.some(e => e.type === 'run.failed')).toBe(false);
    expect(failures).toEqual([]);
    expect(leaksInput(JSON.stringify(events))).toBe(false);
    expect(leaksInput(observed.commands.join('\n'))).toBe(false);
    expect(leaksInput(app.frames.join('\n'))).toBe(false);
    await client.shutdown(); app.unmount();
    await until(() => observed.children.every(p => p.closed), 'owned Java close');
    expect((await auditEntries()).filter(e => e.kind === 'worker')).toHaveLength(4);
    expect((await auditEntries()).filter(e => e.kind === 'allowed')).toHaveLength(3);
    for (const entry of await auditEntries()) {
      expect(entry.kind).not.toBe('forbidden');
      if (entry.kind === 'worker') expect(alive(entry.pid!)).toBe(false);
    }
    for (const child of observed.children) {
      expect(leaksInput(child.stdout) || leaksInput(child.stderr), 'No secret on Java output').toBe(false);
      expect(child.pid !== undefined && alive(child.pid), 'Owned Java gone').toBe(false);
    }
    const journals: string[] = [];
    const walk = async (directory: string) => {
      for (const item of await fs.readdir(directory, {withFileTypes: true})) {
        const file = path.join(directory, item.name);
        if (item.isDirectory()) await walk(file);
        else if (item.name === 'session.jsonl') journals.push(await fs.readFile(file, 'utf8'));
      }
    };
    await walk(home); await walk(workspace);
    expect(journals.length, 'Actual durable Session journal').toBeGreaterThan(0);
    expect(leaksInput(journals.join('\n'))).toBe(false);
    expect(journals.join('\n')).toContain(marker);
    // Reopen same production workspace/home after shutdown; no fake Writer or persistent lock bypass.
    const reopened = new StdioClient(spec); const reopenedEvents: ProtocolEvent[] = [];
    reopened.onEvent(e => reopenedEvents.push(e));
    try {
      reopened.initialize({authLifecycleV1: true, piProviderV1: true});
      await until(() => reopenedEvents.some(e => e.type === 'initialized'), 'new Session Writer after cleanup');
      expect(reopenedEvents.find(e => e.type === 'initialized')!.sessionId).toBeTruthy();
      expect(reopenedEvents.some(e => e.type === 'protocol.error')).toBe(false);
    } finally {await reopened.shutdown();}
  } finally {
    // Keep production cleanup bounds. Never rerun or widen them to turn failures green.
    try {await client.shutdown();} finally {
      app.unmount(); await server.close(); await fs.rm(temporary, {recursive: true, force: true});
    }
  }
}, 90_000);
