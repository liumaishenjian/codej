import {describe, expect, it} from 'vitest';
import {decodeEvent, ProtocolViolation, type ResourceCleanupStatus} from '../src/protocol.js';
import {initialTuiState, reduceTuiState, type TuiState} from '../src/state.js';

// ADR-100 独立清理诊断：只运行 JSON/Reducer，不启动 Java、Worker 或物理终端。
const cleanupStatuses: readonly ResourceCleanupStatus[] = [
  'unknown', 'not_started', 'cleaning', 'released', 'unconfirmed',
];
const legacyPayload = {
  taskId: 'task-cleanup', definitionId: 'reader', status: 'succeeded', failure: 'none',
  modelTurns: 2, toolCalls: 3, estimatedTokens: 40, elapsedMillis: 50,
  summary: 'bounded result', verified: true, worktreeDisposition: null,
};
function frame(payload: Record<string, unknown>, type = 'task.status', sequence = 1) {
  return JSON.stringify({version: 0, type, requestId: 'inspect-1', sessionId: 'session-1', sequence, payload});
}
function receive(state: TuiState, payload: Record<string, unknown>, type = 'task.status', sequence = 1) {
  return reduceTuiState(state, {
    type: 'event.received', event: decodeEvent(frame(payload, type, sequence), sequence),
  });
}
const readyState: TuiState = {...initialTuiState, sessionId: 'session-1', phase: 'ready'};

describe('task cleanup snapshot protocol and projection', () => {
  it.each(['task.status', 'task.terminal'])('旧 %s payload 缺字段默认 unknown', type => {
    const decoded = decodeEvent(frame(legacyPayload, type), 1);
    expect(decoded.payload).toEqual({...legacyPayload, cleanupStatus: 'unknown'});
    expect(receive(readyState, legacyPayload, type).childTasks?.[0]).toMatchObject({
      status: 'succeeded', cleanupStatus: 'unknown', verified: true,
    });
  });

  it.each(cleanupStatuses)('严格接受并保留 %s，不根据成功终态推断释放', cleanupStatus => {
    for (const type of ['task.status', 'task.terminal']) {
      const payload = {...legacyPayload, cleanupStatus};
      expect(decodeEvent(frame(payload, type), 1).payload).toEqual(payload);
      expect(receive(readyState, payload, type).childTasks?.[0]).toMatchObject({
        ...payload, worktreeDisposition: undefined,
      });
    }
  });

  it.each([null, 'finished', 'RELEASED', '', 0, true, [], {}])('拒绝非法 cleanupStatus %j', cleanupStatus => {
    for (const type of ['task.status', 'task.terminal']) {
      expect(() => decodeEvent(frame({...legacyPayload, cleanupStatus}, type), 1))
        .toThrow(ProtocolViolation);
    }
  });

  it('兼容字段仍使用闭合 schema，拒绝额外键与缺少既有必需键', () => {
    for (const extra of [{}, {cleanupStatus: 'cleaning'}]) {
      expect(() => decodeEvent(frame({...legacyPayload, ...extra, resourceReleased: true}), 1))
        .toThrow(ProtocolViolation);
      const {verified: _verified, ...missingRequired} = legacyPayload;
      expect(() => decodeEvent(frame({...missingRequired, ...extra}), 1)).toThrow(ProtocolViolation);
    }
  });

  it.each(['queued', 'starting', 'running', 'succeeded', 'failed', 'cancelled', 'interrupted_unknown'])(
    '保持既有运行状态 %s，终态信封限制不变', status => {
      expect(receive(readyState, {...legacyPayload, status}).childTasks?.[0]).toMatchObject({
        status, cleanupStatus: 'unknown',
      });
      if (['queued', 'starting', 'running'].includes(status)) {
        expect(() => decodeEvent(frame({...legacyPayload, status, cleanupStatus: 'released'}, 'task.terminal'), 1))
          .toThrow(ProtocolViolation);
      }
    },
  );

  it.each(['cleaning', 'unconfirmed'])('terminal %s 后 Worktree 更新也不推断释放', cleanupStatus => {
    let state = receive(readyState, {...legacyPayload, cleanupStatus}, 'task.terminal');
    state = receive(state, {taskId: legacyPayload.taskId, disposition: 'removed'}, 'task.worktree', 2);
    expect(state.childTasks?.[0]).toMatchObject({status: 'succeeded', cleanupStatus, worktreeDisposition: 'removed'});
    expect(state.notice).not.toContain('released');
    expect(state.taskPanelOpen).toBe(readyState.taskPanelOpen);
    expect(state.taskDetailOpen).toBe(readyState.taskDetailOpen);
  });

  it('后续 inspect 覆盖当时快照，既不重写运行终态也不改写旧对象', () => {
    const terminal = receive(readyState, {...legacyPayload, cleanupStatus: 'cleaning'}, 'task.terminal');
    const inspected = receive(terminal, {...legacyPayload, cleanupStatus: 'unconfirmed'}, 'task.status', 2);
    expect(terminal.childTasks?.[0]).toMatchObject({status: 'succeeded', cleanupStatus: 'cleaning'});
    expect(inspected.childTasks).toHaveLength(1);
    expect(inspected.childTasks?.[0]).toMatchObject({status: 'succeeded', cleanupStatus: 'unconfirmed'});
    const released = receive(inspected, {...legacyPayload, cleanupStatus: 'released'}, 'task.status', 3);
    expect(released.childTasks?.[0]).toMatchObject({status: 'succeeded', cleanupStatus: 'released'});
    const legacy = receive(released, legacyPayload, 'task.status', 4);
    expect(legacy.childTasks?.[0]?.cleanupStatus).toBe('unknown');
  });
});
