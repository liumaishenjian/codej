import {describe, expect, it, vi} from 'vitest';
import {ExperienceRuntime, type RuntimeClient, type ApprovalPanel, type PlanPanel, type QuestionsPanel} from '../src/experience/runtime.js';
import type {ProtocolEvent, EventType} from '../src/protocol.js';

function fixture() {
  let listener: ((event: ProtocolEvent) => void) | undefined;
  let failure: ((message: string) => void) | undefined;
  let sequence = 0;
  let requestCount = 0;
  const client = {
    initialize: vi.fn(() => 'init'),
    onEvent: vi.fn((next: (event: ProtocolEvent) => void) => {listener = next; return () => {listener = undefined;};}),
    onFailure: vi.fn((next: (message: string) => void) => {failure = next; return () => {failure = undefined;};}),
    startRun: vi.fn(() => 'run-' + ++requestCount),
    startPlan: vi.fn(() => 'plan-' + ++requestCount),
    cancelRun: vi.fn(() => 'cancel'),
    resolveApproval: vi.fn(() => 'approval-decision'),
    resolveQuestion: vi.fn(() => 'question-decision'),
    resolveQuestionnaire: vi.fn(() => 'batch-decision'),
    resolvePlanReview: vi.fn(() => 'plan-decision'),
    shutdown: vi.fn(async () => {}),
  } satisfies RuntimeClient;
  const runtime = new ExperienceRuntime(client, 'C:/workspace');
  const emit = (type: EventType, payload: Record<string, unknown> = {}, overrides: Partial<Omit<ProtocolEvent, 'runId'>> & {runId?: string | undefined} = {}) => {
    const runId = Object.hasOwn(overrides, 'runId') ? overrides.runId : 'actual-run';
    const {runId: _ignored, ...fields} = overrides;
    const event: ProtocolEvent = {version: 0, type, requestId: 'run-1', sessionId: 'session', sequence: ++sequence, payload, ...(runId === undefined ? {} : {runId}), ...fields};
    listener?.(event);
    return event;
  };
  const initialize = () => {
    runtime.connect();
    emit('initialized', {questionnaireV1: true, experienceV1: true, modelConfigured: true}, {requestId: 'init', runId: undefined});
  };
  const start = () => {
    initialize();
    expect(runtime.submit('inspect workspace')).toBe(true);
    emit('run.started', {requestModel: 'configured-model'});
  };
  const approval = () => {
    emit('approval.requested', {approvalId: 'approval', ordinal: 1, toolName: 'run_command', command: 'echo hi', shell: 'powershell', workingDirectory: '.', sessionScope: true});
    return runtime.state.pending as ApprovalPanel;
  };
  return {runtime, client, emit, initialize, start, approval, fail: () => failure?.('host disconnected')};
}

