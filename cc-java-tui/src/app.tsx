import {useEffect, useReducer, useRef, useState} from 'react';
import {Box, Static, Text, useApp, useInput, usePaste, useWindowSize} from 'ink';
import stringWidth from 'string-width';
import {initialTuiState, orderedSessionTasks, reduceTuiState} from './state.js';
import type {ProtocolEvent} from './protocol.js';
import type {ProviderLoginRequest, ProviderLoginResult, RunHandshakeNotice} from './stdio-client.js';
import type {
  ApprovalView,
  CheckpointPhase,
  CheckpointView,
  ModelFailureView,
  RunView,
  SessionTaskStatus,
  TuiAction,
} from './state.js';
import {AssistantMarkdown} from './assistant-markdown.js';
import {ToolActivityGroup} from './tool-activity.js';
import {HistoricalToolDetail, ToolDetail} from './tool-detail.js';
import {
  activeFileMention,
  boundedFileSuggestions,
  fileMentionEnabled,
  type ActiveFileMention,
} from './file-mention.js';
import {
  parseSlashCommand,
  renderSlashResult,
  slashCommandUsage,
} from './slash-command.js';
import {
  initialPermissionPickerState,
  movePermissionPicker,
  PERMISSION_PICKER_ITEMS,
  selectedPermissionSelection,
  type PermissionPickerState,
} from './permission-picker.js';
import {
  APPROVAL_PICKER_ITEMS,
  initialApprovalPickerState,
  moveApprovalPicker,
  resetApprovalPicker,
  selectedApprovalDecision,
  type ApprovalPickerState,
} from './approval-picker.js';
import {
  createQuestionPicker,
  moveQuestionPicker,
  type QuestionPickerState,
} from './question-picker.js';
import {
  createPlanReviewPicker,
  movePlanReviewPicker,
  PLAN_REVIEW_PICKER_ITEMS,
  selectedPlanReviewDecision,
  togglePlanContextPolicy,
  type PlanReviewPickerState,
} from './plan-review-picker.js';
import {
  independentProviderControlId,
  isIndependentProviderControlResult,
} from './provider-control-id.js';
import {
  applyModelSetupResult,
  beginModelSetupLogin,
  beginModelSetup,
  completeModelSetupLogin,
  editModelSetup,
  enterModelSetup,
  escapeModelSetup,
  moveModelSetup,
  projectModelSetupCredential,
  type ModelSetupState,
} from './model-setup.js';
import {
  acceptPendingComposer,
  acceptSubmittedComposer,
  appendInput,
  beginPendingComposer,
  completionCandidates,
  createComposerState,
  pastePreviewAtCursor,
  projectComposer,
  reduceComposer,
  removeLastCodePoint,
  renderComposerViewport,
  restoreRejectedComposer,
  submittedComposerLabel,
  type ComposerAction,
  type ComposerLayout,
  type ComposerState,
} from './input-editor.js';
import {
  activitySpinnerFrame,
  classifyNotice,
  completionRegionRows,
  createPlanFeedbackDraft,
  editPlanFeedback,
  hasTrailingNewlineEscape,
  isInsertNewlineKey,
  noticeAppearance,
  planFeedbackCursorParts,
  shouldAcceptCompletionOnEnter,
  type PlanFeedbackDraft,
} from './interaction.js';

const PRODUCT_VERSION = '0.1.1';

type PublicPermissionSelection = 'PLAN' | 'ASK' | 'AUTO' | 'ADVANCED';

interface PlanFeedbackInputState {
  readonly review: PlanReviewPickerState;
  readonly draft: PlanFeedbackDraft;
}
type PlanEntryState = {
  readonly phase: 'query' | 'enter' | 'restore-after-start-failure';
  readonly commandId: string;
  readonly task: string | undefined;
  readonly composer: ComposerState | undefined;
  readonly originalSelection: PublicPermissionSelection | undefined;
};
type PlanDecisionState = {
  readonly phase: 'approve' | 'restore-for-execute' | 'reject-revise' | 'reject-exit' | 'restore-for-exit';
  readonly commandId: string;
  readonly planId: string;
  readonly workspaceDigest: string;
};
type PlanSessionState = {
  readonly originalSelection: PublicPermissionSelection;
};
type PendingDurablePlanDecision = {
  readonly requestId: string;
  readonly review: PlanReviewPickerState;
  readonly createsRun: boolean;
  readonly feedback: string | undefined;
};
type PendingDurablePlanRestore = {
  readonly commandId: string;
  readonly review: PlanReviewPickerState;
  readonly decision: 'APPROVE_AUTO' | 'APPROVE_USER' | 'REJECT';
};

const CODEJ_BANNER = [
  ' ██████  ██████  ██████  ███████     ██',
  '██      ██    ██ ██   ██ ██          ██',
  '██      ██    ██ ██   ██ █████       ██',
  '██      ██    ██ ██   ██ ██      ██  ██',
  ' ██████  ██████  ██████  ███████  ████',
] as const;

export interface AgentTuiProps {
  readonly client: AgentClient;
}

