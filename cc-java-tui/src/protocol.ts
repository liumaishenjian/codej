export const PROTOCOL_VERSION = 0;
export const MAX_LINE_BYTES = 64 * 1024;
export const MAX_IDENTIFIER_CHARS = 128;
export const MAX_CHECKPOINTS = 1_000;
export const MAX_CHECKPOINT_TARGET_CHARS = 1_024;
export const MAX_CHECKPOINT_DIFF_CHARS = 16 * 1_024;
export const MAX_CHECKPOINT_MESSAGE_CHARS = 1_024;
export const MAX_FILE_SUGGESTION_QUERY_CHARS = 256;
export const MAX_FILE_SUGGESTION_CANDIDATES = 32;
export const MAX_FILE_SUGGESTION_CANDIDATE_CHARS = 1_024;

const CHECKPOINT_ID = /^checkpoint-[A-Za-z0-9-]+$/;
const CHECKPOINT_PHASES = new Set([
  'create_prepared',
  'create_journal_uncertain',
  'created',
  'post_prepared',
  'post_journal_uncertain',
  'completed_present',
  'completed_absent',
  'undo_prepared',
  'undo_applied',
  'undo_journal_uncertain',
  'undone',
]);
const CHECKPOINT_DIFF_STATUSES = new Set([
  'unchanged', 'changed', 'absent', 'conflict',
]);
const CHECKPOINT_UNDO_STATUSES = new Set([
  'restored', 'already_restored', 'conflict',
]);

const EVENT_TYPES = new Set([
  'initialized',
  'run.command.result',
  'run.launch.failed',
  'run.started',
  'run.budget.governed',
  'skill.invoked',
  'skill.completed',
  'task.status',
  'task.terminal',
  'task.worktree',
  'task.board.snapshot',
  'model.turn.started',
  'model.retry.attempt.started',
  'model.retry.scheduled',
  'model.turn.completed',
  'model.text.delta',
  'plan.proposed',
  'plan.review.requested',
  'plan.execution.accepted',
  'plan.execution.failed',
  'plan.review.rejected',
  'plan.verification.required',
  'plan.verification.correction',
  'plan.verification.completed',
  'plan.feedback.accepted',
  'question.requested',
  'approval.requested',
  'tool.started',
  'tool.output',
  'tool.completed',
  'tool.failed',
  'run.completed',
  'run.failed',
  'run.cancelled',
  'checkpoint.listed',
  'checkpoint.diffed',
  'checkpoint.undone',
  'session.command.result',
  'provider.control.result',
  'steering.queued',
  'steering.discarded',
  'file.suggestions',
  'protocol.error',
]);

export type EventType =
  | 'initialized'
  | 'run.command.result'
  | 'run.launch.failed'
  | 'run.started'
  | 'run.budget.governed'
  | 'skill.invoked'
  | 'skill.completed'
  | 'task.status'
  | 'task.terminal'
  | 'task.worktree'
  | 'task.board.snapshot'
  | 'model.turn.started'
  | 'model.retry.attempt.started'
  | 'model.retry.scheduled'
  | 'model.turn.completed'
  | 'model.text.delta'
  | 'plan.proposed'
  | 'plan.review.requested'
  | 'plan.execution.accepted'
  | 'plan.execution.failed'
  | 'plan.review.rejected'
  | 'plan.verification.required'
  | 'plan.verification.correction'
  | 'plan.verification.completed'
  | 'plan.feedback.accepted'
  | 'question.requested'
  | 'approval.requested'
  | 'tool.started'
  | 'tool.output'
  | 'tool.completed'
  | 'tool.failed'
  | 'run.completed'
  | 'run.failed'
  | 'run.cancelled'
  | 'checkpoint.listed'
  | 'checkpoint.diffed'
  | 'checkpoint.undone'
  | 'session.command.result'
  | 'provider.control.result'
  | 'steering.queued'
  | 'steering.discarded'
  | 'file.suggestions'
  | 'protocol.error';

export interface ProtocolEvent {
  readonly version: number;
  readonly type: EventType;
  readonly requestId: string;
  readonly sessionId?: string;
  readonly runId?: string;
  readonly sequence: number;
  readonly payload: Readonly<Record<string, unknown>>;
}

export interface ProtocolCommand {
  readonly version: number;
  readonly type:
    | 'initialize'
    | 'run.start'
    | 'plan.start'
    | 'plan.review.resolve'
    | 'plan.execute'
    | 'plan.feedback'
    | 'input.begin'
    | 'input.chunk'
    | 'input.commit'
    | 'run.cancel'
    | 'approval.resolve'
    | 'question.resolve'
    | 'checkpoint.list'
    | 'checkpoint.diff'
    | 'checkpoint.undo'
    | 'session.command'
    | 'provider.control'
    | 'skill.invoke'
    | 'task.inspect'
    | 'task.wait'
    | 'task.cancel'
    | 'task.keep'
    | 'task.remove'
    | 'file.suggest'
    | 'shutdown';
  readonly requestId: string;
  readonly sessionId?: string;
  readonly runId?: string;
  readonly sequence: number;
  readonly payload: Readonly<Record<string, unknown>>;
}

/**
 * 表示 TUI 在信任事件前发现的协议错误。
 *
 * Java 仍是 Agent 状态权威；该错误只保护终端 Client 不消费畸形、乱序或未知事件。
 */
export class ProtocolViolation extends Error {
  public constructor(message: string) {
    super(message);
    this.name = 'ProtocolViolation';
  }
}

export function decodeEvent(line: string, expectedSequence: number): ProtocolEvent {
  let value: unknown;
  try {
    value = JSON.parse(line);
  } catch {
    throw new ProtocolViolation('Java stdout 包含无效 JSON');
  }
  if (!isRecord(value)) {
    throw new ProtocolViolation('协议事件必须是 JSON Object');
  }

  const version = requireInteger(value, 'version');
  if (version !== PROTOCOL_VERSION) {
    throw new ProtocolViolation(`不支持的协议版本：${version}`);
  }
  const type = requireText(value, 'type');
  if (!EVENT_TYPES.has(type)) {
    throw new ProtocolViolation(`未知协议事件：${type}`);
  }
  const requestId = requireText(value, 'requestId');
  const sequence = requireInteger(value, 'sequence');
  if (sequence !== expectedSequence) {
    throw new ProtocolViolation(
      `事件 sequence 不连续：期望 ${expectedSequence}，实际 ${sequence}`,
    );
  }
  const payload = value.payload;
  if (!isRecord(payload)) {
    throw new ProtocolViolation('payload 必须是 JSON Object');
  }

  const sessionId = optionalText(value, 'sessionId');
  const runId = optionalText(value, 'runId');
  validateEventShape(type as EventType, sessionId, runId, payload);

  return {
    version,
    type: type as EventType,
    requestId,
    ...(sessionId === undefined ? {} : {sessionId}),
    ...(runId === undefined ? {} : {runId}),
    sequence,
    payload,
  };
}

export function encodeCommand(command: ProtocolCommand): string {
  return `${JSON.stringify(command)}\n`;
}

