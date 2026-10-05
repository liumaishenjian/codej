const PROVIDERS = new Set(['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn']);
const ERROR_CODES = new WeakMap();
const CODES = new Set(['INVALID_ARGUMENT', 'PROVIDER_MISMATCH', 'INVALID_CREDENTIAL',
  'INVALID_TRANSACTION', 'READ_FAILED', 'BEGIN_FAILED', 'CHANGE_FAILED', 'FINISH_FAILED',
  'ABORT_FAILED', 'CANCELLED', 'CLEANUP_TIMEOUT', 'CLOSED']);

/** 封闭错误不携带上游 message、cause、输入或凭证；不调用上游 toString。 */
export class CredentialStoreError extends Error {
  constructor(code) {
    const safe = CODES.has(code) ? code : 'INVALID_ARGUMENT';
    super(safe);
    this.name = 'CredentialStoreError';
    this.code = safe;
    ERROR_CODES.set(this, safe);
    Object.freeze(this);
  }
}

// 只接收普通数据记录，拒绝 accessor、symbol、隐藏字段和原型扩展。
function record(value, keys, code) {
  if (value === null || typeof value !== 'object') throw new CredentialStoreError(code);
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) throw new CredentialStoreError(code);
  const descriptors = Object.getOwnPropertyDescriptors(value);
  const actual = Reflect.ownKeys(descriptors);
  if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) {
    throw new CredentialStoreError(code);
  }
  const copy = {};
  for (const key of keys) {
    const descriptor = descriptors[key];
    if (!descriptor || !Object.hasOwn(descriptor, 'value') || !descriptor.enumerable) {
      throw new CredentialStoreError(code);
    }
    copy[key] = descriptor.value;
  }
  return copy;
}

function cloneCredential(value, providerId) {
  try {
    if (value === null) return undefined;
    const oauth = providerId === 'openai-codex';
    const copy = record(value, oauth ? ['type', 'access', 'refresh', 'expires', 'accountId']
      : ['type', 'key'], 'INVALID_CREDENTIAL');
    const printable = (text, max) => typeof text === 'string' && text.length > 0
      && text.length <= max && /^[\x20-\x7e]+$/.test(text);
    if (oauth ? copy.type !== 'oauth' || !printable(copy.access, 16384)
      || !printable(copy.refresh, 16384) || !printable(copy.accountId, 256)
      || !Number.isSafeInteger(copy.expires) || copy.expires <= 0
      : copy.type !== 'api_key' || !printable(copy.key, 16384)) {
      throw new CredentialStoreError('INVALID_CREDENTIAL');
    }
    if (Buffer.byteLength(JSON.stringify(copy), 'utf8') > 24576) {
      throw new CredentialStoreError('INVALID_CREDENTIAL');
    }
    return copy;
  } catch {
    throw new CredentialStoreError('INVALID_CREDENTIAL');
  }
}

/**
 * Pi 公开 CredentialStore 的受信 Java RPC 适配器；仅绑定一个可信路由。
 * Java 独占身份、跨进程锁、generation/fence/CAS 与持久化权威。
 * 本实例不读取环境/文件、不缓存凭证；返回值仅来自宿主 ACK。
 * 清理超时后实例永久关闭，避免在未收敛 RPC 上启动另一个事务；
 * 宿主仍必须在连接关闭时释放锁。不可中断的回调必须收敛后才释放本地串行权。
 */
