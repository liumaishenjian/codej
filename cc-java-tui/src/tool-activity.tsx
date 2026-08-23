import {Box, Text} from 'ink';
import type {SearchMode, ToolView} from './state.js';

export interface ToolActivityGroupProps {
  readonly tools: readonly ToolView[];
}

interface ToolGroup {
  readonly name: string;
  readonly mode: SearchMode | undefined;
  readonly activity: string | undefined;
  readonly count: number;
  readonly status: ToolView['status'];
  readonly returnedCharacters: number;
  readonly returnedItems: number;
  readonly hasReturnedItems: boolean;
  readonly filteredItems: number;
  readonly truncatedCount: number;
  readonly truncationReason: string | undefined;
  readonly successfulCount: number;
  readonly failedCount: number;
  readonly deniedCount: number;
  readonly errorCode: string | undefined;
  readonly failureCategory: string | undefined;
  readonly retryable: boolean | undefined;
  readonly argumentChangeRequired: boolean;
  readonly strategyChangeRequired: boolean;
}

/**
 * 把连续同类 Tool 调用折叠为低噪声活动摘要。
 *
 * <p>组件只消费 Java stdio 事件中的脱敏元数据，不读取参数、路径或 Tool Result 正文。
 * 失败、拒绝和截断不会被成功摘要隐藏。</p>
 */
export function ToolActivityGroup({tools}: ToolActivityGroupProps) {
  if (tools.length === 0) {
    return null;
  }
  const groups = groupTools(tools);
  const window = boundedActivityWindow(groups);
  return (
    <Box flexDirection="column" marginLeft={2}>
      {window.omitted === undefined ? null : (
        <Text color={window.omitted.failed > 0 ? 'red' : 'yellow'} dimColor={window.omitted.failed === 0}>
          … 较早 {window.omitted.groups} 组 / {window.omitted.calls} 次 Tool
          {window.omitted.failed > 0 ? ` · ${window.omitted.failed} 次失败` : ''}
          {window.omitted.denied > 0 ? ` · ${window.omitted.denied} 次拒绝` : ''}
          {window.omitted.truncated > 0 ? ` · ${window.omitted.truncated} 次截断` : ''}
        </Text>
      )}
      {window.visible.map((group, index) => (
        <ToolActivityRow key={`${group.name}-${index}`} group={group} />
      ))}
    </Box>
  );
}

function ToolActivityRow({group}: {readonly group: ToolGroup}) {
  const appearance = toolAppearance(group);
  return (
    <Text color={appearance.color} dimColor={appearance.dim} wrap="truncate-end">
      {appearance.icon} {toolLabel(group.name, group.mode)}
      {group.count > 1 ? ` ×${group.count}` : ''}
      {group.activity === undefined ? '' : ` · ${compactToolActivity(group.activity)}`}
      {group.status === 'started' ? '（进行中）…' : formatToolDetails(group)}
    </Text>
  );
}

export function compactToolActivity(activity: string, maximum = 120): string {
  const singleLine = activity.replace(/\s+/gu, ' ').trim();
  const codePoints = Array.from(singleLine);
  if (codePoints.length <= maximum) return singleLine;
  return `${codePoints.slice(0, Math.max(1, maximum - 1)).join('')}…`;
}

function boundedActivityWindow(groups: readonly ToolGroup[]): {
  readonly visible: readonly ToolGroup[];
  readonly omitted: {
    readonly groups: number;
    readonly calls: number;
    readonly failed: number;
    readonly denied: number;
    readonly truncated: number;
  } | undefined;
} {
  const maximumRows = 8;
  if (groups.length <= maximumRows) return {visible: groups, omitted: undefined};
  const omittedGroups = groups.slice(0, groups.length - (maximumRows - 1));
  return {
    visible: groups.slice(-(maximumRows - 1)),
    omitted: {
      groups: omittedGroups.length,
      calls: omittedGroups.reduce((total, group) => total + group.count, 0),
      failed: omittedGroups.reduce((total, group) => total + group.failedCount, 0),
      denied: omittedGroups.reduce((total, group) => total + group.deniedCount, 0),
      truncated: omittedGroups.reduce((total, group) => total + group.truncatedCount, 0),
    },
  };
}

function groupTools(tools: readonly ToolView[]): readonly ToolGroup[] {
  const groups: ToolGroup[] = [];
  for (const tool of tools) {
    const previous = groups.at(-1);
    if (
      previous !== undefined
      && previous.name === tool.name
      && previous.mode === tool.mode
      && previous.activity === tool.activity
    ) {
      groups[groups.length - 1] = mergeTool(previous, tool);
    } else {
      groups.push(fromTool(tool));
    }
  }
  return groups;
}

function fromTool(tool: ToolView): ToolGroup {
  return {
    name: tool.name,
    mode: tool.mode,
    activity: tool.activity,
    count: 1,
    status: tool.status,
    returnedCharacters: tool.returnedCharacters ?? 0,
    returnedItems: tool.returnedItems ?? 0,
    hasReturnedItems: tool.returnedItems !== undefined,
    filteredItems: tool.filteredItems ?? 0,
    truncatedCount: tool.truncated ? 1 : 0,
    truncationReason: tool.truncationReason,
    successfulCount: tool.status === 'success' ? 1 : 0,
    failedCount: tool.status === 'failed' ? 1 : 0,
    deniedCount: tool.status === 'denied' ? 1 : 0,
    errorCode: tool.errorCode,
    failureCategory: tool.failureCategory,
    retryable: tool.retryable,
    argumentChangeRequired: tool.argumentChangeRequired,
    strategyChangeRequired: tool.strategyChangeRequired,
  };
}

