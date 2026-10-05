import test from 'node:test';
import assert from 'node:assert/strict';
import {PassThrough} from 'node:stream';
import {runWorker} from './worker-runtime.mjs';
import {JsonLineDecoder, encodeFrame} from './worker-protocol.mjs';
const tick = () => new Promise(resolve => setImmediate(resolve));
const result = {providerId: 'openai', authType: 'api_key', status: 'stored'};
const frame = (sequence, type, payload) => encodeFrame({version: 1, operationId: 'auth-test', sequence, type, payload});
function harness(authenticate) {
  const input = new PassThrough(), output = new PassThrough(), frames = [];
  const decoder = new JsonLineDecoder({onFrame: value => frames.push(value)});
  output.on('data', bytes => decoder.push(bytes));
  const done = runWorker({input, output, authenticate, catalog: () => {assert.fail('AUTH_MUST_NOT_CALL_CATALOG');}});
  return {input, output, frames, done, start(payload = {}) {input.write(frame(0, 'operation.start', {operation: 'auth.login', providerId: 'openai', authType: 'api_key', ...payload}));}};
}

test('生产状态机分发私有认证帧，清理后才发布stored与唯一completed', async () => {
  let complete, closed = 0;
  const h = harness(({send}) => ({
    async run() {await send('auth.prompt', {promptId: 1, kind: 'secret'}); return new Promise(resolve => {complete = resolve;});},
    accept(value) {assert.equal(value.type, 'auth.response'); complete(result);},
    close() {closed++;},
  }));
  h.start(); await tick();
  assert.equal(h.frames.at(-1).type, 'auth.prompt');
  h.input.write(frame(1, 'auth.response', {promptId: 1, value: 'synthetic'}));
  assert.equal(await h.done, 0);
  assert.equal(closed, 1);
  assert.deepEqual(h.frames.map(value => value.type), ['operation.ready', 'auth.prompt', 'auth.result', 'operation.completed']);
  assert.deepEqual(h.frames.map(value => value.sequence), [0, 1, 2, 3]);
});

test('取消拒绝迟到stored，认证与目录不互相fallback', async () => {
  let complete, closed = 0;
  const h = harness(() => ({run: () => new Promise(resolve => {complete = resolve;}), accept() {}, close() {closed++;}}));
  h.start(); await tick();
  h.input.write(frame(1, 'operation.cancel', {}));
  assert.equal(await h.done, 1);
  complete(result); await tick();
  assert.equal(closed, 1);
  assert.deepEqual(h.frames.map(value => value.type), ['operation.ready', 'operation.failed']);
});

test('取消发生于懒加载时，迟到工厂也必须关闭且不能run', async () => {
  let create, closed = 0;
  const h = harness(() => new Promise(resolve => {create = resolve;}));
  h.start(); await tick(); h.input.write(frame(1, 'operation.cancel', {}));
  assert.equal(await h.done, 1);
  create({run() {assert.fail();}, accept() {}, close() {closed++;}});
  await tick(); assert.equal(closed, 1);
});

test('错误身份/认证组合在执行前拒绝', async () => {
  const h = harness(() => {assert.fail();});
  h.start({providerId: 'openai-codex', authType: 'api_key'});
  assert.equal(await h.done, 2);
  assert.equal(h.frames.at(-1).payload.code, 'PROTOCOL_INVALID');
});

test('清理失败不能发出成功终态', async () => {
  const h = harness(() => ({run: async () => result, accept() {}, close() {throw new Error('SYNTHETIC_PRIVATE');}}));
  h.start(); assert.notEqual(await h.done, 0);
  assert.equal(h.frames.some(value => value.type === 'operation.completed'), false);
  assert.equal(JSON.stringify(h.frames).includes('SYNTHETIC_PRIVATE'), false);
});

test('结果和输出端口拒绝凭证正文及提前伪造终态', async () => {
  for (const spoof of [false, true]) {
    const h = harness(({send}) => ({run: async () => {
      if (spoof) await send('operation.completed', {});
      return {...result, secret: 'SYNTHETIC_PRIVATE'};
    }, accept() {}, close() {}}));
    h.start(); assert.equal(await h.done, 2);
    assert.equal(h.frames.some(value => value.type === 'operation.completed'), false);
    assert.equal(JSON.stringify(h.frames).includes('SYNTHETIC_PRIVATE'), false);
  }
});

test('cancelled prompt late reply during result flush is consumed once', async () => {
  for (const duplicate of [false, true]) {
    let closed = false, flush;
    const h = harness(({send}) => ({async run() {
      await send('auth.prompt', {promptId: 1, kind: 'secret'});
      await send('auth.prompt_cancelled', {promptId: 1});
      return result;
    }, accept() {if (closed) throw new Error('CLOSED');}, close() {closed = true;}}));
    const write = h.output.write.bind(h.output);
    h.output.write = (bytes, callback) => write(bytes, error => {
      if (JSON.parse(bytes.toString()).type === 'auth.result') flush = () => callback(error);
      else callback(error);
    });
    h.start(); await tick(); assert.equal(typeof flush, 'function');
    h.input.write(frame(1, 'auth.response', {promptId: 1, value: 'late'}));
    if (duplicate) h.input.write(frame(2, 'auth.response', {promptId: 1, value: 'duplicate'}));
    flush();
    assert.equal(await h.done, duplicate ? 2 : 0);
  }
});

test('认证输出采用32KiB单帧和128KiB整体预算', async () => {
  for (const size of [32768, 24000]) {
    const h = harness(({send}) => ({run: async () => {
      for (let i = 0; i < 6; i++) await send('auth.prompt', {padding: 'x'.repeat(size)});
      return result;
    }, accept() {}, close() {}}));
    h.start(); assert.equal(await h.done, 2);
    assert.equal(h.frames.some(value => value.type === 'operation.completed'), false);
    assert.equal(h.frames.at(-1).payload.code, 'PROTOCOL_LIMIT');
  }
});
