import {createModels} from '@earendil-works/pi-ai';
import {createRegisteredProviders} from './provider-registry.mjs';
import {createCredentialTransactions} from './credential-rpc.mjs';
import {createHostCredentialStore} from './credential-store.mjs';

const ROUTES = new Map([['openai', 'api_key'], ['deepseek', 'api_key'],
  ['qwen-token-plan-cn', 'api_key'], ['openai-codex', 'oauth']]);
const CODES = new Set(['AUTH_INVALID', 'AUTH_CLOSED', 'AUTH_CANCELLED', 'AUTH_ALREADY_RUN',
  'AUTH_INCOMPATIBLE', 'AUTH_LIMIT', 'AUTH_SEND_FAILED', 'AUTH_STORAGE_FAILED', 'AUTH_FAILED',
  'AUTH_PROMPT_CANCELLED']);
/** 只输出本地封闭码；不保留 SDK/宿主的 message、cause、提示正文或凭证。 */
export class AuthOperationError extends Error {
  constructor(code) {
    super(CODES.has(code) ? code : 'AUTH_FAILED');
    this.name = 'AuthOperationError'; this.code = this.message;
    Object.freeze(this);
  }
}
const exact = (value, keys) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const fields = Object.getOwnPropertyDescriptors(value);
  return Reflect.ownKeys(fields).length === keys.length && keys.every(key =>
    fields[key]?.enumerable && Object.hasOwn(fields[key], 'value'));
};
const inputValue = value => typeof value === 'string' && value.trim().length > 0
  && value.length <= 16384 && /^[\x20-\x7e]+$/.test(value);

