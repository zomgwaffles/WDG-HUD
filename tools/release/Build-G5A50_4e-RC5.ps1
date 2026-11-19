# G5A50.4e: signed Android RC5 (territory freshness radius fix) from current working-tree source.
# Makes no source edits to the original checkout; no install/uninstall/data clear.
# Copies Android source to a temporary folder, excluding generated files/secrets.
[CmdletBinding()]
param(
    [string]$KeyStorePath = (Join-Path $HOME 'WDGHUD-Secrets\android-release\wdghud-release.p12'),
    [string]$KeyAlias = 'wdghud-release',
    [string]$SdkPath = '',
    [string]$PriorApk = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedHead = '60445c9da28d6d11bf8c75fb6906025ef64e33b1'
$releaseId = 'io.github.zomgwaffles.wdghud'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$androidSource = Join-Path $repo 'WDGHUD-Android'
$gradleSource = Join-Path $androidSource 'app\build.gradle.kts'
$outDir = Join-Path $androidSource 'app\build\outputs\rc5-private'
$outApk = Join-Path $outDir 'WDGHUD-RC5-10004-TEST-ONLY.apk'
$tmp = Join-Path $HOME ('wrc5-' + [guid]::NewGuid().ToString('N').Substring(0,8))
$secretBstr = [IntPtr]::Zero
$password = $null
$securePassword = $null

function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit $LASTEXITCODE)." }
}
function Get-SignerDigest([string]$apk, [string]$signTool) {
    $lines = @(& $signTool verify --verbose --print-certs $apk)
    Assert-Exit "Signature verification: $apk"
    $line = @($lines | Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})' } | Select-Object -First 1)
    if ($line.Count -ne 1) { throw "Signing certificate digest missing: $apk" }
    return [regex]::Match($line[0], 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})').Groups[1].Value.ToLowerInvariant()
}
function Get-SdkTools([string]$sdk) {
    if (-not $sdk) { return $null }
    $btRoot = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $btRoot -PathType Container)) { return $null }
    $dirs = @(Get-ChildItem -LiteralPath $btRoot -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending)
    foreach ($d in $dirs) {
        $align = Join-Path $d.FullName 'zipalign.exe'
        $sign = Join-Path $d.FullName 'apksigner.bat'
        $aapt = Join-Path $d.FullName 'aapt.exe'
        if ((Test-Path $align) -and (Test-Path $sign) -and (Test-Path $aapt)) {
            return @{ sdk = $sdk; version = $d.Name; align = $align; sign = $sign; aapt = $aapt }
        }
    }
    return $null
}

