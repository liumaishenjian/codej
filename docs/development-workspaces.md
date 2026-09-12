# 单目录开发与重构基线

更新：2026-09-12。项目只使用一个日常开发目录：

- 目录：`G:\AI Cloud\cc-java`
- 当前开发分支：`feat/tui-redesign`
- `main` 作为同一仓库内的分支保留，不单独占用工作目录。
- 原 `G:\AI Cloud\cc-java-tui-redesign` 已撤除，不再用于启动或开发。

新 TUI 实现候选提交为 `0c01799`，包含 `116af64`、`dea3349`。
继续从远程 `feat/tui-redesign` 最新提交开发；文档整理不代表产品能力升级。
剩余差距和验收记录见[交接计划](plans/tui-core-handoff.md)。

## 启动与分支切换

使用 PowerShell 7，在目标业务项目目录执行：

```powershell
& 'G:\AI Cloud\cc-java\scripts\StartCodejDev.ps1' --tui-next
```

现有开发命令也可使用 `codej --tui-next`；本机启动器已指向此目录。
`preview:tui` 仅为离线演示，已安装命令的默认界面选择未改变。

需要查看 main 时，先保证没有未提交改动，再在同一目录执行 `git switch main`；
继续重构时执行 `git switch feat/tui-redesign`。不要为了查看分支再建立同名项目目录。
切分支后使用开发启动器重新校验构建身份，避免复用旧分支产物。

## 本地归档

旧 TUI 的 896 项状态记录与旧 provider-auth 的 37 项状态记录已保存为含未跟踪文件的 stash，
并固定在本地 `refs/archive/cleanup-20260912/`；已收起的本地历史分支也保留归档引用。
没有删除远程历史分支，没有合并到 main。

备份目录：`G:\AI Cloud\codej-backups\cleanup-20260912`。

- `codej-before-cleanup.bundle`：已验证的独立 Git 备份。
- `restore.txt`、`branches.txt`、文件清单：旧改动和分支恢复身份。
- `provider-auth-worktree`：归档的旧子任务目录，已解除 Git 工作树登记，不作开发入口。
- `tui-redesign-directory-snapshot`：迁回前完整目录快照，已移除 Git 工作树标记，不能作为开发仓库使用。
- `main-old-build-cache`：迁移前主目录的模块构建缓存，仅为本机备份。

本地模型配置保留在 `G:\AI Cloud\cc-java\config\provider.local.properties`，
保持 Git 忽略；备份不得上传。旧 stash 只能在独立恢复环境中按原基线检查，
不得整包应用到当前重构分支。历史证据中的旧路径仅表示当时执行位置。
