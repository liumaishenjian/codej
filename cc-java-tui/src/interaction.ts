import {activeFileMention} from './file-mention.js';
import type {ComposerState} from './input-editor.js';

/**
 * TUI 交互辅助：补全回车、通知分级、等待指示和有界输出窗口。
 *
 * <p>这些函数只描述终端 Surface 的本地交互决策，不发送协议命令，也不改变
 * Java 权威的审批、权限或 Run 终态。</p>
 *
 * @since 0.1.1
 */

export type NoticeTone = 'info' | 'success' | 'warning' | 'error';

export const MAX_TOOL_DETAIL_VISIBLE_LINES = 12;
export const MIN_COMPLETION_REGION_ROWS = 3;
export const ACTIVITY_SPINNER_FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧'] as const;

const graphemeSegmenter = new Intl.Segmenter('en', {granularity: 'grapheme'});

/**
 * 回车只在候选项仍是当前输入的真超集时消费为补全。
 *
 * <p>已经打完整命令、或当前 mention 已等于选中路径时，回车必须提交，避免二次 Enter。</p>
 */
export function shouldAcceptCompletionOnEnter(state: ComposerState): boolean {
  const selected = state.completionCandidates[state.completionIndex ?? 0];
  if (selected === undefined) return false;
  const trimmed = state.text.trim();
  if (trimmed === '/permissions' || trimmed === '/plan') return false;
  if (selected.startsWith('@')) {
    const mention = activeFileMention(state);
    if (mention === undefined) return false;
    const current = graphemeSlice(state.text, mention.startGrapheme, mention.endGrapheme);
    return current !== selected;
  }
  return selected !== trimmed && selected.startsWith(trimmed);
}

/** 把通知文案投影为终端色调；不解析服务端自由文本语义。 */
export function classifyNotice(message: string): NoticeTone {
  if (/(失败|拒绝|未执行|未能|不能|不可|损坏|超时|关闭|错误|非法|冲突|未通过|未启动)/u.test(message)) {
    return 'error';
  }
  if (/(已完成|已批准|已保存|已验证|已提交|已接受|已添加|已移除|已删除)/u.test(message)) {
    return 'success';
  }
  if (/(等待|排队|纠正|请|仍在|尚未|恢复|警告)/u.test(message)) {
    return 'warning';
  }
  return 'info';
}

export function noticeAppearance(tone: NoticeTone): {
  readonly color: 'cyan' | 'green' | 'yellow' | 'red';
  readonly icon: string;
} {
  switch (tone) {
    case 'success':
      return {color: 'green', icon: '✓'};
    case 'warning':
      return {color: 'yellow', icon: '!'};
    case 'error':
      return {color: 'red', icon: '✗'};
    case 'info':
      return {color: 'cyan', icon: '•'};
  }
}

export function activitySpinnerFrame(tick: number): string {
  const index = ((tick % ACTIVITY_SPINNER_FRAMES.length) + ACTIVITY_SPINNER_FRAMES.length)
    % ACTIVITY_SPINNER_FRAMES.length;
  return ACTIVITY_SPINNER_FRAMES[index]!;
}

/**
 * 为流式 Markdown 补上未闭合围栏，避免代码框在 token 边界反复重排。
 *
 * <p>只处理行首围栏计数；解析失败时调用方仍应回退纯文本。</p>
 */
export function stabilizeStreamingMarkdown(text: string): string {
  let fences = 0;
  for (const line of text.split('\n')) {
    if (line.startsWith('```')) fences++;
  }
  return fences % 2 === 1 ? `${text}\n${'`'.repeat(3)}` : text;
}

export function visibleToolOutputWindow<T>(
  lines: readonly T[],
  maximum = MAX_TOOL_DETAIL_VISIBLE_LINES,
): {
  readonly lines: readonly T[];
  readonly omitted: number;
  readonly total: number;
} {
  const limit = Math.max(1, Math.floor(maximum));
  if (lines.length <= limit) return {lines, omitted: 0, total: lines.length};
  return {lines: lines.slice(-limit), omitted: lines.length - limit, total: lines.length};
}

