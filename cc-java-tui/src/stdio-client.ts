import {
  spawn,
  spawnSync,
  type ChildProcess,
  type ChildProcessWithoutNullStreams,
  type SpawnOptions,
} from 'node:child_process';
import {EventEmitter} from 'node:events';
import {createHash} from 'node:crypto';
import {TextDecoder} from 'node:util';
import {
  MAX_LINE_BYTES,
  PROTOCOL_VERSION,
  ProtocolViolation,
  decodeEvent,
  encodeCommand,
  type ProtocolCommand,
  type ProtocolEvent,
} from './protocol.js';

export interface ChildProcessSpec {
  readonly executable: string;
  readonly args: readonly string[];
  readonly cwd: string;
  readonly env?: NodeJS.ProcessEnv;
}

export interface StdioClientOptions {
  readonly maxLineBytes?: number;
  readonly shutdownTimeoutMs?: number;
  readonly cancelTimeoutMs?: number;
  readonly providerLoginTimeoutMs?: number;
  /** Run-producing command 等待 Java acceptance 的有界期限。 */
  readonly runHandshakeTimeoutMs?: number;
  /** 仅供确定性生命周期测试注入 Windows 进程树终止结果。 */
  readonly windowsTreeKiller?: (pid: number) => boolean;
  /** 仅供确定性跨平台测试覆盖当前平台判断。 */
  readonly platform?: NodeJS.Platform;
}

export interface ProviderLoginRequest {
  readonly providerId: string;
  readonly profileId: string;
  readonly secretSource: 'store' | 'stdin' | 'env';
  readonly environmentName?: string;
  /** 仅首次配置的短生命周期缓冲；不得记录、序列化或转交 Agent stdio。 */
  readonly secretBytes?: Uint8Array;
  /** 仅显式 true 时把 profile 持久设为该 Provider 默认；省略保持旧接口的非默认语义。 */
  readonly setDefault?: boolean;
}

export interface RunHandshakeNotice {
  readonly requestId: string;
  readonly kind: 'timed_out' | 'late';
}

export interface ProviderLoginResult {
  readonly status: 'succeeded' | 'failed' | 'cancelled' | 'timed_out';
  readonly exitCode: number | null;
  /** 原始 secret 不进入 Node；该值仅是 Java 生成的有界脱敏投影。 */
  readonly credentialPreview?: string;
}

interface LoginTerminal {
  readonly isTTY?: boolean;
  readonly isRaw?: boolean;
  pause(): void;
  resume(): void;
  setRawMode?(mode: boolean): unknown;
}

type LoginSpawn = (
  executable: string,
  args: readonly string[],
  options: SpawnOptions,
) => ChildProcess;

export interface ProviderLoginBridgeOptions {
  readonly timeoutMs?: number;
  readonly spawnProcess?: LoginSpawn;
  readonly terminal?: LoginTerminal;
}

const JAVA_MAIN_CLASS = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,62}$/u;
const ENVIRONMENT_NAME = /^[A-Z][A-Z0-9_]{0,127}$/u;

/**
 * 从启动时已验证的 Java ChildProcessSpec 派生一次性认证进程。
 *
 * 该桥只固定替换主类后的参数，使用 shell=false 且直接继承终端输入输出。STORE 模式下 API key
 * 由 Java Console.readPassword 遮蔽读取，Node/Ink 不接收、不编码也不保存原始 secret；认证成功后
 * 仅从独立 stderr 管道接受一个严格校验的有界脱敏投影。
 */
export class ProviderLoginBridge {
  readonly #spec: ChildProcessSpec;
  readonly #timeoutMs: number;
  readonly #spawn: LoginSpawn;
  readonly #terminal: LoginTerminal;
  #active: ChildProcess | undefined;
  #cancelled = false;
  #loginClaimed = false;