export interface AgentClient {
  onEvent(listener: (event: ProtocolEvent) => void): () => void;
  onFailure(listener: (message: string) => void): () => void;
  onRunHandshake?(listener: (notice: RunHandshakeNotice) => void): () => void;
  onExit(listener: () => void): () => void;
  initialize(): string;
  startRun(prompt: string): string;
  startPlan?(task: string): string;
  resolvePlanReview?(input: {
    readonly planId: string; readonly revision: number; readonly contentDigest: string;
    readonly workspaceDigest: string;
    readonly decision: 'APPROVE_AUTO' | 'APPROVE_USER' | 'CONTINUE_PLANNING' | 'REJECT';
    readonly contextPolicy: 'KEEP' | 'CLEAR'; readonly feedback: string;
  }): string;
  startPlanExecution?(planId: string, workspaceDigest: string): string;
  returnPlanFeedback?(planId: string, revision: number, contentDigest: string): string;
  invokeSkill?(name: string, arguments_: string): string;
  cancelRun(): string;
  resolveApproval(
    approvalId: string,
    decision: 'allow_once' | 'allow_session' | 'deny',
  ): string;
  resolveQuestion?(callId: string, optionId: string): string;
  listCheckpoints?(): string;
  checkpointDiff?(checkpointId: string): string;
  undoCheckpoint?(checkpointId: string, confirmed: boolean): string;
  inspectTask?(taskId: string): string;
  waitTask?(taskId: string, timeoutMillis: number): string;
  cancelTask?(taskId: string): string;
  keepTaskWorktree?(taskId: string): string;
  removeTaskWorktree?(taskId: string): string;
  sessionCommand?(commandId: string, intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume' | 'tasks' | 'plan-status' | 'plan' | 'plan-approve' | 'plan-reject' | 'plan-step-begin' | 'plan-step-complete' | 'plan-execute', arguments_: Readonly<Record<string, unknown>>): string;
  providerControl?(controlId: string, intent: 'providers.configure' | 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove', arguments_: Readonly<Record<string, unknown>>): string;
  providerLogin?(request: ProviderLoginRequest): Promise<ProviderLoginResult>;
  cancelProviderLogin?(): void;
  suggestFiles?(query: string): string;
  shutdown(): Promise<void>;
  terminate(): void;
}

export function renderProviderControlResult(
  intent: string, status: string, code: string, result: Readonly<Record<string, unknown>>,
): string {
  if (status !== 'succeeded') return `Provider 控制未执行：${providerControlError(code)}（${code}）`;
  if (intent === 'auth.list' && Array.isArray(result.profiles)) {
    const lines = result.profiles.flatMap(item => {
      if (typeof item !== 'object' || item === null || Array.isArray(item)) return [];
      const profile = item as Record<string, unknown>;
      const flags = [profile.providerDefault === true ? '默认' : '',
        typeof profile.lastProbeCode === 'string' ? `探测 ${profile.lastProbeCode}` : '']
        .filter(Boolean).join(' · ');
      return [`${String(profile.providerId)}/${String(profile.profileId)} · ${String(profile.authMethod)}/${String(profile.refKind)} · ${String(profile.localStatus)}${flags.length === 0 ? '' : ` · ${flags}`}`];
    });
    return ['Credential profiles', ...(lines.length === 0 ? ['（无）'] : lines)].join('\n');
  }
  if (intent === 'models.list' && Array.isArray(result.models)) {
    const lines = result.models.flatMap(item => typeof item === 'object' && item !== null && !Array.isArray(item)
      ? [`${String((item as Record<string, unknown>).providerId)}/${String((item as Record<string, unknown>).modelId)}${(item as Record<string, unknown>).providerDefault === true ? ' · 默认' : ''}`]
      : []);
    return ['Models', ...(lines.length === 0 ? ['（无）'] : lines)].join('\n');
  }
  if (intent === 'models.add') {
    return `本地模型已添加：${String(result.providerId)}/${String(result.modelId)}${result.setDefault === true ? ' · 已设为持久默认' : ''}`;
  }
  if (intent === 'models.remove') {
    return `本地模型已移除：${String(result.providerId)}/${String(result.modelId)}`;
  }
  if (intent === 'models.use') {
    return `下一 Run 模型：${String(result.providerId)}/${String(result.modelId)} · profile ${String(result.profileId)}${result.setDefault === true ? ' · 已设为持久默认' : ''}`;
  }
  if (intent === 'auth.probe') {
    return `认证探测：${String(result.providerId)}/${String(result.profileId)} · ${String(result.modelId)} · ${String(result.outcome)} · ${String(result.probedAt)}`;
  }
  if (intent === 'auth.logout') {
    return `本机 credential 已删除：${String(result.providerId)}/${String(result.profileId)}；Provider 侧 credential 未撤销`;
  }
  return 'Provider 控制已完成';
}

function providerControlError(code: string): string {
  const labels: Readonly<Record<string, string>> = {
    AUTH_PROFILE_REQUIRED: '需要先选择 credential profile',
    AUTH_PROFILE_UNKNOWN: 'credential profile 不存在',
    AUTH_SECRET_UNAVAILABLE: '本机 secret 不可用',
    AUTH_STORE_INSECURE: '本机 credential store 未通过安全检查',
    AUTH_STORE_LOCKED: '本机 credential store 正在被占用',
    AUTH_STORE_CORRUPT: '本机 credential store 已损坏',
    AUTH_PROBE_REJECTED: 'Provider 拒绝该 credential',
    AUTH_PROBE_RATE_LIMITED: 'Provider 对探测限流',
    AUTH_PROBE_UNSUPPORTED: '该 Provider 不支持安全探测',
    AUTH_PROBE_UNREACHABLE: '探测目标不可达或响应无效',
    AUTH_PROBE_TIMED_OUT: '认证探测超时',
    AUTH_CANCELLED: '操作已取消',
    AUTH_LOGOUT_DRAIN_FAILED: '活动 Run 未能在期限内停止，credential 未删除',
    AUTH_STORE_DELETE_FAILED: '运行资源已停止，但本机 credential 删除失败',
    MODEL_UNKNOWN: '模型不在本地 Provider 目录中',
    AUTH_TRANSACTION_CONFLICT: '当前有活动 Run 或本机状态已变化',
    INVALID_ARGUMENT: '参数无效',
  };
  return labels[code] ?? '请求被安全拒绝';
}
export {
  shouldAcceptCompletionOnEnter,
  classifyNotice,
  activitySpinnerFrame,
  stabilizeStreamingMarkdown,
  visibleToolOutputWindow,
} from './interaction.js';
export {
  appendInput,
  MAX_INPUT_CODE_POINTS as MAX_INPUT_CHARS,
} from './input-editor.js';

/**
 * S03 最小 React/Ink 终端 Surface。
 *
 * 组件只把键盘动作转换成命令并渲染 Reducer 投影；Java Headless 始终拥有 Session、
 * Run、Tool 与终态。当前只展示脱敏 Tool 摘要，不执行 Tool；审批仍属于 S04。
 */
export const TASK_COMPLETION_VISIBLE_MS = 5_000;

/** 安排完成态面板隐藏；取消函数用于手动聚焦或新 snapshot 到达时保留面板。 */
export function scheduleTaskPanelAutoHide(
  dispatch: (action: TuiAction) => void,
  delayMillis = TASK_COMPLETION_VISIBLE_MS,
): () => void {
  const timer = setTimeout(() => dispatch({type: 'task.panel.auto-hide'}), delayMillis);
  return () => clearTimeout(timer);
}

export function AgentTui({client}: AgentTuiProps) {
  const [state, dispatch] = useReducer(reduceTuiState, initialTuiState);
  const [composer, setComposer] = useState<ComposerState>(() => createComposerState(4));
  const [providerLoginActive, setProviderLoginActive] = useState(false);
  const [connectWizard, setConnectWizard] = useState<ModelSetupState | undefined>(undefined);
  const [permissionPicker, setPermissionPicker] = useState<PermissionPickerState | undefined>(undefined);
  const [approvalPicker, setApprovalPicker] = useState<ApprovalPickerState>(() => initialApprovalPickerState());
  const [planReviewPicker, setPlanReviewPicker] = useState<PlanReviewPickerState | undefined>(undefined);
  const [planFeedbackInput, setPlanFeedbackInput] = useState<PlanFeedbackInputState | undefined>(undefined);
  const [questionPicker, setQuestionPicker] = useState<QuestionPickerState | undefined>(undefined);
  const [activityTick, setActivityTick] = useState(0);
  const planReviewPickerRef = useRef<PlanReviewPickerState | undefined>(undefined);
  const allSessionTasksCompleted = state.taskBoard !== undefined
    && state.taskBoard.tasks.length > 0
    && state.taskBoard.tasks.every(task => task.status === 'COMPLETED');
  useEffect(() => {
    if (!state.taskPanelOpen || state.taskPanelFocused === true || !allSessionTasksCompleted) return undefined;
    return scheduleTaskPanelAutoHide(dispatch);
  }, [allSessionTasksCompleted, state.taskBoard?.boardRevision, state.taskPanelFocused, state.taskPanelOpen]);
  const planFeedbackInputRef = useRef<PlanFeedbackInputState | undefined>(undefined);
  const composerRef = useRef(composer);
  const permissionPickerSubmittedRef = useRef(false);
  const historySessionIdRef = useRef<string | undefined>(undefined);
  const pendingSubmissionsRef = useRef(new Map<string, {
    readonly composer: ComposerState;
    readonly label: string;
    status: 'awaiting_acceptance' | 'accepted';
  }>());
  const cancelPending = useRef(false);
  const transportFailureRef = useRef(false);
  const nextCommandNumber = useRef(1);
  const nextConnectGeneration = useRef(1);
  const pendingPlanEntryRef = useRef<PlanEntryState | undefined>(undefined);
  const pendingPlanDecisionRef = useRef<PlanDecisionState | undefined>(undefined);
  const pendingDurablePlanDecisionRef = useRef<PendingDurablePlanDecision | undefined>(undefined);
  const pendingDurablePlanRestoreRef = useRef<PendingDurablePlanRestore | undefined>(undefined);
  const planSessionRef = useRef<PlanSessionState | undefined>(undefined);
  const connectWizardRef = useRef<ModelSetupState | undefined>(undefined);
  const providerLoginActiveRef = useRef(false);
  const setupCredentialBytesRef = useRef<number[]>([]);
  const fileSuggestionRef = useRef<{
    readonly requestId: string;
    readonly query: string;
    readonly mention: ActiveFileMention;
  } | undefined>(undefined);
  const fileSuggestionTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const activeRun = state.runs.findLast(run => run.status === 'running' || run.status === 'retrying');
  const pendingApproval = activeRun?.pendingApproval;
  const pendingQuestion = activeRun?.pendingQuestion;
  const effectiveApprovalPicker = pendingApproval === undefined
    ? approvalPicker
    : resetApprovalPicker(approvalPicker, pendingApproval.approvalId);
  const pendingUndo = state.checkpoints.find(
    item => item.checkpointId === state.pendingUndoCheckpointId,
  );
  const selectedCheckpoint = state.checkpoints.find(
    item => item.checkpointId === state.selectedCheckpointId,
  );
  const checkpointSupported = client.listCheckpoints !== undefined
    && client.checkpointDiff !== undefined
    && client.undoCheckpoint !== undefined;
  const {exit} = useApp();
  const exitRef = useRef(exit);
  exitRef.current = exit;
  const {columns, rows} = useWindowSize();
  const composerLayout: ComposerLayout = {
    width: Math.max(1, columns - 6),
    height: Math.max(1, Math.min(8, rows - 6)),
  };
  const replaceComposer = (next: ComposerState) => {
    composerRef.current = next;
    setComposer(next);
  };
  const replaceConnectWizard = (next: ModelSetupState | undefined) => {
    connectWizardRef.current = next;
    setConnectWizard(next);
  };
  const replacePlanFeedbackInput = (next: PlanFeedbackInputState | undefined) => {
    planFeedbackInputRef.current = next;
    setPlanFeedbackInput(next);
  };
  const replacePlanReviewPicker = (next: PlanReviewPickerState | undefined) => {
    planReviewPickerRef.current = next;
    setPlanReviewPicker(next);
  };
  const applyComposer = (action: ComposerAction) => {
    const transition = reduceComposer(composerRef.current, action, composerLayout);
    replaceComposer(transition.state);
    return transition;
  };
  const sendIndependentProviderControl = (
    intent: 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove',
    arguments_: Readonly<Record<string, unknown>>,
  ) => {
    if (client.providerControl === undefined) return undefined;
    const controlId = independentProviderControlId(nextCommandNumber.current++, intent);
    client.providerControl(controlId, intent, arguments_);
    return controlId;
  };
  const submitDurableReview = (
    review: PlanReviewPickerState,
    decision: 'APPROVE_AUTO' | 'APPROVE_USER' | 'REJECT',
  ): boolean => {
    if (client.resolvePlanReview === undefined) return false;
    try {
      const requestId = client.resolvePlanReview({
        planId: review.planId,
        revision: review.revision,
        contentDigest: review.contentDigest,
        workspaceDigest: review.workspaceDigest,
        decision,
        contextPolicy: review.contextPolicy.toUpperCase() as 'KEEP' | 'CLEAR',
        feedback: '',
      });
      const createsRun = decision !== 'REJECT';
      pendingDurablePlanDecisionRef.current = {
        requestId, review, createsRun, feedback: undefined,
      };
      if (createsRun) {
        dispatch({
          type: 'run.submitted', requestId,
          prompt: decision === 'APPROVE_AUTO'
            ? `执行计划 ${review.planId}（自动审批）` : `执行计划 ${review.planId}`,
          awaitingPlanVerification: true,
        });
      }
      return true;
    } catch {
      pendingDurablePlanDecisionRef.current = undefined;
      return false;
    }
  };
  const acceptCurrentCompletion = () => {
    const current = composerRef.current;
    const selected = current.completionCandidates[current.completionIndex ?? 0];
    if (selected?.startsWith('@')) {
      const mention = activeFileMention(current);
      if (mention === undefined) {
        applyComposer({type: 'CloseCompletion'});
        return;
      }
      const replacement = reduceComposer(current, {
        type: 'ReplaceRange',
        startGrapheme: mention.startGrapheme,
        endGrapheme: mention.endGrapheme,
        text: selected,
      }, composerLayout);
      replaceComposer({...replacement.state, completionCandidates: [], completionIndex: undefined});
      fileSuggestionRef.current = undefined;
      return;
    }
    applyComposer({type: 'AcceptCompletion'});
  };

  useEffect(() => {
    const offEvent = client.onEvent(event => {
      if (event.type === 'initialized') {
        if (historySessionIdRef.current !== event.sessionId) {
          const switchingSession = historySessionIdRef.current !== undefined;
          historySessionIdRef.current = event.sessionId;
          if (switchingSession) replaceComposer(createComposerState(4));
      pendingSubmissionsRef.current.clear();
          pendingPlanEntryRef.current = undefined;
          pendingPlanDecisionRef.current = undefined;
          pendingDurablePlanDecisionRef.current = undefined;
          pendingDurablePlanRestoreRef.current = undefined;
          planSessionRef.current = undefined;
          replacePlanReviewPicker(undefined);
        }
        if (event.payload.modelConfigured === false && connectWizardRef.current === undefined) {
          setupCredentialBytesRef.current.fill(0); setupCredentialBytesRef.current.length = 0;
          replaceConnectWizard(beginModelSetup(nextConnectGeneration.current++, true));
        }
      }
      if (event.type === 'file.suggestions') {
        const pending = fileSuggestionRef.current;
        const mention = activeFileMention(composerRef.current);
        if (pending !== undefined
          && event.requestId === pending.requestId
          && event.payload.query === pending.query
          && mention !== undefined
          && mention.query === pending.query
          && mention.startGrapheme === pending.mention.startGrapheme) {
          const candidates = boundedFileSuggestions(event.payload.candidates as readonly string[]);
          const completion = reduceComposer(
            composerRef.current, {type: 'SetCompletions', candidates}, composerLayout,
          );
          replaceComposer(completion.state);
        }
      }
      if (event.type === 'provider.control.result') {
        const payload = event.payload;
        const currentWizard = connectWizardRef.current;
        const nextWizard = currentWizard === undefined ? undefined : applyModelSetupResult(currentWizard, {
          controlId: String(payload.controlId), intent: String(payload.intent), status: String(payload.status),
          code: String(payload.code), result: payload.result as Readonly<Record<string, unknown>>,
        });
        if (currentWizard !== undefined && nextWizard !== undefined && nextWizard !== currentWizard) {
          replaceConnectWizard(nextWizard);
        } else if (isIndependentProviderControlResult(String(payload.controlId), String(payload.intent))) {
          dispatch({type: 'slash.notice', message: renderProviderControlResult(
            String(payload.intent), String(payload.status), String(payload.code),
            payload.result as Readonly<Record<string, unknown>>,
          )});
          if (payload.intent === 'models.add' && payload.status === 'succeeded') {
            sendIndependentProviderControl('models.list', {});
          }
        }
      }
      if (event.type === 'session.command.result') {
        const payload = event.payload;
        const commandId = String(payload.commandId);
        const intent = String(payload.intent);
        const status = String(payload.status);
        const result = payload.result as Readonly<Record<string, unknown>>;
        let planTransitionHandled = false;
        const entry = pendingPlanEntryRef.current;
        if (entry !== undefined && commandId === entry.commandId && intent === 'permissions') {
          planTransitionHandled = true;
          if (entry.phase === 'query') {
            pendingPlanEntryRef.current = undefined;
            const queriedSelection = status === 'succeeded'
              ? publicPermissionSelection(result.effectiveSelection) : undefined;
            const originalSelection = queriedSelection === 'PLAN'
              && planSessionRef.current !== undefined
              ? planSessionRef.current.originalSelection
              : queriedSelection;
            if (originalSelection === undefined) {
              if (entry.composer !== undefined) {
                replaceComposer(restoreRejectedComposer(composerRef.current, entry.composer));
              }
              dispatch({type: 'slash.notice', message: '无法读取当前权限选择；未进入 Plan'});
            } else {
              const enterId = `tui-plan-${nextCommandNumber.current++}-enter`;
              pendingPlanEntryRef.current = {
                phase: 'enter', commandId: enterId, task: entry.task,
                composer: entry.composer, originalSelection,
              };
              client.sessionCommand?.(enterId, 'permissions', {selection: 'PLAN'});
            }
          } else if (entry.phase === 'enter') {
            pendingPlanEntryRef.current = undefined;
            if (status !== 'succeeded' || result.effectiveSelection !== 'PLAN'
              || entry.originalSelection === undefined) {
              if (entry.composer !== undefined) {
                replaceComposer(restoreRejectedComposer(composerRef.current, entry.composer));
              }
              dispatch({type: 'slash.notice', message: 'Plan 模式未能发布；未启动规划'});
            } else {
              planSessionRef.current = {originalSelection: entry.originalSelection};
              if (entry.task === undefined) {
                client.sessionCommand?.(`tui-plan-${nextCommandNumber.current++}-status`, 'plan-status', {});
              } else if (client.startPlan === undefined) {
                if (entry.composer !== undefined) {
                  replaceComposer(restoreRejectedComposer(composerRef.current, entry.composer));
                }
                if (!restoreAfterPlanStartFailure(client, nextCommandNumber, pendingPlanEntryRef,
                  entry.task, entry.originalSelection, undefined)) {
                  dispatch({type: 'slash.notice', message: 'Plan 任务未启动且恢复命令未被接受；请用 /permissions query 检查当前选择'});
                }
              } else {
                try {
                  const requestId = client.startPlan(entry.task);
                  const label = entry.composer === undefined
                    ? `/plan ${entry.task}` : submittedComposerLabel(entry.composer);
                  if (entry.composer !== undefined) {
                    pendingSubmissionsRef.current.set(requestId, {
                      composer: entry.composer, label, status: 'awaiting_acceptance',
                    });
                  }
                  dispatch({type: 'run.submitted', requestId, prompt: label});
                } catch {
                  if (entry.composer !== undefined) {
                    replaceComposer(restoreRejectedComposer(composerRef.current, entry.composer));
                  }
                  if (!restoreAfterPlanStartFailure(client, nextCommandNumber, pendingPlanEntryRef,
                    entry.task, entry.originalSelection, undefined)) {
                    dispatch({type: 'slash.notice', message: 'Plan 任务未启动且恢复命令未被接受；请用 /permissions query 检查当前选择'});
                  }
                }
              }
            }
          } else {
            pendingPlanEntryRef.current = undefined;
            if (status === 'succeeded'
              && result.effectiveSelection === executionSelection(entry.originalSelection)) {
              planSessionRef.current = undefined;
              dispatch({type: 'slash.notice', message: 'Plan 任务未启动；已恢复进入前权限选择'});
            } else {
              dispatch({type: 'slash.notice', message: 'Plan 任务未启动且权限恢复失败；当前可能仍为 Plan 模式'});
            }
          }
        }
        const durableRestore = pendingDurablePlanRestoreRef.current;
        if (durableRestore !== undefined && commandId === durableRestore.commandId
          && intent === 'permissions') {
          planTransitionHandled = true;
          pendingDurablePlanRestoreRef.current = undefined;
          const expectedSelection = executionSelection(planSessionRef.current?.originalSelection);
          if (status === 'succeeded' && result.effectiveSelection === expectedSelection
            && submitDurableReview(durableRestore.review, durableRestore.decision)) {
            dispatch({type: 'slash.notice', message: durableRestore.decision === 'REJECT'
              ? '已恢复进入 Plan 前的权限，正在拒绝计划'
              : '已恢复进入 Plan 前的权限，正在提交计划执行'});
          } else {
            replacePlanReviewPicker({...durableRestore.review, submitted: false});
            dispatch({type: 'slash.notice', message: '未能恢复进入 Plan 前的权限；计划仍等待决定'});
          }
        }
        const decision = pendingPlanDecisionRef.current;
        if (decision !== undefined && commandId === decision.commandId) {
          planTransitionHandled = true;
          const boundResult = result.planId === decision.planId
            && result.workspaceDigest === decision.workspaceDigest;
          if (decision.phase === 'approve' && intent === 'plan-approve') {
            if (status === 'succeeded' && boundResult) {
              const restoreId = `tui-plan-${nextCommandNumber.current++}-restore-execute`;
              pendingPlanDecisionRef.current = {...decision, phase: 'restore-for-execute', commandId: restoreId};
              client.sessionCommand?.(restoreId, 'permissions',
                permissionRestoreArguments(planSessionRef.current?.originalSelection));
            } else {
              pendingPlanDecisionRef.current = undefined;
              const picker = planReviewPickerRef.current;
              if (picker?.planId === decision.planId && picker.workspaceDigest === decision.workspaceDigest) {
                replacePlanReviewPicker({...picker, submitted: false});
              }
            }
          } else if (decision.phase === 'restore-for-execute' && intent === 'permissions') {
            if (status === 'succeeded'
              && result.effectiveSelection === executionSelection(planSessionRef.current?.originalSelection)) {
              try {
                if (client.startPlanExecution === undefined) throw new Error('unsupported');
                const requestId = client.startPlanExecution(decision.planId, decision.workspaceDigest);
                pendingPlanDecisionRef.current = undefined;
                replacePlanReviewPicker(undefined);
                planSessionRef.current = undefined;
                dispatch({type: 'run.submitted', requestId, prompt: '执行已批准计划'});
                dispatch({type: 'slash.notice', message: '计划已批准，开始实际执行'});
              } catch {
                pendingPlanDecisionRef.current = undefined;
                const picker = planReviewPickerRef.current;
                if (picker?.planId === decision.planId && picker.workspaceDigest === decision.workspaceDigest) {
                  replacePlanReviewPicker({...picker, submitted: false});
                }
                dispatch({type: 'slash.notice', message: '计划执行未能启动；Plan 已保留，可用 /plan 查看'});
              }
            } else {
              pendingPlanDecisionRef.current = undefined;
              const picker = planReviewPickerRef.current;
              if (picker?.planId === decision.planId && picker.workspaceDigest === decision.workspaceDigest) {
                replacePlanReviewPicker({...picker, submitted: false});
              }
              dispatch({type: 'slash.notice', message: '权限恢复失败；Plan 保持已批准且未执行'});
            }
          } else if (decision.phase === 'reject-revise' && intent === 'plan-reject') {
            pendingPlanDecisionRef.current = undefined;
            if (status === 'succeeded' && boundResult) {
              replacePlanReviewPicker(undefined);
              dispatch({type: 'slash.notice', message: '计划未执行；保持 Plan 模式，可用 /plan <自然语言任务> 继续修改'});
            } else {
              const picker = planReviewPickerRef.current;
              if (picker?.planId === decision.planId) replacePlanReviewPicker({...picker, submitted: false});
              dispatch({type: 'slash.notice', message: '计划修改请求未完成；当前 Plan 已保留，可用 /plan 查看'});
            }
          } else if (decision.phase === 'reject-exit' && intent === 'plan-reject') {
            if (status === 'succeeded' && boundResult) {
              const restoreId = `tui-plan-${nextCommandNumber.current++}-restore-exit`;
              pendingPlanDecisionRef.current = {...decision, phase: 'restore-for-exit', commandId: restoreId};
              client.sessionCommand?.(restoreId, 'permissions',
                permissionRestoreArguments(planSessionRef.current?.originalSelection));
              replacePlanReviewPicker(undefined);
            } else {
              pendingPlanDecisionRef.current = undefined;
              const picker = planReviewPickerRef.current;
              if (picker?.planId === decision.planId) replacePlanReviewPicker({...picker, submitted: false});
            }
          } else if (decision.phase === 'restore-for-exit' && intent === 'permissions') {
            pendingPlanDecisionRef.current = undefined;
            if (status === 'succeeded'
              && result.effectiveSelection === executionSelection(planSessionRef.current?.originalSelection)) {
              planSessionRef.current = undefined;
              dispatch({type: 'slash.notice', message: '计划已拒绝，未执行任何步骤'});
            } else {
              dispatch({type: 'slash.notice', message: '计划已拒绝，但权限恢复未确认；请用 /permissions query 检查当前选择'});
            }
          }
        }
        if (intent === 'resume' && status === 'succeeded') {
          historySessionIdRef.current = event.sessionId;
          fileSuggestionRef.current = undefined;
          replaceComposer(createComposerState(4));
      pendingSubmissionsRef.current.clear();
          pendingPlanEntryRef.current = undefined;
          pendingPlanDecisionRef.current = undefined;
          pendingDurablePlanDecisionRef.current = undefined;
          pendingDurablePlanRestoreRef.current = undefined;
          planSessionRef.current = undefined;
          replacePlanReviewPicker(undefined);
        }
        if (!planTransitionHandled) {
          dispatch({
            type: 'slash.notice',
            message: renderSlashResult(
              intent, status, String(payload.code), result,
            ),
          });
          if ((intent === 'plan-status' || intent === 'plan') && status === 'succeeded'
            && isReviewablePlanStatus(result.status)) {
            const planId = String(result.planId);
            const workspaceDigest = String(result.workspaceDigest);
            dispatch({
              type: 'plan.status.received',
              requestId: `plan-status-${planId}`,
              proposal: {
                planId,
                status: 'awaiting_approval',
                objective: String(result.objective),
                workspaceDigest,
                steps: (result.steps as readonly Readonly<Record<string, unknown>>[]).map(step => ({
                  ordinal: Number(step.ordinal), title: String(step.title), detail: String(step.detail),
                })),
              },
            });
            replacePlanReviewPicker(createPlanReviewPicker(planId, workspaceDigest));
          }
        }
      }
      if (event.type === 'plan.proposed') {
        pendingPlanDecisionRef.current = undefined;
        replacePlanReviewPicker(createPlanReviewPicker(
          String(event.payload.planId), String(event.payload.workspaceDigest),
        ));
      }
      if (event.type === 'plan.review.requested') {
        pendingPlanDecisionRef.current = undefined;
        replacePlanFeedbackInput(undefined);
        replacePlanReviewPicker(createPlanReviewPicker(
          String(event.payload.planId), Number(event.payload.revision), String(event.payload.contentDigest),
          String(event.payload.workspaceDigest), String(event.payload.suggestedContextPolicy) as 'keep' | 'clear',
        ));
      }
      if (event.type === 'plan.feedback.accepted') {
        replacePlanFeedbackInput(undefined);
        replacePlanReviewPicker(undefined);
        dispatch({type: 'slash.notice', message: '反馈已接受，正在为同一计划启动新的规划回合'});
      }
      if (event.type === 'plan.execution.accepted') {
        const pending = pendingDurablePlanDecisionRef.current;
        replacePlanReviewPicker(undefined);
        planSessionRef.current = undefined;
        if (pending?.requestId === event.requestId) {
          pendingDurablePlanDecisionRef.current = undefined;
          dispatch({type: 'slash.notice', message: '计划已批准，正在启动执行'});
        }
      }
      if (event.type === 'plan.execution.failed') {
        dispatch({type: 'slash.notice', message: `计划执行失败（${String(event.payload.stopReason)}），不会自动重放；可通过显式恢复重新处理`});
      }
      if (event.type === 'plan.verification.correction') {
        dispatch({type: 'slash.notice', message: `计划证据校验失败，正在同一 Run 内纠正（${String(event.payload.attempt)}/${String(event.payload.maxAttempts)}）；不会自动重放既有副作用`});
      }
      if (event.type === 'plan.verification.required') {
        dispatch({type: 'slash.notice', message: `计划尚未完成：需要验证 ${String(event.payload.blockingRequirementId ?? 'required-evidence-not-declared')}（${String(event.payload.satisfiedEvidence)}/${String(event.payload.requiredEvidence)}）`});
      }
      if (event.type === 'plan.verification.completed') {
        dispatch({type: 'slash.notice', message: `计划证据已验证（${String(event.payload.satisfiedEvidence)}/${String(event.payload.requiredEvidence)}）`});
      }
      if (event.type === 'plan.review.rejected') {
        if (pendingDurablePlanDecisionRef.current?.requestId === event.requestId) {
          pendingDurablePlanDecisionRef.current = undefined;
        }
        replacePlanReviewPicker(undefined);
        planSessionRef.current = undefined;
        dispatch({type: 'slash.notice', message: '计划已拒绝，未执行任何步骤'});
      }
      if (event.type === 'question.requested') {
        setQuestionPicker(createQuestionPicker(String(event.payload.callId)));
      }
      if ((event.type === 'tool.completed' || event.type === 'tool.failed')
        && event.payload.toolName === 'ask_plan_question') {
        setQuestionPicker(undefined);
      }
      if (event.type === 'run.command.result') {
        const pending = pendingSubmissionsRef.current.get(event.requestId);
        if (pending !== undefined) {
          if (event.payload.disposition === 'rejected') {
            replaceComposer(restoreRejectedComposer(composerRef.current, pending.composer));
          } else {
            replaceComposer(acceptPendingComposer(composerRef.current, pending.composer));
            // Java 已权威接受或排队后，原始输入不得再作为“可恢复草稿”保存；
            // 此后断连可能已经产生副作用，只能通过 Session recovery 收敛。
            pendingSubmissionsRef.current.delete(event.requestId);
          }
          if (event.payload.disposition === 'rejected') {
            pendingSubmissionsRef.current.delete(event.requestId);
          }
        }
        if (event.payload.disposition === 'rejected') {
          const pendingPlan = pendingDurablePlanDecisionRef.current;
          if (pendingPlan?.requestId === event.requestId) {
            pendingDurablePlanDecisionRef.current = undefined;
            replacePlanReviewPicker({...pendingPlan.review, submitted: false});
            if (pendingPlan.feedback !== undefined) {
              replacePlanFeedbackInput({review: pendingPlan.review, draft: createPlanFeedbackDraft(pendingPlan.feedback)});
            }
          }
        }
      }
      if (event.type === 'protocol.error') {
        dispatch({
          type: 'run.submission.rejected', requestId: event.requestId,
          message: `Java 协议拒绝：${String(event.payload.code)}`,
        });
        const pendingPlan = pendingDurablePlanDecisionRef.current;
        if (pendingPlan?.requestId === event.requestId) {
          pendingDurablePlanDecisionRef.current = undefined;
          replacePlanReviewPicker({...pendingPlan.review, submitted: false});
          if (pendingPlan.feedback !== undefined) {
            replacePlanFeedbackInput({review: pendingPlan.review, draft: createPlanFeedbackDraft(pendingPlan.feedback)});
          }
        }
      }
      if (event.type === 'steering.discarded' || event.type === 'protocol.error') {
        if (fileSuggestionRef.current?.requestId === event.requestId) {
          fileSuggestionRef.current = undefined;
          if (composerRef.current.completionCandidates.some(candidate => candidate.startsWith('@'))) {
            applyComposer({type: 'CloseCompletion'});
          }
        }
        const rejected = pendingSubmissionsRef.current.get(event.requestId);
        if (rejected !== undefined) {
          replaceComposer(restoreRejectedComposer(composerRef.current, rejected.composer));
          pendingSubmissionsRef.current.delete(event.requestId);
        }
      }
      if (event.type === 'steering.queued') {
        const pending = pendingSubmissionsRef.current.get(event.requestId);
        if (pending !== undefined && pending.status === 'awaiting_acceptance') {
          replaceComposer(acceptPendingComposer(composerRef.current, pending.composer));
          pending.status = 'accepted';
        }
      }
      if (event.type === 'run.started') {
        if (pendingDurablePlanDecisionRef.current?.requestId === event.requestId) {
          pendingDurablePlanDecisionRef.current = undefined;
        }
        const pending = pendingSubmissionsRef.current.get(event.requestId);
        if (pending !== undefined) {
          if (pending.status === 'awaiting_acceptance') {
            replaceComposer(acceptPendingComposer(composerRef.current, pending.composer));
          }
          pendingSubmissionsRef.current.delete(event.requestId);
        }
      }
      if (
        event.type === 'run.completed'
        || event.type === 'run.failed'
        || event.type === 'run.cancelled'
      ) {
        cancelPending.current = false;
        if (pendingDurablePlanDecisionRef.current?.requestId === event.requestId) {
          pendingDurablePlanDecisionRef.current = undefined;
        }
      }
      dispatch({type: 'event.received', event});
    });
    const offRunHandshake = client.onRunHandshake?.(notice => {
      if (notice.kind === 'late') {
        dispatch({type: 'run.submission.late', requestId: notice.requestId});
        return;
      }
      const pending = pendingSubmissionsRef.current.get(notice.requestId);
      if (pending !== undefined) {
        replaceComposer(restoreRejectedComposer(composerRef.current, pending.composer));
        pendingSubmissionsRef.current.delete(notice.requestId);
      }
      const pendingPlan = pendingDurablePlanDecisionRef.current;
      if (pendingPlan?.requestId === notice.requestId) {
        pendingDurablePlanDecisionRef.current = undefined;
        replacePlanReviewPicker({...pendingPlan.review, submitted: false});
        if (pendingPlan.feedback !== undefined) {
          replacePlanFeedbackInput({review: pendingPlan.review, draft: createPlanFeedbackDraft(pendingPlan.feedback)});
        }
      }
      dispatch({type: 'run.submission.timed_out', requestId: notice.requestId});
    }) ?? (() => {});
    const offFailure = client.onFailure(message => {
      cancelPending.current = false;
      const pendingComposers = [...pendingSubmissionsRef.current.values()]
        .filter(pending => pending.status === 'awaiting_acceptance')
        .map(pending => pending.composer);
      const pendingPlanComposer = pendingPlanEntryRef.current?.composer;
      if (pendingComposers.length > 0 || pendingPlanComposer !== undefined) {
        replaceComposer(restorePendingSubmissionComposers(
          composerRef.current, pendingComposers, pendingPlanComposer,
        ));
      }
      pendingSubmissionsRef.current.clear();
      pendingPlanEntryRef.current = undefined;
      pendingPlanDecisionRef.current = undefined;
      pendingDurablePlanDecisionRef.current = undefined;
      pendingDurablePlanRestoreRef.current = undefined;
      planSessionRef.current = undefined;
      replacePlanReviewPicker(undefined);
      replacePlanFeedbackInput(undefined);
      if (!transportFailureRef.current) {
        transportFailureRef.current = true;
        dispatch({type: 'transport.failed', message});
      }
      // Transport 已关闭；回收 Java 子进程，但 TUI 本身仍等 Ctrl+C 才退出。
      client.terminate();
    });
    const offExit = client.onExit(() => {
      cancelPending.current = false;
      const pendingComposers = [...pendingSubmissionsRef.current.values()]
        .filter(pending => pending.status === 'awaiting_acceptance')
        .map(pending => pending.composer);
      const pendingPlanComposer = pendingPlanEntryRef.current?.composer;
      if (pendingComposers.length > 0 || pendingPlanComposer !== undefined) {
        replaceComposer(restorePendingSubmissionComposers(
          composerRef.current, pendingComposers, pendingPlanComposer,
        ));
      }
      pendingSubmissionsRef.current.clear();
      pendingPlanEntryRef.current = undefined;
      pendingPlanDecisionRef.current = undefined;
      pendingDurablePlanDecisionRef.current = undefined;
      pendingDurablePlanRestoreRef.current = undefined;
      planSessionRef.current = undefined;
      replacePlanReviewPicker(undefined);
      replacePlanFeedbackInput(undefined);
      if (transportFailureRef.current) {
        return;
      }
      dispatch({type: 'closed'});
      exitRef.current();
    });
    client.initialize();
    return () => {
      offEvent();
      offRunHandshake();
      offFailure();
      offExit();
      pendingSubmissionsRef.current.clear();
      pendingDurablePlanRestoreRef.current = undefined;
      client.terminate();
    };
  }, [client]);

  useEffect(() => {
    applyComposer({type: 'Resize', width: composerLayout.width, height: composerLayout.height});
  }, [columns, rows]);

  const activityActive = state.phase === 'submitting'
    || state.phase === 'accepted' || state.phase === 'running';
  // 真实终端才驱动等待指示。Vitest 跳过 interval，避免 Ink 并行用例被 120ms 定时器拖慢。
  useEffect(() => {
    if (!activityActive || process.env.VITEST !== undefined) return;
    const timer = setInterval(() => setActivityTick(tick => tick + 1), 120);
    return () => clearInterval(timer);
  }, [activityActive]);

  useEffect(() => {
    if (fileSuggestionTimerRef.current !== undefined) {
      clearTimeout(fileSuggestionTimerRef.current);
      fileSuggestionTimerRef.current = undefined;
    }
    const mention = fileMentionEnabled(composerRef.current)
      ? activeFileMention(composerRef.current) : undefined;
    if (mention === undefined || client.suggestFiles === undefined || state.phase === 'connecting') {
      fileSuggestionRef.current = undefined;
      if (composerRef.current.completionCandidates.some(candidate => candidate.startsWith('@'))) {
        applyComposer({type: 'CloseCompletion'});
      }
      return;
    }
    fileSuggestionTimerRef.current = setTimeout(() => {
      try {
        const requestId = client.suggestFiles!(mention.query);
        fileSuggestionRef.current = {requestId, query: mention.query, mention};
      } catch {
        fileSuggestionRef.current = undefined;
      }
    }, 75);
    return () => {
      if (fileSuggestionTimerRef.current !== undefined) {
        clearTimeout(fileSuggestionTimerRef.current);
        fileSuggestionTimerRef.current = undefined;
      }
    };
  }, [composer.text, composer.cursorGrapheme, client, state.phase]);

  usePaste(pasted => {
    const setup = connectWizardRef.current;
    if (planFeedbackInput !== undefined) {
      replacePlanFeedbackInput({
        ...planFeedbackInput,
        draft: editPlanFeedback(planFeedbackInput.draft, {type: 'paste', text: pasted}),
      });
    } else if (setup?.phase === 'form') {
      replaceConnectWizard(editModelSetup(setup, {kind: 'append', text: pasted}));
    } else if (setup?.phase === 'credential') {
      updateSetupCredential(setupCredentialBytesRef.current, pasted);
      replaceConnectWizard(projectModelSetupCredential(setup,
        maskedCredentialPreview(setupCredentialBytesRef.current), setupCredentialBytesRef.current.length));
    } else if (permissionPicker === undefined && canEditInput(state.phase)) {
      applyComposer({type: 'Paste', text: pasted});
    }
  });

  useInput((text, key) => {
    if (key.ctrl && text.toLowerCase() === 'c') {
      if (providerLoginActive) {
        client.cancelProviderLogin?.();
        return;
      }
      const action = decideInterrupt(
        state.phase,
        state.activeRunId,
        cancelPending.current,
      );
      if (action === 'cancel') {
        cancelPending.current = true;
        client.cancelRun();
      } else if (action === 'terminate') {
        client.terminate();
        exit();
      } else {
        dispatch({type: 'closing'});
        void client.shutdown();
      }
      return;
    }
    if (state.taskPanelOpen && state.taskPanelFocused
      && planFeedbackInputRef.current === undefined && permissionPicker === undefined
      && planReviewPicker === undefined && connectWizardRef.current === undefined
      && pendingQuestion === undefined && pendingApproval === undefined && pendingUndo === undefined) {
      if (key.escape) dispatch({type: 'task.panel.close'});
      else if (key.upArrow) dispatch({type: 'task.panel.move', delta: -1});
      else if (key.downArrow) dispatch({type: 'task.panel.move', delta: 1});
      else if (key.return) dispatch({type: 'task.panel.toggle-detail'});
      return;
    }
    const currentPlanFeedback = planFeedbackInputRef.current;
    if (currentPlanFeedback !== undefined) {
      if (key.escape) {
        replacePlanFeedbackInput(undefined);
      } else if (key.return) {
        const feedback = currentPlanFeedback.draft.text.trim();
        if (feedback.length === 0) {
          dispatch({type: 'slash.notice', message: '继续规划需要非空反馈；Esc 可返回计划选项'});
          return;
        }
        const review = currentPlanFeedback.review;
        replacePlanReviewPicker({...review, submitted: true});
        try {
          const requestId = client.resolvePlanReview!({
            planId: review.planId,
            revision: review.revision,
            contentDigest: review.contentDigest,
            workspaceDigest: review.workspaceDigest,
            decision: 'CONTINUE_PLANNING',
            contextPolicy: review.contextPolicy.toUpperCase() as 'KEEP' | 'CLEAR',
            feedback,
          });
          pendingDurablePlanDecisionRef.current = {
            requestId, review, createsRun: true, feedback,
          };
          replacePlanFeedbackInput(undefined);
          dispatch({type: 'run.submitted', requestId, prompt: `继续规划 ${review.planId}`});
        } catch {
          replacePlanReviewPicker({...review, submitted: false});
          dispatch({type: 'slash.notice', message: 'Plan 反馈未被连接接受；可修改后重新提交'});
        }
      } else if (key.leftArrow) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'left'}),
        });
      } else if (key.rightArrow) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'right'}),
        });
      } else if (key.home) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'home'}),
        });
      } else if (key.end) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'end'}),
        });
      } else if (key.backspace) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'backspace'}),
        });
      } else if (key.delete) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'delete'}),
        });
      } else if (!key.ctrl && !key.meta && text.length > 0) {
        replacePlanFeedbackInput({
          ...currentPlanFeedback,
          draft: editPlanFeedback(currentPlanFeedback.draft, {type: 'insert', text}),
        });
      }
      return;
    }
    if (permissionPicker !== undefined) {
      if (key.escape) {
        permissionPickerSubmittedRef.current = false;
        setPermissionPicker(undefined);
      } else if (key.upArrow || key.downArrow) {
        setPermissionPicker(movePermissionPicker(permissionPicker, key.upArrow ? -1 : 1));
      } else if (key.return && !permissionPickerSubmittedRef.current) {
        permissionPickerSubmittedRef.current = true;
        client.sessionCommand!(
          `tui-command-${nextCommandNumber.current++}`,
          'permissions',
          {selection: selectedPermissionSelection(permissionPicker)},
        );
        setPermissionPicker(undefined);
      }
      return;
    }
    if (planReviewPicker !== undefined) {
      const durableReview = planReviewPicker.durable && client.resolvePlanReview !== undefined;
      if (!durableReview) {
        if (key.escape && !planReviewPicker.submitted) replacePlanReviewPicker(undefined);
        else if (key.upArrow || key.downArrow) {
          replacePlanReviewPicker(movePlanReviewPicker(planReviewPicker, key.upArrow ? -1 : 1));
        } else if (key.return && !planReviewPicker.submitted) {
          const legacyDecision = selectedPlanReviewDecision(planReviewPicker);
          const legacy = legacyDecision === 'approve_auto' || legacyDecision === 'approve_user' ? 'approve'
            : legacyDecision === 'continue_planning' ? 'revise' : 'reject';
          const commandNumber = nextCommandNumber.current++;
          const commandId = legacy === 'approve'
            ? `tui-plan-${commandNumber}-approve` : `tui-plan-${commandNumber}-reject`;
          if (legacy === 'approve' && client.sessionCommand !== undefined) {
            pendingPlanDecisionRef.current = {phase: 'approve', commandId, planId: planReviewPicker.planId,
              workspaceDigest: planReviewPicker.workspaceDigest};
            client.sessionCommand(commandId, 'plan-approve', {planId: planReviewPicker.planId,
              workspaceDigest: planReviewPicker.workspaceDigest});
          } else if (client.sessionCommand !== undefined) {
            pendingPlanDecisionRef.current = {phase: legacy === 'revise' ? 'reject-revise' : 'reject-exit',
              commandId, planId: planReviewPicker.planId, workspaceDigest: planReviewPicker.workspaceDigest};
            client.sessionCommand(commandId, 'plan-reject', {planId: planReviewPicker.planId});
          }
          replacePlanReviewPicker({...planReviewPicker, submitted: true});
        }
        return;
      }
      if (key.escape && !planReviewPicker.submitted) {
        replacePlanReviewPicker(undefined);
      } else if (key.upArrow || key.downArrow) {
        replacePlanReviewPicker(movePlanReviewPicker(planReviewPicker, key.upArrow ? -1 : 1));
      } else if (key.tab && !planReviewPicker.submitted) {
        replacePlanReviewPicker(togglePlanContextPolicy(planReviewPicker));
      } else if (key.return && !planReviewPicker.submitted) {
        if (client.resolvePlanReview === undefined) {
          dispatch({type: 'slash.notice', message: '当前连接不支持 durable Plan review'});
          return;
        }
        const decision = selectedPlanReviewDecision(planReviewPicker);
        if (decision === 'continue_planning') {
          replacePlanFeedbackInput({review: planReviewPicker, draft: createPlanFeedbackDraft()});
          return;
        }
        const protocolDecision = decision === 'approve_auto' ? 'APPROVE_AUTO'
          : decision === 'approve_user' ? 'APPROVE_USER' : 'REJECT';
        const review = planReviewPickerRef.current ?? planReviewPicker;
        if (review.submitted) return;
        replacePlanReviewPicker({...review, submitted: true});
        const planSession = planSessionRef.current;
        if (planSession !== undefined && client.sessionCommand !== undefined) {
          const commandId = `tui-plan-${nextCommandNumber.current++}-restore-durable-review`;
          pendingDurablePlanRestoreRef.current = {commandId, review, decision: protocolDecision};
          try {
            client.sessionCommand(commandId, 'permissions',
              permissionRestoreArguments(planSession.originalSelection));
          } catch {
            pendingDurablePlanRestoreRef.current = undefined;
            replacePlanReviewPicker({...review, submitted: false});
            dispatch({type: 'slash.notice', message: 'Plan 决定未被连接接受；durable review 保持待决定'});
          }
        } else if (!submitDurableReview(review, protocolDecision)) {
          replacePlanReviewPicker({...review, submitted: false});
          dispatch({type: 'slash.notice', message: 'Plan 决定未被连接接受；durable review 保持待决定'});
        }
      }
      return;
    }
    const currentWizard = connectWizardRef.current;
    if (currentWizard !== undefined) {
      if (key.escape) {
        setupCredentialBytesRef.current.fill(0); setupCredentialBytesRef.current.length = 0;
        const next = escapeModelSetup(currentWizard);
        replaceConnectWizard(next);
        return;
      }
      if (key.upArrow || key.downArrow) {
        replaceConnectWizard(moveModelSetup(currentWizard, key.upArrow ? 'baseUrl' : 'modelId'));
        return;
      }
      const textPage = currentWizard.phase === 'form';
      if (textPage) {
        if (key.backspace || key.delete) {
          replaceConnectWizard(editModelSetup(currentWizard, {kind: 'backspace'}));
          return;
        }
        if (!key.ctrl && !key.meta && text.length > 0 && !key.return) {
          replaceConnectWizard(editModelSetup(currentWizard, {kind: 'append', text}));
          return;
        }
      }
      if (currentWizard.phase === 'credential') {
        if (key.backspace || key.delete) setupCredentialBytesRef.current.pop();
        else if (!key.ctrl && !key.meta && text.length > 0 && !key.return) {
          updateSetupCredential(setupCredentialBytesRef.current, text);
        }
        if (!key.return) {
          replaceConnectWizard(projectModelSetupCredential(currentWizard,
            maskedCredentialPreview(setupCredentialBytesRef.current), setupCredentialBytesRef.current.length));
          return;
        }
        if (setupCredentialBytesRef.current.length === 0) return;
        if (client.providerLogin === undefined || providerLoginActiveRef.current) {
          setupCredentialBytesRef.current.fill(0); setupCredentialBytesRef.current.length = 0;
          replaceConnectWizard({...currentWizard, phase: 'error', message: '当前启动器不支持安全 API Key 输入'});
          return;
        }
        const secretBytes = Uint8Array.from(setupCredentialBytesRef.current);
        setupCredentialBytesRef.current.fill(0);
        setupCredentialBytesRef.current.length = 0;
        const loggingIn = beginModelSetupLogin(currentWizard);
        replaceConnectWizard(loggingIn);
        providerLoginActiveRef.current = true;
        setProviderLoginActive(true);
        void client.providerLogin({providerId: currentWizard.providerId, profileId: 'default',
          secretSource: 'stdin', secretBytes, setDefault: true}).then(result => {
          const latest = connectWizardRef.current;
          if (latest !== undefined && latest.generation === currentWizard.generation) {
            replaceConnectWizard(completeModelSetupLogin(latest, result.status));
          }
        }).catch(() => {
          secretBytes.fill(0);
          const latest = connectWizardRef.current;
          if (latest !== undefined && latest.generation === currentWizard.generation) {
            replaceConnectWizard({...latest, phase: 'error', message: 'API Key 未能保存'});
          }
        }).finally(() => {
          secretBytes.fill(0);
          providerLoginActiveRef.current = false;
          setProviderLoginActive(false);
        });
        return;
      }
      if (key.return) {
        if (currentWizard.phase === 'complete') {
          replaceConnectWizard(undefined);
          return;
        }
        const action = enterModelSetup(currentWizard);
        replaceConnectWizard(action.state);
        if (action.kind === 'control') {
          try {
            client.providerControl?.(action.controlId, action.intent, action.arguments);
          } catch {
            replaceConnectWizard({...action.state, phase: 'error', message: '当前连接未接受模型配置'});
          }
        }
        return;
      }
      return;
    }
    if (pendingQuestion !== undefined) {
      const picker = questionPicker?.callId === pendingQuestion.callId
        ? questionPicker : createQuestionPicker(pendingQuestion.callId);
      if (key.upArrow || key.downArrow) {
        setQuestionPicker(moveQuestionPicker(
          picker, pendingQuestion.options.length, key.upArrow ? -1 : 1,
        ));
      } else if (key.return && !picker.submitted) {
        const option = pendingQuestion.options[picker.selectedIndex];
        if (option !== undefined && client.resolveQuestion !== undefined) {
          client.resolveQuestion(pendingQuestion.callId, option.optionId);
          setQuestionPicker({...picker, submitted: true});
        }
      } else if (key.escape && !picker.submitted) {
        client.cancelRun();
      }
      return;
    }
    if (pendingApproval !== undefined) {
      const picker = effectiveApprovalPicker;
      const submitDecision = (decision: 'allow_once' | 'allow_session' | 'deny') => {
        if (pendingApproval.submitted) return;
        client.resolveApproval(pendingApproval.approvalId, decision);
        setApprovalPicker({...picker, approvalId: pendingApproval.approvalId});
        dispatch({type: 'approval.submitted', approvalId: pendingApproval.approvalId});
      };
      if (key.upArrow || key.downArrow) {
        setApprovalPicker(moveApprovalPicker(picker, key.upArrow ? -1 : 1));
      } else if (key.return && !pendingApproval.submitted) {
        submitDecision(selectedApprovalDecision(picker));
      } else if (key.escape && !pendingApproval.submitted) {
        submitDecision('deny');
      } else {
        const shortcut = approvalDecision(text);
        if (shortcut !== undefined && !pendingApproval.submitted && !key.ctrl && !key.meta) {
          submitDecision(shortcut);
        }
      }
      return;
    }
    if (pendingUndo !== undefined) {
      const decision = undoConfirmation(text);
      if (decision === 'confirm') {
        client.undoCheckpoint?.(pendingUndo.checkpointId, true);
      } else if (decision === 'cancel') {
        dispatch({type: 'checkpoint.undo.cancelled'});
      }
      return;
    }
    if (
      state.phase === 'ready'
      && checkpointSupported
      && composerRef.current.text.length === 0
    ) {
      const action = checkpointAction(text, key, state.checkpointPanelOpen);
      if (action === 'list') {
        client.listCheckpoints?.();
        return;
      }
      if (action === 'previous' || action === 'next') {
        const checkpointId = adjacentCheckpointId(
          state.checkpoints,
          state.selectedCheckpointId,
          action === 'previous' ? -1 : 1,
        );
        if (checkpointId !== undefined) {
          dispatch({type: 'checkpoint.selected', checkpointId});
        }
        return;
      }
      if (action === 'diff' && selectedCheckpoint !== undefined) {
        client.checkpointDiff?.(selectedCheckpoint.checkpointId);
        return;
      }
      if (action === 'undo' && selectedCheckpoint?.undoable === true) {
        dispatch({
          type: 'checkpoint.undo.requested',
          checkpointId: selectedCheckpoint.checkpointId,
        });
        return;
      }
    }
    if (key.ctrl && text.toLowerCase() === 't') {
      dispatch({type: 'tool.detail.next'});
      return;
    }
    if (key.ctrl && text.toLowerCase() === 'o') {
      dispatch({type: 'tool.detail.toggle'});
      return;
    }
    if (!canEditInput(state.phase)) {
      return;
    }
    const current = composerRef.current;
    if (isInsertNewlineKey(key, text)) {
      applyComposer({type: 'InsertText', text: '\n'});
      return;
    }
    if (key.escape) {
      applyComposer({type: 'CloseCompletion'});
      return;
    }
    if (key.return && hasTrailingNewlineEscape(current.text)
      && current.completionCandidates.length === 0) {
      applyComposer({type: 'Backspace'});
      applyComposer({type: 'InsertText', text: '\n'});
      return;
    }
    if (key.return) {
      if (shouldAcceptCompletionOnEnter(current)) {
        acceptCurrentCompletion();
        return;
      }
      if ([...pendingSubmissionsRef.current.values()]
        .some(pending => pending.status === 'awaiting_acceptance')) {
        dispatch({type: 'slash.notice', message: '上一条输入仍在提交中，当前草稿已保留'});
        return;
      }
      const submission = applyComposer({type: 'Submit'});
      if (submission.kind !== 'submit-ready') return;
      const prompt = submission.expandedText;
      if (prompt.trim().length === 0) return;
      const slash = parseSlashCommand(prompt.trim());
      if (prompt.trim() === '/plan' && current.completionCandidates.length > 0) {
        applyComposer({type: 'CloseCompletion'});
      }
      if (slash.kind === 'task') {
        const {action, taskId, timeoutMillis} = slash.command;
        try {
          if (action === 'wait' && client.waitTask !== undefined) client.waitTask(taskId, timeoutMillis ?? 30_000);
          else if (action === 'cancel' && client.cancelTask !== undefined) client.cancelTask(taskId);
          else if (action === 'keep' && client.keepTaskWorktree !== undefined) client.keepTaskWorktree(taskId);
          else if (action === 'remove' && client.removeTaskWorktree !== undefined) client.removeTaskWorktree(taskId);
          else throw new Error('unsupported');
        } catch {
          dispatch({type: 'slash.notice', message: '当前连接或状态不支持子任务动作'});
          return;
        }
      } else if (slash.kind === 'provider-control') {
        const {intent, arguments: arguments_} = slash.command;
        if (intent === 'connect') {
          const action = String(arguments_.action);
          if (action === 'providers') {
            if (client.providerControl === undefined) {
              dispatch({type: 'slash.notice', message: '当前连接不支持 Provider 控制命令'});
              return;
            }
            setupCredentialBytesRef.current.fill(0); setupCredentialBytesRef.current.length = 0;
            replaceConnectWizard(beginModelSetup(nextConnectGeneration.current++));
          } else if (action === 'login') {
            if (state.phase === 'running') {
              dispatch({type: 'slash.notice', message: 'Agent Run 运行中，结束或取消后再连接 Provider'});
              return;
            }
            if (client.providerLogin === undefined || providerLoginActiveRef.current) {
              dispatch({type: 'slash.notice', message: providerLoginActive
                ? '已有 Provider 登录正在执行；Ctrl+C 可取消'
                : '当前启动器不支持安全 Provider 登录桥'});
              return;
            }
            const request: ProviderLoginRequest = {
              providerId: String(arguments_.providerId),
              profileId: String(arguments_.profileId),
              secretSource: arguments_.secretSource === 'env' ? 'env' : 'store',
              ...(typeof arguments_.environmentName === 'string'
                ? {environmentName: arguments_.environmentName} : {}),
            };
            setProviderLoginActive(true);
            dispatch({type: 'slash.notice', message: request.secretSource === 'store'
              ? '已暂停 TUI 输入；请在 Java 提示中输入 API key（输入将被遮蔽，Ctrl+C 取消）'
              : `正在保存 ENV 引用 ${request.environmentName ?? ''}；TUI 不读取环境值`});
            void client.providerLogin(request).then(result => {
              if (result.status === 'succeeded') {
                dispatch({type: 'slash.notice', message: 'Provider profile 已保存，正在刷新 credential 列表'});
                sendIndependentProviderControl('auth.list', {});
              } else {
                const label = result.status === 'cancelled' ? '已取消'
                  : result.status === 'timed_out' ? '已超时并终止子进程'
                    : `失败（exit ${result.exitCode ?? 'unknown'}）`;
                dispatch({type: 'slash.notice', message: `Provider 登录${label}；未通过 TUI 传输 secret`});
              }
            }).catch(() => {
              dispatch({type: 'slash.notice', message: 'Provider 登录桥启动失败；未通过 TUI 传输 secret'});
            }).finally(() => setProviderLoginActive(false));
          }
        } else if (client.providerControl === undefined) {
          dispatch({type: 'slash.notice', message: '当前连接不支持 Provider 控制命令'});
          return;
        } else {
          const action = String(arguments_.action);
          const wireIntent = intent === 'auth' ? `auth.${action}` : `models.${action}`;
          const {action: _ignored, ...wireArguments} = arguments_;
          sendIndependentProviderControl(
            wireIntent as 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove',
            wireArguments,
          );
        }
      } else if (slash.kind === 'permission-picker') {
        if (state.phase !== 'ready' || pendingApproval !== undefined || connectWizardRef.current !== undefined
          || client.sessionCommand === undefined) {
          replaceComposer(acceptSubmittedComposer(submission.state));
          dispatch({type: 'slash.notice', message: client.sessionCommand === undefined
            ? '当前连接不支持 Slash 命令' : '当前状态不能打开权限选择'});
          return;
        }
        permissionPickerSubmittedRef.current = false;
        setPermissionPicker(initialPermissionPickerState);
      } else if (slash.kind === 'command') {
        if (client.sessionCommand === undefined) {
          dispatch({type: 'slash.notice', message: '当前连接不支持 Slash 命令'});
          return;
        }
        if (slash.command.intent === 'plan') {
          const task = typeof slash.command.arguments.task === 'string'
            ? slash.command.arguments.task : undefined;
          if (task !== undefined && client.startPlan === undefined) {
            dispatch({type: 'slash.notice', message: '当前连接不支持只读 Plan Runtime'});
            return;
          }
          if (pendingPlanEntryRef.current !== undefined || pendingPlanDecisionRef.current !== undefined) {
            dispatch({type: 'slash.notice', message: 'Plan 状态迁移仍在进行，请等待确认'});
            return;
          }
          const queryId = `tui-plan-${nextCommandNumber.current++}-query`;
          pendingPlanEntryRef.current = {
            phase: 'query', commandId: queryId, task,
            composer: task === undefined ? undefined : submission.state,
            originalSelection: undefined,
          };
          try {
            client.sessionCommand(queryId, 'permissions', {});
          } catch {
            pendingPlanEntryRef.current = undefined;
            dispatch({type: 'slash.notice', message: '当前权限选择未被接受'});
            return;
          }
        } else {
          client.sessionCommand(`tui-command-${nextCommandNumber.current++}`, slash.command.intent, slash.command.arguments);
        }
      } else if (slash.kind === 'skill') {
        if (client.invokeSkill === undefined || state.phase !== 'ready') {
          dispatch({type: 'slash.notice', message: '当前连接或状态不支持 Skill 调用'});
          return;
        }
        try {
          const requestId = client.invokeSkill(slash.name, slash.arguments);
          const label = submittedComposerLabel(submission.state);
          pendingSubmissionsRef.current.set(requestId, {
            composer: submission.state, label, status: 'awaiting_acceptance',
          });
          replaceComposer(beginPendingComposer(submission.state));
          dispatch({type: 'run.submitted', requestId, prompt: label});
        } catch {
          dispatch({type: 'slash.notice', message: 'Skill 调用未被接受'});
          return;
        }
      } else if (slash.kind === 'invalid') {
        dispatch({type: 'slash.notice', message: slash.message});
        return;
      } else {
        try {
          const requestId = client.startRun(prompt);
          const label = submittedComposerLabel(submission.state);
          pendingSubmissionsRef.current.set(requestId, {
            composer: submission.state, label, status: 'awaiting_acceptance',
          });
          replaceComposer(beginPendingComposer(submission.state));
          dispatch({type: 'run.submitted', requestId, prompt: label});
        } catch {
          dispatch({type: 'slash.notice', message: '输入传输未被接受，草稿已保留'});
          return;
        }
      }
      if (slash.kind === 'command' || slash.kind === 'provider-control' || slash.kind === 'task' || slash.kind === 'permission-picker') replaceComposer(acceptSubmittedComposer(submission.state));
      return;
    }
    if (key.upArrow || key.downArrow) {
      applyComposer({type: key.upArrow ? 'MoveUp' : 'MoveDown'});
      return;
    }
    if (key.leftArrow || key.rightArrow) {
      applyComposer({type: key.leftArrow
        ? key.ctrl || key.meta ? 'MoveWordLeft' : 'MoveLeft'
        : key.ctrl || key.meta ? 'MoveWordRight' : 'MoveRight'});
      return;
    }
    if (key.home || key.end) {
      applyComposer({type: key.home ? 'MoveHome' : 'MoveEnd'});
      return;
    }
    if (key.tab) {
      if (current.completionCandidates.length > 0) acceptCurrentCompletion();
      return;
    }
    if (key.backspace || key.delete) {
      applyComposer({type: key.backspace ? 'Backspace' : 'DeleteForward'});
      return;
    }
    if (!key.ctrl && !key.meta && text.length > 0) {
      const transition = applyComposer({type: 'InsertText', text});
      if (transition.kind === 'updated') {
        const nextCandidates = completionCandidates(transition.state.text)
          .filter(candidate => candidate !== transition.state.text);
        const completion = reduceComposer(
          transition.state, {type: 'SetCompletions', candidates: nextCandidates}, composerLayout,
        );
        replaceComposer(completion.state);
      }
    }
  }, {
    isActive: state.phase !== 'closing',
  });

  return <AgentView
    state={state}
    composer={composer}
    columns={columns}
    rows={rows}
    composerLayout={composerLayout}
    {...(connectWizard === undefined ? {} : {connectWizard})}
    {...(permissionPicker === undefined ? {} : {permissionPicker})}
    {...(pendingApproval === undefined ? {} : {approvalPicker: effectiveApprovalPicker})}
    {...(planReviewPicker === undefined ? {} : {planReviewPicker})}
    {...(planFeedbackInput === undefined ? {} : {planFeedbackInput})}
    {...(questionPicker === undefined ? {} : {questionPicker})}
    activityTick={activityTick}
  />;
}

