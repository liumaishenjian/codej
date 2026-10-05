import test from 'node:test';
import assert from 'node:assert/strict';
import {createModels} from '@earendil-works/pi-ai';
import {createRegisteredProviders} from './provider-registry.mjs';
import {createAuthOperation, AuthOperationError} from './auth-operation.mjs';

// 全部为独立合成数据；不读取 env/用户账户，不调用真实 OAuth、网络或浏览器监听器。
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => {resolve = a; reject = b;}); return {promise, resolve, reject}; };
const oauth = () => ({type: 'oauth', access: 'synthetic-access', refresh: 'synthetic-refresh', expires: 9000000000000, accountId: 'synthetic-account'});
const validUrl = 'https://auth.openai.com/oauth/authorize?redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback&state=synthetic-state';
const safeCode = expected => error => error instanceof AuthOperationError && error.code === expected
  && error.message === expected && error.cause === undefined && !String(error).includes('SYNTHETIC_PRIVATE');
const fakeCodex = login => () => [{id: 'openai-codex', name: 'Fake Codex', auth: {oauth: {login}}}];
function harness(options = {}) {
  const frames = [], requests = [];
  let operation, stored = null, finish;
  const reply = (request, result) => operation.accept({type: 'credential.response',
    payload: {requestId: request.requestId, ok: true, result}});
  operation = createAuthOperation({providerId: 'openai', authType: 'api_key', ...options,
    async send(type, payload) {
      frames.push({type, payload});
      if (options.onSend) await options.onSend(type, payload);
      if (type !== 'credential.request') return;
      requests.push(payload);
      if (payload.action === 'begin') reply(payload, {transactionId: 'synthetic-tx', credential: stored});
      else if (payload.action === 'finish') {
        finish = () => {
          if (options.rejectFinish) operation.accept({type: 'credential.response', payload: {
            requestId: payload.requestId, ok: false, code: 'SYNTHETIC_PRIVATE'}});
          else {
            stored = structuredClone(payload.arguments.change.credential);
            reply(payload, {credential: stored});
          }
        };
        if (!options.delayFinish) finish();
      } else if (payload.action === 'abort') reply(payload, {});
      else assert.fail('Unexpected RPC action');
    },
  });
  return {operation, frames, requests, reply,
    answer(promptId, value = 'synthetic-key') {operation.accept({type: 'auth.response', payload: {promptId, value}});},
    finish() {assert.equal(typeof finish, 'function'); finish();},
    stored() {return stored;},
  };
}
async function prompted(h) {
  const result = h.operation.run();
  // 立刻观察错误，避免测试故意取消时产生暂未处理的拒绝。
  void result.catch(() => {});
  await tick();
  return {result};
}

for (const providerId of ['openai', 'deepseek', 'qwen-token-plan-cn']) {
  test(`真实 Pi0.85.1 ${providerId} API Key + Fake 私有宿主：精确提示、持久化 ACK、无凭证结果`, async () => {
    const h = harness({providerId});
    const {result} = await prompted(h);
    assert.deepEqual(h.frames, [{type: 'auth.prompt', payload: {promptId: 1, kind: 'secret'}}]);
    h.answer(1);
    assert.deepEqual(await result, {providerId, authType: 'api_key', status: 'stored'});
    assert.deepEqual(h.stored(), {type: 'api_key', key: 'synthetic-key'});
    assert.deepEqual(h.requests.map(r => r.action), ['begin', 'finish']);
    assert.deepEqual(h.requests.map(r => r.requestId), [1, 2]);
    await assert.rejects(h.operation.run(), safeCode('AUTH_ALREADY_RUN'));
    assert.throws(() => h.answer(1), safeCode('AUTH_CLOSED'));
  });
}

test('真实 Models 工厂注入仅用于观察：单路由 setProvider，禁用环境/文件 fallback', async () => {
  let factoryCalls = 0;
  const routes = [];
  const h = harness({modelsFactory(options) {
    factoryCalls++;
    const models = createModels(options);
    const setProvider = models.setProvider.bind(models);
    models.setProvider = provider => {routes.push(provider.id); setProvider(provider);};
    const login = models.login.bind(models);
    models.login = async (...args) => {
      assert.equal(await options.authContext.env('UNUSED_SYNTHETIC_NAME'), undefined);
      assert.equal(await options.authContext.fileExists('unused-synthetic-path'), false);
      return login(...args);
    };
    return models;
  }});
  const {result} = await prompted(h); h.answer(1); await result;
  assert.equal(factoryCalls, 1); assert.deepEqual(routes, ['openai']);
});

