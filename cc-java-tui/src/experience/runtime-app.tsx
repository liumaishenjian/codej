import {isAuthCommand} from './auth.js';
import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
import {useApp, useInput, useWindowSize} from 'ink';
import {edit, emptyDraft, glyphs, moveDraftVertical, moveDraftWord, type Draft} from './editor.js';
import {ExperienceRuntime, type Answer, type RecordBlock, type RuntimeClient} from './runtime.js';
import {newRuntimeUi, questionAnswers, readingState, runtimeCommands, runtimeFrame, runtimeViewportHeight, visitQuestion, type RuntimeUi} from './runtime-screen.js';
import {NativeHistoryScreen} from './native-history.js';
import {RuntimePresentation} from './presentation.js';

/** 真实适配器共享视觉基础，但绝不使用离线演示的计时推进或固定结果。 */
export function ExperienceRuntimeApp({client, workspace}: {client: RuntimeClient; workspace: string}) {
  const noHistory = useRef<RecordBlock[]>([]).current;
  const [runtime] = useState(() => new ExperienceRuntime(client, workspace));
  const [presentation] = useState(() => new RuntimePresentation(runtime));
  const state = useSyncExternalStore(presentation.subscribe, presentation.snapshot, presentation.snapshot);
  const [storedUi, setUi] = useState(newRuntimeUi);
  const [now, setNow] = useState(Date.now);
  const exiting = useRef(false);
  const restoredInput = useRef<number | undefined>(undefined);
  // 秘密只在短期 ref 中，固定容量避免每次按键产生不可清零的副本。
  const secret = useRef({bytes: new Uint8Array(16_384), count: 0, owner: ''});
  const clearSecret = () => {secret.current.bytes.fill(0); secret.current.count = 0;};
  useEffect(() => {
    const sync = () => {
      const s = runtime.state, p = runtime.auth.panel;
      const owner = s.connection === 'ready' && p?.phase === 'secret' ? s.session + ':' + p.operation + ':' + (p.promptId ?? 'legacy') : '';
      if (owner !== secret.current.owner) {clearSecret(); secret.current.owner = owner;}
    };
    sync(); const unsubscribe = runtime.subscribe(sync);
    return () => {unsubscribe(); clearSecret(); secret.current.owner = '';};
  }, [runtime]);
  const {columns, rows} = useWindowSize();
  const {exit} = useApp();
  useEffect(() => {runtime.connect(); return () => runtime.dispose();}, [runtime]);
  useEffect(() => {
    const rejected=state.rejectedInput;
    if(!rejected||restoredInput.current===rejected.id) return;
    restoredInput.current=rejected.id;
    setUi(previous=>previous.draft.text?previous:{...previous,draft:{text:rejected.text,cursor:glyphs(rejected.text).length},recall:-1,focus:0});
  },[state.rejectedInput]);
  useEffect(() => {
    if (state.status === 'idle') return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [state.status]);
  const pending = state.pending;
  const pendingKey = pending ? pending.kind + ':' + pending.event.sessionId + ':' + pending.event.sequence : '';
  // 新面板首帧即使用新身份，不能短暂显示上一份计划的编辑框或问卷选择。
  const ui = pendingKey && storedUi.key !== pendingKey ? {...storedUi, key: pendingKey, focus: 0, question: 0, questionFocus: {},
    answers: {}, free: {}, feedback: emptyDraft(), editing: false, panelScroll: 0} : storedUi;
  useEffect(() => {
    if (pendingKey) setUi(ui);
  }, [pendingKey]);
  // 普通界面历史由Static保管；键盘只需测量活动面板，展开时才测量完整记录。
  const measuredState = ui.expanded ? readingState(state, ui) : {...state, blocks: noHistory};
  const currentFrame = runtimeFrame(measuredState, ui, columns, runtimeViewportHeight(rows), now, false, runtime.auth.authorizationUrl);
  const previousTotal = useRef(currentFrame.total);
  const viewPositions = useRef(new Map<boolean, {scroll: number; total: number}>());
  useEffect(() => {
    const delta = currentFrame.total - previousTotal.current; previousTotal.current = currentFrame.total;
    if (delta && ui.scroll > 0) setUi(previous => ({...previous, scroll: Math.max(0, previous.scroll + delta)}));
  }, [currentFrame.total]);
  useInput((input, key) => {
    if (key.eventType === 'release' || exiting.current) return;
    // 使用当前控制器而非上一帧闭包；同一输入批次重复 Enter 不会启动第二次 helper。
    const auth = runtime.auth.panel;
    if (key.ctrl && input === 'c') {
      clearSecret(); exiting.current = true; runtime.cancel();
      void client.shutdown().catch(() => {}).finally(() => exit()); return;
    }
    if (auth?.phase === 'secret') {
      if (key.escape) {clearSecret(); runtime.cancel(); return;}
      if (runtime.state.connection !== 'ready') {clearSecret(); return;}
      if (columns < 40 || rows < 24) return;
      if (key.ctrl || key.meta || key.tab) return;
      if (key.return) {
        // 多行粘贴只能拒绝，绝不将其中的换行解释成提交。
        if (input !== '\r' && input !== '\n' && input !== '') return;
        if (!secret.current.count) return;
        const bytes = secret.current.bytes.slice(0, secret.current.count);
        clearSecret(); runtime.auth.submitSecret(bytes); return;
      }
      if (key.backspace || key.delete) {
        if (secret.current.count) secret.current.bytes[--secret.current.count] = 0;
      } else if (input && /^[\x20-\x7e]+$/.test(input) && secret.current.count + input.length <= 16_384) {
        for (let i = 0; i < input.length; i++) secret.current.bytes[secret.current.count++] = input.charCodeAt(i);
      }
      runtime.auth.secretCount(secret.current.count); return;
    }
    if (auth?.phase === 'login') {
      if (key.escape) {clearSecret(); runtime.cancel();}
      return;
    }
    if (state.auth) {
      if (key.escape) runtime.cancel();
      else if (columns >= 40 && rows >= 24 && state.connection === 'ready') {
        if (key.upArrow) runtime.auth.move(-1);
        else if (key.downArrow || key.tab) runtime.auth.move(1);
        else if (key.return && !key.meta && !key.ctrl) runtime.auth.enter();
        else if (key.backspace || key.delete) runtime.auth.input('', true);
        else if (!key.ctrl && !key.meta && input) runtime.auth.input(input);
      }
      return;
    }
    if (key.escape) {
      if (ui.editing && columns >= 40 && rows >= 24) setUi(previous => ({...previous, editing: false}));
      else runtime.cancel();
      return;
    }
    if (columns < 40 || rows < 24 || state.connection !== 'ready') return;
    const p = runtime.state.pending;
    if (key.ctrl && input === 'o') {
      viewPositions.current.set(ui.expanded, {scroll: ui.scroll, total: currentFrame.total});
      const expanded = !ui.expanded;
      const readLimit = expanded ? runtime.state.blocks.length : undefined;
      const nextFrame = runtimeFrame(runtime.state, {...ui, expanded, readLimit}, columns, runtimeViewportHeight(rows), now);
      const saved = viewPositions.current.get(expanded);
      // 两个视图分别记住回看位置；新输出追加时按行差补偿，不把切换当作新输出。
      const scroll = saved && saved.scroll > 0 ? Math.max(0, Math.min(nextFrame.maxScroll, saved.scroll + nextFrame.total - saved.total)) : 0;
      previousTotal.current = nextFrame.total;
      setUi(previous => ({...previous, expanded, readLimit, scroll})); return;
    }
    if (key.pageUp || key.pageDown) {
      if (!p && !state.showPlan && !ui.expanded) return;
      const direction = key.pageUp ? -1 : 1; const page = Math.max(1, rows - 10);
      setUi(previous => p || state.showPlan
        ? {...previous, panelScroll: Math.max(0, Math.min(currentFrame.maxPanelScroll, previous.panelScroll + direction * page))}
        : {...previous, scroll: Math.max(0, Math.min(currentFrame.maxScroll, previous.scroll - direction * page))});
      return;
    }
    if (key.end && ui.expanded && !p && !state.showPlan) {
      previousTotal.current = runtimeFrame(runtime.state, ui, columns, runtimeViewportHeight(rows), now).total;
      setUi(previous => ({...previous, readLimit: runtime.state.blocks.length, scroll: 0})); return;
    }
    function updateDraft(draft: Draft, preserveRecall = false): void {
      setUi(previous => {
        if (p?.kind === 'plan' && previous.editing) return {...previous, feedback: draft};
        if (p?.kind === 'questions' && previous.editing) {
          const q = p.questions[previous.question];
          return q ? {...previous, free: {...previous.free, [q.id]: draft}} : previous;
        }
        return {...previous, draft, focus: 0, recall: preserveRecall ? previous.recall : -1};
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
      runtime.patch({notice: ''});
      const answer: Answer = {questionId: q.id, optionIds: q.multiSelect ? a.optionIds.includes(option.optionId) ? a.optionIds.filter(id => id !== option.optionId) : [...a.optionIds, option.optionId] : [option.optionId], freeText: q.multiSelect ? a.freeText : ''};
      setUi(previous => {
        const updated = {...previous, answers: {...previous.answers, [q.id]: answer}, focus: index, panelScroll: 0};
        return q.multiSelect ? updated : visitQuestion(updated, p, previous.question + 1);
      });
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
          setUi(previous => visitQuestion(previous, p, previous.question + delta)); return;
        }
        if (!q) {
          if (key.return && !key.meta && !key.ctrl) {
            const answers = questionAnswers(p, ui);
            const missing = answers.findIndex(answer => !answer.optionIds.length && !answer.freeText.trim());
            if (missing >= 0) setUi(previous => visitQuestion(previous, p, missing));
            else runtime.answer(p, answers);
          }
          return;
        }
        const length = q.options.length + (q.allowFreeText ? 1 : 0);
        if (key.upArrow || key.downArrow) {setUi(previous => ({...previous, focus: (previous.focus + (key.upArrow ? length - 1 : 1)) % length, panelScroll: 0})); return;}
        if (/^[1-9]$/.test(input)) {selectQuestion(Number(input) - 1); return;}
        if (input === ' ' && q.multiSelect) {selectQuestion(ui.focus); return;}
        if (key.return && !key.meta && !key.ctrl) {
          if (q.multiSelect && ui.focus < q.options.length) {
            const a = ui.answers[q.id];
            if (a && (a.optionIds.length || a.freeText.trim())) setUi(previous => visitQuestion(previous, p, previous.question + 1));
            else runtime.patch({notice: '请至少选择一项，或填写自己的回答。'});
          } else selectQuestion(ui.focus);
        }
        return;
      }
      if (!q) return;
      if (key.return && !key.meta && !key.ctrl) {
        const answerText = ui.free[q.id]?.text.trim() ?? '';
        if (answerText.length > 2000) {runtime.patch({notice: '回答最多 2000 字符，请缩短后保存。'}); return;}
        const a = ui.answers[q.id];
        if (!answerText && !(q.multiSelect && a?.optionIds.length)) {runtime.patch({notice: '请输入回答，或按 Esc 返回选项。'}); return;}
        const answer: Answer = {questionId: q.id, optionIds: q.multiSelect ? a?.optionIds ?? [] : [], freeText: answerText};
        setUi(previous => {
          const updated = {...previous, editing: false, answers: {...previous.answers, [q.id]: answer}};
          return q.multiSelect ? {...updated, focus: 0} : visitQuestion(updated, p, previous.question + 1);
        });
        runtime.patch({notice: ''}); return;
      }
      if (key.tab) {setUi(previous => ({...previous, editing: false})); return;}
    } else {
      if (state.showPlan) return;
      const matches = ui.draft.text.startsWith('/') ? runtimeCommands.filter(command => command.startsWith(ui.draft.text)) : [];
      if (matches.length && key.tab && !key.shift) {
        // 补全只修改草稿；留下参数输入位置，绝不在Tab时进入计划或启动Run。
        updateDraft(edit(emptyDraft(), 'insert', matches[ui.focus % matches.length]! + ' '));
        return;
      }
      if (matches.length && (key.tab || key.upArrow || key.downArrow)) {setUi(previous => ({...previous, focus: (previous.focus + (key.upArrow || key.shift ? matches.length - 1 : 1)) % matches.length})); return;}
      if (key.return && !key.meta && !key.ctrl) {
        const prompt = matches.length && !runtimeCommands.includes(ui.draft.text) ? matches[ui.focus % matches.length]! : ui.draft.text;
        if (runtime.submit(prompt)) setUi(previous => ({...previous, draft: emptyDraft(), focus: 0, recall: -1, scroll: 0, readLimit: undefined,
          history: isAuthCommand(prompt) ? previous.history : [...previous.history, prompt].slice(-30)}));
        return;
      }
      if (key.upArrow || key.downArrow) {
        const moved = moveDraftVertical(ui.draft, key.upArrow ? 'up' : 'down', columns - 2);
        if (moved !== ui.draft) {updateDraft(moved, true); return;}
        if (state.status !== 'idle') return;
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
    else if (key.upArrow || key.downArrow) updateDraft(moveDraftVertical(draft, key.upArrow ? 'up' : 'down', columns - 2));
    else if ((key.ctrl && input === 'j') || (key.return && key.meta)) updateDraft(edit(draft, 'insert', '\n'));
    else if (key.home || (key.ctrl && input === 'a')) updateDraft(edit(draft, 'home'), true);
    else if (key.end || (key.ctrl && input === 'e')) updateDraft(edit(draft, 'end'), true);
    else if (key.leftArrow || key.rightArrow) {
      const direction = key.leftArrow ? 'left' : 'right';
      updateDraft(key.ctrl || key.meta ? moveDraftWord(draft, direction, columns - 2) : edit(draft, direction), true);
    }
    else if (key.backspace || key.delete) updateDraft(edit(draft, key.backspace ? 'backspace' : 'delete'));
    else if (!key.ctrl && !key.meta && !key.tab && input) {
      const updated = edit(draft, 'insert', input);
      if (updated === draft) runtime.patch({notice: '草稿超过 8000 字符，本次输入未插入。'});
      else updateDraft(updated);
    }
  });
  return <NativeHistoryScreen state={state} ui={ui} columns={columns} rows={rows} now={now}
    authorizationUrl={runtime.auth.authorizationUrl}/>;
}
