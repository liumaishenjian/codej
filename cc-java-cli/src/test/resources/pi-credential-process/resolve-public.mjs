import {spawnSync} from 'node:child_process';

// import-only exports 不能用 require.resolve；固定 eval 在组件 cwd 锚定公开 ESM 解析。
// 不解析 exports 的私有目标、不猜 dist/OAuth 路径，不安装依赖；子进程无继承环境。
export function resolvePublic(component) {
  const script = `console.log(JSON.stringify([
    import.meta.resolve('@earendil-works/pi-ai')
  ]));`;
  const result = spawnSync(process.execPath, ['--input-type=module', '--eval', script], {
    cwd: component, env: {}, encoding: 'utf8', timeout: 10000, maxBuffer: 8192,
    windowsHide: true,
  });
  if (result.status !== 0 || result.error) throw new Error('PUBLIC_RESOLUTION_FAILED');
  const urls = JSON.parse(result.stdout);
  if (!Array.isArray(urls) || urls.length !== 1 || urls.some(url => !url.startsWith('file:'))) {
    throw new Error('PUBLIC_RESOLUTION_FAILED');
  }
  return urls;
}
