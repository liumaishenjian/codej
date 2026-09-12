import stringWidth from 'string-width';
import {glyphs} from './editor.js';
import type {Draft} from './editor.js';
import {emptyDraft} from './editor.js';
import {editorRows, lines, palette, RowView, span, writer, type Row} from './screen.js';
import {markdownRows} from './markdown.js';
import type {Answer, QuestionsPanel, RuntimeSnapshot, ToolRecord} from './runtime.js';
export interface RuntimeUi {
  draft: Draft; feedback: Draft; focus: number; question: number; answers: Record<string, Answer>; free: Record<string, Draft>;
  editing: boolean; key: string; expanded: boolean; scroll: number; panelScroll: number; recall: number; history: string[]; saved: Draft;
}
export const newRuntimeUi = (): RuntimeUi => ({draft: emptyDraft(), feedback: emptyDraft(), focus: 0, question: 0, answers: {}, free: {}, editing: false, key: '', expanded: false, scroll: 0, panelScroll: 0, recall: -1, history: [], saved: emptyDraft()});
export const runtimeCommands = ['/plan', '/help'];
export function questionAnswers(panel: QuestionsPanel, ui: RuntimeUi): Answer[] {
  return panel.questions.map(q => ui.answers[q.id] ?? {questionId: q.id, optionIds: [], freeText: ''});
}
function compact(value: string, width: number): string {
  let result = '';
  for (const part of glyphs(value.replace(/[\r\n\t]/g, ' '))) {
    if (stringWidth(result + part) > width - 1) return result + '…';
    result += part;
  }
  return result;
}
/** 内部计划编排不占用对话；审核使用专用面板，失败仍保留可见诊断。 */
const planningToolTitles: Record<string, string> = {
  task_list: '查看任务清单', task_get: '查看任务', task_create: '记录执行任务', task_update: '更新任务状态',
  revise_plan_artifact: '更新计划', request_plan_review: '提交计划审核', declare_plan_evidence: '记录验证要求',
};
function toolTitle(tool: ToolRecord): string {
  if (tool.name === 'run_command') return tool.shell || '命令';
  return ({web_search: '搜索网页', read_file: '读取文件', search_text: '搜索内容', search_content: '搜索内容', glob_files: '查找文件', list_files: '列出文件', write_file: '写入文件', apply_patch: '修改文件', ask_user_questions: '提问', ask_plan_question: '提问'} as Record<string, string>)[tool.name] ?? tool.name;
}
function failure(tool: ToolRecord): string {
  return (({permission_denied: '操作已被拒绝', invalid_arguments: '工具参数无效', plan_gate_blocked: '计划尚未满足审核条件', timeout: '操作超时', cancelled: '已取消'} as Record<string, string>)[tool.failure] ?? tool.failure) || '工具执行失败';
}
export function runtimeFrame(state: RuntimeSnapshot, ui: RuntimeUi, width: number, height: number, now = Date.now()): {rows: Row[]; total: number; maxScroll: number; maxPanelScroll: number} {
  const body = writer(Math.max(12, width)), panel = writer(Math.max(12, width)), detail = writer(Math.max(12, width));
  const muted = (text: string) => [span(text, palette.muted)];
  const rule = () => panel.add(muted('─'.repeat(width)));
  body.add([span(' ▐›▌ ', palette.accent, true), span('codej', undefined, true), span('  新界面', palette.muted)]);
  body.add(muted('      ' + (state.model ? '配置模型：' + state.model : '配置模型：等待运行信息')));
  body.add(muted('      ' + state.workspace)); body.blank();
  if (!state.blocks.length) {body.add(' 输入任务开始，或使用 /plan 先规划。'); body.blank();}
  for (let index = 0; index < state.blocks.length; index++) {
    const block = state.blocks[index]!;
    if (block.kind === 'user') {body.add('❯ ' + block.text, palette.background); body.blank(); continue;}
    if (block.kind === 'assistant') {
      const rich = markdownRows(block.text, Math.max(10, width - 2));
      rich.forEach((row, i) => body.rows.push({...row, spans: [span(i ? '  ' : '● '), ...row.spans]}));
      body.blank(); continue;
    }
    if (block.kind === 'notice') {body.add(muted('  ' + block.text.replace(/\n/g, '\n  '))); body.blank(); continue;}
    if (block.kind !== 'tool') continue;
    if (planningToolTitles[block.name] && block.status !== 'failed') continue;
    if (planningToolTitles[block.name]) {
      const unavailable = block.failureReasonCode === 'verification_tool_unavailable';
      const recovered = block.reviewRecovered || (unavailable && block.recoveredByOrdinal > block.ordinal);
      const summary = block.reviewRecovered ? '审核条件已补齐，计划已提交审核'
        : recovered ? '已修正验证方式，继续规划'
        : unavailable ? '验证方式使用了当前不可用的工具'
        : block.status === 'running' ? '正在处理…'
        : block.status === 'failed' ? failure(block)
        : block.status === 'cancelled' ? '已取消' : '完成';
      body.add([span('● ', block.status === 'failed' && !recovered ? palette.red : undefined), span(planningToolTitles[block.name]!, undefined, true)]);
      body.add([span('  └ ' + summary, block.status === 'failed' && !recovered ? palette.red : palette.muted)]);
      if (ui.expanded && unavailable) {
        body.add(muted('    原声明：失败（验证工具不可用）'));
        if (recovered) body.add(muted('    后续声明：修正成功'));
      }
      body.blank(); continue;
    }
    const group: ToolRecord[] = [block];
    if (!ui.expanded && /^(search_text|search_content|glob_files)$/.test(block.name) && block.status !== 'failed') {
      while (index + 1 < state.blocks.length) {
        const next = state.blocks[index + 1]!;
        if (next.kind !== 'tool' || next.name !== block.name || next.run !== block.run || next.turn !== block.turn || next.status === 'failed') break;
        group.push(next); index++;
      }
    }
    const running = group.some(tool => tool.status === 'running');
    body.add([span('● ', block.status === 'failed' ? palette.red : running ? palette.blue : undefined),
      span(group.length > 1 ? toolTitle(block) + ' · ' + group.length + ' 项' : toolTitle(block), undefined, true),
      span((block.preview ? ' (' + compact(block.preview, Math.max(10, width - 35)) + ')' : '') + '  (Ctrl+O ' + (ui.expanded ? '收起' : '展开') + ')', palette.muted)]);
    // 命令/网页输出带协议包装头，不能把shell或provenance当作用户结果；正文仍在详情中。
    const summary = running ? block.activity || '正在执行…' : block.status === 'failed' ? failure(block) : block.status === 'cancelled' ? '已取消'
      : block.name === 'run_command' ? '命令执行完成'
      : block.name === 'web_search' ? '网页搜索完成'
      : block.output.trim().split('\n').find(Boolean)?.slice(0, 140) || '完成';
    body.add([span('  └ ' + summary, block.status === 'failed' ? palette.red : palette.muted)]);
    if (ui.expanded) {
      if (block.preview) body.add(muted('    ' + block.preview));
      if (block.directory) body.add(muted('    目录：' + block.directory));
      if (block.output) body.add('    ' + block.output.replace(/\n/g, '\n    '));
      else body.add(muted(running ? '    等待工具输出…' : '    宿主未提供可显示的输出正文'));
      if (block.truncated) body.add([span('    输出已截断', palette.accent)]);
    }
    body.blank();
  }
  const pending = state.pending;
  let fixed: Row[] = []; let maxPanelScroll = 0;
  if (pending?.kind === 'approval') {
    detail.add([span(pending.command ? ' ' + (pending.shell || '命令审批') : ' 操作审批', undefined, true)]); detail.blank();
    if (pending.command) {detail.add('  ' + pending.command); detail.add(muted('  目录：' + pending.directory));}
    else {detail.add('  ' + pending.tool + (pending.target ? ' · ' + pending.target : '')); if (pending.operation) detail.add(muted('  ' + pending.operation));}
    const options = ['允许本次操作', pending.sessionScope ? '本会话允许此范围' : '本会话允许此工具', '拒绝本次操作'];
    options.forEach((value, i) => panel.add([span((i === ui.focus ? '❯ ' : '  ') + (i + 1) + '. ' + value, i === ui.focus ? palette.blue : undefined)]));
    panel.blank(); panel.add(muted('↑↓ 选择 · Enter 确认 · Esc 停止本轮'));
  } else if (pending?.kind === 'questions') {
    const q = pending.questions[ui.question];
    const tabs = pending.questions.map((value, i) => (i === ui.question ? '[' + value.title + ']' : value.title)).concat(ui.question === pending.questions.length ? '[提交]' : '提交');
    detail.add([span('← ' + tabs.join('  ') + ' →', palette.blue)]); detail.blank();
    if (!q) {
      detail.add([span(' 核对你的回答', undefined, true)]);
      questionAnswers(pending, ui).forEach((a, i) => {
        const question = pending.questions[i]!;
        detail.add('  ' + question.title);
        const values = a.optionIds.map(id => question.options.find(o => o.optionId === id)?.label ?? '');
        detail.add([span('    ' + [...values, a.freeText].filter(Boolean).join('、'), palette.blue)]);
        if (!a.optionIds.length && !a.freeText.trim()) detail.add([span('    尚未回答', palette.accent)]);
      });
      panel.add([span('❯ 提交回答', palette.blue)]);
      panel.add(muted('Enter 提交 · Shift+Tab 返回 · Esc 取消'));
    } else {
      detail.add([span(' ' + q.question, undefined, true)]); detail.blank();
      const answer = ui.answers[q.id];
      const options = [...q.options.map(o => o.label), ...(q.allowFreeText ? ['自行填写'] : [])];
      options.forEach((label, i) => {
        const chosen = i < q.options.length ? answer?.optionIds.includes(q.options[i]!.optionId) : !!answer?.freeText;
        panel.add([span((ui.focus === i ? '❯ ' : '  ') + (i + 1) + '. ' + (q.multiSelect ? chosen ? '[✓] ' : '[ ] ' : '') + compact(label, width - (q.multiSelect ? 10 : 6)), ui.focus === i ? palette.blue : undefined)]);
        if (ui.focus === i && !ui.editing && q.options[i]) {if (compact(label, width - (q.multiSelect ? 10 : 6)) !== label) detail.add('当前选项：' + label); if (q.options[i]!.description) detail.add(muted(q.options[i]!.description));}
      });
      if (ui.editing) panel.rows.push(...editorRows(ui.free[q.id] ?? emptyDraft(), width, '输入回答'));
      panel.blank();
      panel.add(muted(ui.editing ? 'Enter 保存 · Esc 返回选项 · Ctrl+J 换行' : q.multiSelect ? '空格多选 · Enter 继续 · Tab 切题 · Esc 取消' : '↑↓ / 数字选择 · Enter 确认 · Tab 切题 · Esc 取消'));
    }
  } else if (pending?.kind === 'plan' || state.showPlan) {
    const plan = pending?.kind === 'plan' ? pending : state.plan;
    detail.add([span(' 当前计划', undefined, true)]); detail.blank();
    if (plan) detail.rows.push(...markdownRows(plan.markdown, width));
    if (pending?.kind === 'plan') {
      ['确认并执行', '提出修改意见', '取消计划'].forEach((label, i) => panel.add([span((ui.focus === i ? '❯ ' : '  ') + (i + 1) + '. ' + label, ui.focus === i ? palette.blue : undefined)]));
      if (ui.editing) panel.rows.push(...editorRows(ui.feedback, width, '说明需要调整的内容'));
      panel.blank(); panel.add(muted(state.status !== 'idle' ? '正在等待规划结束，暂不能确认 · Esc 停止' : ui.editing ? 'Enter 发送意见 · Esc 返回 · PgUp/PgDn 阅读计划' : 'Enter 确认 · PgUp/PgDn 阅读计划 · Esc 取消'));
    } else panel.add(muted('Esc 返回输入 · PgUp/PgDn 阅读计划'));
  } else {
    if (state.status !== 'idle' || state.connection === 'connecting') {
      const ticks = Math.max(0, Math.floor((now - state.startedAt) / 250));
      panel.add([span(['·', '✧', '✦', '✧'][ticks % 4] + ' ' + state.activity + '…', palette.accent),
        span(state.startedAt ? ' (' + Math.max(0, Math.floor((now - state.startedAt) / 1000)) + 's · Esc 停止)' : '', palette.muted)]); panel.blank();
    }
    rule(); panel.rows.push(...editorRows(ui.draft, width, state.connection === 'ready' ? '输入任务' : '等待连接'));
    const matches = ui.draft.text.startsWith('/') ? runtimeCommands.filter(command => command.startsWith(ui.draft.text)) : [];
    matches.forEach((command, index) => panel.add([span((index === ui.focus % matches.length ? '❯ ' : '  ') + command, index === ui.focus % matches.length ? palette.blue : palette.muted)]));
    rule(); panel.add(muted('  ' + (state.mode === 'plan' ? '计划模式' : '人工审批') + (state.status !== 'idle' ? ' · 后续草稿已保留' : ' · Ctrl+J 换行 · /help')));
  }
  if (pending || state.showPlan) {
    const allowance = Math.max(1, height - panel.rows.length - 5);
    maxPanelScroll = Math.max(0, detail.rows.length - allowance);
    const top = Math.min(ui.panelScroll, maxPanelScroll);
    fixed = [...lines(muted('─'.repeat(width)), width), ...detail.rows.slice(top, top + allowance), {spans: []}, ...panel.rows,
      ...lines(muted('─'.repeat(width)), width)];
    if (maxPanelScroll) fixed.push(...lines(muted('PgUp/PgDn 阅读详情 · ' + (top + 1) + '/' + detail.rows.length), width));
  } else fixed = panel.rows;
  if (state.notice) fixed.push(...lines([span(state.notice, palette.accent)], width));
  if (width < 40 || height < 24) return {rows: lines([span('请调整终端至至少 40 列 × 24 行。\nEsc 仍可停止任务，Ctrl+C 可退出。', palette.accent)], Math.max(1, width)), total: body.rows.length, maxScroll: 0, maxPanelScroll};
  const budget = Math.max(0, height - fixed.length - 1);
  const maximum = Math.max(0, body.rows.length - budget);
  const end = body.rows.length - Math.min(ui.scroll, maximum);
  let top = Math.max(0, end - budget);
  if (top > 0 && top < 4) top = 4;
  const visible = body.rows.slice(top, end);
  if (top > 0 || ui.scroll > 0) visible.unshift(...lines(muted(ui.scroll ? '↑ 回看中 · End 返回最新' : '↑ 更早内容 · PgUp 回看'), width));
  return {rows: [...visible, ...fixed].slice(-height), total: body.rows.length, maxScroll: maximum, maxPanelScroll};
}
export function RuntimeScreen({state, ui, columns, rows, now}: {state: RuntimeSnapshot; ui: RuntimeUi; columns: number; rows: number; now: number}) {
  return <RowView columns={columns} rows={runtimeFrame(state, ui, columns, rows, now).rows}/>;
}