  public constructor(spec: ChildProcessSpec, options: ProviderLoginBridgeOptions | number = {}) {
    this.#spec = validateJavaChildSpec(spec);
    const normalized = typeof options === 'number' ? {timeoutMs: options} : options;
    const timeoutMs = normalized.timeoutMs ?? 300_000;
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1_000 || timeoutMs > 900_000) {
      throw new Error('Provider login timeout 必须在 1000..900000ms');
    }
    this.#timeoutMs = timeoutMs;
    this.#spawn = normalized.spawnProcess ?? spawn;
    this.#terminal = normalized.terminal ?? process.stdin;
  }

  public active(): boolean {
    return this.#loginClaimed;
  }

  public cancel(): void {
    if (!this.#loginClaimed) return;
    this.#cancelled = true;
    this.#active?.kill();
  }

  public async login(request: ProviderLoginRequest): Promise<ProviderLoginResult> {
    if (this.#loginClaimed) throw new Error('已有 Provider login 正在执行');
    const args = providerLoginArguments(this.#spec.args, request);
    const terminal = this.#terminal;
    if (request.secretSource === 'store' && terminal.isTTY !== true) {
      throw new Error('STORE 登录需要可交互 TTY；可改用 /connect <provider> <profile> env <ENV_NAME>');
    }
    this.#loginClaimed = true;
    this.#cancelled = false;
    const wasRaw = terminal.isRaw === true;
    let paused = false;
    let rawChanged = false;
    try {
      terminal.pause();
      paused = true;
      if (terminal.isTTY === true && typeof terminal.setRawMode === 'function') {
        terminal.setRawMode(false);
        rawChanged = true;
      }
      const child = this.#spawn(this.#spec.executable, args, {
        cwd: this.#spec.cwd,
        env: this.#spec.env ?? process.env,
        shell: false,
        stdio: request.secretSource === 'stdin'
          ? ['pipe', 'inherit', 'pipe'] : ['inherit', 'inherit', 'pipe'],
        windowsHide: false,
      });
      this.#active = child;
      if (request.secretSource === 'stdin') {
        const source = request.secretBytes;
        if (source === undefined || source.byteLength === 0 || child.stdin === null) {
          child.kill();
          throw new Error('stdin credential 无效');
        }
        const payload = Buffer.alloc(source.byteLength + 1);
        payload.set(source); payload[payload.length - 1] = 0x0a;
        source.fill(0);
        child.stdin.end(payload, () => payload.fill(0));
      }
      return await new Promise<ProviderLoginResult>(resolve => {
        let settled = false;
        let timedOut = false;
        let diagnostic = '';
        child.stderr?.setEncoding('utf8');
        child.stderr?.on('data', (chunk: string) => {
          if (diagnostic.length < 4_096) diagnostic += chunk.slice(0, 4_096 - diagnostic.length);
        });
        const finish = (result: ProviderLoginResult) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          child.removeListener('error', onError);
          child.removeListener('exit', onExit);
          resolve(result);
        };
        const terminalStatus = () => timedOut ? 'timed_out' as const
          : this.#cancelled ? 'cancelled' as const : 'failed' as const;
        const onError = () => finish({status: terminalStatus(), exitCode: null});
        const onExit = (code: number | null) => finish({
          status: timedOut ? 'timed_out' : this.#cancelled ? 'cancelled'
            : code === 0 ? 'succeeded' : 'failed',
          exitCode: timedOut ? null : code,
          ...(code === 0 ? credentialPreviewFromDiagnostic(diagnostic) : {}),
        });
        const timer = setTimeout(() => {
          timedOut = true;
          child.kill();
        }, this.#timeoutMs);
        timer.unref();
        child.once('error', onError);
        child.once('exit', onExit);
      });
    } catch {
      return {status: this.#cancelled ? 'cancelled' : 'failed', exitCode: null};
    } finally {
      this.#active = undefined;
      this.#loginClaimed = false;
      if (rawChanged && typeof terminal.setRawMode === 'function') terminal.setRawMode(wasRaw);
      if (paused) terminal.resume();
    }
  }
}

/**
 * 维护 TUI 到 Java Headless 的结构化子进程连接。
 *
 * 该类只发送协议命令、验证事件并管理 Java 进程生命周期。它不执行 Tool、不解释
 * Agent 终态，也不把 stderr 内容展示给用户，从而避免诊断管道成为 Secret 泄漏路径。
 */
export class StdioClient {
  readonly #child: ChildProcessWithoutNullStreams;
  readonly #events = new EventEmitter();
  readonly #decoder = new TextDecoder('utf-8', {fatal: true});
  readonly #maxLineBytes: number;
  readonly #shutdownTimeoutMs: number;
  readonly #cancelTimeoutMs: number;
  readonly #loginSpec: ChildProcessSpec;
  readonly #providerLoginTimeoutMs: number;
  readonly #runHandshakeTimeoutMs: number;
  readonly #windowsTreeKiller: (pid: number) => boolean;
  readonly #platform: NodeJS.Platform;
  #loginBridge: ProviderLoginBridge | undefined;
  #pending = Buffer.alloc(0);
  #nextCommandSequence = 1;
  #nextEventSequence = 1;
  #nextRequestNumber = 1;
  #sessionId: string | undefined;
  #activeRunId: string | undefined;
  #transportClosed = false;
  #processExited = false;
  #treeTerminationRequested = false;
  #shutdownRequested = false;
  #failureEmitted = false;
  #cancelTimer: NodeJS.Timeout | undefined;
  #stderrBytes = 0;
  #issuedSessionCommandIds = new Set<string>();
  #pendingSessionCommands = new Map<string, string>();
  #pendingProviderControls = new Map<string, string>();
  #pendingRunCommands = new Map<string, {
    readonly commandType: 'run.start' | 'plan.start' | 'plan.review.resolve' | 'skill.invoke';
    disposition: 'submitting' | 'accepted' | 'queued';
    legacyQueuedSeen: boolean;
    timer: NodeJS.Timeout;
  }>();
  #terminalRunCommands = new Map<string, 'timed_out' | 'rejected'>();
  #pendingFileSuggestions = new Map<string, string>();
  #completedFileSuggestionIds = new Set<string>();
  static readonly #MAX_ISSUED_SESSION_COMMAND_IDS = 256;
  static readonly #MAX_PENDING_FILE_SUGGESTIONS = 256;
  static readonly #MAX_COMPLETED_FILE_SUGGESTIONS = 256;
  static readonly #MAX_TERMINAL_RUN_COMMANDS = 256;

  public constructor(spec: ChildProcessSpec, options: StdioClientOptions = {}) {
    this.#maxLineBytes = options.maxLineBytes ?? MAX_LINE_BYTES;
    this.#shutdownTimeoutMs = options.shutdownTimeoutMs ?? 2_000;
    this.#cancelTimeoutMs = options.cancelTimeoutMs ?? 2_000;
    this.#loginSpec = spec;
    this.#providerLoginTimeoutMs = options.providerLoginTimeoutMs ?? 300_000;
    this.#runHandshakeTimeoutMs = options.runHandshakeTimeoutMs ?? 5_000;
    if (!Number.isSafeInteger(this.#runHandshakeTimeoutMs)
      || this.#runHandshakeTimeoutMs < 50 || this.#runHandshakeTimeoutMs > 30_000) {
      throw new Error('Run acceptance timeout 必须在 50..30000ms');
    }
    this.#windowsTreeKiller = options.windowsTreeKiller ?? killWindowsProcessTree;
    this.#platform = options.platform ?? process.platform;
    this.#child = spawn(spec.executable, [...spec.args], {
      cwd: spec.cwd,
      env: spec.env ?? process.env,
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.#child.stdout.on('data', (chunk: Buffer) => this.#acceptStdout(chunk));
    this.#child.stderr.on('data', (chunk: Buffer) => {
      this.#stderrBytes += chunk.length;
    });
    this.#child.stdin.on('error', () => {
      if (!this.#processExited && !this.#shutdownRequested) {
        this.#fail('Java 子进程 stdin 连接失败');
      }
    });
    this.#child.once('error', error => {
      const code = 'code' in error && typeof error.code === 'string'
        ? error.code
        : 'UNKNOWN';
      this.#fail(`Java 子进程启动失败：${code}`);
    });
    this.#child.once('exit', (code, signal) => {
      this.#processExited = true;
      this.#transportClosed = true;
      this.#clearCancelTimer();
      if (this.#pending.length > 0) {
        this.#emitFailure('Java stdout 以不完整协议行结束');
      } else if (!this.#shutdownRequested && !this.#failureEmitted) {
        this.#emitFailure(unexpectedExitMessage(code, signal, this.#stderrBytes));
      }
      this.#pendingSessionCommands.clear();
      this.#pendingProviderControls.clear();
      this.#clearRunCommands();
      this.#terminalRunCommands.clear();
      this.#pendingFileSuggestions.clear();
      this.#completedFileSuggestionIds.clear();
      this.#issuedSessionCommandIds.clear();
      this.#events.emit('exit', {code, signal, stderrBytes: this.#stderrBytes});
    });
  }

  public onEvent(listener: (event: ProtocolEvent) => void): () => void {
    this.#events.on('event', listener);
    return () => this.#events.off('event', listener);
  }

  public onFailure(listener: (message: string) => void): () => void {
    this.#events.on('failure', listener);
    return () => this.#events.off('failure', listener);
  }

  /** 监听本地 acceptance watchdog 与迟到结果；该通知不会重放命令。 */
  public onRunHandshake(listener: (notice: RunHandshakeNotice) => void): () => void {
    this.#events.on('run-handshake', listener);
    return () => this.#events.off('run-handshake', listener);
  }

  public onExit(
    listener: (result: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number}) => void,
  ): () => void {
    this.#events.on('exit', listener);
    return () => this.#events.off('exit', listener);
  }

  public initialize(): string {
    return this.#send('initialize', {});
  }

  public startRun(prompt: string): string {
    return this.#startTextRun('run.start', prompt);
  }

  /** 以自然语言任务启动 Java 权威的只读 Plan Runtime。 */
  public startPlan(task: string): string {
    return this.#startTextRun('plan.start', task);
  }

  /** 以一个服务端原子命令收敛 durable review，并在批准时直接接受执行。 */
  public resolvePlanReview(input: {
    readonly planId: string;
    readonly revision: number;
    readonly contentDigest: string;
    readonly workspaceDigest: string;
    readonly decision: 'APPROVE_AUTO' | 'APPROVE_USER' | 'CONTINUE_PLANNING' | 'REJECT';
    readonly contextPolicy: 'KEEP' | 'CLEAR';
    readonly feedback: string;
  }): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined
      || this.#pendingRunCommands.size > 0) {
      throw new Error('只有就绪 Session 可以决定 Plan review');
    }
    const requestId = `tui-${this.#nextRequestNumber++}`;
    const createsRun = input.decision === 'APPROVE_AUTO' || input.decision === 'APPROVE_USER'
      || (input.decision === 'CONTINUE_PLANNING' && input.feedback.trim().length > 0);
    if (!createsRun) {
      return this.#send('plan.review.resolve', input, this.#sessionId, undefined, requestId);
    }
    return this.#sendRunCommand('plan.review.resolve', input, requestId);
  }

  /** 隐藏兼容方法；durable review 的 UI 不再调用第二次 plan.execute。 */
  public startPlanExecution(_planId: string, _workspaceDigest: string): string {
    throw new Error('durable Plan 使用 plan.review.resolve 原子执行交接');
  }

  #startTextRun(type: 'run.start' | 'plan.start', prompt: string): string {
    if (this.#sessionId === undefined) {
      throw new Error('Session 尚未初始化');
    }
    const encoded = Buffer.from(prompt, 'utf8');
    const requestId = `tui-${this.#nextRequestNumber++}`;
    const direct = this.#command(
      type, {prompt}, requestId, this.#nextCommandSequence, this.#sessionId,
    );
    this.#registerRunCommand(requestId, type);
    try {
      if (commandBytes(direct) < this.#maxLineBytes) {
        this.#write(direct);
      } else {
        if (type === 'plan.start') throw new Error('Plan 任务超过单条安全协议预算');
        const inputId = `input-${requestId}`;
        const chunks = protocolTextChunks(
          prompt,
          this.#maxLineBytes,
          (text, ordinal, sequence) => this.#command(
            'input.chunk', {inputId, ordinal, text}, requestId, sequence, this.#sessionId,
          ),
          this.#nextCommandSequence + 1,
        );
        if (chunks.length > 64) throw new Error('输入编码后需要超过 64 个协议分块');
        this.#write(this.#command('input.begin', {
          inputId,
          byteCount: encoded.byteLength,
          chunkCount: chunks.length,
          sha256: createHash('sha256').update(encoded).digest('hex'),
        }, requestId, this.#nextCommandSequence, this.#sessionId));
        chunks.forEach((text, ordinal) => {
          this.#write(this.#command(
            'input.chunk', {inputId, ordinal, text}, requestId,
            this.#nextCommandSequence, this.#sessionId,
          ));
        });
        this.#write(this.#command(
          'input.commit', {inputId}, requestId, this.#nextCommandSequence, this.#sessionId,
        ));
      }
      return requestId;
    } catch (error) {
      this.#removeRunCommand(requestId);
      throw error;
    }
  }

  /** 将当前 durable review revision 返回 DRAFT，随后可在同一 Session 继续 /plan。 */
  public returnPlanFeedback(planId: string, revision: number, contentDigest: string): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined
      || this.#pendingRunCommands.size > 0) {
      throw new Error('只有就绪 Session 可以返回 Plan 反馈');
    }
    return this.#send('plan.feedback', {planId, revision, contentDigest}, this.#sessionId);
  }

  /** 启动 Java 权威的显式 Skill Run。 */
  public invokeSkill(name: string, arguments_: string): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined
      || this.#pendingRunCommands.size > 0) {
      throw new Error('只有就绪 Session 可以显式调用 Skill');
    }
    const requestId = `tui-${this.#nextRequestNumber++}`;
    return this.#sendRunCommand('skill.invoke', {name, arguments: arguments_}, requestId);
  }

  public sessionCommand(
    commandId: string,
    intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume' | 'plan-status' | 'plan' | 'plan-approve' | 'plan-reject' | 'plan-step-begin' | 'plan-step-complete' | 'plan-execute',
    arguments_: Readonly<Record<string, unknown>>,
  ): string {
    if (this.#sessionId === undefined) {
      throw new Error('Session 尚未初始化');
    }
    if (this.#issuedSessionCommandIds.has(commandId)) {
      throw new Error('session.command commandId 已在当前连接签发');
    }
    if (this.#issuedSessionCommandIds.size >= StdioClient.#MAX_ISSUED_SESSION_COMMAND_IDS) {
      throw new Error('session.command commandId 签发数量超过上限');
    }
    const requestId = `tui-${this.#nextRequestNumber++}`;
    this.#issuedSessionCommandIds.add(commandId);
    this.#pendingSessionCommands.set(commandId, requestId);
    try {
      return this.#send('session.command', {
        protocolVersion: PROTOCOL_VERSION, commandId, intent, arguments: arguments_,
      }, this.#sessionId, undefined, requestId);
    } catch (error) {
      this.#pendingSessionCommands.delete(commandId);
      this.#issuedSessionCommandIds.delete(commandId);
      throw error;
    }
  }

  /** 发送不含 secret 的 Provider/Auth 本地控制命令。 */
  public providerControl(
    controlId: string,
    intent: 'providers.configure' | 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove',
    arguments_: Readonly<Record<string, unknown>>,
  ): string {
    if (this.#sessionId === undefined) throw new Error('Session 尚未初始化');
    if (this.#pendingProviderControls.has(controlId)) throw new Error('provider.control controlId 重复');
    const requestId = `tui-${this.#nextRequestNumber++}`;
    this.#pendingProviderControls.set(controlId, requestId);
    try {
      return this.#send('provider.control', {controlId, intent, arguments: arguments_},
        this.#sessionId, undefined, requestId);
    } catch (error) {
      this.#pendingProviderControls.delete(controlId);
      throw error;
    }
  }
  /** 通过继承终端的一次性 Java 进程执行登录；Agent stdio 连接不承载 secret。 */
  public providerLogin(request: ProviderLoginRequest): Promise<ProviderLoginResult> {
    const bridge = this.#loginBridge ??= new ProviderLoginBridge(this.#loginSpec, {
      timeoutMs: this.#providerLoginTimeoutMs,
    });
    return bridge.login(request);
  }

  /** 取消当前一次性登录进程。 */
  public cancelProviderLogin(): void {
    this.#loginBridge?.cancel();
  }

  /** 请求 Java 权威 Workspace 返回显式文件 mention 候选。 */
  public suggestFiles(query: string): string {
    if (this.#sessionId === undefined) {
      throw new Error('Session 尚未初始化');
    }
    if (this.#pendingFileSuggestions.size >= StdioClient.#MAX_PENDING_FILE_SUGGESTIONS) {
      throw new Error('file.suggest 待处理请求超过上限');
    }
    const requestId = `tui-${this.#nextRequestNumber++}`;
    this.#pendingFileSuggestions.set(requestId, query);
    try {
      return this.#send('file.suggest', {query}, this.#sessionId, undefined, requestId);
    } catch (error) {
      this.#pendingFileSuggestions.delete(requestId);
      throw error;
    }
  }

  public listCheckpoints(): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以列出 Checkpoint');
    }
    return this.#send('checkpoint.list', {}, this.#sessionId);
  }

  public checkpointDiff(checkpointId: string): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以比较 Checkpoint');
    }
    return this.#send('checkpoint.diff', {checkpointId}, this.#sessionId);
  }

  public undoCheckpoint(checkpointId: string, confirmed: boolean): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以执行 Undo');
    }
    return this.#send('checkpoint.undo', {checkpointId, confirmed}, this.#sessionId);
  }

  /** 查询 Java 权威子任务状态。 */
  public inspectTask(taskId: string): string {
    return this.#taskCommand('task.inspect', taskId, {});
  }

  /** 有界等待 Java 权威子任务状态；超时不会推断终态。 */
  public waitTask(taskId: string, timeoutMillis: number): string {
    if (!Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1 || timeoutMillis > 300_000) {
      throw new Error('子任务等待时间必须在 1..300000ms');
    }
    return this.#taskCommand('task.wait', taskId, {timeoutMillis});
  }

  /** 请求 Java 权威端取消子任务。 */
  public cancelTask(taskId: string): string {
    return this.#taskCommand('task.cancel', taskId, {});
  }

  /** 显式保留子任务绑定的 worktree。 */
  public keepTaskWorktree(taskId: string): string {
    return this.#taskCommand('task.keep', taskId, {});
  }

  /** 显式删除可证明 clean 的子任务 worktree。 */
  public removeTaskWorktree(taskId: string): string {
    return this.#taskCommand('task.remove', taskId, {});
  }

  #taskCommand(
    type: 'task.inspect' | 'task.wait' | 'task.cancel' | 'task.keep' | 'task.remove',
    taskId: string,
    payload: Readonly<Record<string, unknown>>,
  ): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以管理子任务');
    }
    if (!/^task-[A-Za-z0-9_-]{1,96}$/u.test(taskId)) {
      throw new Error('子任务 ID 无效');
    }
    return this.#send(type, {taskId, ...payload}, this.#sessionId);
  }

  public cancelRun(): string {
    if (this.#sessionId === undefined || this.#activeRunId === undefined) {
      throw new Error('当前没有可以取消的 Run');
    }
    const requestId = this.#send('run.cancel', {}, this.#sessionId, this.#activeRunId);
    this.#clearCancelTimer();
    this.#cancelTimer = setTimeout(() => {
      if (this.#activeRunId !== undefined && !this.#transportClosed) {
        this.#fail('Java 子进程未在取消期限内结束当前 Run');
      }
    }, this.#cancelTimeoutMs);
    this.#cancelTimer.unref();
    return requestId;
  }

  /**
   * 把用户对当前展示请求的单次决定发给 Java 权威端。
   */
  public resolveApproval(
    approvalId: string,
    decision: 'allow_once' | 'allow_session' | 'deny',
  ): string {
    if (this.#sessionId === undefined || this.#activeRunId === undefined) {
      throw new Error('当前没有可以审批的 Run');
    }
    return this.#send(
      'approval.resolve',
      {approvalId, decision},
      this.#sessionId,
      this.#activeRunId,
    );
  }

  /** 返回当前活动 Plan Run 中匹配 callId 的结构化单选答案。 */
  public resolveQuestion(callId: string, optionId: string): string {
    if (this.#sessionId === undefined || this.#activeRunId === undefined) {
      throw new Error('当前没有可以回答的 Plan 问题');
    }
    return this.#send('question.resolve', {callId, optionId}, this.#sessionId, this.#activeRunId);
  }

  public async shutdown(): Promise<void> {
    if (this.#processExited) return;
    this.#clearCancelTimer();
    if (!this.#shutdownRequested && !this.#transportClosed) {
      this.#send('shutdown', {}, this.#sessionId);
      this.#shutdownRequested = true;
      this.#child.stdin.end();
    }
    await this.#awaitExitOrTerminate();
  }

  /**
   * Print 在收到 Java 权威 Run terminal 后关闭 stdin，以 EOF 结束协议循环。
   *
   * <p>该路径不发送额外 shutdown 命令：先给 Java 一个短 grace 自然退出，超时后再可靠
   * 终止直接子进程并等待 exit。交互 TUI 继续使用 {@link shutdown} 的 graceful 协议。</p>
   */
  public async closePrintTransport(): Promise<void> {
    if (this.#processExited) return;
    this.#clearCancelTimer();
    this.#shutdownRequested = true;
    // 协议失败会先关掉 Transport；仍必须等 shutdownTimeout，不能把“已诊断”当成进程已退出。
    if (!this.#child.stdin.destroyed) {
      this.#child.stdin.end();
    }
    if (await this.#waitForExit(this.#shutdownTimeoutMs)) return;
    await this.#terminateAndAwaitExit();
  }

  async #awaitExitOrTerminate(): Promise<void> {
    if (await this.#waitForExit(this.#shutdownTimeoutMs)) return;
    await this.#terminateAndAwaitExit();
  }

  async #terminateAndAwaitExit(): Promise<void> {
    this.terminate();
    if (await this.#waitForExit(this.#shutdownTimeoutMs)) return;
    this.#child.kill('SIGKILL');
    if (!await this.#waitForExit(this.#shutdownTimeoutMs)) {
      throw new Error('Java 子进程在进程树终止和直接强制终止后仍未退出');
    }
  }

  public terminate(): void {
    this.#loginBridge?.cancel();
    if (!this.#processExited && !this.#treeTerminationRequested) {
      this.#shutdownRequested = true;
      this.#treeTerminationRequested = true;
      this.#killChildTree();
    }
  }

  #killChildTree(): void {
    const pid = this.#child.pid;
    if (this.#platform === 'win32' && pid !== undefined
      && this.#windowsTreeKiller(pid)) {
      return;
    }
    this.#child.kill('SIGKILL');
  }

  /**
   * 返回当前 Java 子进程 PID，仅用于生命周期观测与验证。
   */
  public processId(): number | undefined {
    return this.#child.pid;
  }

  /**
   * 判断协议 Transport 是否已经不可继续读写；该状态不代表子进程已经退出。
   */
  public isClosed(): boolean {
    return this.#transportClosed;
  }

  /**
   * 判断底层 Java 子进程是否已报告 exit/exitCode/signalCode。
   */
  public hasProcessExited(): boolean {
    return this.#processExited;
  }

  #sendRunCommand(
    type: 'run.start' | 'plan.start' | 'plan.review.resolve' | 'skill.invoke',
    payload: Readonly<Record<string, unknown>>,
    requestId: string,
  ): string {
    this.#registerRunCommand(requestId, type);
    try {
      return this.#send(type, payload, this.#sessionId, undefined, requestId);
    } catch (error) {
      this.#removeRunCommand(requestId);
      throw error;
    }
  }

  #registerRunCommand(
    requestId: string,
    commandType: 'run.start' | 'plan.start' | 'plan.review.resolve' | 'skill.invoke',
  ): void {
    if (this.#pendingRunCommands.has(requestId)) {
      throw new Error('Run command requestId 重复');
    }
    const timer = setTimeout(() => {
      const pending = this.#pendingRunCommands.get(requestId);
      if (pending === undefined || pending.disposition !== 'submitting') return;
      this.#pendingRunCommands.delete(requestId);
      this.#rememberRunCommandTerminal(requestId, 'timed_out');
      this.#events.emit('run-handshake', {requestId, kind: 'timed_out'} satisfies RunHandshakeNotice);
      // stdin 写入成功但 Java 没有给出 correlated disposition 时，执行结果属于 unknown。
      // 继续复用连接并允许重提可能让两个 Run 执行同一副作用，因此必须关闭 transport，
      // 由 Session recovery gate 决定后续动作，绝不在当前连接内自动重放。
      this.#fail('Java 未在期限内确认 Run 请求；连接已关闭以避免结果未知时重复执行');
    }, this.#runHandshakeTimeoutMs);
    timer.unref();
    this.#pendingRunCommands.set(requestId, {
      commandType, disposition: 'submitting', legacyQueuedSeen: false, timer,
    });
  }

  #removeRunCommand(requestId: string): void {
    const pending = this.#pendingRunCommands.get(requestId);
    if (pending !== undefined) clearTimeout(pending.timer);
    this.#pendingRunCommands.delete(requestId);
  }

  /**
   * 保留已超时或已拒绝请求的有界墓碑，使迟到的 acceptance/start 不能复活为不可见 Run。
   */
  #rememberRunCommandTerminal(requestId: string, terminal: 'timed_out' | 'rejected'): void {
    this.#terminalRunCommands.delete(requestId);
    this.#terminalRunCommands.set(requestId, terminal);
    while (this.#terminalRunCommands.size > StdioClient.#MAX_TERMINAL_RUN_COMMANDS) {
      const oldest = this.#terminalRunCommands.keys().next().value;
      if (oldest !== undefined) this.#terminalRunCommands.delete(oldest);
    }
  }

  #clearRunCommands(): void {
    for (const pending of this.#pendingRunCommands.values()) clearTimeout(pending.timer);
    this.#pendingRunCommands.clear();
  }

  #send(
    type: ProtocolCommand['type'],
    payload: Readonly<Record<string, unknown>>,
    sessionId?: string,
    runId?: string,
    fixedRequestId?: string,
  ): string {
    const requestId = fixedRequestId ?? `tui-${this.#nextRequestNumber++}`;
    this.#write(this.#command(
      type, payload, requestId, this.#nextCommandSequence, sessionId, runId,
    ));
    return requestId;
  }

  #command(
    type: ProtocolCommand['type'],
    payload: Readonly<Record<string, unknown>>,
    requestId: string,
    sequence: number,
    sessionId?: string,
    runId?: string,
  ): ProtocolCommand {
    return {
      version: PROTOCOL_VERSION,
      type,
      requestId,
      ...(sessionId === undefined ? {} : {sessionId}),
      ...(runId === undefined ? {} : {runId}),
      sequence,
      payload,
    };
  }

  #write(command: ProtocolCommand): void {
    if (this.#transportClosed || !this.#child.stdin.writable) {
      throw new Error('Java 子进程连接已关闭');
    }
    const encoded = encodeCommand(command);
    if (Buffer.byteLength(encoded, 'utf8') >= this.#maxLineBytes) {
      throw new Error('Client 协议行超过 Java reader 限制');
    }
    this.#child.stdin.write(encoded, 'utf8');
    this.#nextCommandSequence++;
  }

  #acceptStdout(chunk: Buffer): void {
    if (this.#transportClosed) {
      return;
    }
    this.#pending = Buffer.concat([this.#pending, chunk]);
    let newline = this.#pending.indexOf(0x0a);
    while (newline >= 0) {
      const rawLine = this.#pending.subarray(0, newline);
      this.#pending = this.#pending.subarray(newline + 1);
      if (rawLine.length > this.#maxLineBytes) {
        this.#fail('Java stdout 协议行超过大小限制');
        return;
      }
      const withoutCarriageReturn =
        rawLine.at(-1) === 0x0d ? rawLine.subarray(0, -1) : rawLine;
      try {
        const line = this.#decoder.decode(withoutCarriageReturn);
        const event = decodeEvent(line, this.#nextEventSequence);
        this.#nextEventSequence++;
        this.#observeAuthority(event);
        this.#events.emit('event', event);
      } catch (error) {
        const message = error instanceof ProtocolViolation
          ? error.message
          : 'Java stdout 包含无效 UTF-8';
        this.#fail(message);
        return;
      }
      newline = this.#pending.indexOf(0x0a);
    }
    if (this.#pending.length > this.#maxLineBytes) {
      this.#fail('Java stdout 协议行超过大小限制');
    }
  }

  #observeAuthority(event: ProtocolEvent): void {
    if (event.type === 'protocol.error') {
      this.#pendingFileSuggestions.delete(event.requestId);
      if (this.#pendingRunCommands.has(event.requestId)) {
        this.#removeRunCommand(event.requestId);
        this.#rememberRunCommandTerminal(event.requestId, 'rejected');
      }
      for (const [commandId, requestId] of this.#pendingSessionCommands) {
        if (requestId === event.requestId) this.#pendingSessionCommands.delete(commandId);
      }
      for (const [controlId, requestId] of this.#pendingProviderControls) {
        if (requestId === event.requestId) this.#pendingProviderControls.delete(controlId);
      }
    } else if (event.type === 'run.command.result') {
      if (event.sessionId !== this.#sessionId) {
        throw new ProtocolViolation('run.command.result 与当前 Session 不匹配');
      }
      const pending = this.#pendingRunCommands.get(event.requestId);
      if (pending === undefined) {
        const terminal = this.#terminalRunCommands.get(event.requestId);
        if (terminal === 'timed_out') {
          if (event.payload.disposition !== 'rejected') {
            throw new ProtocolViolation('迟到的 accepted/queued acceptance 无法安全关联，连接已关闭');
          }
          this.#events.emit('run-handshake', {
            requestId: event.requestId, kind: 'late',
          } satisfies RunHandshakeNotice);
          return;
        }
        if (terminal === 'rejected') {
          throw new ProtocolViolation('已拒绝 Run command 收到重复 acceptance，连接已关闭');
        }
        throw new ProtocolViolation('run.command.result 与待处理请求不匹配');
      }
      if (event.payload.commandType !== pending.commandType || pending.disposition !== 'submitting') {
        throw new ProtocolViolation('run.command.result 重复、乱序或命令类型错配');
      }
      clearTimeout(pending.timer);
      if (event.payload.disposition === 'rejected') {
        this.#pendingRunCommands.delete(event.requestId);
        this.#rememberRunCommandTerminal(event.requestId, 'rejected');
      } else {
        pending.disposition = event.payload.disposition as 'accepted' | 'queued';
      }
    } else if (event.type === 'run.launch.failed') {
      const pending = this.#pendingRunCommands.get(event.requestId);
      if (event.sessionId !== this.#sessionId
        || (pending?.disposition !== 'accepted' && pending?.disposition !== 'queued')) {
        throw new ProtocolViolation('run.launch.failed 与已接受请求不匹配');
      }
      this.#removeRunCommand(event.requestId);
      this.#rememberRunCommandTerminal(event.requestId, 'rejected');
    } else if (event.type === 'file.suggestions') {
      const expectedQuery = this.#pendingFileSuggestions.get(event.requestId);
      if (this.#completedFileSuggestionIds.has(event.requestId)
        || expectedQuery === undefined
        || event.sessionId !== this.#sessionId
        || event.payload.query !== expectedQuery) {
        throw new ProtocolViolation('file.suggestions 与待处理请求或当前 Session 不匹配');
      }
      this.#pendingFileSuggestions.delete(event.requestId);
      this.#completedFileSuggestionIds.add(event.requestId);
      if (this.#completedFileSuggestionIds.size > StdioClient.#MAX_COMPLETED_FILE_SUGGESTIONS) {
        const oldest = this.#completedFileSuggestionIds.values().next().value;
        if (oldest !== undefined) this.#completedFileSuggestionIds.delete(oldest);
      }
    } else if (event.type === 'steering.queued') {
      const pending = this.#pendingRunCommands.get(event.requestId);
      if (event.sessionId !== this.#sessionId || pending?.disposition !== 'queued'
        || pending.legacyQueuedSeen) {
        throw new ProtocolViolation('steering.queued 与 queued acceptance 不匹配');
      }
      pending.legacyQueuedSeen = true;
    } else if (event.type === 'steering.discarded') {
      const pending = this.#pendingRunCommands.get(event.requestId);
      if (event.sessionId !== this.#sessionId || pending?.disposition !== 'queued'
        || !pending.legacyQueuedSeen) {
        throw new ProtocolViolation('steering.discarded 与 queued acceptance 不匹配');
      }
      this.#removeRunCommand(event.requestId);
      this.#rememberRunCommandTerminal(event.requestId, 'rejected');
    } else if (event.type === 'provider.control.result') {
      const controlId = event.payload.controlId;
      if (typeof controlId !== 'string' || this.#pendingProviderControls.get(controlId) !== event.requestId
        || event.sessionId !== this.#sessionId) {
        throw new ProtocolViolation('provider.control.result 与待处理请求不匹配');
      }
      this.#pendingProviderControls.delete(controlId);
    } else if (event.type === 'session.command.result') {
      const commandId = event.payload.commandId;
      if (typeof commandId !== 'string') {
        throw new ProtocolViolation('session.command.result 缺少 commandId');
      }
      const requestId = this.#pendingSessionCommands.get(commandId);
      if (requestId === undefined || event.requestId !== requestId) {
        throw new ProtocolViolation('session.command.result 与待处理请求不匹配');
      }
      this.#pendingSessionCommands.delete(commandId);
      if (event.payload.intent === 'resume' && event.payload.status === 'succeeded') {
        const result = event.payload.result;
        if (typeof result === 'object' && result !== null && !Array.isArray(result)
          && typeof (result as Record<string, unknown>).previousSessionId === 'string'
          && (result as Record<string, unknown>).previousSessionId === this.#sessionId
          && typeof (result as Record<string, unknown>).resumedSessionId === 'string'
          && event.sessionId === (result as Record<string, unknown>).resumedSessionId) {
          this.#sessionId = event.sessionId;
          this.#clearRunCommands();
          this.#terminalRunCommands.clear();
          this.#pendingFileSuggestions.clear();
        } else {
          throw new ProtocolViolation('session.command.result resume 与当前 Session 不匹配');
        }
      }
    }
    if (event.type === 'initialized') {
      if (this.#sessionId !== undefined && this.#sessionId !== event.sessionId) {
        this.#pendingFileSuggestions.clear();
      }
      this.#sessionId = event.sessionId;
    } else if (event.type === 'run.started') {
      if (this.#terminalRunCommands.has(event.requestId)) {
        throw new ProtocolViolation('已超时或拒绝请求收到迟到的 run.started，连接已关闭');
      }
      if (event.sessionId !== this.#sessionId || this.#activeRunId !== undefined) return;
      const pending = this.#pendingRunCommands.get(event.requestId);
      if (pending?.disposition !== 'accepted' && pending?.disposition !== 'queued') return;
      this.#removeRunCommand(event.requestId);
      this.#activeRunId = event.runId;
    } else if (
      event.type === 'run.completed'
      || event.type === 'run.failed'
      || event.type === 'run.cancelled'
    ) {
      if (event.sessionId !== this.#sessionId || event.runId !== this.#activeRunId) return;
      this.#activeRunId = undefined;
      this.#clearCancelTimer();
    }
  }

  /**
   * 关闭协议 Transport 并通知失败，但不假装子进程已退出。
   *
   * <p>Print 必须继续走 {@link closePrintTransport} 等待真实 exit；交互 TUI
   * 在 failure 回调里再 terminate。若此处同步 SIGKILL，诊断写出后 promise
   * 会在同一轮完成，Print 无法区分“已诊断、仍在回收进程”。</p>
   */
  #fail(message: string): void {
    if (this.#failureEmitted) return;
    this.#transportClosed = true;
    this.#shutdownRequested = true;
    this.#clearCancelTimer();
    this.#pendingSessionCommands.clear();
    this.#pendingProviderControls.clear();
    this.#clearRunCommands();
    this.#terminalRunCommands.clear();
    this.#pendingFileSuggestions.clear();
    this.#completedFileSuggestionIds.clear();
    this.#issuedSessionCommandIds.clear();
    this.#emitFailure(message);
  }

  #emitFailure(message: string): void {
    if (this.#failureEmitted) {
      return;
    }
    this.#failureEmitted = true;
    this.#events.emit('failure', message);
  }

  #clearCancelTimer(): void {
    if (this.#cancelTimer !== undefined) {
      clearTimeout(this.#cancelTimer);
      this.#cancelTimer = undefined;
    }
  }

  async #waitForExit(timeoutMs: number): Promise<boolean> {
    if (this.#processExited || this.#child.exitCode !== null || this.#child.signalCode !== null) {
      this.#processExited = true;
      return true;
    }
    return await new Promise<boolean>(resolve => {
      let settled = false;
      const finish = (exited: boolean) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.#child.off('exit', onExit);
        resolve(exited);
      };
      const onExit = () => {
        this.#processExited = true;
        finish(true);
      };
      const timer = setTimeout(() => {
        const exited = this.#processExited
          || this.#child.exitCode !== null
          || this.#child.signalCode !== null;
        if (exited) this.#processExited = true;
        finish(exited);
      }, timeoutMs);
      timer.unref();
      this.#child.once('exit', onExit);
      if (this.#processExited || this.#child.exitCode !== null || this.#child.signalCode !== null) {
        this.#processExited = true;
        finish(true);
      }
    });
  }
}