export interface AgentViewProps {
  readonly state: ReturnType<typeof reduceTuiState>;
  readonly composer?: ComposerState;
  /** 兼容纯展示测试；生产路径使用 composer。 */
  readonly input?: string;
  readonly columns: number;
  /** 终端可用行数；省略时保持既有无界纯展示测试兼容。 */
  readonly rows?: number;
  readonly composerLayout?: ComposerLayout;
  readonly connectWizard?: ModelSetupState;
  readonly permissionPicker?: PermissionPickerState;
  readonly approvalPicker?: ApprovalPickerState;
  readonly planReviewPicker?: PlanReviewPickerState;
  readonly planFeedbackInput?: PlanFeedbackInputState;
  readonly questionPicker?: QuestionPickerState;
  readonly activityTick?: number;
}

const MAX_SETUP_CREDENTIAL_BYTES = 16_384;

/** 首次配置只接受常见可打印 ASCII Key，并把原始字节留在短生命周期缓冲中。 */
export function updateSetupCredential(target: number[], text: string): void {
  for (const byte of Buffer.from(text, 'utf8')) {
    if (target.length >= MAX_SETUP_CREDENTIAL_BYTES) return;
    if (byte >= 0x21 && byte <= 0x7e) target.push(byte);
  }
}

/** 实时展示固定前三位/后四位；不足七位时显示已有前缀并遮蔽其余内容。 */
export function maskedCredentialPreview(value: readonly number[]): string {
  if (value.length === 0) return '';
  if (value.length < 7) {
    return `${String.fromCharCode(...value.slice(0, 3))}${'•'.repeat(Math.max(0, value.length - 3))}`;
  }
  const first = String.fromCharCode(...value.slice(0, 3));
  const last = String.fromCharCode(...value.slice(-4));
  return `${first}${'•'.repeat(Math.min(8, value.length - 7))}${last}`;
}

