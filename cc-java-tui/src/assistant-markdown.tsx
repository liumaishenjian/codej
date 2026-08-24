import {useMemo} from 'react';
import type {ReactNode} from 'react';
import {Box, Text} from 'ink';
import {marked} from 'marked';
import type {Token, Tokens} from 'marked';
import {stabilizeStreamingMarkdown} from './interaction.js';

export interface AssistantMarkdownProps {
  readonly text: string;
}

/**
 * 把流式 Assistant Markdown 渲染为 Ink 组件。
 *
 * <p>Marked 只负责成熟的 Markdown 解析，终端布局和样式由本项目控制。
 * 未闭合的流式片段或解析异常会退回纯文本，展示故障不会终止 Agent Run。</p>
 */
export function AssistantMarkdown({text}: AssistantMarkdownProps) {
  const tokens = useMemo(() => {
    try {
      return marked.lexer(stabilizeStreamingMarkdown(text), {gfm: true, breaks: true});
    } catch {
      return undefined;
    }
  }, [text]);

  if (text.length === 0) {
    return null;
  }
  if (tokens === undefined) {
    return <Text>{text}</Text>;
  }
  return (
    <Box flexDirection="column">
      {tokens.map((token, index) => renderBlock(token, `block-${index}`, index === 0))}
    </Box>
  );
}

function renderBlock(token: Token, key: string, first = false): ReactNode {
  switch (token.type) {
    case 'space':
    case 'def':
      return null;
    case 'heading': {
      const heading = token as Tokens.Heading;
      return (
        <Box key={key} marginTop={!first && heading.depth <= 2 ? 1 : 0}>
          <Text bold color={heading.depth === 1 ? 'cyan' : 'white'}>
            {heading.depth <= 2 ? '◆ ' : '• '}
            {renderInline(heading.tokens, key)}
          </Text>
        </Box>
      );
    }
    case 'paragraph': {
      const paragraph = token as Tokens.Paragraph;
      return <Text key={key}>{renderInline(paragraph.tokens, key)}</Text>;
    }
    case 'text': {
      const text = token as Tokens.Text;
      return <Text key={key}>{renderInline(text.tokens ?? [text], key)}</Text>;
    }
    case 'code': {
      const code = token as Tokens.Code;
      return (
        <Box
          key={key}
          flexDirection="column"
          borderStyle="round"
          borderColor="gray"
          paddingX={1}
          marginY={1}
        >
          {code.lang === undefined ? null : <Text dimColor>{code.lang}</Text>}
          <Text>{code.text}</Text>
        </Box>
      );
    }
    case 'blockquote': {
      const quote = token as Tokens.Blockquote;
      return (
        <Box key={key} borderStyle="single" borderLeft borderRight={false}
          borderTop={false} borderBottom={false} borderColor="gray" paddingLeft={1}>
          <Box flexDirection="column">
            {quote.tokens.map((child, index) => (
              renderBlock(child, `${key}-${index}`, index === 0)
            ))}
          </Box>
        </Box>
      );
    }
    case 'list':
      return renderList(token as Tokens.List, key);
    case 'hr':
      return <Text key={key} dimColor>────────────────────────</Text>;
    case 'table':
      return renderTable(token as Tokens.Table, key);
    case 'html':
      return <Text key={key}>{(token as Tokens.HTML).text}</Text>;
    default:
      return <Text key={key}>{token.raw}</Text>;
  }
}

function renderList(list: Tokens.List, key: string): ReactNode {
  return (
    <Box key={key} flexDirection="column" marginY={list.loose ? 1 : 0}>
      {list.items.map((item, index) => {
        const marker = list.ordered
          ? `${(typeof list.start === 'number' ? list.start : 1) + index}.`
          : item.task ? (item.checked ? '☑' : '☐') : '•';
        return (
          <Box key={`${key}-${index}`} flexDirection="row">
            <Text color="cyan">{marker} </Text>
            <Text>{renderListItem(item, `${key}-${index}`)}</Text>
          </Box>
        );
      })}
    </Box>
  );
}

function renderListItem(item: Tokens.ListItem, key: string): ReactNode {
  return item.tokens.flatMap((token, index) => {
    if (token.type === 'paragraph') {
      return renderInline((token as Tokens.Paragraph).tokens, `${key}-${index}`);
    }
    if (token.type === 'text') {
      const text = token as Tokens.Text;
      return renderInline(text.tokens ?? [text], `${key}-${index}`);
    }
    return token.raw;
  });
}

function renderTable(table: Tokens.Table, key: string): ReactNode {
  const rows = [table.header, ...table.rows];
  return (
    <Box key={key} flexDirection="column" marginY={1}>
      {rows.map((row, rowIndex) => (
        <Text key={`${key}-${rowIndex}`} bold={rowIndex === 0}>
          {row.map((cell, cellIndex) => (
            <Text key={`${key}-${rowIndex}-${cellIndex}`}>
              {cellIndex === 0 ? '' : ' │ '}
              {renderInline(cell.tokens, `${key}-${rowIndex}-${cellIndex}`)}
            </Text>
          ))}
        </Text>
      ))}
    </Box>
  );
}

function renderInline(tokens: readonly Token[], key: string): ReactNode[] {
  return tokens.map((token, index) => {
    const childKey = `${key}-inline-${index}`;
    switch (token.type) {
      case 'strong':
        return <Text key={childKey} bold>
          {renderInline((token as Tokens.Strong).tokens, childKey)}
        </Text>;
      case 'em':
        return <Text key={childKey} italic>
          {renderInline((token as Tokens.Em).tokens, childKey)}
        </Text>;
      case 'del':
        return <Text key={childKey} strikethrough>
          {renderInline((token as Tokens.Del).tokens, childKey)}
        </Text>;
      case 'codespan':
        return <Text key={childKey} color="yellow">
          {(token as Tokens.Codespan).text}
        </Text>;
      case 'link': {
        const link = token as Tokens.Link;
        return <Text key={childKey} color="cyan" underline>
          {renderInline(link.tokens, childKey)}
        </Text>;
      }
      case 'image':
        return <Text key={childKey} color="cyan">[图片：{(token as Tokens.Image).text}]</Text>;
      case 'br':
        return '\n';
      case 'escape':
      case 'text': {
        const text = token as Tokens.Text;
        return text.tokens === undefined
          ? text.text
          : <Text key={childKey}>{renderInline(text.tokens, childKey)}</Text>;
      }
      default:
        return token.raw;
    }
  });
}
