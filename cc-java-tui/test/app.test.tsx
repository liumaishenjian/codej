import {readFile, readdir} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {
  AgentTui,
  AgentView,
  approvalDecision,
  adjacentCheckpointId,
  appendInput,
  canEditInput,
  checkpointAction,
  decideInterrupt,
  editInput,
  MAX_INPUT_CHARS,
  renderProviderControlResult,
  undoConfirmation,
} from '../src/app.js';
import type {AgentClient} from '../src/app.js';
import type {ProtocolEvent} from '../src/protocol.js';
import type {ProviderLoginRequest, ProviderLoginResult} from '../src/stdio-client.js';
import type {TuiState} from '../src/state.js';
import {createComposerState, reduceComposer} from '../src/input-editor.js';
import {initialPermissionPickerState} from '../src/permission-picker.js';
import {
  independentProviderControlId,
  isIndependentProviderControlResult,
} from '../src/provider-control-id.js';

const SHIFT_ENTER = String.fromCharCode(27) + '[13;2u';

describe('AgentView', () => {
  it('窄窗口仍能渲染中文、输入和完成状态', () => {
    const state: TuiState = {
      phase: 'ready',
      sessionId: 'session-1',
      activeRunId: undefined,
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-1',
        prompt: '解释中文宽字符',
        runId: 'run-1',
        text: '你好，coding agent。',
        tools: [],
        status: 'completed',
        stopReason: 'completed',
        modelTurns: 1,
        toolCalls: 0,
      }],
    };
    const view = render(<AgentView state={state} input="下一步" columns={20} />);

    expect(view.lastFrame()).toContain('codej');
    expect(view.lastFrame()).not.toContain('S06');
    expect(view.lastFrame()).not.toContain('S15');
    expect(view.lastFrame()).toContain('解释中文宽字符');
    expect(view.lastFrame()).toContain('coding');
    expect(view.lastFrame()).toContain('已完成');
    expect(view.lastFrame()).toContain('1 回合');
    expect(view.lastFrame()).toContain('下一步');
  });

  it('ready 引导明确且极短窗口中 Credential notice 不能挤掉 Composer', () => {
    const ready: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
    };
    const readyView = render(<AgentView state={ready} input="" columns={100} />);
    expect(readyView.lastFrame()).toContain('██████  ██████  ██████');
    expect(readyView.lastFrame()).toContain('v0.1.0 · Java-powered coding agent');
    expect(readyView.lastFrame()).not.toContain('输入任务开始，或使用 /connect 配置模型');
    expect(readyView.lastFrame()).not.toContain('/help 查看命令 · @file 引用工作区文件');
    expect(readyView.lastFrame()).not.toContain('S15');
    expect(readyView.lastFrame()).not.toContain('快速安全失败');
    expect(readyView.lastFrame()).not.toContain('Undo 必须');
    readyView.unmount();

    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      notice: 'Credential profiles\n（无）\nModels\nanthropic/claude-sonnet-4-6',
    };
    const view = render(<AgentView state={state} input="" columns={80} rows={8} />);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('Credential profiles');
    expect(frame).not.toContain('██████  ██████  ██████');
    expect(frame).toContain('╭');
    expect(frame).toContain('❯');
    expect(frame).toContain('Enter 发送，Shift+Enter / Ctrl+J 换行');
  });

  it('极短窗口中大量 Slash 候选窗口化且不能挤掉已输入 Composer', () => {
    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: 'Credential profiles\n（无）\nModels\n（无）',
    };
    const layout = {width: 74, height: 1};
    const inserted = reduceComposer(createComposerState(1), {
      type: 'InsertText', text: '/connect',
    }, layout).state;
    const composer = reduceComposer(inserted, {
      type: 'SetCompletions',
      candidates: Array.from({length: 24}, (_, index) => `/candidate-${index}`),
    }, layout).state;
    let selected = composer;
    for (let index = 0; index < 19; index++) {
      selected = reduceComposer(selected, {type: 'CompletionNext'}, layout).state;
    }
    const view = render(<AgentView
      state={state} composer={selected} columns={80} rows={8} composerLayout={layout}
    />);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('╭');
    expect(frame).toContain('❯ /connect');
    expect(frame).not.toContain('光标 1:9');
    expect(frame).toContain('❯ /candidate-19');
    expect(frame).not.toContain('/candidate-0');
  });

  it('极短 running 窗口不裁剪长历史并保留最新状态与 Composer', () => {
    const completedRuns = Array.from({length: 12}, (_, index) => ({
      requestId: `req-old-${index}`, prompt: `历史任务 ${index}`, runId: `run-old-${index}`,
      text: `历史回答 ${index}\n`.repeat(4), tools: [], status: 'completed' as const,
      stopReason: 'completed', modelTurns: 1, toolCalls: 0,
    }));
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-current',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: '次要 notice\n'.repeat(8),
      runs: [...completedRuns, {
        requestId: 'req-current', prompt: '当前任务', runId: 'run-current', text: '', tools: [],
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined,
      }],
    };
    const view = render(<AgentView state={state} input="可排队补充" columns={80} rows={9} />);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('历史任务 0');
    expect(frame).toContain('历史回答 0');
    expect(frame).toContain('历史任务 11');
    expect(frame).toContain('等待模型响应');
    expect(frame).toContain('╭');
    expect(frame).toContain('❯');
    expect(frame).toContain('可排队补充');
    expect(frame).toContain('Enter 排队补充');
  });

  it('短窗口审批状态、旧历史和 Composer 都保持可渲染', () => {
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-approval',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: '旧 notice\n'.repeat(12),
      runs: [{
        requestId: 'req-old', prompt: '旧任务', runId: 'run-old', text: '旧回答\n'.repeat(20),
        tools: [], status: 'completed', stopReason: 'completed', modelTurns: 1, toolCalls: 0,
      }, {
        requestId: 'req-approval', prompt: '当前修改', runId: 'run-approval', text: '', tools: [],
        pendingApproval: {approvalId: 'approval-short', ordinal: 1, toolName: 'apply_patch',
          effect: 'write_workspace', target: 'src/App.java', operation: 'modify',
          removedLines: 1, addedLines: 2, command: undefined, shell: undefined,
          workingDirectory: undefined, destination: undefined, query: undefined, submitted: false},
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined,
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} rows={12} />);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('旧任务');
    expect(frame).toContain('旧回答');
    expect(frame).toContain('Allow once');
    expect(frame).toContain('╭');
    expect(frame).toContain('❯');
  });

  it('模型首个输出前显示等待阶段', () => {
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-wait',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
      runs: [{requestId: 'req-wait', prompt: '等待', runId: 'run-wait', text: '', tools: [],
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined}],
    };
    const view = render(<AgentView state={state} input="下一条" columns={80} />);
    expect(view.lastFrame()).toContain('等待模型响应');
    expect(view.lastFrame()).toContain('下一条');
  });

  it('已完成 Run 进入 Static transcript 后在后续动态重绘中仍保留', () => {
    const completed = (index: number) => ({
      requestId: `req-${index}`, prompt: `永久历史 ${index}`, runId: `run-${index}`,
      text: `永久回答 ${index}`, tools: [], status: 'completed' as const,
      stopReason: 'completed', modelTurns: 1, toolCalls: 0,
    });
    const base: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [completed(1)],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
    };
    const view = render(<AgentView state={base} input="" columns={80} rows={8} />);
    expect(view.lastFrame()).toContain('永久历史 1');
    view.rerender(<AgentView state={{...base, phase: 'running', activeRunId: 'run-live',
      runs: [completed(1), completed(2), {requestId: 'req-live', prompt: '当前动态',
        runId: 'run-live', text: '流式片段', tools: [], status: 'running',
        stopReason: undefined, modelTurns: undefined, toolCalls: undefined}]}}
      input="补充" columns={80} rows={8} />);
    const frame = view.lastFrame() ?? '';
    expect(frame).toContain('永久历史 1');
    expect(frame).toContain('永久历史 2');
    expect(frame).toContain('当前动态');
    expect(frame).toContain('流式片段');
  });

  it('展示确定性分析阶段与区分来源的 Token，不展示思维链标签', () => {
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-progress',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
      runs: [{requestId: 'req-progress', prompt: '分析', runId: 'run-progress', text: '', tools: [],
        modelProgress: {turn: 3, phase: 'thinking', providerInputTokens: 12_400,
          providerOutputTokens: 620, providerTotalTokens: 13_020, usageReportedTurns: 2,
          usageMissingTurns: 1, contextUsedTokens: 18_500, contextMaximumInputTokens: 128_000,
          contextEstimateKind: 'estimated'},
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined}],
    };
    const frame = render(<AgentView state={state} input="" columns={100} />).lastFrame() ?? '';
    expect(frame).toContain('正在分析 · 第 3 回合');
    expect(frame).toContain('上下文估算 19k/128k');
    expect(frame).toContain('Provider 部分实测 累计 13k（↑ 12k · ↓ 620）');
    expect(frame).not.toContain('思维链');
    expect(frame).not.toContain('reasoning');
  });

  it('显示有界模型重试 attempt 与等待进度', () => {
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-retry',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
      runs: [{requestId: 'req-retry', prompt: '分析', runId: 'run-retry', text: '', tools: [],
        modelProgress: {turn: 1, phase: 'thinking', providerInputTokens: 0,
          providerOutputTokens: 0, providerTotalTokens: 0, usageReportedTurns: 0,
          usageMissingTurns: 0, contextUsedTokens: undefined,
          contextMaximumInputTokens: undefined, contextEstimateKind: undefined,
          retryAttempt: 2, retryMaxAttempts: 11, retryWaitMillis: 2_000,
          retryCategory: 'rate_limited'},
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined}],
    };
    const frame = render(<AgentView state={state} input="" columns={100} />).lastFrame() ?? '';
    expect(frame).toContain('模型请求暂时失败，2 秒后进行第 2/11 次尝试');
    expect(frame).not.toContain('rate_limited');
  });

  it('运行中只显示 Java 投影出的状态', () => {
    const state: TuiState = {
      phase: 'running',
      sessionId: 'session-1',
      activeRunId: 'run-2',
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-2',
        prompt: '继续',
        runId: 'run-2',
        text: '流式',
        tools: [{
          ordinal: 1,
          name: 'read_file',
          mode: undefined,
          status: 'started',
          returnedCharacters: undefined,
          returnedItems: undefined,
          filteredItems: undefined,
          truncated: false,
          truncationReason: undefined,
          errorCode: undefined,
          failureCategory: undefined,
          retryable: undefined,
          argumentChangeRequired: false,
          strategyChangeRequired: false,
          exitCode: undefined,
          output: {lines: [], characters: 0, truncated: false},
        }],
        status: 'running',
        stopReason: undefined,
        modelTurns: undefined,
        toolCalls: undefined,
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain('正在处理');
    expect(view.lastFrame()).toContain('阅读文件（进行中）');
    expect(view.lastFrame()).toContain('运行中');
  });

  it('Tool 输出默认折叠并在展开时保留 stderr、重复数与 exit', () => {
    const tool = {
      ordinal: 1, name: 'run_command', mode: undefined, activity: '运行测试', status: 'failed' as const,
      returnedCharacters: 20, returnedItems: 0, filteredItems: 0, truncated: false,
      truncationReason: undefined, errorCode: 'process_exit', failureCategory: 'process_exit',
      retryable: false, argumentChangeRequired: false, strategyChangeRequired: false, exitCode: 9,
      output: {characters: 30, truncated: false, lines: [
        {stream: 'stderr' as const, text: 'test failed', complete: true, repetitions: 12},
        {stream: 'stderr' as const, text: 'different error', complete: true, repetitions: 1},
      ]},
    };
    const base: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-tool', notice: undefined,
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined, checkpointUndo: undefined,
      runs: [{requestId: 'req-tool', prompt: 'test', runId: 'run-tool', text: '', tools: [tool],
        toolDetailOrdinal: 1, toolDetailExpanded: false, status: 'running', stopReason: undefined,
        modelTurns: undefined, toolCalls: undefined}],
    };
    const collapsed = render(<AgentView state={base} input="" columns={100} />).lastFrame() ?? '';
    expect(collapsed).toContain('详情 1/1 · run_command');
    expect(collapsed).toContain('压缩重复 11');
    expect(collapsed).toContain('Ctrl+O 展开');
    expect(collapsed).not.toContain('test failed');

    const expanded = render(<AgentView state={{...base, runs: [{...base.runs[0]!, toolDetailExpanded: true}]}}
      input="" columns={100} />).lastFrame() ?? '';
    expect(expanded).toContain('stderr │ test failed');
    expect(expanded).toContain('×12');
    expect(expanded).toContain('different error');
    expect(expanded).toContain('failed · process_exit · process_exit · exit 9');

    const longLines = Array.from({length: 16}, (_, index) => ({
      stream: 'stdout' as const, text: `log-line-${index}`, complete: true, repetitions: 1,
    }));
    const longRun = {
      ...base.runs[0]!,
      toolDetailExpanded: true,
      tools: [{...tool, output: {...tool.output, lines: longLines}}],
    };
    const longExpanded = render(<AgentView state={{...base, runs: [longRun]}}
      input="" columns={100} />).lastFrame() ?? '';
    expect(longExpanded).toContain('显示末尾 12/16 行');
    expect(longExpanded).toContain('log-line-15');
    expect(longExpanded).not.toContain('log-line-0');

    const archivedRun = {...base.runs[0]!, status: 'failed' as const,
      toolDetailExpanded: false, stopReason: 'tool_failure'};
    const archivedState = {...base, phase: 'ready' as const, activeRunId: undefined,
      runs: [archivedRun]};
    const archived = render(<AgentView state={archivedState} input="" columns={100} />)
      .lastFrame() ?? '';
    expect(archived).toContain('Ctrl+O 查看最近历史 Tool 详情');
    expect(archived).not.toContain('Ctrl+O 展开');
    expect(archived).not.toContain('test failed');

    const historical = render(<AgentView state={{...archivedState,
      historicalToolDetailRunId: 'run-tool', historicalToolDetailOrdinal: 1,
      historicalToolDetailOpen: true}} input="" columns={100} />).lastFrame() ?? '';
    expect(historical).toContain('最近历史 Tool 详情');
    expect(historical).toContain('stderr │ test failed');
    expect(historical).toContain('Ctrl+O 关闭详情');
  });

  it('审批面板展示受控相对路径和变更行数', () => {
    const state: TuiState = {
      phase: 'running',
      sessionId: 'session-1',
      activeRunId: 'run-write',
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-write',
        prompt: '修改文件',
        runId: 'run-write',
        text: '',
        tools: [],
        pendingApproval: {
          approvalId: 'approval-1',
          ordinal: 1,
          toolName: 'apply_patch',
          effect: 'write_workspace',
          target: 'src/main/App.java',
          operation: 'modify',
          removedLines: 2,
          addedLines: 3,
          command: undefined,
          shell: undefined,
          workingDirectory: undefined,
          destination: undefined,
          query: undefined,
          submitted: false,
        },
        status: 'running',
        stopReason: undefined,
        modelTurns: undefined,
        toolCalls: undefined,
      }],
    };

    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain('修改：src/main/App.java');
    expect(view.lastFrame()).toContain('+3 行');
    expect(view.lastFrame()).toContain('-2 行');
  });

  it('网络审批面板明确展示出站行为和有界搜索词', () => {
    const state: TuiState = {
      phase: 'running',
      sessionId: 'session-1',
      activeRunId: 'run-web',
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-web',
        prompt: '查询天气',
        runId: 'run-web',
        text: '',
        tools: [],
        pendingApproval: {
          approvalId: 'approval-web',
          ordinal: 1,
          toolName: 'web_search',
          effect: 'network_or_remote',
          target: undefined,
          operation: undefined,
          removedLines: undefined,
          addedLines: undefined,
          command: undefined,
          shell: undefined,
          workingDirectory: undefined,
          destination: 'configured_web_search_provider',
          query: '明天杭州天气',
          submitted: false,
        },
        status: 'running',
        stopReason: undefined,
        modelTurns: undefined,
        toolCalls: undefined,
      }],
    };

    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain('需要批准：访问网络');
    expect(view.lastFrame()).toContain('已配置的 Web Search Provider');
    expect(view.lastFrame()).toContain('明天杭州天气');
    expect(view.lastFrame()).toContain('Allow once');
  });

  it('Backspace 按 Unicode Code Point 删除且中断动作取决于 Java Run 投影', () => {
    const afterText = editInput('', '你好', {
      backspace: false,
      ctrl: false,
      meta: false,
    });
    const afterBackspace = editInput(afterText, '', {
      backspace: true,
      ctrl: false,
      meta: false,
    });

    expect(afterBackspace).toBe('你');
    expect(decideInterrupt('running', 'run-1')).toBe('cancel');
    expect(decideInterrupt('running', 'run-1', true)).toBe('terminate');
    expect(decideInterrupt('failed', undefined)).toBe('terminate');
    expect(decideInterrupt('closed', undefined)).toBe('terminate');
    expect(decideInterrupt('ready', undefined)).toBe('shutdown');
  });

  it('Resize 只改变布局宽度，不丢失已有 Run 和输入状态', () => {
    const state: TuiState = {
      phase: 'ready',
      sessionId: 'session-1',
      activeRunId: undefined,
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-resize',
        prompt: '保留上下文',
        runId: 'run-resize',
        text: '已有回答',
        tools: [],
        status: 'completed',
        stopReason: 'completed',
        modelTurns: 1,
        toolCalls: 0,
      }],
    };
    const view = render(<AgentView state={state} input="未提交输入" columns={100} />);

    view.rerender(<AgentView state={state} input="未提交输入" columns={20} />);

    expect(view.lastFrame()).toContain('保留上下文');
    expect(view.lastFrame()).toContain('已有回答');
    expect(view.lastFrame()).toContain('未提交输入');
  });

  it('失败终态展示 Java 权威原因和消耗计数', () => {
    const state: TuiState = {
      phase: 'ready',
      sessionId: 'session-1',
      activeRunId: undefined,
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-failed',
        prompt: '分析 Agent Loop',
        runId: 'run-failed',
        text: '',
        tools: [],
        status: 'failed',
        stopReason: 'model_retry_exhausted',
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
        },
        modelTurns: 1,
        toolCalls: 0,
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain(
      '运行失败 · model_retry_exhausted · 1 回合 · 0 次工具',
    );
    expect(view.lastFrame()).toContain(
      '模型服务暂时不可用（5xx），已尝试 3 次；请稍后重试',
    );
  });

  it('timeout 失败摘要后恢复 ready 输入', () => {
    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined,
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
      runs: [{requestId: 'req-timeout', prompt: '慢任务', runId: 'run-timeout', text: '', tools: [],
        status: 'failed', stopReason: 'time_limit_reached', modelTurns: 1, toolCalls: 0}],
    };
    const view = render(<AgentView state={state} input="可以继续输入" columns={80} />);
    expect(view.lastFrame()).toContain('运行失败 · time_limit_reached');
    expect(view.lastFrame()).toContain('可以继续输入');
    expect(view.lastFrame()).toContain('就绪');
  });

  it('Checkpoint 面板展示具体 phase、Diff 和二次确认目标', () => {
    const state: TuiState = {
      ...initialCheckpointState(),
      checkpoints: [{
        checkpointId: 'checkpoint-run-1-1',
        callId: 'call-1',
        toolName: 'apply_patch',
        target: 'src/App.java',
        existedBefore: true,
        phase: 'post_journal_uncertain',
        undoable: false,
      }, {
        checkpointId: 'checkpoint-run-1-2',
        callId: 'call-2',
        toolName: 'apply_patch',
        target: 'src/Ready.java',
        existedBefore: true,
        phase: 'completed_present',
        undoable: true,
      }],
      selectedCheckpointId: 'checkpoint-run-1-2',
      checkpointDiff: {
        checkpointId: 'checkpoint-run-1-2',
        target: 'src/Ready.java',
        status: 'changed',
        text: '-old\n+new\n',
        truncated: false,
      },
      pendingUndoCheckpointId: 'checkpoint-run-1-2',
    };
    const view = render(<AgentView state={state} input="" columns={100} />);

    expect(view.lastFrame()).toContain('结果记录不确定');
    expect(view.lastFrame()).toContain('Diff · src/Ready.java · changed');
    expect(view.lastFrame()).toContain('确认 Undo 当前 Checkpoint');
    expect(view.lastFrame()).toContain('checkpoint-run-1-2');
    expect(view.lastFrame()).toContain('仅按 Shift+Y 执行');
  });

  it('Checkpoint 键位只把大写 Y 视作针对当前项的二次确认', () => {
    const checkpoints = [{
      checkpointId: 'checkpoint-run-1-1',
      callId: 'call-1',
      toolName: 'apply_patch',
      target: 'src/App.java',
      existedBefore: true,
      phase: 'completed_present' as const,
      undoable: true,
    }, {
      checkpointId: 'checkpoint-run-1-2',
      callId: 'call-2',
      toolName: 'write_file',
      target: 'src/New.java',
      existedBefore: false,
      phase: 'completed_absent' as const,
      undoable: true,
    }];

    expect(checkpointAction('c', {}, false)).toBeUndefined();
    expect(checkpointAction('C', {}, false)).toBe('list');
    expect(checkpointAction('c', {}, true)).toBe('list');
    expect(checkpointAction('D', {}, false)).toBeUndefined();
    expect(checkpointAction('U', {}, false)).toBeUndefined();
    expect(checkpointAction('', {downArrow: true}, false)).toBeUndefined();
    expect(checkpointAction('D', {}, true)).toBe('diff');
    expect(checkpointAction('d', {}, true)).toBe('diff');
    expect(checkpointAction('U', {}, true)).toBe('undo');
    expect(checkpointAction('u', {}, true)).toBe('undo');
    expect(checkpointAction('', {downArrow: true}, true)).toBe('next');
    expect(adjacentCheckpointId(checkpoints, 'checkpoint-run-1-1', 1))
      .toBe('checkpoint-run-1-2');
    expect(undoConfirmation('y')).toBeUndefined();
    expect(undoConfirmation('Y')).toBe('confirm');
    expect(undoConfirmation('N')).toBe('cancel');
  });

  it('可见结构超过上限时显式拒绝，绝不静默截断', () => {
    expect(() => appendInput('前缀', '你'.repeat(MAX_INPUT_CHARS)))
      .toThrowError(new RangeError('VISIBLE_STRUCTURE_LIMIT'));
  });

  it('连接期间真实 useInput 链路立即回显，ready 后可以提交', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);

    await waitForFrame(() => client.initializeCalls === 1);
    view.stdin.write('预输入');
    await waitForFrame(() => view.lastFrame()?.includes('预输入') === true);
    expect(client.prompts).toEqual([]);
    expect(canEditInput('connecting')).toBe(true);

    client.emit({
      version: 0,
      type: 'initialized',
      requestId: 'tui-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {protocolVersion: 0},
    });
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('任务');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['预输入任务']);
    view.unmount();
  });

  it('transport failure 后保留安全摘要并等待 Ctrl+C 显式退出', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);

    const safeSummary = '传输通道异常，诊断详情已隐藏';
    client.emitFailure(safeSummary);
    client.emitExit();
    await waitForFrame(() => {
      const frame = view.lastFrame();
      return frame?.includes('连接失败') === true
        && frame.includes(safeSummary)
        && frame.includes('连接已关闭，Ctrl+C退出');
    });

    const failedFrame = view.lastFrame();
    expect(failedFrame).toContain('连接失败');
    expect(failedFrame).toContain(safeSummary);
    expect(failedFrame).toContain('连接已关闭，Ctrl+C退出');
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).toContain('连接已关闭，Ctrl+C退出');
    expect(client.terminateCalls).toBe(1);
    expect(client.shutdownCalls).toBe(0);

    view.stdin.write('');
    view.unmount();
    expect(client.terminateCalls).toBeGreaterThanOrEqual(1);
    expect(client.shutdownCalls).toBe(0);
  });

  it('通过真实输入链路发送 wait/cancel/keep/remove 子任务动作', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    for (const command of [
      '/task wait task-a 1500', '/task cancel task-a',
      '/task keep task-a', '/task remove task-a',
    ]) {
      view.stdin.write(command); view.stdin.write('\r');
      await waitForFrame(() => client.taskCommands.length === ['/task wait task-a 1500', '/task cancel task-a', '/task keep task-a', '/task remove task-a'].indexOf(command) + 1);
    }
    expect(client.taskCommands).toEqual([
      'wait:task-a:1500', 'cancel:task-a', 'keep:task-a', 'remove:task-a',
    ]);
    view.unmount();
  });

  it('延迟 run.started 期间的后续编辑不会被确认快照覆盖', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('first');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    view.stdin.write('after');
    await waitForFrame(() => view.lastFrame()?.includes('after') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(view.lastFrame()).toContain('after');
    expect(view.lastFrame()).not.toContain('firstafter');
    view.unmount();
  });

  it('上一条未确认时阻止第二笔提交但保留全部键入草稿', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('one'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    view.stdin.write('two'); view.stdin.write('\r'); view.stdin.write('draft');
    await waitForFrame(() => view.lastFrame()?.includes('twodraft') === true);
    expect(client.prompts).toEqual(['one']);
    expect(view.lastFrame()).toContain('上一条输入仍在等待 Java 接受');

    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).toContain('twodraft');
    view.unmount();
  });

  it('协议拒绝把已发送内容恢复到后续编辑之前', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('rejected'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    view.stdin.write('after');
    client.emit({version: 0, type: 'protocol.error', requestId: 'tui-2', sessionId: 'session-1', sequence: 2, payload: {code: 'INPUT_COMMIT_MISMATCH'}});
    await waitForFrame(() => view.lastFrame()?.includes('rejectedafter') === true);

    expect(view.lastFrame()).toContain('rejectedafter');
    view.unmount();
  });

  it('transport 在 acceptance 前失败时恢复草稿，避免静默丢失输入', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('UNACCEPTED_DRAFT'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('正在等待 Java 接受') === true);
    client.emitFailure('transport closed');
    await waitForFrame(() => view.lastFrame()?.includes('连接已关闭') === true);
    await waitForFrame(() => view.lastFrame()?.includes('❯ UNACCEPTED_DRAFT') === true);

    expect(view.lastFrame()).toContain('连接已关闭');
    expect(view.lastFrame()).toContain('❯ UNACCEPTED_DRAFT');
    view.unmount();
  });

  it('transport 在 acceptance 后失败时不恢复输入，避免重复有副作用 Run', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('ACCEPTED_MUST_NOT_RESTORE'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({
      version: 0, type: 'run.command.result', requestId: 'tui-2', sessionId: 'session-1', sequence: 2,
      payload: {commandType: 'run.start', disposition: 'accepted', code: 'ACCEPTED'},
    });
    await waitForFrame(() => view.lastFrame()?.includes('Java 已接受，等待 Run 启动') === true);
    client.emitFailure('transport closed');
    await waitForFrame(() => view.lastFrame()?.includes('连接已关闭') === true);

    expect(view.lastFrame()).not.toContain('❯ ACCEPTED_MUST_NOT_RESTORE');
    view.unmount();
  });

  it('Shift+Enter 写入多行缓冲，Enter 显式提交完整内容', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    expect(view.lastFrame()).toContain('Enter 发送，Shift+Enter / Ctrl+J 换行');

    view.stdin.write('first');
    view.stdin.write(SHIFT_ENTER);
    view.stdin.write('second');
    expect(client.prompts).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['first\nsecond']);
    view.unmount();
  });

  it('运行中仍可编辑并以 Enter 排队普通多行补充，不改变当前 Run 投影', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('initial');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('正在等待 Java 接受') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    view.stdin.write('follow');
    view.stdin.write(SHIFT_ENTER);
    view.stdin.write('up');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 2);

    expect(client.prompts).toEqual(['initial', 'follow\nup']);
    expect(view.lastFrame()).toContain('正在处理');
    expect(view.lastFrame()).toContain('Enter 排队补充');
    view.unmount();
  });

  it('steering 队列满拒绝会恢复本地草稿，且后续 started 不会物化它', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('initial');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('正在等待 Java 接受') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    view.stdin.write('REJECTED_STEERING_SECRET');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 2);
    client.emit({
      version: 0, type: 'protocol.error', requestId: 'tui-3', sessionId: 'session-1', sequence: 3,
      payload: {code: 'STEERING_QUEUE_FULL'},
    });
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(view.lastFrame()).toContain('Java 协议错误：STEERING_QUEUE_FULL');
    expect(view.lastFrame()).toContain('REJECTED_STEERING_SECRET');
    expect(view.lastFrame()).not.toContain('（1/100）');
    view.unmount();
  });

  it('运行中 Slash 始终走命令通道，绝不进入 steering 或模型提示词', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('initial');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('正在等待 Java 接受') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    view.stdin.write('/doctor');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);

    expect(client.prompts).toEqual(['initial']);
    expect(client.sessionCommands).toEqual(['tui-command-1:doctor:{}']);
    view.unmount();
  });

  it('/plan 严格等待 query→PLAN 成功后才启动只读 Plan Run', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/plan 设计一个安全的登录流程'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    expect(client.sessionCommands).toEqual(['tui-plan-1-query:permissions:{}']);
    expect(client.planTasks).toEqual([]);

    client.emit(permissionResult('tui-plan-1-query', 'ASK', 2));
    await waitForFrame(() => client.sessionCommands.length === 2);
    expect(client.sessionCommands[1]).toBe('tui-plan-2-enter:permissions:{"selection":"PLAN"}');
    expect(client.planTasks).toEqual([]);
    client.emit(permissionResult('tui-plan-2-enter', 'PLAN', 3));
    await waitForFrame(() => client.planTasks.length === 1);
    expect(client.planTasks).toEqual(['设计一个安全的登录流程']);

    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 5, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    expect(client.planTasks).toEqual(['设计一个安全的登录流程']);
    view.unmount();
  });

  it('Plan proposal 完整展示，approve 后严格按 approve→restore→execute 顺序发送', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/plan safe task'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1); enterPlan(client);
    await waitForFrame(() => client.planTasks.length === 1);
    client.sessionCommands.length = 0;
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 5, payload: {planId: 'plan-run-plan', status: 'awaiting_approval',
        objective: '安全改造', workspaceDigest: 'a'.repeat(64), steps: [
          {ordinal: 1, title: '检查现状', detail: '阅读相关代码'},
          {ordinal: 2, title: '完成验证', detail: '运行聚焦测试'},
        ]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 6, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true);
    const frame = view.lastFrame() ?? '';
    expect(frame).toContain('安全改造'); expect(frame).toContain('1. 检查现状');
    expect(frame).toContain('阅读相关代码'); expect(frame).toContain('2. 完成验证');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    expect(client.sessionCommands[0]).toBe(
      `tui-plan-3-approve:plan-approve:{"planId":"plan-run-plan","workspaceDigest":"${'a'.repeat(64)}"}`,
    );
    client.emit({version: 0, type: 'session.command.result', requestId: 'approve-result',
      sessionId: 'session-1', sequence: 7, payload: {commandId: 'tui-plan-3-approve', intent: 'plan-approve',
        status: 'succeeded', code: 'ok', result: planCommandResult('plan-run-plan', 'a'.repeat(64), 'APPROVED')}});
    await waitForFrame(() => client.sessionCommands.length === 2);
    expect(client.sessionCommands[1]).toBe('tui-plan-4-restore-execute:permissions:{"selection":"ASK"}');
    expect(client.sessionCommands.some(item => item.includes('plan-execute'))).toBe(false);
    client.emit(permissionResult('tui-plan-4-restore-execute', 'ASK', 8));
    await waitForFrame(() => client.planExecutions.length === 1);
    expect(client.planExecutions[0]).toBe(`plan-run-plan:${'a'.repeat(64)}`);
    expect(client.sessionCommands).toHaveLength(2);
    view.unmount();
  });

  it('Plan revise 保持 PLAN，reject exit 成功后恢复进入前选择', async () => {
    for (const [arrows, revise] of [[1, true], [2, false]] as const) {
      const client = new FakeAgentClient(); const view = await initializedTui(client);
      view.stdin.write('/plan task'); view.stdin.write('\r'); await waitForFrame(() => client.sessionCommands.length === 1);
      enterPlan(client); await waitForFrame(() => client.planTasks.length === 1);
      client.sessionCommands.length = 0;
      client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 4, payload: {}});
      client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 5,
        payload: {planId: 'plan-current', status: 'awaiting_approval', objective: '计划', workspaceDigest: 'b'.repeat(64),
          steps: [{ordinal: 1, title: '步骤', detail: '详情'}]}});
      client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 6,
        payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
      await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true);
      for (let index = 0; index < arrows; index++) {
        view.stdin.write('[B'); await new Promise(resolve => setTimeout(resolve, 10));
      }
      view.stdin.write('\r'); await waitForFrame(() => client.sessionCommands.length === 1);
      expect(client.sessionCommands[0]).toBe('tui-plan-3-reject:plan-reject:{"planId":"plan-current"}');
      client.emit({version: 0, type: 'session.command.result', requestId: 'reject-result', sessionId: 'session-1', sequence: 7,
        payload: {commandId: 'tui-plan-3-reject', intent: 'plan-reject', status: 'succeeded', code: 'ok',
          result: planCommandResult('plan-current', 'b'.repeat(64), 'REJECTED')}});
      if (revise) {
        await waitForFrame(() => view.lastFrame()?.includes('计划未执行') === true);
        expect(client.sessionCommands).toHaveLength(1);
      } else {
        await waitForFrame(() => client.sessionCommands.length === 2);
        expect(client.sessionCommands[1]).toBe('tui-plan-4-restore-exit:permissions:{"selection":"ASK"}');
        client.emit(permissionResult('tui-plan-4-restore-exit', 'ASK', 8));
        await waitForFrame(() => view.lastFrame()?.includes('计划已拒绝') === true);
      }
      expect(client.sessionCommands.some(item => item.includes('plan-execute'))).toBe(false);
      view.unmount();
    }
  });

  it('AUTO→PLAN→revise→再次规划后批准仍恢复最初 AUTO', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan first'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    enterPlan(client, 2, 'AUTO'); await waitForFrame(() => client.planTasks.length === 1);
    client.sessionCommands.length = 0;
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan-1', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan-1', sequence: 5, payload: {planId: 'plan-first', status: 'awaiting_approval',
        objective: '初稿', workspaceDigest: '1'.repeat(64),
        steps: [{ordinal: 1, title: '初稿步骤', detail: '待修改'}]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan-1', sequence: 6, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true);
    view.stdin.write('[B'); await new Promise(resolve => setTimeout(resolve, 10)); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit({version: 0, type: 'session.command.result', requestId: 'reject-first',
      sessionId: 'session-1', sequence: 7, payload: {commandId: 'tui-plan-3-reject', intent: 'plan-reject',
        status: 'succeeded', code: 'ok', result: planCommandResult('plan-first', '1'.repeat(64), 'REJECTED')}});
    await waitForFrame(() => view.lastFrame()?.includes('保持 Plan 模式') === true);

    view.stdin.write('/plan revised'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 2);
    expect(client.sessionCommands[1]).toBe('tui-plan-4-query:permissions:{}');
    client.emit(permissionResult('tui-plan-4-query', 'PLAN', 8));
    await waitForFrame(() => client.sessionCommands.length === 3);
    expect(client.sessionCommands[2]).toBe('tui-plan-5-enter:permissions:{"selection":"PLAN"}');
    client.emit(permissionResult('tui-plan-5-enter', 'PLAN', 9));
    await waitForFrame(() => client.planTasks.length === 2);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-2', sessionId: 'session-1',
      runId: 'run-plan-2', sequence: 10, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-2', sessionId: 'session-1',
      runId: 'run-plan-2', sequence: 11, payload: {planId: 'plan-revised', status: 'awaiting_approval',
        objective: '修订稿', workspaceDigest: '2'.repeat(64),
        steps: [{ordinal: 1, title: '修订步骤', detail: '可执行'}]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-2', sessionId: 'session-1',
      runId: 'run-plan-2', sequence: 12, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('修订稿') === true); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 4);
    client.emit({version: 0, type: 'session.command.result', requestId: 'approve-revised',
      sessionId: 'session-1', sequence: 13, payload: {commandId: 'tui-plan-6-approve', intent: 'plan-approve',
        status: 'succeeded', code: 'ok', result: planCommandResult('plan-revised', '2'.repeat(64), 'APPROVED')}});
    await waitForFrame(() => client.sessionCommands.length === 5);
    expect(client.sessionCommands[4]).toBe('tui-plan-7-restore-execute:permissions:{"selection":"AUTO"}');
    client.emit(permissionResult('tui-plan-7-restore-execute', 'AUTO', 14));
    await waitForFrame(() => client.planExecutions.length === 1);
    expect(client.planExecutions[0]).toBe(`plan-revised:${'2'.repeat(64)}`);
    view.unmount();
  });

  it('Plan start 同步拒绝后发送绑定恢复命令并等待权限结果', async () => {
    const client = new FakeAgentClient(); client.rejectPlanStart = true;
    const view = await initializedTui(client);
    view.stdin.write('/plan rejected task'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit(permissionResult('tui-plan-1-query', 'AUTO', 2));
    client.emit(permissionResult('tui-plan-2-enter', 'PLAN', 3));
    await waitForFrame(() => client.sessionCommands.length === 3);
    expect(client.sessionCommands[2]).toBe(
      'tui-plan-3-restore-start-failure:permissions:{"selection":"AUTO"}',
    );
    expect(view.lastFrame()).not.toContain('已恢复进入前权限选择');
    client.emit(permissionResult('tui-plan-3-restore-start-failure', 'AUTO', 4));
    await waitForFrame(() => view.lastFrame()?.includes('已恢复进入前权限选择') === true);
    expect(client.planTasks).toEqual([]);
    view.unmount();
  });

  it('无参数 /plan 同样执行 query→PLAN→plan-status', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    expect(client.sessionCommands[0]).toBe('tui-plan-1-query:permissions:{}');
    client.emit(permissionResult('tui-plan-1-query', 'AUTO', 2));
    await waitForFrame(() => client.sessionCommands.length === 2);
    expect(client.sessionCommands[1]).toBe('tui-plan-2-enter:permissions:{"selection":"PLAN"}');
    client.emit(permissionResult('tui-plan-2-enter', 'PLAN', 3));
    await waitForFrame(() => client.sessionCommands.length === 3);
    expect(client.sessionCommands[2]).toBe('tui-plan-3-status:plan-status:{}');
    expect(client.planTasks).toEqual([]);
    view.unmount();
  });

  it('无参数 /plan 状态投影会完整显示并重新打开审批选择', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit(permissionResult('tui-plan-1-query', 'AUTO', 2));
    client.emit(permissionResult('tui-plan-2-enter', 'PLAN', 3));
    await waitForFrame(() => client.sessionCommands.length === 3);
    client.emit({version: 0, type: 'session.command.result', requestId: 'plan-status-result',
      sessionId: 'session-1', sequence: 4, payload: {commandId: 'tui-plan-3-status', intent: 'plan-status',
        status: 'succeeded', code: 'ok', result: planCommandResult('plan-existing', '3'.repeat(64), 'AWAITING_APPROVAL')}});
    await waitForFrame(() => view.lastFrame()?.includes('❯ 批准并执行') === true);
    const frame = view.lastFrame() ?? '';
    expect(frame).toContain('等待审批；输入 /plan 可重新打开计划视图');
    expect(frame).toContain('1. 步骤');
    expect(frame).toContain('详情');
    expect(frame).toContain('❯ 批准并执行');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 4);
    expect(client.sessionCommands[3]).toBe(
      `tui-plan-4-approve:plan-approve:{"planId":"plan-existing","workspaceDigest":"${'3'.repeat(64)}"}`,
    );
    view.unmount();
  });

  it('权限恢复失败保持已批准 Plan 且绝不 execute', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan task'); view.stdin.write('\r'); await waitForFrame(() => client.sessionCommands.length === 1);
    enterPlan(client); await waitForFrame(() => client.planTasks.length === 1); client.sessionCommands.length = 0;
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 5,
      payload: {planId: 'plan-current', status: 'awaiting_approval', objective: '计划', workspaceDigest: 'e'.repeat(64),
        steps: [{ordinal: 1, title: '步骤', detail: '详情'}]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 6,
      payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit({version: 0, type: 'session.command.result', requestId: 'approve', sessionId: 'session-1', sequence: 7,
      payload: {commandId: 'tui-plan-3-approve', intent: 'plan-approve', status: 'succeeded', code: 'ok',
        result: planCommandResult('plan-current', 'e'.repeat(64), 'APPROVED')}});
    await waitForFrame(() => client.sessionCommands.length === 2);
    client.emit(permissionResult('tui-plan-4-restore-execute', 'ASK', 8, 'rejected'));
    await waitForFrame(() => view.lastFrame()?.includes('权限恢复失败') === true);
    expect(client.sessionCommands.some(item => item.includes('plan-execute'))).toBe(false);
    expect(view.lastFrame()).toContain('批准并执行');
    view.unmount();
  });

  it('reject exit 只有恢复到预期 selection 才声明完成', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan task'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    enterPlan(client, 2, 'AUTO'); await waitForFrame(() => client.planTasks.length === 1);
    client.sessionCommands.length = 0;
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 5, payload: {planId: 'plan-current', status: 'awaiting_approval',
        objective: '计划', workspaceDigest: '4'.repeat(64),
        steps: [{ordinal: 1, title: '步骤', detail: '详情'}]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 6, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true);
    view.stdin.write('[B'); await new Promise(resolve => setTimeout(resolve, 10));
    view.stdin.write('[B'); await new Promise(resolve => setTimeout(resolve, 10));
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit({version: 0, type: 'session.command.result', requestId: 'reject',
      sessionId: 'session-1', sequence: 7, payload: {commandId: 'tui-plan-3-reject', intent: 'plan-reject',
        status: 'succeeded', code: 'ok', result: planCommandResult('plan-current', '4'.repeat(64), 'REJECTED')}});
    await waitForFrame(() => client.sessionCommands.length === 2);
    expect(client.sessionCommands[1]).toBe('tui-plan-4-restore-exit:permissions:{"selection":"AUTO"}');
    client.emit(permissionResult('tui-plan-4-restore-exit', 'ASK', 8));
    await waitForFrame(() => view.lastFrame()?.includes('权限恢复未确认') === true);
    expect(view.lastFrame()).not.toContain('计划已拒绝，未执行任何步骤');
    view.unmount();
  });

  it('plan.execute 无法启动时明确保留 Plan 恢复路径', async () => {
      const client = new FakeAgentClient(); const view = await initializedTui(client);
      client.rejectPlanExecution = true;
      view.stdin.write('/plan task'); view.stdin.write('\r');
      await waitForFrame(() => client.sessionCommands.length === 1);
      enterPlan(client); await waitForFrame(() => client.planTasks.length === 1);
      client.sessionCommands.length = 0;
      client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1',
        runId: 'run-plan', sequence: 4, payload: {}});
      client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1',
        runId: 'run-plan', sequence: 5, payload: {planId: 'plan-current', status: 'awaiting_approval',
          objective: '计划', workspaceDigest: '5'.repeat(64),
          steps: [{ordinal: 1, title: '步骤', detail: '详情'}]}});
      client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1',
        runId: 'run-plan', sequence: 6, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
      await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true); view.stdin.write('\r');
      client.emit({version: 0, type: 'session.command.result', requestId: 'approve',
        sessionId: 'session-1', sequence: 7, payload: {commandId: 'tui-plan-3-approve', intent: 'plan-approve',
          status: 'succeeded', code: 'ok', result: planCommandResult('plan-current', '5'.repeat(64), 'APPROVED')}});
      await waitForFrame(() => client.sessionCommands.length === 2);
      client.emit(permissionResult('tui-plan-4-restore-execute', 'ASK', 8));
      await waitForFrame(() => view.lastFrame()?.includes('执行未能启动') === true);
      await waitForFrame(() => view.lastFrame()?.includes('Plan 已保留') === true);
      expect(view.lastFrame()).toContain('可用 /plan 查看');
      view.unmount();
  });

  it('进入前已是 PLAN 时批准执行使用安全 ASK', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan task'); view.stdin.write('\r'); await waitForFrame(() => client.sessionCommands.length === 1);
    enterPlan(client, 2, 'PLAN'); await waitForFrame(() => client.planTasks.length === 1); client.sessionCommands.length = 0;
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 5,
      payload: {planId: 'plan-current', status: 'awaiting_approval', objective: '计划', workspaceDigest: 'f'.repeat(64),
        steps: [{ordinal: 1, title: '步骤', detail: '详情'}]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 6,
      payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true); view.stdin.write('\r');
    client.emit({version: 0, type: 'session.command.result', requestId: 'approve', sessionId: 'session-1', sequence: 7,
      payload: {commandId: 'tui-plan-3-approve', intent: 'plan-approve', status: 'succeeded', code: 'ok',
        result: planCommandResult('plan-current', 'f'.repeat(64), 'APPROVED')}});
    await waitForFrame(() => client.sessionCommands.length === 2);
    expect(client.sessionCommands[1]).toBe('tui-plan-4-restore-execute:permissions:{"selection":"ASK"}');
    view.unmount();
  });

  it('迟到或摘要不匹配的 approve 结果不会触发执行', async () => {
    const client = new FakeAgentClient(); const view = await initializedTui(client);
    view.stdin.write('/plan task'); view.stdin.write('\r'); await waitForFrame(() => client.sessionCommands.length === 1); enterPlan(client); await waitForFrame(() => client.planTasks.length === 1);
    client.sessionCommands.length = 0;
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.proposed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 5,
      payload: {planId: 'plan-current', status: 'awaiting_approval', objective: '计划', workspaceDigest: 'c'.repeat(64),
        steps: [{ordinal: 1, title: '步骤', detail: '详情'}]}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1', runId: 'run-plan', sequence: 6,
      payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('批准并执行') === true); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit({version: 0, type: 'session.command.result', requestId: 'stale-result', sessionId: 'session-1', sequence: 5,
      payload: {commandId: 'tui-plan-3-approve', intent: 'plan-approve', status: 'succeeded', code: 'ok',
        result: planCommandResult('plan-stale', 'd'.repeat(64), 'APPROVED')}});
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(client.sessionCommands).toHaveLength(1); expect(view.lastFrame()).toContain('批准并执行');
    view.unmount();
  });

  it('Slash 命令仅经 session command 通道发送，并显示安全终态提示', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/doctor');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    expect(client.sessionCommands).toEqual(['tui-command-1:doctor:{}']);
    expect(client.prompts).toEqual([]);
    client.emit({
      version: 0, type: 'session.command.result', requestId: 'command-result', sessionId: 'session-1', sequence: 2,
      payload: {commandId: 'tui-command-1', intent: 'doctor', status: 'rejected', code: 'deferred', result: {}},
    });
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).toContain('/doctor 未执行');
    view.unmount();
  });

  it('输入斜杠显示命令面板，方向键选择并以 Enter 补全后提交', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/');
    await waitForFrame(() => view.lastFrame()?.includes('Slash 命令 · ↑/↓ 选择') === true);
    expect(view.lastFrame()).toContain('/help — 查看命令与可用状态');
    view.stdin.write('\u001b[B');
    await waitForFrame(() => view.lastFrame()?.includes('❯ /compact') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('/compact') === true);
    expect(client.sessionCommands).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);

    expect(client.sessionCommands).toEqual(['tui-command-1:compact:{"anchors":[]}']);
    view.unmount();
  });

  it('/permissions 打开本地三选 picker，箭头选择后 Enter 仅发送一次准确 selection', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/permissions'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('Ask for approval') === true);

    expect(view.lastFrame()).toContain('Plan');
    expect(view.lastFrame()).toContain('Ask for approval');
    expect(view.lastFrame()).toContain('Approve for me');
    expect(client.prompts).toEqual([]);
    expect(view.lastFrame()).toContain('❯ Ask for approval');
    expect(client.sessionCommands).toEqual([]);
    view.stdin.write('[B');
    await waitForFrame(() => view.lastFrame()?.includes('❯ Approve for me') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    view.stdin.write('\r');
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(client.sessionCommands).toEqual(['tui-command-1:permissions:{"selection":"AUTO"}']);
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('缺少 session command 通道时不打开 permissions picker', async () => {
    const client = new FakeAgentClient();
    Object.defineProperty(client, 'sessionCommand', {
      value: undefined, configurable: true, writable: true,
    });
    const view = await initializedTui(client);
    view.stdin.write('/permissions'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('当前连接不支持 Slash 命令') === true);

    expect(view.lastFrame()).not.toContain('权限选择');
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('permissions picker Escape 关闭且不发送命令，也不会接受 composer 输入', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/permissions'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('Approve for me') === true);
    view.stdin.write('ordinary input');
    view.stdin.write('');
    await waitForFrame(() => view.lastFrame()?.includes('权限选择') === false);

    expect(client.sessionCommands).toEqual([]);
    expect(client.prompts).toEqual([]);
    expect(view.lastFrame()).not.toContain('ordinary input');
    view.unmount();
  });

  it('/permissions query 和 legacy mode 继续走 session command', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/permissions query'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    view.stdin.write('/permissions mode ACCEPT_EDITS'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 2);

    expect(client.sessionCommands).toEqual([
      'tui-command-1:permissions:{}',
      'tui-command-2:permissions:{"mode":"ACCEPT_EDITS"}',
    ]);
    view.unmount();
  });

  it('文件建议优先补全而不提交，下一次 Enter 才发送并支持 steering', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('看 @spa');
    await waitForFrame(() => client.fileSuggestions.includes('spa'));
    client.emit({version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'spa', candidates: ['dir/file name.md']}});
    await waitForFrame(() => view.lastFrame()?.includes('文件建议') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('@"dir/file name.md"') === true);
    expect(client.prompts).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    expect(client.prompts).toEqual(['看 @"dir/file name.md"']);

    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 3, payload: {}});
    view.stdin.write('补充 @src');
    await waitForFrame(() => client.fileSuggestions.includes('src'));
    const request = `file-${client.fileSuggestions.length}`;
    client.emit({version: 0, type: 'file.suggestions', requestId: request, sessionId: 'session-1', sequence: 4,
      payload: {query: 'src', candidates: ['src/App.java']}});
    await waitForFrame(() => view.lastFrame()?.includes('@src/App.java') === true);
    view.stdin.write('\t'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 2);
    expect(client.prompts[1]).toBe('补充 @src/App.java');
    view.unmount();
  });

  it('Escape 关闭文件建议，↑/↓ 在候选间选择', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('@src');
    await waitForFrame(() => client.fileSuggestions.includes('src'));
    client.emit({
      version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'src', candidates: ['src/A.java', 'src/B.java']},
    });
    await waitForFrame(() => view.lastFrame()?.includes('@src/A.java') === true);
    expect(view.lastFrame()).toContain('❯ @src/A.java');

    view.stdin.write('\u001b[B');
    await waitForFrame(() => view.lastFrame()?.includes('❯ @src/B.java') === true);
    view.stdin.write('\u001b[A');
    await waitForFrame(() => view.lastFrame()?.includes('❯ @src/A.java') === true);

    view.stdin.write('\u001b');
    await waitForFrame(() => view.lastFrame()?.includes('@src/B.java') !== true);
    expect(client.prompts).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    expect(client.prompts).toEqual(['@src']);
    view.unmount();
  });

  it('mention 交互期间 TUI 不读取本地文件系统', async () => {
    const sourceDirectory = fileURLToPath(new URL('../src/', import.meta.url));
    const sources = (await readdir(sourceDirectory))
      .filter(name => name.endsWith('.ts') || name.endsWith('.tsx'));
    expect(sources.length).toBeGreaterThan(0);
    const filesystemImports: string[] = [];
    for (const name of sources) {
      const text = await readFile(join(sourceDirectory, name), 'utf8');
      if (/from\s+'node:fs(\/promises)?'|require\('node:fs/u.test(text)) {
        filesystemImports.push(name);
      }
    }
    expect(filesystemImports).toEqual([]);

    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('看 @src/');
    await waitForFrame(() => client.fileSuggestions.includes('src/'));
    client.emit({
      version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'src/', candidates: ['src/App.java']},
    });
    await waitForFrame(() => view.lastFrame()?.includes('@src/App.java') === true);
    view.stdin.write('\t');
    await waitForFrame(() => view.lastFrame()?.includes('看 @src/App.java') === true);

    expect(client.fileSuggestions).toEqual(['src/']);
    view.unmount();
  });

  it('迟到文件建议不会覆盖较新的 token 查询', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    view.stdin.write('@a'); await waitForFrame(() => client.fileSuggestions.includes('a'));
    view.stdin.write('b'); await waitForFrame(() => client.fileSuggestions.includes('ab'));
    client.emit({version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'a', candidates: ['stale.java']}});
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).not.toContain('@stale.java');
    view.unmount();
  });

  it('/help 将 Java 安全投影渲染为可读命令清单', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/help');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit({
      version: 0, type: 'session.command.result', requestId: 'command-result', sessionId: 'session-1', sequence: 2,
      payload: {commandId: 'tui-command-1', intent: 'help', status: 'succeeded', code: 'ok', result: {commands: [
        {intent: 'help', support: 'available'}, {intent: 'clear', support: 'available'},
        {intent: 'compact', support: 'available'}, {intent: 'context', support: 'available'},
        {intent: 'doctor', support: 'available'}, {intent: 'model', support: 'available'},
        {intent: 'permissions', support: 'available'}, {intent: 'resume', support: 'available'},
      ]}},
    });
    await waitForFrame(() => view.lastFrame()?.includes('Slash 命令') === true);

    expect(view.lastFrame()).toContain('/context — 查看上下文用量　[可用]');
    expect(view.lastFrame()).toContain('/resume <session-id> — 安全恢复会话　[可用]');
    expect(view.lastFrame()).toContain('/connect [provider profile [env ENV_NAME]]');
    expect(view.lastFrame()).toContain('/auth list | probe');
    expect(view.lastFrame()).toContain('/models [provider] | use');
    view.unmount();
  });

  it('没有 session command 通道时 Slash 命令本地拒绝而不作为模型提示词提交', async () => {
    const client = new FakeAgentClient();
    Object.defineProperty(client, 'sessionCommand', {value: undefined});
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/doctor');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('当前连接不支持 Slash 命令') === true);
    expect(client.prompts).toEqual([]);
    view.stdin.write('/unknown');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('未知 Slash 命令') === true);
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('真实 useInput 链路完整提交含小写 c/d/u 的普通输入且不触发 Checkpoint', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({
      version: 0,
      type: 'initialized',
      requestId: 'tui-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {protocolVersion: 0},
    });
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('coding');
    await waitForFrame(() => view.lastFrame()?.includes('coding') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['coding']);
    expect(client.checkpointCommands).toEqual([]);
    view.unmount();
  });

  it('真实 useInput 链路可达 list/diff/undo 且仅二次确认后发送 Undo', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({
      version: 0,
      type: 'initialized',
      requestId: 'tui-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {protocolVersion: 0},
    });
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('C');
    await waitForFrame(() => client.checkpointCommands.length === 1);
    expect(client.checkpointCommands).toEqual(['list']);
    client.emit({
      version: 0,
      type: 'checkpoint.listed',
      requestId: 'tui-checkpoint-list',
      sessionId: 'session-1',
      sequence: 2,
      payload: {
        checkpoints: [{
          checkpointId: 'checkpoint-run-1-1',
          callId: 'call-1',
          toolName: 'apply_patch',
          target: 'src/App.java',
          existedBefore: true,
          phase: 'completed_present',
          undoable: true,
        }],
      },
    });
    await waitForFrame(() => view.lastFrame()?.includes('checkpoint-run-1-1') === true);

    view.stdin.write('D');
    await waitForFrame(() => client.checkpointCommands.length === 2);
    expect(client.checkpointCommands[1]).toBe('diff:checkpoint-run-1-1');
    view.stdin.write('U');
    await waitForFrame(() => view.lastFrame()?.includes('确认 Undo 当前 Checkpoint') === true);
    view.stdin.write('y');
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(client.checkpointCommands).toHaveLength(2);
    view.stdin.write('Y');
    await waitForFrame(() => client.checkpointCommands.length === 3);
    expect(client.checkpointCommands[2]).toBe('undo:checkpoint-run-1-1:true');
    view.unmount();
  });
});

