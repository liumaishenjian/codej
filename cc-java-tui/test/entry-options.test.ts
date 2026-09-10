import {describe, expect, it} from 'vitest';
import {resolve} from 'node:path';
import {parseArguments} from '../src/entry-options.js';
const command = ['java', '--workspace', 'existing workspace', '--stdio'];
const environment = {CC_JAVA_SPIKE_COMMAND_BASE64: Buffer.from(JSON.stringify(command)).toString('base64')};
describe('development frontend entry', () => {
  it('keeps the default interface and Java argv unchanged', () => {
    const options = parseArguments([], environment, 'C:/tui');
    expect(options.tuiNext).toBe(false);
    expect(options.child).toEqual({executable: 'java', args: command.slice(1), cwd: 'C:/tui'});
  });
  it('routes the new frontend and Unicode workspace without forwarding frontend flags', () => {
    const options = parseArguments(['--tui-next', '--workspace', '中文 workspace'], environment, 'C:/projects');
    expect(options.tuiNext).toBe(true);
    expect(options.child.cwd).toBe(resolve('C:/projects', '中文 workspace'));
    expect(options.child.args).toEqual(command.slice(1));
  });
  it('preserves noninteractive prompt including with the new selector', () => {
    const options = parseArguments(['--tui-next', '--prompt', 'hello 中文'], environment, 'C:/tui');
    expect(options.prompt).toBe('hello 中文');
    expect(options.child.args).toEqual(command.slice(1));
  });
  it('rejects missing workspace and unknown selectors', () => {
    expect(() => parseArguments(['--workspace'], environment)).toThrow('缺少路径');
    expect(() => parseArguments(['--tui-unknown'], environment)).toThrow('未知参数');
  });
});
