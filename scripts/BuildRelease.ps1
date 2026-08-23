[CmdletBinding()]
param(
    [string]$OutputDirectory = "target/release",
    [string]$Version = "0.1.1",
    [ValidateSet('windows-x64', 'linux-x64')]
    [string]$Platform = $(if ($IsWindows) { 'windows-x64' } else { 'linux-x64' }),
    [string]$JavaRuntimeDirectory,
    [string]$NodeRuntimeDirectory,
    [switch]$PublicRelease,
    [switch]$SkipBuild,
    [switch]$SkipTuiBuild
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$mavenWrapper = Join-Path $root $(if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' })
$versionPattern = '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$'
if ($Version -notmatch $versionPattern) { throw 'Version must be a SemVer value' }
if ($PublicRelease -and -not (Test-Path -LiteralPath (Join-Path $root 'LICENSE') -PathType Leaf)) {
    throw 'Public release requires a maintainer-approved LICENSE'
}
$releaseRoot = [IO.Path]::GetFullPath((Join-Path $root 'target/release'))
$out = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}

# 所有递归删除、移动和 rollback 只能发生在仓库专用 target/release 下。
$releasePrefix = $releaseRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if ($out -ne $releaseRoot -and -not $out.StartsWith($releasePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must stay under target/release'
}
if ($out -eq $releaseRoot) {
    # 默认参数历史上是 target/release；归一化后保持该目录本身。
} elseif ([IO.Path]::GetFileName($out) -in @('', '.', '..')) {
    throw 'OutputDirectory is invalid'
}

function Get-SourceDigest {
    $inputs = @(Get-ChildItem -LiteralPath $root -File -Recurse | Where-Object {
        $relative = [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\','/')
        ($relative -match '^(cc-java-[^/]+/src/(main|test)/|cc-java-tui/(src|test)/|scripts/).+') -and
        $relative -notmatch '(^|/)(target|dist|node_modules|\.claude)(/|$)' -and
        $relative -ne 'generate_henan_weather_xlsx.py'
    } | Sort-Object FullName)
    $accumulator = [Text.StringBuilder]::new()
    foreach ($file in $inputs) {
        $relative = [IO.Path]::GetRelativePath($root, $file.FullName).Replace('\','/')
        [void]$accumulator.Append($relative).Append(':').Append(
            (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()).Append("`n")
    }
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes($accumulator.ToString()))).ToLowerInvariant()
}

function Get-TreeDigest([string]$Directory) {
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { throw 'Compiled TUI directory missing' }
    $accumulator = [Text.StringBuilder]::new()
    foreach ($file in (Get-ChildItem -LiteralPath $Directory -File -Recurse | Sort-Object FullName)) {
        $relative = [IO.Path]::GetRelativePath($Directory, $file.FullName).Replace('\','/')
        [void]$accumulator.Append($relative).Append(':').Append(
            (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()).Append("`n")
    }
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes($accumulator.ToString()))).ToLowerInvariant()
}

$commit = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Current Git commit unavailable' }
$tuiRoot = Join-Path $root 'cc-java-tui'
$tuiDirectory = Join-Path $tuiRoot 'dist/src'
$cli = Join-Path $root 'cc-java-cli/target/cc-java-cli-0.1.1.jar'
$attestationPath = Join-Path $root 'target/codej-build-attestation.json'
New-Item -ItemType Directory -Path (Split-Path -Parent $attestationPath) -Force | Out-Null

$sourceDigest = Get-SourceDigest
$priorAttestation = $null
if ($SkipBuild -or $SkipTuiBuild) {
    try { $priorAttestation = Get-Content -LiteralPath $attestationPath -Raw | ConvertFrom-Json }
    catch { throw 'Skipped build requires a valid build attestation; rebuild the Java and TUI artifacts' }
    if ($priorAttestation.schema -ne 'codej-build-attestation-v1' `
            -or $priorAttestation.currentCommit -ne $commit -or $priorAttestation.sourceDigest -ne $sourceDigest) {
        throw 'Skipped build source identity mismatch; rebuild the Java and TUI artifacts'
    }
}
if (-not $SkipBuild) {
    & $mavenWrapper -q -pl cc-java-cli -am install -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Maven install failed' }
}
if (-not $SkipTuiBuild) {
    $npm = if ($IsWindows) { 'npm.cmd' } else { 'npm' }
    & $npm --prefix $tuiRoot run build
    if ($LASTEXITCODE -ne 0) { throw 'TUI build failed' }
}
if (-not (Test-Path -LiteralPath $cli -PathType Leaf)) { throw 'CLI JAR missing' }
if (-not (Test-Path -LiteralPath (Join-Path $tuiDirectory 'index.js') -PathType Leaf)) { throw 'Compiled TUI entry missing' }

$cliDigest = (Get-FileHash -Algorithm SHA256 -LiteralPath $cli).Hash.ToLowerInvariant()
$tuiDigest = Get-TreeDigest $tuiDirectory
if (($SkipBuild -and $priorAttestation.cliDigest -ne $cliDigest) `
        -or ($SkipTuiBuild -and $priorAttestation.tuiDigest -ne $tuiDigest)) {
    throw 'Skipped build artifact identity mismatch; rebuild the Java and TUI artifacts'
}
[ordered]@{
    schema = 'codej-build-attestation-v1'
    currentCommit = $commit
    sourceDigest = $sourceDigest
    cliDigest = $cliDigest
    tuiDigest = $tuiDigest
} | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $attestationPath -Encoding utf8NoBOM

$runtimeDependencies = Join-Path $root 'cc-java-cli/target/release-dependency'
Remove-Item -LiteralPath $runtimeDependencies -Recurse -Force -ErrorAction SilentlyContinue
& $mavenWrapper -q -pl cc-java-cli -am install -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Maven install failed' }
& $mavenWrapper -q -pl cc-java-cli dependency:copy-dependencies `
    "-DincludeScope=runtime" "-DoutputDirectory=$runtimeDependencies"
if ($LASTEXITCODE -ne 0) { throw 'Runtime dependency collection failed' }

# 部分第三方 JAR 不携带 META-INF/maven/**/pom.properties；Maven resolver 输出作为
# 等价的确定性坐标来源。后续仍要求每个 JAR 恰好解析出一个坐标，绝不猜文件名。
$coordinateFile = Join-Path $root "target/release-runtime-coordinates-$PID.txt"
Remove-Item -LiteralPath $coordinateFile -Force -ErrorAction SilentlyContinue
& $mavenWrapper -q -pl cc-java-cli dependency:list `
    "-DincludeScope=runtime" "-DoutputAbsoluteArtifactFilename=true" `
    "-DoutputFile=$coordinateFile"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $coordinateFile -PathType Leaf)) {
    throw 'Runtime dependency coordinate collection failed'
}
$resolvedCoordinates = [Collections.Generic.List[object]]::new()
foreach ($line in Get-Content -LiteralPath $coordinateFile) {
    if ($line -notmatch '^\s*(.+):(compile|runtime):(.+?)(?: -- module .*)?$') {
        continue
    }
    $prefix = @($Matches[1] -split ':')
    if ($prefix.Count -notin @(4, 5)) { throw 'Unexpected Maven dependency coordinate shape' }
    $coordinate = [ordered]@{ group = $prefix[0]; name = $prefix[1]; version = $prefix[-1] }
    $sourcePath = ($Matches[3] -replace "`e\[[0-9;]*m", '').Trim().Trim("'").Trim('"')
    $jarName = [IO.Path]::GetFileName($sourcePath).Trim()
    if ([string]::IsNullOrWhiteSpace($jarName)) { throw 'Resolved dependency path is invalid' }
    $existing = @($resolvedCoordinates | Where-Object { $_.jarName -ceq $jarName })
    if ($existing.Count -gt 0) {
        if ($existing.Count -ne 1 -or $existing[0].group -ne $coordinate.group `
                -or $existing[0].name -ne $coordinate.name `
                -or $existing[0].version -ne $coordinate.version) {
            throw "Ambiguous Maven coordinate for runtime JAR: $jarName"
        }
    } else {
        $resolvedCoordinates.Add([pscustomobject]@{
            jarName = $jarName
            sourcePath = $sourcePath
            sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash
            group = $coordinate.group
            name = $coordinate.name
            version = $coordinate.version
        })
    }
}
Remove-Item -LiteralPath $coordinateFile -Force
if ($resolvedCoordinates.Count -eq 0) { throw 'No runtime dependency coordinates resolved' }

$staging = "$out.staging-$PID"
$backup = "$out.rollback-$PID"
foreach ($candidate in @($staging, $backup)) {
    $full = [IO.Path]::GetFullPath($candidate)
    $generatedPrefix = $releaseRoot + '.'
    $insideRelease = $full.StartsWith($releasePrefix, [StringComparison]::OrdinalIgnoreCase)
    $generatedSibling = $full.StartsWith($generatedPrefix, [StringComparison]::OrdinalIgnoreCase)
    if (-not $insideRelease -and -not $generatedSibling) {
        throw 'Internal release path escaped target/release'
    }
    Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Path (Join-Path $staging 'app') -Force | Out-Null
Copy-Item -LiteralPath $cli -Destination (Join-Path $staging 'app/cc-java-cli.jar')
Copy-Item -Path (Join-Path $runtimeDependencies '*.jar') -Destination (Join-Path $staging 'app')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'codej-release.cmd') -Destination (Join-Path $staging 'codej.cmd')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'codej-release.sh') -Destination (Join-Path $staging 'codej')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'codej-launcher.mjs') -Destination (Join-Path $staging 'codej-launcher.mjs')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.ps1') -Destination (Join-Path $staging 'install.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.sh') -Destination (Join-Path $staging 'install.sh')
if (-not $IsWindows) {
    & chmod '+x' (Join-Path $staging 'codej') (Join-Path $staging 'install.sh')
    if ($LASTEXITCODE -ne 0) { throw 'Linux launcher permission setup failed' }
}

# 生产 TUI 运行编译后的 JavaScript；tsx、TypeScript、Vitest 与测试源码不进入发行物。
$stagingTui = Join-Path $staging 'tui'
New-Item -ItemType Directory -Path (Join-Path $stagingTui 'dist') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $tuiRoot 'dist/src') -Destination (Join-Path $stagingTui 'dist/src') -Recurse
Copy-Item -LiteralPath (Join-Path $tuiRoot 'package.json') -Destination $stagingTui
Copy-Item -LiteralPath (Join-Path $tuiRoot 'package-lock.json') -Destination $stagingTui
$npm = if ($IsWindows) { 'npm.cmd' } else { 'npm' }
& $npm --prefix $stagingTui ci --omit=dev --ignore-scripts --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'Production TUI dependency installation failed' }

