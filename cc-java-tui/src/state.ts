import type {ProtocolEvent} from './protocol.js';
import {
  appendToolOutput as appendOutputChunk,
  EMPTY_TOOL_OUTPUT,
  finalizeToolOutput,
  type ToolOutputBuffer,
} from './tool-output.js';

export type RunStatus = 'submitting' | 'accepted' | 'queued' | 'running' | 'retrying'
  | 'completed' | 'cancelled' | 'failed';
export type ClientPhase = 'connecting' | 'ready' | 'submitting' | 'accepted' | 'running'
  | 'closing' | 'closed' | 'failed';
export type SearchMode = 'content' | 'files' | 'count';
export type ModelFailureCategory =
  | 'provider_unavailable'
  | 'rate_limited'
  | 'request_timeout'
  | 'request_conflict'
  | 'authentication_failed'
  | 'invalid_request'
  | 'network_error'
  | 'incomplete_stream'
  | 'invalid_response'
  | 'provider_error'
  | 'configuration_required';

export interface ModelFailureView {
  readonly category: ModelFailureCategory;
  readonly statusClass: '4xx' | '5xx' | undefined;
  readonly attempts: number;
  readonly receivedOutput: boolean;
}

export interface ApprovalView {
  readonly approvalId: string;
  readonly ordinal: number;
  readonly toolName: string;
  readonly effect: 'write_workspace' | 'execute_process' | 'network_or_remote';
  readonly target: string | undefined;
  readonly operation: 'modify' | 'create' | undefined;
  readonly removedLines: number | undefined;
  readonly addedLines: number | undefined;
  readonly command: string | undefined;
  readonly shell: 'powershell' | 'sh' | undefined;
  readonly workingDirectory: string | undefined;
  readonly destination: 'configured_web_search_provider' | undefined;
  readonly query: string | undefined;
  readonly submitted: boolean;
}

export interface ToolView {
  readonly ordinal: number;
  readonly name: string;
  readonly mode: SearchMode | undefined;
  readonly activity?: string | undefined;
  readonly status: 'started' | 'success' | 'failed' | 'denied';
  readonly returnedCharacters: number | undefined;
  readonly returnedItems: number | undefined;
  readonly filteredItems: number | undefined;
  readonly truncated: boolean;
  readonly truncationReason: string | undefined;
  readonly errorCode: string | undefined;
  readonly failureCategory: string | undefined;
  readonly retryable: boolean | undefined;
  readonly argumentChangeRequired: boolean;
  readonly strategyChangeRequired: boolean;
  readonly exitCode: number | undefined;
  readonly output: ToolOutputBuffer;
}

export interface ModelProgressView {
  readonly turn: number;
  readonly phase: 'thinking' | 'responding' | 'preparing_tools';
  readonly providerInputTokens: number;
  readonly providerOutputTokens: number;
  readonly providerTotalTokens: number;
  readonly usageReportedTurns: number;
  readonly usageMissingTurns: number;
  readonly contextUsedTokens: number | undefined;
  readonly contextMaximumInputTokens: number | undefined;
  readonly contextEstimateKind: 'estimated' | 'exact' | undefined;
  readonly retryAttempt?: number | undefined;
  readonly retryMaxAttempts?: number | undefined;
  readonly retryWaitMillis?: number | undefined;
  readonly retryCategory?: ModelFailureCategory | undefined;
}

export interface PlanProposalView {
  readonly planId: string;
  readonly status: 'awaiting_approval';
  readonly objective: string;
  readonly workspaceDigest: string;
  readonly steps: readonly {
    readonly ordinal: number;
    readonly title: string;
    readonly detail: string;
  }[];
}

export interface PlanReviewView {
  readonly planId: string;
  readonly status: 'awaiting_approval';
  readonly revision: number;
  readonly contentDigest: string;
  readonly markdown: string;
  readonly workspaceDigest: string;
  readonly originalPermissionMode: 'default' | 'accept_edits';
  readonly suggestedContextPolicy: 'keep' | 'clear';
}

export interface UserQuestionView {
  readonly callId: string;
  readonly question: string;
  readonly options: readonly {
    readonly optionId: string;
    readonly label: string;
    readonly description: string;
  }[];
  readonly submitted: boolean;
}

export interface RunView {
  readonly requestId: string;
  readonly prompt: string;
  readonly runId: string | undefined;
  readonly text: string;
  readonly tools: readonly ToolView[];
  readonly toolDetailOrdinal?: number | undefined;
  readonly toolDetailExpanded?: boolean | undefined;
  readonly modelProgress?: ModelProgressView | undefined;
  readonly pendingApproval?: ApprovalView | undefined;
  readonly planProposal?: PlanProposalView | undefined;
  readonly planReview?: PlanReviewView | undefined;
  readonly planReviewSettled?: boolean | undefined;
  readonly pendingQuestion?: UserQuestionView | undefined;
  readonly status: RunStatus;
  readonly stopReason: string | undefined;
  readonly modelFailure?: ModelFailureView | undefined;
  readonly modelTurns: number | undefined;
  readonly toolCalls: number | undefined;
  readonly planVerification?: string | undefined;
  readonly awaitingPlanVerification?: boolean | undefined;
}

