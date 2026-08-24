import {fileURLToPath} from 'node:url';
import {describe, expect, it, vi} from 'vitest';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';

const fixture = fileURLToPath(new URL('./fixtures/fake-stdio-child.mjs', import.meta.url));

describe('StdioClient', () => {
  it('通过结构化子进程完成初始化、流式输出和退出', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('你好');
    await waitFor(() => events.some(event => event.type === 'run.completed'));
    await client.shutdown();

    expect(events.map(event => event.type)).toEqual([
      'initialized',
      'run.command.result',
      'run.started',
      'model.text.delta',
      'model.text.delta',
      'run.completed',
    ]);
    expect(events.filter(event => event.type === 'model.text.delta')
      .map(event => event.payload.text).join('')).toBe('你好 agent');
  });

  it('通过真实 NDJSON 保序接收 Tool stdout/stderr 与结构化退出码', async () => {
    const client = createClient('tool-output-terminal');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('运行测试');
    await waitFor(() => events.some(event => event.type === 'run.failed'));
    await client.shutdown();

    expect(failures).toEqual([]);
    expect(events.filter(event => event.type.startsWith('tool.')).map(event => [
      event.type,
      event.payload.stream,
      event.payload.text,
      event.payload.exitCode,
    ])).toEqual([
      ['tool.started', undefined, undefined, undefined],
      ['tool.output', 'stdout', 'starting\n', undefined],
      ['tool.output', 'stderr', 'test failed\ntest failed\nother failure\n', undefined],
      ['tool.failed', undefined, undefined, 9],
    ]);
  });

  it('继续规划登记服务端返回的 requestId 为唯一预期新 Run', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    const requestId = client.resolvePlanReview({
      planId: 'plan-1', revision: 3, contentDigest: 'a'.repeat(64),
      workspaceDigest: 'b'.repeat(64), decision: 'CONTINUE_PLANNING',
      contextPolicy: 'KEEP', feedback: 'verify rollback',
    });
    await waitFor(() => events.some(event => event.type === 'run.started'));

    expect(events.find(event => event.type === 'run.started')?.requestId).toBe(requestId);
    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it.each(['APPROVE_AUTO', 'APPROVE_USER'] as const)(
    '%s 仍把服务端执行 requestId 登记为预期新 Run',
    async decision => {
      const client = createClient('plan-review-unexpected-start');
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      const requestId = client.resolvePlanReview({
        planId: 'plan-1', revision: 3, contentDigest: 'a'.repeat(64),
        workspaceDigest: 'b'.repeat(64), decision, contextPolicy: 'KEEP', feedback: '',
      });
      await waitFor(() => events.some(event => event.type === 'run.started'));
      expect(events.find(event => event.type === 'run.started')?.requestId).toBe(requestId);
      expect(failures).toEqual([]);
      await client.shutdown();
    },
  );

  it('REJECT 不把服务端意外 run.started 误登记为预期新 Run，也不伪造 transport failure', async () => {
    const client = createClient('plan-review-unexpected-start');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.resolvePlanReview({
      planId: 'plan-1', revision: 3, contentDigest: 'a'.repeat(64),
      workspaceDigest: 'b'.repeat(64), decision: 'REJECT', contextPolicy: 'KEEP', feedback: '',
    });
    await waitFor(() => events.some(event => event.type === 'protocol.error'));
    expect(events.some(event => event.type === 'run.started')).toBe(false);
    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it('无 acceptance 时 watchdog 终结本地 handshake 且不自动重放', async () => {
    const client = createClient('run-no-ack', {runHandshakeTimeoutMs: 50});
    const notices: {requestId: string; kind: string}[] = [];
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.onRunHandshake(notice => notices.push(notice));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    const requestId = client.startRun('只提交一次');
    await waitFor(() => notices.length === 1);
    expect(notices).toEqual([{requestId, kind: 'timed_out'}]);
    expect(events.some(event => event.type === 'run.started')).toBe(false);
    await client.shutdown();
  });

  it('watchdog 到期立即 fail closed，不能等待迟到 accepted 或启动不可见 Run', async () => {
    const client = createClient('run-late-ack', {runHandshakeTimeoutMs: 50});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('迟到请求');
    await waitFor(() => failures.length === 1);
    expect(failures[0]).toContain('未在期限内确认');
    expect(events.some(event => event.type === 'run.started')).toBe(false);
    expect(client.isClosed()).toBe(true);
    await client.closePrintTransport();
  });

  it('已接受但 Runtime 启动失败时终结 pending，且不伪造成 transport failure', async () => {
    const client = createClient('run-launch-failed');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    const first = client.startRun('launch failure');
    await waitFor(() => events.some(event => event.type === 'run.launch.failed'));

    expect(events.filter(event => event.requestId === first).map(event => event.type))
      .toEqual(['run.command.result', 'run.launch.failed']);
    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    expect(() => client.startRun('next request')).not.toThrow();
    await waitFor(() => events.filter(event => event.type === 'run.launch.failed').length === 2);
    await client.shutdown();
  });

  it('watchdog 后即使服务端原本会迟到 rejected 也关闭 outcome-unknown transport', async () => {
    const client = createClient('run-late-rejected', {runHandshakeTimeoutMs: 50});
    const events: ProtocolEvent[] = [];
    const notices: {requestId: string; kind: string}[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onRunHandshake(notice => notices.push(notice));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    const requestId = client.startRun('迟到拒绝');
    await waitFor(() => failures.length === 1);
    expect(notices).toEqual([{requestId, kind: 'timed_out'}]);
    expect(events.some(event => event.type === 'run.started')).toBe(false);
    expect(failures[0]).toContain('避免结果未知时重复执行');
    expect(client.isClosed()).toBe(true);
    await client.closePrintTransport();
  });

  it('acceptance 前断开连接收敛为 transport terminal', async () => {
    const client = createClient('run-disconnect', {runHandshakeTimeoutMs: 500});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('断开');
    await waitFor(() => failures.length === 1);
    expect(failures[0]).toContain('意外退出');
    expect(events.some(event => event.type === 'run.command.result')).toBe(false);
  });

  it('按实际 NDJSON 编码大小分块并保持 Unicode 无损', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    const text = `${'\\\"\n\t'.repeat(20_000)}${'中文😀'.repeat(5_000)}`;
    const requestId = client.startRun(text);
    await waitFor(() => events.some(event => event.type === 'run.started'));

    expect(events.find(event => event.type === 'run.started')?.requestId).toBe(requestId);
    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it.each(['chunk-error-begin', 'chunk-error-chunk', 'chunk-error-commit'])(
    '%s 将整条分块 submission 关联为同一拒绝并清理',
    async mode => {
      const client = createClient(mode);
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      const requestId = client.startRun('x'.repeat(100_000));
      await waitFor(() => events.some(event => event.type === 'protocol.error'));
      expect(events.find(event => event.type === 'protocol.error')?.requestId).toBe(requestId);
      expect(failures).toEqual([]);
      expect(client.isClosed()).toBe(false);
      await client.shutdown();
    },
  );

  it('初始化后请求并严格关联 Java 文件建议', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    const requestId = client.suggestFiles('space');
    await waitFor(() => events.some(event => event.type === 'file.suggestions'));
    const result = events.find(event => event.type === 'file.suggestions')!;
    expect(result.requestId).toBe(requestId);
    expect(result.payload).toEqual({query: 'space', candidates: ['dir/file name.md']});
    await client.shutdown();
  });

  it('file.suggest 协议错误会终结对应待处理请求而不耗尽上限', async () => {
    const client = createClient('suggest-error');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    for (let index = 0; index < 300; index++) {
      const requestId = client.suggestFiles(`q-${index}`);
      await waitFor(() => events.some(event =>
        event.type === 'protocol.error' && event.requestId === requestId));
    }

    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it.each([
    ['suggest-duplicate', '重复响应'],
    ['suggest-unknown-request', '未知 requestId'],
    ['suggest-wrong-session', '错配 Session'],
    ['suggest-wrong-query', '错配 query'],
  ])('file.suggestions %s 立即 fail closed', async mode => {
    const client = createClient(mode);
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.suggestFiles('src');
    await waitFor(() => failures.length === 1);

    expect(failures[0]).toContain('file.suggestions');
    expect(client.isClosed()).toBe(true);
    await client.closePrintTransport();
  });

  it('通过真实 stdio 编码 wait/cancel/keep/remove 并只消费 Java 权威事件', async () => {
    const client = createClient('task-actions');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.waitTask('task-a', 1500);
    await waitFor(() => events.filter(event => event.type === 'task.status').length === 1);
    client.cancelTask('task-a');
    await waitFor(() => events.filter(event => event.type === 'task.status').length === 2);
    client.keepTaskWorktree('task-a');
    await waitFor(() => events.some(event =>
      event.type === 'task.worktree' && event.payload.disposition === 'kept'));
    client.removeTaskWorktree('task-a');
    await waitFor(() => events.some(event =>
      event.type === 'task.worktree' && event.payload.disposition === 'removed'));

    expect(events.filter(event => event.type.startsWith('task.')).map(event => [
      event.type,
      event.payload.taskId,
      event.payload.status ?? event.payload.disposition,
    ])).toEqual([
      ['task.status', 'task-a', 'running'],
      ['task.status', 'task-a', 'cancelled'],
      ['task.worktree', 'task-a', 'kept'],
      ['task.worktree', 'task-a', 'removed'],
    ]);
    await client.shutdown();
  });

  it('真实 StdioClient 接受 models.add/remove/use 的 Java 正式结果而不触发协议失败', async () => {
    const client = createClient('provider-model-results');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.providerControl('model-add', 'models.add', {
      providerId: 'anthropic', modelId: 'model-added', setDefault: true,
    });
    client.providerControl('model-remove', 'models.remove', {
      providerId: 'anthropic', modelId: 'model-removed',
    });
    client.providerControl('model-use', 'models.use', {
      providerId: 'anthropic', profileId: 'default', modelId: 'model-used', setDefault: true,
    });
    await waitFor(() => events.filter(event => event.type === 'provider.control.result').length === 3);

    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    expect(events.filter(event => event.type === 'provider.control.result').map(event => [
      event.payload.intent, event.payload.result,
    ])).toEqual([
      ['models.add', {providerId: 'anthropic', modelId: 'model-added', setDefault: true}],
      ['models.remove', {providerId: 'anthropic', modelId: 'model-removed'}],
      ['models.use', {providerId: 'anthropic', profileId: 'default', modelId: 'model-used', setDefault: true}],
    ]);
    await client.shutdown();
  });

  it('活动 Run 可以通过命令取消', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('取消我');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    client.cancelRun();
    await waitFor(() => events.some(event => event.type === 'run.cancelled'));
    await client.shutdown();

    expect(events.filter(event => event.type.startsWith('run.')).at(-1)?.type)
      .toBe('run.cancelled');
  });

  it('把匹配的单次审批决定发送给 Java 子进程', async () => {
    const client = createClient('approval');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('修改文件');
    await waitFor(() => events.some(event => event.type === 'approval.requested'));
    const approval = events.find(event => event.type === 'approval.requested')!;
    client.resolveApproval(String(approval.payload.approvalId), 'allow_once');
    await waitFor(() => events.some(event => event.type === 'run.completed'));
    await client.shutdown();

    expect(events.map(event => event.type)).toContain('tool.completed');
    expect(approval.payload).toMatchObject({
      target: 'src/App.java',
      operation: 'modify',
      removedLines: 1,
      addedLines: 2,
    });
  });

  it('仅接受精确关联的 session command terminal result，并保留已签发 commandId', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    const requestId = client.sessionCommand('command-1', 'doctor', {});
    await waitFor(() => events.some(event => event.type === 'session.command.result'));
    expect(events.find(event => event.type === 'session.command.result')?.requestId).toBe(requestId);
    expect(() => client.sessionCommand('command-1', 'doctor', {})).toThrow(/当前连接签发/);
    await client.shutdown();
  });

  it('顺序完成的 commandId 也会消耗固定连接预算', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    for (let index = 1; index <= 256; index++) {
      client.sessionCommand(`command-${index}`, 'doctor', {});
      await waitFor(() => events.filter(event => event.type === 'session.command.result').length === index);
    }
    expect(() => client.sessionCommand('command-overflow', 'doctor', {})).toThrow(/签发数量超过上限/);
    await client.shutdown();
  });

  it('重复 commandId、错配和重复 result 均 fail closed', async () => {
    const delayed = createClient('command-delay');
    const delayedEvents: ProtocolEvent[] = [];
    delayed.onEvent(event => delayedEvents.push(event));
    delayed.initialize();
    await waitFor(() => delayedEvents.some(event => event.type === 'initialized'));
    delayed.sessionCommand('same-command', 'doctor', {});
    expect(() => delayed.sessionCommand('same-command', 'doctor', {})).toThrow(/当前连接签发/);
    await delayed.shutdown();

    for (const mode of ['command-wrong-request', 'command-duplicate-result']) {
      const client = createClient(mode);
      const failures: string[] = [];
      const events: ProtocolEvent[] = [];
      client.onFailure(message => failures.push(message));
      client.onEvent(event => events.push(event));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      client.sessionCommand('command-1', 'doctor', {});
      await waitFor(() => failures.length === 1);
      expect(failures[0]).toContain('session.command.result');
      expect(client.isClosed()).toBe(true);
      await client.closePrintTransport();
    }
  });

  it('严格关联 steering 事件，并且畸形 payload、请求或 Session 立即关闭连接', async () => {
    const valid = createClient('steering-normal');
    const validEvents: ProtocolEvent[] = [];
    valid.onEvent(event => validEvents.push(event));
    valid.initialize();
    await waitFor(() => validEvents.some(event => event.type === 'initialized'));
    valid.startRun('first');
    await waitFor(() => validEvents.some(event => event.type === 'run.started'));
    valid.startRun('UNSENT_STEERING_SECRET');
    await waitFor(() => validEvents.some(event => event.type === 'steering.discarded'));
    expect(validEvents.map(event => event.type)).toEqual([
      'initialized', 'run.command.result', 'run.started',
      'run.command.result', 'steering.queued', 'steering.discarded',
    ]);
    expect(JSON.stringify(validEvents)).not.toContain('UNSENT_STEERING_SECRET');
    await valid.shutdown();

    for (const mode of ['steering-invalid-payload', 'steering-wrong-request', 'steering-wrong-session']) {
      const client = createClient(mode);
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      client.startRun('first');
      await waitFor(() => events.some(event => event.type === 'run.started'));
      client.startRun('secret');
      await waitFor(() => failures.length === 1);
      expect(client.isClosed()).toBe(true);
      expect(failures[0]).toMatch(/steering/);
      await client.closePrintTransport();
    }
  });

  it('steering 队列满的协议拒绝只清理对应请求，连接与后续 steering 保持可用', async () => {
    const client = createClient('steering-queue-full');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('first');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    const rejectedRequestId = client.startRun('rejected');
    await waitFor(() => events.some(event => event.type === 'protocol.error'));

    expect(events.find(event => event.type === 'protocol.error')).toEqual(expect.objectContaining({
      requestId: rejectedRequestId,
      payload: {code: 'STEERING_QUEUE_FULL'},
    }));
    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    client.startRun('accepted');
    await waitFor(() => events.some(event => event.type === 'steering.discarded'));
    await client.shutdown();
  });

  it('queue-full 拒绝后的迟到 run.started fail closed，不能复活不可见 Run', async () => {
    const client = createClient('steering-queue-full-late-start');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('first');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    client.startRun('rejected');
    await waitFor(() => failures.length === 1);

    expect(events.some(event => event.type === 'protocol.error'
      && event.payload.code === 'STEERING_QUEUE_FULL')).toBe(true);
    expect(events.filter(event => event.type === 'run.started')).toHaveLength(1);
    expect(failures[0]).toContain('迟到的 run.started');
    expect(client.isClosed()).toBe(true);
    await client.closePrintTransport();
  });

  it('迟到且错配的 terminal 不会清除当前 authority Run', async () => {
    const client = createClient('steering-late-terminal');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('first');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    client.startRun('late');
    await waitFor(() => events.some(event => event.type === 'run.completed'
      && event.runId === 'run-late'));
    client.startRun('still-steering');
    await waitFor(() => events.some(event => event.type === 'steering.discarded'));

    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it('首个 run.started 延迟时仍将第二个 startRun 关联为 steering', async () => {
    const client = createClient('steering-race');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.startRun('first');
    client.startRun('second');
    await waitFor(() => events.some(event => event.type === 'steering.discarded'));
    await waitFor(() => events.some(event => event.type === 'run.started'));

    expect(events.map(event => event.type)).toEqual([
      'initialized', 'run.command.result', 'run.command.result',
      'steering.queued', 'steering.discarded', 'run.started',
    ]);
    await client.shutdown();
  });

  it('拒绝 steering 控制生命周期中的重复或乱序事件', async () => {
    for (const mode of [
      'steering-duplicate-queued',
      'steering-discarded-before-queued',
      'steering-duplicate-discarded',
    ]) {
      const client = createClient(mode);
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      client.startRun('first');
      await waitFor(() => events.some(event => event.type === 'run.started'));
      client.startRun('second');
      await waitFor(() => failures.length === 1);

      expect(client.isClosed()).toBe(true);
      expect(failures[0]).toMatch(/steering|run\.started/);
      await client.closePrintTransport();
    }
  });

  it('resume 结果未关联当前 Session 时 fail closed', async () => {
    const client = createClient('resume-mismatched-previous');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.sessionCommand('resume-1', 'resume', {sessionId: 'session-2'});
    await waitFor(() => failures.length === 1);

    expect(failures[0]).toContain('resume');
    expect(client.isClosed()).toBe(true);
    await client.closePrintTransport();
  });

  it('乱序 stdout 触发失败并终止子进程', async () => {
    const client = createClient('bad-sequence');
    const failures: string[] = [];
    const events: ProtocolEvent[] = [];
    client.onFailure(message => failures.push(message));
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('触发错误');
    await waitFor(() => failures.length > 0);

    expect(failures[0]).toContain('sequence');
    await client.closePrintTransport();
  });

  it('协议失败关闭 transport 不能伪装进程退出，Print 清理最终等待真实 exit', async () => {
    const treeKiller = vi.fn(() => true);
    const client = createClient('bad-sequence-stay-alive', {
      platform: 'win32', windowsTreeKiller: treeKiller,
    });
    const pid = client.processId();
    const failures: string[] = [];
    const events: ProtocolEvent[] = [];
    client.onFailure(message => failures.push(message));
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.startRun('先协议失败');
    await waitFor(() => failures.length === 1);

    expect(client.isClosed()).toBe(true);
    expect(client.hasProcessExited()).toBe(false);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(true);
    const started = performance.now();

    await client.closePrintTransport();

    expect(performance.now() - started).toBeGreaterThanOrEqual(75);
    expect(treeKiller).toHaveBeenCalledTimes(1);
    expect(failures).toHaveLength(1);
    expect(client.hasProcessExited()).toBe(true);
    expect(isProcessAlive(pid!)).toBe(false);
  });

  it('子进程意外崩溃转成传输失败并报告退出', async () => {
    const client = createClient('crash');
    const failures: string[] = [];
    let exited = false;
    client.onFailure(message => failures.push(message));
    client.onExit(() => {
      exited = true;
    });
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('触发崩溃');
    await waitFor(() => exited);

    expect(failures).toEqual([
      'Java 子进程意外退出（exit=17，stderr=0 bytes）',
    ]);
    expect(client.isClosed()).toBe(true);
    await client.closePrintTransport();
  });

  it('Print terminal 后关闭 stdin，Java 通过 EOF 自然退出', async () => {
    const client = createClient();
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('自然退出');
    await waitFor(() => events.some(event => event.type === 'run.completed'));

    await client.closePrintTransport();

    expect(client.isClosed()).toBe(true);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });

  it('Print Java 忽略 stdin EOF 时强制终止并等待实际退出', async () => {
    const client = createClient('time-limit-ignore-shutdown');
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('强制退出');
    await waitFor(() => events.some(event => event.type === 'run.failed'));

    await client.closePrintTransport();

    expect(client.isClosed()).toBe(true);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });

  it('Windows taskkill 失败时回退 child.kill 并等待 exit', async () => {
    const treeKiller = vi.fn(() => false);
    const client = createClient('time-limit-ignore-shutdown', {
      platform: 'win32', windowsTreeKiller: treeKiller,
    });
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('taskkill fallback');
    await waitFor(() => events.some(event => event.type === 'run.failed'));

    await client.closePrintTransport();

    expect(treeKiller).toHaveBeenCalledWith(pid);
    expect(client.isClosed()).toBe(true);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });

  it('shutdown 超时后强制终止并等待子进程实际退出', async () => {
    const client = createClient('ignore-shutdown');
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    await client.shutdown();

    expect(client.isClosed()).toBe(true);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });

  it('取消超时后终止无响应子进程且不遗留 PID', async () => {
    const client = createClient('ignore-cancel');
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('不响应取消');
    await waitFor(() => events.some(event => event.type === 'run.started'));

    client.cancelRun();
    await waitFor(() => client.isClosed());
    await client.closePrintTransport();

    expect(failures).toEqual(['Java 子进程未在取消期限内结束当前 Run']);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });
});

function createClient(
  mode = 'normal',
  options: {platform?: NodeJS.Platform; windowsTreeKiller?: (pid: number) => boolean;
    runHandshakeTimeoutMs?: number} = {},
): StdioClient {
  return new StdioClient({
    executable: process.execPath,
    args: [fixture, mode],
    cwd: process.cwd(),
  }, {shutdownTimeoutMs: 100, cancelTimeoutMs: 100, ...options});
}

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error('等待 Fake stdio 事件超时');
    }
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