function protocolTextChunks(
  text: string,
  maximumLineBytes: number,
  command: (text: string, ordinal: number, sequence: number) => ProtocolCommand,
  firstSequence: number,
): readonly string[] {
  const chunks: string[] = [];
  let points: string[] = [];
  let encodedTextBytes = 0;
  for (const point of text) {
    const pointBytes = jsonStringContentBytes(point);
    const ordinal = chunks.length;
    const emptyLineBytes = commandBytes(command('', ordinal, firstSequence + ordinal));
    if (points.length > 0 && emptyLineBytes + encodedTextBytes + pointBytes >= maximumLineBytes) {
      chunks.push(points.join(''));
      points = [];
      encodedTextBytes = 0;
    }
    const nextOrdinal = chunks.length;
    const nextEmptyLineBytes = commandBytes(command('', nextOrdinal, firstSequence + nextOrdinal));
    if (nextEmptyLineBytes + pointBytes >= maximumLineBytes) {
      throw new Error('单个 Unicode 字符无法放入协议分块');
    }
    points.push(point);
    encodedTextBytes += pointBytes;
  }
  if (points.length > 0) chunks.push(points.join(''));
  for (let ordinal = 0; ordinal < chunks.length; ordinal++) {
    if (commandBytes(command(chunks[ordinal]!, ordinal, firstSequence + ordinal)) >= maximumLineBytes) {
      throw new Error('Client 无法生成受限协议分块');
    }
  }
  return chunks;
}

