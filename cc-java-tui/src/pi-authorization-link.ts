import {spawn} from 'node:child_process';

/** 认证页只占一行；完整目标留在受限终端链接中，不作为对话或通用状态正文。 */
export const PI_AUTHORIZATION_LABEL = 'OpenAI 授权页';

/** 固定授权origin/path，禁止终端控制、userinfo、fragment与非默认authority。 */
export function validPiAuthorizationUrl(value: string): boolean {
  if (value.length > 16_384 || !value.startsWith('https://auth.openai.com/oauth/authorize?')
    || /[^\x21-\x7e]|\\/u.test(value)) return false;
  try {
    const url = new URL(value);
    return url.origin === 'https://auth.openai.com' && url.pathname === '/oauth/authorize'
      && !url.username && !url.password && !url.port && !url.hash;
  } catch {return false;}
}

/**
 * 让宿主系统使用默认浏览器打开 Pi 发来的授权页。
 *
 * Pi 的 Codex OAuth 会同时启动本地回调监听和 manual_code 回退提示；
 * 浏览器打开属于 TUI 宿主职责，因此不能依赖 PowerShell 是否支持 OSC 8。
 * URL 已先限制到官方授权 origin，进程参数也通过 argv 传递，不经过 shell。
 */
export function openPiAuthorizationUrl(value: string): boolean {
  if (!validPiAuthorizationUrl(value)) return false;
  // Pi 在交互登录面板收到 URL 后直接交给宿主打开；这里不经过 shell，避免
  // Windows OAuth 查询串里的 &、|、^ 被再次解析。离线/管道入口不会调用本函数。
  const command = process.platform === 'win32' ? 'rundll32.exe' : process.platform === 'darwin' ? 'open' : 'xdg-open';
  const args = process.platform === 'win32' ? ['url.dll,FileProtocolHandler', value] : [value];
  try {
    const child = spawn(command, args, {detached: true, stdio: 'ignore', windowsHide: true});
    child.once('error', () => { /* 浏览器不可用时由面板保留手工回退。 */ });
    child.unref();
    return true;
  } catch {
    return false;
  }
}

/** 仅渲染层编码OSC8；非法目标退回固定标签，不向终端传递外部控制字节。 */
export function piAuthorizationText(value: string): string {
  if (!validPiAuthorizationUrl(value)) return PI_AUTHORIZATION_LABEL;
  const escape = '\u001b';
  const terminate = escape + '\\';
  return escape + ']8;;' + value + terminate + PI_AUTHORIZATION_LABEL + escape + ']8;;' + terminate;
}