export type CheckpointPhase =
  | 'create_prepared'
  | 'create_journal_uncertain'
  | 'created'
  | 'post_prepared'
  | 'post_journal_uncertain'
  | 'completed_present'
  | 'completed_absent'
  | 'undo_prepared'
  | 'undo_applied'
  | 'undo_journal_uncertain'
  | 'undone';

export interface CheckpointView {
  readonly checkpointId: string;
  readonly callId: string;
  readonly toolName: string;
  readonly target: string;
  readonly existedBefore: boolean;
  readonly phase: CheckpointPhase;
  readonly undoable: boolean;
}

export interface CheckpointDiffView {
  readonly checkpointId: string;
  readonly target: string;
  readonly status: 'unchanged' | 'changed' | 'absent' | 'conflict';
  readonly text: string;
  readonly truncated: boolean;
}

export interface CheckpointUndoView {
  readonly checkpointId: string;
  readonly target: string;
  readonly status: 'restored' | 'already_restored' | 'conflict';
  readonly message: string;
}

export interface ChildTaskView {
  readonly taskId: string;
  readonly definitionId: string;
  readonly status: 'queued' | 'starting' | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'interrupted_unknown';
  readonly failure: string;
  readonly modelTurns: number;
  readonly toolCalls: number;
  readonly estimatedTokens: number;
  readonly elapsedMillis: number;
  readonly summary: string;
  readonly verified: boolean;
  readonly worktreeDisposition: string | undefined;
}

export interface TuiState {
  readonly phase: ClientPhase;
  readonly sessionId: string | undefined;
  readonly activeRunId: string | undefined;
  readonly runs: readonly RunView[];
  readonly checkpoints: readonly CheckpointView[];
  readonly childTasks?: readonly ChildTaskView[];
  readonly checkpointPanelOpen: boolean;
  readonly selectedCheckpointId: string | undefined;
  readonly checkpointDiff: CheckpointDiffView | undefined;
  readonly pendingUndoCheckpointId: string | undefined;
  readonly checkpointUndo: CheckpointUndoView | undefined;
  readonly steeringQueueDepth?: number | undefined;
  readonly historicalToolDetailRunId?: string | undefined;
  readonly historicalToolDetailOrdinal?: number | undefined;
  readonly historicalToolDetailOpen?: boolean | undefined;
  readonly notice: string | undefined;
}

export type TuiAction =
  | {
    readonly type: 'run.submitted';
    readonly requestId: string;
    readonly prompt: string;
    readonly awaitingPlanVerification?: boolean;
  }
  | {readonly type: 'run.submission.rejected'; readonly requestId: string; readonly message: string}
  | {readonly type: 'run.submission.timed_out'; readonly requestId: string}
  | {readonly type: 'run.submission.late'; readonly requestId: string}
  | {readonly type: 'approval.submitted'; readonly approvalId: string}
  | {readonly type: 'plan.status.received'; readonly requestId: string; readonly proposal: PlanProposalView}
  | {readonly type: 'checkpoint.selected'; readonly checkpointId: string}
  | {readonly type: 'checkpoint.undo.requested'; readonly checkpointId: string}
  | {readonly type: 'checkpoint.undo.cancelled'}
  | {readonly type: 'tool.detail.next'}
  | {readonly type: 'tool.detail.toggle'}
  | {readonly type: 'event.received'; readonly event: ProtocolEvent}
  | {readonly type: 'transport.failed'; readonly message: string}
  | {readonly type: 'slash.notice'; readonly message: string}
  | {readonly type: 'closing'}
  | {readonly type: 'closed'};

export const initialTuiState: TuiState = {
  phase: 'connecting',
  sessionId: undefined,
  activeRunId: undefined,
  runs: [],
  checkpoints: [],
  childTasks: [],
  checkpointPanelOpen: false,
  selectedCheckpointId: undefined,
  checkpointDiff: undefined,
  pendingUndoCheckpointId: undefined,
  checkpointUndo: undefined,
  steeringQueueDepth: 0,
  historicalToolDetailRunId: undefined,
  historicalToolDetailOrdinal: undefined,
  historicalToolDetailOpen: false,
  notice: undefined,
};

/**
 * 把 Java 事件投影为只读终端状态。
 *
 * Reducer 不启动进程、不发送命令，也不自行推断 Run 完成；只有 Java 的互斥终态事件
 * 能把活动 Run 变回 Ready。
 */
