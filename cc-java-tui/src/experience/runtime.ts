import {ExperienceAuth, isAuthCommand, type AuthPanel, type AuthClient} from './auth.js';
import type {ProtocolEvent} from '../protocol.js';
import type {StdioClient} from '../stdio-client.js';
import {DeliveryDiagnostics} from './diagnostics.js';
import {modelFailureNotice} from './model-failure.js';

export interface Question {id: string; title: string; question: string; multiSelect: boolean; allowFreeText: boolean; options: {optionId: string; label: string; description: string}[]}
export interface Answer {questionId: string; optionIds: string[]; freeText: string}
export interface FileChange {scope?: 'file' | 'fragment'; status: 'available' | 'redacted' | 'too_large'; before: string; after: string}
export interface ToolResultSummary {path: string; operation: string; verification?: 'verified'; replacements?: number; removedLines?: number; addedLines?: number}
export interface ApprovalPanel {kind: 'approval'; event: ProtocolEvent; id: string; ordinal: number; tool: string; command: string; shell: string; directory: string; target: string; operation: string; sessionScope: boolean; removedLines?: number; addedLines?: number; fileChange?: FileChange}

/** 旧宿主缺字段时保持未知；异常预览不进入绘制，不能把缺数据解释为零改动。 */
function filePreview(payload: Record<string, unknown>): Partial<ApprovalPanel> {
  const result: Partial<ApprovalPanel> = {};
  for (const key of ['removedLines', 'addedLines'] as const) {
    const value=payload[key];
    if (typeof value==='number' && Number.isSafeInteger(value) && value>=0) result[key]=value;
  }
  const value=payload.fileChange;
  if (value && typeof value==='object') {
    const change=value as Record<string,unknown>;
    if (change.status==='redacted' || change.status==='too_large') result.fileChange={status:change.status,before:'',after:''};
    else if (change.status==='available' && typeof change.before==='string' && typeof change.after==='string'
      && change.before.length<=6000 && change.after.length<=6000) result.fileChange={status:'available',before:change.before,after:change.after,scope:change.scope==='file'?'file':'fragment'};
  }
  return result;
}
export interface QuestionsPanel {kind: 'questions'; event: ProtocolEvent; callId: string; questions: Question[]; legacy: boolean}
export interface PlanPanel {kind: 'plan'; event: ProtocolEvent; planId: string; revision: number; contentDigest: string; workspaceDigest: string; markdown: string}
export type Pending = ApprovalPanel | QuestionsPanel | PlanPanel;
export interface Message {kind: 'user' | 'assistant' | 'notice'; id: string; run: string; turn: number; text: string}
export interface ToolRecord {kind: 'tool'; fileChange?: FileChange | undefined; resultSummary?: ToolResultSummary | undefined; callId?: string; id: string; run: string; turn: number; ordinal: number; name: string; activity: string; status: 'running' | 'completed' | 'failed' | 'cancelled'; output: string; preview: string; shell: string; directory: string; truncated: boolean; failure: string; failureReasonCode: string; failureCategory: string; argumentChangeRequired: boolean; retryable: boolean; recoveredByOrdinal: number; reviewRecovered?: boolean; returnedItems?: number}
export type RecordBlock = Message | ToolRecord;
export interface RuntimeSnapshot {
  auth?: AuthPanel | undefined;
  rejectedInput?: {id: number; text: string} | undefined;
  diagnostics?: readonly string[];
  connection: 'connecting' | 'ready' | 'closed';
  status: 'idle' | 'starting' | 'running' | 'cancelling';
  session: string; workspace: string; model: string; mode: 'chat' | 'plan';
  blocks: RecordBlock[]; pending: Pending | undefined; plan: PlanPanel | undefined; showPlan: boolean;
  notice: string; activity: string; startedAt: number; revision: number; questionnaire: boolean;
}
export interface RuntimeClient extends AuthClient {
  initialize(options?: {questionnaireV1?: boolean; experienceV1?: boolean; directedChunkInputV1?: boolean; authLifecycleV1?: boolean; piProviderV1?: boolean}): string;
  onEvent(listener: (event: ProtocolEvent) => void): () => void;
  onFailure(listener: (message: string) => void): () => void;
  onRunHandshake?: StdioClient['onRunHandshake'];
  startRun(prompt: string): string;
  startPlan(prompt: string, options?: {readonly verificationCorrection?: boolean}): string;
  cancelRun(): string;
  resolveApproval: StdioClient['resolveApproval'];
  resolveQuestion: StdioClient['resolveQuestion'];
  resolveQuestionnaire?(callId: string, answers: readonly Answer[]): string;
  resolvePlanReview: StdioClient['resolvePlanReview'];
  shutdown(): Promise<void>;
}
const text = (data: Readonly<Record<string, unknown>>, key: string): string => typeof data[key] === 'string' ? data[key] as string : '';
const count = (data: Readonly<Record<string, unknown>>, key: string): number => Number.isSafeInteger(data[key]) ? data[key] as number : 0;
const bounded = (value: string) => value.length <= 262144 ? value : value.slice(0, 262144) + '\n[界面内容已截断]';
const internalPlanTools = new Set(['task_list', 'task_get', 'task_create', 'task_update', 'revise_plan_artifact', 'request_plan_review', 'declare_plan_evidence']);
function resultSummary(value: unknown): ToolResultSummary | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const item = value as Record<string, unknown>;
  if (typeof item.path !== 'string' || !item.path || typeof item.operation !== 'string' || !item.operation) return undefined;
  const result: ToolResultSummary = {path: item.path, operation: item.operation};
  if (item.verification === 'verified') result.verification = 'verified';
  for (const key of ['replacements', 'removedLines', 'addedLines'] as const) {
    const number = item[key];
    if (typeof number === 'number' && Number.isSafeInteger(number) && number >= 0) result[key] = number;
  }
  return result;
}

