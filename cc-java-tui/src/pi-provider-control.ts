/** ADR-100 普通 stdio 的纯元数据 Gate；不导入私有认证桥、不读取 ENV 或秘密。 */
const methods: Readonly<Record<string, string>> = Object.freeze({
  openai: 'API_KEY', 'openai-codex': 'OAUTH', deepseek: 'API_KEY', 'qwen-token-plan-cn': 'API_KEY',
});
const brands: Readonly<Record<string, string>> = Object.freeze({
  openai: 'openai', 'openai-codex': 'openai', deepseek: 'deepseek', 'qwen-token-plan-cn': 'qwen',
});
const identityFields = ['backend', 'providerId', 'profileId', 'authMethod'];
const id = (v: unknown): v is string => typeof v === 'string' && /^[a-z0-9][a-z0-9-]{0,62}$/u.test(v);
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 && v.length <= max
  && !/[\u0000-\u001f\u007f-\u009f\uD800-\uDFFF]/u.test(v);
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const own = (v: object, key: string): boolean => Object.hasOwn(v, key);
const exact = (v: Record<string, unknown>, required: readonly string[], optional: readonly string[] = []): boolean =>
  required.every(k => own(v, k)) && Object.keys(v).every(k => required.includes(k) || optional.includes(k));
const provider = (v: unknown): v is string => typeof v === 'string' && own(methods, v);
const identity = (v: Record<string, unknown>): boolean => v.backend === 'pi' && provider(v.providerId)
  && methods[v.providerId] === v.authMethod && id(v.profileId);
const status = (v: unknown): boolean => v === 'CONFIGURED_UNVERIFIED' || v === 'REVOKED_IN_PROCESS';
function invalid(): never { throw new Error('Pi Provider 控制元数据无效'); }

/** 回执仅允许出站 activate 的正十进制字符串，绝不经 JS Number。 */
export function isPiAuthEpoch(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/u.test(value)
    && (value.length < 19 || value <= '9223372036854775807');
}

/** legacy 的省略 backend 语义保持不变；显式 Pi 从不按 provider 名回退。 */
export function validateProviderControlRequest(intent: string, args: Readonly<Record<string, unknown>>): void {
  if (!record(args)) invalid();
  if (intent === 'auth.logout.commit') {
    if (!exact(args, ['confirmationId', 'confirmed']) || !text(args.confirmationId, 128) || args.confirmed !== true) invalid();
    return;
  }
  if (own(args, 'backend') && args.backend !== 'pi' && args.backend !== 'spring-ai') invalid();
  if (args.backend !== 'pi') {
    if (intent === 'providers.catalog' || own(args, 'authEpoch') || own(args, 'authMethod')) invalid();
    return;
  }
  switch (intent) {
    case 'providers.catalog': case 'auth.list':
      if (!exact(args, ['backend'])) invalid();
      break;
    case 'models.list':
      if (!exact(args, ['backend'], ['providerId']) || (own(args, 'providerId') && !provider(args.providerId))) invalid();
      break;
    case 'auth.activate':
      if (!exact(args, [...identityFields, 'authEpoch']) || !identity(args) || !isPiAuthEpoch(args.authEpoch)) invalid();
      break;
    case 'auth.logout.prepare':
      if (!exact(args, identityFields) || !identity(args)) invalid();
      break;
    case 'models.use':
      if (!exact(args, [...identityFields, 'modelId'], ['setDefault']) || !identity(args) || !text(args.modelId, 256)
        || (own(args, 'setDefault') && typeof args.setDefault !== 'boolean')) invalid();
      break;
    default: invalid();
  }
}

/** 判断带标签的结果；空列表由绑定请求辨别后端，不猜测 Provider ID。 */
export function hasProviderBackend(result: Readonly<Record<string, unknown>>): boolean {
  return own(result, 'backend') || ['providers', 'profiles', 'models'].some(key =>
    Array.isArray(result[key]) && result[key].some((item: unknown) => record(item) && own(item, 'backend')));
}

/** 返回 false 表示无 Pi 标签、继续旧验证；带标签但非法必须失败关闭。 */
export function validatePiProviderResult(intent: string, result: Readonly<Record<string, unknown>>): boolean {
  if (intent !== 'providers.catalog' && !hasProviderBackend(result)) return false;
  switch (intent) {
    case 'providers.catalog': {
      if (!exact(result, ['providers']) || !Array.isArray(result.providers) || result.providers.length !== 4) invalid();
      const seen = new Set<string>();
      for (const item of result.providers) {
        if (!record(item) || !exact(item, ['backend', 'providerId', 'brandId', 'label', 'componentAvailable', 'authMethods'])
          || item.backend !== 'pi' || !provider(item.providerId) || brands[item.providerId] !== item.brandId
          || !text(item.label, 80) || typeof item.componentAvailable !== 'boolean'
          || !Array.isArray(item.authMethods) || item.authMethods.length !== 1 || item.authMethods[0] !== methods[item.providerId]
          || seen.has(item.providerId)) invalid();
        seen.add(item.providerId as string);
      }
      break;
    }
    case 'auth.list':
      if (!exact(result, ['profiles']) || !Array.isArray(result.profiles) || result.profiles.length > 256) invalid();
      for (const item of result.profiles) {
        if (!record(item) || !exact(item, [...identityFields, 'refKind', 'localStatus', 'providerDefault']) || !identity(item)
          || !status(item.localStatus) || item.providerDefault !== false
          || (item.authMethod === 'OAUTH' ? item.refKind !== 'OAUTH' : !['API_KEY', 'ENV_REF'].includes(String(item.refKind)))) invalid();
      }
      break;
    case 'models.list':
      if (!exact(result, ['models']) || !Array.isArray(result.models) || result.models.length > 256) invalid();
      for (const item of result.models) {
        if (!record(item) || !exact(item, ['backend', 'providerId', 'modelId', 'providerDefault']) || item.backend !== 'pi'
          || !provider(item.providerId) || !text(item.modelId, 256) || item.providerDefault !== false) invalid();
      }
      break;
    case 'auth.activate':
      if (!exact(result, [...identityFields, 'localStatus']) || !identity(result) || !status(result.localStatus)) invalid();
      break;
    case 'auth.logout.prepare':
      if (!exact(result, [...identityFields, 'confirmationId']) || !identity(result) || !text(result.confirmationId, 128)) invalid();
      break;
    case 'models.use':
      if (!exact(result, [...identityFields, 'modelId', 'setDefault']) || !identity(result) || !text(result.modelId, 256)
        || typeof result.setDefault !== 'boolean') invalid();
      break;
    default: invalid();
  }
  return true;
}