export function reduceTuiState(state: TuiState, action: TuiAction): TuiState {
  switch (action.type) {
    case 'run.submitted': {
      const hasAuthoritativeRun = state.activeRunId !== undefined;
      if (state.phase !== 'ready' && state.phase !== 'accepted' && !hasAuthoritativeRun) {
        return {...state, notice: '当前状态不能提交新的 Run command'};
      }
      return {
        ...state,
        phase: hasAuthoritativeRun ? state.phase : 'submitting',
        notice: undefined,
        historicalToolDetailOpen: false,
        runs: [
          ...state.runs,
          {
            requestId: action.requestId,
            prompt: action.prompt,
            runId: undefined,
            text: '',
            tools: [],
            modelProgress: undefined,
            awaitingPlanVerification: action.awaitingPlanVerification,
            pendingApproval: undefined,
            planProposal: undefined,
            status: 'submitting',
            stopReason: undefined,
            modelFailure: undefined,
            modelTurns: undefined,
            toolCalls: undefined,
          },
        ],
      };
    }
    case 'run.submission.rejected':
      return rejectUnstartedSubmission(state, action.requestId, action.message);
    case 'run.submission.timed_out':
      return rejectUnstartedSubmission(
        state,
        action.requestId,
        'Java 未在期限内确认请求；草稿已恢复，不会自动重放',
      );
    case 'run.submission.late':
      return {...state, notice: '已忽略迟到的 Java acceptance；不会自动重放'};
    case 'approval.submitted':
      return {
        ...state,
        runs: state.runs.map(run => run.pendingApproval?.approvalId === action.approvalId
          ? {
              ...run,
              pendingApproval: {...run.pendingApproval, submitted: true},
            }
          : run),
      };
    case 'plan.status.received': {
      const existingIndex = state.runs.findLastIndex(run =>
        run.planProposal?.planId === action.proposal.planId);
      if (existingIndex >= 0) {
        return {
          ...state,
          runs: state.runs.map((run, index) => index === existingIndex
            ? {...run, planProposal: action.proposal} : run),
        };
      }
      return {
        ...state,
        runs: [...state.runs, {
          requestId: action.requestId,
          prompt: '/plan',
          runId: undefined,
          text: '',
          tools: [],
          modelProgress: undefined,
          pendingApproval: undefined,
          planProposal: action.proposal,
          status: 'completed',
          stopReason: 'completed',
          modelFailure: undefined,
          modelTurns: undefined,
          toolCalls: undefined,
        }],
      };
    }
    case 'checkpoint.selected':
      return state.checkpoints.some(item => item.checkpointId === action.checkpointId)
        ? {
            ...state,
            selectedCheckpointId: action.checkpointId,
            checkpointDiff: undefined,
            checkpointUndo: undefined,
            notice: undefined,
          }
        : state;
    case 'checkpoint.undo.requested':
      return state.checkpoints.some(item => item.checkpointId === action.checkpointId
        && item.undoable)
        ? {...state, pendingUndoCheckpointId: action.checkpointId, notice: undefined}
        : {...state, notice: '当前 Checkpoint 不可 Undo'};
    case 'checkpoint.undo.cancelled':
      return {...state, pendingUndoCheckpointId: undefined};
    case 'tool.detail.next':
      return updateToolDetail(state, 'next');
    case 'tool.detail.toggle':
      return updateToolDetail(state, 'toggle');
    case 'event.received':
      return applyEvent(state, action.event);
    case 'transport.failed':
      return {
        ...state,
        phase: 'failed',
        notice: action.message,
        activeRunId: undefined,
        steeringQueueDepth: 0,
        runs: state.runs
          .filter(run => run.runId !== undefined || !isActiveRunStatus(run.status))
          .map(run => isStartedRun(run) && isActiveRunStatus(run.status)
            ? {...run, status: 'failed' as const, stopReason: 'transport_closed', pendingApproval: undefined}
            : run),
      };
    case 'slash.notice':
      return {...state, notice: action.message};
    case 'closing':
      return {...state, phase: 'closing'};
    case 'closed':
      return {...state, phase: 'closed', activeRunId: undefined, steeringQueueDepth: 0};
  }
}

