param(
    [Parameter(Mandatory = $true)]
    [string]$BundleDirectory
)

$ErrorActionPreference = "Stop"
$bundlePath = (Resolve-Path -LiteralPath $BundleDirectory).Path
$executable = Join-Path $bundlePath "CodexQuota.exe"

if (-not (Test-Path -LiteralPath $executable)) {
    throw "Portable bundle executable is missing: $executable"
}

Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;

public static class CodexQuotaPortableBundleErrorMode
{
    [DllImport("kernel32.dll")]
    public static extern uint SetErrorMode(uint mode);
}
'@

$previousErrorMode = [CodexQuotaPortableBundleErrorMode]::SetErrorMode(1)
try {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $executable
    $startInfo.Arguments = "--smoke-test"
    $startInfo.WorkingDirectory = $bundlePath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.Environment.Clear()
    $startInfo.Environment["SystemRoot"] = $env:SystemRoot
    $startInfo.Environment["WINDIR"] = $env:WINDIR
    $startInfo.Environment["PATH"] = Join-Path $env:SystemRoot "System32"

    $process = [System.Diagnostics.Process]::Start($startInfo)
    if (-not $process.WaitForExit(10000)) {
        $process.Kill($true)
        throw "Portable bundle smoke test timed out"
    }
    if ($process.ExitCode -ne 0) {
        $unsignedExitCode = [BitConverter]::ToUInt32(
            [BitConverter]::GetBytes([int]$process.ExitCode),
            0
        )
        throw "Portable bundle smoke test failed with exit code 0x$($unsignedExitCode.ToString('X8'))"
    }
}
finally {
    [void][CodexQuotaPortableBundleErrorMode]::SetErrorMode($previousErrorMode)
}

"PORTABLE_BUNDLE_SMOKE_PASS"
