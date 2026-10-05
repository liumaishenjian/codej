import test from 'node:test';
import assert from 'node:assert/strict';
import {PassThrough} from 'node:stream';
import {spawn} from 'node:child_process';
import {runWorker} from './worker-runtime.mjs';
import {ModelOperationError} from './model-operation.mjs';
import {encodeFrame, JsonLineDecoder} from './worker-protocol.mjs';
const tick = () => new Promise(resolve => setImmediate(resolve));
const frame = (sequence, type, payload) => encodeFrame({version: 1, operationId: 'model-test', sequence, type, payload});
const start = (extra = {}) => frame(0, 'operation.start', {operation: 'model', providerId: 'openai', modelId: 'fake', request: {}, ...extra});
const result = {text: 'hello', toolCalls: [{id: 'call-1', name: 'read', arguments: {path: 'demo'}}], usage: {input: 1, output: 2, cacheRead: 0, cacheWrite: 0}, continuation: {backend: 'pi', providerId: 'openai', modelId: 'fake', payload: '{}'}};
function harness(model, options = {}) {
  const input = new PassThrough(), output = new PassThrough(), frames = [];
  const decoder = new JsonLineDecoder({onFrame: f => frames.push(f)});
  output.on('data', bytes => decoder.push(bytes));
  const done = runWorker({input, output, catalog: () => assert.fail('NO_FALLBACK'), model, ...options});
  return {input, output, frames, done};
}
const never = () => new Promise(() => {});
const types = h => h.frames.map(f => f.type);

test('模型文本与工具结果在关闭确认之后，终态唯一且RPC仅进入活动模型', async () => {
  let complete, closed = 0;
  const h = harness(({send, providerId, modelId}) => {
    assert.equal(providerId, 'openai'); assert.equal(modelId, 'fake');
    return {async run() {
      await send('credential.request', {requestId: 1, action: 'read', arguments: {}});
      await new Promise(resolve => {complete = resolve;});
      await send('model.frame', {}); await send('model.delta', {text: 'hello'}); return result;
    }, accept(f) {assert.equal(f.type, 'credential.response'); complete();}, async close() {await tick(); closed++;}};
  });
  h.output.on('data', bytes => {if (JSON.parse(bytes).type === 'model.result') assert.equal(closed, 1);});
  h.input.write(start()); await tick(); h.input.write(frame(1, 'credential.response', {requestId: 1, ok: true, result: {credential: null}}));
  assert.equal(await h.done, 0); assert.equal(closed, 1);
  assert.deepEqual(types(h), ['operation.ready', 'credential.request', 'model.frame', 'model.delta', 'model.result', 'operation.completed']);
  assert.deepEqual(h.frames.at(-2).payload, result);
  assert.deepEqual(h.frames.map(f => f.sequence), [0, 1, 2, 3, 4, 5]);
});

for (const framed of [false, true]) test(`封闭模型错误不泄露正文，provider fence=${framed}不能回退`, async () => {
  const h = harness(({send}) => ({async run() {
    if (framed) await send('model.frame', {});
    throw new ModelOperationError('TRANSIENT', true, false, 125);
  }, close() {}, accept() {}}));
  h.input.write(start()); assert.equal(await h.done, 1);
  assert.deepEqual(h.frames.at(-2).payload, {code: 'TRANSIENT', retryable: !framed, providerFrame: framed, retryAfterMs: 125});
  assert.deepEqual(types(h).slice(-2), ['model.error', 'operation.failed']);
});

test('全部封闭错误码保持白名单，未知错误文案归为PERMANENT', async () => {
  for (const code of ['AUTH', 'CANCELLED', 'TIMEOUT', 'RATE_LIMIT', 'TRANSIENT', 'CONTEXT_OVERFLOW',
    'INCOMPLETE', 'PROTOCOL', 'PERMANENT', 'UNSUPPORTED', 'LIMIT', 'SYNTHETIC_PRIVATE']) {
    const h = harness(() => {throw new ModelOperationError(code);});
    h.input.write(start()); assert.equal(await h.done, 1);
    assert.deepEqual(h.frames.at(-2).payload, {code: code === 'SYNTHETIC_PRIVATE' ? 'PERMANENT' : code, retryable: false, providerFrame: false});
  }
});

