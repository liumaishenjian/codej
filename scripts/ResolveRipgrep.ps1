function Initialize-CcJavaRipgrep {
    $configuredPath = $env:CC_JAVA_RIPGREP_PATH
    $resolvedPath = $null

    if ($configuredPath) {
        $candidatePath = [IO.Path]::GetFullPath($configuredPath)
        if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
            throw 'CC_JAVA_RIPGREP_PATH does not point to an existing file.'
        }
        $resolvedPath = $candidatePath
    }

    if (-not $resolvedPath) {
        $systemCommand = Get-Command rg -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($systemCommand) {
            $resolvedPath = $systemCommand.Source
        }
    }

    # Codex Desktop may provide rg without exporting its directory to a new PowerShell.
    # This resolves an existing external tool; the project does not copy or distribute it.
    if (-not $resolvedPath) {
        $codexBinRoot = Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\bin'
        if (Test-Path -LiteralPath $codexBinRoot -PathType Container) {
            $rootCandidate = Join-Path $codexBinRoot 'rg.exe'
            if (Test-Path -LiteralPath $rootCandidate -PathType Leaf) {
                $resolvedPath = $rootCandidate
            }
            else {
                $resolvedPath = Get-ChildItem -LiteralPath $codexBinRoot -Directory |
                    Sort-Object LastWriteTimeUtc -Descending |
                    ForEach-Object { Join-Path $_.FullName 'rg.exe' } |
                    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
                    Select-Object -First 1
            }
        }
    }

    if (-not $resolvedPath) {
        [Console]::Error.WriteLine('[cc-java] ripgrep was not found. Install rg or set CC_JAVA_RIPGREP_PATH.')
        return
    }

    $resolvedPath = [IO.Path]::GetFullPath($resolvedPath)
    $ripgrepDirectory = Split-Path -Parent $resolvedPath
    $pathSeparator = [IO.Path]::PathSeparator
    $pathEntries = @($env:Path -split $pathSeparator)
    if ($pathEntries -notcontains $ripgrepDirectory) {
        $env:Path = "$ripgrepDirectory$pathSeparator$env:Path"
    }
    $env:CC_JAVA_RIPGREP_PATH = $resolvedPath
    Write-Verbose '[cc-java] ripgrep is ready.'
}
