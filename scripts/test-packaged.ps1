param(
    [string]$Executable
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Executable) {
    $Executable = Join-Path $projectRoot "dist\win-unpacked\CodexQuota.exe"
}
$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$diagnosticRoot = Join-Path $env:LOCALAPPDATA "Temp\codex-quota-packaged-tests"
New-Item -ItemType Directory -Path $diagnosticRoot -Force | Out-Null
$reportPath = Join-Path $diagnosticRoot ("diagnostic-{0}.json" -f [Guid]::NewGuid().ToString("N"))
$previousOutput = $env:CODEX_QUOTA_DIAGNOSTIC_OUTPUT

try {
    $smoke = Start-Process -FilePath $resolvedExecutable -ArgumentList "--smoke-test" -PassThru -Wait -WindowStyle Hidden
    if ($smoke.ExitCode -ne 0) { throw "Packaged smoke test failed with exit code $($smoke.ExitCode)" }

    $env:CODEX_QUOTA_DIAGNOSTIC_OUTPUT = $reportPath
    $diagnostic = Start-Process -FilePath $resolvedExecutable -ArgumentList "--diagnostic-service-test" -PassThru -Wait -WindowStyle Hidden
    if ($diagnostic.ExitCode -ne 0) { throw "Packaged diagnostic failed with exit code $($diagnostic.ExitCode)" }
    if (-not (Test-Path -LiteralPath $reportPath)) { throw "Packaged diagnostic did not create a report" }

    $report = Get-Content -Raw -LiteralPath $reportPath | ConvertFrom-Json
    if ($null -eq $report.sourceStatus -or $null -eq $report.windows -or $null -eq $report.resetInventory) {
        throw "Packaged diagnostic report is missing required whitelist fields"
    }
    $serialized = $report | ConvertTo-Json -Depth 8 -Compress
    foreach ($forbidden in @("conversation", "prompt", "cookie", "access_token", "refresh_token", "projectPath")) {
        if ($serialized.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "Packaged diagnostic report contains forbidden field: $forbidden"
        }
    }

    [pscustomobject]@{
        Result = "PACKAGED DIAGNOSTIC PASS"
        SourceStatus = $report.sourceStatus
        Windows = ($report.windows | ConvertTo-Json -Compress)
        ResetInventory = ($report.resetInventory | ConvertTo-Json -Compress)
    } | Format-List
}
finally {
    if ($null -eq $previousOutput) {
        Remove-Item Env:CODEX_QUOTA_DIAGNOSTIC_OUTPUT -ErrorAction SilentlyContinue
    }
    else {
        $env:CODEX_QUOTA_DIAGNOSTIC_OUTPUT = $previousOutput
    }

    $resolvedRoot = [IO.Path]::GetFullPath($diagnosticRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolvedReport = [IO.Path]::GetFullPath($reportPath)
    if ($resolvedReport.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedReport)) {
        Remove-Item -LiteralPath $resolvedReport -Force
    }
}
