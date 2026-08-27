import {describe, expect, it} from 'vitest';
import {
  initialTuiState,
  orderedSessionTasks,
  reduceTuiState as reduceProductionState,
  type TuiAction,
  type TuiState,
} from '../src/state.js';
import type {ProtocolEvent} from '../src/protocol.js';

/** 既有下游投影用例默认模拟 Java 已先发布 accepted；handshake 专项用例直接调用 production reducer。 */
function reduceTuiState(state: TuiState, action: TuiAction): TuiState {
  if (action.type === 'event.received' && action.event.type === 'run.started') {
    const pending = state.runs.findLast(run => run.requestId === action.event.requestId);
    if (pending?.status === 'submitting' && action.event.sessionId === state.sessionId) {
      state = reduceProductionState(state, {
        type: 'event.received',
        event: event('run.command.result', action.event.sequence, {
          commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED',
        }, action.event.requestId, action.event.sessionId),
      });
    }
  }
  return reduceProductionState(state, action);
}

describe('reduceTuiState', () => {
  it('只有 acceptance 后的 run.started 才进入 running，并允许 authority 尚未启动时继续提交', () => {
    let state = reduceProductionState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceProductionState(state, {type: 'run.submitted', requestId: 'first', prompt: 'first'});
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'first', 'session-1', 'run-1'),
    });
    expect(state.runs[0]).toEqual(expect.objectContaining({status: 'submitting', runId: undefined}));
    expect(state.phase).toBe('submitting');

    state = reduceProductionState(state, {
      type: 'event.received',
      event: event('run.command.result', 3, {
        commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED',
      }, 'first', 'session-1'),
    });
    expect(state.phase).toBe('accepted');
    state = reduceProductionState(state, {type: 'run.submitted', requestId: 'second', prompt: 'second'});
    expect(state.runs.at(-1)).toEqual(expect.objectContaining({requestId: 'second', status: 'submitting'}));
    state = reduceProductionState(state, {
      type: 'event.received',
      event: event('run.command.result', 4, {
        commandType: 'run.start', disposition: 'queued', code: 'QUEUED', queueDepth: 1,
      }, 'second', 'session-1'),
    });
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.started', 5, {}, 'first', 'session-1', 'run-1'),
    });
    expect(state.phase).toBe('running');
    expect(state.activeRunId).toBe('run-1');
    expect(state.runs).toEqual([
      expect.objectContaining({requestId: 'first', status: 'running', runId: 'run-1'}),
      expect.objectContaining({requestId: 'second', status: 'queued', runId: undefined}),
    ]);
  });

  it('accepted 后 Runtime 启动失败会删除未启动 Run 并恢复 ready', () => {
    let state = reduceProductionState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceProductionState(state, {type: 'run.submitted', requestId: 'launch', prompt: 'task'});
    state = reduceProductionState(state, {type: 'event.received', event: event(
      'run.command.result', 2,
      {commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED'},
      'launch', 'session-1',
    )});
    state = reduceProductionState(state, {type: 'event.received', event: event(
      'run.launch.failed', 3,
      {code: 'RUNTIME_LAUNCH_FAILED', stopReason: 'internal_error'},
      'launch', 'session-1',
    )});

    expect(state.phase).toBe('ready');
    expect(state.runs).toEqual([]);
    expect(state.notice).toContain('Runtime 启动失败');
  });

  it('只根据 Java 终态完成一次流式 Run', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'req-init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-run',
      prompt: '解释项目',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('model.text.delta', 3, {text: '你好'}, 'req-run', 'session-1', 'run-1'),
    });

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.started', 4, {ordinal: 1, toolName: 'read_file'}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.completed', 5, {
        ordinal: 1,
        toolName: 'read_file',
        status: 'success',
        returnedCharacters: 42,
        returnedItems: 2,
        filteredItems: 3,
        truncated: true,
        truncationReason: 'item_limit',
      }, 'req-run', 'session-1', 'run-1'),
    });

    expect(state.phase).toBe('running');
    expect(state.runs[0]?.text).toBe('你好');
    expect(state.runs[0]?.tools).toEqual([
      expect.objectContaining({
        name: 'read_file',
        status: 'success',
        returnedItems: 2,
        filteredItems: 3,
        truncated: true,
        truncationReason: 'item_limit',
      }),
    ]);

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.completed', 6, {
        stopReason: 'completed',
        modelTurns: 2,
        toolCalls: 1,
        finalText: '不应重复追加的完整文本',
      }, 'req-run', 'session-1', 'run-1'),
    });
    expect(state.phase).toBe('ready');
    expect(state.runs[0]).toEqual(expect.objectContaining({
      text: '你好',
      status: 'completed',
      stopReason: 'completed',
      modelTurns: 2,
      toolCalls: 1,
    }));
  });

  it('累加实测 Provider Usage 并保留最新上下文估算，不投影隐藏思维文本', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'req-run', prompt: '分析项目'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('model.turn.started', 3, {turn: 1}, 'req-run', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.modelProgress).toEqual(expect.objectContaining({turn: 1, phase: 'thinking'}));
    state = reduceTuiState(state, {
      type: 'event.received', event: event('model.turn.completed', 4, {
        turn: 1, finishReason: 'tool_calls',
        usage: {inputTokens: 1_200, outputTokens: 80, totalTokens: 1_280},
        context: {usedTokens: 4_000, maximumInputTokens: 128_000, estimateKind: 'estimated'},
      }, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('model.turn.started', 5, {turn: 2}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('model.turn.completed', 6, {
        turn: 2, finishReason: 'stop',
      }, 'req-run', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.modelProgress).toEqual(expect.objectContaining({
      turn: 2, providerInputTokens: 1_200, providerOutputTokens: 80,
      providerTotalTokens: 1_280, usageReportedTurns: 1, usageMissingTurns: 1,
      contextUsedTokens: 4_000, contextMaximumInputTokens: 128_000,
    }));
    expect(JSON.stringify(state)).not.toContain('PRIVATE_CHAIN_OF_THOUGHT');
  });

  it('投影重试进度并把 Plan 执行失败与验证状态分离', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'req-plan', prompt: '执行计划'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'req-plan', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('model.turn.started', 3, {turn: 1}, 'req-plan', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('model.retry.scheduled', 4, {
        turn: 1, failedAttempt: 1, nextAttempt: 2, maxAttempts: 11,
        waitMillis: 2_000, category: 'rate_limited',
      }, 'req-plan', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.modelProgress).toEqual(expect.objectContaining({
      retryAttempt: 2, retryMaxAttempts: 11, retryWaitMillis: 2_000,
      retryCategory: 'rate_limited',
    }));
    state = reduceTuiState(state, {
      type: 'event.received', event: event('plan.execution.failed', 5, {
        planId: 'plan-1', status: 'failed', stopReason: 'model_retry_exhausted',
        modelFailure: {category: 'provider_unavailable', statusClass: '5xx',
          attempts: 11, receivedOutput: false},
      }, 'req-plan', 'session-1'),
    });
    expect(state.notice).toContain('不会自动重放');
    expect(state.notice).not.toContain('需要验证');
  });

  it('Plan evidence correction 保持同一 Run 运行且不会展示未验证 final', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'req-plan', prompt: '执行计划'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'req-plan', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('plan.verification.correction', 3, {
        attempt: 1, maxAttempts: 2, incompleteTaskCount: 1, incompleteTaskIds: ['task-1'],
        failures: [{requirementId: 'weather-xlsx', kind: 'deliverable',
          locator: '河南各市7天天气.xlsx', reason: 'FILE_MISSING_OR_UNSAFE'}],
      }, 'req-plan', 'session-1', 'run-1'),
    });
    expect(state.phase).toBe('running');
    expect(state.notice).toContain('同一 Run 内纠正（1/2）');
    expect(state.notice).toContain('不会自动重放');
    expect(state.runs[0]?.text).toBe('');
    state = reduceTuiState(state, {
      type: 'event.received', event: event('plan.verification.required', 4, {
        planId: 'plan-1', status: 'needs_verification', requiredEvidence: 1, satisfiedEvidence: 0,
      }, 'req-plan', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.failed', 5, {
        stopReason: 'plan_verification_required', modelTurns: 2, toolCalls: 0,
      }, 'req-plan', 'session-1', 'run-1'),
    });
    expect(state.phase).toBe('ready');
    expect(state.runs[0]).toEqual(expect.objectContaining({
      text: '', status: 'failed', stopReason: 'plan_verification_required',
      planVerification: '计划尚未完成：需要验证 required-evidence-not-declared（0/1）',
    }));
  });

  it('显式 Plan resume 只接受同 Session 与 pending requestId 的 detached review', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'plan.resume.requested', requestId: 'resume-1'});
    const duplicate = reduceTuiState(state, {type: 'plan.resume.requested', requestId: 'resume-2'});
    expect(duplicate.pendingPlanResumeRequestId).toBe('resume-1');

    const payload = {planId: 'plan-1', status: 'awaiting_approval', revision: 8,
      contentDigest: 'a'.repeat(64), markdown: '# Resume', workspaceDigest: 'b'.repeat(64),
      originalPermissionMode: 'default', suggestedContextPolicy: 'keep'};
    const unknown = reduceTuiState(state, {
      type: 'event.received', event: event('plan.review.requested', 2, payload, 'spoof', 'session-1'),
    });
    expect(unknown.detachedPlanReview).toBeUndefined();
    expect(unknown.pendingPlanResumeRequestId).toBe('resume-1');
    const wrongSession = reduceTuiState(state, {
      type: 'event.received', event: event('plan.review.requested', 3, payload, 'resume-1', 'session-other'),
    });
    expect(wrongSession.detachedPlanReview).toBeUndefined();

    state = reduceTuiState(state, {
      type: 'event.received', event: event('plan.review.requested', 4, payload, 'resume-1', 'session-1'),
    });
    expect(state.pendingPlanResumeRequestId).toBeUndefined();
    expect(state.detachedPlanReview).toEqual(expect.objectContaining({planId: 'plan-1', revision: 8}));
    const late = reduceTuiState(state, {
      type: 'event.received', event: event('plan.review.requested', 5,
        {...payload, revision: 9}, 'resume-1', 'session-1'),
    });
    expect(late.detachedPlanReview?.revision).toBe(8);

    state = reduceTuiState(state, {type: 'event.received', event: event('plan.execution.accepted', 6,
      {planId: 'plan-1', status: 'approved'}, 'resume-decision', 'session-1')});
    expect(state.detachedPlanReview).toBeUndefined();
  });

  it('在没有流式 delta 时使用 Java 终态 finalText', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'req-init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'req-run', prompt: '执行计划'});
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.completed', 3, {
        stopReason: 'completed', modelTurns: 2, toolCalls: 1, finalText: 'approved plan executed',
      }, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('plan.verification.completed', 4, {
        satisfiedEvidence: 1, requiredEvidence: 1,
      }, 'req-run', 'session-1'),
    });

    expect(state.runs[0]).toEqual(expect.objectContaining({
      text: 'approved plan executed', status: 'completed', planVerification: '计划证据已验证（1/1）',
    }));
  });

  it('只在 Java 成功 resume 终态时切换本地 Session 投影', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-source'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('session.command.result', 2, {
        commandId: 'resume-1', intent: 'resume', status: 'succeeded', code: 'ok',
        result: {previousSessionId: 'session-source', resumedSessionId: 'session-target'},
      }, 'resume', 'session-target'),
    });
    expect(state.sessionId).toBe('session-target');
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('session.command.result', 3, {
        commandId: 'resume-2', intent: 'resume', status: 'rejected', code: 'recovery_required', result: {},
      }, 'resume-rejected', 'session-target'),
    });
    expect(state.sessionId).toBe('session-target');
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('session.command.result', 4, {
        commandId: 'resume-3', intent: 'resume', status: 'succeeded', code: 'ok',
        result: {previousSessionId: 'session-stale', resumedSessionId: 'session-unrelated'},
      }, 'resume-mismatch', 'session-unrelated'),
    });
    expect(state.sessionId).toBe('session-target');
  });

  it('只投影 steering 深度与固定丢弃原因，不把文本写入 Run 或 notice', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted', requestId: 'steering-1', prompt: 'SECRET_PROMPT',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.command.result', 2, {
        commandType: 'run.start', disposition: 'queued', code: 'QUEUED', queueDepth: 1,
      }, 'steering-1', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('steering.queued', 3, {queueDepth: 1}, 'steering-1', 'session-1'),
    });
    expect(state.steeringQueueDepth).toBe(1);
    expect(state.notice).toBe('补充消息已排队（1/100）');
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('steering.discarded', 4, {reason: 'cancelled'}, 'steering-1', 'session-1'),
    });
    expect(state.steeringQueueDepth).toBe(0);
    expect(state.notice).toBe('当前 Run 取消，已丢弃一条未发送补充消息');
    expect(state.runs).toEqual([]);
    expect(JSON.stringify(state)).not.toContain('SECRET_PROMPT');
    state = reduceTuiState(state, {type: 'transport.failed', message: 'transport closed'});
    expect(state.steeringQueueDepth).toBe(0);
  });

  it('用 Java task.worktree 事件更新已有任务卡片而不自行决定删除', () => {
    const task = {
      taskId: 'task-a', definitionId: 'e2e', status: 'running' as const, failure: 'none',
      modelTurns: 0, toolCalls: 0, estimatedTokens: 0, elapsedMillis: 1,
      summary: '', verified: false, worktreeDisposition: undefined,
    };
    let state = reduceTuiState({...initialTuiState, childTasks: [task]}, {
      type: 'event.received',
      event: event('task.worktree', 1, {
        taskId: 'task-a', disposition: 'removed',
      }, 'req-remove', 'session-1'),
    });
    expect(state.childTasks).toEqual([{...task, worktreeDisposition: 'removed'}]);
    expect(state.notice).toContain('removed');

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('task.worktree', 2, {
        taskId: 'task-unknown', disposition: 'kept',
      }, 'req-keep', 'session-1'),
    });
    expect(state.childTasks).toEqual([{...task, worktreeDisposition: 'removed'}]);
  });

  it('不把协议错误文本原样显示', () => {
    const state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event(
        'protocol.error',
        1,
        {code: 'INVALID_INPUT', message: '可能包含秘密的原文'},
        'req-1',
      ),
    });

    expect(state.notice).toBe('Java 协议错误：INVALID_INPUT');
    expect(state.notice).not.toContain('秘密');
  });

  it('同一 Session 连续保留两个已完成 Run', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    for (const [index, prompt] of ['first', 'second'].entries()) {
      const requestId = `req-${index + 1}`;
      const runId = `run-${index + 1}`;
      state = reduceTuiState(state, {
        type: 'run.submitted',
        requestId,
        prompt,
      });
      state = reduceTuiState(state, {
        type: 'event.received',
        event: event(
          'run.started',
          2 + index * 3,
          {},
          requestId,
          'session-1',
          runId,
        ),
      });
      state = reduceTuiState(state, {
        type: 'event.received',
        event: event(
          'model.text.delta',
          3 + index * 3,
          {text: `answer-${index + 1}`},
          requestId,
          'session-1',
          runId,
        ),
      });
      state = reduceTuiState(state, {
        type: 'event.received',
        event: event(
          'run.completed',
          4 + index * 3,
          {stopReason: 'completed', modelTurns: 1, toolCalls: 0},
          requestId,
          'session-1',
          runId,
        ),
      });
    }

    expect(state.sessionId).toBe('session-1');
    expect(state.phase).toBe('ready');
    expect(state.runs).toEqual([
      expect.objectContaining({prompt: 'first', text: 'answer-1', status: 'completed'}),
      expect.objectContaining({prompt: 'second', text: 'answer-2', status: 'completed'}),
    ]);
  });

  it('保留 Java 返回的失败原因和预算计数', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-failed',
      prompt: '检查失败原因',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-failed', 'session-1', 'run-failed'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.failed', 3, {
        stopReason: 'turn_limit_reached',
        modelTurns: 16,
        toolCalls: 12,
      }, 'req-failed', 'session-1', 'run-failed'),
    });

    expect(state.runs[0]).toEqual(expect.objectContaining({
      status: 'failed',
      stopReason: 'turn_limit_reached',
      modelTurns: 16,
      toolCalls: 12,
    }));
  });

  it('投影类型化模型失败摘要', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted', requestId: 'req-model', prompt: '简单任务',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-model', 'session-1', 'run-model'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.failed', 3, {
        stopReason: 'model_retry_exhausted',
        modelTurns: 1,
        toolCalls: 0,
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
        },
      }, 'req-model', 'session-1', 'run-model'),
    });

    expect(state.runs[0]?.modelFailure).toEqual({
      category: 'provider_unavailable',
      statusClass: '5xx',
      attempts: 3,
      receivedOutput: false,
    });
  });

  it('投影单次审批并在用户提交决定后清理等待面板', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-run',
      prompt: '修改文件',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('approval.requested', 3, {
        approvalId: 'approval-1',
        ordinal: 1,
        toolName: 'apply_patch',
        effect: 'write_workspace',
        target: 'src/main/App.java',
        operation: 'modify',
        removedLines: 2,
        addedLines: 3,
      }, 'req-run', 'session-1', 'run-1'),
    });

    expect(state.runs[0]?.pendingApproval).toEqual({
      approvalId: 'approval-1',
      ordinal: 1,
      toolName: 'apply_patch',
      effect: 'write_workspace',
      target: 'src/main/App.java',
      operation: 'modify',
      removedLines: 2,
      addedLines: 3,
      command: undefined,
      shell: undefined,
      workingDirectory: undefined,
      destination: undefined,
      query: undefined,
      submitted: false,
    });
    state = reduceTuiState(state, {
      type: 'approval.submitted',
      approvalId: 'approval-1',
    });
    expect(state.runs[0]?.pendingApproval?.submitted).toBe(true);
    expect(state.phase).toBe('running');
  });

  it('投影受控网络审批的固定目的类型和查询', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-web',
      prompt: '查询天气',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-web', 'session-1', 'run-web'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('approval.requested', 3, {
        approvalId: 'approval-web',
        ordinal: 1,
        toolName: 'web_search',
        effect: 'network_or_remote',
        operation: 'search',
        destination: 'configured_web_search_provider',
        query: '明天杭州天气',
      }, 'req-web', 'session-1', 'run-web'),
    });

    expect(state.runs[0]?.pendingApproval).toMatchObject({
      effect: 'network_or_remote',
      destination: 'configured_web_search_provider',
      query: '明天杭州天气',
    });
  });

  it('保留 Checkpoint phase、Diff 和 Undo 投影并逐项选择', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('checkpoint.listed', 2, {
        checkpoints: [{
          checkpointId: 'checkpoint-run-1-1',
          callId: 'call-1',
          toolName: 'apply_patch',
          target: 'src/App.java',
          existedBefore: true,
          phase: 'completed_present',
          undoable: true,
        }, {
          checkpointId: 'checkpoint-run-1-2',
          callId: 'call-2',
          toolName: 'write_file',
          target: 'src/New.java',
          existedBefore: false,
          phase: 'post_journal_uncertain',
          undoable: false,
        }],
      }, 'checkpoint-list', 'session-1'),
    });

    expect(state.checkpointPanelOpen).toBe(true);
    expect(state.selectedCheckpointId).toBe('checkpoint-run-1-1');
    expect(state.checkpoints[1]).toEqual(expect.objectContaining({
      phase: 'post_journal_uncertain',
      undoable: false,
    }));
    state = reduceTuiState(state, {
      type: 'checkpoint.selected', checkpointId: 'checkpoint-run-1-2',
    });
    expect(state.selectedCheckpointId).toBe('checkpoint-run-1-2');
    state = reduceTuiState(state, {
      type: 'checkpoint.undo.requested', checkpointId: 'checkpoint-run-1-2',
    });
    expect(state.pendingUndoCheckpointId).toBeUndefined();
    expect(state.notice).toContain('不可 Undo');

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('checkpoint.diffed', 3, {
        checkpointId: 'checkpoint-run-1-1',
        target: 'src/App.java',
        status: 'changed',
        text: '-old\n+new\n',
        truncated: false,
      }, 'checkpoint-diff', 'session-1'),
    });
    expect(state.checkpointDiff).toEqual(expect.objectContaining({status: 'changed'}));

    state = reduceTuiState(state, {
      type: 'checkpoint.undo.requested', checkpointId: 'checkpoint-run-1-1',
    });
    expect(state.pendingUndoCheckpointId).toBe('checkpoint-run-1-1');
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('checkpoint.undone', 4, {
        checkpointId: 'checkpoint-run-1-1',
        target: 'src/App.java',
        status: 'restored',
        message: 'Checkpoint 已恢复',
      }, 'checkpoint-undo', 'session-1'),
    });
    expect(state.pendingUndoCheckpointId).toBeUndefined();
    expect(state.checkpoints[0]).toEqual(expect.objectContaining({
      phase: 'undone',
      undoable: false,
    }));
  });

  it('保留 Java 投影的类型化 Tool 失败治理元数据', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'req-failure', prompt: 'search'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'req-failure', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('tool.failed', 3, {
        ordinal: 1, toolName: 'web_search', status: 'failed', errorCode: 'web_search_forbidden',
        failureCategory: 'http_forbidden', retryable: false,
        argumentChangeRequired: true, strategyChangeRequired: true,
      }, 'req-failure', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.tools[0]).toEqual(expect.objectContaining({
      errorCode: 'web_search_forbidden', failureCategory: 'http_forbidden', retryable: false,
      argumentChangeRequired: true, strategyChangeRequired: true,
    }));
  });

  it('把命令输出追加到对应 Tool 且保持通道标记', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-command',
      prompt: '运行测试',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-command', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.started', 3, {
        ordinal: 1,
        toolName: 'run_command',
        status: 'started',
      }, 'req-command', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.output', 4, {
        ordinal: 1,
        toolName: 'run_command',
        stream: 'stderr',
        text: 'test failed\n',
      }, 'req-command', 'session-1', 'run-1'),
    });

    expect(state.runs[0]?.tools[0]?.output.lines).toEqual([
      {stream: 'stderr', text: 'test failed', complete: true, repetitions: 1},
    ]);
    expect(state.runs[0]).toEqual(expect.objectContaining({
      toolDetailOrdinal: 1,
      toolDetailExpanded: false,
    }));

    state = reduceTuiState(state, {type: 'tool.detail.toggle'});
    expect(state.runs[0]?.toolDetailExpanded).toBe(true);
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.failed', 5, {
        ordinal: 1,
        toolName: 'run_command',
        status: 'failed',
        failureCategory: 'process_exit',
        errorCode: 'process_exit',
        exitCode: 9,
      }, 'req-command', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.tools[0]).toEqual(expect.objectContaining({exitCode: 9}));
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.failed', 6, {
        stopReason: 'tool_failure', modelTurns: 1, toolCalls: 1,
      }, 'req-command', 'session-1', 'run-1'),
    });
    const historical = reduceTuiState(state, {type: 'tool.detail.toggle'});
    expect(historical.runs[0]?.toolDetailExpanded).toBe(true);
    expect(historical).toEqual(expect.objectContaining({
      historicalToolDetailRunId: 'run-1',
      historicalToolDetailOrdinal: 1,
      historicalToolDetailOpen: true,
    }));
    const closed = reduceTuiState(historical, {type: 'tool.detail.toggle'});
    expect(closed.historicalToolDetailOpen).toBe(false);
  });
  it('ignores unknown and late Run events without claiming the transport closed', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'current', prompt: 'current'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'unknown', 'session-1', 'run-unknown'),
    });
    expect(state.phase).toBe('submitting');
    expect(state.activeRunId).toBeUndefined();
    expect(state.notice).toContain('已忽略无法关联');

    state = reduceTuiState(state, {
      type: 'event.received', event: event('tool.started', 3, {
        ordinal: 1, toolName: 'before_start',
      }, 'current', 'session-1', 'run-current'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.completed', 4, {
        stopReason: 'completed', modelTurns: 1, toolCalls: 1,
      }, 'current', 'session-1', 'run-current'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 5, {}, 'current', 'session-stale', 'run-current'),
    });
    expect(state.phase).toBe('submitting');
    expect(state.runs[0]).toEqual(expect.objectContaining({runId: undefined, status: 'submitting', tools: []}));

    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 6, {}, 'current', 'session-1', 'run-current'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.completed', 7, {
        stopReason: 'completed', modelTurns: 1, toolCalls: 0,
      }, 'current', 'session-1', 'run-current'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('tool.started', 8, {
        ordinal: 1, toolName: 'late_tool',
      }, 'current', 'session-1', 'run-current'),
    });
    expect(state.phase).toBe('ready');
    expect(state.runs[0]?.tools).toEqual([]);
    expect(state.notice).toContain('已忽略无法关联');
  });

  it('rolls back an unstarted submission and marks a started Run interrupted only on real transport failure', () => {
    let state = reduceTuiState({
      ...initialTuiState, phase: 'ready', sessionId: 'session-1',
    }, {type: 'run.submitted', requestId: 'optimistic', prompt: 'execution'});
    state = reduceTuiState(state, {
      type: 'run.submission.rejected', requestId: 'optimistic', message: 'rejected',
    });
    expect(state.phase).toBe('ready');
    expect(state.runs).toEqual([]);

    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'started', prompt: 'execution'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 1, {}, 'started', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {type: 'transport.failed', message: 'transport closed'});
    expect(state.phase).toBe('failed');
    expect(state.runs[0]).toEqual(expect.objectContaining({status: 'failed', stopReason: 'transport_closed'}));
  });
});