if (-not [string]::IsNullOrWhiteSpace($JavaRuntimeDirectory)) {
    $source = [IO.Path]::GetFullPath($JavaRuntimeDirectory)
    if (-not (Test-Path -LiteralPath $source -PathType Container)) { throw 'Java runtime directory missing' }
    New-Item -ItemType Directory -Path (Join-Path $staging 'runtime') -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination (Join-Path $staging 'runtime/java') -Recurse
}
if (-not [string]::IsNullOrWhiteSpace($NodeRuntimeDirectory)) {
    $source = [IO.Path]::GetFullPath($NodeRuntimeDirectory)
    $nodeBinary = if ($Platform -eq 'windows-x64') {
        Join-Path $source 'node.exe'
    } else {
        Join-Path $source 'bin/node'
    }
    if (-not (Test-Path -LiteralPath $nodeBinary -PathType Leaf)) { throw 'Node runtime executable missing' }
    $nodeTarget = if ($Platform -eq 'windows-x64') {
        Join-Path $staging 'runtime/node'
    } else {
        Join-Path $staging 'runtime/node/bin'
    }
    New-Item -ItemType Directory -Path $nodeTarget -Force | Out-Null
    Copy-Item -LiteralPath $nodeBinary -Destination $nodeTarget
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-UniqueProperty([string[]]$Lines, [string]$Name, [string]$JarName) {
    $values = @($Lines | ForEach-Object {
        if ($_ -match "^$([regex]::Escape($Name))\s*[=:]\s*(.+?)\s*$") { $Matches[1] }
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    if ($values.Count -ne 1) { throw "Missing or ambiguous $Name in Maven metadata: $JarName" }
    return $values[0]
}
function Read-JarCoordinate([IO.FileInfo]$Jar, [Collections.Generic.List[object]]$ResolverCoordinates) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Jar.FullName)
    try {
        $entries = @($archive.Entries | Where-Object {
            $_.FullName -match '^META-INF/maven/[^/]+/[^/]+/pom\.properties$'
        })
        if ($entries.Count -gt 1) { throw "Ambiguous Maven metadata entries: $($Jar.Name)" }
        if ($entries.Count -eq 1) {
            $reader = [IO.StreamReader]::new($entries[0].Open(), [Text.Encoding]::UTF8, $true)
            try { $lines = @($reader.ReadToEnd() -split "`r?`n") } finally { $reader.Dispose() }
            return [ordered]@{
                group = Read-UniqueProperty $lines 'groupId' $Jar.Name
                name = Read-UniqueProperty $lines 'artifactId' $Jar.Name
                version = Read-UniqueProperty $lines 'version' $Jar.Name
            }
        }
    } finally {
        $archive.Dispose()
    }
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar.FullName).Hash
    $matches = @($ResolverCoordinates | Where-Object { $_.sourceHash -eq $jarHash })
    if ($matches.Count -ne 1) {
        throw "Maven coordinate metadata missing or ambiguous for JAR: $($Jar.Name)"
    }
    return [ordered]@{
        group = $matches[0].group
        name = $matches[0].name
        version = $matches[0].version
    }
}