function validateEventShape(
  type: EventType,
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (type === 'initialized' && sessionId === undefined) {
    throw new ProtocolViolation('initialized 缺少 sessionId');
  }
  if (type === 'run.command.result') {
    validateRunCommandResult(sessionId, runId, payload);
  }
  if (type === 'run.launch.failed') {
    if (sessionId === undefined || runId !== undefined
      || !hasExactFields(payload, new Set(['code', 'stopReason']))
      || payload.code !== 'RUNTIME_LAUNCH_FAILED' || payload.stopReason !== 'internal_error') {
      throw new ProtocolViolation('run.launch.failed 包含无效启动失败投影');
    }
  }
  if (
    (type === 'checkpoint.listed'
      || type === 'checkpoint.diffed'
      || type === 'checkpoint.undone')
    && (sessionId === undefined || runId !== undefined)
  ) {
    throw new ProtocolViolation(`${type} 必须携带 sessionId 且不能携带 runId`);
  }
  if (type === 'session.command.result') {
    validateSessionCommandResult(sessionId, runId, payload);
  } else if (type === 'provider.control.result') {
    validateProviderControlResult(sessionId, runId, payload);
  } else if (type === 'file.suggestions') {
    validateFileSuggestions(sessionId, runId, payload);
  } else if (type === 'steering.queued') {
    validateSteeringQueued(sessionId, runId, payload);
  } else if (type === 'steering.discarded') {
    validateSteeringDiscarded(sessionId, runId, payload);
  } else if (type === 'checkpoint.listed') {
    validateCheckpointList(payload);
  } else if (type === 'checkpoint.diffed') {
    validateCheckpointDiff(payload);
  } else if (type === 'checkpoint.undone') {
    validateCheckpointUndo(payload);
  }
  if (
    (type === 'run.started'
      || type === 'run.budget.governed'
      || type === 'skill.completed'
      || type === 'model.text.delta'
      || type === 'plan.proposed'
      || type === 'plan.review.requested'
      || type === 'question.requested'
      || type === 'approval.requested'
      || type === 'tool.started'
      || type === 'tool.output'
      || type === 'tool.completed'
      || type === 'tool.failed'
      || type === 'run.completed'
      || type === 'run.failed'
      || type === 'run.cancelled')
    && (sessionId === undefined || runId === undefined)
  ) {
    throw new ProtocolViolation(`${type} 缺少 sessionId 或 runId`);
  }
  if (type === 'task.status' || type === 'task.terminal') {
    validateTaskEvent(sessionId, runId, payload, type === 'task.terminal');
  } else if (type === 'task.board.snapshot') {
    if (sessionId === undefined || runId === undefined) {
      throw new ProtocolViolation('task.board.snapshot 缺少 Session 或 Run 归属');
    }
    validateTaskBoardSnapshot(payload);
  } else if (type === 'task.worktree') {
    if (!hasExactFields(payload, new Set(['taskId', 'disposition']))
      || sessionId === undefined || runId !== undefined
      || typeof payload.taskId !== 'string'
      || !/^task-[A-Za-z0-9_-]{1,96}$/u.test(payload.taskId)
      || typeof payload.disposition !== 'string'
      || payload.disposition.length > 64) {
      throw new ProtocolViolation('task.worktree 投影无效');
    }
  }
  if (type === 'skill.invoked') {
    if (sessionId === undefined || runId !== undefined) {
      throw new ProtocolViolation('skill.invoked 必须携带 sessionId 且不能携带 runId');
    }
    validateSkillEvent(payload, false);
  } else if (type === 'skill.completed') {
    validateSkillEvent(payload, true);
  }
  if (type === 'model.text.delta' && typeof payload.text !== 'string') {
    throw new ProtocolViolation('model.text.delta 缺少文本');
  }
  if (type === 'plan.proposed') {
    validatePlanProposal(payload);
  } else if (type === 'plan.review.requested') {
    validatePlanReview(payload);
  } else if (type === 'question.requested') {
    validateUserQuestion(payload);
  }
  if (type === 'plan.execution.accepted') {
    if (sessionId === undefined || runId !== undefined
      || !hasExactFields(payload, new Set(['planId', 'status', 'revision', 'contentDigest', 'contextPolicy', 'approvalReviewer']))
      || payload.status !== 'approved' || typeof payload.planId !== 'string'
      || !Number.isSafeInteger(payload.revision) || (payload.revision as number) < 1
      || typeof payload.contentDigest !== 'string' || !/^[0-9a-f]{64}$/u.test(payload.contentDigest)
      || !['keep', 'clear'].includes(String(payload.contextPolicy))
      || !['user', 'auto_review'].includes(String(payload.approvalReviewer))) {
      throw new ProtocolViolation('plan.execution.accepted 投影无效');
    }
  }
  if (type === 'plan.execution.failed') {
    const allowedFields = new Set(['planId', 'status', 'stopReason', 'modelFailure']);
    if (sessionId === undefined || runId !== undefined
      || Object.keys(payload).some(key => !allowedFields.has(key))
      || !['failed', 'cancelled', 'timed_out', 'limit_exceeded'].includes(String(payload.status))
      || typeof payload.planId !== 'string' || payload.planId.trim().length === 0
      || typeof payload.stopReason !== 'string'
      || payload.stopReason === 'completed') {
      throw new ProtocolViolation('plan.execution.failed 投影无效');
    }
    validateOptionalModelFailure(type, payload);
  }
  if (type === 'plan.verification.required' || type === 'plan.verification.completed') {
    if (typeof payload.planId !== 'string' || typeof payload.status !== 'string'
      || !Number.isSafeInteger(payload.requiredEvidence) || !Number.isSafeInteger(payload.satisfiedEvidence)
      || (payload.blockingRequirementId !== undefined && typeof payload.blockingRequirementId !== 'string')) {
      throw new ProtocolViolation('plan verification 投影无效');
    }
  }
  if (type === 'plan.verification.correction') {
    if (!hasExactFields(payload, new Set([
      'attempt', 'maxAttempts', 'incompleteTaskCount', 'incompleteTaskIds', 'failures',
    ]))
      || !Number.isSafeInteger(payload.attempt) || (payload.attempt as number) < 1
      || !Number.isSafeInteger(payload.maxAttempts)
      || (payload.maxAttempts as number) < (payload.attempt as number)
      || !Number.isSafeInteger(payload.incompleteTaskCount)
      || (payload.incompleteTaskCount as number) < 0 || (payload.incompleteTaskCount as number) > 256
      || !Array.isArray(payload.incompleteTaskIds)
      || payload.incompleteTaskIds.length !== payload.incompleteTaskCount
      || payload.incompleteTaskIds.some(taskId => typeof taskId !== 'string'
        || !/^task-[1-9][0-9]*$/u.test(taskId))
      || !Array.isArray(payload.failures) || payload.failures.length > 64
      || (payload.failures.length === 0 && payload.incompleteTaskIds.length === 0)
      || payload.failures.some(failure => !isRecord(failure)
        || !hasExactFields(failure, new Set(['requirementId', 'kind', 'locator', 'reason']))
        || typeof failure.requirementId !== 'string'
        || (failure.kind !== 'deliverable' && failure.kind !== 'verification')
        || typeof failure.locator !== 'string' || typeof failure.reason !== 'string')) {
      throw new ProtocolViolation('plan lifecycle correction 投影无效');
    }
  }
  if (type === 'plan.review.rejected') {
    if (sessionId === undefined || runId !== undefined
      || !hasExactFields(payload, new Set(['planId', 'status']))
      || typeof payload.planId !== 'string' || payload.status !== 'rejected') {
      throw new ProtocolViolation('plan.review.rejected 投影无效');
    }
  }
  if (type === 'plan.feedback.accepted') {
    if (sessionId === undefined || runId !== undefined
      || !hasExactFields(payload, new Set(['planId', 'status', 'revision', 'contentDigest']))
      || typeof payload.planId !== 'string' || payload.status !== 'draft'
      || !Number.isSafeInteger(payload.revision) || (payload.revision as number) < 1
      || typeof payload.contentDigest !== 'string' || !/^[0-9a-f]{64}$/u.test(payload.contentDigest)) {
      throw new ProtocolViolation('plan.feedback.accepted 投影无效');
    }
  }
  if (
    type === 'approval.requested'
    && (typeof payload.approvalId !== 'string'
      || payload.approvalId.trim().length === 0
      || payload.approvalId.length > MAX_IDENTIFIER_CHARS
      || !Number.isSafeInteger(payload.ordinal)
      || (payload.ordinal as number) < 1
      || typeof payload.toolName !== 'string'
      || payload.toolName.trim().length === 0
      || (payload.effect !== 'write_workspace'
        && payload.effect !== 'execute_process'
        && payload.effect !== 'network_or_remote'))
  ) {
    throw new ProtocolViolation('approval.requested 缺少安全审批摘要');
  }
  if (
    type === 'tool.output'
    && (!Number.isSafeInteger(payload.ordinal)
      || (payload.ordinal as number) < 1
      || typeof payload.toolName !== 'string'
      || payload.toolName.trim().length === 0
      || (payload.stream !== 'stdout' && payload.stream !== 'stderr')
      || typeof payload.text !== 'string'
      || payload.text.length === 0
      || Array.from(payload.text).length > 4_096)
  ) {
    throw new ProtocolViolation('tool.output 缺少有界输出摘要');
  }
  if (type === 'approval.requested') {
    validateApprovalPreview(payload);
  }
  if (type === 'model.turn.started') {
    if (!hasExactFields(payload, new Set(['turn']))
      || !Number.isSafeInteger(payload.turn) || (payload.turn as number) < 1) {
      throw new ProtocolViolation('model.turn.started 投影无效');
    }
  }
  if (type === 'model.retry.attempt.started') {
    if (!hasExactFields(payload, new Set(['turn', 'attempt', 'maxAttempts']))
      || !positiveSafeInteger(payload.turn)
      || !positiveSafeInteger(payload.attempt)
      || !positiveSafeInteger(payload.maxAttempts)
      || (payload.attempt as number) > (payload.maxAttempts as number)
      || (payload.maxAttempts as number) > 100) {
      throw new ProtocolViolation('model.retry.attempt.started 投影无效');
    }
  }
  if (type === 'model.retry.scheduled') {
    if (!hasExactFields(payload, new Set([
      'turn', 'failedAttempt', 'nextAttempt', 'maxAttempts', 'waitMillis', 'category',
    ]))
      || !positiveSafeInteger(payload.turn)
      || !positiveSafeInteger(payload.failedAttempt)
      || !positiveSafeInteger(payload.nextAttempt)
      || !positiveSafeInteger(payload.maxAttempts)
      || (payload.nextAttempt as number) !== (payload.failedAttempt as number) + 1
      || (payload.nextAttempt as number) > (payload.maxAttempts as number)
      || (payload.maxAttempts as number) > 100
      || !Number.isSafeInteger(payload.waitMillis) || (payload.waitMillis as number) < 0
      || (payload.waitMillis as number) > 300_000
      || typeof payload.category !== 'string'
      || !MODEL_FAILURE_CATEGORIES.has(payload.category)) {
      throw new ProtocolViolation('model.retry.scheduled 投影无效');
    }
  }
  if (type === 'model.turn.completed') {
    validateModelTurnCompleted(payload);
  }
  if (
    (type === 'tool.started' || type === 'tool.completed' || type === 'tool.failed')
    && (!Number.isSafeInteger(payload.ordinal)
      || (payload.ordinal as number) < 1
      || typeof payload.toolName !== 'string'
      || payload.toolName.trim().length === 0)
  ) {
    throw new ProtocolViolation(`${type} 缺少安全 Tool 摘要`);
  }
  if (type === 'tool.started' || type === 'tool.completed' || type === 'tool.failed') {
    validateOptionalToolPresentation(type, payload);
  }
  if (isTerminalRunEvent(type)) {
    const stopReason = payload.stopReason;
    if (
      typeof stopReason !== 'string'
      || !/^[a-z][a-z0-9_]{0,63}$/.test(stopReason)
    ) {
      throw new ProtocolViolation(`${type} 缺少安全 stopReason`);
    }
    validateOptionalTerminalCount(type, payload, 'modelTurns');
    validateOptionalTerminalCount(type, payload, 'toolCalls');
    validateOptionalModelFailure(type, payload);
  }
}

