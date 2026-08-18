<#
.SYNOPSIS
    Locates the Git for Windows bash.exe.
.DESCRIPTION
    Dot-sourced by the wrapper scripts. Git installs are searched before PATH because
    PATH may resolve to C:\Windows\System32\bash.exe, which launches WSL: a different
    filesystem view (/mnt/d), a different toolchain, and paths this repository's scripts
    do not expect.
#>
function Find-GitBash {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }

    $onPath = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($onPath -and $onPath.Source -notlike "$env:SystemRoot*") { return $onPath.Source }

    return $null
}