function applyEvent(state: TuiState, event: ProtocolEvent): TuiState {
  switch (event.type) {
    case 'initialized':
      return {
        ...state,
        phase: 'ready',
        sessionId: event.sessionId,
        steeringQueueDepth: 0,
        notice: undefined,
      };
    case 'skill.invoked':
      return {...state, notice: `Skill /${String(event.payload.skillId)} 已提交`};
    case 'skill.completed':
      return {...state, notice: event.payload.status === 'succeeded'
        ? `Skill /${String(event.payload.skillId)} 已完成`
        : `Skill /${String(event.payload.skillId)} 未完成`};
    case 'task.status':
    case 'task.terminal': {
      const task: ChildTaskView = {
        taskId: String(event.payload.taskId),
        definitionId: String(event.payload.definitionId),
        status: event.payload.status as ChildTaskView['status'],
        failure: String(event.payload.failure),
        modelTurns: Number(event.payload.modelTurns),
        toolCalls: Number(event.payload.toolCalls),
        estimatedTokens: Number(event.payload.estimatedTokens),
        elapsedMillis: Number(event.payload.elapsedMillis),
        summary: String(event.payload.summary),
        verified: event.payload.verified === true,
        worktreeDisposition: optionalText(event.payload.worktreeDisposition),
      };
      const childTasks = state.childTasks ?? [];
      const existing = childTasks.findIndex(item => item.taskId === task.taskId);
      return {
        ...state,
        childTasks: existing < 0
          ? [...childTasks, task]
          : childTasks.map(item => item.taskId === task.taskId ? task : item),
        notice: event.type === 'task.terminal'
          ? `子任务 ${task.taskId}：${task.status}` : state.notice,
      };
    }
    case 'task.worktree': {
      const taskId = String(event.payload.taskId);
      const disposition = String(event.payload.disposition);
      return {
        ...state,
        childTasks: (state.childTasks ?? []).map(task => task.taskId === taskId
          ? {...task, worktreeDisposition: disposition} : task),
        notice: `子任务 ${taskId} worktree：${disposition}`,
      };
    }
    case 'run.budget.governed':
      return {...state, notice: `交互预算：${String(event.payload.reason)}`};
    case 'run.command.result':
      return applyRunCommandResult(state, event);
    case 'run.launch.failed':
      return rejectUnstartedSubmission(state, event.requestId,
        'Java 已接受请求，但 Runtime 启动失败；不会自动重放');
    case 'run.started':
      return updateCurrentRun(state, event, run => ({
        ...run,
        runId: event.runId,
        status: 'running',
      }), event.runId, 'running');
    case 'model.turn.started':
      return updateCurrentRun(state, event, run => ({
        ...run,
        status: 'running',
        modelProgress: {
          turn: Number(event.payload.turn),
          phase: 'thinking',
          providerInputTokens: run.modelProgress?.providerInputTokens ?? 0,
          providerOutputTokens: run.modelProgress?.providerOutputTokens ?? 0,
          providerTotalTokens: run.modelProgress?.providerTotalTokens ?? 0,
          usageReportedTurns: run.modelProgress?.usageReportedTurns ?? 0,
          usageMissingTurns: run.modelProgress?.usageMissingTurns ?? 0,
          contextUsedTokens: run.modelProgress?.contextUsedTokens,
          contextMaximumInputTokens: run.modelProgress?.contextMaximumInputTokens,
          contextEstimateKind: run.modelProgress?.contextEstimateKind,
        },
      }));
    case 'model.retry.attempt.started':
      return updateCurrentRun(state, event, run => ({
        ...run,
        status: Number(event.payload.attempt) > 1 ? 'retrying' : 'running',
        modelProgress: run.modelProgress === undefined ? undefined : {
          ...run.modelProgress,
          retryAttempt: Number(event.payload.attempt),
          retryMaxAttempts: Number(event.payload.maxAttempts),
          retryWaitMillis: undefined,
          retryCategory: undefined,
        },
      }));
    case 'model.retry.scheduled':
      return updateCurrentRun(state, event, run => ({
        ...run,
        status: 'retrying',
        modelProgress: run.modelProgress === undefined ? undefined : {
          ...run.modelProgress,
          retryAttempt: Number(event.payload.nextAttempt),
          retryMaxAttempts: Number(event.payload.maxAttempts),
          retryWaitMillis: Number(event.payload.waitMillis),
          retryCategory: String(event.payload.category) as ModelFailureCategory,
        },
      }));
    case 'model.turn.completed':
      return updateCurrentRun(state, event, run => ({
        ...run,
        modelProgress: completedModelProgress(run.modelProgress, event.payload),
      }));
    case 'model.text.delta':
      return updateCurrentRun(state, event, run => ({
        ...run,
        text: run.text + String(event.payload.text),
        modelProgress: run.modelProgress === undefined
          ? undefined : {...run.modelProgress, phase: 'responding'},
      }));
    case 'plan.proposed':
      return updateCurrentRun(state, event, run => ({
        ...run,
        planProposal: {
          planId: String(event.payload.planId),
          status: 'awaiting_approval',
          objective: String(event.payload.objective),
          workspaceDigest: String(event.payload.workspaceDigest),
          steps: (event.payload.steps as readonly Readonly<Record<string, unknown>>[]).map(step => ({
            ordinal: Number(step.ordinal),
            title: String(step.title),
            detail: String(step.detail),
          })),
        },
      }));
    case 'plan.review.requested':
      return updateCurrentRun(state, event, run => ({
        ...run,
        planReview: {
          planId: String(event.payload.planId),
          status: 'awaiting_approval',
          revision: Number(event.payload.revision),
          contentDigest: String(event.payload.contentDigest),
          markdown: String(event.payload.markdown),
          workspaceDigest: String(event.payload.workspaceDigest),
          originalPermissionMode: String(event.payload.originalPermissionMode) as 'default' | 'accept_edits',
          suggestedContextPolicy: String(event.payload.suggestedContextPolicy) as 'keep' | 'clear',
        },
      }));
    case 'question.requested':
      return updateCurrentRun(state, event, run => ({
        ...run,
        pendingQuestion: {
          callId: String(event.payload.callId),
          question: String(event.payload.question),
          options: (event.payload.options as readonly Readonly<Record<string, unknown>>[]).map(option => ({
            optionId: String(option.optionId), label: String(option.label),
            description: String(option.description),
          })),
          submitted: false,
        },
      }));
    case 'approval.requested':
      return updateCurrentRun(state, event, run => ({
        ...run,
        pendingApproval: {
          approvalId: String(event.payload.approvalId),
          ordinal: Number(event.payload.ordinal),
          toolName: String(event.payload.toolName),
          effect: event.payload.effect as ApprovalView['effect'],
          target: optionalText(event.payload.target),
          operation: approvalOperation(event.payload.operation),
          removedLines: optionalNonNegativeInteger(event.payload.removedLines),
          addedLines: optionalNonNegativeInteger(event.payload.addedLines),
          command: optionalText(event.payload.command),
          shell: approvalShell(event.payload.shell),
          workingDirectory: optionalText(event.payload.workingDirectory),
          destination: approvalDestination(event.payload.destination),
          query: optionalText(event.payload.query),
          submitted: false,
        },
      }));
    case 'tool.started':
      return updateCurrentRun(state, event, run => ({
        ...run,
        tools: upsertStartedTool(run.tools, event),
        modelProgress: run.modelProgress === undefined
          ? undefined : {...run.modelProgress, phase: 'preparing_tools'},
      }));
    case 'tool.completed':
    case 'tool.failed':
      return updateCurrentRun(state, event, run => ({
        ...run,
        tools: upsertFinishedTool(run.tools, event),
        pendingApproval: run.pendingApproval?.ordinal === Number(event.payload.ordinal)
          ? undefined : run.pendingApproval,
        pendingQuestion: event.payload.toolName === 'ask_plan_question'
          ? undefined : run.pendingQuestion,
      }));
    case 'tool.output':
      return updateCurrentRun(state, event, run => ({
        ...run,
        tools: appendToolOutput(run.tools, event),
        toolDetailOrdinal: run.toolDetailOrdinal ?? Number(event.payload.ordinal),
        toolDetailExpanded: run.toolDetailExpanded ?? false,
      }));
    case 'run.completed':
      return finishRun(state, event, 'completed');
    case 'run.cancelled':
      return finishRun(state, event, 'cancelled');
    case 'run.failed':
      return finishRun(state, event, 'failed');
    case 'checkpoint.listed': {
      const checkpoints = checkpointList(event.payload);
      const selection = checkpoints.some(item => item.checkpointId === state.selectedCheckpointId)
        ? state.selectedCheckpointId
        : checkpoints[0]?.checkpointId;
      return {
        ...state,
        checkpoints,
        checkpointPanelOpen: true,
        selectedCheckpointId: selection,
        checkpointDiff: selection === state.selectedCheckpointId
          ? state.checkpointDiff : undefined,
        pendingUndoCheckpointId: undefined,
        notice: checkpointListNotice(checkpoints),
      };
    }
    case 'checkpoint.diffed':
      return {
        ...state,
        selectedCheckpointId: String(event.payload.checkpointId),
        checkpointDiff: checkpointDiffView(event.payload),
        checkpointUndo: undefined,
        notice: undefined,
      };
    case 'checkpoint.undone':
      return {
        ...state,
        checkpoints: state.checkpoints.map(item =>
          item.checkpointId === event.payload.checkpointId
            ? {...item, phase: 'undone', undoable: false}
            : item),
        checkpointUndo: checkpointUndoView(event.payload),
        pendingUndoCheckpointId: undefined,
        notice: undefined,
      };
    case 'session.command.result':
      return applySessionCommandResult(state, event);
    case 'plan.execution.failed':
      return {
        ...state,
        notice: `计划执行失败（${String(event.payload.stopReason)}），不会自动重放；可通过显式恢复重新处理`,
      };
    case 'plan.verification.correction':
      return {
        ...state,
        notice: `计划证据校验失败，正在同一 Run 内纠正（${String(event.payload.attempt)}/${String(event.payload.maxAttempts)}）；不会自动重放既有副作用`,
      };
    case 'plan.verification.required':
      return annotatePlanVerification(state, event,
        `计划尚未完成：需要验证 ${String(event.payload.blockingRequirementId ?? 'required-evidence-not-declared')}（${String(event.payload.satisfiedEvidence)}/${String(event.payload.requiredEvidence)}）`);
    case 'plan.verification.completed':
      return annotatePlanVerification(state, event,
        `计划证据已验证（${String(event.payload.satisfiedEvidence)}/${String(event.payload.requiredEvidence)}）`);
    case 'provider.control.result':
      return state;
    case 'plan.feedback.accepted':
    case 'plan.execution.accepted':
    case 'plan.review.rejected':
      return settlePlanReview(state, String(event.payload.planId));
    case 'file.suggestions':
      return state;
    case 'steering.queued':
      return {
        ...state,
        steeringQueueDepth: Number(event.payload.queueDepth),
        notice: `补充消息已排队（${event.payload.queueDepth}/100）`,
      };
    case 'steering.discarded': {
      const rejected = rejectUnstartedSubmission(
        state,
        event.requestId,
        steeringDiscardedNotice(event.payload.reason),
      );
      return {
        ...rejected,
        steeringQueueDepth: Math.max(0, (state.steeringQueueDepth ?? 0) - 1),
        notice: steeringDiscardedNotice(event.payload.reason),
      };
    }
    case 'protocol.error':
      return {
        ...state,
        notice: safeProtocolMessage(event.payload),
      };
  }
}

