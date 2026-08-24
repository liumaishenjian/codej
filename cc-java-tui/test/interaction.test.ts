import {describe, expect, it} from 'vitest';
import {createComposerState, reduceComposer} from '../src/input-editor.js';
import {
  activitySpinnerFrame,
  classifyNotice,
  completionRegionRows,
  createPlanFeedbackDraft,
  editPlanFeedback,
  hasTrailingNewlineEscape,
  isInsertNewlineKey,
  planFeedbackCursorParts,
  shouldAcceptCompletionOnEnter,
  stabilizeStreamingMarkdown,
  visibleToolOutputWindow,
} from '../src/interaction.js';

const layout = {width: 80, height: 4};

describe('interaction helpers', () => {
  it('完整命令与已选中 mention 的 Enter 提交，未完成项才补全', () => {
    const help = reduceComposer(createComposerState(), {
      type: 'SetCompletions', candidates: ['/help'],
    }, layout).state;
    const typed = {...help, text: '/help', cursorGrapheme: 5};
    expect(shouldAcceptCompletionOnEnter(typed)).toBe(false);

    const partial = reduceComposer(
      reduceComposer(createComposerState(), {type: 'InsertText', text: '/he'}, layout).state,
      {type: 'SetCompletions', candidates: ['/help']},
      layout,
    ).state;
    expect(shouldAcceptCompletionOnEnter(partial)).toBe(true);

    const plan = reduceComposer(
      reduceComposer(createComposerState(), {type: 'InsertText', text: '/plan'}, layout).state,
      {type: 'SetCompletions', candidates: ['/plan-status']},
      layout,
    ).state;
    expect(shouldAcceptCompletionOnEnter(plan)).toBe(false);
  });

  it('按文案分级通知色调', () => {
    expect(classifyNotice('/doctor 未执行：已延期')).toBe('error');
    expect(classifyNotice('Skill /demo 已完成')).toBe('success');
    expect(classifyNotice('补充消息已排队（1/100）')).toBe('warning');
    expect(classifyNotice('Credential profiles')).toBe('info');
  });

  it('流式 Markdown 虚拟闭合未结束围栏', () => {
    expect(stabilizeStreamingMarkdown('```java\nclass A {}')).toBe('```java\nclass A {}\n```');
    expect(stabilizeStreamingMarkdown('```java\nclass A {}\n```')).toBe('```java\nclass A {}\n```');
  });

  it('Tool 详情只保留末尾有界窗口', () => {
    const lines = Array.from({length: 20}, (_, index) => `line-${index}`);
    const window = visibleToolOutputWindow(lines, 12);
    expect(window.total).toBe(20);
    expect(window.omitted).toBe(8);
    expect(window.lines).toEqual(lines.slice(8));
  });

  it('矮视口仍给补全区最小高度', () => {
    expect(completionRegionRows(8, 8, 6)).toBe(3);
    expect(completionRegionRows(0, 8, 6)).toBe(0);
  });

  it('换行快捷键和行尾反斜杠降级', () => {
    expect(isInsertNewlineKey({shift: true, return: true}, '')).toBe(true);
    expect(isInsertNewlineKey({ctrl: true}, 'j')).toBe(true);
    expect(isInsertNewlineKey({return: true}, '')).toBe(false);
    expect(hasTrailingNewlineEscape('foo \\')).toBe(true);
    expect(hasTrailingNewlineEscape('foo\\')).toBe(false);
    expect(hasTrailingNewlineEscape('C:\\Users\\foo\\')).toBe(false);
    expect(hasTrailingNewlineEscape('foo\\\\')).toBe(false);
  });

  it('计划反馈支持左右移动光标', () => {
    let draft = createPlanFeedbackDraft('中文反馈');
    draft = editPlanFeedback(draft, {type: 'home'});
    expect(draft.cursor).toBe(0);
    draft = editPlanFeedback(draft, {type: 'insert', text: 'x'});
    expect(draft.text).toBe('x中文反馈');
    expect(draft.cursor).toBe(1);
    draft = editPlanFeedback(draft, {type: 'right'});
    expect(draft.cursor).toBe(2);
  });

  it('计划反馈渲染光标与编辑使用同一 grapheme', () => {
    const family = '\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}';
    const draft = editPlanFeedback(createPlanFeedbackDraft(family), {type: 'home'});
    expect(planFeedbackCursorParts(draft)).toEqual({before: '', at: family, after: ''});
  });

  it('等待指示按 tick 轮换且不抛出', () => {
    expect(activitySpinnerFrame(0)).toBe('⠋');
    expect(activitySpinnerFrame(8)).toBe('⠋');
  });
});