function initialCheckpointState(): TuiState {
  return {
    phase: 'ready',
    sessionId: 'session-1',
    activeRunId: undefined,
    runs: [],
    checkpoints: [],
    checkpointPanelOpen: true,
    selectedCheckpointId: undefined,
    checkpointDiff: undefined,
    pendingUndoCheckpointId: undefined,
    checkpointUndo: undefined,
    notice: undefined,
  };
}

  it('Provider 控制完整展示 list、probe、selection、logout 和结构化错误', () => {
    expect(renderProviderControlResult('auth.list', 'succeeded', 'OK', {profiles: [{
      providerId: 'anthropic', profileId: 'personal', authMethod: 'API_KEY', refKind: 'ENV',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true, lastProbeCode: 'SUCCESS',
    }]})).toContain('API_KEY/ENV · AVAILABLE_LOCAL · 默认 · 探测 SUCCESS');
    expect(renderProviderControlResult('models.list', 'succeeded', 'OK', {models: [{
      providerId: 'anthropic', modelId: 'claude-sonnet', providerDefault: true,
    }]})).toContain('anthropic/claude-sonnet · 默认');
    const selection = renderProviderControlResult('models.use', 'succeeded', 'OK', {
      providerId: 'anthropic', modelId: 'claude-sonnet', profileId: 'personal', setDefault: true,
    });
    expect(selection).toContain('下一 Run 模型'); expect(selection).toContain('profile personal');
    expect(selection).toContain('持久默认');
    expect(renderProviderControlResult('models.add', 'succeeded', 'OK', {
      providerId: 'anthropic', modelId: 'claude-opus', setDefault: false,
    })).toContain('本地模型已添加');
    expect(renderProviderControlResult('models.remove', 'succeeded', 'OK', {
      providerId: 'anthropic', modelId: 'claude-opus',
    })).toContain('本地模型已移除');
    const probe = renderProviderControlResult('auth.probe', 'succeeded', 'OK', {
      providerId: 'anthropic', profileId: 'personal', modelId: 'claude-sonnet',
      outcome: 'SUCCESS', probedAt: '2026-08-14T12:00:00Z',
    });
    expect(probe).toContain('认证探测'); expect(probe).toContain('SUCCESS');
    expect(probe).toContain('2026-08-14T12:00:00Z');
    const logout = renderProviderControlResult('auth.logout', 'succeeded', 'OK', {
      providerId: 'anthropic', profileId: 'personal', remoteRevoked: false,
    });
    expect(logout).toContain('anthropic/personal'); expect(logout).toContain('Provider 侧 credential 未撤销');
    const conflict = renderProviderControlResult('models.use', 'rejected', 'AUTH_TRANSACTION_CONFLICT', {});
    expect(conflict).toContain('当前有活动 Run'); expect(conflict).toContain('AUTH_TRANSACTION_CONFLICT');
    const rejected = renderProviderControlResult('auth.probe', 'rejected', 'AUTH_PROBE_REJECTED', {});
    expect(rejected).toContain('Provider 拒绝该 credential'); expect(rejected).toContain('AUTH_PROBE_REJECTED');
  });

  it('远离内置命令的合法 Slash 仍经显式 Skill 通道提交', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/deploy-check safe args'); view.stdin.write('\r');
    await waitForFrame(() => client.skillInvocations.length === 1);

    expect(client.skillInvocations).toEqual(['deploy-check:safe args']);
    expect(client.prompts).toEqual([]);
    expect(client.sessionCommands).toEqual([]);
    expect(client.providerControls).toEqual([]);
    view.unmount();
  });

  it('首次启动缺少模型配置时自动打开最小表单且不能用 Esc 绕过', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1,
      payload: {modelConfigured: false}});
    await waitForFrame(() => view.lastFrame()?.includes('配置 CodeJ 模型') === true);

    expect(view.lastFrame()).toContain('API Base URL');
    expect(view.lastFrame()).toContain('模型名称');
    expect(view.lastFrame()).toContain('粘贴 API Key');
    expect(view.lastFrame()).not.toContain('╭');
    expect(view.lastFrame()).not.toContain('Anthropic');
    expect(view.lastFrame()).not.toContain('OpenRouter');

    view.stdin.write('\x1b');
    await new Promise(resolve => setTimeout(resolve, 25));
    expect(view.lastFrame()).toContain('配置 CodeJ 模型');
    view.unmount();
  });

  it('已有可用默认模型时首次启动不打断 Composer', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1,
      payload: {modelConfigured: true}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    expect(view.lastFrame()).not.toContain('配置 CodeJ 模型');
    expect(client.providerControls).toEqual([]);
    view.unmount();
  });

  it('/connect 实时显示脱敏 Key，再通过一次性 stdin 保存', async () => {
    const client = new FakeAgentClient();
    client.providerLoginResult = {
      status: 'succeeded', exitCode: 0, credentialPreview: 'sk-…a9K2',
    };
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => view.lastFrame()?.includes('配置 CodeJ 模型') === true);

    view.stdin.write('https://gateway.example/v1');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('❯ 模型名称') === true);
    view.stdin.write('model-x');
    view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);

    expect(client.providerControls).toEqual([
      'tui-setup:1:configure:providers.configure:{"baseUrl":"https://gateway.example/v1","modelId":"model-x"}',
    ]);
    client.emit(connectResult('tui-setup:1:configure', 'providers.configure', 2, {
      providerId: 'codej-custom', displayName: 'CodeJ Custom', modelId: 'model-x',
    }));
    await waitForFrame(() => view.lastFrame()?.includes('粘贴或输入') === true);
    view.stdin.write('sk-');
    await waitForFrame(() => view.lastFrame()?.includes('API Key　sk-') === true);
    view.stdin.write('\x1b[200~protected-a9K2\x1b[201~');
    await waitForFrame(() => view.lastFrame()?.includes('sk-••••••••a9K2') === true);
    expect(view.lastFrame()).not.toContain('protected');
    view.stdin.write('\r');
    await waitForFrame(() => client.providerLogins.length === 1);
    expect(client.providerLogins[0]).toMatchObject({
      providerId: 'codej-custom', profileId: 'default', secretSource: 'stdin', setDefault: true,
    });
    await waitForFrame(() => view.lastFrame()?.includes('模型配置完成：model-x') === true);
    expect(view.lastFrame()).toContain('API Key　sk-••••••••a9K2');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('配置 CodeJ 模型') === false);
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('/connect 在当前表单内校验 HTTPS URL，并允许 Esc 返回或关闭', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    view.stdin.write('http://unsafe.example/v1');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('请输入有效的 HTTPS API Base URL') === true);
    expect(client.providerControls).toEqual([]);

    for (let index = 0; index < 'http://unsafe.example/v1'.length; index++) view.stdin.write('\x7f');
    view.stdin.write('https://safe.example/v1');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('❯ 模型名称') === true);
    view.stdin.write('\x1b');
    await waitForFrame(() => view.lastFrame()?.includes('❯ API Base URL') === true);
    view.stdin.write('\x1b');
    await waitForFrame(() => view.lastFrame()?.includes('配置 CodeJ 模型') === false);
    view.unmount();
  });
  it('/connect profile 直接调用一次性 Java 登录并在成功后刷新 auth.list', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/connect anthropic personal'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);

    expect(client.providerLogins).toEqual([{
      providerId: 'anthropic', profileId: 'personal', secretSource: 'store',
    }]);
    expect(client.providerControls).toEqual(['tui-provider:1:auth.list:auth.list:{}']);
    expect(client.prompts).toEqual([]);
    expect(view.lastFrame()).toContain('正在刷新 credential 列表');
    view.unmount();
  });

  it('/connect ENV 只传合法环境变量名称而不读取值', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/connect openrouter ci env OPENROUTER_API_KEY'); view.stdin.write('\r');
    await waitForFrame(() => client.providerLogins.length === 1);

    expect(client.providerLogins[0]).toEqual({
      providerId: 'openrouter', profileId: 'ci', secretSource: 'env', environmentName: 'OPENROUTER_API_KEY',
    });
    expect(view.lastFrame()).toContain('TUI 不读取环境值');
    view.unmount();
  });

  it('发送 models.add/remove，并在 add 成功后刷新 models.list', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/models add anthropic claude-opus default'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);
    expect(client.providerControls).toEqual([
      'tui-provider:1:models.add:models.add:{"providerId":"anthropic","modelId":"claude-opus","setDefault":true}',
    ]);
    client.emit({version: 0, type: 'provider.control.result', requestId: 'provider-result',
      sessionId: 'session-1', sequence: 2, payload: {controlId: 'tui-provider:1:models.add', intent: 'models.add',
        status: 'succeeded', code: 'OK', result: {providerId: 'anthropic', modelId: 'claude-opus'}}});
    await waitForFrame(() => client.providerControls.length === 2);
    expect(client.providerControls[1]).toBe('tui-provider:2:models.list:models.list:{}');

    view.stdin.write('/models remove anthropic claude-sonnet'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 3);
    expect(client.providerControls[2]).toBe(
      'tui-provider:3:models.remove:models.remove:{"providerId":"anthropic","modelId":"claude-sonnet"}',
    );
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('Provider Slash 通过真实 stdio 控制通道并渲染安全结果', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/auth list'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);
    expect(client.providerControls).toEqual(['tui-provider:1:auth.list:auth.list:{}']);
    expect(client.prompts).toEqual([]);
    client.emit({version: 0, type: 'provider.control.result', requestId: 'provider-result',
      sessionId: 'session-1', sequence: 2, payload: {controlId: 'tui-provider:1:auth.list', intent: 'auth.list',
        status: 'succeeded', code: 'OK', result: {profiles: [{providerId: 'anthropic', profileId: 'personal',
          authMethod: 'API_KEY', refKind: 'ENV', localStatus: 'AVAILABLE_LOCAL', providerDefault: true}]}}});
    await waitForFrame(() => view.lastFrame()?.includes('anthropic/personal') === true);
    view.unmount();
  });
