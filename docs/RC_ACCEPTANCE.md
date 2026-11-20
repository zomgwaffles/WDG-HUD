# G5A50.3c — Signed release-candidate acceptance

**Purpose:** privately validate a clean public-app installation and a same-certificate, higher-version in-place upgrade. **Not a public release.** The existing debug app is never removed, and watch NVS is never erased. The script builds two APKs in a temporary detached Git worktree, leaving the checked-in application ID/version untouched.

## Current release-candidate note — G5A50.5f / RC6 (2026-11-20)

This document's RC1→RC2 commands are a **historical private acceptance procedure**, **not** instructions to rebuild those versions on current `main`. The older script pins commit `60445c9` and is expected to reject source checkpoint `662f93c`.

Maintainer-reported private candidates progressed to signed **RC5 / code 10004** and **RC6 / code 10005** under release application ID `io.github.zomgwaffles.wdghud`. For RC6 (`1.0.0-rc6`), Android logs confirmed `CFG:STALE,30`/`STALE_INTERVAL=30` and `CFG:STALE,60`/`STALE_INTERVAL=60`, alongside successful AP prefetch/retry. Explicit `240` log proof, extended TTL and independent clean install/account-switch acceptance are still needed.

**Do not run** `Build-G5A50_5a-RC6.ps1` from current commit without reviewing its expected-HEAD guard: it is pinned to `fb0c802` and intentionally rejects later changes. Never remove signing/identity checks merely to make a build pass; build a **new** reviewed candidate for the future public-release work. Do not uninstall existing apps or wipe watch NVS during upgrade verification.

## 1. Build two signed candidates (no installation)

From the repository root (`C:\Users\zomgw\Documents\GitHub\WDG-HUD`):

```powershell
.\tools\release\Build-RcUpgradePair.ps1
```

The script requires a clean repository at commit `60445c9da28d6d11bf8c75fb6906025ef64e33b1`, the existing private release key outside Git and Android SDK Build Tools. It refuses unexpected Gradle settings. No credentials are put into source, logs or files. It does not install anything, and does not overwrite an earlier candidate-output folder.

Outputs under `WDGHUD-Android\app\build\outputs\rc-acceptance\`:

- `WDGHUD-RC1-10000-TEST-ONLY.apk`: `io.github.zomgwaffles.wdghud`, code `10000`, `1.0.0`.
- `WDGHUD-RC2-10001-TEST-ONLY.apk`: **same** app ID/certificate, code `10001`, `1.0.0-rc2`.
- `SHA256SUMS.txt`, `RC_TEST_REPORT.txt`.

The two APKs are for **private acceptance only**. After testing, **the repository still specifies `10000`**; the scratch `10001` build is not committed. Do not tag v1.0 yet. A phone that has already installed RC2 cannot downgrade to the official `10000` build without a separately planned migration: use a test phone/profile only.

## 2. Choose a test device

**Preferred:** a separate Android phone with BLE/GPS, USB debugging, and a compatible Android OS, or an isolated test user/profile where suitable. Use a test WDGWars account where available. Do not send API keys, cookies, passcodes, GPS tracks or private route details in test logs.

Do not uninstall/clear the existing development app `com.example.wdghud`. Stop its driving session and ensure the original phone is not still connecting to the watch before testing a second phone. On a single-phone trial, force-stop the debug app first; two independent apps must not compete for the watch BLE connection. A second phone is safer. The two app icons may look identical; verify the application ID in Android Settings when launching.

In PowerShell, verify that exactly one intended USB device is selected:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
```

Use `-s <device-serial>` with each `adb` command if more than one device is listed. **Do not use `adb uninstall`, `pm clear`, or any NVS erase command.**

## 3. Fresh-install RC1

From the repository root:

```powershell
$rc = '.\WDGHUD-Android\app\build\outputs\rc-acceptance'
& $adb shell pm list packages io.github.zomgwaffles.wdghud
& $adb install (Join-Path $rc 'WDGHUD-RC1-10000-TEST-ONLY.apk')
& $adb shell dumpsys package io.github.zomgwaffles.wdghud | Select-String 'versionCode=|versionName='
```

The package should **not** already be installed before the first RC1 test. If it is, stop: this is not a clean-install test. Confirm the public app launches (launch through the icon rather than guessing the runtime component), requests appropriate permissions, accepts an independently verified WDGWars account, pairs with the watch using the displayed six-digit PIN, and shows OPERATOR/GANG/3×3/5×5 data. A fresh release app has its **own** empty storage; it will not inherit the debug app's API key/AP history.

Record **non-sensitive** acceptance observations: app ID and code `10000`, successful login/pairing, visible saved settings, and a recognizable valid cached sector. Close the app/reopen to confirm persistence.

## 4. Same-key upgrade to RC2

**Before upgrade:** Record a non-secret setting (e.g. configured stale-age minutes) and whether a recognizable cache tile is populated; confirm the app has an authenticated account. Stop the driving session or disconnect BLE during APK update.

```powershell
& $adb install -r (Join-Path $rc 'WDGHUD-RC2-10001-TEST-ONLY.apk')
& $adb shell dumpsys package io.github.zomgwaffles.wdghud | Select-String 'versionCode=|versionName='
```

Expect `versionCode=10001` and `versionName=1.0.0-rc2`; the existing release-app storage should remain. Reopen the release app; verify that it retains its own WDGWars credentials, saved setting, and historical AP cache and can reconnect to the watch. `adb install -r` requests reinstall while retaining application data, but check the behavior on hardware.

**Safety:** Do not install a `10000` APK again after RC2. Do not uninstall the debug app. Do not clear watch NVS. Do not share the generated APKs as a public release.

## 5. Evidence to report

Report PASS/FAIL for: signed RC1/RC2 builds, fresh install, version codes, same-certificate upgrade, setting/API-key retention, AP history retention, watch BLE/pairing, and coexistence with the development app. Note the Android version/model and sanitized error lines only.

**Still outside this test:** real A → B → A account switching, network outage/return-trip trials, additional devices, resolved dependency license audit, historical secret scan, reproducible release build/CI and v1.0 release approval.

## References

- Android app update identity and signing: https://developer.android.com/google/play/app-updates
- Android Debug Bridge `install -r`: https://developer.android.com/tools/adb