$componentList = [Collections.Generic.List[object]]::new()
$cliCoordinate = $null
foreach ($jar in (Get-ChildItem -LiteralPath (Join-Path $staging 'app') -Filter '*.jar' -File |
        Sort-Object Name)) {
    $coordinate = Read-JarCoordinate $jar $resolvedCoordinates
    if ($jar.Name -eq 'cc-java-cli.jar') { $cliCoordinate = $coordinate }
    $encodedGroup = [Uri]::EscapeDataString($coordinate.group)
    $encodedName = [Uri]::EscapeDataString($coordinate.name)
    $encodedVersion = [Uri]::EscapeDataString($coordinate.version)
    $componentList.Add([ordered]@{
        type = 'library'
        group = $coordinate.group
        name = $coordinate.name
        version = $coordinate.version
        purl = "pkg:maven/$encodedGroup/$encodedName@$encodedVersion"
        hashes = @([ordered]@{
            alg = 'SHA-256'
            content = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.ToLowerInvariant()
        })
    })
}
if ($componentList.Count -eq 0 -or $null -eq $cliCoordinate) {
    throw 'Release SBOM requires non-empty components and the CLI Maven coordinate'
}
$nodeLock = Get-Content -LiteralPath (Join-Path $stagingTui 'package-lock.json') -Raw |
    ConvertFrom-Json -Depth 100 -AsHashtable
