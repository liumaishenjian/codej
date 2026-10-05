import {afterEach, expect, it, vi} from 'vitest';
import {EventEmitter} from 'node:events';
import {marked} from 'marked';
import {render as renderTerminal} from 'ink';
import {cleanup, render} from 'ink-testing-library';
import stringWidth from 'string-width';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import {ExperienceRuntime, type RuntimeClient} from '../src/experience/runtime.js';
import {newRuntimeUi, runtimeFrame, runtimeViewportHeight} from '../src/experience/runtime-screen.js';
import {rowText} from '../src/experience/screen.js';
import {markdownRows} from '../src/experience/markdown.js';
import {moveDraftVertical} from '../src/experience/editor.js';
import {NativeHistoryScreen, stableHistoryLength} from '../src/experience/native-history.js';
import type {ProtocolEvent} from '../src/protocol.js';
const size = vi.hoisted(() => ({columns: 80, rows: 24}));
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => size}));
afterEach(() => {cleanup(); size.columns = 80; size.rows = 24;});
const wait = () => new Promise(resolve => setTimeout(resolve, 35));

it('多文件审批保留各自上下文，拒绝或冲突不冒称完成；脱敏清除本地预览',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('修改两个文件');h.emit('run.started');
  for(const ordinal of [1,2]) {
    const name=`sample${ordinal}.txt`;
    h.emit('tool.started',{ordinal,toolName:'apply_patch'});
    h.emit('approval.requested',{approvalId:'a'+ordinal,ordinal,toolName:'apply_patch',target:name,operation:'modify',
      fileChange:{status:'available',scope:'file',before:`上下文${ordinal}\nold\n末行\n`,after:`上下文${ordinal}\nnew\n末行\n`}});
    for(const columns of [40,80,120])for(const height of [24,35]) {
      const frame=runtimeFrame(r.state,newRuntimeUi(),columns,height);
      expect(frame.rows.every(row=>stringWidth(rowText(row))<=columns)).toBe(true);
      expect(frame.fixedRows.map(rowText).join('\n')).toContain('允许本次操作');
    }
    const pending=r.state.pending!;if(pending.kind!=='approval')throw Error('expected approval');
    r.approve(pending,ordinal===1?'allow_once':'deny');
    h.emit('tool.failed',{ordinal,toolName:'apply_patch',errorCode:ordinal===1?'file_conflict':'permission_denied'});
  }
  const details=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).bodyRows.map(rowText).join('\n');
  expect(details).toContain('上下文1');expect(details).toContain('上下文2');
  expect(details).toContain('文件冲突，需重新读取核对');expect(details).toContain('操作已被拒绝');expect(details).not.toContain('文件修改完成');
  h.emit('tool.started',{ordinal:3,toolName:'apply_patch'});
  h.emit('approval.requested',{approvalId:'a3',ordinal:3,toolName:'apply_patch',target:'third.txt',operation:'modify',
    fileChange:{status:'available',scope:'file',before:'临时可见',after:'改动'}});
  h.emit('tool.failed',{ordinal:3,toolName:'apply_patch',contentRedacted:true});
  expect(r.state.blocks.find(block=>block.kind==='tool'&&block.ordinal===3)).toMatchObject({fileChange:undefined});
  expect(r.state.pending).toBeUndefined();
  r.dispose();
});

it('文件工具终态优先显示宿主的真实结果摘要',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('修改文件');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'apply_patch'});
  h.emit('tool.completed',{ordinal:1,toolName:'apply_patch',content:'path: src/App.java\noperation: modified',
    resultSummary:{path:'src/App.java',operation:'modified',verification:'verified',replacements:2,removedLines:3,addedLines:4}});
  const text=runtimeFrame(r.state,newRuntimeUi(),80,24).bodyRows.map(rowText).join('\n');
  expect(text).toContain('已修改 src/App.java · 已核验 · 2 处替换');
  expect(r.state.blocks.find(block=>block.kind==='tool')).toMatchObject({resultSummary:{path:'src/App.java',verification:'verified',replacements:2}});
  r.dispose();
});

it.each(['none','preamble','tool','task-update','stream-final','final-text'])('最终交付 %s 不能以工具前说明或任务更新冒充回答',scenario=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('验收样本');h.emit('run.started');
  h.emit('model.turn.started',{turn:1});
  if(scenario!=='none')h.emit('model.text.delta',{text:'我会检查样本'});
  if(scenario!=='none'&&scenario!=='preamble') {
    h.emit('tool.started',{ordinal:1,toolName:scenario==='task-update'?'task_update':'read_file',turn:1});
    h.emit('tool.completed',{ordinal:1,toolName:scenario==='task-update'?'task_update':'read_file',content:'执行结果'});
    h.emit('model.turn.started',{turn:2});
  }
  if(scenario==='stream-final')h.emit('model.text.delta',{text:'核对结果与限制'});
  h.emit('run.completed',scenario==='final-text'?{finalText:'最终核对结果'}:{});
  expect(r.state.notice.includes('没有返回可显示')).toBe(['none','tool','task-update'].includes(scenario));
  expect(r.state.status).toBe('idle');r.dispose();
});

it.each(['completed','failed'])('工具 %s 终态拒绝迟到重启、输出、重复结果和错调用覆盖',status=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('样本');h.emit('run.started');
  const payload={ordinal:1,toolName:'run_command',callId:'original'};
  h.emit('tool.started',payload);h.emit('tool.completed',{...payload,callId:'wrong',content:'错误调用'});
  expect(r.state.blocks.find(block=>block.kind==='tool')).toMatchObject({status:'running',output:''});
  h.emit('tool.'+status,{...payload,contentRedacted:true});
  const terminal=r.state.blocks.find(block=>block.kind==='tool');
  h.emit('tool.output',{...payload,text:'迟到内容'});h.emit('tool.started',payload);
  h.emit('tool.completed',{...payload,content:'重复正文'});
  expect(r.state.blocks.find(block=>block.kind==='tool')).toBe(terminal);
  const ui={...newRuntimeUi(),expanded:true};
  for(const columns of [40,80,120])for(const rows of [24,35]) {
    const frame=runtimeFrame(r.state,ui,columns,rows);const shown=frame.bodyRows.map(rowText).join('\n');
    expect(shown).toContain('内容含敏感信息');expect(shown).not.toMatch(/迟到内容|重复正文|错误调用/);
    expect(frame.rows.every(row=>stringWidth(rowText(row))<=columns)).toBe(true);
  }
  r.dispose();
});

