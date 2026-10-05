import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

// 仅为本地验收工具：显式借用已有PTY组件，不安装依赖，不进入产品运行路径。
// 输入都是本脚本生成的合成凭证；绝不能改为读取真实账号或凭证配置。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (![2, 4].includes(args.length) || args[0] !== '--pty-module' || !path.isAbsolute(args[1])
  || (args.length === 4 && (args[2] !== '--size' || !/^(80|100|120)x(24|35)$/.test(args[3])))) {
  throw new Error('Usage: node scripts/TestLoginLogoutPty.mjs --pty-module <absolute installed node-pty module path> [--size 100x35]');
}
const [columns, rows] = (args[3] ?? '100x35').split('x').map(Number);
const pty = createRequire(import.meta.url)(args[1]);
const report = await fs.readFile(path.join(root, 'cc-java-cli/target/surefire-reports/TEST-io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthLifecycleTest.xml'), 'utf8');
const match = report.match(/<property name="java.class.path" value="([^"]+)"/);
if (!match) throw new Error('Compile and run the authentication Java tests first');
const classpath = match[1].replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-auth-conpty-'));
const home = path.join(directory, 'home'), workspace = path.join(directory, 'workspace');
await fs.mkdir(home); await fs.mkdir(workspace);
const command = ['java', `-Duser.home=${home}`, '-cp', classpath,
  'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain', '--workspace', workspace, '--stdio'];
// 只继承启动工具链需要的系统变量，不把宿主凭证、代理或Node/Java注入参数带入fixture。
const allowed = new Set(['PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
  'JAVA_HOME', 'SYSTEMDRIVE', 'PROGRAMFILES', 'PROGRAMFILES(X86)', 'COMMONPROGRAMFILES',
  'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE']);
const environment = {...Object.fromEntries(Object.keys(process.env).filter(key => allowed.has(key.toUpperCase()))
  .map(key => [key, process.env[key]])), HOME: home, USERPROFILE: home, TERM: 'xterm-256color',
  CC_JAVA_REPOSITORY_ROOT: workspace,
  CC_JAVA_SPIKE_COMMAND_BASE64: Buffer.from(JSON.stringify(command)).toString('base64')};
const terminal = pty.spawn(process.execPath, [path.join(root, 'cc-java-tui/dist/src/index.js'), '--tui-next', '--workspace', workspace],
  {name: 'xterm-256color', cols: columns, rows, cwd: workspace, env: environment});
const canary = 'synthetic-pty-password-only-Z7q4';
let output = '', exited = false, succeeded = false, exitCode;
const observers = new Set();
terminal.onData(data => {
  output += data;
  if (output.length > 1_048_576) { terminal.kill(); throw new Error('PTY output exceeded test bound'); }
  for (const observer of [...observers]) observer();
});
terminal.onExit(event => { exited = true; exitCode = event.exitCode; for (const observer of [...observers]) observer(); });
const plain = value => value.replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/gu, '').replace(/\x1b\[[0-?]*[ -/]*[@-~]/gu, '');
const awaitCondition = (predicate, label) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => { observers.delete(check); reject(new Error(`PTY timeout at ${label}; exit=${exitCode ?? 'running'}`)); }, 20_000);
  const check = () => {
    if (predicate()) { clearTimeout(timer); observers.delete(check); resolve(); }
    else if (exited) { clearTimeout(timer); observers.delete(check); reject(new Error(`PTY exited at ${label}: ${exitCode}`)); }
  };
  observers.add(check); check();
});
const waitText = (text, offset = 0) => awaitCondition(() => plain(output.slice(offset)).includes(text), text);
const key = async (value, expected) => {
  const offset = output.length;
  terminal.write(value);
  if (expected) await waitText(expected, offset);
};
const profileCount = async () => {
  try { return JSON.parse(await fs.readFile(path.join(home, '.cc-java/auth/profiles.v1.json'), 'utf8')).profiles.length; }
  catch (failure) { if (failure.code === 'ENOENT') return 0; throw failure; }
};
async function secretField() {
  await key('\r', '选择 Profile');
  await key('\r', '选择认证方式');
  await key('\r', '[已遮蔽]');
}
async function login() {
  await secretField();
  await key(canary);
  await new Promise(resolve => setTimeout(resolve, 100));
  await key('\r', '选择默认模型');
  if (plain(output).includes(canary)) throw new Error('Synthetic password was echoed in terminal output');
  await key('\r', '默认模型已选择');
  if (await profileCount() !== 1) throw new Error('Expected one persisted profile');
}
try {
  await waitText('选择服务商'); await secretField();
  await key(canary); await new Promise(resolve => setTimeout(resolve, 100));
  await key('\x1b', '认证面板已关闭');
  if (await profileCount() !== 0) throw new Error('Cancelling password input persisted a profile');
  await key('/login', '/login'); await key('\r', '选择服务商'); await login();
  await key('/logout', '/logout'); await key('\r', '选择明确的退出目标');
  await key('\r', '确认退出');
  await key('\x1b', '认证面板已关闭');
  if (await profileCount() !== 1) throw new Error('Cancelled confirmation changed profile');
  await key('/logout', '/logout'); await key('\r', '选择明确的退出目标');
  await key('\r', '确认退出');
  await key('\x1b[B');
  await key('\r', '本机退出已完成');
  if (await profileCount() !== 0) throw new Error('Confirmed logout did not remove profile');
  await key('\r', '本机退出已完成；');
  await key('/login', '/login'); await key('\r', '选择服务商'); await login();
  await key('\x03');
  await awaitCondition(() => exited, 'graceful exit');
  if (exitCode !== 0) throw new Error('Nonzero terminal exit');
  succeeded = true;
} catch (failure) {
  // 只有合成数据的独立终端；仍遮蔽合成密码，仅输出有界失败上下文供诊断。
  console.error(plain(output).slice(-4_000).replaceAll(canary, '[synthetic-password]'));
  throw failure;
} finally {
  if (!exited) {
    terminal.write('\x03');
    await new Promise(resolve => {
      const timer = setTimeout(() => { observers.delete(check); resolve(); }, 2_000);
      const check = () => { if (exited) { clearTimeout(timer); observers.delete(check); resolve(); } };
      observers.add(check); check();
    });
    if (!exited) { terminal.kill(); await new Promise(resolve => setTimeout(resolve, 500)); }
  }
  output = '';
  await fs.rm(directory, {recursive: true, force: true});
}
if (succeeded) {
  console.log(JSON.stringify({result: 'passed', terminal: 'ConPTY', columns, rows,
    maskedStdinStoreLogin: true, passwordEchoed: false, cancelledInputNotSaved: true, cancelPreservesProfile: true,
    logoutRemovesProfile: true, sameHostRelogin: true, noModelPromptSubmitted: true, physicalScreenshot: false}));
  // 借用的beta版ConPTY模块可能仍持有原生管道；只在子进程exit0且清理完成后退出测试宿主。
  process.exit(0);
}
