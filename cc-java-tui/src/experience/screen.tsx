import {Box, Text} from 'ink';
import stringWidth from 'string-width';
import {glyphs, type Draft} from './editor.js';
import {answerText, candidates, isBusy, questions, type Experience} from './state.js';

export const palette = {accent: '#D89470', muted: '#989BA3', blue: '#A7C6DA', red: '#E18C8C', green: '#99BB94', background: '#363636'};
export interface Span {text: string; color?: string; bold?: boolean; inverse?: boolean}
export interface Row {spans: Span[]; background?: string}
export const span = (text: string, color?: string, bold = false): Span => ({text, ...(color ? {color} : {}), ...(bold ? {bold} : {})});
export const rowText = (row: Row): string => row.spans.map(part => part.text).join('');

/** 将布局转成终端单元格行，统一处理中文宽度和视口；不把 ANSI 控制字节当正文。 */
export function lines(parts: Span[], width: number, background?: string): Row[] {
  const result: Row[] = [];
  let current: Span[] = []; let used = 0;
  const flush = () => {result.push({spans: current, ...(background ? {background} : {})}); current = []; used = 0;};
  for (const part of parts) {
    for (const glyph of glyphs(part.text.replace(/\r/g, '').replace(/[\x00-\x08\x0b-\x1f\x7f-\x9f]/g, ''))) {
      if (glyph === '\n') {flush(); continue;}
      const cells = stringWidth(glyph);
      if (used + cells > width && used) flush();
      current.push({...part, text: glyph}); used += cells;
    }
  }
  flush(); return result;
}
export function writer(width: number) {
  const rows: Row[] = [];
  return {rows, add: (text: string | Span[], background?: string) => rows.push(...lines(typeof text === 'string' ? [span(text)] : text, width, background)),
    blank: () => rows.push({spans: []})};
}
export function editorRows(draft: Draft, width: number, placeholder: string): Row[] {
  if (!draft.text) return lines([span('❯ ', palette.accent), {text: ' ', inverse: true}, span(placeholder, palette.muted)], width);
  const result: Row[] = []; let current: Span[] = [span('❯ ', palette.accent)]; let used = 2; let cursorRow = 0;
  const chars = glyphs(draft.text);
  for (let index = 0; index <= chars.length; index++) {
    const char = chars[index] ?? ' ';
    if (char !== '\n' && used + stringWidth(char) > width) {result.push({spans: current}); current = [span('  ')]; used = 2;}
    const cursor = index === draft.cursor;
    if (cursor) cursorRow = result.length;
    if (char === '\n') {
      if (cursor) current.push({text: ' ', inverse: true});
      result.push({spans: current}); current = [span('  ')]; used = 2;
    } else {
      current.push({text: char, ...(cursor ? {inverse: true} : {})}); used += stringWidth(char);
    }
  }
  result.push({spans: current});
  const top = Math.max(0, cursorRow - 2);
  return result.slice(top, top + 3);
}