function jsonStringContentBytes(text: string): number {
  return Buffer.byteLength(JSON.stringify(text), 'utf8') - 2;
}

function commandBytes(command: ProtocolCommand): number {
  return Buffer.byteLength(encodeCommand(command), 'utf8');
}

function validateJavaChildSpec(spec: ChildProcessSpec): ChildProcessSpec {
  if (spec.executable.length === 0 || /[\x00\r\n]/u.test(spec.executable)
    || spec.cwd.length === 0 || /[\x00\r\n]/u.test(spec.cwd)
    || spec.args.length < 1 || spec.args.length > 64
    || spec.args.some(value => value.length === 0 || /[\x00\r\n]/u.test(value))) {
    throw new Error('Java ChildProcessSpec 无效');
  }
  const mainIndex = spec.args.indexOf(JAVA_MAIN_CLASS);
  if (mainIndex < 0 || spec.args.indexOf(JAVA_MAIN_CLASS, mainIndex + 1) >= 0
    || spec.args.at(-1) !== '--stdio'
    || spec.args.indexOf('--stdio') !== spec.args.length - 1) {
    throw new Error('Java ChildProcessSpec 必须固定为唯一主类且以 --stdio 结尾');
  }
  return {executable: spec.executable, args: [...spec.args], cwd: spec.cwd, ...(spec.env === undefined ? {} : {env: spec.env})};
}

