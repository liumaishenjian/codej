import http from 'node:http';
import assert from 'node:assert/strict';

/** 独立 HTTP 脚本：read_file → 根据真正 Tool Result 交付 → 下一轮正文。不是私有 Worker Fake。 */
export async function startPiAuthSse(provider: string, marker: string, key: string) {
  let round = 0;
  const errors: string[] = [];
  const requests: {round: number; toolResultObserved: boolean}[] = [];
  const finalText = 'Verified ' + marker;
  const continuationText = 'Second turn remains usable.';
  const server = http.createServer(async (req, res) => {
    try {
      const chunks: Buffer[] = [];
      let bytes = 0;
      for await (const chunk of req) {
        bytes += chunk.length;
        assert.ok(bytes <= 2 * 1024 * 1024, 'REQUEST_LIMIT');
        chunks.push(chunk);
      }
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      ++round;
      assert.ok(round >= 1 && round <= 3, 'EXTRA_HTTP_TURN');
      assert.equal(req.method, 'POST', 'METHOD');
      // Boolean assertions keep even synthetic material out of failure messages.
      assert.ok(req.headers.authorization === 'Bearer ' + key, 'AUTH_HEADER');
      assert.ok(body.stream === true, 'SSE_REQUIRED');
      const responses = provider === 'openai';
      assert.equal(req.url, responses ? '/v1/responses' : '/v1/chat/completions', 'SDK_ENDPOINT');
      assert.ok(JSON.stringify(body.tools).includes('read_file'), 'REAL_TOOL_CATALOG');
      let observed = false;
      if (round === 1) assert.ok(!JSON.stringify(body).includes(marker), 'FILE_MARKER_NOT_IN_PROMPT');
      if (round >= 2) {
        const result = responses
          ? body.input.find((item: Record<string, unknown>) => item.type === 'function_call_output' && item.call_id === 'call_ui_read')?.output
          : body.messages.find((item: Record<string, unknown>) => item.role === 'tool' && item.tool_call_id === 'call_ui_read')?.content;
        observed = typeof result === 'string' && result.includes(marker);
        assert.ok(observed, 'REAL_READ_RESULT_REQUIRED');
      }
      requests.push({round, toolResultObserved: observed});
      const text = round === 2 ? finalText : continuationText;
      const sse = responses ? responsesSse(round, text) : completionsSse(round, text);
      res.writeHead(200, {'Content-Type': 'text/event-stream'});
      res.end(sse);
    } catch {
      // Never forward request/header/error payloads; the stage is sufficient to locate the failed assertion.
      errors.push('HTTP_SCRIPT_ROUND_' + round);
      res.writeHead(400); res.end();
    }
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address !== 'string');
  return {origin: `http://127.0.0.1:${address.port}`, errors, requests, finalText, continuationText,
    close: () => new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve()); server.closeAllConnections();
    })};
}
function completionsSse(round: number, text: string): string {
  const tools = round === 1;
  const delta = tools ? {role: 'assistant', tool_calls: [{index: 0, id: 'call_ui_read', type: 'function',
    function: {name: 'read_file', arguments: JSON.stringify({path: 'fixture.txt'})}}]} : {role: 'assistant', content: text};
  return `data: ${JSON.stringify({id: 'completion_ui_' + round, choices: [{index: 0, delta}]})}\n\n`
    + `data: ${JSON.stringify({id: 'completion_ui_' + round, choices: [{index: 0, delta: {}, finish_reason: tools ? 'tool_calls' : 'stop'}],
      usage: {prompt_tokens: 20, completion_tokens: 10, total_tokens: 30}})}\n\ndata: [DONE]\n\n`;
}
function responsesSse(round: number, text: string): string {
  let output = '';
  const event = (type: string, fields: Record<string, unknown>) => {output += `event: ${type}\ndata: ${JSON.stringify({...fields, type})}\n\n`;};
  const id = 'resp_ui_' + round;
  event('response.created', {response: {id, status: 'in_progress'}});
  let completed: Record<string, unknown>;
  if (round === 1) {
    const item = {type: 'function_call', id: 'fc_ui_read', call_id: 'call_ui_read', name: 'read_file', arguments: ''};
    event('response.output_item.added', {output_index: 0, item});
    const args = JSON.stringify({path: 'fixture.txt'});
    event('response.function_call_arguments.delta', {output_index: 0, item_id: item.id, delta: args});
    completed = {...item, arguments: args};
  } else {
    const message = 'msg_ui_' + round;
    event('response.output_item.added', {output_index: 0, item: {type: 'message', id: message, role: 'assistant', content: []}});
    event('response.content_part.added', {output_index: 0, content_index: 0, item_id: message,
      part: {type: 'output_text', text: '', annotations: []}});
    event('response.output_text.delta', {output_index: 0, content_index: 0, item_id: message, delta: text});
    completed = {type: 'message', id: message, role: 'assistant', content: [{type: 'output_text', text, annotations: []}]};
  }
  event('response.output_item.done', {output_index: 0, item: completed});
  event('response.completed', {response: {id, status: 'completed', output: [completed],
    usage: {input_tokens: 20, output_tokens: 10, total_tokens: 30}}});
  return output;
}
