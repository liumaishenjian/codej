import {expect,it} from 'vitest';
import {lines,paintSpans,rowText, type Span} from '../src/experience/screen.js';
import {glyphs} from '../src/experience/editor.js';

it('长正文绘制节点按样式段而非字数增长，完整文字不变',()=>{
  const text='中文😀abcdefgh '.repeat(400);
  const rows=lines([{text}],80);
  const before=rows.reduce((n,row)=>n+row.spans.length,0);
  const after=rows.reduce((n,row)=>n+paintSpans(row).length,0);
  expect(after).toBe(rows.length);expect(before).toBeGreaterThan(after*30);
  expect(rows.flatMap(paintSpans).map(part=>part.text).join('')).toBe(text);
});
it('合并不越过光标、颜色和嵌套样式边界，不修改排版输入',()=>{
  const parts:Span[]=[{text:'普通中文😀'},{text:'光',inverse:true},{text:'标',inverse:true},{text:'普通'},
    {text:'粗斜',bold:true,italic:true},{text:'删除',strikethrough:true},{text:'红',color:'red'},{text:'蓝',color:'blue'}];
  const expand=(spans:Span[])=>spans.flatMap(part=>glyphs(part.text).map(text=>({...part,text})));
  for(const row of lines(parts,12)) {
    const before=JSON.stringify(row);const painted=paintSpans(row);
    expect(expand(painted)).toEqual(expand(row.spans));expect(painted.map(part=>part.text).join('')).toBe(rowText(row));
    expect(JSON.stringify(row)).toBe(before);expect(paintSpans(row)).toBe(painted);
  }
});
