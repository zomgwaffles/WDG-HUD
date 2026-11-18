# Android release signing — G5A50.3a (signing-drill phase)

**Release status:** **Not ready for distribution.** This phase adds local tooling to exercise private key management, APK alignment, signing, verification and SHA-256 checksums. It does **not** create a v1.0 build variant, change the Android application ID, add a signing key to Git, or update existing phones.

## Identities and data safety

| | Current debug build | Proposed future public release |
| --- | --- | --- |
| Application ID | `com.example.wdghud` (unchanged) | `io.github.zomgwaffles.wdghud` (**proposal; not yet applied**) |
| Certificate | Android's debug certificate | Separate private production certificate |
| Device storage | Existing encrypted WDGWars API key + cumulative AP history | Separate app sandbox; **no automatic migration** |
| Gradle version | `versionCode=1`, `versionName=1.0` (development settings) | To be established before public release |

An Android in-place update normally needs the *same application ID* and a compatible signing key lineage. Never uninstall the debug app to install a release candidate: doing so can permanently delete API credentials and accumulated AP history. No migration/export capability exists yet.

## 1. Generate the release key (once, on a trusted machine)

From the repository root, PowerShell:

```powershell
.\tools\release\New-ReleaseKey.ps1
```

The script uses JDK `keytool` and prompts you for a password; it never stores the password in source. The generated PKCS12 key is outside Git at:

`$HOME\WDGHUD-Secrets\android-release\wdghud-release.p12`

**Back up the keystore AND its password separately and securely.** Losing the private key prevents ordinary signed APK updates. Never paste its password into an issue, screenshot or chat; never upload the private key. Do not rename/recreate a key after a release has shipped.

## 2. Test signing without changing your app

Run from repository root:

```powershell
.\tools\release\Build-SigningDrill.ps1
```

This calls Gradle `:app:assembleRelease` and expects `app-release-unsigned.apk`, then runs Android SDK Build Tools `zipalign`, `apksigner sign`, and `apksigner verify --verbose --print-certs`. The script reads one passphrase from a secure terminal prompt, prints the certificate fingerprints and SHA-256, and writes drill output and `SHA256SUMS.txt` under the Git-ignored `WDGHUD-Android/app/build/outputs/signing-drill/` directory.

**Do not install, publish or distribute the signing-drill APK.** It currently contains the existing development package ID, but a different signing certificate from the installed debug app. Trying to install it as an update would fail signature compatibility checks; removing the debug app to force installation would lose private app data.

If `app-release-unsigned.apk` is absent, stop and report the error. The script intentionally refuses to re-sign an already-signed APK. If SDK tools cannot be found, pass `-SdkPath "C:\Path\To\Android\Sdk"` with the actual SDK location.

## 3. Required before a public APK

- Choose/freeze the **public Android application ID**. A distinct public application means a fresh install; it does not migrate debug storage.
- Define stable `versionName`, strictly increasing `versionCode`, and a compatibility policy for the watch's BLE protocol.
- Configure a public build variant / release signing path that cannot accidentally ship a debug-signed APK or `com.example.wdghud`.
- Confirm the packaged manifest, app ID, debug flag and signature with tools on the final built artifact.
- Review resolved licenses and corresponding GPLv3 source, make the hashes and public release notes available, and audit repository history for credentials.
- Test fresh install, signing-to-same-key upgrade and permission/pairing behavior on a separate device.

## Official references

- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/tools/zipalign
- https://developer.android.com/tools/apksigner
- https://developer.android.com/build/build-variants

The toolchain was reviewed against these references; full Windows PowerShell + Android SDK execution remains a local acceptance step.
