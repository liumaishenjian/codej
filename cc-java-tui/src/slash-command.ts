export type SlashIntent =
  | 'help'
  | 'clear'
  | 'compact'
  | 'context'
  | 'doctor'
  | 'model'
  | 'connect'
  | 'auth'
  | 'models'
  | 'permissions'
  | 'resume'
  | 'tasks'
  | 'plan-status'
  | 'plan';

export type SessionSlashIntent = Exclude<SlashIntent, 'connect' | 'auth' | 'models'>;

export interface ParsedSlashCommand {
  readonly intent: SessionSlashIntent;
  readonly arguments: Readonly<Record<string, unknown>>;
}

export interface ProviderControlSlashCommand {
  readonly intent: 'connect' | 'auth' | 'models';
  readonly arguments: Readonly<Record<string, unknown>>;
}

export interface ParsedTaskCommand {
  readonly action: 'wait' | 'cancel' | 'keep' | 'remove';
  readonly taskId: string;
  readonly timeoutMillis?: number;
}

export type SlashParseResult =
  | {readonly kind: 'not-command'}
  | {readonly kind: 'command'; readonly command: ParsedSlashCommand}
  | {readonly kind: 'provider-control'; readonly command: ProviderControlSlashCommand}
  | {readonly kind: 'task'; readonly command: ParsedTaskCommand}
  | {readonly kind: 'permission-picker'}
  | {readonly kind: 'skill'; readonly name: string; readonly arguments: string}
  | {readonly kind: 'invalid'; readonly message: string};

const MAX_ARGUMENT_CHARS = 256;
const MAX_COMPACT_ANCHORS = 16;
const MAX_COMPACT_ANCHOR_CODE_POINTS = 512;
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f]/u;
const COMMAND_NAMES: readonly SlashIntent[] = [
  'help', 'clear', 'compact', 'context', 'doctor', 'model', 'connect', 'auth', 'models', 'permissions', 'resume',
  'tasks', 'plan-status', 'plan',
];
const COMMANDS = new Set<SlashIntent>(COMMAND_NAMES);
const TYPO_PROTECTED_COMMANDS: readonly string[] = [...COMMAND_NAMES, 'task'];
const MIN_SHORT_TYPO_LENGTH = 4;

const COMMAND_USAGE: Readonly<Record<SlashIntent, string>> = {
  help: '/help — 查看命令与可用状态',
  clear: '/clear — 清除当前界面的瞬态内容',
  compact: '/compact [anchor...] — 请求压缩下一轮上下文',
  context: '/context — 查看上下文用量',
  doctor: '/doctor — 查看安全诊断',
  model: '/model <name> — 切换到已配置模型',
  connect: '/connect [provider profile [env ENV_NAME]] — 查看 Provider/profile/model 状态与 STORE/ENV/legacy 指引，或连接 Provider',
  auth: '/auth list | probe <provider> <profile> [model] | logout <provider> <profile> confirm — 管理本机 credential',
  models: '/models [provider] | use <provider> <model> [profile] | add <provider> <model> [default] | remove <provider> <model> — 本地模型选择',
  permissions: '/permissions [query|mode MODE] — 查看或切换权限模式',
  resume: '/resume <session-id> — 安全恢复会话',
  tasks: '/tasks — 打开当前 Session 的执行任务列表',
  'plan-status': '/plan-status — 查看当前计划状态',
  plan: '/plan [自然语言任务] — 进入只读 Plan 模式、规划任务或查看当前计划',
};

/**
 * 将 TUI 输入转换为封闭的 S08 命令意图，不猜测路径、配置或权限 selector。
 */
