#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$previousClasspath = $env:CC_JAVA_TEST_CLASSPATH
Push-Location $root
try {
    # 定向离线回归，不读取用户凭证、不访问真实模型，也不替代完整clean verify或物理TTY验收。
    $tests = '*ProviderAuth*,*Credential*,*ProviderControl*,*StdioProtocol*,*RuntimeStdioCommandHandlerTest,*DefaultCliModeRunnerTest,*HeadlessRuntimeSessionTest,*ProviderLoginRouteTest,*OpenRouterBrowserLoginTest,*BrowserAuthConfigurationTest,*SelectedProvider*,*ProviderSummaryRouteTest,*SpringAiContextSummarizerTest'
    & .\mvnw.cmd -q -pl cc-java-cli -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'Java authentication regression failed' }
    & .\mvnw.cmd -q -pl cc-java-cli dependency:build-classpath '-Dmdep.outputFile=target/login-classpath.txt' '-DincludeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Java dependency classpath generation failed' }
    $paths = foreach ($module in @('cc-java-cli', 'cc-java-domain', 'cc-java-core', 'cc-java-model-spring-ai', 'cc-java-model-pi', 'cc-java-tools-local')) {
        Join-Path $root "$module/target/test-classes"
        Join-Path $root "$module/target/classes"
    }
    $dependencies = (Get-Content -LiteralPath (Join-Path $root 'cc-java-cli/target/login-classpath.txt') -Raw -Encoding utf8).Trim()
    $env:CC_JAVA_TEST_CLASSPATH = ($paths -join [IO.Path]::PathSeparator) + [IO.Path]::PathSeparator + $dependencies
    & npm.cmd --prefix cc-java-tui run build
    if ($LASTEXITCODE -ne 0) { throw 'TUI build failed' }
    & npm.cmd --prefix cc-java-tui test
    if ($LASTEXITCODE -ne 0) { throw 'TUI regression including real Java fixtures failed' }
    & npm.cmd --prefix cc-java-provider-pi test
    if ($LASTEXITCODE -ne 0) { throw 'Optional Pi bridge offline regression failed; install its locked dependencies first' }
    Write-Output 'Login/logout offline regression passed; no online authorization or physical terminal verification claimed.'
} finally {
    $env:CC_JAVA_TEST_CLASSPATH = $previousClasspath
    Pop-Location
}
