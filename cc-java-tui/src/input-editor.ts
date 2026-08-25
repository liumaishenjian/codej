export const MAX_VISIBLE_STRUCTURE_UNITS = 8_192;
/** @deprecated Use MAX_VISIBLE_STRUCTURE_UNITS. */
export const MAX_INPUT_CODE_POINTS = MAX_VISIBLE_STRUCTURE_UNITS;
export const MAX_HISTORY_ENTRIES = 100;
export const MAX_COMPLETION_CANDIDATES = 32;
export const MAX_PASTE_PAYLOADS = 32;
export const MAX_PASTE_ITEM_UTF8_BYTES = 1_048_576;
export const MAX_PASTE_TOTAL_UTF8_BYTES = 1_048_576;
export const MAX_HISTORY_PASTE_UTF8_BYTES = 1_048_576;
export const MAX_SUBMISSION_CODE_POINTS = 1_048_576;
export const MAX_SUBMISSION_UTF16_UNITS = 1_048_576;
export const MAX_SUBMISSION_UTF8_BYTES = 1_048_576;
export const LARGE_PASTE_DISPLAY_THRESHOLD_UTF8_BYTES = 1_024;

const encoder = new TextEncoder();
const graphemeSegmenter = new Intl.Segmenter('en', {granularity: 'grapheme'});
const TOKEN_START = String.fromCodePoint(0xF0000);
const TOKEN_END = String.fromCodePoint(0xF0001);
const TOKEN_PATTERN = new RegExp(`${TOKEN_START}paste:([1-9][0-9]*)${TOKEN_END}`, 'gu');

const COMMAND_COMPLETIONS = [
  '/clear', '/compact', '/context', '/doctor', '/help', '/model', '/permissions', '/resume',
  '/tasks', '/plan-status', '/plan',
] as const;
const ARGUMENT_COMPLETIONS: Readonly<Record<string, readonly string[]>> = {
  '/permissions': [
    '/permissions mode ACCEPT_EDITS',
    '/permissions mode DEFAULT',
    '/permissions mode PLAN',
    '/permissions query',
  ],
};

export type ComposerValidationCode =
  | 'VISIBLE_STRUCTURE_LIMIT'
  | 'PASTE_COUNT_LIMIT'
  | 'PASTE_ITEM_LIMIT'
  | 'PASTE_TOTAL_LIMIT'
  | 'PASTE_REFERENCE_FORGED'
  | 'PASTE_REFERENCE_STALE'
  | 'PASTE_REFERENCE_DUPLICATE'
  | 'PASTE_REFERENCE_ORPHAN'
  | 'SUBMISSION_CODE_POINT_LIMIT'
  | 'SUBMISSION_UTF16_LIMIT'
  | 'SUBMISSION_UTF8_LIMIT';

export interface PastePayload {
  readonly id: number;
  readonly text: string;
  readonly utf8Bytes: number;
}

export interface ComposerDocument {
  readonly text: string;
  readonly pastePayloads: ReadonlyMap<number, PastePayload>;
}

export interface ComposerState extends ComposerDocument {
  readonly cursorGrapheme: number;
  readonly preferredVisualColumn: number | undefined;
  readonly viewportTop: number;
  readonly viewportHeight: number;
  readonly historyEntries: readonly ComposerDocument[];
  readonly historyIndex: number | undefined;
  readonly historyDraft: ComposerDocument | undefined;
  readonly completionCandidates: readonly string[];
  readonly completionIndex: number | undefined;
  readonly nextPasteOrdinal: number;
  readonly validationCode: ComposerValidationCode | undefined;
}

export interface ComposerLayout {
  readonly width: number;
  readonly height: number;
}

export interface ComposerVisualLine {
  readonly startGrapheme: number;
  readonly endGrapheme: number;
  readonly width: number;
}

export interface ComposerProjection {
  readonly lines: readonly ComposerVisualLine[];
  readonly cursorRow: number;
  readonly cursorColumn: number;
  readonly viewportTop: number;
  readonly viewportHeight: number;
  readonly visibleLines: readonly ComposerVisualLine[];
  readonly structureUnits: number;
}

