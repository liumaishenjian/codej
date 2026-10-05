import {expect, it} from 'vitest';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import stringWidth from 'string-width';
import {webTarget} from '../src/experience/hyperlinks.js';
import {markdownRows} from '../src/experience/markdown.js';
import {lines, paintSpans, rowText} from '../src/experience/screen.js';

it('只接受绝对网页目标，拒绝控制字节、可执行协议和相对文件', () => {
  for (const value of ['javascript:alert(1)', 'file:///C:/x', 'data:text/html,x', './report.md', '//example.com',
    'https://example.com/\x1b]8;;evil\x07', 'https://example.com/\nnext', 'https://example.com/\u009c', 'https://']) {
    expect(webTarget(value)).toBeUndefined();
  }
  expect(webTarget('https://example.com/中文?q=1&b=2')).toBe('https://example.com/%E4%B8%AD%E6%96%87?q=1&b=2');
});

it('折行、表格、图片和样式保留网页目标，代码与不安全目标保持惰性', () => {
  const text='[**中文来源😀**](https://example.com/source)\n\n|来源|图片|\n|---|---|\n|https://example.com/plain|![说明](https://example.com/image)|\n\n`https://example.com/code`\n\n[本地](report.md) [危险](javascript:alert)';
  for (const width of [40, 80, 120]) {
    const rows=markdownRows(text,width);
    expect(rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    const parts=rows.flatMap(paintSpans);
    expect(parts.some(part=>part.bold && part.href==='https://example.com/source')).toBe(true);
    for (const suffix of ['source','plain','image']) expect(parts.some(part=>part.href==='https://example.com/'+suffix)).toBe(true);
    expect(parts.some(part=>part.href?.includes('code') || part.href?.includes('javascript') || part.href?.includes('report'))).toBe(false);
    expect(rows.map(rowText).join('')).toContain('report.md');
    expect(JSON.stringify(rows)).not.toContain('\\u001b');
    expect(markdownRows(text,width)).toBe(rows);
  }
  const row=lines([{text:'甲',href:'https://a.test/'},{text:'乙',href:'https://b.test/'},{text:'丙'}],80)[0]!;
  expect(paintSpans(row).map(part=>part.href)).toEqual(['https://a.test/','https://b.test/',undefined]);
});

// 独立Node进程使公共包按不同终端能力初始化；验证真实公共Ink输出而非mock序列化。
function terminal(force: string, columns: number): string {
  const result=spawnSync(process.execPath,['--import','tsx','--input-type=module','-e',`
    import React from 'react';
    import {render, Box, Text} from 'ink';
    import {EventEmitter} from 'node:events';
    import {RowView} from './src/experience/screen.tsx';
    import {markdownRows} from './src/experience/markdown.ts';
    let output='';
    const stream=Object.assign(new EventEmitter(),{columns:${columns},rows:35,isTTY:true,write(text){output+=text;return true;}});
    const rows=markdownRows('[**中文来源😀重复标签**](https://example.com/source?q=one&b=two)',${columns});
    const app=render(React.createElement(Box,{flexDirection:'column'},React.createElement(RowView,{rows,columns:${columns}}),React.createElement(Text,null,'NEXT_INPUT')),{stdout:stream,debug:false,patchConsole:false,exitOnCtrlC:false,isScreenReaderEnabled:false});
    await new Promise(resolve=>setTimeout(resolve,80));
    app.unmount();
    process.stdout.write(JSON.stringify(output));
  `],{cwd:fileURLToPath(new URL('..',import.meta.url)),env:{...process.env,FORCE_HYPERLINK:force},encoding:'utf8',timeout:15000});
  expect(result.error).toBeUndefined();expect(result.status,result.stderr).toBe(0);
  return JSON.parse(result.stdout) as string;
}

it('公共Ink保留每段OSC8且在下一输入前闭合，退化输出保留完整地址', () => {
  expect(terminal('',80)).not.toContain('\x1b]8;;');
  for (const width of [40,80,120]) {
    const active=terminal('1',width);
    const inactive=terminal('0',width);
    const escapes=[...active.matchAll(/\x1b\]8;;([^\x07]*)\x07/g)];
    expect(escapes.length).toBeGreaterThan(1);
    let target='';
    let offset=0;
    let linked='';
    for (const match of escapes) {
      if (active.slice(offset,match.index).includes('NEXT_INPUT')) expect(target).toBe('');
      if (target) linked+=active.slice(offset,match.index);
      target=match[1]!;
      expect(['','https://example.com/source?q=one&b=two']).toContain(target);
      offset=match.index!+match[0].length;
    }
    expect(target).toBe('');expect(active.slice(offset)).toContain('NEXT_INPUT');
    expect(linked).toContain('中文来源😀重复标签');
    expect(linked.replace(/\s/g,'')).toContain('https://example.com/source?q=one&b=two');
    expect(inactive).not.toContain('\x1b]8;;');
    const visible=(value:string)=>value.replace(/\x1b\]8;;[^\x07]*\x07/g,'').replace(/\x1b\[[0-9;?]*[A-Za-z]/g,'');
    expect(visible(active)).toBe(visible(inactive));
    expect(visible(inactive).replace(/\s/g,'')).toContain('https://example.com/source?q=one&b=two');
  }
}, 20000);
