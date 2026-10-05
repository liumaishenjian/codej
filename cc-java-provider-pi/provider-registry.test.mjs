import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRegisteredProviders, describeCatalog, PI_VERSION } from './provider-registry.mjs';

const IDS = ['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn'];
const forbidden = () => { throw new Error('secret-must-not-escape'); };
const model = (id = 'model-a') => ({ id, name: '测试模型', api: 'openai-responses',
  contextWindow: 128000, maxTokens: 4096, reasoning: false, input: ['text', 'image'] });
function fakes(models = [model()]) {
  return IDS.map(id => ({ id,
    auth: id === 'openai-codex' ? { oauth: { login: forbidden, refresh: forbidden } }
      : { apiKey: { resolve: forbidden } },
    getModels: () => models,
    getAuth: forbidden, getAvailable: forbidden, refresh: forbidden, refreshModels: forbidden,
  }));
}
function isDeepFrozen(value) {
  if (value !== null && typeof value === 'object') {
    assert.ok(Object.isFrozen(value));
    for (const child of Object.values(value)) isDeepFrozen(child);
  }
}
function rejects(providers) {
  assert.throws(() => describeCatalog(providers), {
    name: 'TypeError', message: 'Invalid provider catalog metadata',
  });
}

test('真实公开工厂：三个品牌、四路由、完整模型与声明认证能力；不查询凭证', () => {
  const providers = createRegisteredProviders();
  assert.deepEqual(providers.map(p => p.id), IDS);
  const catalog = describeCatalog(providers);
  assert.equal(PI_VERSION, '0.85.1');
  assert.equal(catalog.piVersion, PI_VERSION);
  assert.deepEqual(catalog.brands, [
    { id: 'openai', label: 'OpenAI', providerIds: ['openai', 'openai-codex'] },
    { id: 'deepseek', label: 'DeepSeek', providerIds: ['deepseek'] },
    { id: 'qwen', label: '通义', providerIds: ['qwen-token-plan-cn'] },
  ]);
  assert.match(catalog.providers[0].label, /API/);
  assert.match(catalog.providers[1].label, /ChatGPT\/Codex/);
  assert.match(catalog.providers[3].label, /国内.*Token Plan/);
  catalog.providers.forEach((p, index) => {
    const source = providers[index];
    assert.ok(p.models.length > 0);
    assert.deepEqual(p.models.map(m => m.id), source.getModels().map(m => m.id).sort());
    assert.deepEqual(p.authMethods.map(m => m.id), [index === 1 ? 'oauth' : 'api_key']);
    assert.equal(typeof source.auth[index === 1 ? 'oauth' : 'apiKey'], 'object');
    for (const m of p.models) assert.deepEqual(Object.keys(m),
      ['id', 'label', 'api', 'contextWindow', 'maxTokens', 'reasoning', 'input']);
  });
  assert.deepEqual(JSON.parse(JSON.stringify(catalog)), catalog);
  assert.deepEqual(describeCatalog(), catalog);
  isDeepFrozen(catalog);
});

test('纯投影不读取未知秘密字段，也不调用认证或可用性接口', () => {
  const m = model();
  const providers = fakes([m]);
  for (const source of [m, ...providers]) {
    for (const key of ['endpoint', 'baseUrl', 'headers', 'compat', 'env', 'secret', 'toJSON']) {
      Object.defineProperty(source, key, { get: forbidden, enumerable: true });
    }
  }
  const catalog = describeCatalog(providers);
  assert.deepEqual(Object.keys(catalog), ['piVersion', 'brands', 'providers']);
  for (const p of catalog.providers) {
    assert.deepEqual(Object.keys(p), ['id', 'brandId', 'label', 'authMethods', 'models']);
    assert.deepEqual(Object.keys(p.authMethods[0]), ['id', 'label']);
  }
  assert.doesNotMatch(JSON.stringify(catalog), /secret|endpoint|headers|compat|baseUrl|resolve|login/);
});

test('空模型目录合法；路由固定排序、模型稳定排序且跨路由同名合法', () => {
  assert.ok(describeCatalog(fakes([])).providers.every(p => p.models.length === 0));
  const models = [model('z'), model('A'), model('a')];
  const catalog = describeCatalog(fakes(models).reverse());
  assert.deepEqual(catalog.providers.map(p => p.id), IDS);
  assert.deepEqual(catalog.providers[0].models.map(m => m.id), ['A', 'a', 'z']);
  assert.deepEqual(models.map(m => m.id), ['z', 'A', 'a']);
});

test('快照深冻结、输入不冻结、后续元数据变更不影响快照', () => {
  const m = model();
  const catalog = describeCatalog(fakes([m]));
  isDeepFrozen(catalog);
  assert.throws(() => { catalog.providers[0].models[0].input.push('text'); }, TypeError);
  assert.throws(() => { catalog.brands[0].providerIds[0] = 'other'; }, TypeError);
  m.input.pop();
  m.name = '已改变';
  assert.equal(catalog.providers[0].models[0].label, '测试模型');
  assert.deepEqual(catalog.providers[0].models[0].input, ['text', 'image']);
  assert.ok(!Object.isFrozen(m));
});

test('拒绝缺失、未知、重复或非数组 Provider', () => {
  for (const bad of [null, {}, [], fakes().slice(1), [...fakes(), fakes()[0]],
    [null, ...fakes().slice(1)], new Array(4)]) rejects(bad);
  const unknown = fakes(); unknown[0].id = 'unknown'; rejects(unknown);
  const duplicate = fakes(); duplicate[0] = duplicate[1]; rejects(duplicate);
});

test('拒绝不匹配、缺失或非对象认证能力；不执行回调', () => {
  for (const auth of [null, {}, { apiKey: {} }, { apiKey: { resolve: true } }, { apiKey: true }, { oauth: {} },
    { apiKey: {}, oauth: {} }, { apiKey: [], oauth: undefined }]) {
    const providers = fakes(); providers[0].auth = auth; rejects(providers);
  }
  const providers = fakes(); providers[1].auth = { apiKey: {} }; rejects(providers);
});

test('拒绝异常、异步、稀疏、超限及重复模型目录且隐藏异常详情', () => {
  for (const models of [null, {}, Promise.resolve([]), new Array(1),
    new Array(10001), [model(), model()], [null]]) rejects(fakes(models));
  const throwing = fakes(); throwing[0].getModels = forbidden; rejects(throwing);
  const missing = fakes(); delete missing[0].getModels; rejects(missing);
  const getter = model(); Object.defineProperty(getter, 'name', { get: forbidden });
  rejects(fakes([getter]));
});

const badFields = {
  id: ['', ' ', ' x', 'x\n', 1, null, 'x'.repeat(257), '\ud800'],
  name: ['', false, 'x'.repeat(513), 'x\u001b'],
  api: ['', 'OpenAI', 'https://example.invalid', 'x'.repeat(129), {}],
  contextWindow: [0, -1, 1.2, NaN, Infinity, '128', 1000000001],
  maxTokens: [0, -1, 1.2, NaN, Infinity, '128', 1000000001],
  reasoning: [0, 1, 'false', null],
  input: [[], ['audio'], ['text', 'text'], ['text', 'image', 'text'], 'text', null, new Array(1)],
};
for (const [field, values] of Object.entries(badFields)) {
  test(`严格验证模型字段 ${field} 的类型、缺失和上限`, () => {
    for (const value of values) rejects(fakes([{ ...model(), [field]: value }]));
    const missing = model(); delete missing[field]; rejects(fakes([missing]));
  });
}