test('成功Fake模型跨真实Node进程，完成帧之后确认物理EOF与exit0', async () => {
  const runtime = new URL('./worker-runtime.mjs', import.meta.url).href;
  const script = `import {runWorker} from ${JSON.stringify(runtime)};
    let closed = false;
    const code = await runWorker({input: process.stdin, output: process.stdout,
      catalog: () => {throw new Error('NO_FALLBACK');},
      model: ({send}) => ({async run() {
        await send('model.frame', {}); await send('model.delta', {text: 'hello'});
        return ${JSON.stringify(result)};
      }, async close() {await new Promise(r => setImmediate(r)); closed = true;}})});
    process.stdin.destroy(); process.exit(closed ? code : 2);`;
  const child = spawn(process.execPath, ['--input-type=module', '--eval', script], {stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
  const frames = []; let stderr = 0, eof = false;
  const decoder = new JsonLineDecoder({onFrame: f => frames.push(f)});
  child.stdout.on('data', b => decoder.push(b));
  child.stdout.on('end', () => {decoder.end(); eof = true;});
  child.stderr.on('data', b => {stderr += b.length;});
  child.stdin.on('error', () => {});
  const timer = setTimeout(() => child.kill(), 5000);
  try {
    const done = new Promise((resolve, reject) => {child.on('error', reject); child.on('close', resolve);});
    child.stdin.write(start()); assert.equal(await done, 0);
    assert.equal(eof, true); assert.equal(stderr, 0);
    assert.deepEqual(frames.map(f => f.type), ['operation.ready', 'model.frame', 'model.delta', 'model.result', 'operation.completed']);
    assert.deepEqual(frames.at(-2).payload, result);
  } finally {clearTimeout(timer); child.kill();}
});

test('结果write callback失败不能发布completed，仍关闭模型', async () => {
  let closed = 0;
  const h = harness(() => ({run: async () => result, close() {closed++;}}));
  const write = h.output.write.bind(h.output);
  h.output.write = (bytes, callback) => {
    if (JSON.parse(bytes).type === 'model.result') {callback(new Error('PRIVATE')); return false;}
    return write(bytes, callback);
  };
  h.input.write(start()); assert.equal(await h.done, 2); assert.equal(closed, 1);
  assert.equal(types(h).includes('operation.completed'), false);
  assert.equal(JSON.stringify(h.frames).includes('PRIVATE'), false);
});

test('普通异常正文不进入任何输出', async () => {
  const h = harness(() => {throw new Error('SYNTHETIC_PRIVATE_BODY');});
  h.input.write(start()); assert.equal(await h.done, 1);
  assert.equal(JSON.stringify(h.frames).includes('SYNTHETIC_PRIVATE'), false);
  assert.deepEqual(types(h), ['operation.ready', 'operation.failed']);
});

for (const reason of ['cancel', 'timeout', 'eof', 'output']) test(`模型${reason}清理且丢弃迟到结果`, async () => {
  let complete, closed = 0;
  const h = harness(() => ({run: () => new Promise(resolve => {complete = resolve;}), close() {closed++;}, accept() {}}), {timeoutMillis: reason === 'timeout' ? 30 : 1000});
  h.input.write(start()); await tick();
  if (reason === 'cancel') h.input.write(frame(1, 'operation.cancel', {}));
  if (reason === 'eof') h.input.end();
  if (reason === 'output') h.output.emit('error', new Error('PRIVATE'));
  assert.notEqual(await h.done, 0); assert.equal(closed, 1);
  complete(result); await tick(); assert.equal(types(h).includes('model.result'), false);
});

test('取消时迟到factory只关闭不run', async () => {
  let create, closed = 0;
  const h = harness(() => new Promise(resolve => {create = resolve;}));
  h.input.write(start()); await tick(); h.input.write(frame(1, 'operation.cancel', {}));
  assert.equal(await h.done, 1);
  create({run() {assert.fail('LATE_RUN');}, close() {closed++;}});
  await tick(); assert.equal(closed, 1);
});

for (const mode of ['throw', 'reject', 'hang']) test(`关闭${mode}绝不发布成功`, async () => {
  const h = harness(() => ({run: async () => result, close() {
    if (mode === 'throw') throw new Error('PRIVATE');
    if (mode === 'reject') return Promise.reject(new Error('PRIVATE'));
    return never();
  }}), {timeoutMillis: 30});
  h.input.write(start()); assert.notEqual(await h.done, 0);
  assert.equal(types(h).includes('model.result'), false); assert.equal(types(h).includes('operation.completed'), false);
});

for (const type of ['operation.completed', 'model.result', 'auth.prompt']) test(`helper禁止伪造${type}，即使吞掉错误`, async () => {
  const h = harness(({send}) => ({async run() {await send(type, {}).catch(() => {}); return result;}, close() {}}));
  h.input.write(start()); assert.equal(await h.done, 2); assert.equal(types(h).includes('operation.completed'), false);
});

for (const type of ['auth.response', 'operation.start', 'model.result']) test(`模型输入拒绝无关或重复${type}`, async () => {
  const h = harness(() => ({run: never, accept() {assert.fail('WRONG_RPC');}, close() {}}));
  h.input.write(start()); await tick(); h.input.write(frame(1, type, {}));
  assert.equal(await h.done, 2); assert.equal(types(h).includes('operation.completed'), false);
});

test('错误credential RPC通过封闭ModelOperationError终止', async () => {
  let closed = 0;
  const h = harness(() => ({run: never, accept() {throw new ModelOperationError('PROTOCOL');}, close() {closed++;}}));
  h.input.write(start()); await tick(); h.input.write(frame(1, 'credential.response', {requestId: 999}));
  assert.equal(await h.done, 1); assert.equal(closed, 1); assert.equal(h.frames.at(-2).payload.code, 'PROTOCOL');
});

test('模型start大于32KiB且不超过1MiB可进入受信工厂', async () => {
  const h = harness(({request}) => {assert.equal(request.padding.length, 40000); return {run: async () => result, close() {}};});
  h.input.write(start({request: {padding: 'x'.repeat(40000)}})); assert.equal(await h.done, 0);
});

test('模型start字段严格且不接受外部factory/SDK配置', async () => {
  const h = harness(() => assert.fail('MUST_NOT_RUN'));
  h.input.write(start({url: 'SYNTHETIC_PRIVATE'})); assert.equal(await h.done, 2);
});

for (const mode of ['line', 'total', 'frames']) test(`模型stdin ${mode}上限`, async () => {
  const h = harness(() => ({run: never, accept() {}, close() {}}));
  h.input.write(start()); await tick();
  if (mode === 'line') h.input.write(Buffer.alloc(1024 * 1024, 32));
  if (mode === 'total') {
    for (let i = 1; i <= 34; i++) h.input.write(frame(i, 'credential.response', {padding: 'x'.repeat(1000000)}));
  }
  if (mode === 'frames') {
    const batch = Array.from({length: 65536}, (_, i) => frame(i + 1, 'credential.response', {}));
    h.input.write(Buffer.concat(batch));
  }
  assert.equal(await h.done, 2); assert.equal(types(h).includes('operation.completed'), false);
});

for (const mode of ['line', 'total', 'frames']) test(`模型stdout ${mode}上限与被吞掉错误均失败`, async () => {
  const h = harness(({send}) => ({async run() {
    try {
      if (mode === 'line') await send('model.delta', {text: 'x'.repeat(1024 * 1024)});
      if (mode === 'total') for (let i = 0; i < 18; i++) await send('model.delta', {text: 'x'.repeat(1000000)});
      if (mode === 'frames') for (let i = 0; i < 65536; i++) await send('model.frame', {});
    } catch { /* 测试故意模拟helper错误吞掉失败，runtime仍须封闭。 */ }
    return result;
  }, close() {}}), {timeoutMillis: 30000});
  h.input.write(start()); assert.equal(await h.done, 2); assert.equal(types(h).includes('operation.completed'), false);
});