/**
 * 纯展示组件，使宽字符、窄窗口和各 Run 终态无需真实终端即可验证。
 */
export function AgentView({state, composer, input = '', columns, rows, composerLayout, connectWizard, permissionPicker, approvalPicker, planReviewPicker, planFeedbackInput, questionPicker, activityTick}: AgentViewProps) {
  const width = Math.max(20, columns);
  const viewportRows = rows === undefined
    ? undefined
    : Math.max(5, Math.floor(rows));
  const overlayBlocksComposer = connectWizard !== undefined || permissionPicker !== undefined
    || state.taskPanelFocused === true;
  if (connectWizard !== undefined && connectWizard.required) {
    return <Box flexDirection="column">
      <Text>
        <Text bold color="cyan">codej</Text>
        <Text dimColor>  v{PRODUCT_VERSION}</Text>
        <Text dimColor>  · 配置模型</Text>
      </Text>
      <ConnectWizardPanel state={connectWizard} />
    </Box>;
  }
  const effectiveComposer = composer ?? reduceComposer(
    createComposerState(4), {type: 'InsertText', text: input}, {width: Math.max(1, width - 6), height: 4},
  ).state;
  const layout = composerLayout ?? {width: Math.max(1, width - 6), height: 4};
  const projection = projectComposer(effectiveComposer, layout);
  const renderedLines = renderComposerViewport(effectiveComposer, layout);
  const candidates = canEditInput(state.phase) && !overlayBlocksComposer
    ? effectiveComposer.completionCandidates : [];
  const selectedCompletion = effectiveComposer.completionIndex ?? 0;
  const composerFixedRows = renderedLines.length
    + 3
    + (effectiveComposer.validationCode === undefined ? 0 : 1);
  const candidateRegionRows = completionRegionRows(
    candidates.length, viewportRows, composerFixedRows,
  );
  const visibleCandidates = completionWindow(
    candidates,
    selectedCompletion,
    candidateRegionRows === undefined ? candidates.length : Math.max(0, candidateRegionRows - 1),
  );
  const showStartupBrand = state.phase === 'ready'
    && state.runs.length === 0
    && !overlayBlocksComposer
    && width >= 52
    && (viewportRows === undefined || viewportRows >= 16);
  const pastePreview = overlayBlocksComposer ? undefined : pastePreviewAtCursor(effectiveComposer);
  const noticeTone = state.notice === undefined ? undefined : classifyNotice(state.notice);
  const noticeLook = noticeTone === undefined ? undefined : noticeAppearance(noticeTone);
  const spinnerGlyph = activityTick === undefined ? '◌' : activitySpinnerFrame(activityTick);
  const archivedRuns = state.runs.filter(isArchivedRun);
  const liveRuns = state.runs.filter(run => !isArchivedRun(run));
  const historicalToolDetailRun = state.historicalToolDetailOpen === true
    ? state.runs.find(run => run.runId === state.historicalToolDetailRunId)
    : undefined;
  const hasHistoricalToolOutput = state.runs.some(run =>
    run.status !== 'running'
      && run.tools.some(tool => tool.output.lines.length > 0));
  return (
    <Box flexDirection="column" width={width}>
      <Static items={archivedRuns}>
        {run => <RunPresentation key={run.requestId} run={run} />}
      </Static>
      <Box flexShrink={0}>
        <Text bold color="cyan">codej</Text>
        {width < 28 ? null : (
          <>
            <Text dimColor>  v{PRODUCT_VERSION}</Text>
            <Text dimColor>  · {phaseLabel(state.phase)}</Text>
          </>
        )}
      </Box>
      <Box
        flexDirection="column"
        flexGrow={1}
        flexShrink={0}
      >
        <Box flexDirection="column" flexShrink={0}>
      {state.notice === undefined || noticeLook === undefined ? null : (
        <Box marginTop={1}>
          <Text color={noticeLook.color}>{noticeLook.icon} {state.notice}</Text>
        </Box>
      )}
      {state.phase === 'failed' ? (
        <Box marginTop={1}>
          <Text color="red">连接已关闭，Ctrl+C退出</Text>
        </Box>
      ) : null}
      {connectWizard === undefined ? null : <ConnectWizardPanel state={connectWizard} />}
      {permissionPicker === undefined ? null : <PermissionPickerPanel state={permissionPicker} />}
      {liveRuns.map(run => <RunPresentation
        key={run.requestId}
        run={run}
        approvalPicker={approvalPicker}
        planReviewPicker={planReviewPicker}
        planFeedbackInput={planFeedbackInput}
        questionPicker={questionPicker}
        spinnerGlyph={spinnerGlyph}
      />)}
      <SessionTaskPanel state={state} columns={width} spinnerGlyph={spinnerGlyph} />
      <ChildTaskPanel state={state} />
      <CheckpointPanel state={state} />
      {historicalToolDetailRun === undefined ? null : (
        <HistoricalToolDetail
          run={historicalToolDetailRun}
          selectedOrdinal={state.historicalToolDetailOrdinal}
        />
      )}
      {state.phase === 'ready' ? (
        <Box marginTop={1} flexDirection="column">
          {state.runs.length === 0 ? (
            <>
              {showStartupBrand ? (
                <Box flexDirection="column" marginBottom={1}>
                  {CODEJ_BANNER.map((line, index) => (
                    <Text
                      key={line}
                      bold
                      color={index < 2 ? 'cyanBright' : index < 4 ? 'blueBright' : 'magentaBright'}
                    >
                      {line}
                    </Text>
                  ))}
                  <Text bold>
                    <Text color="magentaBright">v{PRODUCT_VERSION}</Text>
                    <Text dimColor> · </Text>
                    <Text color="cyanBright">Java-powered coding agent</Text>
                  </Text>
                  <Text dimColor>Read, edit, run, and verify code from your terminal.</Text>
                </Box>
              ) : null}
            </>
          ) : null}
          {hasHistoricalToolOutput ? (
            <Text dimColor>
              Ctrl+O {state.historicalToolDetailOpen === true ? '关闭' : '查看'}最近历史 Tool 详情
            </Text>
          ) : null}
          {state.checkpoints.length === 0 ? null : (
            <>
              <Text dimColor>C 列表　面板打开后 c/d/u　↑/↓ 选择</Text>
              <Text dimColor>Undo 必须针对当前 Checkpoint 二次确认，绝不自动重放。</Text>
            </>
          )}
        </Box>
      ) : null}
        </Box>
      </Box>
      <Box flexDirection="column" flexShrink={0}>
      <Box
        borderStyle="round"
        borderColor={state.runs.findLast(run => run.status === 'running' || run.status === 'retrying')
          ?.pendingApproval === undefined
          ? state.phase === 'ready' ? 'cyan' : 'gray'
          : 'yellow'}
        paddingX={1}
      >
        <Text color="cyan">❯ </Text>
        {(canEditInput(state.phase) || effectiveComposer.text.length > 0)
          && !overlayBlocksComposer ? (
          <Box flexDirection="column">
            {renderedLines.map((line, index) => (
              <Text key={`${projection.viewportTop + index}-${line.beforeCursor.length}`}>
                {line.beforeCursor}
                {line.cursorText === undefined ? null : <Text inverse>{line.cursorText}</Text>}
                {line.afterCursor}
              </Text>
            ))}
          </Box>
        ) : null}
        {permissionPicker !== undefined
          ? <Text dimColor>正在选择权限模式，上方列表用 ↑/↓ 选择</Text>
          : connectWizard !== undefined
          ? <Text dimColor>正在配置连接，请按上方提示操作</Text>
          : state.phase === 'submitting'
          ? <Text dimColor>正在提交请求；拒绝或超时会恢复草稿</Text>
          : state.phase === 'accepted'
          ? <Text dimColor>Java 已接受请求，正在等待 Run 启动</Text>
          : state.phase === 'running'
          ? <Text dimColor>
              正在处理… Enter 排队补充{(state.steeringQueueDepth ?? 0) > 0
                ? `（${state.steeringQueueDepth}/100）` : ''}　Ctrl+C 取消
            </Text>
          : effectiveComposer.text.length === 0
            ? <Text dimColor>{inputHint(state.phase)}</Text>
            : null}
      </Box>
      {overlayBlocksComposer || pastePreview === undefined ? null : (
        <Text dimColor>
          粘贴 #{pastePreview.id} · {formatPasteBytes(pastePreview.utf8Bytes)} · {pastePreview.preview}
        </Text>
      )}
      {overlayBlocksComposer || effectiveComposer.validationCode === undefined ? null : (
        <Text color="red">输入未接受：{validationMessage(effectiveComposer.validationCode)}</Text>
      )}
      {overlayBlocksComposer || candidates.length === 0 || candidateRegionRows === 0 ? null : (
        <Box
          flexDirection="column"
          marginLeft={2}
          height={candidateRegionRows}
          overflow="hidden"
          flexShrink={1}
        >
          <Text dimColor>{candidates[0]?.startsWith('@')
            ? '文件建议 · ↑/↓ 选择 · Tab/Enter 补全 · Esc 关闭'
            : 'Slash 命令 · ↑/↓ 选择 · Tab/Enter 补全'}</Text>
          {visibleCandidates.map(({candidate, index}) => (
            <Text key={candidate} color={index === selectedCompletion ? 'cyan' : 'white'}>
              {index === selectedCompletion ? '❯ ' : '  '}{candidate.startsWith('@')
                ? candidate : slashCommandUsage(candidate)}
            </Text>
          ))}
        </Box>
      )}
      </Box>
    </Box>
  );
}

