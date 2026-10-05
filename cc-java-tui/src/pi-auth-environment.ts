/**
 * 私有Java认证helper沿用实际宿主的显式代理，但不携带API Key或Node启动选项。
 * 调用者传入启动器选定的环境（显式spec.env存在时不再合并ambient环境）。
 * 只读取白名单属性，不枚举或解析任何凭据变量。
 */
export function piAuthEnvironment(source: Readonly<Record<string, string | undefined>>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const key of ['SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'TMPDIR', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY']) {
    const value = source[key];
    if (typeof value === 'string') result[key] = value;
  }
  return result;
}