/** Provider Usage 与本地 Context 估算严格分栏，禁止 Surface 把估算冒充实测值。 */
function validateModelTurnCompleted(payload: Readonly<Record<string, unknown>>): void {
  const allowedFields = new Set(['turn', 'finishReason', 'usage', 'context']);
  if (!Object.keys(payload).every(field => allowedFields.has(field))
    || !Object.hasOwn(payload, 'turn') || !Object.hasOwn(payload, 'finishReason')
    || !Number.isSafeInteger(payload.turn) || (payload.turn as number) < 1
    || typeof payload.finishReason !== 'string'
    || !/^[a-z][a-z0-9_]{0,63}$/u.test(payload.finishReason)) {
    throw new ProtocolViolation('model.turn.completed 投影无效');
  }
  if (payload.usage !== undefined) {
    const usage = payload.usage;
    if (typeof usage !== 'object' || usage === null || Array.isArray(usage)
      || !hasExactFields(usage as Record<string, unknown>, new Set([
        'inputTokens', 'outputTokens', 'totalTokens',
      ]))) {
      throw new ProtocolViolation('model.turn.completed Usage 无效');
    }
    const value = usage as Record<string, unknown>;
    for (const field of ['inputTokens', 'outputTokens', 'totalTokens']) {
      if (!Number.isSafeInteger(value[field]) || (value[field] as number) < 0) {
        throw new ProtocolViolation('model.turn.completed Usage 无效');
      }
    }
    if ((value.totalTokens as number) < (value.inputTokens as number)
      + (value.outputTokens as number)) {
      throw new ProtocolViolation('model.turn.completed Usage 总数无效');
    }
  }
  if (payload.context !== undefined) {
    const context = payload.context;
    if (typeof context !== 'object' || context === null || Array.isArray(context)
      || !hasExactFields(context as Record<string, unknown>, new Set([
        'usedTokens', 'maximumInputTokens', 'estimateKind',
      ]))) {
      throw new ProtocolViolation('model.turn.completed Context 无效');
    }
    const value = context as Record<string, unknown>;
    if (!Number.isSafeInteger(value.usedTokens) || (value.usedTokens as number) < 0
      || !Number.isSafeInteger(value.maximumInputTokens) || (value.maximumInputTokens as number) < 1
      || (value.estimateKind !== 'estimated' && value.estimateKind !== 'exact')) {
      throw new ProtocolViolation('model.turn.completed Context 无效');
    }
  }
}

function validatePlanReview(payload: Readonly<Record<string, unknown>>): void {
  if (!hasExactFields(payload, new Set(['planId', 'status', 'revision', 'contentDigest', 'markdown', 'workspaceDigest', 'originalPermissionMode', 'suggestedContextPolicy']))
    || typeof payload.planId !== 'string'
    || !/^plan-[A-Za-z0-9-]{1,123}$/u.test(payload.planId)
    || payload.status !== 'awaiting_approval'
    || !Number.isSafeInteger(payload.revision) || (payload.revision as number) < 1
    || typeof payload.contentDigest !== 'string' || !/^[0-9a-f]{64}$/u.test(payload.contentDigest)
    || typeof payload.markdown !== 'string' || payload.markdown.trim().length === 0
    || typeof payload.workspaceDigest !== 'string' || !/^[0-9a-f]{64}$/u.test(payload.workspaceDigest)
    || !['default', 'accept_edits'].includes(String(payload.originalPermissionMode))
    || !['keep', 'clear'].includes(String(payload.suggestedContextPolicy))
    || Buffer.byteLength(payload.markdown, 'utf8') > 1_048_576) {
    throw new ProtocolViolation('plan.review.requested durable 工件投影无效');
  }
}

