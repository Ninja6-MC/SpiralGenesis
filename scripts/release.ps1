<#
.SYNOPSIS
    Cuts a release tag and pushes it. PowerShell wrapper around scripts/release.sh.
.DESCRIPTION
    The checks live in release.sh, which is also the version CI's conventions are built
    around. This wrapper only locates Git Bash and hands over, so there is one
    implementation of the release guard rather than two that can drift apart.
.EXAMPLE
    scripts\release.ps1 1.0.0-beta.1
.EXAMPLE
    scripts\release.ps1 1.0.0 --dry-run
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ScriptArgs
)

. (Join-Path $PSScriptRoot 'Find-GitBash.ps1')

$bash = Find-GitBash
if (-not $bash) {
    Write-Error 'Could not find Git Bash. Install Git for Windows (https://git-scm.com/download/win), then run this again.'
    exit 1
}

$repoRoot = Split-Path -Parent $PSScriptRoot
# Forward slashes: bash.exe handles "C:/path" but treats backslashes as escapes.
# [char]92 and [char]47 avoid embedding a literal backslash in this file.
$script = (Join-Path $PSScriptRoot 'release.sh').Replace([char]92, [char]47)

Push-Location $repoRoot
try {
    & $bash $script @ScriptArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
