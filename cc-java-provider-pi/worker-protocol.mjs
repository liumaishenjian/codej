const LINE_LIMIT = 1024 * 1024;
const TOTAL_LIMIT = 32 * 1024 * 1024;
const FRAME_LIMIT = 65536;
const DEPTH_LIMIT = 64;
const ERROR_CODES = new Set(['PROTOCOL_INVALID', 'PROTOCOL_LIMIT', 'PROTOCOL_CLOSED']);

/** 私有协议仅传播封闭错误码，不携带输入、底层异常或凭证。 */
export class ProtocolError extends Error {
  constructor(code = 'PROTOCOL_INVALID') {
    super(ERROR_CODES.has(code) ? code : 'PROTOCOL_INVALID');
    this.name = 'ProtocolError';
  }
}
const reject = (code = 'PROTOCOL_INVALID') => {throw new ProtocolError(code);};
const limit = (value, maximum) => {
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) reject('PROTOCOL_LIMIT');
  return value;
};
function unicode(value) {
  for (let i = 0; i < value.length; i++) {
    const unit = value.charCodeAt(i);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(++i);
      if (!(next >= 0xdc00 && next <= 0xdfff)) reject();
    } else if (unit >= 0xdc00 && unit <= 0xdfff) reject();
  }
  return value;
}

/**
 * 解析独立的严格JSON，拒绝重复键、非法Unicode、非有限数和超深结构。
 * 属性使用defineProperty写入，保留工具参数中合法的原型同名键而不修改对象原型。
 * 不改变业务参数，也不执行Schema转换；Tool校验仍由Java Pipeline负责。
 */
export function parseStrictJson(source) {
  if (typeof source !== 'string') reject();
  let cursor = 0;
  const whitespace = () => {
    while (cursor < source.length && /[\x20\t\r\n]/.test(source[cursor])) cursor++;
  };
  const string = () => {
    const start = cursor;
    if (source[cursor++] !== '"') reject();
    while (cursor < source.length) {
      const current = source[cursor++];
      if (current === '"') {
        try {return unicode(JSON.parse(source.slice(start, cursor)));}
        catch {reject();}
      }
      if (current === '\\') cursor++;
    }
    reject();
  };
  const value = depth => {
    whitespace();
    const current = source[cursor];
    if (current === '"') return string();
    if (current === '{' || current === '[') {
      if (depth >= DEPTH_LIMIT) reject('PROTOCOL_LIMIT');
      const object = current === '{';
      const end = object ? '}' : ']';
      const result = object ? {} : [];
      const keys = new Set();
      cursor++; whitespace();
      if (source[cursor] === end) {cursor++; return result;}
      while (cursor < source.length) {
        let key;
        if (object) {
          whitespace(); key = string();
          if (keys.has(key)) reject();
          keys.add(key); whitespace();
          if (source[cursor++] !== ':') reject();
        }
        const child = value(depth + 1);
        if (object) Object.defineProperty(result, key, {value: child, enumerable: true, configurable: true, writable: true});
        else result.push(child);
        whitespace();
        if (source[cursor] === end) {cursor++; return result;}
        if (source[cursor++] !== ',') reject();
      }
      reject();
    }
    for (const [literal, result] of [['true', true], ['false', false], ['null', null]]) {
      if (source.startsWith(literal, cursor)) {cursor += literal.length; return result;}
    }
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(source.slice(cursor));
    if (!match) reject();
    cursor += match[0].length;
    const number = Number(match[0]);
    if (!Number.isFinite(number) || (Number.isInteger(number) && !Number.isSafeInteger(number))) reject();
    return number;
  };
  try {
    const parsed = value(0); whitespace();
    if (cursor !== source.length) reject();
    return parsed;
  } catch (error) {
    if (error instanceof ProtocolError) throw error;
    reject();
  }
}

/**
 * 单操作的有界NDJSON解码器。onFrame必须同步消费或入队，不能在这里开启异步处理竞赛。
 * 单行上限包含LF；CRLF的CR由JSON空白语义处理。物理chunk可在UTF-8码点内部切分。
 * 消费/错误/关闭均擦除自有可变缓冲，但不承诺擦除JSON字符串或调用方拥有的输入副本。
 */
