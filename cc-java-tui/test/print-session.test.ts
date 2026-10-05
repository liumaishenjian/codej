import {PassThrough} from 'node:stream';
import {fileURLToPath} from 'node:url';
import {describe, expect, it, vi} from 'vitest';
import type {ProtocolEvent} from '../src/protocol.js';
import {parseJavaRunTimeoutMillis, runNonInteractive} from '../src/print-session.js';
import {StdioClient} from '../src/stdio-client.js';

const fixture = fileURLToPath(new URL('./fixtures/fake-stdio-child.mjs', import.meta.url));

describe('runNonInteractive', () => {
  it('从结构化 Java argv 严格解析现有 CliDuration 安全子集', () => {
    expect(parseJavaRunTimeoutMillis(['main', '--timeout', '250ms', '--stdio'])).toBe(250);
    expect(parseJavaRunTimeoutMillis(['main', '--timeout', '30s', '--stdio'])).toBe(30_000);
    expect(parseJavaRunTimeoutMillis(['main', '--timeout', '5m', '--stdio'])).toBe(300_000);
    expect(parseJavaRunTimeoutMillis(['main', '--timeout', 'PT1M30S', '--stdio'])).toBe(90_000);
    expect(parseJavaRunTimeoutMillis(['main', '--timeout', 'PT0.25S', '--stdio'])).toBe(250);
    expect(() => parseJavaRunTimeoutMillis(['main', '--stdio'])).toThrow(/唯一 --timeout/u);
    expect(() => parseJavaRunTimeoutMillis(['main', '--timeout', '2s', '--timeout', '3s', '--stdio']))
      .toThrow(/唯一 --timeout/u);
    expect(() => parseJavaRunTimeoutMillis(['main', '--timeout=2s', '--stdio']))
      .toThrow(/唯一 --timeout/u);
    expect(() => parseJavaRunTimeoutMillis(['main', '--timeout', '-2s', '--stdio']))
      .toThrow(/语法无效/u);
    expect(() => parseJavaRunTimeoutMillis(['main', '--timeout', 'PT1D', '--stdio']))
      .toThrow(/语法无效/u);
    expect(() => parseJavaRunTimeoutMillis(['main', '--timeout', '31m', '--stdio']))
      .toThrow(/10ms\.\.30m/u);
  });

  it('非 TTY 路径输出纯文本而不是 ANSI', async () => {
    const output = new PassThrough();
    let text = '';
    output.setEncoding('utf8');
    output.on('data', chunk => {
      text += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(client, '你好', output);

    expect(code).toBe(0);
    expect(text).toBe('你好 agent\n');
    expect(client.isClosed()).toBe(true);
    expect(client.hasProcessExited()).toBe(true);
    expect(text).not.toMatch(/\u001B\[/u);
  });

  it('永不发 terminal 的 child 在独立 deadline 后有界退出且 PID 消失', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'never-terminal'],
      cwd: process.cwd(),
    }, {shutdownTimeoutMs: 50});
    const pid = client.processId();
    const started = performance.now();

    const code = await runNonInteractive(
      client, 'never terminal', output, diagnosticOutput,
      {runTimeoutMs: 40, startupGraceMs: 20},
    );

    expect(code).toBe(1);
    expect(diagnostic).toBe('cc-java: run timed out\n');
    expect(performance.now() - started).toBeLessThan(1_000);
    expect(client.hasProcessExited()).toBe(true);
    if (pid !== undefined) expect(isProcessAlive(pid)).toBe(false);
  });

  it('正常 terminal 先到会取消独立 watchdog', async () => {
    // 验证时序而非要求操作系统必须在200ms内启动子进程；真实退出由上面的跨进程用例覆盖。
    vi.useFakeTimers();
    try {
      const output = new PassThrough();
      const diagnosticOutput = new PassThrough();
      let diagnostic = '';
      diagnosticOutput.setEncoding('utf8');
      diagnosticOutput.on('data', chunk => {
        diagnostic += chunk;
      });
      const client = new EmittingClient();
      const result = runNonInteractive(
        client, 'normal terminal', output, diagnosticOutput,
        {runTimeoutMs: 200, startupGraceMs: 0},
      );
      client.emitEvent({version: 1, type: 'initialized', payload: {}, sequence: 1, requestId: 'init', sessionId: 's'});
      client.emitEvent({version: 1, type: 'run.completed', payload: {}, sequence: 2, requestId: 'run', sessionId: 's', runId: 'r'});
      await vi.advanceTimersByTimeAsync(250);
      expect(client.closeCalls).toBe(1);
      client.emitExit();
      expect(await result).toBe(0);
      await vi.advanceTimersByTimeAsync(250);
      expect(diagnostic).toBe('');
      expect(client.closeCalls).toBe(1);
    } finally {vi.useRealTimers();}
  });

  it('failure 与 watchdog 竞态只保留首先收敛的一条诊断', async () => {
    const client = new EmittingClient();
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });

    const result = runNonInteractive(
      client, 'failure race', output, diagnosticOutput,
      {runTimeoutMs: 10, startupGraceMs: 0},
    );
    setTimeout(() => client.emitFailure('late transport failure'), 10);
    await new Promise(resolve => setTimeout(resolve, 30));
    client.emitExit();

    await expect(result).resolves.toBe(1);
    expect(['cc-java: run timed out\n', 'cc-java: late transport failure\n']).toContain(diagnostic);
    expect(diagnostic.trim().split('\n')).toHaveLength(1);
    expect(client.closeCalls).toBe(1);
  });

  it('非 TTY 超时输出固定诊断并返回失败退出码', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let text = '';
    let diagnostic = '';
    output.setEncoding('utf8');
    diagnosticOutput.setEncoding('utf8');
    output.on('data', chunk => {
      text += chunk;
    });
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'time-limit'],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(
      client,
      'timeout',
      output,
      diagnosticOutput,
    );

    expect(code).toBe(1);
    expect(text).toBe('');
    expect(diagnostic).toBe('cc-java: run timed out\n');
  });

  it.each([
    [
      'provider-configuration-required',
      'cc-java: Provider 配置不可用；请运行 /connect 或 codej auth login\n',
    ],
    ['provider-error', 'cc-java: 模型服务调用失败；请检查 Provider 状态\n'],
  ] as const)('%s 使用准确且不混淆的固定诊断', async (mode, expectedDiagnostic) => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, mode],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(client, 'provider failure', output, diagnosticOutput);

    expect(code).toBe(1);
    expect(diagnostic).toBe(expectedDiagnostic);
    expect(client.hasProcessExited()).toBe(true);
  });

  it('run.failed 后 Java 忽略 shutdown 时有界强制退出且不残留 child', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'time-limit-ignore-shutdown'],
      cwd: process.cwd(),
    }, {shutdownTimeoutMs: 100});
    const pid = client.processId();
    const started = performance.now();

    const code = await runNonInteractive(client, 'timeout', output, diagnosticOutput);

    expect(code).toBe(1);
    expect(diagnostic).toBe('cc-java: run timed out\n');
    expect(performance.now() - started).toBeLessThan(1_000);
    expect(client.isClosed()).toBe(true);
    if (pid !== undefined) expect(() => process.kill(pid, 0)).toThrow();
  });

  it('真实 fake protocol.error 在 child exit 前不 resolve 且最终 PID 消失', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'protocol-error-stay-alive'],
      cwd: process.cwd(),
    }, {shutdownTimeoutMs: 100});
    const pid = client.processId();
    let resolved = false;

    const result = runNonInteractive(client, 'protocol error', output, diagnosticOutput)
      .then(code => {
        resolved = true;
        return code;
      });
    await waitFor(() => diagnostic.length > 0);

    expect(resolved).toBe(false);
    expect(pid).toBeDefined();
    if (pid !== undefined) expect(isProcessAlive(pid)).toBe(true);
    await expect(result).resolves.toBe(1);
    expect(diagnostic).toBe('cc-java: Java 返回协议错误\n');
    if (pid !== undefined) expect(isProcessAlive(pid)).toBe(false);
  });

  it('真实协议 decode failure 在 child exit 前不 resolve 且只输出一个诊断', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'initialize-protocol-failure'],
      cwd: process.cwd(),
    }, {shutdownTimeoutMs: 100});
    const pid = client.processId();
    let resolved = false;

    const result = runNonInteractive(client, 'decode failure', output, diagnosticOutput)
      .then(code => {
        resolved = true;
        return code;
      });
    await waitFor(() => diagnostic.length > 0);

    expect(resolved).toBe(false);
    await expect(result).resolves.toBe(1);
    expect(diagnostic).toBe('cc-java: Java stdout 包含无效 JSON\n');
    if (pid !== undefined) expect(isProcessAlive(pid)).toBe(false);
  });

  it('client.emit failure 在异步 child exit 前不 resolve', async () => {
    const client = new EmittingClient();
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    let resolved = false;

    const result = runNonInteractive(client, 'emit failure', output, diagnosticOutput)
      .then(code => {
        resolved = true;
        return code;
      });
    client.emitFailure('Java 子进程 stdin 连接失败');
    await Promise.resolve();

    expect(resolved).toBe(false);
    expect(client.closeCalls).toBe(1);
    expect(diagnostic).toBe('cc-java: Java 子进程 stdin 连接失败\n');
    client.emitExit();
    await expect(result).resolves.toBe(1);
    expect(diagnostic).toBe('cc-java: Java 子进程 stdin 连接失败\n');
  });

  it('initialize throw 仍等待 close 完成并保持原始失败退出码', async () => {
    const client = new EmittingClient();
    client.initializeError = new Error('initialize failed');
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    let resolved = false;

    const result = runNonInteractive(client, 'initialize failure', output, diagnosticOutput)
      .then(code => {
        resolved = true;
        return code;
      });
    await Promise.resolve();

    expect(resolved).toBe(false);
    expect(client.closeCalls).toBe(1);
    expect(diagnostic).toBe('cc-java: 无法初始化 Java 子进程连接\n');
    client.emitExit();
    await expect(result).resolves.toBe(1);
  });

  it('非 TTY 输出长度终止使用固定诊断', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'output-limit'],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(
      client,
      'long answer',
      output,
      diagnosticOutput,
    );

    expect(code).toBe(1);
    expect(diagnostic).toBe('cc-java: output limit reached\n');
  });

  it('Java 在活动 Run 中崩溃时返回固定失败而不是输出 Node 堆栈', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'crash'],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(
      client,
      'crash',
      output,
      diagnosticOutput,
    );

    expect(code).toBe(1);
    expect(diagnostic).toMatch(
      /^cc-java: Java 子进程意外退出（exit=17，stderr=0 bytes）\n$/u,
    );
    expect(diagnostic).not.toContain('Error:');
    expect(client.isClosed()).toBe(true);
  });
});