export function completionWindow(
  candidates: readonly string[],
  selectedIndex: number,
  maximumItems: number,
): readonly {readonly candidate: string; readonly index: number}[] {
  if (maximumItems <= 0 || candidates.length === 0) return [];
  const size = Math.min(candidates.length, Math.floor(maximumItems));
  const selected = Math.max(0, Math.min(candidates.length - 1, selectedIndex));
  const start = Math.max(0, Math.min(selected - Math.floor(size / 2), candidates.length - size));
  return candidates.slice(start, start + size).map((candidate, offset) => ({
    candidate,
    index: start + offset,
  }));
}

function PermissionPickerPanel({state}: {readonly state: PermissionPickerState}) {
  return <Box flexDirection="column" marginTop={1} borderStyle="round" borderColor="cyan" paddingX={1}>
    <Text bold color="cyan">权限选择</Text>
    {PERMISSION_PICKER_ITEMS.map((item, index) => (
      <Text key={item.selection} color={index === state.selectedIndex ? 'cyan' : 'white'}>
        {index === state.selectedIndex ? '❯ ' : '  '}{item.label}
      </Text>
    ))}
    <Text dimColor>↑/↓ 选择　Enter 确认　Esc 取消</Text>
  </Box>;
}

function ConnectWizardPanel({state}: {readonly state: ModelSetupState}) {
  let body;
  switch (state.phase) {
    case 'form':
      body = <>
        <Text color={state.field === 'baseUrl' ? 'cyan' : 'white'}>
          {state.field === 'baseUrl' ? '❯ ' : '  '}API Base URL　{state.baseUrl || '例如 https://api.openai.com/v1'}
        </Text>
        <Text color={state.field === 'modelId' ? 'cyan' : 'white'}>
          {state.field === 'modelId' ? '❯ ' : '  '}模型名称　　　{state.modelId || '例如 gpt-5.2'}
        </Text>
        {state.validation === undefined ? null : <Text color="red">{state.validation}</Text>}
        <Text dimColor>填写完成后将打开遮罩输入，请粘贴 API Key。</Text>
      </>;
      break;
    case 'saving':
      body = <Text color="yellow">正在保存模型配置…</Text>;
      break;
    case 'logging-in':
      body = <Text color="yellow">正在安全保存 API Key…</Text>;
      break;
    case 'credential':
      body = <><Text>API Key　<Text color="cyan">{state.credentialPreview || '粘贴或输入'}</Text></Text>
        <Text dimColor>仅显示前三位和后四位，中间内容始终隐藏 · Enter 保存</Text></>;
      break;
    case 'complete':
      body = <><Text color="green">模型配置完成：{state.modelId}</Text>
        {state.credentialPreview === undefined ? null : <Text>API Key　{state.credentialPreview}</Text>}
        <Text>按 Enter 开始使用 CodeJ</Text></>;
      break;
    case 'error':
      body = <><Text color="red">{state.message}</Text><Text>Enter 返回重新填写</Text></>;
      break;
  }
  return <Box flexDirection="column" marginTop={1} flexShrink={0}>
    <Text bold color="cyan">配置 CodeJ 模型</Text>
    {body}
    <Text dimColor>↑/↓ 切换字段　Enter 下一步　Esc 返回{state.required ? '' : '或关闭'}</Text>
  </Box>;
}

