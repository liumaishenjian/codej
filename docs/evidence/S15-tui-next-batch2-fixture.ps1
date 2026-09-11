param(
    [switch]$Check,
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$tuiDirectory = Join-Path $repositoryRoot 'cc-java-tui'
$maven = Join-Path $repositoryRoot 'mvnw.cmd'
$systemTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar)
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $systemTempRoot (
    'codej-batch2-' + [Guid]::NewGuid().ToString('N'))))
$workspace = Join-Path $temporaryRoot 'workspace'
$sessions = Join-Path $temporaryRoot 'sessions'

function Remove-Batch2TemporaryRoot {
    param([Parameter(Mandatory)][string]$Path)

    $candidate = [IO.Path]::GetFullPath($Path).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    $leaf = [IO.Path]::GetFileName($candidate)
    $parent = [IO.Path]::GetDirectoryName($candidate)
    if ($candidate -eq $systemTempRoot -or
            $parent -ne $systemTempRoot -or
            $leaf -notmatch '^codej-batch2-[0-9a-f]{32}$') {
        throw "拒绝清理非本次 Fixture 临时目录：$candidate"
    }
    if (Test-Path -LiteralPath $candidate) {
        $item = Get-Item -LiteralPath $candidate -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "拒绝清理已变为重解析点的 Fixture 临时目录：$candidate"
        }
        Remove-Item -LiteralPath $candidate -Recurse -Force -Confirm:$false
    }
}

try {
    New-Item -ItemType Directory -Path $workspace, $sessions | Out-Null
    [Console]::Error.WriteLine("[batch2 fixture] 临时目录：$temporaryRoot")
    & git init --quiet $workspace
    if ($LASTEXITCODE -ne 0) { throw '无法初始化临时 Git Workspace' }

    & $maven -q -f (Join-Path $repositoryRoot 'pom.xml') -pl cc-java-cli -am `
        test-compile dependency:build-classpath '-DincludeScope=test' `
        '-Dmdep.outputFile=target/test-dependency-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Fixture Java 编译失败' }

    $dependencyFile = Join-Path $repositoryRoot 'cc-java-cli\target\test-dependency-classpath.txt'
    $dependencies = (Get-Content -LiteralPath $dependencyFile -Raw -Encoding UTF8).Trim()
    $modules = @(
        'cc-java-cli', 'cc-java-core', 'cc-java-domain', 'cc-java-model-spring-ai',
        'cc-java-tools-local', 'cc-java-tools-web', 'cc-java-mcp', 'cc-java-protocol', 'cc-java-sdk'
    )
    $classpathParts = @(
        (Join-Path $repositoryRoot 'cc-java-cli\target\test-classes')
        (Join-Path $repositoryRoot 'cc-java-cli\target\classes')
    ) + ($modules | Where-Object { $_ -ne 'cc-java-cli' } | ForEach-Object {
        Join-Path $repositoryRoot "$_\target\classes"
    }) + @($dependencies)
    $classpath = $classpathParts -join [IO.Path]::PathSeparator
    $java = (Get-Command java -ErrorAction Stop).Source
    $childCommand = @(
        $java,
        '-Dfile.encoding=UTF-8',
        '-cp',
        $classpath,
        'io.github.liumaishenjian.ccjava.cli.stdio.TuiCoreInteractionFixtureMain',
        $workspace,
        $sessions
    )
    $commandJson = ConvertTo-Json -Compress -InputObject $childCommand
    $env:CC_JAVA_SPIKE_COMMAND_BASE64 = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($commandJson))

    [Console]::Error.WriteLine('[batch2 fixture] 按键步骤：')
    [Console]::Error.WriteLine('1. 输入“第一轮”，立即输入未提交草稿“后续中文草稿😀”，再按 Ctrl+O。')
    [Console]::Error.WriteLine('2. 审批出现后选择 2（本会话允许）并按 Enter；确认草稿和完整命令尾部仍可见。')
    [Console]::Error.WriteLine('3. 等待第一轮完成；第二条同命令不得再次出现审批。')
    [Console]::Error.WriteLine('4. 按 Enter 提交保留草稿；看到命令输出后按 Esc，确认“本轮已停止”且面板不重开。')
    [Console]::Error.WriteLine('5. 按 Ctrl+C 退出。Fixture 使用 Fake Model，但 Tool/Permission/进程/stdio/TUI 均为生产链。')
    if ($Check) {
        [Console]::Out.WriteLine('batch2 fixture check: ready')
        return
    }

    Push-Location $tuiDirectory
    try {
        & npm.cmd --silent run dev -- --tui-next --workspace $workspace
        exit $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    Remove-Item Env:CC_JAVA_SPIKE_COMMAND_BASE64 -ErrorAction SilentlyContinue
    if ($Cleanup) {
        Remove-Batch2TemporaryRoot -Path $temporaryRoot
        [Console]::Error.WriteLine("[batch2 fixture] 已安全清理临时目录：$temporaryRoot")
    }
    else {
        [Console]::Error.WriteLine("[batch2 fixture] 已保留临时目录供复核：$temporaryRoot")
        [Console]::Error.WriteLine('[batch2 fixture] 如需自动清理，请使用 -Cleanup 重新运行。')
    }
}
