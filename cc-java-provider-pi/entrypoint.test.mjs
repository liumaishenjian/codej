import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 不发 login，不创建真实 Provider 回调或网络请求；覆盖实际入口的 EOF/异常退出。
function helper(input) {
  return new Promise((resolve, reject) => {
    const process = spawn(globalThis.process.execPath, [fileURLToPath(new URL('./login.mjs', import.meta.url))], {
      cwd: fileURLToPath(new URL('.', import.meta.url)), env: {}, stdio: ['pipe', 'pipe', 'pipe']
    });
    let stdout = '', stderr = '';
    const timeout = setTimeout(() => { process.kill(); reject(Error('helper timeout')); }, 5000);
    process.stdout.on('data', chunk => { stdout += chunk; });
    process.stderr.on('data', chunk => { stderr += chunk; });
    process.on('error', reject);
    process.on('close', code => { clearTimeout(timeout); resolve({ code, stdout, stderr }); });
    process.stdin.end(input);
  });
}

test('production entrypoint cancels before login with no diagnostics', async () => {
  const result = await helper('{"type":"start"}\n{"type":"cancel"}\n');
  assert.equal(result.code, 0);
  assert.equal(result.stderr, '');
  assert.deepEqual(result.stdout.trim().split('\n').map(JSON.parse), [{ type: 'ready' }, { type: 'cancelled' }]);
});
test('production entrypoint EOF and invalid input fail without raw echo', async () => {
  for (const input of ['{"type":"start"}\n', 'SECRET_INVALID_JSON\n']) {
    const result = await helper(input);
    assert.equal(result.stderr, '');
    assert.ok(!result.stdout.includes('SECRET_INVALID_JSON'));
    assert.deepEqual(JSON.parse(result.stdout.trim().split('\n').at(-1)), { type: 'error', code: 'login_failed' });
  }
});