function ChildTaskPanel({state}: {readonly state: AgentViewProps['state']}) {
  const tasks = state.childTasks ?? [];
  if (tasks.length === 0) return null;
  return (
    <Box marginTop={1} flexDirection="column" borderStyle="round" borderColor="magenta" paddingX={1}>
      <Text bold color="magenta">Sub-Agent Tasks</Text>
      {tasks.map(task => (
        <Text key={task.taskId} color={task.status === 'succeeded' ? 'green'
          : task.status === 'failed' || task.status === 'cancelled' ? 'red' : 'yellow'}>
          {task.taskId} · {task.definitionId} · {task.status}
          {' · '}{task.modelTurns} turns / {task.toolCalls} tools / {task.estimatedTokens} tokens
          {' · '}{task.elapsedMillis}ms
          {task.worktreeDisposition === undefined ? '' : ` · worktree ${task.worktreeDisposition}`}
        </Text>
      ))}
    </Box>
  );
}

function SessionTaskPanel({state, columns, spinnerGlyph}: {
  readonly state: AgentViewProps['state'];
  readonly columns: number;
  readonly spinnerGlyph: string;
}) {
  if (!state.taskPanelOpen) return null;
  const board = state.taskBoard;
  const tasks = orderedSessionTasks(board?.tasks ?? []);
  const completed = tasks.filter(task => task.status === 'COMPLETED').length;
  const running = tasks.filter(task => task.status === 'IN_PROGRESS').length;
  const blocked = tasks.filter(task => task.status === 'PENDING' && task.blocked).length;
  const allCompleted = tasks.length > 0 && completed === tasks.length;
  const panelColumns = Math.max(1, Math.floor(columns) - 2);
  const summary = truncateTerminalText(board === undefined
    ? '正在读取任务…'
    : allCompleted
      ? `✓ 全部 ${board.totalTasks} 项任务已完成`
      : `任务 ${completed}/${board.totalTasks} 完成`
        + (running === 0 ? '' : ` · ${running} 进行中`)
        + (blocked === 0 ? '' : ` · ${blocked} 等待`), panelColumns);
  return (
    <Box marginTop={1} marginLeft={2} flexDirection="column">
      <Text bold color={allCompleted ? 'green' : 'cyan'}>{summary}</Text>
      {board === undefined
        ? null
        : tasks.length === 0
          ? <Text dimColor>  暂无执行任务</Text>
          : tasks.map(task => {
            const selected = state.taskPanelFocused === true && task.taskId === state.selectedTaskId;
            const prefix = selected ? '❯' : ' ';
            const live = task.status === 'IN_PROGRESS' && !task.recoveryRequired;
            const symbol = task.status === 'COMPLETED' ? '✓'
              : live ? spinnerGlyph : task.status === 'IN_PROGRESS' ? '●' : task.blocked ? '◌' : '○';
            const activity = live && task.activeForm !== undefined && task.activeForm !== task.subject
              ? ` · ${task.activeForm}` : '';
            const dependency = task.blockerIds.length === 0
              ? '' : ` · 等待 ${task.blockerIds.length} 项前置任务`;
            const recovery = task.recoveryRequired ? ' · 需要恢复' : '';
            const compactSuffix = `${activity}${task.blockerIds.length === 0
              ? '' : ` · 等待${task.blockerIds.length}项`}${task.recoveryRequired ? ' · 恢复' : ''}`;
            const line = projectSessionTaskLine(
              task.subject, `${activity}${dependency}${recovery}`, compactSuffix, columns,
            );
            return (
              <Box key={task.taskId} flexDirection="column">
                <Text {...(selected ? {color: 'cyanBright' as const}
                  : task.recoveryRequired ? {color: 'yellow' as const} : {})}>
                  {prefix}{' '}
                  <Text {...(task.status === 'COMPLETED' ? {color: 'green' as const}
                    : task.status === 'IN_PROGRESS' ? {color: 'yellow' as const} : {})}>
                    {symbol}{' '}
                  </Text>
                  <Text
                    bold={task.status === 'IN_PROGRESS'}
                    dimColor={task.blocked || sessionTaskTextDecoration(task.status).dimColor}
                    strikethrough={sessionTaskTextDecoration(task.status).strikethrough}
                  >
                    {line.subject}
                  </Text>
                  <Text dimColor={task.blocked || task.status === 'COMPLETED'}>{line.suffix}</Text>
                </Text>
                {selected && state.taskDetailOpen ? (
                  <Text dimColor>
                    {'    '}{truncateTerminalText(`${task.status === 'COMPLETED' ? '已完成'
                      : task.status === 'IN_PROGRESS' ? '进行中' : task.blocked ? '等待前置任务' : '待处理'}`,
                    Math.max(1, panelColumns - 4))}
                  </Text>
                ) : null}
              </Box>
            );
          })}
      {board?.truncated === true
        ? <Text dimColor>{truncateTerminalText(
          `  还有 ${Math.max(0, board.totalTasks - board.tasks.length)} 项未显示`, panelColumns,
        )}</Text>
        : null}
      {state.taskPanelFocused === true
        ? <Text dimColor>{truncateTerminalText('  ↑/↓ 选择　Enter 详情　Esc 关闭', panelColumns)}</Text>
        : null}
    </Box>
  );
}

