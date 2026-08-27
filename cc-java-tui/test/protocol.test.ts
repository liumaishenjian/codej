import {describe, expect, it} from 'vitest';
import {ProtocolViolation, decodeEvent} from '../src/protocol.js';

describe('decodeEvent', () => {
  it('接受带中文 Delta 的连续事件', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'model.text.delta',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 2,
      payload: {text: '你好'},
    }), 2);

    expect(event.payload.text).toBe('你好');
  });

  it('严格接受模型阶段、Provider Usage 与上下文估算投影', () => {
    const started = {
      version: 0, type: 'model.turn.started', requestId: 'req-1', sessionId: 'session-1',
      runId: 'run-1', sequence: 1, payload: {turn: 2},
    };
    expect(decodeEvent(JSON.stringify(started), 1).payload.turn).toBe(2);
    const completed = {
      ...started, type: 'model.turn.completed', sequence: 2,
      payload: {
        turn: 2, finishReason: 'tool_calls',
        usage: {inputTokens: 1_200, outputTokens: 80, totalTokens: 1_280},
        context: {usedTokens: 4_000, maximumInputTokens: 128_000, estimateKind: 'estimated'},
      },
    };
    expect(decodeEvent(JSON.stringify(completed), 2).payload.usage).toEqual(completed.payload.usage);
    expect(() => decodeEvent(JSON.stringify({
      ...completed, payload: {...completed.payload, reasoning: 'PRIVATE_CHAIN_OF_THOUGHT'},
    }), 2)).toThrowError(/model\.turn\.completed/);
    expect(() => decodeEvent(JSON.stringify({
      ...completed, payload: {...completed.payload,
        usage: {...completed.payload.usage, totalTokens: 1}},
    }), 2)).toThrowError(/Usage/);
  });

  it('严格接受模型重试与 Plan 执行失败的脱敏投影', () => {
    const attempt = {
      version: 0, type: 'model.retry.attempt.started', requestId: 'req-retry',
      sessionId: 'session-1', runId: 'run-1', sequence: 1,
      payload: {turn: 1, attempt: 2, maxAttempts: 11},
    };
    expect(decodeEvent(JSON.stringify(attempt), 1).payload.attempt).toBe(2);
    const scheduled = {
      ...attempt, type: 'model.retry.scheduled', sequence: 2,
      payload: {turn: 1, failedAttempt: 1, nextAttempt: 2, maxAttempts: 11,
        waitMillis: 2_000, category: 'rate_limited'},
    };
    expect(decodeEvent(JSON.stringify(scheduled), 2).payload.waitMillis).toBe(2_000);
    const planFailure = {
      version: 0, type: 'plan.execution.failed', requestId: 'req-plan',
      sessionId: 'session-1', sequence: 3,
      payload: {planId: 'plan-1', status: 'failed', stopReason: 'model_retry_exhausted',
        modelFailure: {category: 'provider_unavailable', statusClass: '5xx',
          attempts: 11, receivedOutput: false}},
    };
    expect(decodeEvent(JSON.stringify(planFailure), 3).payload.status).toBe('failed');
    expect(() => decodeEvent(JSON.stringify({...scheduled, payload: {
      ...scheduled.payload, endpoint: 'https://private.example',
    }}), 2)).toThrowError(/model\.retry\.scheduled/);
    expect(() => decodeEvent(JSON.stringify({...planFailure, payload: {
      ...planFailure.payload, body: 'SECRET_PROVIDER_BODY',
    }}), 3)).toThrowError(/plan\.execution\.failed/);
  });

  it('严格接受有界 Plan evidence correction 并拒绝额外或非法字段', () => {
    const correction = {
      version: 0, type: 'plan.verification.correction', requestId: 'req-plan',
      sessionId: 'session-1', runId: 'run-1', sequence: 4,
      payload: {attempt: 1, maxAttempts: 2, incompleteTaskCount: 1,
        incompleteTaskIds: ['task-1'], failures: [{
          requirementId: 'weather-xlsx', kind: 'deliverable', locator: '河南各市7天天气.xlsx',
          reason: 'FILE_MISSING_OR_UNSAFE',
        }]},
    };
    expect(decodeEvent(JSON.stringify(correction), 4).payload.failures).toEqual(correction.payload.failures);
    expect(() => decodeEvent(JSON.stringify({...correction, payload: {
      ...correction.payload, prompt: 'PRIVATE_PROMPT',
    }}), 4)).toThrowError(/plan lifecycle correction/);
    expect(() => decodeEvent(JSON.stringify({...correction, payload: {
      attempt: 2, maxAttempts: 1, failures: correction.payload.failures,
    }}), 4)).toThrowError(/correction/);
    expect(() => decodeEvent(JSON.stringify({...correction, payload: {
      ...correction.payload, failures: [{...correction.payload.failures[0], output: 'SECRET_OUTPUT'}],
    }}), 4)).toThrowError(/correction/);
  });

  it('接受关联 Run 的类型化预算治理事件', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0, type: 'run.budget.governed', requestId: 'req-budget',
      sessionId: 'session-1', runId: 'run-1', sequence: 1,
      payload: {reason: 'explicit_limit', modelTurns: 16, toolCalls: 16,
        totalModelTurns: 16},
    }), 1);
    expect(event.payload.reason).toBe('explicit_limit');
  });

  it('严格接受 accepted 后、run.started 前的隐私安全启动失败', () => {
    const event = {
      version: 0, type: 'run.launch.failed', requestId: 'req-launch',
      sessionId: 'session-1', sequence: 1,
      payload: {code: 'RUNTIME_LAUNCH_FAILED', stopReason: 'internal_error'},
    };
    expect(decodeEvent(JSON.stringify(event), 1).payload.code).toBe('RUNTIME_LAUNCH_FAILED');
    expect(() => decodeEvent(JSON.stringify({...event, payload: {
      ...event.payload, exception: 'SECRET_STACK',
    }}), 1)).toThrowError(/run\.launch\.failed/);
  });

  it('接受严格有界的 Plan proposal 并拒绝额外执行字段', () => {
    const base = {
      version: 0,
      type: 'plan.proposed',
      requestId: 'req-plan',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        planId: 'plan-run-1',
        status: 'awaiting_approval',
        objective: 'safe change',
        workspaceDigest: 'a'.repeat(64),
        steps: [{ordinal: 1, title: 'inspect', detail: 'read files'}],
      },
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.objective).toBe('safe change');
    expect(() => decodeEvent(JSON.stringify({...base, payload: {
      ...base.payload, steps: [{...base.payload.steps[0], command: 'rm'}],
    }}), 1)).toThrowError(/plan\.proposed/);
  });

  it('接受 privacy-safe Skill lifecycle 并拒绝参数或正文泄漏', () => {
    const invoked = {
      version: 0, type: 'skill.invoked', requestId: 'req-skill', sessionId: 'session-1', sequence: 1,
      payload: {skillId: 'code-review', invocationKind: 'explicit'},
    };
    expect(decodeEvent(JSON.stringify(invoked), 1).payload.skillId).toBe('code-review');
    const completed = {
      ...invoked, type: 'skill.completed', runId: 'run-1', sequence: 2,
      payload: {skillId: 'code-review', invocationKind: 'explicit', status: 'succeeded', stopReason: 'completed'},
    };
    expect(decodeEvent(JSON.stringify(completed), 2).payload.status).toBe('succeeded');
    expect(() => decodeEvent(JSON.stringify({
      ...invoked, payload: {...invoked.payload, arguments: 'SECRET'},
    }), 1)).toThrowError(/Skill/);
    expect(() => decodeEvent(JSON.stringify({...completed, runId: undefined}), 2)).toThrowError(/runId/);
  });

  it('严格接受 providers.add 非秘密投影并拒绝 endpoint 或控制字', () => {
    const base = {
      version: 0, type: 'provider.control.result', requestId: 'provider-add', sessionId: 'session-1', sequence: 1,
      payload: {controlId: 'tui-connect:1:action:provider', intent: 'providers.add', status: 'succeeded', code: 'OK',
        result: {providerId: 'team', displayName: 'Team Gateway', modelId: 'model-x'}},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.result).toEqual(base.payload.result);
    expect(() => decodeEvent(JSON.stringify({...base, payload: {...base.payload,
      result: {...base.payload.result, baseUrl: 'https://private.example'}}}), 1)).toThrowError(/provider/);
    expect(() => decodeEvent(JSON.stringify({...base, payload: {...base.payload,
      result: {...base.payload.result, displayName: 'bad\nname'}}}), 1)).toThrowError(/provider/);
  });

  it.each([
    ['models.add', {providerId: 'anthropic', modelId: 'model-x', setDefault: true}],
    ['models.remove', {providerId: 'anthropic', modelId: 'model-x'}],
    ['models.use', {providerId: 'anthropic', profileId: 'default', modelId: 'model-x', setDefault: true}],
  ])('严格接受 %s 正式结果投影并拒绝多余字段', (intent, result) => {
    const event = {
      version: 0, type: 'provider.control.result', requestId: `request-${intent}`,
      sessionId: 'session-1', sequence: 1,
      payload: {controlId: `control-${intent}`, intent, status: 'succeeded', code: 'OK', result},
    };
    expect(decodeEvent(JSON.stringify(event), 1).payload.result).toEqual(result);
    expect(() => decodeEvent(JSON.stringify({...event, payload: {...event.payload,
      result: {...result, unexpected: true}}}), 1)).toThrowError(/provider\.control/);
  });

  it('拒绝 models.add/use 非 boolean setDefault', () => {
    for (const intent of ['models.add', 'models.use']) {
      const result = intent === 'models.use'
        ? {providerId: 'anthropic', profileId: 'default', modelId: 'm', setDefault: 'true'}
        : {providerId: 'anthropic', modelId: 'm', setDefault: 'true'};
      expect(() => decodeEvent(JSON.stringify({
        version: 0, type: 'provider.control.result', requestId: 'request', sessionId: 'session-1', sequence: 1,
        payload: {controlId: 'control', intent, status: 'succeeded', code: 'OK', result},
      }), 1)).toThrowError(/provider\.control/);
    }
  });

  it('拒绝乱序事件', () => {
    expect(() => decodeEvent(JSON.stringify({
      version: 0,
      type: 'initialized',
      requestId: 'req-1',
      sessionId: 'session-1',
      sequence: 3,
      payload: {},
    }), 1)).toThrowError(ProtocolViolation);
  });

  it('拒绝缺失 Run 关联的 Delta', () => {
    expect(() => decodeEvent(JSON.stringify({
      version: 0,
      type: 'model.text.delta',
      requestId: 'req-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {text: 'x'},
    }), 1)).toThrowError(/runId/);
  });

  it('接受无 Run 的 Checkpoint 投影并拒绝伪造 Run 关联', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'checkpoint.listed',
      requestId: 'req-checkpoint',
      sessionId: 'session-1',
      sequence: 1,
      payload: {
        checkpoints: [{
          checkpointId: 'checkpoint-run-1-1',
          callId: 'call-1',
          toolName: 'apply_patch',
          target: 'src/App.java',
          existedBefore: true,
          phase: 'completed_present',
          undoable: true,
        }],
      },
    }), 1);

    expect(event.payload.checkpoints).toBeDefined();
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      runId: 'run-forged',
    }), 1)).toThrowError(/不能携带 runId/);
  });

  it('严格校验 Checkpoint 列表的 phase、相对路径、字段和数量', () => {
    const base = {
      version: 0,
      type: 'checkpoint.listed',
      requestId: 'req-checkpoint',
      sessionId: 'session-1',
      sequence: 1,
      payload: {
        checkpoints: [{
          checkpointId: 'checkpoint-run-1-1',
          callId: 'call-1',
          toolName: 'apply_patch',
          target: 'src/App.java',
          existedBefore: true,
          phase: 'completed_present',
          undoable: true,
        }],
      },
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.checkpoints).toHaveLength(1);

    for (const payload of [
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], phase: 'unknown'}]},
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], target: 'C:\\secret.txt'}]},
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], undoable: false}]},
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], secret: 'leak'}]},
      {checkpoints: Array.from({length: 1_001}, () => base.payload.checkpoints[0])},
    ]) {
      expect(() => decodeEvent(JSON.stringify({...base, payload}), 1))
        .toThrowError(/checkpoint\.listed/);
    }
  });

  it('严格校验 Checkpoint Diff 和 Undo 的有界安全投影', () => {
    const diff = {
      version: 0,
      type: 'checkpoint.diffed',
      requestId: 'req-diff',
      sessionId: 'session-1',
      sequence: 1,
      payload: {
        checkpointId: 'checkpoint-run-1-1',
        target: 'src/App.java',
        status: 'changed',
        text: '-old\n+new\n',
        truncated: false,
      },
    };
    expect(decodeEvent(JSON.stringify(diff), 1).payload.status).toBe('changed');
    expect(() => decodeEvent(JSON.stringify({
      ...diff,
      payload: {...diff.payload, text: 'x'.repeat(16 * 1_024 + 1)},
    }), 1)).toThrowError(/checkpoint\.diffed/);
    expect(() => decodeEvent(JSON.stringify({
      ...diff,
      payload: {...diff.payload, status: 'restored'},
    }), 1)).toThrowError(/checkpoint\.diffed/);

    const undo = {
      ...diff,
      type: 'checkpoint.undone',
      requestId: 'req-undo',
      payload: {
        checkpointId: 'checkpoint-run-1-1',
        target: 'src/App.java',
        status: 'restored',
        message: 'Checkpoint 已恢复',
      },
    };
    expect(decodeEvent(JSON.stringify(undo), 1).payload.status).toBe('restored');
    expect(() => decodeEvent(JSON.stringify({
      ...undo,
      payload: {...undo.payload, providerText: 'secret'},
    }), 1)).toThrowError(/checkpoint\.undone/);
  });

  it('只接受白名单 steering 安全投影', () => {
    const queued = {
      version: 0, type: 'steering.queued', requestId: 'steering-1', sessionId: 'session-1', sequence: 1,
      payload: {queueDepth: 1},
    };
    expect(decodeEvent(JSON.stringify(queued), 1).payload.queueDepth).toBe(1);
    const discarded = {
      ...queued, type: 'steering.discarded', requestId: 'steering-2',
      payload: {reason: 'cancelled'},
    };
    expect(decodeEvent(JSON.stringify(discarded), 1).payload.reason).toBe('cancelled');
    for (const invalid of [
      {...queued, payload: {queueDepth: 0}},
      {...queued, payload: {queueDepth: 101}},
      {...queued, runId: 'run-1'},
      {...discarded, payload: {reason: 'secret'}},
      {...discarded, payload: {reason: 'clear', prompt: 'SECRET_PROMPT'}},
    ]) {
      expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrowError(/steering/);
    }
  });

  it('接受严格且无 Run 关联的 session command terminal result', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-command',
      sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'command-1', intent: 'doctor', status: 'rejected', code: 'deferred', result: {}},
    }), 1);
    expect(event.type).toBe('session.command.result');
    expect(() => decodeEvent(JSON.stringify({
      ...event, runId: 'run-1', payload: {...event.payload, secret: 'leak'},
    }), 1)).toThrowError(/session\.command\.result/);
  });

  it('按 intent 严格校验 session command 投影，拒绝泄漏、未知字段和超限数组', () => {
    const base = {
      version: 0, type: 'session.command.result', requestId: 'req-command',
      sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'command-1', intent: 'help', status: 'succeeded', code: 'ok', result: {
        commands: [
          {intent: 'help', support: 'available'}, {intent: 'clear', support: 'deferred'},
          {intent: 'compact', support: 'not_available'}, {intent: 'context', support: 'available'},
          {intent: 'doctor', support: 'available'}, {intent: 'model', support: 'not_available'},
          {intent: 'permissions', support: 'deferred'}, {intent: 'resume', support: 'deferred'},
          {intent: 'tasks', support: 'available'}, {intent: 'plan-status', support: 'available'},
          {intent: 'plan', support: 'available'},
        ],
      }},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.intent).toBe('help');
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {commands: base.payload.result.commands, providerText: 'secret'}},
    }), 1)).toThrowError(/session\.command\.result/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {commands: [...base.payload.result.commands, ...base.payload.result.commands]}},
    }), 1)).toThrowError(/session\.command\.result/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, status: 'succeeded', code: 'active_run', result: {}},
    }), 1)).toThrowError(/session\.command\.result/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, commandId: 'bad\ncommand'},
    }), 1)).toThrowError(/session\.command\.result/);
  });

  it('严格校验 mutation-driven Task Board snapshot 与 /tasks 查询投影', () => {
    const snapshot = {
      boardRevision: 3, totalTasks: 2, truncated: false, tasks: [
        {taskId: 'task-1', revision: 2, status: 'IN_PROGRESS', subject: '实现刷新', blocked: false,
          blockerIds: [], owner: 'root', activeForm: '编写测试', recoveryRequired: false},
        {taskId: 'task-2', revision: 4, status: 'COMPLETED', subject: '审查现状', blocked: false,
          blockerIds: [], owner: null, activeForm: null, recoveryRequired: false},
      ],
    };
    expect(decodeEvent(JSON.stringify({
      version: 0, type: 'task.board.snapshot', requestId: 'req-task', sessionId: 'session-1',
      runId: 'run-1', sequence: 1, payload: snapshot,
    }), 1).payload).toEqual(snapshot);
    expect(decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-tasks', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'tasks-1', intent: 'tasks', status: 'succeeded', code: 'ok', result: snapshot},
    }), 1).payload.result).toEqual(snapshot);

    for (const invalid of [
      {version: 0, type: 'task.board.snapshot', requestId: 'req-task', runId: 'run-1', sequence: 1, payload: snapshot},
      {version: 0, type: 'task.board.snapshot', requestId: 'req-task', sessionId: 'session-1', sequence: 1, payload: snapshot},
      {version: 0, type: 'task.board.snapshot', requestId: 'req-task', sessionId: 'session-1', runId: 'run-1',
        sequence: 1, payload: {...snapshot, secret: 'leak'}},
      {version: 0, type: 'task.board.snapshot', requestId: 'req-task', sessionId: 'session-1', runId: 'run-1',
        sequence: 1, payload: {...snapshot, tasks: [snapshot.tasks[0], snapshot.tasks[0]]}},
      {version: 0, type: 'task.board.snapshot', requestId: 'req-task', sessionId: 'session-1', runId: 'run-1',
        sequence: 1, payload: {...snapshot, tasks: [{...snapshot.tasks[0], status: 'CANCELLED'}]}},
      {version: 0, type: 'task.board.snapshot', requestId: 'req-task', sessionId: 'session-1', runId: 'run-1',
        sequence: 1, payload: {...snapshot, totalTasks: 51, truncated: true,
          tasks: Array.from({length: 51}, (_, index) => ({...snapshot.tasks[0], taskId: `task-${index + 1}`}))}},
    ]) {
      expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrowError(/Task Board|task\.board\.snapshot/);
    }
  });

  it('严格校验 permissions 安全投影且不接受 selector 泄漏', () => {
    const result = {
      effectiveMode: 'PLAN', effectiveReviewer: 'USER', effectiveSelection: 'PLAN',
      modeSourceKind: 'PROJECT_SHARED', modeSafeSourceId: 'project-shared',
      modeValidationStatus: 'VALID', startupRuleCount: 1,
      rules: [{ruleId: 'project-read', sourceKind: 'PROJECT_SHARED', safeSourceId: 'project-shared',
        operation: 'REPLACE', validationStatus: 'VALID'}],
    };
    expect(decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-permissions', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'permissions-1', intent: 'permissions', status: 'succeeded', code: 'ok', result},
    }), 1).payload.result).toEqual(result);
    for (const invalidResult of [
      {...result, rules: [{...result.rules[0], selector: 'secret'}]},
      (({effectiveReviewer: _ignored, ...rest}) => rest)(result),
      {...result, effectiveReviewer: 'ROBOT'},
      {...result, effectiveSelection: 'ACCEPT_EDITS'},
      {...result, unexpected: true},
    ]) {
      expect(() => decodeEvent(JSON.stringify({
        version: 0, type: 'session.command.result', requestId: 'req-permissions', sessionId: 'session-1', sequence: 1,
        payload: {commandId: 'permissions-1', intent: 'permissions', status: 'succeeded', code: 'ok', result: invalidResult},
      }), 1)).toThrowError(/permissions/);
    }
  });

  it('接受 overflow context 的负 freeTokens，但仍拒绝不安全数值', () => {
    const result = {
      systemTokens: 10, transcriptTokens: 20, toolTokens: 0, memoryTokens: 0,
      totalTokens: 30, availableInputTokens: 25, freeTokens: -5, overflowTokens: 5,
      sourceRevision: 1, estimateKind: 'HEURISTIC', contextStatus: 'OVERFLOW',
      modelRequestAttempts: 0, reductionStrategies: [], reasonCodes: ['OVERFLOW'],
    };
    expect(decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-context', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'context-1', intent: 'context', status: 'succeeded', code: 'ok', result},
    }), 1).payload.result).toEqual(result);
    expect(() => decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-context', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'context-1', intent: 'context', status: 'succeeded', code: 'ok', result: {...result, freeTokens: Number.MAX_SAFE_INTEGER + 1}},
    }), 1)).toThrowError(/context/);
  });

  it('接受 resume 的最小会话切换投影并拒绝路径或额外字段', () => {
    const base = {
      version: 0, type: 'session.command.result', requestId: 'req-resume', sessionId: 'session-target', sequence: 1,
      payload: {commandId: 'resume-1', intent: 'resume', status: 'succeeded', code: 'ok', result: {
        previousSessionId: 'session-source', resumedSessionId: 'session-target',
      }},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.result).toEqual(base.payload.result);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {...base.payload.result, storagePath: 'C:\\secret'}},
    }), 1)).toThrowError(/resume/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {...base.payload.result, resumedSessionId: 'session-source'}},
    }), 1)).toThrowError(/resume/);
  });

  it('严格校验有界 file suggestions 安全投影', () => {
    const base = {
      version: 0, type: 'file.suggestions', requestId: 'req-file', sessionId: 'session-1', sequence: 1,
      payload: {query: 'src', candidates: ['src/App.java', 'dir/file name.md']},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.candidates).toHaveLength(2);
    for (const invalid of [
      {...base, runId: 'run-1'},
      {...base, payload: {...base.payload, secret: 'leak'}},
      {...base, payload: {query: 'src', candidates: ['../escape']}},
      {...base, payload: {query: 'src', candidates: Array.from({length: 33}, (_, i) => `${i}`)}},
      {...base, payload: {query: 'src', candidates: ['same', 'same']}},
    ]) expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrow(/file\.suggestions/);
  });

  it('只接受 Java 权威且有界的 task.worktree 投影', () => {
    const base = {
      version: 0,
      type: 'task.worktree',
      requestId: 'req-worktree',
      sessionId: 'session-1',
      sequence: 1,
      payload: {taskId: 'task-a', disposition: 'removed'},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.disposition).toBe('removed');
    for (const invalid of [
      {...base, runId: 'run-1'},
      {...base, payload: {...base.payload, taskId: 'invalid'}},
      {...base, payload: {...base.payload, disposition: 'x'.repeat(65)}},
      {...base, payload: {...base.payload, secret: 'leak'}},
    ]) {
      expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrowError(/task\.worktree/);
    }
  });

  it('拒绝包含控制字符的终止原因', () => {
    expect(() => decodeEvent(JSON.stringify({
      version: 0,
      type: 'run.failed',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {stopReason: 'model_error\n伪造终端输出'},
    }), 1)).toThrowError(/stopReason/);
  });

  it('只接受白名单模型失败摘要', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'run.failed',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        stopReason: 'model_retry_exhausted',
        modelTurns: 1,
        toolCalls: 0,
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
        },
      },
    }), 1);

    expect(event.payload.modelFailure).toEqual(expect.objectContaining({attempts: 3}));
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {
        ...event.payload,
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
          message: 'SECRET_PROVIDER_TEXT',
        },
      },
    }), 1)).toThrowError(/模型失败摘要/);
  });

  it('接受安全 Tool 展示摘要并拒绝未知模式', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'tool.completed',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        ordinal: 1,
        toolName: 'search_text',
        status: 'success',
        mode: 'content',
        returnedItems: 12,
        truncationReason: 'item_limit',
      },
    }), 1);

    expect(event.payload.returnedItems).toBe(12);
    const command = {...event, payload: {
      ordinal: 2, toolName: 'run_command', status: 'failed', exitCode: 9,
    }};
    expect(decodeEvent(JSON.stringify(command), 1).payload.exitCode).toBe(9);
    expect(() => decodeEvent(JSON.stringify({
      ...command, payload: {...command.payload, exitCode: -2},
    }), 1)).toThrowError(/退出码/);
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {...event.payload, mode: 'raw'},
    }), 1)).toThrowError(/模式/);
  });

  it('只接受带固定副作用分类的审批摘要', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'approval.requested',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        approvalId: 'approval-1',
        ordinal: 2,
        toolName: 'apply_patch',
        effect: 'write_workspace',
        target: 'src/main/App.java',
        operation: 'modify',
        removedLines: 2,
        addedLines: 3,
      },
    }), 1);

    expect(event.payload.approvalId).toBe('approval-1');
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {...event.payload, effect: 'system_or_destructive'},
    }), 1)).toThrowError(/审批摘要/);
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {...event.payload, target: 'C:\\secret.txt'},
    }), 1)).toThrowError(/文件预览/);
  });

  it('接受准确命令审批和有界 Tool 输出事件', () => {
    const approval = decodeEvent(JSON.stringify({
      version: 0,
      type: 'approval.requested',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        approvalId: 'approval-command',
        ordinal: 1,
        toolName: 'run_command',
        effect: 'execute_process',
        operation: 'execute',
        command: 'mvn test',
        shell: 'powershell',
        workingDirectory: '.',
      },
    }), 1);
    const output = decodeEvent(JSON.stringify({
      version: 0,
      type: 'tool.output',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 2,
      payload: {
        ordinal: 1,
        toolName: 'run_command',
        stream: 'stdout',
        text: 'BUILD SUCCESS\n',
      },
    }), 2);

    expect(approval.payload.command).toBe('mvn test');
    expect(output.payload.text).toContain('BUILD SUCCESS');
  });

  it('严格接受 providers.configure 安全投影且不允许 endpoint 回传', () => {
    const base = {
      version: 0, type: 'provider.control.result', requestId: 'quick', sessionId: 'session-1', sequence: 1,
      payload: {controlId: 'tui-setup:1:configure', intent: 'providers.configure', status: 'succeeded', code: 'OK',
        result: {providerId: 'codej-custom', displayName: 'CodeJ Custom', modelId: 'model-x'}},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.result).toEqual(base.payload.result);
    expect(() => decodeEvent(JSON.stringify({...base, payload: {...base.payload,
      result: {...base.payload.result, baseUrl: 'https://private.example'}}}), 1)).toThrowError(/provider/);
  });

  it('接受固定目的类型和有界查询的网络审批摘要', () => {
    const approval = decodeEvent(JSON.stringify({
      version: 0,
      type: 'approval.requested',
      requestId: 'req-network',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        approvalId: 'approval-network',
        ordinal: 1,
        toolName: 'web_search',
        effect: 'network_or_remote',
        operation: 'search',
        destination: 'configured_web_search_provider',
        query: '明天杭州天气',
      },
    }), 1);

    expect(approval.payload.query).toBe('明天杭州天气');
    expect(() => decodeEvent(JSON.stringify({
      ...approval,
      payload: {...approval.payload, destination: 'https://attacker.example'},
    }), 1)).toThrowError(/网络预览/);
    expect(() => decodeEvent(JSON.stringify({
      ...approval,
      payload: {...approval.payload, query: 'weather\nspoof'},
    }), 1)).toThrowError(/网络预览/);
  });
});