function applyRunCommandResult(state: TuiState, event: ProtocolEvent): TuiState {
  const disposition = event.payload.disposition;
  const index = state.runs.findLastIndex(run => run.requestId === event.requestId);
  if (index < 0) return ignoredRunEvent(state, event);
  const run = state.runs[index];
  if (run === undefined || run.runId !== undefined || run.status !== 'submitting') {
    return ignoredRunEvent(state, event);
  }
  if (disposition === 'rejected') {
    return rejectUnstartedSubmission(
      state,
      event.requestId,
      `Java 拒绝请求：${String(event.payload.code)}`,
    );
  }
  const status = disposition === 'queued' ? 'queued' as const : 'accepted' as const;
  const runs = [...state.runs];
  runs[index] = {...run, status};
  return {
    ...state,
    runs,
    phase: state.activeRunId === undefined ? 'accepted' : state.phase,
    steeringQueueDepth: disposition === 'queued'
      ? Number(event.payload.queueDepth)
      : state.steeringQueueDepth,
    notice: disposition === 'queued'
      ? `请求已排队（${String(event.payload.queueDepth)}/100）`
      : state.notice,
  };
}

function rejectUnstartedSubmission(state: TuiState, requestId: string, message: string): TuiState {
  const rejected = state.runs.find(run => run.requestId === requestId);
  if (rejected === undefined || rejected.runId !== undefined) {
    return {...state, notice: message};
  }
  return {
    ...state,
    phase: state.activeRunId === undefined ? 'ready' : state.phase,
    runs: state.runs.filter(run => run.requestId !== requestId),
    notice: message,
  };
}

