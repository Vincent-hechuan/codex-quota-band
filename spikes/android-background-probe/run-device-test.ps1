param(
    [switch]$NotificationTest
)

$ErrorActionPreference = "Stop"

$probeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $probeRoot)
$jdk = Join-Path $env:LOCALAPPDATA "codex-quota-dev\jdk-17"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$devices = @(& $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($devices.Count -ne 1) {
    throw "Expected exactly one authorized Android device, found $($devices.Count)."
}

Push-Location -LiteralPath $probeRoot
try {
    & .\gradlew.bat assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Android probe build failed" }
}
finally {
    Pop-Location
}

$serverLog = Join-Path $probeRoot "PROTOTYPE-server.log"
$serverError = Join-Path $probeRoot "PROTOTYPE-server-error.log"
$serverPid = Join-Path $probeRoot "PROTOTYPE-server.pid"
$serverScript = if ($NotificationTest) { "server-notification-test.mjs" } else { "server.mjs" }
$server = Start-Process -FilePath "node.exe" -ArgumentList @((Join-Path $probeRoot $serverScript)) `
    -WorkingDirectory $probeRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $serverLog -RedirectStandardError $serverError
$server.Id | Set-Content -LiteralPath $serverPid -Encoding ascii

& $adb reverse tcp:17421 tcp:17421
if ($LASTEXITCODE -ne 0) { throw "adb reverse failed" }

$apk = Join-Path $probeRoot "app\build\outputs\apk\debug\app-debug.apk"
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed" }

& $adb shell am force-stop com.example.codexquotabackgroundprobe
& $adb shell monkey -p com.example.codexquotabackgroundprobe 1

[pscustomobject]@{
    Device = $devices[0]
    Apk = $apk
    ServerPid = $server.Id
    ServerLog = $serverLog
} | Format-List
