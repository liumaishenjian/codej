import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render} from 'ink-testing-library';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {ExperienceRuntimeApp} from '../src/experience/runtime-app.js';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';
vi.mock('ink', async original => ({...await original<typeof import('ink')>(), useWindowSize: () => ({columns: 100, rows: 35})}));
afterEach(cleanup);
const cp = process.env.CC_JAVA_TEST_CLASSPATH;
const wait = (ms = 50) => new Promise(resolve => setTimeout(resolve, ms));

it.skipIf(!cp)('真实Java宿主与独立ENV登录进程完成新TUI登录→退出→同进程重新登录，不调用模型或网络', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'codej-auth-ui-'));
  const home = path.join(root, 'home'), workspace = path.join(root, 'workspace');
  await fs.mkdir(home); await fs.mkdir(workspace);
  const events: ProtocolEvent[] = [], failures: string[] = [];
  const canary = 'synthetic-auth-canary-not-a-real-key';
  const client = new StdioClient({executable: 'java', args: [`-Duser.home=${home}`, '-cp', cp!,
    'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain', '--workspace', workspace, '--stdio'], cwd: workspace,
    env: {...process.env, CC_JAVA_REPOSITORY_ROOT: workspace, CC_JAVA_OPENAI_API_KEY: '', CC_JAVA_OPENAI_BASE_URL: '', CC_JAVA_OPENAI_MODEL: '', CODEJ_FIXTURE_LOGIN_KEY: canary}},
    {providerLoginTimeoutMs: 15_000});
  client.onEvent(event => events.push(event)); client.onFailure(failure => failures.push(failure));
  const app = render(<ExperienceRuntimeApp client={client} workspace={workspace}/>);
  const until = async (text: string) => {
    const deadline = Date.now() + 20_000;
    while (!app.lastFrame()?.includes(text)) {
      if (Date.now() > deadline) throw new Error(JSON.stringify({expected: text, frame: app.lastFrame(), failures, events: events.slice(-3)}));
      await wait();
    }
    await wait();
  };
  const key = async (text: string) => {app.stdin.write(text); await wait();};
  const login = async () => {
    await until('选择服务商'); await key('\r');
    await until('选择 Profile'); await key('\r');
    await until('选择认证方式');
    expect(app.lastFrame()).toContain('API Key · 遮蔽输入');
    // ENV 保持第二项，真实跨进程场景不使用共享 TTY 密码读取。
    await key('\x1b[B'); await key('\r');
    await until('只填写 ENV_NAME'); await key('CODEJ_FIXTURE_LOGIN_KEY'); await key('\r');
    await until('选择默认模型'); await key('\r');
    await until('默认模型已选择');
  };
  try {
    await login();
    await key('/logout'); await key('\r'); await until('选择明确的退出目标');
    await key('\r'); await until('确认退出 anthropic / default');
    await key('\x1b[B'); await key('\r'); await until('本机退出已完成'); await key('\r');
    await key('/login'); await key('\r'); await login();
    expect(events.filter(event => event.type === 'provider.control.result' && event.payload.intent === 'auth.activate'
      && event.payload.status === 'succeeded')).toHaveLength(2);
    expect(events.filter(event => event.type === 'provider.control.result' && event.payload.intent === 'auth.logout.commit'
      && event.payload.status === 'succeeded')).toHaveLength(1);
    expect(events.some(event => event.type === 'model.turn.started')).toBe(false);
    expect(JSON.stringify(events)).not.toContain(canary);
    expect(app.frames.join('\n')).not.toContain(canary);
    expect(failures).toEqual([]);
  } finally {
    await client.shutdown(); app.unmount(); await fs.rm(root, {recursive: true, force: true});
  }
}, 70_000);
