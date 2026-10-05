import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {createModelOperation, ModelOperationError} from './model-operation.mjs';
import {createRegisteredProviders} from './provider-registry.mjs';

const request = () => ({systemPrompt: 'Independent fixture', messages: [{role: 'user', text: 'hello'}], tools: [], options: {}});
test('ordinary JSON prototype-named schema properties remain data', async () => {
  const input = request();
  input.tools = [{name: 'inspect', description: 'Independent schema', parameters: JSON.parse(
    '{"type":"object","properties":{"constructor":{"type":"string"},"__proto__":{"type":"string"},"prototype":{"type":"string"}}}') }];
  const argumentsValue = JSON.parse('{"__proto__":{"polluted":true},"constructor":"data","prototype":"ordinary"}');
  const h = harness({request: input, modelsFactory: fakeFactory(
    [{type: 'toolCall', id: 'ordinary-keys', name: 'inspect', arguments: argumentsValue}], ctx => {
      const properties = ctx.tools[0].parameters.properties;
      assert.deepEqual(properties, input.tools[0].parameters.properties);
      assert.equal(Object.getPrototypeOf(properties), Object.prototype);
      assert.equal(Object.hasOwn(properties, '__proto__'), true);
    }, 'toolUse')});
  const result = await h.operation.run();
  assert.deepEqual(result.toolCalls[0].arguments, argumentsValue);
  assert.equal(Object.getPrototypeOf(result.toolCalls[0].arguments), Object.prototype);
  assert.equal(Object.prototype.polluted, undefined);
});

const providers = createRegisteredProviders();
const selected = id => providers.find(p => p.id === id).getModels().find(m => m.api === 'openai-completions')
  ?? providers.find(p => p.id === id).getModels()[0];