export function createHostCredentialStore(input) {
  let providerId, transactions;
  try {
    ({ providerId, transactions } = input);
    if (!PROVIDERS.has(providerId) || !transactions
      || ['list', 'read', 'begin', 'finish', 'abort'].some(key => typeof transactions[key] !== 'function')) {
      throw new Error();
    }
  } catch { throw new CredentialStoreError('INVALID_ARGUMENT'); }
  let busy = false;
  let closed = false;
  const queue = [];
  const counts = Object.create(null);
  const fail = code => { throw new CredentialStoreError(code); };
  const cancelled = signal => { if (signal?.aborted) fail('CANCELLED'); };
  function check(id, options) {
    if (id !== providerId) fail('PROVIDER_MISMATCH');
    if (closed) fail('CLOSED');
    const signal = options?.signal;
    if (signal !== undefined && !(signal instanceof AbortSignal)) fail('INVALID_ARGUMENT');
    cancelled(signal);
    return signal;
  }
  // 仅在本地生成的错误才保留分类；上游异常在每个调用边界直接丢弃。
  async function boundary(fn) {
    try { return await fn(); }
    catch (error) {
      const code = ERROR_CODES.get(error) ?? 'INVALID_ARGUMENT';
      counts[code] = (counts[code] ?? 0) + 1;
      throw new CredentialStoreError(code);
    }
  }
  async function rpc(method, args, options, code) {
    try { return await transactions[method](args, options); }
    catch {
      // begin 失败可能已取得锁但丢失响应；abort 失败也不能证明锁已释放。
      if (method === 'begin' || method === 'abort') closed = true;
      fail(code);
    }
  }
  function snapshot(response) {
    let result;
    try { result = record(response, ['credential'], 'INVALID_CREDENTIAL'); }
    catch { fail('INVALID_CREDENTIAL'); }
    return cloneCredential(result.credential, providerId);
  }
  function acquire(signal) {
    cancelled(signal);
    if (!busy) { busy = true; return Promise.resolve(); }
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, signal, abort: undefined };
      waiter.abort = () => {
        const index = queue.indexOf(waiter);
        if (index >= 0) queue.splice(index, 1);
        reject(new CredentialStoreError('CANCELLED'));
      };
      queue.push(waiter);
      signal?.addEventListener('abort', waiter.abort, { once: true });
    });
  }
  function release() {
    const next = queue.shift();
    if (!next) { busy = false; return; }
    next.signal?.removeEventListener('abort', next.abort);
    next.resolve();
  }
  // 独立于 Run 的两秒清理窗口。超时不是回滚，也不是持久化失败的证明。
  async function cleanup(method, payload) {
    const controller = new AbortController();
    let timer;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => {
        closed = true;
        controller.abort();
        reject(new CredentialStoreError('CLEANUP_TIMEOUT'));
      }, 2000);
    });
    try {
      return await Promise.race([rpc(method, payload, { signal: controller.signal },
        method === 'finish' ? 'FINISH_FAILED' : 'ABORT_FAILED'), deadline]);
    } finally { clearTimeout(timer); }
  }
  const store = {
    read(id, options) {
      return boundary(async () => {
        const signal = check(id, options);
        const response = await rpc('read', { signal }, undefined, 'READ_FAILED');
        cancelled(signal);
        return snapshot(response);
      });
    },
    modify(id, fn, options) {
      return boundary(async () => {
        const signal = check(id, options);
        if (typeof fn !== 'function') fail('INVALID_ARGUMENT');
        await acquire(signal);
        let transactionId;
        let finished = false;
        try {
          check(id, options);
          const response = await rpc('begin', { signal }, undefined, 'BEGIN_FAILED');
          // 先取得可信宿主事务 ID，随后 snapshot 校验失败也必须 abort。
          try {
            const descriptor = Object.getOwnPropertyDescriptor(response, 'transactionId');
            const candidate = descriptor?.value;
            if (typeof candidate !== 'string' || !/^[A-Za-z0-9_-]{1,96}$/.test(candidate)) {
              fail('INVALID_TRANSACTION');
            }
            transactionId = candidate;
          } catch {
            closed = true; // 无合法 ID 无法定向释放；需要关闭宿主连接。
            fail('INVALID_TRANSACTION');
          }
          let begin;
          try { begin = record(response, ['transactionId', 'credential'], 'INVALID_TRANSACTION'); }
          catch { fail('INVALID_TRANSACTION'); }
          const current = cloneCredential(begin.credential, providerId);
          cancelled(signal);
          let candidate;
          try { candidate = await fn(current); }
          catch { fail(signal?.aborted ? 'CANCELLED' : 'CHANGE_FAILED'); }
          const change = candidate === undefined ? { kind: 'keep' }
            : candidate === null ? { kind: 'delete' }
              : { kind: 'put', credential: cloneCredential(candidate, providerId) };
          // 即使 Run 刚取消，合法旋转结果仍可提交；只有 ACK 才代表已提交。
          const ack = await cleanup('finish', { transactionId, change });
          finished = true;
          const result = snapshot(ack);
          cancelled(signal);
          return result;
        } catch (error) {
          if (transactionId !== undefined && !finished && !closed) {
            await cleanup('abort', { transactionId });
          }
          throw error;
        } finally { release(); }
      });
    },
    async delete(id, options) { await store.modify(id, () => null, options); },
    list(options) {
      return boundary(async () => {
        const signal = check(providerId, options);
        // 宿主只查询索引，不能为列出认证种类而读取access/refresh或ENV值。
        const response = await rpc('list', { signal }, undefined, 'READ_FAILED');
        cancelled(signal);
        const {entries} = record(response, ['entries'], 'INVALID_CREDENTIAL');
        if (!Array.isArray(entries) || entries.length > 1) fail('INVALID_CREDENTIAL');
        return entries.map(entry => {
          const metadata = record(entry, ['providerId', 'type'], 'INVALID_CREDENTIAL');
          if (metadata.providerId !== providerId || metadata.type !==
              (providerId === 'openai-codex' ? 'oauth' : 'api_key')) fail('INVALID_CREDENTIAL');
          return metadata;
        });
      });
    },
    /** 只返回封闭错误计数，不记录值、异常对象或秘密字符串。 */
    diagnostics() { return Object.freeze({ ...counts }); },
  };
  return Object.freeze(store);
}
