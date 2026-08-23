[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$builder = Join-Path $PSScriptRoot 'BuildRelease.ps1'

& $builder
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }

$release = Join-Path $root 'target/release'
$sbomPath = Join-Path $release 'sbom.cdx.json'
$manifestPath = Join-Path $release 'release-manifest.json'
$checksumsPath = Join-Path $release 'SHA256SUMS'
$sbom = Get-Content -LiteralPath $sbomPath -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$components = @($sbom.components)
if ($components.Count -eq 0) { throw 'CycloneDX components must not be empty' }
foreach ($required in @('codej-launcher.mjs', 'install.ps1', 'install.sh', 'tui/dist/src/index.js')) {
    if (-not (Test-Path -LiteralPath (Join-Path $release $required) -PathType Leaf)) {
        throw "Installable release file missing: $required"
    }
}
$installerText = (Get-Content -LiteralPath (Join-Path $release 'install.ps1') -Raw) + "`n" +
    (Get-Content -LiteralPath (Join-Path $release 'install.sh') -Raw)
if ($installerText -notlike '*github.com/liumaishenjian/codej/releases/*' `
        -or $installerText -like '*github.com/liumaishenjian/cc-java/releases/*') {
    throw 'Public installers do not target the codej GitHub repository'
}

function Assert-Coordinate([string]$Group, [string]$Name, [string]$Version) {
    $matches = @($components | Where-Object {
        $_.group -eq $Group -and $_.name -eq $Name -and $_.version -eq $Version
    })
    if ($matches.Count -ne 1) { throw "Expected exactly one coordinate: $Group`:$Name`:$Version" }
    $expectedPurl = "pkg:maven/$([Uri]::EscapeDataString($Group))/$([Uri]::EscapeDataString($Name))@$([Uri]::EscapeDataString($Version))"
    if ($matches[0].purl -ne $expectedPurl) { throw "PURL mismatch: $Group`:$Name`:$Version" }
}

Assert-Coordinate 'info.picocli' 'picocli' '4.7.7'
Assert-Coordinate 'org.springframework.ai' 'spring-ai-anthropic' '2.0.0'
Assert-Coordinate 'com.anthropic' 'anthropic-java-core' '2.40.1'
Assert-Coordinate 'io.github.liumaishenjian' 'cc-java-core' '0.1.1'
$nodeComponents = @($components | Where-Object { $_.purl -like 'pkg:npm/*' })
if ($nodeComponents.Count -lt 3) { throw 'TUI npm components missing from SBOM' }
if ($sbom.metadata.component.group -ne 'io.github.liumaishenjian' `
        -or $sbom.metadata.component.name -ne 'cc-java-cli' `
        -or $sbom.metadata.component.version -ne '0.1.1') {
    throw 'Application Maven coordinate is incorrect'
}

$artifactFiles = @(Get-ChildItem -LiteralPath $release -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS')
$checksumLines = @(Get-Content -LiteralPath $checksumsPath)
if ($checksumLines.Count -ne $artifactFiles.Count) { throw 'Checksum coverage count mismatch' }
foreach ($line in $checksumLines) {
    if ($line -notmatch '^([0-9A-Fa-f]{64})  (.+)$') { throw 'Invalid checksum line' }
    $path = [IO.Path]::GetFullPath((Join-Path $release $Matches[2]))
    $prefix = $release.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Checksum path escaped release root'
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash -ne $Matches[1]) {
        throw "Checksum mismatch: $($Matches[2])"
    }
}
if ($manifest.artifacts -ne ($artifactFiles.Count + 1)) {
    throw 'Manifest artifact count does not include SHA256SUMS exactly once'
}
if ($manifest.publicReleaseAllowed -ne $false) { throw 'Public release must remain disabled' }
if ($manifest.compatibility.minimumNode -ne 22) { throw 'Minimum Node runtime must be 22' }
# 启动器必须拒绝 manifest 指向与实际包不同的 JAR/TUI，避免旧产物冒充当前构建。
$originalManifestText = Get-Content -LiteralPath $manifestPath -Raw
$driftManifest = $originalManifestText | ConvertFrom-Json
$driftManifest.build.cliDigest = '0' * 64
$driftManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
$driftVersion = & (Join-Path $release 'codej.cmd') --version 2>&1
if ($LASTEXITCODE -eq 0 -or ($driftVersion -join "`n") -notlike '*packaged build identity drift detected*') {
    throw 'Launcher did not fail closed on packaged build identity drift'
}
$originalManifestText | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

