Set-StrictMode -Version Latest

$script:CodejJavaModules = @(
    'cc-java-domain',
    'cc-java-core',
    'cc-java-model-spring-ai',
    'cc-java-tools-local',
    'cc-java-cli'
)
$script:CodejShimMarker = 'CC_JAVA_CODEJ_DEV_SHIM'
$script:CodejShimSchema = '1'
$script:CodejMetadataSchema = 1
$script:DefaultContextMaximumInputTokens = 256000L
$script:DefaultContextReservedOutputTokens = 8192L
$script:DefaultContextSafetyMarginTokens = 4096L

function ConvertTo-CodejAbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$BasePath
    )

    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

function ConvertFrom-CodejArguments {
    param(
        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [AllowEmptyCollection()]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$InvocationDirectory
    )

    if ($null -eq $Arguments) { $Arguments = @() }
    if ($Arguments.Count -gt 0 -and $Arguments[0] -in @('providers', 'auth', 'models')) {
        if ($Arguments | Where-Object { $_ -match "[`r`n`0]" }) {
            throw 'Provider 控制参数包含非法控制字符。'
        }
        return [pscustomobject]@{
            Workspace = [IO.Path]::GetFullPath($InvocationDirectory)
            ProviderControlArguments = @($Arguments)
            Model = $null; Timeout = '30m'; Print = $null; Continue = $false; Resume = $null; Fork = $null
            ContextMaximumInputTokens = $script:DefaultContextMaximumInputTokens
            ContextReservedOutputTokens = $script:DefaultContextReservedOutputTokens
            ContextSafetyMarginTokens = $script:DefaultContextSafetyMarginTokens
            ModelDiagnostics = 'off'; ModelDiagnosticsDirectory = $null
            Rebuild = $false; Doctor = $false; Help = $false
        }
    }
    $values = @{}
    $flags = @{}
    $remaining = [Collections.Generic.List[string]]::new()
    $stopped = $false

    for ($index = 0; $index -lt $Arguments.Count; $index++) {
        $argument = $Arguments[$index]
        if ($stopped) {
            $remaining.Add($argument)
            continue
        }
        if ($argument -eq '--') {
            $stopped = $true
            continue
        }

        $name = $argument
        $inlineValue = $null
        $hasInlineValue = $false
        if ($argument.StartsWith('--') -and $argument.Contains('=')) {
            $parts = $argument.Split('=', 2)
            $name = $parts[0]
            $inlineValue = $parts[1]
            $hasInlineValue = $true
            # cmd.exe → pwsh -File 会移除 --name="value with spaces" 中的引号，
            # 并把等号后的值拆成多个 argv。只为已知带值参数拼回连续的非选项片段；
            # 下一项以 -- 开头时立即停止，避免吞掉后续参数。
            if ($name -eq '--workspace' -and $inlineValue -match '^[A-Za-z]$' -and
                $index + 1 -lt $Arguments.Count -and $Arguments[$index + 1].StartsWith('\')) {
                # PowerShell -File 经 cmd.exe 转发 --workspace="G:\path with spaces" 时，
                # 会把 drive colon 从首片段剥离，并把其余绝对路径放进下一 argv。
                $index++
                $inlineValue = "$inlineValue`:$($Arguments[$index])"
            }
            while ($index + 1 -lt $Arguments.Count -and -not $Arguments[$index + 1].StartsWith('--')) {
                $index++
                $inlineValue = "$inlineValue $($Arguments[$index])"
            }
        }

        if ($name -in @('--rebuild', '--doctor', '--help', '--continue')) {
            if ($hasInlineValue) {
                throw "参数 $name 不接受值。"
            }
            if ($flags.ContainsKey($name)) {
                throw "参数 $name 不能重复。"
            }
            $flags[$name] = $true
            continue
        }

        if ($name -notin @(
            '--workspace', '--model', '--timeout', '--print', '--resume', '--fork',
            '--context-maximum-input-tokens', '--context-reserved-output-tokens',
            '--context-safety-margin-tokens', '--model-diagnostics', '--model-diagnostics-dir'
        )) {
            throw "未知参数：$argument"
        }
        if ($values.ContainsKey($name)) {
            throw "参数 $name 不能重复。"
        }

        $value = $inlineValue
        if (-not $hasInlineValue) {
            $index++
            if ($index -ge $Arguments.Count -or $Arguments[$index] -eq '--') {
                throw "参数 $name 缺少值。"
            }
            $value = $Arguments[$index]
        }
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "参数 $name 的值不能为空。"
        }
        $values[$name] = $value
    }

    if ($remaining.Count -gt 0) {
        throw "-- 后暂不支持额外参数：$($remaining -join ' ')"
    }
    if ($flags.ContainsKey('--help') -and ($values.Count -gt 0 -or $flags.Count -gt 1)) {
        throw '--help 不能与其他参数组合。'
    }
    if ($flags.ContainsKey('--doctor') -and ($values.ContainsKey('--print') -or $flags.ContainsKey('--rebuild'))) {
        throw '--doctor 不能与 --print 或 --rebuild 组合。'
    }
    $sessionSelections = @(@(
        $flags.ContainsKey('--continue'),
        $values.ContainsKey('--resume'),
        $values.ContainsKey('--fork')
    ) | Where-Object { $_ })
    if ($sessionSelections.Count -gt 1) {
        throw '--continue、--resume 和 --fork 只能选择一个。'
    }

    $contextMaximum = Get-CodejTokenOption -Values $values -Name '--context-maximum-input-tokens' `
        -DefaultValue $script:DefaultContextMaximumInputTokens
    $contextReserved = Get-CodejTokenOption -Values $values -Name '--context-reserved-output-tokens' `
        -DefaultValue $script:DefaultContextReservedOutputTokens
    $contextMargin = Get-CodejTokenOption -Values $values -Name '--context-safety-margin-tokens' `
        -DefaultValue $script:DefaultContextSafetyMarginTokens
    if ($contextMaximum -le $contextReserved + $contextMargin) {
        throw 'Context 输入上限必须大于输出保留与安全余量之和。'
    }
    $diagnosticMode = if ($values.ContainsKey('--model-diagnostics')) {
        ([string]$values['--model-diagnostics']).ToLowerInvariant()
    } else { 'off' }
    if ($diagnosticMode -notin @('off', 'safe', 'verbose')) {
        throw '--model-diagnostics 只接受 off、safe 或 verbose。'
    }
    if ($diagnosticMode -eq 'off' -and $values.ContainsKey('--model-diagnostics-dir')) {
        throw '--model-diagnostics-dir 仅可与 safe 或 verbose 一起使用。'
    }

    $workspace = if ($values.ContainsKey('--workspace')) {
        ConvertTo-CodejAbsolutePath -Path $values['--workspace'] -BasePath $InvocationDirectory
    }
    else {
        [IO.Path]::GetFullPath($InvocationDirectory)
    }

    return [pscustomobject]@{
        Workspace = $workspace
        Model = if ($values.ContainsKey('--model')) { $values['--model'] } else { $null }
        Timeout = if ($values.ContainsKey('--timeout')) { $values['--timeout'] } else { '30m' }
        Print = if ($values.ContainsKey('--print')) { $values['--print'] } else { $null }
        Continue = $flags.ContainsKey('--continue')
        Resume = if ($values.ContainsKey('--resume')) { $values['--resume'] } else { $null }
        Fork = if ($values.ContainsKey('--fork')) { $values['--fork'] } else { $null }
        ContextMaximumInputTokens = $contextMaximum
        ContextReservedOutputTokens = $contextReserved
        ContextSafetyMarginTokens = $contextMargin
        ModelDiagnostics = $diagnosticMode
        ModelDiagnosticsDirectory = if ($values.ContainsKey('--model-diagnostics-dir')) {
            ConvertTo-CodejAbsolutePath -Path $values['--model-diagnostics-dir'] -BasePath $InvocationDirectory
        } else { $null }
        Rebuild = $flags.ContainsKey('--rebuild')
        Doctor = $flags.ContainsKey('--doctor')
        Help = $flags.ContainsKey('--help')
    }
}

function Get-CodejTokenOption {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][long]$DefaultValue
    )
    if (-not $Values.ContainsKey($Name)) { return $DefaultValue }
    $parsed = 0L
    if (-not [long]::TryParse([string]$Values[$Name], [ref]$parsed) -or $parsed -le 0 -or $parsed -gt [int]::MaxValue) {
        throw "参数 $Name 必须是 1 到 $([int]::MaxValue) 之间的整数。"
    }
    return $parsed
}

function Get-CodejHelpText {
    return @'
codej - cc-java 源码开发启动器

用法：
  codej [--workspace <path>] [--model <name>] [--timeout <duration>] [--continue | --resume <session-id> | --fork <session-id>]
  codej --print <prompt> [--workspace <path>] [--model <name>] [--timeout <duration>] [--continue | --resume <session-id> | --fork <session-id>]
  codej --doctor [--workspace <path>]
  codej --rebuild [其他启动参数]
  codej --help

说明：
  未指定 --workspace 时，使用执行 codej 时的当前目录。
  --print 是一次性非交互 Run；不表示进入 TUI 后预填消息。
  --timeout 默认 30m，作为 --print 的总 Run 硬限制；普通交互与 Plan 不装配总 Run deadline。
  --continue、--resume 和 --fork 选择同一 Workspace 下的持久 Session。
  默认启用 256000 Token Context 管线；可用 --context-maximum-input-tokens、
  --context-reserved-output-tokens 和 --context-safety-margin-tokens 显式覆盖。
  --rebuild 忽略开发构建缓存并强制执行 Maven 增量构建。
'@
}

function Get-CodejCommandInfo {
    param([Parameter(Mandatory = $true)][string]$Name)
    return Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Get-CodejJavaVersion {
    $java = Get-CodejCommandInfo -Name 'java'
    if (-not $java) {
        return [pscustomobject]@{ Present = $false; Supported = $false; Description = 'not found' }
    }
    $lines = @(& $java.Source '-version' 2>&1)
    $description = ($lines | Select-Object -First 1).ToString()
    $match = [regex]::Match($description, 'version\s+"(?<version>\d+)')
    $major = if ($match.Success) { [int]$match.Groups['version'].Value } else { 0 }
    return [pscustomobject]@{
        Present = $true
        Supported = $major -ge 21
        Description = $description
        Executable = $java.Source
        Major = $major
    }
}

function Get-CodejNodeVersion {
    $node = Get-CodejCommandInfo -Name 'node'
    if (-not $node) {
        return [pscustomobject]@{ Present = $false; Supported = $false; Description = 'not found' }
    }
    $description = (& $node.Source '--version').Trim()
    $match = [regex]::Match($description, '^v(?<version>\d+)')
    $major = if ($match.Success) { [int]$match.Groups['version'].Value } else { 0 }
    return [pscustomobject]@{
        Present = $true
        Supported = $major -ge 22
        Description = $description
        Executable = $node.Source
        Major = $major
    }
}

function Get-CodejBuildPaths {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $stateDirectory = Join-Path $RepositoryRoot 'target\codej-dev'
    return [pscustomobject]@{
        StateDirectory = $stateDirectory
        FingerprintFile = Join-Path $stateDirectory 'build-fingerprint.txt'
        LockFile = Join-Path $stateDirectory 'build.lock'
        ClasspathFile = Join-Path $RepositoryRoot 'cc-java-cli\target\codej-runtime-classpath.txt'
        MainClassFile = Join-Path $RepositoryRoot 'cc-java-cli\target\classes\io\github\liumaishenjian\ccjava\cli\CcJavaCliMain.class'
    }
}

function Get-CodejHashInputFiles {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $files = [Collections.Generic.List[IO.FileInfo]]::new()
    foreach ($relative in @('pom.xml', 'mvnw', 'mvnw.cmd')) {
        $path = Join-Path $RepositoryRoot $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $files.Add((Get-Item -LiteralPath $path))
        }
    }
    $wrapperDirectory = Join-Path $RepositoryRoot '.mvn\wrapper'
    if (Test-Path -LiteralPath $wrapperDirectory -PathType Container) {
        foreach ($file in Get-ChildItem -LiteralPath $wrapperDirectory -File -Recurse) {
            $files.Add($file)
        }
    }
    foreach ($module in $script:CodejJavaModules) {
        $moduleRoot = Join-Path $RepositoryRoot $module
        $pom = Join-Path $moduleRoot 'pom.xml'
        if (Test-Path -LiteralPath $pom -PathType Leaf) {
            $files.Add((Get-Item -LiteralPath $pom))
        }
        foreach ($sourceRelative in @('src\main\java', 'src\main\resources')) {
            $sourceRoot = Join-Path $moduleRoot $sourceRelative
            if (Test-Path -LiteralPath $sourceRoot -PathType Container) {
                foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse) {
                    $files.Add($file)
                }
            }
        }
    }
    return @($files | Sort-Object FullName -Unique)
}

function Get-CodejBuildFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [string]$JavaDescription
    )

    if ([string]::IsNullOrWhiteSpace($JavaDescription)) {
        $JavaDescription = (Get-CodejJavaVersion).Description
    }
    $root = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $prefix = [Text.Encoding]::UTF8.GetBytes("schema=1`njdk=$JavaDescription`n")
        $null = $sha.TransformBlock($prefix, 0, $prefix.Length, $null, 0)
        foreach ($file in Get-CodejHashInputFiles -RepositoryRoot $root) {
            $relative = [IO.Path]::GetRelativePath($root, $file.FullName).Replace('\', '/')
            $header = [Text.Encoding]::UTF8.GetBytes("path=$relative`nlength=$($file.Length)`n")
            $null = $sha.TransformBlock($header, 0, $header.Length, $null, 0)
            $stream = [IO.File]::OpenRead($file.FullName)
            try {
                $buffer = New-Object byte[] 65536
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $null = $sha.TransformBlock($buffer, 0, $read, $null, 0)
                }
            }
            finally {
                $stream.Dispose()
            }
        }
        $classpathMarker = [Text.Encoding]::UTF8.GetBytes("runtime-classpath-scope=runtime`n")
        $null = $sha.TransformFinalBlock($classpathMarker, 0, $classpathMarker.Length)
        return [Convert]::ToHexString($sha.Hash).ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Test-CodejBuildOutputs {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $missing = [Collections.Generic.List[string]]::new()
    foreach ($module in $script:CodejJavaModules) {
        $classes = Join-Path $RepositoryRoot "$module\target\classes"
        if (-not (Test-Path -LiteralPath $classes -PathType Container)) {
            $missing.Add("$module/target/classes")
        }
    }
    $paths = Get-CodejBuildPaths -RepositoryRoot $RepositoryRoot
    if (-not (Test-Path -LiteralPath $paths.MainClassFile -PathType Leaf)) {
        $missing.Add('cc-java-cli main class')
    }
    if (-not (Test-Path -LiteralPath $paths.ClasspathFile -PathType Leaf)) {
        $missing.Add('runtime classpath file')
    }
    elseif ([string]::IsNullOrWhiteSpace((Get-Content -LiteralPath $paths.ClasspathFile -Raw -Encoding UTF8))) {
        $missing.Add('non-empty runtime classpath file')
    }
    return [pscustomobject]@{ Complete = $missing.Count -eq 0; Missing = @($missing) }
}

function Get-CodejBuildState {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [string]$JavaDescription
    )

    $paths = Get-CodejBuildPaths -RepositoryRoot $RepositoryRoot
    $current = Get-CodejBuildFingerprint -RepositoryRoot $RepositoryRoot -JavaDescription $JavaDescription
    $recorded = if (Test-Path -LiteralPath $paths.FingerprintFile -PathType Leaf) {
        (Get-Content -LiteralPath $paths.FingerprintFile -Raw -Encoding UTF8).Trim()
    }
    else { '' }
    $outputs = Test-CodejBuildOutputs -RepositoryRoot $RepositoryRoot
    return [pscustomobject]@{
        CurrentFingerprint = $current
        RecordedFingerprint = $recorded
        Outputs = $outputs
        Reusable = $outputs.Complete -and $current -eq $recorded
        Paths = $paths
    }
}

