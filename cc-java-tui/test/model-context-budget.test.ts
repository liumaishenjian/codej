import {describe, expect, it} from 'vitest';
import {decodeEvent} from '../src/protocol.js';
import {initialTuiState, reduceTuiState} from '../src/state.js';

const frame = {
  version: 0, type: 'run.launch.failed', requestId: 'launch', sessionId: 'session', sequence: 3,
  payload: {code: 'MODEL_CONTEXT_BUDGET_INCOMPATIBLE', stopReason: 'internal_error'},
};
const decode = (value: unknown, sequence = 3) => decodeEvent(JSON.stringify(value), sequence);

describe('model context budget launch recovery', () => {
  it.each(['MODEL_CONTEXT_BUDGET_INCOMPATIBLE', 'RUNTIME_LAUNCH_FAILED'])('accepts only safe %s projection and restores input', code => {
    let state = reduceTuiState(initialTuiState, {type: 'event.received', event: decode({
      ...frame, type: 'initialized', requestId: 'init', sequence: 1, payload: {},
    }, 1)});
    state = reduceTuiState(state, {type: 'run.submitted', requestId: 'launch', prompt: 'task'});
    state = reduceTuiState(state, {type: 'event.received', event: decode({
      ...frame, type: 'run.command.result', sequence: 2,
      payload: {commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED'},
    }, 2)});
    state = reduceTuiState(state, {type: 'event.received', event: decode({...frame, payload: {...frame.payload, code}})});
    expect(state.phase).toBe('ready');
    expect(state.runs).toEqual([]);
    expect(state.notice).toBe(code === 'MODEL_CONTEXT_BUDGET_INCOMPATIBLE'
      ? '模型窗口不足以容纳当前保留预算；请选择更大窗口模型，或显式调整 Context 参数。'
      : 'Java 已接受请求，但 Runtime 启动失败；不会自动重放');
  });

  it.each(['UNKNOWN', 'model_context_budget_incompatible', '', 123, null])('rejects malformed code %s', code => {
    expect(() => decode({...frame, payload: {...frame.payload, code}})).toThrow();
  });

  it('keeps exact fields, sequence and identity validation', () => {
    for (const value of [
      {...frame, payload: {...frame.payload, message: 'unsafe'}},
      {...frame, payload: {...frame.payload, stopReason: 'completed'}},
      {...frame, sessionId: undefined}, {...frame, runId: 'unexpected'},
      {...frame, sequence: 4}, {...frame, requestId: ''},
    ]) expect(() => decode(value)).toThrow();
  });
});
