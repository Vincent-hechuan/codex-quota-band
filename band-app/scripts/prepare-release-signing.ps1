param(
  [string]$AndroidProjectPath = (Join-Path $PSScriptRoot '..\..\android-app')
)

$ErrorActionPreference = 'Stop'

function Get-RequiredProperty([hashtable]$properties, [string]$name) {
  $value = $properties[$name]
  if ([string]::IsNullOrWhiteSpace($value)) {
    throw "Missing $name in ignored android-app/local.properties."
  }
  return $value
}

$propertiesPath = Join-Path $AndroidProjectPath 'local.properties'
if (-not (Test-Path $propertiesPath)) {
  throw "Release signing configuration was not found: $propertiesPath"
}

$properties = @{}
Get-Content $propertiesPath | ForEach-Object {
  if ($_ -match '^([^=]+)=(.*)$') {
    $properties[$matches[1]] = $matches[2]
  }
}

$storePath = Get-RequiredProperty $properties 'codexQuotaReleaseStoreFile'
$storePassword = Get-RequiredProperty $properties 'codexQuotaReleaseStorePassword'
$keyAlias = Get-RequiredProperty $properties 'codexQuotaReleaseKeyAlias'
$keyPassword = Get-RequiredProperty $properties 'codexQuotaReleaseKeyPassword'
$keytool = Join-Path $env:LOCALAPPDATA 'codex-quota-dev\jdk-17\bin\keytool.exe'
$openssl = 'C:\Program Files\Git\usr\bin\openssl.exe'
if (-not (Test-Path $keytool) -or -not (Test-Path $openssl)) {
  throw 'The configured JDK keytool or Git OpenSSL executable is unavailable.'
}

$releaseDirectory = Join-Path $PSScriptRoot '..\sign\release'
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
$temporaryP12 = Join-Path ([System.IO.Path]::GetTempPath()) ("codexquota-band-{0}.p12" -f [guid]::NewGuid())
$temporaryPassword = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))

try {
  & $keytool -importkeystore -noprompt `
    -srckeystore $storePath -srcstoretype JKS -srcstorepass $storePassword -srckeypass $keyPassword -srcalias $keyAlias `
    -destkeystore $temporaryP12 -deststoretype PKCS12 -deststorepass $temporaryPassword -destkeypass $temporaryPassword -destalias $keyAlias 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not export the local Android release signing identity.' }

  $privateBundle = (& $openssl pkcs12 -in $temporaryP12 -passin "pass:$temporaryPassword" -nocerts -nodes) -join "`n"
  $certificateBundle = (& $openssl pkcs12 -in $temporaryP12 -passin "pass:$temporaryPassword" -clcerts -nokeys) -join "`n"
  $privateMatch = [regex]::Match($privateBundle, '(?s)-----BEGIN(?: RSA)? PRIVATE KEY-----.*?-----END(?: RSA)? PRIVATE KEY-----')
  $certificateMatch = [regex]::Match($certificateBundle, '(?s)-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----')
  if (-not $privateMatch.Success -or -not $certificateMatch.Success) {
    throw 'Could not derive PEM signing material from the Android release identity.'
  }

  [System.IO.File]::WriteAllText((Join-Path $releaseDirectory 'private.pem'), $privateMatch.Value + [Environment]::NewLine)
  [System.IO.File]::WriteAllText((Join-Path $releaseDirectory 'certificate.pem'), $certificateMatch.Value + [Environment]::NewLine)

  $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $releaseDirectory 'certificate.pem'))
  $digest = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
  Write-Output "Prepared ignored band-app/sign/release signing material (certificate SHA-256: $digest)."
}
finally {
  if (Test-Path $temporaryP12) {
    [System.IO.File]::Delete($temporaryP12)
  }
}
