import {createModels} from '@earendil-works/pi-ai';
import {createRegisteredProviders} from './provider-registry.mjs';
import {createCredentialTransactions} from './credential-rpc.mjs';
import {createHostCredentialStore} from './credential-store.mjs';
import {parseStrictJson} from './worker-protocol.mjs';

const ROUTES = new Set(['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn']);
const CODES = new Set(['AUTH', 'CANCELLED', 'TIMEOUT', 'RATE_LIMIT', 'TRANSIENT',
  'CONTEXT_OVERFLOW', 'INCOMPLETE', 'PROTOCOL', 'PERMANENT', 'UNSUPPORTED', 'LIMIT']);
const OWN = new WeakSet();
const MiB = 1024 * 1024;
/** 封闭模型失败；绝不保存上游异常、HTTP header、诊断或错误正文。 */
export class ModelOperationError extends Error {
  constructor(code, retryable = false, providerFrame = false, retryAfterMs) {
    super(CODES.has(code) ? code : 'PERMANENT');
    this.name = 'ModelOperationError'; this.code = this.message;
    this.retryable = retryable === true; this.providerFrame = providerFrame === true;
    if (Number.isSafeInteger(retryAfterMs) && retryAfterMs >= 0) this.retryAfterMs = retryAfterMs;
    OWN.add(this); Object.freeze(this);
  }
}
const bad = (code = 'PROTOCOL') => { throw new ModelOperationError(code); };
function fields(v, required, optional = []) {
  if (!v || typeof v !== 'object' || Array.isArray(v)
      || ![Object.prototype, null].includes(Object.getPrototypeOf(v))) bad();
  const ds = Object.getOwnPropertyDescriptors(v);
  for (const k of Reflect.ownKeys(ds)) {
    if (typeof k !== 'string' || !ds[k].enumerable || !Object.hasOwn(ds[k], 'value')
        || (required && !required.includes(k) && !optional.includes(k))) bad();
  }
  if (required?.some(k => !Object.hasOwn(ds, k))) bad();
  return ds;
}
/** 在读取值之前检查描述符；拒绝异常原型、getter、循环和稀疏数组；原型同名JSON键仍是数据。 */
function copy(value, max = MiB) {
  let size = 0, nodes = 0;
  function visit(v, depth) {
    if (++nodes > 200000 || depth > 48) bad('LIMIT');
    if (v === null || typeof v === 'boolean') { size += 5; return v; }
    if (typeof v === 'string') {
      size += Buffer.byteLength(v) + 2;
      if (size > max || /[\ud800-\udfff]/u.test(v)) bad('LIMIT');
      return v;
    }
    if (typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= Number.MAX_SAFE_INTEGER) { size += 24; return v; }
    if (Array.isArray(v)) {
      if (Object.getPrototypeOf(v) !== Array.prototype || v.length > 200000) bad();
      const ds = Object.getOwnPropertyDescriptors(v);
      if (Reflect.ownKeys(ds).length !== v.length + 1) bad();
      return Array.from({length: v.length}, (_, i) => {
        if (!ds[i]?.enumerable || !Object.hasOwn(ds[i], 'value')) bad();
        return visit(ds[i].value, depth + 1);
      });
    }
    const ds = fields(v);
    const result = {};
    for (const k of Object.keys(ds)) {
      size += Buffer.byteLength(k) + 3;
      if (size > max) bad('LIMIT');
      // 使用自有数据属性，不调用__proto__ setter，也不擅自缩窄工具JSON Schema。
      Object.defineProperty(result, k, {value: visit(ds[k].value, depth + 1),
        enumerable: true, configurable: true, writable: true});
    }
    return result;
  }
  const result = visit(value, 0);
  if (Buffer.byteLength(JSON.stringify(result)) > max) bad('LIMIT');
  return result;
}
const text = (v, max = MiB, empty = true) => {
  if (typeof v !== 'string' || v.length > max || (!empty && !v.trim())) bad();
  return v;
};
const count = v => { if (!Number.isSafeInteger(v) || v < 0) bad(); return v; };
function calls(v) {
  if (!Array.isArray(v) || v.length > 1024) bad();
  const seen = new Set();
  return v.map(c => {
    fields(c, ['id', 'name', 'arguments']);
    text(c.id, 512, false); text(c.name, 256, false); fields(c.arguments);
    if (seen.has(c.id)) bad(); seen.add(c.id);
    return c;
  });
}
const zeroUsage = () => ({input: 0, output: 0, cacheRead: 0, cacheWrite: 0,
  totalTokens: 0, cost: {input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0}});
