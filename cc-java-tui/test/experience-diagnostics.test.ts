import {expect,it} from 'vitest';
import {DeliveryDiagnostics} from '../src/experience/diagnostics.js';
import type {ProtocolEvent} from '../src/protocol.js';

it('诊断拒绝所有自由文本和身份，保留受控故障码并聚合高频输出',()=>{
  const journal=new DeliveryDiagnostics();
  const event={version:1,type:'tool.output',sequence:1,requestId:'PRIVATE-ID',payload:{text:'PRIVATE-TEXT',errorCode:'PRIVATE-ERROR'}} as ProtocolEvent;
  let rows:readonly string[]=[];
  for(let i=0;i<1000;i++) rows=journal.event(event,'running','current');
  expect(rows).toHaveLength(1);expect(rows[0]).toContain('×1000');expect(JSON.stringify(rows)).not.toContain('PRIVATE');
  rows=journal.event({...event,type:'PRIVATE-TYPE' as ProtocolEvent['type'],payload:{code:'PRIVATE-CODE'}},'idle','other-request');
  expect(rows.at(-1)).toContain('other-event');expect(JSON.stringify(rows)).not.toContain('PRIVATE');
  for(let i=0;i<100;i++) rows=journal.event({...event,type:i%2?'run.started':'run.failed',payload:{stopReason:'timeout'}},'running','current');
  expect(rows).toHaveLength(64);expect(rows.at(-1)).toContain('timeout');expect(journal.transport().at(-1)).toContain('transport.failure');
});