export type ComposerAction =
  | {readonly type: 'InsertText'; readonly text: string}
  | {readonly type: 'Paste'; readonly text: string}
  | {readonly type: 'MoveLeft' | 'MoveRight' | 'MoveUp' | 'MoveDown' | 'MoveHome' | 'MoveEnd' | 'MoveWordLeft' | 'MoveWordRight' | 'Backspace' | 'DeleteForward' | 'HistoryPrevious' | 'HistoryNext' | 'CompletionPrevious' | 'CompletionNext' | 'AcceptCompletion' | 'CloseCompletion' | 'Submit' | 'Clear'}
  | {readonly type: 'Resize'; readonly width?: number; readonly height?: number}
  | {readonly type: 'SetCompletions'; readonly candidates: readonly string[]}
  | {readonly type: 'ReplaceRange'; readonly startGrapheme: number; readonly endGrapheme: number; readonly text: string};

export type ComposerTransition =
  | {readonly kind: 'updated'; readonly state: ComposerState}
  | {readonly kind: 'submit-ready'; readonly state: ComposerState; readonly expandedText: string}
  | {readonly kind: 'submission-rejected'; readonly state: ComposerState; readonly code: ComposerValidationCode};

interface EditorUnit {
  readonly raw: string;
  readonly kind: 'grapheme' | 'paste';
  readonly payloadId?: number;
}

interface InternalVisualLine extends ComposerVisualLine {
  readonly columns: readonly number[];
}

export function createComposerState(viewportHeight = 1): ComposerState {
  return {
    text: '', cursorGrapheme: 0, preferredVisualColumn: undefined,
    viewportTop: 0, viewportHeight: normalizeHeight(viewportHeight),
    historyEntries: [], historyIndex: undefined, historyDraft: undefined,
    completionCandidates: [], completionIndex: undefined,
    pastePayloads: new Map(), nextPasteOrdinal: 1, validationCode: undefined,
  };
}

export function reduceComposer(
  state: ComposerState,
  action: ComposerAction,
  layout: ComposerLayout,
): ComposerTransition {
  const effectiveLayout = action.type === 'Resize'
    ? {width: action.width ?? layout.width, height: action.height ?? layout.height}
    : layout;
  const units = editorUnits(state.text);
  let next = state;

  switch (action.type) {
    case 'InsertText':
      return insertLiteral(state, action.text, effectiveLayout);
    case 'ReplaceRange':
      return replaceRange(state, action.startGrapheme, action.endGrapheme, action.text, effectiveLayout);
    case 'Paste':
      return pasteText(state, action.text, effectiveLayout);
    case 'MoveLeft':
      next = moveCursor(state, Math.max(0, state.cursorGrapheme - 1), effectiveLayout, true);
      break;
    case 'MoveRight':
      next = moveCursor(state, Math.min(units.length, state.cursorGrapheme + 1), effectiveLayout, true);
      break;
    case 'MoveHome': {
      let cursor = state.cursorGrapheme;
      while (cursor > 0 && units[cursor - 1]?.raw !== '\n') cursor--;
      next = moveCursor(state, cursor, effectiveLayout, true);
      break;
    }
    case 'MoveEnd': {
      let cursor = state.cursorGrapheme;
      while (cursor < units.length && units[cursor]?.raw !== '\n') cursor++;
      next = moveCursor(state, cursor, effectiveLayout, true);
      break;
    }
    case 'MoveWordLeft':
      next = moveCursor(state, wordLeft(units, state.cursorGrapheme), effectiveLayout, true);
      break;
    case 'MoveWordRight':
      next = moveCursor(state, wordRight(units, state.cursorGrapheme), effectiveLayout, true);
      break;
    case 'MoveUp':
    case 'MoveDown': {
      if (state.completionCandidates.length > 0) {
        return reduceComposer(state, {type: action.type === 'MoveUp' ? 'CompletionPrevious' : 'CompletionNext'}, effectiveLayout);
      }
      const moved = moveVertical(state, action.type === 'MoveUp' ? -1 : 1, effectiveLayout);
      if (moved !== undefined) next = moved;
      else return reduceComposer(state, {type: action.type === 'MoveUp' ? 'HistoryPrevious' : 'HistoryNext'}, effectiveLayout);
      break;
    }
    case 'Backspace':
      if (state.cursorGrapheme === 0) return updated(withViewport(state, effectiveLayout));
      return deleteRange(state, state.cursorGrapheme - 1, state.cursorGrapheme, effectiveLayout);
    case 'DeleteForward':
      if (state.cursorGrapheme >= units.length) return updated(withViewport(state, effectiveLayout));
      return deleteRange(state, state.cursorGrapheme, state.cursorGrapheme + 1, effectiveLayout);
    case 'HistoryPrevious':
      next = historyPrevious(state, effectiveLayout);
      break;
    case 'HistoryNext':
      next = historyNext(state, effectiveLayout);
      break;
    case 'CompletionPrevious':
      next = cycleCompletion(state, -1);
      break;
    case 'CompletionNext':
      next = cycleCompletion(state, 1);
      break;
    case 'AcceptCompletion': {
      const selected = selectedCompletion(state);
      if (selected === undefined) return updated(withViewport(state, effectiveLayout));
      const insertion = insertLiteral(
        state,
        selected.startsWith(state.text) ? selected.slice(state.text.length) : selected,
        effectiveLayout,
      );
      if (insertion.kind !== 'updated') return insertion;
      return updated({...insertion.state, completionCandidates: [], completionIndex: undefined});
    }
    case 'CloseCompletion':
      next = {...state, completionCandidates: [], completionIndex: undefined};
      break;
    case 'SetCompletions': {
      const candidates = [...action.candidates].slice(0, MAX_COMPLETION_CANDIDATES);
      next = {...state, completionCandidates: candidates, completionIndex: candidates.length === 0 ? undefined : 0};
      break;
    }
    case 'Resize':
      next = {...state, viewportHeight: normalizeHeight(effectiveLayout.height)};
      break;
    case 'Clear':
      next = {
        ...state, text: '', cursorGrapheme: 0, preferredVisualColumn: undefined,
        viewportTop: 0, historyIndex: undefined, historyDraft: undefined,
        completionCandidates: [], completionIndex: undefined,
        pastePayloads: new Map(), validationCode: undefined,
      };
      break;
    case 'Submit': {
      const expansion = expandSubmission(state);
      if ('code' in expansion) return rejected(state, expansion.code);
      return {kind: 'submit-ready', state: {...state, validationCode: undefined}, expandedText: expansion.text};
    }
  }
  return updated(withViewport({...next, validationCode: undefined}, effectiveLayout));
}

