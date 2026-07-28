param(
    [ValidateRange(30, 14400)]
    [int]$DurationSeconds = 600,
    [ValidateRange(1, 30)]
    [int]$SampleSeconds = 5
)

$ErrorActionPreference = "Stop"

$probeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
$env:Path = (Join-Path $env:USERPROFILE ".cargo\bin") + ";" +
    [Environment]::GetEnvironmentVariable("Path", "User") + ";" +
    [Environment]::GetEnvironmentVariable("Path", "Machine")
$targetRoot = Join-Path $env:LOCALAPPDATA "Temp\codex-quota-rust-runtime-probe-target"
$reportPath = Join-Path $probeRoot "PROTOTYPE-report.json"
$stdoutPath = Join-Path $env:TEMP "codex-quota-rust-runtime-probe.stdout.txt"
$stderrPath = Join-Path $env:TEMP "codex-quota-rust-runtime-probe.stderr.txt"
$env:CARGO_TARGET_DIR = $targetRoot

Push-Location -LiteralPath $probeRoot
try {
    & $cargo build --release
    if ($LASTEXITCODE -ne 0) {
        throw "PROTOTYPE build failed"
    }
}
finally {
    Pop-Location
}

$exePath = Join-Path $targetRoot "release\codex-quota-rust-runtime-probe.exe"
$process = Start-Process -FilePath $exePath -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
$samples = [System.Collections.Generic.List[object]]::new()
$logicalProcessors = [Environment]::ProcessorCount
$previousCpu = $process.TotalProcessorTime.TotalSeconds
$previousAt = [DateTimeOffset]::UtcNow
$startedAt = $previousAt

try {
    Start-Sleep -Seconds 2
    while (([DateTimeOffset]::UtcNow - $startedAt).TotalSeconds -lt $DurationSeconds) {
        $process.Refresh()
        if ($process.HasExited) {
            throw "PROTOTYPE process exited early"
        }

        $now = [DateTimeOffset]::UtcNow
        $cpuNow = $process.TotalProcessorTime.TotalSeconds
        $wallSeconds = [Math]::Max(0.001, ($now - $previousAt).TotalSeconds)
        $cpuPercent = (($cpuNow - $previousCpu) / $wallSeconds / $logicalProcessors) * 100
        $perf = Get-CimInstance Win32_PerfRawData_PerfProc_Process `
            -Filter "IDProcess=$($process.Id)" -ErrorAction Stop

        $samples.Add([pscustomobject]@{
            at = $now.ToString("O")
            privateWorkingSetBytes = [int64]$perf.WorkingSetPrivate
            privateBytes = [int64]$process.PrivateMemorySize64
            workingSetBytes = [int64]$process.WorkingSet64
            normalizedCpuPercent = [Math]::Round($cpuPercent, 4)
        })

        $previousCpu = $cpuNow
        $previousAt = $now
        Start-Sleep -Seconds $SampleSeconds
    }
}
finally {
    if (-not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit()
    }
}

$privateWorkingSets = @($samples | ForEach-Object { $_.privateWorkingSetBytes })
$cpuValues = @($samples | ForEach-Object { $_.normalizedCpuPercent })
$firstPrivate = $privateWorkingSets[0]
$lastPrivate = $privateWorkingSets[-1]
$report = [ordered]@{
    prototype = $true
    durationSeconds = [Math]::Round(([DateTimeOffset]::UtcNow - $startedAt).TotalSeconds, 2)
    sampleCount = $samples.Count
    logicalProcessors = $logicalProcessors
    privateWorkingSet = [ordered]@{
        firstBytes = $firstPrivate
        lastBytes = $lastPrivate
        maxBytes = ($privateWorkingSets | Measure-Object -Maximum).Maximum
        growthBytes = $lastPrivate - $firstPrivate
    }
    normalizedCpu = [ordered]@{
        averagePercent = [Math]::Round(($cpuValues | Measure-Object -Average).Average, 4)
        maxPercent = [Math]::Round(($cpuValues | Measure-Object -Maximum).Maximum, 4)
    }
    processExitedAfterProbe = $process.HasExited
    targetPrivateWorkingSetBytes = 100MB
    targetAverageCpuPercent = 1
    samples = $samples
}

$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding utf8
$report | ConvertTo-Json -Depth 4