function isActiveRunStatus(status: RunStatus): boolean {
  return status === 'submitting' || status === 'accepted' || status === 'queued'
    || status === 'running' || status === 'retrying';
}

function isStartedRun(run: RunView): boolean {
  return run.runId !== undefined;
}

function applySessionCommandResult(state: TuiState, event: ProtocolEvent): TuiState {
  if (
    event.payload.intent === 'resume'
    && event.payload.status === 'succeeded'
    && typeof event.payload.result === 'object'
    && event.payload.result !== null
    && !Array.isArray(event.payload.result)
  ) {
    const result = event.payload.result as Readonly<Record<string, unknown>>;
    if (
      typeof result.previousSessionId === 'string'
      && result.previousSessionId === state.sessionId
      && typeof result.resumedSessionId === 'string'
      && event.sessionId === result.resumedSessionId
    ) {
      return {...state, sessionId: result.resumedSessionId, steeringQueueDepth: 0};
    }
  }
  return state;
}

function steeringDiscardedNotice(reason: unknown): string {
  switch (reason) {
    case 'clear': return '已清除一条未发送补充消息';
    case 'cancelled': return '当前 Run 取消，已丢弃一条未发送补充消息';
    case 'session_switch': return '会话已切换，已丢弃一条未发送补充消息';
    case 'shutdown': return '连接关闭，已丢弃一条未发送补充消息';
    default: return '未发送补充消息已丢弃';
  }
}

function checkpointList(
  payload: Readonly<Record<string, unknown>>,
): readonly CheckpointView[] {
  return (payload.checkpoints as readonly Readonly<Record<string, unknown>>[]).map(item => ({
    checkpointId: String(item.checkpointId),
    callId: String(item.callId),
    toolName: String(item.toolName),
    target: String(item.target),
    existedBefore: item.existedBefore === true,
    phase: item.phase as CheckpointPhase,
    undoable: item.undoable === true,
  }));
}

function checkpointDiffView(
  payload: Readonly<Record<string, unknown>>,
): CheckpointDiffView {
  return {
    checkpointId: String(payload.checkpointId),
    target: String(payload.target),
    status: payload.status as CheckpointDiffView['status'],
    text: String(payload.text),
    truncated: payload.truncated === true,
  };
}

function checkpointUndoView(
  payload: Readonly<Record<string, unknown>>,
): CheckpointUndoView {
  return {
    checkpointId: String(payload.checkpointId),
    target: String(payload.target),
    status: payload.status as CheckpointUndoView['status'],
    message: String(payload.message),
  };
}

function checkpointListNotice(checkpoints: readonly CheckpointView[]): string {
  const uncertain = checkpoints.filter(item => !item.undoable
    && item.phase !== 'undone').length;
  return checkpoints.length === 0
    ? '当前 Session 没有 Checkpoint'
    : `Checkpoint：${checkpoints.length} 个，${uncertain} 个不可 Undo/需检查`;
}

function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function approvalOperation(
  value: unknown,
): ApprovalView['operation'] {
  return value === 'modify' || value === 'create' ? value : undefined;
}

function approvalShell(value: unknown): ApprovalView['shell'] {
  return value === 'powershell' || value === 'sh' ? value : undefined;
}

function approvalDestination(value: unknown): ApprovalView['destination'] {
  return value === 'configured_web_search_provider' ? value : undefined;
}

function optionalNonNegativeInteger(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number
    : undefined;
}

function upsertStartedTool(
  tools: readonly ToolView[],
  event: ProtocolEvent,
): readonly ToolView[] {
  const ordinal = Number(event.payload.ordinal);
  const item: ToolView = {
    ordinal,
    name: String(event.payload.toolName),
    mode: searchMode(event.payload.mode),
    activity: optionalText(event.payload.activity),
    status: 'started',
    returnedCharacters: undefined,
    returnedItems: undefined,
    filteredItems: undefined,
    truncated: false,
    truncationReason: undefined,
    errorCode: undefined,
    failureCategory: undefined,
    retryable: undefined,
    argumentChangeRequired: false,
    strategyChangeRequired: false,
    exitCode: undefined,
    output: EMPTY_TOOL_OUTPUT,
  };
  return [...tools.filter(tool => tool.ordinal !== ordinal), item]
    .sort((left, right) => left.ordinal - right.ordinal);
}