foreach ($packageName in @('ink', 'marked', 'react')) {
    $package = $nodeLock['packages']["node_modules/$packageName"]
    if ($null -eq $package -or [string]::IsNullOrWhiteSpace($package.version)) {
        throw "TUI dependency metadata missing: $packageName"
    }
    $componentList.Add([ordered]@{
        type = 'library'
        group = ''
        name = $packageName
        version = $package.version
        purl = "pkg:npm/$([Uri]::EscapeDataString($packageName))@$([Uri]::EscapeDataString($package.version))"
    })
}
$sbom = [ordered]@{
    bomFormat = 'CycloneDX'
    specVersion = '1.6'
    serialNumber = "urn:uuid:$([guid]::NewGuid())"
    version = 1
    metadata = [ordered]@{
        component = [ordered]@{
            type = 'application'
            group = $cliCoordinate.group
            name = $cliCoordinate.name
            version = $cliCoordinate.version
            purl = "pkg:maven/$([Uri]::EscapeDataString($cliCoordinate.group))/$([Uri]::EscapeDataString($cliCoordinate.name))@$([Uri]::EscapeDataString($cliCoordinate.version))"
        }
    }
    components = @($componentList)
}
$sbom | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $staging 'sbom.cdx.json') -Encoding utf8NoBOM

