import {expect, it} from 'vitest';
import stringWidth from 'string-width';
import {markdownRows} from '../src/experience/markdown.js';
import {rowText} from '../src/experience/screen.js';

const source = '| 日期 | 天气 | 温度 |\n| :--- | :---: | ---: |\n| 周一 | 晴☀️ | 26℃ |\n| 周二 | **小雨** | 21℃ |';
it('来源、引用和产物地址在三种宽度完整保留，裸网址不重复',()=>{
  const url='https://example.com/forecast?city=qingdao&days=7';
  const path='G:/AI Cloud/result.md';
  for(const width of [38,78,118]) {
    const rows=markdownRows(`[天气来源](${url})\n\n[产物](<${path}>)\n\n![图表](https://example.com/chart.png)\n\n[参考][ref]\n\n[ref]: ${url}`,width);
    const flat=rows.map(rowText).join('');
    for(const value of [url,path,'https://example.com/chart.png','天气来源','产物','参考']) expect(flat).toContain(value);
    expect(rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(markdownRows(url,width).map(rowText).join('')).toBe(url);
  }
});
it('样式跨折行保留，代码中的Markdown保持字面值',()=>{
  const rows=markdownRows('***强调中文*** ~~已替换~~\n\n`[代码](https://example.com)`',12);
  const spans=rows.flatMap(row=>row.spans);
  expect(spans.find(part=>part.text==='强')).toMatchObject({bold:true,italic:true});
  expect(spans.find(part=>part.text==='替')).toMatchObject({strikethrough:true});
  expect(rows.map(rowText).join('')).toContain('[代码](https://example.com)');
});
it('过深嵌套降级保留内容，有序任务保留完成状态',()=>{
  expect(markdownRows('> '.repeat(20)+'最深正文',80).map(rowText).join('')).toContain('最深正文');
  const task=markdownRows('1. [x] 已完成\n2. [ ] 待处理',40).map(rowText).join('\n');
  expect(task).toContain('1. [✓] 已完成');expect(task).toContain('2. [ ] 待处理');
});
it('窄表格完整保留链接且不输出终端控制字符',()=>{
  const rows=markdownRows('| 来源 | 说明 |\n| --- | --- |\n| [数据](https://example.com/data) | 完整来源 |',12);
  expect(rows.map(rowText).join('').replace(/\s/g,'')).toContain('https://example.com/data');
  const untrusted=markdownRows('[目标](https://example.com/\x1b]52;clipboard\x07)',80).map(rowText).join('');
  expect(untrusted).not.toMatch(/[\x00-\x08\x0b-\x1f\x7f-\x9f]/);
});
it('中文表格在不同终端宽度完整保留内容与列边界',()=>{
  for(const width of [38,78,118]) {
    const rows=markdownRows(source,width);const text=rows.map(rowText).join('\n');
    expect(rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(text).toContain('┌');expect(text).not.toContain(':---');
    for(const value of ['日期','天气','温度','周一','周二','晴☀️','小雨','26℃','21℃']) expect(text).toContain(value);
    const edges=rows.filter(row=>rowText(row).startsWith('│')).map(row=>stringWidth(rowText(row)));
    expect(new Set(edges).size).toBe(1);
    expect(rows.some(row=>row.spans.some(part=>part.text==='小'&&part.bold))).toBe(true);
  }
});
it('按Marked对齐信息补空格，空单元格和转义管道不串列',()=>{
  const text=markdownRows('| 左列 | 中列 | 右列 |\n| :--- | :---: | ---: |\n| a | b | 2 |\n| a\\|b | | 12 |',60).map(rowText).join('\n');
  expect(text).toContain('│ a    │  b   │    2 │');
  expect(text).toContain('a|b');expect(text).not.toContain('a\\|b');
});
it('过窄表格转为键值且长中文与链接没有省略',()=>{
  const value='长中文内容'.repeat(15);
  const text=markdownRows('| 名称 | 说明 | 状态 |\n| --- | --- | --- |\n| 示例 | '+value+' | 完成 |',20).map(rowText).join('\n');
  expect(text).toContain('名称：示例');expect(text).toContain('状态：完成');
  expect(text.replace(/\s/g,'')).toContain(value);expect(text).not.toContain('…');
});
it('代码行折行保持侧边与缩进，未闭合围栏仍可读',()=>{
  const code='```powershell\n  Write-Output "'+ '中文'.repeat(25)+'"\n\n\tWrite-Output `n';
  const rows=markdownRows(code,38);const text=rows.map(rowText).join('\n');
  expect(rows.every(row=>stringWidth(rowText(row))<=38)).toBe(true);
  expect(rows.every(row=>rowText(row).startsWith('│ '))).toBe(true);
  expect(text).toContain('│   Write-Output');expect(text).toContain('│     Write-Output `n');
  expect(text).toContain('\n│ \n');expect(text).not.toContain('```');
});
it('列表中文续行悬挂缩进，正文前缀不会重复为项目符号',()=>{
  const rows=markdownRows('- '+ '中文长列表'.repeat(10),38).map(rowText);
  expect(rows[0]).toMatch(/^• /);expect(rows.slice(1).every(row=>row.startsWith('  '))).toBe(true);
  expect(rows.join('').replace(/[•\s]/g,'')).toBe('中文长列表'.repeat(10));
});
it('流式分片和宽度变化重新解析，最终表格不保留分隔符原文',()=>{
  for(let end=1;end<=source.length;end++) expect(()=>markdownRows(source.slice(0,end),38)).not.toThrow();
  const final=markdownRows(source,78).map(rowText).join('\n');expect(final).toContain('└');expect(final).not.toContain('---');
  expect(markdownRows(source,12).every(row=>stringWidth(rowText(row))<=12)).toBe(true);
});

it('嵌套列表标记仅属于本项，不把父标记复制给子项和后续段落',()=>{
  const source='- 父项目\n\n  后续段落\n\n  - 子项目一\n  - 子项目二\n\n- 第二项目';
  const text=markdownRows(source,38).map(rowText).join('\n');
  expect(text.match(/•/g)).toHaveLength(4);
  expect(text).toContain('\n  后续段落');expect(text).toContain('\n  • 子项目一');
  expect(text).not.toContain('• •');expect(text).toContain('\n• 第二项目');
});
it('有序列表保留起始编号，项内代码和空行不重复编号',()=>{
  const source='9. 第一项\n\n   ```powershell\n   Write-Output "甲"\n\n   Write-Output "乙"\n   ```\n\n10. 第二项';
  const text=markdownRows(source,38).map(rowText).join('\n');
  expect(text.match(/9\. /g)).toHaveLength(1);expect(text.match(/10\. /g)).toHaveLength(1);
  expect(text).toContain('\n   │ powershell');expect(text).toContain('\n   │ \n');
  expect(text).toContain('\n10. 第二项');
});
it('引用里的列表续行和空行保留引用标记，不重复列表标记',()=>{
  const text=markdownRows('> - '+ '中文内容'.repeat(18)+'\n>\n>   后续段落',38).map(rowText).join('\n');
  expect(text.match(/•/g)).toHaveLength(1);
  expect(text.split('\n').filter(Boolean).every(row=>row.startsWith('│'))).toBe(true);
  expect(text).toContain('\n│   后续段落');
});
it('任务项包含表格时保留表头与数据，勾选标记只出现一次',()=>{
  const text=markdownRows('- [x] 已完成\n\n  | 字段 | 值 |\n  | --- | --- |\n  | 状态 | 正常 |',38).map(rowText).join('\n');
  expect(text.match(/\[✓\]/g)).toHaveLength(1);expect(text).toContain('字段');expect(text).toContain('正常');
  expect(text).toContain('\n    ┌');
});

it('引用内表格窄屏回退、嵌套流式块在不同宽度不丢正文',()=>{
  const source='> - 引用任务\n>\n>   后续段落\n>\n>   | 字段 | 内容 |\n>   | --- | --- |\n>   | 说明 | '+ '甲乙丙丁'.repeat(12)+' |\n>\n>   - 子任务';
  for(const width of [20,38,78,118]) {
    const rows=markdownRows(source,width);const text=rows.map(rowText).join('\n');
    expect(rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(rows.filter(row=>rowText(row)).every(row=>rowText(row).startsWith('│'))).toBe(true);
    expect(text.match(/•/g)).toHaveLength(2);expect(text).toContain('子任务');
    // 表格多列按视觉行交错，逐字计数检查内容没有被裁剪，不能跨列拼接整表当单个单元格。
    for(const char of ['甲','乙','丙','丁']) expect(text.split(char).length-1).toBe(12);
  }
  for(let i=1;i<source.length;i+=5) expect(()=>markdownRows(source.slice(0,i),38)).not.toThrow();
});
