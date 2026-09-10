import process from 'node:process';
import {resolve} from 'node:path';
import type {ChildProcessSpec} from './stdio-client.js';

/** 启动器专用参数只决定前端与工作目录，不混入 Java 子进程参数。 */
export interface Options {
  readonly child: ChildProcessSpec;
  readonly tuiNext: boolean;
  readonly prompt?: string;
}

export function parseArguments(
  args: readonly string[],
  environment: Readonly<Record<string, string | undefined>> = process.env,
  cwd: string = process.cwd(),
): Options {
  let tuiNext = false;
  let workspace = cwd;
  let childCommandBase64 = environment.CC_JAVA_SPIKE_COMMAND_BASE64;
  let prompt = decodeOptionalBase64(environment.CC_JAVA_SPIKE_PROMPT_BASE64);
  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (argument === '--child-command-base64') {
      childCommandBase64 = args[++index];
    } else if (argument === '--tui-next') {
      tuiNext = true;
    } else if (argument === '--workspace') {
      const value = args[++index];
      if (value === undefined || value.trim().length === 0) {
        throw new Error('--workspace 缺少路径');
      }
      workspace = resolve(cwd, value);
    } else if (argument === '--prompt') {
      prompt = args[++index];
    } else {
      throw new Error(`未知参数：${argument ?? ''}`);
    }
  }
  if (childCommandBase64 === undefined) {
    throw new Error('Spike 必须通过 --child-command-base64 提供结构化 Java 启动参数');
  }
  const childCommandJson = Buffer.from(childCommandBase64, 'base64').toString('utf8');
  const command = JSON.parse(childCommandJson) as unknown;
  if (
    !Array.isArray(command)
    || command.length < 1
    || command.length > 64
    || command.some(value => typeof value !== 'string' || value.length === 0)
  ) {
    throw new Error('--child-command-base64 必须解码为 1-64 个非空字符串组成的 JSON Array');
  }
  const executable = command[0];
  if (executable === undefined) {
    throw new Error('缺少 Java 可执行文件');
  }
  return {
    tuiNext,
    child: {
      executable,
      args: command.slice(1),
      cwd: workspace,
    },
    ...(prompt === undefined ? {} : {prompt}),
  };
}

function decodeOptionalBase64(value: string | undefined): string | undefined {
  return value === undefined ? undefined : Buffer.from(value, 'base64').toString('utf8');
}
