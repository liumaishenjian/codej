import {expect,it,vi} from 'vitest';
import {marked} from 'marked';
import {markdownRows} from '../src/experience/markdown.js';
import {newRuntimeUi,readingState} from '../src/experience/runtime-screen.js';
import {rowText} from '../src/experience/screen.js';
import {ExperienceRuntime,type RuntimeClient} from '../src/experience/runtime.js';

it('范围限制不冻结终态或脱敏，也不隐藏新审批面板',()=>{
  const state=new ExperienceRuntime({} as RuntimeClient,'.').state;
  const blocks=Array.from({length:3},(_,i)=>({kind:'assistant' as const,id:'a'+i,run:'r',turn:i,text:'正文'+i}));
  const ui={...newRuntimeUi(),expanded:true,readLimit:2};
  const first=readingState({...state,blocks},ui);expect(first.blocks).toHaveLength(2);
  const pending={kind:'approval' as const,id:'gate'} as NonNullable<typeof state.pending>;
  const corrected={...state,status:'idle' as const,pending,blocks:blocks.map((block,i)=>i===0?{...block,text:'已脱敏'}:block)};
  const next=readingState(corrected,ui);
  expect(next.blocks[0]).toMatchObject({text:'已脱敏'});expect(next.pending).toBe(pending);expect(next.status).toBe('idle');
  expect(readingState(corrected,ui).blocks).toBe(next.blocks);
});
it('跨宽度复用语法，但后置引用定义与围栏闭合仍重新解释',()=>{
  const source='[引用109][source]\n\n```ps\nWrite-Output 109';
  markdownRows(source,39);const lexer=vi.spyOn(marked,'lexer');
  try {
    markdownRows(source,79);markdownRows(source,119);expect(lexer).not.toHaveBeenCalled();
    const final=source+'\n```\n\n[source]: https://example.com/109';
    expect(markdownRows(final,79).map(rowText).join('')).toContain('https://example.com/109');
    expect(lexer).toHaveBeenCalledTimes(1);
  } finally {lexer.mockRestore();}
});
it('语法缓存有界淘汰，淘汰只影响重解析而不丢正文',()=>{
  const samples=Array.from({length:35},(_,i)=>`缓存淘汰109-${i}\n\n**正文**`);
  for(const text of samples) markdownRows(text,41);
  const lexer=vi.spyOn(marked,'lexer');
  try {
    expect(markdownRows(samples[34]!,81).map(rowText).join('')).toContain('缓存淘汰109-34');expect(lexer).not.toHaveBeenCalled();
    expect(markdownRows(samples[0]!,81).map(rowText).join('')).toContain('缓存淘汰109-0');expect(lexer).toHaveBeenCalledTimes(1);
  } finally {lexer.mockRestore();}
});
