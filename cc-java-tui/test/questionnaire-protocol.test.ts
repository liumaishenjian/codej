import {describe,it,expect} from 'vitest';
import {fileURLToPath} from 'node:url';
import {StdioClient} from '../src/stdio-client.js';
import {decodeEvent,type ProtocolEvent} from '../src/protocol.js';
const fixture=fileURLToPath(new URL('./fixtures/questionnaire-child.mjs',import.meta.url));
const wait=async(p:()=>boolean)=>{const end=Date.now()+4000;while(!p()){if(Date.now()>end)throw Error('超时');await new Promise(r=>setTimeout(r,10));}};
function client(mode='new'){return new StdioClient({executable:process.execPath,args:[fixture,mode],cwd:process.cwd()});}
describe('问卷协议',()=>{
 it('只在双向协商后传递完整答案并保留中文字',async()=>{
  const c=client();const events:ProtocolEvent[]=[];c.onEvent(e=>events.push(e));
  try{
   c.initialize({questionnaireV1:true});await wait(()=>c.questionnaireEnabled);
   c.startRun('问卷');await wait(()=>events.some(e=>e.type==='question.requested'));
   expect(()=>c.resolveQuestionnaire('call',[{questionId:'q',optionIds:[],freeText:'\ud800'}])).toThrow();
   const answers=[{questionId:'q',optionIds:[],freeText:'中文😀\n第二行'}];
   c.resolveQuestionnaire('call',answers);await wait(()=>events.some(e=>e.type==='run.completed'));
   expect(JSON.parse(String(events.find(e=>e.type==='model.text.delta')?.payload.text))).toEqual({callId:'call',answers});
   expect(()=>c.resolveQuestionnaire('call',answers)).toThrow();
  }finally{await c.shutdown();}
 });
 it('旧宿主不启用问卷，未请求能力不能被宿主擅自打开',async()=>{
  const old=client('old');const events:ProtocolEvent[]=[];old.onEvent(e=>events.push(e));
  try{old.initialize({questionnaireV1:true});await wait(()=>events.length>0);expect(old.questionnaireEnabled).toBe(false);expect(()=>old.resolveQuestionnaire('call',[])).toThrow();}finally{await old.shutdown();}
  const unsolicited=client();const failures:string[]=[];unsolicited.onFailure(e=>failures.push(e));
  try{unsolicited.initialize();await wait(()=>failures.length>0);expect(failures[0]).toContain('未协商');}finally{await unsolicited.shutdown();}
 });
 it('拒绝重复题号、未声明字段和无答案入口的题目',()=>{
  const q={id:'q',title:'题签',question:'问题',multiSelect:false,allowFreeText:true,options:[]};
  const decode=(questions:unknown[])=>decodeEvent(JSON.stringify({version:0,type:'question.requested',requestId:'r',sessionId:'s',runId:'run',sequence:1,payload:{callId:'call',questions}}),1);
  expect(()=>decode([q])).not.toThrow();expect(()=>decode([q,q])).toThrow();expect(()=>decode([{...q,allowFreeText:false}])).toThrow();expect(()=>decode([{...q,raw:'secret'}])).toThrow();
 });
});