function Test-CodejProcessAlive {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Enter-CodejBuildLock {
    param(
        [Parameter(Mandatory = $true)][string]$LockFile,
        [int]$TimeoutSeconds = 120
    )

    $directory = Split-Path -Parent $LockFile
    $null = New-Item -ItemType Directory -Path $directory -Force
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $announced = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $stream = [IO.File]::Open($LockFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $payload = [Text.Encoding]::UTF8.GetBytes("schema=1`npid=$PID`n")
            $stream.Write($payload, 0, $payload.Length)
            $stream.Flush($true)
            return [pscustomobject]@{ Stream = $stream; Path = $LockFile }
        }
        catch [IO.IOException] {
            $ownerPid = 0
            try {
                $content = Get-Content -LiteralPath $LockFile -Raw -ErrorAction Stop
                $match = [regex]::Match($content, '(?m)^pid=(?<pid>\d+)$')
                if ($match.Success) { $ownerPid = [int]$match.Groups['pid'].Value }
            }
            catch { }
            if ($ownerPid -gt 0 -and -not (Test-CodejProcessAlive -ProcessId $ownerPid)) {
                Remove-Item -LiteralPath $LockFile -Force -ErrorAction SilentlyContinue
                continue
            }
            if (-not $announced) {
                [Console]::Error.WriteLine('[codej] Another terminal is building cc-java; waiting for the development build lock.')
                $announced = $true
            }
            Start-Sleep -Milliseconds 200
        }
    }
    throw "等待开发构建锁超时：$LockFile"
}

