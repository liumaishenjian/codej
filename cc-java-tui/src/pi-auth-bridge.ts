import {spawn, type ChildProcess, type SpawnOptions} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {TextDecoder} from 'node:util';
import type {ChildProcessSpec} from './stdio-client.js';

/** ADR-100 四路身份；品牌展示不参与认证路由，Codex 不接受 API Key。 */
export type PiAuthIdentity = {
  readonly backend: 'pi';
  readonly profileId: string;
} & ({readonly providerId: 'openai' | 'deepseek' | 'qwen-token-plan-cn'; readonly authMethod: 'API_KEY'}
  | {readonly providerId: 'openai-codex'; readonly authMethod: 'OAUTH'});
/** 精确存储代次只用于用户随后显式 activate；桥不激活、不持久化回执。 */
export type PiAuthReceipt = PiAuthIdentity & {readonly authEpoch: string};
export interface PiAuthCallbacks {
  readonly onPrompt: (prompt: {readonly promptId: number; readonly kind: 'secret' | 'manual_code'}) => void;
  readonly onPromptCancelled: (promptId: number) => void;
  readonly onAuthorizationUrl: (url: string) => void;
}
export type PiAuthFailureCode = 'BUSY' | 'IDENTITY' | 'SPAWN' | 'IO' | 'PROTOCOL' | 'LIMIT'
  | 'LOGIN' | 'CLEANUP' | 'OUTPUT' | 'CANCELLED' | 'TIMEOUT' | 'CALLBACK' | 'EXIT';
export type PiAuthResult = {readonly status: 'stored'; readonly receipt: PiAuthReceipt}
  | {readonly status: 'failed' | 'cancelled' | 'timed_out'; readonly code: PiAuthFailureCode};
/** ENV_REF 只传名称；不得将 OAuth 材料或环境值放入 argv。 */
export interface PiAuthLoginOptions { readonly environmentName?: string }
export interface PiAuthBridgeOptions {
  /** 测试只能缩短生产 300 秒总期限及 2 秒停止窗口。 */
  readonly timeoutMs?: number;
  readonly stopTimeoutMs?: number;
  readonly spawnProcess?: (executable: string, args: readonly string[], options: SpawnOptions) => ChildProcess;
}
const MAIN = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const AUTH_MAIN = 'io.github.liumaishenjian.ccjava.cli.auth.PiAuthBridgeMain';
const FRAME = 32 * 1024;
const TOTAL = 128 * 1024;
const MAX_EPOCH = '9223372036854775807';
const SERVER_CODES = new Set(['PROTOCOL', 'LIMIT', 'CANCELLED', 'LOGIN', 'CLEANUP', 'OUTPUT', 'TIMEOUT']);

/**
 * 从已经由启动器验证的 spec 派生独立双向 helper；本类不验证 JVM 参数的来源可信性。
 * 不借用普通 StdioClient.send，不接触 terminal/raw/input 所有权。环境只复制可信 spec，
 * 绝不从当前 process.env 补入凭据；调用方须按启动器的最小环境契约提供 spec。
 * 清理未知仍占用槽位；迟到 close 可释放槽位，却不能把先前失败升级成 stored。
 */
export class PiAuthBridge {
  readonly #spec: ChildProcessSpec;
  readonly #options: Required<PiAuthBridgeOptions>;
  #operation: Operation | undefined;