function upsertFinishedTool(
  tools: readonly ToolView[],
  event: ProtocolEvent,
): readonly ToolView[] {
  const ordinal = Number(event.payload.ordinal);
  const rawStatus = String(event.payload.status);
  const status: ToolView['status'] = rawStatus === 'success'
    ? 'success'
    : rawStatus === 'denied' ? 'denied' : 'failed';
  const previous = tools.find(tool => tool.ordinal === ordinal);
  const item: ToolView = {
    ordinal,
    name: String(event.payload.toolName),
    mode: searchMode(event.payload.mode),
    activity: optionalText(event.payload.activity) ?? previous?.activity,
    status,
    returnedCharacters: safeCount(event.payload.returnedCharacters),
    returnedItems: safeCount(event.payload.returnedItems),
    filteredItems: safeCount(event.payload.filteredItems),
    truncated: event.payload.truncated === true,
    truncationReason: typeof event.payload.truncationReason === 'string'
      ? event.payload.truncationReason : undefined,
    errorCode: typeof event.payload.errorCode === 'string'
      ? event.payload.errorCode : undefined,
    failureCategory: typeof event.payload.failureCategory === 'string'
      ? event.payload.failureCategory : undefined,
    retryable: typeof event.payload.retryable === 'boolean'
      ? event.payload.retryable : undefined,
    argumentChangeRequired: event.payload.argumentChangeRequired === true,
    strategyChangeRequired: event.payload.strategyChangeRequired === true,
    exitCode: safeSignedCount(event.payload.exitCode),
    output: finalizeToolOutput(previous?.output ?? EMPTY_TOOL_OUTPUT),
  };
  return [...tools.filter(tool => tool.ordinal !== ordinal), item]
    .sort((left, right) => left.ordinal - right.ordinal);
}

function completedModelProgress(
  previous: ModelProgressView | undefined,
  payload: Readonly<Record<string, unknown>>,
): ModelProgressView {
  const usage = isRecord(payload.usage) ? payload.usage : undefined;
  const context = isRecord(payload.context) ? payload.context : undefined;
  const hasUsage = usage !== undefined;
  const finishReason = String(payload.finishReason);
  return {
    turn: Number(payload.turn),
    phase: finishReason === 'tool_calls' ? 'preparing_tools' : 'responding',
    providerInputTokens: (previous?.providerInputTokens ?? 0)
      + (hasUsage ? Number(usage.inputTokens) : 0),
    providerOutputTokens: (previous?.providerOutputTokens ?? 0)
      + (hasUsage ? Number(usage.outputTokens) : 0),
    providerTotalTokens: (previous?.providerTotalTokens ?? 0)
      + (hasUsage ? Number(usage.totalTokens) : 0),
    usageReportedTurns: (previous?.usageReportedTurns ?? 0) + (hasUsage ? 1 : 0),
    usageMissingTurns: (previous?.usageMissingTurns ?? 0) + (hasUsage ? 0 : 1),
    contextUsedTokens: context === undefined ? previous?.contextUsedTokens : Number(context.usedTokens),
    contextMaximumInputTokens: context === undefined
      ? previous?.contextMaximumInputTokens : Number(context.maximumInputTokens),
    contextEstimateKind: context === undefined
      ? previous?.contextEstimateKind : context.estimateKind as 'estimated' | 'exact',
  };
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function updateToolDetail(state: TuiState, action: 'next' | 'toggle'): TuiState {
  const activeIndex = state.runs.findIndex(run =>
    run.runId === state.activeRunId && isActiveRunStatus(run.status));
  const activeRun = state.runs[activeIndex];
  if (activeIndex >= 0 && activeRun !== undefined) {
    const candidates = outputToolOrdinals(activeRun);
    if (candidates.length === 0) return state;
    const currentIndex = candidates.indexOf(activeRun.toolDetailOrdinal ?? candidates[0]!);
    const ordinal = action === 'next'
      ? candidates[(Math.max(0, currentIndex) + 1) % candidates.length]
      : (activeRun.toolDetailOrdinal ?? candidates[0]);
    const runs = [...state.runs];
    runs[activeIndex] = {
      ...activeRun,
      toolDetailOrdinal: ordinal,
      toolDetailExpanded: action === 'toggle'
        ? !(activeRun.toolDetailExpanded ?? false)
        : false,
    };
    return {...state, historicalToolDetailOpen: false, runs};
  }

  const selectedRun = state.runs.find(run =>
    run.runId === state.historicalToolDetailRunId
      && !isActiveRunStatus(run.status)
      && outputToolOrdinals(run).length > 0)
    ?? state.runs.findLast(run =>
      !isActiveRunStatus(run.status) && outputToolOrdinals(run).length > 0);
  if (selectedRun === undefined || selectedRun.runId === undefined) return state;
  const candidates = outputToolOrdinals(selectedRun);
  if (action === 'toggle') {
    if (state.historicalToolDetailOpen === true) {
      return {...state, historicalToolDetailOpen: false};
    }
    return {
      ...state,
      historicalToolDetailRunId: selectedRun.runId,
      historicalToolDetailOrdinal:
        state.historicalToolDetailOrdinal !== undefined
          && candidates.includes(state.historicalToolDetailOrdinal)
          ? state.historicalToolDetailOrdinal
          : candidates[0],
      historicalToolDetailOpen: true,
    };
  }
  if (state.historicalToolDetailOpen !== true) return state;
  const currentIndex = candidates.indexOf(
    state.historicalToolDetailOrdinal ?? candidates[0]!,
  );
  return {
    ...state,
    historicalToolDetailRunId: selectedRun.runId,
    historicalToolDetailOrdinal:
      candidates[(Math.max(0, currentIndex) + 1) % candidates.length],
  };
}

function outputToolOrdinals(run: RunView): readonly number[] {
  return run.tools
    .filter(tool => tool.output.lines.length > 0)
    .map(tool => tool.ordinal);
}

function appendToolOutput(
  tools: readonly ToolView[],
  event: ProtocolEvent,
): readonly ToolView[] {
  const ordinal = Number(event.payload.ordinal);
  const current = tools.find(tool => tool.ordinal === ordinal);
  if (current === undefined) {
    return tools;
  }
  const output = appendOutputChunk(
    current.output,
    event.payload.stream as 'stdout' | 'stderr',
    String(event.payload.text),
  );
  return tools.map(tool => tool.ordinal === ordinal ? {...tool, output} : tool);
}

function annotatePlanVerification(
  state: TuiState,
  event: ProtocolEvent,
  message: string,
): TuiState {
  const index = state.runs.findLastIndex(run => run.requestId === event.requestId);
  if (index < 0) return state;
  const run = state.runs[index];
  if (run === undefined) return state;
  const runs = [...state.runs];
  runs[index] = {...run, planVerification: message, awaitingPlanVerification: false};
  return {...state, runs};
}

function settlePlanReview(state: TuiState, planId: string): TuiState {
  return {
    ...state,
    runs: state.runs.map(run => run.planReview?.planId === planId
      ? {...run, planReviewSettled: true} : run),
  };
}

function finishRun(
  state: TuiState,
  event: ProtocolEvent,
  status: 'completed' | 'cancelled' | 'failed',
): TuiState {
  const index = associatedRunIndex(state, event);
  if (index < 0) {
    return ignoredRunEvent(state, event);
  }
  const run = state.runs[index];
  if (run === undefined || !isActiveRunStatus(run.status) || run.runId === undefined) {
    return ignoredRunEvent(state, event);
  }
  const runs = [...state.runs];
  const finalText = terminalText(event.payload.finalText);
  runs[index] = {
    ...run,
    text: run.text.length === 0 && finalText !== undefined ? finalText : run.text,
    status,
    pendingApproval: undefined,
    stopReason: terminalText(event.payload.stopReason),
    modelFailure: modelFailureView(event.payload.modelFailure),
    modelTurns: terminalCount(event.payload.modelTurns),
    toolCalls: terminalCount(event.payload.toolCalls),
    awaitingPlanVerification: status === 'completed' ? run.awaitingPlanVerification : false,
  };
  return {
    ...state,
    runs,
    phase: state.activeRunId === event.runId ? 'ready' : state.phase,
    activeRunId: state.activeRunId === event.runId ? undefined : state.activeRunId,
  };
}

function modelFailureView(value: unknown): ModelFailureView | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined;
  }
  const failure = value as Record<string, unknown>;
  return {
    category: failure.category as ModelFailureCategory,
    statusClass: failure.statusClass === '4xx' || failure.statusClass === '5xx'
      ? failure.statusClass : undefined,
    attempts: Number(failure.attempts),
    receivedOutput: failure.receivedOutput === true,
  };
}

