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
  const end=Date.now()+50000;
  while(!check()) {if(Date.now()>end) throw Error(JSON.stringify(diagnostic())); await wait();}
}
const cp=process.env.CC_JAVA_TEST_CLASSPATH;
async function launch(main:string,args:string[]) {
  const events:ProtocolEvent[]=[]; const failures:string[]=[];
  const fixtureCp=process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
  const effectiveCp=fixtureCp ? fixtureCp+path.delimiter+cp! : cp!;
  const client=new StdioClient({executable:'java',args:['-cp',effectiveCp,main,...args],cwd:process.cwd()}, {shutdownTimeoutMs:2000});
  client.onEvent(event=>events.push(event));client.onFailure(error=>failures.push(error));
  const app=render(<ExperienceRuntimeApp client={client} workspace={args[0]!}/>);
  const diagnostic=()=>({events:events.slice(-8),failures,screen:app.lastFrame()});
  await until(()=>events.some(e=>e.type==='initialized'),diagnostic);
  await wait();
  const key=async(text:string)=>{app.stdin.write(text);await wait();};
  return {client,app,events,failures,key,diagnostic};
}
it.skipIf(!cp)('R1-R4 Fixture保留草稿、投影无审批命令元数据并取消终态',async()=>{
  const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-core-interaction-'));
  const workspace=path.join(parent,'workspace');const sessions=path.join(parent,'sessions');
  await fs.mkdir(workspace);await fs.mkdir(sessions);
  const {execFile}=await import('node:child_process');const {promisify}=await import('node:util');
  await promisify(execFile)('git',['init',workspace]);
  const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.TuiCoreInteractionFixtureMain',[workspace,sessions]);
  try {
    await h.key('第一轮');await h.key('\r');await h.key('后续中文草稿😀');await h.key('\x0f');
    await until(()=>h.events.some(e=>e.type==='approval.requested'),h.diagnostic);await wait();
    const started=h.events.find(e=>e.type==='tool.started'&&e.payload.toolName==='run_command')!;
    const approval=h.events.find(e=>e.type==='approval.requested')!;
    expect(started.payload.command).toContain('fixture-command-full-tail');
    expect(started.payload.command).toBe(approval.payload.command);
    expect(started.payload.shell).toBe(approval.payload.shell);
    expect(started.payload.workingDirectory).toBe(approval.payload.workingDirectory);
    await h.key('2');await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='tool.started'&&e.payload.toolName==='run_command').length===2,h.diagnostic);
    await until(()=>h.events.some(e=>e.type==='run.completed'),h.diagnostic);await wait();
    expect(h.events.filter(e=>e.type==='approval.requested')).toHaveLength(1);
    expect(h.app.lastFrame()).toContain('后续中文草稿😀');
    expect(h.app.lastFrame()).toContain('fixture-command-full-tail');
    await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='tool.started'&&e.payload.toolName==='run_command').length===3,h.diagnostic);
    const currentStarted=h.events.filter(e=>e.type==='tool.started'&&e.payload.toolName==='run_command').at(-1)!;
    await until(()=>h.events.some(e=>e.type==='tool.output'
      && e.runId===currentStarted.runId
      && e.payload.ordinal===currentStarted.payload.ordinal
      && String(e.payload.text).includes('fixture-command-start')),h.diagnostic);
    expect(h.events.filter(e=>e.type==='approval.requested')).toHaveLength(1);
    await h.key('\x1b');
    await until(()=>h.events.some(e=>e.type==='run.cancelled'&&e.runId===currentStarted.runId),h.diagnostic);await wait(100);
    expect(h.app.lastFrame()).toContain('本轮已停止');
    expect(h.app.lastFrame()).not.toContain('允许本次操作');
    expect(h.failures).toEqual([]);
  } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
},60000);

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
    await h.key('问卷完成后下一轮');await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='run.completed').length===2,h.diagnostic);await wait();
    expect(h.events.filter(e=>e.type==='tool.completed'&&e.payload.toolName==='ask_user_questions')).toHaveLength(1);
    expect(h.app.lastFrame()).toContain('问卷结束后下一轮仍可继续');
    expect(h.failures).toEqual([]);
  } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
},40000);

it.skipIf(!cp)('新界面取消真实Java问卷后不提交旧答案并恢复下一轮',async()=>{
  const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-question-cancel-'));
  const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.QuestionnairesFixtureMain',[path.resolve('..'),path.join(parent,'sessions')]);
  try {
    await h.key('请提问');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='question.requested'),h.diagnostic);await wait();
    await h.key('1');await h.key('\x1b');
    await until(()=>h.events.some(e=>e.type==='run.cancelled'),h.diagnostic);await wait();
    expect(h.events.some(e=>e.type==='tool.completed'&&e.payload.toolName==='ask_user_questions')).toBe(false);
    expect(h.app.lastFrame()).toContain('本轮已停止');
    expect(h.app.lastFrame()).not.toContain('核对你的回答');
    await h.key('取消后下一轮');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='run.completed'),h.diagnostic);await wait();
    expect(h.app.lastFrame()).toContain('问卷结束后下一轮仍可继续');
    expect(h.events.some(e=>e.type==='tool.completed'&&e.payload.toolName==='ask_user_questions')).toBe(false);
    expect(h.failures).toEqual([]);
  } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
},40000);