export function parseSlashCommand(input: string): SlashParseResult {
  if (!input.startsWith('/')) return {kind: 'not-command'};
  const [rawName, ...values] = input.slice(1).trim().split(/\s+/u);
  if (rawName === 'task') return parseTaskCommand(values);
  if (rawName === 'connect') return parseConnectCommand(values);
  if (rawName === 'auth') return parseAuthCommand(values);
  if (rawName === 'models') return parseModelsCommand(values);
  if (rawName === undefined || rawName.length === 0 || !COMMANDS.has(rawName as SlashIntent)) {
    if (/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(rawName ?? '') && (rawName?.length ?? 0) <= 64) {
      const typoMatch = protectedCommandTypoMatch(rawName ?? '');
      if (typoMatch.kind === 'unique') {
        return {kind: 'invalid', message: `未知 Slash 命令；你是否想输入 /${typoMatch.suggestion}？`};
      }
      if (typoMatch.kind === 'ambiguous') {
        return {kind: 'invalid', message: '未知 Slash 命令'};
      }
      const arguments_ = values.join(' ');
      return Array.from(arguments_).length <= 8_192
        ? {kind: 'skill', name: rawName ?? '', arguments: arguments_}
        : {kind: 'invalid', message: 'Skill 参数超过上限'};
    }
    return {kind: 'invalid', message: '未知 Slash 命令'};
  }
  const intent = rawName as SessionSlashIntent;
  if (['help', 'clear', 'context', 'doctor', 'tasks', 'plan-status'].includes(intent)) {
    return values.length === 0
      ? {kind: 'command', command: {intent, arguments: {}}}
      : {kind: 'invalid', message: `/${intent} 不接受参数`};
  }
  if (intent === 'plan') {
    const task = input.slice(input.indexOf('/plan') + '/plan'.length).trim();
    if (Array.from(task).length > 8_192 || CONTROL_CHARACTER_PATTERN.test(task)) {
      return {kind: 'invalid', message: '/plan 自然语言任务非法或超过上限'};
    }
    return {kind: 'command', command: {intent, arguments: task.length === 0 ? {} : {task}}};
  }
  if (intent === 'compact') {
    if (values.length > MAX_COMPACT_ANCHORS || values.some(invalidCompactAnchor)) {
      return {kind: 'invalid', message: '/compact 参数非法或超过上限'};
    }
    return {kind: 'command', command: {intent, arguments: {anchors: values}}};
  }
  if (intent === 'permissions') {
    if (values.length === 0) {
      return {kind: 'permission-picker'};
    }
    if (values.length === 1 && values[0] === 'query') {
      return {kind: 'command', command: {intent, arguments: {}}};
    }
    const [operation, mode] = values;
    return values.length === 2 && operation === 'mode' && mode !== undefined
      && (mode === 'DEFAULT' || mode === 'PLAN' || mode === 'ACCEPT_EDITS')
      ? {kind: 'command', command: {intent, arguments: {mode}}}
      : {kind: 'invalid', message: '/permissions 只接受 query 或 mode DEFAULT|PLAN|ACCEPT_EDITS'};
  }
  const value = values[0];
  const key = intent === 'model' ? 'name' : 'sessionId';
  return values.length === 1 && value !== undefined && !invalidArgument(value)
    ? {kind: 'command', command: {intent, arguments: {[key]: value}}}
    : {kind: 'invalid', message: `/${intent} 需要一个有界参数`};
}

/** 返回命令面板使用的本地固定说明，不使用服务端自由文本。 */
export function slashCommandUsage(candidate: string): string {
  if (candidate.includes(' ')) {
    return candidate;
  }
  const intent = candidate.slice(1).split(/\s+/u)[0] as SlashIntent | undefined;
  return intent !== undefined && intent in COMMAND_USAGE
    ? COMMAND_USAGE[intent]
    : candidate;
}

/** 将严格协议结果渲染为不含 Prompt、Secret 或服务端自由文本的本地安全投影。 */
export function renderSlashResult(
  intent: string,
  status: string,
  code: string,
  result: Readonly<Record<string, unknown>> = {},
): string {
  if (status === 'succeeded') {
    return renderSuccessfulResult(intent, result);
  }
  const labels: Record<string, string> = {
    active_run: '当前 Run 仍在执行', unavailable: '当前没有可用视图',
    not_available: '当前版本尚未提供', deferred: '已延期至后续安全切片',
    invalid_argument: '参数无效', request_budget_exhausted: '命令请求额度已用尽',
    cancelled: '请求已取消', compaction_rejected: '压缩候选未通过安全校验',
    internal_failure: '内部处理未完成', current_session: '目标已经是当前 Session',
    session_active: '目标 Session 正由其他 Writer 使用', recovery_required: '目标未通过恢复安全检查',
  };
  return `/${intent} 未执行：${labels[code] ?? '请求被安全拒绝'}`;
}

