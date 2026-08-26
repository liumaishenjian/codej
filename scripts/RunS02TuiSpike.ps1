param(
    [Parameter(Mandatory = $false)]
    [string]$Prompt,

    [Parameter(Mandatory = $false)]
    [string]$Workspace,

    [Parameter(Mandatory = $false)]
    [string]$Model,

    [Parameter(Mandatory = $false)]
    [string]$Timeout = '30m',

    [Parameter(Mandatory = $false)]
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $PSScriptRoot 'StartCodejDev.ps1'
$workspaceRoot = if ([string]::IsNullOrWhiteSpace($Workspace)) {
    $repositoryRoot
}
else {
    [IO.Path]::GetFullPath($Workspace)
}

# 保持历史 Spike 的默认 Workspace，同时把日常构建和启动逻辑统一交给 codej 开发入口。
$arguments = @('--workspace', $workspaceRoot, '--timeout', $Timeout)
if (-not [string]::IsNullOrWhiteSpace($Model)) {
    $arguments += @('--model', $Model)
}
if (-not [string]::IsNullOrWhiteSpace($Prompt)) {
    $arguments += @('--print', $Prompt)
}
# 新入口默认验证内容摘要并复用产物，因此旧 -SkipBuild 无需额外转换；未传时同样避免无谓重建。
& pwsh -NoLogo -NoProfile -File $launcher @arguments
exit $LASTEXITCODE
