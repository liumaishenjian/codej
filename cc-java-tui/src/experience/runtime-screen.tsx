import {fileReviewRows} from './file-review.js';
import stringWidth from 'string-width';
import {glyphs} from './editor.js';
import type {Draft} from './editor.js';
import {emptyDraft} from './editor.js';
import {brandRows, editorRows, lines, palette, RowView, span, writer, type Row} from './screen.js';
import {markdownRows} from './markdown.js';
import type {Answer, QuestionsPanel, RecordBlock, RuntimeSnapshot, ToolRecord} from './runtime.js';
export interface RuntimeUi {
  draft: Draft; feedback: Draft; focus: number; question: number; answers: Record<string, Answer>; free: Record<string, Draft>;
  editing: boolean; key: string; expanded: boolean; scroll: number; panelScroll: number; recall: number; history: string[]; saved: Draft;
  questionFocus: Record<string, number>;
  readLimit?: number | undefined;
}
const readingBlocks = new WeakMap<RecordBlock[], {limit: number; blocks: RecordBlock[]}>();
/** 流式工具输出只保留排版尾窗；Java 仍负责真实输出上限，完成后再显示该上限内的全文。 */
const ACTIVE_OUTPUT_TAIL_CHARS = 16_384;
/** 限制阅读范围但始终使用最新记录；安全脱敏和终态修正不能被旧快照遮蔽。 */
export function readingState(state: RuntimeSnapshot, ui: RuntimeUi): RuntimeSnapshot {
  if (!ui.expanded || ui.readLimit === undefined || ui.readLimit >= state.blocks.length) return state;
  let cached = readingBlocks.get(state.blocks);
  if (!cached || cached.limit !== ui.readLimit) {
    cached = {limit: ui.readLimit, blocks: state.blocks.slice(0, ui.readLimit)};
    readingBlocks.set(state.blocks, cached);
  }
  return {...state, blocks: cached.blocks};
}
export const newRuntimeUi = (): RuntimeUi => ({draft: emptyDraft(), feedback: emptyDraft(), focus: 0, question: 0, questionFocus: {}, answers: {}, free: {}, editing: false, key: '', expanded: false, scroll: 0, panelScroll: 0, recall: -1, history: [], saved: emptyDraft()});
/** 按键与渲染必须使用同一活动区高度；给公共Ink保留两行，避免Windows全屏回退。 */
export const runtimeViewportHeight = (rows: number): number => Math.max(1, rows - 2);
/** 切题保存焦点，答案与未保存草稿仍分别保存；新请求由宿主面板身份统一重置。 */
export function visitQuestion(ui: RuntimeUi, panel: QuestionsPanel, index: number): RuntimeUi {
  const current = panel.questions[ui.question];
  const questionFocus = current ? {...ui.questionFocus, [current.id]: ui.focus} : ui.questionFocus;
  const question = Math.max(0, Math.min(panel.questions.length, index));
  const target = panel.questions[question];
  const focus = target ? questionFocus[target.id] ?? 0 : 0;
  return {...ui, question, questionFocus, focus, editing: false, panelScroll: 0};
}
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
/** 内部计划编排由专用计划面板和终态通知呈现，不进入普通工具流水。 */
const hiddenPlanTools = new Set([
  'task_list', 'task_get', 'task_create', 'task_update',
  'revise_plan_artifact', 'request_plan_review', 'declare_plan_evidence',
]);
function toolTitle(tool: ToolRecord): string {
  if (tool.name === 'run_command') return tool.shell || '命令';
  return ({web_search: '搜索网页', read_file: '读取文件', search_text: '搜索内容', search_content: '搜索内容', glob_files: '查找文件', list_files: '列出文件', write_file: '写入文件', apply_patch: '修改文件', ask_user_questions: '提问', ask_plan_question: '提问'} as Record<string, string>)[tool.name] ?? tool.name;
}
function failure(tool: ToolRecord, runActive: boolean): string {
  if (tool.failure === 'transport_lost') return '连接中断，执行结果未知';
  if (tool.failure === 'invalid_arguments' && tool.argumentChangeRequired) {
    return runActive ? '参数无效，已返回模型修正' : '工具参数无效';
  }
  if (tool.failure === 'invalid_arguments' && tool.failureCategory === 'validation' && tool.retryable) {
    return runActive ? '参数校验失败，等待模型重试' : '工具参数无效';
  }
  return (({file_conflict: '文件冲突，需重新读取核对', permission_denied: '操作已被拒绝', invalid_arguments: '工具参数无效', plan_gate_blocked: '计划尚未满足审核条件', timeout: '操作超时', cancelled: '已取消'} as Record<string, string>)[tool.failure] ?? tool.failure) || '工具执行失败';
}
/** 只去掉本项目命令结果固定前缀；不从文本中的退出码或标记决定工具状态。 */
function commandBody(tool: ToolRecord): string {
  if (tool.status === 'running') return tool.output;
  const body = tool.output.replace(/^shell: [^\n]*\nbackend: [^\n]*\nenforcement: [^\n]*\nfallback: [^\n]*\nworkingDirectory: [^\n]*\nexitCode: -?\d+\ntimedOut: (?:true|false)\ncancelled: (?:true|false)\nstdout:\n/, '');
  return body === tool.output ? body : body.replace(/\nstderr:\s*$/, '');
}
interface BodyProjection {
  width: number; expanded: boolean; hideHeader: boolean; pending: RuntimeSnapshot['pending'];
  status: RuntimeSnapshot['status']; model: string; workspace: string; rows: Row[];
}
// Runtime使用新数组/新记录发布变化。弱引用缓存不另外持有整个会话，也不按文本永久积累键。
const bodyProjections = new WeakMap<RecordBlock[], BodyProjection[]>();
const messageProjections = new WeakMap<RecordBlock, Map<number, Row[]>>();
const toolOutputProjections = new WeakMap<ToolRecord, Map<string, Row[]>>();
/** 工具输出按不可变 ToolRecord 缓存；流式更新会产生新记录，旧快照可被 GC。 */
function outputRows(tool: ToolRecord, width: number, mode: 'command' | 'full'): Row[] {
  const key = mode + ':' + width;
  const entries = toolOutputProjections.get(tool) ?? new Map<string, Row[]>();
  const hit = entries.get(key);
  if (hit) return hit;
  const raw = mode === 'command' ? commandBody(tool).trimEnd() : tool.output;
  const value = tool.status === 'running' && raw.length > ACTIVE_OUTPUT_TAIL_CHARS
    ? '[运行中较早输出已省略]\n' + raw.slice(-ACTIVE_OUTPUT_TAIL_CHARS)
    : raw;
  const rows = lines([span(value)], Math.max(1, width - 4))
    .map(row => ({spans: [span('    '), ...row.spans]}));
  entries.set(key, rows); toolOutputProjections.set(tool, entries);
  return rows;
}
function runtimeBody(state: RuntimeSnapshot, ui: RuntimeUi, width: number, hideHeader: boolean): Row[] {
  const entries = bodyProjections.get(state.blocks) ?? [];
  const hit = entries.find(entry => entry.width === width && entry.expanded === ui.expanded
    && entry.hideHeader === hideHeader && entry.pending === state.pending
    && entry.status === state.status
    && entry.model === state.model && entry.workspace === state.workspace);
  if (hit) return hit.rows;
  const rows = renderBody(state, ui, width, hideHeader);
  bodyProjections.set(state.blocks, [...entries.slice(-3), {width, expanded: ui.expanded, hideHeader,
    pending: state.pending, status: state.status, model: state.model, workspace: state.workspace, rows}]);
  return rows;
}
function messageRows(block: RecordBlock & {text: string}, width: number, tailOnly = false): Row[] {
  const entries = messageProjections.get(block) ?? new Map<number, Row[]>();
  const key = width * 2 + (tailOnly ? 1 : 0);
  const hit = entries.get(key);
  if (hit) return hit;
  const value = tailOnly && block.text.length > ACTIVE_OUTPUT_TAIL_CHARS
    ? '[运行中较早回答已省略]\n' + block.text.slice(-ACTIVE_OUTPUT_TAIL_CHARS)
    : block.text;
  const rows = markdownRows(value, width);
  if (entries.size >= 3) entries.delete(entries.keys().next().value!);
  entries.set(key, rows); messageProjections.set(block, entries);
  return rows;
}
function renderBody(state: RuntimeSnapshot, ui: RuntimeUi, width: number, hideHeader: boolean): Row[] {
  const body = writer(Math.max(12, width));
  const muted = (text: string) => [span(text, palette.muted)];
  const activeRun = state.status !== 'idle' ? state.blocks.findLast(block => block.kind === 'user')?.run : undefined;
  if (!hideHeader) {
  body.rows.push(...brandRows(width));
  body.add(muted('      ' + (state.model ? '配置模型：' + state.model : '配置模型：等待运行信息')));
  body.add(muted('      ' + state.workspace)); body.blank();
  if (!state.blocks.length) {body.add(' 输入任务开始，或使用 /plan 先规划。'); body.blank();}
  }
  for (let index = 0; index < state.blocks.length; index++) {
    const block = state.blocks[index]!;
    if (block.kind === 'user') {body.add('❯ ' + block.text, palette.background); body.blank(); continue;}
    if (block.kind === 'assistant') {
      const tailOnly = activeRun !== undefined && block.run === activeRun;
      const rich = messageRows(block, Math.max(10, width - 2), tailOnly);
      rich.forEach((row, i) => body.rows.push({...row, spans: [span(i ? '  ' : '● '), ...row.spans]}));
      if (tailOnly && block.text.length > ACTIVE_OUTPUT_TAIL_CHARS) body.add(muted('  … 运行中仅显示回答尾部，完成后保留全文'));
      body.blank(); continue;
    }
    if (block.kind === 'notice') {body.add(muted('  ' + block.text.replace(/\n/g, '\n  '))); body.blank(); continue;}
    if (block.kind !== 'tool') continue;
    if (hiddenPlanTools.has(block.name)) continue;
    const group: ToolRecord[] = [block];
    if (!ui.expanded && /^(search_text|search_content|glob_files)$/.test(block.name) && block.status === 'completed') {
      while (index + 1 < state.blocks.length) {
        const next = state.blocks[index + 1]!;
        if (next.kind !== 'tool' || next.name !== block.name || next.run !== block.run || next.turn !== block.turn || next.status !== 'completed') break;
        group.push(next); index++;
      }
    }
    const running = group.some(tool => tool.status === 'running');
    const awaitingApproval = state.pending?.kind === 'approval' && state.pending.ordinal === block.ordinal
      && state.pending.event.runId === block.run;
    body.add([span('● ', block.status === 'failed' ? palette.red : running ? palette.blue : undefined),
      span(group.length > 1 ? toolTitle(block) + ' · ' + group.length + ' 次调用' : toolTitle(block), undefined, true),
      span((group.some(tool => tool.preview) ? ' (' + compact(group.map(tool => tool.preview).filter(Boolean).join('；'), Math.max(10, width - 35)) + ')' : '') + '  (Ctrl+O ' + (ui.expanded ? '收起' : '展开') + ')', palette.muted)]);
    const search = /^(search_text|search_content|glob_files|web_search)$/.test(block.name);
    const returned = group.every(tool => tool.returnedItems !== undefined) ? group.reduce((sum, tool) => sum + tool.returnedItems!, 0) : undefined;
    // 命令/网页输出带协议包装头，不能把shell或provenance当作用户结果；正文仍在详情中。
    const summary = awaitingApproval ? '等待审批' : running ? block.name === 'run_command' ? '正在执行…' : block.activity || '正在执行…' : block.status === 'failed' ? failure(block, state.status !== 'idle') : block.status === 'cancelled' ? '已取消'
      : block.name === 'run_command' ? '命令执行完成'
      : search ? (returned !== undefined ? '返回 ' + returned + ' 项结果' : block.name === 'web_search' ? '网页搜索完成' : '搜索完成')
      : block.resultSummary ? resultSummaryText(block)
      : block.name === 'write_file' ? '文件创建完成' : block.name === 'apply_patch' ? '文件修改完成'
      : block.output.trim().split('\n').find(Boolean)?.slice(0, 140) || '完成';
    body.add([span('  └ ' + summary, block.status === 'failed' ? palette.red : palette.muted)]);
    if (!ui.expanded && block.name === 'run_command' && block.output) {
      const preview = outputRows(block, width, 'command');
      // 预览以实际终端行计，长中文行也不挤走输入；运行时跟随末尾，完成后从头读。
      const shown = running ? preview.slice(-3) : preview.slice(0, 3);
      shown.forEach(row => body.rows.push(row));
      if (preview.length > shown.length) body.add(muted(block.status === 'running' && block.output.length > ACTIVE_OUTPUT_TAIL_CHARS
        ? '    … 运行中仅显示最近输出，完成后可展开全部'
        : '    … 更多输出，Ctrl+O 展开'));
    }
    if (ui.expanded) {
      if (block.preview) body.add(muted('    ' + block.preview));
      if (block.directory) body.add(muted('    目录：' + block.directory));
      if (block.fileChange?.status === 'available') {
        body.add(muted('    审批时的修改意图（执行结果见本次工具状态）'));
        body.rows.push(...fileReviewRows(block.fileChange, width));
      }
      if (block.output) body.rows.push(...outputRows(block, width, 'full'));
      else body.add(muted(running ? '    等待工具输出…' : '    宿主未提供可显示的输出正文'));
    }
    if (group.some(tool => tool.truncated)) body.add([span('    输出已截断', palette.accent)]);
    body.blank();
  }
  return body.rows;
}
function resultSummaryText(block: ToolRecord): string {
  const summary = block.resultSummary!;
  const operation = summary.operation === 'created' ? '已创建' : summary.operation === 'modified' ? '已修改' : '已完成';
  const verified = summary.verification === 'verified' ? ' · 已核验' : '';
  const counts = summary.replacements !== undefined ? ' · ' + summary.replacements + ' 处替换' : '';
  return operation + ' ' + summary.path + verified + counts;
}
export function runtimeFrame(state: RuntimeSnapshot, ui: RuntimeUi, width: number, height: number, now = Date.now(), hideHeader = false): {rows: Row[]; bodyRows: Row[]; fixedRows: Row[]; total: number; maxScroll: number; maxPanelScroll: number} {
  const panel = writer(Math.max(12, width)), detail = writer(Math.max(12, width));
  const muted = (text: string) => [span(text, palette.muted)];
  const rule = () => panel.add(muted('─'.repeat(width)));
  const body = {rows: runtimeBody(state, ui, width, hideHeader)};
  if (ui.expanded && state.notice && !state.pending && state.diagnostics?.length) {
    const diagnostic=writer(Math.max(12,width));
    diagnostic.blank();diagnostic.add(muted('运行诊断（最近事件；仅内存，不含正文和参数）'));
    for(const entry of state.diagnostics.slice(-12)) diagnostic.add(muted('  '+entry));
    body.rows=[...body.rows,...diagnostic.rows];
  }
  const pending = state.pending;
  const noticeRows = state.notice ? lines([span(state.notice, palette.accent)], Math.max(12, width)) : [];
  let fixed: Row[] = []; let maxPanelScroll = 0;
  if (pending?.kind === 'approval') {
    detail.add([span(pending.command ? ' ' + (pending.shell || '命令审批') : ' 操作审批', undefined, true)]); detail.blank();
    if (pending.command) {detail.add('  ' + pending.command); detail.add(muted('  目录：' + pending.directory));}
    else {
      detail.add('  ' + pending.tool + (pending.target ? ' · ' + pending.target : ''));
      const file = pending.tool === 'write_file' || pending.tool === 'apply_patch';
      if (pending.operation) detail.add(muted('  ' + (pending.operation === 'create' ? '待创建文件' : pending.operation === 'modify' ? '待修改文件' : pending.operation)));
      if (file) {
        if (pending.removedLines !== undefined || pending.addedLines !== undefined) detail.add([
          span('  待替换片段：'), span('移除 ' + (pending.removedLines ?? '未知') + ' 行', palette.red),
          span(' · '), span('写入 ' + (pending.addedLines ?? '未知') + ' 行', palette.green)]);
        const change = pending.fileChange;
        if (change?.status === 'available') {
          detail.blank(); detail.add(muted('  修改意图预览（执行前仍会校验文件）'));
          detail.rows.push(...fileReviewRows(change, width));
        } else detail.add(muted(change?.status === 'redacted' ? '  内容含敏感信息或控制字符，未展示正文'
          : change?.status === 'too_large' ? '  内容超过预览上限，仅显示摘要'
          : '  宿主未提供变更正文，仅显示摘要'));
      }
    }
    const options = ['允许本次操作', pending.sessionScope ? '本会话允许此范围' : '本会话允许此工具', '拒绝本次操作'];
    options.forEach((value, i) => panel.add([span((i === ui.focus ? '❯ ' : '  ') + (i + 1) + '. ' + value, i === ui.focus ? palette.blue : undefined)]));
    panel.blank(); panel.add(muted('↑↓ 选择 · Enter 确认 · Esc 停止本轮'));
  } else if (pending?.kind === 'questions') {
    const q = pending.questions[ui.question];
    // 导航只承担切题定位；长标题不能把当前问题推到数屏之后。
    const tabWidth = Math.max(2, Math.floor((width - 6 - pending.questions.length * 2) / (pending.questions.length + 1)));
    const tabs = [...pending.questions.map(value => value.title), '提交'].map((title, i) => {
      const label = compact(title, tabWidth);
      return i === ui.question ? '[' + label + ']' : label;
    });
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
      detail.add([span(' ' + q.question, undefined, true)]);
      if (compact(q.title, tabWidth) !== q.title) detail.add(muted(q.title));
      detail.blank();
      const answer = ui.answers[q.id];
      const options = [...q.options.map(o => o.label), ...(q.allowFreeText ? ['自行填写'] : [])];
      // 按终端高度提供焦点窗口，原序号与全部选项保持不变。
      const visibleCount = Math.max(2, Math.min(options.length, Math.floor(height / 3)));
      const from = Math.max(0, Math.min(options.length - visibleCount, ui.focus - Math.floor(visibleCount / 2)));
      options.slice(from, from + visibleCount).forEach((label, offset) => {
        const i = from + offset;
        const chosen = i < q.options.length ? answer?.optionIds.includes(q.options[i]!.optionId) : !!answer?.freeText;
        const marker = ui.focus === i ? '❯ ' : offset === 0 && from > 0 ? '↑ ' : offset === visibleCount - 1 && from + visibleCount < options.length ? '↓ ' : '  ';
        panel.add([span(marker + (i + 1) + '. ' + (q.multiSelect ? chosen ? '[✓] ' : '[ ] ' : '') + compact(label, width - (q.multiSelect ? 10 : 6)), ui.focus === i ? palette.blue : undefined)]);
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
    rule(); panel.rows.push(...editorRows(ui.draft, width, state.connection === 'ready' ? '输入任务' : state.connection === 'closed' ? '连接已断开' : '等待连接'));
    const matches = ui.draft.text.startsWith('/') ? runtimeCommands.filter(command => command.startsWith(ui.draft.text)) : [];
    matches.forEach((command, index) => panel.add([span((index === ui.focus % matches.length ? '❯ ' : '  ') + command, index === ui.focus % matches.length ? palette.blue : palette.muted)]));
    rule(); panel.add(muted(ui.expanded ? 'Ctrl+O 返回 · End 最新 · PgUp/PgDn 阅读' : '  ' + (state.mode === 'plan' ? '计划模式' : '人工审批') + (state.status !== 'idle' ? ' · 后续草稿已保留' : ' · Ctrl+J 换行 · /help')));
  }
  if (pending || state.showPlan) {
    const allowance = Math.max(1, height - panel.rows.length - noticeRows.length - 5);
    maxPanelScroll = Math.max(0, detail.rows.length - allowance);
    const top = Math.min(ui.panelScroll, maxPanelScroll);
    fixed = [...lines(muted('─'.repeat(width)), width), ...detail.rows.slice(top, top + allowance), {spans: []}, ...panel.rows,
      ...lines(muted('─'.repeat(width)), width)];
    if (maxPanelScroll) fixed.push(...lines(muted('PgUp/PgDn 阅读详情 · ' + (top + 1) + '/' + detail.rows.length), width));
  } else fixed = panel.rows;
  fixed.push(...noticeRows);
  if (width < 40 || height < 22) return {rows: lines([span('请调整终端至至少 40 列 × 24 行。\nEsc 仍可停止任务，Ctrl+C 可退出。', palette.accent)], Math.max(1, width)), bodyRows: body.rows, fixedRows: fixed, total: body.rows.length, maxScroll: 0, maxPanelScroll};
  const budget = Math.max(0, height - fixed.length - 1);
  const maximum = Math.max(0, body.rows.length - budget);
  const end = body.rows.length - Math.min(ui.scroll, maximum);
  let top = Math.max(0, end - budget);
  const visible = body.rows.slice(top, end);
  return {rows: [...visible, ...fixed].slice(-height), bodyRows: body.rows, fixedRows: fixed, total: body.rows.length, maxScroll: maximum, maxPanelScroll};
}
export function RuntimeScreen({state, ui, columns, rows, now}: {state: RuntimeSnapshot; ui: RuntimeUi; columns: number; rows: number; now: number}) {
  return <RowView columns={columns} rows={runtimeFrame(state, ui, columns, rows, now).rows}/>;
}