class EmittingClient {
  readonly #events = new Set<(event: ProtocolEvent) => void>();
  readonly #failures = new Set<(message: string) => void>();
  readonly #exits = new Set<(result: {
    code: number | null;
    signal: NodeJS.Signals | null;
    stderrBytes: number;
  }) => void>();
  #closeResolve: (() => void) | undefined;
  public initializeError: Error | undefined;
  public closeCalls = 0;

  public onEvent(listener: (event: ProtocolEvent) => void): () => void {
    this.#events.add(listener);
    return () => this.#events.delete(listener);
  }

  public onFailure(listener: (message: string) => void): () => void {
    this.#failures.add(listener);
    return () => this.#failures.delete(listener);
  }

  public onExit(listener: (result: {
    code: number | null;
    signal: NodeJS.Signals | null;
    stderrBytes: number;
  }) => void): () => void {
    this.#exits.add(listener);
    return () => this.#exits.delete(listener);
  }

  public initialize(): string {
    if (this.initializeError !== undefined) throw this.initializeError;
    return 'init';
  }

  public startRun(): string {
    return 'run';
  }

  public closePrintTransport(): Promise<void> {
    this.closeCalls++;
    return new Promise<void>(resolve => {
      this.#closeResolve = resolve;
    });
  }

  public emitFailure(message: string): void {
    for (const listener of this.#failures) listener(message);
  }

  public emitEvent(event: ProtocolEvent): void {
    for (const listener of this.#events) listener(event);
  }

  public emitExit(): void {
    for (const listener of this.#exits) listener({code: 1, signal: null, stderrBytes: 0});
    this.#closeResolve?.();
  }
}

async function waitFor(condition: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = performance.now() + timeoutMs;
  while (!condition()) {
    if (performance.now() >= deadline) throw new Error('等待条件超时');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
