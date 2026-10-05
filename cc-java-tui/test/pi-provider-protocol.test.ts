import {describe, expect, it} from 'vitest';
import {decodeEvent} from '../src/protocol.js';
import {isPiAuthEpoch, validateProviderControlRequest} from '../src/pi-provider-control.js';

const identity = {backend: 'pi', providerId: 'openai', profileId: 'default', authMethod: 'API_KEY'};
const profile = {...identity, refKind: 'ENV_REF', localStatus: 'CONFIGURED_UNVERIFIED', providerDefault: false};
const routes = ['openai', 'openai-codex', 'deepseek', 'qwen-token-plan-cn'];
const catalog = routes.map(providerId => ({backend: 'pi', providerId,
  brandId: providerId.startsWith('openai') ? 'openai' : providerId === 'deepseek' ? 'deepseek' : 'qwen',
  label: providerId, componentAvailable: false, authMethods: [providerId === 'openai-codex' ? 'OAUTH' : 'API_KEY']}));
function decode(intent: string, result: unknown) {
  return decodeEvent(JSON.stringify({version: 0, type: 'provider.control.result', requestId: 'r', sessionId: 's', sequence: 1,
    payload: {controlId: 'c', intent, status: 'succeeded', code: 'OK', result}}), 1);
}

describe('ADR100 Pi metadata protocol', () => {
  it('四路缺组件仍完整且目录无需凭证', () => {
    expect(decode('providers.catalog', {providers: catalog}).payload.result).toEqual({providers: catalog});
  });
  it.each(routes)('身份和方法精确匹配 %s', providerId => {
    const target = {...identity, providerId, authMethod: providerId === 'openai-codex' ? 'OAUTH' : 'API_KEY'};
    const item = {...profile, ...target, refKind: providerId === 'openai-codex' ? 'OAUTH' : 'API_KEY'};
    expect(() => decode('auth.list', {profiles: [item]})).not.toThrow();
    expect(() => decode('auth.list', {profiles: [{...item, authMethod: target.authMethod === 'OAUTH' ? 'API_KEY' : 'OAUTH'}]})).toThrow();
  });
  it('固定成功结果shape不反射epoch', () => {
    for (const [intent, result] of [
      ['auth.activate', {...identity, localStatus: 'CONFIGURED_UNVERIFIED'}],
      ['auth.logout.prepare', {...identity, confirmationId: 'ticket'}],
      ['models.use', {...identity, modelId: 'gpt-5', setDefault: false}],
      ['models.list', {models: [{backend: 'pi', providerId: 'openai', modelId: 'gpt-5', providerDefault: false}]}],
      ['auth.logout.commit', {providerId: 'openai', profileId: 'default', remoteRevoked: false}],
    ] as const) {
      expect(() => decode(intent, result)).not.toThrow();
      expect(() => decode(intent, {...result, authEpoch: '9007199254740993'})).toThrow();
    }
  });
  it.each(['environmentName', 'accountId', 'url', 'secret', 'authEpoch'])('拒绝元数据字段 %s', field => {
    expect(() => decode('auth.list', {profiles: [{...profile, [field]: 'canary'}]})).toThrow();
    expect(() => decode('auth.activate', {...identity, localStatus: 'CONFIGURED_UNVERIFIED', [field]: 'canary'})).toThrow();
    expect(() => validateProviderControlRequest('auth.activate', {...identity, authEpoch: '1', [field]: 'canary'})).toThrow();
  });
  it('legacy同名保持旧字段，混合列表和无backend新字段失败', () => {
    const legacy = {providerId: 'openai', profileId: 'default', authMethod: 'API_KEY', refKind: 'STORE',
      localStatus: 'CONFIGURED_UNVERIFIED', providerDefault: true};
    expect(() => decode('auth.list', {profiles: [legacy]})).not.toThrow();
    expect(() => decode('auth.list', {profiles: [{...legacy, authMethod: 'OAUTH'}]})).toThrow();
    expect(() => decode('auth.list', {profiles: [legacy, profile]})).toThrow();
    const {backend: _, ...untagged} = profile;
    expect(() => decode('auth.list', {profiles: [untagged]})).toThrow();
    expect(() => decode('auth.list', {profiles: [{...profile, backend: 'unknown'}]})).toThrow();
    expect(() => decode('auth.list', {profiles: [{...profile, providerDefault: true}]})).toThrow();
    expect(() => decode('auth.list', {profiles: [{...profile, localStatus: 'REVOKED_IN_PROCESS'}]})).not.toThrow();
  });
  it.each([0, 1, 9007199254740992, '0', '01', '-1', '1.0', '1e3', '9223372036854775808', '99999999999999999999', null])('拒绝非精确epoch %s', authEpoch => {
    expect(isPiAuthEpoch(authEpoch)).toBe(false);
    expect(() => validateProviderControlRequest('auth.activate', {...identity, authEpoch})).toThrow();
  });
  it.each(['1', '9007199254740993', '9223372036854775807'])('精确字符串epoch %s', authEpoch => {
    expect(isPiAuthEpoch(authEpoch)).toBe(true);
    expect(() => validateProviderControlRequest('auth.activate', {...identity, authEpoch})).not.toThrow();
  });
  it.each(['providers.configure', 'providers.add', 'models.add', 'models.remove', 'auth.probe', 'auth.logout'])('Pi不支持操作不回退 %s', intent => {
    expect(() => validateProviderControlRequest(intent, identity)).toThrow();
  });
  it('提交只能是确认票据；任何后端或身份污染拒绝', () => {
    const args = {confirmationId: 'ticket', confirmed: true};
    expect(() => validateProviderControlRequest('auth.logout.commit', args)).not.toThrow();
    for (const extra of [{backend: 'pi'}, {backend: 'spring-ai'}, {authMethod: 'API_KEY'}, {authEpoch: '1'},
      {profileId: 'default'}, {providerId: 'openai'}, {secret: 'canary'}, {confirmed: false}]) {
      expect(() => validateProviderControlRequest('auth.logout.commit', {...args, ...extra})).toThrow();
    }
  });
  it('显式后端未知或不匹配方法拒绝，但不猜同名legacy', () => {
    expect(() => validateProviderControlRequest('models.list', {providerId: 'openai'})).not.toThrow();
    expect(() => validateProviderControlRequest('auth.list', {backend: 'spring-ai'})).not.toThrow();
    for (const backend of [null, 1, 'PI', 'other']) expect(() => validateProviderControlRequest('auth.list', {backend})).toThrow();
    expect(() => validateProviderControlRequest('auth.activate', {...identity, authMethod: 'OAUTH', authEpoch: '1'})).toThrow();
    expect(() => validateProviderControlRequest('auth.activate', {...identity, providerId: 'other', authEpoch: '1'})).toThrow();
    expect(() => validateProviderControlRequest('providers.catalog', {})).toThrow();
  });
});
