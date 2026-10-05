import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {encodeFrame, JsonLineDecoder, FrameSequence} from '../../../../cc-java-provider-pi/worker-protocol.mjs';

// 独立、无凭证业务样例；先运行 PiWorkerProtocolTest，再显式执行本离线互操作检查。
// Maven 离线 Java 测试本身不依赖 Node 可执行文件。
const expected = {
  version: 1, operationId: 'operation-1', sequence: 0, type: 'model.delta',
  payload: JSON.parse('{"text":"中文😀","__proto__":{"safe":true},"constructor":"data","values":[null,false,-1,1.25,9007199254740991]}'),
};
const base = new URL('../../../target/protocol-interop/', import.meta.url);
const nodeBytes = encodeFrame(expected);
assert.deepEqual(nodeBytes, readFileSync(new URL('node-sample.ndjson', base)));
const guard = new FrameSequence('operation-1');
let count = 0;
const decoder = new JsonLineDecoder({onFrame(frame) {
  guard.accept(frame, {terminal: true});
  assert.deepEqual(frame, expected);
  count++;
}});
const javaBytes = readFileSync(new URL('java-sample.ndjson', base));
for (const byte of javaBytes) decoder.push(Uint8Array.of(byte));
decoder.end();
assert.equal(count, 1);
nodeBytes.fill(0);
javaBytes.fill(0);
console.log('Java/Node independent sample: passed (both directions, byte-split UTF-8)');