describe('new frontend runtime event boundaries', () => {
  it('initializes once and projects streaming/tool/final content without duplicate rows', () => {
    const f = fixture(); f.start(); f.runtime.connect();
    expect(f.client.initialize).toHaveBeenCalledExactlyOnceWith({questionnaireV1: true, experienceV1: true});
    expect(f.runtime.state.workspace).toBe('C:/workspace');
    expect(f.runtime.state.model).toBe('configured-model');
    f.emit('model.turn.started', {turn: 1});
    f.emit('model.text.delta', {text: 'checking'});
    f.emit('tool.started', {ordinal: 1, toolName: 'read_file', activity: 'read sample', turn: 1, parametersPreview: 'sample.txt'});
    f.emit('tool.output', {ordinal: 1, toolName: 'read_file', text: 'part'});
    f.emit('tool.completed', {ordinal: 1, toolName: 'read_file', content: 'complete 中文', contentTruncated: true});
    f.emit('model.turn.started', {turn: 2});
    const delta = f.emit('model.text.delta', {text: 'answer'});
    f.runtime.accept(delta);
    f.emit('run.completed', {finalText: 'answer'});
    expect(f.runtime.state.status).toBe('idle');
    expect(f.runtime.state.blocks.flatMap(block => block.kind === 'assistant' ? [block.text] : [])).toEqual(['checking', 'answer']);
    const tools = f.runtime.state.blocks.filter(block => block.kind === 'tool');
    expect(tools).toHaveLength(1);
    expect(tools[0]).toMatchObject({status: 'completed', output: 'complete 中文', truncated: true, preview: 'sample.txt'});
  });

  it('defers cancellation until run identity arrives then ignores late output and panels', () => {
    const f = fixture(); f.initialize(); f.runtime.submit('start'); f.runtime.cancel(); f.runtime.cancel();
    expect(f.client.cancelRun).not.toHaveBeenCalled();
    expect(f.runtime.state.status).toBe('cancelling');
    f.emit('run.started');
    expect(f.client.cancelRun).toHaveBeenCalledTimes(1);
    f.emit('model.text.delta', {text: 'late'}); f.approval();
    expect(f.runtime.state.pending).toBeUndefined();
    expect(f.runtime.state.blocks).toHaveLength(1);
    f.emit('run.cancelled');
    f.emit('tool.started', {ordinal: 2, toolName: 'read_file'});
    f.emit('run.started');
    expect(f.runtime.state.status).toBe('idle');
    expect(f.runtime.state.blocks).toHaveLength(1);
  });

  it('rejects events for other requests sessions or run IDs without blocking the valid event', () => {
    const f = fixture(); f.start();
    f.emit('model.text.delta', {text: 'wrong-request'}, {requestId: 'foreign'});
    f.emit('model.text.delta', {text: 'wrong-session'}, {sessionId: 'foreign'});
    f.emit('model.text.delta', {text: 'wrong-run'}, {runId: 'foreign'});
    f.emit('model.text.delta', {text: 'valid'});
    expect(f.runtime.state.blocks.flatMap(block => block.kind === 'assistant' ? [block.text] : [])).toEqual(['valid']);
  });

  it('does not let unsolicited initialized reset an established active session', () => {
    const f = fixture(); f.start();
    f.emit('initialized', {questionnaireV1: true}, {requestId: 'foreign-init', sessionId: 'foreign', runId: undefined});
    expect(f.runtime.state.session).toBe('session');
    expect(f.runtime.state.status).toBe('running');
    expect(f.runtime.state.blocks).toHaveLength(1);
  });

  it('sends an approval once and rejects replaced panel references', () => {
    const f = fixture(); f.start(); const previous = f.approval(); const current = f.approval();
    expect(f.runtime.approve(previous, 'allow_once')).toBe(false);
    expect(f.runtime.approve(current, 'allow_session')).toBe(true);
    expect(f.runtime.approve(current, 'allow_session')).toBe(false);
    expect(f.client.resolveApproval).toHaveBeenCalledExactlyOnceWith('approval', 'allow_session');
    f.emit('run.completed');
    f.emit('protocol.error', {code: 'STALE'}, {requestId: 'approval-decision'});
    expect(f.runtime.state.pending).toBeUndefined();
  });

  it('does not restore an approval from a decision error carrying a foreign run ID', () => {
    const f = fixture(); f.start(); const current = f.approval();
    f.runtime.approve(current, 'allow_once');
    f.emit('protocol.error', {code: 'STALE'}, {requestId: 'approval-decision', runId: 'foreign-run'});
    expect(f.runtime.state.pending).toBeUndefined();
  });

  it('retains an approval when transport throws without silently retrying', () => {
    const f = fixture(); f.start(); const pending = f.approval();
    f.client.resolveApproval.mockImplementationOnce(() => {throw new Error('write failed');});
    expect(f.runtime.approve(pending, 'deny')).toBe(false);
    expect(f.runtime.state.pending).toBe(pending);
    expect(f.client.resolveApproval).toHaveBeenCalledTimes(1);
  });

  it('requires planning terminal before one APPROVE_USER KEEP decision and starts no extra run', () => {
    const f = fixture(); f.initialize();
    expect(f.runtime.submit('/plan implement core')).toBe(true);
    expect(f.client.startPlan).toHaveBeenCalledExactlyOnceWith('implement core');
    f.emit('run.started', {}, {requestId: 'plan-1'});
    f.emit('plan.review.requested', {planId: 'plan', revision: 2, contentDigest: 'content', workspaceDigest: 'workspace', markdown: 'approved design'}, {requestId: 'plan-1', runId: undefined});
    const plan = f.runtime.state.pending as PlanPanel;
    expect(f.runtime.review(plan, 'APPROVE_USER', '')).toBe(false);
    f.emit('run.completed', {}, {requestId: 'plan-1'});
    expect(f.runtime.review(plan, 'APPROVE_USER', '')).toBe(true);
    expect(f.runtime.review(plan, 'APPROVE_USER', '')).toBe(false);
    expect(f.client.resolvePlanReview).toHaveBeenCalledExactlyOnceWith({planId: 'plan', revision: 2, contentDigest: 'content', workspaceDigest: 'workspace', decision: 'APPROVE_USER', contextPolicy: 'KEEP', feedback: ''});
    expect(f.client.startRun).not.toHaveBeenCalled();
    f.emit('plan.execution.accepted', {}, {requestId: 'plan-decision', runId: undefined});
    f.emit('run.started', {}, {requestId: 'plan-decision', runId: 'execution-run'});
    expect(f.runtime.state.status).toBe('running');
    expect(f.runtime.state.mode).toBe('chat');
  });

  it('validates a whole questionnaire and sends mixed answers once without a normal run', () => {
    const f = fixture(); f.start();
    f.emit('question.requested', {callId: 'questions', questions: [
      {id: 'single', title: 'single', question: 'one?', multiSelect: false, allowFreeText: false, options: [{optionId: 'a', label: 'A', description: ''}]},
      {id: 'multi', title: 'multi', question: 'many?', multiSelect: true, allowFreeText: true, options: [{optionId: 'b', label: 'B', description: ''}, {optionId: 'c', label: 'C', description: ''}]},
      {id: 'text', title: 'text', question: 'details?', multiSelect: false, allowFreeText: true, options: []},
    ]});
    const panel = f.runtime.state.pending as QuestionsPanel;
    const answers = [{questionId: 'single', optionIds: ['a'], freeText: ''}, {questionId: 'multi', optionIds: ['b', 'c'], freeText: 'extra'}, {questionId: 'text', optionIds: [], freeText: '中文😀'}];
    expect(f.runtime.answer(panel, answers.slice(0, 2))).toBe(false);
    expect(f.runtime.answer(panel, [{...answers[0]!, optionIds: ['unknown']}, ...answers.slice(1)])).toBe(false);
    expect(f.runtime.answer(panel, answers)).toBe(true);
    expect(f.runtime.answer(panel, answers)).toBe(false);
    expect(f.client.resolveQuestionnaire).toHaveBeenCalledExactlyOnceWith('questions', answers);
    expect(f.client.resolveQuestion).not.toHaveBeenCalled();
    expect(f.client.startRun).toHaveBeenCalledTimes(1);
  });

  it('closes on disconnect and does not allow pending actions or late events', () => {
    const f = fixture(); f.start(); const panel = f.approval(); f.fail();
    expect(f.runtime.state.connection).toBe('closed');
    expect(f.runtime.approve(panel, 'allow_once')).toBe(false);
    f.emit('model.text.delta', {text: 'late'});
    expect(f.runtime.state.blocks).toHaveLength(1);
    f.runtime.dispose(); f.runtime.connect();
    expect(f.client.initialize).toHaveBeenCalledTimes(1);
  });
});

