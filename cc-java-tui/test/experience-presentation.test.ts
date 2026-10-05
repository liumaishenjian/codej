import {afterEach, expect, it, vi} from 'vitest';
import {RuntimePresentation} from '../src/experience/presentation.js';
import {ExperienceRuntime, type RuntimeClient} from '../src/experience/runtime.js';
import type {ProtocolEvent} from '../src/protocol.js';

afterEach(()=>vi.useRealTimers());
function harness() {
  vi.useFakeTimers();
  let receive=(_event:ProtocolEvent)=>{};let disconnected=(_message:string)=>{};let sequence=0;
  const client={initialize:()=> 'init',onEvent:(fn:typeof receive)=>{receive=fn;return()=>{};},onFailure:(fn:typeof disconnected)=>{disconnected=fn;return()=>{};},
    startRun:()=> 'req',startPlan:()=> 'req',cancelRun:()=> 'cancel',resolveApproval:()=> 'approve',resolveQuestion:()=> 'answer',resolvePlanReview:()=> 'review',shutdown:async()=>{}} satisfies RuntimeClient;
  const runtime=new ExperienceRuntime(client,'.');runtime.connect();
  const emit=(type:string,payload={},requestId='req')=>receive({version:1,type,payload,requestId,sessionId:'s',runId:'r',sequence:++sequence} as ProtocolEvent);
  emit('initialized',{model:'fake'},'init');runtime.submit('test');emit('run.started');emit('model.turn.started',{turn:1});
  const view=new RuntimePresentation(runtime);const publish=vi.fn();const stop=view.subscribe(publish);
  return {runtime,view,publish,emit,stop,disconnected};
}
it('200个密集增量完整同步入库，只发布一个视图帧，慢流不被无限推迟',()=>{
  const h=harness();const immediate=vi.fn();h.runtime.subscribe(immediate);
  for(let i=0;i<200;i++) h.emit('model.text.delta',{text:'中文😀'+i+'\n'});
  expect(immediate).toHaveBeenCalledTimes(200);expect(h.publish).not.toHaveBeenCalled();
  expect(h.runtime.state.blocks.at(-1)).toMatchObject({text:Array.from({length:200},(_,i)=>'中文😀'+i+'\n').join('')});
  vi.advanceTimersByTime(50);expect(h.publish).toHaveBeenCalledTimes(1);expect(h.view.snapshot()).toBe(h.runtime.state);
  for(let i=0;i<5;i++) {h.emit('model.text.delta',{text:'slow'});vi.advanceTimersByTime(50);}
  expect(h.publish).toHaveBeenCalledTimes(6);h.stop();
});
it('终态修正立即取代待发正文，取消和迟到事件不能复活任务',()=>{
  const h=harness();h.emit('model.text.delta',{text:'临时正文'});h.emit('run.completed',{finalText:'最终正文'});
  expect(h.view.snapshot()).toMatchObject({status:'idle'});expect(h.view.snapshot().blocks.at(-1)).toMatchObject({text:'最终正文'});
  const calls=h.publish.mock.calls.length;vi.advanceTimersByTime(100);h.emit('model.text.delta',{text:'迟到'});
  expect(h.publish).toHaveBeenCalledTimes(calls);h.stop();
  const cancelled=harness();cancelled.emit('model.text.delta',{text:'部分正文'});cancelled.runtime.cancel();
  expect(cancelled.view.snapshot().status).toBe('cancelling');cancelled.emit('model.text.delta',{text:'不应出现'});cancelled.emit('run.cancelled');
  expect(cancelled.view.snapshot().status).toBe('idle');expect(JSON.stringify(cancelled.view.snapshot())).not.toContain('不应出现');
  vi.advanceTimersByTime(100);expect(cancelled.view.snapshot().status).toBe('idle');cancelled.stop();
});
it('工具审批、脱敏和断连立即发布，卸载清理旧帧',()=>{
  const h=harness();h.emit('tool.started',{ordinal:1,toolName:'run_command'});
  h.publish.mockClear();h.emit('tool.output',{ordinal:1,text:'原始样本'});expect(h.publish).not.toHaveBeenCalled();
  h.emit('approval.requested',{ordinal:1,approvalId:'a',toolName:'run_command',command:'echo ok'});
  expect(h.view.snapshot().pending?.kind).toBe('approval');
  h.emit('tool.completed',{ordinal:1,contentRedacted:true});expect(JSON.stringify(h.view.snapshot())).not.toContain('原始样本');
  h.emit('model.text.delta',{text:'待发'});h.disconnected('disconnected');expect(h.view.snapshot().connection).toBe('closed');
  const calls=h.publish.mock.calls.length;vi.advanceTimersByTime(100);expect(h.publish).toHaveBeenCalledTimes(calls);h.stop();
  const closed=harness();closed.emit('model.text.delta',{text:'待卸载'});closed.stop();vi.advanceTimersByTime(100);expect(closed.publish).not.toHaveBeenCalled();
});
