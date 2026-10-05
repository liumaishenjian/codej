// 仅由 Java 以受信任的原生 node 可执行文件启动，stdout 是私有秘密管道。
const privateWrite = process.stdout.write.bind(process.stdout);
process.stdout.write = () => true;
process.stderr.write = () => true;
for (const key of Object.keys(process.env)) {
  if (!['SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'TMPDIR', 'PATH'].includes(key.toUpperCase())) delete process.env[key];
}
process.env.PI_OAUTH_CALLBACK_HOST = '127.0.0.1';
process.env.DO_NOT_TRACK = '1';
process.env.OTEL_SDK_DISABLED = 'true';
let stopped = false;
function finish() {
  if (stopped) return;
  stopped = true;
  process.stdin.pause();
  // AbortSignal 先给回调服务器正常释放机会，再强制退出以关闭残留 socket。
  setTimeout(() => process.exit(0), 100).unref();
}
process.on('uncaughtException', () => { process.exitCode = 1; finish(); });
process.on('unhandledRejection', () => { process.exitCode = 1; finish(); });
try {
  const { openrouterProvider } = await import('@earendil-works/pi-ai/providers/openrouter');
  const { runBridge } = await import('./bridge.mjs');
  const bridge = runBridge({ input: process.stdin, send: privateWrite, provider: openrouterProvider(), finished: finish });
  process.on('SIGTERM', bridge.cancel);
  process.on('SIGINT', bridge.cancel);
} catch {
  privateWrite('{"type":"error","code":"helper_unavailable"}\n');
  finish();
}
