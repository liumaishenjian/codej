import {render, renderToString} from 'ink';
import {ExperienceApp} from './app.js';
import {initial, start, type Experience, type Phase} from './state.js';
import {Screen} from './screen.js';

/** 演示入口刻意不引用 StdioClient，启动即保证没有真实工具或模型调用。 */
export function sample(phase: Phase): Experience {
  let state = start(initial(), phase === 'error' ? 'error' : 'demo', '检查模型配置，并验证配置文件');
  if (phase === 'idle') return initial();
  state = {...state, phase, searchComplete: phase !== 'search', tick: phase === 'command' ? 9 : 3};
  if (['command', 'question', 'review', 'respond', 'done', 'error'].includes(phase)) state.approval = '允许本次（演示）';
  if (phase === 'review' || phase === 'done') {state.question = 2; state.answers = [[1], [0, 1]];}
  return state;
}
const sceneArg = process.argv.find(value => value.startsWith('--scene='))?.slice(8);
const valid: Phase[] = ['idle', 'search', 'approval', 'command', 'question', 'review', 'respond', 'done', 'error', 'stopped'];
if (sceneArg && !valid.includes(sceneArg as Phase)) throw new Error('未知演示场景');
if (process.argv.includes('--snapshot')) {
  const columns = Number(process.argv.find(value => value.startsWith('--width='))?.slice(8) ?? 100);
  const rows = Number(process.argv.find(value => value.startsWith('--rows='))?.slice(7) ?? 30);
  if (!Number.isInteger(columns) || columns < 20 || columns > 200 || !Number.isInteger(rows) || rows < 10 || rows > 100) throw new Error('无效终端尺寸');
  const state = {...sample((sceneArg as Phase) ?? 'search'), expanded: process.argv.includes('--expanded')};
  process.stdout.write(renderToString(<Screen state={state} columns={columns} rows={rows}/>, {columns}) + '\n');
} else if (!process.stdin.isTTY || !process.stdout.isTTY) {
  process.stderr.write('请在交互终端运行；静态检查可添加 --snapshot。\n'); process.exitCode = 2;
} else {
  const app = render(<ExperienceApp {...(sceneArg ? {seed: sample(sceneArg as Phase)} : {})}/>, {exitOnCtrlC: false, interactive: true, incrementalRendering: true, maxFps: 30});
  await app.waitUntilExit();
}
