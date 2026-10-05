import {expect, it, vi} from 'vitest';
import {ExperienceAuth, type AuthClient, type AuthIntent} from '../src/experience/auth.js';
import type {ProtocolEvent} from '../src/protocol.js';
import type {PiAuthIdentity, PiAuthCallbacks, PiAuthResult} from '../src/pi-auth-bridge.js';
const identity = {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'} as const;
function fixture() {
  const calls: {request: string; controlId: string; intent: AuthIntent; args: Readonly<Record<string, unknown>>}[] = [];
  let finish: (r: PiAuthResult) => void = undefined!;
  const client = {
    providerControl: vi.fn((controlId: string, intent: AuthIntent, args: Readonly<Record<string, unknown>>) => {const request = 'r' + calls.length; calls.push({request, controlId, intent, args}); return request;}),
    piLogin: vi.fn((_i: PiAuthIdentity, _c: PiAuthCallbacks) => new Promise<PiAuthResult>(r => {finish = r;})), piSubmit: () => true, piCancel: vi.fn(),
  } satisfies AuthClient;
  const auth = new ExperienceAuth(client, () => {}); auth.initialize('s', {piProviderV1: true, authLifecycleV1: true});
  const event = (r: Record<string, unknown>): ProtocolEvent => {const c = calls.at(-1)!; return {version: 0, requestId: c.request, sessionId: 's', sequence: 1, type: 'provider.control.result', payload: {controlId: c.controlId, intent: c.intent, status: 'succeeded', result: r}} as ProtocolEvent;};
  const result = (r: Record<string, unknown>) => auth.accept(event(r));
  const choose = (v: string) => {const i = auth.panel!.choices.findIndex(c => c.value === v); expect(i).toBeGreaterThanOrEqual(0); auth.move(i - auth.panel!.focus); auth.enter();};
  return {auth, client, calls, event, result, choose, stored: async () => {finish({status: 'stored', receipt: {...identity, authEpoch: '9007199254740993'}}); await Promise.resolve();}};
}
it('暂不启用后不可通过已配置profile跳过本次exact receipt激活', async () => {
  const h = fixture();
  const open = () => {h.auth.open('/login'); h.result({providers: []}); h.choose('openai'); h.result({profiles: [{...identity, refKind: 'API_KEY', localStatus: 'CONFIGURED_UNVERIFIED'}]}); h.choose('default');};
  open(); h.choose('login'); await h.stored(); h.choose('cancel'); open();
  expect(h.auth.panel!.choices.some(c => c.value === 'existing')).toBe(false); expect(h.calls.some(c => c.intent === 'auth.activate')).toBe(false);
});
it('同名Pi和legacy退出目标同时可辨；legacy不带backend，commit仅一次性ticket', () => {
  const h = fixture(); h.auth.open('/logout'); h.result({profiles: [{...identity, refKind: 'API_KEY'}]}); h.result({profiles: [{providerId: 'openai', profileId: 'default'}]});
  expect(h.auth.panel!.choices.map(c => c.label).join('\n')).toContain('pi / openai / API_KEY');
  expect(h.auth.panel!.choices.map(c => c.label).join('\n')).toContain('spring-ai / API_KEY / openai');
  h.choose('legacy:0'); expect(h.calls.at(-1)?.args).toEqual({providerId: 'openai', profileId: 'default'});
  h.result({providerId: 'openai', profileId: 'default', confirmationId: 'legacy-ticket'}); h.choose('logout');
  expect(h.calls.at(-1)?.args).toEqual({confirmationId: 'legacy-ticket', confirmed: true});
  h.result({providerId: 'openai', profileId: 'default', remoteRevoked: false}); expect(h.auth.panel!.phase).toBe('done');
});
it('旧目录响应不能覆盖下一操作，同request错session也拒绝', () => {
  const h = fixture(); h.auth.open('/login'); const old = h.event({providers: []}); h.auth.open('/login');
  expect(h.auth.accept(old)).toBe(false); const current = h.event({providers: []}); expect(h.auth.accept({...current, sessionId: 'other'})).toBe(false);
  expect(h.auth.panel!.phase).toBe('wait'); expect(h.auth.accept(current)).toBe(true); expect(h.auth.panel!.phase).toBe('providers');
});
