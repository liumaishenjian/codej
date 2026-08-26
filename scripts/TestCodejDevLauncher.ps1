$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'CodejDevLauncher.psm1') -Force

$passed = 0
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERT FAILED: $Message" }
    $script:passed++
}
function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern)
    try { & $Action; throw 'Expected action to throw.' }
    catch {
        if ($_.Exception.Message -eq 'Expected action to throw.' -or $_.Exception.Message -notmatch $Pattern) {
            throw "ASSERT FAILED: expected error /$Pattern/, actual: $($_.Exception.Message)"
        }
    }
    $script:passed++
}
function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $parent = Split-Path -Parent $Path
    if ($parent) { $null = New-Item -ItemType Directory -Path $parent -Force }
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}
function Initialize-FakeRepository {
    param([string]$Path)
    Write-Utf8File (Join-Path $Path 'pom.xml') '<project />'
    Write-Utf8File (Join-Path $Path 'mvnw') 'wrapper'
    Write-Utf8File (Join-Path $Path 'mvnw.cmd') 'wrapper'
    foreach ($module in @('cc-java-domain','cc-java-core','cc-java-model-spring-ai','cc-java-tools-local','cc-java-cli')) {
        Write-Utf8File (Join-Path $Path "$module\pom.xml") "<$module />"
        Write-Utf8File (Join-Path $Path "$module\src\main\java\Sample.java") "class $($module.Replace('-', '_')) {}"
        Write-Utf8File (Join-Path $Path "$module\src\main\resources\app.txt") 'resource'
    }
}
function New-FakeBuildOutputs {
    param([string]$Path)
    foreach ($module in @('cc-java-domain','cc-java-core','cc-java-model-spring-ai','cc-java-tools-local','cc-java-cli')) {
        $null = New-Item -ItemType Directory -Path (Join-Path $Path "$module\target\classes") -Force
    }
    Write-Utf8File (Join-Path $Path 'cc-java-cli\target\classes\io\github\liumaishenjian\ccjava\cli\CcJavaCliMain.class') 'class'
    Write-Utf8File (Join-Path $Path 'cc-java-cli\target\codej-runtime-classpath.txt') 'dependency.jar'
}
function Invoke-CapturedProcess {
    param(
        [string]$FileName,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [hashtable]$Environment = @{}
    )
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FileName
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $null = $info.ArgumentList.Add($argument) }
    foreach ($entry in $Environment.GetEnumerator()) { $info.Environment[$entry.Key] = [string]$entry.Value }
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return [pscustomobject]@{ ExitCode = $process.ExitCode; Stdout = $stdout; Stderr = $stderr }
}