function permissionResult(
  commandId: string,
  selection: 'PLAN' | 'ASK' | 'AUTO' | 'ADVANCED',
  sequence: number,
  status: 'succeeded' | 'rejected' = 'succeeded',
): ProtocolEvent {
  const mode = selection === 'PLAN' ? 'PLAN' : selection === 'ADVANCED' ? 'ACCEPT_EDITS' : 'DEFAULT';
  const reviewer = selection === 'AUTO' ? 'AUTO_REVIEW' : 'USER';
  return {version: 0, type: 'session.command.result', requestId: `result-${commandId}`,
    sessionId: 'session-1', sequence, payload: {commandId, intent: 'permissions', status,
      code: status === 'succeeded' ? 'ok' : 'invalid_argument', result: status === 'succeeded' ? {
        effectiveMode: mode, effectiveReviewer: reviewer, effectiveSelection: selection,
        modeSourceKind: 'BASELINE', modeSafeSourceId: 'runtime-baseline',
        modeValidationStatus: 'BASELINE', startupRuleCount: 0, rules: [],
      } : {}}};
}

function enterPlan(client: FakeAgentClient, sequence = 2, selection: 'PLAN' | 'ASK' | 'AUTO' | 'ADVANCED' = 'ASK'): void {
  client.emit(permissionResult('tui-plan-1-query', selection, sequence));
  client.emit(permissionResult('tui-plan-2-enter', 'PLAN', sequence + 1));
}

