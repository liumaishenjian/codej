import {editorRows, palette, span, writer, type Row} from './screen.js';
import type {AuthPanel} from './auth.js';
import {glyphs} from './editor.js';
import stringWidth from 'string-width';
import {PI_AUTHORIZATION_LABEL, validPiAuthorizationUrl} from '../pi-authorization-link.js';

function compact(value: string, width: number): string {
  let result = '';
  for (const point of glyphs(value.replace(/[\u0000-\u001f\u007f]/g, ' '))) {
    if (stringWidth(result + point) > width - 1) return result + '…';
    result += point;
  }
  return result;
}
/** 专用有界视图；只显示允许字段，不渲染协议 JSON、凭证摘要或错误自由文本。 */
export function authRows(panel: AuthPanel, width: number, height: number, authorizationUrl?: string, authInput = ''): Row[] {
  const out = writer(width);
  const line = (value: string, color?: string) => out.add([span(compact(value, width), color)]);
  line('─'.repeat(width), palette.muted);
  line(' ' + panel.title, palette.blue);
  if (panel.providerId) {
    const provider = panel.providerId === 'openai-codex' ? 'OpenAI Codex' : panel.providerId;
    line(' ' + provider, palette.muted);
  }
  if (panel.message) panel.message.split('\n').slice(0, 2).forEach(value => line(' ' + value, palette.muted));
  if (panel.backend === 'pi' && panel.authMethod === 'OAUTH' && authorizationUrl && validPiAuthorizationUrl(authorizationUrl)) {
    if (panel.authorizationOpened === false) {
      // 系统 opener 失败时，PowerShell/旧终端可能没有 OSC8 点击能力；保留一小段
      // 可选中的纯文本目标作为回退。正常授权 URL 远短于此上限，长目标仍受面板高度保护。
      const value = ' 授权地址：' + authorizationUrl;
      const chunkWidth = Math.max(8, width - 1);
      for (let offset = 0, count = 0; offset < value.length && count < 6; offset += chunkWidth, count++) {
        line(value.slice(offset, offset + chunkWidth));
      }
      if (value.length > chunkWidth * 6) line(' …（授权地址过长，请重新打开浏览器）', palette.muted);
    } else if (width >= stringWidth(PI_AUTHORIZATION_LABEL)) {
      // 不交给逐字正文切行器；链接只在最终渲染层编码一次，避免长目标重复或挤占高度。
      out.rows.push({spans: [{text: PI_AUTHORIZATION_LABEL, color: palette.blue, authorizationUrl}]});
    } else line('请放大终端后打开授权链接', palette.muted);
  }
  if (panel.phase === 'form' && panel.form) {
    const f = panel.form;
    line(' HTTPS API Base URL', palette.muted);
    if (f.phase === 'form' && f.field === 'baseUrl') out.rows.push(...editorRows({text: f.baseUrl, cursor: glyphs(f.baseUrl).length}, width, 'https://…').slice(-3));
    else line(' ' + f.baseUrl);
    line(' Model ID', palette.muted);
    if (f.phase === 'form' && f.field === 'modelId') out.rows.push(...editorRows({text: f.modelId, cursor: glyphs(f.modelId).length}, width, '模型名称').slice(-3));
    else line(' ' + f.modelId);
    if (f.validation) line(f.validation, palette.accent);
  }
  if (panel.phase === 'env') out.rows.push(...editorRows({text: panel.environmentName, cursor: panel.environmentName.length}, width, 'ENV_NAME').slice(-3));
  if (panel.phase === 'secret') {
    if (panel.promptKind === 'manual_code') out.rows.push(...editorRows({text: authInput, cursor: glyphs(authInput).length}, width, '粘贴授权码或回调 URL').slice(-3));
    else if (panel.backend === 'pi') line(' API Key：[已遮蔽]', palette.muted);
    else line(' API Key：[已遮蔽] · ' + Math.max(0, Math.min(16_384, panel.secretByteCount ?? 0)) + ' 字节', palette.muted);
  }
  const pageSize = Math.max(1, Math.min(6, height - out.rows.length - 6));
  const start = Math.floor(panel.focus / pageSize) * pageSize;
  panel.choices.slice(start, start + pageSize).forEach((choice, i) => line((start + i === panel.focus ? '❯ ' : '  ') + choice.label, start + i === panel.focus ? palette.blue : undefined));
  if (panel.choices.length > pageSize) line(`${panel.focus + 1}/${panel.choices.length} · ↑↓ 翻页`, palette.muted);
  line(panel.backend === 'pi' && panel.phase === 'login' ? ' Esc 取消 · Ctrl+C 退出'
    : panel.backend === 'pi' && panel.phase === 'wait' ? ' Esc 关闭'
    : panel.phase === 'secret' ? ' Enter 提交 · Esc 取消'
    : panel.phase === 'env' || panel.phase === 'form' ? ' Enter 继续 · Tab 切字段 · Esc 关闭'
    : ' ↑↓ 选择 · Enter 确认 · Esc 关闭', palette.muted);
  line('─'.repeat(width), palette.muted);
  return out.rows;
}
