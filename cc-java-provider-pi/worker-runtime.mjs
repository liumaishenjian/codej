import {FrameSequence, JsonLineDecoder, ProtocolError, encodeFrame} from './worker-protocol.mjs';

import {ModelOperationError} from './model-operation.mjs';

const outputLimit = 16 * 1024 * 1024;
const ownKeys = (value, keys) => Object.keys(value).sort().join(',') === [...keys].sort().join(',');

/**
 * ADR-100：驱动catalog、auth.login或单回合model；工厂只由受信入口注入。
 * catalog为受信构造器注入的函数，不从请求加载模块。EOF/取消/超时封闭发布入口，迟到结果丢弃。
 * 借用input/output，结束时解除订阅；进程入口仍须在返回后退出并执行宿主侧进程清理。
 */
export function runWorker({input, output, catalog, authenticate, model, timeoutMillis = 10000}) {
  if ((model !== undefined && typeof model !== 'function') || typeof catalog !== 'function' || (authenticate !== undefined && typeof authenticate !== 'function') || !Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1 || timeoutMillis > 300000) {
    return Promise.resolve(2);
  }
  return new Promise(resolve => {
    const cancellation = new AbortController();
    let operationId;
    let inputSequence;
    let nextOutput = 0;
    let outputBytes = 0;
    let inputBytes = 0;
    let authentication = false;
    let modeling = false;
    let modelOperation, modelClosing;
    let providerFrame = false;
    let inputFrames = 0, partialLength = 0;
    let lineLengths = [], lineCursor = 0;
    let authOperation;
    let authClosed = false;
    let authFinished = false;
    const cancelledPrompts = new Set();
    let closed = false;
    let settling = false;
    let writes = Promise.resolve();
    let flushTimer;
    const pendingBuffers = new Set();
    const deadline = setTimeout(() => fail('TIMEOUT', 1), timeoutMillis);

    // 未知启动暂容1MiB，但尚不执行auth；Java认证入口仍在物理层执行更严格的32KiB。
    // 此处按原始LF长度而非JSON重编码收紧。
    // 每个chunk先扫描，确保start后的超大完整行/半行不能借同chunk绕过auth限制。
    const reader = new JsonLineDecoder({onFrame: accept});
    function checkInputBudget() {
      if (!inputSequence) return;
      if (!modeling && (inputBytes > 131072 || inputFrames > 512
          || partialLength >= 32768 || lineLengths.some(n => n > 32768))) throw new ProtocolError('PROTOCOL_LIMIT');
      if (authentication && inputBytes + outputBytes > 131072) throw new ProtocolError('PROTOCOL_LIMIT');
    }
    function closeModel() {
      if (!modelOperation) return Promise.resolve();
      modelClosing ??= Promise.resolve().then(() => modelOperation.close());
      return modelClosing;
    }
    function closeAuth() {
      if (!authOperation || authClosed) return;
      authOperation.close(); authClosed = true;
    }
    function finish(code) {
      if (closed) return;
      closed = true;
      cancellation.abort();
      try {closeAuth();} catch {code = 2;}
      clearTimeout(deadline); clearTimeout(flushTimer);
      reader.close();
      input.off('data', onData); input.off('end', onEnd); input.off('error', onInputError);
      output.off('error', onOutputError);
      input.pause();
      for (const bytes of pendingBuffers) bytes.fill(0);
      pendingBuffers.clear();
      lineLengths = []; lineCursor = 0; partialLength = 0;
      cancelledPrompts.clear();
      // 不合作的异步清理不能挂住进程；超时仍是失败，绝不确认成功。
      let cleanupTimer;
      Promise.race([closeModel(), new Promise((_, reject) => {
        cleanupTimer = setTimeout(() => reject(new ProtocolError()), 250);
      })]).then(() => resolve(code), () => resolve(2)).finally(() => clearTimeout(cleanupTimer));
    }
    function emit(type, payload, terminal = false) {
      if (closed || (settling && !terminal)) return Promise.reject(new ProtocolError('PROTOCOL_CLOSED'));
      let bytes;
      try {
        bytes = encodeFrame({version: 1, operationId, sequence: nextOutput, type, payload}, authentication ? 32768 : 1024 * 1024);
        if (nextOutput >= (authentication ? 512 : 65536) || outputBytes + bytes.length > outputLimit || (authentication && outputBytes + inputBytes + bytes.length > 131072)) {bytes.fill(0); throw new ProtocolError('PROTOCOL_LIMIT');}
        outputBytes += bytes.length;
        nextOutput++;
      } catch (error) {return Promise.reject(error);}
      pendingBuffers.add(bytes);
      writes = writes.then(() => new Promise((done, reject) => {
        if (closed) {bytes.fill(0); pendingBuffers.delete(bytes); reject(new ProtocolError('PROTOCOL_CLOSED')); return;}
        output.write(bytes, error => {
          bytes.fill(0); pendingBuffers.delete(bytes);
          if (error) reject(new ProtocolError()); else done();
        });
      }));
      return writes;
    }
    function fail(code, exitCode, modelError) {
      if (closed || settling) return;
      settling = true; cancellation.abort();
      clearTimeout(deadline);
      // 对端不再读取时，不能为了报告错误永久等待pipe drain；宿主将观察非零退出/不完整终态。
      flushTimer = setTimeout(() => finish(exitCode), 250);
      if (!operationId) {finish(exitCode); return;}
      writes.catch(() => {}).then(() => {
        writes = Promise.resolve();
        return (modelError ? emit('model.error', modelError, true) : Promise.resolve())
          .then(() => emit('operation.failed', {code}, true));
      }).then(() => finish(exitCode), () => finish(exitCode));
    }
    function rejectOperation(error, fallback = 'WORKER_FAILED') {
      if (modeling && error instanceof ModelOperationError) {
        const codes = ['AUTH', 'CANCELLED', 'TIMEOUT', 'RATE_LIMIT', 'TRANSIENT',
          'CONTEXT_OVERFLOW', 'INCOMPLETE', 'PROTOCOL', 'PERMANENT', 'UNSUPPORTED', 'LIMIT'];
        const framed = providerFrame || error.providerFrame === true;
        const safe = {code: codes.includes(error.code) ? error.code : 'PERMANENT',
          retryable: !framed && error.retryable === true, providerFrame: framed};
        if (Number.isSafeInteger(error.retryAfterMs) && error.retryAfterMs >= 0) safe.retryAfterMs = error.retryAfterMs;
        fail('WORKER_FAILED', 1, safe);
      } else fail(error instanceof ProtocolError ? error.message : fallback, error instanceof ProtocolError ? 2 : 1);
    }
    async function executeOperation(payload) {
      let completing = false;
      try {
        await emit('operation.ready', {piVersion: '0.85.1', operations: ['catalog', ...(authenticate ? ['auth.login'] : []), ...(model ? ['model'] : [])]});
        if (closed || settling) return;
        let result;
        if (authentication) {
          authOperation = await authenticate({providerId: payload.providerId, authType: payload.authType,
            signal: cancellation.signal, send(type, value) {
              if (!['credential.request', 'auth.prompt', 'auth.prompt_cancelled', 'auth.url'].includes(type)) return Promise.reject(new ProtocolError());
              if (type === 'auth.prompt_cancelled') {
                if (!value || !ownKeys(value, ['promptId']) || !Number.isSafeInteger(value.promptId)
                    || value.promptId < 1 || value.promptId > 16) return Promise.reject(new ProtocolError());
                cancelledPrompts.add(value.promptId);
              }
              return emit(type, value);
            }});
          if (closed || settling) {closeAuth(); return;}
          result = await authOperation.run();
          closeAuth();
          if (!result || !ownKeys(result, ['providerId', 'authType', 'status'])
            || result.providerId !== payload.providerId || result.authType !== payload.authType || result.status !== 'stored') throw new ProtocolError();
          authFinished = true;
        } else if (modeling) {
          modelOperation = await model({providerId: payload.providerId, modelId: payload.modelId,
            request: payload.request, signal: cancellation.signal, send(type, value) {
              if (!['credential.request', 'model.frame', 'model.delta'].includes(type)) {
                const error = new ProtocolError(); rejectOperation(error); return Promise.reject(error);
              }
              if (type === 'model.frame') providerFrame = true;
              return emit(type, value).catch(error => {rejectOperation(error); throw error;});
            }});
          if (closed || settling) {await closeModel(); return;}
          result = await modelOperation.run();
          await closeModel();
        } else result = await catalog({signal: cancellation.signal});
        if (closed || settling) return;
        await emit(authentication ? 'auth.result' : modeling ? 'model.result' : 'catalog.result', result);
        if (closed || settling) return;
        settling = true; completing = true;
        flushTimer = setTimeout(() => finish(2), 250);
        await emit('operation.completed', {status: 'completed'}, true);
        finish(0);
      } catch (error) {
        if (completing && !closed) {finish(2); return;}
        if (closed || settling) return;
        rejectOperation(error);
      }
    }
    function accept(frame) {
      if (closed || settling) throw new ProtocolError('PROTOCOL_CLOSED');
      const rawLength = lineLengths[lineCursor++];
      if (!Number.isSafeInteger(rawLength)) throw new ProtocolError();
      if (!inputSequence) {
        const guard = new FrameSequence(frame?.operationId);
        guard.accept(frame);
        inputSequence = guard; operationId = frame.operationId;
        if (frame.type !== 'operation.start' || typeof frame.payload.operation !== 'string') throw new ProtocolError();
        if (frame.payload.operation === 'auth.login' && authenticate) {
          if (!ownKeys(frame.payload, ['operation', 'providerId', 'authType'])) throw new ProtocolError();
          const {providerId, authType} = frame.payload;
          if (!(providerId === 'openai-codex' ? authType === 'oauth'
            : ['openai', 'deepseek', 'qwen-token-plan-cn'].includes(providerId) && authType === 'api_key')) throw new ProtocolError();
          authentication = true;
          if (inputBytes + outputBytes > 131072) throw new ProtocolError('PROTOCOL_LIMIT');
        } else if (frame.payload.operation === 'model' && model) {
          if (!ownKeys(frame.payload, ['operation', 'providerId', 'modelId', 'request'])) throw new ProtocolError();
          modeling = true;
        } else {
          if (!ownKeys(frame.payload, ['operation'])) throw new ProtocolError();
          if (frame.payload.operation !== 'catalog') {fail('OPERATION_UNSUPPORTED', 2); return;}
        }
        checkInputBudget();
        void executeOperation(frame.payload);
      } else {
        inputSequence.accept(frame);
        if (frame.type === 'operation.cancel' && ownKeys(frame.payload, [])) fail('CANCELLED', 1);
        else if (modeling && modelOperation && !modelClosing && frame.type === 'credential.response') {
          // decoder只保留ProtocolError；业务封闭错误必须在穿过decoder前完成投影。
          try {modelOperation.accept(frame);} catch (error) {rejectOperation(error);}
        }
        else if (authentication && authOperation) {
          if (frame.type === 'auth.response') {
            const id = frame.payload.promptId;
            // helper已清理但结果帧仍在flush时，只消费先前取消提示的一次迟到输入；不打开新的输入路径。
            if (authFinished && cancelledPrompts.has(id)) {
              if (!ownKeys(frame.payload, ['promptId', 'value']) || typeof frame.payload.value !== 'string'
                  || !frame.payload.value.trim() || frame.payload.value.length > 16384
                  || !/^[\x20-\x7e]+$/.test(frame.payload.value)) throw new ProtocolError();
              cancelledPrompts.delete(id); return;
            }
            cancelledPrompts.delete(id);
          }
          authOperation.accept(frame);
        }
        else throw new ProtocolError();
      }
    }
    function onData(bytes) {
      try {
        if (!(bytes instanceof Uint8Array)) throw new ProtocolError();
        inputBytes += bytes.length;
        if (inputBytes > 32 * 1024 * 1024) throw new ProtocolError('PROTOCOL_LIMIT');
        lineLengths = []; lineCursor = 0;
        for (const byte of bytes) {
          partialLength++;
          if (partialLength > 1024 * 1024) throw new ProtocolError('PROTOCOL_LIMIT');
          if (byte === 10) {
            if (++inputFrames > 65536) throw new ProtocolError('PROTOCOL_LIMIT');
            lineLengths.push(partialLength); partialLength = 0;
          }
        }
        checkInputBudget();
        reader.push(bytes);
        lineLengths = []; lineCursor = 0;
      }
      catch (error) {
        if (error instanceof ModelOperationError && modeling) rejectOperation(error);
        else fail(error instanceof ProtocolError ? error.message : 'PROTOCOL_INVALID', 2);
      }
    }
    function onEnd() {
      try {reader.end();}
      catch (error) {fail(error instanceof ProtocolError ? error.message : 'PROTOCOL_INVALID', 2); return;}
      fail('CANCELLED', 1);
    }
    function onInputError() {fail('WORKER_IO_FAILED', 1);}
    function onOutputError() {finish(2);}
    input.on('data', onData); input.on('end', onEnd); input.on('error', onInputError);
    output.on('error', onOutputError);
  });
}
