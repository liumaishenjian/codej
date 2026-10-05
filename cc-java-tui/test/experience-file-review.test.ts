import {expect, it} from 'vitest';
import stringWidth from 'string-width';
import {fileReviewRows} from '../src/experience/file-review.js';
import {rowText} from '../src/experience/screen.js';

it('文件坐标、多块上下文和中文折行使用真实行号', () => {
  const before = Array.from({length: 30}, (_, i) => `原行${i+1}\n`).join('');
  const change = {status:'available',scope:'file',before,after:before.replace('原行5','新行五'.repeat(35)).replace('原行25','新行二十五')} as const;
  for(const width of [40,80,120]) {
    const rows=fileReviewRows(change,width), text=rows.map(rowText).join('\n');
    expect(text).toContain('@@ -2,7 +2,7 @@'); expect(text).toContain('@@ -22,7 +22,7 @@');
    expect(text).toContain('   5      - 原行5');expect(text).toContain('        5 + ');
    expect(rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(text).not.toContain('原行15');
  }
});
it('创建、删除与末尾换行变化不伪造额外行',()=>{
  const show=(before:string,after:string)=>fileReviewRows({status:'available',scope:'file',before,after},80).map(rowText).join('\n');
  expect(show('','内容\n')).toContain('        1 + 内容');
  expect(show('内容\n','')).toContain('   1      - 内容');
  expect(show('内容','内容\n')).toContain('此处末尾无换行');
  expect(show('','')).toContain('没有文本差异');
});
it('旧片段不显示文件行号，拒显不生成正文',()=>{
  const rows=fileReviewRows({status:'available',before:'old',after:'new'},40).map(rowText).join('\n');
  expect(rows).toContain('文件行号未知');expect(rows).not.toContain('@@');
  expect(fileReviewRows({status:'redacted',scope:'file',before:'不得显示',after:''},80)).toEqual([]);
});