function planCommandResult(planId: string, workspaceDigest: string, status: string) {
  return {planId, status, approvalGate: status === 'APPROVED' ? 'APPROVED' : 'PENDING', objective: '计划',
    workspaceDigest, steps: [{ordinal: 1, title: '步骤', detail: '详情', expectedDigest: workspaceDigest}],
    nextStep: 1, activeStep: null};
}

async function initializedTui(client: FakeAgentClient) {
  const view = render(<AgentTui client={client} />);
  await waitForFrame(() => client.initializeCalls === 1);
  client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
  await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
  return view;
}

function submitConnect(view: ReturnType<typeof render>): void {
  view.stdin.write('/connect');
  view.stdin.write('\r');
}

function connectResult(
  controlId: string,
  intent: 'providers.configure' | 'providers.add' | 'models.list' | 'models.use' | 'auth.list',
  sequence: number,
  result: Readonly<Record<string, unknown>>,
  status: 'succeeded' | 'rejected' = 'succeeded',
  code = 'OK',
): ProtocolEvent {
  return {
    version: 0, type: 'provider.control.result', requestId: `provider-result-${sequence}`,
    sessionId: 'session-1', sequence,
    payload: {controlId, intent, status, code, result},
  };
}

describe('approvalDecision', () => {
  it('把 Y/A/N 映射为单次允许、会话允许或拒绝', () => {
    expect(approvalDecision('Y')).toBe('allow_once');
    expect(approvalDecision('a')).toBe('allow_session');
    expect(approvalDecision('n')).toBe('deny');
    expect(approvalDecision('x')).toBeUndefined();
  });
});

