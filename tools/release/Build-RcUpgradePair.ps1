# G5A50.3c PATCH3: build TWO PRIVATE release-acceptance APKs.
# PATCH3 uses an explicit [ref] result: native tool output can never become a candidate record.
# Never changes the original checkout; uses a temporary detached Git worktree.
# Never installs APKs, publishes files, or writes any private key/password to disk.
[CmdletBinding()]
param(
    [string]$KeyStorePath = (Join-Path $HOME 'WDGHUD-Secrets\android-release\wdghud-release.p12'),
    [string]$KeyAlias = 'wdghud-release',
    [string]$SdkPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedHead = '60445c9da28d6d11bf8c75fb6906025ef64e33b1'
$publicId = 'io.github.zomgwaffles.wdghud'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$appRoot = Join-Path $repo 'WDGHUD-Android'
$repoStatus = @(& git -C $repo status --porcelain)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Git status.' }
# Accept ONLY the two freshly installed, untracked G5A50.3c files.
# They are not part of the built release candidates; the scratch worktree uses HEAD.
$allowed = @('?? tools/release/Build-RcUpgradePair.ps1', '?? docs/RC_ACCEPTANCE.md')
$unexpected = @($repoStatus | Where-Object { $_ -and ($_ -notin $allowed) })
if ($unexpected.Count -ne 0) {
    throw 'Unexpected Git changes; do not build until reviewed: ' + ($unexpected -join ', ')
}
$head = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $expectedHead) {
    throw "Unexpected source revision $head. Requires fresh review against current main."
}

$key = [IO.Path]::GetFullPath($KeyStorePath)
if (-not (Test-Path -LiteralPath $key -PathType Leaf)) { throw "Keystore not found: $key" }
$repoPrefix = $repo.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($key.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Private signing key must be outside the Git repository.'
}
if ($KeyAlias -notmatch '^[A-Za-z0-9_.-]{1,50}$') { throw 'Invalid key alias.' }

function FindBuildTools($path) {
    if (-not $path) { return $null }
    $root = Join-Path $path 'build-tools'
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { return $null }
    $versions = @(Get-ChildItem -LiteralPath $root -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending)
    foreach ($v in $versions) {
        $align = Join-Path $v.FullName 'zipalign.exe'
        $sign = Join-Path $v.FullName 'apksigner.bat'
        $aapt = Join-Path $v.FullName 'aapt.exe'
        if ((Test-Path $align) -and (Test-Path $sign) -and (Test-Path $aapt)) {
            return @{ zipalign = $align; apksigner = $sign; aapt = $aapt; version = $v.Name }
        }
    }
    return $null
}

$candidates = @()
if ($SdkPath) { $candidates += $SdkPath }
if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
$bt = $null
foreach ($candidate in $candidates) {
    $bt = FindBuildTools $candidate
    if ($bt) { break }
}
if (-not $bt) { throw 'SDK zipalign/apksigner/aapt missing. Use -SdkPath.' }

$gradleOriginal = Join-Path $appRoot 'app\build.gradle.kts'
$gradleText = [IO.File]::ReadAllText($gradleOriginal)
$markers = @(
    'applicationId = "com.example.wdghud"',
    'variant.applicationId.set("io.github.zomgwaffles.wdghud")',
    'output.versionCode.set(10000)',
    'output.versionName.set("1.0.0")'
)
foreach ($marker in $markers) {
    if (-not $gradleText.Contains($marker)) {
        throw "Unexpected Gradle release identity: missing $marker"
    }
}
if ($gradleText.Split(@('output.versionCode.set(10000)'), [StringSplitOptions]::None).Count -ne 2 -or
    $gradleText.Split(@('output.versionName.set("1.0.0")'), [StringSplitOptions]::None).Count -ne 2) {
    throw 'Expected exactly one release versionCode and versionName statement.'
}

