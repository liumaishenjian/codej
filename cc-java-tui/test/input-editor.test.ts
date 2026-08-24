import {describe, expect, it} from 'vitest';
import {
  LARGE_PASTE_DISPLAY_THRESHOLD_UTF8_BYTES,
  MAX_COMPLETION_CANDIDATES,
  MAX_HISTORY_ENTRIES,
  MAX_HISTORY_PASTE_UTF8_BYTES,
  MAX_PASTE_PAYLOADS,
  MAX_SUBMISSION_CODE_POINTS,
  MAX_SUBMISSION_UTF16_UNITS,
  MAX_SUBMISSION_UTF8_BYTES,
  MAX_VISIBLE_STRUCTURE_UNITS,
  acceptPendingComposer,
  acceptSubmittedComposer,
  appendInput,
  beginPendingComposer,
  completionCandidates,
  createComposerState,
  displayWidth,
  expandSubmission,
  initialInputHistoryState,
  navigateInputHistory,
  pastePreviewAtCursor,
  projectComposer,
  recordInputHistory,
  reduceComposer,
  renderComposerViewport,
  restoreRejectedComposer,
  submittedComposerLabel,
  type ComposerAction,
  type ComposerLayout,
  type ComposerState,
  type ComposerTransition,
  type PastePayload,
} from '../src/input-editor.js';

const layout: ComposerLayout = {width: 80, height: 4};
const token = (id: number): string => `${String.fromCodePoint(0xF0000)}paste:${id}${String.fromCodePoint(0xF0001)}`;

function update(state: ComposerState, action: ComposerAction, customLayout = layout): ComposerState {
  const transition = reduceComposer(state, action, customLayout);
  expect(transition.kind).toBe('updated');
  return transition.state;
}

function reject(state: ComposerState, action: ComposerAction, code: string): ComposerTransition {
  const transition = reduceComposer(state, action, layout);
  expect(transition.kind).toBe('submission-rejected');
  expect(transition).toMatchObject({code});
  return transition;
}

describe('Composer grapheme editing', () => {
  it('inserts at arbitrary grapheme boundaries without splitting combining or ZWJ sequences', () => {
    const combining = 'é';
    const family = '👨‍👩‍👧‍👦';
    let state = createComposerState();
    state = update(state, {type: 'InsertText', text: `${combining}${family}界`});
    expect(state.cursorGrapheme).toBe(3);

    state = update(state, {type: 'MoveLeft'});
    state = update(state, {type: 'MoveLeft'});
    state = update(state, {type: 'InsertText', text: '!'});
    expect(state.text).toBe(`${combining}!${family}界`);
    expect(state.cursorGrapheme).toBe(2);

    state = update(state, {type: 'DeleteForward'});
    expect(state.text).toBe(`${combining}!界`);
    state = update(state, {type: 'Backspace'});
    expect(state.text).toBe(`${combining}界`);
    state = update(state, {type: 'Backspace'});
    expect(state.text).toBe('界');
  });

  it('implements no-op boundaries, logical Home/End, and locale-independent word movement', () => {
    let state = createComposerState();
    const boundary = update(state, {type: 'Backspace'});
    expect(boundary.text).toBe(state.text);
    expect(boundary.cursorGrapheme).toBe(state.cursorGrapheme);
    state = update(state, {type: 'InsertText', text: 'ab 中文,🙂  cd\nlast'});
    state = update(state, {type: 'MoveHome'});
    expect(state.cursorGrapheme).toBe(12);
    state = update(state, {type: 'MoveEnd'});
    expect(state.cursorGrapheme).toBe(16);
    state = update(state, {type: 'MoveWordLeft'});
    expect(state.cursorGrapheme).toBe(12);
    state = update(state, {type: 'MoveWordLeft'});
    expect(state.cursorGrapheme).toBe(9);
    state = update(state, {type: 'MoveWordLeft'});
    expect(state.cursorGrapheme).toBe(5);
    state = update(state, {type: 'MoveWordLeft'});
    expect(state.cursorGrapheme).toBe(3);
    state = update(state, {type: 'MoveWordRight'});
    expect(state.cursorGrapheme).toBe(5);
  });

  it('rejects visible structure limit+1 atomically without truncation', () => {
    let state = createComposerState();
    state = update(state, {type: 'InsertText', text: 'a'.repeat(MAX_VISIBLE_STRUCTURE_UNITS)});
    const transition = reject(state, {type: 'InsertText', text: 'b'}, 'VISIBLE_STRUCTURE_LIMIT');
    expect(transition.state.text).toBe(state.text);
    expect(transition.state.cursorGrapheme).toBe(MAX_VISIBLE_STRUCTURE_UNITS);
  });
});