/**
 * 为 Task 行统一计算完整宽度预算；缩进、选择符、状态符、正文和依赖后缀都计入终端列数。
 */
function projectSessionTaskLine(
  subject: string,
  suffix: string,
  compactSuffix: string,
  columns: number,
): {readonly subject: string; readonly suffix: string} {
  const textColumns = Math.max(1, Math.floor(columns) - 2 - 4);
  const fullSuffixFits = terminalDisplayWidth(suffix) <= Math.max(0, textColumns - Math.min(12, textColumns));
  const compactSuffixFits = terminalDisplayWidth(compactSuffix) <= Math.max(0, textColumns - Math.min(4, textColumns));
  const projectedSuffix = suffix === '' ? '' : fullSuffixFits ? suffix : compactSuffixFits ? compactSuffix : '';
  const subjectColumns = Math.max(1, Math.min(120, textColumns - terminalDisplayWidth(projectedSuffix)));
  return {subject: truncateTerminalText(subject, subjectColumns), suffix: projectedSuffix};
}

/** 按终端显示列安全缩短用户可见文本，正确处理 CJK、emoji 与 combining sequence。 */
export function truncateTerminalText(text: string, maximumColumns: number): string {
  const safeMaximum = Math.max(1, Math.floor(maximumColumns));
  if (stringWidth(text) <= safeMaximum) return text;
  const ellipsis = '…';
  const contentLimit = Math.max(0, safeMaximum - stringWidth(ellipsis));
  const segmenter = new Intl.Segmenter(undefined, {granularity: 'grapheme'});
  let result = '';
  let used = 0;
  for (const {segment} of segmenter.segment(text)) {
    const width = stringWidth(segment);
    if (used + width > contentLimit) break;
    result += segment;
    used += width;
  }
  return `${result}${ellipsis}`;
}

/** 返回不含 ANSI 控制序列的文本在终端中占用的显示列数。 */
export function terminalDisplayWidth(text: string): number {
  return stringWidth(text);
}

/** 把 canonical Task 状态映射为终端文本装饰；完成项必须同时弱化并划线。 */
export function sessionTaskTextDecoration(status: SessionTaskStatus): {
  readonly dimColor: boolean;
  readonly strikethrough: boolean;
} {
  const completed = status === 'COMPLETED';
  return {dimColor: completed, strikethrough: completed};
}

function CheckpointPanel({state}: {readonly state: AgentViewProps['state']}) {
  if (
    !state.checkpointPanelOpen
    && state.checkpointDiff === undefined
    && state.checkpointUndo === undefined
  ) {
    return null;
  }
  const pendingUndo = state.checkpoints.find(
    item => item.checkpointId === state.pendingUndoCheckpointId,
  );
  return (
    <Box
      marginTop={1}
      flexDirection="column"
      borderStyle="round"
      borderColor={pendingUndo === undefined ? 'blue' : 'red'}
      paddingX={1}
    >
      <Text bold color="blue">Session Checkpoints</Text>
      {state.checkpoints.length === 0
        ? <Text dimColor>当前 Session 没有 Checkpoint</Text>
        : state.checkpoints.map(checkpoint => (
          <CheckpointRow
            key={checkpoint.checkpointId}
            checkpoint={checkpoint}
            selected={checkpoint.checkpointId === state.selectedCheckpointId}
          />
        ))}
      {state.checkpointDiff === undefined ? null : (
        <Box marginTop={1} flexDirection="column">
          <Text color="cyan">
            Diff · {state.checkpointDiff.target} · {state.checkpointDiff.status}
            {state.checkpointDiff.truncated ? ' · 已裁剪' : ''}
          </Text>
          {state.checkpointDiff.text.length === 0
            ? <Text dimColor>（无文本差异）</Text>
            : <Text>{state.checkpointDiff.text}</Text>}
        </Box>
      )}
      {state.checkpointUndo === undefined ? null : (
        <Text color={state.checkpointUndo.status === 'conflict' ? 'red' : 'green'}>
          Undo · {state.checkpointUndo.target} · {state.checkpointUndo.status}
        </Text>
      )}
      {pendingUndo === undefined ? null : (
        <Box marginTop={1} flexDirection="column">
          <Text color="red" bold>
            确认 Undo 当前 Checkpoint？
          </Text>
          <Text>{pendingUndo.checkpointId}</Text>
          <Text>{pendingUndo.target}</Text>
          <Text dimColor>
            仅按 Shift+Y 执行；N 或 Esc 取消。此操作只恢复普通文件 Checkpoint。
          </Text>
        </Box>
      )}
    </Box>
  );
}

function CheckpointRow({
  checkpoint,
  selected,
}: {
  readonly checkpoint: CheckpointView;
  readonly selected: boolean;
}) {
  return (
    <Text color={checkpoint.undoable ? 'green' : 'yellow'}>
      {selected ? '❯' : ' '} {checkpoint.checkpointId} · {checkpoint.target}
      {' · '}{checkpointPhaseLabel(checkpoint.phase)}
      {checkpoint.undoable ? ' · 可 Undo' : ''}
    </Text>
  );
}

function PlanFeedbackLine({draft}: {readonly draft: PlanFeedbackDraft}) {
  const {before, at, after} = planFeedbackCursorParts(draft);
  return <>
    {before}
    <Text inverse>{at ?? ' '}</Text>
    {after}
  </>;
}

function formatPasteBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(bytes < 10_240 ? 1 : 0)} KiB`;
}

function DurablePlanReviewPanel({review, picker, feedbackInput}: {
  readonly review: NonNullable<RunView['planReview']>;
  readonly picker: PlanReviewPickerState | undefined;
  readonly feedbackInput: PlanFeedbackInputState | undefined;
}) {
  const active = picker?.planId === review.planId;
  return <Box marginTop={1} marginLeft={2} flexDirection="column"
    borderStyle="round" borderColor="cyan" paddingX={1}>
    <Text bold color="cyan">实施计划 · revision {review.revision}</Text>
    <AssistantMarkdown text={review.markdown} />
    {!active ? <Text dimColor>该计划已不再等待当前窗口决定</Text>
      : feedbackInput?.review.planId === review.planId ? <Box marginTop={1} flexDirection="column">
          <Text bold color="yellow">请输入计划反馈</Text>
          <Text>
            {feedbackInput.draft.text.length === 0 && feedbackInput.draft.cursor === 0
              ? <Text inverse> </Text>
              : <PlanFeedbackLine draft={feedbackInput.draft} />}
          </Text>
          <Text dimColor>←/→ 移动　Enter 提交非空反馈　Esc 返回四项审批</Text>
        </Box>
      : picker.submitted ? <Text dimColor>决定已发送，正在核对持久化版本</Text>
        : <><Box marginTop={1} flexDirection="column">
          {PLAN_REVIEW_PICKER_ITEMS.map((item, index) => <Text key={item.decision}
            color={index === picker.selectedIndex ? 'cyan' : 'white'}>
            {index === picker.selectedIndex ? '❯ ' : '  '}{item.label}
          </Text>)}
        </Box><Text dimColor>上下选择　Tab 切换上下文（当前 {picker.contextPolicy === 'keep' ? '保留' : '清空'}）　Enter 一次确认</Text></>}
  </Box>;
}

function QuestionPrompt({question, picker}: {
  readonly question: NonNullable<RunView['pendingQuestion']>;
  readonly picker: QuestionPickerState | undefined;
}) {
  const selected = picker?.callId === question.callId ? picker.selectedIndex : 0;
  return <Box marginTop={1} marginLeft={2} flexDirection="column"
    borderStyle="round" borderColor="yellow" paddingX={1}>
    <Text bold color="yellow">需要你的选择</Text>
    <Text>{question.question}</Text>
    <Box marginTop={1} flexDirection="column">
      {question.options.map((option, index) => <Box key={option.optionId} flexDirection="column">
        <Text color={index === selected ? 'cyan' : 'white'}>
          {index === selected ? '❯ ' : '  '}{option.label}
        </Text>
        <Text dimColor>    {option.description}</Text>
      </Box>)}
    </Box>
    <Text dimColor>{picker?.submitted ? '答案已发送，正在继续同一规划会话' : '↑/↓ 选择　Enter 回答　Esc 取消 Run'}</Text>
  </Box>;
}

function PlanReviewPanel({proposal, picker}: {
  readonly proposal: NonNullable<RunView['planProposal']>;
  readonly picker: PlanReviewPickerState | undefined;
}) {
  const active = picker?.planId === proposal.planId;
  return (
    <Box marginTop={1} marginLeft={2} flexDirection="column"
      borderStyle="round" borderColor="cyan" paddingX={1}>
      <Text bold color="cyan">实施计划</Text>
      <Text bold>{proposal.objective}</Text>
      {proposal.steps.map(step => (
        <Box key={step.ordinal} flexDirection="column" marginTop={1}>
          <Text bold>{step.ordinal}. {step.title}</Text>
          <Text>{step.detail}</Text>
        </Box>
      ))}
      {!active ? <Text dimColor>该计划已不再等待当前窗口决定</Text>
        : picker.submitted ? <Text dimColor>批准已发送，正在核对 Plan 与工作区状态</Text>
          : <>
            <Box marginTop={1} flexDirection="column">
              {[{decision: 'approve', label: '批准并执行'},
                {decision: 'revise', label: '继续修改计划'},
                {decision: 'reject', label: '拒绝并退出'}].map((item, index) => (
                <Text key={item.decision} color={index === picker.selectedIndex ? 'cyan' : 'white'}>
                  {index === picker.selectedIndex ? '❯ ' : '  '}{item.label}
                </Text>
              ))}
            </Box>
            <Text dimColor>↑/↓ 选择　Enter 确认　Esc 关闭当前选择面板；稍后可用 /plan 重新查询</Text>
          </>}
    </Box>
  );
}

function ApprovalPrompt({approval, picker}: {
  readonly approval: ApprovalView;
  readonly picker: ApprovalPickerState | undefined;
}) {
  const action = approval.effect === 'write_workspace'
    ? '修改 Workspace'
    : approval.effect === 'execute_process'
      ? '启动本地进程'
      : '访问网络';
  return (
    <Box
      marginTop={1}
      marginLeft={2}
      flexDirection="column"
      borderStyle="round"
      borderColor="yellow"
      paddingX={1}
    >
      <Text color="yellow" bold>需要批准：{action}</Text>
      <Text>{approval.toolName} · 第 {approval.ordinal} 个工具调用</Text>
      {approval.target === undefined
        ? null
        : (
          <>
            <Text>
              {approval.operation === 'create' ? '创建' : '修改'}：{approval.target}
            </Text>
            <Text color="green">
              +{approval.addedLines ?? 0} 行
              <Text color="red">　-{approval.removedLines ?? 0} 行</Text>
            </Text>
          </>
        )}
      {approval.command === undefined
        ? null
        : (
          <>
            <Text>Shell：{approval.shell}</Text>
            <Text>工作目录：{approval.workingDirectory}</Text>
            <Text color="cyan">{approval.command}</Text>
          </>
        )}
      {approval.effect !== 'network_or_remote' ? null : (
        <>
          <Text>将搜索词发送给已配置的 Web Search Provider：</Text>
          <Text color="cyan">{approval.query ?? '（查询内容不可安全预览）'}</Text>
        </>
      )}
      {approval.submitted
        ? <Text dimColor>决定已发送，等待确认</Text>
        : <>
          {APPROVAL_PICKER_ITEMS.map((item, index) => (
            <Text key={item.decision} color={index === (picker?.selectedIndex ?? 0) ? 'cyan' : 'white'}>
              {index === (picker?.selectedIndex ?? 0) ? '❯ ' : '  '}{item.label}
            </Text>
          ))}
          <Text dimColor>↑/↓ 选择　Y 允许一次　A 本会话　N/Esc 拒绝　Enter 确认</Text>
        </>}
    </Box>
  );
}

/**
 * 把 Java 权威终态投影为不包含 Provider 原文的稳定诊断摘要。
 */
export function formatRunTerminal(run: RunView): string {
  if (run.status === 'submitting') return '正在提交';
  if (run.status === 'accepted') return 'Java 已接受，等待 Run 启动';
  if (run.status === 'queued') return '已排队，等待前一 Run 终结';
  if (run.status === 'running' || run.status === 'retrying') return '正在运行';
  const counts = [
    run.modelTurns === undefined ? undefined : `${run.modelTurns} 回合`,
    run.toolCalls === undefined ? undefined : `${run.toolCalls} 次工具`,
  ].filter((value): value is string => value !== undefined);
  if (run.status === 'completed') {
    return counts.length === 0 ? '已完成' : `已完成 · ${counts.join(' · ')}`;
  }
  const reason = run.stopReason === undefined ? '' : ` · ${run.stopReason}`;
  return `${runStatusLabel(run.status)}${reason}`
    + (counts.length === 0 ? '' : ` · ${counts.join(' · ')}`);
}

export function formatModelFailure(summary: ModelFailureView): string {
  const base = (() => {
    switch (summary.category) {
      case 'provider_unavailable': return '模型服务暂时不可用';
      case 'rate_limited': return '模型服务请求过于频繁';
      case 'request_timeout': return '模型请求超时';
      case 'request_conflict': return '模型服务暂时无法处理该请求';
      case 'authentication_failed': return '模型服务鉴权失败';
      case 'invalid_request': return '模型服务拒绝了请求';
      case 'network_error': return '无法连接模型服务';
      case 'incomplete_stream': return '模型输出流未完整结束';
      case 'invalid_response': return '模型服务返回了无效响应';
      case 'provider_error': return '模型服务调用失败';
      case 'configuration_required': return '尚未配置 Provider profile 或模型选择';
    }
  })();
  const status = summary.statusClass === undefined ? '' : `（${summary.statusClass}）`;
  const attempts = summary.attempts > 1 ? `，已尝试 ${summary.attempts} 次` : '';
  const action = summary.category === 'authentication_failed'
    ? '；请检查 Provider 凭证或权限'
    : summary.category === 'invalid_request'
      ? '；请检查模型与请求配置'
      : summary.category === 'configuration_required'
        ? '；请运行 /connect 或 codej auth login'
        : summary.category === 'invalid_response' || summary.category === 'provider_error'
          ? '；请检查 Provider 状态'
          : '；请稍后重试';
  return base + status + attempts + action;
}

/**
 * 展示当前 Run 的唯一加载行与数值 Usage；不展示或伪造隐藏思维链。
 *
 * <p>该行只展示模型请求、重试和 Usage，不投影 Session Task。Task 的加载图标、
 * activeForm 与终态装饰统一由下方唯一 Task List 渲染，避免同一 Board 上下重复。</p>
 */
function ModelProgressLine({run, spinnerGlyph}: {
  readonly run: RunView;
  readonly spinnerGlyph: string;
}) {
  const progress = run.modelProgress;
  const activeTool = run.tools.some(tool => tool.status === 'started');
  let status: string | undefined;
  if (run.runId !== undefined && (run.status === 'running' || run.status === 'retrying')) {
    if (progress?.retryAttempt !== undefined && progress.retryMaxAttempts !== undefined
      && progress.retryWaitMillis !== undefined) {
      const wait = progress.retryWaitMillis >= 1000
        ? `${(progress.retryWaitMillis / 1000).toFixed(progress.retryWaitMillis % 1000 === 0 ? 0 : 1)} 秒`
        : `${progress.retryWaitMillis} 毫秒`;
      status = `模型请求暂时失败，${wait}后进行第 ${progress.retryAttempt}/${progress.retryMaxAttempts} 次尝试`;
    } else if (progress?.retryAttempt !== undefined && progress.retryAttempt > 1
      && progress.retryMaxAttempts !== undefined) {
      status = `正在进行第 ${progress.retryAttempt}/${progress.retryMaxAttempts} 次模型尝试`;
    } else if (!activeTool && progress?.phase === 'thinking') {
      status = `正在分析 · 第 ${progress.turn} 回合`;
    } else if (!activeTool && progress?.phase === 'preparing_tools') {
      status = '正在准备工具调用';
    } else if (!activeTool && run.text.length === 0) {
      status = '等待模型响应';
    }
  }
  const usage: string[] = [];
  if (progress?.contextUsedTokens !== undefined
    && progress.contextMaximumInputTokens !== undefined) {
    usage.push(`上下文${progress.contextEstimateKind === 'exact' ? '实测' : '估算'} ${formatTokenCount(progress.contextUsedTokens)}/${formatTokenCount(progress.contextMaximumInputTokens)}`);
  }
  if (progress !== undefined && progress.usageReportedTurns > 0) {
    const coverage = progress.usageMissingTurns === 0 ? 'Provider 实测' : 'Provider 部分实测';
    usage.push(`${coverage} 累计 ${formatTokenCount(progress.providerTotalTokens)}（↑ ${formatTokenCount(progress.providerInputTokens)} · ↓ ${formatTokenCount(progress.providerOutputTokens)}）`);
  }
  if (status === undefined && usage.length === 0) return null;
  return <Box marginLeft={2} flexDirection="column">
    {status === undefined ? null : <Text color="yellow">
      {spinnerGlyph} {/(?:…|\.{3})$/u.test(status.trimEnd()) ? status.trimEnd() : `${status.trimEnd()}…`}
    </Text>}
    {usage.length === 0 ? null : <Text dimColor>Token · {usage.join(' · ')}</Text>}
  </Box>;
}

function formatTokenCount(value: number): string {
  if (value < 1_000) return String(value);
  if (value < 1_000_000) return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)}k`;
  return `${(value / 1_000_000).toFixed(1)}m`;
}