function validateUserQuestion(payload: Readonly<Record<string, unknown>>): void {
  if (!hasExactFields(payload, new Set(['callId', 'question', 'options']))
    || typeof payload.callId !== 'string' || payload.callId.trim().length === 0
    || payload.callId.length > MAX_IDENTIFIER_CHARS
    || typeof payload.question !== 'string' || payload.question.trim().length === 0
    || Array.from(payload.question).length > 1_000
    || !Array.isArray(payload.options) || payload.options.length < 2 || payload.options.length > 4) {
    throw new ProtocolViolation('question.requested 投影无效');
  }
  const ids = new Set<string>();
  payload.options.forEach(option => {
    if (!isRecord(option) || !hasExactFields(option, new Set(['optionId', 'label', 'description']))
      || typeof option.optionId !== 'string' || option.optionId.trim().length === 0
      || option.optionId.length > 64 || ids.has(option.optionId)
      || typeof option.label !== 'string' || option.label.trim().length === 0
      || Array.from(option.label).length > 120
      || typeof option.description !== 'string' || option.description.trim().length === 0
      || Array.from(option.description).length > 500) {
      throw new ProtocolViolation('question.requested option 无效');
    }
    ids.add(option.optionId);
  });
}

function validatePlanProposal(payload: Readonly<Record<string, unknown>>): void {
  if (!hasExactFields(payload, new Set(['planId', 'status', 'objective', 'workspaceDigest', 'steps']))
    || typeof payload.planId !== 'string'
    || !/^plan-[A-Za-z0-9_-]{1,123}$/u.test(payload.planId)
    || payload.status !== 'awaiting_approval'
    || typeof payload.objective !== 'string'
    || payload.objective.trim().length === 0
    || Array.from(payload.objective).length > 8_000
    || typeof payload.workspaceDigest !== 'string'
    || !/^[a-f0-9]{64}$/u.test(payload.workspaceDigest)
    || !Array.isArray(payload.steps)
    || payload.steps.length < 1
    || payload.steps.length > 128) {
    throw new ProtocolViolation('plan.proposed 投影无效');
  }
  payload.steps.forEach((item, index) => {
    if (!isRecord(item)
      || !hasExactFields(item, new Set(['ordinal', 'title', 'detail']))
      || item.ordinal !== index + 1
      || typeof item.title !== 'string'
      || item.title.trim().length === 0
      || Array.from(item.title).length > 200
      || typeof item.detail !== 'string'
      || item.detail.trim().length === 0
      || Array.from(item.detail).length > 8_000) {
      throw new ProtocolViolation('plan.proposed step 无效');
    }
  });
}

function validateTaskBoardSnapshot(payload: Readonly<Record<string, unknown>>): void {
  if (!hasExactFields(payload, new Set(['boardRevision', 'totalTasks', 'truncated', 'tasks']))
    || !Number.isSafeInteger(payload.boardRevision) || (payload.boardRevision as number) < 0
    || !Number.isSafeInteger(payload.totalTasks) || (payload.totalTasks as number) < 0
    || typeof payload.truncated !== 'boolean' || !Array.isArray(payload.tasks)
    || payload.tasks.length > 50 || (payload.totalTasks as number) < payload.tasks.length) {
    throw new ProtocolViolation('Task Board snapshot 投影无效');
  }
  const ids = new Set<string>();
  for (const item of payload.tasks) {
    if (!isRecord(item) || !hasExactFields(item, new Set([
      'taskId', 'revision', 'status', 'subject', 'blocked', 'blockerIds', 'owner',
      'activeForm', 'recoveryRequired',
    ]))
      || typeof item.taskId !== 'string' || !/^task-[1-9][0-9]*$/u.test(item.taskId)
      || ids.has(item.taskId)
      || !Number.isSafeInteger(item.revision) || (item.revision as number) < 1
      || (item.status !== 'PENDING' && item.status !== 'IN_PROGRESS' && item.status !== 'COMPLETED')
      || !isSafeDisplayText(item.subject, 200, false)
      || typeof item.blocked !== 'boolean' || !Array.isArray(item.blockerIds)
      || item.blockerIds.length > 32
      || item.blockerIds.some(id => typeof id !== 'string' || !/^task-[1-9][0-9]*$/u.test(id))
      || (item.owner !== null && !isBoundedIdentifier(item.owner))
      || (item.activeForm !== null && !isSafeDisplayText(item.activeForm, 200, false))
      || typeof item.recoveryRequired !== 'boolean') {
      throw new ProtocolViolation('Task Board snapshot 条目无效');
    }
    ids.add(item.taskId);
  }
}

function validateTaskEvent(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
  terminal: boolean,
): void {
  const statuses = new Set(['queued', 'starting', 'running', 'succeeded', 'failed', 'cancelled', 'interrupted_unknown']);
  const dispositions = new Set([
    'ready', 'in_use', 'kept', 'removed', 'removed_branch_preserved', 'failed_preserved',
  ]);
  const fields = new Set([
    'taskId', 'definitionId', 'status', 'failure', 'modelTurns', 'toolCalls',
    'estimatedTokens', 'elapsedMillis', 'summary', 'verified', 'worktreeDisposition',
  ]);
  if (!hasExactFields(payload, fields)
    || sessionId === undefined || runId !== undefined
    || typeof payload.taskId !== 'string' || !/^task-[A-Za-z0-9_-]{1,96}$/.test(payload.taskId)
    || typeof payload.definitionId !== 'string' || payload.definitionId.length > MAX_IDENTIFIER_CHARS
    || typeof payload.status !== 'string' || !statuses.has(payload.status)
    || typeof payload.failure !== 'string'
    || !Number.isSafeInteger(payload.modelTurns) || Number(payload.modelTurns) < 0
    || !Number.isSafeInteger(payload.toolCalls) || Number(payload.toolCalls) < 0
    || !Number.isSafeInteger(payload.estimatedTokens) || Number(payload.estimatedTokens) < 0
    || !Number.isSafeInteger(payload.elapsedMillis) || Number(payload.elapsedMillis) < 0
    || typeof payload.summary !== 'string' || Array.from(payload.summary).length > 4096
    || /[\u0000-\u001f\u007f]/u.test(payload.summary)
    || typeof payload.verified !== 'boolean'
    || (payload.worktreeDisposition !== null
      && (typeof payload.worktreeDisposition !== 'string'
        || !dispositions.has(payload.worktreeDisposition)))
    || (terminal && !['succeeded', 'failed', 'cancelled', 'interrupted_unknown'].includes(payload.status))) {
    throw new ProtocolViolation('task event 字段无效');
  }
}

function validateSkillEvent(payload: Readonly<Record<string, unknown>>, completed: boolean): void {
  const fields = completed
    ? new Set(['skillId', 'invocationKind', 'status', 'stopReason'])
    : new Set(['skillId', 'invocationKind']);
  if (!hasExactFields(payload, fields)
    || typeof payload.skillId !== 'string'
    || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(payload.skillId)
    || Array.from(payload.skillId).length > 64
    || payload.invocationKind !== 'explicit') {
    throw new ProtocolViolation('Skill 事件缺少安全身份摘要');
  }
  if (completed && ((payload.status !== 'succeeded' && payload.status !== 'failed')
    || typeof payload.stopReason !== 'string'
    || !/^[a-z][a-z0-9_]{0,63}$/u.test(payload.stopReason))) {
    throw new ProtocolViolation('skill.completed 缺少安全终态');
  }
}