for (const [providerId, authType] of [['openai', 'oauth'], ['openai-codex', 'api_key'],
  ['deepseek', 'oauth'], ['qwen-token-plan-cn', 'oauth'], ['unknown', 'api_key'], ['openai', 'API_KEY']]) {
  test(`拒绝不匹配路由 ${providerId}/${authType}，不调用工厂`, () => {
    assert.throws(() => createAuthOperation({providerId, authType, send() {}, modelsFactory() {assert.fail();}}), safeCode('AUTH_INVALID'));
  });
}

test('Codex rejects extra secret prompts', async () => {
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    await i.prompt({type: 'secret'}); return oauth();
  })});
  const {result} = await prompted(h);
  if (h.frames.some(frame => frame.type === 'auth.prompt')) h.answer(1);
  await assert.rejects(result, safeCode('AUTH_INCOMPATIBLE'));
  assert.equal(h.requests.length, 0);
});

test('Fake Codex +真实 Models：只选 browser、授权URL与manual_code，不增加密码提示', async () => {
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async interaction => {
    assert.equal(await interaction.prompt({type: 'select', message: 'SYNTHETIC_PRIVATE', options: [
      {id: 'device', label: 'SYNTHETIC_PRIVATE'}, {id: 'browser', label: 'SYNTHETIC_PRIVATE'}]}), 'browser');
    interaction.notify({type: 'info', message: 'SYNTHETIC_PRIVATE'});
    interaction.notify({type: 'progress', message: 'SYNTHETIC_PRIVATE'});
    interaction.notify({type: 'auth_url', url: validUrl, instructions: 'SYNTHETIC_PRIVATE'});
    assert.equal(await interaction.prompt({type: 'manual_code', message: 'SYNTHETIC_PRIVATE'}), 'synthetic-callback');
    return oauth();
  })});
  const {result} = await prompted(h);
  assert.deepEqual(h.frames, [{type: 'auth.url', payload: {url: validUrl}},
    {type: 'auth.prompt', payload: {promptId: 1, kind: 'manual_code'}}]);
  h.answer(1, 'synthetic-callback');
  assert.deepEqual(await result, {providerId: 'openai-codex', authType: 'oauth', status: 'stored'});
  assert.ok(!JSON.stringify(h.frames).includes('SYNTHETIC_PRIVATE'));
});

for (const options of [[], [{id: 'device'}], [{id: 'browser'}, {id: 'browser'}]]) {
  test(`Fake Codex 拒绝不兼容 browser 选项 ${JSON.stringify(options)}`, async () => {
    const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
      await i.prompt({type: 'select', options}); return oauth();
    })});
    await assert.rejects(h.operation.run(), safeCode('AUTH_INCOMPATIBLE'));
    assert.equal(h.frames.length, 0);
  });
}

const badUrls = [
  ['非 HTTPS', validUrl.replace('https:', 'http:')],
  ['非官方主机', validUrl.replace('auth.openai.com', 'evil.example')],
  ['主机后缀', validUrl.replace('auth.openai.com', 'auth.openai.com.evil.example')],
  ['userinfo', validUrl.replace('auth.openai.com', 'user@auth.openai.com')],
  ['空 userinfo', validUrl.replace('auth.openai.com', '@auth.openai.com')],
  ['显式443', validUrl.replace('auth.openai.com', 'auth.openai.com:443')],
  ['任意端口', validUrl.replace('auth.openai.com', 'auth.openai.com:8443')],
  ['错误路径', validUrl.replace('/oauth/authorize?', '/oauth/token?')],
  ['hash', validUrl + '#fragment'], ['空hash', validUrl + '#'],
  ['空state', validUrl.replace('synthetic-state', '')],
  ['缺state', validUrl.replace('&state=synthetic-state', '')],
  ['重复state', validUrl + '&state=other'], ['编码重复state', validUrl + '&%73tate=other'],
  ['重复redirect', validUrl + '&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback'],
  ['缺redirect', 'https://auth.openai.com/oauth/authorize?state=synthetic'],
  ['错误回调主机', validUrl.replace('localhost', '127.0.0.1')],
  ['错误回调端口', validUrl.replace('1455', '1456')],
  ['回调额外query', validUrl.replace('callback&', 'callback%3Fx%3Dy&')],
  ['前导空格', ' ' + validUrl], ['反斜线', validUrl.replace('/oauth/', '\\oauth/')],
  ['超长', validUrl + '&padding=' + 'x'.repeat(16384)],
];
for (const [name, url] of badUrls) {
  test(`Fake Codex URL 白名单拒绝：${name}`, async () => {
    const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
      i.notify({type: 'auth_url', url}); return oauth();
    })});
    await assert.rejects(h.operation.run(), safeCode('AUTH_INVALID'));
    assert.equal(h.frames.length, 0); assert.equal(h.stored(), null);
  });
}