it.each(['chat','plan','legacy'])('问卷 %s 只在原工具确认后写入一次历史，拒绝修改不留下旧答案',mode=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();
  r.submit(mode==='plan'?'/plan 样本':'样本');h.emit('run.started');
  h.emit('question.requested',mode==='legacy'?{callId:'q',question:'选择范围',options}
    :{callId:'q',questions:[questions[0]]});
  const pending=r.state.pending!;if(pending.kind!=='questions')throw Error('expected questions');
  const answer=(id:string)=>[{questionId:pending.questions[0]!.id,optionIds:[id],freeText:''}];
  const summaries=()=>r.state.blocks.filter(block=>block.kind==='notice');
  expect(r.answer(pending,answer('a'))).toBe(true);expect(summaries()).toHaveLength(0);
  h.emit('protocol.error',{code:'INVALID_STATE'},mode==='legacy'?'question':'answers');
  expect(r.state.pending).toBe(pending);expect(summaries()).toHaveLength(0);
  expect(r.answer(pending,answer('b'))).toBe(true);expect(summaries()).toHaveLength(0);
  const payload={ordinal:1,callId:'q',toolName:mode==='legacy'?'ask_plan_question':'ask_user_questions',content:'已接收'};
  h.emit('tool.started',payload);
  h.emit('tool.completed',{...payload,callId:'other'});h.emit('tool.completed',{...payload,toolName:'read_file'});
  h.emit('tool.completed',payload,'request','other-run');expect(summaries()).toHaveLength(0);
  h.emit('tool.completed',payload);h.emit('tool.completed',payload);
  expect(summaries()).toHaveLength(1);expect(summaries()[0]).toMatchObject({text:expect.stringContaining('第二个')});
  expect(summaries()[0]).toMatchObject({text:expect.not.stringContaining('第一个')});
  h.emit('protocol.error',{code:'INVALID_STATE'},mode==='legacy'?'question':'answers');expect(r.state.pending).toBeUndefined();
  h.emit('run.completed',{finalText:'最终回答'});expect(summaries()).toHaveLength(1);r.dispose();
});

it.each(['failed','redacted','missing-id','cancelled','disconnected','ended'])('问卷 %s 不把未确认或受保护答案写入历史',outcome=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('样本');h.emit('run.started');
  h.emit('question.requested',{callId:'q',questions:[questions[1],questions[2]]});
  const pending=r.state.pending!;if(pending.kind!=='questions')throw Error('expected questions');
  expect(r.answer(pending,[{questionId:'multi',optionIds:['a','b'],freeText:''},{questionId:'text',optionIds:[],freeText:'独立自由答案'}])).toBe(true);
  const payload={ordinal:1,toolName:'ask_user_questions',callId:'q',contentRedacted:outcome==='redacted'};
  if(outcome==='cancelled')r.cancel();
  if(outcome==='disconnected')h.failed();
  if(outcome==='ended')h.emit('run.completed',{finalText:'未提供工具确认'});
  h.emit(outcome==='failed'?'tool.failed':'tool.completed',{...payload,callId:outcome==='missing-id'?undefined:'q'});
  expect(r.state.blocks.filter(block=>block.kind==='notice')).toHaveLength(0);
  if(outcome==='failed'||outcome==='redacted') {
    h.emit('tool.completed',{...payload,contentRedacted:false});
    expect(r.state.blocks.filter(block=>block.kind==='notice')).toHaveLength(0);
  }
  r.dispose();
});

it('问卷提交被拒绝后保留原复核选择，再次发送须再次确认',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'q',questions:[{id:'q',title:'选择范围',question:'选择样本',multiSelect:false,allowFreeText:true,
    options:[{optionId:'a',label:'第一项',description:''},{optionId:'b',label:'第二项',description:''}]}]});await wait();
  await h.key('2');await h.key('\r');expect(h.client.resolveQuestionnaire).toHaveBeenCalledTimes(1);
  h.emit('protocol.error',{code:'INVALID_STATE'},'answers');await wait();
  expect(h.app.lastFrame()).toContain('核对你的回答');expect(h.app.lastFrame()).toContain('第二项');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledTimes(1);await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledTimes(2);
  expect(h.client.resolveQuestionnaire).toHaveBeenLastCalledWith('q',[{questionId:'q',optionIds:['b'],freeText:''}]);
});

it('明确拒绝恢复原草稿但不覆盖后续草稿，也不自动重发',async()=>{
  for(const following of ['', '后续草稿', '兼容协议错误草稿']) {
    const h=host();const app=render(<ExperienceRuntimeApp client={h.client} workspace="example"/>);await wait();h.initialize();await wait();
    app.stdin.write('原任务😀');await wait();app.stdin.write('\r');await wait();
    if(following){app.stdin.write(following);await wait();}
    if(following==='兼容协议错误草稿') h.emit('protocol.error',{code:'INVALID_STATE'},'request',undefined);
    else h.emit('run.command.result',{disposition:'rejected'},'request',undefined);
    await wait();
    expect(h.client.startRun).toHaveBeenCalledTimes(1);
    app.stdin.write('\r');await wait();
    expect(h.client.startRun).toHaveBeenLastCalledWith(following||'原任务😀');
    app.unmount();
  }
});

it('计划确认被拒绝后恢复同一审核，随后的错误不清空计划或自动执行',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('/plan 样本');h.emit('run.started');
  h.emit('plan.review.requested',{planId:'p',revision:1,markdown:'原计划',contentDigest:'c',workspaceDigest:'w'});h.emit('run.completed');
  const plan=r.state.pending!;if(plan.kind!=='plan')throw Error('expected plan');
  r.review(plan,'APPROVE_USER','');
  h.emit('run.command.result',{disposition:'rejected'},'review',undefined);
  h.emit('protocol.error',{code:'INVALID_STATE'},'review',undefined);
  expect(r.state.pending).toBe(plan);expect(r.state.mode).toBe('plan');expect(r.state.status).toBe('idle');
  expect(h.client.resolvePlanReview).toHaveBeenCalledTimes(1);
  r.review(plan,'APPROVE_USER','');expect(h.client.resolvePlanReview).toHaveBeenCalledTimes(2);
  h.emit('run.command.result',{disposition:'accepted'},'review',undefined);
  h.emit('protocol.error',{code:'INTERNAL_ERROR'},'review',undefined);
  expect(r.state.pending).toBeUndefined();expect(r.state.status).toBe('starting');
  h.emit('run.started',{},'review','execution');h.emit('run.completed',{finalText:'执行结果'},'review','execution');
  expect(r.state.status).toBe('idle');expect(r.state.blocks.some(block=>'text'in block&&block.text==='执行结果')).toBe(true);r.dispose();
});

