import {describe, expect, it} from 'vitest';
import {
  parseSlashCommand,
  renderSlashResult,
  slashCommandUsage,
} from '../src/slash-command.js';

describe('parseSlashCommand', () => {
  it('parses only declared commands into bounded transport arguments', () => {
    expect(parseSlashCommand('/help')).toEqual({
      kind: 'command', command: {intent: 'help', arguments: {}},
    });
    expect(parseSlashCommand('/compact focus release')).toEqual({
      kind: 'command', command: {intent: 'compact', arguments: {anchors: ['focus', 'release']}},
    });
    expect(parseSlashCommand('/plan-status')).toEqual({
      kind: 'command', command: {intent: 'plan-status', arguments: {}},
    });
    expect(parseSlashCommand('/plan-resume')).toEqual({
      kind: 'command', command: {intent: 'plan-resume', arguments: {}},
    });
    expect(parseSlashCommand('/plan')).toEqual({
      kind: 'command', command: {intent: 'plan', arguments: {}},
    });
    expect(parseSlashCommand('/plan 设计一个安全的登录流程')).toEqual({
      kind: 'command', command: {intent: 'plan', arguments: {task: '设计一个安全的登录流程'}},
    });
    expect(parseSlashCommand('/permissions')).toEqual({kind: 'permission-picker'});
    expect(parseSlashCommand('/permissions query')).toEqual({
      kind: 'command', command: {intent: 'permissions', arguments: {}},
    });
    expect(parseSlashCommand('/permissions mode ACCEPT_EDITS')).toEqual({
      kind: 'command', command: {intent: 'permissions', arguments: {mode: 'ACCEPT_EDITS'}},
    });
    expect(parseSlashCommand('/connect')).toEqual({
      kind: 'provider-control', command: {intent: 'connect', arguments: {action: 'providers'}},
    });
    expect(parseSlashCommand('/connect anthropic personal')).toEqual({
      kind: 'provider-control', command: {intent: 'connect', arguments: {
        action: 'login', providerId: 'anthropic', profileId: 'personal', secretSource: 'store',
      }},
    });
    expect(parseSlashCommand('/connect openrouter ci env OPENROUTER_API_KEY')).toEqual({
      kind: 'provider-control', command: {intent: 'connect', arguments: {
        action: 'login', providerId: 'openrouter', profileId: 'ci', secretSource: 'env',
        environmentName: 'OPENROUTER_API_KEY',
      }},
    });
    expect(parseSlashCommand('/auth list')).toEqual({
      kind: 'provider-control', command: {intent: 'auth', arguments: {action: 'list'}},
    });
    expect(parseSlashCommand('/auth probe anthropic personal claude-sonnet')).toEqual({
      kind: 'provider-control', command: {intent: 'auth', arguments: {
        action: 'probe', providerId: 'anthropic', profileId: 'personal', modelId: 'claude-sonnet',
      }},
    });
    expect(parseSlashCommand('/auth logout anthropic personal confirm')).toEqual({
      kind: 'provider-control', command: {intent: 'auth', arguments: {
        action: 'logout', providerId: 'anthropic', profileId: 'personal', confirmed: true,
      }},
    });
    expect(parseSlashCommand('/models use anthropic claude-sonnet personal')).toEqual({
      kind: 'provider-control', command: {intent: 'models', arguments: {
        action: 'use', providerId: 'anthropic', modelId: 'claude-sonnet', profileId: 'personal',
      }},
    });
    expect(parseSlashCommand('/models add anthropic claude-opus default')).toEqual({
      kind: 'provider-control', command: {intent: 'models', arguments: {
        action: 'add', providerId: 'anthropic', modelId: 'claude-opus', setDefault: true,
      }},
    });
    expect(parseSlashCommand('/models add anthropic claude-sonnet')).toEqual({
      kind: 'provider-control', command: {intent: 'models', arguments: {
        action: 'add', providerId: 'anthropic', modelId: 'claude-sonnet', setDefault: false,
      }},
    });
    expect(parseSlashCommand('/models remove anthropic claude-sonnet')).toEqual({
      kind: 'provider-control', command: {intent: 'models', arguments: {
        action: 'remove', providerId: 'anthropic', modelId: 'claude-sonnet',
      }},
    });
    expect(parseSlashCommand('/task wait task-a 1500')).toEqual({
      kind: 'task', command: {action: 'wait', taskId: 'task-a', timeoutMillis: 1500},
    });
    expect(parseSlashCommand('/task cancel task-a')).toEqual({
      kind: 'task', command: {action: 'cancel', taskId: 'task-a'},
    });
    expect(parseSlashCommand('/task keep task-a')).toEqual({
      kind: 'task', command: {action: 'keep', taskId: 'task-a'},
    });
    expect(parseSlashCommand('/task remove task-a')).toEqual({
      kind: 'task', command: {action: 'remove', taskId: 'task-a'},
    });
  });

  it('keeps ordinary prompts out of the command path and rejects invalid inputs', () => {
    expect(parseSlashCommand('explain this repository')).toEqual({kind: 'not-command'});
    expect(parseSlashCommand('/unknown')).toEqual({kind: 'skill', name: 'unknown', arguments: ''});
    expect(parseSlashCommand('/unknown-skill keep these args')).toEqual({
      kind: 'skill', name: 'unknown-skill', arguments: 'keep these args',
    });
    expect(parseSlashCommand('/doctor extra').kind).toBe('invalid');
    expect(parseSlashCommand(`/compact ${Array.from({length: 17}, () => 'anchor').join(' ')}`).kind).toBe('invalid');
    expect(parseSlashCommand(`/compact ${'x'.repeat(513)}`).kind).toBe('invalid');
    expect(parseSlashCommand('/compact bad\0anchor').kind).toBe('invalid');
    expect(parseSlashCommand(`/model ${'x'.repeat(257)}`).kind).toBe('invalid');
    expect(parseSlashCommand('/permissions change').kind).toBe('invalid');
    expect(parseSlashCommand('/permissions mode plan').kind).toBe('invalid');
    expect(parseSlashCommand('/permissions mode PLAN extra').kind).toBe('invalid');
    expect(parseSlashCommand('/task wait task-a 0').kind).toBe('invalid');
    expect(parseSlashCommand('/task remove invalid').kind).toBe('invalid');
    expect(parseSlashCommand('/connect secret').kind).toBe('invalid');
    expect(parseSlashCommand('/connect anthropic personal env bad-name').kind).toBe('invalid');
    expect(parseSlashCommand('/connect anthropic personal env KEY extra').kind).toBe('invalid');
    expect(parseSlashCommand('/auth logout anthropic personal').kind).toBe('invalid');
    expect(parseSlashCommand('/auth logout anthropic personal yes').kind).toBe('invalid');
    expect(parseSlashCommand('/models add anthropic').kind).toBe('invalid');
    expect(parseSlashCommand('/models add anthropic model primary').kind).toBe('invalid');
    expect(parseSlashCommand('/models remove anthropic').kind).toBe('invalid');
    expect(parseSlashCommand('/models remove anthropic model extra').kind).toBe('invalid');
    expect(parseSlashCommand('/plan-approve digest-a')).toEqual({
      kind: 'skill', name: 'plan-approve', arguments: 'digest-a',
    });
    expect(parseSlashCommand(`/plan ${'x'.repeat(8_193)}`).kind).toBe('invalid');
  });

  it('在 Skill 入口前保护封闭命令的一次拼写错误且不受参数影响', () => {
    for (const [input, suggestion] of [
      ['/connet', '/connect'],
      ['/conect', '/connect'],
      ['/conenct', '/connect'],
      ['/connest', '/connect'],
      ['/modles', '/models'],
      ['/modles ignored arguments', '/models'],
      ['/taks task-a', '/task'],
      ['/hlep', '/help'],
    ] as const) {
      expect(parseSlashCommand(input)).toEqual({
        kind: 'invalid', message: `未知 Slash 命令；你是否想输入 ${suggestion}？`,
      });
    }

    expect(parseSlashCommand('/connext')).toEqual({kind: 'invalid', message: '未知 Slash 命令'});
    expect(parseSlashCommand('/contect ignored arguments')).toEqual({kind: 'invalid', message: '未知 Slash 命令'});
    expect(parseSlashCommand('/deploy-check')).toEqual({kind: 'skill', name: 'deploy-check', arguments: ''});
    expect(parseSlashCommand('/go')).toEqual({kind: 'skill', name: 'go', arguments: ''});
    expect(parseSlashCommand('/bad_name').kind).toBe('invalid');
  });

  it('把 /tasks 解析为无参数 Session 命令并保持 /task child 控制语义', () => {
    expect(parseSlashCommand('/tasks')).toEqual({
      kind: 'command', command: {intent: 'tasks', arguments: {}},
    });
    expect(parseSlashCommand('/tasks extra')).toEqual({
      kind: 'invalid', message: '/tasks 不接受参数',
    });
    expect(parseSlashCommand('/task wait task-a')).toEqual({
      kind: 'task', command: {action: 'wait', taskId: 'task-a', timeoutMillis: 30_000},
    });
  });

  it('renders fixed local status without server-provided text', () => {
    expect(renderSlashResult('compact', 'rejected', 'not_available'))
      .toBe('/compact 未执行：当前版本尚未提供');
    expect(renderSlashResult('compact', 'succeeded', 'ok')).toBe('/compact 已完成');
  });

  it('renders discoverable usage and structured safe command projections', () => {
    expect(slashCommandUsage('/context')).toContain('查看上下文用量');
    expect(slashCommandUsage('/permissions mode PLAN')).toBe('/permissions mode PLAN');
    const help = renderSlashResult('help', 'succeeded', 'ok', {commands: [
      {intent: 'help', support: 'available'},
      {intent: 'clear', support: 'available'},
      {intent: 'not-a-command', support: 'available'},
      {intent: 'doctor', support: 'unexpected'},
      'invalid-entry',
    ]});
    expect(help).toContain('/help — 查看命令与可用状态　[可用]');
    expect(help).toContain('/connect [provider profile [env ENV_NAME]]');
    expect(help).toContain('/auth list | probe');
    expect(help).toContain('/models [provider] | use');
    expect(help).not.toContain('/not-a-command');
    expect(help).not.toContain('/doctor — 查看安全诊断');
    expect(renderSlashResult('context', 'succeeded', 'ok', {
      systemTokens: 10, transcriptTokens: 20, toolTokens: 30, memoryTokens: 40,
      totalTokens: 100, availableInputTokens: 256000, freeTokens: 255900,
      overflowTokens: 0, sourceRevision: 1, estimateKind: 'ESTIMATED',
      contextStatus: 'WITHIN_BUDGET', modelRequestAttempts: 1,
      reductionStrategies: ['C1'], reasonCodes: [],
    })).toContain('总计 100 / 可输入 256000 / 剩余 255900');
    const permissions = renderSlashResult('permissions', 'succeeded', 'ok', {
      effectiveMode: 'PLAN', effectiveReviewer: 'USER', effectiveSelection: 'PLAN',
      modeSourceKind: 'SESSION', modeSafeSourceId: 'session',
      modeValidationStatus: 'VALID', startupRuleCount: 1,
      rules: [{ruleId: 'read-docs', sourceKind: 'PROJECT_SHARED', safeSourceId: 'project', operation: 'REPLACE', validationStatus: 'VALID'}],
    });
    expect(permissions).toContain('模式 PLAN · 审阅 USER · 选择 PLAN');
    expect(permissions).toContain('- read-docs · PROJECT_SHARED/project · REPLACE');
    expect(renderSlashResult('doctor', 'succeeded', 'ok', {
      settingsAvailable: true, settingsRevision: 3, instructionCount: 2,
      contextAvailable: true, activeRun: false,
      entries: [{component: 'SETTINGS', sourceKind: 'PROJECT_SHARED', safeId: 'project', code: 'PUBLISHED', severity: 'INFO'}],
    })).toContain('- SETTINGS · PROJECT_SHARED/project · PUBLISHED · INFO');
  });
});