/** 把已发送但未获 Java 确认的文档从活动草稿中分离，payload 仍由 pending snapshot 持有。 */
export function beginPendingComposer(state: ComposerState): ComposerState {
  return {
    ...state, text: '', cursorGrapheme: 0, preferredVisualColumn: undefined,
    viewportTop: 0, historyIndex: undefined, historyDraft: undefined,
    completionCandidates: [], completionIndex: undefined, pastePayloads: new Map(), validationCode: undefined,
  };
}

/** 在 Java 确认后只把 pending 文档加入历史，绝不覆盖确认期间形成的新草稿。 */
export function acceptPendingComposer(
  current: ComposerState,
  pending: ComposerDocument,
): ComposerState {
  const document = cloneDocument(pending);
  const entries = document.text.length === 0
    ? current.historyEntries
    : retainHistoryDocuments([...current.historyEntries, document]);
  return {...current, historyEntries: entries, validationCode: undefined};
}

/** 协议拒绝时把原提交置于后续草稿之前，并合并双方 payload 所有权。 */
export function restoreRejectedComposer(
  current: ComposerState,
  pending: ComposerState,
): ComposerState {
  const pendingUnits = editorUnits(pending.text).length;
  const payloads = new Map(pending.pastePayloads);
  for (const [id, payload] of current.pastePayloads) {
    if (payloads.has(id)) throw new Error('pending 与活动草稿的 paste ID 冲突');
    payloads.set(id, payload);
  }
  return {
    ...current,
    text: pending.text + current.text,
    cursorGrapheme: pendingUnits + current.cursorGrapheme,
    pastePayloads: payloads,
    nextPasteOrdinal: Math.max(current.nextPasteOrdinal, pending.nextPasteOrdinal),
    preferredVisualColumn: undefined,
    viewportTop: 0,
    completionCandidates: [],
    completionIndex: undefined,
    historyIndex: undefined,
    historyDraft: undefined,
    validationCode: undefined,
  };
}

/** Records a transport-accepted submission, then clears only the active draft. */
export function acceptSubmittedComposer(state: ComposerState): ComposerState {
  return acceptPendingComposer(beginPendingComposer(state), state);
}

export function projectComposer(state: ComposerState, layout: ComposerLayout): ComposerProjection {
  const lines = visualLines(editorUnits(state.text), normalizeWidth(layout.width));
  const position = cursorPosition(lines, state.cursorGrapheme);
  const viewportHeight = normalizeHeight(layout.height);
  const viewportTop = clampViewport(state.viewportTop, position.row, lines.length, viewportHeight);
  return {
    lines: lines.map(({startGrapheme, endGrapheme, width}) => ({startGrapheme, endGrapheme, width})),
    cursorRow: position.row, cursorColumn: position.column, viewportTop, viewportHeight,
    visibleLines: lines.slice(viewportTop, viewportTop + viewportHeight)
      .map(({startGrapheme, endGrapheme, width}) => ({startGrapheme, endGrapheme, width})),
    structureUnits: editorUnits(state.text).length,
  };
}

