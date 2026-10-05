import {useMemo, useRef} from 'react';
import {Box, Static} from 'ink';
import type {RecordBlock, RuntimeSnapshot} from './runtime.js';
import {newRuntimeUi, readingState, runtimeFrame, runtimeViewportHeight, type RuntimeUi} from './runtime-screen.js';
import {RowView, type Row} from './screen.js';

/** 只封存不会再被当前回合修正的前缀；调用分组和审核恢复仍由原事件链决定。 */
export function stableHistoryLength(state: RuntimeSnapshot, sealed = 0): number {
  if (state.status === 'idle') return state.blocks.length;
  let index = sealed;
  while (index < state.blocks.length) {
    const block = state.blocks[index]!;
    if (block.kind === 'user' || block.kind === 'notice') {index++; continue;}
    if (block.kind === 'assistant') {
      // 同一模型回合的finalText仍可修正工具前说明；看到后续回合才封存。
      if (!state.blocks.slice(index + 1).some(next => next.kind === 'user'
        || ((next.kind === 'assistant' || next.kind === 'tool') && next.run === block.run && next.turn > block.turn))) break;
      index++; continue;
    }
    if (block.kind !== 'tool') break;
    let end = index + 1;
    while (end < state.blocks.length) {
      const next = state.blocks[end]!;
      if (next.kind !== 'tool' || next.name !== block.name || next.run !== block.run || next.turn !== block.turn) break;
      end++;
    }
    if (end === state.blocks.length || state.blocks.slice(index, end).some(item => item.kind === 'tool'
      && (item.status === 'running' || (item.status === 'failed' && /^(declare_plan_evidence|request_plan_review)$/.test(item.name))))) break;
    index = end;
  }
  return index;
}

/** Static仅追加稳定内容；普通模式不启用鼠标捕获，滚动和选取交给终端。 */
export function NativeHistoryScreen({state, ui, columns, rows, now, authorizationUrl}: {state: RuntimeSnapshot; ui: RuntimeUi; columns: number; rows: number; now: number; authorizationUrl?: string}) {
  const history = useRef<{count: number; entries: {id: number; rows: Row[]}[]}>({count: 0, entries: []});
  const sealed = history.current.count;
  const end = useMemo(() => !sealed && !state.model && state.status !== 'idle' ? 0 : stableHistoryLength(state, sealed),
    [state.blocks, state.status, state.model, sealed]);
  if (end > history.current.count && columns >= 40 && rows >= 24 && !ui.expanded) {
    const blocks: RecordBlock[] = state.blocks.slice(history.current.count, end);
    const frame = runtimeFrame({...state, blocks}, newRuntimeUi(), columns, rows, now, history.current.count > 0, authorizationUrl);
    history.current.entries = [...history.current.entries, {id: end, rows: frame.bodyRows}];
    history.current.count = end;
  }
  const count = history.current.count;
  const liveBlocks = useMemo(() => state.blocks.slice(count), [state.blocks, count]);
  const live = ui.expanded ? readingState(state, ui) : {...state, blocks: liveBlocks};
  const frame = runtimeFrame(live, ui.expanded ? ui : {...ui, scroll: 0}, columns, runtimeViewportHeight(rows), now, !ui.expanded && history.current.count > 0, authorizationUrl);
  return <Box flexDirection="column">
    <Static items={history.current.entries}>{entry => <RowView key={entry.id} rows={entry.rows} columns={columns}/>}</Static>
    <RowView rows={frame.rows} columns={columns}/>
  </Box>;
}
