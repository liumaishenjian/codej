import test from 'node:test';
import assert from 'node:assert/strict';
import { createHostCredentialStore, CredentialStoreError } from './credential-store.mjs';

const key = (value = 'fake-key') => ({ type: 'api_key', key: value });
const oauth = () => ({ type: 'oauth', access: 'fake-access', refresh: 'fake-refresh',
  expires: 100000, accountId: 'fake-account' });
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const tick = () => new Promise(resolve => setImmediate(resolve));
const errorCode = code => error => error instanceof CredentialStoreError
  && error.code === code && error.message === code && !Object.hasOwn(error, 'cause');
function fixture(providerId = 'openai', credential = key()) {
  const host = { credential, calls: [], active: false };
  const transactions = {
    async list() {
      host.calls.push('list');
      return { entries: host.credential === null ? [] : [{ providerId, type: host.credential.type }] };
    },
    async read() { host.calls.push('read'); return { credential: host.credential }; },
    async begin() {
      assert.equal(host.active, false);
      host.active = true;
      host.calls.push('begin');
      return { transactionId: 'host_TX-1', credential: host.credential };
    },
    async finish({ transactionId, change }) {
      assert.equal(transactionId, 'host_TX-1');
      host.calls.push(change.kind);
      if (change.kind === 'put') host.credential = change.credential;
      if (change.kind === 'delete') host.credential = null;
      host.active = false;
      return { credential: host.credential };
    },
    async abort({ transactionId }) {
      assert.equal(transactionId, 'host_TX-1');
      host.calls.push('abort'); host.active = false;
    },
  };
  const store = createHostCredentialStore({ providerId, transactions });
  return { host, transactions, store };
}

test('Pi undefined 保持、null 删除，同步/异步修改，list/delete 与缺失 undefined', async () => {
  const { host, store } = fixture();
  assert.deepEqual(await store.modify('openai', current => { current.key = 'not-persisted'; }), key());
  assert.deepEqual(await store.modify('openai', async () => key('next')), key('next'));
  assert.deepEqual(await store.list(), [{ providerId: 'openai', type: 'api_key' }]);
  assert.equal(await store.modify('openai', () => null), undefined);
  assert.equal(await store.read('openai'), undefined);
  assert.deepEqual(await store.list(), []);
  await store.modify('openai', () => key());
  await store.delete('openai');
  assert.equal(host.credential, null);
});

test('list只读取宿主元数据，不借道读取秘密', async () => {
  const {store, transactions, host} = fixture('openai-codex', oauth());
  transactions.read = () => assert.fail('metadata listing must not read secrets');
  assert.deepEqual(await store.list(), [{providerId: 'openai-codex', type: 'oauth'}]);
  assert.deepEqual(host.calls, ['list']);
  for (const entries of [[{providerId: 'openai', type: 'api_key'}],
    [{providerId: 'openai-codex', type: 'api_key'}],
    [{providerId: 'openai-codex', type: 'oauth', access: 'SYNTHETIC_PRIVATE'}],
    [{providerId: 'openai-codex', type: 'oauth'}, {providerId: 'openai-codex', type: 'oauth'}]]) {
    transactions.list = async () => ({entries});
    await assert.rejects(store.list(), errorCode('INVALID_CREDENTIAL'));
  }
});

test('四路由绑定与其他路由立即拒绝，无事务副作用', async () => {
  for (const providerId of ['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn']) {
    const { store, host } = fixture(providerId, providerId === 'openai-codex' ? oauth() : key());
    assert.ok(await store.read(providerId));
    host.calls.length = 0;
    for (const other of ['unknown', providerId === 'openai' ? 'deepseek' : 'openai', {}, null]) {
      await assert.rejects(store.read(other), errorCode('PROVIDER_MISMATCH'));
      await assert.rejects(store.modify(other, () => key()), errorCode('PROVIDER_MISMATCH'));
      await assert.rejects(store.delete(other), errorCode('PROVIDER_MISMATCH'));
    }
    assert.deepEqual(host.calls, []);
  }
  assert.throws(() => createHostCredentialStore({ providerId: 'unknown', transactions: {} }),
    errorCode('INVALID_ARGUMENT'));
});