for (const type of ['device_code', 'unknown']) {
  test(`Fake Codex 拒绝 ${type} 通知，不输出 SDK 正文`, async () => {
    const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
      i.notify({type, userCode: 'SYNTHETIC_PRIVATE'}); return oauth();
    })});
    await assert.rejects(h.operation.run(), safeCode('AUTH_INCOMPATIBLE')); assert.equal(h.frames.length, 0);
  });
}

test('Fake API route 拒绝 manual_code/select/auth_url，不能串到 Codex', async () => {
  for (const action of [i => i.prompt({type: 'manual_code'}), i => i.prompt({type: 'select', options: [{id: 'browser'}]}),
    i => i.notify({type: 'auth_url', url: validUrl})]) {
    const h = harness({providersFactory: () => [{id: 'openai', auth: {apiKey: {login: async i => {
      await action(i); return {type: 'api_key', key: 'synthetic'};
    }}}}]});
    await assert.rejects(h.operation.run(), safeCode('AUTH_INCOMPATIBLE')); assert.equal(h.frames.length, 0);
  }
});

test('Fake Codex 回调抢先取消提示：一次迟到输入仅消耗旧 tombstone，不污染新 prompt', async () => {
  const callback = new AbortController();
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    await assert.rejects(i.prompt({type: 'manual_code', signal: callback.signal}), safeCode('AUTH_PROMPT_CANCELLED'));
    assert.equal(await i.prompt({type: 'manual_code'}), 'second-input'); return oauth();
  })});
  const {result} = await prompted(h);
  callback.abort('SYNTHETIC_PRIVATE'); await tick();
  assert.deepEqual(h.frames, [
    {type: 'auth.prompt', payload: {promptId: 1, kind: 'manual_code'}},
    {type: 'auth.prompt_cancelled', payload: {promptId: 1}},
    {type: 'auth.prompt', payload: {promptId: 2, kind: 'manual_code'}},
  ]);
  h.answer(1, 'late-old-input'); await tick(); assert.equal(h.requests.length, 0);
  h.answer(2, 'second-input'); await result;
});

test('Fake Codex 已取消提示的第二次迟到输入失败关闭', async () => {
  const callback = new AbortController();
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    await i.prompt({type: 'manual_code', signal: callback.signal}).catch(() => {});
    await i.prompt({type: 'manual_code'}); return oauth();
  })});
  const {result} = await prompted(h); callback.abort(); await tick(); h.answer(1);
  assert.throws(() => h.answer(1), safeCode('AUTH_INVALID'));
  await assert.rejects(result, safeCode('AUTH_INVALID')); assert.equal(h.requests.length, 0);
});

test('Fake Codex 预先 aborted prompt 不发送、不挂起；后续仍可独立输入', async () => {
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    await assert.rejects(i.prompt({type: 'manual_code', signal: AbortSignal.abort('SYNTHETIC_PRIVATE')}), safeCode('AUTH_PROMPT_CANCELLED'));
    await i.prompt({type: 'manual_code'}); return oauth();
  })});
  const {result} = await prompted(h);
  assert.deepEqual(h.frames, [{type: 'auth.prompt', payload: {promptId: 1, kind: 'manual_code'}}]);
  h.answer(1); await result;
});

test('Fake Codex prompt signal 注册期间 race：取消事件不会遗漏', async () => {
  const callback = new AbortController();
  const add = callback.signal.addEventListener.bind(callback.signal);
  callback.signal.addEventListener = (...args) => {callback.abort(); add(...args);};
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    await assert.rejects(i.prompt({type: 'manual_code', signal: callback.signal}), safeCode('AUTH_PROMPT_CANCELLED'));
    return oauth();
  })});
  await h.operation.run();
  assert.deepEqual(h.frames.slice(0, 2).map(f => f.type), ['auth.prompt', 'auth.prompt_cancelled']);
});