function renderSuccessfulResult(
  intent: string,
  result: Readonly<Record<string, unknown>>,
): string {
  if (intent === 'help' && isUnknownArray(result.commands)) {
    const supportLabels: Readonly<Record<HelpCommandSupport, string>> = {
      available: '可用', deferred: '延期', not_available: '不可用',
    };
    const commands = result.commands.filter(isHelpCommandEntry);
    const lines = commands.map(item =>
      `${COMMAND_USAGE[item.intent]}　[${supportLabels[item.support]}]`);
    const providerLines = (['connect', 'auth', 'models'] as const)
      .filter(intent_ => !commands.some(item => item.intent === intent_))
      .map(intent_ => `${COMMAND_USAGE[intent_]}　[可用]`);
    return ['Slash 命令', ...lines, ...providerLines].join('\n');
  }
  if (intent === 'context') {
    return [
      'Context 用量',
      `总计 ${safeValue(result.totalTokens)} / 可输入 ${safeValue(result.availableInputTokens)} / 剩余 ${safeValue(result.freeTokens)}`,
      `系统 ${safeValue(result.systemTokens)} · 对话 ${safeValue(result.transcriptTokens)} · 工具 ${safeValue(result.toolTokens)} · 记忆 ${safeValue(result.memoryTokens)}`,
      `状态 ${safeValue(result.contextStatus)} · 估算 ${safeValue(result.estimateKind)} · 溢出 ${safeValue(result.overflowTokens)}`,
      `压缩 ${safeList(result.reductionStrategies)} · 原因 ${safeList(result.reasonCodes)}`,
    ].join('\n');
  }
  if (intent === 'permissions') {
    const rules = Array.isArray(result.rules) ? result.rules : [];
    const ruleLines = rules.flatMap(rule => isRecord(rule)
      ? [`- ${safeValue(rule.ruleId)} · ${safeValue(rule.sourceKind)}/${safeValue(rule.safeSourceId)} · ${safeValue(rule.operation)}`]
      : []);
    return [
      'Permissions',
      `模式 ${safeValue(result.effectiveMode)} · 审阅 ${safeValue(result.effectiveReviewer)} · 选择 ${safeValue(result.effectiveSelection)}`,
      `来源 ${safeValue(result.modeSourceKind)}/${safeValue(result.modeSafeSourceId)} · ${safeValue(result.modeValidationStatus)} · 启动规则 ${safeValue(result.startupRuleCount)}`,
      ...ruleLines,
    ].join('\n');
  }
  if (intent === 'doctor') {
    const entries = Array.isArray(result.entries) ? result.entries : [];
    const entryLines = entries.flatMap(entry => isRecord(entry)
      ? [`- ${safeValue(entry.component)} · ${safeValue(entry.sourceKind)}/${safeValue(entry.safeId)} · ${safeValue(entry.code)} · ${safeValue(entry.severity)}`]
      : []);
    return [
      'Doctor',
      `Settings ${result.settingsAvailable === true ? '可用' : '不可用'} (rev ${safeValue(result.settingsRevision)}) · Instructions ${safeValue(result.instructionCount)}`,
      `Context ${result.contextAvailable === true ? '可用' : '不可用'} · Run ${result.activeRun === true ? '活动' : '空闲'}`,
      ...entryLines,
    ].join('\n');
  }
  if (intent === 'tasks') {
    return result.truncated === true
      ? `Task List 已打开（显示 ${safeValue(Array.isArray(result.tasks) ? result.tasks.length : 0)}/${safeValue(result.totalTasks)}）`
      : `Task List 已打开（${safeValue(result.totalTasks)} 项）`;
  }
  if (intent === 'plan-status' || intent === 'plan') {
    const steps = Array.isArray(result.steps) ? result.steps : [];
    return [
      `Plan · ${safeValue(result.status)}`,
      String(result.objective ?? ''),
      ...steps.flatMap(step => isRecord(step)
        ? [`${safeValue(step.ordinal)}. ${safeValue(step.title)} — ${safeValue(step.detail)}`] : []),
      result.approvalGate === 'PENDING' ? '等待审批；输入 /plan 可重新打开计划视图' : '',
    ].filter(Boolean).join('\n');
  }
  return `/${intent} 已完成`;
}

function safeValue(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '-';
}