describe('Composer visual layout and viewport', () => {
  it('uses the same CJK, combining, emoji and regional-indicator widths for wrapping and cursor projection', () => {
    expect(displayWidth('Aé界👩‍💻')).toBe(6);
    expect(displayWidth('🇨🇳')).toBe(2);
    let state = createComposerState(2);
    state = update(state, {type: 'InsertText', text: 'ab界c👩‍💻d'}, {width: 4, height: 2});
    const projection = projectComposer(state, {width: 4, height: 2});
    expect(projection.lines.map(line => line.width)).toEqual([4, 4]);
    expect(projection.cursorRow).toBe(1);
    expect(projection.cursorColumn).toBe(4);
    expect(projection.viewportTop).toBe(0);
  });

  it('moves by visual rows, clamps short rows, and restores the preferred column', () => {
    let state = createComposerState(2);
    state = update(state, {type: 'InsertText', text: 'abcd\nx\nwxyz'}, {width: 20, height: 2});
    state = update(state, {type: 'MoveLeft'}, {width: 20, height: 2});
    expect(projectComposer(state, {width: 20, height: 2}).cursorColumn).toBe(3);
    state = update(state, {type: 'MoveUp'}, {width: 20, height: 2});
    expect(projectComposer(state, {width: 20, height: 2}).cursorColumn).toBe(1);
    expect(state.preferredVisualColumn).toBe(3);
    state = update(state, {type: 'MoveUp'}, {width: 20, height: 2});
    expect(projectComposer(state, {width: 20, height: 2}).cursorColumn).toBe(3);
  });

  it('preserves logical cursor identity across resize and follows it in a clipped viewport', () => {
    let state = createComposerState(2);
    state = update(state, {type: 'InsertText', text: 'abcdefghijkl'}, {width: 3, height: 2});
    expect(state.viewportTop).toBe(2);
    const cursor = state.cursorGrapheme;
    state = update(state, {type: 'Resize', width: 6, height: 1}, {width: 3, height: 2});
    expect(state.cursorGrapheme).toBe(cursor);
    expect(state.text).toBe('abcdefghijkl');
    expect(projectComposer(state, {width: 6, height: 1})).toMatchObject({cursorRow: 1, viewportTop: 1, viewportHeight: 1});
    expect(renderComposerViewport(state, {width: 6, height: 1})).toEqual([{
      beforeCursor: 'ghijkl', cursorText: ' ', afterCursor: '',
    }]);
  });

  it('renders only viewport lines and preserves cursor grapheme identity', () => {
    let state = createComposerState(2);
    state = update(state, {type: 'InsertText', text: 'abc\ndef\nghi'}, {width: 3, height: 2});
    state = update(state, {type: 'MoveLeft'}, {width: 3, height: 2});
    const lines = renderComposerViewport(state, {width: 3, height: 2});
    expect(lines).toHaveLength(2);
    expect(lines.map(line => line.beforeCursor + (line.cursorText ?? '') + line.afterCursor)).toEqual(['def', 'ghi']);
    expect(lines[1]).toEqual({beforeCursor: 'gh', cursorText: 'i', afterCursor: ''});
  });

  it('renders exactly one cursor at an exact soft-wrap boundary', () => {
    let state = createComposerState(2);
    state = update(state, {type: 'InsertText', text: 'abcdef'}, {width: 3, height: 2});
    state = {...state, cursorGrapheme: 3, viewportTop: 0};
    const projection = projectComposer(state, {width: 3, height: 2});
    const lines = renderComposerViewport(state, {width: 3, height: 2});
    expect(projection).toMatchObject({cursorRow: 1, cursorColumn: 0});
    expect(lines.filter(line => line.cursorText !== undefined)).toHaveLength(1);
    expect(lines[0]).toEqual({beforeCursor: 'abc', cursorText: undefined, afterCursor: ''});
    expect(lines[1]).toEqual({beforeCursor: '', cursorText: 'd', afterCursor: 'ef'});
  });
});

