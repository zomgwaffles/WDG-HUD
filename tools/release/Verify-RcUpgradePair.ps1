# G5A50.3c verification-only recovery: NEVER rebuild, sign, install, or delete an APK.
# Checks existing private RC1/RC2 files after a build-script result-handling error.
[CmdletBinding()]
param(
    [string]$OutputDir = '',
    [string]$SdkPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $OutputDir) {
    $OutputDir = Join-Path $repo 'WDGHUD-Android\app\build\outputs\rc-acceptance'
}
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
if (-not (Test-Path -LiteralPath $OutputDir -PathType Container)) {
    throw "Candidate output directory is missing: $OutputDir. Do NOT rerun the builder yet."
}

function FindBuildTools([string]$path) {
    if (-not $path) { return $null }
    $root = Join-Path $path 'build-tools'
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { return $null }
    $versions = @(Get-ChildItem -LiteralPath $root -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending)
    foreach ($v in $versions) {
        $zipalign = Join-Path $v.FullName 'zipalign.exe'
        $apksigner = Join-Path $v.FullName 'apksigner.bat'
        $aapt = Join-Path $v.FullName 'aapt.exe'
        if ((Test-Path -LiteralPath $zipalign) -and
            (Test-Path -LiteralPath $apksigner) -and
            (Test-Path -LiteralPath $aapt)) {
            return @{ zipalign = $zipalign; apksigner = $apksigner;
                      aapt = $aapt; version = $v.Name }
        }
    }
    return $null
}
$candidates = @()
if ($SdkPath) { $candidates += $SdkPath }
if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
$tools = $null
foreach ($sdk in $candidates) {
    $tools = FindBuildTools $sdk
    if ($tools) { break }
}
if ($null -eq $tools) { throw 'Android SDK build tools not found (aapt, apksigner, zipalign).' }

function VerifyCandidate([string]$fileName, [int]$expectedCode, [string]$expectedVersion,
                         [hashtable]$tools, [string]$directory, [ref]$record) {
    $record.Value = $null
    $apk = Join-Path $directory $fileName
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Signed candidate is missing: $apk"
    }
    $badging = @(& $tools.aapt dump badging $apk)
    if ($LASTEXITCODE -ne 0) { throw "aapt badging failed for $fileName" }
    $identities = @($badging | Where-Object { $_ -match '^package:\s+name=' })
    $expected = "^package:\s+name='io\.github\.zomgwaffles\.wdghud'\s+versionCode='$expectedCode'\s+versionName='$([regex]::Escape($expectedVersion))'"
    if ($identities.Count -ne 1 -or $identities[0] -notmatch $expected) {
        throw "Incorrect package ID or version for $fileName"
    }
    if (@($badging | Where-Object { $_ -match 'application-debuggable' }).Count -gt 0) {
        throw "Release APK must not be debuggable: $fileName"
    }
    & $tools.zipalign -c -v 4 $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "zipalign verification failed for $fileName" }
    $certLines = @(& $tools.apksigner verify --verbose --print-certs $apk)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed for $fileName" }
    $found = @($certLines | Where-Object {
        $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
    })
    if ($found.Count -ne 1) { throw "Cannot uniquely identify signing certificate for $fileName" }
    $match = [regex]::Match([string]$found[0],
        'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if (-not $match.Success) { throw "Malformed signing certificate digest for $fileName" }
    $cert = $match.Groups[1].Value.ToLowerInvariant()
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
    $record.Value = @{ apk = $apk; name = $fileName;
                       certificate = $cert; sha256 = $hash }
    Write-Host "VERIFIED $fileName / code $expectedCode / version $expectedVersion"
}

Write-Host 'G5A50.3c VERIFY-ONLY: no build, signing-key access, installation, or deletion.'
Write-Host 'SDK build tools:' $tools.version
Write-Host 'Source APK directory:' $OutputDir
$rc1 = $null
$rc2 = $null
VerifyCandidate 'WDGHUD-RC1-10000-TEST-ONLY.apk' 10000 '1.0.0' $tools $OutputDir ([ref]$rc1)
VerifyCandidate 'WDGHUD-RC2-10001-TEST-ONLY.apk' 10001 '1.0.0-rc2' $tools $OutputDir ([ref]$rc2)

if ($null -eq $rc1 -or $null -eq $rc2 -or
    $rc1 -isnot [hashtable] -or $rc2 -isnot [hashtable]) {
    throw 'Internal error: candidate verification did not yield two records.'
}
foreach ($record in @($rc1, $rc2)) {
    foreach ($field in @('apk', 'name', 'certificate', 'sha256')) {
        if (-not $record.ContainsKey($field) -or $null -eq $record[$field]) {
            throw "Internal error: missing $field in result."
        }
    }
}
if ([string]$rc1['certificate'] -cne [string]$rc2['certificate']) {
    throw 'FATAL: RC1 and RC2 are NOT signed by the same certificate.'
}

$checksums = @(
    "$($rc1['sha256'])  $($rc1['name'])",
    "$($rc2['sha256'])  $($rc2['name'])"
)
$sumFile = Join-Path $OutputDir 'SHA256SUMS.txt'
$expectedText = ($checksums -join "`n") + "`n"
if (Test-Path -LiteralPath $sumFile) {
    $current = [IO.File]::ReadAllText($sumFile).Replace("`r`n", "`n")
    if ($current -cne $expectedText) {
        throw 'Existing SHA256SUMS.txt disagrees with APK contents. Stop; do not overwrite.'
    }
} else {
    [IO.File]::WriteAllText($sumFile, $expectedText)
}
Write-Host 'RC1 + RC2 APK identity, version, alignment, signature and SAME CERTIFICATE: PASS'
Write-Host 'Signing certificate SHA-256:' $rc1['certificate']
Write-Host 'SHA256SUMS:' $sumFile
Write-Host 'APK install/upgrade acceptance is still PENDING; do not publish these private test APKs.'
