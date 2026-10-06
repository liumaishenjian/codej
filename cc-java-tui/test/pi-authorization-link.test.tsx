import {expect, it} from 'vitest';
import {render} from 'ink-testing-library';
import stringWidth from 'string-width';
import {PI_AUTHORIZATION_LABEL, piAuthorizationText, validPiAuthorizationUrl} from '../src/pi-authorization-link.js';
import {authRows} from '../src/experience/auth-screen.js';
import type {AuthPanel} from '../src/experience/auth.js';
import {ExperienceRuntime, type RuntimeClient} from '../src/experience/runtime.js';
import {RuntimeScreen, newRuntimeUi, runtimeFrame} from '../src/experience/runtime-screen.js';
import {rowText} from '../src/experience/screen.js';

const panel: AuthPanel = {operation: 1, phase: 'secret', title: '本次网页授权', message: '输入本次回调内容；原文不显示。',
  choices: [], focus: 0, providerId: 'openai-codex', profileId: 'default', environmentName: '', backend: 'pi',
  authMethod: 'OAUTH', promptId: 1, promptKind: 'manual_code', secretByteCount: 0};
const url = 'https://auth.openai.com/oauth/authorize?state=' + 's'.repeat(10_000);
it('完整长目标仅编码为受控OSC8，显示宽度保持短标签', () => {
  const text = piAuthorizationText(url);
  expect(text).toContain('\u001b]8;;' + url + '\u001b\\');
  expect(stringWidth(text)).toBe(stringWidth(PI_AUTHORIZATION_LABEL));
});
it('系统浏览器未打开时保留可选中的授权地址回退', () => {
  const rows = authRows({...panel, authorizationOpened: false}, 80, 24, url);
  expect(rows.map(rowText).join('\n')).toContain('授权地址：https://auth.openai.com');
  expect(rows.flatMap(r => r.spans).some(s => s.authorizationUrl)).toBe(false);
});
it.each([
  'https://evil.invalid/oauth/authorize?state=x', 'https://auth.openai.com@evil.invalid/oauth/authorize?x',
  'http://auth.openai.com/oauth/authorize?x', 'https://auth.openai.com/other?x',
  'https://auth.openai.com/oauth/authorize?x#fragment', 'https://auth.openai.com/oauth/authorize?x\u001b]52;bad',
  'https://auth.openai.com/oauth/authorize?x\n', 'https://auth.openai.com/oauth/authorize?x\\y',
  'https://auth.openai.com/oauth/authorize?x=' + 'x'.repeat(16_384),
])('非法目标不能成为终端指令 %s', value => {
  expect(validPiAuthorizationUrl(value)).toBe(false);
  expect(piAuthorizationText(value)).toBe(PI_AUTHORIZATION_LABEL);
});
it.each([[80, 24], [100, 30], [120, 40], [160, 50], [60, 24], [40, 24]])('认证长链接位于%d×%d高度预算内且不挤掉输入与取消', (width, height) => {
  const rows = authRows(panel, width, height, url);
  expect(rows.length).toBeLessThanOrEqual(height);
  expect(rows.map(rowText).join('\n')).toContain('粘贴授权码或回调 URL');
  expect(rows.map(rowText).join('\n')).not.toContain('已遮蔽');
  expect(rows.map(rowText).join('\n')).toContain('OpenAI Codex');
  expect(rows.map(rowText).join('\n')).not.toContain('pi / OAUTH / openai-codex');
  expect(rows.map(rowText).join('\n')).toContain('Esc');
  expect(rows.flatMap(r => r.spans).filter(s => s.authorizationUrl)).toHaveLength(1);
  expect(rows.every(row => stringWidth(rowText(row)) <= width)).toBe(true);
  const runtime = new ExperienceRuntime({} as RuntimeClient, '/fixture');
  const state = {...runtime.snapshot(), auth: panel};
   const frame = runtimeFrame(state, newRuntimeUi(), width, height, 0, false, url);
  expect(frame.rows.length).toBeLessThanOrEqual(height);
  expect(frame.rows.map(rowText).join('\n')).toContain(PI_AUTHORIZATION_LABEL);
  expect(JSON.stringify(state)).not.toContain(url);
  const view = render(<RuntimeScreen state={state} ui={newRuntimeUi()} columns={width} rows={height} now={0} authorizationUrl={url}/>);
  try {
    expect(view.lastFrame()).toContain('\u001b]8;;' + url + '\u001b\\');
    expect(view.lastFrame()).toContain('粘贴授权码或回调 URL');
  } finally {view.unmount();}
});