describe('TUI interaction polish', () => {
  it('审批快捷键 y/a/n 立即提交，Esc 拒绝当前工具而不取消 Run', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('patch file'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-approval', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'approval.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-approval', sequence: 3, payload: {approvalId: 'approval-y', ordinal: 1,
        toolName: 'apply_patch', effect: 'write_workspace', target: 'src/App.java', operation: 'modify',
        removedLines: 1, addedLines: 2}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Allow once'));
    view.stdin.write('y');
    await waitForFrame(() => client.approvals.length === 1);
    expect(client.approvals).toEqual(['approval-y:allow_once']);
    expect(client.cancelRunCalls).toBe(0);
    view.unmount();

    const denyClient = new FakeAgentClient();
    const denyView = await initializedTui(denyClient);
    denyView.stdin.write('patch file'); denyView.stdin.write('\r');
    await waitForFrame(() => denyClient.prompts.length === 1);
    denyClient.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-deny', sequence: 2, payload: {}});
    denyClient.emit({version: 0, type: 'approval.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-deny', sequence: 3, payload: {approvalId: 'approval-esc', ordinal: 1,
        toolName: 'apply_patch', effect: 'write_workspace', target: 'src/App.java', operation: 'modify',
        removedLines: 1, addedLines: 2}});
    await waitForFrame(() => (denyView.lastFrame() ?? '').includes('N/Esc 拒绝'));
    denyView.stdin.write('\x1b');
    await waitForFrame(() => denyClient.approvals.length === 1);
    expect(denyClient.approvals).toEqual(['approval-esc:deny']);
    expect(denyClient.cancelRunCalls).toBe(0);
    denyView.unmount();
  });

  it('完整 Slash 命令第一次 Enter 就提交，不再被补全吞掉', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/help');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    expect(client.sessionCommands).toEqual(['tui-command-1:help:{}']);
    view.unmount();
  });

  it('权限选择保留历史对话，不整屏替换', () => {
    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, notice: undefined,
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined, checkpointUndo: undefined,
      runs: [{
        requestId: 'req-keep', prompt: '保留这段历史', runId: 'run-keep', text: '已有回答',
        tools: [], status: 'completed', stopReason: 'completed', modelTurns: 1, toolCalls: 0,
      }],
    };
    const frame = render(<AgentView
      state={state} permissionPicker={initialPermissionPickerState} columns={80}
    />).lastFrame() ?? '';
    expect(frame).toContain('保留这段历史');
    expect(frame).toContain('已有回答');
    expect(frame).toContain('Ask for approval');
    expect(frame).toContain('权限选择');
  });

  it('行尾空白加反斜杠再 Enter 写入换行而不是提交', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('first \\');
    view.stdin.write('\r');
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(client.prompts).toEqual([]);
    view.stdin.write('second');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    expect(client.prompts).toEqual(['first \nsecond']);
    view.unmount();
  });

  it('Windows 路径尾部反斜杠第一次 Enter 提交而不是换行', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('C:\\Users\\foo\\');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    expect(client.prompts).toEqual(['C:\\Users\\foo\\']);
    view.unmount();
  });
});