function visible(content) {
  return {text: content.filter(c => c.type === 'text').map(c => c.text).join(''),
    toolCalls: calls(content.filter(c => c.type === 'toolCall').map(c =>
      ({id: c.id, name: c.name, arguments: c.arguments})))};
}
function contentTree(content) {
  if (!Array.isArray(content) || content.length > 4096) bad();
  return content.map(c => {
    if (c.type === 'text') {
      fields(c, ['type', 'text'], ['textSignature']); text(c.text);
      if (c.textSignature !== undefined) text(c.textSignature);
    } else if (c.type === 'thinking') {
      fields(c, ['type', 'thinking'], ['thinkingSignature', 'redacted']); text(c.thinking);
      if (c.thinkingSignature !== undefined) text(c.thinkingSignature);
      if (c.redacted !== undefined && typeof c.redacted !== 'boolean') bad();
    } else if (c.type === 'toolCall') {
      fields(c, ['type', 'id', 'name', 'arguments'], ['thoughtSignature', 'namespace']);
      calls([{id: c.id, name: c.name, arguments: c.arguments}]);
      for (const k of ['thoughtSignature', 'namespace']) if (c[k] !== undefined) text(c[k]);
    } else bad('UNSUPPORTED');
    return c;
  });
}
function payloadTree(p) {
  fields(p, ['api', 'content', 'stopReason', 'timestamp'], ['responseId', 'providerThinkingLevel']);
  text(p.api, 128, false); count(p.timestamp);
  if (!['stop', 'toolUse'].includes(p.stopReason)) bad('INCOMPLETE');
  for (const k of ['responseId', 'providerThinkingLevel']) if (p[k] !== undefined) text(p[k], 4096);
  p.content = contentTree(p.content); return p;
}
/** SDK 对象仅选取白名单自有数据字段，不遍历 diagnostics/errorMessage。 */
function snapshot(message) {
  const ds = fields(message);
  const p = {};
  for (const k of ['api', 'content', 'stopReason', 'timestamp', 'responseId', 'providerThinkingLevel']) {
    if (ds[k]?.value !== undefined) p[k] = ds[k].value;
  }
  if (Array.isArray(p.content)) {
    const array = Object.getOwnPropertyDescriptors(p.content);
    if (p.content.length > 4096 || Reflect.ownKeys(array).length !== p.content.length + 1
        || Object.getPrototypeOf(p.content) !== Array.prototype) bad();
    p.content = Array.from({length: p.content.length}, (_, i) => {
    if (!array[i]?.enumerable || !Object.hasOwn(array[i], 'value')) bad();
    const d = fields(array[i].value); const out = {};
    for (const k of ['type', 'text', 'textSignature', 'thinking', 'thinkingSignature', 'redacted',
      'id', 'name', 'arguments', 'thoughtSignature', 'namespace']) {
      if (d[k]?.value !== undefined) out[k] = d[k].value;
    }
    return out;
    });
  }
  return copy(p);
}
function validateRequest(request) {
  const r = copy(request, 32 * MiB);
  fields(r, ['systemPrompt', 'messages', 'tools', 'options']); text(r.systemPrompt, 32 * MiB);
  fields(r.options, [], ['maxTokens', 'temperature']);
  if (r.options.maxTokens !== undefined && (count(r.options.maxTokens) < 1 || r.options.maxTokens > 1e9)) bad();
  if (r.options.temperature !== undefined && (typeof r.options.temperature !== 'number'
      || r.options.temperature < 0 || r.options.temperature > 2)) bad();
  if (!Array.isArray(r.messages) || r.messages.length > 10000 || !Array.isArray(r.tools) || r.tools.length > 1024) bad();
  const names = new Set();
  for (const t of r.tools) {
    fields(t, ['name', 'description', 'parameters']); text(t.name, 256, false); text(t.description);
    fields(t.parameters); if (names.has(t.name)) bad(); names.add(t.name);
  }
  const pending = new Map(), callIds = new Set();
  for (const m of r.messages) {
    if (m.role !== 'toolResult' && pending.size) bad();
    if (m.role === 'user') { fields(m, ['role', 'text']); text(m.text); }
    else if (m.role === 'assistant') {
      fields(m, ['role', 'text', 'toolCalls'], ['continuation']); text(m.text); calls(m.toolCalls);
      for (const c of m.toolCalls) {
        if (callIds.has(c.id)) bad(); callIds.add(c.id); pending.set(c.id, c.name);
      }
      if (m.continuation !== undefined) {
        fields(m.continuation, ['backend', 'providerId', 'modelId', 'payload']);
        for (const k of ['backend', 'providerId', 'modelId']) text(m.continuation[k], 256, false);
        text(m.continuation.payload); if (Buffer.byteLength(m.continuation.payload) > MiB) bad('LIMIT');
      }
    } else if (m.role === 'toolResult') {
      fields(m, ['role', 'toolCallId', 'toolName', 'text', 'isError']);
      text(m.toolCallId, 512, false); text(m.toolName, 256, false); text(m.text);
      if (typeof m.isError !== 'boolean' || pending.get(m.toolCallId) !== m.toolName) bad();
      pending.delete(m.toolCallId);
    } else bad();
  }
  if (pending.size) bad();
  return r;
}
/** JSON对象键序不是工具参数语义；数组/调用顺序及每个值仍须完全一致。 */
function sameJson(a, b) {
  if (a === b) return true;
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v, i) => sameJson(v, b[i]));
  }
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(k => Object.hasOwn(b, k) && sameJson(a[k], b[k]));
}
function contextFor(r, model) {
  return {systemPrompt: r.systemPrompt, tools: r.tools, messages: r.messages.map(m => {
    if (m.role === 'user') return {role: 'user', content: m.text, timestamp: 0};
    if (m.role === 'toolResult') return {role: m.role, toolCallId: m.toolCallId,
      toolName: m.toolName, content: [{type: 'text', text: m.text}], isError: m.isError, timestamp: 0};
    const c = m.continuation;
    let p;
    if (c?.backend === 'pi' && c.providerId === model.provider && c.modelId === model.id) {
      try { p = payloadTree(copy(parseStrictJson(c.payload))); }
      catch (e) { if (OWN.has(e)) throw e; bad(); }
      if (p.api !== model.api || !sameJson(visible(p.content), {text: m.text, toolCalls: m.toolCalls})) p = undefined;
    }
    p ??= {api: model.api, content: [...(m.text ? [{type: 'text', text: m.text}] : []),
      ...m.toolCalls.map(t => ({type: 'toolCall', ...t}))],
    stopReason: m.toolCalls.length ? 'toolUse' : 'stop', timestamp: 0};
    return {role: 'assistant', provider: model.provider, model: model.id, usage: zeroUsage(), ...p};
  })};
}