export function frame(s: Experience, columns: number, height: number): Row[] {
  const width = Math.max(12, columns);
  const body = writer(width), panel = writer(width);
  const muted = (text: string) => [span(text, palette.muted)];
  const rule = () => panel.add(muted('─'.repeat(width)));
  body.add([span(' ▐›▌ ', palette.accent, true), span('codej', undefined, true), span('  前端预览 1', palette.muted)]);
  body.add(muted('      离线演示 · 不调用模型或执行命令'));
  body.add(muted('      ~/example-project'));
  body.blank();
  if (s.phase === 'idle' && !s.message) {
    body.add(' 从一段真实的交互流程开始。');
    body.add(muted(' /demo 完整流程   /bash 命令   /questions 提问'));
    body.add(muted(' /diff 文件变更   /error 失败   /help 操作说明'));
    body.blank();
  }
  for (const turn of s.archived) {
    body.add('❯ ' + turn.message, palette.background); body.blank();
    body.add(muted('● ' + turn.summary)); body.blank();
  }
  if (s.message) {
    body.add('❯ ' + s.message, palette.background); body.blank();
    if (s.scenario === 'demo') {
      const stopped = s.phase === 'stopped' && !s.searchComplete;
      body.add([span('● ', s.phase === 'search' ? palette.blue : undefined),
        span(s.phase === 'search' ? '正在搜索 2 组配置关键词…' : stopped ? '搜索已停止' : '已搜索 2 组配置关键词'),
        span('  (Ctrl+O ' + (s.expanded ? '收起' : '展开') + ')', palette.muted)]);
      body.add(muted('  └ ' + (s.phase === 'search' ? 'model、provider · config/' : 'config/model.example.json')));
      if (s.expanded) {
        body.add(muted('    Search(model, config/)'));
        body.add(muted('      model.example.json:3  model: example-small'));
        body.add(muted('    Search(provider, config/)'));
        body.add(muted('      model.example.json:2  provider: example'));
      }
      body.blank();
    }
    if (s.approval && s.scenario !== 'diff') {
      body.add([span('● ', s.phase === 'error' ? palette.red : undefined), span('Bash', undefined, true), span(' (npm run check:config)', palette.muted)]);
      const result = s.phase === 'command' ? '正在校验配置…' : s.phase === 'error' ? '缺少 model 字段 · 退出码 1' : s.phase === 'stopped' ? '命令已停止（模拟）' : '配置检查通过';
      body.add([span('  └ ' + result, s.phase === 'error' ? palette.red : palette.muted)]);
      if (s.expanded) {
        body.add(muted('    $ npm run check:config'));
        body.add(muted('    > node scripts/check-config.mjs'));
        if (s.tick > 3 || s.phase !== 'command') body.add(muted('    [1/3] 读取配置文件'));
        if (s.tick > 7 || s.phase !== 'command') body.add(muted('    [2/3] 检查 provider 和 model'));
        if (s.tick > 10 || s.phase !== 'command') body.add(muted('    [3/3] ' + (s.phase === 'error' ? 'model 必填，当前为空' : '格式校验完成')));
        body.add(muted('    所有输出均由演示生成'));
      }
      body.blank();
    }
    if (s.feedback.text && s.phase !== 'approval') {body.add(muted('  附加说明：' + s.feedback.text)); body.blank();}
    if (s.phase === 'done' || s.phase === 'respond') {
      const answer = s.scenario === 'diff'
        ? ['● 已完成文件变更演示。', '  config/model.example.json 的默认模型已在预览中更新。', '  实际文件没有被修改。']
        : ['● 配置位于 config/model.example.json。', '  provider 指定服务，model 指定模型名称。', '  这是固定示例结果，不是对当前工作区的分析。'];
      const count = s.phase === 'respond' ? Math.min(3, 1 + Math.floor(s.tick / 3)) : 3;
      for (const text of answer.slice(0, count)) body.add(text);
      if (s.scenario === 'questions' || s.scenario === 'demo') {
        body.blank();
        for (let index = 0; index < 2; index++) if (answerText(s, index)) body.add(muted('  ' + questions[index]!.title + '：' + answerText(s, index)));
      }
      body.blank();
    }
    if (s.phase === 'error') {
      body.add([span('● 校验未通过', palette.red, true)]);
      body.add('  请为 model 补充一个模型名称，再重新运行校验。');
      body.add(muted('  输入 /error 可重新演示；Ctrl+O 查看错误输出。')); body.blank();
    }
    if (s.phase === 'stopped') {body.add(muted('● 本轮已停止')); body.blank();}
  }
  if (s.help) {
    body.add([span('操作说明', undefined, true)]);
    for (const text of ['Enter 发送 / 确认；Ctrl+J 换行；←→ 移动光标', 'Ctrl+O 展开完整记录；PgUp/PgDn 回看；End 回到底部', '问卷：↑↓ 选择，数字快捷选择，空格多选', 'Tab / Shift+Tab 切题；自行填写后 Enter 保存', 'Esc 停止或退出编辑；Ctrl+C 退出程序', '/clear 清空演示；Shift+Tab 切换演示模式']) body.add(muted('  ' + text));
    body.blank();
  }
  if (isBusy(s)) {
    panel.add([span(['·', '✧', '✦', '✧'][s.tick % 4] + ' ' + (s.phase === 'search' ? '正在查找配置' : s.phase === 'command' ? '正在运行校验' : '正在整理回答') + '…', palette.accent),
      span(' (' + (s.tick / 4).toFixed(0) + 's · Esc 停止)', palette.muted)]);
    panel.blank();
  }
  if (s.phase === 'approval') {
    rule();
    panel.add([span(s.scenario === 'diff' ? ' 文件变更' : ' Bash 命令', undefined, true)]);
    panel.blank();
    if (s.scenario === 'diff') {
      panel.add('  config/model.example.json');
      panel.add([span('  -  "model": "example-small"', palette.red)]);
      panel.add([span('  +  "model": "example-large"', palette.green)]);
      panel.add(muted('  调整默认模型；仅预览，不写入文件'));
    } else {
      panel.add('  npm run check:config');
      panel.add(muted('  检查配置文件格式 · Bash · ~/example-project'));
    }
    panel.blank();
    ['允许本次操作', '本会话允许此操作（演示）', '拒绝并停止'].forEach((label, index) => {
      panel.add([span((s.focus === index ? '❯ ' : '  ') + (index + 1) + '. ' + label, s.focus === index ? palette.blue : undefined)]);
    });
    if (s.editing) {
      panel.blank(); panel.add(muted('  附加说明（仅保存在演示中）'));
      panel.rows.push(...editorRows(s.feedback, width, '输入说明'));
    }
    panel.blank();
    panel.add(muted(s.editing ? 'Enter 确认 · Tab 返回选项 · Esc 退出编辑' : '↑↓ / 数字选择 · Enter 确认 · Tab 附加说明 · Esc 停止'));
    rule();
  } else if (s.phase === 'question' || s.phase === 'review') {
    rule();
    panel.add([span('← ', palette.muted), ...['说明方式', '检查范围', '提交'].map((title, index) => span(' ' + (index === s.question ? '[' + title + ']' : title) + ' ', index === s.question ? palette.blue : palette.muted)), span(' →', palette.muted)]);
    panel.blank();
    if (s.phase === 'review') {
      panel.add([span(' 核对你的回答', undefined, true)]);
      for (let index = 0; index < 2; index++) {panel.add('  ' + questions[index]!.title); panel.add([span('    ' + (answerText(s, index) || '尚未回答'), answerText(s, index) ? palette.blue : palette.red)]);}
      panel.blank(); panel.add([span('❯ 提交回答', palette.blue)]);
    } else {
      const q = questions[s.question]!;
      panel.add([span(' ' + q.text, undefined, true)]); panel.blank();
      q.labels.forEach((label, index) => {
        const chosen = s.answers[s.question]!.includes(index);
        const marker = q.multi ? chosen ? '[✓] ' : '[ ] ' : '';
        panel.add([span((s.focus === index ? '❯ ' : '  ') + (index + 1) + '. ' + marker + label, s.focus === index ? palette.blue : undefined)]);
        if (s.focus === index && !s.editing) panel.add(muted('     ' + q.descriptions[index]));
      });
      if (s.editing) {panel.blank(); panel.rows.push(...editorRows(s.free[s.question]!, width, '输入你的回答'));}
      if (q.multi && !s.editing) panel.add(muted('  已选：' + (answerText(s, s.question) || '无')));
    }
    panel.blank();
    panel.add(muted(s.editing ? 'Enter 保存 · Esc 返回选项 · Ctrl+J 换行' : s.phase === 'review' ? 'Enter 提交 · Shift+Tab 返回修改 · Esc 取消' : questions[s.question]!.multi ? '空格多选 · Enter 继续 · Tab 切题 · Esc 取消' : '↑↓ / 数字选择 · Enter 确认 · Tab 切题 · Esc 取消'));
    rule();
  } else {
    rule();
    panel.rows.push(...editorRows(s.draft, width, s.phase === 'idle' ? '输入任务，或按 Enter 体验完整流程' : ''));
    const matches = candidates(s);
    const candidateTop = Math.max(0, (s.focus % Math.max(1, matches.length)) - 3);
    matches.slice(candidateTop, candidateTop + 4).forEach((value, index) => panel.add([span((index + candidateTop === s.focus % matches.length ? '❯ ' : '  ') + value, index + candidateTop === s.focus % matches.length ? palette.blue : palette.muted)]));
    rule();
    panel.add(muted('  ' + s.mode + ' · ' + (isBusy(s) ? '草稿会保留' : 'Ctrl+J 换行 · /help')));
  }
  if (s.notice) panel.add([span(s.notice, palette.accent)]);
  // 极短终端明确提示调整尺寸，避免裁掉审批决策但继续接受不可见操作。
  if (height < 24 || width < 40) return lines([span('请将终端调整至至少 40 列 × 24 行。\n当前演示暂停接受操作；Ctrl+C 可退出。', palette.accent)], width);
  const budget = Math.max(0, height - panel.rows.length - 1);
  const end = Math.max(0, body.rows.length - s.scroll);
  const rawTop = Math.max(0, end - budget);
  const top = rawTop > 0 && rawTop < 4 ? 4 : rawTop;
  const visible = body.rows.slice(top, end);
  if (top > 0 || s.scroll > 0) visible.unshift(...lines(muted(s.scroll ? '↑ 正在回看 · PgDn 向下 · End 返回最新' : '↑ 更早内容 · PgUp 回看'), width));
  return [...visible, ...panel.rows].slice(-height);
}
export function Screen({state, columns, rows}: {state: Experience; columns: number; rows: number}) {
  return <RowView rows={frame(state, columns, rows)} columns={columns}/>;
}
export function RowView({rows, columns}: {rows: Row[]; columns: number}) {
  return <Box width={columns} flexDirection="column">{rows.map((row, index) => {
    const pad = row.background ? ' '.repeat(Math.max(0, columns - stringWidth(rowText(row)))) : '';
    return <Text key={index} {...(row.background ? {backgroundColor: row.background} : {})}>{row.spans.map((part, i) => <Text key={i} {...(part.color ? {color: part.color} : {})} {...(part.bold ? {bold: true} : {})} {...(part.inverse ? {inverse: true} : {})}>{part.text}</Text>)}{pad}{row.spans.length === 0 ? ' ' : ''}</Text>;
  })}</Box>;
}
