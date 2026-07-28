$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "Temp\codex-quota-rust-gnullvm-test"
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
$toolchain = "+stable-x86_64-pc-windows-gnullvm"

Push-Location -LiteralPath $projectRoot
try {
    & $cargo $toolchain fmt -- --check
    if ($LASTEXITCODE -ne 0) { throw "cargo fmt failed" }

    & $cargo $toolchain test
    if ($LASTEXITCODE -ne 0) { throw "cargo test failed" }
}
finally {
    Pop-Location
}