/** 只将已验证stdio事件投影成视图；Java继续拥有执行、权限与取消决定。 */
export class ExperienceRuntime {
  state: RuntimeSnapshot;
  readonly auth: ExperienceAuth;
  readonly #client: RuntimeClient;
  readonly #listeners = new Set<(streaming: boolean) => void>();
  #cleanup: (() => void)[] = [];
  #init = ''; #request = ''; #run = ''; #turn = 0; #sequence = -1; #serial = 0;
  #disposed = false; #cancelWanted = false; #execution = false; #completionNotice = '';
  /** 计划内部纠正的摘要只在本轮终态交付，避免把内部 Tool 调用刷进对话。 */
  #planDiagnostics: string[] = [];
  readonly #decisions = new Map<string, Pending>();
  // 未确认答案不能进入不可撤销的终端历史；以控制请求保留，等对应工具终态消费。
  readonly #answerSummaries = new Map<string, string>();
  readonly #diagnostics = new DeliveryDiagnostics();
  #submittedInput = ''; #awaitingAcceptance = false;
  constructor(client: RuntimeClient, workspace: string) {
    this.#client = client;
    this.auth = new ExperienceAuth(client, (auth, notice, model) => this.patch({auth, ...(notice === undefined ? {} : {notice}), ...(model === undefined ? {} : {model})}));
    this.state = {connection: 'connecting', status: 'idle', session: '', workspace, model: '', mode: 'chat', blocks: [],
      pending: undefined, plan: undefined, showPlan: false, notice: '', activity: '正在启动', startedAt: 0, revision: 0, questionnaire: false};
  }
  subscribe = (listener: (streaming: boolean) => void) => {this.#listeners.add(listener); return () => {this.#listeners.delete(listener);};};
  snapshot = () => this.state;
  connect(): void {
    if (this.#cleanup.length || this.#disposed) return;
    this.#cleanup = [this.#client.onEvent(event => this.accept(event)), this.#client.onFailure(() => this.fail('连接已断开，无法确认后台执行结果。请重新启动后核对。'))];
    if (this.#client.onRunHandshake) this.#cleanup.push(this.#client.onRunHandshake(notice => {
      if (notice.requestId === this.#request && notice.kind === 'timed_out') {
        this.patch({notice: '运行启动尚未确认。不会自动重试，请等待宿主结果或退出。'});
      }
    }));
    try {this.#init = this.#client.initialize({
      questionnaireV1: true,
      experienceV1: true,
      directedChunkInputV1: true,
      authLifecycleV1: true,
      ...(this.#client.piLogin ? {piProviderV1: true} : {}),
    });}
    catch {this.fail('无法初始化 Java 连接。');}
  }
  dispose(): void {this.auth.invalidate(); this.#disposed = true; for (const cleanup of this.#cleanup) cleanup(); this.#cleanup = []; this.#listeners.clear(); this.#decisions.clear(); this.#answerSummaries.clear();}
  patch(patch: Partial<RuntimeSnapshot>, streaming = false): void {
    if (this.#disposed) return;
    this.state = {...this.state, ...patch, revision: this.state.revision + 1};
    for (const listener of this.#listeners) listener(streaming);
  }
  fail(message: string): void {
    this.#submittedInput='';this.#awaitingAcceptance=false;
    this.state = {...this.state, diagnostics: this.#diagnostics.transport()};
    this.#request = ''; this.#run = ''; this.#cancelWanted = false; this.#decisions.clear(); this.#answerSummaries.clear();
    if (this.state.auth) {this.auth.cancel(true); message = this.state.notice;}
    this.patch({connection: 'closed', status: 'idle', pending: undefined, showPlan: false, notice: message,
      blocks: this.state.blocks.map(block => block.kind === 'tool' && block.status === 'running' ? {...block, status: 'failed', failure: 'transport_lost'} : block)});
  }
  submit(value: string): boolean {
    const prompt = value.trim();
    if (!prompt || this.state.connection !== 'ready') return false;
    if (isAuthCommand(prompt)) {
      if (this.state.status !== 'idle' || this.state.pending || this.state.showPlan || this.state.auth) {this.patch({notice: '当前操作或面板尚未结束；认证命令不会排队执行。'}); return false;}
      return this.auth.open(prompt);
    }
    if (this.state.auth) return false;
    if (this.auth.required) {this.auth.open('/login'); return false;}
    if (this.state.status !== 'idle' || this.state.pending) {this.patch({notice: '当前操作尚未结束，草稿已保留。'}); return false;}
    if (prompt === '/help') {this.patch({notice: '核心入口：/plan 规划任务，/login 连接服务商，/logout 选择账号并确认退出；/connect、/auth、/models 保留高级入口。Ctrl+O详情，PgUp/PgDn回看，Esc停止。'}); return true;}
    if (prompt === '/plan') {
      this.patch({mode: 'plan', showPlan: !!this.state.plan, notice: this.state.plan ? '' : '已进入计划模式。请输入需要规划的任务。'}); return true;
    }
    if (prompt.startsWith('/') && !prompt.startsWith('/plan ')) {this.patch({notice: '此界面提供 /plan、/help、/login、/logout 与高级认证命令。'}); return false;}
    const explicitPlanTask = prompt.startsWith('/plan ');
    const planning = explicitPlanTask || this.state.mode === 'plan';
    const task = explicitPlanTask ? prompt.slice(6).trim() : prompt;
    if (!task) return false;
    try {
      const request = planning
        ? explicitPlanTask
          ? this.#client.startPlan(task, {verificationCorrection: true})
          : this.#client.startPlan(task)
        : this.#client.startRun(task);
      this.#request = request; this.#run = ''; this.#turn = 0; this.#cancelWanted = false; this.#execution = false; this.#completionNotice = ''; this.#planDiagnostics = [];
      this.#submittedInput = prompt; this.#awaitingAcceptance = true;
      this.patch({status: 'starting', rejectedInput: undefined, showPlan: false, pending: undefined, notice: '', startedAt: Date.now(), activity: planning ? '正在规划' : '正在思考', mode: planning ? 'plan' : 'chat',
        blocks: [...this.state.blocks, {kind: 'user', id: 'user-' + ++this.#serial, run: request, turn: 0, text: task}]});
      return true;
    } catch {this.patch({notice: '运行未能启动，输入已保留；请检查模型配置和连接。'}); return false;}
  }
  cancel(): void {
    if (this.state.auth) {this.auth.cancel(); return;}
    if (this.state.status === 'idle') {
      if (this.state.pending?.kind === 'plan') this.review(this.state.pending, 'REJECT', '');
      else this.patch({showPlan: false, notice: ''});
      return;
    }
    if (this.state.status === 'cancelling') return;
    this.#cancelWanted = true;
    this.patch({status: 'cancelling', pending: undefined, activity: '正在停止', notice: ''});
    if (this.#run) this.sendCancel();
  }
  private sendCancel(): void {try {this.#client.cancelRun();} catch {this.fail('无法确认取消结果，请退出后检查任务状态。');}}
  approve(expected: ApprovalPanel, decision: 'allow_once' | 'allow_session' | 'deny'): boolean {
    if (this.state.pending !== expected || this.state.status !== 'running' || this.#cancelWanted) return false;
    try {
      const request = this.#client.resolveApproval(expected.id, decision);
      this.#decisions.set(request, expected); this.patch({pending: undefined, notice: '', activity: '正在执行工具'}); return true;
    } catch {this.patch({notice: '审批未能发送，请检查连接后重试。'}); return false;}
  }
  answer(expected: QuestionsPanel, answers: readonly Answer[]): boolean {
    if (this.state.pending !== expected || this.state.status !== 'running' || this.#cancelWanted) return false;
    if (answers.length !== expected.questions.length || new Set(answers.map(a => a.questionId)).size !== answers.length) return false;
    for (const q of expected.questions) {
      const a = answers.find(value => value.questionId === q.id);
      if (!a || a.freeText.length > 2000 || (!a.optionIds.length && !a.freeText.trim()) || new Set(a.optionIds).size !== a.optionIds.length
        || a.optionIds.some(id => !q.options.some(option => option.optionId === id))
        || (!q.allowFreeText && !!a.freeText.trim()) || (!q.multiSelect && a.optionIds.length + (a.freeText.trim() ? 1 : 0) !== 1)) return false;
    }
    try {
      const request = expected.legacy
        ? this.#client.resolveQuestion(expected.callId, answers[0]!.optionIds[0]!)
        : this.#client.resolveQuestionnaire!(expected.callId, answers);
      this.#decisions.set(request, expected);
      this.#answerSummaries.set(request, expected.questions.map(q => {
        const a = answers.find(item => item.questionId === q.id)!;
        return q.title + '：' + [...a.optionIds.map(id => q.options.find(o => o.optionId === id)!.label), a.freeText.trim()].filter(Boolean).join('、');
        }).join('\n'));
      this.patch({pending: undefined, activity: '正在处理回答', notice: ''});
      return true;
    } catch {this.patch({notice: '回答未能发送，当前选择已保留。'}); return false;}
  }
  review(expected: PlanPanel, decision: 'APPROVE_USER' | 'CONTINUE_PLANNING' | 'REJECT', feedback: string): boolean {
    if (this.state.pending !== expected || this.state.status !== 'idle' || this.state.connection !== 'ready') return false;
    if (decision === 'CONTINUE_PLANNING' && !feedback.trim()) {this.patch({notice: '请输入具体修改意见。'}); return false;}
    try {
      const request = this.#client.resolvePlanReview({planId: expected.planId, revision: expected.revision, contentDigest: expected.contentDigest,
        workspaceDigest: expected.workspaceDigest, decision, contextPolicy: 'KEEP', feedback});
      this.#decisions.set(request, expected);
      this.#request = request; this.#run = ''; this.#turn = 0; this.#cancelWanted = false; this.#execution = decision === 'APPROVE_USER'; this.#completionNotice = ''; this.#planDiagnostics = [];
      this.#submittedInput = ''; this.#awaitingAcceptance = true;
      this.patch({pending: undefined, showPlan: false, status: 'starting', startedAt: Date.now(),
        mode: decision === 'APPROVE_USER' ? 'chat' : 'plan', activity: decision === 'APPROVE_USER' ? '正在启动计划执行' : '正在处理计划决定', notice: ''});
      return true;
    } catch {this.patch({notice: '计划决定未能发送，当前计划保留。'}); return false;}
  }
  /** 只恢复尚未启动且被明确拒绝的计划决定；不是执行失败后的自动重试。 */
  private restorePlanDecision(request: string): boolean {
    const pending=this.#decisions.get(request);
    if(pending?.kind!=='plan'||this.#run||!this.#awaitingAcceptance||request!==this.#request) return false;
    if(this.#cancelWanted) {this.finish('本轮已停止。');return true;}
    this.#decisions.delete(request);this.#request='';this.#execution=false;this.#awaitingAcceptance=false;
    this.patch({pending,plan:pending,showPlan:true,status:'idle',mode:'plan',activity:'',notice:'宿主未接受计划决定，原计划和修改意见已保留。请核对后再确认。'});
    return true;
  }
  /** 已明确拒绝的未接受输入可返回Composer；接受后或结果未知时不能走此路径。 */
  private rejectSubmission(): void {
    if(this.restorePlanDecision(this.#request)) return;
    const original=this.#cancelWanted?'':this.#submittedInput;
    this.finish(this.#cancelWanted?'本轮已停止。':'宿主拒绝启动本轮，原输入已保留；已有后续草稿时，可按 ↑ 找回原任务。');
    if(original) this.patch({rejectedInput:{id:++this.#serial,text:original}});
  }
  /** 连接层已验证结构；这里再次绑定当前请求生命周期，杜绝旧事件重开焦点。 */
  accept(event: ProtocolEvent): void {
    if (this.#disposed) return;
    const control=this.#decisions.has(event.requestId);
    const route=event.sequence<=this.#sequence?'old-sequence'
      :this.state.connection==='closed'?'closed'
      :this.state.connection==='connecting'?(event.requestId===this.#init?'current':'other-request')
      :event.sessionId!==this.state.session?'other-session'
      :event.requestId!==this.#request&&!control?'other-request'
      :this.#run&&event.runId&&event.runId!==this.#run?'other-run':'current';
    this.state={...this.state,diagnostics:this.#diagnostics.event(event,this.state.status,route)};
    if (event.sequence <= this.#sequence) return;
    if (event.type === 'initialized') {
      if (this.state.connection !== 'connecting' || event.requestId !== this.#init) return;
      this.#sequence = event.sequence; this.#request = ''; this.#run = ''; this.#cancelWanted = false; this.#decisions.clear(); this.#answerSummaries.clear();
      this.patch({connection: 'ready', session: event.sessionId ?? '', status: 'idle', pending: undefined, plan: undefined, showPlan: false,
        mode: 'chat', blocks: [], questionnaire: event.payload.questionnaireV1 === true, model: text(event.payload, 'model'),
        notice: '', activity: ''});
      this.auth.initialize(this.state.session, event.payload);
      return;
    }
    if (this.state.connection === 'connecting' && event.type === 'protocol.error' && event.requestId === this.#init) {this.fail('Java 初始化请求被拒绝，请检查启动配置。'); return;}
    if (this.state.connection !== 'ready') return;
    // protocol.error可以没有Session；认证控制器仍要求精确匹配已发request和当前操作代次。
    if (this.auth.accept(event)) {this.#sequence = event.sequence; return;}
    if (event.sessionId !== this.state.session) return;
    if (event.type === 'protocol.error' && this.#decisions.has(event.requestId)) {
      this.#sequence = event.sequence;
      if(this.restorePlanDecision(event.requestId)) return;
      const rejected = this.#decisions.get(event.requestId)!;
      if(rejected.kind==='plan') {
        this.patch({notice:'宿主已接受计划请求后报告错误，等待运行终态；不会自动重新执行。'}); return;
      }
      if (rejected.event.runId !== this.#run || (event.runId && event.runId !== this.#run)) return;
      this.#decisions.delete(event.requestId);
      this.#answerSummaries.delete(event.requestId);
      if(this.state.pending && this.state.pending!==rejected) return;
      this.patch({pending: this.#cancelWanted ? undefined : rejected, notice: '宿主未接受本次提交，内容已保留。'});
      return;
    }
    if (event.requestId !== this.#request) return;
    if (this.#run && event.runId && event.runId !== this.#run) return;
    this.#sequence = event.sequence;
    const p = event.payload;
    if (event.type === 'run.started') {
      if (!event.runId || this.#run) return;
      this.#run = event.runId;
      this.#awaitingAcceptance = false; this.#submittedInput = '';
      this.patch({status: this.#cancelWanted ? 'cancelling' : 'running', model: text(p, 'requestModel') || text(p, 'model') || text(p, 'modelId') || this.state.model});
      if (this.#cancelWanted) this.sendCancel();
      return;
    }
    if (event.type === 'run.command.result') {
      if (this.#run) return;
      if(p.disposition==='accepted'||p.disposition==='queued') {this.#awaitingAcceptance=false;this.#submittedInput='';}
      if (p.disposition === 'queued') this.patch({activity: this.#cancelWanted ? '等待宿主开始后停止' : '宿主已接收，等待开始本轮'});
      else if (p.disposition === 'rejected' && this.#awaitingAcceptance) {
        this.rejectSubmission();
      }
      return;
    }
    if (event.type === 'steering.discarded' && !this.#run) {
      this.finish(this.#cancelWanted ? '本轮已停止。' : '宿主已丢弃尚未开始的请求，本轮未执行。'); return;
    }
    if (event.type === 'run.launch.failed') {
      this.finish(p.code === 'MODEL_CONTEXT_BUDGET_INCOMPATIBLE'
        ? '模型窗口不足以容纳当前保留预算；请选择更大窗口模型，或显式调整 Context 参数。'
        : '运行启动失败，请检查配置和当前计划状态。');
      return;
    }
    if (event.type === 'plan.review.rejected') {this.finish('计划已取消，没有启动执行。'); return;}
    if (['run.completed', 'run.failed', 'run.cancelled'].includes(event.type)) {
      if (event.type === 'run.cancelled') this.#cancelWanted = true;
      if (!this.#cancelWanted && event.type !== 'run.cancelled' && typeof p.finalText === 'string' && p.finalText) this.assistant(p.finalText, true);
      const review = !this.#cancelWanted && event.type === 'run.completed' && this.state.pending?.kind === 'plan' ? this.state.pending : undefined;
      const missingPlan = !this.#cancelWanted && this.state.mode === 'plan' && !review
        && ((event.type === 'run.completed')
          || (event.type === 'run.failed' && text(p, 'stopReason') === 'invalid_model_response'));
      const lastTool = this.state.blocks.findLastIndex(block => block.kind === 'tool' && block.run === this.#run);
      const hasDelivery = text(p, 'finalText').trim() || this.state.blocks.some((block, index) => index > lastTool
        && block.kind === 'assistant' && block.run === this.#run && block.turn === this.#turn && block.text.trim());
      const emptyResult=event.type==='run.completed'&&!review&&!hasDelivery;
      const terminalNotice = this.#cancelWanted || event.type === 'run.cancelled' ? '本轮已停止。' : this.#completionNotice || modelFailureNotice(p.modelFailure) || (missingPlan
        ? '本次未生成可审核的计划，未开始执行。请补充要求后继续规划。'
        : event.type === 'run.failed' ? '运行未完成：' + (text(p, 'stopReason') || '请检查连接或模型状态')
        : emptyResult ? '本轮已结束，但宿主没有返回可显示的回答。Ctrl+O 可查看运行详情。' : '');
      this.finish(terminalNotice, review);
      return;
    }
    if (this.#cancelWanted) return;
    if (event.type === 'plan.verification.required') {
      this.#completionNotice = p.requiredEvidence === 0
        ? '计划缺少验收依据，未完成交付。当前执行已停止，不能把任务状态更新当作结果。'
        : '计划验收尚未通过，未完成交付。请检查本轮工具结果与未满足的验收要求。';
      this.patch({notice: this.#completionNotice}); return;
    }
    if (event.type === 'plan.verification.completed') {this.#completionNotice = ''; return;}
    if (event.type === 'plan.execution.failed') {
      this.#completionNotice = modelFailureNotice(p.modelFailure)
        || '计划执行未完成：' + (text(p, 'stopReason') || '运行失败') + '。';
      this.patch({notice: this.#completionNotice}); return;
    }
    if (event.type === 'model.turn.started') {this.#turn = count(p, 'turn'); this.patch({activity: this.state.mode === 'plan' ? '正在规划' : '正在思考'});}
    if (event.type === 'model.retry.scheduled') this.patch({activity: '模型请求暂时失败，正在等待重试'});
    if (event.type === 'model.text.delta') this.assistant(text(p, 'text'));
    if (event.type === 'tool.started' || event.type === 'tool.output' || event.type === 'tool.completed' || event.type === 'tool.failed') this.tool(event);
    if (event.type === 'approval.requested') {
      const pending: ApprovalPanel = {kind: 'approval', event, id: text(p, 'approvalId'), ordinal: count(p, 'ordinal'), tool: text(p, 'toolName'),
        command: text(p, 'command'), shell: text(p, 'shell'), directory: text(p, 'workingDirectory'), target: text(p, 'target'),
        operation: text(p, 'operation'), sessionScope: p.sessionScope === true, ...filePreview(p)};
      this.patch({pending, activity: '等待审批'});
      this.patch({blocks: this.state.blocks.map(block => block.kind === 'tool' && block.run === this.#run && block.ordinal === pending.ordinal
        ? {...block, preview: pending.command || pending.target, shell: pending.shell, directory: pending.directory, fileChange: pending.fileChange} : block)});
    }
    if (event.type === 'question.requested') {
      const legacy = !Array.isArray(p.questions);
      const options = (p.options ?? []) as Question['options'];
      const qs = legacy ? [{id: 'question', title: '问题', question: text(p, 'question'), multiSelect: false, allowFreeText: false, options}] : p.questions as Question[];
      this.patch({pending: {kind: 'questions', event, callId: text(p, 'callId'), questions: qs, legacy}, activity: '等待你的回答'});
    }
    if (event.type === 'plan.review.requested') {
      const pending: PlanPanel = {kind: 'plan', event, planId: text(p, 'planId'), revision: count(p, 'revision'), contentDigest: text(p, 'contentDigest'),
        workspaceDigest: text(p, 'workspaceDigest'), markdown: text(p, 'markdown')};
      // 宿主已发布当前Run审核面板，才证明此前同Run审核Gate已解除。保留失败历史。
      const recovered = this.state.notice === '审核条件已补齐，计划已提交审核'
        || this.state.blocks.some(block => block.kind === 'tool' && block.run === this.#run
          && block.name === 'request_plan_review' && block.status === 'failed' && block.failure === 'plan_gate_blocked');
      this.patch({pending, plan: pending, showPlan: true, activity: '计划已生成',
        ...(recovered ? {notice: '审核条件已补齐，计划已提交审核'} : {}),
        blocks: this.state.blocks.map(block => block.kind === 'tool' && block.run === this.#run
          && block.name === 'request_plan_review' && block.status === 'failed' && block.failure === 'plan_gate_blocked'
          ? {...block, reviewRecovered: true} : block)});
    }
    if (event.type === 'protocol.error') {
      if(this.#awaitingAcceptance&&!this.#run) this.rejectSubmission();
      else this.finish('宿主拒绝了当前请求，请检查配置或重新启动。');
    }
  }
  private assistant(value: string, final = false): void {
    const id = this.#run + ':assistant:' + this.#turn;
    const found = this.state.blocks.find(block => block.kind === 'assistant' && block.id === id) as Message | undefined;
    const message: Message = {kind: 'assistant', id, run: this.#run, turn: this.#turn, text: bounded(final ? value : (found?.text ?? '') + value)};
    this.patch({blocks: found ? this.state.blocks.map(block => block.id === id ? message : block) : [...this.state.blocks, message], activity: '正在回答'}, !final);
  }
  private addPlanDiagnostic(value: string): void {
    if (!this.#planDiagnostics.includes(value)) this.#planDiagnostics.push(value);
  }
  private tool(event: ProtocolEvent): void {
    const p = event.payload; const ordinal = count(p, 'ordinal'); const id = this.#run + ':tool:' + ordinal;
    const previous = this.state.blocks.find(block => block.kind === 'tool' && block.id === id) as ToolRecord | undefined;
    // 工具终态不可逆；不能让迟到增量重新暴露已脱敏结果或重启已结束调用。
    if (previous && (previous.status !== 'running'
      || (previous.callId && text(p, 'callId') && previous.callId !== text(p, 'callId'))
      || (previous.name && text(p, 'toolName') && previous.name !== text(p, 'toolName')))) return;
    const tool: ToolRecord = previous ? {...previous} : {kind: 'tool', id, run: this.#run, turn: count(p, 'turn') || this.#turn, ordinal,
      name: text(p, 'toolName'), activity: '', status: 'running', output: '', preview: '', shell: '', directory: '', truncated: false, failure: '', failureReasonCode: '', failureCategory: '', argumentChangeRequired: false, retryable: false, recoveredByOrdinal: 0};
    if (text(p, 'callId')) tool.callId = text(p, 'callId');
    if (event.type === 'tool.started') {tool.activity = text(p, 'activity'); tool.preview = text(p, 'command') || text(p, 'parametersPreview') || text(p, 'preview'); tool.shell = text(p, 'shell'); tool.directory = text(p, 'workingDirectory');}
    if (event.type === 'tool.output') {tool.output = bounded(tool.output + text(p, 'text')); tool.truncated ||= tool.output.includes('[界面内容已截断]');}
    if (event.type === 'tool.completed' || event.type === 'tool.failed') {
      tool.status = event.type === 'tool.completed' ? 'completed' : p.errorCode === 'cancelled' || p.errorCode === 'operation_cancelled' ? 'cancelled' : 'failed';
      const output = text(p, 'content') || text(p, 'output') || text(p, 'resultText');
      if (output) tool.output = bounded(output);
      if (p.contentRedacted === true) {tool.output = '内容含敏感信息，未在终端展开'; tool.fileChange = undefined;}
      tool.truncated ||= p.truncated === true || p.outputTruncated === true || p.contentTruncated === true;
      tool.resultSummary = resultSummary(p.resultSummary);
      // 缺失统计不等于零；只消费宿主结构化计数，不从输出正文猜测命中数。
      if (typeof p.returnedItems === 'number' && Number.isSafeInteger(p.returnedItems) && p.returnedItems >= 0) tool.returnedItems = p.returnedItems;
      tool.failure = text(p, 'errorCode');
      tool.failureReasonCode = text(p, 'failureReasonCode');
      // 仅消费 Java 已白名单化的布尔/分类元数据；不把校验正文或原始参数带到终端。
      tool.argumentChangeRequired = p.argumentChangeRequired === true;
      tool.retryable = p.retryable === true;
      const category = text(p, 'failureCategory');
      tool.failureCategory = /^[a-z][a-z0-9_]{0,63}$/u.test(category) ? category : '';
      if (typeof p.exitCode === 'number') tool.failure = '退出码 ' + p.exitCode;
      for (const [key, pending] of this.#decisions) if (pending.kind === 'approval' && pending.ordinal === ordinal) this.#decisions.delete(key);
    }
    let blocks = previous ? this.state.blocks.map(block => block.id === id ? tool : block) : [...this.state.blocks, tool];
    if (event.type === 'tool.completed' || event.type === 'tool.failed') {
      for (const [request, pending] of this.#decisions) {
        if (pending.kind !== 'questions' || pending.event.runId !== this.#run
          || !pending.callId || pending.callId !== text(p, 'callId')
          || text(p, 'toolName') !== (pending.legacy ? 'ask_plan_question' : 'ask_user_questions')) continue;
        const summary = this.#answerSummaries.get(request);
        this.#answerSummaries.delete(request);
        this.#decisions.delete(request);
        // 不能用本地答案绕过宿主结果脱敏，也不能把取消后的迟到成功当作确认。
        if (summary && event.type === 'tool.completed' && !this.#cancelWanted && p.contentRedacted !== true) {
          blocks = [...blocks, {kind: 'notice', id: 'note-' + ++this.#serial, run: this.#run, turn: this.#turn, text: summary}];
        }
      }
    }
    const recoveredFailureOrdinal = count(p, 'recoveredFailureOrdinal');
    if (event.type === 'tool.completed' && recoveredFailureOrdinal > 0) {
      const recoveredId = this.#run + ':tool:' + recoveredFailureOrdinal;
      blocks = blocks.map(block => block.kind === 'tool'
        && block.id === recoveredId
        && block.name === 'declare_plan_evidence'
        && block.status === 'failed'
        && block.failureReasonCode === 'verification_tool_unavailable'
        ? {...block, recoveredByOrdinal: ordinal} : block);
    }
    const pending = this.state.pending;
    const finishedApproval = tool.status !== 'running' && pending?.kind === 'approval'
      && pending.event.runId === this.#run && pending.ordinal === ordinal;
    const internalFailureNotice = tool.status === 'failed' && internalPlanTools.has(tool.name)
      ? tool.failure === 'plan_gate_blocked'
        ? '计划尚未满足审核条件，请补充计划内容或验收依据后继续。'
        : tool.failure === 'invalid_arguments'
          ? '计划内部步骤未能完成，正在等待模型纠正参数。'
          : '计划内部步骤未完成，请检查计划审核结果。'
      : undefined;
    if (tool.status === 'failed' && internalPlanTools.has(tool.name)
      && tool.failureReasonCode === 'verification_tool_unavailable') {
      this.addPlanDiagnostic('验证方式使用了当前不可用的工具');
    }
    const internalRecoveryNotice = event.type === 'tool.completed' && recoveredFailureOrdinal > 0
      ? '审核条件已补齐，计划已提交审核'
      : undefined;
    if (internalRecoveryNotice && tool.name === 'declare_plan_evidence') {
      this.addPlanDiagnostic('已修正验证方式，继续规划');
    }
    this.patch({blocks, pending: finishedApproval ? undefined : pending, activity: tool.status !== 'running' ? '正在处理工具结果'
      : tool.name === 'run_command' ? '正在执行命令' : tool.activity || '正在处理工具',
      ...(internalFailureNotice ? {notice: internalFailureNotice} : internalRecoveryNotice ? {notice: internalRecoveryNotice} : {})}, event.type === 'tool.output');
  }
  private finish(notice: string, pending?: PlanPanel): void {
    this.#awaitingAcceptance=false;this.#submittedInput='';
    const cancelled = this.#cancelWanted;
    const finalNotice = cancelled || !this.#planDiagnostics.length
      ? notice : [notice, ...this.#planDiagnostics].filter(Boolean).join('\n');
    this.#planDiagnostics = [];
    this.#request = ''; this.#run = ''; this.#decisions.clear(); this.#answerSummaries.clear(); this.#cancelWanted = false;
    this.patch({status: 'idle', pending, showPlan: !!pending, notice: finalNotice, activity: '', mode: this.#execution ? 'chat' : this.state.mode,
      blocks: this.state.blocks.map(block => block.kind === 'tool' && block.status === 'running' ? {...block, status: cancelled ? 'cancelled' : 'failed'} : block)});
  }
}