# staging 必须再次对账受控 build attestation，复制不能改变被证明的产物身份。
$stagedCliDigest = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $staging 'app/cc-java-cli.jar')).Hash.ToLowerInvariant()
$stagedTuiDigest = Get-TreeDigest (Join-Path $staging 'tui/dist/src')
if ($stagedCliDigest -ne $cliDigest -or $stagedTuiDigest -ne $tuiDigest) {
    throw 'Staged artifacts do not match the build attestation'
}

$artifactFiles = Get-ChildItem -LiteralPath $staging -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS' |
    Sort-Object FullName
$manifest = [ordered]@{
    schema = 'cc-java-release-manifest-v1'
    version = $Version
    platform = $Platform
    product = 'codej'
    build = [ordered]@{ currentCommit = $commit; sourceDigest = $sourceDigest; cliDigest = $cliDigest; tuiDigest = $tuiDigest }
    entrypoint = $(if ($Platform -eq 'windows-x64') { 'codej.cmd' } else { 'codej' })
    compatibility = [ordered]@{
        protocolMajors=@(0,1)
        sessionExportMajor=1
        minimumJava=21
        minimumNode=22
    }
    # 当前尚未写 manifest 与 SHA256SUMS，因此最终总数需加二。
    artifacts = $artifactFiles.Count + 2
    publicReleaseAllowed = [bool]$PublicRelease
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $staging 'release-manifest.json') -Encoding utf8NoBOM

# Manifest 与 SBOM 均写入后再计算 checksum；SHA256SUMS 自身不自引用。
$checksumFiles = Get-ChildItem -LiteralPath $staging -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS' |
    Sort-Object FullName
$checksums = foreach ($file in $checksumFiles) {
    $relative = [IO.Path]::GetRelativePath($staging, $file.FullName).Replace('\','/')
    "$(Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName | Select-Object -ExpandProperty Hash)  $relative"
}
$checksums | Set-Content -LiteralPath (Join-Path $staging 'SHA256SUMS') -Encoding utf8NoBOM

# 发布 staging 前立即复验每个 checksum，避免复制期间损坏进入 current candidate。
foreach ($line in Get-Content -LiteralPath (Join-Path $staging 'SHA256SUMS')) {
    if ($line -notmatch '^([0-9A-Fa-f]{64})  (.+)$') { throw 'Invalid checksum manifest' }
    $expected = $Matches[1]
    $relative = $Matches[2]
    $candidate = [IO.Path]::GetFullPath((Join-Path $staging $relative))
    $stagingPrefix = [IO.Path]::GetFullPath($staging).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($stagingPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Checksum entry escaped staging'
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidate).Hash
    if ($actual -ne $expected) { throw "Checksum mismatch: $relative" }
}

if (Test-Path -LiteralPath $out) {
    Move-Item -LiteralPath $out -Destination $backup
    try {
        Move-Item -LiteralPath $staging -Destination $out
        Remove-Item -LiteralPath $backup -Recurse -Force
    } catch {
        if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
        Move-Item -LiteralPath $backup -Destination $out
        throw
    }
} else {
    Move-Item -LiteralPath $staging -Destination $out
}
Write-Output "release candidate: $out"
