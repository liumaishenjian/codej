import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {AssistantMarkdown} from '../src/assistant-markdown.js';

describe('AssistantMarkdown', () => {
  it('把标题、列表和行内代码渲染成终端层级', () => {
    const view = render(
      <AssistantMarkdown text={'## 结果\n\n- 第一项\n- 使用 `AgentRuntime`'} />,
    );
    const frame = view.lastFrame();

    expect(frame).toContain('◆ 结果');
    expect(frame?.replace(/\x1b\[[0-9;]*m/gu, '')).toContain('• 第一项');
    expect(frame).toContain('AgentRuntime');
    expect(frame).not.toContain('##');
  });

  it('把代码块放入独立区域并容忍未闭合的流式片段', () => {
    const view = render(
      <AssistantMarkdown text={'```java\nclass Demo {}\n```\n\n**还在流式'} />,
    );
    const frame = view.lastFrame();

    expect(frame).toContain('java');
    expect(frame).toContain('class Demo {}');
    expect(frame).toContain('还在流式');
  });

  it('未闭合围栏在流式过程中保持代码区域而不是反复拆成段落', () => {
    const view = render(<AssistantMarkdown text={'```ts\nconst ready = true;'} />);
    const frame = view.lastFrame() ?? '';
    expect(frame).toContain('ts');
    expect(frame).toContain('const ready = true;');
    expect(frame).toContain('╭');
  });
});