test('Fake Codex 最多16提示，超限失败，无持久化', async () => {
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    for (let n = 0; n < 17; n++) await i.prompt({type: 'manual_code'});
    return oauth();
  })});
  const {result} = await prompted(h);
  for (let n = 1; n <= 16; n++) {h.answer(n); await tick();}
  await assert.rejects(result, safeCode('AUTH_LIMIT'));
  assert.equal(h.frames.length, 16); assert.equal(h.requests.length, 0);
});

test('Fake Codex 并发第二个提示拒绝并拒绝原提示', async () => {
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    const first = i.prompt({type: 'manual_code'});
    const second = i.prompt({type: 'manual_code'});
    await Promise.all([first, second]); return oauth();
  })});
  await assert.rejects(h.operation.run(), safeCode('AUTH_INVALID')); assert.equal(h.requests.length, 0);
});

for (const [name, value] of [['空', ''], ['whitespace', '   '], ['非ASCII', '合成'], ['控制字符', 'a\nb'], ['DEL', 'a\x7f'],
  ['超长', 'x'.repeat(16385)], ['非字符串', 1]]) {
  test(`API secret 拒绝${name}输入`, async () => {
    const h = harness(); const {result} = await prompted(h);
    assert.throws(() => h.answer(1, value), safeCode('AUTH_INVALID'));
    await assert.rejects(result, safeCode('AUTH_INVALID')); assert.equal(h.requests.length, 0);
  });
}

test('API secret 接受16384 ASCII边界值', async () => {
  const h = harness(); const {result} = await prompted(h); h.answer(1, 'x'.repeat(16384)); await result;
  assert.equal(h.stored().key.length, 16384);
});

for (const action of [h => h.answer(2), h => h.answer(0), h => h.answer(1.5),
  h => h.operation.accept({type: 'auth.response', payload: {promptId: 1, value: 'synthetic', extra: true}}),
  h => h.operation.accept({type: 'unknown', payload: {}})]) {
  test('未知/非法ID、额外字段及未知帧失败关闭', async () => {
    const h = harness(); const {result} = await prompted(h);
    assert.throws(() => action(h), safeCode('AUTH_INVALID'));
    await assert.rejects(result, safeCode('AUTH_INVALID'));
  });
}

test('重复输入拒绝，不允许消耗另一提示或完成登录', async () => {
  const h = harness(); const {result} = await prompted(h); h.answer(1);
  assert.throws(() => h.answer(1), safeCode('AUTH_INVALID'));
  await assert.rejects(result, safeCode('AUTH_INVALID')); assert.equal(h.requests.length, 0);
});

test('run 只能启动一次，在途重复调用不重建 SDK 或关闭原合法输入', async () => {
  const h = harness(); const {result} = await prompted(h);
  await assert.rejects(h.operation.run(), safeCode('AUTH_ALREADY_RUN'));
  h.answer(1); await result;
});

for (const action of ['abort', 'close']) {
  test(`外部 ${action} 拒绝pending与迟到响应；不能复活`, async () => {
    const controller = new AbortController();
    const h = harness({signal: controller.signal}); const {result} = await prompted(h);
    const code = action === 'abort' ? 'AUTH_CANCELLED' : 'AUTH_CLOSED';
    if (action === 'abort') controller.abort('SYNTHETIC_PRIVATE'); else h.operation.close();
    await assert.rejects(result, safeCode(code)); assert.throws(() => h.answer(1), safeCode(code));
    h.operation.close(); await tick(); assert.equal(h.requests.length, 0);
  });
}

test('run 前取消或close零工厂调用/零输出；run前响应拒绝', async () => {
  for (const mode of ['abort', 'close', 'input']) {
    const h = harness({signal: mode === 'abort' ? AbortSignal.abort('SYNTHETIC_PRIVATE') : undefined,
      modelsFactory() {assert.fail();}});
    if (mode === 'close') h.operation.close();
    if (mode === 'input') assert.throws(() => h.answer(1), safeCode('AUTH_INVALID'));
    await assert.rejects(h.operation.run(), safeCode(mode === 'abort' ? 'AUTH_CANCELLED' : mode === 'close' ? 'AUTH_CLOSED' : 'AUTH_INVALID'));
    assert.equal(h.frames.length, 0);
  }
});