test('每次读/事务重读宿主最新值，无 snapshot 缓存', async () => {
  const { store, host } = fixture();
  await store.read('openai');
  host.credential = key('external-write');
  assert.deepEqual(await store.read('openai'), key('external-write'));
  host.credential = key('newest');
  assert.deepEqual(await store.modify('openai', current => {
    assert.deepEqual(current, key('newest'));
  }), key('newest'));
});

test('read、回调输入、候选与 ACK 返回值均独立克隆', async () => {
  const { store, host, transactions } = fixture();
  const read = await store.read('openai');
  read.key = 'mutated';
  assert.deepEqual(host.credential, key());
  const gate = deferred();
  const originalFinish = transactions.finish;
  let pending;
  transactions.finish = async (request, options) => {
    pending = request;
    await gate.promise;
    return originalFinish(request, options);
  };
  const candidate = key('candidate');
  const resultPromise = store.modify('openai', current => {
    current.key = 'callback-mutated';
    return candidate;
  });
  await tick();
  assert.deepEqual(host.credential, key());
  candidate.key = 'late-mutation';
  assert.deepEqual(pending.change.credential, key('candidate'));
  gate.resolve();
  const result = await resultPromise;
  result.key = 'returned-mutation';
  assert.deepEqual(host.credential, key('candidate'));
});

test('等待持久化 ACK，不返回本地候选而返回宿主权威结果', async () => {
  const { store, transactions } = fixture();
  const ack = deferred();
  transactions.finish = () => ack.promise;
  let settled = false;
  const work = store.modify('openai', () => key('candidate')).finally(() => { settled = true; });
  await tick();
  assert.equal(settled, false);
  ack.resolve({ credential: key('host-authoritative') });
  assert.deepEqual(await work, key('host-authoritative'));
});

test('并发 modify 串行重读，不重复开始事务', async () => {
  const { store, host } = fixture();
  const gate = deferred();
  const first = store.modify('openai', async () => { await gate.promise; return key('first'); });
  const second = store.modify('openai', current => {
    assert.deepEqual(current, key('first'));
    return key('second');
  });
  await tick();
  assert.deepEqual(host.calls, ['begin']);
  gate.resolve();
  await Promise.all([first, second]);
  assert.deepEqual(host.calls, ['begin', 'put', 'begin', 'put']);
});

test('begin 前取消及排队取消不调用宿主，也不释放前序锁', async () => {
  const { store, host } = fixture();
  const already = AbortSignal.abort('secret-reason');
  await assert.rejects(store.modify('openai', () => key(), { signal: already }), errorCode('CANCELLED'));
  assert.deepEqual(host.calls, []);
  const gate = deferred();
  const first = store.modify('openai', async () => { await gate.promise; });
  const controller = new AbortController();
  const second = store.modify('openai', () => assert.fail('must not invoke'), { signal: controller.signal });
  const rejected = assert.rejects(second, errorCode('CANCELLED'));
  controller.abort();
  await rejected;
  const third = store.modify('openai', () => key('third'));
  await tick();
  assert.deepEqual(host.calls, ['begin']);
  gate.resolve();
  await Promise.all([first, third]);
  assert.deepEqual(host.calls, ['begin', 'keep', 'begin', 'put']);
});

test('取消等待 begin 返回后 abort，不执行回调', async () => {
  const { store, transactions, host } = fixture();
  const begin = deferred();
  transactions.begin = () => begin.promise;
  const controller = new AbortController();
  const work = store.modify('openai', () => assert.fail(), { signal: controller.signal });
  const rejected = assert.rejects(work, errorCode('CANCELLED'));
  await tick();
  controller.abort();
  begin.resolve({ transactionId: 'host_TX-1', credential: key() });
  await rejected;
  assert.deepEqual(host.calls, ['abort']);
});

test('回调取消不提前释放仍运行回调；收敛后 abort，再启动下一事务', async () => {
  const { store, host } = fixture();
  const gate = deferred();
  const controller = new AbortController();
  const first = store.modify('openai', async () => { await gate.promise; throw 'secret'; },
    { signal: controller.signal });
  const rejected = assert.rejects(first, errorCode('CANCELLED'));
  await tick();
  controller.abort();
  const second = store.modify('openai', () => key('second'));
  await tick();
  assert.deepEqual(host.calls, ['begin']);
  gate.resolve();
  await rejected;
  await second;
  assert.deepEqual(host.calls, ['begin', 'abort', 'begin', 'put']);
});