it('旧审批拒绝不能覆盖新的面板，已接受或取消的启动不能恢复成待重发输入',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('原输入');
  h.emit('run.command.result',{disposition:'accepted'},'request',undefined);h.emit('run.command.result',{disposition:'rejected'},'request',undefined);
  expect(r.state.rejectedInput).toBeUndefined();h.emit('run.started');
  h.emit('approval.requested',{approvalId:'old',ordinal:1,toolName:'run_command'});
  const old=r.state.pending!;if(old.kind!=='approval')throw Error('expected approval');r.approve(old,'allow_once');
  h.emit('approval.requested',{approvalId:'new',ordinal:2,toolName:'run_command'});const newer=r.state.pending;
  h.emit('protocol.error',{code:'INVALID_STATE'},'approval');expect(r.state.pending).toBe(newer);
  r.cancel();h.emit('run.cancelled');r.submit('第二轮');r.cancel();h.emit('run.command.result',{disposition:'rejected'},'request',undefined);
  expect(r.state.rejectedInput).toBeUndefined();expect(r.state.status).toBe('idle');r.dispose();
});

it('计划修改意见在拒绝后仍可编辑，重新提交必须由用户再次确认',async()=>{
  const h=await mount();h.emit('plan.review.requested',{planId:'p',revision:1,markdown:'原计划',contentDigest:'c',workspaceDigest:'w'});h.emit('run.completed');await wait();
  await h.key('2');await h.key('\r');await h.key('保留的修改意见');await h.key('\r');
  h.emit('run.command.result',{disposition:'rejected'},'review',undefined);h.emit('protocol.error',{},'review',undefined);await wait();
  expect(h.app.lastFrame()).toContain('保留的修改意见');expect(h.client.resolvePlanReview).toHaveBeenCalledTimes(1);
  await h.key('\r');expect(h.client.resolvePlanReview).toHaveBeenLastCalledWith(expect.objectContaining({feedback:'保留的修改意见'}));
});

it('宿主取消与断连分开，不能把断连当作副作用已取消的证据',()=>{
  for(const cancel of [true,false]) {
    const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('样本');h.emit('run.started');
    h.emit('tool.started',{ordinal:1,toolName:'run_command'});
    if(cancel) h.emit('run.cancelled',{finalText:'不得显示的取消后正文'});else h.failed();
    const tool=r.state.blocks.find(block=>block.kind==='tool');
    expect(tool?.kind==='tool'&&tool.status).toBe(cancel?'cancelled':'failed');
    expect(r.state.blocks.some(block=>'text'in block&&block.text.includes('不得显示'))).toBe(false);
    if(!cancel) expect(r.state.notice).toContain('无法确认');r.dispose();
  }
});

it('验收未通过仍交付宿主解释正文，诊断不进入普通聊天',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('执行样本');h.emit('run.started');
  h.emit('plan.verification.required',{requiredEvidence:1});
  h.emit('run.completed',{finalText:'实际完成部分与未满足条件的说明'});
  expect(r.state.notice).toContain('尚未通过');
  expect(r.state.blocks.some(block=>'text'in block&&block.text.includes('实际完成部分'))).toBe(true);
  expect(runtimeFrame(r.state,newRuntimeUi(),80,35).bodyRows.map(rowText).join('\n')).not.toContain('运行诊断');
  expect(runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).bodyRows.map(rowText).join('\n')).toContain('plan.verification.required');
  for(const columns of [40,80,120]) for(const height of [24,35]) {
    const frame=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},columns,height);
    expect(frame.rows.length).toBeLessThanOrEqual(height);
    expect(frame.rows.every(row=>stringWidth(rowText(row))<=columns)).toBe(true);
    expect(frame.fixedRows.map(rowText).join('\n')).toContain('Ctrl+O 返回');
  }
  r.dispose();
});

it('无正文结束与缺审核计划明确告知，上一轮正文不能掩盖本轮空结果',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();
  r.submit('第一轮');h.emit('run.started',{},'request','one');h.emit('run.completed',{finalText:'旧回答'},'request','one');
  r.submit('第二轮');h.emit('run.started',{},'request','two');h.emit('run.completed',{},'request','two');
  expect(r.state.notice).toContain('没有返回可显示');
  r.submit('/plan 样本');h.emit('run.started',{},'request','three');h.emit('run.completed',{finalText:'只能提供解释，未生成审核计划'},'request','three');
  expect(r.state.notice).toContain('未生成可审核');expect(r.state.pending).toBeUndefined();
  expect(r.state.blocks.some(block=>'text'in block&&block.text.includes('只能提供解释'))).toBe(true);r.dispose();
});

it('旧事件路由、取消后正文和断连可诊断而不重新激活运行',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('样本');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'run_command'},'other-request','other-run');
  expect(r.state.diagnostics?.at(-1)).toContain('other-request');
  r.cancel();h.emit('run.completed',{finalText:'不得显示的迟到结果'});
  expect(r.state.blocks.some(block=>'text'in block&&block.text.includes('不得显示'))).toBe(false);
  h.failed();expect(r.state.diagnostics?.at(-1)).toContain('transport.failure');
  expect(r.state.connection).toBe('closed');expect(r.state.status).toBe('idle');r.dispose();
});

it('宿主queued不是拒绝，后续工具纠正、计划审核和最终回答仍到达界面',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('独立规划样本');
  h.emit('run.command.result',{disposition:'queued'},'request',undefined);
  expect(r.state.status).toBe('starting');expect(r.state.notice).toBe('');
  h.emit('steering.queued',{},'request',undefined);h.emit('run.started');
  h.emit('tool.failed',{ordinal:1,toolName:'task_create',errorCode:'invalid_arguments'});
  h.emit('tool.completed',{ordinal:2,toolName:'task_create'});
  h.emit('plan.review.requested',{planId:'plan',revision:1,markdown:'独立计划正文',contentDigest:'digest',workspaceDigest:'workspace'});
  h.emit('run.completed',{finalText:'最终交付可见'});
  expect(r.state.pending?.kind).toBe('plan');expect(r.state.status).toBe('idle');
  expect(r.state.blocks.some(block=>'text' in block&&block.text==='最终交付可见')).toBe(true);
  expect(r.state.notice).toBe('');r.dispose();
});

