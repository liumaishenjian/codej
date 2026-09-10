/** 以字素而非 UTF-16 下标编辑，避免删除半个中文扩展字符或 emoji。 */
export interface Draft {text: string; cursor: number}
export const emptyDraft = (): Draft => ({text: '', cursor: 0});
export const glyphs = (text: string): string[] => Array.from(new Intl.Segmenter('zh', {granularity: 'grapheme'}).segment(text), part => part.segment);
export function edit(draft: Draft, operation: 'insert' | 'left' | 'right' | 'home' | 'end' | 'backspace' | 'delete' | 'clear', input = ''): Draft {
  const chars = glyphs(draft.text);
  let cursor = Math.min(draft.cursor, chars.length);
  if (operation === 'left') cursor = Math.max(0, cursor - 1);
  if (operation === 'right') cursor = Math.min(chars.length, cursor + 1);
  if (operation === 'home') {while (cursor > 0 && chars[cursor - 1] !== '\n') cursor--;}
  if (operation === 'end') {while (cursor < chars.length && chars[cursor] !== '\n') cursor++;}
  if (operation === 'clear') return emptyDraft();
  if (operation === 'backspace' && cursor > 0) {chars.splice(--cursor, 1);}
  if (operation === 'delete') chars.splice(cursor, 1);
  if (operation === 'insert') {
    // 终端控制字节不能作为显示指令；CRLF 保持为一个换行。
    const clean = input.replace(/\r\n?/g, '\n').replace(/\t/g, '  ').replace(/[\x00-\x08\x0b-\x1f\x7f-\x9f]/g, '');
    const incoming = glyphs(clean);
    if (chars.length + incoming.length > 8000) return draft;
    chars.splice(cursor, 0, ...incoming); cursor += incoming.length;
  }
  return {text: chars.join(''), cursor};
}
