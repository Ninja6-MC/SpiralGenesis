<#
.SYNOPSIS
    Cuts a release tag and pushes it, or dispatches Publish Release for an existing tag.
    PowerShell wrapper around scripts/release.sh.
.DESCRIPTION
    The checks live in release.sh, which is also the version CI's conventions are built
    around. This wrapper only locates Git Bash and hands over, so there is one
    implementation of the release guard rather than two that can drift apart. Every
    argument is passed through, including the publish mode.

    PowerShell parses an unquoted comma list such as modrinth,hangar as an array, and a
    [string[]] parameter would hand it on as "modrinth hangar", which release.sh rejects.
    The parameter is therefore [object[]], and each array argument is joined back with
    commas before Git Bash sees it. scripts/tests/test_release_wrapper.py checks this.
.EXAMPLE
    scripts\release.ps1 1.0.0-beta.1
.EXAMPLE
    scripts\release.ps1 1.0.0 --dry-run
.EXAMPLE
    scripts\release.ps1 publish 1.0.0 123456789 --attest modrinth,hangar
.EXAMPLE
    scripts\release.ps1 publish 1.0.0 123456789 --destinations hangar --attest hangar
.EXAMPLE
    scripts\release.ps1 publish 1.0.0 123456789 --destinations shared-state --dry-run
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [object[]] $ScriptArgs
)

# An unquoted a,b arrives as one array element; release.sh expects the literal "a,b".
$bashArgs = @(foreach ($arg in $ScriptArgs) { @($arg) -join ',' })

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
    & $bash $script @bashArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
