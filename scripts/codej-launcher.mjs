#!/usr/bin/env node
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {existsSync, readFileSync, readdirSync} from 'node:fs';
import {dirname, isAbsolute, join, relative, resolve, sep} from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const installationRoot = dirname(fileURLToPath(import.meta.url));
const mainClass = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const contextDefaults = ['--context-maximum-input-tokens', '256000',
  '--context-reserved-output-tokens', '8192', '--context-safety-margin-tokens', '4096'];

main();

function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === '--help') return printHelp();
  if (args.length === 1 && args[0] === 'update') return manageInstallation(false);
  if (args.length === 1 && args[0] === 'uninstall') return manageInstallation(true);

  const identity = verifyReleaseIdentity();
  if (identity === undefined) return exitWith(1);
  if (args.length === 1 && args[0] === '--version') return printVersion(identity);
  if (args.length === 1 && args[0] === 'doctor') return doctor();

  const java = resolveJava();
  if (isJavaControlCommand(args) || isHeadlessCommand(args)) {
    return exitWith(run(java, javaCommand(args), {...process.env, CC_JAVA_REPOSITORY_ROOT: installationRoot}));
  }

  const parsed = parseAgentArguments(args);
  const childArgs = ['-Dfile.encoding=UTF-8', '-cp', join(installationRoot, 'app', '*'), mainClass,
    '--workspace', parsed.workspace, '--timeout', parsed.timeout, ...contextDefaults, ...parsed.forwarded,
    '--stdio'];
  const childCommand = Buffer.from(JSON.stringify([java, ...childArgs]), 'utf8').toString('base64');
  const tuiArgs = [join(installationRoot, 'tui', 'dist', 'src', 'index.js'),
    '--child-command-base64', childCommand];
  if (parsed.prompt !== undefined) tuiArgs.push('--prompt', parsed.prompt);
  const environment = {...process.env, CC_JAVA_REPOSITORY_ROOT: installationRoot};
  delete environment.CC_JAVA_SPIKE_COMMAND_BASE64;
  delete environment.CC_JAVA_SPIKE_PROMPT_BASE64;
  return exitWith(run(process.execPath, tuiArgs, environment));
}

function parseAgentArguments(args) {
  let workspace = process.cwd();
  let timeout = '30m';
  let prompt;
  const forwarded = [];
  const valueOptions = new Set(['--workspace', '--model', '--timeout', '--resume', '--fork',
    '--permission-mode', '--model-diagnostics', '--model-diagnostics-dir', '--execution-backend',
    '--execution-shell', '--context-maximum-input-tokens', '--context-reserved-output-tokens',
    '--context-safety-margin-tokens']);
  const flags = new Set(['--continue']);
  for (let index = 0; index < args.length; index++) {
    const current = args[index];
    if (current === '--print') {
      prompt = requiredValue(args, ++index, current);
      continue;
    }
    const [name, inline] = splitOption(current);
    if (valueOptions.has(name)) {
      const value = inline ?? requiredValue(args, ++index, name);
      if (name === '--workspace') workspace = isAbsolute(value) ? resolve(value) : resolve(process.cwd(), value);
      else if (name === '--timeout') timeout = value;
      else if (name.startsWith('--context-')) replaceDefaultContext(name, value);
      else forwarded.push(name, value);
      continue;
    }
    if (flags.has(current)) {
      forwarded.push(current);
      continue;
    }
    fail(`未知参数：${current}`);
  }
  return {workspace, timeout, prompt, forwarded};
}

function replaceDefaultContext(name, value) {
  const position = contextDefaults.indexOf(name);
  contextDefaults[position + 1] = value;
}

function requiredValue(args, index, option) {
  const value = args[index];
  if (value === undefined || value.length === 0 || value === '--') fail(`参数 ${option} 缺少值`);
  return value;
}

function splitOption(value) {
  const position = value.indexOf('=');
  return position < 0 ? [value, undefined] : [value.slice(0, position), value.slice(position + 1)];
}

function isJavaControlCommand(args) {
  return args.length > 0 && ['providers', 'auth', 'models'].includes(args[0]);
}

function isHeadlessCommand(args) {
  return args.some(value => ['--stdio', '--stdio-v1', '--daemon', '--extension-status',
    '--trust-project-extensions'].includes(value));
}

