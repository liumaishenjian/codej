# ADR-126：可恢复工具参数失败的终端投影

Proposed；2026-10-05；S15，CLI-04/05/09 L2、TOOL-10/11 L1，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| 工具参数校验失败后继续当前回合 | `src/components/messages/GroupedToolUseContent.tsx`、工具结果关联链，AUTH-SRC-2026-07-29-A，Observed/Inferred | 每个调用与自己的结果/错误绑定；错误可见，但后续回合仍可继续，不能把一次失败直接显示成整轮成功 | Java 已白名单化的 `argumentChangeRequired`、`retryable` 和 `failureCategory` 随原 ToolRecord 保存；运行仍在继续时显示“参数无效，已返回模型修正”或“等待模型重试”。终态没有宿主关联证据时回到中性的“工具参数无效”，不猜测后续调用是否修正了旧调用 | Runtime 应用回归覆盖参数失败、后续同 Run 成功、`run.completed` 后的中性终态；无原始参数、校验正文或异常进入界面 |

该投影只解释宿主已经提供的安全元数据，不自动重试、不改写参数、不把普通工具失败与后续调用猜测关联。计划专用的 `recoveredFailureOrdinal` 仍由 Java 明确提供时才建立恢复关系；本 ADR 不扩展权限、计划 Gate 或 Tool Pipeline。
