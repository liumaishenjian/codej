import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createRequire} from 'node:module';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {startPiAuthSse} from '../cc-java-tui/test/fixtures/pi-auth-sse.ts';

// ADR-100：仅在自有ConPTY中使用合成材料，不读真实配置，不安装PTY依赖，不进入发布物。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i], value = process.argv[i + 1];
  if (!['--pty-module', '--size', '--surface', '--provider', '--release'].includes(key) || !value || options.has(key)) throw Error('INVALID_ARGUMENTS');
  options.set(key, value);
}
if (!options.has('--pty-module') || !path.isAbsolute(options.get('--pty-module'))) throw Error('EXPLICIT_PTY_MODULE_REQUIRED');
const size = options.get('--size') ?? '80x24', surface = options.get('--surface') ?? 'all';
if (!/^(80|100|120)x(24|35)$/.test(size) || !['all', 'tui', 'cli'].includes(surface)) throw Error('INVALID_ARGUMENTS');
const providers = options.has('--provider') ? [options.get('--provider')] : ['openai', 'deepseek', 'qwen-token-plan-cn'];
if (providers.some(p => !['openai', 'deepseek', 'qwen-token-plan-cn'].includes(p))) throw Error('API_KEY_ROUTES_ONLY');
const [columns, rows] = size.split('x').map(Number);
const pty = createRequire(import.meta.url)(options.get('--pty-module'));
const release = options.get('--release');
if (release && !path.isAbsolute(release)) throw Error('ABSOLUTE_RELEASE_REQUIRED');
const report = await fs.readFile(path.join(root, 'cc-java-cli/target/surefire-reports/TEST-io.github.liumaishenjian.ccjava.cli.PiPublicCliProcessTest.xml'), 'utf8');
const property = name => {
  const value = report.match(new RegExp(`<property name="${name.replaceAll('.', '\\.')}" value="([^"]+)"`))?.[1];
  if (!value) throw Error('CURRENT_JAVA_REPORT_REQUIRED');
  return value.replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
};
const classpath = property('java.class.path');
if (!classpath.replaceAll('\\', '/').toLowerCase().includes(path.join(root, 'cc-java-cli/target/classes').replaceAll('\\', '/').toLowerCase())) throw Error('WRONG_WORKTREE_CLASSPATH');
const java = path.join(property('java.home'), 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const allowed = new Set(['PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP', 'JAVA_HOME',
  'SYSTEMDRIVE', 'PROGRAMFILES', 'PROGRAMFILES(X86)', 'COMMONPROGRAMFILES', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE']);
const baseEnvironment = Object.fromEntries(Object.keys(process.env).filter(k => allowed.has(k.toUpperCase())).map(k => [k, process.env[k]]));
const plain = value => value.replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/gu, '').replace(/\x1b\[[0-?]*[ -/]*[@-~]/gu, '');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const alive = pid => {try {process.kill(pid, 0); return true;} catch {return false;}};
const ownedDirectories = new Set(), ownedServers = new Set();

async function runCase(provider, mode) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-pi-auth-conpty-'));
  ownedDirectories.add(directory);
  const home = path.join(directory, 'home'), workspace = path.join(directory, 'workspace'), trusted = path.join(directory, 'trusted component');
  for (const folder of [home, workspace, trusted]) await fs.mkdir(folder);
  const marker = 'file-only-' + randomUUID(), canary = 'synthetic-pty-key-' + randomUUID();
  await fs.writeFile(path.join(workspace, 'fixture.txt'), marker + '\n');
  const server = await startPiAuthSse(provider, marker, canary); ownedServers.add(server);
  const audit = path.join(directory, 'worker-audit.jsonl'), worker = path.join(trusted, 'worker.mjs');
  const component = release ? path.join(release, 'pi') : path.join(root, 'cc-java-provider-pi');
  const metadata = path.join(trusted, 'node_modules/@earendil-works/pi-ai/package.json');
  await fs.mkdir(path.dirname(metadata), {recursive: true});
  await fs.copyFile(path.join(component, 'node_modules/@earendil-works/pi-ai/package.json'), metadata);
  await fs.writeFile(worker, `import {runPiAuthFixture} from ${JSON.stringify(pathToFileURL(path.join(root, 'cc-java-tui/test/fixtures/pi-auth-worker.mjs')).href)};\n`
    + `await runPiAuthFixture(${JSON.stringify({component, home, origin: server.origin, audit})});\n`);
  const tuiDirectory = release ? path.join(release, 'tui/dist/src') : path.join(root, 'cc-java-tui/dist/src');
  let javaEntry = java;
  if (release) {
    const version = spawnSync(process.execPath, [path.join(release, 'codej-launcher.mjs'), '--version'], {env: baseEnvironment, encoding: 'utf8', timeout: 15000});
    if (version.status !== 0 || !version.stdout.startsWith('codej ')) throw Error('RELEASE_IDENTITY_REQUIRED');
    javaEntry = path.join(trusted, 'isolated-java.exe');
    const compiler = path.join(process.env.WINDIR, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe');
    const compiled = spawnSync(compiler, ['/nologo', '/target:exe', '/out:' + javaEntry, path.join(root, 'scripts/IsolatedJavaTestLauncher.cs')], {env: baseEnvironment, timeout: 15000});
    if (compiled.status !== 0) throw Error('TEST_ADAPTER_COMPILATION');
    await fs.writeFile(path.join(trusted, 'isolated-java.paths'), [java, home, worker].join('\n') + '\n');
    const homeProbe = spawnSync(javaEntry, ['-XshowSettings:properties', '-version'], {env: baseEnvironment, encoding: 'utf8', timeout: 15000});
    if (homeProbe.status !== 0 || !homeProbe.stderr.split(/\r?\n/u).some(line => line.trim() === 'user.home = ' + home)) {
      console.error(JSON.stringify({stage: 'HOME_PROBE', status: homeProbe.status,
        stdoutBytes: homeProbe.stdout?.length, stderrBytes: homeProbe.stderr?.length,
        hasHomeProperty: homeProbe.stderr?.includes('user.home =')}));
      throw Error('TEMPORARY_JAVA_HOME_REQUIRED');
    }
  }
  const prefix = [`-Dfile.encoding=UTF-8`, `-Duser.home=${home}`, `-Dcodej.nodeExecutable=${process.execPath}`,
    `-Dcodej.piWorker=${worker}`, `-Dcodej.piAuthCli=${path.join(tuiDirectory, 'pi-auth-cli.js')}`,
    '-cp', release ? path.join(release, 'app', '*') : classpath, 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain'];
  const isTui = mode === 'default' || mode === 'next';
  const command = [javaEntry, ...prefix, '--workspace', workspace, '--timeout', '30m',
    '--context-maximum-input-tokens', '256000', '--context-reserved-output-tokens', '8192',
    '--context-safety-margin-tokens', '4096', '--stdio'];
  const env = {...baseEnvironment, HOME: home, USERPROFILE: home, TERM: 'xterm-256color', CC_JAVA_REPOSITORY_ROOT: workspace,
    ...(release ? {CODEJ_JAVA: javaEntry} : {}),
    ...(isTui ? {CC_JAVA_SPIKE_COMMAND_BASE64: Buffer.from(JSON.stringify(command)).toString('base64')} : {})};
  const executable = isTui || release ? process.execPath : java;
  const loginArgs = ['auth', 'login', '--backend', 'pi', '--provider', provider, '--profile', 'default', '--auth-method', 'API_KEY'];
  const argv = release && mode === 'default' ? [path.join(release, 'codej-launcher.mjs'), '--workspace', workspace]
    : isTui ? [path.join(tuiDirectory, 'index.js'), ...(mode === 'next' ? ['--tui-next'] : []),
      ...(release ? ['--child-command-base64', Buffer.from(JSON.stringify(command)).toString('base64')] : []), '--workspace', workspace]
    : release ? [path.join(release, 'codej-launcher.mjs'), ...loginArgs] : [...prefix, ...loginArgs];
  const terminal = pty.spawn(executable, argv, {name: 'xterm-256color', cols: columns, rows, cwd: workspace, env});
  let output = '', exited = false, exitCode, phase = 'startup', overflow = false;
  const observers = new Set();
  terminal.onData(data => {
    output += data;
    if (output.length > 1_048_576) {overflow = true; terminal.kill();}
    for (const observer of [...observers]) observer();
  });
  terminal.onExit(event => {exited = true; exitCode = event.exitCode; for (const observer of [...observers]) observer();});
  const until = (predicate, label) => new Promise((resolve, reject) => {
    phase = label;
    const timer = setTimeout(() => {clearInterval(tick); observers.delete(check); reject(Error('PTY_DEADLINE'));}, 20_000);
    const check = () => {
      if (predicate()) {clearTimeout(timer); clearInterval(tick); observers.delete(check); resolve();}
      else if (exited || overflow) {clearTimeout(timer); clearInterval(tick); observers.delete(check); reject(Error('PTY_EARLY_EXIT'));}
    };
    const tick = setInterval(check, 40);
    observers.add(check); check();
  });
  const text = (value, offset = 0) => until(() => plain(output.slice(offset)).includes(value), value);
  const key = async (value, expected) => {
    const offset = output.length; terminal.write(value);
    if (expected) await text(expected, offset); else await pause(60);
  };
  const profileCount = async () => {
    try {return JSON.parse(await fs.readFile(path.join(home, '.cc-java/auth/pi/index.v1.json'), 'utf8')).profiles.length;}
    catch (error) {if (error.code === 'ENOENT') return 0; throw error;}
  };
  const checkNoEcho = () => {if (plain(output).includes(canary) || plain(output).includes(canary.slice(0, 12))) throw Error('INPUT_ECHO');};
  try {
    if (isTui) {
      await text('选择服务商');
      const route = {openai: 0, deepseek: 2, 'qwen-token-plan-cn': 3}[provider];
      const secretField = async () => {
        for (let i = 0; i < route; ++i) await key('\x1b[B');
        await key('\r', '选择 Profile'); await key('\r', '登录方式'); await key('\r', '输入 API Key');
      };
      await secretField(); await key(canary); checkNoEcho(); await key('\x1b', '认证结果待核对');
      if (await profileCount() !== 0) throw Error('CANCELLED_INPUT_SAVED');
      const cancelledWorkers = (await fs.readFile(audit, 'utf8')).trim().split('\n').map(line => JSON.parse(line)).filter(e => e.kind === 'worker');
      await until(() => cancelledWorkers.every(e => !alive(e.pid) && !alive(e.parent)), 'cancelled helper closed');
      await key('/login'); await key('\r', '选择服务商'); await secretField();
      await key(canary); checkNoEcho(); await key('\r', '尚未启用');
      if (await profileCount() !== 1 || server.requests.length !== 0) throw Error('STORED_OR_AUTO_ACTIVATION');
      await key('\x1b[B'); await key('\r', '设为默认模型'); await key('\r', '本机配置不表示在线验证');
      if (provider === 'openai') {
        // 保留原安装失败：8192窗口的gpt-4不能吞掉12288保留预算。必须先明确失败，再由用户重选。
        await key('Read fixture.txt using read_file and report its contents.'); await key('\r', '模型窗口不足');
        if (server.requests.length !== 0) throw Error('INCOMPATIBLE_CONTEXT_CALLED_MODEL');
        await key('/login'); await key('\r', '选择服务商'); await key('\r', '选择 Profile');
        await key('\r', '登录方式'); await key('\r', '设为默认模型');
        await key('\x1b[B'); await key('\r', '本机配置不表示在线验证');
      }
      await key('Read fixture.txt using read_file and report its contents.'); await key('\r', server.finalText);
      if (server.requests.length !== 2 || !server.requests[1].toolResultObserved) throw Error('REAL_DELIVERY_REQUIRED');
      await key('Confirm this second turn is usable.'); await key('\r', server.continuationText);
      await key('/logout'); await key('\r', '选择明确的退出目标');
      await key('\r', '确认退出本机 Profile'); await key('\x1b', '认证面板已关闭');
      if (await profileCount() !== 1) throw Error('CANCELLED_LOGOUT_DELETED');
      await key('/logout'); await key('\r', '选择明确的退出目标');
      await key('\r', '确认退出本机 Profile'); await key('\x1b[B'); await key('\r', '本机退出已完成');
      if (await profileCount() !== 0 || server.requests.length !== 3 || server.errors.length) throw Error('LOGOUT_OR_MODEL_CHAIN');
      await key('\r'); await key('\x03'); await until(() => exited, 'normal TUI exit');
      if (exitCode !== 0) throw Error('TUI_EXIT_CODE');
    } else {
      await text('API Key'); await key(canary); checkNoEcho();
      await key(mode === 'cli-cancel' ? '\x03' : '\r'); await until(() => exited, 'CLI terminal');
      if (mode === 'cli-cancel' ? (exitCode === 0 || await profileCount() !== 0) : (exitCode !== 0 || await profileCount() !== 1)) throw Error('CLI_STATUS');
      if (server.requests.length !== 0) throw Error('AUTH_NETWORK_REQUEST');
    }
    checkNoEcho();
    const entries = (await fs.readFile(audit, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
    for (const entry of entries) {
      if (entry.kind === 'forbidden') throw Error('EXTERNAL_FETCH');
      if (entry.kind === 'worker' && (alive(entry.pid) || alive(entry.parent))) throw Error('OWNED_PROCESS_REMAINS');
    }
    console.log(JSON.stringify({result: 'passed', terminal: 'ConPTY', mode, provider, columns, rows,
      secretEchoed: false, httpRequests: server.requests.length, actualIndex: isTui, installed: !!release,
      installedLauncher: !!release && mode !== 'next',
      actualPublicCli: !isTui, ...(isTui || mode === 'cli-cancel' ? {cancelledInputNotSaved: true} : {}),
      ...(isTui ? {cancelledLogoutPreserved: true, contextEnabled: true,
        ...(provider === 'openai' ? {originalContextFailureRecoveredExplicitly: true} : {})} : {}),
      ownedParentsAndWorkersGone: true, physicalScreenshot: false}));
  } catch {
    // 仅对已证明隔离的合成fixture提供有界脱敏帧；不记录header或认证材料。
    console.error(JSON.stringify({result: 'failed', phase, mode, provider, exitCode, overflow,
      httpRequests: server.requests.length, httpErrors: server.errors,
      // 安装用例已先证明临时home，仅输出本脚本自有fixture的有界脱敏帧；不是产品遥测。
      ...(release ? {fixtureDiagnostic: plain(output).replaceAll(canary, '[synthetic-input]').replaceAll(canary.slice(0, 12), '[input-prefix]').slice(-3000)} : {})}));
    throw Error('PI_AUTH_PTY_FAILED');
  } finally {
    if (!exited) {
      terminal.write('\x03'); await pause(1500);
      if (!exited) {terminal.kill(); await pause(500);}
    }
    output = '';
    await server.close(); ownedServers.delete(server);
    await fs.rm(directory, {recursive: true, force: true}); ownedDirectories.delete(directory);
  }
}
let result = 0;
try {
  for (const provider of providers) {
    if (surface !== 'cli') for (const mode of ['default', 'next']) await runCase(provider, mode);
    if (surface !== 'tui') for (const mode of ['cli-save', 'cli-cancel']) await runCase(provider, mode);
  }
} catch (error) {
  result = 1; console.error(JSON.stringify({result: 'failed', code: /^[A-Z_]+$/.test(error.message) ? error.message : 'SETUP_FAILED'}));
} finally {
  // setup失败也只清理本次实际创建的server/目录，不扫描或清理用户目录。
  for (const server of ownedServers) await server.close();
  for (const directory of ownedDirectories) await fs.rm(directory, {recursive: true, force: true});
}
// 自有子进程已经实际退出；借用beta ConPTY库的原生管道不作为产品清理证据。
process.exit(result);