function providerLoginArguments(base: readonly string[], request: ProviderLoginRequest): string[] {
  if (!PROVIDER_ID.test(request.providerId) || !PROVIDER_ID.test(request.profileId)) {
    throw new Error('Provider/profile ID 无效');
  }
  if (request.setDefault !== undefined && typeof request.setDefault !== 'boolean') {
    throw new Error('setDefault 必须是 boolean');
  }
  const mainIndex = base.indexOf(JAVA_MAIN_CLASS);
  const fixed = base.slice(0, mainIndex + 1);
  const control = ['auth', 'login', '--provider', request.providerId, '--profile', request.profileId];
  if (request.secretSource === 'store') {
    if (request.environmentName !== undefined || request.secretBytes !== undefined) {
      throw new Error('STORE 不接受 ENV name/secret bytes');
    }
    control.push('--tui-preview');
  } else if (request.secretSource === 'stdin') {
    if (request.environmentName !== undefined || request.secretBytes === undefined
      || request.secretBytes.byteLength < 1 || request.secretBytes.byteLength > 16_384) {
      throw new Error('stdin credential 无效');
    }
    control.push('--api-key-stdin');
  } else {
    if (request.secretBytes !== undefined || request.environmentName === undefined
      || !ENVIRONMENT_NAME.test(request.environmentName)) {
      throw new Error('ENV name 无效');
    }
    control.push('--from-env', request.environmentName);
  }
  if (request.setDefault === true) control.push('--set-default');
  return [...fixed, ...control];
}

