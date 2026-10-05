import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {JsonLineDecoder, encodeFrame} from './worker-protocol.mjs';

const entry = fileURLToPath(new URL('./worker.mjs', import.meta.url));
function invoke(input, args = []) {
  return new Promise((resolve, reject) => {
    const env = {};
    for (const name of ['SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP']) if (process.env[name]) env[name] = process.env[name];
    env.OPENAI_API_KEY = 'SYNTHETIC_UNUSED_WORKER_KEY';
    const child = spawn(process.execPath, [entry, ...args], {env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
    const frames = []; let stderrBytes = 0; let stdoutBytes = 0; let failure;
    const reader = new JsonLineDecoder({onFrame: frame => frames.push(frame)});
    const timer = setTimeout(() => {failure = new Error('worker did not exit'); child.kill();}, 5000);
    child.on('error', error => {clearTimeout(timer); reject(error);});
    child.stdin.on('error', () => {});
    child.stdout.on('data', bytes => {
      stdoutBytes += bytes.length;
      try {reader.push(bytes);} catch (error) {failure = error; child.kill();}
    });
    child.stderr.on('data', bytes => {stderrBytes += bytes.length;});
    child.on('close', code => {
      clearTimeout(timer);
      try {reader.end();} catch (error) {failure ??= error;}
      if (failure) reject(failure); else resolve({code, frames, stderrBytes, stdoutBytes});
    });
    if (input === undefined) child.stdin.end(); else child.stdin.write(input);
  });
}

test('private worker rejects unexpected argv without reflecting it', async () => {
  const result = await invoke(undefined, ['--unexpected=SYNTHETIC_PRIVATE_ARG']);
  assert.equal(result.code, 2); assert.equal(result.stdoutBytes, 0); assert.equal(result.stderrBytes, 0);
});

test('unbound malformed input never produces raw diagnostics', async () => {
  const result = await invoke(Buffer.from('{"secret":"SYNTHETIC_PRIVATE_FRAME"}\n'));
  assert.equal(result.code, 2); assert.equal(result.stdoutBytes, 0); assert.equal(result.stderrBytes, 0);
});

test('real Pi catalog crosses the private process boundary without credential resolution', async () => {
  const result = await invoke(encodeFrame({version: 1, operationId: 'entry-test', sequence: 0, type: 'operation.start', payload: {operation: 'catalog'}}));
  assert.equal(result.code, 0); assert.equal(result.stderrBytes, 0);
  assert.deepEqual(result.frames.map(frame => frame.type), ['operation.ready', 'catalog.result', 'operation.completed']);
  assert.deepEqual(result.frames.map(frame => frame.sequence), [0, 1, 2]);
  assert.deepEqual(result.frames[0].payload.operations, ['catalog', 'auth.login', 'model']);
  const catalog = result.frames[1].payload;
  assert.equal(catalog.piVersion, '0.85.1');
  assert.deepEqual(catalog.brands.map(brand => brand.id), ['openai', 'deepseek', 'qwen']);
  assert.deepEqual(catalog.providers.map(provider => provider.id), ['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn']);
  assert.ok(catalog.providers.every(provider => provider.models.length > 0));
  assert.equal(JSON.stringify(result.frames).includes('SYNTHETIC_UNUSED_WORKER_KEY'), false);
});

test('真实Worker模型错误经model.error与failed后EOF/非零退出，不访问凭证或网络', async () => {
  const result = await invoke(encodeFrame({version: 1, operationId: 'entry-test', sequence: 0, type: 'operation.start', payload: {
    operation: 'model', providerId: 'openai', modelId: 'synthetic-not-a-registered-model',
    request: {systemPrompt: '', messages: [{role: 'user', text: 'synthetic'}], tools: [], options: {}},
  }}));
  assert.equal(result.code, 1); assert.equal(result.stderrBytes, 0);
  assert.deepEqual(result.frames.map(f => f.type), ['operation.ready', 'model.error', 'operation.failed']);
  assert.deepEqual(result.frames[1].payload, {code: 'UNSUPPORTED', retryable: false, providerFrame: false});
  assert.equal(JSON.stringify(result.frames).includes('SYNTHETIC_UNUSED_WORKER_KEY'), false);
});

test('malformed model operation does not invoke an implicit provider fallback', async () => {
  const result = await invoke(encodeFrame({version: 1, operationId: 'entry-test', sequence: 0, type: 'operation.start', payload: {operation: 'model'}}));
  assert.equal(result.code, 2); assert.equal(result.stderrBytes, 0);
  assert.equal(result.frames.length, 1);
  assert.equal(result.frames[0].payload.code, 'PROTOCOL_INVALID');
  assert.equal(JSON.stringify(result.frames).includes('SYNTHETIC'), false);
});