describe('Pending submission reconciliation', () => {
  it('accepts an older pending document without replacing post-send edits', () => {
    let submitted = createComposerState();
    submitted = update(submitted, {type: 'InsertText', text: 'first'});
    let current = beginPendingComposer(submitted);
    current = update(current, {type: 'InsertText', text: 'after'});

    const accepted = acceptPendingComposer(current, submitted);
    expect(accepted.text).toBe('after');
    expect(accepted.historyEntries.at(-1)?.text).toBe('first');
  });

  it('restores rejected content before subsequent edits and merges paste ownership', () => {
    let submitted = createComposerState();
    submitted = update(submitted, {type: 'Paste', text: 'S'.repeat(2_000)});
    let current = beginPendingComposer(submitted);
    current = update(current, {type: 'Paste', text: 'N'.repeat(2_000)});
    const restored = restoreRejectedComposer(current, submitted);

    expect(expandSubmission(restored)).toEqual({text: `${'S'.repeat(2_000)}${'N'.repeat(2_000)}`});
    expect(restored.pastePayloads.size).toBe(2);
  });
});

describe('Completion and history ownership', () => {
  it('gives an open completion precedence over visual movement and caps candidates', () => {
    let state = createComposerState();
    state = update(state, {type: 'InsertText', text: 'draft'});
    state = update(state, {type: 'SetCompletions', candidates: Array.from({length: 40}, (_, index) => `/${index}`)});
    expect(state.completionCandidates).toHaveLength(MAX_COMPLETION_CANDIDATES);
    const cursor = state.cursorGrapheme;
    state = update(state, {type: 'MoveDown'});
    expect(state.cursorGrapheme).toBe(cursor);
    expect(state.completionIndex).toBe(1);
    state = update(state, {type: 'AcceptCompletion'});
    expect(state.text).toBe('draft/1');
    expect(state.completionCandidates).toEqual([]);
  });

  it('accepts completion in place without discarding draft text or owned paste payloads', () => {
    let state = createComposerState();
    state = update(state, {type: 'Paste', text: 'P'.repeat(LARGE_PASTE_DISPLAY_THRESHOLD_UTF8_BYTES + 1)});
    const payload = state.pastePayloads.get(1);
    state = update(state, {type: 'SetCompletions', candidates: ['/help']});
    state = update(state, {type: 'AcceptCompletion'});
    expect(state.text).toBe(`${token(1)}/help`);
    expect(state.pastePayloads.get(1)).toEqual(payload);
    expect(expandSubmission(state)).toEqual({text: `${payload!.text}/help`});
  });

  it('光标在折叠粘贴块上时给出前两行预览', () => {
    let state = createComposerState();
    state = update(state, {type: 'Paste', text: 'alpha line\nbeta line\ngamma\n'.repeat(40)});
    const preview = pastePreviewAtCursor(state);
    expect(preview?.id).toBe(1);
    expect(preview?.preview).toContain('alpha line');
    expect(preview?.preview).toContain('beta line');
    expect(preview?.preview).not.toContain('gamma');
  });

  it('enters history only at visual boundaries and restores a draft with its payload map', () => {
    let state = createComposerState();
    state = update(state, {type: 'InsertText', text: 'old'});
    state = acceptSubmittedComposer(state);
    state = update(state, {type: 'Paste', text: 'D'.repeat(LARGE_PASTE_DISPLAY_THRESHOLD_UTF8_BYTES + 1)});
    const draftText = state.text;
    const draftPayload = state.pastePayloads.get(1);
    state = update(state, {type: 'MoveUp'});
    expect(state.text).toBe('old');
    expect(state.historyIndex).toBe(0);
    state = update(state, {type: 'MoveDown'});
    expect(state.text).toBe(draftText);
    expect(state.pastePayloads.get(1)).toEqual(draftPayload);
    expect(state.historyIndex).toBeUndefined();
  });

  it('bounds paste payload memory retained by history across accepted submissions', () => {
    let state = createComposerState();
    const payload = 'p'.repeat((MAX_HISTORY_PASTE_UTF8_BYTES / 2) + 1);
    for (let index = 0; index < 3; index++) {
      state = update(state, {type: 'Paste', text: payload});
      state = acceptSubmittedComposer(state);
    }
    expect(state.historyEntries).toHaveLength(1);
    expect(state.historyEntries[0]?.pastePayloads.get(3)?.text).toBe(payload);
  });

  it('retains only 100 accepted submissions and editing detaches from history', () => {
    let state = createComposerState();
    for (let index = 0; index < MAX_HISTORY_ENTRIES + 2; index++) {
      state = update(state, {type: 'InsertText', text: `entry-${index}`});
      state = acceptSubmittedComposer(state);
    }
    expect(state.historyEntries).toHaveLength(MAX_HISTORY_ENTRIES);
    expect(state.historyEntries[0]?.text).toBe('entry-2');
    state = update(state, {type: 'HistoryPrevious'});
    state = update(state, {type: 'InsertText', text: '!'});
    expect(state.historyIndex).toBeUndefined();
  });
});