describe('continuous plan Ink interaction', () => {
  it('renders structured options and resumes the same active run with the selected option', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan task'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-1', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'question.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-1', sequence: 3, payload: {callId: 'ask-1', question: 'Choose rollout', options: [
        {optionId: 'safe', label: 'Safe', description: 'Staged'},
        {optionId: 'fast', label: 'Fast', description: 'Direct'},
      ]}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Choose rollout'));
    expect(view.lastFrame()).toContain('Safe');
    view.stdin.write('\u001b[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ Fast'));
    view.stdin.write('\r');
    await waitForFrame(() => client.questionAnswers.length === 1);
    expect(client.questionAnswers).toEqual(['ask-1:fast']);
    view.unmount();
  });

  it('renders durable Markdown review and sends one bound normal-approval decision', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan task'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-1', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-1', sequence: 3, payload: {planId: 'plan-a', status: 'awaiting_approval', revision: 3,
        contentDigest: 'a'.repeat(64), markdown: '# Plan\n\nSafe rollout.', workspaceDigest: 'b'.repeat(64), originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-1', sequence: 4, payload: {stopReason: 'completed', modelTurns: 2, toolCalls: 2}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Safe rollout'));
    view.stdin.write('\u001b[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 批准并执行（后续 Tool 正常逐项询问）'));
    view.stdin.write('\r');
    await waitForFrame(() => client.planReviewResolutions.length === 1);
    expect(client.planReviewResolutions).toEqual([
      `plan-a:3:${'a'.repeat(64)}:${'b'.repeat(64)}:APPROVE_USER:KEEP:`,
    ]);
    view.unmount();
  });

  it('/plan durable approval restores the entry permission before the atomic execution handoff', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('/plan durable task'); view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit(permissionResult('tui-plan-1-query', 'ASK', 2));
    await waitForFrame(() => client.sessionCommands.length === 2);
    client.emit(permissionResult('tui-plan-2-enter', 'PLAN', 3));
    await waitForFrame(() => client.planTasks.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 4, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 5, payload: {planId: 'plan-durable', status: 'awaiting_approval', revision: 3,
        contentDigest: 'a'.repeat(64), markdown: '# Durable', workspaceDigest: 'b'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-plan-1', sessionId: 'session-1',
      runId: 'run-plan', sequence: 6, payload: {stopReason: 'completed', modelTurns: 2, toolCalls: 2}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('批准并自动执行'));
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 3);

    expect(client.planReviewResolutions).toEqual([]);
    expect(client.sessionCommands[2]).toContain(':permissions:{"selection":"ASK"}');
    const restoreCommandId = client.sessionCommands[2]!.split(':', 1)[0]!;
    client.emit(permissionResult(restoreCommandId, 'ASK', 7));
    await waitForFrame(() => client.planReviewResolutions.length === 1);
    expect(client.planReviewResolutions[0]).toContain(':APPROVE_AUTO:KEEP:');
    view.unmount();
  });

  it('default approve auto and Tab context toggle are driven by actual key events', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan task'); view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-key', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-key', sequence: 3, payload: {planId: 'plan-keys', status: 'awaiting_approval', revision: 7,
        contentDigest: 'c'.repeat(64), markdown: '# Key plan\n\nVerify.', workspaceDigest: 'd'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-key', sequence: 4, payload: {stopReason: 'completed', modelTurns: 2, toolCalls: 3}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('批准并自动执行'));
    view.stdin.write(String.fromCharCode(9));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('当前 清空'));
    view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => client.planReviewResolutions.length === 1);
    expect(client.planReviewResolutions[0]).toContain(':APPROVE_AUTO:CLEAR');
    view.unmount();
  });

  it('selects feedback, accepts keyboard input, and receives a fresh run for the same plan', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan task'); view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-choice', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-choice', sequence: 3, payload: {planId: 'plan-choice', status: 'awaiting_approval', revision: 4,
        contentDigest: 'e'.repeat(64), markdown: '# Choice plan', workspaceDigest: 'f'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'clear'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-choice', sequence: 4, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 1}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Choice plan'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 批准并执行（后续 Tool 正常逐项询问）'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 带反馈继续规划'));
    view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('请输入计划反馈'));
    view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('继续规划需要非空反馈'));
    expect(client.planReviewResolutions).toEqual([]);
    view.stdin.write('refine rollback verification');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('refine rollback verification'));
    view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => client.planReviewResolutions.length === 1);
    expect(client.planReviewResolutions[0]).toContain(':CONTINUE_PLANNING:CLEAR:refine rollback verification');
    client.emit({version: 0, type: 'plan.feedback.accepted', requestId: 'tui-plan-review-1', sessionId: 'session-1',
      sequence: 5, payload: {planId: 'plan-choice', status: 'draft', revision: 5, contentDigest: '1'.repeat(64)}});
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-review-1', sessionId: 'session-1',
      runId: 'run-feedback', sequence: 6, payload: {}});
    client.emit({version: 0, type: 'model.text.delta', requestId: 'tui-plan-review-1', sessionId: 'session-1',
      runId: 'run-feedback', sequence: 7, payload: {text: 'refined planning round'}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('refined planning round'));
    expect(view.lastFrame()).toContain('反馈已接受，正在为同一计划启动新的规划回合');
    expect(view.lastFrame()).not.toContain('决定已发送');
    expect(view.lastFrame()).not.toContain('带反馈继续规划');
    expect(view.lastFrame()).not.toContain('请输入计划反馈');
    view.unmount();
  });

  it('preprojects approved execution before an early run.started and renders tools through completion', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan atomic handoff'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-planning', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-planning', sequence: 3, payload: {planId: 'plan-atomic', status: 'awaiting_approval', revision: 3,
        contentDigest: 'a'.repeat(64), markdown: '# Atomic plan\n\nRun two tools.', workspaceDigest: 'b'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-planning', sequence: 4, payload: {stopReason: 'completed', modelTurns: 2, toolCalls: 2}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Run two tools'));

    view.stdin.write('\r');
    view.stdin.write('\r');
    await waitForFrame(() => client.planReviewResolutions.length === 1);
    await waitForFrame(() => (view.lastFrame() ?? '').includes('执行计划 plan-atomic（自动审批）'));
    const requestId = 'tui-plan-review-1';
    client.emit({version: 0, type: 'run.started', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 5, payload: {}});
    client.emit({version: 0, type: 'model.turn.started', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 6, payload: {turn: 1}});
    client.emit({version: 0, type: 'model.turn.completed', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 7, payload: {turn: 1, finishReason: 'tool_calls',
        usage: {inputTokens: 1200, outputTokens: 80, totalTokens: 1280},
        context: {usedTokens: 4000, maximumInputTokens: 128000, estimateKind: 'estimated'}}});
    client.emit({version: 0, type: 'tool.started', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 8, payload: {ordinal: 1, toolName: 'read_file',
        activity: '读取 src/Plan.java'}});
    client.emit({version: 0, type: 'tool.completed', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 9, payload: {ordinal: 1, toolName: 'read_file', status: 'success',
        returnedCharacters: 12, returnedItems: 1, filteredItems: 0, truncated: false}});
    client.emit({version: 0, type: 'tool.started', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 10, payload: {ordinal: 2, toolName: 'git_status'}});
    client.emit({version: 0, type: 'tool.completed', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 11, payload: {ordinal: 2, toolName: 'git_status', status: 'success',
        returnedCharacters: 20, returnedItems: 2, filteredItems: 0, truncated: false}});
    client.emit({version: 0, type: 'model.turn.started', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 12, payload: {turn: 2}});
    client.emit({version: 0, type: 'model.text.delta', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 13, payload: {text: 'execution verified'}});
    client.emit({version: 0, type: 'model.turn.completed', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 14, payload: {turn: 2, finishReason: 'stop',
        usage: {inputTokens: 1800, outputTokens: 120, totalTokens: 1920},
        context: {usedTokens: 5200, maximumInputTokens: 128000, estimateKind: 'estimated'}}});
    client.emit({version: 0, type: 'run.completed', requestId, sessionId: 'session-1',
      runId: 'run-execution', sequence: 15, payload: {stopReason: 'completed', modelTurns: 2, toolCalls: 2}});
    client.emit({version: 0, type: 'plan.verification.completed', requestId, sessionId: 'session-1',
      sequence: 16, payload: {planId: 'plan-atomic', status: 'completed', requiredEvidence: 1, satisfiedEvidence: 1}});
    client.emit({version: 0, type: 'plan.execution.accepted', requestId, sessionId: 'session-1',
      sequence: 17, payload: {planId: 'plan-atomic', status: 'approved', revision: 3,
        contentDigest: 'a'.repeat(64), contextPolicy: 'keep', approvalReviewer: 'auto_review'}});

    await waitForFrame(() => (view.lastFrame() ?? '').includes('execution verified')
      && (view.lastFrame() ?? '').includes('已完成 · 2 回合 · 2 次工具')
      && (view.lastFrame() ?? '').includes('计划证据已验证'));
    const frame = view.lastFrame() ?? '';
    expect(frame).toContain('阅读文件 · 读取 src/Plan.java');
    expect(frame).toContain('检查工作区');
    expect(frame).toContain('Provider 实测 累计 3.2k（↑ 3.0k · ↓ 200）');
    expect(frame).toContain('上下文估算 5.2k/128k');
    expect(frame).not.toContain('无法关联');
    expect(frame).not.toContain('连接已关闭');
    view.unmount();
  });

  it('renders correction continuation without exposing a withheld completion claim', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('execute approved plan'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    const requestId = 'tui-2';
    client.emit({version: 0, type: 'run.started', requestId, sessionId: 'session-1',
      runId: 'run-correction', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.verification.correction', requestId, sessionId: 'session-1',
      runId: 'run-correction', sequence: 3, payload: {attempt: 1, maxAttempts: 2, failures: [{
        requirementId: 'weather-xlsx', kind: 'deliverable', locator: '河南各市7天天气.xlsx',
        reason: 'FILE_MISSING_OR_UNSAFE',
      }]}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('同一 Run 内纠正（1/2）'));
    expect(view.lastFrame()).toContain('不会自动重放既有副作用');
    expect(view.lastFrame()).toContain('运行中');
    expect(view.lastFrame()).not.toContain('FIRST_UNVERIFIED_FINAL');

    client.emit({version: 0, type: 'plan.verification.required', requestId, sessionId: 'session-1',
      sequence: 4, payload: {planId: 'plan-1', status: 'needs_verification',
        requiredEvidence: 1, satisfiedEvidence: 0}});
    client.emit({version: 0, type: 'run.completed', requestId, sessionId: 'session-1',
      runId: 'run-correction', sequence: 5, payload: {stopReason: 'completed', modelTurns: 2, toolCalls: 0}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('计划尚未完成')
      && (view.lastFrame() ?? '').includes('0/1') && (view.lastFrame() ?? '').includes('就绪'));
    const frame = view.lastFrame() ?? '';
    expect(frame).not.toContain('FIRST_UNVERIFIED_FINAL');
    expect(frame).not.toContain('已完成并交付');
    view.unmount();
  });

  it('用专用组合键选择和折叠 Tool 详情且不抢 Approval picker', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('run command'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    const requestId = 'tui-2';
    client.emit({version: 0, type: 'run.started', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'tool.started', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 3, payload: {ordinal: 1, toolName: 'run_command'}});
    client.emit({version: 0, type: 'tool.output', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 4, payload: {ordinal: 1, toolName: 'run_command',
        stream: 'stderr', text: 'failure\nfailure\n'}});
    client.emit({version: 0, type: 'approval.requested', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 5, payload: {approvalId: 'approval-tool', ordinal: 1,
        toolName: 'run_command', effect: 'execute_process', command: 'test', shell: 'powershell',
        workingDirectory: '.'}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Ctrl+O 展开'));
    view.stdin.write(String.fromCharCode(15));
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).not.toContain('stderr │ failure');

    view.stdin.write('\r');
    await new Promise(resolve => setTimeout(resolve, 20));
    client.emit({version: 0, type: 'tool.failed', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 6, payload: {ordinal: 1, toolName: 'run_command',
        status: 'failed', errorCode: 'process_exit', failureCategory: 'process_exit', retryable: false,
        exitCode: 9}});
    await waitForFrame(() => !(view.lastFrame() ?? '').includes('允许一次'));
    await new Promise(resolve => setTimeout(resolve, 20));
    view.stdin.write(String.fromCharCode(15));
    await new Promise(resolve => setTimeout(resolve, 30));
    expect(view.lastFrame()).toContain('stderr │ failure');
    expect(view.lastFrame()).toContain('×2');
    view.stdin.write(String.fromCharCode(15));
    await waitForFrame(() => !(view.lastFrame() ?? '').includes('stderr │ failure'));

    client.emit({version: 0, type: 'tool.started', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 7,
      payload: {ordinal: 2, toolName: 'read_file'}});
    client.emit({version: 0, type: 'tool.output', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 8,
      payload: {ordinal: 2, toolName: 'read_file', stream: 'stdout', text: 'second detail\n'}});
    client.emit({version: 0, type: 'tool.completed', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 9,
      payload: {ordinal: 2, toolName: 'read_file', status: 'success'}});
    client.emit({version: 0, type: 'run.failed', requestId, sessionId: 'session-1',
      runId: 'run-tool-detail', sequence: 10,
      payload: {stopReason: 'tool_failure', modelTurns: 1, toolCalls: 2}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Ctrl+O 查看最近历史 Tool 详情'));
    expect(view.lastFrame()).not.toContain('Ctrl+O 展开');
    view.stdin.write(String.fromCharCode(15));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('最近历史 Tool 详情')
      && (view.lastFrame() ?? '').includes('stderr │ failure'));
    expect(view.lastFrame()).toContain('Ctrl+O 关闭详情');
    view.stdin.write(String.fromCharCode(20));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('stdout │ second detail'));
    expect(view.lastFrame()).not.toContain('stderr │ failure');
    await new Promise(resolve => setTimeout(resolve, 20));
    view.stdin.write(String.fromCharCode(15));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Ctrl+O 查看最近历史 Tool 详情'));
    view.unmount();
  });

  it('rejects a durable review without creating an execution Run row', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan reject task'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-reject', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-reject', sequence: 3, payload: {planId: 'plan-reject', status: 'awaiting_approval', revision: 2,
        contentDigest: '4'.repeat(64), markdown: '# Reject plan', workspaceDigest: '5'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-reject', sequence: 4, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 1}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Reject plan'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 批准并执行（后续 Tool 正常逐项询问）'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 带反馈继续规划'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 拒绝并退出'));
    view.stdin.write('\r');
    await waitForFrame(() => client.planReviewResolutions.length === 1);
    client.emit({version: 0, type: 'plan.review.rejected', requestId: 'tui-plan-review-1', sessionId: 'session-1',
      sequence: 5, payload: {planId: 'plan-reject', status: 'rejected'}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('计划已拒绝，未执行任何步骤'));
    expect(client.planReviewResolutions[0]).toContain(':REJECT:KEEP:');
    expect(view.lastFrame()).not.toContain('执行计划 plan-reject');
    view.unmount();
  });

  it.each([
    ['run.failed', '运行失败', 'execution_failed'],
    ['run.cancelled', '已取消', 'cancelled'],
  ] as const)('renders approved execution terminal %s without losing request correlation', async (terminalType, label, reason) => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan terminal task'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-plan-terminal', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-plan-terminal', sequence: 3, payload: {planId: 'plan-terminal', status: 'awaiting_approval', revision: 2,
        contentDigest: '6'.repeat(64), markdown: '# Terminal plan', workspaceDigest: '7'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-plan-terminal', sequence: 4, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 1}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Terminal plan'));
    view.stdin.write('\r');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('执行计划 plan-terminal'));
    client.emit({version: 0, type: 'run.started', requestId: 'tui-plan-review-1', sessionId: 'session-1',
      runId: 'run-terminal', sequence: 5, payload: {}});
    client.emit({version: 0, type: terminalType, requestId: 'tui-plan-review-1', sessionId: 'session-1',
      runId: 'run-terminal', sequence: 6, payload: {stopReason: reason, modelTurns: 1, toolCalls: 0}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes(label));
    expect(view.lastFrame()).not.toContain('无法关联');
    expect(view.lastFrame()).not.toContain('连接已关闭');
    view.unmount();
  });

  it('submission exception restores durable review without creating an execution run', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan retry task'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-retry', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-retry', sequence: 3, payload: {planId: 'plan-retry', status: 'awaiting_approval', revision: 2,
        contentDigest: 'c'.repeat(64), markdown: '# Retry plan', workspaceDigest: 'd'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-retry', sequence: 4, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 1}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Retry plan'));
    client.rejectPlanReviewResolution = true;
    view.stdin.write('\r');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Plan 决定未被连接接受'));
    expect(view.lastFrame()).toContain('批准并自动执行');
    expect(view.lastFrame()).not.toContain('执行计划 plan-retry');
    expect(client.planReviewResolutions).toEqual([]);
    view.unmount();
  });

  it('protocol rejection removes the optimistic execution row and restores review choices', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan protocol retry'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-protocol', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-protocol', sequence: 3, payload: {planId: 'plan-protocol', status: 'awaiting_approval', revision: 2,
        contentDigest: 'e'.repeat(64), markdown: '# Protocol plan', workspaceDigest: 'f'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-protocol', sequence: 4, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 1}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Protocol plan'));
    view.stdin.write('\r');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('执行计划 plan-protocol'));
    client.emit({version: 0, type: 'protocol.error', requestId: 'tui-plan-review-1', sequence: 5,
      payload: {code: 'INVALID_STATE', message: 'unsafe detail'}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Java 协议错误：INVALID_STATE'));
    expect(view.lastFrame()).toContain('批准并自动执行');
    expect(view.lastFrame()).not.toContain('执行计划 plan-protocol');
    expect(view.lastFrame()).not.toContain('连接已关闭');
    view.unmount();
  });

  it('protocol rejection restores durable feedback text and removes the optimistic planning row', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan feedback retry'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-feedback-retry', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-feedback-retry', sequence: 3, payload: {planId: 'plan-feedback-retry', status: 'awaiting_approval', revision: 2,
        contentDigest: '1'.repeat(64), markdown: '# Feedback retry plan', workspaceDigest: '2'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    client.emit({version: 0, type: 'run.completed', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-feedback-retry', sequence: 4, payload: {stopReason: 'completed', modelTurns: 1, toolCalls: 1}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Feedback retry plan'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 批准并执行（后续 Tool 正常逐项询问）'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 带反馈继续规划'));
    view.stdin.write('\r');
    view.stdin.write('preserve this feedback');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('preserve this feedback'));
    view.stdin.write('\r');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('继续规划 plan-feedback-retry'));
    client.emit({version: 0, type: 'protocol.error', requestId: 'tui-plan-review-1', sequence: 5,
      payload: {code: 'STALE_PLAN_REVIEW', message: 'unsafe detail'}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('preserve this feedback')
      && (view.lastFrame() ?? '').includes('请输入计划反馈'));
    expect(view.lastFrame()).not.toContain('继续规划 plan-feedback-retry');
    expect(view.lastFrame()).not.toContain('连接已关闭');
    view.unmount();
  });

  it('Escape cancels feedback input and returns to all four review choices', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    view.stdin.write('plan cancel task'); view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => client.prompts.length === 1);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-review', sequence: 2, payload: {}});
    client.emit({version: 0, type: 'plan.review.requested', requestId: 'tui-2', sessionId: 'session-1',
      runId: 'run-review', sequence: 3, payload: {planId: 'plan-cancel', status: 'awaiting_approval', revision: 2,
        contentDigest: '2'.repeat(64), markdown: '# Cancel feedback', workspaceDigest: '3'.repeat(64),
        originalPermissionMode: 'default', suggestedContextPolicy: 'keep'}});
    await waitForFrame(() => (view.lastFrame() ?? '').includes('Cancel feedback'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 批准并执行（后续 Tool 正常逐项询问）'));
    view.stdin.write(String.fromCharCode(27) + '[B');
    await waitForFrame(() => (view.lastFrame() ?? '').includes('❯ 带反馈继续规划'));
    view.stdin.write(String.fromCharCode(13));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('请输入计划反馈'));
    view.stdin.write('discard me'); view.stdin.write(String.fromCharCode(27));
    await waitForFrame(() => (view.lastFrame() ?? '').includes('批准并自动执行'));
    expect(view.lastFrame()).toContain('拒绝并退出');
    expect(client.planReviewResolutions).toEqual([]);
    view.unmount();
  });

});

