import { openaiProvider } from '@earendil-works/pi-ai/providers/openai';
import { openaiCodexProvider } from '@earendil-works/pi-ai/providers/openai-codex';
import { deepseekProvider } from '@earendil-works/pi-ai/providers/deepseek';
import { qwenTokenPlanCnProvider } from '@earendil-works/pi-ai/providers/qwen-token-plan-cn';

/** ADR-100 锁定的目录协议来源版本；不代表账号权益或在线可用性。 */
export const PI_VERSION = '0.85.1';

const ROUTES = [
  { id: 'openai', brandId: 'openai', label: 'OpenAI API', auth: 'apiKey' },
  { id: 'openai-codex', brandId: 'openai', label: 'OpenAI ChatGPT/Codex', auth: 'oauth' },
  { id: 'deepseek', brandId: 'deepseek', label: 'DeepSeek API', auth: 'apiKey' },
  { id: 'qwen-token-plan-cn', brandId: 'qwen', label: '通义国内 Token Plan', auth: 'apiKey' },
];

/**
 * 仅实例化四个公开静态工厂，顺序也是协议路由顺序。
 * 不创建 Models、凭证存储或用户配置，不解析认证、不刷新、不执行模型请求。
 * @returns {Array<object>} 独立 Provider 实例；调用者负责后续运行时生命周期。
 */
export function createRegisteredProviders() {
  return [openaiProvider(), openaiCodexProvider(), deepseekProvider(), qwenTokenPlanCnProvider()];
}

function invalid() {
  // 不回显上游对象、字段值或异常，避免诊断意外携带秘密。
  throw new TypeError('Invalid provider catalog metadata');
}

function record(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) invalid();
  return value;
}

function text(value, max) {
  if (typeof value !== 'string' || value.length === 0 || value.length > max
      || value.trim() !== value || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value)) invalid();
  return value;
}

function tokens(value) {
  if (!Number.isSafeInteger(value) || value < 1 || value > 1_000_000_000) invalid();
  return value;
}

/** 只冻结新建的 JSON 树，不改变工厂或调用方的模型数组。 */
function freezeTree(value) {
  if (value !== null && typeof value === 'object') {
    for (const child of Object.values(value)) freezeTree(child);
    Object.freeze(value);
  }
  return value;
}

function projectModel(model, seen) {
  record(model);
  const id = text(model.id, 256);
  if (seen.has(id)) invalid();
  seen.add(id);
  const label = text(model.name, 512);
  const api = text(model.api, 128);
  if (!/^[a-z][a-z0-9-]*$/.test(api)) invalid();
  const contextWindow = tokens(model.contextWindow);
  const maxTokens = tokens(model.maxTokens);
  if (typeof model.reasoning !== 'boolean') invalid();
  if (!Array.isArray(model.input) || model.input.length < 1 || model.input.length > 2) invalid();
  const input = [];
  for (const modality of model.input) {
    if ((modality !== 'text' && modality !== 'image') || input.includes(modality)) invalid();
    input.push(modality);
  }
  return { id, label, api, contextWindow, maxTokens, reasoning: model.reasoning, input };
}

/**
 * 生成可供 Java/Node 共用的 JSON 安全深冻结快照。
 * 必须恰有四个已知 Provider；认证能力须与锁定路由一致，但绝不调用认证函数。
 * 仅同步 getModels；空模型目录合法，错误目录失败关闭。模型 ID 在各路由内唯一
 * （跨路由同名合法），按 UTF-16 ID 顺序排序，不依赖系统 locale。
 * 上限：每路由一万模型，ID 256、名称 512、API 128 字符，token 上限十亿。
 * 未列入投影的 endpoint、headers、环境、费用与认证对象均不读取或输出。
 * @param {Array<object>} providers 可信工厂或离线 Fake；不是可执行的不可信插件入口。
 * @returns {object} 仅表示声明目录，不表示已登录、可用模型或套餐权益。
 * @throws {TypeError} 缺失、重复、未知 Provider 或非法元数据；不暴露上游错误。
 */
export function describeCatalog(providers = createRegisteredProviders()) {
  try {
    if (!Array.isArray(providers) || providers.length !== ROUTES.length) invalid();
    const byId = new Map();
    for (const provider of providers) {
      record(provider);
      const id = provider.id;
      if (!ROUTES.some(route => route.id === id) || byId.has(id)) invalid();
      byId.set(id, provider);
    }
    const projected = ROUTES.map(route => {
      const provider = byId.get(route.id);
      const auth = record(provider.auth);
      const other = route.auth === 'apiKey' ? 'oauth' : 'apiKey';
      const capability = record(auth[route.auth]);
      const operation = route.auth === 'apiKey' ? 'resolve' : 'login';
      if (typeof capability[operation] !== 'function'
          || auth[other] !== undefined || typeof provider.getModels !== 'function') invalid();
      const models = provider.getModels();
      if (!Array.isArray(models) || models.length > 10_000) invalid();
      const seen = new Set();
      const safeModels = [];
      for (const model of models) safeModels.push(projectModel(model, seen));
      safeModels.sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
      return {
        id: route.id, brandId: route.brandId, label: route.label,
        authMethods: [{ id: route.auth === 'apiKey' ? 'api_key' : 'oauth',
          label: route.auth === 'apiKey' ? 'API 密钥' : '网页授权（ChatGPT/Codex）' }],
        models: safeModels,
      };
    });
    const brands = [ ['openai', 'OpenAI'], ['deepseek', 'DeepSeek'], ['qwen', '通义'] ]
      .map(([id, label]) => ({ id, label,
        providerIds: ROUTES.filter(route => route.brandId === id).map(route => route.id) }));
    return freezeTree({ piVersion: PI_VERSION, brands, providers: projected });
  } catch {
    invalid();
  }
}
