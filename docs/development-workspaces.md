# 开发工作树与重构基线

更新：2026-09-12。此文用于避免从旧目录继续 TUI 重构。

| 用途 | 目录 | 分支 |
| --- | --- | --- |
| 新 TUI 唯一开发入口 | `G:\AI Cloud\cc-java-tui-redesign` | `feat/tui-redesign` |
| main 基线 | `G:\AI Cloud\cc-java` | `main` |

新 TUI 当前实现候选提交为 `0c01799`，包含 `116af64`、`dea3349`。
获取远程 `feat/tui-redesign` 最新提交继续开发；后续文档提交不代表产品新增能力。
该候选版本不表示整体体验已获用户验收，剩余差距见[交接计划](plans/tui-core-handoff.md)。

## 开发启动

使用 PowerShell 7，在目标业务项目目录执行：

```powershell
& 'G:\AI Cloud\cc-java-tui-redesign\scripts\StartCodejDev.ps1' --tui-next
```

`preview:tui` 是离线演示，不能替代真实入口验证。已安装 `codej` 默认入口未替换。
不得把 main 目录的旧构建缓存或旧界面当作新重构版本。

## 已归档的旧工作

2026-09-12 清理前，旧 TUI 工作树有 896 项状态记录，旧 provider-auth 工作树有 37 项。
均已通过包含未跟踪文件的 stash 保存，并固定在本地 `refs/archive/cleanup-20260912/` 下。
已清理的本地历史分支也保留在该归档命名空间；没有删除远程历史分支或合并到 main。

独立本地备份目录：

`G:\AI Cloud\codej-backups\cleanup-20260912`

其中包含：
- `codej-before-cleanup.bundle`：已验证的 Git 备份，包含旧改动与分支引用；
- `restore.txt`、`branches.txt`：恢复身份及原分支列表；
- `old-tui-files.txt`、`provider-auth-files.txt`：归档前文件清单；
- `provider-auth-worktree`：保留本地配置与缓存的旧子任务目录，已脱离开发分支。

备份仅保留本机，不上传。恢复应在独立临时工作树中按记录的 base 和 stash 身份操作，
不要把旧 stash 整包应用到 `feat/tui-redesign`。需要旧能力时逐项评估后迁移。