describe('Lossless paste placeholders', () => {
  it('stores large pastes atomically, expands multiple placeholders in order, and supports immediate submit', () => {
    const first = '一'.repeat(500);
    const second = 'Z'.repeat(1_100);
    let state = createComposerState();
    state = update(state, {type: 'InsertText', text: 'before:'});
    state = update(state, {type: 'Paste', text: first});
    state = update(state, {type: 'InsertText', text: ':middle:'});
    state = update(state, {type: 'Paste', text: second});
    expect(state.pastePayloads.size).toBe(2);
    expect(projectComposer(state, layout).structureUnits).toBe(17);
    const submit = reduceComposer(state, {type: 'Submit'}, layout);
    expect(submit).toMatchObject({kind: 'submit-ready', expandedText: `before:${first}:middle:${second}`});
    expect(state.text).not.toContain(first);
    const label = submittedComposerLabel(state);
    expect(label).toContain('[粘贴 #1');
    expect(label).toContain('[粘贴 #2');
    expect(label).not.toContain(first);
    expect(label).not.toContain(second);
  });

  it('treats a placeholder as one editing atom and garbage-collects deleted payloads', () => {
    let state = createComposerState();
    state = update(state, {type: 'Paste', text: 'x'.repeat(2_000)});
    expect(state.cursorGrapheme).toBe(1);
    state = update(state, {type: 'MoveLeft'});
    state = update(state, {type: 'DeleteForward'});
    expect(state.text).toBe('');
    expect(state.pastePayloads.size).toBe(0);
  });

  it('clears payloads only after explicit clear or accepted submission', () => {
    let state = createComposerState();
    state = update(state, {type: 'Paste', text: 'x'.repeat(2_000)});
    const ready = reduceComposer(state, {type: 'Submit'}, layout);
    expect(ready.state.pastePayloads.size).toBe(1);
    state = acceptSubmittedComposer(ready.state);
    expect(state.pastePayloads.size).toBe(0);
    expect(state.historyEntries[0]?.pastePayloads.size).toBe(1);
    state = update(state, {type: 'Clear'});
    expect(state.pastePayloads.size).toBe(0);
  });

  it('rejects literal private-use token text without reusing or relocating an owned payload', () => {
    let state = createComposerState();
    state = update(state, {type: 'Paste', text: 'owned'.repeat(300)});
    const ownedText = state.text;
    const ownedPayload = state.pastePayloads.get(1);
    const transition = reject(state, {type: 'InsertText', text: token(1)}, 'PASTE_REFERENCE_FORGED');
    expect(transition.state.text).toBe(ownedText);
    expect(transition.state.cursorGrapheme).toBe(1);
    expect(transition.state.pastePayloads.get(1)).toEqual(ownedPayload);
    reject(state, {type: 'Paste', text: `${token(1)}literal`.repeat(100)}, 'PASTE_REFERENCE_FORGED');
  });

  it('rejects stale, duplicate, orphan and forged references while preserving the draft', () => {
    const payload: PastePayload = {id: 1, text: 'secret', utf8Bytes: 6};
    const base = createComposerState();
    const cases: readonly [ComposerState, string][] = [
      [{...base, text: token(1), cursorGrapheme: 1}, 'PASTE_REFERENCE_STALE'],
      [{...base, text: token(1) + token(1), cursorGrapheme: 2, pastePayloads: new Map([[1, payload]])}, 'PASTE_REFERENCE_DUPLICATE'],
      [{...base, text: '', pastePayloads: new Map([[1, payload]])}, 'PASTE_REFERENCE_ORPHAN'],
      [{...base, text: String.fromCodePoint(0xF0000), cursorGrapheme: 1}, 'PASTE_REFERENCE_FORGED'],
    ];
    for (const [state, code] of cases) {
      const transition = reject(state, {type: 'Submit'}, code);
      expect(transition.state.text).toBe(state.text);
      expect(transition.state.pastePayloads).toEqual(state.pastePayloads);
    }
  });

  it('accepts an exact 1 MiB payload and rejects item limit+1 without changing the draft', () => {
    let state = createComposerState();
    state = update(state, {type: 'Paste', text: 'x'.repeat(MAX_SUBMISSION_UTF8_BYTES)});
    expect(state.pastePayloads.get(1)?.utf8Bytes).toBe(MAX_SUBMISSION_UTF8_BYTES);
    expect(expandSubmission(state)).toEqual({text: 'x'.repeat(MAX_SUBMISSION_UTF8_BYTES)});

    const empty = createComposerState();
    const transition = reject(empty, {type: 'Paste', text: 'x'.repeat(MAX_SUBMISSION_UTF8_BYTES + 1)}, 'PASTE_ITEM_LIMIT');
    expect(transition.state.text).toBe('');
    expect(transition.state.pastePayloads.size).toBe(0);
  });

  it('enforces payload count and total budgets without partially inserting', () => {
    let state = createComposerState();
    const item = 'x'.repeat(LARGE_PASTE_DISPLAY_THRESHOLD_UTF8_BYTES + 1);
    for (let index = 0; index < MAX_PASTE_PAYLOADS; index++) state = update(state, {type: 'Paste', text: item});
    reject(state, {type: 'Paste', text: item}, 'PASTE_COUNT_LIMIT');

    state = createComposerState();
    state = update(state, {type: 'Paste', text: 'a'.repeat(524_288)});
    state = update(state, {type: 'Paste', text: 'b'.repeat(524_288)});
    expect(state.pastePayloads.size).toBe(2);
    const unchanged = state;
    const transition = reject(state, {type: 'Paste', text: 'c'.repeat(1_025)}, 'PASTE_TOTAL_LIMIT');
    expect(transition.state.text).toBe(unchanged.text);
    expect(transition.state.pastePayloads.size).toBe(2);
  });
});

