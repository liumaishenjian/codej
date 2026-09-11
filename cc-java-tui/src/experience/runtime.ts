import type {ProtocolEvent} from '../protocol.js';
import type {StdioClient} from '../stdio-client.js';

export interface Question {id: string; title: string; question: string; multiSelect: boolean; allowFreeText: boolean; options: {optionId: string; label: string; description: string}[]}
export interface Answer {questionId: string; optionIds: string[]; freeText: string}
export interface ApprovalPanel {kind: 'approval'; event: ProtocolEvent; id: string; ordinal: number; tool: string; command: string; shell: string; directory: string; target: string; operation: string; sessionScope: boolean}
export interface QuestionsPanel {kind: 'questions'; event: ProtocolEvent; callId: string; questions: Question[]; legacy: boolean}
export interface PlanPanel {kind: 'plan'; event: ProtocolEvent; planId: string; revision: number; contentDigest: string; workspaceDigest: string; markdown: string}
export type Pending = ApprovalPanel | QuestionsPanel | PlanPanel;
export interface Message {kind: 'user' | 'assistant' | 'notice'; id: string; run: string; turn: number; text: string}
export interface ToolRecord {kind: 'tool'; id: string; run: string; turn: number; ordinal: number; name: string; activity: string; status: 'running' | 'completed' | 'failed' | 'cancelled'; output: string; preview: string; shell: string; directory: string; truncated: boolean; failure: string; failureReasonCode: string; recoveredByOrdinal: number}
export type RecordBlock = Message | ToolRecord;
export interface RuntimeSnapshot {
  connection: 'connecting' | 'ready' | 'closed';
  status: 'idle' | 'starting' | 'running' | 'cancelling';
  session: string; workspace: string; model: string; mode: 'chat' | 'plan';
  blocks: RecordBlock[]; pending: Pending | undefined; plan: PlanPanel | undefined; showPlan: boolean;
  notice: string; activity: string; startedAt: number; revision: number; questionnaire: boolean;
}
export interface RuntimeClient {
  initialize(options?: {questionnaireV1?: boolean; experienceV1?: boolean; directedChunkInputV1?: boolean}): string;
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

/** 只将已验证stdio事件投影成视图；Java继续拥有执行、权限与取消决定。 */
export class ExperienceRuntime {
  state: RuntimeSnapshot;
  readonly #client: RuntimeClient;
  readonly #listeners = new Set<() => void>();
  #cleanup: (() => void)[] = [];
  #init = ''; #request = ''; #run = ''; #turn = 0; #sequence = -1; #serial = 0;
  #disposed = false; #cancelWanted = false; #execution = false; #completionNotice = '';
  readonly #decisions = new Map<string, Pending>();
  constructor(client: RuntimeClient, workspace: string) {
    this.#client = client;
    this.state = {connection: 'connecting', status: 'idle', session: '', workspace, model: '', mode: 'chat', blocks: [],
      pending: undefined, plan: undefined, showPlan: false, notice: '', activity: '正在连接 Java', startedAt: 0, revision: 0, questionnaire: false};
  }
  subscribe = (listener: () => void) => {this.#listeners.add(listener); return () => {this.#listeners.delete(listener);};};
  snapshot = () => this.state;
  connect(): void {
    if (this.#cleanup.length || this.#disposed) return;
    this.#cleanup = [this.#client.onEvent(event => this.accept(event)), this.#client.onFailure(() => this.fail('连接已断开。当前操作已停止，请重新启动。'))];
    if (this.#client.onRunHandshake) this.#cleanup.push(this.#client.onRunHandshake(notice => {
      if (notice.requestId === this.#request && notice.kind === 'timed_out') {
        this.patch({notice: '运行启动尚未确认。不会自动重试，请等待宿主结果或退出。'});
      }
    }));
    try {this.#init = this.#client.initialize({
      questionnaireV1: true,
      experienceV1: true,
      directedChunkInputV1: true,
    });}
    catch {this.fail('无法初始化 Java 连接。');}
  }
  dispose(): void {this.#disposed = true; for (const cleanup of this.#cleanup) cleanup(); this.#cleanup = []; this.#listeners.clear(); this.#decisions.clear();}
  patch(patch: Partial<RuntimeSnapshot>): void {
    if (this.#disposed) return;
    this.state = {...this.state, ...patch, revision: this.state.revision + 1};
    for (const listener of this.#listeners) listener();
  }
  fail(message: string): void {
    this.#request = ''; this.#run = ''; this.#cancelWanted = false; this.#decisions.clear();
    this.patch({connection: 'closed', status: 'idle', pending: undefined, showPlan: false, notice: message,
      blocks: this.state.blocks.map(block => block.kind === 'tool' && block.status === 'running' ? {...block, status: 'cancelled'} : block)});
  }
  private addNotice(value: string): void {
    this.patch({blocks: [...this.state.blocks, {kind: 'notice', id: 'note-' + ++this.#serial, run: this.#run, turn: this.#turn, text: value}]});
  }
  submit(value: string): boolean {
    const prompt = value.trim();
    if (!prompt || this.state.connection !== 'ready') return false;
    if (this.state.status !== 'idle' || this.state.pending) {this.patch({notice: '当前操作尚未结束，草稿已保留。'}); return false;}
    if (prompt === '/help') {this.patch({notice: '核心入口：/plan 进入计划，/plan 任务开始规划。Ctrl+O详情，PgUp/PgDn回看，Esc停止。'}); return true;}
    if (prompt === '/plan') {
      this.patch({mode: 'plan', showPlan: !!this.state.plan, notice: this.state.plan ? '' : '已进入计划模式。请输入需要规划的任务。'}); return true;
    }
    if (prompt.startsWith('/') && !prompt.startsWith('/plan ')) {this.patch({notice: '此界面仅提供 /plan 和 /help。'}); return false;}
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
      this.#request = request; this.#run = ''; this.#turn = 0; this.#cancelWanted = false; this.#execution = false; this.#completionNotice = '';
      this.patch({status: 'starting', showPlan: false, pending: undefined, notice: '', startedAt: Date.now(), activity: planning ? '正在规划' : '正在思考', mode: planning ? 'plan' : 'chat',
        blocks: [...this.state.blocks, {kind: 'user', id: 'user-' + ++this.#serial, run: request, turn: 0, text: task}]});
      return true;
    } catch {this.patch({notice: '运行未能启动，输入已保留；请检查模型配置和连接。'}); return false;}
  }
  cancel(): void {
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
      this.patch({pending: undefined, activity: '正在处理回答', notice: ''});
      this.addNotice(expected.questions.map(q => {
        const a = answers.find(item => item.questionId === q.id)!;
        return q.title + '：' + [...a.optionIds.map(id => q.options.find(o => o.optionId === id)!.label), a.freeText.trim()].filter(Boolean).join('、');
      }).join('\n'));
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
      this.#request = request; this.#run = ''; this.#turn = 0; this.#cancelWanted = false; this.#execution = decision === 'APPROVE_USER'; this.#completionNotice = '';
      this.patch({pending: undefined, showPlan: false, status: 'starting', startedAt: Date.now(),
        mode: decision === 'APPROVE_USER' ? 'chat' : 'plan', activity: decision === 'APPROVE_USER' ? '正在启动计划执行' : '正在处理计划决定', notice: ''});
      return true;
    } catch {this.patch({notice: '计划决定未能发送，当前计划保留。'}); return false;}
  }
  /** 连接层已验证结构；这里再次绑定当前请求生命周期，杜绝旧事件重开焦点。 */
  accept(event: ProtocolEvent): void {
    if (this.#disposed || event.sequence <= this.#sequence) return;
    if (event.type === 'initialized') {
      if (this.state.connection !== 'connecting' || event.requestId !== this.#init) return;
      this.#sequence = event.sequence; this.#request = ''; this.#run = ''; this.#cancelWanted = false; this.#decisions.clear();
      this.patch({connection: 'ready', session: event.sessionId ?? '', status: 'idle', pending: undefined, plan: undefined, showPlan: false,
        mode: 'chat', blocks: [], questionnaire: event.payload.questionnaireV1 === true, model: text(event.payload, 'model'),
        notice: event.payload.modelConfigured === false ? '尚未配置模型，请先通过现有 codej 配置入口完成配置。' : '', activity: ''});
      return;
    }
    if (this.state.connection === 'connecting' && event.type === 'protocol.error' && event.requestId === this.#init) {this.fail('Java 初始化请求被拒绝，请检查启动配置。'); return;}
    if (this.state.connection !== 'ready' || event.sessionId !== this.state.session) return;
    if (event.type === 'protocol.error' && this.#decisions.has(event.requestId)) {
      this.#sequence = event.sequence;
      const rejected = this.#decisions.get(event.requestId)!;
      if (rejected.kind !== 'plan' && (rejected.event.runId !== this.#run || (event.runId && event.runId !== this.#run))) return;
      this.#decisions.delete(event.requestId);
      this.patch({pending: this.#cancelWanted ? undefined : rejected, notice: '宿主未接受本次提交，内容已保留。', ...(rejected.kind === 'plan' ? {status: 'idle' as const} : {})});
      return;
    }
    if (event.requestId !== this.#request) return;
    if (this.#run && event.runId && event.runId !== this.#run) return;
    this.#sequence = event.sequence;
    const p = event.payload;
    if (event.type === 'run.started') {
      if (!event.runId || this.#run) return;
      this.#run = event.runId;
      this.patch({status: this.#cancelWanted ? 'cancelling' : 'running', model: text(p, 'requestModel') || text(p, 'model') || text(p, 'modelId') || this.state.model});
      if (this.#cancelWanted) this.sendCancel();
      return;
    }
    if (event.type === 'run.command.result') {
      if (p.disposition !== 'accepted') this.finish('启动请求未被接受。');
      return;
    }
    if (event.type === 'run.launch.failed') {this.finish('运行启动失败，请检查配置和当前计划状态。'); return;}
    if (event.type === 'plan.review.rejected') {this.finish('计划已取消，没有启动执行。'); return;}
    if (['run.completed', 'run.failed', 'run.cancelled'].includes(event.type)) {
      if (!this.#cancelWanted && !this.#completionNotice && typeof p.finalText === 'string' && p.finalText) this.assistant(p.finalText, true);
      const review = !this.#cancelWanted && event.type === 'run.completed' && this.state.pending?.kind === 'plan' ? this.state.pending : undefined;
      const missingPlan = !this.#cancelWanted && this.state.mode === 'plan' && !review
        && ((event.type === 'run.completed' && !text(p, 'finalText'))
          || (event.type === 'run.failed' && text(p, 'stopReason') === 'invalid_model_response'));
      this.finish(this.#cancelWanted || event.type === 'run.cancelled' ? '本轮已停止。' : this.#completionNotice || (missingPlan
        ? '本次未生成可审核的计划，未开始执行。请补充要求后继续规划。'
        : event.type === 'run.failed' ? '运行未完成：' + (text(p, 'stopReason') || '请检查连接或模型状态') : ''), review);
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
      this.#completionNotice = '计划执行未完成：' + (text(p, 'stopReason') || '运行失败') + '。';
      this.patch({notice: this.#completionNotice}); return;
    }
    if (event.type === 'model.turn.started') {this.#turn = count(p, 'turn'); this.patch({activity: this.state.mode === 'plan' ? '正在规划' : '正在思考'});}
    if (event.type === 'model.retry.scheduled') this.patch({activity: '模型请求暂时失败，正在等待重试'});
    if (event.type === 'model.text.delta') this.assistant(text(p, 'text'));
    if (event.type === 'tool.started' || event.type === 'tool.output' || event.type === 'tool.completed' || event.type === 'tool.failed') this.tool(event);
    if (event.type === 'approval.requested') {
      const pending: ApprovalPanel = {kind: 'approval', event, id: text(p, 'approvalId'), ordinal: count(p, 'ordinal'), tool: text(p, 'toolName'),
        command: text(p, 'command'), shell: text(p, 'shell'), directory: text(p, 'workingDirectory'), target: text(p, 'target'),
        operation: text(p, 'operation'), sessionScope: p.sessionScope === true};
      this.patch({pending, activity: '等待审批'});
      this.patch({blocks: this.state.blocks.map(block => block.kind === 'tool' && block.run === this.#run && block.ordinal === pending.ordinal
        ? {...block, preview: pending.command || pending.target, shell: pending.shell, directory: pending.directory} : block)});
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
      this.patch({pending, plan: pending, showPlan: true, activity: '计划已生成'});
    }
    if (event.type === 'protocol.error') this.finish('宿主拒绝了当前请求，请检查配置或重新启动。');
  }
  private assistant(value: string, final = false): void {
    const id = this.#run + ':assistant:' + this.#turn;
    const found = this.state.blocks.find(block => block.kind === 'assistant' && block.id === id) as Message | undefined;
    const message: Message = {kind: 'assistant', id, run: this.#run, turn: this.#turn, text: bounded(final ? value : (found?.text ?? '') + value)};
    this.patch({blocks: found ? this.state.blocks.map(block => block.id === id ? message : block) : [...this.state.blocks, message], activity: '正在回答'});
  }
  private tool(event: ProtocolEvent): void {
    const p = event.payload; const ordinal = count(p, 'ordinal'); const id = this.#run + ':tool:' + ordinal;
    const previous = this.state.blocks.find(block => block.kind === 'tool' && block.id === id) as ToolRecord | undefined;
    const tool: ToolRecord = previous ? {...previous} : {kind: 'tool', id, run: this.#run, turn: count(p, 'turn') || this.#turn, ordinal,
      name: text(p, 'toolName'), activity: '', status: 'running', output: '', preview: '', shell: '', directory: '', truncated: false, failure: '', failureReasonCode: '', recoveredByOrdinal: 0};
    if (event.type === 'tool.started') {tool.activity = text(p, 'activity'); tool.preview = text(p, 'command') || text(p, 'parametersPreview') || text(p, 'preview'); tool.shell = text(p, 'shell'); tool.directory = text(p, 'workingDirectory');}
    if (event.type === 'tool.output') {tool.output = bounded(tool.output + text(p, 'text')); tool.truncated ||= tool.output.includes('[界面内容已截断]');}
    if (event.type === 'tool.completed' || event.type === 'tool.failed') {
      tool.status = event.type === 'tool.completed' ? 'completed' : 'failed';
      const output = text(p, 'content') || text(p, 'output') || text(p, 'resultText');
      if (output) tool.output = bounded(output);
      if (p.contentRedacted === true) tool.output = '内容含敏感信息，未在终端展开';
      tool.truncated ||= p.truncated === true || p.outputTruncated === true || p.contentTruncated === true;
      tool.failure = text(p, 'errorCode');
      tool.failureReasonCode = text(p, 'failureReasonCode');
      if (typeof p.exitCode === 'number') tool.failure = '退出码 ' + p.exitCode;
      for (const [key, pending] of this.#decisions) if (pending.kind === 'approval' && pending.ordinal === ordinal) this.#decisions.delete(key);
    }
    let blocks = previous ? this.state.blocks.map(block => block.id === id ? tool : block) : [...this.state.blocks, tool];
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
    this.patch({blocks, activity: tool.activity || '正在处理工具'});
  }
  private finish(notice: string, pending?: PlanPanel): void {
    const cancelled = this.#cancelWanted; this.#request = ''; this.#run = ''; this.#decisions.clear(); this.#cancelWanted = false;
    this.patch({status: 'idle', pending, showPlan: !!pending, notice, activity: '', mode: this.#execution ? 'chat' : this.state.mode,
      blocks: this.state.blocks.map(block => block.kind === 'tool' && block.status === 'running' ? {...block, status: cancelled ? 'cancelled' : 'failed'} : block)});
  }
}
