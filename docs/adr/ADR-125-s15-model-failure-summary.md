# ADR-125：真实模型失败的安全终态摘要

Proposed；2026-10-05；S15，CLI-04/05/09 L2、MODEL-13 L1，等级不变。

| 用户场景 / Feature ID | 参考路径与证据分类 | 参考可观察行为 | 本项目采用机制与必要偏差 | 可证伪证据 |
| --- | --- | --- | --- | --- |
| Provider 请求失败 | `src/screens/REPL.tsx`、模型失败状态投影，AUTH-SRC-2026-07-29-A，Observed/Inferred | 终态说明失败类别和下一步，不把底层 Provider 原文或凭证直接写入对话 | TUI Runtime 消费 Java stdio 已白名单化的 `modelFailure`（category/statusClass/attempts/receivedOutput），映射为稳定中文提示；非法摘要退回安全 `stopReason` | Runtime 模型失败摘要/非法摘要 2 项回归；真实入口曾复现 `model_error`，不调用工具、不产生副作用 |
| 未分类 Provider/SDK 异常 | `SpringAiModelGateway` 请求、订阅与响应解码边界，AUTH-SRC-2026-07-29-A，Inferred | 适配器异常不能穿透为无上下文的裸模型错误，也不能把底层异常文本带入用户界面 | Java 适配器把建流、订阅和解码阶段未分类 `RuntimeException` 收敛为固定 `PROVIDER_ERROR`；诊断只记阶段和 `UNKNOWN`，原异常仅保留 JVM 内部 cause | `SpringAiModelDiagnosticTest` 6/6；模型模块 89 tests、0 failures/errors、2 skipped；sentinel 文本不出现在摘要 |
| 计划执行失败 | 计划执行终态链路，Observed/Inferred | 计划失败停止，不能显示完成或伪造最终正文 | `plan.execution.failed` 同样使用安全模型失败摘要；最终正文仍只来自合法 `run.completed.finalText` | 计划失败 Runtime 回归和既有 Java plan E2E |

该摘要不包含请求正文、响应正文、URL、凭证或异常堆栈；真实 Provider 在线质量、可用性和模型选择仍不因本 ADR 提升 Capability 等级。
