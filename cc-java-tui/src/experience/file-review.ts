import {structuredPatch, type StructuredPatch} from 'diff';
import type {FileChange} from './runtime.js';
import {lines, span, palette, type Row} from './screen.js';

/** 只绘制宿主提供的审阅快照；片段没有文件坐标，不猜测行号。 */
export function fileReviewRows(change: FileChange, width: number): Row[] {
  if (change.status !== 'available') return [];
  const rows: Row[] = [];
  const add = (prefix: string, value: string, color?: string) => {
    lines([span(value.replace(/\t/g, '    '), color)], Math.max(2, width - prefix.length)).forEach((row, index) =>
      rows.push({spans: [span(index ? ' '.repeat(prefix.length) : prefix, color), ...row.spans]}));
  };
  if (change.scope === 'file') {
    let patch = patches.get(change);
    if (!patches.has(change)) {
      patch = structuredPatch('', '', change.before, change.after, undefined, undefined, {context: 3, maxEditLength: 12000, timeout: 50});
      patches.set(change, patch ?? null);
    }
    if (patch) {
      for (const hunk of patch.hunks) {
        add('  ', `@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`, palette.muted);
        let oldLine = hunk.oldStart, newLine = hunk.newStart;
        for (const line of hunk.lines) {
          const marker = line[0];
          if (marker === '\\') {add('    ', '此处末尾无换行', palette.muted); continue;}
          const oldNumber = marker === '+' ? '' : String(oldLine++);
          const newNumber = marker === '-' ? '' : String(newLine++);
          add(` ${oldNumber.padStart(4)} ${newNumber.padStart(4)} ${marker} `, line.slice(1),
            marker === '+' ? palette.green : marker === '-' ? palette.red : palette.muted);
        }
      }
      if (!patch.hunks.length) add('  ', '没有文本差异', palette.muted);
      return rows;
    }
    add('  ', '差异计算达到预算，以下展示前后文本', palette.muted);
  } else add('  ', '仅替换片段，文件行号未知', palette.muted);
  for (const [text, marker, color] of [[change.before, '- ', palette.red], [change.after, '+ ', palette.green]] as const) {
    if (!text) continue;
    const normalized = text.replace(/\r\n?/g, '\n'), parts = normalized.split('\n');
    if (normalized.endsWith('\n')) parts.pop();
    parts.forEach(value => add('  ' + marker, value, color));
    if (!normalized.endsWith('\n')) add('    ', '此片段末尾无换行', palette.muted);
  }
  if (!change.before && !change.after) add('  ', '空内容', palette.muted);
  return rows;
}
const patches = new WeakMap<FileChange, StructuredPatch | null>();
