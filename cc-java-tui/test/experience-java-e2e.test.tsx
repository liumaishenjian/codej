import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => ({columns:80,rows:35})}));
afterEach(cleanup);
const wait = (ms=45) => new Promise(resolve=>setTimeout(resolve,ms));
async function until(check:()=>boolean, diagnostic:()=>unknown) {
  const end=Date.now()+25000;
  while(!check()) {if(Date.now()>end) throw Error(JSON.stringify(diagnostic())); await wait();}
}
const cp=process.env.CC_JAVA_TEST_CLASSPATH;
async function launch(main:string,args:string[]) {
  const events:ProtocolEvent[]=[]; const failures:string[]=[];
  const client=new StdioClient({executable:'java',args:['-cp',cp!,main,...args],cwd:process.cwd()}, {shutdownTimeoutMs:2000});
  client.onEvent(event=>events.push(event));client.onFailure(error=>failures.push(error));
  const app=render(<ExperienceRuntimeApp client={client} workspace={args[0]!}/>);
  const diagnostic=()=>({events:events.slice(-8),failures,screen:app.lastFrame()});
  await until(()=>events.some(e=>e.type==='initialized'),diagnostic);
  await wait();
  const key=async(text:string)=>{app.stdin.write(text);await wait();};
  return {client,app,events,failures,key,diagnostic};
}
it.skipIf(!cp)('新界面→真实Java问卷管线→原调用唯一工具结果→回答',async()=>{
  const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-question-'));
  const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.QuestionnairesFixtureMain',[path.resolve('..'),path.join(parent,'sessions')]);
  try {
    await h.key('请提问');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='question.requested'),h.diagnostic);await wait();
    await h.key('1');await h.key('1');await h.key('2');await h.key('\r');
    await h.key('\r');await h.key('真实中文回答');await h.key('\r');
    expect(h.app.lastFrame()).toContain('核对你的回答');
    await h.key('\x1b[Z');await h.key('\t');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='run.completed'),h.diagnostic);await wait();
    expect(h.events.filter(e=>e.type==='tool.completed'&&e.payload.toolName==='ask_user_questions')).toHaveLength(1);
    expect(h.app.lastFrame()).toContain('真实中文回答');
    expect(h.events.find(e=>e.type==='run.completed')!.payload.finalText).toContain('问卷答案已通过真实工具结果返回模型');
    expect(h.failures).toEqual([]);
  } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
},40000);
it.skipIf(!cp)('新界面单入口Plan→反馈→确认KEEP人工审批→真实文件工具执行',async()=>{
  const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-plan-'));
  const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain',['plan-runtime',parent]);
  try {
    await h.key('/plan 分析并生成实施计划');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='run.completed'),h.diagnostic);await wait();
    expect(h.app.lastFrame()).toContain('确认并执行');
    await h.key('2');await h.key('\r');await h.key('add rollback verification');await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='run.completed').length===2,h.diagnostic);await wait();
    expect(h.app.lastFrame()).toContain('验证纠正后的工作簿与回滚结果');
    await h.key('1');await h.key('\r');
    for(let i=1;i<=2;i++) {
      await until(()=>h.events.filter(e=>e.type==='approval.requested').length===i,h.diagnostic);await wait();
      expect(h.app.lastFrame()).toContain('允许本次操作');
      await h.key('\r');
    }
    await until(()=>h.events.filter(e=>e.type==='run.completed').length===3,h.diagnostic);await wait();
    expect(h.events.filter(e=>e.type==='tool.completed'&&e.payload.toolName==='write_file')).toHaveLength(2);
    expect(h.events.at(-1)?.payload.finalText).toContain('approved plan corrected and verified');
    expect(h.failures).toEqual([]);
    await h.key('普通输入');await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='run.completed').length===4,h.diagnostic);
  } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
},60000);


it.skipIf(!cp)('真实搜索与命令失败原位呈现；取消后的输出不重启运行',async()=>{
  const {execFile}=await import('node:child_process');const {promisify}=await import('node:util');
  for(const cancel of [false,true]) {
    const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-tools-'));
    const workspace=path.join(parent,'workspace');await fs.mkdir(workspace);
    await promisify(execFile)('git',['init',workspace]);
    await fs.writeFile(path.join(workspace,'fixture.txt'),'fixture-search-marker\n');
    const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.CoreToolsFixtureMain',[workspace,path.join(parent,'sessions'),...(cancel?['cancel']:[])]);
    try {
      await h.key('搜索并验证命令');await h.key('\r');
      await until(()=>h.events.some(e=>e.type==='approval.requested'),h.diagnostic);await wait();
      const approval=h.events.find(e=>e.type==='approval.requested')!;
      expect(approval.payload.workingDirectory).toBeTruthy();expect(approval.payload.shell).toBeTruthy();
      expect(h.app.lastFrame()).toContain('fixture-command-start');
      await h.key('\r');
      await until(()=>h.events.some(e=>e.type==='tool.output'),h.diagnostic);await wait();
      if(cancel) await h.key('\x1b');
      await until(()=>h.events.some(e=>e.type=== (cancel?'run.cancelled':'run.completed')),h.diagnostic);await wait();
      if(cancel) expect(h.app.lastFrame()).toContain('本轮已停止');
      else {
        expect(h.events.some(e=>e.type==='tool.failed'&&e.payload.toolName==='run_command')).toBe(true);
        await h.key('\x0f');expect(h.app.lastFrame()).toContain('fixture-command-stderr');
      }
      expect(h.failures).toEqual([]);
    } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
  }
},60000);
