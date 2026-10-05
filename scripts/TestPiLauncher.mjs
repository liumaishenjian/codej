import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';

// 只执行当前生产 launcher；所有进程调用被 Fake 捕获，不接触真实 Java、用户配置或网络。
const source = fs.readFileSync(new URL('./codej-launcher.mjs', import.meta.url), 'utf8')
  .replace(/^#!.*\n/, '').replace(/^import .*;\r?\n/gm, '')
  .replace('import.meta.url', 'launcherUrl');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function tree(directory) {
  const files = fs.readdirSync(directory, {recursive: true, withFileTypes: true})
    .filter(item => item.isFile()).map(item => path.join(item.parentPath, item.name)).sort();
  return digest(files.map(file => `${path.relative(directory, file).split(path.sep).join('/')}:${digest(fs.readFileSync(file))}\n`).join(''));
}
function fixture(body) {
  const root = fs.mkdtempSync(path.join(tmpdir(), "codej Pi ' quoted "));
  const put = (name, text = 'fake') => {
    const file = path.join(root, name); fs.mkdirSync(path.dirname(file), {recursive: true}); fs.writeFileSync(file, text);
  };
  put('app/cc-java-cli.jar'); put('tui/dist/src/index.js');
  for (const name of ['worker.mjs', 'login.mjs', 'package.json', 'package-lock.json',
    'node_modules/@earendil-works/pi-ai/package.json', 'node_modules/transitive/LICENSE',
    'node_modules/.package-lock.json']) put(`pi/${name}`);
  const manifest = {schema: 'cc-java-release-manifest-v1', version: '0.1.1', build: {
    currentCommit: 'a'.repeat(40), sourceDigest: 'b'.repeat(64),
    cliDigest: digest(fs.readFileSync(path.join(root, 'app/cc-java-cli.jar'))),
    tuiDigest: tree(path.join(root, 'tui/dist/src')), piDigest: tree(path.join(root, 'pi'))}};
  put('release-manifest.json', JSON.stringify(manifest));
  const run = (args = ['auth', 'list'], version = '22.19.0') => {
    const calls = [], messages = [];
    const fakeProcess = {argv: ['node', 'launcher', ...args], execPath: path.join(root, 'selected node.exe'),
      platform: 'win32', versions: {node: version}, version: `v${version}`, env: {CODEJ_JAVA: 'fake-java'},
      cwd: () => path.join(root, 'untrusted workspace'), stdout: {write: x => messages.push(x)},
      stderr: {write: x => messages.push(x)}, exit(code) { this.exitCode = code; throw new Error('fake-exit'); }};
    const context = vm.createContext({...fs, ...path, fileURLToPath, createHash, Buffer,
      process: fakeProcess, launcherUrl: new URL(`file:///${root.replaceAll('\\', '/')}/codej-launcher.mjs`).href,
      spawnSync: (...args) => { calls.push(args); return {status: 0}; }});
    try { vm.runInContext(source, context); } catch (error) { if (error.message !== 'fake-exit') throw error; }
    return {calls, messages: messages.join(''), code: fakeProcess.exitCode ?? 0, node: fakeProcess.execPath};
  };
  try { body({root, put, run, manifest}); } finally { fs.rmSync(root, {recursive: true, force: true}); }
}

test('control and headless pass same Node and quoted trusted worker/login as individual arguments', () => fixture(({root, run}) => {
  for (const args of [['auth', 'list'], ['--stdio']]) {
    const result = run(args); assert.equal(result.code, 0); assert.equal(result.calls.length, 1);
    const [executable, argv] = result.calls[0]; assert.equal(executable, 'fake-java');
    for (const value of [`-Dcodej.nodeExecutable=${result.node}`, `-Dcodej.piWorker=${path.join(root, 'pi/worker.mjs')}`,
      `-Dcodej.piBridge=${path.join(root, 'pi/login.mjs')}`]) assert.ok(argv.includes(value));
  }
}));
test('interactive child uses same properties and launches only chosen Node (never npm)', () => fixture(({root, run}) => {
  const result = run([]); assert.equal(result.code, 0); assert.equal(result.calls.length, 1);
  const [executable, argv] = result.calls[0]; assert.equal(executable, result.node);
  const child = JSON.parse(Buffer.from(argv[2], 'base64').toString());
  assert.ok(child.includes(`-Dcodej.piWorker=${path.join(root, 'pi/worker.mjs')}`));
  assert.ok(child.includes(`-Dcodej.nodeExecutable=${result.node}`));
}));
test('Node 22.0/22.18 and 21 rejected before any process; 22.19/23/24 allowed', () => fixture(({run}) => {
  for (const version of ['22.0.0', '22.18.9', '21.99.0']) {
    const result = run(['auth', 'list'], version); assert.notEqual(result.code, 0); assert.equal(result.calls.length, 0);
    assert.match(result.messages, /22\.19\.0/);
  }
  for (const version of ['22.19.0', '23.0.0', '24.14.0']) assert.equal(run(['auth', 'list'], version).code, 0);
  assert.notEqual(run(['doctor'], '22.18.0').code, 0);
}));
for (const name of ['worker.mjs', 'login.mjs', 'node_modules/transitive/LICENSE', 'node_modules/.package-lock.json']) {
  test(`Pi tamper/missing fails closed without Java/npm: ${name}`, () => fixture(({root, put, run}) => {
    put(`pi/${name}`, 'tampered'); let result = run(); assert.notEqual(result.code, 0); assert.equal(result.calls.length, 0);
    fs.unlinkSync(path.join(root, 'pi', name)); result = run(); assert.notEqual(result.code, 0); assert.equal(result.calls.length, 0);
  }));
}
test('missing piDigest rejects older incomplete attestation; version output keeps compatibility', () => fixture(({put, run, manifest}) => {
  assert.match(run(['--version']).messages, /^codej 0\.1\.1 commit=.* source=.* cli=.* tui=[a-f0-9]{64}\n$/);
  delete manifest.build.piDigest; put('release-manifest.json', JSON.stringify(manifest));
  assert.notEqual(run(['--version']).code, 0);
}));
