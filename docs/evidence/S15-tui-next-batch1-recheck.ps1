param(
    [string]$Workspace = (Join-Path ([System.IO.Path]::GetTempPath()) 'codej-tui-batch1-weather')
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$launcher = Join-Path $repository 'scripts\StartCodejDev.ps1'
if (-not (Test-Path -LiteralPath $Workspace)) {
    New-Item -ItemType Directory -Path $Workspace | Out-Null
}

Write-Host 'S15 第一批真实入口复验（不会记录 Provider 地址、凭证或完整日志）'
Write-Host "Workspace: $Workspace"
Write-Host '输入：/plan 查询青岛未来七天天气，只在对话中给出结果，不创建文件或安装软件。'
Write-Host '检查：未注册验证工具失败时显示“验证方式使用了当前不可用的工具”；只有同一要求后续声明真实成功后才显示“已修正验证方式，继续规划”。'
Write-Host '继续完成审核、必要审批、最终天气正文、Plan 终态与下一轮输入；若本次模型未触发该失败分支，记录为场景未覆盖，不得伪造恢复。'
Write-Host '退出后只保存脱敏的事件类型、ordinal、终态和屏幕观察，不保存 Prompt 全文、Tool 参数、Provider 配置或用户 Session。'

& $launcher --tui-next --workspace $Workspace
exit $LASTEXITCODE