function Exit-CodejBuildLock {
    param([Parameter(Mandatory = $true)]$Lock)
    try { $Lock.Stream.Dispose() } finally { Remove-Item -LiteralPath $Lock.Path -Force -ErrorAction SilentlyContinue }
}

function Invoke-CodejJavaBuild {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [switch]$Force
    )

    $java = Get-CodejJavaVersion
    if (-not $java.Present -or -not $java.Supported) {
        throw "codej 需要 JDK 21 或更高版本；当前：$($java.Description)"
    }
    $paths = Get-CodejBuildPaths -RepositoryRoot $RepositoryRoot
    $state = Get-CodejBuildState -RepositoryRoot $RepositoryRoot -JavaDescription $java.Description
    if (-not $Force -and $state.Reusable) {
        [Console]::Error.WriteLine('[codej] Reusing verified Java development build outputs.')
        return $state
    }

    $lock = Enter-CodejBuildLock -LockFile $paths.LockFile
    try {
        $state = Get-CodejBuildState -RepositoryRoot $RepositoryRoot -JavaDescription $java.Description
        if (-not $Force -and $state.Reusable) {
            [Console]::Error.WriteLine('[codej] Reusing outputs built by another terminal.')
            return $state
        }
        [Console]::Error.WriteLine('[codej] Building Java Headless; the first run may take 1-2 minutes.')
        $maven = Join-Path $RepositoryRoot 'mvnw.cmd'
        $arguments = @(
            '-q', '--file', (Join-Path $RepositoryRoot 'pom.xml'),
            '-pl', 'cc-java-cli', '-am', 'package', '-DskipTests',
            'dependency:build-classpath', '-Dmdep.includeScope=runtime',
            '-Dmdep.outputFile=target/codej-runtime-classpath.txt'
        )
        & $maven @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Maven 构建失败，退出码：$LASTEXITCODE"
        }
        $state = Get-CodejBuildState -RepositoryRoot $RepositoryRoot -JavaDescription $java.Description
        if (-not $state.Outputs.Complete) {
            throw "构建结束但产物不完整：$($state.Outputs.Missing -join ', ')"
        }
        $null = New-Item -ItemType Directory -Path $paths.StateDirectory -Force
        [IO.File]::WriteAllText($paths.FingerprintFile, "$($state.CurrentFingerprint)`n", [Text.UTF8Encoding]::new($false))
        [Console]::Error.WriteLine('[codej] Java Headless build completed.')
        return Get-CodejBuildState -RepositoryRoot $RepositoryRoot -JavaDescription $java.Description
    }
    finally {
        Exit-CodejBuildLock -Lock $lock
    }
}

