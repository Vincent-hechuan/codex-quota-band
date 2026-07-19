$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "Temp\codex-quota-rust-target"
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"

Push-Location -LiteralPath $projectRoot
try {
    & $cargo +stable-x86_64-pc-windows-gnu fmt -- --check
    if ($LASTEXITCODE -ne 0) { throw "cargo fmt failed" }

    & $cargo +stable-x86_64-pc-windows-gnu test
    if ($LASTEXITCODE -ne 0) { throw "cargo test failed" }
}
finally {
    Pop-Location
}
