# ADR-118：文件上下文审阅与结果关联

Proposed；2026-09-13；S15，CLI-04/05/09 L2、TOOL-10/11 L1，等级不变。

| 场景 | 授权参考链 / 分类 | 采用与必要偏差 | 可证伪验证 |
| --- | --- | --- | --- |
| 修改前审阅 | AUTH-SRC-2026-07-29-A，FileEditToolDiff.loadDiffData→getPatchForDisplay→StructuredDiffList/StructuredDiff，Observed | Java受限快照→stdio标记file范围→公开jsdiff结构化差异→独立终端行号/折行。只对小文件提供全文件基础的上下文，不复制扫描器或高亮代码 | 中间行、多处替换、中文/换行、旧片段退化、敏感/大文件/路径逃逸 |
| 新建与审批 | FileWriteToolDiff→StructuredDiff、Permission回调，Observed | 新建内容以空文件为起点；审批分页保留固定选择；文件落盘继续原子写入与冲突复检 | 批准前无写入、拒绝/取消、审批中外部修改 |
| 结果审阅 | 工具调用→对应tool result→结果渲染，Observed | 同Run/ordinal保留审批时预览，详情标明是意图；成功/失败取真实终态，绝不把预览称为落盘diff | 多文件身份隔离、失败不宣称成功、下一轮 |

公开依赖采用[jsdiff](https://github.com/kpdecker/jsdiff)（BSD-3-Clause），复用structuredPatch及复杂度预算；保留包许可证和lockfile，不引入参考源码字节。依赖兼容通过Node22/TypeScript/Ink组合回归确认。

后端复用当前WorkspaceGuard及WorkspaceTextSnapshotReader；仅experienceV1与内置文件工具开启。快照读取限制24KB，前后文本各6000 UTF-16单元；整份候选文本通过既有敏感信息/控制字符拒显。读取失败/超限/不唯一匹配不得伪造位置，沿用片段退化；真实工具仍需Read证据并在批准后和写入前检查冲突。本预览只服务用户授权的审阅，不登记模型Read证据、不写Session/日志。预览不是锁，也不保证审批期间文件不变。

全文件基础diff提供上下文和真实行号；超大文件扫描、多语言语法高亮、跨平台实机和落盘后的重新读取diff仍保留差距。不得将本批描述为任意规模完整文件审阅。
