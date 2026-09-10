import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
import {useApp, useInput, useWindowSize} from 'ink';
import {edit, emptyDraft, glyphs, type Draft} from './editor.js';
import {ExperienceRuntime, type Answer, type RuntimeClient} from './runtime.js';
import {newRuntimeUi, questionAnswers, runtimeCommands, runtimeFrame, RuntimeScreen, type RuntimeUi} from './runtime-screen.js';

/** 真实适配器共享视觉基础，但绝不使用离线演示的计时推进或固定结果。 */
export function ExperienceRuntimeApp({client, workspace}: {client: RuntimeClient; workspace: string}) {
  const [runtime] = useState(() => new ExperienceRuntime(client, workspace));
  const state = useSyncExternalStore(runtime.subscribe, runtime.snapshot, runtime.snapshot);
  const [ui, setUi] = useState(newRuntimeUi);
  const [now, setNow] = useState(Date.now);
  const exiting = useRef(false);
  const {columns, rows} = useWindowSize();
  const {exit} = useApp();
  useEffect(() => {runtime.connect(); return () => runtime.dispose();}, [runtime]);
  useEffect(() => {
    if (state.status === 'idle') return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [state.status]);
  const pending = state.pending;
  const pendingKey = pending ? pending.kind + ':' + pending.event.sessionId + ':' + pending.event.sequence : '';
  useEffect(() => {
    if (pendingKey) setUi(previous => previous.key === pendingKey ? previous : {...previous, key: pendingKey, focus: 0, question: 0, answers: {}, free: {}, feedback: emptyDraft(), editing: false, panelScroll: 0});
  }, [pendingKey]);
  const currentFrame = runtimeFrame(state, ui, columns, rows, now);
  const previousTotal = useRef(currentFrame.total);
  useEffect(() => {
    const delta = currentFrame.total - previousTotal.current; previousTotal.current = currentFrame.total;
    if (delta && ui.scroll > 0) setUi(previous => ({...previous, scroll: Math.max(0, previous.scroll + delta)}));
  }, [currentFrame.total]);
  useInput((input, key) => {
    if (key.eventType === 'release' || exiting.current) return;
    if (key.ctrl && input === 'c') {
      exiting.current = true; runtime.cancel();
      void client.shutdown().catch(() => {}).finally(() => exit()); return;
    }
    if (key.escape) {
      if (ui.editing && columns >= 40 && rows >= 24) setUi(previous => ({...previous, editing: false}));
      else runtime.cancel();
      return;
    }
    if (columns < 40 || rows < 24 || state.connection !== 'ready') return;
    const p = runtime.state.pending;
    if (key.ctrl && input === 'o') {setUi(previous => ({...previous, expanded: !previous.expanded, scroll: 0})); return;}
    if (key.pageUp || key.pageDown) {
      const direction = key.pageUp ? -1 : 1; const page = Math.max(1, rows - 10);
      setUi(previous => p || state.showPlan
        ? {...previous, panelScroll: Math.max(0, Math.min(currentFrame.maxPanelScroll, previous.panelScroll + direction * page))}
        : {...previous, scroll: Math.max(0, Math.min(currentFrame.maxScroll, previous.scroll - direction * page))});
      return;
    }
    if (key.end && ui.scroll && !p) {setUi(previous => ({...previous, scroll: 0})); return;}
    function updateDraft(draft: Draft): void {
      setUi(previous => {
        if (p?.kind === 'plan' && previous.editing) return {...previous, feedback: draft};
        if (p?.kind === 'questions' && previous.editing) {
          const q = p.questions[previous.question];
          return q ? {...previous, free: {...previous.free, [q.id]: draft}} : previous;
        }
        return {...previous, draft, focus: 0, recall: -1};
      });
    }
    function selectQuestion(index: number): void {
      if (p?.kind !== 'questions') return;
      const q = p.questions[ui.question]; if (!q) return;
      const a = ui.answers[q.id] ?? {questionId: q.id, optionIds: [], freeText: ''};
      if (index === q.options.length) {
        if (q.allowFreeText) setUi(previous => ({...previous, focus: index, editing: true, free: {...previous.free, [q.id]: previous.free[q.id] ?? {text: a.freeText, cursor: glyphs(a.freeText).length}}}));
        return;
      }
      const option = q.options[index]; if (!option) return;
      const answer: Answer = {questionId: q.id, optionIds: q.multiSelect ? a.optionIds.includes(option.optionId) ? a.optionIds.filter(id => id !== option.optionId) : [...a.optionIds, option.optionId] : [option.optionId], freeText: q.multiSelect ? a.freeText : ''};
      setUi(previous => ({...previous, answers: {...previous.answers, [q.id]: answer}, focus: q.multiSelect ? index : 0, question: q.multiSelect ? previous.question : previous.question + 1, panelScroll: 0}));
    }
    if (p?.kind === 'approval') {
      if (key.upArrow || key.downArrow) setUi(previous => ({...previous, focus: (previous.focus + (key.upArrow ? 2 : 1)) % 3}));
      else if (/^[1-3]$/.test(input)) setUi(previous => ({...previous, focus: Number(input) - 1}));
      else if (key.return && !key.meta && !key.ctrl) runtime.approve(p, (['allow_once', 'allow_session', 'deny'] as const)[ui.focus % 3]!);
      return;
    }
    if (p?.kind === 'plan') {
      if (!ui.editing) {
        if (key.upArrow || key.downArrow) setUi(previous => ({...previous, focus: (previous.focus + (key.upArrow ? 2 : 1)) % 3}));
        else if (/^[1-3]$/.test(input)) setUi(previous => ({...previous, focus: Number(input) - 1}));
        else if (key.return && !key.meta && !key.ctrl && state.status === 'idle') {
          if (ui.focus === 1) setUi(previous => ({...previous, editing: true}));
          else runtime.review(p, ui.focus === 0 ? 'APPROVE_USER' : 'REJECT', '');
        }
        return;
      }
      if (key.return && !key.meta && !key.ctrl) {runtime.review(p, 'CONTINUE_PLANNING', ui.feedback.text); return;}
    } else if (p?.kind === 'questions') {
      const q = p.questions[ui.question];
      if (!ui.editing) {
        if (key.tab || key.leftArrow || key.rightArrow) {
          const delta = key.leftArrow || (key.tab && key.shift) ? -1 : 1;
          setUi(previous => ({...previous, question: Math.max(0, Math.min(p.questions.length, previous.question + delta)), focus: 0, panelScroll: 0})); return;
        }
        if (!q) {
          if (key.return && !key.meta && !key.ctrl) {
            const answers = questionAnswers(p, ui);
            const missing = answers.findIndex(answer => !answer.optionIds.length && !answer.freeText.trim());
            if (missing >= 0) setUi(previous => ({...previous, question: missing, focus: 0}));
            else runtime.answer(p, answers);
          }
          return;
        }
        const length = q.options.length + (q.allowFreeText ? 1 : 0);
        if (key.upArrow || key.downArrow) {setUi(previous => ({...previous, focus: (previous.focus + (key.upArrow ? length - 1 : 1)) % length})); return;}
        if (/^[1-9]$/.test(input)) {selectQuestion(Number(input) - 1); return;}
        if (input === ' ' && q.multiSelect) {selectQuestion(ui.focus); return;}
        if (key.return && !key.meta && !key.ctrl) {
          if (q.multiSelect && ui.focus < q.options.length) {
            const a = ui.answers[q.id];
            if (a && (a.optionIds.length || a.freeText.trim())) setUi(previous => ({...previous, question: previous.question + 1, focus: 0, panelScroll: 0}));
            else runtime.patch({notice: '请至少选择一项，或填写自己的回答。'});
          } else selectQuestion(ui.focus);
        }
        return;
      }
      if (!q) return;
      if (key.return && !key.meta && !key.ctrl) {
        const answerText = ui.free[q.id]?.text.trim() ?? '';
        if (answerText.length > 2000) {runtime.patch({notice: '回答最多 2000 字符，请缩短后保存。'}); return;}
        if (!answerText) {runtime.patch({notice: '请输入回答，或按 Esc 返回选项。'}); return;}
        const a = ui.answers[q.id];
        const answer: Answer = {questionId: q.id, optionIds: q.multiSelect ? a?.optionIds ?? [] : [], freeText: answerText};
        setUi(previous => ({...previous, editing: false, answers: {...previous.answers, [q.id]: answer}, question: q.multiSelect ? previous.question : previous.question + 1, focus: 0}));
        runtime.patch({notice: ''}); return;
      }
      if (key.tab) {setUi(previous => ({...previous, editing: false})); return;}
    } else {
      if (state.showPlan) return;
      const matches = ui.draft.text.startsWith('/') ? runtimeCommands.filter(command => command.startsWith(ui.draft.text)) : [];
      if (matches.length && (key.tab || key.upArrow || key.downArrow)) {setUi(previous => ({...previous, focus: (previous.focus + (key.upArrow || key.shift ? matches.length - 1 : 1)) % matches.length})); return;}
      if (key.return && !key.meta && !key.ctrl) {
        const prompt = matches.length && !runtimeCommands.includes(ui.draft.text) ? matches[ui.focus % matches.length]! : ui.draft.text;
        if (runtime.submit(prompt)) setUi(previous => ({...previous, draft: emptyDraft(), focus: 0, recall: -1, scroll: 0, history: [...previous.history, prompt].slice(-30)}));
        return;
      }
      if (state.status === 'idle' && !ui.draft.text.includes('\n') && (key.upArrow || key.downArrow)) {
        setUi(previous => {
          const recall = Math.max(-1, Math.min(previous.history.length - 1, previous.recall + (key.upArrow ? 1 : -1)));
          const saved = previous.recall === -1 ? previous.draft : previous.saved;
          const value = previous.history[previous.history.length - 1 - recall] ?? '';
          return {...previous, recall, saved, draft: recall === -1 ? saved : {text: value, cursor: glyphs(value).length}};
        }); return;
      }
    }
    const draft = ui.editing ? p?.kind === 'plan' ? ui.feedback : p?.kind === 'questions' ? ui.free[p.questions[ui.question]!.id] ?? emptyDraft() : ui.draft : ui.draft;
    if (key.ctrl && input === 'u') updateDraft(emptyDraft());
    else if ((key.ctrl && input === 'j') || (key.return && key.meta)) updateDraft(edit(draft, 'insert', '\n'));
    else if (key.home || (key.ctrl && input === 'a')) updateDraft(edit(draft, 'home'));
    else if (key.end || (key.ctrl && input === 'e')) updateDraft(edit(draft, 'end'));
    else if (key.leftArrow) updateDraft(edit(draft, 'left'));
    else if (key.rightArrow) updateDraft(edit(draft, 'right'));
    else if (key.backspace || key.delete) updateDraft(edit(draft, key.backspace ? 'backspace' : 'delete'));
    else if (!key.ctrl && !key.meta && !key.tab && input) {
      const updated = edit(draft, 'insert', input);
      if (updated === draft) runtime.patch({notice: '草稿超过 8000 字符，本次输入未插入。'});
      else updateDraft(updated);
    }
  });
  return <RuntimeScreen state={state} ui={ui} columns={columns} rows={rows} now={now}/>;
}