export function expandSubmission(state: ComposerState): {readonly text: string} | {readonly code: ComposerValidationCode} {
  const units = editorUnits(state.text);
  const referenced = new Set<number>();
  let text = '';
  for (const unit of units) {
    if (unit.kind === 'grapheme') {
      if (unit.raw.includes(TOKEN_START) || unit.raw.includes(TOKEN_END)) return {code: 'PASTE_REFERENCE_FORGED'};
      text += unit.raw;
      continue;
    }
    const id = unit.payloadId;
    if (id === undefined) return {code: 'PASTE_REFERENCE_FORGED'};
    if (referenced.has(id)) return {code: 'PASTE_REFERENCE_DUPLICATE'};
    const payload = state.pastePayloads.get(id);
    if (payload === undefined) return {code: 'PASTE_REFERENCE_STALE'};
    referenced.add(id);
    text += payload.text;
  }
  if ([...state.pastePayloads.keys()].some(id => !referenced.has(id))) return {code: 'PASTE_REFERENCE_ORPHAN'};
  if (Array.from(text).length > MAX_SUBMISSION_CODE_POINTS) return {code: 'SUBMISSION_CODE_POINT_LIMIT'};
  if (text.length > MAX_SUBMISSION_UTF16_UNITS) return {code: 'SUBMISSION_UTF16_LIMIT'};
  if (utf8Length(text) > MAX_SUBMISSION_UTF8_BYTES) return {code: 'SUBMISSION_UTF8_LIMIT'};
  return {text};
}

export function displayWidth(text: string): number {
  let column = 0;
  for (const unit of segmentGraphemes(text)) column += unitWidth(unit, column);
  return column;
}

/** 将 Composer 的内部 token 投影成不暴露正文的固定折叠占位。 */
export function renderComposerText(state: ComposerState): string {
  return editorUnits(state.text).map(unit => displayUnit(state, unit)).join('');
}

export interface PastePreview {
  readonly id: number;
  readonly utf8Bytes: number;
  readonly preview: string;
}

/**
 * 光标落在折叠粘贴块上时，给出发送前可核对的前两行摘要。
 *
 * <p>正文仍不进入 Composer 可见结构；预览只服务本机当前草稿。</p>
 */
export function pastePreviewAtCursor(state: ComposerState, maxLines = 2, maxChars = 160): PastePreview | undefined {
  const units = editorUnits(state.text);
  const unit = units[state.cursorGrapheme] ?? units[state.cursorGrapheme - 1];
  if (unit?.kind !== 'paste' || unit.payloadId === undefined) return undefined;
  const payload = state.pastePayloads.get(unit.payloadId);
  if (payload === undefined) return undefined;
  const lines = payload.text.split(/\r?\n/u).slice(0, Math.max(1, maxLines));
  const preview = lines.join(' · ').replace(/\s+/gu, ' ').trim();
  const codePoints = Array.from(preview);
  return {
    id: payload.id,
    utf8Bytes: payload.utf8Bytes,
    preview: codePoints.length <= maxChars
      ? preview
      : `${codePoints.slice(0, Math.max(1, maxChars - 1)).join('')}…`,
  };
}

export interface ComposerRenderedLine {
  readonly beforeCursor: string;
  readonly cursorText: string | undefined;
  readonly afterCursor: string;
}

/** 只投影 viewport 内的视觉行，并以当前 grapheme identity 标记唯一光标。 */
export function renderComposerViewport(
  state: ComposerState,
  layout: ComposerLayout,
): readonly ComposerRenderedLine[] {
  const units = editorUnits(state.text);
  const projection = projectComposer(state, layout);
  return projection.visibleLines.map((line, visibleIndex) => {
    const row = projection.viewportTop + visibleIndex;
    const cursor = row === projection.cursorRow ? state.cursorGrapheme : undefined;
    const beforeEnd = cursor ?? line.endGrapheme;
    const beforeCursor = units.slice(line.startGrapheme, beforeEnd)
      .map(unit => displayUnit(state, unit)).join('');
    const cursorUnit = cursor === undefined || cursor >= line.endGrapheme
      ? undefined : units[cursor];
    const afterStart = cursorUnit === undefined ? beforeEnd : beforeEnd + 1;
    return {
      beforeCursor,
      cursorText: cursor === undefined ? undefined
        : cursorUnit === undefined ? ' ' : displayUnit(state, cursorUnit),
      afterCursor: units.slice(afterStart, line.endGrapheme)
        .map(unit => displayUnit(state, unit)).join(''),
    };
  });
}