function safeList(value: unknown): string {
  return Array.isArray(value) && value.every(item => typeof item === 'string') && value.length > 0
    ? value.join(', ')
    : '无';
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

type HelpCommandSupport = 'available' | 'deferred' | 'not_available';

interface HelpCommandEntry {
  readonly intent: SlashIntent;
  readonly support: HelpCommandSupport;
}

function isUnknownArray(value: unknown): value is readonly unknown[] {
  return Array.isArray(value);
}

function isHelpCommandEntry(value: unknown): value is HelpCommandEntry {
  return isRecord(value)
    && isSlashIntent(value.intent)
    && (value.support === 'available'
      || value.support === 'deferred'
      || value.support === 'not_available');
}

function isSlashIntent(value: unknown): value is SlashIntent {
  switch (value) {
    case 'help':
    case 'clear':
    case 'compact':
    case 'context':
    case 'doctor':
    case 'model':
    case 'connect':
    case 'auth':
    case 'models':
    case 'permissions':
    case 'resume':
    case 'tasks':
    case 'plan-status':
    case 'plan':
      return true;
    default:
      return false;
  }
}

function parseTaskCommand(values: readonly string[]): SlashParseResult {
  const [action, taskId, timeout] = values;
  if (!['wait', 'cancel', 'keep', 'remove'].includes(action ?? '')
    || taskId === undefined || !/^task-[A-Za-z0-9_-]{1,96}$/u.test(taskId)) {
    return {kind: 'invalid', message: '/task 只接受 wait|cancel|keep|remove 与有效 task ID'};
  }
  if (action === 'wait') {
    if (values.length < 2 || values.length > 3) {
      return {kind: 'invalid', message: '/task wait <task-id> [timeout-ms]'};
    }
    const timeoutMillis = timeout === undefined ? 30_000 : Number(timeout);
    if (!Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1 || timeoutMillis > 300_000) {
      return {kind: 'invalid', message: '/task wait timeout 必须在 1..300000ms'};
    }
    return {kind: 'task', command: {action, taskId, timeoutMillis}};
  }
  return values.length === 2
    ? {kind: 'task', command: {action: action as 'cancel' | 'keep' | 'remove', taskId}}
    : {kind: 'invalid', message: `/task ${action} 只接受 task ID`};
}

function parseConnectCommand(values: readonly string[]): SlashParseResult {
  if (values.length === 0) {
    return {kind: 'provider-control', command: {intent: 'connect', arguments: {action: 'providers'}}};
  }
  const [providerId, profileId, source, environmentName] = values;
  if (!validId(providerId) || !validId(profileId)) {
    return {kind: 'invalid', message: '/connect [provider profile [env ENV_NAME]]；ENV_NAME 必须匹配 [A-Z][A-Z0-9_]{0,127}'};
  }
  if (values.length === 2) {
    return {kind: 'provider-control', command: {intent: 'connect', arguments: {
      action: 'login', providerId, profileId, secretSource: 'store',
    }}};
  }
  if (values.length === 4 && source === 'env' && validEnvironmentName(environmentName)) {
    return {kind: 'provider-control', command: {intent: 'connect', arguments: {
      action: 'login', providerId, profileId, secretSource: 'env', environmentName,
    }}};
  }
  return {kind: 'invalid', message: '/connect [provider profile [env ENV_NAME]]；ENV_NAME 必须匹配 [A-Z][A-Z0-9_]{0,127}'};
}

function parseAuthCommand(values: readonly string[]): SlashParseResult {
  if (values.length === 1 && values[0] === 'list') {
    return {kind: 'provider-control', command: {intent: 'auth', arguments: {action: 'list'}}};
  }
  const [action, providerId, profileId, value] = values;
  if (action === 'probe' && (values.length === 3 || values.length === 4)
    && validId(providerId) && validId(profileId)
    && (value === undefined || !invalidArgument(value))) {
    return {kind: 'provider-control', command: {intent: 'auth', arguments: {
      action, providerId, profileId, ...(value === undefined ? {} : {modelId: value}),
    }}};
  }
  return action === 'logout' && values.length === 4 && value === 'confirm'
    && validId(providerId) && validId(profileId)
    ? {kind: 'provider-control', command: {intent: 'auth', arguments: {action, providerId, profileId, confirmed: true}}}
    : {kind: 'invalid', message: '/auth 只接受 list、probe <provider> <profile> [model] 或 logout <provider> <profile> confirm'};
}
function parseModelsCommand(values: readonly string[]): SlashParseResult {
  if (values.length <= 1 && (values.length === 0 || validId(values[0]))) {
    return {kind: 'provider-control', command: {intent: 'models', arguments: values.length === 0
      ? {action: 'list'} : {action: 'list', providerId: values[0]}}};
  }
  const [action, providerId, modelId, option] = values;
  if (action === 'use' && (values.length === 3 || values.length === 4)
    && validId(providerId) && validModelId(modelId)
    && (option === undefined || validId(option))) {
    return {kind: 'provider-control', command: {intent: 'models', arguments: {
      action, providerId, modelId, ...(option === undefined ? {} : {profileId: option}),
    }}};
  }
  if (action === 'add' && (values.length === 3 || values.length === 4)
    && validId(providerId) && validModelId(modelId)
    && (option === undefined || option === 'default')) {
    return {kind: 'provider-control', command: {intent: 'models', arguments: {
      action, providerId, modelId, setDefault: option === 'default',
    }}};
  }
  if (action === 'remove' && values.length === 3
    && validId(providerId) && validModelId(modelId)) {
    return {kind: 'provider-control', command: {intent: 'models', arguments: {
      action, providerId, modelId,
    }}};
  }
  return {kind: 'invalid', message: '/models [provider]、use <provider> <model> [profile]、add <provider> <model> [default] 或 remove <provider> <model>'};
}

type ProtectedCommandTypoMatch =
  | {readonly kind: 'none'}
  | {readonly kind: 'unique'; readonly suggestion: string}
  | {readonly kind: 'ambiguous'};

/**
 * 只保护封闭命令的一次局部拼写错误，不做一般模糊匹配或自动执行。
 *
 * <p>候选固定且数量有界；仅接受单字符插入、删除、替换或一次相邻换位。长度不足四个
 * 字符的输入不参与保护，避免把短 Skill 名误判为内置命令。唯一候选才建议；多个候选
 * 必须与无候选分开表达，以免歧义输入回落为 Skill。</p>
 */
function protectedCommandTypoMatch(
  name: string,
  protectedCommands: readonly string[] = TYPO_PROTECTED_COMMANDS,
): ProtectedCommandTypoMatch {
  if (Array.from(name).length < MIN_SHORT_TYPO_LENGTH) return {kind: 'none'};
  const candidates = protectedCommands.filter(candidate => isSingleCommandEdit(name, candidate));
  // `/task` 是既有 child-task 控制命令；新增 `/tasks` 后，历史拼写 `/taks` 同时距两者一步。
  // 固定保留旧建议，不自动执行，也不把其他歧义输入降级成 Skill。
  if (name === 'taks' && candidates.includes('task') && candidates.includes('tasks')) {
    return {kind: 'unique', suggestion: 'task'};
  }
  const [suggestion] = candidates;
  if (suggestion === undefined) return {kind: 'none'};
  return candidates.length === 1
    ? {kind: 'unique', suggestion}
    : {kind: 'ambiguous'};
}

function isSingleCommandEdit(input: string, candidate: string): boolean {
  const left = Array.from(input);
  const right = Array.from(candidate);
  const lengthDifference = left.length - right.length;
  if (Math.abs(lengthDifference) > 1 || (left.length === right.length && left.length === 0)) return false;

  if (left.length === right.length) {
    const mismatches: number[] = [];
    for (let index = 0; index < left.length; index++) {
      if (left[index] !== right[index]) mismatches.push(index);
      if (mismatches.length > 2) return false;
    }
    if (mismatches.length === 1) return true;
    const [firstMismatch, secondMismatch] = mismatches;
    return firstMismatch !== undefined && secondMismatch !== undefined
      && secondMismatch === firstMismatch + 1
      && left[firstMismatch] === right[secondMismatch]
      && left[secondMismatch] === right[firstMismatch];
  }

  const longer = left.length > right.length ? left : right;
  const shorter = left.length > right.length ? right : left;
  let longerIndex = 0;
  let shorterIndex = 0;
  let skipped = false;
  while (longerIndex < longer.length && shorterIndex < shorter.length) {
    if (longer[longerIndex] === shorter[shorterIndex]) {
      longerIndex++;
      shorterIndex++;
    } else if (!skipped) {
      skipped = true;
      longerIndex++;
    } else {
      return false;
    }
  }
  return true;
}

function validId(value: string | undefined): value is string {
  return value !== undefined && /^[a-z0-9][a-z0-9-]{0,62}$/u.test(value);
}

function validEnvironmentName(value: string | undefined): value is string {
  return value !== undefined && /^[A-Z][A-Z0-9_]{0,127}$/u.test(value);
}

function validModelId(value: string | undefined): value is string {
  return value !== undefined && !invalidArgument(value) && !/\s/u.test(value);
}

function invalidArgument(value: string): boolean {
  return value.length === 0 || Array.from(value).length > MAX_ARGUMENT_CHARS
    || CONTROL_CHARACTER_PATTERN.test(value);
}

function invalidCompactAnchor(value: string): boolean {
  return value.length === 0 || Array.from(value).length > MAX_COMPACT_ANCHOR_CODE_POINTS
    || CONTROL_CHARACTER_PATTERN.test(value);
}
