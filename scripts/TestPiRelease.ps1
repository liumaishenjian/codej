#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
# AST 仅提取生产纯函数及 Pi 打包区块；绝不执行 BuildRelease 入口或真正的 npm/Maven。
$scriptPath = Join-Path $PSScriptRoot 'BuildRelease.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "BuildRelease parse errors: $errors" }
foreach ($name in @('Get-SourceDigest', 'Get-TreeDigest')) {
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    . ([scriptblock]::Create($definition.Extent.Text))
}
$root = Join-Path ([IO.Path]::GetTempPath()) ("codej-pi-release-' space-" + [guid]::NewGuid().ToString('N'))
function Put([string]$Name, [string]$Text = 'fake') {
    $file = Join-Path $root $Name
    New-Item -ItemType Directory -Path (Split-Path $file -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($file, $Text)
}
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
try {
    Put 'cc-java-provider-pi/worker.mjs'
    $before = Get-SourceDigest
    foreach ($name in @('cc-java-provider-pi/worker.test.mjs', 'cc-java-provider-pi/model-operation.mjs',
            'cc-java-provider-pi/package.json', 'cc-java-provider-pi/package-lock.json', 'pom.xml',
            'cc-java-model-pi/pom.xml', 'cc-java-tui/package.json', 'cc-java-tui/package-lock.json')) {
        Put $name
        $after = Get-SourceDigest
        Assert ($after -ne $before) "Source identity omitted $name"
        $before = $after
    }
    foreach ($name in @('cc-java-provider-pi/workspace/private.mjs', 'cc-java-provider-pi/auth.json',
            'cc-java-provider-pi/node_modules/test.mjs', 'cc-java-provider-pi/fixture/session.json')) { Put $name }
    Assert ((Get-SourceDigest) -eq $before) 'Source selection unexpectedly included workspace/auth/dependencies'
    Put 'cc-java-provider-pi/login.mjs'
    $piRoot = Join-Path $root 'cc-java-provider-pi'
    $piDirectory = Join-Path $root 'target/pi-release-runtime'
    $SkipBuild = $false
    $script:npmCalls = 0
    function Invoke-FakeNpm {
        $script:npmCalls++
        Assert (($args -join ' ') -eq "--prefix $piDirectory ci --omit=dev --ignore-scripts --no-audit --no-fund --bin-links=false") 'Unexpected npm production command'
        Put 'target/pi-release-runtime/node_modules/@earendil-works/pi-ai/package.json' '{"name":"@earendil-works/pi-ai","version":"0.85.1","license":"MIT"}'
        Put 'target/pi-release-runtime/node_modules/dependency/package.json' '{"name":"dependency","version":"1.2.3","license":"Apache-2.0"}'
        Put 'target/pi-release-runtime/node_modules/dependency/LICENSE' 'synthetic license fixture'
        Put 'target/pi-release-runtime/node_modules/.package-lock.json' '{}'
        $global:LASTEXITCODE = 0
    }
    # 在当前作用域遮蔽可执行程序；生产脚本区块仍原样执行，用断言证明参数与复制白名单。
    Set-Alias npm Invoke-FakeNpm
    Set-Alias npm.cmd Invoke-FakeNpm
    $piBuild = @($ast.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.IfStatementAst] -and
        $_.Extent.Text.Contains('Production Pi dependency installation failed')
    })
    Assert ($piBuild.Count -eq 1) 'Pi build block not uniquely found'
    . ([scriptblock]::Create($piBuild[0].Extent.Text))
    Assert ($script:npmCalls -eq 1) 'Production preparation did not call controlled npm'
    foreach ($name in @('worker.mjs', 'login.mjs', 'model-operation.mjs', 'package.json', 'package-lock.json')) {
        Assert (Test-Path -LiteralPath (Join-Path $piDirectory $name) -PathType Leaf) "Missing production input $name"
    }
    foreach ($name in @('worker.test.mjs', 'workspace', 'auth.json', 'fixture')) {
        Assert (-not (Test-Path -LiteralPath (Join-Path $piDirectory $name))) "Unwanted release input $name"
    }
    $SkipBuild = $true
    . ([scriptblock]::Create($piBuild[0].Extent.Text))
    Assert ($script:npmCalls -eq 1) 'SkipBuild unexpectedly installed Pi dependencies'
    $digest = Get-TreeDigest $piDirectory -Ordinal
    Put 'target/pi-release-runtime/node_modules/.package-lock.json' '{"changed":true}'
    Assert ((Get-TreeDigest $piDirectory -Ordinal) -ne $digest) 'Pi digest missed hidden installed dependency metadata'

    # 原样执行生产 skip/staging 身份 Gate；不能以改旧测试预期来接受缺失或漂移的 Pi。
    $skipGate = @($ast.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.IfStatementAst] -and
        $_.Extent.Text.Contains('Skipped build artifact identity mismatch')
    })
    Assert ($skipGate.Count -eq 1) 'Skipped artifact Gate not uniquely found'
    $cliDigest = 'cli'; $tuiDigest = 'tui'; $piDigest = 'pi'; $SkipTuiBuild = $false
    foreach ($priorPi in @($null, 'changed', 'pi')) {
        $priorAttestation = @{ cliDigest = 'cli'; tuiDigest = 'tui'; piDigest = $priorPi }
        $rejected = $false
        try { . ([scriptblock]::Create($skipGate[0].Extent.Text)) } catch { $rejected = $true }
        Assert ($rejected -eq ($priorPi -ne 'pi')) 'SkipBuild Pi attestation Gate mismatch'
    }
    $stagingGate = @($ast.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.IfStatementAst] -and
        $_.Extent.Text.Contains('Staged artifacts do not match the build attestation')
    })
    Assert ($stagingGate.Count -eq 1) 'Staging Gate not uniquely found'
    $stagedCliDigest = 'cli'; $stagedTuiDigest = 'tui'
    foreach ($value in @('changed', 'pi')) {
        $stagedPiDigest = $value; $rejected = $false
        try { . ([scriptblock]::Create($stagingGate[0].Extent.Text)) } catch { $rejected = $true }
        Assert ($rejected -eq ($value -ne 'pi')) 'Staging Pi attestation Gate mismatch'
    }
    $stagingPi = $piDirectory
    Put 'target/pi-release-runtime/package-lock.json' '{"packages":{"":{},"node_modules/@earendil-works/pi-ai":{"version":"0.85.1","license":"MIT"},"node_modules/dependency":{"version":"1.2.3","license":"Apache-2.0"},"node_modules/other-platform":{"version":"1.0.0","optional":true},"node_modules/dev-only":{"version":"1.0.0","dev":true}}}'
    $componentList = [Collections.Generic.List[object]]::new()
    $sbomStatements = @($ast.EndBlock.Statements | Where-Object {
        ($_.Extent.Text -match '^\$piLock\s*=') -or
        ($_.Extent.Text -match '^foreach \(\$entry in \$piLock')
    })
    Assert ($sbomStatements.Count -eq 2) 'Pi SBOM block not uniquely found'
    foreach ($statement in $sbomStatements) { . ([scriptblock]::Create($statement.Extent.Text)) }
    Assert ($componentList.Count -eq 2) 'SBOM must describe only actual production dependencies'
    Assert ($componentList[1].name -eq 'dependency' -and $componentList[1].version -eq '1.2.3') 'Transitive package version missing'
    Assert ($componentList[1].licenses[0].license.name -eq 'Apache-2.0') 'Declared license metadata missing'
    Assert (Test-Path -LiteralPath (Join-Path $stagingPi 'node_modules/dependency/LICENSE')) 'Dependency license was removed'
    Write-Output 'Pi release offline checks passed: source identity, production layout, install flags/skip, hidden digest, transitive SBOM/license. No actual build/install/network.'
} finally {
    Remove-Item Alias:npm, Alias:npm.cmd -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