function harness(extra = {}) {
  let operation;
  const frames = []; let credential = extra.credential === undefined ? {type: 'api_key', key: 'fixture-only'} : extra.credential;
  const {rejectStore, ...options} = extra;
  operation = createModelOperation({providerId: 'deepseek', modelId: selected('deepseek').id, request: request(),
    send(type, payload) {
      if (type !== 'credential.request') { frames.push({type, payload}); return; }
      queueMicrotask(() => {
        if (rejectStore) { operation.accept({type: 'credential.response', payload: {requestId: payload.requestId, ok: false, code: 'DENIED'}}); return; }
        let result;
        if (payload.action === 'read') result = {credential};
        else if (payload.action === 'begin') result = {transactionId: 'tx', credential};
        else if (payload.action === 'finish') {
          if (payload.arguments.change.kind === 'put') credential = payload.arguments.change.credential;
          result = {credential};
        } else result = {};
        operation.accept({type: 'credential.response', payload: {requestId: payload.requestId, ok: true, result}});
      });
    }, ...options});
  return {operation, frames, getCredential: () => credential};
}
async function server(t, handler) {
  let attempts = 0;
  const s = createServer((req, res) => { attempts++; req.resume(); handler(req, res); });
  s.listen(0, '127.0.0.1'); await once(s, 'listening');
  t.after(() => { s.closeAllConnections(); s.close(); });
  const url = `http://127.0.0.1:${s.address().port}`;
  return {url, attempts: () => attempts, fetch: (input, init) => {
    assert.equal(new URL(typeof input === 'string' ? input : input.url).origin, url);
    return fetch(input, init);
  }, factory: () => providers.map(p => ({...p, getModels: () => p.getModels().map(m => ({...m, baseUrl: `${url}/v1`}))}))};
}
const sse = (res, data) => res.write(`data: ${JSON.stringify(data)}\n\n`);
function completion(res, tools = false, finish = 'stop') {
  res.writeHead(200, {'content-type': 'text/event-stream'});
  const chunk = (delta, reason = null) => sse(res, {id: 'fixture', choices: [{index: 0, delta, finish_reason: reason}]});
  chunk({role: 'assistant'});
  chunk({content: 'hel'}); chunk({content: 'lo'});
  if (tools) {
    chunk({tool_calls: [{index: 0, id: 'a', type: 'function', function: {name: 'first', arguments: '{"n":'}},
      {index: 1, id: 'b', type: 'function', function: {name: 'second', arguments: '{"s":'}}]});
    chunk({tool_calls: [{index: 1, function: {arguments: '"x"}'}}, {index: 0, function: {arguments: '1}'}}]});
  }
  if (finish) chunk({}, tools ? 'tool_calls' : finish);
  res.end('data: [DONE]\n\n');
}
for (const id of ['openai', 'deepseek', 'qwen-token-plan-cn']) {
  test(`real Pi SDK loopback ${id} text and fragmented multi tools, one attempt`, async t => {
    const s = await server(t, (_req, res) => {
      if (selected(id).api !== 'openai-responses') return completion(res, true);
      res.writeHead(200, {'content-type': 'text/event-stream'});
      const event = (type, data) => {res.write(`event: ${type}\n`); sse(res, {type, ...data});};
      event('response.created', {response: {id: 'r', status: 'in_progress'}});
      event('response.output_item.added', {output_index: 0, item: {type: 'message', id: 'msg', role: 'assistant', content: []}});
      event('response.content_part.added', {output_index: 0, content_index: 0, item_id: 'msg', part: {type: 'output_text', text: '', annotations: []}});
      event('response.output_text.delta', {output_index: 0, content_index: 0, item_id: 'msg', delta: 'hello'});
      event('response.output_item.done', {output_index: 0, item: {type: 'message', id: 'msg', role: 'assistant', content: [{type: 'output_text', text: 'hello', annotations: []}]}});
      for (const [i, call] of [{id: 'a', name: 'first', arguments: '{"n":1}'}, {id: 'b', name: 'second', arguments: '{"s":"x"}'}].entries()) {
        const item = {type: 'function_call', id: `fc${i}`, call_id: call.id, name: call.name, arguments: ''};
        event('response.output_item.added', {output_index: i + 1, item});
        event('response.function_call_arguments.delta', {output_index: i + 1, item_id: item.id, delta: call.arguments});
        event('response.output_item.done', {output_index: i + 1, item: {...item, arguments: call.arguments}});
      }
      event('response.completed', {response: {id: 'r', status: 'completed', usage: {input_tokens: 2, output_tokens: 3, total_tokens: 5}}});
      res.end();
    });
    const h = harness({providerId: id, modelId: selected(id).id, providersFactory: s.factory, fetchImpl: s.fetch});
    const result = await h.operation.run();
    assert.equal(result.text, 'hello');
    assert.deepEqual(result.toolCalls.map(c => ({...c, id: c.id.split('|')[0]})), [{id: 'a', name: 'first', arguments: {n: 1}}, {id: 'b', name: 'second', arguments: {s: 'x'}}]);
    assert.deepEqual(Object.keys(result.usage), ['input', 'output', 'cacheRead', 'cacheWrite']);
    assert.equal(h.frames.filter(f => f.type === 'model.frame').length, 1);
    assert.equal(h.frames.filter(f => f.type === 'model.delta').map(f => f.payload.text).join(''), result.text);
    assert.equal(s.attempts(), 1);
  });
}
for (const [status, code, retryable] of [[401, 'AUTH', false], [429, 'RATE_LIMIT', true], [500, 'TRANSIENT', true],
  [408, 'TIMEOUT', true], [409, 'TRANSIENT', true], [400, 'PERMANENT', false]]) {
  test(`real SDK HTTP ${status} has no synthesized start fence and no retry`, async t => {
    const s = await server(t, (_req, res) => {
      res.writeHead(status, {'content-type': 'application/json', 'retry-after': '1.25'});
      res.end(JSON.stringify({error: {message: 'private context_length_exceeded secret', code: 'unrelated'}}));
    });
    const h = harness({providersFactory: s.factory, fetchImpl: s.fetch});
    await assert.rejects(h.operation.run(), e => {
      assert.ok(e instanceof ModelOperationError); assert.equal(e.code, code);
      assert.equal(e.retryable, retryable); assert.equal(e.providerFrame, false); assert.equal(e.retryAfterMs, 1250);
      assert.ok(!JSON.stringify(e).includes('private')); return true;
    });
    assert.equal(s.attempts(), 1); assert.deepEqual(h.frames, []);
  });
}
test('real SDK bounded typed overflow only', async t => {
  const s = await server(t, (_req, res) => {res.writeHead(400); res.end('{"error":{"code":"context_length_exceeded"}}');});
  const h = harness({providersFactory: s.factory, fetchImpl: s.fetch});
  await assert.rejects(h.operation.run(), {code: 'CONTEXT_OVERFLOW', retryable: false});
});
for (const finish of [null, 'length']) test(`real SDK incomplete/length ${finish}`, async t => {
  const s = await server(t, (_req, res) => completion(res, false, finish));
  const h = harness({providersFactory: s.factory, fetchImpl: s.fetch});
  await assert.rejects(h.operation.run(), {code: 'INCOMPLETE', retryable: false, providerFrame: true});
});
test('no credential never consults real process env or network', async () => {
  const previous = process.env.DEEPSEEK_API_KEY; process.env.DEEPSEEK_API_KEY = 'must-not-use';
  try {
    const h = harness({credential: null, fetchImpl: () => {assert.fail('network forbidden');}});
    await assert.rejects(h.operation.run(), {code: 'AUTH', retryable: false});
  } finally { if (previous === undefined) delete process.env.DEEPSEEK_API_KEY; else process.env.DEEPSEEK_API_KEY = previous; }
});
test('storage denial never reaches network', async () => {
  const h = harness({rejectStore: true, fetchImpl: () => {assert.fail('network forbidden');}});
  await assert.rejects(h.operation.run(), {code: 'AUTH'});
});
function fakeFactory(content, inspect = () => {}, reason = 'stop', mutate = true) {
  return options => ({setProvider() {}, streamSimple(model, ctx, opts) {
    inspect(ctx, opts, options);
    return (async function* () {
      const partial = {role: 'assistant', api: model.api, provider: model.provider, model: model.id,
        timestamp: 1, stopReason: 'pending', content: [], usage: {input: 2, output: 3, cacheRead: 0, cacheWrite: 0}};
      yield {type: 'start', partial};
      partial.content = structuredClone(content);
      for (const [contentIndex, c] of content.entries()) {
        yield {type: `${c.type === 'toolCall' ? 'toolcall' : c.type}_start`, contentIndex, partial};
        if (c.type === 'text') yield {type: 'text_delta', contentIndex, delta: c.text, partial};
      }
      partial.stopReason = reason;
      yield {type: 'done', reason, message: partial};
      if (mutate) partial.content[0] = {type: 'text', text: 'mutated after done'};
    })();
  }});
}
test('Fake mutable done snapshot, hidden signatures/order, same route and changed visible/cross route', async () => {
  const content = [{type: 'thinking', thinking: '', thinkingSignature: 'opaque', redacted: true},
    {type: 'text', text: 'answer', textSignature: 'signed'},
    {type: 'toolCall', id: 't', name: 'f', arguments: {x: 1, y: 2}, thoughtSignature: 'tool-signed'}];
  const h = harness({modelsFactory: fakeFactory(content, (_ctx, opts) => {
    assert.equal(opts.maxRetries, 0); assert.equal(opts.transport, 'sse'); assert.ok(opts.signal instanceof AbortSignal);
  }, 'toolUse')});
  const result = await h.operation.run();
  assert.deepEqual(JSON.parse(result.continuation.payload).content, content);
  for (const change of ['same', 'key-order', 'visible', 'route']) {
    const r = request();
    r.messages.push({role: 'assistant', text: change === 'visible' ? 'edited' : result.text,
      toolCalls: change === 'key-order' ? result.toolCalls.map(c => ({...c, arguments: {y: 2, x: 1}})) : result.toolCalls,
      continuation: {...result.continuation,
        ...(change === 'route' ? {providerId: 'openai'} : {})}});
    r.messages.push({role: 'toolResult', toolCallId: 't', toolName: 'f', text: 'fixture result', isError: false});
    const next = harness({request: r, modelsFactory: fakeFactory([{type: 'text', text: 'next'}], ctx => {
      const restored = ctx.messages[1].content;
      if (change === 'same' || change === 'key-order') assert.deepEqual(restored, content);
      else assert.ok(!JSON.stringify(restored).includes('signed') && !restored.some(c => c.type === 'thinking'));
    })});
    await next.operation.run();
  }
});
test('Fake cancellation settles hung stream, timeout classified without retry', async () => {
  for (const timeout of [false, true]) {
    const controller = new AbortController();
    const h = harness({signal: controller.signal, modelsFactory: () => ({setProvider() {}, streamSimple() {
      return {[Symbol.asyncIterator]: () => ({next: () => new Promise(() => {})})};
    }})});
    const run = h.operation.run(); controller.abort(timeout ? new DOMException('hidden', 'TimeoutError') : undefined);
    await assert.rejects(run, {code: timeout ? 'TIMEOUT' : 'CANCELLED', retryable: false});
  }
});
test('strict request rejects SDK injection/getters/prototypes and wrong model without network', async () => {
  for (const field of ['baseUrl', 'headers', 'apiKey', 'fetch', 'env', 'maxRetries', 'timeoutMs']) {
    const r = request(); r.options[field] = 'forbidden';
    assert.throws(() => harness({request: r}), {code: 'PROTOCOL'});
  }
  const r = request(); Object.defineProperty(r, 'systemPrompt', {enumerable: true, get() {assert.fail('getter invoked');}});
  assert.throws(() => harness({request: r}), {code: 'PROTOCOL'});
  assert.throws(() => harness({request: Object.assign(Object.create({}), request())}), {code: 'PROTOCOL'});
  const h = harness({modelId: 'not-a-model', fetchImpl: () => assert.fail('network')});
  await assert.rejects(h.operation.run(), {code: 'UNSUPPORTED'});
});
test('Fake Codex OAuth refresh is persisted ACK before real Models provider dispatch', async () => {
  const model = selected('openai-codex'); let refreshed = 0, dispatched = 0;
  let h;
  const fakeProvider = {...providers.find(p => p.id === 'openai-codex'), auth: {oauth: {
    async refresh(c, signal) { assert.ok(signal instanceof AbortSignal); refreshed++; return {...c, access: 'rotated', expires: Date.now() + 3600000}; },
    toAuth(c) { return {apiKey: c.access}; },
  }}, streamSimple(m, ctx, opts) {
    dispatched++; assert.equal(h.getCredential().access, 'rotated'); assert.equal(opts.apiKey, 'rotated');
    return fakeFactory([{type: 'text', text: 'ok'}], () => {}, 'stop', false)({}).streamSimple(m, ctx, opts);
  }};
  h = harness({providerId: 'openai-codex', modelId: model.id,
    credential: {type: 'oauth', access: 'old', refresh: 'refresh', accountId: 'account', expires: 1},
    providersFactory: () => [fakeProvider], fetchImpl: () => assert.fail('network')});
  assert.equal((await h.operation.run()).text, 'ok'); assert.equal(refreshed, 1); assert.equal(dispatched, 1);
});
test('wrong credential response ID seals operation without network', async () => {
  let operation;
  operation = createModelOperation({providerId: 'deepseek', modelId: selected('deepseek').id,
    request: request(), fetchImpl: () => assert.fail('network'), send() {
      assert.throws(() => operation.accept({type: 'credential.response', payload:
        {requestId: 99, ok: true, result: {credential: null}}}), {code: 'PROTOCOL'});
    }});
  await assert.rejects(operation.run(), e => ['AUTH', 'PROTOCOL'].includes(e.code));
});
test('wrong tool IDs/name, unpaired tools and deep payload rejected', () => {
  for (const toolName of ['wrong', 'f']) {
    const r = request(); r.messages.push({role: 'toolResult', toolCallId: 'unknown', toolName, text: '', isError: false});
    assert.throws(() => harness({request: r}), {code: 'PROTOCOL'});
  }
  const r = request(); let deep = {}; r.tools = [{name: 'f', description: '', parameters: deep}];
  for (let i = 0; i < 65; i++) {deep.next = {}; deep = deep.next;}
  assert.throws(() => harness({request: r}), {code: 'LIMIT'});
});
test('real SDK 400 oversized or message-only overflow never typed', async t => {
  for (const body of [JSON.stringify({error: {code: 'context_length_exceeded', extra: 'x'.repeat(17000)}}),
    '{"error":{"message":"context_length_exceeded"}}']) {
    const s = await server(t, (_req, res) => { res.writeHead(400); res.end(body); });
    const h = harness({providersFactory: s.factory, fetchImpl: s.fetch});
    await assert.rejects(h.operation.run(), {code: 'PERMANENT', retryable: false});
    assert.equal(s.attempts(), 1);
  }
});
test('real SDK loopback socket transport failure allows host retry, one attempt', async t => {
  const s = await server(t, (req) => req.socket.destroy());
  const h = harness({providersFactory: s.factory, fetchImpl: s.fetch});
  await assert.rejects(h.operation.run(), {code: 'TRANSIENT', retryable: true, providerFrame: false});
  assert.equal(s.attempts(), 1);
});
test('real SDK loopback abort after actual content never retries', async t => {
  const controller = new AbortController();
  const s = await server(t, (_req, res) => {
    res.writeHead(200, {'content-type': 'text/event-stream'});
    sse(res, {id: 'a', choices: [{index: 0, delta: {content: 'partial'}, finish_reason: null}]});
  });
  let operation;
  operation = createModelOperation({providerId: 'deepseek', modelId: selected('deepseek').id,
    request: request(), signal: controller.signal, providersFactory: s.factory, fetchImpl: s.fetch,
    send(type, payload) {
      if (type === 'credential.request') queueMicrotask(() => operation.accept({type: 'credential.response',
        payload: {requestId: payload.requestId, ok: true, result: {credential: {type: 'api_key', key: 'fixture'}}}}));
      if (type === 'model.delta') controller.abort();
    }});
  await assert.rejects(operation.run(), {code: 'CANCELLED', providerFrame: true, retryable: false});
  assert.equal(s.attempts(), 1);
});
test('Fake empty done and inconsistent final text fail closed', async () => {
  const empty = harness({modelsFactory: fakeFactory([])});
  await assert.rejects(empty.operation.run(), {code: 'INCOMPLETE'});
  const factory = fakeFactory([{type: 'text', text: 'answer'}]);
  const h = harness({modelsFactory: options => {
    const models = factory(options);
    return {...models, streamSimple(...args) {
      const stream = models.streamSimple(...args);
      return (async function* () {
        for await (const e of stream) {
          if (e.type === 'text_delta') yield {...e, delta: 'different'}; else yield e;
        }
      })();
    }};
  }});
  await assert.rejects(h.operation.run(), {code: 'INCOMPLETE', providerFrame: true});
});
for (const reason of ['length', 'error', 'aborted', 'deferred']) test(`Fake non-success terminal ${reason} rejected`, async () => {
  const h = harness({modelsFactory: fakeFactory([{type: 'text', text: 'partial'}], () => {}, reason)});
  await assert.rejects(h.operation.run(), {code: 'INCOMPLETE', retryable: false, providerFrame: true});
});