function validateFileSuggestions(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (
    sessionId === undefined
    || runId !== undefined
    || !hasExactFields(payload, new Set(['query', 'candidates']))
    || typeof payload.query !== 'string'
    || Array.from(payload.query).length > MAX_FILE_SUGGESTION_QUERY_CHARS
    || /[\u0000-\u001f\u007f]/u.test(payload.query)
    || !Array.isArray(payload.candidates)
    || payload.candidates.length > MAX_FILE_SUGGESTION_CANDIDATES
  ) {
    throw new ProtocolViolation('file.suggestions 包含无效安全投影');
  }
  const seen = new Set<string>();
  for (const candidate of payload.candidates) {
    if (typeof candidate !== 'string'
      || Array.from(candidate).length > MAX_FILE_SUGGESTION_CANDIDATE_CHARS
      || /[\u0000-\u001f\u007f]/u.test(candidate)
      || candidate.startsWith('/')
      || candidate.startsWith('\\')
      || /^[A-Za-z]:/u.test(candidate)
      || candidate.includes('\\')
      || candidate.includes('"')
      || candidate.split('/').some(segment => segment.length === 0 || segment === '.' || segment === '..')
      || seen.has(candidate)) {
      throw new ProtocolViolation('file.suggestions 包含无效候选');
    }
    seen.add(candidate);
  }
}

function validateRunCommandResult(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  const commandTypes = new Set(['run.start', 'plan.start', 'plan.review.resolve', 'skill.invoke']);
  const dispositions = new Set(['accepted', 'queued', 'rejected']);
  const expectedFields = payload.disposition === 'queued'
    ? new Set(['commandType', 'disposition', 'code', 'queueDepth'])
    : new Set(['commandType', 'disposition', 'code']);
  if (sessionId === undefined || runId !== undefined
    || !hasExactFields(payload, expectedFields)
    || typeof payload.commandType !== 'string' || !commandTypes.has(payload.commandType)
    || typeof payload.disposition !== 'string' || !dispositions.has(payload.disposition)
    || typeof payload.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/u.test(payload.code)
    || (payload.disposition === 'accepted' && payload.code !== 'ACCEPTED')
    || (payload.disposition === 'queued' && payload.code !== 'QUEUED')
    || (payload.disposition === 'queued'
      && (!Number.isSafeInteger(payload.queueDepth)
        || (payload.queueDepth as number) < 1
        || (payload.queueDepth as number) > 100))) {
    throw new ProtocolViolation('run.command.result 包含无效 acceptance 投影');
  }
}

function validateSteeringQueued(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (
    sessionId === undefined
    || runId !== undefined
    || !hasExactFields(payload, new Set(['queueDepth']))
    || !Number.isSafeInteger(payload.queueDepth)
    || (payload.queueDepth as number) < 1
    || (payload.queueDepth as number) > 100
  ) {
    throw new ProtocolViolation('steering.queued 包含无效安全投影');
  }
}

function validateSteeringDiscarded(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (
    sessionId === undefined
    || runId !== undefined
    || !hasExactFields(payload, new Set(['reason']))
    || (payload.reason !== 'clear'
      && payload.reason !== 'cancelled'
      && payload.reason !== 'session_switch'
      && payload.reason !== 'shutdown')
  ) {
    throw new ProtocolViolation('steering.discarded 包含无效安全投影');
  }
}

const PROVIDER_CONTROL_INTENTS = new Set([
  'providers.configure', 'providers.add', 'auth.list', 'auth.probe', 'auth.logout',
  'models.list', 'models.add', 'models.remove', 'models.use',
]);

function validateProviderControlResult(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (sessionId === undefined || runId !== undefined
    || !hasExactFields(payload, new Set(['controlId', 'intent', 'status', 'code', 'result']))
    || !isBoundedIdentifier(payload.controlId)
    || typeof payload.intent !== 'string' || !PROVIDER_CONTROL_INTENTS.has(payload.intent)
    || (payload.status !== 'succeeded' && payload.status !== 'rejected')
    || typeof payload.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/u.test(payload.code)
    || !isRecord(payload.result)) {
    throw new ProtocolViolation('provider.control.result 包含无效安全投影');
  }
  if (payload.status === 'rejected') {
    if (!hasExactFields(payload.result, new Set())) throw new ProtocolViolation('provider.control 拒绝结果不得携带数据');
    return;
  }
  const result = payload.result;
  if (payload.intent === 'providers.add' || payload.intent === 'providers.configure') {
    if (!hasExactFields(result, new Set(['providerId', 'displayName', 'modelId']))
      || !isProviderId(result.providerId)
      || !isProviderDisplayName(result.displayName)
      || !isProviderModelId(result.modelId)) {
      throw new ProtocolViolation('provider.control provider 投影无效');
    }
  } else if (payload.intent === 'auth.list') {
    if (!hasExactFields(result, new Set(['profiles'])) || !Array.isArray(result.profiles) || result.profiles.length > 256) {
      throw new ProtocolViolation('provider.control profile 投影无效');
    }
    for (const item of result.profiles) {
      const required = new Set(['providerId', 'profileId', 'authMethod', 'refKind', 'localStatus', 'providerDefault']);
      if (!isRecord(item) || !Object.keys(item).every(key => required.has(key) || key === 'lastProbeCode' || key === 'lastProbeAt')
        || ![...required].every(key => key in item) || !isBoundedIdentifier(item.providerId)
        || !isBoundedIdentifier(item.profileId) || !isBoundedProjectionEnum(item.authMethod)
        || (item.refKind !== 'STORE' && item.refKind !== 'ENV') || !isBoundedProjectionEnum(item.localStatus)
        || typeof item.providerDefault !== 'boolean') throw new ProtocolViolation('provider.control profile 条目无效');
    }
  } else if (payload.intent === 'models.list') {
    if (!hasExactFields(result, new Set(['models'])) || !Array.isArray(result.models) || result.models.length > 256
      || result.models.some(item => !isRecord(item)
        || !hasExactFields(item, new Set(['providerId', 'modelId', 'providerDefault']))
        || !isBoundedIdentifier(item.providerId) || typeof item.modelId !== 'string'
        || item.modelId.length < 1 || item.modelId.length > 256 || typeof item.providerDefault !== 'boolean')) {
      throw new ProtocolViolation('provider.control model 投影无效');
    }
  } else if (payload.intent === 'models.add') {
    if (!hasExactFields(result, new Set(['providerId', 'modelId', 'setDefault']))
      || !isProviderId(result.providerId) || !isProviderModelId(result.modelId)
      || typeof result.setDefault !== 'boolean') {
      throw new ProtocolViolation('provider.control model add 投影无效');
    }
  } else if (payload.intent === 'models.remove') {
    if (!hasExactFields(result, new Set(['providerId', 'modelId']))
      || !isProviderId(result.providerId) || !isProviderModelId(result.modelId)) {
      throw new ProtocolViolation('provider.control model remove 投影无效');
    }
  } else if (payload.intent === 'models.use') {
    if (!hasExactFields(result, new Set(['providerId', 'profileId', 'modelId', 'setDefault']))
      || !isProviderId(result.providerId) || !isProviderId(result.profileId)
      || !isProviderModelId(result.modelId) || typeof result.setDefault !== 'boolean') {
      throw new ProtocolViolation('provider.control selection 投影无效');
    }
  } else if (payload.intent === 'auth.probe') {
    if (!hasExactFields(result, new Set(['providerId', 'profileId', 'modelId', 'outcome', 'probedAt']))) throw new ProtocolViolation('provider.control probe 投影无效');
  } else if (!hasExactFields(result, new Set(['providerId', 'profileId', 'remoteRevoked'])) || result.remoteRevoked !== false) {
    throw new ProtocolViolation('provider.control logout 投影无效');
  }
}
const SESSION_COMMAND_INTENTS = new Set([
  'help', 'clear', 'compact', 'context', 'doctor', 'model', 'permissions', 'resume', 'tasks',
  'plan-status', 'plan', 'plan-approve', 'plan-step-begin', 'plan-reject', 'plan-step-complete', 'plan-execute',
]);
const PUBLIC_HELP_INTENTS = new Set([
  'help', 'clear', 'compact', 'context', 'doctor', 'model', 'permissions', 'resume', 'tasks', 'plan-status', 'plan',
]);
const SESSION_COMMAND_CODES = new Set([
  'ok', 'active_run', 'invalid_argument', 'unavailable', 'not_available', 'deferred',
  'cancelled', 'compaction_rejected', 'internal_failure', 'request_budget_exhausted',
  'current_session', 'session_active', 'recovery_required',
]);
const SESSION_COMMAND_SUPPORT = new Set(['available', 'deferred', 'not_available']);