# 所有会执行包内 Java/TUI 的入口都必须先验证身份，而不只 --version。
$originalCliBytes = [IO.File]::ReadAllBytes((Join-Path $release 'app/cc-java-cli.jar'))
try {
    [IO.File]::WriteAllBytes((Join-Path $release 'app/cc-java-cli.jar'), [byte[]](1, 2, 3))
    $driftStart = & (Join-Path $release 'codej.cmd') --stdio 2>&1
    if ($LASTEXITCODE -eq 0 -or ($driftStart -join "`n") -notlike '*packaged build identity drift detected*') {
        throw 'Ordinary launcher start did not fail closed on packaged build identity drift'
    }
} finally {
    [IO.File]::WriteAllBytes((Join-Path $release 'app/cc-java-cli.jar'), $originalCliBytes)
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$reportedVersion = & (Join-Path $release 'codej.cmd') --version
$currentCommit = (& git -C $root rev-parse HEAD).Trim()
$releaseCliDigest = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $release 'app/cc-java-cli.jar')).Hash.ToLowerInvariant()
$tuiDirectory = Join-Path $release 'tui/dist/src'
$tuiAccumulator = [Text.StringBuilder]::new()
foreach ($file in (Get-ChildItem -LiteralPath $tuiDirectory -File -Recurse | Sort-Object FullName)) {
    $relative = [IO.Path]::GetRelativePath($tuiDirectory, $file.FullName).Replace('\','/')
    [void]$tuiAccumulator.Append($relative).Append(':').Append(
        (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()).Append("`n")
}
$releaseTuiDigest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
    [Text.Encoding]::UTF8.GetBytes($tuiAccumulator.ToString()))).ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $manifest.build.currentCommit -ne $currentCommit `
        -or $manifest.build.cliDigest -ne $releaseCliDigest -or $manifest.build.tuiDigest -ne $releaseTuiDigest `
        -or $reportedVersion -notlike "codej $($manifest.version) commit=$currentCommit source=* cli=$releaseCliDigest tui=$releaseTuiDigest") {
    throw 'Product launcher build identity drift detected'
}
$stdioInput = Join-Path $release 'stdio-input.ndjson'
@(
    '{"version":0,"type":"initialize","requestId":"installed-init","sequence":1,"payload":{}}',
    '{"version":0,"type":"shutdown","requestId":"installed-stop","sequence":2,"payload":{}}'
) | Set-Content -LiteralPath $stdioInput -Encoding utf8NoBOM
$stdioOut = Join-Path $release 'stdio-output.ndjson'; $stdioErr = Join-Path $release 'stdio-error.txt'
$stdioProcess = Start-Process -FilePath (Join-Path $release 'codej.cmd') -ArgumentList '--stdio' `
    -WorkingDirectory $root -NoNewWindow -PassThru -RedirectStandardInput $stdioInput `
    -RedirectStandardOutput $stdioOut -RedirectStandardError $stdioErr
if (-not $stdioProcess.WaitForExit(15000)) { $stdioProcess.Kill($true); throw 'Installed launcher stdio did not exit' }
$stdioEvents = @(Get-Content -LiteralPath $stdioOut | ForEach-Object { $_ | ConvertFrom-Json })
if ($stdioProcess.ExitCode -ne 0 -or @($stdioEvents | Where-Object type -eq 'initialized').Count -ne 1 `
        -or (Get-Item -LiteralPath $stdioErr).Length -ne 0) {
    throw 'Installed launcher Java stdio smoke failed'
}
Remove-Item -LiteralPath $stdioInput,$stdioOut,$stdioErr -Force

$attestationPath = Join-Path $root 'target/codej-build-attestation.json'
$attestationText = Get-Content -LiteralPath $attestationPath -Raw
try {
    $tampered = $attestationText | ConvertFrom-Json
    $tampered.cliDigest = '0' * 64
    $tampered | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $attestationPath -Encoding utf8NoBOM
    $failedClosed = $false
    try { & $builder -SkipBuild -SkipTuiBuild -OutputDirectory 'target/release/attestation-negative' }
    catch { $failedClosed = $_.Exception.Message -like '*build artifact identity mismatch*' }
    if (-not $failedClosed) { throw 'Tampered build attestation did not fail closed' }
} finally {
    $attestationText | Set-Content -LiteralPath $attestationPath -Encoding utf8NoBOM
}

$sourceProbe = Join-Path $root 'scripts/.release-source-drift-probe'
try {
    'drift' | Set-Content -LiteralPath $sourceProbe -Encoding ascii
    $failedClosed = $false
    try { & $builder -SkipBuild -SkipTuiBuild -OutputDirectory 'target/release/source-negative' }
    catch { $failedClosed = $_.Exception.Message -like '*build source identity mismatch*' }
    if (-not $failedClosed) { throw 'Stale source/build artifacts did not fail closed' }
} finally {
    Remove-Item -LiteralPath $sourceProbe -Force -ErrorAction SilentlyContinue
}

$escaped = Join-Path $root 'target/release-escape-negative'
$failedClosed = $false
try {
    & $builder -SkipBuild -OutputDirectory $escaped
} catch {
    $failedClosed = $_.Exception.Message -like '*target/release*'
}
if (-not $failedClosed) { throw 'OutputDirectory escape negative did not fail closed' }

$publicOutput = Join-Path $root 'target/release/public-gate-smoke'
& $builder -SkipBuild -SkipTuiBuild -PublicRelease -OutputDirectory 'target/release/public-gate-smoke'
if ($LASTEXITCODE -ne 0) { throw 'Public release gate build failed' }
$publicManifest = Get-Content -LiteralPath (Join-Path $publicOutput 'release-manifest.json') -Raw |
    ConvertFrom-Json
if ($publicManifest.publicReleaseAllowed -ne $true) {
    throw 'Apache-2.0 LICENSE did not unlock explicit public release build'
}

Write-Output "S14 release self-test passed: $($components.Count) Maven components, checksums=$($checksumLines.Count)."