it('排队时取消保留意图，丢弃和真正拒绝各自正常终结且不自动重发',()=>{
  for(const discarded of [false,true]) {
    const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('样本');
    h.emit('run.command.result',{disposition:'queued'},'request',undefined);r.cancel();
    expect(h.client.cancelRun).not.toHaveBeenCalled();
    if(discarded) h.emit('steering.discarded',{},'request',undefined);
    else {h.emit('run.started');expect(h.client.cancelRun).toHaveBeenCalledTimes(1);h.emit('run.cancelled');}
    expect(r.state.status).toBe('idle');expect(h.client.startRun).toHaveBeenCalledTimes(1);r.dispose();
  }
  const h=host();const r=new ExperienceRuntime(h.client,'example');r.connect();h.initialize();r.submit('样本');
  h.emit('run.command.result',{disposition:'rejected'},'request',undefined);
  expect(r.state.status).toBe('idle');expect(r.state.notice).toContain('拒绝启动');r.dispose();
});

it('文件审批在六尺寸展示真实意图片段与摘要，拒显和旧宿主不伪造正文',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('修改');h.emit('run.started');
  const base={approvalId:'file',ordinal:1,toolName:'apply_patch',target:'src/文件.txt',operation:'modify',removedLines:1,addedLines:2};
  h.emit('approval.requested',{...base,fileChange:{status:'available',before:'旧内容',after:'新的中文😀'.repeat(30)+'\n第二行'}});
  expect(r.state.pending?.kind).toBe('approval');
  for(const columns of [40,80,120]) for(const height of [24,35]) {
    const frame=runtimeFrame(r.state,newRuntimeUi(),columns,height);
    const pages=Array.from({length:frame.maxPanelScroll+1},(_,panelScroll)=>runtimeFrame(r.state,{...newRuntimeUi(),panelScroll},columns,height));
    const text=pages.flatMap(page=>page.rows).map(rowText).join('\n');
    expect(text).toContain('旧内容');expect(text).toContain('第二行');expect(text).toContain('移除 1 行');
    expect(pages.flatMap(page=>page.rows).every(row=>stringWidth(rowText(row))<=columns)).toBe(true);
    expect(frame.fixedRows.map(rowText).join('\n')).toContain('允许本次操作');
  }
  for(const status of ['redacted','too_large']) {
    h.emit('approval.requested',{...base,fileChange:{status,before:'DO_NOT_DISPLAY',after:'DO_NOT_DISPLAY'}});
    const text=runtimeFrame(r.state,newRuntimeUi(),80,24).rows.map(rowText).join('\n');
    expect(text).not.toContain('DO_NOT_DISPLAY');expect(text).toContain(status==='redacted'?'未展示正文':'超过预览上限');
  }
  h.emit('approval.requested',base);
  expect(runtimeFrame(r.state,newRuntimeUi(),80,24).rows.map(rowText).join('\n')).toContain('宿主未提供变更正文');
  r.cancel();expect(r.state.pending).toBeUndefined();r.dispose();
});