function safeCount(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number : undefined;
}

function safeSignedCount(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= -1
    ? value as number : undefined;
}

function searchMode(value: unknown): SearchMode | undefined {
  return value === 'content' || value === 'files' || value === 'count'
    ? value : undefined;
}

function terminalText(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function terminalCount(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number : undefined;
}

function updateCurrentRun(
  state: TuiState,
  event: ProtocolEvent,
  transform: (run: RunView) => RunView,
  activeRunId: string | undefined = state.activeRunId,
  phase: ClientPhase = state.phase,
): TuiState {
  const index = associatedRunIndex(state, event);
  if (index < 0) {
    return ignoredRunEvent(state, event);
  }
  const run = state.runs[index];
  if (run === undefined || !isActiveRunStatus(run.status)) {
    return ignoredRunEvent(state, event);
  }
  const runs = [...state.runs];
  runs[index] = transform(run);
  return {...state, runs, activeRunId, phase};
}

/**
 * 只接受由本地 submission 预建、且 sessionId/requestId/runId 均能证明归属的 Run 事件。
 * `run.started` 是唯一可绑定尚未设置 runId 的事件；未知、提前、迟到或错配事件
 * 不会改变 Transport 状态，也不会完成其他仍在运行的 Run。
 */
function associatedRunIndex(state: TuiState, event: ProtocolEvent): number {
  if (event.sessionId !== state.sessionId) return -1;
  const index = state.runs.findLastIndex(run => run.requestId === event.requestId);
  if (index < 0) return -1;
  const run = state.runs[index];
  if (run === undefined) return -1;
  if (event.type === 'run.started') {
    return (run.status === 'accepted' || run.status === 'queued')
      && (run.runId === undefined || run.runId === event.runId) ? index : -1;
  }
  return run.runId !== undefined && run.runId === event.runId ? index : -1;
}

function ignoredRunEvent(state: TuiState, event: ProtocolEvent): TuiState {
  return {
    ...state,
    notice: `已忽略无法关联的 ${event.type} 事件`,
  };
}

function safeProtocolMessage(payload: Readonly<Record<string, unknown>>): string {
  const code = typeof payload.code === 'string' ? payload.code : 'PROTOCOL_ERROR';
  return `Java 协议错误：${code}`;
}
