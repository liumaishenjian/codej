import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {compactToolActivity, ToolActivityGroup} from '../src/tool-activity.js';
import type {ToolView} from '../src/state.js';

describe('ToolActivityGroup', () => {
  it('把长 activity 归一为有界单行', () => {
    const compact = compactToolActivity(`run  command\n${'x'.repeat(200)}`, 32);
    expect(compact).not.toContain('\n');
    expect(Array.from(compact)).toHaveLength(32);
    expect(compact).toMatch(/^run command /u);
    expect(compact).toMatch(/…$/u);
  });

  it('聚合连续同类 Tool 并显示有界元数据', () => {
    const tools: ToolView[] = [
      {...tool(1, 'search_text', 'success', 900), mode: 'content', returnedItems: 7},
      {
        ...tool(2, 'search_text', 'success', 1_200),
        mode: 'content',
        returnedItems: 5,
        truncated: true,
        truncationReason: 'item_limit',
      },
      {...tool(3, 'read_file', 'success', 800), returnedItems: 20},
    ];
    const view = render(<ToolActivityGroup tools={tools} />);
    const frame = view.lastFrame();

    expect(frame).toContain('搜索内容 ×2 · 12 处匹配 · 结果已截断');
    expect(frame).toContain('阅读文件 · 20 行');
    expect(frame).not.toContain('[tool 1]');
  });

  it('活动、失败与拒绝使用清晰且不泄漏结果的摘要', () => {
    const tools: ToolView[] = [
      {...tool(1, 'search_text', 'started'), mode: 'content'},
      {...tool(2, 'read_file', 'failed'), errorCode: 'READ_FAILED'},
      tool(3, 'git_diff', 'denied'),
    ];
    const view = render(<ToolActivityGroup tools={tools} />);
    const frame = view.lastFrame();

    expect(frame).toContain('搜索内容（进行中）');
    expect(frame).toContain('阅读文件 · READ_FAILED');
    expect(frame).toContain('查看变更');
  });

  it('展示 Java 白名单活动摘要且不同目标不错误聚合', () => {
    const tools: ToolView[] = [
      {...tool(1, 'read_file', 'success', 20), activity: '读取 src/App.java'},
      {...tool(2, 'read_file', 'success', 30), activity: '读取 README.md'},
    ];
    const frame = render(<ToolActivityGroup tools={tools} />).lastFrame() ?? '';
    expect(frame).toContain('阅读文件 · 读取 src/App.java');
    expect(frame).toContain('阅读文件 · 读取 README.md');
    expect(frame).not.toContain('阅读文件 ×2');
  });

  it('大量异构历史活动保持有界并汇总被折叠区间的失败事实', () => {
    const tools = Array.from({length: 30}, (_, index) => ({
      ...tool(index + 1, `tool_${index + 1}`, index === 2 ? 'failed' : index === 4 ? 'denied' : 'success'),
      activity: `activity-${index + 1}`,
      truncated: index === 6,
    } satisfies ToolView));
    const frame = render(<ToolActivityGroup tools={tools} />).lastFrame() ?? '';

    expect(frame.split('\n')).toHaveLength(8);
    expect(frame).toContain('较早 23 组 / 23 次 Tool');
    expect(frame).toContain('1 次失败');
    expect(frame).toContain('1 次拒绝');
    expect(frame).toContain('1 次截断');
    expect(frame).not.toContain('activity-1');
    expect(frame).toContain('activity-30');
  });

  it('连续参数失败显示纠错与重复阻断而不是只有调用次数', () => {
    const tools: ToolView[] = [
      {
        ...tool(1, 'search_text', 'failed'),
        mode: 'content',
        errorCode: 'invalid_arguments',
        failureCategory: 'validation',
        retryable: false,
        argumentChangeRequired: true,
      },
      {
        ...tool(2, 'search_text', 'failed'),
        mode: 'content',
        errorCode: 'repeated_failure',
        failureCategory: 'internal',
        retryable: false,
        strategyChangeRequired: true,
      },
    ];

    const frame = render(<ToolActivityGroup tools={tools} />).lastFrame() ?? '';

    expect(frame).toContain('搜索内容 ×2');
    expect(frame).toContain('已阻止相同失败重试');
    expect(frame).not.toMatch(/^✗ 搜索内容 ×2$/mu);
  });

  it('同类 Tool 失败后恢复时显示混合结果而不是伪装成全失败', () => {
    const tools: ToolView[] = [
      {
        ...tool(1, 'search_text', 'failed'),
        mode: 'content',
        errorCode: 'INVALID_ARGUMENTS',
        failureCategory: 'validation',
        retryable: false,
      },
      {
        ...tool(2, 'search_text', 'success', 2_000),
        mode: 'content',
        returnedItems: 4,
      },
    ];
    const view = render(<ToolActivityGroup tools={tools} />);
    const frame = view.lastFrame();

    expect(frame).toContain('! 搜索内容 ×2 · 4 处匹配');
    expect(frame).toContain('1 次失败');
    expect(frame).toContain('INVALID_ARGUMENTS');
  });
});

function tool(
  ordinal: number,
  name: string,
  status: ToolView['status'],
  returnedCharacters?: number,
): ToolView {
  return {
    ordinal,
    name,
    mode: undefined,
    status,
    returnedCharacters,
    returnedItems: undefined,
    filteredItems: undefined,
    truncated: false,
    truncationReason: undefined,
    errorCode: undefined,
    failureCategory: undefined,
    retryable: undefined,
    argumentChangeRequired: false,
    strategyChangeRequired: false,
    exitCode: undefined,
    output: {lines: [], characters: 0, truncated: false},
  };
}
