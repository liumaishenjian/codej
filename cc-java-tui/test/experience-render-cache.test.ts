import {expect, it, vi} from 'vitest';
import {marked} from 'marked';
import {newRuntimeUi, runtimeFrame} from '../src/experience/runtime-screen.js';
import {rowText} from '../src/experience/screen.js';
import type {RuntimeSnapshot, ToolRecord} from '../src/experience/runtime.js';

function conversation(): RuntimeSnapshot {
  return {connection:'ready',status:'idle',session:'s',workspace:'example',model:'fake',mode:'chat',
    blocks:Array.from({length:24},(_,i)=>({kind:'assistant' as const,id:'a'+i,run:'r'+i,turn:1,text:`历史缓存样本${i}\n\n|项|值|\n|---|---|\n|中文|${i}|`})),
    pending:undefined,plan:undefined,showPlan:false,notice:'',activity:'',startedAt:0,revision:0,questionnaire:true};
}
it('超过8条历史时输入和动画不重复解析稳定Markdown',()=>{
  const state=conversation();const ui=newRuntimeUi();
  runtimeFrame(state,ui,80,24);
  const lexer=vi.spyOn(marked,'lexer');
  try {
    for(let i=0;i<12;i++) runtimeFrame({...state,activity:'正在思考'}, {...ui,draft:{text:'草稿'+i,cursor:3}},80,24,i*250);
    expect(lexer).not.toHaveBeenCalled();
  } finally {lexer.mockRestore();}
});
it('当前消息修正只重新解析该消息，改变宽度仍重新排版',()=>{
  const state=conversation();const ui=newRuntimeUi();runtimeFrame(state,ui,80,24);
  const lexer=vi.spyOn(marked,'lexer');
  try {
    const corrected={...state,blocks:state.blocks.map((block,i)=>i===23?{...block,text:'最终修正内容'}:block)};
    const frame=runtimeFrame(corrected,ui,80,24);
    expect(lexer).toHaveBeenCalledTimes(1);
    expect(frame.bodyRows.map(rowText).join('\n')).toContain('最终修正内容');
    expect(frame.bodyRows.map(rowText).join('\n')).not.toContain('历史缓存样本23');
    lexer.mockClear();runtimeFrame(corrected,ui,40,24);
    expect(lexer).not.toHaveBeenCalled();
  } finally {lexer.mockRestore();}
});
it('正文复用不冻结审批状态、脱敏结果或真实模型头部',()=>{
  const tool:ToolRecord={kind:'tool',id:'t',run:'r',turn:1,ordinal:1,name:'run_command',activity:'',status:'running',output:'PRIVATE-PLACEHOLDER',preview:'echo ok',shell:'PowerShell',directory:'.',truncated:false,failure:'',failureReasonCode:'',failureCategory:'',argumentChangeRequired:false,retryable:false,recoveredByOrdinal:0};
  const state={...conversation(),blocks:[tool]};const ui={...newRuntimeUi(),expanded:true};
  const frame=()=>runtimeFrame(state,ui,80,24).bodyRows.map(rowText).join('\n');
  expect(frame()).toContain('正在执行');
  const awaiting:RuntimeSnapshot={...state,pending:{kind:'approval',id:'gate',ordinal:1,tool:'run_command',command:'echo ok',shell:'PowerShell',directory:'.',target:'',operation:'',sessionScope:true,event:{version:1,type:'approval.requested',runId:'r',sessionId:'s',requestId:'q',sequence:1,payload:{}}}};
  expect(runtimeFrame(awaiting,ui,80,24).bodyRows.map(rowText).join('\n')).toContain('等待审批');
  expect(frame()).not.toContain('等待审批');
  const redacted={...state,model:'new-model',blocks:[{...tool,status:'completed' as const,output:'内容已脱敏'}]};
  const rendered=runtimeFrame(redacted,ui,80,24).bodyRows.map(rowText).join('\n');
  expect(rendered).toContain('内容已脱敏');expect(rendered).not.toContain('PRIVATE-PLACEHOLDER');expect(rendered).toContain('new-model');
});

it('流式长工具输出只排版有界尾窗，完成后详情仍保留宿主上限内全文',()=>{
  const longOutput=Array.from({length:5000},(_,i)=>`output-line-${i}`).join('\n');
  const tool:ToolRecord={kind:'tool',id:'long',run:'r',turn:1,ordinal:1,name:'run_command',activity:'执行中',status:'running',output:longOutput,preview:'echo long',shell:'PowerShell',directory:'.',truncated:false,failure:'',failureReasonCode:'',failureCategory:'',argumentChangeRequired:false,retryable:false,recoveredByOrdinal:0};
  const running={...conversation(),status:'running' as const,blocks:[tool]};
  const collapsed=runtimeFrame(running,newRuntimeUi(),80,24).bodyRows.map(rowText).join('\n');
  expect(collapsed).toContain('运行中仅显示最近输出');
  expect(collapsed).toContain('output-line-4999');
  expect(collapsed).not.toContain('output-line-0');
  const completed={...running,status:'idle' as const,blocks:[{...tool,status:'completed' as const}]};
  const details=runtimeFrame(completed,{...newRuntimeUi(),expanded:true},80,35).bodyRows.map(rowText).join('\n');
  expect(details).toContain('output-line-0');
  expect(details).toContain('output-line-4999');
  expect(details).not.toContain('运行中较早输出已省略');
});

it('当前回合的流式长回答只解析有界尾窗，终态恢复完整正文',()=>{
  const longText=Array.from({length:5000},(_,i)=>`answer-line-${i}`).join('\n');
  const state:RuntimeSnapshot={...conversation(),status:'running',blocks:[
    {kind:'user',id:'u',run:'active',turn:0,text:'长回答'},
    {kind:'assistant',id:'a',run:'active',turn:1,text:longText},
  ]};
  const running=runtimeFrame(state,newRuntimeUi(),80,24).bodyRows.map(rowText).join('\n');
  expect(running).toContain('运行中仅显示回答尾部');
  expect(running).toContain('answer-line-4999');
  expect(running).not.toContain('answer-line-0');
  const completed=runtimeFrame({...state,status:'idle'},newRuntimeUi(),80,35).bodyRows.map(rowText).join('\n');
  expect(completed).toContain('answer-line-0');
  expect(completed).toContain('answer-line-4999');
  expect(completed).not.toContain('运行中仅显示回答尾部');
});
