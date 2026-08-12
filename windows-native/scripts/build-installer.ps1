param(
    [string]$Version
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$cargoToml = Join-Path $projectRoot "Cargo.toml"
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
$makensis = "${env:ProgramFiles(x86)}\NSIS\makensis.exe"
$targetRoot = if ($env:CARGO_TARGET_DIR) {
    [IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)
}
else {
    Join-Path $projectRoot "target"
}

if (-not (Test-Path -LiteralPath $cargo)) { throw "Rust cargo not found: $cargo" }
if (-not (Test-Path -LiteralPath $makensis)) { throw "NSIS makensis not found: $makensis" }

if (-not $Version) {
    $cargoTomlText = Get-Content -Raw -LiteralPath $cargoToml
    $match = [regex]::Match($cargoTomlText, '(?m)^version\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw "Could not read version from $cargoToml" }
    $Version = $match.Groups[1].Value
}

Push-Location $projectRoot
$previousRustFlags = $env:RUSTFLAGS
try {
    $env:RUSTFLAGS = "-C target-feature=+crt-static"
    & $cargo build --release --bin codex_quota_windows
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed with exit code $LASTEXITCODE" }

    $binary = Join-Path $targetRoot "release\codex_quota_windows.exe"
    if (-not (Test-Path -LiteralPath $binary)) { throw "Missing release binary: $binary" }
    & (Join-Path $PSScriptRoot "test-executable-icon.ps1") -Executable $binary
    if ($LASTEXITCODE -ne 0) {
        throw "Executable icon verification failed with exit code $LASTEXITCODE"
    }

    $bundleRoot = [IO.Path]::GetFullPath((Join-Path $targetRoot "installer-bundle"))
    $resolvedTargetRoot = [IO.Path]::GetFullPath($targetRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $bundleRoot.StartsWith(
        $resolvedTargetRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Unsafe installer bundle target: $bundleRoot"
    }
    if (Test-Path -LiteralPath $bundleRoot) {
        Remove-Item -LiteralPath $bundleRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $bundleRoot | Out-Null
    $bundledBinary = Join-Path $bundleRoot "CodexQuota.exe"
    Copy-Item -LiteralPath $binary -Destination $bundledBinary

    & (Join-Path $PSScriptRoot "test-portable-bundle.ps1") -BundleDirectory $bundleRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Portable bundle smoke test failed with exit code $LASTEXITCODE"
    }

    $dist = Join-Path $projectRoot "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $output = Join-Path $dist "Codex-Quota-Setup-$Version.exe"
    $versionMatch = [regex]::Match($Version, '^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.-]+)?$')
    if (-not $versionMatch.Success) {
        throw "Installer version must use major.minor.patch or a SemVer prerelease suffix: $Version"
    }
    $versionInfo = "$($versionMatch.Groups[1].Value).$($versionMatch.Groups[2].Value).$($versionMatch.Groups[3].Value).0"
    & $makensis "/DAPP_VERSION=$Version" "/DAPP_VERSION_INFO=$versionInfo" "/DAPP_BINARY=$bundledBinary" "/DOUTPUT_PATH=$output" "installer\CodexQuota.nsi"
    if ($LASTEXITCODE -ne 0) { throw "makensis failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $output)) { throw "Missing installer: $output" }

    Get-Item -LiteralPath $output | Select-Object FullName,Length,LastWriteTime
}
finally {
    $env:RUSTFLAGS = $previousRustFlags
    Pop-Location
}