test('真实 SDK API Key 等待持久化ACK，拒绝ACK时没有stored', async () => {
  for (const rejectFinish of [false, true]) {
    const h = harness({delayFinish: true, rejectFinish});
    const {result} = await prompted(h); let settled = false; void result.then(() => {settled = true;}, () => {settled = true;});
    h.answer(1); await tick(); assert.equal(settled, false); assert.equal(h.stored(), null);
    h.finish();
    if (rejectFinish) await assert.rejects(result, safeCode('AUTH_STORAGE_FAILED'));
    else assert.equal((await result).status, 'stored');
    assert.deepEqual(h.requests.map(r => r.action), ['begin', 'finish']);
  }
});

test('持久ACK在途取消关闭RPC；迟到ACK不能输出成功', async () => {
  const controller = new AbortController(); const h = harness({delayFinish: true, signal: controller.signal});
  const {result} = await prompted(h); h.answer(1); await tick(); controller.abort();
  await assert.rejects(result, safeCode('AUTH_CANCELLED'));
  assert.throws(() => h.finish(), safeCode('AUTH_CANCELLED'));
});

test('提示send未完成时即使收到输入也不允许存储；随后send失败不会误报成功', async () => {
  const output = deferred();
  const h = harness({onSend: type => type === 'auth.prompt' ? output.promise : undefined});
  const {result} = await prompted(h); h.answer(1); await tick(); assert.equal(h.requests.length, 0);
  output.reject(new Error('SYNTHETIC_PRIVATE'));
  await assert.rejects(result, safeCode('AUTH_SEND_FAILED')); assert.equal(h.requests.length, 0);
});

test('Fake Codex 同步notify的异步写入失败：取消SDK且禁止begin/保存/完成', async () => {
  const output = deferred(); let sdkSignal;
  const h = harness({providerId: 'openai-codex', authType: 'oauth',
    onSend: type => type === 'auth.url' ? output.promise : undefined,
    providersFactory: fakeCodex(async i => {sdkSignal = i.signal; i.notify({type: 'auth_url', url: validUrl}); return oauth();})});
  const {result} = await prompted(h); assert.equal(h.requests.length, 0);
  output.reject(new Error('SYNTHETIC_PRIVATE'));
  await assert.rejects(result, safeCode('AUTH_SEND_FAILED')); assert.equal(sdkSignal.aborted, true);
  assert.equal(h.requests.length, 0); assert.equal(h.stored(), null);
});

test('Fake Codex prompt_cancelled写入失败不保存、不漏拒绝', async () => {
  const callback = new AbortController();
  const h = harness({providerId: 'openai-codex', authType: 'oauth',
    onSend(type) {if (type === 'auth.prompt_cancelled') throw new Error('SYNTHETIC_PRIVATE');},
    providersFactory: fakeCodex(async i => {
      await i.prompt({type: 'manual_code', signal: callback.signal}).catch(() => {}); return oauth();
    })});
  const {result} = await prompted(h); callback.abort();
  await assert.rejects(result, safeCode('AUTH_SEND_FAILED')); assert.equal(h.requests.length, 0);
});

test('Fake SDK不守取消仍不能迟到保存/完成；所有错误为固定码', async () => {
  const wait = deferred(); let options;
  const h = harness({modelsFactory(input) {
    options = input;
    return {setProvider() {}, async login(id) {
      await wait.promise;
      await input.credentials.modify(id, () => ({type: 'api_key', key: 'synthetic'}));
      return {type: 'api_key', key: 'synthetic'};
    }};
  }});
  const {result} = await prompted(h); h.operation.close();
  await assert.rejects(result, safeCode('AUTH_CLOSED')); wait.resolve(); await tick();
  assert.equal(h.requests.length, 0);
  assert.throws(() => options.credentials.read('openai'), safeCode('AUTH_CLOSED'));
});

test('Fake SDK返回凭证但未持久ACK不算stored；SDK失效不fallback', async () => {
  for (const login of [async () => ({type: 'api_key', key: 'SYNTHETIC_PRIVATE'}),
    async () => {throw new Error('SYNTHETIC_PRIVATE');}]) {
    let calls = 0;
    const h = harness({modelsFactory() {calls++; return {setProvider() {}, login};}});
    await assert.rejects(h.operation.run(), error => error instanceof AuthOperationError
      && ['AUTH_STORAGE_FAILED', 'AUTH_FAILED'].includes(error.code) && !String(error).includes('SYNTHETIC_PRIVATE') && !error.cause);
    assert.equal(calls, 1); assert.equal(h.requests.length, 0);
  }
});