class FakeAgentClient implements AgentClient {
  readonly prompts: string[] = [];
  readonly planTasks: string[] = [];
  readonly planExecutions: string[] = [];
  readonly planFeedback: string[] = [];
  readonly planReviewResolutions: string[] = [];
  readonly questionAnswers: string[] = [];
  readonly checkpointCommands: string[] = [];
  readonly sessionCommands: string[] = [];
  readonly providerControls: string[] = [];
  readonly providerLogins: ProviderLoginRequest[] = [];
  providerLoginResult: ProviderLoginResult = {status: 'succeeded', exitCode: 0};
  rejectPlanStart = false;
  rejectPlanExecution = false;
  rejectPlanReviewResolution = false;
  readonly fileSuggestions: string[] = [];
  readonly taskCommands: string[] = [];
  readonly skillInvocations: string[] = [];
  readonly approvals: string[] = [];
  initializeCalls = 0;
  terminateCalls = 0;
  shutdownCalls = 0;
  cancelRunCalls = 0;
  readonly #eventListeners = new Set<(event: ProtocolEvent) => void>();
  readonly #runCommandResults = new Set<string>();
  readonly #failureListeners = new Set<(message: string) => void>();
  readonly #exitListeners = new Set<() => void>();

  public onEvent(listener: (event: ProtocolEvent) => void): () => void {
    this.#eventListeners.add(listener);
    return () => this.#eventListeners.delete(listener);
  }