/**
 * ADR-100 / MODEL-01/04/05/06/08/10/13：仅一个模型回合，不创建 Agent、不执行工具。
 * Pi 0.85.1 openai-{responses,completions,codex-responses} 在读取内容前合成 start；
 * 因此 start/HTTP headers 不建立 provider fence，内容块事件才建立。partial/done 是可变
 * SDK 对象，必须在下一次 await 前复制。工厂及 fetch 是受信组合根接缝，不是请求字段。
 * 外层负责 timeout AbortSignal、帧封套/原始字节预算与唯一 result/error/EOF 终态。
 */
export function createModelOperation({providerId, modelId, request, signal, send,
  modelsFactory = createModels, providersFactory = createRegisteredProviders, fetchImpl = globalThis.fetch}) {
  if (!ROUTES.has(providerId) || typeof send !== 'function' || typeof modelsFactory !== 'function'
      || typeof providersFactory !== 'function' || typeof fetchImpl !== 'function'
      || (signal !== undefined && !(signal instanceof AbortSignal))) bad();
  text(modelId, 256, false);
  const r = validateRequest(request);
  const controller = new AbortController();
  let started = false, closed = false, failure, frame = false, status, retryAfter, transport = false,
    overflow = false, attempts = 0, emitted = '', outputBytes = 0, rpc;
  let rejectStopped;
  const stopped = new Promise((_, reject) => { rejectStopped = reject; });
  void stopped.catch(() => {});
  const error = code => new ModelOperationError(code, !frame &&
    (['RATE_LIMIT', 'TRANSIENT'].includes(code) || (code === 'TIMEOUT' && status === 408)), frame, retryAfter);
  function seal(code) {
    if (closed) return;
    closed = true; failure = code; signal?.removeEventListener('abort', abort);
    controller.abort(); rpc?.close(); rejectStopped(error(code));
  }
  function check() { if (closed) throw error(failure); }
  function abort() { seal(signal.reason instanceof DOMException && signal.reason.name === 'TimeoutError' ? 'TIMEOUT' : 'CANCELLED'); }
  async function emit(type, payload) {
    check(); const bytes = Buffer.byteLength(JSON.stringify(payload)); outputBytes += bytes;
    if (bytes > MiB - 1024 || outputBytes > 16 * MiB) throw error('LIMIT');
    try { await Promise.race([Promise.resolve(send(type, payload)), stopped]); }
    catch { check(); throw error('PROTOCOL'); }
    check();
  }
  rpc = createCredentialTransactions({send, onFatal: () => seal('AUTH')});
  const host = createHostCredentialStore({providerId, transactions: rpc.transactions});
  const credentials = Object.freeze({
    read: async (...args) => { check(); try { return await host.read(...args); } catch { throw error('AUTH'); } },
    list: (...args) => host.list(...args),
    modify: async (id, fn, opts) => {
      check();
      if (providerId !== 'openai-codex') throw error('AUTH');
      return host.modify(id, async current => {
        const next = await fn(current);
        if (next === null || (next && (next.type !== 'oauth' || next.accountId !== current?.accountId))) throw error('AUTH');
        return next;
      }, opts);
    },
    delete: async () => { throw error('AUTH'); },
  });
  function classify() {
    if (closed) return failure;
    if (status === 401 || status === 403) return 'AUTH';
    if (status === 400 && overflow) return 'CONTEXT_OVERFLOW';
    if (status === 429) return 'RATE_LIMIT';
    if (status === 408) return 'TIMEOUT';
    if (status === 409 || (status >= 500 && status <= 599) || transport) return 'TRANSIENT';
    if (status >= 400) return 'PERMANENT';
    return attempts === 0 ? 'AUTH' : 'INCOMPLETE';
  }
  async function guardedFetch(input, init) {
    check();
    if (++attempts !== 1) { seal('PROTOCOL'); throw error('PROTOCOL'); }
    let response;
    try { response = await fetchImpl(input, {...init, redirect: 'error'}); }
    catch { transport = true; throw error(classify()); }
    status = response.status;
    const value = response.headers.get('retry-after');
    if (value && /^[0-9]+(?:\.[0-9]+)?$/.test(value) && value.length <= 16) {
      const ms = Math.ceil(Number(value) * 1000);
      if (Number.isSafeInteger(ms) && ms <= 86400000) retryAfter = ms;
    }
    if (status === 400 && response.body) {
      // 只读取有界结构化错误码；超限立即取消副本，不等待不可信无限 body。
      const reader = response.clone().body.getReader();
      const chunks = []; let size = 0;
      try {
        while (true) {
          const next = await Promise.race([reader.read(), stopped]);
          if (next.done) break;
          size += next.value.length; if (size > 16384) break;
          chunks.push(Buffer.from(next.value));
        }
        if (size <= 16384) {
          const parsed = parseStrictJson(Buffer.concat(chunks).toString('utf8'));
          overflow = ['context_length_exceeded', 'context_window_exceeded', 'max_context_length_exceeded'].includes(parsed?.error?.code);
        }
      } catch { /* 非结构化/超限错误保持 PERMANENT，不解析自由文本。 */ }
      finally { void reader.cancel().catch(() => {}); }
    }
    return response;
  }
  signal?.addEventListener('abort', abort, {once: true}); if (signal?.aborted) abort();
  return Object.freeze({
    async run() {
      if (started) throw error('PROTOCOL'); started = true;
      try {
        check();
        const providers = providersFactory();
        const matches = providers.filter(p => p.id === providerId);
        if (matches.length !== 1) throw error('UNSUPPORTED');
        const found = matches[0].getModels().filter(m => m.id === modelId && m.provider === providerId);
        if (found.length !== 1) throw error('UNSUPPORTED');
        const model = found[0];
        const models = modelsFactory({credentials, authContext: {env: async () => undefined, fileExists: async () => false}});
        models.setProvider(matches[0]);
        const stream = models.streamSimple(model, contextFor(r, model), {...r.options,
          transport: 'sse', maxRetries: 0, signal: controller.signal, fetch: guardedFetch});
        const iterator = stream[Symbol.asyncIterator]();
        let done, began = false;
        while (true) {
          const step = await Promise.race([iterator.next(), stopped]); check();
          if (step.done) break;
          const e = step.value;
          fields(e);
          // 先复制，再发送/等待；排除可变 SDK 引用越过异步边界。
          const partial = e.partial ? snapshot(e.partial) : undefined;
          if (done) throw error('PROTOCOL');
          if (e.type === 'start') { if (began) throw error('PROTOCOL'); began = true; continue; }
          if (e.type === 'error') throw error(classify());
          if (!began) throw error('PROTOCOL');
          if (e.type === 'done') {
            done = payloadTree(snapshot(e.message));
            if (e.reason !== done.stopReason || e.message.provider !== providerId || e.message.model !== modelId
                || done.api !== model.api) throw error('PROTOCOL');
            const ds = fields(e.message.usage); const usage = {};
            for (const k of ['input', 'output', 'cacheRead', 'cacheWrite']) usage[k] = count(ds[k]?.value);
            done = {payload: done, usage}; continue;
          }
          if (!['text_start', 'text_delta', 'text_end', 'thinking_start', 'thinking_delta', 'thinking_end',
            'toolcall_start', 'toolcall_delta', 'toolcall_end'].includes(e.type) || !partial) throw error('PROTOCOL');
          const delta = e.type === 'text_delta' ? text(e.delta) : undefined;
          if (!frame) { frame = true; await emit('model.frame', {}); }
          if (delta) { emitted += delta; await emit('model.delta', {text: delta}); }
        }
        if (!done) throw error('INCOMPLETE');
        const projection = visible(done.payload.content);
        if ((!projection.text.trim() && !projection.toolCalls.length) || projection.text !== emitted) throw error('INCOMPLETE');
        if ((done.payload.stopReason === 'toolUse') !== (projection.toolCalls.length > 0)) throw error('PROTOCOL');
        if (!frame) { frame = true; await emit('model.frame', {}); }
        const result = {...projection, usage: done.usage, continuation: {backend: 'pi', providerId, modelId,
          payload: JSON.stringify(done.payload)}};
        if (Buffer.byteLength(JSON.stringify(result)) > MiB - 1024) throw error('LIMIT');
        seal('CANCELLED'); return copy(result);
      } catch (cause) {
        const safe = OWN.has(cause) ? error(cause.code) : error(classify());
        seal(safe.code); throw safe;
      }
    },
    /** 仅消费外层已验证 credential.response；错 ID/重复/未知输入封闭本次模型操作。 */
    accept(input) {
      check();
      try {
        if (!started || input?.type !== 'credential.response') throw error('PROTOCOL');
        rpc.accept(input.payload);
      } catch { seal('PROTOCOL'); throw error('PROTOCOL'); }
    },
    close() { seal('CANCELLED'); },
  });
}
