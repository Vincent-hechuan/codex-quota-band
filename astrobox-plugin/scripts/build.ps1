param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "Temp\codex-quota-rust-target"
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
$profile = if ($Release) { "release" } else { "debug" }
$cargoArguments = @(
    "+stable-x86_64-pc-windows-gnu",
    "build",
    "--target",
    "wasm32-wasip2"
)
if ($Release) { $cargoArguments += "--release" }

Push-Location -LiteralPath $projectRoot
try {
    & $cargo @cargoArguments
    if ($LASTEXITCODE -ne 0) { throw "AstroBox WASI build failed" }
}
finally {
    Pop-Location
}

$dist = Join-Path $projectRoot "dist"
New-Item -ItemType Directory -Path $dist -Force | Out-Null

$wasmName = "codex_quota_astrobox.wasm"
$wasmSource = Join-Path $env:CARGO_TARGET_DIR "wasm32-wasip2\$profile\$wasmName"
$wasmDestination = Join-Path $dist $wasmName
$manifestDestination = Join-Path $dist "manifest.json"
$iconDestination = Join-Path $dist "icon.png"
Copy-Item -LiteralPath $wasmSource -Destination $wasmDestination -Force
Copy-Item -LiteralPath (Join-Path $projectRoot "manifest.json") -Destination $manifestDestination -Force
Copy-Item -LiteralPath (Join-Path $workspaceRoot "build\icon.png") -Destination $iconDestination -Force

$zipPath = Join-Path $dist "codex-quota-astrobox.zip"
$packagePath = Join-Path $dist "codex-quota-astrobox-0.2.0.abp"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
if (Test-Path -LiteralPath $packagePath) { Remove-Item -LiteralPath $packagePath -Force }
Compress-Archive -LiteralPath @($manifestDestination, $iconDestination, $wasmDestination) -DestinationPath $zipPath
Move-Item -LiteralPath $zipPath -Destination $packagePath

$sha256 = [System.Security.Cryptography.SHA256]::Create()
$stream = [System.IO.File]::OpenRead($packagePath)
try {
    $hash = [System.BitConverter]::ToString($sha256.ComputeHash($stream)).Replace("-", "")
}
finally {
    $stream.Dispose()
    $sha256.Dispose()
}
[pscustomobject]@{
    Path = $packagePath
    Bytes = (Get-Item -LiteralPath $packagePath).Length
    SHA256 = $hash
} | Format-List
