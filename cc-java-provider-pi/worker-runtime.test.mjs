import test from 'node:test';
import assert from 'node:assert/strict';
import {PassThrough} from 'node:stream';
import {runWorker} from './worker-runtime.mjs';
import {JsonLineDecoder, encodeFrame} from './worker-protocol.mjs';

const start = (operation = 'catalog') => ({version: 1, operationId: 'test-operation', sequence: 0, type: 'operation.start', payload: {operation}});
function harness(options = {}) {
  const input = new PassThrough();
  const output = new PassThrough();
  const frames = [];
  const decoder = new JsonLineDecoder({onFrame: value => frames.push(value)});
  output.on('data', bytes => decoder.push(bytes));
  const result = runWorker({input, output, catalog: async () => ({piVersion: '0.85.1', brands: [], providers: []}), ...options});
  return {input, frames, result};
}

test('catalog operation has ordered handshake, result and exactly one terminal', async () => {
  const h = harness(); h.input.write(encodeFrame(start()));
  assert.equal(await h.result, 0);
  assert.deepEqual(h.frames.map(x => x.type), ['operation.ready', 'catalog.result', 'operation.completed']);
  assert.deepEqual(h.frames.map(x => x.sequence), [0, 1, 2]);
  assert.ok(h.frames.every(x => x.operationId === 'test-operation'));
  assert.deepEqual(h.frames[0].payload.operations, ['catalog']);
});

test('unsupported operation does not claim model or auth readiness', async () => {
  const h = harness(); h.input.write(encodeFrame(start('model')));
  assert.equal(await h.result, 2);
  assert.deepEqual(h.frames.map(x => x.type), ['operation.failed']);
  assert.equal(h.frames[0].payload.code, 'OPERATION_UNSUPPORTED');
});

test('invalid unbound input exits without reflecting untrusted correlation or body', async () => {
  const h = harness(); h.input.write(Buffer.from('{"secret":"SYNTHETIC_PRIVATE"}\n'));
  assert.equal(await h.result, 2);
  assert.deepEqual(h.frames, []);
});

test('cancel wins over a late catalog result', async () => {
  let resolve;
  const h = harness({catalog: () => new Promise(done => {resolve = done;})});
  h.input.write(encodeFrame(start()));
  await new Promise(done => setImmediate(done));
  h.input.write(encodeFrame({...start(), sequence: 1, type: 'operation.cancel', payload: {}}));
  assert.equal(await h.result, 1);
  resolve({secret: 'SYNTHETIC_LATE_RESULT'});
  await new Promise(done => setImmediate(done));
  assert.deepEqual(h.frames.map(x => x.type), ['operation.ready', 'operation.failed']);
  assert.equal(h.frames.at(-1).payload.code, 'CANCELLED');
});

test('cooperative cancellation rejection cannot replace the cancel terminal', async () => {
  const h = harness({catalog: ({signal}) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new Error('SYNTHETIC_ABORT_DETAIL')), {once: true});
  })});
  h.input.write(encodeFrame(start()));
  await new Promise(done => setImmediate(done));
  h.input.write(encodeFrame({...start(), sequence: 1, type: 'operation.cancel', payload: {}}));
  assert.equal(await h.result, 1);
  assert.equal(h.frames.at(-1).payload.code, 'CANCELLED');
});

test('oversized result does not consume a sequence before the failure frame', async () => {
  const h = harness({catalog: async () => ({value: 'x'.repeat(1024 * 1024)})});
  h.input.write(encodeFrame(start()));
  assert.equal(await h.result, 2);
  assert.deepEqual(h.frames.map(x => x.sequence), [0, 1]);
  assert.equal(h.frames.at(-1).payload.code, 'PROTOCOL_LIMIT');
});

test('EOF cancels a pending operation without inventing success', async () => {
  const h = harness({catalog: () => new Promise(() => {})});
  h.input.write(encodeFrame(start()));
  await new Promise(done => setImmediate(done));
  h.input.end();
  assert.equal(await h.result, 1);
  assert.equal(h.frames.at(-1).payload.code, 'CANCELLED');
});

test('deadline remains effective when handler ignores cancellation', async () => {
  const h = harness({catalog: () => new Promise(() => {}), timeoutMillis: 15});
  h.input.write(encodeFrame(start()));
  assert.equal(await h.result, 1);
  assert.equal(h.frames.at(-1).payload.code, 'TIMEOUT');
});

