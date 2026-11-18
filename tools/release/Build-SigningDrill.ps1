# G5A50.3b — Non-distributable public-ID signing acceptance drill.
# Android release ID is distinct from the existing development package.
# Does not modify app source, signing configuration, or installed devices.
# Signed output stays under app/build/ (Git-ignored); DO NOT PUBLISH or INSTALL yet.
[CmdletBinding()]
param(
    [string]$KeyStorePath = (Join-Path $HOME 'WDGHUD-Secrets\android-release\wdghud-release.p12'),
    [string]$KeyAlias = 'wdghud-release',
    [string]$SdkPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Checked($exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed (exit $LASTEXITCODE): $exe" }
}
function IsUnder($candidate, $parent) {
    $p = [IO.Path]::GetFullPath($parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $c = [IO.Path]::GetFullPath($candidate)
    return $c.StartsWith($p, [StringComparison]::OrdinalIgnoreCase)
}
function FindBuildTools($path) {
    $root = Join-Path $path 'build-tools'
    if (-not (Test-Path -LiteralPath $root)) { return $null }
    $versions = @(Get-ChildItem -LiteralPath $root -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending)
    foreach ($v in $versions) {
        $align = Join-Path $v.FullName 'zipalign.exe'
        $sign = Join-Path $v.FullName 'apksigner.bat'
        $aapt = Join-Path $v.FullName 'aapt.exe'
        if ((Test-Path $align) -and (Test-Path $sign) -and (Test-Path $aapt)) {
            return @{ zipalign=$align; apksigner=$sign; aapt=$aapt; version=$v.Name }
        }
    }
    return $null
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$appRoot = Join-Path $repo 'WDGHUD-Android'
$key = [IO.Path]::GetFullPath($KeyStorePath)
if (IsUnder $key $repo) { throw 'Signing key must be OUTSIDE the repository.' }
if (-not (Test-Path -LiteralPath $key -PathType Leaf)) { throw "Keystore not found: $key" }
if ($KeyAlias -notmatch '^[A-Za-z0-9_.-]{1,50}$') { throw 'Invalid key alias.' }
if (-not (Test-Path (Join-Path $appRoot 'gradlew.bat'))) { throw 'Android project not found.' }

# Refuse to run against an unexpected build configuration. This is a *dry run*;
# no APK will be installed or published by this script.
$gradleFile = Join-Path $appRoot 'app\build.gradle.kts'
$gradleText = [IO.File]::ReadAllText($gradleFile)
$identityMarkers = @(
    'applicationId = "com.example.wdghud"',
    'onVariants(selector().withBuildType("release"))',
    'variant.applicationId.set("io.github.zomgwaffles.wdghud")',
    'output.versionCode.set(10000)',
    'output.versionName.set("1.0.0")'
)
foreach ($marker in $identityMarkers) {
    if (-not $gradleText.Contains($marker)) {
        throw "Unexpected Gradle identity configuration. Missing expected marker: $marker"
    }
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
if (-not $tools) { throw 'Cannot find zipalign/apksigner/aapt in Android SDK Build Tools. Provide -SdkPath.' }

Write-Host 'G5A50.3b PUBLIC-ID SIGNING DRILL ONLY. No device install or public release.'
Write-Host 'Android build-tools:' $tools.version
Push-Location $appRoot
try {
    Invoke-Checked '.\gradlew.bat' @(':app:assembleRelease')
} finally {
    Pop-Location
}
$apkDir = Join-Path $appRoot 'app\build\outputs\apk\release'
$unsigned = Join-Path $apkDir 'app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsigned -PathType Leaf)) {
    throw 'Expected unsigned APK not found. Stop: do not re-sign an APK without inspection.'
}

# Validate the actual packaged application identity. Do not rely only on Gradle source.
$badging = (& $tools.aapt dump badging $unsigned) | Select-String '^package: name=' | Select-Object -First 1
if ($LASTEXITCODE -ne 0 -or -not $badging -or
    $badging.ToString() -notmatch "^package:\s+name='io\.github\.zomgwaffles\.wdghud'\s+versionCode='10000'\s+versionName='1\.0\.0'") {
    throw 'Built APK is not io.github.zomgwaffles.wdghud / 10000 / 1.0.0. Refusing to sign.'
}

$outDir = Join-Path $appRoot 'app\build\outputs\public-id-drill'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$aligned = Join-Path $outDir 'WDGHUD-1.0.0-10000-aligned-unsigned.apk'
$signed = Join-Path $outDir 'WDGHUD-1.0.0-10000-SIGNING-DRILL-NOT-FOR-DISTRIBUTION.apk'
if (Test-Path -LiteralPath $signed) { throw "Drill output already exists; move/rename it before re-running: $signed" }
Invoke-Checked $tools.zipalign @('-f', '4', $unsigned, $aligned)
Invoke-Checked $tools.zipalign @('-c', '-v', '4', $aligned)

# Use a secure console prompt; never embed the password in arguments or files.
# PKCS12 keytool creates the key and keystore with the same password.
$securePassword = Read-Host 'Private keystore password (not shown)' -AsSecureString
$ptr = [IntPtr]::Zero
try {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    # 'stdin' consumes exactly one line; native apksigner inherits it.
    $password | & $tools.apksigner sign --ks $key --ks-key-alias $KeyAlias --ks-pass stdin --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed. No release artifact is valid.' }
} finally {
    if ($ptr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    $password = $null
    $securePassword = $null
}
Invoke-Checked $tools.apksigner @('verify', '--verbose', '--print-certs', $signed)
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $signed).Hash.ToLowerInvariant()
$head = (& git -C $repo rev-parse HEAD).Trim()
$sumFile = Join-Path $outDir 'SHA256SUMS.txt'
[IO.File]::WriteAllText($sumFile, "$hash  $(Split-Path -Leaf $signed)`n")
Write-Host 'PUBLIC ID / VERSION / SIGNING DRILL PASSED'
Write-Host 'Source commit:' $head
Write-Host 'SHA-256:' $hash
Write-Host 'Saved in Git-ignored build outputs:' $signed
Write-Host 'Public candidate ID: io.github.zomgwaffles.wdghud, versionCode=10000, versionName=1.0.0'
Write-Host 'DO NOT INSTALL OR PUBLISH. Fresh-install and upgrade acceptance is still pending.'
