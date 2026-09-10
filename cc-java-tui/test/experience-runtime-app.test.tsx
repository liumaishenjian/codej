import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import stringWidth from 'string-width';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import {ExperienceRuntime, type RuntimeClient} from '../src/experience/runtime.js';
import {newRuntimeUi, runtimeFrame} from '../src/experience/runtime-screen.js';
import {rowText} from '../src/experience/screen.js';
import {markdownRows} from '../src/experience/markdown.js';
import type {ProtocolEvent} from '../src/protocol.js';
const size = vi.hoisted(() => ({columns: 80, rows: 24}));
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => size}));
afterEach(() => {cleanup(); size.columns = 80; size.rows = 24;});
const wait = () => new Promise(resolve => setTimeout(resolve, 35));
function host() {
  let listener = (_event: ProtocolEvent) => {};
  let failed = (_message: string) => {};
  let sequence = 0;
  const client = {
    initialize: vi.fn(() => 'init'), onEvent: (fn: typeof listener) => {listener = fn; return () => {};},
    onFailure: (fn: typeof failed) => {failed = fn; return () => {};},
    startRun: vi.fn(() => 'request'), startPlan: vi.fn(() => 'request'), cancelRun: vi.fn(() => 'cancel'),
    resolveApproval: vi.fn(() => 'approval'), resolveQuestion: vi.fn(() => 'question'),
    resolveQuestionnaire: vi.fn(() => 'answers'), resolvePlanReview: vi.fn(() => 'review'),
    shutdown: vi.fn(async () => {}),
  } satisfies RuntimeClient;
  const emit = (type: string, payload = {}, requestId = 'request', runId: string | undefined = 'run') =>
    listener({type, payload, requestId, sessionId: 'session', runId, sequence: ++sequence} as ProtocolEvent);
  return {client, emit, failed: () => failed('disconnected'), initialize: () => emit('initialized', {questionnaireV1: true, modelConfigured: true}, 'init', undefined)};
}
const options = [{optionId: 'a', label: '第一个', description: '说明一'}, {optionId: 'b', label: '第二个', description: '说明二'}];
const questions = [
  {id: 'single', title: '范围', question: '选择检查范围', multiSelect: false, allowFreeText: true, options},
  {id: 'multi', title: '项目', question: '选择多个项目', multiSelect: true, allowFreeText: true, options},
  {id: 'text', title: '补充', question: '补充说明', multiSelect: false, allowFreeText: true, options: []},
];
async function mount() {
  const h = host(); const app = render(<ExperienceRuntimeApp client={h.client} workspace="G:\\example"/>);
  await wait(); h.initialize(); await wait();
  const key = async (s: string) => {app.stdin.write(s); await wait();};
  await key('任务'); await key('\r'); h.emit('run.started', {requestModel: 'configured-model'}); await wait();
  return {...h, app, key};
}
it('真实适配器保留运行中草稿，取消后迟到问卷不重夺焦点，断连保留输入', async () => {
  const h = await mount();
  await h.key('后续中文😀'); await h.key('\x0f'); await h.key('\x0f');
  await h.key('\r'); expect(h.client.startRun).toHaveBeenCalledTimes(1);
  await h.key('\x1b'); expect(h.client.cancelRun).toHaveBeenCalledTimes(1);
  h.emit('question.requested', {callId: 'late', questions}); h.emit('run.cancelled'); await wait();
  expect(h.app.lastFrame()).toContain('后续中文😀');
  expect(h.app.lastFrame()).not.toContain('选择检查范围');
  h.failed(); await wait(); expect(h.app.lastFrame()).toContain('连接已断开');
  expect(h.app.lastFrame()).toContain('后续中文😀');
});
it('真实问卷单选多选自由回答、复核返回修改后只整批提交一次', async () => {
  const h = await mount(); h.emit('question.requested', {callId: 'call', questions}); await wait();
  await h.key('1'); await h.key('1'); await h.key('2'); await h.key('\r');
  await h.key('\r'); await h.key('中文补充😀'); await h.key('\r');
  expect(h.app.lastFrame()).toContain('核对你的回答');
  expect(h.client.resolveQuestionnaire).not.toHaveBeenCalled();
  await h.key('\x1b[Z'); await h.key('\x1b[Z');
  expect(h.app.lastFrame()).toContain('[✓]');
  await h.key('2'); await h.key('\t'); await h.key('\t'); await h.key('\r'); await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledTimes(1);
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledWith('call', [
    {questionId: 'single', optionIds: ['a'], freeText: ''},
    {questionId: 'multi', optionIds: ['a'], freeText: ''},
    {questionId: 'text', optionIds: [], freeText: '中文补充😀'},
  ]);
});
it('完整Shell审批需Enter确认；数字选中不会提前执行', async () => {
  const h = await mount(); h.emit('approval.requested', {approvalId:'gate',ordinal:1,toolName:'run_command',shell:'PowerShell',workingDirectory:'G:\\example',command:'Write-Output 42',sessionScope:true}); await wait();
  expect(h.app.lastFrame()).toContain('PowerShell'); expect(h.app.lastFrame()).toContain('Write-Output 42');
  await h.key('2'); expect(h.client.resolveApproval).not.toHaveBeenCalled();
  await h.key('\r'); await h.key('\r');
  expect(h.client.resolveApproval).toHaveBeenCalledExactlyOnceWith('gate','allow_session');
});
it('Markdown及长问卷在六种视窗保留选项、焦点与退出入口', () => {
  const h=host(); const runtime=new ExperienceRuntime(h.client,'G:\\example'); runtime.connect(); h.initialize(); runtime.submit('任务'); h.emit('run.started');
  const long = questions.map(q=>({...q,title:'题目'.repeat(30),question:'问题正文'.repeat(80),options:Array.from({length:8},(_,i)=>({optionId:String(i),label:'长选项'.repeat(30),description:'详细说明'.repeat(100)}))}));
  h.emit('question.requested',{callId:'call',questions:long});
  for (const width of [40,80,120]) for(const height of [24,35]) {
    const ui={...newRuntimeUi(),focus:7};
    const frame=runtimeFrame(runtime.state,ui,width,height);
    expect(frame.rows.length).toBeLessThanOrEqual(height);
    expect(frame.rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(frame.rows.map(rowText).join('\n')).toContain('8.');
    expect(frame.rows.map(rowText).join('\n')).toContain('Esc');
    const md=markdownRows('# 标题\n\n- 中文'.repeat(30)+'\n\n1. **加粗**与代码\n\n~~~ts\nconst x = 1;\n~~~',width);
    expect(md.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
  }
});


it('Alt+Enter只换行，小屏编辑态Esc仍立即停止运行',async()=>{
  const h=host();const app=render(<ExperienceRuntimeApp client={h.client} workspace="G:\\example"/>);
  await wait();h.initialize();await wait();
  const key=async(s:string)=>{app.stdin.write(s);await wait();};
  await key('第一行');await key('\x1b\r');await key('第二行');
  expect(h.client.startRun).not.toHaveBeenCalled();
  await key('\r');expect(h.client.startRun).toHaveBeenCalledWith('第一行\n第二行');
  h.emit('run.started');h.emit('question.requested',{callId:'call',questions});await wait();
  await key('3');await key('未提交草稿');
  size.columns=30;app.rerender(<ExperienceRuntimeApp client={h.client} workspace="G:\\example"/>);await wait();
  await key('\x1b');expect(h.client.cancelRun).toHaveBeenCalledTimes(1);
});
it('多选自由回答保存后可以用Enter重新编辑并保留选项',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'call',questions});await wait();
  await h.key('1');await h.key('1');await h.key('3');await h.key('补充');await h.key('\r');
  await h.key('\x1b[A');await h.key('\r');
  expect(h.app.lastFrame()).toContain('Enter 保存');
  await h.key('修改');await h.key('\r');await h.key('\r');
  expect(h.app.lastFrame()).toContain('补充说明');
});

it('正常计划编排不刷屏，展开也不泄漏任务JSON；失败仍可见',()=>{
  for(const name of ['task_list','task_create','task_get','task_update','revise_plan_artifact','request_plan_review','declare_plan_evidence']) {
    const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('/plan 示例');h.emit('run.started');
    h.emit('tool.started',{ordinal:1,toolName:name,parametersPreview:'internal-plan-id'});
    h.emit('tool.completed',{ordinal:1,toolName:name,content:'{"board_revision":1,"task":{"id":"task-1"}}'});
    for(const expanded of [false,true]) {
      const text=runtimeFrame(r.state,{...newRuntimeUi(),expanded},80,35).rows.map(rowText).join('\n');
      expect(text).not.toContain('board_revision');expect(text).not.toContain('internal-plan-id');expect(text).not.toContain('task-1');
      expect(text).not.toContain('完成');expect(text).not.toContain('查看任务清单');
    }
    h.emit('tool.failed',{ordinal:1,toolName:name,errorCode:'invalid_arguments'});
    expect(runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n')).toContain('工具参数无效');
  }
});