function event(
  type: ProtocolEvent['type'],
  sequence: number,
  payload: Record<string, unknown>,
  requestId: string,
  sessionId?: string,
  runId?: string,
): ProtocolEvent {
  return {
    version: 0,
    type,
    requestId,
    ...(sessionId === undefined ? {} : {sessionId}),
    ...(runId === undefined ? {} : {runId}),
    sequence,
    payload,
  };
}

describe('continuous plan projections', () => {
  it('keeps durable Markdown review and correlated question in the active run', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'plan', prompt: '/plan task'});
    state = reduceTuiState(state, {
      type: 'event.received', event: event('run.started', 2, {}, 'plan', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received', event: event('question.requested', 3, {
        callId: 'ask-1', question: 'Choose', options: [
          {optionId: 'a', label: 'A', description: 'first'},
          {optionId: 'b', label: 'B', description: 'second'},
        ],
      }, 'plan', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.pendingQuestion?.callId).toBe('ask-1');
    state = reduceTuiState(state, {
      type: 'event.received', event: event('tool.completed', 4, {
        ordinal: 1, toolName: 'ask_plan_question', status: 'success', returnedCharacters: 1,
        returnedItems: 1, filteredItems: 0, truncated: false, truncationReason: 'none',
      }, 'plan', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.pendingQuestion).toBeUndefined();
    state = reduceTuiState(state, {
      type: 'event.received', event: event('plan.review.requested', 5, {
        planId: 'plan-a', status: 'awaiting_approval', revision: 3,
        contentDigest: 'a'.repeat(64), markdown: '# Plan\n\nSafe.',
      }, 'plan', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.planReview).toEqual(expect.objectContaining({revision: 3, markdown: '# Plan\n\nSafe.'}));
  });
});

describe('session task board projections', () => {
  it('按恢复、进行中、可执行、阻塞、完成排序，并响应选择与详情动作', () => {
    const tasks = [
      task('task-5', 'COMPLETED'),
      task('task-4', 'PENDING', true),
      task('task-3', 'PENDING'),
      task('task-2', 'IN_PROGRESS'),
      task('task-1', 'IN_PROGRESS', false, true),
    ] as const;
    expect(orderedSessionTasks(tasks).map(item => item.taskId))
      .toEqual(['task-1', 'task-2', 'task-3', 'task-4', 'task-5']);

    let state = reduceProductionState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceProductionState(state, {
      type: 'event.received', event: event('session.command.result', 2, {
        commandId: 'tasks-1', intent: 'tasks', status: 'succeeded', code: 'ok',
        result: {boardRevision: 9, totalTasks: 5, truncated: false, tasks},
      }, 'tasks-1', 'session-1'),
    });
    expect(state.taskPanelOpen).toBe(true);
    expect(state.taskPanelFocused).toBe(true);
    expect(state.selectedTaskId).toBe('task-1');
    state = reduceProductionState(state, {type: 'task.panel.move', delta: 1});
    expect(state.selectedTaskId).toBe('task-2');
    state = reduceProductionState(state, {type: 'task.panel.toggle-detail'});
    expect(state.taskDetailOpen).toBe(true);
    state = reduceProductionState(state, {type: 'task.panel.close'});
    expect(state.taskPanelOpen).toBe(false);
  });

  it('mutation snapshot 自动显示但不抢焦点，并按 revision 即时推进到完成态', () => {
    let state = reduceProductionState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceProductionState(state, {type: 'run.submitted', requestId: 'run', prompt: 'work'});
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.command.result', 2, {
        commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED',
      }, 'run', 'session-1'),
    });
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.started', 3, {}, 'run', 'session-1', 'run-1'),
    });
    const snapshot = (boardRevision: number, status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED',
      taskRevision: number, subject = '执行任务') => ({
      boardRevision, totalTasks: 1, truncated: false,
      tasks: [{...task('task-1', status), revision: taskRevision, subject}],
    });

    state = reduceProductionState(state, {
      type: 'event.received', event: event('task.board.snapshot', 3,
        snapshot(1, 'PENDING', 1), 'run', 'session-1', 'run-1'),
    });
    expect(state.taskPanelOpen).toBe(true);
    expect(state.taskPanelFocused).toBe(false);
    expect(state.taskBoard?.tasks[0]).toEqual(expect.objectContaining({status: 'PENDING', revision: 1}));

    state = reduceProductionState(state, {
      type: 'event.received', event: event('task.board.snapshot', 4,
        snapshot(2, 'IN_PROGRESS', 2), 'run', 'session-1', 'run-1'),
    });
    expect(state.taskBoard?.tasks[0]).toEqual(expect.objectContaining({status: 'IN_PROGRESS', revision: 2}));
    const current = state;
    state = reduceProductionState(state, {
      type: 'event.received', event: event('task.board.snapshot', 5,
        snapshot(2, 'COMPLETED', 3, '迟到重复'), 'run', 'session-1', 'run-1'),
    });
    expect(state).toBe(current);
    state = reduceProductionState(state, {
      type: 'event.received', event: event('task.board.snapshot', 6,
        snapshot(3, 'COMPLETED', 3), 'run', 'session-1', 'run-1'),
    });
    expect(state.taskBoard?.tasks[0]).toEqual(expect.objectContaining({status: 'COMPLETED', revision: 3}));
    expect(state.taskPanelFocused).toBe(false);

    const completed = state;
    state = reduceProductionState(state, {
      type: 'event.received', event: event('task.board.snapshot', 7,
        snapshot(4, 'PENDING', 4, 'wrong run'), 'run', 'session-1', 'run-other'),
    });
    expect(state).toBe(completed);
    state = reduceProductionState(state, {
      type: 'event.received', event: event('task.board.snapshot', 8,
        snapshot(4, 'PENDING', 4, 'wrong session'), 'run', 'session-other', 'run-1'),
    });
    expect(state).toBe(completed);
  });

  it('canonical final 仅投影未完成任务提示，不覆盖模型最终文本', () => {
    let state = reduceProductionState(initialTuiState, {
      type: 'event.received', event: event('initialized', 1, {}, 'init', 'session-1'),
    });
    state = reduceProductionState(state, {type: 'run.submitted', requestId: 'run', prompt: 'work'});
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.command.result', 2, {
        commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED',
      }, 'run', 'session-1'),
    });
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.started', 3, {}, 'run', 'session-1', 'run-1'),
    });
    state = reduceProductionState(state, {
      type: 'event.received', event: event('run.completed', 4, {
        stopReason: 'completed', modelTurns: 1, toolCalls: 2, finalText: 'canonical answer',
        pendingTaskCount: 3, recoveryTaskCount: 1,
      }, 'run', 'session-1', 'run-1'),
    });
    expect(state.runs[0]?.text).toBe('canonical answer');
    expect(state.notice).toBe('Task List 仍有 3 项未完成，其中 1 项需要显式恢复；输入 /tasks 查看');
  });
});

function task(
  taskId: string,
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED',
  blocked = false,
  recoveryRequired = false,
) {
  return {
    taskId, revision: 1, status, subject: taskId, blocked,
    blockerIds: blocked ? ['task-99'] : [], owner: undefined,
    activeForm: undefined, recoveryRequired,
  } as const;
}
