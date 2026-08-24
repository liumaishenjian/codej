import React from 'react';
import {Box, Text} from 'ink';
import type {RunView, ToolView} from './state.js';
import {visibleToolOutputWindow} from './interaction.js';
import {toolOutputStats} from './tool-output.js';

export function ToolDetail({run}: {readonly run: RunView}) {
  return <ToolDetailContent
    run={run}
    selectedOrdinal={run.toolDetailOrdinal}
    expanded={run.toolDetailExpanded === true}
    controls={run.status === 'running'}
  />;
}

/**
 * 在 live region 展示最近一个已完成 Run 的 Tool 详情，不修改已进入 Ink Static 的历史节点。
 */
export function HistoricalToolDetail({run, selectedOrdinal}: {
  readonly run: RunView;
  readonly selectedOrdinal: number | undefined;
}) {
  return <Box borderStyle="round" borderColor="gray" flexDirection="column" paddingX={1}>
    <Text bold>最近历史 Tool 详情</Text>
    <ToolDetailContent
      run={run}
      selectedOrdinal={selectedOrdinal}
      expanded
      controls={false}
    />
    <Text dimColor>Ctrl+T 选择 Tool · Ctrl+O 关闭详情</Text>
  </Box>;
}

function ToolDetailContent({run, selectedOrdinal, expanded, controls}: {
  readonly run: RunView;
  readonly selectedOrdinal: number | undefined;
  readonly expanded: boolean;
  readonly controls: boolean;
}) {
  const candidates = run.tools.filter(tool => tool.output.lines.length > 0);
  if (candidates.length === 0) return null;
  const selected = candidates.find(tool => tool.ordinal === selectedOrdinal) ?? candidates[0];
  if (selected === undefined) return null;
  const index = candidates.indexOf(selected) + 1;
  const stats = toolOutputStats(selected.output);
  const summary = [
    `详情 ${index}/${candidates.length} · ${selected.name}`,
    `stdout ${stats.stdoutLines}`,
    `stderr ${stats.stderrLines}`,
    stats.repeatedLines > 0 ? `压缩重复 ${stats.repeatedLines}` : undefined,
    selected.output.truncated ? '输出已截断' : undefined,
    selected.exitCode === undefined ? undefined : `exit ${selected.exitCode}`,
  ].filter((value): value is string => value !== undefined).join(' · ');
  const hint = controls
    ? ` · Ctrl+T 选择 · Ctrl+O ${expanded ? '折叠' : '展开'}`
    : '';

  return <Box marginLeft={4} flexDirection="column">
    <Text dimColor>{summary}{hint}</Text>
    {expanded ? <ExpandedToolOutput tool={selected} /> : null}
  </Box>;
}

function ExpandedToolOutput({tool}: {readonly tool: ToolView}) {
  const window = visibleToolOutputWindow(tool.output.lines);
  return <Box flexDirection="column">
    {window.omitted === 0 ? null : (
      <Text dimColor>显示末尾 {window.lines.length}/{window.total} 行</Text>
    )}
    {window.lines.map((line, index) => (
      line.stream === 'stderr' ? (
        <Text key={`${window.omitted + index}-${line.stream}`} color="yellow">
          stderr │ {line.text.length === 0 ? ' ' : line.text}
          {line.repetitions > 1 ? `  ×${line.repetitions}` : ''}
        </Text>
      ) : (
        <Text key={`${window.omitted + index}-${line.stream}`}>
          stdout │ {line.text.length === 0 ? ' ' : line.text}
          {line.repetitions > 1 ? `  ×${line.repetitions}` : ''}
        </Text>
      )
    ))}
    {tool.status === 'failed' || tool.status === 'denied' ? (
      <Text color="red">
        {tool.status === 'denied' ? 'denied' : 'failed'}
        {tool.failureCategory === undefined ? '' : ` · ${tool.failureCategory}`}
        {tool.errorCode === undefined ? '' : ` · ${tool.errorCode}`}
        {tool.exitCode === undefined ? '' : ` · exit ${tool.exitCode}`}
      </Text>
    ) : null}
  </Box>;
}
