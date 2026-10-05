/** 认证页只占一行；完整目标留在受限终端链接中，不作为对话或通用状态正文。 */
export const PI_AUTHORIZATION_LABEL = 'OpenAI 授权页 · Ctrl/⌘+单击';

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

/** 仅渲染层编码OSC8；非法目标退回固定标签，不向终端传递外部控制字节。 */
export function piAuthorizationText(value: string): string {
  if (!validPiAuthorizationUrl(value)) return PI_AUTHORIZATION_LABEL;
  const escape = '\u001b';
  const terminate = escape + '\\';
  return escape + ']8;;' + value + terminate + PI_AUTHORIZATION_LABEL + escape + ']8;;' + terminate;
}
