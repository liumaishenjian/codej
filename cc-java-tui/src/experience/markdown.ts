import {marked, type Token, type Tokens} from 'marked';
import stringWidth from 'string-width';
import {webTarget} from './hyperlinks.js';
import {lines, palette, span, rowText, type Row, type Span} from './screen.js';

/** 使用现有Marked解析，不用正则重造Markdown语法；HTML仅作惰性文本显示。 */
const cache = new Map<string, Row[]>();
const parsed = new Map<string, TokensList>();
type TokensList = ReturnType<typeof marked.lexer>;
let parsedSize = 0;
/** 语法与终端宽度无关；按原文保留有界LRU，后置引用定义仍由Marked完整解释。 */
function tokensFor(text: string): TokensList {
  const hit = parsed.get(text);
  if (hit) {parsed.delete(text); parsed.set(text, hit); return hit;}
  const tokens = marked.lexer(text, {gfm: true});
  if (text.length > 1048576) return tokens;
  while (parsed.size && (parsed.size >= 32 || parsedSize + text.length > 1048576)) {
    const oldest = parsed.keys().next().value!;
    parsed.delete(oldest); parsedSize -= oldest.length;
  }
  parsed.set(text, tokens); parsedSize += text.length;
  return tokens;
}
function inline(tokens: readonly Token[], depth = 0): Span[] {
  if (depth > 16) return tokens.map(token => span(token.raw));
  return tokens.flatMap(token => {
    if (token.type === 'checkbox') return [];
    if (token.type === 'strong') return inline((token as Tokens.Strong).tokens, depth + 1).map(part => ({...part, bold: true}));
    if (token.type === 'em') return inline((token as Tokens.Em).tokens, depth + 1).map(part => ({...part, italic: true}));
    if (token.type === 'del') return inline((token as Tokens.Del).tokens, depth + 1).map(part => ({...part, strikethrough: true}));
    if (token.type === 'codespan') return [span((token as Tokens.Codespan).text, palette.accent)];
    if (token.type === 'link') {
      const link = token as Tokens.Link;
      const label = inline(link.tokens, depth + 1);
      // 可见地址不随终端能力改变；结构化目标直到绘制边界才序列化。
      const text = label.map(part => part.text).join('');
      const href = webTarget(link.href);
      const parts = !text || text === link.href ? [span(link.href, palette.blue)]
        : [...label, span(' (' + link.href + ')', palette.blue)];
      return href ? parts.map(part => ({...part, href})) : parts;
    }
    if (token.type === 'br') return [span('\n')];
    if (token.type === 'image') {
      const image = token as Tokens.Image;
      const href = webTarget(image.href);
      return [span('[图片：' + image.text + '] '), {...span(image.href, palette.blue), ...(href ? {href} : {})}];
    }
    return [span('text' in token ? String(token.text) : token.raw)];
  });
}
export function markdownRows(text: string, width: number): Row[] {
  width = Math.max(2, Math.floor(width));
  const key = width + '\0' + text;
  const hit = cache.get(key);
  if (hit) return hit;
  const rows: Row[] = [];
  // 首行标记与续行缩进分开，折行不能丢失列表层级或代码侧边线。
  function append(parts: Span[], prefix: string, continuation = prefix): void {
    const available = Math.max(2, width - stringWidth(prefix));
    lines(parts, available).forEach((row, index) => rows.push(...lines([span(index ? continuation : prefix, palette.muted), ...row.spans], width)));
  }
  /** 表格布局只消费Marked单元格；统计宽度与实际输出共享终端行规则。 */
  function table(token: Tokens.Table, indent: string): void {
    const available = Math.max(2, width - stringWidth(indent));
    const cells = [token.header, ...token.rows].map(row => token.header.map((_, i) => inline(row[i]?.tokens ?? [])));
    const count = token.header.length;
    if (!count) return;
    const budget = available - count * 3 - 1;
    const widths = Array<number>(count).fill(2);
    const ideals = widths.map((_, i) => Math.max(2, ...cells.map(row => Math.max(...lines(row[i]!, available).map(line => stringWidth(rowText(line)))))));
    let remaining = budget - count * 2;
    while (remaining > 0) {
      const col = widths.reduce((best, value, i) => ideals[i]! - value > ideals[best]! - widths[best]! ? i : best, 0);
      if (widths[col]! >= ideals[col]!) break;
      widths[col]!++; remaining--;
    }
    const wrapped = cells.map(row => row.map((cell, i) => lines(cell, widths[i]!)));
    if (budget < count * 2 || wrapped.some(row => row.some(cell => cell.length > 5))) {
      // 窄屏逐条保留表头和值，不用省略号吞掉单元格。
      token.rows.forEach((_, index) => {
        if (index) rows.push({spans: []});
        cells[index + 1]!.forEach((cell, col) => append([
          ...cells[0]![col]!.map(part => ({...part, bold: true})), span('：'), ...cell,
        ], indent));
      });
      if (!token.rows.length) cells[0]!.forEach(cell => append(cell, indent));
      return;
    }
    const border = (left: string, join: string, right: string) => append([span(left + widths.map(size => '─'.repeat(size + 2)).join(join) + right, palette.muted)], indent);
    border('┌', '┬', '┐');
    wrapped.forEach((row, index) => {
      const height = Math.max(...row.map(cell => cell.length));
      for (let line = 0; line < height; line++) {
        const parts: Span[] = [span('│', palette.muted)];
        row.forEach((cell, col) => {
          const value = cell[line]?.spans ?? [];
          const pad = Math.max(0, widths[col]! - stringWidth(value.map(part => part.text).join('')));
          const alignment = token.align[col];
          const before = alignment === 'right' ? pad : alignment === 'center' ? Math.floor(pad / 2) : 0;
          parts.push(span(' ' + ' '.repeat(before)), ...value.map(part => index === 0 ? {...part, bold: true} : part), span(' '.repeat(pad - before) + ' '), span('│', palette.muted));
        });
        append(parts, indent);
      }
      if (index === 0) border('├', '┼', '┤');
    });
    border('└', '┴', '┘');
  }
  function block(tokens: readonly Token[], indent = '', depth = 0): void {
    if (depth > 16) {for (const token of tokens) append([span(token.raw)], ''); return;}
    for (const token of tokens) {
      // Marked 18 把任务框同时投影为 token；列表项标记已呈现，正文不能再印一次。
      if (token.type === 'checkbox') continue;
      if (token.type === 'space' || token.type === 'def') {
        if (rows.length && token.type === 'space') rows.push({spans: indent.includes('│') ? [span(indent.trimEnd(), palette.muted)] : []});
        continue;
      }
      if (token.type === 'heading') {
        const heading = token as Tokens.Heading;
        append(inline(heading.tokens).map(part => ({...part, bold: true})), indent);
      } else if (token.type === 'paragraph' || token.type === 'text') {
        const paragraph = token as Tokens.Paragraph;
        append(paragraph.tokens ? inline(paragraph.tokens) : [span(paragraph.text)], indent);
      } else if (token.type === 'table') {
        table(token as Tokens.Table, indent);
      } else if (token.type === 'code') {
        const code = token as Tokens.Code;
        if (code.lang) append([span(code.lang, palette.muted)], indent + '│ ', indent + '│ ');
        for (const line of code.text.replace(/\t/g, '    ').split('\n')) append([span(line)], indent + '│ ', indent + '│ ');
      } else if (token.type === 'list') {
        const list = token as Tokens.List;
        list.items.forEach((item, index) => {
          const marker = (list.ordered ? String((typeof list.start === 'number' ? list.start : 1) + index) + '. ' : item.task ? '' : '• ')
            + (item.task ? item.checked ? '[✓] ' : '[ ] ' : '');
          const bodyIndent = indent + ' '.repeat(stringWidth(marker));
          const begin = rows.length;
          block(item.tokens, bodyIndent, depth + 1);
          // 标记属于列表项，不属于其每个子块。先按正文缩进排版，再给首个内容行加标记。
          let first = begin;
          while (first < rows.length && (!rowText(rows[first]!).startsWith(bodyIndent) || !rowText(rows[first]!).slice(bodyIndent.length).trim())) first++;
          const startsWithList = item.tokens.find(child => child.type !== 'space')?.type === 'list';
          if (first < rows.length && !startsWithList) {
            rows[first] = {...rows[first]!, spans: [span(indent + marker, palette.muted), ...rows[first]!.spans.slice(bodyIndent.length)]};
          } else rows.splice(begin, 0, ...lines([span(indent + marker)], width));
        });
      } else if (token.type === 'blockquote') {
        block((token as Tokens.Blockquote).tokens, indent + '│ ', depth + 1);
      } else if (token.type === 'hr') append([span('─'.repeat(Math.max(2, Math.min(width - stringWidth(indent), 30))), palette.muted)], indent);
      else append([span(token.raw)], indent);
    }
  }
  try {block(tokensFor(text));}
  catch {rows.push(...lines([span(text)], width));}
  if (cache.size >= 8) cache.delete(cache.keys().next().value!);
  cache.set(key, rows);
  return rows;
}