test('已收到输入但prompt写入ACK未到时取消，重复输入不能消费tombstone', async () => {
  const callback = new AbortController(), output = deferred();
  const h = harness({providerId: 'openai-codex', authType: 'oauth',
    onSend: (type, payload) => type === 'auth.prompt' && payload.promptId === 1 ? output.promise : undefined,
    providersFactory: fakeCodex(async i => {
      await i.prompt({type: 'manual_code', signal: callback.signal}).catch(() => {});
      await i.prompt({type: 'manual_code'}); return oauth();
    })});
  const {result} = await prompted(h); h.answer(1); callback.abort(); await tick();
  assert.throws(() => h.answer(1), safeCode('AUTH_INVALID'));
  await assert.rejects(result, safeCode('AUTH_INVALID')); output.resolve(); await tick();
  assert.equal(h.requests.length, 0);
});

test('未知 credential.response 关闭真实登录RPC与pending输入', async () => {
  const h = harness(); const {result} = await prompted(h);
  assert.throws(() => h.operation.accept({type: 'credential.response', payload: {
    requestId: 1, ok: true, result: {credential: null}}}), safeCode('AUTH_STORAGE_FAILED'));
  await assert.rejects(result, safeCode('AUTH_STORAGE_FAILED'));
  assert.equal(h.requests.length, 0);
});

test('外部 signal 注册 race 和运行中SDK无限等待均及时收敛', async () => {
  const controller = new AbortController();
  const add = controller.signal.addEventListener.bind(controller.signal);
  controller.signal.addEventListener = (...args) => {controller.abort(); add(...args);};
  const before = harness({signal: controller.signal});
  await assert.rejects(before.operation.run(), safeCode('AUTH_CANCELLED'));
  assert.equal(before.frames.length, 0);
  const pending = harness({providerId: 'openai-codex', authType: 'oauth',
    providersFactory: fakeCodex(() => new Promise(() => {}))});
  const {result} = await prompted(pending); pending.operation.close();
  await assert.rejects(result, safeCode('AUTH_CLOSED'));
});

test('Fake Codex URL成功写入前不启动持久化，ACK后才完成', async () => {
  const output = deferred();
  const h = harness({providerId: 'openai-codex', authType: 'oauth', delayFinish: true,
    onSend: type => type === 'auth.url' ? output.promise : undefined,
    providersFactory: fakeCodex(async i => {i.notify({type: 'auth_url', url: validUrl}); return oauth();})});
  const {result} = await prompted(h); assert.equal(h.requests.length, 0);
  output.resolve(); await tick(); assert.deepEqual(h.requests.map(r => r.action), ['begin', 'finish']);
  let settled = false; void result.then(() => {settled = true;});
  await tick(); assert.equal(settled, false); h.finish(); assert.equal((await result).status, 'stored');
});

test('SDK未知prompt类型失败关闭且不转发正文', async () => {
  const h = harness({providerId: 'openai-codex', authType: 'oauth', providersFactory: fakeCodex(async i => {
    await i.prompt({type: 'text', message: 'SYNTHETIC_PRIVATE'}); return oauth();
  })});
  await assert.rejects(h.operation.run(), safeCode('AUTH_INCOMPATIBLE')); assert.equal(h.frames.length, 0);
});

test('Fake SDK LOGIN keep/delete不能成为成功持久化', async () => {
  for (const candidate of [undefined, null]) {
    const h = harness({modelsFactory({credentials}) {return {setProvider() {}, async login(id) {
      await credentials.modify(id, async () => candidate); return {type: 'api_key', key: 'synthetic'};
    }};}});
    await assert.rejects(h.operation.run(), safeCode('AUTH_STORAGE_FAILED'));
    assert.ok(h.requests.every(request => request.action !== 'finish'));
  }
});

test('缺少或重复目标工厂拒绝，不静默选择其他路由', async () => {
  for (const providersFactory of [() => [], () => [createRegisteredProviders()[0], createRegisteredProviders()[0]]]) {
    const h = harness({providersFactory}); await assert.rejects(h.operation.run(), safeCode('AUTH_INCOMPATIBLE'));
    assert.equal(h.frames.length, 0);
  }
});