test('合法迟到旋转独立 finish，ACK 后抛取消，不声称回滚', async () => {
  const { store, host, transactions } = fixture();
  const ack = deferred();
  const controller = new AbortController();
  const finish = transactions.finish;
  transactions.finish = async (request, { signal }) => {
    assert.notEqual(signal, controller.signal);
    assert.equal(signal.aborted, false);
    await ack.promise;
    return finish(request);
  };
  let settled = false;
  const work = store.modify('openai', () => {
    controller.abort('secret');
    return key('rotated');
  }, { signal: controller.signal }).finally(() => { settled = true; });
  const rejected = assert.rejects(work, errorCode('CANCELLED'));
  await tick();
  assert.equal(settled, false);
  ack.resolve();
  await rejected;
  assert.deepEqual(host.credential, key('rotated'));
  assert.deepEqual(host.calls, ['begin', 'put']);
});

test('严格白名单、类型、ASCII、单项/整体编码上限；无 getter/toJSON 执行', async () => {
  // 重复键必须由私有帧解码器在 JSON.parse 前拒绝；store 不接受原始 JSON 字符串。
  const badKeys = [undefined, '{"type":"api_key","type":"oauth","key":"fake"}', {}, [], oauth(), { ...key(), env: {} }, { ...key(), headers: {} },
    { ...key(), extra: 1 }, { type: 'api_key', key: 1 }, key(''), key('\n'), key('中文'),
    key('\x7f'), key('x'.repeat(16385)), Object.assign(Object.create({ inherited: 1 }), key()),
    Object.defineProperty(key(), 'key', { get() { assert.fail('getter'); } }),
    Object.defineProperty(key(), 'hidden', { value: 1 }), { ...key(), [Symbol()]: 1 },
    { ...key(), toJSON() { assert.fail('toJSON'); } }];
  const badOAuth = [key(), { ...oauth(), accountId: '' }, { ...oauth(), accountId: 'x'.repeat(257) },
    { ...oauth(), access: '' }, { ...oauth(), refresh: '\t' }, { ...oauth(), expires: 0 },
    { ...oauth(), expires: 1.2 }, { ...oauth(), expires: Number.MAX_SAFE_INTEGER + 1 },
    { ...oauth(), expires: '100' }, { ...oauth(), expires: NaN },
    { ...oauth(), access: 'a'.repeat(16384), refresh: 'r'.repeat(16384) },
    { ...oauth(), access: '"'.repeat(13000) }, { ...oauth(), env: {} }];
  for (const [id, invalid] of [['openai', badKeys], ['openai-codex', badOAuth]]) {
    for (const value of invalid) {
      const { store, host } = fixture(id, value);
      // fixture 的默认参数会把 undefined 替换成 key，因此单独赋值。
      host.credential = value;
      await assert.rejects(store.read(id), errorCode('INVALID_CREDENTIAL'));
      host.credential = id === 'openai' ? key() : oauth();
      if (value !== undefined) {
        await assert.rejects(store.modify(id, () => value), errorCode('INVALID_CREDENTIAL'));
        assert.equal(host.calls.at(-1), 'abort');
      }
    }
  }
  assert.ok(await fixture('openai', key('x'.repeat(16384))).store.read('openai'));
  assert.ok(await fixture('openai-codex', oauth()).store.read('openai-codex'));
});

test('无效宿主事务 ID 拒绝，callback 不能提供 ID', async () => {
  for (const id of ['', 'x'.repeat(97), '../escape', '中文', 'id\n', 1]) {
    const { store, transactions } = fixture();
    transactions.begin = async () => ({ transactionId: id, credential: key() });
    await assert.rejects(store.modify('openai', () => assert.fail()), errorCode('INVALID_TRANSACTION'));
  }
  const { store, host } = fixture();
  await assert.rejects(store.modify('openai', () => ({ ...key(), transactionId: 'evil' })),
    errorCode('INVALID_CREDENTIAL'));
  assert.equal(host.calls.at(-1), 'abort');
});

