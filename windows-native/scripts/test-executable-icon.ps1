param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
)

$ErrorActionPreference = "Stop"
$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$readobj = Get-Command llvm-readobj -ErrorAction Stop
$resources = & $readobj.Source --coff-resources $resolvedExecutable 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "llvm-readobj failed while checking executable resources"
}

if ($resources -notmatch '(?m)^\s*Type:\s*ICON\s+' -or
    $resources -notmatch '(?m)^\s*Type:\s*GROUP_ICON\s+') {
    throw "Windows executable does not contain ICON and GROUP_ICON resources: $resolvedExecutable"
}

Write-Host "Embedded Windows application icon verified: $resolvedExecutable"