/**
 * 只有不再需要交互更新的 Run 才能进入 Ink Static；否则 picker 或流式文本会被冻结。
 */
function isArchivedRun(run: RunView): boolean {
  return (run.status === 'completed' || run.status === 'cancelled' || run.status === 'failed')
    && run.pendingApproval === undefined
    && run.planProposal === undefined
    && (run.planReview === undefined || run.planReviewSettled === true)
    && run.pendingQuestion === undefined
    && run.awaitingPlanVerification !== true;
}

function RunPresentation({
  run,
  approvalPicker,
  planReviewPicker,
  planFeedbackInput,
  questionPicker,
  spinnerGlyph = '◌',
}: {
  readonly run: RunView;
  readonly approvalPicker?: ApprovalPickerState | undefined;
  readonly planReviewPicker?: PlanReviewPickerState | undefined;
  readonly planFeedbackInput?: PlanFeedbackInputState | undefined;
  readonly questionPicker?: QuestionPickerState | undefined;
  readonly spinnerGlyph?: string;
}) {
  return <Box flexDirection="column" marginTop={1}>
    <Box>
      <Text color="green" bold>❯ </Text>
      <Text bold>{run.prompt}</Text>
    </Box>
    <ModelProgressLine run={run} spinnerGlyph={spinnerGlyph} />
    <ToolActivityGroup tools={run.tools} />
    <ToolDetail run={run} />
    {run.pendingApproval === undefined
      ? null : <ApprovalPrompt approval={run.pendingApproval} picker={approvalPicker} />}
    {run.planProposal === undefined
      ? null : <PlanReviewPanel proposal={run.planProposal} picker={planReviewPicker} />}
    {run.planReview === undefined
      ? null : <DurablePlanReviewPanel review={run.planReview} picker={planReviewPicker}
        feedbackInput={planFeedbackInput} />}
    {run.pendingQuestion === undefined
      ? null : <QuestionPrompt question={run.pendingQuestion} picker={questionPicker} />}
    {run.text.length === 0 ? null : (
      <Box marginTop={1} flexDirection="row">
        <Text color="cyan">● </Text>
        <Box flexDirection="column" flexGrow={1}>
          <AssistantMarkdown text={run.text} />
        </Box>
      </Box>
    )}
    <RunTerminal run={run} />
    {run.planVerification === undefined ? null : (
      <Box marginLeft={4}><Text color="yellow">{run.planVerification}</Text></Box>
    )}
    {run.modelFailure === undefined ? null : (
      <Box marginLeft={4}><Text color="red">{formatModelFailure(run.modelFailure)}</Text></Box>
    )}
  </Box>;
}

function RunTerminal({run}: {readonly run: RunView}) {
  if (run.status === 'running') {
    return null;
  }
  const failed = run.status === 'failed';
  return (
    <Box marginTop={1} marginLeft={2}>
      <Text color={failed ? 'red' : run.status === 'cancelled' ? 'yellow' : 'green'}
        dimColor={!failed}>
        {failed ? '✗' : run.status === 'cancelled' ? '■' : '✓'} {formatRunTerminal(run)}
      </Text>
    </Box>
  );
}

function phaseLabel(phase: ReturnType<typeof reduceTuiState>['phase']): string {
  switch (phase) {
    case 'connecting':
      return '正在连接';
    case 'ready':
      return '就绪';
    case 'submitting':
      return '等待接受';
    case 'accepted':
      return '等待启动';
    case 'running':
      return '运行中';
    case 'closing':
      return '正在关闭';
    case 'closed':
      return '已关闭';
    case 'failed':
      return '连接失败';
  }
}

function runStatusLabel(status: 'cancelled' | 'failed'): string {
  return status === 'cancelled' ? '已取消' : '运行失败';
}

function inputHint(phase: ReturnType<typeof reduceTuiState>['phase']): string {
  return phase === 'connecting'
    ? '连接中，可以先输入任务'
    : 'Enter 发送，Shift+Enter / Ctrl+J 换行';
}

function validationMessage(code: ComposerState['validationCode']): string {
  switch (code) {
    case 'VISIBLE_STRUCTURE_LIMIT': return '可见输入结构超过 8192 单元';
    case 'PASTE_COUNT_LIMIT': return '折叠粘贴数量超过上限';
    case 'PASTE_ITEM_LIMIT': return '单次粘贴超过 1 MiB';
    case 'PASTE_TOTAL_LIMIT': return '粘贴总量超过 1 MiB';
    case 'PASTE_REFERENCE_FORGED': return '粘贴引用格式无效';
    case 'PASTE_REFERENCE_STALE': return '粘贴内容已失效';
    case 'PASTE_REFERENCE_DUPLICATE': return '粘贴引用重复';
    case 'PASTE_REFERENCE_ORPHAN': return '粘贴内容缺少引用';
    case 'SUBMISSION_CODE_POINT_LIMIT': return '展开内容的 Unicode 字符数超过上限';
    case 'SUBMISSION_UTF16_LIMIT': return '展开内容的 Java 字符数超过上限';
    case 'SUBMISSION_UTF8_LIMIT': return '展开内容的 UTF-8 字节数超过 1 MiB';
    case undefined: return '';
  }
}

/**
 * 空 Composer 上的 Checkpoint 快捷键。
 *
 * <p>大写 {@code C} 才能在面板关闭时拉列表；小写 {@code c/d/u} 只在面板已打开时生效，
 * 避免 {@code can you…} 这类首字母被吞掉。Undo 二次确认仍只接受 Shift+Y。</p>
 */
export function checkpointAction(
  text: string,
  key: {readonly upArrow?: boolean; readonly downArrow?: boolean},
  panelOpen: boolean,
): 'list' | 'previous' | 'next' | 'diff' | 'undo' | undefined {
  if (panelOpen && key.upArrow === true) {
    return 'previous';
  }
  if (panelOpen && key.downArrow === true) {
    return 'next';
  }
  switch (text) {
    case 'C': return 'list';
    case 'c': return panelOpen ? 'list' : undefined;
    case 'D':
    case 'd': return panelOpen ? 'diff' : undefined;
    case 'U':
    case 'u': return panelOpen ? 'undo' : undefined;
    default: return undefined;
  }
}

export function adjacentCheckpointId(
  checkpoints: readonly CheckpointView[],
  selectedCheckpointId: string | undefined,
  delta: -1 | 1,
): string | undefined {
  if (checkpoints.length === 0) {
    return undefined;
  }
  const selected = checkpoints.findIndex(
    item => item.checkpointId === selectedCheckpointId,
  );
  const origin = selected < 0 ? (delta > 0 ? -1 : 0) : selected;
  const index = (origin + delta + checkpoints.length) % checkpoints.length;
  return checkpoints[index]?.checkpointId;
}

export function undoConfirmation(
  text: string,
): 'confirm' | 'cancel' | undefined {
  if (text === 'Y') {
    return 'confirm';
  }
  if (text.toLowerCase() === 'n' || text === '\u001b') {
    return 'cancel';
  }
  return undefined;
}

function checkpointPhaseLabel(phase: CheckpointPhase): string {
  switch (phase) {
    case 'create_prepared': return '创建准备中';
    case 'create_journal_uncertain': return '创建记录不确定';
    case 'created': return '等待 Tool 结果';
    case 'post_prepared': return '结果准备中';
    case 'post_journal_uncertain': return '结果记录不确定';
    case 'completed_present': return '已完成（文件存在）';
    case 'completed_absent': return '已完成（文件不存在）';
    case 'undo_prepared': return 'Undo 状态不确定';
    case 'undo_applied': return 'Undo 已应用待确认';
    case 'undo_journal_uncertain': return 'Undo 记录不确定';
    case 'undone': return '已 Undo';
  }
}

export function approvalDecision(
  text: string,
): 'allow_once' | 'allow_session' | 'deny' | undefined {
  const normalized = text.toLowerCase();
  if (normalized === 'y') {
    return 'allow_once';
  }
  if (normalized === 'a') {
    return 'allow_session';
  }
  if (normalized === 'n') {
    return 'deny';
  }
  return undefined;
}

export function decideInterrupt(
  phase: ReturnType<typeof reduceTuiState>['phase'],
  activeRunId: string | undefined,
  cancelPending = false,
): 'cancel' | 'terminate' | 'shutdown' {
  if (phase === 'running' && activeRunId !== undefined) {
    return cancelPending ? 'terminate' : 'cancel';
  }
  if (phase === 'failed' || phase === 'closed') {
    return 'terminate';
  }
  return 'shutdown';
}

function publicPermissionSelection(value: unknown): PublicPermissionSelection | undefined {
  return value === 'PLAN' || value === 'ASK' || value === 'AUTO' || value === 'ADVANCED'
    ? value : undefined;
}

/** PLAN 不能执行副作用；若用户原本已在 PLAN，则以 ASK 作为安全退出选择。 */
function executionSelection(value: PublicPermissionSelection | undefined): Exclude<PublicPermissionSelection, 'PLAN'> {
  return value === undefined || value === 'PLAN' ? 'ASK' : value;
}

function permissionRestoreArguments(
  value: PublicPermissionSelection | undefined,
): Readonly<Record<string, unknown>> {
  const selection = executionSelection(value);
  return selection === 'ADVANCED' ? {mode: 'ACCEPT_EDITS'} : {selection};
}

/** transport terminal 时按提交顺序恢复所有尚未由 run.started 消费的草稿。 */
function restorePendingSubmissionComposers(
  current: ComposerState,
  pending: readonly ComposerState[],
  planEntry: ComposerState | undefined,
): ComposerState {
  let restored = current;
  for (const composer of [...pending].reverse()) {
    restored = restoreRejectedComposer(restored, composer);
  }
  return planEntry === undefined ? restored : restoreRejectedComposer(restored, planEntry);
}

function restoreAfterPlanStartFailure(
  client: AgentClient,
  nextCommandNumber: {current: number},
  pendingEntry: {current: PlanEntryState | undefined},
  task: string,
  originalSelection: PublicPermissionSelection,
  composer: ComposerState | undefined = undefined,
): boolean {
  const restoreId = `tui-plan-${nextCommandNumber.current++}-restore-start-failure`;
  pendingEntry.current = {
    phase: 'restore-after-start-failure', commandId: restoreId, task, composer, originalSelection,
  };
  try {
    if (client.sessionCommand === undefined) {
      pendingEntry.current = undefined;
      return false;
    }
    client.sessionCommand(restoreId, 'permissions', permissionRestoreArguments(originalSelection));
    return true;
  } catch {
    pendingEntry.current = undefined;
    return false;
  }
}

function isReviewablePlanStatus(value: unknown): boolean {
  return value === 'DRAFT' || value === 'AWAITING_APPROVAL';
}

/**
 * 连接建立期间允许预先编辑；运行期间也保留本地输入，以便提交普通 steering。
 */
export function canEditInput(
  phase: ReturnType<typeof reduceTuiState>['phase'],
): boolean {
  return phase === 'connecting' || phase === 'ready' || phase === 'submitting'
    || phase === 'accepted' || phase === 'running';
}

export function editInput(
  current: string,
  text: string,
  key: {readonly backspace: boolean; readonly ctrl: boolean; readonly meta: boolean},
): string {
  if (key.backspace) {
    return removeLastCodePoint(current);
  }
  return !key.ctrl && !key.meta && text.length > 0
    ? appendInput(current, text)
    : current;
}