test('handler errors are sanitized and terminal is unique', async () => {
  const h = harness({catalog: async () => {throw new Error('SYNTHETIC_PRIVATE_ERROR');}});
  h.input.write(encodeFrame(start()));
  assert.equal(await h.result, 1);
  assert.equal(h.frames.at(-1).payload.code, 'WORKER_FAILED');
  assert.equal(JSON.stringify(h.frames).includes('SYNTHETIC_PRIVATE'), false);
});

// 原始空白不改变JSON语义，但必须完整计入认证和目录物理预算。
const authStart = () => encodeFrame({...start(), payload: {operation: 'auth.login', providerId: 'openai', authType: 'api_key'}});
const response = (sequence, size = 0) => {
  const bytes = encodeFrame({...start(), sequence, type: 'auth.response', payload: {}});
  return Buffer.concat([Buffer.alloc(size, 32), bytes]);
};
for (const variant of ['start', 'same-chunk-line', 'same-chunk-partial', 'later-line', 'later-partial', 'split-partial']) test(`auth32KiB原始字节拒绝${variant}`, async () => {
  let called = 0;
  const h = harness({authenticate: () => {called++; return {run: () => new Promise(() => {}), accept() {}, close() {}};}});
  if (variant === 'start') h.input.write(Buffer.concat([Buffer.alloc(32768, 32), authStart()]));
  else if (variant.startsWith('same-chunk')) h.input.write(Buffer.concat([authStart(), variant.endsWith('line') ? response(1, 32768) : Buffer.alloc(32768, 32)]));
  else {
    h.input.write(authStart()); await new Promise(resolve => setImmediate(resolve));
    if (variant === 'later-line') h.input.write(response(1, 32768));
    else if (variant === 'split-partial') {h.input.write(Buffer.alloc(20000, 32)); h.input.write(Buffer.alloc(12768, 32));}
    else h.input.write(Buffer.alloc(32768, 32));
  }
  assert.equal(await h.result, 2); assert.equal(h.frames.some(f => f.type === 'operation.completed'), false);
  if (variant === 'start' || variant.startsWith('same-chunk')) assert.equal(called, 0);
});

test('认证允许恰好32KiB启动，不按JSON重编码误拒绝', async () => {
  const bytes = authStart();
  const h = harness({authenticate: () => ({run: async () => ({providerId: 'openai', authType: 'api_key', status: 'stored'}), close() {}})});
  h.input.write(Buffer.concat([Buffer.alloc(32768 - bytes.length, 32), bytes]));
  assert.equal(await h.result, 0);
});

test('认证stdin与stdout共享128KiB，不能各自独立放宽', async () => {
  const h = harness({authenticate: ({send}) => ({async run() {
    await send('auth.prompt', {padding: 'x'.repeat(24000)});
    await send('auth.prompt', {padding: 'x'.repeat(24000)});
    return new Promise(() => {});
  }, accept() {}, close() {}})});
  h.input.write(authStart()); await new Promise(resolve => setImmediate(resolve));
  for (let i = 1; i <= 4; i++) h.input.write(response(i, 24000));
  assert.equal(await h.result, 2); assert.equal(h.frames.some(f => f.type === 'operation.completed'), false);
});

test('认证输入512帧上限仍独立有效', async () => {
  const h = harness({authenticate: () => ({run: () => new Promise(() => {}), accept() {}, close() {}})});
  h.input.write(authStart()); await new Promise(resolve => setImmediate(resolve));
  h.input.write(Buffer.concat(Array.from({length: 512}, (_, i) => response(i + 1))));
  assert.equal(await h.result, 2); assert.equal(h.frames.at(-1).payload.code, 'PROTOCOL_LIMIT');
});

test('目录超大启动不能借用模型预算', async () => {
  const h = harness({catalog: () => assert.fail('OVERSIZED_CATALOG')});
  h.input.write(Buffer.concat([Buffer.alloc(40000, 32), encodeFrame(start())]));
  assert.equal(await h.result, 2); assert.equal(h.frames.at(-1).payload.code, 'PROTOCOL_LIMIT');
});

test('duplicate request poisons operation and late work cannot publish', async () => {
  const h = harness({catalog: () => new Promise(() => {})});
  h.input.write(Buffer.concat([encodeFrame(start()), encodeFrame(start())]));
  assert.equal(await h.result, 2);
  assert.equal(h.frames.filter(x => x.type === 'operation.failed').length, 1);
  assert.equal(h.frames.at(-1).payload.code, 'PROTOCOL_INVALID');
});