function credentialPreviewFromDiagnostic(diagnostic: string): {readonly credentialPreview?: string} {
  const match = /(?:^|\r?\n)CODEJ_CREDENTIAL_PREVIEW=([A-Za-z0-9_-]{3}\.\.\.[A-Za-z0-9_-]{4}|已保存（已隐藏）)(?:\r?\n|$)/u
    .exec(diagnostic);
  return match?.[1] === undefined ? {} : {credentialPreview: match[1].replace('...', '…')};
}

function killWindowsProcessTree(pid: number): boolean {
  const result = spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
    windowsHide: true,
    stdio: 'ignore',
    timeout: 1_000,
  });
  return result.error === undefined && result.status === 0;
}

function unexpectedExitMessage(
  code: number | null,
  signal: NodeJS.Signals | null,
  stderrBytes: number,
): string {
  const reason = code === null ? `signal=${signal ?? 'UNKNOWN'}` : `exit=${code}`;
  return `Java 子进程意外退出（${reason}，stderr=${stderrBytes} bytes）`;
}

/**
 * 在 Node 主进程退出时同步触发子进程终止，避免 TUI 异常退出后遗留 Java 进程。
 *
 * Node 的 exit 事件不能等待异步清理，因此这里只执行可同步发起的 kill；正常关闭仍由
 * {@link StdioClient.shutdown} 负责等待子进程真正退出。
 */
export function installProcessExitGuard(
  client: Pick<StdioClient, 'terminate'>,
): () => void {
  const terminateChild = () => client.terminate();
  process.once('exit', terminateChild);
  return () => process.off('exit', terminateChild);
}
