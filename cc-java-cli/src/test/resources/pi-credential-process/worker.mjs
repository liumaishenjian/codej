// 独立测试入口，非生产 worker 的 auth 实现。真实 SDK/RPC/store，只有网络回调为 Fake。
import {component, rpcUrl, storeUrl, protocolUrl, registryUrl} from './bindings.mjs';
import {resolvePublic} from './resolve-public.mjs';
import net from 'node:net';
import http from 'node:http';
import https from 'node:https';
let networkCalls = 0;
const noNetwork = () => { networkCalls++; throw new Error('NETWORK_FORBIDDEN'); };
globalThis.fetch = noNetwork;
net.Socket.prototype.connect = noNetwork;
http.request = https.request = noNetwork;
http.get = https.get = noNetwork;
const {createCredentialTransactions} = await import(rpcUrl);
const {createHostCredentialStore} = await import(storeUrl);
const {JsonLineDecoder, FrameSequence, encodeFrame} = await import(protocolUrl);
const [root] = resolvePublic(component);
const {createModels} = await import(root);
const {createRegisteredProviders} = await import(registryUrl);
let sequence, operationId, next = 0, sentBytes = 0, client, terminal = false, resume;
const key = 'synthetic-process-key';
const rotated = {type: 'oauth', access: 'synthetic-new-access', refresh: 'synthetic-new-refresh',
  expires: 4102444800000, accountId: 'synthetic-account'};
function requireTrue(value) { if (!value) throw new Error('CHECK_FAILED'); }
async function send(type, payload) {
  if (terminal) throw new Error('TERMINAL');
  const bytes = encodeFrame({version: 1, operationId, sequence: next++, type, payload}, 32768);
  try {
    sentBytes += bytes.length;
    if (sentBytes > 131072) throw new Error('LIMIT');
    await new Promise((resolve, reject) => process.stdout.write(bytes, error => error ? reject(error) : resolve()));
  } finally { bytes.fill(0); }
}
async function end(ok, counts) {
  if (terminal) return;
  await send(ok ? 'operation.completed' : 'operation.failed', ok ? counts : {code: 'FIXTURE_FAILED', ...counts});
  terminal = true;
  client?.close();
  process.stdin.pause();
  // 等 write callback 后才退出，Java 仍须观察真实 EOF 和 exit，不以终态代替。
  process.exit(ok ? 0 : 7);
}
async function run({scenario, providerId}) {
  let refreshCalls = 0, prompts = 0, reads = 0, rpcFatal = false;
  try {
    client = createCredentialTransactions({send, onFatal: () => {
      // 生产 RPC 同时 reject 全部 pending；让唯一 run catch 发送 failed，禁止竞争 completed。
      rpcFatal = true;
    }});
    const store = createHostCredentialStore({providerId, transactions: client.transactions});
    const provider = createRegisteredProviders().find(value => value.id === providerId);
    requireTrue(provider !== undefined);
    if (providerId === 'openai-codex') {
      provider.auth.oauth.refresh = async (current, signal) => {
        refreshCalls++;
        requireTrue(signal instanceof AbortSignal && current.access === 'synthetic-old-access');
        if (scenario === 'crash') process.exit(23); // begin ACK 已经到达，事务确实开始。
        if (scenario === 'refresh-failure') throw new Error('SYNTHETIC_FAILURE');
        if (scenario === 'late-delete') {
          const barrier = new Promise(resolve => { resume = resolve; });
          await send('fixture.before_finish', {ready: true});
          await barrier; // Java 完成 delete 后才允许 finish 进入管道。
        }
        return {...rotated};
      };
    }
    const models = createModels({credentials: store, authContext: {
      env: async () => { throw new Error('AMBIENT_ENV_FORBIDDEN'); },
      fileExists: async () => { throw new Error('AMBIENT_FILE_FORBIDDEN'); },
    }});
    models.setProvider(provider);
    if (scenario === 'login' || scenario === 'login-read-after-ack') {
      const result = await models.login(providerId, 'api_key', {
        prompt: async prompt => { requireTrue(prompt.type === 'secret'); prompts++; return key; },
        notify: () => {},
      });
      requireTrue(result.type === 'api_key' && result.key === key && prompts === 1);
      // LOGIN 绑定输入前 generation；正常登录在 ACK 后终结，Java 用新 store 回读。
      // 单独负例验证：同一 LOGIN 操作继续 read 会 CONFLICT，但不能假称材料未发布。
      if (scenario === 'login-read-after-ack') {
        const saved = await store.read(providerId); reads++;
        requireTrue(saved.key === key);
      }
    } else {
      const auth = await models.getAuth(providerId);
      requireTrue(auth?.auth.apiKey === (providerId === 'openai-codex' ? rotated.access : key));
      const saved = await store.read(providerId); reads++;
      requireTrue(providerId === 'openai-codex' ? saved.access === rotated.access && saved.refresh === rotated.refresh
        : saved.type === 'api_key' && saved.key === key);
    }
    requireTrue(networkCalls === 0 && !rpcFatal);
    await end(true, {refreshCalls, prompts, reads, networkCalls, verified: true});
  } catch {
    await end(false, {refreshCalls, prompts, reads, networkCalls, verified: false});
  }
}
const decoder = new JsonLineDecoder({maximumLineBytes: 32768, maximumTotalBytes: 131072,
  onFrame(frame) {
    if (!sequence) { operationId = frame.operationId; sequence = new FrameSequence(operationId); }
    sequence.accept(frame);
    if (frame.type === 'operation.start' && next === 0 && !client) {
      void run(frame.payload).catch(() => process.exit(9));
    } else if (frame.type === 'credential.response' && client) client.accept(frame.payload);
    else if (frame.type === 'fixture.continue' && resume) { const action = resume; resume = undefined; action(); }
    else throw new Error('INVALID_DISPATCH');
  },
});
process.stdin.on('data', bytes => {
  try { decoder.push(bytes); } catch { void end(false, {verified: false, networkCalls}); }
  finally { bytes.fill(0); }
});
process.stdin.on('end', () => { try { decoder.end(); } finally { client?.close(); process.exit(8); } });
