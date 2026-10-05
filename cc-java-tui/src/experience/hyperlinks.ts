import terminalLink from 'terminal-link';

/** 只激活可独立解释的网页地址；拒绝终端控制字符及可执行/本地协议。 */
export function webTarget(value: string): string | undefined {
  if (!/^https?:\/\//i.test(value) || /[\s\x00-\x1f\x7f-\x9f]/u.test(value)) return undefined;
  try {
    const url = new URL(value);
    return url.hostname ? url.href : undefined;
  } catch {return undefined;}
}

/** 排版完成后才生成OSC8；退化文字已经含地址，公共包不得重复追加。 */
export function paintLink(text: string, href?: string): string {
  const target = href && webTarget(href);
  return target ? terminalLink(text, target, {fallback: false}) : text;
}
