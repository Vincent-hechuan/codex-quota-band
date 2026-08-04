param(
    [string]$Installer
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Installer) {
    $cargoToml = Join-Path $projectRoot "Cargo.toml"
    $cargoTomlText = Get-Content -Raw -LiteralPath $cargoToml
    $match = [regex]::Match($cargoTomlText, '(?m)^version\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw "Could not read version from $cargoToml" }
    $Installer = Join-Path $projectRoot "dist\Codex-Quota-Setup-$($match.Groups[1].Value).exe"
}
$installerPath = (Resolve-Path -LiteralPath $Installer).Path
$testRoot = Join-Path $env:LOCALAPPDATA "Temp\codex-quota-installer-smoke"
$tempRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA "Temp")).TrimEnd([IO.Path]::DirectorySeparatorChar)
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)

if (-not $resolvedTestRoot.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe installer smoke target: $resolvedTestRoot"
}
if (Test-Path -LiteralPath $testRoot) { throw "Installer smoke target already exists: $testRoot" }
if (Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\CodexQuota') {
    throw "Refusing to replace an existing CodexQuota installation during smoke testing"
}

$install = Start-Process -FilePath $installerPath -ArgumentList @('/S', "/D=$testRoot") -PassThru -Wait -WindowStyle Hidden
if ($install.ExitCode -ne 0) { throw "Installer failed with exit code $($install.ExitCode)" }

try {
    $executable = Join-Path $testRoot "CodexQuota.exe"
    $runtimeDll = Join-Path $testRoot "libunwind.dll"
    $uninstaller = Join-Path $testRoot "Uninstall.exe"
    if (
        -not (Test-Path -LiteralPath $executable) -or
        -not (Test-Path -LiteralPath $uninstaller)
    ) {
        throw "Installed executable or uninstaller is missing"
    }
    if (Test-Path -LiteralPath $runtimeDll) {
        throw "Installed bundle unexpectedly depends on libunwind.dll"
    }
    & (Join-Path $PSScriptRoot "test-portable-bundle.ps1") -BundleDirectory $testRoot
    if ($LASTEXITCODE -ne 0) { throw "Installed executable smoke test failed with exit code $LASTEXITCODE" }
}
finally {
    $uninstaller = Join-Path $testRoot "Uninstall.exe"
    if (Test-Path -LiteralPath $uninstaller) {
        $uninstall = Start-Process -FilePath $uninstaller -ArgumentList @('/S') -PassThru -Wait -WindowStyle Hidden
        if ($uninstall.ExitCode -ne 0) { throw "Uninstaller failed with exit code $($uninstall.ExitCode)" }
    }
}

if (Test-Path -LiteralPath $testRoot) { throw "Uninstaller left smoke target directory" }
if (Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\CodexQuota') {
    throw "Uninstaller left the CodexQuota registry key"
}

"INSTALLER_SMOKE_PASS"
