// 独立私有 JSONL 状态机；不读取 Pi 配置，不持久化，不转发上游文案。
export const LINE_LIMIT = 32 * 1024;
export const TOTAL_LIMIT = 128 * 1024;

export function authorizationUrl(value) {
  if (typeof value !== 'string' || value.length > 4096 || /[^\x21-\x7e]/.test(value)) throw Error('invalid_url');
  if (!value.startsWith('https://openrouter.ai/auth?')) throw Error('invalid_url');
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.hostname !== 'openrouter.ai' || url.port || url.username || url.password || url.pathname !== '/auth' || url.hash) throw Error('invalid_url');
  const keys = [...url.searchParams.keys()];
  if (keys.length !== 3 || new Set(keys).size !== 3 || !keys.every(k => ['callback_url', 'code_challenge', 'code_challenge_method'].includes(k))) throw Error('invalid_url');
  if (url.searchParams.get('code_challenge_method') !== 'S256' || !/^[A-Za-z0-9_-]{43}$/.test(url.searchParams.get('code_challenge') ?? '')) throw Error('invalid_url');
  const callbackValue = url.searchParams.get('callback_url');
  if (!/^http:\/\/127\.0\.0\.1:[0-9]{1,5}\/oauth\/callback\/[A-Za-z0-9-]{1,80}$/.test(callbackValue ?? '')) throw Error('invalid_url');
  const callback = new URL(callbackValue);
  if (Number(callback.port) < 1) throw Error('invalid_url');
  if (callback.protocol !== 'http:' || callback.hostname !== '127.0.0.1' || !callback.port || callback.username || callback.password || callback.search || callback.hash || !/^\/oauth\/callback\/[A-Za-z0-9-]{1,80}$/.test(callback.pathname)) throw Error('invalid_url');
  return url.href;
}

export function validKey(key) {
  return typeof key === 'string' && key.length >= 1 && key.length <= 16384 && /^[\x20-\x7e]+$/.test(key) && key.trim().length > 0;
}

// provider 是唯一依赖缝隙；生产只注入公开 openrouterProvider().auth.oauth。
export function runBridge({ input, send, provider, timeoutMs = 300000, finished = () => {} }) {
  const controller = new AbortController();
  let state = 'initial', terminal = false, pending = Buffer.alloc(0), total = 0, output = 0;
  const emit = frame => {
    const text = JSON.stringify(frame) + '\n';
    if (Buffer.byteLength(text) > LINE_LIMIT || (output += Buffer.byteLength(text)) > TOTAL_LIMIT) throw Error('output_limit');
    send(text);
  };
  const finish = frame => {
    if (terminal) return;
    terminal = true;
    clearTimeout(timer);
    controller.abort();
    input.removeListener('data', data);
    input.removeListener('end', end);
    input.removeListener('error', error);
    try { emit(frame); } catch { /* 不回显 pipe 或上游异常。 */ }
    finished();
  };
  const fail = () => finish({ type: 'error', code: 'login_failed' });
  const timer = setTimeout(() => finish({ type: 'error', code: 'timeout' }), timeoutMs);
  const login = async () => {
    try {
      const credential = await provider.auth.oauth.login({
        signal: controller.signal,
        notify(event) {
          if (terminal) return;
          if (event.type === 'auth_url') {
            try { emit({ type: 'auth_url', url: authorizationUrl(event.url) }); }
            catch { fail(); throw Error('login_failed'); }
          }
        },
        prompt(prompt) {
          // 不接受手工 URL/code；保持等待，回调获胜或取消时才结束此 Promise。
          if (prompt.type !== 'manual_code') return Promise.reject(Error('unsupported_prompt'));
          return new Promise((_, reject) => {
            const signals = [controller.signal, prompt.signal].filter(Boolean);
            const abort = () => { signals.forEach(s => s.removeEventListener('abort', abort)); reject(Error('cancelled')); };
            signals.forEach(s => s.addEventListener('abort', abort, { once: true }));
            if (signals.some(s => s.aborted)) abort();
          });
        }
      });
      if (terminal) return;
      const auth = await provider.auth.oauth.toAuth(credential);
      if (terminal) return;
      if (!validKey(auth.apiKey)) return fail();
      finish({ type: 'success', key: auth.apiKey });
    } catch { fail(); }
  };
  const frame = line => {
    let value;
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(line);
      if (!/^\s*\{\s*"type"\s*:\s*"(start|login|cancel)"\s*\}\s*$/.test(text)) return fail();
      value = JSON.parse(text);
    } catch { return fail(); }
    if (!value || Array.isArray(value) || Object.keys(value).length !== 1 || typeof value.type !== 'string') return fail();
    if (value.type === 'cancel') return finish({ type: 'cancelled' });
    if (state === 'initial' && value.type === 'start') { state = 'ready'; emit({ type: 'ready' }); }
    else if (state === 'ready' && value.type === 'login') { state = 'login'; void login(); }
    else fail();
  };
  function data(chunk) {
    if (terminal) return;
    total += chunk.length;
    if (total > TOTAL_LIMIT) return fail();
    pending = Buffer.concat([pending, chunk]);
    while (!terminal) {
      const newline = pending.indexOf(10);
      if (newline < 0) { if (pending.length > LINE_LIMIT) fail(); return; }
      if (newline + 1 > LINE_LIMIT) return fail();
      const line = pending.subarray(0, newline);
      pending = pending.subarray(newline + 1);
      try { frame(line); } catch { fail(); }
    }
  }
  function end() { if (!terminal) fail(); }
  function error() { fail(); }
  input.on('data', data); input.on('end', end); input.on('error', error);
  return { cancel: () => finish({ type: 'cancelled' }) };
}
