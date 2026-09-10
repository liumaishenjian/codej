#!/usr/bin/env node
import process from 'node:process';
import {render} from 'ink';
import {AgentTui} from './app.js';
import {ExperienceRuntimeApp} from './experience/runtime-app.js';
import {parseArguments} from './entry-options.js';
import {parseJavaRunTimeoutMillis, runNonInteractive} from './print-session.js';
import {
  installProcessExitGuard,
  StdioClient,
} from './stdio-client.js';

const options = parseArguments(process.argv.slice(2));
delete process.env.CC_JAVA_SPIKE_COMMAND_BASE64;
delete process.env.CC_JAVA_SPIKE_PROMPT_BASE64;
const nonInteractive = options.prompt !== undefined || !process.stdin.isTTY || !process.stdout.isTTY;
const runTimeoutMs = nonInteractive ? parseJavaRunTimeoutMillis(options.child.args) : undefined;
const child = new StdioClient(options.child);
const removeExitGuard = installProcessExitGuard(child);

try {
  if (nonInteractive) {
    const prompt = options.prompt;
    if (prompt === undefined || prompt.trim().length === 0) {
      process.stderr.write('非交互模式必须提供 --prompt\n');
      process.exitCode = 2;
    } else {
      process.exitCode = await runNonInteractive(
        child,
        prompt,
        process.stdout,
        process.stderr,
        {runTimeoutMs: runTimeoutMs!},
      );
    }
  } else {
    const instance = render(options.tuiNext
      ? <ExperienceRuntimeApp client={child} workspace={options.child.cwd!} />
      : <AgentTui client={child} />, {
      exitOnCtrlC: false,
      interactive: true,
    });
    await instance.waitUntilExit();
  }
} finally {
  removeExitGuard();
  child.terminate();
}
