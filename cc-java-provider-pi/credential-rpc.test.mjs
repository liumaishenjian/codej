import test from 'node:test';
import assert from 'node:assert/strict';
import {createCredentialTransactions, CredentialRpcError} from './credential-rpc.mjs';
import {createHostCredentialStore} from './credential-store.mjs';
const tick = () => new Promise(resolve => setImmediate(resolve));
const code = expected => error => error instanceof CredentialRpcError && error.code === expected && !error.cause;
const ok = (requestId, result) => ({requestId, ok: true, result});

test('RPC适配与凭证store实际组合：绑定单身份，按序list/read/begin/finish', async () => {
  let credential = {type: 'api_key', key: 'synthetic'};
  const requests = [];
  let rpc;
  rpc = createCredentialTransactions({send(type, payload) {
    assert.equal(type, 'credential.request'); requests.push(payload);
    let result;
    switch (payload.action) {
      case 'list': result = {entries: [{providerId: 'openai', type: 'api_key'}]}; break;
      case 'read': result = {credential}; break;
      case 'begin': result = {transactionId: 'transaction-1', credential}; break;
      case 'finish': credential = payload.arguments.change.credential; result = {credential}; break;
      default: assert.fail();
    }
    rpc.accept(ok(payload.requestId, result));
  }});
  const store = createHostCredentialStore({providerId: 'openai', transactions: rpc.transactions});
  assert.deepEqual(await store.list(), [{providerId: 'openai', type: 'api_key'}]);
  assert.equal((await store.read('openai')).key, 'synthetic');
  assert.equal((await store.modify('openai', () => ({type: 'api_key', key: 'new-synthetic'}))).key, 'new-synthetic');
  assert.deepEqual(requests.map(r => r.requestId), [1, 2, 3, 4]);
  assert.deepEqual(requests.slice(0, 3).map(r => r.arguments), [{}, {}, {}]);
  assert.ok(requests.every(r => !Object.hasOwn(r, 'providerId')));
  rpc.close();
});

test('同时等待发送完成和宿主ACK；返回副本不共用宿主对象', async () => {
  let sent;
  const rpc = createCredentialTransactions({send: () => new Promise(resolve => {sent = resolve;})});
  let settled = false;
  const result = rpc.transactions.read().then(value => {settled = true; return value;});
  await tick();
  const response = ok(1, {credential: {type: 'api_key', key: 'synthetic'}});
  rpc.accept(response); response.result.credential.key = 'mutated';
  await tick(); assert.equal(settled, false);
  sent(); assert.equal((await result).credential.key, 'synthetic');
  rpc.close();
});

test('发送失败不能因先收到响应而误报成功，异常正文不外泄', async () => {
  let rpc;
  rpc = createCredentialTransactions({send() {
    rpc.accept(ok(1, {credential: null})); throw new Error('SYNTHETIC_PRIVATE');
  }});
  await assert.rejects(rpc.transactions.read(), code('RPC_SEND_FAILED'));
});

test('拒绝未知与重复回复，并一次性关闭所有pending', async () => {
  let fatals = 0;
  const rpc = createCredentialTransactions({send() {}, onFatal() {fatals++;}});
  const result = rpc.transactions.read();
  await tick(); rpc.accept(ok(1, {credential: null})); await result;
  assert.throws(() => rpc.accept(ok(1, {credential: null})), code('RPC_INVALID'));
  await assert.rejects(rpc.transactions.read(), code('RPC_CLOSED'));
  assert.equal(fatals, 1);
  const other = createCredentialTransactions({send() {}});
  const pending = assert.rejects(other.transactions.read(), code('RPC_INVALID'));
  assert.throws(() => other.accept(ok(2, {})), code('RPC_INVALID'));
  await pending;
});

test('取消前不发送，取消在途RPC关闭会话，迟到回复不能重开', async () => {
  let sends = 0;
  const rpc = createCredentialTransactions({send() {sends++;}});
  await assert.rejects(rpc.transactions.read({signal: AbortSignal.abort('SYNTHETIC_PRIVATE')}), code('RPC_CANCELLED'));
  assert.equal(sends, 0);
  const controller = new AbortController();
  const pending = assert.rejects(rpc.transactions.begin({signal: controller.signal}), code('RPC_CANCELLED'));
  await tick(); controller.abort(); await pending;
  assert.throws(() => rpc.accept(ok(1, {transactionId: 'late', credential: null})), code('RPC_CLOSED'));
});

test('宿主拒绝只返回本地固定错误，不回显code或私有正文', async () => {
  let rpc;
  rpc = createCredentialTransactions({send() {rpc.accept({requestId: 1, ok: false, code: 'SYNTHETIC_PRIVATE'});}});
  await assert.rejects(rpc.transactions.read(), error => code('RPC_REJECTED')(error) && !String(error).includes('SYNTHETIC'));
  rpc.close();
});

test('宿主失败关闭本连接，拒绝其他pending与后续请求', async () => {
  let failed = 0;
  const rpc = createCredentialTransactions({send() {}, onFatal() {failed++;}});
  const first = assert.rejects(rpc.transactions.read(), code('RPC_REJECTED'));
  const second = assert.rejects(rpc.transactions.begin(), code('RPC_REJECTED'));
  await tick();
  rpc.accept({requestId: 1, ok: false, code: 'CONFLICT'});
  await Promise.all([first, second]);
  await assert.rejects(rpc.transactions.abort({transactionId: 'old'}), code('RPC_CLOSED'));
  assert.equal(failed, 1);
});

test('限制单帧及pending数量，不无限积累秘密请求', async () => {
  const oversized = createCredentialTransactions({send() {assert.fail();}});
  await assert.rejects(oversized.transactions.finish({transactionId: 'tx', change: {kind: 'put', credential: {key: 'x'.repeat(32768)}}}), code('RPC_LIMIT'));
  const rpc = createCredentialTransactions({send() {}});
  const pending = Array.from({length: 16}, () => assert.rejects(rpc.transactions.read(), code('RPC_LIMIT')));
  await assert.rejects(rpc.transactions.read(), code('RPC_LIMIT'));
  await Promise.all(pending);
});
