# Android release signing — G5A50.3b (public-ID acceptance drill)

**Status:** Build configuration and signing drill only. **No public v1.0 APK has been approved.**

## Application separation

| | Installed development app | Distinct release build |
| --- | --- | --- |
| Application ID | `com.example.wdghud` | `io.github.zomgwaffles.wdghud` |
| Version | `1` / `1.0` | `10000` / `1.0.0` |
| Certificate | Android debug key | Private release key |
| Local app storage | Existing encrypted key + historical sectors | Brand-new sandbox (no migration) |

The `namespace` and Kotlin `package com.example.wdghud` remain unchanged; the release variant changes only the installed application ID and its version. Do not uninstall or clear your existing debug app. The different app ID lets both apps coexist but does not transfer local data.

## Private keystore

Run **once** from the repository root, only if it has not been created:

```powershell
.\tools\release\New-ReleaseKey.ps1
```

The PKCS12 file lives outside Git at `$HOME\WDGHUD-Secrets\android-release\wdghud-release.p12`. Keep offline, independently secured backups of **both** the keystore and its password. Never commit/upload a `.p12`, private password, signing properties, or signing-drill APK.

## G5A50.3b verification

First run host tests and compile both build types from `WDGHUD-Android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

From the **repository root**, run the adapted script:

```powershell
.\tools\release\Build-SigningDrill.ps1
```

The script invokes Gradle, validates the *actual APK* using `aapt dump badging`, requiring `io.github.zomgwaffles.wdghud` / `10000` / `1.0.0`, aligns it, signs using the private PKCS12 file, verifies the signature, and creates `SHA256SUMS.txt`. It does not write credentials into source, install an APK, or publish anything. Signed output remains in the ignored `WDGHUD-Android/app/build/outputs/public-id-drill/` directory.

A mismatch in manifest identity, version, or signature must be treated as a **stop**, not a reason to edit the expected checks. Please share only sanitized logs, never keystore files or passwords.

## Remaining gates

- Confirm debug APK still packages `com.example.wdghud`, version `1` / `1.0`, and signs with the debug certificate.
- Confirm the release APK packages `io.github.zomgwaffles.wdghud`, version `10000` / `1.0.0`, and signs with the private release certificate.
- Verify release manifest and Android component launch paths (`com.example.wdghud.MainActivity`), BLE/GPS permissions and account setup on a **fresh separate** test device or Android user profile.
- Make a second signed release build with an incremented version code and prove a same-key upgrade retains *release* data. Do not publish yet.
- Complete library license audit, source/history secret scan, device matrix and watch compatibility. Only then tag and publish `v1.0.0`.

Official references: https://developer.android.com/studio/publish/app-signing , https://developer.android.com/tools/apksigner , https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ApplicationVariant .
