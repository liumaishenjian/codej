import type {ProtocolEvent} from '../protocol.js';

const kinds = new Set(['initialized','protocol.error','run.command.result','run.launch.failed','run.started',
  'run.completed','run.failed','run.cancelled','steering.queued','steering.discarded','model.turn.started',
  'model.retry.scheduled','model.text.delta','tool.started','tool.output','tool.completed','tool.failed',
  'approval.requested','question.requested','plan.review.requested','plan.review.rejected',
  'plan.verification.required','plan.verification.completed','plan.execution.failed']);
const codes = new Set(['accepted','queued','rejected','completed','cancelled','invalid_arguments',
  'invalid_model_response','permission_denied','timeout','plan_gate_blocked','INVALID_STATE',
  'INVALID_PAYLOAD','INTERNAL_ERROR','STEERING_QUEUE_FULL']);
export type EventRoute = 'current' | 'old-sequence' | 'other-session' | 'other-request' | 'other-run' | 'closed';

/** 只保存封闭词汇；不接受原始ID、正文或自由错误信息，重复流式事件聚合。 */
export class DeliveryDiagnostics {
  #rows: {text:string; count:number}[]=[];
  event(event: ProtocolEvent, phase: 'idle'|'starting'|'running'|'cancelling', route: EventRoute): readonly string[] {
    const kind=kinds.has(event.type)?event.type:'other-event';
    const raw=event.payload.disposition??event.payload.errorCode??event.payload.stopReason??event.payload.code;
    const code=typeof raw==='string'&&codes.has(raw)?raw:'—';
    return this.record(`${kind} · ${code} · ${phase} · ${route}`);
  }
  transport(): readonly string[] {return this.record('transport.failure · closed');}
  private record(text:string): readonly string[] {
    const last=this.#rows.at(-1);
    if(last?.text===text) last.count=Math.min(999999,last.count+1);
    else {this.#rows.push({text,count:1});if(this.#rows.length>64)this.#rows.shift();}
    return this.#rows.map(row=>row.text+(row.count>1?' ×'+row.count:''));
  }
}