export function completionRegionRows(
  candidateCount: number,
  viewportRows: number | undefined,
  composerFixedRows: number,
): number | undefined {
  if (viewportRows === undefined) return undefined;
  if (candidateCount === 0) return 0;
  const available = viewportRows - 1 - composerFixedRows;
  if (available <= 0) return MIN_COMPLETION_REGION_ROWS;
  return Math.max(MIN_COMPLETION_REGION_ROWS, available);
}

export function isInsertNewlineKey(
  key: {
    readonly shift?: boolean;
    readonly return?: boolean;
    readonly ctrl?: boolean;
    readonly meta?: boolean;
  },
  text: string,
): boolean {
  if (key.shift === true && key.return === true) return true;
  if (key.meta === true && key.return === true) return true;
  if (key.ctrl === true && (text === 'j' || text === 'J' || text === '\n')) return true;
  return false;
}

/**
 * 空白后的单个反斜杠作为窄终端换行降级。
 *
 * <p>连续两个反斜杠视为字面量；紧贴路径字符的尾部 {@code \\}（如
 * {@code C:\\Users\\foo\\}）不得吞掉 Enter，否则 Windows 路径无法提交。</p>
 */
export function hasTrailingNewlineEscape(text: string): boolean {
  if (!text.endsWith('\\') || text.endsWith('\\\\')) return false;
  const prefix = text.slice(0, -1);
  return prefix.length === 0 || /\s$/u.test(prefix);
}

export interface PlanFeedbackDraft {
  readonly text: string;
  readonly cursor: number;
}

export function createPlanFeedbackDraft(text = ''): PlanFeedbackDraft {
  const bounded = boundPlanFeedback(text);
  return {text: bounded, cursor: graphemes(bounded).length};
}

export function editPlanFeedback(
  draft: PlanFeedbackDraft,
  action:
    | {readonly type: 'insert'; readonly text: string}
    | {readonly type: 'paste'; readonly text: string}
    | {readonly type: 'backspace'}
    | {readonly type: 'delete'}
    | {readonly type: 'left'}
    | {readonly type: 'right'}
    | {readonly type: 'home'}
    | {readonly type: 'end'},
): PlanFeedbackDraft {
  const units = graphemes(draft.text);
  const cursor = Math.max(0, Math.min(draft.cursor, units.length));
  switch (action.type) {
    case 'left':
      return {text: draft.text, cursor: Math.max(0, cursor - 1)};
    case 'right':
      return {text: draft.text, cursor: Math.min(units.length, cursor + 1)};
    case 'home':
      return {text: draft.text, cursor: 0};
    case 'end':
      return {text: draft.text, cursor: units.length};
    case 'backspace': {
      if (cursor === 0) return {text: draft.text, cursor};
      const next = [...units.slice(0, cursor - 1), ...units.slice(cursor)];
      return {text: boundPlanFeedback(next.join('')), cursor: cursor - 1};
    }
    case 'delete': {
      if (cursor >= units.length) return {text: draft.text, cursor};
      const next = [...units.slice(0, cursor), ...units.slice(cursor + 1)];
      return {text: boundPlanFeedback(next.join('')), cursor};
    }
    case 'insert':
    case 'paste': {
      const inserted = graphemes(action.text);
      const next = boundPlanFeedback([...units.slice(0, cursor), ...inserted, ...units.slice(cursor)].join(''));
      const nextUnits = graphemes(next);
      return {text: next, cursor: Math.min(nextUnits.length, cursor + inserted.length)};
    }
  }
}

/**
 * 按与编辑同一套 grapheme 切分反馈草稿，避免渲染光标和左右移动错位。
 */
export function planFeedbackCursorParts(draft: PlanFeedbackDraft): {
  readonly before: string;
  readonly at: string | undefined;
  readonly after: string;
} {
  const units = graphemes(draft.text);
  const cursor = Math.max(0, Math.min(draft.cursor, units.length));
  return {
    before: units.slice(0, cursor).join(''),
    at: units[cursor],
    after: units.slice(cursor + 1).join(''),
  };
}

function boundPlanFeedback(text: string): string {
  return graphemes(text).slice(0, 4_000).join('');
}

function graphemes(text: string): readonly string[] {
  return [...graphemeSegmenter.segment(text)].map(item => item.segment);
}

function graphemeSlice(text: string, start: number, end: number): string {
  return graphemes(text).slice(start, end).join('');
}