function Find-CodejRipgrep {
    $configured = $env:CC_JAVA_RIPGREP_PATH
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        try {
            $candidate = [IO.Path]::GetFullPath($configured)
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
        catch { }
        return $null
    }
    $command = Get-CodejCommandInfo -Name 'rg'
    if ($command) { return $command.Source }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $root = Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\bin'
        if (Test-Path -LiteralPath $root -PathType Container) {
            $candidate = Get-ChildItem -LiteralPath $root -File -Filter 'rg.exe' -Recurse -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
            if ($candidate) { return $candidate.FullName }
        }
    }
    return $null
}

function Get-CodejDoctorReport {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$WorkspaceRoot,
        [Parameter(Mandatory = $true)][string]$InstallationRoot
    )

    $repository = [IO.Path]::GetFullPath($RepositoryRoot)
    $workspace = [IO.Path]::GetFullPath($WorkspaceRoot)
    $launcher = Join-Path $repository 'scripts\StartCodejDev.ps1'
    $providerFile = Join-Path $repository 'config\provider.local.properties'
    $providerExists = Test-Path -LiteralPath $providerFile -PathType Leaf
    $java = Get-CodejJavaVersion
    $node = Get-CodejNodeVersion
    $build = if (Test-Path -LiteralPath (Join-Path $repository 'pom.xml') -PathType Leaf) {
        Get-CodejBuildState -RepositoryRoot $repository -JavaDescription $java.Description
    }
    else { $null }
    $envConfigured = @(
        'CC_JAVA_OPENAI_BASE_URL', 'CC_JAVA_OPENAI_API_KEY', 'CC_JAVA_OPENAI_MODEL'
    ) | ForEach-Object { -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
    $source = if ($envConfigured -contains $true -and $providerExists) { 'environment and local file may contribute' }
        elseif ($envConfigured -contains $true) { 'environment variables present' }
        elseif ($providerExists) { 'local file present' }
        else { 'no source detected' }

    return [pscustomobject]@{
        InstallationRoot = [IO.Path]::GetFullPath($InstallationRoot)
        RepositoryRoot = $repository
        WorkspaceRoot = $workspace
        LauncherPresent = Test-Path -LiteralPath $launcher -PathType Leaf
        PowerShellSupported = $PSVersionTable.PSEdition -eq 'Core' -and $PSVersionTable.PSVersion.Major -ge 7
        PowerShellVersion = $PSVersionTable.PSVersion.ToString()
        Java = $java
        Node = $node
        MavenWrapperPresent = Test-Path -LiteralPath (Join-Path $repository 'mvnw.cmd') -PathType Leaf
        BuildReusable = $null -ne $build -and $build.Reusable
        BuildOutputsComplete = $null -ne $build -and $build.Outputs.Complete
        MissingBuildOutputs = if ($null -ne $build) { @($build.Outputs.Missing) } else { @('repository unavailable') }
        TuiDependenciesPresent = Test-Path -LiteralPath (Join-Path $repository 'cc-java-tui\node_modules') -PathType Container
        RipgrepPresent = $null -ne (Find-CodejRipgrep)
        ProviderLocalFilePresent = $providerExists
        ProviderLocalFileRegular = $providerExists -and -not ([IO.FileInfo](Get-Item -LiteralPath $providerFile)).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)
        ProviderBaseUrlEnvironmentPresent = $envConfigured[0]
        ProviderApiKeyEnvironmentPresent = $envConfigured[1]
        ProviderModelEnvironmentPresent = $envConfigured[2]
        ProviderPossibleSource = $source
    }
}

