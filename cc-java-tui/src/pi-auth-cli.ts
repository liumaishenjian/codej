import {isAbsolute, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import type {Readable, Writable} from 'node:stream';
import type {EventEmitter} from 'node:events';
import {PiAuthBridge, type PiAuthIdentity, type PiAuthResult} from './pi-auth-bridge.js';
import type {ChildProcessSpec} from './stdio-client.js';
import {piAuthEnvironment} from './pi-auth-environment.js';

/** 早于父Java的298秒工作期限结束，为桥停止与TTY还原留出正常路径的余量。 */
export const PI_AUTH_CLI_LOGIN_TIMEOUT_MS = 290_000;

export interface PiAuthCliArguments {
  readonly spec: ChildProcessSpec;
  readonly identity: PiAuthIdentity;
  readonly apiKeyStdin: boolean;
}
/** 仅接收父 Java 构造的可信启动参数；语法检查不等于证明 JVM flags 来源可信。 */
export function parsePiAuthCliArguments(argv: readonly string[]): PiAuthCliArguments {
  const bad = () => new Error('PI_AUTH_CLI_ARGUMENTS');
  const fields = new Map<string, string>();
  let apiKeyStdin = false;
  const names = ['--java', '--java-args-base64', '--cwd', '--provider', '--profile', '--auth-method'];
  if (argv.length < 12 || argv.length > 13) throw bad();
  for (let i = 0; i < argv.length; i++) {
    const name = argv[i]!;
    if (name === '--api-key-stdin') { if (apiKeyStdin) throw bad(); apiKeyStdin = true; continue; }
    const value = argv[++i];
    if (!names.includes(name) || fields.has(name) || !value || value.length > 131072
      || /[\u0000-\u001f\u007f]/u.test(value)) throw bad();
    fields.set(name, value);
  }
  if (fields.size !== names.length) throw bad();
  const executable = fields.get('--java')!, cwd = fields.get('--cwd')!;
  if (!isAbsolute(executable) || !isAbsolute(cwd) || executable.length > 32768 || cwd.length > 32768) throw bad();
  let args: unknown;
  try {
    const encoded = fields.get('--java-args-base64')!;
    if (!/^[A-Za-z0-9_-]+$/u.test(encoded)) throw bad();
    const bytes = Buffer.from(encoded, 'base64url');
    if (bytes.toString('base64url') !== encoded) throw bad();
    args = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch { throw bad(); }
  if (!Array.isArray(args) || args.length < 2 || args.length > 64
    || args.some(a => typeof a !== 'string' || !a || a.length > 32768 || /[\u0000-\u001f\u007f]/u.test(a))) throw bad();
  const providerId = fields.get('--provider')!, profileId = fields.get('--profile')!, authMethod = fields.get('--auth-method')!;
  if (!/^[a-z0-9][a-z0-9-]{0,62}$/u.test(profileId)
    || !(providerId === 'openai-codex' ? authMethod === 'OAUTH' && !apiKeyStdin
      : ['openai', 'deepseek', 'qwen-token-plan-cn'].includes(providerId) && authMethod === 'API_KEY')) throw bad();
  const spec = {executable, cwd, args: args as string[], env: {}};
  // 同一个桥负责固定主类/stdio 形状检查；绝不在此生成第二套认证命令。
  new PiAuthBridge(spec);
  return {spec, identity: {backend: 'pi', providerId, profileId, authMethod} as PiAuthIdentity, apiKeyStdin};
}

export interface PiAuthCliHost {
  readonly input: Readable & {isTTY?: boolean; isRaw?: boolean; setRawMode?: (raw: boolean) => unknown};
  readonly output: Writable & {isTTY?: boolean};
  readonly signals: EventEmitter;
  readonly environment?: Readonly<Record<string, string | undefined>>;
  readonly createBridge?: (spec: ChildProcessSpec, options: {timeoutMs: number}) => Pick<PiAuthBridge, 'login' | 'submit' | 'cancel'>;
}

/**
 * ADR-100 独立 TTY 壳：只管理本进程输入所有权，登录/EOF/存储权威全部复用 PiAuthBridge。
 * 固定 16KiB 自有缓冲可清零；JS String、Node/OS 管道副本不承诺可擦除。
 * 当前工作树无可引用的 SecretInputBuffer 导出，因此在本入口内保留最小字节编辑状态。
 */
export async function runPiAuthCli(argv: readonly string[], host: PiAuthCliHost): Promise<number> {
  const {input, output, signals} = host;
  let parsed: PiAuthCliArguments;
  const message = (text: string) => { output.write(text); };
  try { parsed = parsePiAuthCliArguments(argv); }
  catch { try { message('认证启动参数无效。\n'); } catch { /* 不输出底层诊断。 */ } return 2; }
  if ((!parsed.apiKeyStdin && (!input.isTTY || !output.isTTY || !input.setRawMode))
    || (parsed.apiKeyStdin && input.isTTY) || input.readableEncoding !== null
    || input.listenerCount('data') > 0 || input.listenerCount('readable') > 0 || input.readableFlowing === true) {
    try { message('认证需要独占安全输入；网页认证仅支持交互终端。\n'); } catch { /* 固定失败。 */ }
    return 2;
  }
  const bytes = Buffer.alloc(16384);
  let length = 0, prompt: number | undefined, stopped = false, reading = false, rawChanged = false;
  let lineEnd = false, cr = false, stdinUsed = false, inputEnded = false;
  const previousRaw = input.isRaw === true;
  const previouslyPaused = input.isPaused();
  let bridge: Pick<PiAuthBridge, 'login' | 'submit' | 'cancel'> | undefined;
  let cancelTimer: NodeJS.Timeout | undefined;
  let cancelDeadline!: (result: PiAuthResult) => void;
  const cancellation = new Promise<PiAuthResult>(r => { cancelDeadline = r; });
  const erase = () => { bytes.fill(0); length = 0; };
  const detach = () => {
    if (!reading) return;
    input.pause(); input.removeListener('data', onData); input.removeListener('end', onEnd); reading = false;
  };
  const cancel = () => {
    if (stopped) return;
    stopped = true; prompt = undefined; erase(); detach();
    // 桥通常在两次 2 秒停止窗口内收口；外壳另设上界，只报告未知而非伪报清理。
    cancelTimer = setTimeout(() => cancelDeadline({status: 'failed', code: 'CLEANUP'}), 4500);
    try { bridge?.cancel(); } catch { /* 无清理成功推断。 */ }
  };
  const submit = () => {
    const id = prompt;
    if (id === undefined || !length) { cancel(); return; }
    prompt = undefined;
    const owned = Buffer.from(bytes.subarray(0, length)); erase();
    try { if (!bridge?.submit(id, owned)) cancel(); } catch { cancel(); }
    finally { owned.fill(0); }
  };
  function onData(chunk: unknown): void {
    if (stopped) return;
    if (!Buffer.isBuffer(chunk) || chunk.length > 16386) { cancel(); return; }
    // 输入块由 Readable 拥有，不对调用方/OS 缓冲作擦除承诺。
    try {
      if (parsed.apiKeyStdin) {
        for (const byte of chunk) {
          if (lineEnd) { cancel(); return; }
          if (cr) { if (byte !== 10) { cancel(); return; } cr = false; lineEnd = true; continue; }
          if (byte === 13) { cr = true; continue; }
          if (byte === 10) { lineEnd = true; continue; }
          if (byte < 32 || byte > 126 || length === bytes.length) { cancel(); return; }
          bytes[length++] = byte;
        }
        return;
      }
      const currentPrompt = prompt;
      for (let i = 0; i < chunk.length; i++) {
        const byte = chunk[i]!;
        if (byte === 3 || byte === 27) { cancel(); return; }
        if (currentPrompt === undefined || prompt !== currentPrompt) continue;
        if (byte === 13 || byte === 10) {
          // 同一个 paste 块含第二行时拒绝整次输入；不让后半块流向下一提示。
          if (i + 1 < chunk.length && !(byte === 13 && i + 2 === chunk.length && chunk[i + 1] === 10)) { cancel(); return; }
          submit(); message('\n'); return;
        }
        if (byte === 8 || byte === 127) { if (length) bytes[--length] = 0; continue; }
        if (byte < 32 || byte > 126 || length === bytes.length) { cancel(); return; }
        bytes[length++] = byte;
      }
    } catch { cancel(); }
  }
  const onInputClose = () => { if (!inputEnded) cancel(); };
  function onEnd(): void {
    inputEnded = true; detach();
    if (!parsed.apiKeyStdin || cr || !length || prompt === undefined) { cancel(); return; }
    submit();
  }
  const startReading = () => {
    if (reading || stopped) return;
    reading = true; input.on('data', onData); input.on('end', onEnd); input.resume();
  };
  let code = 1;
  try {
    const spec = {...parsed.spec, env: piAuthEnvironment(host.environment ?? {})};
    const bridgeOptions = {timeoutMs: PI_AUTH_CLI_LOGIN_TIMEOUT_MS};
    bridge = host.createBridge?.(spec, bridgeOptions) ?? new PiAuthBridge(spec, bridgeOptions);
    input.on('error', cancel); input.on('close', onInputClose); output.on('error', cancel); output.on('close', cancel);
    signals.on('SIGINT', cancel); signals.on('SIGTERM', cancel); signals.on('SIGHUP', cancel);
    if (!parsed.apiKeyStdin) { rawChanged = true; input.setRawMode!(true); startReading(); }
    const result = await Promise.race([bridge.login(parsed.identity, {
      onPrompt: p => {
        if (stopped) return;
        erase(); prompt = p.promptId;
        if (parsed.apiKeyStdin) {
          if (stdinUsed || p.kind !== 'secret') { cancel(); return; }
          stdinUsed = true; startReading();
        } else message(p.kind === 'secret' ? '输入 API Key（隐藏输入；Enter 提交，Esc 取消）：\n'
          : '输入授权回调代码（隐藏输入；Enter 提交，Esc 取消）：\n');
      },
      onPromptCancelled: id => { if (prompt === id) { prompt = undefined; erase(); if (parsed.apiKeyStdin) detach(); } },
      onAuthorizationUrl: url => {
        if (stopped) return;
        if (parsed.apiKeyStdin || !output.isTTY) { cancel(); return; }
        message('请在浏览器完成授权：\n' + url + '\n');
      },
    }), cancellation]);
    code = !stopped && result.status === 'stored' ? 0 : 1;
  } catch { cancel(); }
  finally {
    clearTimeout(cancelTimer);
    stopped = true; erase(); detach();
    input.removeListener('error', cancel); input.removeListener('close', onInputClose);
    output.removeListener('error', cancel); output.removeListener('close', cancel);
    signals.removeListener('SIGINT', cancel); signals.removeListener('SIGTERM', cancel); signals.removeListener('SIGHUP', cancel);
    try { if (rawChanged) input.setRawMode!(previousRaw); } catch { code = 1; }
    // 本入口拒绝原先 flowing 的输入；恢复原先显式暂停状态，不消费后续秘密。
    if (previouslyPaused) input.pause();
  }
  try { message(code === 0 ? '已配置，尚未验证账号或模型访问；未激活或选择模型。\n'
    : '认证未确认完成；保存或清理结果可能需要核对。\n'); } catch { code = 1; }
  return code;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const code = await runPiAuthCli(process.argv.slice(2), {input: process.stdin, output: process.stdout, signals: process, environment: process.env});
  // 独立壳不因未确认关闭的 helper 管道无限驻留；退出只结束本 Node，不宣称 Java 后代已清理。
  const flushDeadline = setTimeout(() => process.exit(code), 250);
  process.stdout.write('', () => { clearTimeout(flushDeadline); process.exit(code); });
}