test('错误 canary：read/begin/change/finish/abort 本地错误不泄露上游异常', async () => {
  const canary = 'CANARY-NEVER-LOG';
  const secret = { get message() { assert.fail('message accessed'); },
    toString() { assert.fail('toString called'); } };
  for (const [stage, code] of [['read', 'READ_FAILED'], ['begin', 'BEGIN_FAILED'],
    ['change', 'CHANGE_FAILED'], ['finish', 'FINISH_FAILED'], ['abort', 'ABORT_FAILED']]) {
    const { store, transactions } = fixture();
    if (stage !== 'change') transactions[stage] = async () => { throw secret; };
    const action = stage === 'read' ? store.read('openai') : store.modify('openai', () => {
      if (stage === 'change' || stage === 'abort') throw new Error(canary);
      return key();
    });
    await assert.rejects(action, error => {
      assert.ok(errorCode(code)(error));
      assert.equal(String(error).includes(canary), false);
      assert.equal(JSON.stringify(error).includes(canary), false);
      assert.equal(error.stack.includes(canary), false);
      return true;
    });
    assert.deepEqual(store.diagnostics(), { [code]: 1 });
  }
  const { store } = fixture();
  await assert.rejects(store.read('openai', { get signal() { throw secret; } }),
    errorCode('INVALID_ARGUMENT'));
});

test('finish 拒绝（含 logout fence）必须 abort，不返回候选', async () => {
  const { store, host, transactions } = fixture();
  transactions.finish = async () => { throw new Error('generation-conflict-secret'); };
  await assert.rejects(store.modify('openai', () => key('rotated')), errorCode('FINISH_FAILED'));
  assert.deepEqual(host.credential, key());
  assert.deepEqual(host.calls, ['begin', 'abort']);
});

test('损坏的 begin snapshot 释放锁，非法 ACK 不假成功', async () => {
  const { store, transactions, host } = fixture();
  transactions.begin = async () => ({ transactionId: 'host_TX-1', credential: { ...key(), env: {} } });
  await assert.rejects(store.modify('openai', () => assert.fail()), errorCode('INVALID_CREDENTIAL'));
  assert.deepEqual(host.calls, ['abort']);
  const other = fixture();
  other.transactions.finish = async () => ({ credential: undefined });
  await assert.rejects(other.store.modify('openai', () => key()), errorCode('INVALID_CREDENTIAL'));
});

test('abort 失败使实例关闭，不能假定宿主锁已释放', async () => {
  const { store, transactions, host } = fixture();
  transactions.abort = async () => { throw 'secret'; };
  await assert.rejects(store.modify('openai', () => { throw 'secret'; }), errorCode('ABORT_FAILED'));
  await assert.rejects(store.modify('openai', () => key()), errorCode('CLOSED'));
  assert.deepEqual(host.calls, ['begin']);
});

test('本地抛出的恶意 Proxy 异常也不触发原型/message/toString', async () => {
  const { store } = fixture();
  const hostile = new Proxy({}, {
    getPrototypeOf() { throw new Error('CANARY-prototype'); },
    get() { throw new Error('CANARY-get'); },
  });
  await assert.rejects(store.read('openai', { get signal() { throw hostile; } }),
    errorCode('INVALID_ARGUMENT'));
});

test('finish/abort 清理超时有界，永久关闭实例，不启动下一事务', async () => {
  await Promise.all(['finish', 'abort'].map(async stage => {
    const { store, transactions, host } = fixture();
    const pending = deferred();
    let cleanupSignal;
    transactions[stage] = (_, { signal }) => { cleanupSignal = signal; return pending.promise; };
    const started = performance.now();
    const first = store.modify('openai', () => {
      if (stage === 'abort') throw new Error('secret');
      return key('candidate');
    });
    const rejected = assert.rejects(first, errorCode('CLEANUP_TIMEOUT'));
    const second = assert.rejects(store.modify('openai', () => assert.fail()), errorCode('CLOSED'));
    await rejected;
    await second;
    assert.ok(performance.now() - started >= 1900);
    assert.ok(performance.now() - started < 4000);
    assert.equal(cleanupSignal.aborted, true);
    assert.deepEqual(host.calls, ['begin']);
    await assert.rejects(store.read('openai'), errorCode('CLOSED'));
    // 即使 RPC 忽略 abort 后迟到成功，也不能重开实例或返回候选。
    pending.resolve({ credential: key('late') });
    await tick();
    await assert.rejects(store.modify('openai', () => key()), errorCode('CLOSED'));
  }));
});
