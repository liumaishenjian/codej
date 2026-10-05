# ADR-115：问卷答案以工具结果确认后进入历史

Proposed；2026-09-12；S15，CLI-04/05/09 L2、PLAN-01 L1 不变。

参考 AUTH-SRC-2026-07-29-A（Observed）：`AskUserQuestionPermissionRequest/AskUserQuestionPermissionRequest.tsx` 的提交处理将答案交给工具决定回调；`AskUserQuestionTool/AskUserQuestionTool.tsx` 的结果渲染使用工具返回的答案。采纳面板提交与工具结果呈现分离的职责，不复制源码表达。跨进程确认与不可撤销的原生终端历史是本项目独立适配问题。

现有问卷发送后立即追加答案 notice，宿主拒绝后无法撤销已经输出的原生历史，重试又重复追加。采用现有 experienceV1 工具结果 callId，以当前 session/run、问卷调用 ID 和工具名精确关联；成功终态后只追加一次。提交待确认期间仅保留处理状态。失败、取消、断连和明确拒绝清理未确认摘要；拒绝仍按 ADR114 保留复核内容，必须再次手动提交。

结果标记 contentRedacted 时禁止从本地保存的答案重新展示敏感内容。旧宿主无 callId 时不猜测关联，不追加本地答案摘要，仍呈现真实工具结果和模型正文。不开新命令、不改 Java Agent Loop、不额外发送普通消息、不扩展协议。

可证伪验证：普通与计划问卷、单题旧接口、多选自由回答；发送未确认没有历史答案，拒绝后修改重试只有最后确认的摘要，错 callId/run/工具名不可确认，重复终态只显示一次，失败/遮蔽/取消/断连不回显。通过 Java Fake stdio 验证请求与工具结果 ID 一致、模型收到真实答案及继续下一轮。物理终端与在线 Provider 未覆盖的分支明确保留为差距。