function validateSessionCommandResult(
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  const fields = new Set(['commandId', 'intent', 'status', 'code', 'result']);
  if (
    sessionId === undefined || runId !== undefined
    || !hasExactFields(payload, fields)
    || !isBoundedIdentifier(payload.commandId)
    || typeof payload.intent !== 'string' || !SESSION_COMMAND_INTENTS.has(payload.intent)
    || typeof payload.status !== 'string'
    || typeof payload.code !== 'string' || !SESSION_COMMAND_CODES.has(payload.code)
    || !isRecord(payload.result)
    || !isValidSessionCommandStatus(payload.status, payload.code)
  ) {
    throw new ProtocolViolation('session.command.result 包含无效安全投影');
  }
  validateSessionCommandPayload(payload.intent, payload.status, payload.result);
}

function isValidSessionCommandStatus(status: unknown, code: unknown): boolean {
  return (status === 'succeeded' && code === 'ok')
    || (status === 'cancelled' && code === 'cancelled')
    || (status === 'failed' && code === 'internal_failure')
    || (status === 'rejected' && typeof code === 'string'
      && code !== 'ok' && code !== 'cancelled' && code !== 'internal_failure');
}

function validateSessionCommandPayload(
  intent: string,
  status: string,
  result: Readonly<Record<string, unknown>>,
): void {
  if (status !== 'succeeded') {
    if (!hasExactFields(result, new Set())) throw new ProtocolViolation('session.command.result 拒绝结果不得携带数据');
    return;
  }
  if (intent === 'help') {
    if (!hasExactFields(result, new Set(['commands'])) || !Array.isArray(result.commands)
      || result.commands.length !== PUBLIC_HELP_INTENTS.size) {
      throw new ProtocolViolation('session.command.result help 投影无效');
    }
    const seen = new Set<string>();
    for (const item of result.commands) {
      if (!isRecord(item) || !hasExactFields(item, new Set(['intent', 'support']))
        || typeof item.intent !== 'string' || !PUBLIC_HELP_INTENTS.has(item.intent)
        || typeof item.support !== 'string' || !SESSION_COMMAND_SUPPORT.has(item.support)
        || seen.has(item.intent)) throw new ProtocolViolation('session.command.result help 条目无效');
      seen.add(item.intent);
    }
    return;
  }
  if (intent === 'context') {
    const fields = new Set(['systemTokens', 'transcriptTokens', 'toolTokens', 'memoryTokens',
      'totalTokens', 'availableInputTokens', 'freeTokens', 'overflowTokens', 'sourceRevision',
      'estimateKind', 'contextStatus', 'modelRequestAttempts', 'reductionStrategies', 'reasonCodes']);
    if (!hasExactFields(result, fields)
      || !nonNegativeSafeIntegers(result, ['systemTokens', 'transcriptTokens', 'toolTokens', 'memoryTokens',
        'totalTokens', 'overflowTokens', 'sourceRevision', 'modelRequestAttempts'])
      || !Number.isSafeInteger(result.freeTokens)
      || !Number.isSafeInteger(result.availableInputTokens) || (result.availableInputTokens as number) < 1
      || !isBoundedProjectionEnum(result.estimateKind) || !isBoundedProjectionEnum(result.contextStatus)
      || !isBoundedProjectionEnumList(result.reductionStrategies) || !isBoundedProjectionEnumList(result.reasonCodes)) {
      throw new ProtocolViolation('session.command.result context 投影无效');
    }
    return;
  }
  if (intent === 'permissions') {
    const fields = new Set(['effectiveMode', 'effectiveReviewer', 'effectiveSelection', 'modeSourceKind', 'modeSafeSourceId', 'modeValidationStatus', 'startupRuleCount', 'rules']);
    if (!hasExactFields(result, fields)
      || (result.effectiveMode !== 'DEFAULT' && result.effectiveMode !== 'PLAN' && result.effectiveMode !== 'ACCEPT_EDITS')
      || (result.effectiveReviewer !== 'USER' && result.effectiveReviewer !== 'AUTO_REVIEW')
      || (result.effectiveSelection !== 'PLAN' && result.effectiveSelection !== 'ASK'
        && result.effectiveSelection !== 'AUTO' && result.effectiveSelection !== 'ADVANCED')
      || !isBoundedProjectionEnum(result.modeSourceKind) || !isSafeRelativeTarget(result.modeSafeSourceId)
      || !isBoundedProjectionEnum(result.modeValidationStatus)
      || !Number.isSafeInteger(result.startupRuleCount) || (result.startupRuleCount as number) < 0
      || (result.startupRuleCount as number) > 128 || !Array.isArray(result.rules)
      || result.rules.length !== result.startupRuleCount) {
      throw new ProtocolViolation('session.command.result permissions 投影无效');
    }
    for (const rule of result.rules) {
      if (!isRecord(rule) || !hasExactFields(rule, new Set(['ruleId', 'sourceKind', 'safeSourceId', 'operation', 'validationStatus']))
        || typeof rule.ruleId !== 'string' || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(rule.ruleId) || rule.ruleId.length > 64
        || !isBoundedProjectionEnum(rule.sourceKind) || !isSafeRelativeTarget(rule.safeSourceId)
        || !isBoundedProjectionEnum(rule.operation) || !isBoundedProjectionEnum(rule.validationStatus)) {
        throw new ProtocolViolation('session.command.result permissions 规则投影无效');
      }
    }
    return;
  }
  if (intent === 'tasks') {
    validateTaskBoardSnapshot(result);
    return;
  }
  if (intent.startsWith('plan')) {
    if (!hasExactFields(result, new Set(['planId', 'status', 'approvalGate', 'objective', 'workspaceDigest', 'steps', 'nextStep', 'activeStep']))) {
      throw new ProtocolViolation('session.command.result plan 投影无效');
    }
    if (!isBoundedIdentifier(result.planId) || !isBoundedProjectionEnum(result.status)
      || !isBoundedProjectionEnum(result.approvalGate) || typeof result.objective !== 'string'
      || !isSafeRelativeTarget(result.workspaceDigest) || !Array.isArray(result.steps)
      || result.steps.length < 1 || result.steps.length > 128
      || (result.nextStep !== null && !Number.isSafeInteger(result.nextStep))
      || (result.activeStep !== null && !Number.isSafeInteger(result.activeStep))) {
      throw new ProtocolViolation('session.command.result plan 投影无效');
    }
    for (const step of result.steps) {
      if (!isRecord(step) || !hasExactFields(step, new Set(['ordinal', 'title', 'detail', 'expectedDigest']))
        || !Number.isSafeInteger(step.ordinal) || typeof step.title !== 'string'
        || typeof step.detail !== 'string' || !isSafeRelativeTarget(step.expectedDigest)) {
        throw new ProtocolViolation('session.command.result plan step 投影无效');
      }
    }
    return;
  }
  if (intent === 'resume') {
    if (!hasExactFields(result, new Set(['previousSessionId', 'resumedSessionId']))
      || !isSessionId(result.previousSessionId) || !isSessionId(result.resumedSessionId)
      || result.previousSessionId === result.resumedSessionId) {
      throw new ProtocolViolation('session.command.result resume 投影无效');
    }
    return;
  }
  if (intent === 'doctor') {
    const fields = new Set(['settingsAvailable', 'settingsRevision', 'instructionCount', 'contextAvailable', 'activeRun', 'entries']);
    if (!hasExactFields(result, fields) || typeof result.settingsAvailable !== 'boolean'
      || !Number.isSafeInteger(result.settingsRevision) || (result.settingsRevision as number) < 0
      || !Number.isSafeInteger(result.instructionCount) || (result.instructionCount as number) < 0
      || typeof result.contextAvailable !== 'boolean' || typeof result.activeRun !== 'boolean'
      || !Array.isArray(result.entries) || result.entries.length > 128) {
      throw new ProtocolViolation('session.command.result doctor 投影无效');
    }
    for (const entry of result.entries) {
      if (!isRecord(entry) || !hasExactFields(entry, new Set(['component', 'sourceKind', 'safeId', 'code', 'severity']))
        || !isBoundedProjectionEnum(entry.component) || !isBoundedProjectionEnum(entry.sourceKind)
        || !isSafeRelativeTarget(entry.safeId) || !isBoundedProjectionEnum(entry.code)
        || !isBoundedProjectionEnum(entry.severity)) throw new ProtocolViolation('session.command.result doctor 条目无效');
    }
    return;
  }
  if (!hasExactFields(result, new Set())) throw new ProtocolViolation('session.command.result 不应携带数据');
}

