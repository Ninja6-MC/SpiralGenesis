<#
.SYNOPSIS
    Boots a local Paper server with the built plugin. PowerShell wrapper around
    scripts/dev-server.sh.
.DESCRIPTION
    dev-server.sh deliberately mirrors .github/scripts/smoke-test.sh so that a local run
    and a CI run differ by machine rather than by script. This wrapper locates Git Bash
    and hands over rather than reimplementing any of it.

    Build the plugin first: ./gradlew shadowJar
.EXAMPLE
    scripts\dev-server.ps1
.EXAMPLE
    scripts\dev-server.ps1 1.21.8 myseed
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
$script = (Join-Path $PSScriptRoot 'dev-server.sh').Replace([char]92, [char]47)

Push-Location $repoRoot
try {
    & $bash $script @ScriptArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