/** 为已提交 Run 生成有界展示摘要，paste 正文永远不会被展开。 */
export function submittedComposerLabel(state: ComposerState, maximumGraphemes = 256): string {
  const units = editorUnits(state.text).slice(0, maximumGraphemes);
  const label = units.map(unit => displayUnit(state, unit)).join('');
  return editorUnits(state.text).length > units.length ? `${label}…` : label;
}

function displayUnit(state: ComposerState, unit: EditorUnit): string {
  if (unit.kind === 'grapheme') return unit.raw;
  const payload = unit.payloadId === undefined ? undefined : state.pastePayloads.get(unit.payloadId);
  if (payload === undefined) return '[粘贴引用无效]';
  const bucket = payload.utf8Bytes < 64 * 1024 ? '<64KiB'
    : payload.utf8Bytes < 256 * 1024 ? '<256KiB'
      : payload.utf8Bytes < 1024 * 1024 ? '<1MiB' : '1MiB';
  return `[粘贴 #${payload.id} · ${bucket}]`;
}

function insertLiteral(state: ComposerState, text: string, layout: ComposerLayout): ComposerTransition {
  return replaceRange(state, state.cursorGrapheme, state.cursorGrapheme, text, layout);
}

function replaceRange(
  state: ComposerState,
  start: number,
  end: number,
  text: string,
  layout: ComposerLayout,
): ComposerTransition {
  if (containsPrivatePasteMarker(text)) return rejected(state, 'PASTE_REFERENCE_FORGED');
  const units = editorUnits(state.text);
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)
    || start < 0 || end < start || end > units.length) {
    return rejected(state, 'VISIBLE_STRUCTURE_LIMIT');
  }
  const inserted = segmentGraphemes(text);
  if (units.length - (end - start) + inserted.length > MAX_VISIBLE_STRUCTURE_UNITS) {
    return rejected(state, 'VISIBLE_STRUCTURE_LIMIT');
  }
  const removedIds = units.slice(start, end)
    .flatMap(unit => unit.payloadId === undefined ? [] : [unit.payloadId]);
  const insertedUnits: EditorUnit[] = inserted.map(raw => ({raw, kind: 'grapheme'}));
  const remaining: readonly EditorUnit[] = [...units.slice(0, start), ...insertedUnits, ...units.slice(end)];
  const payloads = new Map(state.pastePayloads);
  for (const id of removedIds) {
    if (!remaining.some(unit => unit.payloadId === id)) payloads.delete(id);
  }
  return updated(withViewport(detachForEdit({
    ...state,
    text: remaining.map(unit => unit.raw).join(''),
    pastePayloads: payloads,
    cursorGrapheme: start + inserted.length,
    preferredVisualColumn: undefined,
    validationCode: undefined,
  }), layout));
}

function pasteText(state: ComposerState, text: string, layout: ComposerLayout): ComposerTransition {
  if (containsPrivatePasteMarker(text)) return rejected(state, 'PASTE_REFERENCE_FORGED');
  const bytes = utf8Length(text);
  if (bytes <= LARGE_PASTE_DISPLAY_THRESHOLD_UTF8_BYTES) return insertLiteral(state, text, layout);
  if (bytes > MAX_PASTE_ITEM_UTF8_BYTES) return rejected(state, 'PASTE_ITEM_LIMIT');
  if (state.pastePayloads.size >= MAX_PASTE_PAYLOADS) return rejected(state, 'PASTE_COUNT_LIMIT');
  const total = [...state.pastePayloads.values()].reduce((sum, payload) => sum + payload.utf8Bytes, 0);
  if (total + bytes > MAX_PASTE_TOTAL_UTF8_BYTES) return rejected(state, 'PASTE_TOTAL_LIMIT');
  const units = editorUnits(state.text);
  if (units.length + 1 > MAX_VISIBLE_STRUCTURE_UNITS) return rejected(state, 'VISIBLE_STRUCTURE_LIMIT');
  const id = state.nextPasteOrdinal;
  const token = pasteToken(id);
  const payloads = new Map(state.pastePayloads);
  payloads.set(id, {id, text, utf8Bytes: bytes});
  const nextText = units.slice(0, state.cursorGrapheme).map(unit => unit.raw).join('')
    + token + units.slice(state.cursorGrapheme).map(unit => unit.raw).join('');
  return updated(withViewport(detachForEdit({
    ...state, text: nextText, pastePayloads: payloads, nextPasteOrdinal: id + 1,
    cursorGrapheme: state.cursorGrapheme + 1, preferredVisualColumn: undefined, validationCode: undefined,
  }), layout));
}

