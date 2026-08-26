import {execFile} from 'node:child_process';
import fs from 'node:fs/promises';
import {promisify} from 'node:util';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import React from 'react';
import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {AgentTui, sessionTaskTextDecoration} from '../src/app.js';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const workspacePath = path.resolve(testDirectory, '..', '..');
const execFileAsync = promisify(execFile);
const moduleClassDirectories = [
  'cc-java-cli', 'cc-java-core', 'cc-java-domain', 'cc-java-model-spring-ai', 'cc-java-tools-local',
  'cc-java-tools-web', 'cc-java-mcp', 'cc-java-protocol', 'cc-java-sdk',
].map(module => path.resolve(workspacePath, module, 'target', 'classes'));

/** Cross-process contract: this deliberately starts the compiled Java CLI, not the fake child. */
describe('real Java stdio plan flow', () => {
  it('starts natural-language Plan runtime and keeps approval bound to the proposal', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const workspace = workspacePath.replaceAll('\\', '/');
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const planFakeClasspath = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    expect(planFakeClasspath,
      'CC_JAVA_PLAN_FAKE_CLASSPATH must point to the deterministic Plan model fixture').toBeTruthy();
    const launchClasspath = [planFakeClasspath!, effectiveClasspath].join(path.delimiter);
    const fixtureParent = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-plan-acceptance-'));
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', launchClasspath,
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'plan-runtime', fixtureParent],
      cwd: workspace,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: planFakeClasspath!},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    try {
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'),
        () => diagnostic(events, failures, exit));
      const sessionId = events.find(event => event.type === 'initialized')!.sessionId!;

      const requestId = client.startPlan('分析当前项目并给出只读实施计划');
      await waitFor(() => events.some(event => event.type === 'plan.review.requested'
        && event.requestId === requestId), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed' && event.requestId === requestId));
      const review = events.find(event => event.type === 'plan.review.requested'
        && event.requestId === requestId)!;
      const planId = String(review.payload.planId);
      const revision = Number(review.payload.revision);
      const contentDigest = String(review.payload.contentDigest);
      const workspaceDigest = String(review.payload.workspaceDigest);
      expect(String(review.payload.markdown)).toContain('跨进程实施计划');
      expect(revision).toBe(3); // plan + evidence declaration + review transition
      expect(events.some(event => event.type === 'plan.proposed')).toBe(false);
      expect(events.some(event => event.type === 'model.text.delta' && event.requestId === requestId)).toBe(false);

      const feedbackRequest = client.resolvePlanReview({
        planId, revision, contentDigest, workspaceDigest,
        decision: 'CONTINUE_PLANNING', contextPolicy: 'CLEAR', feedback: 'add rollback verification',
      });
      await waitFor(() => events.some(event => event.type === 'plan.feedback.accepted'
        && event.requestId === feedbackRequest && event.payload.planId === planId),
      () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.started'
        && event.requestId === feedbackRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'plan.review.requested'
        && event.requestId === feedbackRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === feedbackRequest), () => diagnostic(events, failures, exit));
      const revisedReview = events.find(event => event.type === 'plan.review.requested'
        && event.requestId === feedbackRequest)!;
      expect(revisedReview.payload.planId).toBe(planId);
      expect(revisedReview.payload.revision).toBe(6);
      expect(String(revisedReview.payload.markdown)).toContain('验证纠正后的工作簿与回滚结果');

      const executionRequest = client.resolvePlanReview({
        planId, revision: Number(revisedReview.payload.revision),
        contentDigest: String(revisedReview.payload.contentDigest),
        workspaceDigest: String(revisedReview.payload.workspaceDigest),
        decision: 'APPROVE_USER', contextPolicy: 'KEEP', feedback: '',
      });
      await waitFor(() => events.some(event => event.type === 'plan.execution.accepted'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.started'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.filter(event => event.type === 'approval.requested'
        && event.requestId === executionRequest).length === 1, () => diagnostic(events, failures, exit));
      const firstApproval = events.find(event => event.type === 'approval.requested'
        && event.requestId === executionRequest)!;
      client.resolveApproval(String(firstApproval.payload.approvalId), 'allow_once');
      await waitFor(() => events.some(event => event.type === 'tool.completed'
        && event.requestId === executionRequest && event.payload.toolName === 'write_file'),
      () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'plan.verification.correction'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      const correction = events.find(event => event.type === 'plan.verification.correction'
        && event.requestId === executionRequest)!;
      expect(correction.payload).toEqual({attempt: 1, maxAttempts: 2, failures: [{
        requirementId: 'weather-xlsx', kind: 'deliverable', locator: '河南各市7天天气.xlsx',
        reason: 'FILE_MISSING_OR_UNSAFE',
      }]});
      expect(events.some(event => JSON.stringify(event.payload).includes('FIRST_UNVERIFIED_FINAL'))).toBe(false);
      await waitFor(() => events.filter(event => event.type === 'approval.requested'
        && event.requestId === executionRequest).length === 2, () => diagnostic(events, failures, exit));
      const secondApproval = events.filter(event => event.type === 'approval.requested'
        && event.requestId === executionRequest)[1]!;
      client.resolveApproval(String(secondApproval.payload.approvalId), 'allow_once');
      await waitFor(() => events.filter(event => event.type === 'tool.completed'
        && event.requestId === executionRequest && event.payload.toolName === 'write_file').length === 2,
      () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'plan.verification.completed'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      const executionTerminal = events.find(event => event.type === 'run.completed'
        && event.requestId === executionRequest)!;
      expect(executionTerminal.payload.stopReason).toBe('completed');
      expect(String(executionTerminal.payload.finalText)).toContain('approved plan corrected and verified');
      expect(events.some(event => event.type === 'run.failed'
        && event.requestId === executionRequest)).toBe(false);
      // plan.verification.completed 只在 durable PlanArtifact 已进入 COMPLETED 时发布。
      expect(events.some(event => event.type === 'plan.verification.completed'
        && event.requestId === executionRequest)).toBe(true);
      expect(events.filter(event => event.type === 'tool.completed'
        && event.requestId === executionRequest && event.payload.toolName === 'write_file')).toHaveLength(2);
      const planningTaskSnapshots = events.filter(event => event.type === 'task.board.snapshot'
        && (event.requestId === requestId || event.requestId === feedbackRequest));
      expect(planningTaskSnapshots.map(event => event.payload.boardRevision)).toEqual([1, 2]);
      expect(planningTaskSnapshots.at(-1)?.payload.tasks).toEqual([
        expect.objectContaining({taskId: 'task-1', subject: '生成精确命名的河南天气工作簿。'}),
        expect.objectContaining({taskId: 'task-2', subject: '验证纠正后的工作簿与回滚结果。',
          blockerIds: ['task-1']}),
      ]);
      const taskSnapshots = events.filter(event => event.type === 'task.board.snapshot'
        && event.requestId === executionRequest);
      expect(taskSnapshots.map(event => event.payload.boardRevision)).toEqual([2, 4, 5, 7, 8]);
      expect(taskSnapshots.map(event => event.payload.tasks)).toEqual([
        [
          expect.objectContaining({subject: '生成精确命名的河南天气工作簿。', status: 'PENDING'}),
          expect.objectContaining({subject: '验证纠正后的工作簿与回滚结果。', status: 'PENDING'}),
        ],
        [
          expect.objectContaining({subject: '生成精确命名的河南天气工作簿。', status: 'IN_PROGRESS'}),
          expect.objectContaining({subject: '验证纠正后的工作簿与回滚结果。', status: 'PENDING'}),
        ],
        [
          expect.objectContaining({subject: '生成精确命名的河南天气工作簿。', status: 'COMPLETED'}),
          expect.objectContaining({subject: '验证纠正后的工作簿与回滚结果。', status: 'PENDING'}),
        ],
        [
          expect.objectContaining({subject: '生成精确命名的河南天气工作簿。', status: 'COMPLETED'}),
          expect.objectContaining({subject: '验证纠正后的工作簿与回滚结果。', status: 'IN_PROGRESS'}),
        ],
        [
          expect.objectContaining({subject: '生成精确命名的河南天气工作簿。', status: 'COMPLETED'}),
          expect.objectContaining({subject: '验证纠正后的工作簿与回滚结果。', status: 'COMPLETED'}),
        ],
      ]);
      expect(events.some(event => event.type === 'tool.completed'
        && event.requestId === executionRequest && event.payload.toolName === 'task_create')).toBe(false);
      expect(JSON.stringify(taskSnapshots)).not.toContain('执行获批计划');
      const executionTypes = events.filter(event => event.requestId === executionRequest)
        .map(event => event.type);
      expect(executionTypes.indexOf('plan.execution.accepted')).toBeLessThan(
        executionTypes.indexOf('run.started'));
      expect(executionTypes.indexOf('run.started')).toBeLessThan(executionTypes.indexOf('task.board.snapshot'));
      expect(executionTypes.indexOf('task.board.snapshot')).toBeLessThan(executionTypes.indexOf('tool.started'));
      expect(executionTypes.indexOf('tool.started')).toBeLessThan(executionTypes.indexOf('plan.verification.correction'));
      expect(executionTypes.indexOf('plan.verification.correction')).toBeLessThan(
        executionTypes.lastIndexOf('tool.started'));
      expect(executionTypes.lastIndexOf('tool.completed')).toBeLessThan(
        executionTypes.indexOf('plan.verification.completed'));
      expect(executionTypes.indexOf('plan.verification.completed')).toBeLessThan(
        executionTypes.indexOf('run.completed'));
      expect(events.some(event => event.type === 'plan.verification.required')).toBe(false);
      expect(events.filter(event => event.requestId === executionRequest)
        .some(event => event.type === 'plan.proposed')).toBe(false);

      const followUpRequest = client.startRun('计划验证完成后的普通输入');
      await waitFor(() => events.some(event => event.type === 'run.command.result'
        && event.requestId === followUpRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.started'
        && event.requestId === followUpRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === followUpRequest), () => diagnostic(events, failures, exit));
      const followUpEvents = events.filter(event => event.requestId === followUpRequest);
      expect(followUpEvents.filter(event => event.type === 'run.command.result')).toHaveLength(1);
      expect(followUpEvents.find(event => event.type === 'run.command.result')?.payload.disposition)
        .toMatch(/accepted|queued/u);
      expect(followUpEvents.filter(event => event.type === 'run.started')).toHaveLength(1);
      expect(followUpEvents.filter(event => event.type === 'run.completed')).toHaveLength(1);
      expect(followUpEvents.findIndex(event => event.type === 'run.command.result')).toBeLessThan(
        followUpEvents.findIndex(event => event.type === 'run.started'));
      const followUpRunId = followUpEvents.find(event => event.type === 'run.started')!.runId!;
      const fixtureRoot = (await fs.readdir(fixtureParent, {withFileTypes: true}))
        .find(entry => entry.isDirectory() && entry.name.startsWith('plan-runtime-'));
      expect(fixtureRoot, 'Plan fixture root must remain available until shutdown').toBeDefined();
      await expect(fs.readFile(path.join(fixtureParent, fixtureRoot!.name, 'workspace',
        '河南各市7天天气预报.xlsx'), 'utf8')).resolves.toBe('wrong-name');
      await expect(fs.readFile(path.join(fixtureParent, fixtureRoot!.name, 'workspace',
        '河南各市7天天气.xlsx'), 'utf8')).resolves.toBe('correct-name');
      const journalPath = path.join(
        fixtureParent, fixtureRoot!.name, 'sessions', sessionId, 'session.jsonl',
      );
      try {
        await waitFor(async () => {
          try {
            const journal = await fs.readFile(journalPath, 'utf8');
            return journal.split(/\r?\n/u).filter(Boolean).map(line => JSON.parse(line) as {
              recordType?: string; runId?: string;
            }).some(record => record.recordType === 'run.started' && record.runId === followUpRunId);
          } catch {
            return false;
          }
        }, () => diagnostic(events, failures, exit));
      } catch (failure) {
        const records = await safeJournalLifecycle(journalPath);
        throw new Error(`${String(failure)}; journal=[${records}]`);
      }
      const journal = (await fs.readFile(journalPath, 'utf8')).split(/\r?\n/u).filter(Boolean)
        .map(line => JSON.parse(line) as {recordType?: string; runId?: string});
      expect(journal.filter(record => record.recordType === 'run.started'
        && record.runId === followUpRunId)).toHaveLength(1);
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
      await fs.rm(fixtureParent, {recursive: true, force: true});
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.signal).toBeNull();
    expect(exit?.stderrBytes).toBe(0);
  }, 30_000);

  it('renders early approved execution events through the real Ink reducer path', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const workspace = workspacePath.replaceAll('\\', '/');
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const planFakeClasspath = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    expect(planFakeClasspath,
      'CC_JAVA_PLAN_FAKE_CLASSPATH must point to the deterministic Plan model fixture').toBeTruthy();
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', [planFakeClasspath!, effectiveClasspath].join(path.delimiter),
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'plan-runtime', os.tmpdir()],
      cwd: workspace,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: planFakeClasspath!},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    const view = render(React.createElement(AgentTui, {client}));
    try {
      await waitFor(() => view.lastFrame()?.includes('就绪') === true,
        () => diagnostic(events, failures, exit));
      view.stdin.write('/plan source Ink lifecycle');
      view.stdin.write('\r');
      await waitFor(() => view.lastFrame()?.includes('实施计划 · revision 3') === true
        && view.lastFrame()?.includes('批准并自动执行') === true,
      () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === events.find(item => item.type === 'plan.review.requested')?.requestId),
      () => diagnostic(events, failures, exit));
      view.stdin.write('[B');
      await new Promise(resolve => setTimeout(resolve, 10));
      view.stdin.write('\r');
      await waitFor(() => events.filter(event => event.type === 'approval.requested').length === 1,
        () => diagnostic(events, failures, exit));
      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'plan.verification.correction'),
        () => diagnostic(events, failures, exit));
      await waitFor(() => view.lastFrame()?.includes('同一 Run 内纠正（1/2）') === true
        && view.lastFrame()?.includes('不会自动重放既有副作用') === true,
      () => diagnostic(events, failures, exit));
      expect(view.lastFrame()).not.toContain('FIRST_UNVERIFIED_FINAL');
      await waitFor(() => events.filter(event => event.type === 'approval.requested').length === 2,
        () => diagnostic(events, failures, exit));
      view.stdin.write('\r');
      await waitFor(() => view.lastFrame()?.includes('approved plan corrected and verified') === true
        && view.lastFrame()?.includes('计划证据已验证') === true
        && view.lastFrame()?.includes('✓ 生成精确命名的河南天气工作簿。') === true
        && view.lastFrame()?.includes('已完成') === true,
      () => diagnostic(events, failures, exit));
      await waitFor(() => view.lastFrame()?.includes('· 就绪') === true,
        () => diagnostic(events, failures, exit));

      const eventBoundary = events.length;
      view.stdin.write('计划完成后的普通输入');
      view.stdin.write('\r');
      await waitFor(() => events.slice(eventBoundary).some(event => event.type === 'run.command.result'
        && event.payload.commandType === 'run.start'), () => diagnostic(events, failures, exit));
      const followUpResult = events.slice(eventBoundary).find(event => event.type === 'run.command.result'
        && event.payload.commandType === 'run.start')!;
      const followUpRequest = followUpResult.requestId;
      await waitFor(() => events.some(event => event.type === 'run.started'
        && event.requestId === followUpRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === followUpRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => view.lastFrame()?.includes('follow-up completed') === true,
        () => diagnostic(events, failures, exit));
      const followUpTypes = events.filter(event => event.requestId === followUpRequest)
        .map(event => event.type);
      expect(followUpTypes.filter(type => type === 'run.command.result')).toHaveLength(1);
      expect(followUpTypes.filter(type => type === 'run.started')).toHaveLength(1);
      expect(followUpTypes.filter(type => type === 'run.completed')).toHaveLength(1);
      expect(followUpTypes.indexOf('run.command.result')).toBeLessThan(followUpTypes.indexOf('run.started'));
      const frame = view.lastFrame() ?? '';
      expect(frame).not.toContain('上一条输入仍在提交中');
      expect(frame).not.toContain('无法关联');
      expect(frame).not.toContain('连接已关闭');
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
      view.unmount();
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.stderrBytes).toBe(0);
  }, 30_000);

  it('renders ordinary durable Task mutations from real Java stdio through Ink', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const workspace = workspacePath.replaceAll('\\', '/');
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const fixtureClasses = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    expect(fixtureClasses,
      'CC_JAVA_PLAN_FAKE_CLASSPATH must point to deterministic Java fixture classes').toBeTruthy();
    const fixtureParent = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-task-acceptance-'));
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', [fixtureClasses!, effectiveClasspath].join(path.delimiter),
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'task-runtime', fixtureParent],
      cwd: workspace,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: fixtureClasses!},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    const view = render(React.createElement(AgentTui, {client}));
    try {
      await waitFor(() => view.lastFrame()?.includes('就绪') === true,
        () => diagnostic(events, failures, exit));
      view.stdin.write('执行普通复杂任务');
      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && String(event.payload.finalText).includes('task lifecycle verified')),
      () => diagnostic(events, failures, exit));
      await waitFor(() => view.lastFrame()?.includes('✓ 完成真实 Task 闭环') === true
        && view.lastFrame()?.includes('task lifecycle verified') === true,
      () => diagnostic(events, failures, exit));

      const requestId = events.find(event => event.type === 'run.started')!.requestId;
      const runEvents = events.filter(event => event.requestId === requestId);
      const snapshots = runEvents.filter(event => event.type === 'task.board.snapshot');
      expect(snapshots.map(event => event.payload.boardRevision)).toEqual([1, 2, 3]);
      expect(snapshots.map(event => (event.payload.tasks as Array<{status: string}>)[0]?.status))
        .toEqual(['PENDING', 'IN_PROGRESS', 'COMPLETED']);
      for (const snapshot of snapshots) {
        const index = runEvents.indexOf(snapshot);
        expect(runEvents[index - 1]?.type).toBe('tool.completed');
        expect(['task_create', 'task_update']).toContain(runEvents[index - 1]?.payload.toolName);
      }
      expect(view.lastFrame()).not.toContain('执行进度由 Java');
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
      view.unmount();
      await fs.rm(fixtureParent, {recursive: true, force: true});
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.signal).toBeNull();
    expect(exit?.stderrBytes).toBe(0);
  }, 30_000);

  it('executes five approved Chinese Tasks and delivers a real XLSX through run_command', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const fixtureClasses = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    expect(fixtureClasses, 'CC_JAVA_PLAN_FAKE_CLASSPATH must point to deterministic Java fixture classes').toBeTruthy();
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    const fixtureParent = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-xlsx-plan-acceptance-'));
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', [fixtureClasses!, effectiveClasspath].join(path.delimiter),
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'xlsx-plan-runtime', fixtureParent],
      cwd: workspacePath,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: fixtureClasses!},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    const view = render(React.createElement(AgentTui, {client}));
    try {
      await waitFor(() => view.lastFrame()?.includes('就绪') === true,
        () => diagnostic(events, failures, exit));
      view.stdin.write('/plan 生成河南各市7天天气工作簿并验证真实文件');
      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'plan.review.requested'
        && String(event.payload.markdown).includes('河南天气工作簿交付计划')),
      () => diagnostic(events, failures, exit));
      const review = events.find(event => event.type === 'plan.review.requested')!;
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === review.requestId), () => diagnostic(events, failures, exit));
      const subjects = [
        '创建独立的工作簿生成器。',
        '生成真实的河南天气 XLSX 文件。',
        '校验 OpenXML 工作簿结构与中文数据。',
        '执行长耗时质量检查。',
        '汇总真实交付与验证证据。',
      ];
      const planningSnapshots = events.filter(event => event.requestId === review.requestId
        && event.type === 'task.board.snapshot');
      expect(planningSnapshots.map(event => event.payload.boardRevision)).toEqual([1, 2, 3, 4, 5]);
      expect((planningSnapshots.at(-1)!.payload.tasks as Array<{
        taskId: string; subject: string; blockerIds: string[]; status: string;
      }>).map(task => ({taskId: task.taskId, subject: task.subject, blockerIds: task.blockerIds, status: task.status})))
        .toEqual(subjects.map((subject, index) => ({
          taskId: `task-${index + 1}`,
          subject,
          blockerIds: index === 0 ? [] : [`task-${index}`],
          status: 'PENDING',
        })));
      await waitFor(() => subjects.every(subject => view.lastFrame()?.includes(subject) === true),
        () => diagnostic(events, failures, exit));
      const fixtureRoot = (await fs.readdir(fixtureParent, {withFileTypes: true}))
        .find(entry => entry.isDirectory() && entry.name.startsWith('xlsx-plan-runtime-'));
      expect(fixtureRoot).toBeDefined();
      const workbookPath = path.join(
        fixtureParent, fixtureRoot!.name, 'workspace', '河南各市7天天气.xlsx');
      await expect(fs.stat(workbookPath)).rejects.toMatchObject({code: 'ENOENT'});

      // 通过真实 Ink picker 提交决定，使 execution request 在 reducer 中先建立 optimistic live Run。
      // 直接调用 client 会绕过本地 submission 关联，后续 run.started/Task snapshot 会被安全拒绝。
      view.stdin.write('[B');
      await new Promise(resolve => setTimeout(resolve, 10));
      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'plan.execution.accepted'),
        () => diagnostic(events, failures, exit));
      const executionRequest = events.find(event => event.type === 'plan.execution.accepted')!.requestId;
      await waitFor(() => events.some(event => event.type === 'run.started'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      const executionStartedAt = Date.now();
      for (let approvalCount = 1; approvalCount <= 4; approvalCount++) {
        await waitFor(() => events.filter(event => event.type === 'approval.requested'
          && event.requestId === executionRequest).length >= approvalCount,
        () => diagnostic(events, failures, exit));
        const approval = events.filter(event => event.type === 'approval.requested'
          && event.requestId === executionRequest)[approvalCount - 1]!;
        client.resolveApproval(String(approval.payload.approvalId), 'allow_once');
        if (approvalCount === 4) {
          await waitFor(() => events.filter(event => event.type === 'tool.started'
            && event.requestId === executionRequest && event.payload.toolName === 'run_command').length === 3
            && events.filter(event => event.type === 'tool.completed'
              && event.requestId === executionRequest && event.payload.toolName === 'run_command').length === 2
            && view.lastFrame()?.includes('正在执行长耗时质量检查…') === true
            && (view.lastFrame()?.match(/正在执行长耗时质量检查…/gu) ?? []).length === 1,
          () => `${diagnostic(events, failures, exit)}, lastFrame=${JSON.stringify(view.lastFrame())}`);
        }
      }
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      const runEvents = events.filter(event => event.requestId === executionRequest);
      const snapshots = runEvents.filter(event => event.type === 'task.board.snapshot');
      expect(snapshots.map(event => event.payload.boardRevision)).toEqual(
        [5, 7, 8, 10, 11, 13, 14, 16, 17, 19, 20]);
      expect((snapshots[0]!.payload.tasks as Array<{subject: string; status: string}>).map(task => task.subject))
        .toEqual([
          '创建独立的工作簿生成器。',
          '生成真实的河南天气 XLSX 文件。',
          '校验 OpenXML 工作簿结构与中文数据。',
          '执行长耗时质量检查。',
          '汇总真实交付与验证证据。',
        ]);
      const expectedActiveForms = [
        '正在创建工作簿生成器', '正在生成126条天气记录', '正在校验OpenXML结构',
        '正在执行长耗时质量检查', '正在汇总交付证据',
      ];
      for (let index = 0; index < 5; index++) {
        const projections = snapshots.map(snapshot =>
          (snapshot.payload.tasks as Array<{status: string; activeForm?: string}>)[index]!);
        expect(projections.map(task => task.status)).toContain('PENDING');
        expect(projections.map(task => task.status)).toContain('IN_PROGRESS');
        expect(projections.at(-1)?.status).toBe('COMPLETED');
        expect(projections.some(task => task.status === 'IN_PROGRESS'
          && task.activeForm === expectedActiveForms[index])).toBe(true);
      }
      expect(runEvents.filter(event => event.type === 'tool.completed'
        && event.payload.toolName === 'run_command'), diagnostic(events, failures, exit)).toHaveLength(3);
      expect(runEvents.some(event => event.type === 'tool.completed'
        && event.payload.toolName === 'task_create')).toBe(false);
      expect(runEvents.some(event => event.type === 'tool.completed'
        && event.payload.toolName === 'task_list')).toBe(true);
      expect(runEvents.filter(event => event.type === 'tool.completed'
        && event.payload.toolName === 'task_get')).toHaveLength(5);
      expect(runEvents.some(event => event.type === 'tool.failed')).toBe(false);
      expect(JSON.stringify(runEvents)).not.toMatch(
        /invalid_arguments|TASK_CAPABILITY_DENIED|TASK_NOT_FOUND|time_limit_reached/u);
      expect(runEvents.find(event => event.type === 'run.completed')?.payload.stopReason).toBe('completed');
      expect(Date.now() - executionStartedAt).toBeGreaterThanOrEqual(1_000);
      view.stdin.write('/tasks');
      view.stdin.write('\r');
      await waitFor(() => [
        '创建独立的工作簿生成器。',
        '生成真实的河南天气 XLSX 文件。',
        '校验 OpenXML 工作簿结构与中文数据。',
        '执行长耗时质量检查。',
        '汇总真实交付与验证证据。',
      ].every(subject => view.lastFrame()?.includes(`✓ ${subject}`) === true),
      () => diagnostic(events, failures, exit));
      const completedFrame = view.lastFrame() ?? '';
      for (const subject of [
        '创建独立的工作簿生成器。',
        '生成真实的河南天气 XLSX 文件。',
        '校验 OpenXML 工作簿结构与中文数据。',
        '执行长耗时质量检查。',
        '汇总真实交付与验证证据。',
      ]) {
        expect(completedFrame).toContain(`✓ ${subject}`);
      }
      expect(completedFrame).not.toContain('0/5');
      expect(completedFrame).not.toContain('time_limit_reached');
      expect(sessionTaskTextDecoration('COMPLETED')).toEqual({dimColor: true, strikethrough: true});

      const workbook = await fs.readFile(workbookPath);
      expect(workbook.subarray(0, 2).toString('ascii')).toBe('PK');
      expect(workbook.includes(Buffer.from('[Content_Types].xml'))).toBe(true);
      expect(workbook.includes(Buffer.from('xl/worksheets/sheet1.xml'))).toBe(true);
      expect(workbook.byteLength).toBeGreaterThan(800);
      const reopened = await execFileAsync('java', [
        '-cp', [fixtureClasses!, effectiveClasspath].join(path.delimiter),
        'io.github.liumaishenjian.ccjava.cli.stdio.WorkbookMakerFixtureMain',
        'verify', workbookPath,
      ]);
      expect(reopened.stderr).toBe('');
      expect(reopened.stdout.trim()).toBe('verified_rows=126');
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
      view.unmount();
      await fs.rm(fixtureParent, {recursive: true, force: true});
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.stderrBytes).toBe(0);
  }, 45_000);

  it('keeps Task recovery and Run terminal consistent after a real command timeout', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath).toBeTruthy();
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const fixtureClasses = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    expect(fixtureClasses).toBeTruthy();
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    const fixtureParent = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-task-timeout-'));
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', [fixtureClasses!, effectiveClasspath].join(path.delimiter),
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'task-timeout-runtime', fixtureParent],
      cwd: workspacePath,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: fixtureClasses!},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    const view = render(React.createElement(AgentTui, {client}));
    try {
      await waitFor(() => view.lastFrame()?.includes('就绪') === true,
        () => diagnostic(events, failures, exit));
      view.stdin.write('执行会超时的真实命令');
      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'approval.requested'),
        () => diagnostic(events, failures, exit));
      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'run.completed'),
        () => diagnostic(events, failures, exit));
      const toolFailure = events.find(event => event.type === 'tool.failed'
        && event.payload.toolName === 'run_command');
      expect(toolFailure).toBeDefined();
      expect(toolFailure?.payload.errorCode).toBe('operation_timed_out');
      const terminal = events.find(event => event.type === 'run.completed')!;
      expect(terminal.payload.stopReason).toBe('completed');
      expect(terminal.payload.pendingTaskCount).toBe(1);
      expect(terminal.payload.recoveryTaskCount).toBe(1);
      await waitFor(() => view.lastFrame()?.includes('· 就绪') === true,
        () => diagnostic(events, failures, exit));
      const tasksRequest = client.sessionCommand!('tui-command-timeout-tasks', 'tasks', {});
      await waitFor(() => events.some(event => event.type === 'session.command.result'
        && event.requestId === tasksRequest), () => diagnostic(events, failures, exit));
      const taskResult = events.find(event => event.type === 'session.command.result'
        && event.requestId === tasksRequest)!;
      const recoveredTasks = (taskResult.payload.result as {tasks: Array<{
        status: string; recoveryRequired: boolean;
      }>}).tasks;
      expect(recoveredTasks).toEqual([expect.objectContaining({
        status: 'IN_PROGRESS', recoveryRequired: true,
      })]);
      await waitFor(() => view.lastFrame()?.includes('执行超时命令') === true
        && view.lastFrame()?.includes('需要恢复') === true,
      () => diagnostic(events, failures, exit));
      expect(view.lastFrame()).toContain('命令已超时，任务保持待恢复');
      expect(view.lastFrame()).not.toContain('✓ 执行超时命令');
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
      view.unmount();
      await fs.rm(fixtureParent, {recursive: true, force: true});
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.stderrBytes).toBe(0);
  }, 30_000);

  it('uses allow once then allow for session and suppresses the second same-scope prompt', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const workspace = workspacePath.replaceAll('\\', '/');
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const planFakeClasspath = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    expect(planFakeClasspath,
      'CC_JAVA_PLAN_FAKE_CLASSPATH must point to the deterministic Java fixture classes').toBeTruthy();
    const launchClasspath = [planFakeClasspath!, effectiveClasspath].join(path.delimiter);
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', launchClasspath,
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'permission-runtime', workspace],
      cwd: workspace,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: planFakeClasspath!},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    try {
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'),
        () => diagnostic(events, failures, exit));
      const requestId = client.startRun('apply the two fixture patches');
      await waitFor(() => events.filter(event => event.type === 'approval.requested').length === 1,
        () => diagnostic(events, failures, exit));
      const first = events.find(event => event.type === 'approval.requested')!;
      client.resolveApproval(String(first.payload.approvalId), 'allow_once');
      await waitFor(() => events.filter(event => event.type === 'approval.requested').length === 2,
        () => diagnostic(events, failures, exit));
      const second = events.filter(event => event.type === 'approval.requested')[1]!;
      client.resolveApproval(String(second.payload.approvalId), 'allow_session');
      await waitFor(() => events.some(event => event.type === 'run.completed' && event.requestId === requestId),
        () => diagnostic(events, failures, exit));
      expect(events.filter(event => event.type === 'approval.requested')).toHaveLength(2);
      expect(events.filter(event => event.type === 'tool.completed'
        && event.payload.toolName === 'apply_patch'), diagnostic(events, failures, exit)).toHaveLength(3);
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.signal).toBeNull();
    expect(exit?.stderrBytes).toBe(0);
  }, 30_000);
});