  public constructor(spec: ChildProcessSpec, options: PiAuthBridgeOptions = {}) {
    const main = spec.args.indexOf(MAIN);
    if (!spec.executable || !spec.cwd || /[\0\r\n]/u.test(spec.executable + spec.cwd)
      || spec.args.length > 64 || spec.args.some(a => !a || /[\0\r\n]/u.test(a))
      || main < 0 || spec.args.lastIndexOf(MAIN) !== main || spec.args.at(-1) !== '--stdio'
      || spec.args.indexOf('--stdio') !== spec.args.length - 1) throw new Error('PI_AUTH_SPEC');
    this.#spec = {executable: spec.executable, cwd: spec.cwd, args: spec.args.slice(0, main),
      env: {...(spec.env ?? {})}};
    const timeoutMs = options.timeoutMs ?? 300_000;
    const stopTimeoutMs = options.stopTimeoutMs ?? 2_000;
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 300_000
      || !Number.isSafeInteger(stopTimeoutMs) || stopTimeoutMs < 1 || stopTimeoutMs > 2_000)
      throw new Error('PI_AUTH_CONFIGURATION');
    this.#options = {timeoutMs, stopTimeoutMs, spawnProcess: options.spawnProcess ?? spawn};
  }

  /** 清理未确认时保持 true，即使本次 Promise 已报告固定失败。 */
  public active(): boolean { return this.#operation !== undefined; }
  public cancel(): void { this.#operation?.cancel(); }
  /** 转移一次输入缓冲的所有权；所有拒绝路径也擦除调用方缓冲。 */
  public submit(promptId: number, owned: Uint8Array): boolean {
    try { return this.#operation?.submit(promptId, owned) ?? false; }
    finally { owned.fill(0); }
  }
  public login(identity: PiAuthIdentity, callbacks: PiAuthCallbacks,
    options: PiAuthLoginOptions = {}): Promise<PiAuthResult> {
    if (this.active()) return Promise.resolve({status: 'failed', code: 'BUSY'});
    let snapshot: PiAuthIdentity;
    let environmentName: string | undefined;
    try {
      environmentName = options.environmentName;
      if (Object.keys(options).some(k => k !== 'environmentName')
        || environmentName !== undefined && (identity.authMethod !== 'API_KEY'
          || typeof environmentName !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/u.test(environmentName)))
        return Promise.resolve({status: 'failed', code: 'IDENTITY'});
      if (!validIdentity(identity)) return Promise.resolve({status: 'failed', code: 'IDENTITY'});
      snapshot = Object.freeze({...identity});
      if (!validIdentity(snapshot) || environmentName !== undefined && snapshot.authMethod !== 'API_KEY')
        return Promise.resolve({status: 'failed', code: 'IDENTITY'});
    } catch { return Promise.resolve({status: 'failed', code: 'IDENTITY'}); }
    const operation = new Operation(snapshot, callbacks, this.#options, environmentName, () => {
      if (this.#operation === operation) this.#operation = undefined;
    });
    this.#operation = operation;
    return operation.start(this.#spec);
  }
}

/** 一次性状态机；仅保存关联、计数和仍被 Writable 拥有的可擦除编码缓冲。 */
class Operation {
  readonly #id = randomUUID();
  readonly #identity: PiAuthIdentity;
  readonly #callbacks: PiAuthCallbacks;
  readonly #options: Required<PiAuthBridgeOptions>;
  readonly #release: () => void;
  #child: ChildProcess | undefined;
  #resolve!: (result: PiAuthResult) => void;
  #settled = false;
  #failure: Exclude<PiAuthResult, {status: 'stored'}> | undefined;
  #receipt: PiAuthReceipt | undefined;
  #terminal = false;
  #stopped = false;
  #stdinEnded = false;
  #stdinFinished = false;
  #outEnded = false;
  #errEnded = false;
  #exited = false;
  #closed = false;
  #exitCode: number | null = null;
  #prompt: number | undefined;
  #lastPrompt = 0;
  #inSequence = 0;
  #outSequence = 0;
  #bytes = 0;
  #frames = 0;
  #pending: Buffer<ArrayBufferLike> = Buffer.alloc(0);
  #writing = new Set<Buffer>();
  #queued: Buffer | undefined;
  #submittedPrompt: number | undefined;
  #timer: NodeJS.Timeout | undefined;
  #stopTimer: NodeJS.Timeout | undefined;
  #killTimer: NodeJS.Timeout | undefined;

  public constructor(identity: PiAuthIdentity, callbacks: PiAuthCallbacks,
    options: Required<PiAuthBridgeOptions>, readonly environmentName: string | undefined, release: () => void) {
    this.#identity = identity; this.#callbacks = callbacks; this.#options = options; this.#release = release;
  }

  public start(spec: ChildProcessSpec): Promise<PiAuthResult> {
    return new Promise(resolve => {
      this.#resolve = resolve;
      try {
        const child = this.#options.spawnProcess(spec.executable, [...spec.args, AUTH_MAIN,
          '--operation-id', this.#id, '--provider', this.#identity.providerId,
          '--profile', this.#identity.profileId, '--auth-method', this.#identity.authMethod,
          ...(this.environmentName === undefined ? [] : ['--environment-name', this.environmentName])],
        {cwd: spec.cwd, env: spec.env, shell: false, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
        this.#child = child;
        this.#timer = setTimeout(() => this.#fail('TIMEOUT', 'timed_out'), this.#options.timeoutMs);
        child.on('error', () => this.#fail('SPAWN'));
        child.on('exit', (code: number | null, signal: NodeJS.Signals | null) => {
          this.#exited = true; this.#exitCode = code;
          this.#eraseWrites();
          if (code !== 0 || signal !== null) this.#fail('EXIT');
          this.#shortDeadline();
          this.#complete();
        });
        child.on('close', (code: number | null, signal: NodeJS.Signals | null) => {
          this.#closed = true;
          this.#eraseWrites();
          // spawn 失败不一定发 exit；close 仍是 Node 已关闭直接子进程及管道的证据。
          if (!this.#exited || code !== this.#exitCode || signal !== null) this.#fail('EXIT');
          this.#complete();
        });
        if (child.stdin === null || child.stdout === null || child.stderr === null) {
          this.#fail('IO'); return;
        }
        child.stdin.on('error', () => this.#fail('IO'));
        child.stdin.on('finish', () => { this.#stdinFinished = true; this.#complete(); });
        child.stdout.on('error', () => this.#fail('IO'));
        child.stderr.on('error', () => this.#fail('IO'));
        child.stdout.on('data', (chunk: Buffer) => this.#stdout(chunk));
        child.stderr.on('data', (chunk: Buffer) => {
          if (!Buffer.isBuffer(chunk)) { this.#fail('PROTOCOL'); return; }
          if (this.#terminal && chunk.length > 0) this.#fail('PROTOCOL');
          this.#charge(chunk.length);
        });
        child.stdout.on('end', () => {
          this.#outEnded = true;
          if (this.#pending.length !== 0 || !this.#terminal) this.#fail('PROTOCOL');
          this.#shortDeadline(); this.#complete();
        });
        child.stderr.on('end', () => { this.#errEnded = true; this.#complete(); });
        child.stdout.on('close', () => { if (!this.#outEnded) this.#fail('IO'); });
        child.stderr.on('close', () => { if (!this.#errEnded) this.#fail('IO'); });
      } catch {
        if (this.#child === undefined) {
          this.#release(); this.#finish({status: 'failed', code: 'SPAWN'});
        } else this.#fail('SPAWN');
      }
    });
  }

  public submit(promptId: number, owned: Uint8Array): boolean {
    if (this.#failure || this.#settled || this.#stopped || this.#prompt !== promptId
      || owned.length === 0 || owned.length > 16_384) return false;
    try {
      // JSON/JS String 与 OS 管道副本不能可靠擦除；它们只作为局部编码临时值，
      // 不进入 React state、日志、异常或实例字段。只有自有 Uint8Array/Buffer 可保证清零。
      const value = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(owned);
      if (/[\u0000-\u001f\u007f-\u009f]/u.test(value)) return false;
      if (this.#queued !== undefined) return false;
      this.#prompt = undefined;
      this.#submittedPrompt = promptId;
      return this.#send('auth.response', {promptId, value});
    } catch { this.#fail('PROTOCOL'); return false; }
  }

  public cancel(): void {
    if (this.#settled || this.#failure) return;
    // 不把待发材料排入 Writable 内部：取消直接擦除本桥的单槽队列，不越过在途 write。
    if (!this.#stopped && this.#writing.size === 0) this.#send('auth.cancel', {});
    this.#fail('CANCELLED', 'cancelled');
  }

  #send(type: 'auth.response' | 'auth.cancel', payload: object): boolean {
    if (this.#queued !== undefined || this.#stdinEnded || this.#failure) return false;
    const bytes = Buffer.from(JSON.stringify({version: 1, operationId: this.#id,
      sequence: this.#outSequence, type, payload}) + '\n', 'utf8');
    // 排队前保守预留预算；取消丢弃的帧不退还额度，不发送的 sequence 则不递增。
    if (bytes.length > FRAME || !this.#charge(bytes.length, true)) {
      bytes.fill(0); this.#fail('LIMIT'); return false;
    }
    if (this.#writing.size !== 0) { this.#queued = bytes; return true; }
    return this.#write(bytes);
  }

  #write(bytes: Buffer): boolean {
    const stream = this.#child?.stdin;
    if (!stream?.writable || stream.destroyed) { bytes.fill(0); this.#fail('IO'); return false; }
    this.#outSequence++;
    this.#writing.add(bytes);
    try {
      stream.write(bytes, error => {
        bytes.fill(0); this.#writing.delete(bytes);
        if (error) this.#fail('IO');
        const queued = this.#queued; this.#queued = undefined;
        if (queued !== undefined) {
          if (this.#stopped || this.#failure || this.#settled) queued.fill(0);
          else this.#write(queued);
        }
        this.#endInput(); this.#complete();
      });
      return true;
    } catch {
      // 即使自定义 Writable 在抛错前取得引用，也等 exit/close 才擦除，不能假设没发送。
      this.#fail('IO'); return false;
    }
  }

  #charge(bytes: number, frame = false): boolean {
    this.#bytes += bytes;
    if (frame) this.#frames++;
    if (this.#bytes > TOTAL || this.#frames > 512) { this.#fail('LIMIT'); return false; }
    return true;
  }

  #stdout(chunk: Buffer): void {
    if (!Buffer.isBuffer(chunk)) { this.#fail('PROTOCOL'); return; }
    if (!this.#charge(chunk.length) || this.#failure || this.#settled) return;
    if (this.#terminal && chunk.length > 0) { this.#fail('PROTOCOL'); return; }
    // 原始字节先记账；未结束行及所有空白同样消耗上限。
    this.#pending = Buffer.concat([this.#pending, chunk]);
    while (this.#pending.length > 0 && !this.#failure) {
      const lf = this.#pending.indexOf(10);
      if ((lf < 0 && this.#pending.length >= FRAME) || lf + 1 > FRAME) {
        this.#fail('LIMIT'); return;
      }
      if (lf < 0) return;
      const line = this.#pending.subarray(0, lf);
      this.#pending = this.#pending.subarray(lf + 1);
      if (!this.#charge(0, true)) return;
      try {
        if (this.#terminal || line.includes(13)) throw invalid();
        this.#receive(parseStrict(new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(line)));
      } catch { this.#fail('PROTOCOL'); }
      if (this.#terminal && this.#pending.length !== 0) this.#fail('PROTOCOL');
    }
  }

  #receive(value: unknown): void {
    const frame = fields(value, ['version', 'operationId', 'sequence', 'type', 'payload']);
    if (frame.version !== 1 || frame.operationId !== this.#id || frame.sequence !== this.#inSequence++) throw invalid();
    const p = object(frame.payload);
    switch (frame.type) {
      case 'auth.prompt': {
        fields(p, ['promptId', 'kind']);
        if (this.environmentName !== undefined || this.#stopped || this.#prompt !== undefined || this.#queued !== undefined
          || p.promptId !== this.#lastPrompt + 1 || this.#lastPrompt >= 16
          || p.kind !== (this.#identity.authMethod === 'OAUTH' ? 'manual_code' : 'secret')) throw invalid();
        this.#submittedPrompt = undefined;
        this.#prompt = ++this.#lastPrompt;
        this.#notify(() => this.#callbacks.onPrompt({promptId: this.#lastPrompt, kind: p.kind as 'secret' | 'manual_code'}));
        break;
      }
      case 'auth.prompt_cancelled': {
        fields(p, ['promptId']);
        if (this.#stopped || (p.promptId !== this.#prompt && p.promptId !== this.#submittedPrompt)) throw invalid();
        const id = p.promptId as number; this.#prompt = undefined; this.#submittedPrompt = undefined;
        this.#discardQueued();
        this.#notify(() => this.#callbacks.onPromptCancelled(id));
        break;
      }
      case 'auth.url': {
        fields(p, ['url']);
        if (this.environmentName !== undefined || this.#stopped || this.#identity.providerId !== 'openai-codex' || typeof p.url !== 'string'
          || !validUrl(p.url)) throw invalid();
        this.#notify(() => this.#callbacks.onAuthorizationUrl(p.url as string));
        break;
      }
      case 'auth.input_stop':
        fields(p, []);
        if (this.#stopped) throw invalid();
        this.#stopped = true; this.#dropPrompt(); this.#shortDeadline(); this.#endInput();
        break;
      case 'auth.stored': {
        fields(p, ['backend', 'providerId', 'profileId', 'authMethod', 'authEpoch']);
        if (!this.#stopped || !this.#stdinFinished || !validIdentity({backend: p.backend,
          providerId: p.providerId, profileId: p.profileId, authMethod: p.authMethod})
          || p.backend !== this.#identity.backend || p.providerId !== this.#identity.providerId
          || p.profileId !== this.#identity.profileId || p.authMethod !== this.#identity.authMethod
          || !validEpoch(p.authEpoch)) throw invalid();
        this.#receipt = Object.freeze({...this.#identity, authEpoch: p.authEpoch});
        this.#terminal = true; this.#shortDeadline();
        break;
      }
      case 'auth.failed':
        fields(p, ['code']);
        if (typeof p.code !== 'string' || !SERVER_CODES.has(p.code)) throw invalid();
        this.#terminal = true; this.#fail(p.code as PiAuthFailureCode,
          p.code === 'CANCELLED' ? 'cancelled' : p.code === 'TIMEOUT' ? 'timed_out' : 'failed');
        break;
      default: throw invalid();
    }
  }

  #notify(call: () => void): void {
    if (this.#settled || this.#failure) return;
    try { call(); } catch { this.#fail('CALLBACK'); }
  }
  #discardQueued(): void {
    this.#queued?.fill(0); this.#queued = undefined;
  }
  #dropPrompt(): void {
    this.#discardQueued(); this.#submittedPrompt = undefined;
    const prompt = this.#prompt; this.#prompt = undefined;
    if (prompt !== undefined) {
      // 撤销输入归属是清理通知，亦在本地失败/取消时交付；迟到消息不会再次通知。
      try { this.#callbacks.onPromptCancelled(prompt); } catch { /* 原失败分类优先。 */ }
    }
  }
  #endInput(): void {
    if (!this.#stopped || this.#writing.size !== 0 || this.#stdinEnded) return;
    this.#stdinEnded = true;
    try { this.#child?.stdin?.end(); } catch { this.#fail('IO'); }
  }
  #fail(code: PiAuthFailureCode, status: 'failed' | 'cancelled' | 'timed_out' = 'failed'): void {
    if (this.#settled) return;
    this.#failure ??= {status, code};
    this.#receipt = undefined; this.#stopped = true; this.#dropPrompt();
    this.#pending = Buffer.alloc(0);
    this.#endInput(); this.#shortDeadline(); this.#complete();
  }
  #shortDeadline(): void {
    if (this.#stopTimer !== undefined || this.#settled) return;
    this.#stopTimer = setTimeout(() => {
      this.#failure ??= {status: 'failed', code: 'CLEANUP'};
      this.#receipt = undefined; this.#stopped = true; this.#dropPrompt();
      this.#killTimer = setTimeout(() => {
        this.#finish(this.#failure!); // 未 close 不释放 busy，也不擦除仍可能写出的缓冲。
      }, this.#options.stopTimeoutMs);
      // 只终止固定 Java 直接子进程；不宣称 Windows 任意后代或 OS sandbox 清理。
      try { this.#child?.kill('SIGKILL'); } catch { /* 等待实际 close，不把 kill 返回值当证据。 */ }
      this.#complete();
    }, this.#options.stopTimeoutMs);
  }
  #eraseWrites(): void {
    this.#discardQueued();
    for (const bytes of this.#writing) bytes.fill(0);
    this.#writing.clear();
  }
  #complete(): void {
    if (!this.#closed) return;
    this.#release();
    if (this.#settled) return;
    if (this.#failure) { this.#finish(this.#failure); return; }
    if (this.#terminal && this.#receipt && this.#exited && this.#exitCode === 0
      && this.#outEnded && this.#errEnded && this.#stdinFinished && this.#writing.size === 0) {
      this.#finish({status: 'stored', receipt: this.#receipt});
    } else this.#finish({status: 'failed', code: 'CLEANUP'});
  }
  #finish(result: PiAuthResult): void {
    if (this.#settled) return;
    this.#settled = true;
    clearTimeout(this.#timer); clearTimeout(this.#stopTimer); clearTimeout(this.#killTimer);
    this.#receipt = undefined;
    this.#resolve(result);
  }
}

function validIdentity(value: unknown): value is PiAuthIdentity {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const p = value as Record<string, unknown>;
  return Object.keys(p).length === 4 && p.backend === 'pi' && typeof p.profileId === 'string'
    && /^[a-z0-9][a-z0-9-]{0,62}$/u.test(p.profileId)
    && (p.providerId === 'openai-codex' ? p.authMethod === 'OAUTH'
      : ['openai', 'deepseek', 'qwen-token-plan-cn'].includes(p.providerId as string) && p.authMethod === 'API_KEY');
}
function validEpoch(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/u.test(value)
    && (value.length < MAX_EPOCH.length || value <= MAX_EPOCH);
}
function validUrl(value: string): boolean {
  if (!/^https:\/\/auth\.openai\.com(?::443)?(?:\/|\?|$)/u.test(value)
    || /[\s\\\u0000-\u001f\u007f]/u.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname === 'auth.openai.com'
      && !url.username && !url.password && !url.port;
  } catch { return false; }
}
function invalid(): Error { return new Error('PI_AUTH_PROTOCOL'); }
function object(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid();
  return value as Record<string, unknown>;
}
function fields(value: unknown, expected: readonly string[]): Record<string, unknown> {
  const p = object(value);
  if (Object.keys(p).length !== expected.length || expected.some(k => !Object.hasOwn(p, k))) throw invalid();
  return p;
}

/**
 * 有界独立 JSON 语法读取器。JSON.parse 只用于单个字符串 token；对象在丢失重复键前拒绝，
 * 包括转义后的同名键。深度、surrogate 与安全整数在构造值时验证，不借普通协议的宽松解析。
 */
function parseStrict(text: string): unknown {
  let i = 0;
  const whitespace = () => { while (i < text.length && /[ \t\n\r]/u.test(text[i]!)) i++; };
  const string = (): string => {
    const start = i++;
    let escaped = false;
    while (i < text.length) {
      const c = text[i++]!;
      if (c === '"' && !escaped) {
        const value: unknown = JSON.parse(text.slice(start, i));
        if (typeof value !== 'string' || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)) throw invalid();
        return value;
      }
      if (c === '\\' && !escaped) escaped = true; else escaped = false;
    }
    throw invalid();
  };
  const value = (depth: number): unknown => {
    if (depth > 64) throw invalid();
    whitespace();
    if (text[i] === '"') return string();
    if (text[i] === '{') {
      i++; whitespace();
      const result: Record<string, unknown> = Object.create(null) as Record<string, unknown>;
      if (text[i] === '}') { i++; return result; }
      for (;;) {
        whitespace(); if (text[i] !== '"') throw invalid();
        const key = string();
        if (Object.hasOwn(result, key)) throw invalid();
        whitespace(); if (text[i++] !== ':') throw invalid();
        result[key] = value(depth + 1); whitespace();
        const end = text[i++]; if (end === '}') return result;
        if (end !== ',') throw invalid();
      }
    }
    if (text[i] === '[') {
      i++; whitespace(); const result: unknown[] = [];
      if (text[i] === ']') { i++; return result; }
      for (;;) {
        result.push(value(depth + 1)); whitespace(); const end = text[i++];
        if (end === ']') return result; if (end !== ',') throw invalid();
      }
    }
    for (const [word, literal] of [['true', true], ['false', false], ['null', null]] as const) {
      if (text.startsWith(word, i)) { i += word.length; return literal; }
    }
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/u.exec(text.slice(i));
    if (!match) throw invalid();
    i += match[0].length;
    // 私有认证协议仅有整数数值字段，拒绝指数/小数写法以免舍入掩盖恶意输入。
    if (!/^-?(?:0|[1-9][0-9]*)$/u.test(match[0])) throw invalid();
    const number = Number(match[0]);
    if (!Number.isSafeInteger(number)) throw invalid();
    return number;
  };
  const result = value(0); whitespace(); if (i !== text.length) throw invalid();
  return result;
}