function javaCommand(args) {
  return ['-Dfile.encoding=UTF-8', '-cp', join(installationRoot, 'app', '*'), mainClass, ...args];
}

function resolveJava() {
  if (process.env.CODEJ_JAVA) return process.env.CODEJ_JAVA;
  const bundled = process.platform === 'win32'
    ? join(installationRoot, 'runtime', 'java', 'bin', 'java.exe')
    : join(installationRoot, 'runtime', 'java', 'bin', 'java');
  if (existsSync(bundled)) return bundled;
  if (process.env.JAVA_HOME) return join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  return 'java';
}

function run(executable, args, environment = process.env) {
  const result = spawnSync(executable, args, {cwd: process.cwd(), env: environment, stdio: 'inherit', windowsHide: true});
  if (result.error) fail(`无法启动 ${executable}：${result.error.message}`);
  return result.status ?? 1;
}

function doctor() {
  const required = [join(installationRoot, 'app', 'cc-java-cli.jar'),
    join(installationRoot, 'tui', 'dist', 'src', 'index.js')];
  const missing = required.filter(path => !existsSync(path));
  process.stdout.write(`codej installation: ${installationRoot}\n`);
  process.stdout.write(`node: ${process.version} (${Number(process.versions.node.split('.')[0]) >= 22 ? 'ok' : 'requires 22+'})\n`);
  process.stdout.write(`java: ${resolveJava()}\n`);
  process.stdout.write(`files: ${missing.length === 0 ? 'ok' : `missing ${missing.join(', ')}`}\n`);
  return exitWith(missing.length === 0 ? 0 : 1);
}

function manageInstallation(uninstall) {
  if (process.platform === 'win32') {
    const args = ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
      join(installationRoot, 'install.ps1')];
    if (uninstall) args.push('-Uninstall');
    return exitWith(run('powershell.exe', args));
  }
  return exitWith(run('sh', [join(installationRoot, 'install.sh'), ...(uninstall ? ['--uninstall'] : [])]));
}

function verifyReleaseIdentity() {
  try {
    const manifest = JSON.parse(readFileSync(join(installationRoot, 'release-manifest.json'), 'utf8'));
    const build = manifest.build ?? {};
    const cli = fileDigest(join(installationRoot, 'app', 'cc-java-cli.jar'));
    const tui = treeDigest(join(installationRoot, 'tui', 'dist', 'src'));
    if (manifest.schema !== 'cc-java-release-manifest-v1' || build.cliDigest !== cli || build.tuiDigest !== tui
      || !/^[0-9a-f]{40}$/.test(build.currentCommit ?? '')
      || !/^[0-9a-f]{64}$/.test(build.sourceDigest ?? '')) {
      throw new Error('identity drift');
    }
    return {manifest, build, cli, tui};
  } catch {
    process.stderr.write('codej: packaged build identity drift detected\n');
    return undefined;
  }
}

function printVersion(identity) {
  const {manifest, build, cli, tui} = identity;
  process.stdout.write(`codej ${manifest.version} commit=${build.currentCommit} source=${build.sourceDigest} cli=${cli} tui=${tui}\n`);
}

function fileDigest(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function treeDigest(directory) {
  const files = [];
  const visit = current => {
    for (const entry of readdirSync(current, {withFileTypes: true})) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile()) files.push(path);
      else throw new Error('unsupported TUI entry');
    }
  };
  visit(directory);
  const accumulator = files.sort().map(path =>
    `${relative(directory, path).split(sep).join('/')}:${fileDigest(path)}\n`).join('');
  return createHash('sha256').update(accumulator, 'utf8').digest('hex');
}

function printHelp() {
  process.stdout.write(`codej - Java-powered coding agent CLI

用法：
  codej [--workspace <path>] [--model <name>]
  codej --print <prompt> [--workspace <path>] [--model <name>]
  codej auth|providers|models <command>
  codej doctor | update | uninstall | --version | --help

默认进入交互式终端；--print 用于脚本和 CI。
`);
}

function exitWith(code) {
  process.exitCode = code;
}

function fail(message) {
  process.stderr.write(`codej: ${message}\n`);
  process.exit(2);
}