describe('Submission budget validation', () => {
  function stateWithPayload(text: string): ComposerState {
    const payload: PastePayload = {id: 1, text, utf8Bytes: Buffer.byteLength(text)};
    return {...createComposerState(), text: token(1), cursorGrapheme: 1, pastePayloads: new Map([[1, payload]])};
  }

  it('accepts the exact 1 MiB ASCII boundary losslessly', () => {
    const text = 'a'.repeat(MAX_SUBMISSION_UTF8_BYTES);
    expect(expandSubmission(stateWithPayload(text))).toEqual({text});
  });

  it('reports code point, UTF-16, and UTF-8 limit+1 with distinct deterministic codes', () => {
    expect(expandSubmission(stateWithPayload('a'.repeat(MAX_SUBMISSION_CODE_POINTS + 1)))).toEqual({code: 'SUBMISSION_CODE_POINT_LIMIT'});
    expect(expandSubmission(stateWithPayload('😀'.repeat((MAX_SUBMISSION_UTF16_UNITS / 2) + 1)))).toEqual({code: 'SUBMISSION_UTF16_LIMIT'});
    expect(expandSubmission(stateWithPayload('界'.repeat(Math.floor(MAX_SUBMISSION_UTF8_BYTES / 3) + 1)))).toEqual({code: 'SUBMISSION_UTF8_LIMIT'});
  });

  it('preserves all state on submission rejection and never truncates', () => {
    const state = stateWithPayload('界'.repeat(Math.floor(MAX_SUBMISSION_UTF8_BYTES / 3) + 1));
    const transition = reject(state, {type: 'Submit'}, 'SUBMISSION_UTF8_LIMIT');
    expect(transition.state.text).toBe(state.text);
    expect(transition.state.cursorGrapheme).toBe(state.cursorGrapheme);
    expect(transition.state.pastePayloads.get(1)?.text).toBe(state.pastePayloads.get(1)?.text);
  });
});

describe('legacy W4 compatibility seams', () => {
  it('rejects overflow explicitly instead of silently truncating compatibility input', () => {
    const current = 'a'.repeat(MAX_VISIBLE_STRUCTURE_UNITS);
    expect(() => appendInput(current, 'b')).toThrowError(new RangeError('VISIBLE_STRUCTURE_LIMIT'));
    expect(appendInput('prefix', '😀')).toBe('prefix😀');
  });

  it('keeps closed completion ordering and old history behavior until app integration', () => {
    expect(completionCandidates('/permissions m')).toEqual([
      '/permissions mode ACCEPT_EDITS', '/permissions mode DEFAULT', '/permissions mode PLAN',
    ]);
    expect(completionCandidates('/plan-')).toEqual(['/plan-status']);
    expect(completionCandidates('/plan')).toEqual(['/plan', '/plan-status']);
    let history = initialInputHistoryState;
    for (let index = 0; index < MAX_HISTORY_ENTRIES + 2; index++) history = recordInputHistory(history, `input-${index}`);
    const previous = navigateInputHistory(history, 'draft', 'previous');
    expect(previous.input).toBe('input-101');
    expect(navigateInputHistory(previous.state, 'input-101', 'next').input).toBe('draft');
  });
});