describe('continuous plan protocol', () => {
  it('accepts durable Markdown review and structured question, rejecting leaked fields', () => {
    const review = {
      version: 0, type: 'plan.review.requested', requestId: 'plan', sessionId: 'session-1',
      runId: 'run-1', sequence: 1,
      payload: {planId: 'plan-abc', status: 'awaiting_approval', revision: 3,
        contentDigest: 'a'.repeat(64), markdown: '# Plan\n\nRead safely.', workspaceDigest: 'b'.repeat(64), originalPermissionMode: 'default', suggestedContextPolicy: 'keep'},
    };
    expect(decodeEvent(JSON.stringify(review), 1).payload.markdown).toContain('# Plan');
    const detachedReview = {...review, requestId: 'resume-1'};
    delete (detachedReview as {runId?: string}).runId;
    expect(decodeEvent(JSON.stringify(detachedReview), 1).runId).toBeUndefined();
    const reviewWithoutSession = {...detachedReview};
    delete (reviewWithoutSession as {sessionId?: string}).sessionId;
    expect(() => decodeEvent(JSON.stringify(reviewWithoutSession), 1)).toThrowError(/缺少 sessionId/);
    expect(() => decodeEvent(JSON.stringify({...review,
      payload: {...review.payload, objective: 'hidden'}}), 1)).toThrowError(/plan\.review/);

    const question = {
      version: 0, type: 'question.requested', requestId: 'plan', sessionId: 'session-1',
      runId: 'run-1', sequence: 2,
      payload: {callId: 'ask-1', question: 'Choose rollout', options: [
        {optionId: 'safe', label: 'Safe', description: 'Staged'},
        {optionId: 'fast', label: 'Fast', description: 'Direct'},
      ]},
    };
    expect(decodeEvent(JSON.stringify(question), 2).payload.options).toHaveLength(2);
    expect(() => decodeEvent(JSON.stringify({...question,
      payload: {...question.payload, rawArguments: '{}'}}), 2)).toThrowError(/question\.requested/);
  });
});