function deleteRange(state: ComposerState, start: number, end: number, layout: ComposerLayout): ComposerTransition {
  const units = editorUnits(state.text);
  const removedIds = units.slice(start, end).flatMap(unit => unit.payloadId === undefined ? [] : [unit.payloadId]);
  const remaining = [...units.slice(0, start), ...units.slice(end)];
  const payloads = new Map(state.pastePayloads);
  for (const id of removedIds) {
    if (!remaining.some(unit => unit.payloadId === id)) payloads.delete(id);
  }
  return updated(withViewport(detachForEdit({
    ...state, text: remaining.map(unit => unit.raw).join(''), pastePayloads: payloads,
    cursorGrapheme: start, preferredVisualColumn: undefined, validationCode: undefined,
  }), layout));
}

function moveCursor(state: ComposerState, cursor: number, layout: ComposerLayout, resetPreferred: boolean): ComposerState {
  const next = {...state, cursorGrapheme: cursor, preferredVisualColumn: resetPreferred ? undefined : state.preferredVisualColumn};
  return withViewport(next, layout);
}

function moveVertical(state: ComposerState, delta: number, layout: ComposerLayout): ComposerState | undefined {
  const lines = visualLines(editorUnits(state.text), normalizeWidth(layout.width));
  const current = cursorPosition(lines, state.cursorGrapheme);
  const targetRow = current.row + delta;
  if (targetRow < 0 || targetRow >= lines.length) return undefined;
  const preferred = state.preferredVisualColumn ?? current.column;
  const target = lines[targetRow]!;
  let offset = 0;
  while (offset + 1 < target.columns.length && target.columns[offset + 1]! <= preferred) offset++;
  return withViewport({...state, cursorGrapheme: target.startGrapheme + offset, preferredVisualColumn: preferred}, layout);
}

function historyPrevious(state: ComposerState, layout: ComposerLayout): ComposerState {
  if (state.historyEntries.length === 0) return withViewport(state, layout);
  const index = state.historyIndex === undefined
    ? state.historyEntries.length - 1 : Math.max(0, state.historyIndex - 1);
  const draft = state.historyIndex === undefined ? cloneDocument(state) : state.historyDraft;
  return loadDocument({...state, historyIndex: index, historyDraft: draft}, state.historyEntries[index]!, layout);
}

function historyNext(state: ComposerState, layout: ComposerLayout): ComposerState {
  if (state.historyIndex === undefined) return withViewport(state, layout);
  const index = state.historyIndex + 1;
  if (index >= state.historyEntries.length) {
    const draft = state.historyDraft ?? {text: '', pastePayloads: new Map<number, PastePayload>()};
    return loadDocument({...state, historyIndex: undefined, historyDraft: undefined}, draft, layout);
  }
  return loadDocument({...state, historyIndex: index}, state.historyEntries[index]!, layout);
}

function loadDocument(state: ComposerState, document: ComposerDocument, layout: ComposerLayout): ComposerState {
  const text = document.text;
  return withViewport({
    ...state, text, pastePayloads: new Map(document.pastePayloads), cursorGrapheme: editorUnits(text).length,
    preferredVisualColumn: undefined, completionCandidates: [], completionIndex: undefined,
  }, layout);
}

function cycleCompletion(state: ComposerState, delta: number): ComposerState {
  const count = state.completionCandidates.length;
  if (count === 0) return state;
  const current = state.completionIndex ?? 0;
  return {...state, completionIndex: (current + delta + count) % count};
}

function selectedCompletion(state: ComposerState): string | undefined {
  if (state.completionCandidates.length === 0) return undefined;
  return state.completionCandidates[state.completionIndex ?? 0];
}

function detachForEdit(state: ComposerState): ComposerState {
  return {...state, historyIndex: undefined, historyDraft: undefined, completionCandidates: [], completionIndex: undefined};
}

function withViewport(state: ComposerState, layout: ComposerLayout): ComposerState {
  const height = normalizeHeight(layout.height);
  const lines = visualLines(editorUnits(state.text), normalizeWidth(layout.width));
  const cursor = cursorPosition(lines, state.cursorGrapheme);
  return {...state, viewportHeight: height, viewportTop: clampViewport(state.viewportTop, cursor.row, lines.length, height)};
}