it('上下移动复用视觉列，跨短行后恢复目标列且不拆emoji',()=>{
  const original={text:'中文😀ab\nx\n中文😀cd',cursor:5};
  const middle=moveDraftVertical(original,'down',20);
  expect(middle.cursor).toBe(7);
  expect(moveDraftVertical(middle,'down',20).cursor).toBe(13);
  const wrapped={text:'中文😀abcdef',cursor:9};
  const up=moveDraftVertical(wrapped,'up',8);
  expect(up.cursor).toBe(2);
  expect(moveDraftVertical(up,'down',8).cursor).toBe(9);
});
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
it('最大问卷在六种尺寸保留当前题、聚焦选项、编辑框与验证提示',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('问卷');h.emit('run.started');
  h.emit('question.requested',{callId:'q',questions:Array.from({length:4},(_,n)=>({id:n?'q'+n:'q',title:'范围'.repeat(60),question:'请选择范围',multiSelect:true,allowFreeText:true,
    options:Array.from({length:8},(_,i)=>({optionId:'o'+i,label:'选项'+i,description:'完整说明'.repeat(20)}))}))});
  r.patch({notice:'回答最多 2000 字符，请缩短后保存。'});
  for(const width of [40,80,120]) for(const height of [24,35]) {
    const ui={...newRuntimeUi(),focus:8,editing:true,free:{q:{text:'第一行\n第二行\n第三行',cursor:11}}};
    const frame=runtimeFrame(r.state,ui,width,runtimeViewportHeight(height));const text=frame.rows.map(rowText).join('\n');
    expect(text).toContain('请选择范围');expect(text).toContain('❯ 9.');expect(text).toContain('自行填写');
    expect(text).toContain('第三行');expect(text).toContain('请缩短后保存');expect(text).toContain('Enter 保存');
    expect(frame.rows.length).toBeLessThanOrEqual(height-2);
  }
});
async function mount() {
  const h = host(); const app = render(<ExperienceRuntimeApp client={h.client} workspace="G:\\example"/>);
  await wait(); h.initialize(); await wait();
  const key = async (s: string) => {app.stdin.write(s); await wait();};
  await key('任务'); await key('\r'); h.emit('run.started', {requestModel: 'configured-model'}); await wait();
  return {...h, app, key};
}
it('详情固定记录范围，新消息不挤动阅读，End刷新且提交下一轮不隐藏新结果',async()=>{
  const h=await mount();h.emit('model.turn.started',{turn:1});
  h.emit('model.text.delta',{text:'正在阅读的旧记录'});await wait();await h.key('\x0f');
  h.emit('model.turn.started',{turn:2});h.emit('model.text.delta',{text:'新回合记录'});await wait();await wait();
  expect(h.app.lastFrame()).toContain('正在阅读的旧记录');expect(h.app.lastFrame()).not.toContain('新回合记录');
  await h.key('\x1b[F');expect(h.app.lastFrame()).toContain('新回合记录');
  h.emit('run.completed');await wait();await h.key('下一轮');await h.key('\r');
  h.emit('run.started',{},'request','next-run');h.emit('model.turn.started',{turn:1},'request','next-run');
  h.emit('run.completed',{finalText:'下一轮结果'},'request','next-run');await wait();
  expect(h.app.lastFrame()).toContain('下一轮结果');
});
it('断连清除审批并立即说明停止，不显示仍在等待连接或发送旧决定',async()=>{
  const h=await mount();h.emit('tool.started',{ordinal:1,toolName:'run_command'});
  h.emit('approval.requested',{approvalId:'a',ordinal:1,toolName:'run_command',command:'echo ok'});await wait();
  h.failed();await wait();
  expect(h.app.lastFrame()).toContain('连接已断开');expect(h.app.lastFrame()).not.toContain('等待连接');
  expect(h.app.lastFrame()).not.toContain('允许本次操作');await h.key('\r');expect(h.client.resolveApproval).not.toHaveBeenCalled();
});
it('问卷窗口随焦点移动，切换说明回到开头，缩放与返回复核不丢答案',async()=>{
  size.columns=40;size.rows=24;
  const h=await mount();
  h.emit('question.requested',{callId:'batch',questions:[{id:'many',title:'选项',question:'选择结果内容',multiSelect:true,allowFreeText:true,
    options:Array.from({length:8},(_,i)=>({optionId:'o'+i,label:'内容'+i,description:'说明开头'+i+'\n'+Array.from({length:30},(_,j)=>'说明行'+j).join('\n')}))}]});await wait();
  await h.key('\r');expect(h.app.lastFrame()).toContain('请至少选择一项');
  await h.key(' ');expect(h.app.lastFrame()).not.toContain('请至少选择一项');
  await h.key('\x1b[6~');await h.key('\x1b[B');expect(h.app.lastFrame()).toContain('说明开头1');
  for(let i=0;i<6;i++) await h.key('\x1b[B');expect(h.app.lastFrame()).toContain('❯ 8.');
  await h.key(' ');await h.key('\x1b[B');await h.key('\r');await h.key('补充中文😀');await h.key('\r');
  size.columns=120;size.rows=35;await h.key('\t');
  expect(h.app.lastFrame()).toContain('内容0、内容7、补充中文😀');
  await h.key('\x1b[Z');size.columns=40;size.rows=24;await h.key('\t');await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledTimes(1);
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledWith('batch',[{questionId:'many',optionIds:['o0','o7'],freeText:'补充中文😀'}]);
});
it('流式链接完成后保留来源和产物，展开返回后提交原草稿',async()=>{
  const h=await mount();
  h.emit('model.turn.started',{turn:1});
  h.emit('model.text.delta',{text:'交付：[报告](<G:/AI Cloud/'});await wait();
  h.emit('model.text.delta',{text:'report.md>)\n\n来源：[参考](https://example.com/source)'});
  h.emit('run.completed');await wait();
  const output=h.app.lastFrame()??'';
  expect(output).toContain('G:/AI Cloud/report.md');expect(output).toContain('https://example.com/source');
  await h.key('检查交付');await h.key('\x0f');await h.key('\x0f');await h.key('\r');
  expect(h.client.startRun).toHaveBeenLastCalledWith('检查交付');
});
it('长会话普通输入和展开阅读不因草稿编辑重解析已结束回答',async()=>{
  const h=await mount();
  for(let turn=1;turn<=16;turn++) {
    h.emit('model.turn.started',{turn});h.emit('model.text.delta',{text:`挂载长历史${turn}\n\n**正文** 中文内容${turn}`});
  }
  h.emit('run.completed');await wait();
  const lexer=vi.spyOn(marked,'lexer');
  try {
    await h.key('下一轮');await h.key('😀');await h.key('\x1b[D');
    expect(lexer.mock.calls.length).toBe(0);
    await h.key('\x0f');lexer.mockClear();
    await h.key('继续');await h.key('\x1b[5~');await h.key('\x1b[6~');
    expect(lexer.mock.calls.length).toBe(0);
    await h.key('\x0f');expect(h.app.lastFrame()).toContain('下一轮继续😀');
  } finally {lexer.mockRestore();}
});
it('历史内容只移动光标后仍能向下恢复原草稿',async()=>{
  const h=await mount();h.emit('run.completed');await wait();
  await h.key('原草稿😀');await h.key('\x1b[A');await h.key('\x1b[D');await h.key('\x1b[C');await h.key('\x1b[B');
  await h.key('\r');expect(h.client.startRun).toHaveBeenLastCalledWith('原草稿😀');
});
it('Ctrl和Alt按词移动与旧编辑器一致，正常提交中英文emoji原文',async()=>{
  const h=await mount();h.emit('run.completed');await wait();
  await h.key('alpha 中文 😀 omega');await h.key('\x1b[1;5D');await h.key('new ');
  await h.key('\x1b[1;3C');await h.key('!');await h.key('\r');
  expect(h.client.startRun).toHaveBeenLastCalledWith('alpha 中文 😀 new omega!');
});
it('返回单选题定位已选项，重新确认不意外改成第一项',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'call',questions:[questions[0],questions[2]]});await wait();
  await h.key('2');await h.key('\x1b[Z');
  expect(h.app.lastFrame()).toContain('❯ 2. 第二个');
  await h.key('\r');await h.key('\r');await h.key('补充');await h.key('\r');await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledWith('call',[
    {questionId:'single',optionIds:['b'],freeText:''},{questionId:'text',optionIds:[],freeText:'补充'}]);
});
it('多选保留选项时可以清除已保存的自由回答',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'call',questions:[questions[1]]});await wait();
  await h.key('1');await h.key('3');await h.key('旧说明');await h.key('\r');
  await h.key('3');await h.key('\x15');await h.key('\r');await h.key('\r');await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledWith('call',[{questionId:'multi',optionIds:['a'],freeText:''}]);
});
it('未保存自由回答切回仍保留草稿，缺必答不能整批提交',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'call',questions:[questions[2]]});await wait();
  await h.key('\r');await h.key('未保存😀');await h.key('\x1b');await h.key('\t');await h.key('\r');
  expect(h.client.resolveQuestionnaire).not.toHaveBeenCalled();
  await h.key('\r');expect(h.app.lastFrame()).toContain('未保存😀');
  await h.key('\r');await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledExactlyOnceWith('call',[{questionId:'text',optionIds:[],freeText:'未保存😀'}]);
});
it('新问卷请求不继承旧题焦点或答案',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'old',questions:[questions[0],questions[2]]});await wait();
  await h.key('2');await h.key('\x1b');h.emit('run.cancelled');await wait();
  await h.key('下一任务');await h.key('\r');h.emit('run.started',{},'request','new-run');
  h.emit('question.requested',{callId:'new',questions:[questions[0]]},'request','new-run');await wait();
  expect(h.app.lastFrame()).toContain('❯ 1. 第一个');
  await h.key('\t');await h.key('\r');expect(h.client.resolveQuestionnaire).not.toHaveBeenCalled();
});
it('最小终端审批翻到末页能读到完整目录，选项仍可确认',async()=>{
  const h=await mount();
  h.emit('approval.requested',{approvalId:'gate',ordinal:1,toolName:'run_command',shell:'PowerShell',workingDirectory:'LAST-DIRECTORY-END',command:Array.from({length:45},(_,i)=>'command-line-'+i).join('\n'),sessionScope:true});await wait();
  for(let i=0;i<6;i++) await h.key('\x1b[6~');
  expect(h.app.lastFrame()).toContain('LAST-DIRECTORY-END');
  expect(h.app.lastFrame()).toContain('允许本次操作');
  await h.key('3');await h.key('\r');expect(h.client.resolveApproval).toHaveBeenCalledWith('gate','deny');
});
it('六尺寸长审批和展开输出首尾可达，保留焦点与输入',()=>{
  const h=host();const runtime=new ExperienceRuntime(h.client,'G:\\example');runtime.connect();h.initialize();runtime.submit('检查');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'run_command',shell:'powershell',command:'COMMAND-FIRST\n'+Array.from({length:40},(_,i)=>'中文命令-'+i).join('\n')+'\nCOMMAND-LAST'});
  h.emit('tool.output',{ordinal:1,text:'OUTPUT-FIRST\n'+Array.from({length:40},(_,i)=>'中文输出-'+i).join('\n')+'\nOUTPUT-LAST'});
  for(const columns of [40,80,120]) for(const rows of [24,35]) {
    const ui={...newRuntimeUi(),expanded:true,draft:{text:'保留😀',cursor:3}};
    const frame=runtimeFrame(runtime.state,ui,columns,runtimeViewportHeight(rows));
    const atTop=runtimeFrame(runtime.state,{...ui,scroll:frame.maxScroll},columns,runtimeViewportHeight(rows));
    expect(atTop.rows.map(rowText).join('\n')).toContain('配置模型');
    expect(frame.rows.map(rowText).join('\n')).toContain('OUTPUT-LAST');
    expect(frame.rows.map(rowText).join('\n')).toContain('保留😀');
    h.emit('approval.requested',{approvalId:'gate',ordinal:1,toolName:'run_command',shell:'PowerShell',workingDirectory:'DIRECTORY-LAST',command:'COMMAND-FIRST\n'+Array.from({length:40},()=> '长命令中文').join('\n')+'\nCOMMAND-LAST',sessionScope:true});
    const first=runtimeFrame(runtime.state,{...ui,focus:2},columns,runtimeViewportHeight(rows));
    const last=runtimeFrame(runtime.state,{...ui,focus:2,panelScroll:first.maxPanelScroll},columns,runtimeViewportHeight(rows));
    expect(first.rows.map(rowText).join('\n')).toContain('PowerShell');
    expect(last.rows.map(rowText).join('\n')).toContain('DIRECTORY-LAST');
    expect(last.rows.map(rowText).join('\n')).toContain('❯ 3. 拒绝本次操作');
    runtime.patch({pending:undefined});
  }
  runtime.dispose();
});
it('Tab补全计划只修改草稿，补充任务并Enter才发起计划',async()=>{
  const h=await mount();h.emit('run.completed');await wait();
  await h.key('/pla');await h.key('\t');
  expect(h.app.lastFrame()).toContain('❯ /plan');
  expect(h.client.startPlan).not.toHaveBeenCalled();
  await h.key('检查目录');await h.key('\r');
  expect(h.client.startPlan).toHaveBeenCalledExactlyOnceWith('检查目录', {verificationCorrection:true});
});
it('多候选按选中项补全，运行中Enter仍不排队',async()=>{
  const h=await mount();await h.key('/');await h.key('\x1b[B');await h.key('\t');
  expect(h.app.lastFrame()).toContain('❯ /help');
  await h.key('\x15');await h.key('/pla');await h.key('\t');await h.key('待办');await h.key('\r');
  expect(h.client.startPlan).not.toHaveBeenCalled();
  expect(h.app.lastFrame()).toContain('/plan 待办');
  h.emit('run.completed');await wait();await h.key('\r');
  expect(h.client.startPlan).toHaveBeenCalledExactlyOnceWith('待办', {verificationCorrection:true});
});
it('多行草稿先上下移动，到边界才取历史并能恢复原稿',async()=>{
  const h=await mount();h.emit('run.completed');await wait();
  await h.key('甲乙\n丙丁');await h.key('\x1b[A');
  await h.key('\x1b[A');expect(h.app.lastFrame()).toContain('❯ 任务');
  await h.key('\x1b[B');await h.key('改');await h.key('\r');
  expect(h.client.startRun).toHaveBeenLastCalledWith('甲乙改\n丙丁');
});
it('运行中软折行箭头编辑草稿，不取历史或提交下一轮',async()=>{
  const h=await mount();size.columns=40;
  await h.key('a'.repeat(40));await h.key('\x1b[A');await h.key('中');
  h.emit('run.completed');await wait();await h.key('\r');
  expect(h.client.startRun).toHaveBeenLastCalledWith('aa中'+'a'.repeat(38));
});
it('问卷自由回答上下移动后，复核提交真实修改内容',async()=>{
  const h=await mount();h.emit('question.requested',{callId:'call',questions:[questions[2]]});await wait();
  await h.key('\r');await h.key('甲乙\n丙丁');await h.key('\x1b[A');await h.key('改');
  await h.key('\r');await h.key('\r');
  expect(h.client.resolveQuestionnaire).toHaveBeenCalledWith('call',[{questionId:'text',optionIds:[],freeText:'甲乙改\n丙丁'}]);
});
it('计划反馈的上下键编辑文字，不触发审核选项',async()=>{
  const h=await mount();
  h.emit('plan.review.requested',{planId:'p',revision:1,contentDigest:'c',workspaceDigest:'w',markdown:'# 计划'});
  expect(h.app.lastFrame()).not.toContain('审核条件已补齐');
  h.emit('run.completed');await wait();
  await h.key('2');await h.key('\r');await h.key('甲乙\n丙丁');await h.key('\x1b[A');await h.key('改');
  expect(h.client.resolvePlanReview).not.toHaveBeenCalled();
  await h.key('\r');
  expect(h.client.resolvePlanReview).toHaveBeenCalledWith(expect.objectContaining({feedback:'甲乙改\n丙丁',decision:'CONTINUE_PLANNING'}));
});
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
  await h.key('\x0f');await h.key('\x0f');
  expect(h.app.lastFrame()).toContain('❯ 2. 本会话允许此范围');
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
    expect(visible).toContain('搜索内容 · 2 次调用');
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
  expect(visible.match(/搜索内容 · 2 次调用/g)).toHaveLength(1);
  expect(visible).toContain('工具参数无效');
  expect(visible).toContain('later');
  visible=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).rows.map(rowText).join('\n');
  expect(visible).not.toContain('搜索内容 · 2 次调用');
  expect(visible.match(/搜索内容/g)).toHaveLength(4);
});

