$ErrorActionPreference = "Stop"

$probeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$serverPidPath = Join-Path $probeRoot "PROTOTYPE-server.pid"
$serverLog = Join-Path $probeRoot "PROTOTYPE-server.log"
$reportPath = Join-Path $probeRoot "PROTOTYPE-result.txt"

$processLine = & $adb shell pidof com.example.codexquotabackgroundprobe
$preferences = & $adb shell run-as com.example.codexquotabackgroundprobe cat shared_prefs/background_probe.xml
$lastServerLines = if (Test-Path -LiteralPath $serverLog) { Get-Content -LiteralPath $serverLog -Tail 30 } else { @("server log missing") }

$report = @(
    "CollectedAt=$([DateTimeOffset]::Now.ToString('O'))"
    "AndroidProcessId=$processLine"
    "Preferences:"
    $preferences
    "ServerTail:"
    $lastServerLines
) -join [Environment]::NewLine

$report | Set-Content -LiteralPath $reportPath -Encoding utf8
$report

if (Test-Path -LiteralPath $serverPidPath) {
    $serverPid = [int](Get-Content -LiteralPath $serverPidPath -Raw)
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
}

& $adb reverse --remove tcp:17421