function visualLines(units: readonly EditorUnit[], width: number): readonly InternalVisualLine[] {
  const lines: InternalVisualLine[] = [];
  let start = 0;
  let columns = [0];
  let column = 0;
  for (let index = 0; index < units.length; index++) {
    const unit = units[index]!;
    if (unit.raw === '\n') {
      lines.push({startGrapheme: start, endGrapheme: index, width: column, columns});
      start = index + 1; columns = [0]; column = 0;
      continue;
    }
    const measured = unit.kind === 'paste' ? Math.min(12, width) : unitWidth(unit.raw, column);
    if (column > 0 && column + measured > width) {
      lines.push({startGrapheme: start, endGrapheme: index, width: column, columns});
      start = index; columns = [0]; column = 0;
    }
    column += unit.kind === 'paste' ? Math.min(12, width) : unitWidth(unit.raw, column);
    columns.push(column);
  }
  lines.push({startGrapheme: start, endGrapheme: units.length, width: column, columns});
  return lines;
}

function cursorPosition(lines: readonly InternalVisualLine[], cursor: number): {readonly row: number; readonly column: number} {
  for (let row = lines.length - 1; row >= 0; row--) {
    const line = lines[row]!;
    if (cursor >= line.startGrapheme && cursor <= line.endGrapheme) {
      const offset = Math.min(cursor - line.startGrapheme, line.columns.length - 1);
      return {row, column: line.columns[offset] ?? line.width};
    }
  }
  return {row: 0, column: 0};
}

function clampViewport(top: number, cursorRow: number, lineCount: number, height: number): number {
  const maximum = Math.max(0, lineCount - height);
  let next = Math.min(Math.max(0, top), maximum);
  if (cursorRow < next) next = cursorRow;
  if (cursorRow >= next + height) next = cursorRow - height + 1;
  return Math.min(Math.max(0, next), maximum);
}

function editorUnits(text: string): readonly EditorUnit[] {
  const units: EditorUnit[] = [];
  let offset = 0;
  TOKEN_PATTERN.lastIndex = 0;
  for (const match of text.matchAll(TOKEN_PATTERN)) {
    const index = match.index;
    if (index > offset) units.push(...segmentGraphemes(text.slice(offset, index)).map(raw => ({raw, kind: 'grapheme' as const})));
    units.push({raw: match[0], kind: 'paste', payloadId: Number(match[1])});
    offset = index + match[0].length;
  }
  if (offset < text.length) units.push(...segmentGraphemes(text.slice(offset)).map(raw => ({raw, kind: 'grapheme' as const})));
  return units;
}

function segmentGraphemes(text: string): readonly string[] {
  return [...graphemeSegmenter.segment(text)].map(segment => segment.segment);
}

function unitWidth(grapheme: string, column: number): number {
  if (grapheme === '\n' || grapheme === '\r') return 0;
  if (grapheme === '\t') return 4 - (column % 4);
  const points = Array.from(grapheme);
  if (points.some(point => /\p{Extended_Pictographic}/u.test(point)) || isRegionalIndicatorFlag(points)) return 2;
  for (const point of points) {
    const code = point.codePointAt(0)!;
    if (/\p{Mark}|\p{Default_Ignorable_Code_Point}/u.test(point)) continue;
    return isWide(code) ? 2 : code < 0x20 || (code >= 0x7F && code < 0xA0) ? 0 : 1;
  }
  return 0;
}

function isWide(code: number): boolean {
  return code >= 0x1100 && (
    code <= 0x115F || code === 0x2329 || code === 0x232A ||
    (code >= 0x2E80 && code <= 0xA4CF && code !== 0x303F) ||
    (code >= 0xAC00 && code <= 0xD7A3) || (code >= 0xF900 && code <= 0xFAFF) ||
    (code >= 0xFE10 && code <= 0xFE19) || (code >= 0xFE30 && code <= 0xFE6F) ||
    (code >= 0xFF00 && code <= 0xFF60) || (code >= 0xFFE0 && code <= 0xFFE6) ||
    (code >= 0x20000 && code <= 0x3FFFD)
  );
}

function wordClass(unit: EditorUnit): 'space' | 'word' | 'other' {
  if (/^\s+$/u.test(unit.raw)) return 'space';
  if (unit.kind === 'grapheme' && /[\p{Letter}\p{Number}_]/u.test(unit.raw)) return 'word';
  return 'other';
}

function wordLeft(units: readonly EditorUnit[], cursor: number): number {
  let index = cursor;
  while (index > 0 && wordClass(units[index - 1]!) === 'space') index--;
  if (index === 0) return 0;
  const target = wordClass(units[index - 1]!);
  while (index > 0 && wordClass(units[index - 1]!) === target) index--;
  return index;
}