it('参数校验失败保留同一工具记录，活动运行提示可修正，终态不猜测是否已修正',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('查询');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'search_text',parametersPreview:'bad'});
  h.emit('tool.failed',{ordinal:1,toolName:'search_text',errorCode:'invalid_arguments',argumentChangeRequired:true,retryable:true,failureCategory:'validation'});
  expect(r.state.blocks.find(block=>block.kind==='tool')).toMatchObject({
    argumentChangeRequired:true,retryable:true,failureCategory:'validation',status:'failed',
  });
  let visible=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\\n');
  expect(visible).toContain('参数无效，已返回模型修正');
  h.emit('tool.started',{ordinal:2,toolName:'search_text',parametersPreview:'corrected'});
  h.emit('tool.completed',{ordinal:2,toolName:'search_text',content:'ok'});
  h.emit('run.completed',{stopReason:'completed',finalText:'查询完成'});
  visible=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\\n');
  expect(visible).toContain('工具参数无效');
  expect(visible).not.toContain('模型未完成修正');
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
  expect(visible).toContain('计划内部步骤未能完成');
  expect(visible).not.toContain('记录验证要求');
  expect(visible).not.toContain('已修正验证方式');

  h.emit('tool.started',{ordinal:4,toolName:'declare_plan_evidence'});
  h.emit('tool.completed',{ordinal:4,toolName:'declare_plan_evidence',recoveredFailureOrdinal:2});
  visible=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).rows.map(rowText).join('\n');
  expect(visible).toContain('审核条件已补齐，计划已提交审核');
  expect(visible).not.toContain('验证方式使用了当前不可用的工具');
  expect(visible).not.toContain('记录验证要求');
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
  expect(visible).toContain('计划内部步骤未能完成');
  expect(visible).not.toContain('记录验证要求');
});