function mergeTool(group: ToolGroup, tool: ToolView): ToolGroup {
  return {
    ...group,
    count: group.count + 1,
    status: strongerStatus(group.status, tool.status),
    returnedCharacters: group.returnedCharacters + (tool.returnedCharacters ?? 0),
    returnedItems: group.returnedItems + (tool.returnedItems ?? 0),
    hasReturnedItems: group.hasReturnedItems || tool.returnedItems !== undefined,
    filteredItems: group.filteredItems + (tool.filteredItems ?? 0),
    truncatedCount: group.truncatedCount + (tool.truncated ? 1 : 0),
    truncationReason: tool.truncationReason ?? group.truncationReason,
    successfulCount: group.successfulCount + (tool.status === 'success' ? 1 : 0),
    failedCount: group.failedCount + (tool.status === 'failed' ? 1 : 0),
    deniedCount: group.deniedCount + (tool.status === 'denied' ? 1 : 0),
    errorCode: tool.errorCode ?? group.errorCode,
    failureCategory: tool.failureCategory ?? group.failureCategory,
    retryable: tool.retryable ?? group.retryable,
    argumentChangeRequired: group.argumentChangeRequired || tool.argumentChangeRequired,
    strategyChangeRequired: group.strategyChangeRequired || tool.strategyChangeRequired,
  };
}

function strongerStatus(
  left: ToolView['status'],
  right: ToolView['status'],
): ToolView['status'] {
  const rank: Record<ToolView['status'], number> = {
    success: 0,
    started: 1,
    denied: 2,
    failed: 3,
  };
  return rank[right] > rank[left] ? right : left;
}

function formatToolDetails(group: ToolGroup): string {
  const details: string[] = [];
  const itemSummary = formatReturnedItems(group);
  if (itemSummary !== undefined) {
    details.push(itemSummary);
  } else if (group.returnedCharacters > 0) {
    details.push(`${formatCount(group.returnedCharacters)} 字符`);
  }
  if (group.filteredItems > 0) {
    details.push(`过滤 ${group.filteredItems} 项`);
  }
  if (group.truncatedCount > 0) {
    details.push(truncationLabel(group.truncationReason));
  }
  if (group.failedCount > 1 || (group.failedCount > 0 && group.successfulCount > 0)) {
    details.push(`${group.failedCount} 次失败`);
  }
  if (group.deniedCount > 1 || (group.deniedCount > 0 && group.successfulCount > 0)) {
    details.push(`${group.deniedCount} 次拒绝`);
  }
  if (group.strategyChangeRequired) {
    details.push('已阻止相同失败重试');
  } else if (group.argumentChangeRequired) {
    details.push('需要修改参数');
  }
  if (group.errorCode !== undefined) details.push(group.errorCode);
  if (group.failureCategory !== undefined) {
    details.push(`${group.failureCategory}${group.retryable === true ? ' · retryable' : ''}`);
  }
  return details.length === 0 ? '' : ` · ${details.join(' · ')}`;
}

function formatReturnedItems(group: ToolGroup): string | undefined {
  if (!group.hasReturnedItems) {
    return undefined;
  }
  if (group.name === 'search_text') {
    switch (group.mode) {
      case 'content':
        return `${group.returnedItems} 处匹配`;
      case 'files':
        return `${group.returnedItems} 个文件`;
      case 'count':
        return `${group.returnedItems} 个文件已统计`;
      case undefined:
        return `${group.returnedItems} 条结果`;
    }
  }
  if (group.returnedItems === 0) {
    return undefined;
  }
  if (group.name === 'read_file') {
    return `${group.returnedItems} 行`;
  }
  return `${group.returnedItems} 项`;
}

function truncationLabel(reason: string | undefined): string {
  switch (reason) {
    case 'item_limit':
      return '结果已截断';
    case 'pipeline_character_limit':
      return '输出已截断';
    case 'file_limit':
    case 'scan_byte_limit':
      return '扫描受限';
    default:
      return '已截断';
  }
}

function formatCount(value: number): string {
  if (value < 1_000) {
    return String(value);
  }
  return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)}k`;
}

function toolAppearance(group: ToolGroup): {
  readonly icon: string;
  readonly color: 'green' | 'yellow' | 'red' | 'cyan';
  readonly dim: boolean;
} {
  if (
    (group.failedCount > 0 || group.deniedCount > 0)
    && group.successfulCount > 0
  ) {
    return {icon: '!', color: 'yellow', dim: false};
  }
  switch (group.status) {
    case 'started':
      return {icon: '●', color: 'yellow', dim: false};
    case 'success':
      return {icon: '✓', color: 'green', dim: true};
    case 'denied':
      return {icon: '!', color: 'yellow', dim: false};
    case 'failed':
      return {icon: '✗', color: 'red', dim: false};
  }
}

function toolLabel(name: string, mode: SearchMode | undefined): string {
  switch (name) {
    case 'search_text':
      return mode === 'files'
        ? '搜索文件'
        : mode === 'count' ? '统计匹配' : '搜索内容';
    case 'read_file':
      return '阅读文件';
    case 'list_files':
      return '枚举文件';
    case 'git_status':
      return '检查工作区';
    case 'git_diff':
      return '查看变更';
    default:
      return name;
  }
}