export class JsonLineDecoder {
  #buffer; #used = 0; #total = 0; #frames = 0; #closed = false;
  #onFrame; #maximumTotal; #maximumFrames;
  #decoder = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true});
  constructor({onFrame, maximumLineBytes = LINE_LIMIT, maximumTotalBytes = TOTAL_LIMIT, maximumFrames = FRAME_LIMIT}) {
    if (typeof onFrame !== 'function') reject();
    this.#buffer = Buffer.alloc(limit(maximumLineBytes, LINE_LIMIT));
    this.#maximumTotal = limit(maximumTotalBytes, TOTAL_LIMIT);
    this.#maximumFrames = limit(maximumFrames, FRAME_LIMIT);
    this.#onFrame = onFrame;
  }
  push(bytes) {
    if (this.#closed) reject('PROTOCOL_CLOSED');
    try {
      if (!(bytes instanceof Uint8Array)) reject();
      this.#total += bytes.byteLength;
      if (this.#total > this.#maximumTotal) reject('PROTOCOL_LIMIT');
      for (const byte of bytes) {
        if (byte === 10) {
          if (++this.#frames > this.#maximumFrames) reject('PROTOCOL_LIMIT');
          let parsed;
          try {parsed = parseStrictJson(this.#decoder.decode(this.#buffer.subarray(0, this.#used)));}
          finally {this.#buffer.fill(0, 0, this.#used); this.#used = 0;}
          const returned = this.#onFrame(parsed);
          if (returned instanceof Promise) {returned.catch(() => {}); reject();}
          if (this.#closed) reject('PROTOCOL_CLOSED');
        } else {
          if (this.#used >= this.#buffer.length - 1) reject('PROTOCOL_LIMIT');
          this.#buffer[this.#used++] = byte;
        }
      }
    } catch (error) {
      this.close();
      if (error instanceof ProtocolError) throw error;
      reject();
    }
  }
  /** EOF不是隐式行分隔符；不完整末行必须失败。空流由上层操作状态机判断是否缺终态。 */
  end() {
    if (this.#closed) reject('PROTOCOL_CLOSED');
    const incomplete = this.#used !== 0;
    this.close();
    if (incomplete) reject();
  }
  /** 幂等终止本实例；不能重置计数后在同一操作内复用预算。 */
  close() {
    this.#closed = true;
    this.#buffer.fill(0);
    this.#used = 0;
    this.#onFrame = undefined;
  }
}

/** 编码一个有界、以LF结束的帧；拒绝JSON.stringify会静默丢弃的值。返回缓冲由发送方擦除。 */
export function encodeFrame(value, maximumLineBytes = LINE_LIMIT) {
  limit(maximumLineBytes, LINE_LIMIT);
  let bytes;
  try {
    const source = JSON.stringify(value, (_key, item) => {
      if (typeof item === 'undefined' || typeof item === 'function' || typeof item === 'symbol' || typeof item === 'bigint') reject();
      if (typeof item === 'string') unicode(item);
      if (typeof item === 'number' && (!Number.isFinite(item) || (Number.isInteger(item) && !Number.isSafeInteger(item)))) reject();
      return item;
    });
    if (typeof source !== 'string') reject();
    if (Buffer.byteLength(source) + 1 > maximumLineBytes) reject('PROTOCOL_LIMIT');
    parseStrictJson(source);
    bytes = Buffer.from(source + '\n');
    return bytes;
  } catch (error) {
    bytes?.fill(0);
    if (error instanceof ProtocolError) throw error;
    reject();
  }
}

/** 私有通道单方向的序号护栏；版本、操作身份和唯一终态不能由迟到帧替换。 */
export class FrameSequence {
  #operationId; #next = 0; #closed = false;
  constructor(operationId) {
    if (typeof operationId !== 'string' || !/^[a-zA-Z0-9_-]{1,96}$/.test(operationId)) reject();
    this.#operationId = operationId;
  }
  accept(frame, {terminal = false} = {}) {
    try {
      if (this.#closed) reject('PROTOCOL_CLOSED');
      if (!frame || typeof frame !== 'object' || Array.isArray(frame)) reject();
      const keys = Object.keys(frame).sort();
      if (keys.join(',') !== 'operationId,payload,sequence,type,version') reject();
      if (frame.version !== 1 || frame.operationId !== this.#operationId || !Number.isSafeInteger(frame.sequence) || frame.sequence !== this.#next) reject();
      if (typeof frame.type !== 'string' || frame.type.length > 64 || !/^[a-z][a-z0-9]*(?:[._][a-z][a-z0-9]*)*$/.test(frame.type)) reject();
      if (!frame.payload || typeof frame.payload !== 'object' || Array.isArray(frame.payload) || typeof terminal !== 'boolean') reject();
      if (this.#next >= FRAME_LIMIT) reject('PROTOCOL_LIMIT');
      this.#next++;
      this.#closed = terminal;
    } catch (error) {
      this.#closed = true;
      if (error instanceof ProtocolError) throw error;
      reject();
    }
  }
}