function Format-CodejDoctorReport {
    param([Parameter(Mandatory = $true)]$Report)

    $yesNo = { param($value) if ($value) { 'yes' } else { 'no' } }
    return @(
        'codej development launcher doctor (presence/source checks only)',
        "installationRoot: $($Report.InstallationRoot)",
        "repositoryRoot: $($Report.RepositoryRoot)",
        "workspaceRoot: $($Report.WorkspaceRoot)",
        "launcher present: $(& $yesNo $Report.LauncherPresent)",
        "PowerShell 7+: $(& $yesNo $Report.PowerShellSupported) ($($Report.PowerShellVersion))",
        "JDK 21+: $(& $yesNo $Report.Java.Supported) ($($Report.Java.Description))",
        "Node.js 22+: $(& $yesNo $Report.Node.Supported) ($($Report.Node.Description))",
        "Maven Wrapper present: $(& $yesNo $Report.MavenWrapperPresent)",
        "build cache reusable: $(& $yesNo $Report.BuildReusable)",
        "build outputs complete: $(& $yesNo $Report.BuildOutputsComplete)",
        "TUI dependencies present: $(& $yesNo $Report.TuiDependenciesPresent)",
        "ripgrep discoverable: $(& $yesNo $Report.RipgrepPresent)",
        "Provider local file present: $(& $yesNo $Report.ProviderLocalFilePresent)",
        "Provider local file regular: $(& $yesNo $Report.ProviderLocalFileRegular)",
        "Provider base-url env present: $(& $yesNo $Report.ProviderBaseUrlEnvironmentPresent)",
        "Provider api-key env present: $(& $yesNo $Report.ProviderApiKeyEnvironmentPresent)",
        "Provider model env present: $(& $yesNo $Report.ProviderModelEnvironmentPresent)",
        "Provider possible source: $($Report.ProviderPossibleSource)",
        'Provider validity is checked only by the Java ProviderSettingsLoader at startup.'
    ) -join [Environment]::NewLine
}