it('正常计划编排不刷屏，展开可核对真实参数和结果；失败仍可见',()=>{
  for(const name of ['task_list','task_create','task_get','task_update','revise_plan_artifact','request_plan_review','declare_plan_evidence']) {
    const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('/plan 示例');h.emit('run.started');
    h.emit('tool.started',{ordinal:1,toolName:name,parametersPreview:'internal-plan-id'});
    h.emit('tool.completed',{ordinal:1,toolName:name,content:'{"board_revision":1,"task":{"id":"task-1"}}'});
    for(const expanded of [false]) {
      const text=runtimeFrame(r.state,{...newRuntimeUi(),expanded},80,35).rows.map(rowText).join('\n');
      expect(text).not.toContain('board_revision');expect(text).not.toContain('internal-plan-id');expect(text).not.toContain('task-1');
      expect(text).not.toContain('完成');expect(text).not.toContain('查看任务清单');
    }
    const details=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).bodyRows.map(rowText).join('\n');
    expect(details).not.toContain('board_revision');expect(details).not.toContain('internal-plan-id');
    h.emit('tool.failed',{ordinal:2,toolName:name,errorCode:'invalid_arguments'});
    const failed=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
    expect(failed).not.toContain('工具参数无效');
  }
});

it('命令与网页搜索折叠摘要不冒用协议头，展开保留真实输出',()=>{
  for(const [name,output,summary] of [
    ['run_command','shell: powershell\nbackend: local\nenforcement: local\nfallback: false\nworkingDirectory: .\nexitCode: 0\ntimedOut: false\ncancelled: false\nstdout:\nquery-result\nstderr:','命令执行完成'],
    ['web_search','provenance: external-web-search\nquery-result','网页搜索完成'],
  ] as const) {
    const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('查询');h.emit('run.started');
    h.emit('tool.started',{ordinal:1,toolName:name});
    h.emit('tool.completed',{ordinal:1,toolName:name,content:output});
    const collapsed=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
    expect(collapsed).toContain(summary);expect(collapsed).not.toContain(output.split('\n')[0]);if(name==='run_command')expect(collapsed).not.toContain('stderr:');
    const expanded=runtimeFrame(r.state,{...newRuntimeUi(),expanded:true},80,35).rows.map(rowText).join('\n');
    expect(expanded).toContain(output.split('\n')[0]);expect(expanded).toContain('query-result');
    h.emit('tool.failed',{ordinal:2,toolName:name,errorCode:'invalid_arguments'});
    const failed=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
    expect(failed).toContain(summary);expect(failed).toContain('工具参数无效');
  }
});

it('搜索统计只来自已成功调用，取消不混入分组且截断始终可见',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('搜索');h.emit('run.started');
  for(const ordinal of [1,2,3,4]) {
    h.emit('tool.started',{ordinal,toolName:'search_text',parametersPreview:'query-'+ordinal});
    if(ordinal===3) h.emit('tool.failed',{ordinal,toolName:'search_text',errorCode:'operation_cancelled'});
    else h.emit('tool.completed',{ordinal,toolName:'search_text',returnedItems:ordinal===4?undefined:ordinal,content:'不能推断为99项',truncated:ordinal===2});
  }
  const text=runtimeFrame(r.state,newRuntimeUi(),120,35).rows.map(rowText).join('\n');
  expect(text).toContain('2 次调用');expect(text).not.toContain('3 次调用');
  expect(text).toContain('返回 3 项结果');expect(text).toContain('已取消');
  expect(text).toContain('搜索完成');expect(text).not.toContain('返回 0 项');expect(text).not.toContain('99项');
  expect(text).toContain('输出已截断');expect(text).toContain('query-2');
});