  public onFailure(listener: (message: string) => void): () => void {
    this.#failureListeners.add(listener);
    return () => this.#failureListeners.delete(listener);
  }

  public onExit(listener: () => void): () => void {
    this.#exitListeners.add(listener);
    return () => this.#exitListeners.delete(listener);
  }

  public initialize(): string {
    this.initializeCalls++;
    return 'tui-1';
  }

  public startRun(prompt: string): string {
    this.prompts.push(prompt);
    return `tui-${this.prompts.length + 1}`;
  }

  public startPlan(task: string): string {
    if (this.rejectPlanStart) throw new Error('rejected');
    this.planTasks.push(task);
    return `tui-plan-${this.planTasks.length}`;
  }

  public resolvePlanReview(input: {
    readonly planId: string; readonly revision: number; readonly contentDigest: string;
    readonly workspaceDigest: string;
    readonly decision: 'APPROVE_AUTO' | 'APPROVE_USER' | 'CONTINUE_PLANNING' | 'REJECT';
    readonly contextPolicy: 'KEEP' | 'CLEAR'; readonly feedback: string;
  }): string {
    if (this.rejectPlanReviewResolution) throw new Error('rejected');
    this.planReviewResolutions.push(`${input.planId}:${input.revision}:${input.contentDigest}:${input.workspaceDigest}:${input.decision}:${input.contextPolicy}:${input.feedback}`);
    return `tui-plan-review-${this.planReviewResolutions.length}`;
  }

  public startPlanExecution(planId: string, workspaceDigest: string): string {
    if (this.rejectPlanExecution) throw new Error('rejected');
    this.planExecutions.push(`${planId}:${workspaceDigest}`);
    return `tui-plan-execute-${this.planExecutions.length}`;
  }

  public returnPlanFeedback(planId: string, revision: number, contentDigest: string): string {
    this.planFeedback.push(`${planId}:${revision}:${contentDigest}`);
    return `tui-plan-feedback-${this.planFeedback.length}`;
  }

  public resolveQuestion(callId: string, optionId: string): string {
    this.questionAnswers.push(`${callId}:${optionId}`);
    return `tui-question-${this.questionAnswers.length}`;
  }

  public invokeSkill(name: string, arguments_: string): string {
    this.skillInvocations.push(`${name}:${arguments_}`);
    return `tui-skill-${this.skillInvocations.length}`;
  }

  public cancelRun(): string {
    this.cancelRunCalls++;
    return 'tui-3';
  }

  public resolveApproval(
    approvalId: string,
    decision: 'allow_once' | 'allow_session' | 'deny',
  ): string {
    this.approvals.push(`${approvalId}:${decision}`);
    return 'tui-4';
  }

  public listCheckpoints(): string {
    this.checkpointCommands.push('list');
    return 'tui-checkpoint-list';
  }

  public checkpointDiff(checkpointId: string): string {
    this.checkpointCommands.push(`diff:${checkpointId}`);
    return 'tui-checkpoint-diff';
  }

  public undoCheckpoint(checkpointId: string, confirmed: boolean): string {
    this.checkpointCommands.push(`undo:${checkpointId}:${confirmed}`);
    return 'tui-checkpoint-undo';
  }

  public waitTask(taskId: string, timeoutMillis: number): string {
    this.taskCommands.push(`wait:${taskId}:${timeoutMillis}`); return 'tui-task-wait';
  }
  public cancelTask(taskId: string): string {
    this.taskCommands.push(`cancel:${taskId}`); return 'tui-task-cancel';
  }
  public keepTaskWorktree(taskId: string): string {
    this.taskCommands.push(`keep:${taskId}`); return 'tui-task-keep';
  }
  public removeTaskWorktree(taskId: string): string {
    this.taskCommands.push(`remove:${taskId}`); return 'tui-task-remove';
  }

  public sessionCommand(commandId: string, intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume' | 'plan-status' | 'plan' | 'plan-approve' | 'plan-reject' | 'plan-step-begin' | 'plan-step-complete' | 'plan-execute', arguments_: Readonly<Record<string, unknown>>): string {
    this.sessionCommands.push(`${commandId}:${intent}:${JSON.stringify(arguments_)}`);
    return 'tui-session-command';
  }

  public providerControl(controlId: string, intent: 'providers.configure' | 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove', arguments_: Readonly<Record<string, unknown>>): string {
    this.providerControls.push(`${controlId}:${intent}:${JSON.stringify(arguments_)}`);
    return 'tui-provider-control';
  }
  public async providerLogin(request: ProviderLoginRequest): Promise<ProviderLoginResult> {
    this.providerLogins.push(request);
    return await Promise.resolve(this.providerLoginResult);
  }
  public cancelProviderLogin(): void {
  }
  public suggestFiles(query: string): string {
    this.fileSuggestions.push(query);
    return `file-${this.fileSuggestions.length}`;
  }

  public async shutdown(): Promise<void> {
    this.shutdownCalls++;
    return await Promise.resolve();
  }

  public terminate(): void {
    this.terminateCalls++;
  }

  public emit(event: ProtocolEvent): void {
    if (event.type === 'run.command.result') {
      this.#runCommandResults.add(event.requestId);
    } else if (event.type === 'run.started' && !this.#runCommandResults.has(event.requestId)) {
      const commandType = event.requestId.startsWith('tui-plan-review-')
        ? 'plan.review.resolve'
        : event.requestId.startsWith('tui-plan-')
          ? 'plan.start'
          : event.requestId.startsWith('tui-skill-') ? 'skill.invoke' : 'run.start';
      const accepted: ProtocolEvent = {
        version: 0,
        type: 'run.command.result',
        requestId: event.requestId,
        ...(event.sessionId === undefined ? {} : {sessionId: event.sessionId}),
        sequence: event.sequence,
        payload: {commandType, disposition: 'accepted', code: 'ACCEPTED'},
      };
      this.#runCommandResults.add(event.requestId);
      for (const listener of this.#eventListeners) listener(accepted);
    }
    for (const listener of this.#eventListeners) {
      listener(event);
    }
  }

  public emitFailure(message: string): void {
    for (const listener of this.#failureListeners) {
      listener(message);
    }
  }

  public emitExit(): void {
    for (const listener of this.#exitListeners) {
      listener();
    }
  }
}

async function waitForFrame(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error('等待 Ink 输入投影超时');
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