it('规划无审核工件明确提示失败，兼容旧宿主空白成功且保留继续规划能力', () => {
  for (const type of ['run.completed', 'run.failed'] as const) {
    const f = fixture(); f.initialize();
    expect(f.runtime.submit('/plan 看下青岛未来七天天气')).toBe(true);
    f.emit('run.started', {}, {requestId:'plan-1'});
    f.emit(type, {stopReason:type==='run.failed'?'invalid_model_response':'completed'}, {requestId:'plan-1'});
    expect(f.runtime.state.status).toBe('idle');
    expect(f.runtime.state.pending).toBeUndefined();
    expect(f.runtime.state.notice).toContain('未生成可审核的计划');
    expect(f.runtime.state.notice).toContain('未开始执行');
    expect(f.client.resolvePlanReview).not.toHaveBeenCalled();
    expect(f.runtime.submit('先制定查询和验证步骤，批准后执行')).toBe(true);
    expect(f.client.startPlan).toHaveBeenCalledTimes(2);
  }
});

it('计划待验证状态不会被随后空白终态清掉，也不会当成已交付结果',()=>{
  for(const requiredEvidence of [0,1]) {
    const f=fixture();f.start();
    f.emit('plan.verification.required',{requiredEvidence,satisfiedEvidence:0},{runId:undefined});
    f.emit('run.completed',{stopReason:'completed'});
    expect(f.runtime.state.status).toBe('idle');
    expect(f.runtime.state.notice).toContain(requiredEvidence===0?'缺少验收依据':'验收尚未通过');
    expect(f.runtime.state.notice).toContain('未完成交付');
    expect(f.runtime.submit('下一次请求')).toBe(true);
    f.emit('run.started',{}, {requestId:'run-2',runId:'second'});
    f.emit('model.text.delta',{text:'新的完整回答'},{requestId:'run-2',runId:'second'});
    f.emit('run.completed',{finalText:'新的完整回答'},{requestId:'run-2',runId:'second'});
    expect(f.runtime.state.notice).toBe('');
  }
});
it('计划执行中失败保留真实原因，正常验证完成后最终正文完整显示',()=>{
  const f=fixture();f.start();
  f.emit('plan.execution.failed',{stopReason:'deadline_exceeded'},{runId:undefined});
  expect(f.runtime.state.status).toBe('running');
  f.emit('run.failed',{stopReason:'deadline_exceeded'});
  expect(f.runtime.state.notice).toContain('执行未完成');
  expect(f.runtime.state.notice).not.toContain('启动失败');
  f.runtime.submit('下一轮');f.emit('run.started',{}, {requestId:'run-2',runId:'second'});
  f.emit('plan.verification.completed',{}, {requestId:'run-2',runId:undefined});
  f.emit('run.completed',{finalText:'七天结果正文'}, {requestId:'run-2',runId:'second'});
  expect(f.runtime.state.blocks.some(b=>b.kind==='assistant'&&b.text==='七天结果正文')).toBe(true);
});