it('运行命令原位显示末尾输出，完成预览不挤走草稿，脱敏覆盖先前增量',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('命令');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'run_command',command:'Write-Output 测试',shell:'powershell'});
  h.emit('tool.output',{ordinal:1,toolName:'run_command',stream:'stdout',text:Array.from({length:20},(_,i)=>'输出行'+i).join('\n')});
  expect(r.state.activity).toBe('正在执行命令');
  const ui={...newRuntimeUi(),draft:{text:'下一轮中文😀',cursor:7}};
  for(const width of [40,80,120]) for(const height of [24,35]) {
    const frame=runtimeFrame(r.state,ui,width,height);const text=frame.rows.map(rowText).join('\n');
    expect(text).toContain('输出行19');expect(text).not.toContain('输出行0\n');expect(text).toContain('更多输出');
    expect(text).toContain('下一轮中文😀');expect(frame.rows.length).toBeLessThanOrEqual(height);
    expect(frame.rows.every(row=>stringWidth(rowText(row))<=width)).toBe(true);
  }
  h.emit('tool.failed',{ordinal:1,toolName:'run_command',errorCode:'execution_failed',exitCode:7,content:'stderr: 测试失败'});
  let text=runtimeFrame(r.state,ui,80,35).rows.map(rowText).join('\n');
  expect(text).toContain('退出码 7');expect(text).toContain('测试失败');expect(text).not.toContain('命令执行完成');
  h.emit('tool.started',{ordinal:2,toolName:'run_command'});
  h.emit('tool.output',{ordinal:2,text:'待遮蔽的增量'});
  h.emit('tool.failed',{ordinal:2,toolName:'run_command',errorCode:'execution_failed',contentRedacted:true});
  text=runtimeFrame({...r.state,blocks:r.state.blocks.slice(-1)},{...ui,expanded:true},80,35).rows.map(rowText).join('\n');
  expect(text).toContain('内容含敏感信息');expect(text).not.toContain('测试失败');expect(text).not.toContain('输出行19');
  expect(text).not.toContain('待遮蔽的增量');
});

it('当前命令审批前显示等待审批，不以命令描述冒充已执行',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('命令');h.emit('run.started');
  h.emit('tool.started',{ordinal:1,toolName:'run_command',command:'Write-Output 42',activity:'不应重复的命令描述'});
  h.emit('approval.requested',{approvalId:'gate',ordinal:1,toolName:'run_command',shell:'powershell',workingDirectory:'.',command:'Write-Output 42'});
  const text=runtimeFrame(r.state,newRuntimeUi(),80,35).rows.map(rowText).join('\n');
  expect(text).toContain('等待审批');expect(text).not.toContain('不应重复的命令描述');
});

it('详情视图重新打开恢复阅读位置，主界面不再强制分页',async()=>{
  const h=await mount();
  h.emit('model.text.delta',{text:Array.from({length:60},(_,i)=>'历史段落'+i).join('\n')});await wait();
  await h.key('保留草稿');await h.key('\x0f');await h.key('\x1b[5~');await h.key('\x1b[5~');
  const before=h.app.lastFrame()!;const anchor=before.match(/历史段落\d+/)?.[0];expect(anchor).toBeTruthy();
  await h.key('\x0f');
  h.emit('model.text.delta',{text:'\n追加的新内容'});await wait();
  await h.key('\x0f');
  expect(h.app.lastFrame()).toContain(anchor);expect(h.app.lastFrame()).not.toContain('PgUp 回看');expect(h.app.lastFrame()).toContain('保留草稿');
  await h.key('\x1b[F');expect(h.app.lastFrame()).toContain('追加的新内容');
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

it('原生历史接收完整终态回答，下一轮编辑不重印已封存正文',async()=>{
  const h=await mount();
  h.emit('model.text.delta',{text:'可变草稿'});await wait();
  const before=h.app.frames.length;
  h.emit('run.completed',{stopReason:'completed',finalText:'原生历史首行\n'+Array.from({length:60},(_,i)=>'稳定正文'+i).join('\n')+'\n原生历史末行'});await wait();
  const output=h.app.frames.slice(before).join('\n');
  expect(output).toContain('原生历史首行');expect(output).toContain('原生历史末行');
  expect(output).not.toContain('可变草稿');expect(output).not.toContain('PgUp 回看');
  const sealed=h.app.frames.length;await h.key('下一轮草稿');await h.key('继续编辑');
  // ink-testing-library 的 debug 快照包含累计 Static 历史，每帧只应出现一份。
  for (const frame of h.app.frames.slice(sealed)) expect(frame.split('原生历史首行')).toHaveLength(2);
  expect(h.app.lastFrame()).toContain('下一轮草稿继续编辑');
});

it('原生封存等待工具分组边界和真实审核恢复，不冻结活动消息',()=>{
  const h=host();const r=new ExperienceRuntime(h.client,'G:\\example');r.connect();h.initialize();r.submit('查询');h.emit('run.started');
  h.emit('model.text.delta',{text:'规划中'});
  expect(stableHistoryLength(r.state)).toBe(1);
  for(const ordinal of [1,2]) {
    h.emit('tool.started',{ordinal,toolName:'search_text'});h.emit('tool.completed',{ordinal,toolName:'search_text',returnedItems:1});
  }
  expect(stableHistoryLength(r.state)).toBe(1);
  h.emit('model.turn.started',{turn:1});h.emit('model.text.delta',{text:'后续回合'});
  expect(stableHistoryLength(r.state)).toBe(4);
  h.emit('tool.started',{ordinal:3,toolName:'request_plan_review'});
  h.emit('tool.failed',{ordinal:3,toolName:'request_plan_review',errorCode:'plan_gate_blocked'});
  h.emit('tool.started',{ordinal:4,toolName:'read_file'});
  expect(stableHistoryLength(r.state)).toBe(4);
  h.emit('run.completed');expect(stableHistoryLength(r.state)).toBe(r.state.blocks.length);
});

it('非debug终端输出只追加稳定正文，编辑不重印且不清空历史',async()=>{
  const h=host();const runtime=new ExperienceRuntime(h.client,'G:\\example');
  runtime.connect();h.initialize();runtime.submit('查询');h.emit('run.started',{requestModel:'configured-model'});
  let output='';
  const stdout=Object.assign(new EventEmitter(),{columns:80,rows:24,isTTY:true,write:(text:string)=>{output+=text;return true;}});
  const view=(draft='')=><NativeHistoryScreen state={runtime.state} ui={{...newRuntimeUi(),draft:{text:draft,cursor:draft.length}}} columns={80} rows={24} now={Date.now()}/>;
  // 独立于ink-testing-library的累计debug快照，验证实际渲染器写出的字节。
  const app=renderTerminal(view(),{stdout:stdout as NodeJS.WriteStream,debug:false,patchConsole:false,exitOnCtrlC:false,isScreenReaderEnabled:false});
  try {
    await wait();
    h.emit('run.completed',{finalText:Array.from({length:60},(_,i)=>`SEALED-${i}`).join('\n')});
    app.rerender(view());await wait();await wait();
    expect(output).toContain('SEALED-0');expect(output).toContain('SEALED-59');
    expect(output).not.toContain('\x1b[3J');expect(output).not.toContain('\x1b[?1049h');
    output='';app.rerender(view('下一轮'));await wait();await wait();
    expect(output).toContain('下一轮');expect(output).not.toContain('SEALED-');
  } finally {app.unmount();app.cleanup();runtime.dispose();}
});
