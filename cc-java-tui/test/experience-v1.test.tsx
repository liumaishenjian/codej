import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import {renderToString} from 'ink';
import stringWidth from 'string-width';
import {ExperienceApp} from '../src/experience/app.js';
import {frame, rowText, Screen} from '../src/experience/screen.js';
import {answerText, advance, choose, initial, start, type Experience, type Phase} from '../src/experience/state.js';
import {edit, emptyDraft} from '../src/experience/editor.js';
const size = vi.hoisted(() => ({columns: 80, rows: 24}));
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => size}));
afterEach(() => {cleanup(); size.columns = 80; size.rows = 24;});
const wait = (ms = 60) => new Promise(resolve => setTimeout(resolve, ms));
function seed(phase: Phase): Experience {return {...start(initial(), 'demo', '检查示例配置'), phase};}
it('字素编辑、光标插入和多行不会破坏emoji或组合字符', () => {
  let d = edit(emptyDraft(), 'insert', '中👩‍💻é');
  d = edit(d, 'backspace'); expect(d.text).toBe('中👩‍💻');
  d = edit(d, 'left'); d = edit(d, 'insert', '文\n'); expect(d.text).toBe('中文\n👩‍💻');
  d = edit(d, 'delete'); expect(d.text).toBe('中文\n');
});
it('各种正常尺寸面板保留选择和取消入口，Ink真实渲染保持留白', () => {
  for (const columns of [40, 80, 120]) for (const rows of [24, 35]) for (const phase of ['approval', 'question', 'review', 'command'] as const) {
    const s = seed(phase);
    const built = frame(s, columns, rows);
    expect(built.length).toBeLessThanOrEqual(rows);
    expect(built.every(row => stringWidth(rowText(row)) <= columns)).toBe(true);
    if (phase === 'approval') expect(built.map(rowText).join('\n')).toContain('拒绝并停止');
    if (phase === 'question') expect(built.map(rowText).join('\n')).toContain('自行填写');
    const ink = renderToString(<Screen state={s} columns={columns} rows={rows}/>, {columns});
    expect(ink.split('\n').length).toBeLessThanOrEqual(rows);
    expect(ink).toContain('Esc');
  }
});
it('附加说明和正文隔离，拒绝后不会被计时器重新启动', async () => {
  const app = render(<ExperienceApp seed={{...seed('approval'), draft: {text: '后续草稿', cursor: 4}}}/>); await wait();
  const key = async (text: string) => {app.stdin.write(text); await wait();};
  await key('3'); await key('\t'); await key('请先解释'); await key('\r');
  expect(app.lastFrame()).toContain('本轮已停止');
  expect(app.lastFrame()).toContain('请先解释');
  expect(app.lastFrame()).toContain('后续草稿');
  await wait(350); expect(app.lastFrame()).not.toContain('正在运行校验');
});
it('多题包含自由回答、多选和返回修改，最终提交前保留答案', async () => {
  const app = render(<ExperienceApp seed={seed('question')}/>); await wait();
  const key = async (text: string) => {app.stdin.write(text); await wait();};
  await key('4'); await key('中文😀说明'); await key('\r');
  expect(app.lastFrame()).toContain('还需要检查哪些内容');
  await key('1'); await key('2'); await key('\r');
  expect(app.lastFrame()).toContain('核对你的回答');
  expect(app.lastFrame()).toContain('中文😀说明');
  expect(app.lastFrame()).toContain('默认模型、环境覆盖');
  await key('\x1b[Z'); expect(app.lastFrame()).toContain('[✓]');
  await key('\t'); await key('\r'); expect(app.lastFrame()).toContain('正在整理回答');
});
it('正文编辑、斜杠菜单、详情切换及缩放保留草稿', async () => {
  const app = render(<ExperienceApp/>); await wait();
  const key = async (text: string) => {app.stdin.write(text); await wait();};
  await key('/'); expect(app.lastFrame()).toContain('/questions');
  await key('\x15'); await key('中文😀'); await key('\x1b[D'); await key('插入');
  await key('\x0f'); await key('\x0f');
  expect(app.lastFrame()).toContain('中文插入😀');
  size.columns = 40; app.rerender(<ExperienceApp/>); await wait(); expect(app.lastFrame()).toContain('中文插入😀');
});
it('漏题复核不能提交，自由多选再次编辑不会撤销已有选择', () => {
  let s = {...seed('review'), question: 2};
  s = choose(s); expect(s.phase).toBe('question');
  s = {...s, question: 1, editing: true, focus: 3, answers: [[], [3]], free: [emptyDraft(), {text: '额外范围', cursor: 4}]};
  s = choose(s); expect(answerText(s, 1)).toBe('额外范围');
});
it('完整离线流程经过审批、命令、问题、复核后到回答，停止状态不会前进', () => {
  let s = start(initial(), 'demo', '检查配置');
  for (let i = 0; i < 10; i++) s = advance(s);
  expect(s.phase).toBe('approval');
  s = choose(s);
  for (let i = 0; i < 14; i++) s = advance(s);
  expect(s.phase).toBe('question');
  s = choose(s, 1); s = choose(s, 0);
  s = {...s, phase: 'review', question: 2}; s = choose(s);
  for (let i = 0; i < 8; i++) s = advance(s);
  expect(s.phase).toBe('done');
  expect(advance({...s, phase: 'stopped'}).phase).toBe('stopped');
});