it.skipIf(!cp)('真实Java验证声明失败只按宿主关联显示恢复且保留历史失败',async()=>{
  const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-evidence-recovery-'));
  const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.PlanEvidenceRecoveryFixtureMain',[parent]);
  try {
    await h.key('/plan 查询天气');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='plan.review.requested'),h.diagnostic);
    await until(()=>h.events.some(e=>e.type==='run.completed'),h.diagnostic);await wait();
    const failed=h.events.filter(e=>e.type==='tool.failed'&&e.payload.toolName==='declare_plan_evidence');
    const completed=h.events.filter(e=>e.type==='tool.completed'&&e.payload.toolName==='declare_plan_evidence');
    expect(failed).toHaveLength(2);
    expect(failed.every(e=>e.payload.failureReasonCode==='verification_tool_unavailable')).toBe(true);
    expect(completed).toHaveLength(2);
    expect(completed[0]!.payload.recoveredFailureOrdinal).toBeUndefined();
    expect(completed[1]!.payload.recoveredFailureOrdinal).toBe(failed[1]!.payload.ordinal);
    expect(failed.every(e=>e.payload.status==='failure')).toBe(true);
    expect(h.app.lastFrame()).toContain('验证方式使用了当前不可用的工具');
    expect(h.app.lastFrame()).toContain('已修正验证方式，继续规划');
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
    expect(h.events.some(e=>e.type==='plan.verification.completed')).toBe(true);
    expect(h.events.some(e=>e.type==='plan.verification.required')).toBe(false);
    const executionEvents=h.events.filter(e=>e.requestId===h.events.find(event=>event.type==='plan.execution.accepted')?.requestId);
    expect(executionEvents.findIndex(e=>e.type==='plan.execution.accepted')).toBeLessThan(executionEvents.findIndex(e=>e.type==='run.started'));
    const finalRun=h.events.filter(e=>e.type==='run.completed').at(-1)!;
    expect(finalRun.payload.stopReason).toBe('completed');
    expect(finalRun.payload.finalText).toContain('approved plan corrected and verified');
    expect(h.app.lastFrame()).toContain('approved plan corrected and verified');
    const fixture=(await fs.readdir(parent,{withFileTypes:true})).find(entry=>entry.isDirectory()&&entry.name.startsWith('plan-runtime-'));
    expect(fixture).toBeDefined();
    const workspace=path.join(parent,fixture!.name,'workspace');
    await expect(fs.readFile(path.join(workspace,'河南各市7天天气.xlsx'),'utf8')).resolves.toBe('correct-name');
    await expect(fs.access(path.join(workspace,'河南各市7天天气.xlsx'))).resolves.toBeUndefined();
    const taskSnapshots=h.events.filter(e=>e.type==='task.board.snapshot'&&e.requestId===finalRun.requestId);
    expect(taskSnapshots.at(-1)?.payload.tasks).toEqual([
      expect.objectContaining({status:'COMPLETED'}),expect.objectContaining({status:'COMPLETED'}),
    ]);
    expect(h.failures).toEqual([]);
    await h.key('普通输入');await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='run.completed').length===4,h.diagnostic);await wait();
    expect(h.app.lastFrame()).toContain('follow-up completed');
  } finally {await h.client.shutdown();h.app.unmount();await fs.rm(parent,{recursive:true,force:true});}
},60000);

it.skipIf(!cp)('新界面拒绝真实Java计划后无执行副作用并恢复下一轮',async()=>{
  const parent=await fs.mkdtemp(path.join(os.tmpdir(),'codej-next-plan-reject-'));
  const h=await launch('io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain',['plan-runtime',parent]);
  try {
    await h.key('/plan 分析但不要执行');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='plan.review.requested'),h.diagnostic);
    await until(()=>h.events.some(e=>e.type==='run.completed'),h.diagnostic);await wait();
    await h.key('3');await h.key('\r');
    await until(()=>h.events.some(e=>e.type==='plan.review.rejected'),h.diagnostic);await wait();
    expect(h.app.lastFrame()).toContain('计划已取消，没有启动执行');
    expect(h.events.some(e=>e.type==='plan.execution.accepted')).toBe(false);
    expect(h.events.some(e=>e.type==='approval.requested')).toBe(false);
    expect(h.events.some(e=>e.type==='tool.completed'&&e.payload.toolName==='write_file')).toBe(false);
    const fixture=(await fs.readdir(parent,{withFileTypes:true})).find(entry=>entry.isDirectory()&&entry.name.startsWith('plan-runtime-'));
    expect(fixture).toBeDefined();
    const workspace=path.join(parent,fixture!.name,'workspace');
    await expect(fs.access(path.join(workspace,'河南各市7天天气.xlsx'))).rejects.toBeDefined();
    await expect(fs.access(path.join(workspace,'河南各市7天天气预报.xlsx'))).rejects.toBeDefined();
    await h.key('/plan 拒绝后新计划');await h.key('\r');
    await until(()=>h.events.filter(e=>e.type==='plan.review.requested').length===2,h.diagnostic);
    await until(()=>h.events.filter(e=>e.type==='run.completed').length===2,h.diagnostic);await wait();
    const reviews=h.events.filter(e=>e.type==='plan.review.requested');
    expect(reviews[1]!.payload.planId).not.toBe(reviews[0]!.payload.planId);
    expect(h.app.lastFrame()).toContain('拒绝后的全新计划');
    expect(h.events.some(e=>e.type==='run.launch.failed')).toBe(false);
    expect(h.failures).toEqual([]);
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
