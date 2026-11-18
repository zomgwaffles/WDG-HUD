# G5A50.3a — Create a *private* Android signing key outside the repository.
# Run manually ONCE; never paste the passphrase or share the .p12 file.
[CmdletBinding()]
param(
    [string]$KeyStorePath = (Join-Path $HOME 'WDGHUD-Secrets\android-release\wdghud-release.p12'),
    [string]$KeyAlias = 'wdghud-release'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function IsUnder($candidate, $parent) {
    $p = [IO.Path]::GetFullPath($parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $c = [IO.Path]::GetFullPath($candidate)
    return $c.StartsWith($p, [StringComparison]::OrdinalIgnoreCase)
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$target = [IO.Path]::GetFullPath($KeyStorePath)
if (IsUnder $target $repo) { throw 'Refusing to store private signing key inside Git repository.' }
if (Test-Path -LiteralPath $target) { throw "Keystore already exists. Never overwrite a release key: $target" }
if ($KeyAlias -notmatch '^[A-Za-z0-9_.-]{1,50}$') { throw 'Invalid key alias.' }
$keytoolCmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
if (-not $keytoolCmd) { $keytoolCmd = Get-Command keytool -ErrorAction SilentlyContinue }
if (-not $keytoolCmd) { throw 'keytool not found. Open the terminal with a JDK configured.' }
$dir = Split-Path -Parent $target
New-Item -ItemType Directory -Force -Path $dir | Out-Null

Write-Host 'Creating a 4096-bit RSA / PKCS12 release key in:' $target
Write-Host 'You will be prompted by keytool for a password. Keep it private.'
Write-Host 'Do NOT use a password from this chat or store the keystore inside Git.'
& $keytoolCmd.Source -genkeypair -keystore $target -storetype PKCS12 -alias $KeyAlias -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=WDG HUD Android Release'
if ($LASTEXITCODE -ne 0) { throw "keytool failed (exit $LASTEXITCODE). Check output before retrying." }
if (-not (Test-Path -LiteralPath $target)) { throw 'No keystore created.' }
Write-Host 'KEY CREATED. Make an offline backup of BOTH the keystore and password.'
Write-Host 'The key controls all future APK updates for the signing identity.'