$temp = Join-Path ([IO.Path]::GetTempPath()) "codej-launcher-test-$PID"
try {
    $null = New-Item -ItemType Directory -Path $temp -Force

    $empty = ConvertFrom-CodejArguments -Arguments @() -InvocationDirectory $temp
    Assert-True ($empty.Workspace -eq [IO.Path]::GetFullPath($temp) -and $null -eq $empty.Print) 'empty arguments select interactive mode'
    Assert-True ($empty.Timeout -eq '30m') 'launcher default timeout matches Java print default'
    $providerControl = ConvertFrom-CodejArguments -Arguments @('auth', 'list', '--json') -InvocationDirectory $temp
    Assert-True (($providerControl.ProviderControlArguments -join '|') -eq 'auth|list|--json') 'provider control commands pass through without TUI parsing'
    Assert-True ($empty.ContextMaximumInputTokens -eq 256000 -and $empty.ContextReservedOutputTokens -eq 8192 -and $empty.ContextSafetyMarginTokens -eq 4096) 'interactive defaults enable the 256k context pipeline'

    $parsed = ConvertFrom-CodejArguments -Arguments @('--workspace', '目录 with spaces', '--model=x', '--timeout', '30s', '--print', 'hello') -InvocationDirectory $temp
    Assert-True ($parsed.Workspace -eq [IO.Path]::GetFullPath((Join-Path $temp '目录 with spaces'))) 'workspace pair syntax'
    Assert-True ($parsed.Model -eq 'x' -and $parsed.Timeout -eq '30s' -and $parsed.Print -eq 'hello') 'value parameters'
    $contextOverride = ConvertFrom-CodejArguments -Arguments @(
        '--context-maximum-input-tokens', '128000',
        '--context-reserved-output-tokens', '4096',
        '--context-safety-margin-tokens', '2048') -InvocationDirectory $temp
    Assert-True ($contextOverride.ContextMaximumInputTokens -eq 128000 -and $contextOverride.ContextReservedOutputTokens -eq 4096 -and $contextOverride.ContextSafetyMarginTokens -eq 2048) 'context capacity overrides are forwarded as validated integers'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--context-maximum-input-tokens', 'oops') -InvocationDirectory $temp } '必须是 1 到'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--context-maximum-input-tokens', '1000') -InvocationDirectory $temp } '必须大于输出保留'
    $diagnostics = ConvertFrom-CodejArguments -Arguments @('--model-diagnostics', 'safe', '--model-diagnostics-dir', '诊断 dir') -InvocationDirectory $temp
    Assert-True ($diagnostics.ModelDiagnostics -eq 'safe' -and $diagnostics.ModelDiagnosticsDirectory -eq [IO.Path]::GetFullPath((Join-Path $temp '诊断 dir'))) 'diagnostic mode and trusted local directory are forwarded'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--model-diagnostics', 'raw') -InvocationDirectory $temp } '只接受 off'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--model-diagnostics-dir', 'diagnostics') -InvocationDirectory $temp } '仅可与 safe'
    $inline = ConvertFrom-CodejArguments -Arguments @("--workspace=$temp") -InvocationDirectory 'C:\'
    Assert-True ($inline.Workspace -eq [IO.Path]::GetFullPath($temp)) 'workspace inline syntax'
    $splitInline = ConvertFrom-CodejArguments -Arguments @('--workspace=G', '\AI Cloud\cc-java', '--doctor') -InvocationDirectory $temp
    Assert-True ($splitInline.Workspace -eq [IO.Path]::GetFullPath('G:\AI Cloud\cc-java') -and $splitInline.Doctor) 'cmd split inline syntax'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--model', 'a', '--model=b') -InvocationDirectory $temp } '不能重复'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--timeout') -InvocationDirectory $temp } '缺少值'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--unknown') -InvocationDirectory $temp } '未知参数'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--', 'tail') -InvocationDirectory $temp } '暂不支持'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--doctor', '--rebuild') -InvocationDirectory $temp } '不能与'
    $continued = ConvertFrom-CodejArguments -Arguments @('--continue') -InvocationDirectory $temp
    Assert-True ($continued.Continue -and $null -eq $continued.Resume -and $null -eq $continued.Fork) 'continue session selection'
    $resumed = ConvertFrom-CodejArguments -Arguments @('--resume', 'session-resume-1') -InvocationDirectory $temp
    Assert-True (-not $resumed.Continue -and $resumed.Resume -eq 'session-resume-1') 'resume session selection'
    $forked = ConvertFrom-CodejArguments -Arguments @('--fork=session-fork-1') -InvocationDirectory $temp
    Assert-True ($forked.Fork -eq 'session-fork-1') 'fork session selection'
    Assert-Throws { ConvertFrom-CodejArguments -Arguments @('--continue', '--resume', 'session-1') -InvocationDirectory $temp } '只能选择一个'

    $repo = Join-Path $temp 'repo'
    Initialize-FakeRepository -Path $repo
    $hash1 = Get-CodejBuildFingerprint -RepositoryRoot $repo -JavaDescription 'jdk-test'
    (Get-Item (Join-Path $repo 'pom.xml')).LastWriteTimeUtc = [DateTime]::UtcNow.AddDays(-2)
    $hash2 = Get-CodejBuildFingerprint -RepositoryRoot $repo -JavaDescription 'jdk-test'
    Assert-True ($hash1 -eq $hash2) 'mtime does not affect fingerprint'
    Write-Utf8File (Join-Path $repo 'cc-java-core\src\main\resources\app.txt') 'changed'
    $hash3 = Get-CodejBuildFingerprint -RepositoryRoot $repo -JavaDescription 'jdk-test'
    Assert-True ($hash3 -ne $hash2) 'resource content affects fingerprint'
    $hash4 = Get-CodejBuildFingerprint -RepositoryRoot $repo -JavaDescription 'jdk-other'
    Assert-True ($hash4 -ne $hash3) 'JDK description affects fingerprint'

    New-FakeBuildOutputs -Path $repo
    Assert-True (Test-CodejBuildOutputs -RepositoryRoot $repo).Complete 'all module outputs accepted'
    Remove-Item -LiteralPath (Join-Path $repo 'cc-java-core\target\classes') -Recurse -Force
    Assert-True (-not (Test-CodejBuildOutputs -RepositoryRoot $repo).Complete) 'missing module output invalidates cache'

    $lockPath = Join-Path $temp 'lock\build.lock'
    Write-Utf8File $lockPath "schema=1`npid=2147483000`n"
    $lock = Enter-CodejBuildLock -LockFile $lockPath -TimeoutSeconds 2
    Assert-True ($null -ne $lock.Stream) 'stale lock recovered'
    Exit-CodejBuildLock -Lock $lock
    Assert-True (-not (Test-Path -LiteralPath $lockPath)) 'lock released'

    $shim = New-CodejShimContent -RepositoryRoot 'G:\Path with spaces\cc-java'
    Assert-True ($shim.Contains((Get-CodejShimMarker))) 'shim marker present'
    Assert-True ($shim.Contains('if not exist')) 'shim checks repository before launch'
    $shimPath = Join-Path $temp 'codej.cmd'
    Write-Utf8File $shimPath $shim
    Assert-True (Test-CodejOwnedShim -Path $shimPath) 'owned shim detected'
    Write-Utf8File $shimPath '@echo off'
    Assert-True (-not (Test-CodejOwnedShim -Path $shimPath)) 'foreign shim rejected'

    $candidate = Join-Path $temp '.local\bin'
    $pathValue = "C:\Tools;$($candidate.ToUpperInvariant())\"
    Assert-True (Test-CodejPathContains -PathValue $pathValue -Candidate $candidate) 'PATH comparison ignores case and trailing separator'
    $added = Add-CodejPathEntry -PathValue 'C:\Tools' -Candidate $candidate
    Assert-True (Test-CodejPathContains -PathValue $added -Candidate $candidate) 'PATH add'
    $removed = Remove-CodejPathEntry -PathValue $added -Candidate $candidate
    Assert-True (-not (Test-CodejPathContains -PathValue $removed -Candidate $candidate)) 'PATH remove'

    $secret = 'doctor-secret-value'
    $oldKey = $env:CC_JAVA_OPENAI_API_KEY
    try {
        $env:CC_JAVA_OPENAI_API_KEY = $secret
        $report = Get-CodejDoctorReport -RepositoryRoot (Split-Path -Parent $PSScriptRoot) -WorkspaceRoot $temp -InstallationRoot $candidate
        $text = Format-CodejDoctorReport -Report $report
        Assert-True ($report.ProviderApiKeyEnvironmentPresent) 'doctor reports provider environment presence'
        Assert-True (-not $text.Contains($secret)) 'doctor does not reveal provider value'
        Assert-True ($text.Contains('presence/source checks only')) 'doctor labels existence semantics'
    }
    finally { $env:CC_JAVA_OPENAI_API_KEY = $oldKey }

    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $installer = Join-Path $PSScriptRoot 'InstallCodejDevCommand.ps1'
    $userHome = Join-Path $temp '用户 home with spaces'
    $installationRoot = Join-Path $userHome '.local\bin'
    $installedShim = Join-Path $installationRoot 'codej.cmd'
    $metadataPath = Join-Path $userHome '.cc-java\codej-dev-install.json'
    $initialPath = 'C:\Existing Tools'

    $whatIf = Invoke-CapturedProcess -FileName 'pwsh' -WorkingDirectory $repositoryRoot -Arguments @(
        '-NoLogo', '-NoProfile', '-File', $installer,
        '-UserHome', $userHome, '-UserPathOverride', $initialPath,
        '-SkipDependencies', '-AddToUserPath', '-WhatIf')
    Assert-True ($whatIf.ExitCode -eq 0) 'installer WhatIf succeeds'
    Assert-True (-not (Test-Path -LiteralPath $installedShim) -and -not (Test-Path -LiteralPath $metadataPath)) 'WhatIf writes no installation files'

    $install = Invoke-CapturedProcess -FileName 'pwsh' -WorkingDirectory $repositoryRoot -Arguments @(
        '-NoLogo', '-NoProfile', '-File', $installer,
        '-UserHome', $userHome, '-UserPathOverride', $initialPath,
        '-SkipDependencies', '-AddToUserPath')
    Assert-True ($install.ExitCode -eq 0 -and (Test-CodejOwnedShim -Path $installedShim)) 'installer writes owned shim'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ([bool]$metadata.pathAddedByInstaller) 'installer records PATH ownership'

    $reinstall = Invoke-CapturedProcess -FileName 'pwsh' -WorkingDirectory $repositoryRoot -Arguments @(
        '-NoLogo', '-NoProfile', '-File', $installer,
        '-UserHome', $userHome, '-UserPathOverride', "$installationRoot;$initialPath",
        '-SkipDependencies', '-AddToUserPath')
    $reinstalledMetadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ($reinstall.ExitCode -eq 0 -and [bool]$reinstalledMetadata.pathAddedByInstaller) 'reinstall preserves PATH ownership'

    $doctorWorkspace = Join-Path $temp '调用 目录 with spaces'
    $null = New-Item -ItemType Directory -Path $doctorWorkspace -Force
    $doctor = Invoke-CapturedProcess -FileName 'cmd.exe' -WorkingDirectory $doctorWorkspace -Arguments @('/d', '/c', $installedShim, '--doctor') -Environment @{
        CODEJ_INSTALLATION_HOME = $userHome
        CC_JAVA_OPENAI_API_KEY = $secret
    }
    Assert-True ($doctor.ExitCode -eq 0) 'installed shim runs doctor from external workspace'
    Assert-True ($doctor.Stdout.Contains("workspaceRoot: $([IO.Path]::GetFullPath($doctorWorkspace))")) 'doctor preserves invocation workspace'
    Assert-True ($doctor.Stdout.Contains("repositoryRoot: $([IO.Path]::GetFullPath($repositoryRoot))")) 'doctor keeps source repository root'
    Assert-True (-not ($doctor.Stdout + $doctor.Stderr).Contains($secret)) 'installed doctor does not reveal provider value'

    $foreignHome = Join-Path $temp 'foreign-home'
    $foreignShim = Join-Path $foreignHome '.local\bin\codej.cmd'
    Write-Utf8File $foreignShim '@echo off'
    $foreign = Invoke-CapturedProcess -FileName 'pwsh' -WorkingDirectory $repositoryRoot -Arguments @(
        '-NoLogo', '-NoProfile', '-File', $installer,
        '-UserHome', $foreignHome, '-UserPathOverride', $initialPath,
        '-SkipDependencies')
    Assert-True ($foreign.ExitCode -ne 0 -and (Get-Content -LiteralPath $foreignShim -Raw) -eq '@echo off') 'installer refuses foreign shim'

    $uninstall = Invoke-CapturedProcess -FileName 'pwsh' -WorkingDirectory $repositoryRoot -Arguments @(
        '-NoLogo', '-NoProfile', '-File', $installer,
        '-UserHome', $userHome, '-UserPathOverride', "$installationRoot;$initialPath",
        '-Uninstall', '-RemoveUserPath')
    Assert-True ($uninstall.ExitCode -eq 0 -and -not (Test-Path -LiteralPath $installedShim) -and -not (Test-Path -LiteralPath $metadataPath)) 'owned uninstall removes shim and metadata'
    Assert-Throws { & $installer -UserHome $userHome -UserPathOverride $initialPath -Uninstall -RemoveUserPath } 'metadata was not found'

    $invalidRoot = Join-Path $temp 'invalid-repository'
    $invalidShim = Join-Path $temp 'invalid-shim.cmd'
    Write-Utf8File $invalidShim (New-CodejShimContent -RepositoryRoot $invalidRoot)
    $invalid = Invoke-CapturedProcess -FileName 'cmd.exe' -WorkingDirectory $temp -Arguments @('/d', '/c', $invalidShim, '--doctor')
    Assert-True ($invalid.ExitCode -eq 3 -and $invalid.Stderr.Contains('source repository reference is invalid')) 'shim diagnoses stale repository reference'

    $concurrentLockPath = Join-Path $temp 'concurrent-lock\build.lock'
    $lockReady = Join-Path $temp 'concurrent-lock\ready.txt'
    $holder = Start-Job -ScriptBlock {
        param($module, $lockPath, $readyPath)
        Import-Module $module -Force
        $held = Enter-CodejBuildLock -LockFile $lockPath -TimeoutSeconds 5
        try {
            [IO.File]::WriteAllText($readyPath, 'ready')
            Start-Sleep -Milliseconds 600
        }
        finally { Exit-CodejBuildLock -Lock $held }
    } -ArgumentList (Join-Path $PSScriptRoot 'CodejDevLauncher.psm1'), $concurrentLockPath, $lockReady
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        while (-not (Test-Path -LiteralPath $lockReady) -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 50
        }
        Assert-True (Test-Path -LiteralPath $lockReady) 'holder acquires development build lock'
        $started = [Diagnostics.Stopwatch]::StartNew()
        $waiter = Enter-CodejBuildLock -LockFile $concurrentLockPath -TimeoutSeconds 5
        $started.Stop()
        Exit-CodejBuildLock -Lock $waiter
        Assert-True ($started.ElapsedMilliseconds -ge 300) 'second process waits for development build lock'
        $holder | Wait-Job | Out-Null
        $null = @($holder | Receive-Job)
        Assert-True ($holder.State -eq 'Completed') 'concurrent lock holder completes'
        Assert-True (-not (Test-Path -LiteralPath $concurrentLockPath)) 'concurrent lock is released'
    }
    finally { $holder | Remove-Job -Force -ErrorAction SilentlyContinue }

    $spikeText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'RunS02TuiSpike.ps1') -Raw -Encoding UTF8
    Assert-True ($spikeText.Contains("`$workspaceRoot = if") -and $spikeText.Contains("`$repositoryRoot")) 'legacy spike defaults workspace to repository'
    Assert-True ($spikeText.Contains("StartCodejDev.ps1") -and -not $spikeText.Contains('mvnw.cmd')) 'legacy spike delegates build and launch'
    $startText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'StartCodejDev.ps1') -Raw -Encoding UTF8
    Assert-True ($startText.Contains("'--context-maximum-input-tokens'") -and
        $startText.Contains("'--context-reserved-output-tokens'") -and
        $startText.Contains("'--context-safety-margin-tokens'")) 'development launcher always forwards the validated context capacity tuple'
    Assert-True ($startText.Contains("'--model-diagnostics'") -and
        $startText.Contains("'--model-diagnostics-dir'")) 'development launcher forwards explicit diagnostic flags without changing stdio'

    [Console]::Out.WriteLine("CodejDevLauncher self-test passed: $passed assertions.")
}
finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