function Get-CodejShimMarker { return $script:CodejShimMarker }
function Get-CodejShimSchema { return $script:CodejShimSchema }

function ConvertTo-CodejComparablePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/').ToLowerInvariant()
}

function Test-CodejPathContains {
    param(
        [AllowEmptyString()][string]$PathValue,
        [Parameter(Mandatory = $true)][string]$Candidate
    )
    $expected = ConvertTo-CodejComparablePath -Path $Candidate
    foreach ($entry in @($PathValue -split [IO.Path]::PathSeparator)) {
        if ([string]::IsNullOrWhiteSpace($entry)) { continue }
        try {
            if ((ConvertTo-CodejComparablePath -Path $entry) -eq $expected) { return $true }
        }
        catch { }
    }
    return $false
}

function Add-CodejPathEntry {
    param(
        [AllowEmptyString()][string]$PathValue,
        [Parameter(Mandatory = $true)][string]$Candidate
    )
    if (Test-CodejPathContains -PathValue $PathValue -Candidate $Candidate) { return $PathValue }
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return [IO.Path]::GetFullPath($Candidate) }
    return "$([IO.Path]::GetFullPath($Candidate))$([IO.Path]::PathSeparator)$PathValue"
}

function Remove-CodejPathEntry {
    param(
        [AllowEmptyString()][string]$PathValue,
        [Parameter(Mandatory = $true)][string]$Candidate
    )
    $expected = ConvertTo-CodejComparablePath -Path $Candidate
    $kept = @($PathValue -split [IO.Path]::PathSeparator | Where-Object {
        if ([string]::IsNullOrWhiteSpace($_)) { return $false }
        try { return (ConvertTo-CodejComparablePath -Path $_) -ne $expected } catch { return $true }
    })
    return $kept -join [IO.Path]::PathSeparator
}