async function waitForEvent(events: ProtocolEvent[], requestId: string): Promise<ProtocolEvent> {
  await waitFor(() => events.some(event => event.type === 'session.command.result' && event.requestId === requestId));
  return events.find(event => event.type === 'session.command.result' && event.requestId === requestId)!;
}
async function waitFor(
  predicate: () => boolean | Promise<boolean>,
  onTimeout?: () => string,
): Promise<void> {
  const deadline = Date.now() + 15_000;
  while (!(await predicate())) {
    if (Date.now() >= deadline) throw new Error(onTimeout?.() ?? '等待真实 Java stdio 事件超时');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
function diagnostic(
  events: readonly ProtocolEvent[], failures: readonly string[],
  exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined,
): string {
  const counts = new Map<string, number>();
  for (const event of events) counts.set(safeEventType(event.type),
    (counts.get(safeEventType(event.type)) ?? 0) + 1);
  const eventCounts = [...counts.entries()].sort(([left], [right]) => left.localeCompare(right))
    .map(([type, count]) => `${type}=${count}`).join(',');
  const terminalReasons = events.filter(event => event.type === 'run.completed' || event.type === 'run.failed')
    .map(event => safeStopReason(event.payload.stopReason)).join(',');
  const startedTools = events.filter(event => event.type === 'tool.started')
    .map(event => safeToolName(event.payload.toolName)).join(',');
  const completedTools = events.filter(event => event.type === 'tool.completed')
    .map(event => safeToolName(event.payload.toolName)).join(',');
  const failedTools = events.filter(event => event.type === 'tool.failed')
    .map(event => `${safeToolName(event.payload.toolName)}:${safeWireToken(event.payload.errorCode)}`).join(',');
  const commandResults = events.filter(event => event.type === 'run.command.result')
    .map(event => `${safeWireToken(event.payload.commandType)}:${safeWireToken(event.payload.disposition)}`)
    .join(',');
  const protocolErrors = events.filter(event => event.type === 'protocol.error')
    .map(event => `${safeWireToken(event.payload.code)}:${String(event.payload.message).slice(0, 160)}`)
    .join(',');
  const startedRunIds = events.filter(event => event.type === 'run.started')
    .map(event => typeof event.runId === 'string' ? event.runId : 'missing').join(',');
  const exitMetadata = exit === undefined ? 'pending'
    : `code=${exit.code ?? 'null'}:signal=${safeSignal(exit.signal)}:stderrBytes=${Math.min(exit.stderrBytes, 999_999)}`;
  return `等待真实 Java 事件超时；eventCount=${events.length}, eventTypes=[${eventCounts}], terminalReasons=[${terminalReasons}], startedTools=[${startedTools}], completedTools=[${completedTools}], failedTools=[${failedTools}], commandResults=[${commandResults}], protocolErrors=[${protocolErrors}], startedRunIds=[${startedRunIds}], failureCount=${failures.length}, exit=${exitMetadata}`;
}

async function safeJournalLifecycle(journalPath: string): Promise<string> {
  try {
    const content = await fs.readFile(journalPath, 'utf8');
    return content.split(/\r?\n/u).filter(Boolean).flatMap(line => {
      try {
        const record = JSON.parse(line) as {recordType?: unknown; runId?: unknown};
        return [`${safeWireToken(record.recordType)}:${typeof record.runId === 'string' ? record.runId : '-'}`];
      } catch {
        return ['invalid-json'];
      }
    }).join(',');
  } catch {
    return 'unavailable';
  }
}

function safeEventType(type: string): string {
  const known = new Set([
    'plan.execution.accepted', 'plan.execution.blocked', 'plan.feedback.submitted',
    'plan.review.requested', 'plan.verification.completed', 'plan.verification.required',
    'protocol.error', 'run.cancelled', 'run.completed', 'run.failed', 'run.started',
    'session.command.result', 'task.board.snapshot', 'tool.completed', 'tool.failed', 'tool.started',
  ]);
  return known.has(type) ? type : 'other';
}

function safeWireToken(value: unknown): string {
  return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(value) ? value : 'unknown';
}

function safeStopReason(value: unknown): string {
  return typeof value === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(value) ? value : 'unknown';
}

function safeToolName(value: unknown): string {
  return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_.:-]{0,127}$/.test(value) ? value : 'unknown';
}

function safeSignal(signal: NodeJS.Signals | null): string {
  return signal !== null && /^SIG[A-Z0-9]+$/.test(signal) ? signal : 'null-or-other';
}
