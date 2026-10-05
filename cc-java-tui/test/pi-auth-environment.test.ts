import {expect, it, vi} from 'vitest';
import {piAuthEnvironment} from '../src/pi-auth-environment.js';

it('仅保持同宿主显式代理与OS白名单，不读取凭据或Node注入选项', () => {
  const secret = vi.fn(() => {throw new Error('MUST_NOT_READ');});
  const source = {SystemRoot: 'C:/Windows', HTTPS_PROXY: 'http://127.0.0.1:32123', NO_PROXY: 'localhost',
    NODE_OPTIONS: '--synthetic-untrusted-option', PATH: 'synthetic', get OPENAI_API_KEY(): string {return secret();}};
  expect(piAuthEnvironment(source)).toEqual({SystemRoot: 'C:/Windows', HTTPS_PROXY: 'http://127.0.0.1:32123', NO_PROXY: 'localhost'});
  expect(secret).not.toHaveBeenCalled();
});
it('显式空环境保持空白，不从process环境补入任何值', () => {
  expect(piAuthEnvironment({})).toEqual({});
  expect(piAuthEnvironment({HTTPS_PROXY: undefined, https_proxy: 'ignored'})).toEqual({});
});