$outDir = Join-Path $appRoot 'app\build\outputs\rc-acceptance'
if (Test-Path -LiteralPath $outDir) {
    throw "Output directory already exists. Move it aside before a new run: $outDir"
}
# Keep the worktree path short to avoid Windows MAX_PATH failures inside Gradle.
# Use the current user's profile (writable without elevation), not C:\ or AppData\Local\Temp.
$worktree = Join-Path $HOME ('wrc3c-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
if (Test-Path -LiteralPath $worktree) { throw "Refusing to replace existing worktree path: $worktree" }
$worktreeAdded = $false
$passwdPtr = [IntPtr]::Zero
$passwd = $null
$securePassword = $null

function Require-Exit([string]$description) {
    if ($LASTEXITCODE -ne 0) { throw "$description failed (exit $LASTEXITCODE)." }
}
function BuildCandidate([string]$workspace, [int]$versionCode, [string]$versionName,
                        [string]$outputName, [hashtable]$bt, [string]$key,
                        [string]$keyAlias, [string]$password, [string]$outDir,
                        [ref]$candidateResult) {
    # Clear result until the complete candidate has been signed, independently
    # verified, checked for identity/version and its hashes calculated.
    $candidateResult.Value = $null
    $android = Join-Path $workspace 'WDGHUD-Android'
    Push-Location $android
    try {
        # Keep native build output visible without returning it as a function value.
        & .\gradlew.bat :app:assembleRelease | Out-Host
        Require-Exit 'Gradle release build'
    } finally { Pop-Location }

    $unsigned = Join-Path $android 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (-not (Test-Path -LiteralPath $unsigned -PathType Leaf)) {
        throw "Unsigned APK missing: $unsigned"
    }
    $badging = @(& $bt.aapt dump badging $unsigned)
    Require-Exit 'aapt dump badging'
    $identity = @($badging | Where-Object { $_ -match '^package:\s+name=' })
    $expected = "^package:\s+name='io\.github\.zomgwaffles\.wdghud'\s+versionCode='$versionCode'\s+versionName='$([regex]::Escape($versionName))'"
    if ($identity.Count -ne 1 -or $identity[0] -notmatch $expected) {
        throw "Unexpected packaged identity/version for $versionCode. Refuse signing."
    }
    if ($badging -match 'application-debuggable') { throw 'Release candidate unexpectedly debuggable.' }

    $aligned = Join-Path $outDir ($outputName + '-aligned-unsigned.apk')
    $signed  = Join-Path $outDir ($outputName + '.apk')
    if (Test-Path -LiteralPath $signed) { throw "Refusing to replace signed APK: $signed" }
    & $bt.zipalign -f 4 $unsigned $aligned | Out-Host
    Require-Exit 'zipalign'
    & $bt.zipalign -c -v 4 $aligned | Out-Null
    Require-Exit 'zipalign verification'

    # Password travels via stdin, NOT in command-line arguments or on disk.
    $password | & $bt.apksigner sign --ks $key --ks-key-alias $keyAlias --ks-pass stdin --out $signed $aligned | Out-Host
    Require-Exit 'APK signing'
    $certOutput = @(& $bt.apksigner verify --verbose --print-certs $signed)
    Require-Exit 'APK signature verification'
    $certificateLine = @($certOutput | Where-Object {
        $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
    } | Select-Object -First 1)
    if ($certificateLine.Count -ne 1) { throw 'Cannot identify signing certificate SHA-256.' }
    $match = [regex]::Match($certificateLine[0], 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    $cert = $match.Groups[1].Value.ToLowerInvariant()
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $signed).Hash.ToLowerInvariant()
    Remove-Item -LiteralPath $aligned -Force
    # Do not return records through the PowerShell success-output pipeline:
    # external build tools and function output can contaminate it.
    $candidateResult.Value = @{
        apk = $signed
        certificate = $cert
        sha256 = $hash
        versionCode = $versionCode
        versionName = $versionName
    }
    Write-Host "G5A50.3c PATCH3 verified candidate versionCode=$versionCode"
}

Write-Host 'G5A50.3c PATCH3 candidate build: no production source changes and NO installs.'
Write-Host 'SDK build-tools:' $bt.version
Write-Host 'Source commit:' $head
try {
    & git -C $repo worktree add --detach $worktree HEAD
    Require-Exit 'Temporary worktree setup'
    $worktreeAdded = $true

    # Git may check out LF while the original Windows working copy has CRLF.
    # Compare EXACT content except line-ending convention; never ignore other edits.
    $testGradle = Join-Path $worktree 'WDGHUD-Android\app\build.gradle.kts'
    $worktreeText = [IO.File]::ReadAllText($testGradle)
    $normalizeEol = { param([string]$text) $text.Replace("`r`n", "`n").Replace("`r", "`n") }
    if ((& $normalizeEol $worktreeText) -cne (& $normalizeEol $gradleText)) {
        throw 'Temporary worktree build.gradle differs beyond line endings from audited baseline.'
    }

    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $securePassword = Read-Host 'Release keystore password (not shown)' -AsSecureString
    $passwdPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $passwd = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwdPtr)

    # RC1: baseline release ID, versionCode 10000.
    $rc1 = $null
    BuildCandidate $worktree 10000 '1.0.0' 'WDGHUD-RC1-10000-TEST-ONLY' `
        $bt $key $KeyAlias $passwd $outDir ([ref]$rc1)

    # RC2: modify only the TEMPORARY detached worktree; original Git checkout untouched.
    $sourceText = [IO.File]::ReadAllText($testGradle)
    if ((& $normalizeEol $sourceText) -cne (& $normalizeEol $gradleText)) {
        throw 'Temporary worktree build.gradle changed unexpectedly during RC1 build.'
    }
    $secondText = $sourceText.Replace('output.versionCode.set(10000)', 'output.versionCode.set(10001)').Replace(
        'output.versionName.set("1.0.0")', 'output.versionName.set("1.0.0-rc2")')
    if ($secondText -eq $sourceText) { throw 'RC2 version edit did not apply.' }
    [IO.File]::WriteAllText($testGradle, $secondText)
    $rc2 = $null
    BuildCandidate $worktree 10001 '1.0.0-rc2' 'WDGHUD-RC2-10001-TEST-ONLY' `
        $bt $key $KeyAlias $passwd $outDir ([ref]$rc2)

    # Explicit out-parameters avoid the PowerShell pipeline return-value trap.
    # Fail closed on absent/malformed results (and never access an undefined property).
    foreach ($record in @($rc1, $rc2)) {
        if ($null -eq $record -or $record -isnot [hashtable]) {
            throw 'Internal error: candidate result was not a single hashtable.'
        }
        foreach ($field in @('apk', 'certificate', 'sha256', 'versionCode', 'versionName')) {
            if (-not $record.ContainsKey($field) -or $null -eq $record[$field] -or
                ([string]$record[$field]).Length -eq 0) {
                throw "Internal error: candidate result missing $field."
            }
        }
        if ([string]$record['certificate'] -notmatch '^[0-9a-f]{64}$' -or
            [string]$record['sha256'] -notmatch '^[0-9a-f]{64}$' -or
            -not (Test-Path -LiteralPath ([string]$record['apk']) -PathType Leaf)) {
            throw 'Internal error: invalid certificate, checksum or signed APK path.'
        }
    }
    if ([string]$rc1['certificate'] -cne [string]$rc2['certificate']) {
        throw 'FATAL: RC1/RC2 use different signing certificates.'
    }
    $checksums = @(
        "$($rc1['sha256'])  $(Split-Path -Leaf $rc1['apk'])",
        "$($rc2['sha256'])  $(Split-Path -Leaf $rc2['apk'])"
    )
    [IO.File]::WriteAllText((Join-Path $outDir 'SHA256SUMS.txt'), ($checksums -join "`n") + "`n")
    $report = @(
        'G5A50.3c RELEASE CANDIDATES - PRIVATE ACCEPTANCE ONLY',
        "Source commit: $head",
        "Application ID: $publicId",
        'RC1 versionCode: 10000; versionName: 1.0.0',
        'RC2 versionCode: 10001; versionName: 1.0.0-rc2',
        "Signing cert SHA-256: $($rc1['certificate'])",
        '', 'Build and signature verified; Android installation and data retention NOT YET TESTED.',
        'Never publish these APKs or distribute signing credentials.'
    )
    [IO.File]::WriteAllText((Join-Path $outDir 'RC_TEST_REPORT.txt'), ($report -join "`n") + "`n")
    Write-Host 'RC1 + RC2 build, package metadata, alignment, signing, and matching certificate: PASS'
    Write-Host 'Output:' $outDir
    Write-Host 'Next: follow docs/RC_ACCEPTANCE.md on a SECONDARY Android device.'
} finally {
    $passwd = $null
    $securePassword = $null
    if ($passwdPtr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwdPtr)
    }
    if ($worktreeAdded) {
        & git -c core.longpaths=true -C $repo worktree remove --force $worktree
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Temporary worktree could not be removed. Remove it after checking: $worktree"
        }
    }
}
