param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArguments
)

$ErrorActionPreference = 'Stop'
$invocationDirectory = [IO.Path]::GetFullPath((Get-Location).Path)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$modulePath = Join-Path $PSScriptRoot 'CodejDevLauncher.psm1'
Import-Module $modulePath -Force

try {
    # PowerShell 对无剩余参数的 ValueFromRemainingArguments 绑定为 $null；
    # @($null) 会变成一个空元素，而不是空数组，需先显式规范化。
    $arguments = if ($null -eq $CliArguments) { @() } else { @($CliArguments) }
    $options = ConvertFrom-CodejArguments -Arguments $arguments -InvocationDirectory $invocationDirectory
}
catch {
    [Console]::Error.WriteLine("codej: $($_.Exception.Message)")
    [Console]::Error.WriteLine('Run codej --help for usage.')
    exit 2
}

if ($options.Help) {
    [Console]::Out.WriteLine((Get-CodejHelpText))
    exit 0
}

$installationHome = if ([string]::IsNullOrWhiteSpace($env:CODEJ_INSTALLATION_HOME)) {
    [Environment]::GetFolderPath('UserProfile')
}
else {
    [IO.Path]::GetFullPath($env:CODEJ_INSTALLATION_HOME)
}
$installationRoot = Join-Path $installationHome '.local\bin'
if ($options.Doctor) {
    try {
        $report = Get-CodejDoctorReport `
            -RepositoryRoot $repositoryRoot `
            -WorkspaceRoot $options.Workspace `
            -InstallationRoot $installationRoot
        [Console]::Out.WriteLine((Format-CodejDoctorReport -Report $report))
        exit 0
    }
    catch {
        [Console]::Error.WriteLine("codej doctor failed: $($_.Exception.Message)")
        exit 1
    }
}

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7) {
    [Console]::Error.WriteLine('codej requires PowerShell 7 (pwsh).')
    exit 2
}
if (-not (Test-Path -LiteralPath $options.Workspace -PathType Container)) {
    [Console]::Error.WriteLine("codej: Workspace does not exist or is not a directory: $($options.Workspace)")
    exit 2
}
$node = Get-CodejNodeVersion
if (-not $node.Present -or -not $node.Supported -or
        [version]($node.Description.TrimStart('v')) -lt [version]'22.19.0') {
    [Console]::Error.WriteLine("codej requires Node.js 22.19.0 or newer; current: $($node.Description)")
    exit 2
}
$tuiDirectory = Join-Path $repositoryRoot 'cc-java-tui'
if (-not (Test-Path -LiteralPath (Join-Path $tuiDirectory 'node_modules') -PathType Container)) {
    [Console]::Error.WriteLine('codej TUI dependencies are missing. Run:')
    [Console]::Error.WriteLine("npm.cmd --prefix `"$tuiDirectory`" ci --ignore-scripts")
    exit 2
}

try {
    $buildState = Invoke-CodejJavaBuild -RepositoryRoot $repositoryRoot -Force:$options.Rebuild
}
catch {
    [Console]::Error.WriteLine("codej build failed: $($_.Exception.Message)")
    exit 1
}

. (Join-Path $PSScriptRoot 'ResolveRipgrep.ps1')
Initialize-CcJavaRipgrep

$java = Get-CodejJavaVersion
$dependencyClasspath = (Get-Content -LiteralPath $buildState.Paths.ClasspathFile -Raw -Encoding UTF8).Trim()
$separator = [IO.Path]::PathSeparator
$mainClasses = Join-Path $repositoryRoot 'cc-java-cli\target\classes'
$classpath = "$mainClasses$separator$dependencyClasspath"
# 显式绑定源码启动器固定入口；缺组件由对应路由失败关闭，不下载、不从工作区发现、不回退。
$browserBridge = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'cc-java-provider-pi/login.mjs'))
$piWorker = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'cc-java-provider-pi/worker.mjs'))
$piAuthCli = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'cc-java-tui/dist/src/pi-auth-cli.js'))
$browserProperties = @("-Dcodej.nodeExecutable=$($node.Executable)",
    "-Dcodej.piWorker=$piWorker", "-Dcodej.piBridge=$browserBridge", "-Dcodej.piAuthCli=$piAuthCli")
