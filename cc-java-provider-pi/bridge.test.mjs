import test from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { runBridge, authorizationUrl, validKey, LINE_LIMIT, TOTAL_LIMIT } from './bridge.mjs';
import { openrouterProvider } from '@earendil-works/pi-ai/providers/openrouter';

const goodUrl = 'https://openrouter.ai/auth?' + new URLSearchParams({ callback_url: 'http://127.0.0.1:3000/oauth/callback/fake-id', code_challenge: 'a'.repeat(43), code_challenge_method: 'S256' });
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture(login, options = {}) {
  const input = new PassThrough(), output = [];
  const provider = { auth: { oauth: { login, toAuth: async credential => ({ apiKey: credential.access }) } } };
  const bridge = runBridge({ input, send: text => output.push(JSON.parse(text)), provider, timeoutMs: 1000, ...options });
  return { input, output, bridge, send: type => input.write(JSON.stringify({ type }) + '\n'), start() { this.send('start'); this.send('login'); } };
}

test('public exported provider API exists without initiating network/auth', () => {
  const provider = openrouterProvider();
  assert.equal(provider.id, 'openrouter');
  assert.equal(typeof provider.auth.oauth.login, 'function');
  assert.equal(typeof provider.auth.oauth.toAuth, 'function');
});
test('strict authorization and loopback allowlist', () => {
  assert.equal(authorizationUrl(goodUrl), goodUrl);
  for (const url of [goodUrl.replace('https:', 'http:'), goodUrl.replace('openrouter.ai/auth', 'openrouter.ai.evil/auth'), goodUrl + '&key=secret', goodUrl + '#secret', goodUrl.replace('127.0.0.1', 'localhost'), goodUrl.replace('S256', 'plain'), goodUrl.replace('/auth?', '/auth/../auth?'), goodUrl.replace('openrouter.ai', 'user@openrouter.ai')]) {
    assert.throws(() => authorizationUrl(url));
  }
});
test('key contract bounds printable ASCII and blank', () => {
  for (const key of ['', ' ', '\n', 'é', 'a'.repeat(16385), undefined]) assert.equal(validKey(key), false);
  assert.equal(validKey('a'.repeat(16384)), true);
});
test('success maps only URL and final key; suppresses upstream messages', async () => {
  const f = fixture(async interaction => {
    interaction.notify({ type: 'progress', message: 'secret-upstream-error' });
    interaction.notify({ type: 'auth_url', url: goodUrl, instructions: 'secret-upstream-error' });
    return { access: 'synthetic-key' };
  });
  f.start(); await tick();
  assert.deepEqual(f.output, [{ type: 'ready' }, { type: 'auth_url', url: goodUrl }, { type: 'success', key: 'synthetic-key' }]);
  assert.ok(!JSON.stringify(f.output).includes('secret-upstream-error'));
});
test('cancel aborts callback wait and rejects late credential terminal', async () => {
  let complete, signal;
  const f = fixture(interaction => { signal = interaction.signal; return new Promise(resolve => { complete = resolve; }); });
  f.start(); f.send('cancel');
  assert.equal(signal.aborted, true);
  complete({ access: 'late-secret' }); await tick();
  assert.deepEqual(f.output, [{ type: 'ready' }, { type: 'cancelled' }]);
});
test('timeout is terminal and late key is discarded', async () => {
  let complete;
  const f = fixture(() => new Promise(resolve => { complete = resolve; }), { timeoutMs: 5 });
  f.start(); await new Promise(resolve => setTimeout(resolve, 20));
  complete({ access: 'late-secret' }); await tick();
  assert.deepEqual(f.output.at(-1), { type: 'error', code: 'timeout' });
  assert.equal(f.output.length, 2);
});
test('manual prompt never accepts pasted input and follows prompt cancellation', async () => {
  const f = fixture(async interaction => {
    const controller = new AbortController();
    const pending = interaction.prompt({ type: 'manual_code', signal: controller.signal });
    controller.abort();
    await assert.rejects(pending);
    return { access: 'synthetic-key' };
  });
  f.start(); await tick(); assert.equal(f.output.at(-1).type, 'success');
});
test('raw errors and invalid URL never cross private output', async () => {
  for (const login of [async () => { throw Error('SECRET_API_KEY'); }, async i => { i.notify({ type: 'auth_url', url: 'https://evil.test/SECRET_API_KEY' }); return { access: 'SECRET_API_KEY' }; }]) {
    const f = fixture(login); f.start(); await tick();
    assert.deepEqual(f.output.at(-1), { type: 'error', code: 'login_failed' });
    assert.ok(!JSON.stringify(f.output).includes('SECRET_API_KEY'));
  }
});
test('invalid frames, unknown fields, oversized lines, total and UTF8 fail closed', async () => {
  for (const input of ['not-json\n', '{"type":"start","type":"start"}\n', '{"type":"start","key":"SECRET"}\n', '{"type":"login"}\n', '{"type":"codex"}\n', 'x'.repeat(LINE_LIMIT + 1), Buffer.alloc(TOTAL_LIMIT + 1), Buffer.from([0xff, 10])]) {
    const f = fixture(async () => ({ access: 'unexpected' }));
    f.input.write(input); await tick();
    assert.deepEqual(f.output, [{ type: 'error', code: 'login_failed' }]);
  }
});
test('output budget prevents unbounded notify and drops eventual secret', async () => {
  const f = fixture(async interaction => {
    for (let i = 0; i < 2000; i++) interaction.notify({ type: 'auth_url', url: goodUrl });
    return { access: 'must-not-escape' };
  });
  f.start(); await tick();
  const bytes = f.output.reduce((sum, frame) => sum + Buffer.byteLength(JSON.stringify(frame) + '\n'), 0);
  assert.ok(bytes <= TOTAL_LIMIT);
  assert.ok(!JSON.stringify(f.output).includes('must-not-escape'));
});

test('duplicate start and disconnected input fail closed', () => {
  const f = fixture(async () => ({ access: 'unexpected' }));
  f.send('start'); f.send('start');
  assert.equal(f.output.at(-1).type, 'error');
  const g = fixture(() => new Promise(() => {}));
  g.start(); g.input.emit('end');
  assert.equal(g.output.at(-1).type, 'error');
});