function hasExactFields(value: Readonly<Record<string, unknown>>, fields: ReadonlySet<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === fields.size && keys.every(key => fields.has(key));
}

function positiveSafeInteger(value: unknown): boolean {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function nonNegativeSafeIntegers(value: Readonly<Record<string, unknown>>, fields: readonly string[]): boolean {
  return fields.every(field => Number.isSafeInteger(value[field]) && (value[field] as number) >= 0);
}

function isProviderId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-z0-9][a-z0-9-]{0,62}$/u.test(value);
}

function isProviderDisplayName(value: unknown): value is string {
  return typeof value === 'string' && value.trim() === value && value.length > 0
    && Array.from(value).length <= 80 && Buffer.byteLength(value, 'utf8') <= 256
    && !/[\u0000-\u001f\u007f]/u.test(value);
}

function isProviderModelId(value: unknown): value is string {
  return typeof value === 'string' && value.trim() === value && value.length > 0
    && Array.from(value).length <= 256 && Buffer.byteLength(value, 'utf8') <= 1_024
    && !/[\u0000-\u001f\u007f]/u.test(value);
}

function isBoundedProjectionEnum(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value);
}

function isBoundedProjectionEnumList(value: unknown): value is readonly string[] {
  return Array.isArray(value) && value.length <= 32 && value.every(isBoundedProjectionEnum);
}

function validateCheckpointList(
  payload: Readonly<Record<string, unknown>>,
): void {
  if (
    Object.keys(payload).some(key => key !== 'checkpoints')
    || !Array.isArray(payload.checkpoints)
    || payload.checkpoints.length > MAX_CHECKPOINTS
  ) {
    throw new ProtocolViolation('checkpoint.listed 包含无效列表');
  }
  for (const checkpoint of payload.checkpoints) {
    if (!isRecord(checkpoint)) {
      throw new ProtocolViolation('checkpoint.listed 包含无效条目');
    }
    const allowedFields = new Set([
      'checkpointId', 'callId', 'toolName', 'target', 'existedBefore', 'phase', 'undoable',
    ]);
    if (
      Object.keys(checkpoint).some(key => !allowedFields.has(key))
      || Object.keys(checkpoint).length !== allowedFields.size
      || !isCheckpointId(checkpoint.checkpointId)
      || !isBoundedIdentifier(checkpoint.callId)
      || !isBoundedIdentifier(checkpoint.toolName)
      || !isSafeRelativeTarget(checkpoint.target)
      || typeof checkpoint.existedBefore !== 'boolean'
      || typeof checkpoint.phase !== 'string'
      || !CHECKPOINT_PHASES.has(checkpoint.phase)
      || typeof checkpoint.undoable !== 'boolean'
      || checkpoint.undoable !== (
        checkpoint.phase === 'completed_present'
        || checkpoint.phase === 'completed_absent'
      )
    ) {
      throw new ProtocolViolation('checkpoint.listed 包含无效条目');
    }
  }
}

function validateCheckpointDiff(
  payload: Readonly<Record<string, unknown>>,
): void {
  const allowedFields = new Set([
    'checkpointId', 'target', 'status', 'text', 'truncated',
  ]);
  if (
    Object.keys(payload).some(key => !allowedFields.has(key))
    || Object.keys(payload).length !== allowedFields.size
    || !isCheckpointId(payload.checkpointId)
    || !isSafeRelativeTarget(payload.target)
    || typeof payload.status !== 'string'
    || !CHECKPOINT_DIFF_STATUSES.has(payload.status)
    || !isSafeDisplayText(payload.text, MAX_CHECKPOINT_DIFF_CHARS, true)
    || typeof payload.truncated !== 'boolean'
  ) {
    throw new ProtocolViolation('checkpoint.diffed 包含无效有界结果');
  }
}

function validateCheckpointUndo(
  payload: Readonly<Record<string, unknown>>,
): void {
  const allowedFields = new Set([
    'checkpointId', 'target', 'status', 'message',
  ]);
  if (
    Object.keys(payload).some(key => !allowedFields.has(key))
    || Object.keys(payload).length !== allowedFields.size
    || !isCheckpointId(payload.checkpointId)
    || !isSafeRelativeTarget(payload.target)
    || typeof payload.status !== 'string'
    || !CHECKPOINT_UNDO_STATUSES.has(payload.status)
    || !isSafeDisplayText(payload.message, MAX_CHECKPOINT_MESSAGE_CHARS, false)
  ) {
    throw new ProtocolViolation('checkpoint.undone 包含无效结果');
  }
}

function isCheckpointId(value: unknown): value is string {
  return typeof value === 'string'
    && value.length <= MAX_IDENTIFIER_CHARS
    && CHECKPOINT_ID.test(value);
}

function isBoundedIdentifier(value: unknown): value is string {
  return typeof value === 'string'
    && value.trim().length > 0
    && value.length <= MAX_IDENTIFIER_CHARS
    && !/[\u0000-\u001f\u007f]/u.test(value);
}

function isSessionId(value: unknown): value is string {
  return typeof value === 'string'
    && value.length <= MAX_IDENTIFIER_CHARS
    && /^session-[A-Za-z0-9-]+$/.test(value);
}

function isSafeRelativeTarget(value: unknown): value is string {
  return typeof value === 'string'
    && value.length > 0
    && value.length <= MAX_CHECKPOINT_TARGET_CHARS
    && !/[\u0000-\u001f\u007f]/u.test(value)
    && !value.startsWith('/')
    && !value.startsWith('\\')
    && !/^[A-Za-z]:/.test(value)
    && !value.split(/[\\/]/).includes('..');
}