try {
    if (-not (Test-Path -LiteralPath $gradleSource -PathType Leaf)) {
        throw 'Run this script from tools\release in the WDG-HUD repository.'
    }
    $head = (& git -C $repo rev-parse HEAD).Trim()
    Assert-Exit 'Git revision check'
    if ($head -ne $expectedHead) { throw "Unexpected Git HEAD $head. Re-review before building." }
    if (Test-Path -LiteralPath $outApk) { throw "Refusing to overwrite existing RC5: $outApk" }
    # Ensure this RC includes the ownership-freshness bug fix, not the broken RC4 source.
    $freshnessSource = Join-Path $androidSource 'app\src\main\java\com\example\wdghud\TerritoryFreshnessStore.kt'
    if (-not (Test-Path -LiteralPath $freshnessSource -PathType Leaf)) {
        throw 'TerritoryFreshnessStore.kt missing; refusing RC5 build.'
    }
    $freshnessText = [IO.File]::ReadAllText($freshnessSource)
    if ($freshnessText.Contains('WdgWarsGrid.window(center, 3)') -or
        -not $freshnessText.Contains('for (northOffset in -3L..3L)') -or
        -not $freshnessText.Contains('for (eastOffset in -3L..3L)')) {
        throw 'RC5 ownership-freshness fix absent or incomplete. Stop and inspect source.'
    }
    $sourceText = [IO.File]::ReadAllText($gradleSource)
    $checks = @(
        'applicationId = "com.example.wdghud"',
        'variant.applicationId.set("io.github.zomgwaffles.wdghud")',
        'output.versionCode.set(10000)',
        'output.versionName.set("1.0.0")'
    )
    foreach ($c in $checks) { if (-not $sourceText.Contains($c)) { throw "Gradle identity check failed: $c" } }
    foreach ($c in @('output.versionCode.set(10000)', 'output.versionName.set("1.0.0")')) {
        if ($sourceText.Split(@($c),[StringSplitOptions]::None).Count -ne 2) {
            throw "Expected exactly one occurrence of $c"
        }
    }
    $key = [IO.Path]::GetFullPath($KeyStorePath)
    if (-not (Test-Path -LiteralPath $key -PathType Leaf)) { throw "Missing signing key: $key" }
    $repoPrefix = $repo.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ($key.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Signing key must be stored outside the repository.'
    }
    if ($KeyAlias -notmatch '^[A-Za-z0-9_.-]{1,50}$') { throw 'Invalid signing alias.' }

    $sdks = @()
    if ($SdkPath) { $sdks += $SdkPath }
    if ($env:ANDROID_HOME) { $sdks += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdks += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $sdks += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    $bt = $null
    foreach ($sdk in $sdks) { $bt = Get-SdkTools $sdk; if ($bt) { break } }
    if (-not $bt) { throw 'Android build-tools not found (aapt, zipalign, apksigner). Pass -SdkPath.' }

    Write-Host 'RC5: staging the CURRENT Android working tree (including uncommitted source edits).'
    Write-Host "Source Git HEAD: $head"
    Write-Host "Android build tools: $($bt.version)"
    Write-Host 'Original checkout and release versionCode=10000 will NOT be edited.'
    New-Item -ItemType Directory -Path $tmp -ErrorAction Stop | Out-Null
    $stagedAndroid = Join-Path $tmp 'WDGHUD-Android'
    # /XJ avoids following any user-created junctions. Explicitly exclude build output,
    # local credentials and per-machine SDK settings. SDK path is set below.
    & robocopy $androidSource $stagedAndroid /E /XJ /R:1 /W:1 /NFL /NDL /NJH /NJS /NP `
        /XD '.gradle' '.idea' '.git' '.kotlin' 'build' '.cxx' `
        /XF 'local.properties' '*.p12' '*.jks' '*.keystore' '*.key' '.env' '*.pem' '*.pfx' | Out-Host
    $copyCode = $LASTEXITCODE
    if ($copyCode -ge 8) { throw "Source snapshot failed (robocopy code $copyCode)." }

    $stagedFreshness = Join-Path $stagedAndroid 'app\src\main\java\com\example\wdghud\TerritoryFreshnessStore.kt'
    if (-not (Test-Path -LiteralPath $stagedFreshness -PathType Leaf) -or
        [IO.File]::ReadAllText($stagedFreshness) -cne $freshnessText) {
        throw 'RC5 freshness fix not faithfully staged; refusing to build.'
    }
    $stagedGradle = Join-Path $stagedAndroid 'app\build.gradle.kts'
    $stagedText = [IO.File]::ReadAllText($stagedGradle)
    $normalize = { param([string]$s) $s.Replace("`r`n", "`n").Replace("`r", "`n") }
    if ((& $normalize $stagedText) -cne (& $normalize $sourceText)) {
        throw 'Staged Gradle source differs from original; refusing version modification.'
    }
    $rc5Text = $stagedText.Replace('output.versionCode.set(10000)', 'output.versionCode.set(10004)').Replace(
        'output.versionName.set("1.0.0")', 'output.versionName.set("1.0.0-rc5")')
    [IO.File]::WriteAllText($stagedGradle, $rc5Text)
    $sdkForward = $bt.sdk.Replace('\', '/')
    [IO.File]::WriteAllText((Join-Path $stagedAndroid 'local.properties'), "sdk.dir=$sdkForward`n")

    Push-Location $stagedAndroid
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease | Out-Host
        Assert-Exit 'RC5 Gradle tests and release build'
    } finally { Pop-Location }
    $unsigned = Join-Path $stagedAndroid 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (-not (Test-Path -LiteralPath $unsigned -PathType Leaf)) { throw 'Unsigned RC5 build not found.' }
    $badging = @(& $bt.aapt dump badging $unsigned)
    Assert-Exit 'APK package metadata read'
    $package = @($badging | Where-Object { $_ -match '^package:\s+name=' })
    $pattern = "^package:\s+name='io\.github\.zomgwaffles\.wdghud'\s+versionCode='10004'\s+versionName='1\.0\.0-rc5'"
    if ($package.Count -ne 1 -or $package[0] -notmatch $pattern) {
        throw "RC5 package identity/version mismatch: $($package -join ' ')"
    }
    if ($badging -match 'application-debuggable') { throw 'Refusing to sign debuggable RC5.' }

    $aligned = Join-Path $tmp 'rc5-aligned-unsigned.apk'
    $signed = Join-Path $tmp 'rc5-signed.apk'
    & $bt.align -f 4 $unsigned $aligned | Out-Host
    Assert-Exit 'APK zipalign'
    & $bt.align -c -v 4 $aligned | Out-Null
    Assert-Exit 'APK zipalign verification'
    $securePassword = Read-Host 'Existing release keystore password (hidden)' -AsSecureString
    $secretBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretBstr)
    $password | & $bt.sign sign --ks $key --ks-key-alias $KeyAlias --ks-pass stdin --out $signed $aligned | Out-Host
    Assert-Exit 'RC5 signing'
    $cert = Get-SignerDigest $signed $bt.sign
    # RC5 is an in-place update to the already installed RC4 release identity.
    # Require a matching signing certificate for the EXACT previous APK.
    $comparisonApk = $PriorApk
    if (-not $comparisonApk) {
        $comparisonApk = Join-Path $androidSource 'app\build\outputs\rc4-private\WDGHUD-RC4-10003-TEST-ONLY.apk'
    }
    $prior = [IO.Path]::GetFullPath($comparisonApk)
    if (-not (Test-Path -LiteralPath $prior -PathType Leaf)) {
        throw "Prior signed RC4 APK missing: $prior. Pass -PriorApk for a verified previous release."
    }
    $oldCert = Get-SignerDigest $prior $bt.sign
    if ($cert -ne $oldCert) {
        throw 'STOP: RC5 signing certificate does not match RC4; no upgrade permitted.'
    }
    Write-Host 'RC4/RC5 signing certificate comparison: PASS'
    $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $signed).Hash.ToLowerInvariant()
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    if (Test-Path -LiteralPath $outApk) { throw 'RC5 output appeared unexpectedly; refusing overwrite.' }
    Move-Item -LiteralPath $signed -Destination $outApk -ErrorAction Stop
    [IO.File]::WriteAllText((Join-Path $outDir 'SHA256SUMS.txt'), "$sha256  $(Split-Path -Leaf $outApk)`n")
    $report = @(
        'G5A50.4e RC5 -- PRIVATE TEST ONLY',
        "Git HEAD: $head",
        'Source: snapshot of current Android working directory, including uncommitted edits',
        "Application ID: $releaseId",
        'versionCode: 10004',
        'versionName: 1.0.0-rc5',
        "Signing cert SHA-256: $cert",
        "APK SHA-256: $sha256",
        "Prior certificate comparison: PASS ($prior)",
        'Tests/build/signature: PASS; install/upgrade/data-retention: NOT YET TESTED'
    )
    [IO.File]::WriteAllText((Join-Path $outDir 'RC5_TEST_REPORT.txt'), ($report -join "`n") + "`n")
    Write-Host 'RC5 tests, package identity, alignment, signature: PASS'
    Write-Host "Signed APK: $outApk"
    Write-Host "Signer SHA-256: $cert"
    Write-Host 'NO install was performed. Verify report before upgrading the installed RC4 app.'
} finally {
    $password = $null
    $securePassword = $null
    if ($secretBstr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretBstr)
    }
    if (Test-Path -LiteralPath $tmp -PathType Container) {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
