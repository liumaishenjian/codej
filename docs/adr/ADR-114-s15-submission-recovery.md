# ADR-114：提交被拒绝后的内容恢复

Proposed；2026-09-12；S15，CLI-04/05/09 L2、PLAN-01 L1不变。

参考AUTH-SRC-2026-07-29-A（Observed）：FilePermissionDialog/usePermissionHandler将面板关闭与toolUseConfirm决定分开；handlePromptSubmit区分输入、队列与执行。跨stdio拒绝恢复属于本项目独立协议，依据ADR085的pending registry/拒绝tombstone与不自动重发契约，不声称参考拥有相同协议。

现有问题：计划确认的run.command.result rejected先finish清掉decisions，后续protocol.error无法还原计划；普通拒绝已经清空Composer，原任务只能人工找回；旧控制错误可能覆盖更新的审批面板。

采用：仅未接受的启动拒绝恢复原输入。输入框为空时填回；已有后续草稿时保留，原文仍在输入历史，可用向上键找回，不合并或自动发送。accepted/queued/started后、取消或断连不触发自动恢复。计划决定在运行尚未开始且宿主明确拒绝时恢复同一计划身份/编辑状态；相邻错误不会二次覆盖。老审批错误不得抢占新面板；问卷/审批发送异常保留选择，协议拒绝恢复原请求。迟到旧事件不能触发执行。

验证：启动拒绝/后续草稿、queued/accepted不可恢复、计划rejected→protocol.error及重试、取消/断连、旧审批错误、新问卷返回选择。只改TUI适配层，不修改Java loop/授权/原子审核，不增加命令或依赖。
