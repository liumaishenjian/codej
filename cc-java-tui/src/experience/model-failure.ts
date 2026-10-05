import type {ModelFailureCategory, ModelFailureView} from '../state.js';

const categories = new Set<ModelFailureCategory>([
  'provider_unavailable', 'rate_limited', 'request_timeout', 'request_conflict',
  'authentication_failed', 'invalid_request', 'network_error', 'incomplete_stream',
  'invalid_response', 'provider_error', 'configuration_required',
]);

/** 只消费 Java stdio 已白名单化的模型失败摘要，不把 Provider 原文或凭证带入 TUI。 */
export function modelFailureNotice(value: unknown): string {
  if (!value || typeof value !== 'object') return '';
  const item = value as Record<string, unknown>;
  if (typeof item.category !== 'string' || !categories.has(item.category as ModelFailureCategory)
    || !Number.isSafeInteger(item.attempts) || (item.attempts as number) < 1
    || (item.statusClass !== undefined && item.statusClass !== '4xx' && item.statusClass !== '5xx')) return '';
  const summary = item as unknown as ModelFailureView;
  const base = (() => {
    switch (summary.category) {
      case 'provider_unavailable': return '模型服务暂时不可用';
      case 'rate_limited': return '模型服务请求过于频繁';
      case 'request_timeout': return '模型请求超时';
      case 'request_conflict': return '模型服务暂时无法处理该请求';
      case 'authentication_failed': return '模型服务鉴权失败';
      case 'invalid_request': return '模型服务拒绝了请求';
      case 'network_error': return '无法连接模型服务';
      case 'incomplete_stream': return '模型输出流未完整结束';
      case 'invalid_response': return '模型服务返回了无效响应';
      case 'provider_error': return '模型服务调用失败';
      case 'configuration_required': return '尚未配置 Provider profile 或模型选择';
    }
  })();
  const status = summary.statusClass === undefined ? '' : `（${summary.statusClass}）`;
  const attempts = summary.attempts > 1 ? `，已尝试 ${summary.attempts} 次` : '';
  const action = summary.category === 'authentication_failed'
    ? '；请检查 Provider 凭证或权限'
    : summary.category === 'invalid_request'
      ? '；请检查模型与请求配置'
      : summary.category === 'configuration_required'
        ? '；请运行 /connect 或 codej auth login'
        : summary.category === 'invalid_response' || summary.category === 'provider_error'
          ? '；请检查 Provider 状态'
          : '；请稍后重试';
  return base + status + attempts + action;
}