function wordRight(units: readonly EditorUnit[], cursor: number): number {
  let index = cursor;
  while (index < units.length && wordClass(units[index]!) === 'space') index++;
  if (index >= units.length) return units.length;
  const target = wordClass(units[index]!);
  while (index < units.length && wordClass(units[index]!) === target) index++;
  return index;
}

function cloneDocument(document: ComposerDocument): ComposerDocument {
  return {text: document.text, pastePayloads: new Map(document.pastePayloads)};
}

function retainHistoryDocuments(documents: readonly ComposerDocument[]): readonly ComposerDocument[] {
  const retained: ComposerDocument[] = [];
  let payloadBytes = 0;
  for (let index = documents.length - 1; index >= 0 && retained.length < MAX_HISTORY_ENTRIES; index--) {
    const document = documents[index]!;
    const documentBytes = [...document.pastePayloads.values()]
      .reduce((sum, payload) => sum + payload.utf8Bytes, 0);
    if (payloadBytes + documentBytes > MAX_HISTORY_PASTE_UTF8_BYTES) continue;
    retained.push(document);
    payloadBytes += documentBytes;
  }
  return retained.reverse();
}

function containsPrivatePasteMarker(text: string): boolean {
  return text.includes(TOKEN_START) || text.includes(TOKEN_END);
}

function isRegionalIndicatorFlag(points: readonly string[]): boolean {
  return points.length === 2 && points.every(point => /\p{Regional_Indicator}/u.test(point));
}

function pasteToken(id: number): string {
  return `${TOKEN_START}paste:${id}${TOKEN_END}`;
}

function utf8Length(text: string): number {
  return encoder.encode(text).byteLength;
}

function normalizeWidth(width: number): number {
  return Number.isFinite(width) ? Math.max(1, Math.floor(width)) : 1;
}

function normalizeHeight(height: number): number {
  return Number.isFinite(height) ? Math.max(1, Math.floor(height)) : 1;
}

function updated(state: ComposerState): ComposerTransition {
  return {kind: 'updated', state};
}

function rejected(state: ComposerState, code: ComposerValidationCode): ComposerTransition {
  return {kind: 'submission-rejected', state: {...state, validationCode: code}, code};
}

// Compatibility helpers retained until W4 moves app.tsx onto ComposerState.
export interface InputHistoryState {
  readonly entries: readonly string[];
  readonly index: number | undefined;
  readonly draft: string | undefined;
}
export const initialInputHistoryState: InputHistoryState = {entries: [], index: undefined, draft: undefined};
export function appendInput(current: string, text: string): string {
  if (text.length === 0) return current;
  if (segmentGraphemes(current).length + segmentGraphemes(text).length > MAX_VISIBLE_STRUCTURE_UNITS) {
    throw new RangeError('VISIBLE_STRUCTURE_LIMIT');
  }
  return current + text;
}
export function removeLastCodePoint(input: string): string {
  return segmentGraphemes(input).slice(0, -1).join('');
}
export function recordInputHistory(state: InputHistoryState, submitted: string): InputHistoryState {
  if (submitted.length === 0) return {...state, index: undefined, draft: undefined};
  return {entries: [...state.entries, submitted].slice(-MAX_HISTORY_ENTRIES), index: undefined, draft: undefined};
}
export function navigateInputHistory(
  state: InputHistoryState, currentInput: string, direction: 'previous' | 'next',
): {readonly state: InputHistoryState; readonly input: string | undefined} {
  if (state.entries.length === 0) return {state, input: undefined};
  if (direction === 'previous') {
    const index = state.index === undefined ? state.entries.length - 1 : Math.max(0, state.index - 1);
    return {state: {...state, index, draft: state.index === undefined ? currentInput : state.draft}, input: state.entries[index]};
  }
  if (state.index === undefined) return {state, input: undefined};
  const index = state.index + 1;
  if (index >= state.entries.length) return {state: {...state, index: undefined, draft: undefined}, input: state.draft ?? ''};
  return {state: {...state, index}, input: state.entries[index]};
}
export function completionCandidates(input: string): readonly string[] {
  if (!input.startsWith('/')) return [];
  const candidates = ARGUMENT_COMPLETIONS[input.split(/\s+/u)[0] ?? ''] ?? COMMAND_COMPLETIONS;
  return candidates.filter(candidate => candidate.startsWith(input)).slice().sort().slice(0, MAX_COMPLETION_CANDIDATES);
}
