import test from 'node:test';
import assert from 'node:assert/strict';
import {JsonLineDecoder, ProtocolError, FrameSequence, encodeFrame, parseStrictJson} from './worker-protocol.mjs';

function decode(parts, limits = {}) {
  const frames = [];
  const reader = new JsonLineDecoder({...limits, onFrame: frame => frames.push(frame)});
  for (const part of parts) reader.push(Buffer.from(part));
  reader.end();
  return frames;
}
const invalid = action => assert.throws(action, error => error instanceof ProtocolError && !error.cause && /^PROTOCOL_/.test(error.message));

test('decoder preserves UTF8 across physical byte chunks and accepts CRLF', () => {
  const bytes = Buffer.from('{"value":"中文😀"}\r\n{"ok":true}\n');
  assert.deepEqual(decode([...bytes].map(value => Buffer.from([value]))), [{value: '中文😀'}, {ok: true}]);
});

test('strict JSON rejects duplicate keys including unicode-escaped equivalents', () => {
  for (const value of ['{"x":1,"x":2}', '{"x":1,"\\u0078":2}', '{"nested":{"x":0,"x":1}}']) invalid(() => parseStrictJson(value));
});

test('strict JSON rejects trailing input, malformed numbers and non-JSON syntax', () => {
  for (const value of ['{}{}', '{"x":01}', '[1,]', '{"x":NaN}', '{"x":1e999}', '{"x":9007199254740992}', '{"x":undefined}', '/*comment*/{}']) invalid(() => parseStrictJson(value));
});

test('prototype-shaped keys remain own inert data', () => {
  const parsed = parseStrictJson('{"__proto__":{"polluted":true},"constructor":"data"}');
  assert.equal(Object.getPrototypeOf(parsed), Object.prototype);
  assert.equal(Object.hasOwn(parsed, '__proto__'), true);
  assert.equal(parsed.__proto__.polluted, true);
  assert.equal({}.polluted, undefined);
});

test('lone surrogates and invalid UTF8 fail instead of silent replacement', () => {
  invalid(() => parseStrictJson('"\\ud800"'));
  invalid(() => parseStrictJson('"\\udc00"'));
  invalid(() => decode([Buffer.from([0x22, 0xc0, 0xaf, 0x22, 0x0a])]));
});

test('line limit includes LF and rejects larger frames', () => {
  assert.deepEqual(decode(['{}\n'], {maximumLineBytes: 3}), [{}]);
  invalid(() => decode(['{} \n'], {maximumLineBytes: 3}));
});

test('total byte and frame limits apply across pushes', () => {
  invalid(() => decode(['{}\n', '{}\n'], {maximumTotalBytes: 5}));
  invalid(() => decode(['{}\n{}\n'], {maximumFrames: 1}));
});

test('EOF never treats an incomplete last line as a successful frame', () => {
  invalid(() => decode(['{"ok":true}']));
  invalid(() => decode(['\n']));
  assert.deepEqual(decode([]), []);
});

test('closed or poisoned readers cannot be reused', () => {
  const reader = new JsonLineDecoder({onFrame() {}});
  invalid(() => reader.push(Buffer.from('{bad}\n')));
  invalid(() => reader.push(Buffer.from('{}\n')));
  reader.close();
  const closed = new JsonLineDecoder({onFrame() {}});
  closed.push(Buffer.from('{"unfinished":'));
  closed.close();
  invalid(() => closed.push(Buffer.from('0}\n')));
});

test('callback failure is sanitized without continuing to later frames', () => {
  let calls = 0;
  const reader = new JsonLineDecoder({onFrame() {calls++; throw new Error('SYNTHETIC_PRIVATE_DATA');}});
  assert.throws(() => reader.push(Buffer.from('{}\n{}\n')), error => error.message === 'PROTOCOL_INVALID' && !error.stack.includes('SYNTHETIC_PRIVATE_DATA'));
  assert.equal(calls, 1);
});

test('nesting is bounded while ordinary argument values round trip', () => {
  invalid(() => parseStrictJson('['.repeat(65) + '0' + ']'.repeat(65)));
  const value = {x: [null, true, false, -1, 1.25, 'a\nb'], deep: {escaped: '"\\'}};
  assert.deepEqual(decode([encodeFrame(value)]), [value]);
});

test('encoder fails closed on non-JSON values, invalid strings and oversized data', () => {
  for (const value of [{x: undefined}, {x: Infinity}, {x: 1n}, {x() {}}, {x: '\ud800'}]) invalid(() => encodeFrame(value));
  const cyclic = {}; cyclic.self = cyclic;
  invalid(() => encodeFrame(cyclic));
  invalid(() => encodeFrame({x: '12345678'}, 8));
});

function frame(sequence, type = 'model.delta', payload = {}) {
  return {version: 1, operationId: 'operation-1', sequence, type, payload};
}
test('sequence binds version and operation and seals exactly one terminal', () => {
  const guard = new FrameSequence('operation-1');
  guard.accept(frame(0, 'model.start'));
  guard.accept(frame(1));
  guard.accept(frame(2, 'model.done'), {terminal: true});
  invalid(() => guard.accept(frame(3)));
});

test('sequence rejects duplicates, gaps, wrong operation, unknown fields and bad headers', () => {
  const values = [frame(1), {...frame(0), version: 2}, {...frame(0), operationId: 'other'}, {...frame(0), extra: true}, {...frame(0), sequence: -1}, {...frame(0), type: 'unsafe\nvalue'}, {...frame(0), payload: []}];
  for (const value of values) invalid(() => new FrameSequence('operation-1').accept(value));
  const guard = new FrameSequence('operation-1'); guard.accept(frame(0));
  invalid(() => guard.accept(frame(0)));
});