if ($null -ne $options.ProviderControlArguments) {
    $control = @($options.ProviderControlArguments)
    $backendIndex = [Array]::IndexOf($control, '--backend')
    $piBackend = $control -contains '--backend=pi' -or
        ($backendIndex -ge 0 -and $backendIndex + 1 -lt $control.Count -and $control[$backendIndex + 1] -ceq 'pi')
    $envReference = @($control | Where-Object { $_ -eq '--from-env' -or $_ -like '--from-env=*' }).Count -gt 0
    # 私有TTY壳也是TUI编译产物；只在需要它的Pi登录路径构建，不增加ENV/旧控制路径依赖。
    if ($control.Count -ge 2 -and $control[0] -ceq 'auth' -and $control[1] -ceq 'login' -and $piBackend -and -not $envReference) {
        Push-Location $tuiDirectory
        try {
            & npm.cmd --silent run build
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
        finally { Pop-Location }
        if (-not (Test-Path -LiteralPath $piAuthCli -PathType Leaf)) { throw 'Compiled Pi authentication CLI missing' }
    }
    $env:CC_JAVA_REPOSITORY_ROOT = $repositoryRoot
    & $java.Executable '-Dfile.encoding=UTF-8' "-Duser.home=$installationHome" @browserProperties '-cp' $classpath `
        'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain' @($options.ProviderControlArguments)
    exit $LASTEXITCODE
}
$childCommand = @(
    $java.Executable,
    '-Dfile.encoding=UTF-8',
    "-Duser.home=$installationHome",
    '-cp',
    $classpath,
    'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain',
    '--workspace',
    $options.Workspace,
    '--timeout',
    $options.Timeout,
    '--context-maximum-input-tokens',
    [string]$options.ContextMaximumInputTokens,
    '--context-reserved-output-tokens',
    [string]$options.ContextReservedOutputTokens,
    '--context-safety-margin-tokens',
    [string]$options.ContextSafetyMarginTokens
)
if ($browserProperties.Count -gt 0) {
    $childCommand = @($childCommand[0]) + $browserProperties + @($childCommand[1..($childCommand.Count - 1)])
}
if (-not [string]::IsNullOrWhiteSpace($options.Model)) {
    $childCommand += @('--model', $options.Model)
}
$childCommand += @('--model-diagnostics', $options.ModelDiagnostics)
if (-not [string]::IsNullOrWhiteSpace($options.ModelDiagnosticsDirectory)) {
    $childCommand += @('--model-diagnostics-dir', $options.ModelDiagnosticsDirectory)
}
if ($options.Continue) {
    $childCommand += '--continue'
}
elseif (-not [string]::IsNullOrWhiteSpace($options.Resume)) {
    $childCommand += @('--resume', $options.Resume)
}
elseif (-not [string]::IsNullOrWhiteSpace($options.Fork)) {
    $childCommand += @('--fork', $options.Fork)
}
$childCommand += '--stdio'
$commandJson = ConvertTo-Json -Compress -InputObject $childCommand
$env:CC_JAVA_SPIKE_COMMAND_BASE64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($commandJson))
$env:CC_JAVA_REPOSITORY_ROOT = $repositoryRoot
if ($null -ne $options.Print) {
    $env:CC_JAVA_SPIKE_PROMPT_BASE64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($options.Print))
}
else {
    Remove-Item Env:CC_JAVA_SPIKE_PROMPT_BASE64 -ErrorAction SilentlyContinue
}

[Console]::Error.WriteLine("[codej] Starting cc-java for workspace: $($options.Workspace)")
Push-Location $tuiDirectory
try {
    # 新界面的模式与目录只交给 Node，不能成为 Java CLI 的未知参数。
    if ($options.TuiNext -and $null -eq $options.Print) {
        & npm.cmd --silent run dev -- --tui-next --workspace $options.Workspace
    }
    else {
        & npm.cmd --silent run dev
    }
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
