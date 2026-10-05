# ADR-110：终端中的网页来源链接

Proposed；2026-09-12；S15，CLI-03/04/09 L2不变；AUTH-SRC-2026-07-29-A。

| 对照点 | 授权参考（Observed） | 本项目独立契约与验证 |
| --- | --- | --- |
| Markdown链接到终端 | src/utils/markdown.ts → src/utils/hyperlink.ts → src/ink/supports-hyperlinks.ts：解析链接、检测能力、序列化OSC8，不支持时保留目标 | Marked链接生成结构化href；排版仅处理可见文本；公共Ink Text绘制边界使用公共terminal-link包序列化，先验证真实Ink输出 |
| 能力与退化 | 参考使用supports-hyperlinks并补充私有终端列表 | 不复制扩展列表；terminal-link 5.0.0（MIT、Node >=20）复用标准能力检测。保留既有标签和完整地址，支持时二者可点击；不支持时仍能复制地址 |
| 折行与样式 | 参考终端渲染层维护链接范围 | Span携带href，字素折行保留；合并绘制段不能跨目标；每个段独立闭合，避免后续提示成为链接。中文、表格、样式、窄屏和非TTY覆盖 |
| 不可信目标 | 本项目安全契约 | 只为无控制字符的绝对HTTP/HTTPS地址添加链接；其余原文保持可读但不激活。不自动打开地址、不增加文件访问或命令执行。文件路径点击与相对地址解析留待专门契约 |

公共依赖入口：https://github.com/sindresorhus/terminal-link 。锁定版本与lockfile；兼容性证据必须包含构建、公共Ink非debug输出和终端协议实验。模型文本不能携带控制序列进入href；只由公共包在最后一步产生协议字节。现有布局缓存不依赖终端能力，因为可见内容在两种终端上一致。

偏差：参考可用标签替代地址；本批保留ADR-107可复制完整目标的既有行为。仅接通网页来源，不把未知文件路径伪装成可打开的产物。协议字节验证不等于物理鼠标点击验证，后者仍为Unknown。Java Runtime、stdio与审批管线不变。
