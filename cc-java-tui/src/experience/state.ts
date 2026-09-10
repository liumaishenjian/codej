import {emptyDraft, type Draft} from './editor.js';
export type Phase = 'idle' | 'search' | 'approval' | 'command' | 'question' | 'review' | 'respond' | 'done' | 'error' | 'stopped';
export type Scenario = 'demo' | 'bash' | 'questions' | 'diff' | 'error';
export const questions = [
  {title: '说明方式', text: '你希望如何查看模型配置？', multi: false,
    labels: ['简洁结论', '逐项解释', '给出配置示例', '自行填写'],
    descriptions: ['先说明文件位置和当前问题', '说明 provider、model 等配置项的用途', '用一段短配置说明填写方式', '输入自己的要求']},
  {title: '检查范围', text: '还需要检查哪些内容？', multi: true,
    labels: ['默认模型', '环境覆盖', '启动参数', '自行填写'],
    descriptions: ['确认默认使用的模型名称', '确认环境配置是否覆盖默认值', '确认命令行参数的优先级', '补充其他检查范围']},
] as const;
export interface Experience {
  phase: Phase; scenario: Scenario; message: string; draft: Draft; feedback: Draft;
  free: Draft[]; answers: number[][]; question: number; focus: number; editing: boolean;
  tick: number; searchComplete: boolean; expanded: boolean; scroll: number; notice: string; mode: '人工审批' | '计划模式';
  recent: string[]; recall: number; savedDraft: Draft; archived: {message: string; summary: string}[];
  help: boolean; approval: string;
}
export function initial(): Experience {
  return {phase: 'idle', scenario: 'demo', message: '', draft: emptyDraft(), feedback: emptyDraft(),
    free: [emptyDraft(), emptyDraft()], answers: [[], []], question: 0, focus: 0, editing: false,
    tick: 0, searchComplete: false, expanded: false, scroll: 0, notice: '', mode: '人工审批', recent: [], recall: -1,
    savedDraft: emptyDraft(), archived: [], help: false, approval: ''};
}
export function isBusy(s: Experience): boolean {return ['search', 'command', 'respond'].includes(s.phase);}
export function isModal(s: Experience): boolean {return ['approval', 'question', 'review'].includes(s.phase);}
export function answerText(s: Experience, index: number): string {
  return (s.answers[index] ?? []).map(value => value === 3 ? s.free[index]!.text.trim() : questions[index]!.labels[value]).filter(Boolean).join('、');
}
export function start(s: Experience, scenario: Scenario, text: string): Experience {
  const archived = s.message ? [...s.archived, {message: s.message, summary: s.phase === 'done' ? '上一轮演示已完成' : '上一轮演示已停止'}].slice(-12) : s.archived;
  return {...initial(), recent: [...s.recent, text].slice(-30), archived, mode: s.mode, scenario, message: text,
    phase: scenario === 'questions' ? 'question' : scenario === 'demo' ? 'search' : 'approval'};
}
export function submit(s: Experience): Experience {
  const text = s.draft.text.trim();
  if (isBusy(s)) return {...s, notice: '草稿已保留，当前演示结束后按 Enter 发送'};
  if (text === '/help') return {...s, help: !s.help, draft: emptyDraft()};
  if (text === '/clear') return {...initial(), recent: s.recent, mode: s.mode};
  const commands: Record<string, Scenario> = {'/demo': 'demo', '/bash': 'bash', '/questions': 'questions', '/diff': 'diff', '/error': 'error'};
  if (text.startsWith('/') && !commands[text]) return {...s, notice: '未知演示入口。输入 /help 查看可用命令。'};
  const scenario = commands[text] ?? 'demo';
  const descriptions: Record<Scenario, string> = {demo: '检查模型配置，并验证配置文件', bash: '运行配置校验', questions: '先确认配置检查的要求', diff: '预览默认模型配置的修改', error: '演示配置校验失败后的呈现'};
  return start(s, scenario, text && !text.startsWith('/') ? text : descriptions[scenario]);
}
/** 固定演示时序；退出活动阶段后不再推进，不存在网络或真实副作用。 */
export function advance(s: Experience): Experience {
  if (!isBusy(s)) return s;
  const next = {...s, tick: s.tick + 1};
  if (s.phase === 'search' && next.tick >= 10) return {...next, phase: 'approval', tick: 0, focus: 0, searchComplete: true};
  if (s.phase === 'command' && next.tick >= 14) return {...next, tick: 0, phase: s.scenario === 'error' ? 'error' : s.scenario === 'demo' ? 'question' : 'respond', focus: 0};
  if (s.phase === 'respond' && next.tick >= 8) return {...next, phase: 'done', tick: 0};
  return next;
}
export function cancel(s: Experience): Experience {
  if (s.help) return {...s, help: false};
  if (s.editing) return {...s, editing: false, notice: ''};
  if (isBusy(s) || isModal(s)) return {...s, phase: 'stopped', tick: 0, editing: false, notice: '已停止演示，草稿保留'};
  return {...s, notice: '', expanded: false, scroll: 0};
}
export function changeQuestion(s: Experience, delta: number): Experience {
  const question = Math.max(0, Math.min(2, s.question + delta));
  return {...s, question, phase: question === 2 ? 'review' : 'question', focus: (s.answers[question] ?? [])[0] ?? 0, editing: false, notice: ''};
}
export function choose(s: Experience, digit?: number): Experience {
  const focus = digit ?? s.focus;
  if (s.phase === 'approval') {
    if (focus === 2) return {...s, phase: 'stopped', editing: false, notice: s.feedback.text.trim() ? '已停止演示；附加说明已保留在本次记录中' : '已拒绝，演示未执行命令'};
    return {...s, phase: s.scenario === 'diff' ? 'respond' : 'command', tick: 0, editing: false, approval: focus === 1 ? '本会话允许（演示）' : '允许本次（演示）', notice: ''};
  }
  if (s.phase === 'review') {
    const missing = questions.findIndex((_, index) => !answerText(s, index));
    if (missing >= 0) return {...s, phase: 'question', question: missing, focus: 0, notice: '请先回答这一题'};
    return {...s, phase: 'respond', tick: 0, notice: ''};
  }
  if (s.phase !== 'question') return s;
  if (focus === 3 && !s.editing) return {...s, focus, editing: true, notice: ''};
  if (s.editing && !s.free[s.question]!.text.trim()) return {...s, notice: '请输入回答，或按 Esc 返回选项'};
  const current = s.answers[s.question]!;
  const multi = questions[s.question]!.multi;
  const selected = multi ? s.editing ? [...new Set([...current, focus])] : current.includes(focus) ? current.filter(value => value !== focus) : [...current, focus] : [focus];
  const answers = s.answers.map((value, index) => index === s.question ? selected : value);
  const next = {...s, answers, focus, editing: false, notice: ''};
  return multi ? next : changeQuestion(next, 1);
}


export const demoCommands = ['/demo', '/bash', '/questions', '/diff', '/error', '/help', '/clear'];
export function candidates(s: Experience): string[] {return s.draft.text.startsWith('/') ? demoCommands.filter(value => value.startsWith(s.draft.text)) : [];}
