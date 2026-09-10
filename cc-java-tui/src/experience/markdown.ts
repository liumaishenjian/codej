import {marked, type Token, type Tokens} from 'marked';
import {lines, palette, span, type Row, type Span} from './screen.js';

/** 使用现有Marked解析，不用正则重造Markdown语法；HTML仅作惰性文本显示。 */
const cache = new Map<string, Row[]>();
function inline(tokens: readonly Token[], depth = 0): Span[] {
  if (depth > 16) return [];
  return tokens.flatMap(token => {
    if (token.type === 'strong') return inline((token as Tokens.Strong).tokens, depth + 1).map(part => ({...part, bold: true}));
    if (token.type === 'em' || token.type === 'del') return inline((token as Tokens.Em).tokens, depth + 1);
    if (token.type === 'codespan') return [span((token as Tokens.Codespan).text, palette.accent)];
    if (token.type === 'link') return inline((token as Tokens.Link).tokens, depth + 1);
    if (token.type === 'br') return [span('\n')];
    if (token.type === 'image') return [span('[图片：' + (token as Tokens.Image).text + ']')];
    return [span('text' in token ? String(token.text) : token.raw)];
  });
}
export function markdownRows(text: string, width: number): Row[] {
  const key = width + '\0' + text;
  const hit = cache.get(key);
  if (hit) return hit;
  const rows: Row[] = [];
  function block(tokens: readonly Token[], indent = '', depth = 0): void {
    if (depth > 16) return;
    for (const token of tokens) {
      if (token.type === 'space' || token.type === 'def') {if (rows.length) rows.push({spans: []}); continue;}
      if (token.type === 'heading') {
        const heading = token as Tokens.Heading;
        rows.push(...lines([span(indent), ...inline(heading.tokens).map(part => ({...part, bold: true}))], width));
      } else if (token.type === 'paragraph' || token.type === 'text') {
        const paragraph = token as Tokens.Paragraph;
        rows.push(...lines([span(indent), ...(paragraph.tokens ? inline(paragraph.tokens) : [span(paragraph.text)])], width));
      } else if (token.type === 'code') {
        const code = token as Tokens.Code;
        if (code.lang) rows.push(...lines([span(indent + code.lang, palette.muted)], width));
        for (const line of code.text.split('\n')) rows.push(...lines([span(indent + '  ' + line)], width));
      } else if (token.type === 'list') {
        const list = token as Tokens.List;
        list.items.forEach((item, index) => {
          const marker = list.ordered ? String((typeof list.start === 'number' ? list.start : 1) + index) + '. ' : item.task ? item.checked ? '[✓] ' : '[ ] ' : '• ';
          const begin = rows.length; block(item.tokens, indent + marker, depth + 1);
          if (rows.length === begin) rows.push(...lines([span(indent + marker + item.text)], width));
        });
      } else if (token.type === 'blockquote') {
        block((token as Tokens.Blockquote).tokens, indent + '│ ', depth + 1);
      } else if (token.type === 'hr') rows.push(...lines([span('─'.repeat(Math.min(width, 30)), palette.muted)], width));
      else rows.push(...lines([span(indent + token.raw)], width));
    }
  }
  try {block(marked.lexer(text, {gfm: true}));}
  catch {rows.push(...lines([span(text)], width));}
  if (cache.size >= 8) cache.delete(cache.keys().next().value!);
  cache.set(key, rows);
  return rows;
}