function isSafeDisplayText(
  value: unknown,
  maxCharacters: number,
  allowEmpty: boolean,
): value is string {
  return typeof value === 'string'
    && (allowEmpty || value.length > 0)
    && Array.from(value).length <= maxCharacters
    && !/[\u0000\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value);
}

function validateApprovalPreview(
  payload: Readonly<Record<string, unknown>>,
): void {
  const networkFields = [payload.destination, payload.query];
  const networkPresent = networkFields.filter(value => value !== undefined).length;
  if (payload.effect === 'network_or_remote') {
    const hasNonNetworkPreview = payload.target !== undefined
      || payload.removedLines !== undefined
      || payload.addedLines !== undefined
      || payload.command !== undefined
      || payload.shell !== undefined
      || payload.workingDirectory !== undefined;
    if (hasNonNetworkPreview) {
      throw new ProtocolViolation('approval.requested 网络预览混入其他副作用字段');
    }
    if (networkPresent === 0 && payload.operation === undefined) {
      return;
    }
    if (
      networkPresent !== networkFields.length
      || payload.operation !== 'search'
      || payload.destination !== 'configured_web_search_provider'
      || typeof payload.query !== 'string'
      || payload.query.trim().length === 0
      || Array.from(payload.query).length > 512
      || !isSafeDisplayText(payload.query, 512, false)
      || /[\u0000-\u001f\u007f]/u.test(payload.query)
    ) {
      throw new ProtocolViolation('approval.requested 网络预览无效');
    }
    return;
  }
  if (networkPresent > 0 || payload.operation === 'search') {
    throw new ProtocolViolation('approval.requested 非网络审批包含网络预览');
  }
  const fields = [
    payload.target,
    payload.operation,
    payload.removedLines,
    payload.addedLines,
  ];
  const present = fields.filter(value => value !== undefined).length;
  const commandFields = [
    payload.command,
    payload.shell,
    payload.workingDirectory,
  ];
  const commandPresent = commandFields.filter(value => value !== undefined).length;
  if (present === 0 && commandPresent === 0) {
    return;
  }
  if (commandPresent > 0) {
    if (
      present !== 1
      || payload.operation !== 'execute'
      || commandPresent !== commandFields.length
      || typeof payload.command !== 'string'
      || payload.command.trim().length === 0
      || Array.from(payload.command).length > 8_192
      || typeof payload.shell !== 'string'
      || (payload.shell !== 'powershell' && payload.shell !== 'sh')
      || payload.workingDirectory !== '.'
    ) {
      throw new ProtocolViolation('approval.requested 命令预览无效');
    }
    return;
  }
  if (
    present !== fields.length
    || typeof payload.target !== 'string'
    || payload.target.length === 0
    || payload.target.length > 512
    || payload.target.startsWith('/')
    || payload.target.startsWith('\\')
    || /^[A-Za-z]:/.test(payload.target)
    || payload.target.split(/[\\/]/).includes('..')
    || (payload.operation !== 'modify' && payload.operation !== 'create')
    || !Number.isSafeInteger(payload.removedLines)
    || (payload.removedLines as number) < 0
    || !Number.isSafeInteger(payload.addedLines)
    || (payload.addedLines as number) < 0
  ) {
    throw new ProtocolViolation('approval.requested 文件预览无效');
  }
}

function validateOptionalToolPresentation(
  type: EventType,
  payload: Readonly<Record<string, unknown>>,
): void {
  if ('activity' in payload
    && (typeof payload.activity !== 'string'
      || payload.activity.trim().length === 0
      || Array.from(payload.activity).length > 320
      || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(payload.activity))) {
    throw new ProtocolViolation(`${type} 包含无效 Tool 活动摘要`);
  }
  if (
    'mode' in payload
    && payload.mode !== 'content'
    && payload.mode !== 'files'
    && payload.mode !== 'count'
  ) {
    throw new ProtocolViolation(`${type} 包含未知搜索展示模式`);
  }
  if (
    'returnedItems' in payload
    && (!Number.isSafeInteger(payload.returnedItems)
      || (payload.returnedItems as number) < 0)
  ) {
    throw new ProtocolViolation(`${type} 的 returnedItems 必须是非负安全整数`);
  }
  if (
    'exitCode' in payload
    && (type === 'tool.started'
      || !Number.isSafeInteger(payload.exitCode)
      || (payload.exitCode as number) < -1
      || (payload.exitCode as number) > 2_147_483_647)
  ) {
    throw new ProtocolViolation(`${type} 包含无效命令退出码`);
  }
  if (
    'truncationReason' in payload
    && (typeof payload.truncationReason !== 'string'
      || !/^[a-z][a-z0-9_]{0,63}$/.test(payload.truncationReason))
  ) {
    throw new ProtocolViolation(`${type} 包含无效截断原因`);
  }
}

function isTerminalRunEvent(type: EventType): boolean {
  return type === 'run.completed'
    || type === 'run.failed'
    || type === 'run.cancelled';
}

function validateOptionalTerminalCount(
  type: EventType,
  payload: Readonly<Record<string, unknown>>,
  field: 'modelTurns' | 'toolCalls',
): void {
  if (
    field in payload
    && (!Number.isSafeInteger(payload[field]) || (payload[field] as number) < 0)
  ) {
    throw new ProtocolViolation(`${type} 的 ${field} 必须是非负安全整数`);
  }
}

const MODEL_FAILURE_CATEGORIES = new Set([
  'provider_unavailable',
  'rate_limited',
  'request_timeout',
  'request_conflict',
  'authentication_failed',
  'invalid_request',
  'network_error',
  'incomplete_stream',
  'invalid_response',
  'provider_error',
  'configuration_required',
]);

function validateOptionalModelFailure(
  type: EventType,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (!('modelFailure' in payload)) {
    return;
  }
  if ((type !== 'run.failed' && type !== 'plan.execution.failed')
    || !isRecord(payload.modelFailure)) {
    throw new ProtocolViolation(`${type} 包含无效模型失败摘要`);
  }
  const failure = payload.modelFailure;
  const allowedFields = new Set([
    'category', 'statusClass', 'attempts', 'receivedOutput',
  ]);
  if (
    Object.keys(failure).some(key => !allowedFields.has(key))
    || typeof failure.category !== 'string'
    || !MODEL_FAILURE_CATEGORIES.has(failure.category)
    || ('statusClass' in failure
      && failure.statusClass !== '4xx'
      && failure.statusClass !== '5xx')
    || !Number.isSafeInteger(failure.attempts)
    || (failure.attempts as number) < 1
    || (failure.attempts as number) > 100
    || typeof failure.receivedOutput !== 'boolean'
  ) {
    throw new ProtocolViolation(`${type} 包含无效模型失败摘要`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireText(value: Record<string, unknown>, field: string): string {
  const text = value[field];
  if (
    typeof text !== 'string'
    || text.trim().length === 0
    || text.length > MAX_IDENTIFIER_CHARS
  ) {
    throw new ProtocolViolation(`${field} 为空或超过长度限制`);
  }
  return text;
}

function optionalText(
  value: Record<string, unknown>,
  field: string,
): string | undefined {
  if (!(field in value) || value[field] === null) {
    return undefined;
  }
  return requireText(value, field);
}

function requireInteger(value: Record<string, unknown>, field: string): number {
  const number = value[field];
  if (!Number.isSafeInteger(number) || (number as number) < 0) {
    throw new ProtocolViolation(`${field} 必须是非负安全整数`);
  }
  return number as number;
}
