import {appendFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import path from 'node:path';

/**
 * S15 ADR-100 / MODEL-13 L1 保持。受信测试组合根，不替代 Worker、认证或模型协议。
 * 唯一 SDK 模型变更是 getModels 的 baseUrl；所有 SDK 导入之前封闭 global fetch，
 * 同一 guard 注入生产 model operation。无账户文件、无外网、无重试、无伪造私有终态。
 */
export async function runPiAuthFixture({component, home, origin, audit}) {
  const allowed = new Set(['SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'PATH', 'LANG', 'LC_ALL', 'TZ']);
  for (const name of Object.keys(process.env)) if (!allowed.has(name.toUpperCase())) delete process.env[name];
  Object.assign(process.env, {HOME: home, USERPROFILE: home, XDG_CONFIG_HOME: home,
    DO_NOT_TRACK: '1', OTEL_SDK_DISABLED: 'true'});
  appendFileSync(audit, JSON.stringify({kind: 'worker', pid: process.pid, parent: process.ppid}) + '\n');
  const nativeFetch = globalThis.fetch.bind(globalThis);
  const restrictedFetch = (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.origin !== origin || url.username || url.password) {
      appendFileSync(audit, '{"kind":"forbidden"}\n');
      throw Error('NETWORK_FORBIDDEN');
    }
    appendFileSync(audit, '{"kind":"allowed"}\n');
    return nativeFetch(input, {...init, redirect: 'error'});
  };
  globalThis.fetch = restrictedFetch;
  const module = name => import(pathToFileURL(path.join(component, name)).href);
  const {runWorker} = await module('worker-runtime.mjs');
  const {createAuthOperation} = await module('auth-operation.mjs');
  const {createModelOperation} = await module('model-operation.mjs');
  const {createRegisteredProviders, describeCatalog} = await module('provider-registry.mjs');
  const providersFactory = () => createRegisteredProviders().map(provider => ({...provider,
    getModels: () => provider.getModels().map(model => ({...model, baseUrl: origin + '/v1'})),
  }));
  const code = await runWorker({input: process.stdin, output: process.stdout, timeoutMillis: 30000,
    authenticate: options => createAuthOperation({...options, providersFactory}),
    model: options => createModelOperation({...options, providersFactory, fetchImpl: restrictedFetch}),
    catalog: () => describeCatalog(),
  });
  process.stdin.destroy();
  process.exit(code);
}