/** URL 只供受保护的本次登录通道；不记录 state，不将宽松 URL 规范化用作白名单绕过。 */
function allowedUrl(value) {
  if (typeof value !== 'string' || value.length > 16384
      || !/^https:\/\/auth\.openai\.com\/oauth\/authorize\?[^\s\\#]+$/.test(value)) return false;
  try {
    const url = new URL(value);
    const redirect = url.searchParams.getAll('redirect_uri');
    const state = url.searchParams.getAll('state');
    return url.protocol === 'https:' && url.hostname === 'auth.openai.com' && !url.port
      && !url.username && !url.password && !url.hash && url.pathname === '/oauth/authorize'
      && redirect.length === 1 && redirect[0] === 'http://localhost:1455/auth/callback'
      && state.length === 1 && state[0].length > 0;
  } catch { return false; }
}

/**
 * ADR-100 / S15 MODEL-13 L1：一次登录、一个固定路由、一个待输入提示。
 * 只调用公开 Models.login；不创建 Agent、不读环境/账户文件、不实现 SDK 失效 fallback。
 * send 必须是外层受保护私有输出，accept 只接受 FrameSequence 已验证的帧。
 * Java 持有持久化权威；SDK 完成且持久 ACK 与所有通知写入完成后才返回 stored。
 * 本层不创建秘密字节缓冲区；尽快释放字符串引用，不承诺 JavaScript 字符串物理抹除。
 */
export function createAuthOperation({providerId, authType, signal, send,
  modelsFactory = createModels, providersFactory = createRegisteredProviders}) {
  if (!ROUTES.has(providerId) || ROUTES.get(providerId) !== authType || typeof send !== 'function'
      || typeof modelsFactory !== 'function' || typeof providersFactory !== 'function'
      || (signal !== undefined && !(signal instanceof AbortSignal))) throw new AuthOperationError('AUTH_INVALID');
  const controller = new AbortController();
  let closed = false, started = false, failure, pending, nextPrompt = 1, persisted = false;
  let rpc;
  const tombstones = new Set();
  const writes = new Set();
  let rejectStopped;
  const stopped = new Promise((_, reject) => { rejectStopped = reject; });
  // 取消可能早于 run；提前安装处理器，且不依赖上游正确消费每个回调 Promise。
  void stopped.catch(() => {});
  function detachPrompt(entry) {
    entry.signal?.removeEventListener('abort', entry.abort);
    if (pending === entry) pending = undefined;
  }
  function seal(code) {
    if (closed) return;
    closed = true; failure = code;
    signal?.removeEventListener('abort', externalAbort);
    if (pending) {
      const entry = pending; detachPrompt(entry); entry.value = undefined;
      entry.reject(new AuthOperationError(code));
    }
    tombstones.clear();
    controller.abort(new AuthOperationError(code));
    rpc?.close();
    rejectStopped(new AuthOperationError(code));
  }
  function check() { if (closed) throw new AuthOperationError(failure ?? 'AUTH_CLOSED'); }
  function fail(code) { seal(code); throw new AuthOperationError(code); }
  function externalAbort() { seal('AUTH_CANCELLED'); }
  // 每个异步写入都有拒绝处理器。通知 API 同步返回，但失败立即封闭整次登录。
  function emit(type, payload) {
    check();
    const write = Promise.resolve().then(() => { check(); return send(type, payload); });
    const tracked = write.then(() => {}, () => { seal('AUTH_SEND_FAILED'); });
    writes.add(tracked);
    void tracked.then(() => { writes.delete(tracked); });
    return tracked;
  }
  async function drain() {
    while (writes.size) { await Promise.race([Promise.all([...writes]), stopped]); check(); }
    check();
  }
  rpc = createCredentialTransactions({
    async send(type, payload) {
      await drain(); check();
      // LOGIN 不授予删除/保留权限，不能用成功的空事务冒充已保存登录。
      if (payload.action === 'finish' && payload.arguments.change.kind !== 'put') fail('AUTH_STORAGE_FAILED');
      return send(type, payload);
    },
    onFatal() { seal('AUTH_STORAGE_FAILED'); },
  });
  const hostStore = createHostCredentialStore({providerId, transactions: rpc.transactions});
  const credentials = Object.freeze({
    read: (...args) => { check(); return hostStore.read(...args); },
    list: (...args) => { check(); return hostStore.list(...args); },
    delete: () => { fail('AUTH_STORAGE_FAILED'); },
    async modify(id, fn, options) {
      await drain();
      const result = await hostStore.modify(id, async current => {
        check();
        const candidate = await fn(current);
        await drain(); check();
        if (!candidate || candidate.type !== authType) fail('AUTH_STORAGE_FAILED');
        return candidate;
      }, options);
      check();
      if (!result || result.type !== authType) fail('AUTH_STORAGE_FAILED');
      persisted = true;
      return result;
    },
  });
  function settlePrompt(entry) {
    if (pending !== entry || !entry.sent || !entry.received || closed) return;
    detachPrompt(entry);
    const value = entry.value; entry.value = undefined;
    entry.resolve(value);
  }
  function prompt(request) {
    const operation = (async () => {
      check();
      if (!request || (request.signal !== undefined && !(request.signal instanceof AbortSignal))) fail('AUTH_INCOMPATIBLE');
      if (request.signal?.aborted) throw new AuthOperationError('AUTH_PROMPT_CANCELLED');
      if (pending) fail('AUTH_INVALID');
      if (request.type === 'select') {
        if (providerId !== 'openai-codex' || !Array.isArray(request.options)
            || request.options.filter(option => option?.id === 'browser').length !== 1) fail('AUTH_INCOMPATIBLE');
        return 'browser';
      }
      if (!(request.type === 'secret' && authType === 'api_key')
          && !(request.type === 'manual_code' && providerId === 'openai-codex')) fail('AUTH_INCOMPATIBLE');
      if (nextPrompt > 16) fail('AUTH_LIMIT');
      return new Promise((resolve, reject) => {
        const entry = {id: nextPrompt++, signal: request.signal, resolve, reject,
          sent: false, received: false, value: undefined};
        entry.abort = () => {
          if (pending !== entry || closed) return;
          detachPrompt(entry); entry.value = undefined;
          // 已收到过输入但仍等输出 ACK 时取消，也不能给重复响应第二次机会。
          if (!entry.received) tombstones.add(entry.id); // 最多16项，仅消费一次迟到输入。
          emit('auth.prompt_cancelled', {promptId: entry.id});
          reject(new AuthOperationError('AUTH_PROMPT_CANCELLED'));
        };
        pending = entry;
        // 先排入提示输出，再注册取消；同一微任务队列保持 prompt/cancelled 的线序。
        const sent = emit('auth.prompt', {promptId: entry.id, kind: request.type});
        entry.signal?.addEventListener('abort', entry.abort, {once: true});
        if (entry.signal?.aborted) entry.abort();
        void sent.then(() => { entry.sent = true; settlePrompt(entry); });
      });
    })();
    void operation.catch(() => {});
    return operation;
  }
  function notify(event) {
    check();
    if (event?.type === 'info' || event?.type === 'progress') return;
    if (event?.type !== 'auth_url' || providerId !== 'openai-codex') fail('AUTH_INCOMPATIBLE');
    if (!allowedUrl(event.url)) fail('AUTH_INVALID');
    emit('auth.url', {url: event.url});
  }
  signal?.addEventListener('abort', externalAbort, {once: true});
  if (signal?.aborted) externalAbort();
  return Object.freeze({
    async run() {
      if (started) throw new AuthOperationError('AUTH_ALREADY_RUN');
      started = true;
      try {
        check();
        const providers = providersFactory();
        if (!Array.isArray(providers)) fail('AUTH_INCOMPATIBLE');
        const selected = providers.filter(provider => provider?.id === providerId);
        if (selected.length !== 1) fail('AUTH_INCOMPATIBLE');
        const models = modelsFactory({credentials,
          authContext: {env: async () => undefined, fileExists: async () => false}});
        models.setProvider(selected[0]);
        await Promise.race([models.login(providerId, authType, {
          signal: controller.signal, prompt, notify,
        }), stopped]);
        await drain(); check();
        if (pending || !persisted) fail('AUTH_STORAGE_FAILED');
        const result = Object.freeze({providerId, authType, status: 'stored'});
        seal('AUTH_CLOSED');
        return result;
      } catch {
        const code = failure ?? 'AUTH_FAILED';
        seal(code);
        throw new AuthOperationError(code);
      }
    },
    /** 帧封套/序号由外层校验；只允许两种业务响应，未知或重复输入永久失败关闭。 */
    accept(frame) {
      check();
      try {
        if (!started) fail('AUTH_INVALID');
        if (frame?.type === 'credential.response') { rpc.accept(frame.payload); return; }
        if (frame?.type !== 'auth.response' || !exact(frame.payload, ['promptId', 'value'])) fail('AUTH_INVALID');
        const {promptId, value} = frame.payload;
        if (!Number.isSafeInteger(promptId) || promptId < 1 || promptId > 16 || !inputValue(value)) fail('AUTH_INVALID');
        if (tombstones.delete(promptId)) return;
        if (!pending || pending.id !== promptId || pending.received) fail('AUTH_INVALID');
        pending.received = true; pending.value = value;
        settlePrompt(pending);
      } catch {
        const code = failure ?? 'AUTH_INVALID'; seal(code); throw new AuthOperationError(code);
      }
    },
    /** EOF/外部取消后不可复活；在途持久化是否落盘仍以宿主事务事实为准。 */
    close() { seal('AUTH_CLOSED'); },
  });
}