function New-CodejShimContent {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    $normalized = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
    $batchPath = $normalized.Replace('%', '%%')
    return @"
@echo off
rem $script:CodejShimMarker
rem schema=$script:CodejShimSchema
set "CODEJ_REPOSITORY=$batchPath"
if not defined CODEJ_INSTALLATION_HOME set "CODEJ_INSTALLATION_HOME=%USERPROFILE%"
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  >&2 echo codej: PowerShell 7 ^(pwsh^) was not found.
  exit /b 2
)
if not exist "%CODEJ_REPOSITORY%\scripts\StartCodejDev.ps1" (
  >&2 echo codej: the cc-java source repository reference is invalid: "%CODEJ_REPOSITORY%"
  >&2 echo codej: reinstall the development command from the repository's new location.
  exit /b 3
)
pwsh.exe -NoLogo -NoProfile -File "%CODEJ_REPOSITORY%\scripts\StartCodejDev.ps1" %*
exit /b %ERRORLEVEL%
"@
}

function Test-CodejOwnedShim {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try {
        $prefix = (Get-Content -LiteralPath $Path -TotalCount 4 -Encoding UTF8) -join "`n"
        return $prefix.Contains($script:CodejShimMarker) -and $prefix.Contains("schema=$script:CodejShimSchema")
    }
    catch { return $false }
}

Export-ModuleMember -Function @(
    'ConvertFrom-CodejArguments', 'Get-CodejHelpText', 'Get-CodejJavaVersion',
    'Get-CodejNodeVersion', 'Get-CodejBuildFingerprint', 'Test-CodejBuildOutputs',
    'Get-CodejBuildState', 'Enter-CodejBuildLock', 'Exit-CodejBuildLock',
    'Invoke-CodejJavaBuild', 'Find-CodejRipgrep', 'Get-CodejDoctorReport',
    'Format-CodejDoctorReport', 'Get-CodejShimMarker', 'Get-CodejShimSchema',
    'ConvertTo-CodejComparablePath', 'Test-CodejPathContains', 'Add-CodejPathEntry',
    'Remove-CodejPathEntry', 'New-CodejShimContent', 'Test-CodejOwnedShim'
)
