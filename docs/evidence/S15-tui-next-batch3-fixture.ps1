param(
    [ValidateSet('Questionnaire', 'Plan')]
    [string]$Scenario = 'Questionnaire',
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
    'codej-batch3-' + [Guid]::NewGuid().ToString('N'))))
$workspace = Join-Path $temporaryRoot 'workspace'
$sessions = Join-Path $temporaryRoot 'sessions'

function Remove-Batch3TemporaryRoot {
    param([Parameter(Mandatory)][string]$Path)

    $candidate = [IO.Path]::GetFullPath($Path).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    $leaf = [IO.Path]::GetFileName($candidate)
    $parent = [IO.Path]::GetDirectoryName($candidate)
    if ($candidate -eq $systemTempRoot -or
            $parent -ne $systemTempRoot -or
            $leaf -notmatch '^codej-batch3-[0-9a-f]{32}$') {
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
    if ($Scenario -eq 'Questionnaire') {
        & git init --quiet $workspace
        if ($LASTEXITCODE -ne 0) { throw '无法初始化问卷临时 Git Workspace' }
    }
    [Console]::Error.WriteLine("[batch3 fixture] 场景：$Scenario；临时目录：$temporaryRoot")

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
    $fixtureMain = if ($Scenario -eq 'Questionnaire') {
        'io.github.liumaishenjian.ccjava.cli.stdio.QuestionnairesFixtureMain'
    }
    else {
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain'
    }
    $fixtureArguments = if ($Scenario -eq 'Questionnaire') {
        @($workspace, $sessions)
    }
    else {
        @('plan-runtime', $temporaryRoot)
    }
    $childCommand = @($java, '-Dfile.encoding=UTF-8', '-cp', $classpath, $fixtureMain) + $fixtureArguments
    $commandJson = ConvertTo-Json -Compress -InputObject $childCommand
    $env:CC_JAVA_SPIKE_COMMAND_BASE64 = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($commandJson))

    if ($Scenario -eq 'Questionnaire') {
        [Console]::Error.WriteLine('[batch3 fixture] 问卷步骤：')
        [Console]::Error.WriteLine('1. 输入“请提问”，逐题完成单选、多选和自由回答；切回前题修改后进入最终复核。')
        [Console]::Error.WriteLine('2. 确认复核只提交一次，最终正文可见；输入“问卷完成后下一轮”验证继续输入。')
        [Console]::Error.WriteLine('3. 重新启动本场景时，可在问卷出现后按 Esc；确认无 Tool 成功，输入“取消后下一轮”仍有正文。')
    }
    else {
        [Console]::Error.WriteLine('[batch3 fixture] Plan 步骤：')
        [Console]::Error.WriteLine('1. 输入“/plan 分析并生成实施计划”，选择“提出修改意见”，提交 add rollback verification。')
        [Console]::Error.WriteLine('2. 修订审核选择“确认并执行”；两次写文件审批均 Allow Once。')
        [Console]::Error.WriteLine('3. 最终正文出现后、退出前，从打印的 plan-runtime 子目录实读 workspace\河南各市7天天气.xlsx，内容必须为 correct-name。')
        [Console]::Error.WriteLine('4. 批准完成后输入“普通输入”验证聊天下一轮。另一次启动可选“取消计划”，确认无文件和审批。')
        [Console]::Error.WriteLine('5. 拒绝后输入“/plan 拒绝后新计划”，必须形成不同 planId 的新审核，不得 run.launch.failed。')
    }
    [Console]::Error.WriteLine('Fixture 只替代 ModelGateway；协议、Runtime、Tool、Permission、持久化与 TUI 均走生产代码。')
    if ($Check) {
        [Console]::Out.WriteLine("batch3 fixture check: $Scenario ready")
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
        Remove-Batch3TemporaryRoot -Path $temporaryRoot
        [Console]::Error.WriteLine("[batch3 fixture] 已安全清理临时目录：$temporaryRoot")
    }
    else {
        [Console]::Error.WriteLine("[batch3 fixture] 已保留临时目录供复核：$temporaryRoot")
        [Console]::Error.WriteLine('[batch3 fixture] 如需自动清理，请使用 -Cleanup 重新运行。')
    }
}
