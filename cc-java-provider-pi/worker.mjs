#!/usr/bin/env node

// 固定私有入口不接收命令参数或终端交互；不把底层加载/流异常打印为可能含秘密的stack。
const fail = () => process.exit(2);
process.on('uncaughtException', fail);
process.on('unhandledRejection', fail);
process.stdin.on('error', fail);
process.stdout.on('error', fail);
if (process.argv.length !== 2 || process.stdin.isTTY || process.stdout.isTTY) fail();

// 宿主必须在spawn前已清环境（尤其NODE_OPTIONS）；此处是第二层，不能撤销Node预加载。
const allowed = new Set(['PATH', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'TZ', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY']);
for (const name of Object.keys(process.env)) if (!allowed.has(name.toUpperCase())) delete process.env[name];
process.env.DO_NOT_TRACK = '1';
process.env.OTEL_SDK_DISABLED = 'true';

// 版本握手不能只复述编译常量：先核对本组件实际安装的受信依赖清单，且有界读取。
const {open} = await import('node:fs/promises');
const {parseStrictJson} = await import('./worker-protocol.mjs');
const unavailable = () => {throw new Error('COMPONENT_UNAVAILABLE');};
const manifest = await open(new URL('./node_modules/@earendil-works/pi-ai/package.json', import.meta.url), 'r');
try {
  const info = await manifest.stat();
  if (!info.isFile() || info.size < 1 || info.size > 65536) unavailable();
  const bytes = Buffer.alloc(info.size + 1);
  try {
    let used = 0;
    while (used < bytes.length) {
      const {bytesRead} = await manifest.read(bytes, used, bytes.length - used, used);
      if (!bytesRead) break;
      used += bytesRead;
    }
    if (used !== info.size) unavailable();
    const metadata = parseStrictJson(new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes.subarray(0, used)));
    if (metadata.name !== '@earendil-works/pi-ai' || metadata.version !== '0.85.1') unavailable();
  } finally {bytes.fill(0);}
} finally {await manifest.close();}

const {runWorker} = await import('./worker-runtime.mjs');
const result = await runWorker({
  input: process.stdin,
  output: process.stdout,
  // 独立上限；Java操作deadline仍可更早取消并清理进程。
  timeoutMillis: 300000,
  model: async options => {
    const {createModelOperation} = await import('./model-operation.mjs');
    return createModelOperation(options);
  },
  authenticate: async options => {
    const {createAuthOperation} = await import('./auth-operation.mjs');
    return createAuthOperation(options);
  },
  catalog: async ({signal}) => {
    const {describeCatalog} = await import('./provider-registry.mjs');
    signal.throwIfAborted();
    return describeCatalog();
  },
});
process.stdin.destroy();
// runWorker已等待终态write callback或有界flush失败；不让私有helper成为残留常驻进程。
process.exit(result);
