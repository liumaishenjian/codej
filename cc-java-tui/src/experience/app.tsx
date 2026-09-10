import {useEffect, useState} from 'react';
import {useApp, useInput, useWindowSize} from 'ink';
import {edit, emptyDraft, glyphs, type Draft} from './editor.js';
import {advance, answerText, candidates, demoCommands, cancel, changeQuestion, choose, initial, isBusy, questions, submit, type Experience} from './state.js';
import {frame, Screen} from './screen.js';

/** 单一键盘入口保证审批、选项编辑、问卷和正文不会同时处理一次按键。 */
export function ExperienceApp({seed}: {seed?: Experience}) {
  const [state, setState] = useState(seed ?? initial);
  const {columns, rows} = useWindowSize();
  const {exit} = useApp();
  const busy = isBusy(state);
  useEffect(() => {
    if (!busy || columns < 40 || rows < 24) return;
    const timer = setInterval(() => setState(advance), 250);
    return () => clearInterval(timer);
  }, [busy, state.phase, columns, rows]);
  useInput((input, key) => {
    if (key.eventType === 'release') return;
    if (key.ctrl && input === 'c') {exit(); return;}
    if (columns < 40 || rows < 24) return;
    setState(s => {
      if (key.ctrl && input === 'o') return {...s, expanded: !s.expanded, scroll: 0};
      if (key.pageUp || key.pageDown) {
        const maximum = Math.max(0, frame({...s, scroll: 0}, columns, 10000).length - rows + 1);
        return {...s, scroll: Math.max(0, Math.min(maximum, s.scroll + (key.pageUp ? 1 : -1) * Math.max(1, rows - 8)))};
      }
      if (key.end && s.scroll) return {...s, scroll: 0};
      if (key.escape) return cancel(s);
      const inputDraft = s.editing ? s.phase === 'approval' ? s.feedback : s.free[s.question]! : s.draft;
      function updateDraft(draft: Draft): Experience {
        if (s.editing && s.phase === 'approval') return {...s, feedback: draft, notice: ''};
        if (s.editing && s.phase === 'question') return {...s, free: s.free.map((old, index) => index === s.question ? draft : old), notice: ''};
        return {...s, draft, focus: 0, recall: -1, notice: ''};
      }
      if (s.phase === 'approval') {
        if (key.tab && s.focus !== 1) return {...s, editing: !s.editing};
        if (key.return) return choose(s);
        if (!s.editing) {
          if (key.upArrow || key.downArrow) return {...s, focus: (s.focus + (key.upArrow ? 2 : 1)) % 3};
          if (/^[1-3]$/.test(input)) return {...s, focus: Number(input) - 1};
          return s;
        }
      } else if (s.phase === 'question' || s.phase === 'review') {
        if (!s.editing) {
          if (key.tab || key.leftArrow || key.rightArrow) return changeQuestion(s, key.leftArrow || (key.tab && key.shift) ? -1 : 1);
          if (s.phase === 'review') return key.return ? choose(s) : s;
          if (key.upArrow || key.downArrow) return {...s, focus: (s.focus + (key.upArrow ? 3 : 1)) % 4, notice: ''};
          if (/^[1-4]$/.test(input)) return choose(s, Number(input) - 1);
          if (input === ' ' && questions[s.question]!.multi) return choose(s);
          if (key.return) {
            if (s.focus === 3) return choose(s);
            if (!questions[s.question]!.multi) return choose(s);
            return answerText(s, s.question) ? changeQuestion(s, 1) : {...s, notice: '请至少选择一项，空格切换选中状态'};
          }
          return s;
        }
        if (key.return) return choose(s);
        if (key.tab) return {...s, editing: false};
      } else {
        const matches = candidates(s);
        if (matches.length && (key.tab || key.upArrow || key.downArrow)) {
          const delta = key.upArrow || (key.tab && key.shift) ? -1 : 1;
          return {...s, focus: (s.focus + delta + matches.length) % matches.length};
        }
        if (key.tab && key.shift) return {...s, mode: s.mode === '人工审批' ? '计划模式' : '人工审批'};
        if (key.return) {
          const chosen = matches.length && !demoCommands.includes(s.draft.text) ? matches[s.focus % matches.length] : undefined;
          return submit(chosen ? {...s, draft: {text: chosen, cursor: chosen.length}} : s);
        }
        if (!isBusy(s) && (key.upArrow || key.downArrow) && !s.draft.text.includes('\n')) {
          const recall = Math.max(-1, Math.min(s.recent.length - 1, s.recall + (key.upArrow ? 1 : -1)));
          const savedDraft = s.recall === -1 ? s.draft : s.savedDraft;
          const text = s.recent[s.recent.length - 1 - recall] ?? '';
          return {...s, recall, savedDraft, draft: recall === -1 ? savedDraft : {text, cursor: glyphs(text).length}};
        }
      }
      if (key.ctrl && input === 'j') return updateDraft(edit(inputDraft, 'insert', '\n'));
      if (key.ctrl && input === 'u') return updateDraft(emptyDraft());
      if (key.ctrl && input === 'a') return updateDraft(edit(inputDraft, 'home'));
      if (key.ctrl && input === 'e') return updateDraft(edit(inputDraft, 'end'));
      if (key.leftArrow) return updateDraft(edit(inputDraft, 'left'));
      if (key.rightArrow) return updateDraft(edit(inputDraft, 'right'));
      if (key.home) return updateDraft(edit(inputDraft, 'home'));
      if (key.end) return updateDraft(edit(inputDraft, 'end'));
      if (key.backspace || key.delete) return updateDraft(edit(inputDraft, key.backspace ? 'backspace' : 'delete'));
      if (!key.ctrl && !key.meta && !key.tab && input) {
        const next = edit(inputDraft, 'insert', input);
        if (next === inputDraft) return {...s, notice: '单份草稿最多 8000 个字符；此次输入未插入'};
        return updateDraft(next);
      }
      return s;
    });
  });
  return <Screen state={state} columns={columns} rows={rows}/>;
}
