# G5A50.3a — Non-distributable signing drill for the EXISTING development package.
# Does not modify the app, source, Android signing configuration or installed device.
# Signed output lives under app/build/ (Git-ignored); NEVER publish/install this drill.
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

# G5A50.3a is explicitly only a signing dry run with the old development ID.
$gradleFile = Join-Path $appRoot 'app\build.gradle.kts'
$gradleText = [IO.File]::ReadAllText($gradleFile)
if ($gradleText -notmatch 'applicationId\s*=\s*"com\.example\.wdghud"' -or
    $gradleText -notmatch 'versionCode\s*=\s*1') {
    throw 'Unexpected Android application ID/version. Stop; review signing policy before continuing.'
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

Write-Host 'G5A50.3a signing drill ONLY. No application/device install and no public release.'
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
if (-not $badging -or $badging.ToString() -notmatch "^package: name='com\.example\.wdghud'") {
    throw 'Built APK identity is not the expected development ID. Refusing to sign.'
}

$outDir = Join-Path $appRoot 'app\build\outputs\signing-drill'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$aligned = Join-Path $outDir 'WDGHUD-G5A50.3a-aligned-unsigned.apk'
$signed = Join-Path $outDir 'WDGHUD-G5A50.3a-SIGNING-DRILL-NOT-FOR-DISTRIBUTION.apk'
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
Write-Host 'SIGNING DRILL PASSED'
Write-Host 'Source commit:' $head
Write-Host 'SHA-256:' $hash
Write-Host 'Saved in Git-ignored build outputs:' $signed
Write-Host 'DO NOT INSTALL OR PUBLISH. App ID is still com.example.wdghud.'
Write-Host 'The final public identity and release version are not configured yet.'
