import {PiAuthBridge, type PiAuthCallbacks, type PiAuthIdentity, type PiAuthLoginOptions, type PiAuthResult} from './pi-auth-bridge.js';
import type {ChildProcessSpec, StdioClient} from './stdio-client.js';

/** 只附加私有认证通道；所有原有动态 Run/Session fence 仍在 StdioClient 原方法中执行。 */
export function attachPiAuth(client: StdioClient, spec: ChildProcessSpec) {
  // 不兼容的 helper 只使私有登录失败，不从环境搜索其他入口或隐藏目录。
  let bridge: PiAuthBridge | undefined;
  try {bridge = new PiAuthBridge(spec);} catch { /* 原来的非生产 Fake spec 仍可用于 TUI。 */ }
  let ready = false, session = '', activeRun: string | undefined, legacy = false;
  let pendingPi: Promise<PiAuthResult> | undefined;
  const pendingRuns = new Set<string>(), pendingControls = new Set<string>();
  client.onEvent(e => {
    if (e.type === 'initialized') {
      bridge?.cancel(); session = e.sessionId ?? '';
      ready = !!session && e.payload.piProviderV1 === true && e.payload.authLifecycleV1 === true;
      activeRun = undefined; pendingRuns.clear(); pendingControls.clear();
    }
    // 请求级拒绝可以没有Session；只释放精确请求，不能被另一Session或无关错误解锁。
    if (e.type === 'protocol.error' && (e.sessionId === undefined || e.sessionId === session)) {
      pendingRuns.delete(e.requestId!); pendingControls.delete(e.requestId!); return;
    }
    if (e.sessionId !== session) return;
    if (e.type === 'run.started') {activeRun = e.runId; pendingRuns.delete(e.requestId!); bridge?.cancel();}
    if ((e.type === 'run.completed' || e.type === 'run.cancelled' || e.type === 'run.failed') && e.runId === activeRun) {activeRun = undefined; pendingRuns.delete(e.requestId!);}
    if (e.type === 'protocol.error' || e.type === 'run.launch.failed' || (e.type === 'run.command.result' && e.payload.disposition === 'rejected')) pendingRuns.delete(e.requestId!);
    if (e.type === 'session.command.result' || e.type === 'protocol.error') pendingControls.delete(e.requestId!);
  });
  client.onRunHandshake(n => {if (n.kind === 'timed_out') {ready = false; bridge?.cancel();}});
  client.onFailure(() => {ready = false; bridge?.cancel();});
  client.onExit(() => {ready = false; bridge?.cancel();});
  const guard = () => {if (bridge?.active()) throw new Error('PI_AUTH_BUSY');};
  // 包装仍调用原实例方法，不能用展开对象或原型拷贝丢失私有状态及动态分派。
  for (const name of ['startRun', 'startPlan', 'startPlanExecution', 'invokeSkill'] as const) {
    const original = client[name].bind(client) as (...args: unknown[]) => string;
    Object.defineProperty(client, name, {configurable: true, value: (...args: unknown[]) => {
      guard(); const result = original(...args); pendingRuns.add(result); return result;
    }});
  }
  const resolve = client.resolvePlanReview.bind(client), command = client.sessionCommand.bind(client);
  const resume = client.resumePlanVerification.bind(client), providerLogin = client.providerLogin.bind(client);
  const terminate = client.terminate.bind(client), shutdown = client.shutdown.bind(client);
  return Object.assign(client, {
    resolvePlanReview(input: Parameters<StdioClient['resolvePlanReview']>[0]): string {
      guard(); const request = resolve(input);
      if (input.decision !== 'REJECT' && (input.decision !== 'CONTINUE_PLANNING' || input.feedback.trim())) pendingRuns.add(request);
      return request;
    },
    resumePlanVerification(): string {guard(); return resume();},
    sessionCommand(...args: Parameters<StdioClient['sessionCommand']>): string {guard(); const request = command(...args); pendingControls.add(request); return request;},
    async providerLogin(...args: Parameters<StdioClient['providerLogin']>) {
      if (bridge?.active() || legacy) {args[0].secretBytes?.fill(0); return {status: 'failed' as const, exitCode: null};}
      legacy = true; try {return await providerLogin(...args);} finally {legacy = false;}
    },
    piLogin(identity: PiAuthIdentity, callbacks: PiAuthCallbacks, options?: PiAuthLoginOptions): Promise<PiAuthResult> {
      if (!ready || activeRun || pendingRuns.size || pendingControls.size || legacy || !bridge) return Promise.resolve({status: 'failed', code: 'BUSY'});
      const result = bridge.login(identity, callbacks, options); pendingPi = result;
      const settled = () => {if (pendingPi === result) pendingPi = undefined;};
      void result.then(settled, settled);
      return result;
    },
    piSubmit(promptId: number, bytes: Uint8Array): boolean {try {return bridge?.submit(promptId, bytes) ?? false;} finally {bytes.fill(0);}},
    piCancel(): void {bridge?.cancel();},
    terminate(): void {ready = false; bridge?.cancel(); terminate();},
    async shutdown(): Promise<void> {
      ready = false; bridge?.cancel();
      // 私有桥自身提供有界取消期限；宿主不能在它结算前退出并丢失输入/进程归属。
      let failed = false;
      try {await pendingPi;} catch {failed = true;}
      finally {await shutdown();}
      if (failed || bridge?.active()) throw new Error('PI_AUTH_CLEANUP');
    },
  });
}
