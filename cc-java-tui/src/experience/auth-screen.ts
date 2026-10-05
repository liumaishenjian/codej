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
export function authRows(panel: AuthPanel, width: number, height: number, authorizationUrl?: string): Row[] {
  const out = writer(width);
  const line = (value: string, color?: string) => out.add([span(compact(value, width), color)]);
  line('─'.repeat(width), palette.muted);
  line(' ' + panel.title, palette.blue);
  if (panel.providerId) line(' ' + (panel.backend ? panel.backend + ' / ' + panel.authMethod + ' / ' : '') + panel.providerId + (panel.profileId ? ' / ' + panel.profileId : ''), palette.muted);
  if (panel.message) panel.message.split('\n').slice(0, 2).forEach(value => line(' ' + value, palette.muted));
  if (panel.backend === 'pi' && panel.authMethod === 'OAUTH' && authorizationUrl && validPiAuthorizationUrl(authorizationUrl)) {
    if (width >= stringWidth(PI_AUTHORIZATION_LABEL)) {
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
  if (panel.phase === 'secret') line((panel.promptKind === 'manual_code' ? ' 手工回调：[已遮蔽] · ' : ' API Key：[已遮蔽] · ') + Math.max(0, Math.min(16_384, panel.secretByteCount ?? 0)) + ' 字节', palette.muted);
  const pageSize = Math.max(1, Math.min(6, height - out.rows.length - 6));
  const start = Math.floor(panel.focus / pageSize) * pageSize;
  panel.choices.slice(start, start + pageSize).forEach((choice, i) => line((start + i === panel.focus ? '❯ ' : '  ') + choice.label, start + i === panel.focus ? palette.blue : undefined));
  if (panel.choices.length > pageSize) line(`${panel.focus + 1}/${panel.choices.length} · ↑↓ 翻页`, palette.muted);
  line(panel.phase === 'login' ? ' Esc 取消等待 · Ctrl+C 退出 · 不保证回滚'
    : panel.phase === 'wait' ? ' 等待宿主结果 · Esc 关闭（不自动重发）'
    : panel.phase === 'secret' ? ' Enter 提交 · Backspace 删除 · Esc 清零关闭'
    : panel.phase === 'env' || panel.phase === 'form' ? ' Enter 继续 · Tab 切字段 · Esc 关闭'
    : ' ↑↓ 选择 · Enter 确认 · Esc 关闭', palette.muted);
  line('─'.repeat(width), palette.muted);
  return out.rows;
}
