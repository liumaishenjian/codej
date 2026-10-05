import {encodeFrame, parseStrictJson} from './worker-protocol.mjs';

const CODES = new Set(['RPC_INVALID', 'RPC_LIMIT', 'RPC_CLOSED', 'RPC_CANCELLED', 'RPC_SEND_FAILED', 'RPC_REJECTED']);
/** 私有RPC只保留本地封闭码，拒绝把宿主错误正文或凭证带入异常。 */
export class CredentialRpcError extends Error {
  constructor(code) {
    super(CODES.has(code) ? code : 'RPC_INVALID');
    this.name = 'CredentialRpcError'; this.code = this.message;
    Object.freeze(this);
  }
}
const exact = (value, keys) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new CredentialRpcError('RPC_INVALID');
  const descriptors = Object.getOwnPropertyDescriptors(value);
  if (Reflect.ownKeys(descriptors).length !== keys.length || keys.some(key =>
    !descriptors[key]?.enumerable || !Object.hasOwn(descriptors[key], 'value'))) throw new CredentialRpcError('RPC_INVALID');
  return value;
};

/**
 * 单操作凭证RPC客户端。send只接到已绑定的Worker私有输出；accept只接收同连接校验后的response payload。
 * 身份、帧版本/序号及原始管道字节预算仍由外层负责；这里另作保守重编码预算，不能替代真实字节计数。
 * 任一在途RPC取消会永久关闭客户端，宿主必须随连接终止释放事务，不能丢弃未知锁的响应后继续。
 */
export function createCredentialTransactions({send, onFatal = () => {}}) {
  if (typeof send !== 'function' || typeof onFatal !== 'function') throw new CredentialRpcError('RPC_INVALID');
  const pending = new Map();
  let next = 1, bytesUsed = 0, closed = false;
  function shutdown(code, notify = true) {
    if (closed) return;
    closed = true;
    for (const entry of pending.values()) {
      entry.signal?.removeEventListener('abort', entry.abort);
      entry.reject(new CredentialRpcError(code));
    }
    pending.clear();
    if (notify) {
      try { Promise.resolve(onFatal(code)).catch(() => {}); } catch { /* 不传播生命周期回调异常。 */ }
    }
  }
  function clone(type, payload) {
    let bytes;
    try {
      // 用最大合法operationId/序号预留封套开销，而不是把真实关联身份写入本地缓存。
      bytes = encodeFrame({version: 1, operationId: 'x'.repeat(96), sequence: 65535, type, payload}, 32768);
      if (bytesUsed + bytes.length > 131072) throw new CredentialRpcError('RPC_LIMIT');
      bytesUsed += bytes.length;
      return parseStrictJson(bytes.toString('utf8')).payload;
    } catch (error) {
      throw new CredentialRpcError(error?.code === 'RPC_LIMIT' || error?.message === 'PROTOCOL_LIMIT' ? 'RPC_LIMIT' : 'RPC_INVALID');
    } finally { bytes?.fill(0); }
  }
  function settle(id, entry) {
    if (!entry.sent || !entry.received || closed) return;
    pending.delete(id);
    entry.signal?.removeEventListener('abort', entry.abort);
    if (entry.ok) entry.resolve(entry.result);
    else {
      entry.reject(new CredentialRpcError('RPC_REJECTED'));
      // 宿主已在失败路径关闭事务。禁止继续发送abort/新请求，清理失败仍由Java关闭路径保留。
      shutdown('RPC_REJECTED');
    }
  }
  function call(action, args, signal) {
    if (closed) return Promise.reject(new CredentialRpcError('RPC_CLOSED'));
    if (signal !== undefined && !(signal instanceof AbortSignal)) return Promise.reject(new CredentialRpcError('RPC_INVALID'));
    if (signal?.aborted) return Promise.reject(new CredentialRpcError('RPC_CANCELLED'));
    let payload;
    const id = next;
    try {
      if (next > 512 || pending.size >= 16) throw new CredentialRpcError('RPC_LIMIT');
      payload = clone('credential.request', {requestId: id, action, arguments: args});
    } catch (error) { shutdown(error.code); return Promise.reject(error); }
    next++;
    return new Promise((resolve, reject) => {
      const entry = {resolve, reject, signal, sent: false, received: false,
        abort: () => shutdown('RPC_CANCELLED')};
      pending.set(id, entry);
      signal?.addEventListener('abort', entry.abort, {once: true});
      if (signal?.aborted) {entry.abort(); return;}
      Promise.resolve().then(() => {
        if (closed) throw new CredentialRpcError('RPC_CLOSED');
        return send('credential.request', payload);
      }).then(() => {entry.sent = true; settle(id, entry);}, () => shutdown('RPC_SEND_FAILED'));
    });
  }
  const transactions = Object.freeze({
    list: ({signal} = {}) => call('list', {}, signal),
    read: ({signal} = {}) => call('read', {}, signal),
    begin: ({signal} = {}) => call('begin', {}, signal),
    finish: (args, {signal} = {}) => {
      try {exact(args, ['transactionId', 'change']);}
      catch {return Promise.reject(new CredentialRpcError('RPC_INVALID'));}
      return call('finish', args, signal);
    },
    abort: (args, {signal} = {}) => {
      try {exact(args, ['transactionId']);}
      catch {return Promise.reject(new CredentialRpcError('RPC_INVALID'));}
      return call('abort', args, signal);
    },
  });
  return Object.freeze({
    transactions,
    accept(payload) {
      if (closed) throw new CredentialRpcError('RPC_CLOSED');
      try {
        const value = clone('credential.response', payload);
        exact(value, value.ok === true ? ['requestId', 'ok', 'result'] : ['requestId', 'ok', 'code']);
        if (!Number.isSafeInteger(value.requestId) || value.requestId < 1 || typeof value.ok !== 'boolean') throw new CredentialRpcError('RPC_INVALID');
        const entry = pending.get(value.requestId);
        if (!entry || entry.received) throw new CredentialRpcError('RPC_INVALID');
        if (value.ok ? !value.result || typeof value.result !== 'object' || Array.isArray(value.result)
          : typeof value.code !== 'string' || !/^[A-Z_]{1,64}$/.test(value.code)) throw new CredentialRpcError('RPC_INVALID');
        entry.received = true; entry.ok = value.ok; entry.result = value.ok ? value.result : undefined;
        settle(value.requestId, entry);
      } catch (error) {shutdown(error.code); throw error;}
    },
    /** 正常操作结束亦须释放pending引用；不把显式关闭回调成新的业务失败。 */
    close() {shutdown('RPC_CLOSED', false);},
  });
}
