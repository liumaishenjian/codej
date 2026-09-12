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
  h.emit('question.requested', {callId: 'late', questions});
  h.emit('approval.requested', {approvalId:'late-gate',ordinal:1,toolName:'run_command',shell:'powershell',workingDirectory:'.',command:'LATE_COMMAND',sessionScope:true});
  h.emit('model.text.delta', {text: 'LATE_ASSISTANT_TEXT'});
  h.emit('tool.started', {ordinal: 1, toolName: 'run_command', command: 'LATE_COMMAND', shell: 'powershell', workingDirectory: '.'});
  h.emit('run.cancelled'); await wait();
  expect(h.app.lastFrame()).toContain('后续中文😀');
  expect(h.app.lastFrame()).not.toContain('选择检查范围');
  expect(h.app.lastFrame()).not.toContain('LATE_COMMAND');
  expect(h.app.lastFrame()).not.toContain('LATE_ASSISTANT_TEXT');
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
it('无审批元数据优先显示完整命令，审批焦点结束后恢复中文草稿', async () => {
  const h = await mount();
  await h.key('审批期间保留😀');
  const command = `Write-Output ${'长命令'.repeat(90)}-完整尾部`;
  h.emit('tool.started', {
    ordinal: 1,
    toolName: 'run_command',
    command,
    parametersPreview: '执行 “已截断…”',
    shell: 'powershell',
    workingDirectory: '.',
  });
  await wait();
  await h.key('\x0f');
  expect(h.app.lastFrame()).toContain('完整尾部');
  expect(h.app.lastFrame()).toContain('审批期间保留😀');

  h.emit('approval.requested', {
    approvalId: 'gate', ordinal: 1, toolName: 'run_command', shell: 'powershell',
    workingDirectory: '.', command, sessionScope: true,
  });
  await wait();
  expect(h.app.lastFrame()).toContain('允许本次操作');
  await h.key('1');
  expect(h.client.resolveApproval).not.toHaveBeenCalled();
  await h.key('\r');
  expect(h.client.resolveApproval).toHaveBeenCalledExactlyOnceWith('gate', 'allow_once');
  expect(h.app.lastFrame()).toContain('审批期间保留😀');
  expect(h.app.lastFrame()).toContain('完整尾部');
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


it('搜索分组、命令详情、草稿与审批在六种视窗保持可达',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('尺寸');h.emit('run.started');
  h.emit('model.turn.started',{turn:1});
  h.emit('tool.started',{ordinal:1,toolName:'search_text',parametersPreview:'alpha'});h.emit('tool.completed',{ordinal:1,toolName:'search_text',content:'one'});
  h.emit('tool.started',{ordinal:2,toolName:'search_text',parametersPreview:'beta'});h.emit('tool.completed',{ordinal:2,toolName:'search_text',content:'two'});
  h.emit('tool.started',{ordinal:3,toolName:'run_command',command:'Write-Output 完整命令尾部',parametersPreview:'执行 “已截断…”',shell:'powershell',workingDirectory:'.'});
  for (const width of [40,80,120]) for(const height of [24,35]) {
    const ui={...newRuntimeUi(),draft:{text:'中文草稿😀',cursor:6}};
    let frame=runtimeFrame(r.state,ui,width,height);
    let visible=frame.rows.map(rowText).join('\n');
    expect(frame.rows.length).toBeLessThanOrEqual(height);
    expect(frame.rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(visible).toContain('搜索内容 · 2 项');
    expect(visible).toContain('powershell');
    expect(visible).toContain('中文草稿😀');
    frame=runtimeFrame(r.state,{...ui,expanded:true},width,height);
    visible=frame.rows.map(rowText).join('\n');
    expect(visible).toContain('完整命令尾部');
    expect(visible).toContain('目录：.');
  }
  h.emit('approval.requested',{approvalId:'gate',ordinal:3,toolName:'run_command',effect:'execute_process',operation:'execute',command:'Write-Output 完整命令尾部',shell:'powershell',workingDirectory:'.',sessionScope:true});
  for (const width of [40,80,120]) for(const height of [24,35]) {
    const frame=runtimeFrame(r.state,newRuntimeUi(),width,height);
    const visible=frame.rows.map(rowText).join('\n');
    expect(frame.rows.length).toBeLessThanOrEqual(height);
    expect(frame.rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
    expect(visible).toContain('3. 拒绝本次操作');
    expect(visible).toContain('Esc');
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

it('搜索只折叠同Run同回合同类相邻成功，失败与展开保持独立',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('搜索');h.emit('run.started');
  h.emit('model.turn.started',{turn:1});
  h.emit('tool.started',{ordinal:1,toolName:'search_text',parametersPreview:'alpha'});
  h.emit('tool.completed',{ordinal:1,toolName:'search_text',content:'one'});
  h.emit('tool.started',{ordinal:2,toolName:'search_text',parametersPreview:'beta'});
  h.emit('tool.completed',{ordinal:2,toolName:'search_text',content:'two'});
  h.emit('tool.started',{ordinal:3,toolName:'search_text',parametersPreview:'failed'});
  h.emit('tool.failed',{ordinal:3,toolName:'search_text',errorCode:'invalid_arguments'});
  h.emit('model.turn.started',{turn:2});
  h.emit('tool.started',{ordinal:4,toolName:'search_text',parametersPreview:'later'});
  h.emit('tool.completed',{ordinal:4,toolName:'search_text',content:'four'});
  let visible=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
  expect(visible.match(/搜索内容 · 2 项/g)).toHaveLength(1);
  expect(visible).toContain('工具参数无效');
  expect(visible).toContain('later');
  visible=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).rows.map(rowText).join('\n');
  expect(visible).not.toContain('搜索内容 · 2 项');
  expect(visible.match(/搜索内容/g)).toHaveLength(4);
});

it('流式增量被同回合finalText替换且正文只显示一次',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('回答');h.emit('run.started');
  h.emit('model.turn.started',{turn:1});
  h.emit('model.text.delta',{text:'唯一'});h.emit('model.text.delta',{text:'正文'});
  h.emit('run.completed',{stopReason:'completed',finalText:'唯一正文'});
  const visible=runtimeFrame(r.state,newRuntimeUi(),80,24).rows.map(rowText).join('\n');
  expect(visible.match(/唯一正文/g)).toHaveLength(1);
  expect(r.state.status).toBe('idle');
});

it('验证声明只按宿主 ordinal 显示真实恢复，旧字段与跨 Run 不猜测',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\example');r.connect();h.initialize();r.submit('/plan 天气');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'declare_plan_evidence'});
  h.emit('tool.failed',{ordinal:1,toolName:'declare_plan_evidence',errorCode:'invalid_arguments',failureReasonCode:'verification_tool_unavailable'});
  h.emit('tool.started',{ordinal:2,toolName:'declare_plan_evidence'});
  h.emit('tool.failed',{ordinal:2,toolName:'declare_plan_evidence',errorCode:'invalid_arguments',failureReasonCode:'verification_tool_unavailable'});
  h.emit('tool.started',{ordinal:3,toolName:'declare_plan_evidence'});
  h.emit('tool.completed',{ordinal:3,toolName:'declare_plan_evidence'});
  let visible=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
  expect(visible.match(/验证方式使用了当前不可用的工具/g)).toHaveLength(2);
  expect(visible).not.toContain('已修正验证方式');

  h.emit('tool.started',{ordinal:4,toolName:'declare_plan_evidence'});
  h.emit('tool.completed',{ordinal:4,toolName:'declare_plan_evidence',recoveredFailureOrdinal:2});
  visible=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).rows.map(rowText).join('\n');
  expect(visible.match(/验证方式使用了当前不可用的工具/g)).toHaveLength(1);
  expect(visible).toContain('已修正验证方式，继续规划');
  expect(visible).toContain('原声明：失败（验证工具不可用）');
  expect(visible).toContain('后续声明：修正成功');
  expect(visible).not.toMatch(/(?:原|修正)调用 #\d+/);
  expect(r.state.blocks.find(block=>block.kind==='tool'&&block.ordinal===2)).toMatchObject({status:'failed',recoveredByOrdinal:4});

  h.emit('tool.started',{ordinal:5,toolName:'read_file'});
  h.emit('tool.failed',{ordinal:5,toolName:'read_file',errorCode:'invalid_arguments'});
  h.emit('tool.started',{ordinal:6,toolName:'declare_plan_evidence'});
  h.emit('tool.completed',{ordinal:6,toolName:'declare_plan_evidence',recoveredFailureOrdinal:5});
  expect(r.state.blocks.find(block=>block.kind==='tool'&&block.ordinal===5)).toMatchObject({status:'failed',recoveredByOrdinal:0});
  h.emit('tool.started',{ordinal:7,toolName:'declare_plan_evidence'});
  h.emit('tool.failed',{ordinal:7,toolName:'declare_plan_evidence',errorCode:'invalid_arguments'});
  h.emit('tool.started',{ordinal:8,toolName:'declare_plan_evidence'});
  h.emit('tool.completed',{ordinal:8,toolName:'declare_plan_evidence',recoveredFailureOrdinal:7});
  expect(r.state.blocks.find(block=>block.kind==='tool'&&block.ordinal===7)).toMatchObject({status:'failed',recoveredByOrdinal:0});

  h.emit('run.completed',{stopReason:'completed'});r.submit('/plan 另一轮');h.emit('run.started',{},'request','run-2');
  h.emit('tool.started',{ordinal:1,toolName:'declare_plan_evidence'},'request','run-2');
  h.emit('tool.failed',{ordinal:1,toolName:'declare_plan_evidence',errorCode:'invalid_arguments'},'request','run-2');
  h.emit('tool.started',{ordinal:2,toolName:'declare_plan_evidence'},'request','run-2');
  h.emit('tool.completed',{ordinal:2,toolName:'declare_plan_evidence'},'request','run-2');
  visible=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
  expect(visible).toContain('工具参数无效');
  expect(visible.match(/已修正验证方式/g)).toHaveLength(1);
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

it('命令与网页搜索折叠摘要不冒用协议头，展开保留真实输出',()=>{
  for(const [name,output,summary] of [
    ['run_command','shell: powershell\nstdout:\nquery-result','命令执行完成'],
    ['web_search','provenance: external-web-search\nquery-result','网页搜索完成'],
  ] as const) {
    const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('查询');h.emit('run.started');
    h.emit('tool.started',{ordinal:1,toolName:name});
    h.emit('tool.completed',{ordinal:1,toolName:name,content:output});
    const collapsed=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
    expect(collapsed).toContain(summary);expect(collapsed).not.toContain(output.split('\n')[0]);
    const expanded=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).rows.map(rowText).join('\n');
    expect(expanded).toContain(output.split('\n')[0]);expect(expanded).toContain('query-result');
    h.emit('tool.failed',{ordinal:1,toolName:name,errorCode:'invalid_arguments'});
    const failed=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
    expect(failed).not.toContain(summary);expect(failed).toContain('工具参数无效');
  }
});

it('审核条件失败只在当前Run真实审核事件后显示恢复，不改写历史失败',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('/plan 天气');h.emit('run.started');
  const visible=()=>runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
  h.emit('tool.started',{ordinal:1,toolName:'request_plan_review'});
  h.emit('tool.failed',{ordinal:1,toolName:'request_plan_review',errorCode:'plan_gate_blocked'});
  expect(visible()).toContain('计划尚未满足审核条件');expect(visible()).not.toContain('plan_gate_blocked');
  h.emit('tool.started',{ordinal:2,toolName:'read_file'});h.emit('tool.completed',{ordinal:2,toolName:'read_file'});
  expect(r.state.blocks.find(b=>b.kind==='tool'&&b.ordinal===1)).not.toHaveProperty('reviewRecovered');
  h.emit('plan.review.requested',{planId:'p',revision:2,contentDigest:'c',workspaceDigest:'w',markdown:'# 计划'});
  expect(r.state.blocks.find(b=>b.kind==='tool'&&b.ordinal===1)).toMatchObject({status:'failed',reviewRecovered:true});
  r.state.showPlan=false;r.state.pending=undefined;
  expect(visible()).toContain('审核条件已补齐');
  h.emit('run.completed');r.submit('/plan 另一任务');h.emit('run.started',{},'request','run-2');
  h.emit('tool.started',{ordinal:1,toolName:'request_plan_review'},'request','run-2');
  h.emit('tool.failed',{ordinal:1,toolName:'request_plan_review',errorCode:'plan_gate_blocked'},'request','run-2');
  h.emit('plan.review.requested',{planId:'old',revision:3},'request','run');
  expect(r.state.blocks.find(b=>b.kind==='tool'&&b.run==='run-2'&&b.ordinal===1)).not.toHaveProperty('reviewRecovered');
});
